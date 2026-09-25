package com.school.nav.core.data

import com.school.nav.core.model.Building
import com.school.nav.core.model.CampusData
import com.school.nav.core.model.Element
import com.school.nav.core.model.ElementType
import com.school.nav.core.model.Floor
import com.school.nav.core.model.Geo
import com.school.nav.core.model.LngLat
import com.school.nav.core.model.Position
import com.school.nav.core.model.Target
import kotlinx.serialization.json.Json

/**
 * 搜索命中的一条结果。
 *
 * 带上 [score] 是为了排序；[reason] 便于调试“为什么搜到了它”。
 */
data class SearchHit(
    val building: Building,
    val floor: Floor,
    val element: Element,
    val score: Int,
) {
    fun toTarget(): Target = Target(building = building, floor = floor, element = element)
}

/**
 * 楼栋数据仓库。
 *
 * 数据来源有两层：
 *  1. **assets 里的静态 JSON**（零后端、编译期打包）—— 内置的完整数据，有楼层有元素；
 *  2. **地图编辑器导出的配置文件**（运行期写入应用私有目录）—— 目前只有楼栋外轮廓。
 *
 * 两层由 [Companion.create] 合并：assets 打底，配置覆盖同名楼栋的几何。
 * 本类只依赖 JSON 字符串与已解析的数据，由 app 模块负责文件 IO，
 * 因此可以脱离 Android 做单元测试。
 */
class CampusRepository private constructor(
    val data: CampusData,
    val jsonParser: Json,
    /** 合并过程中发现的问题（例如新加的楼栋还没有楼层），供 UI 与日志展示。 */
    val mergeWarnings: List<String> = emptyList(),
) {

    /** 兼容旧用法：直接给一段 JSON。 */
    constructor(
        json: String,
        jsonParser: Json = DefaultJson,
    ) : this(
        data = jsonParser.decodeFromString(CampusData.serializer(), json),
        jsonParser = jsonParser,
    )

    val buildings: List<Building> get() = data.buildings

    val buildingNames: List<String> get() = buildings.map { it.name }

    fun buildingById(id: String): Building? = data.buildingById(id)

    /**
     * 用 GPS 原始点判断用户在哪栋楼。
     *
     * 支持点落在多个多边形重叠的情况：取“离楼栋质心最近”的那一栋，保证结果稳定。
     */
    fun buildingAt(point: LngLat): Building? {
        val inside = buildings.filter { Geo.containsPoint(it.polygon, point) }
        if (inside.isEmpty()) return null
        if (inside.size == 1) return inside.first()
        return inside.minByOrNull { Geo.distanceMeters(point, Geo.centroid(it.polygon)) }
    }

    /** 找离给定点最近的楼栋（即使不在任何多边形内），用于室内 GPS 漂移兜底。 */
    fun nearestBuilding(point: LngLat): Building? =
        buildings.minByOrNull { Geo.distanceMeters(point, Geo.centroid(it.polygon)) }

    /**
     * 在当前楼层里找离用户最近的元素，直接作为默认当前位置。
     *
     * 产品文档明确要求“不弹窗、不推荐、不打断”，所以这里只做一件事：返回最近的元素。
     * 楼梯口、卫生间这类不是“人可能站的地方”的元素排除在外，
     * 避免默认位置显示成“东楼梯口”。
     */
    fun nearestStandableElement(floor: Floor, point: LngLat): Element? {
        val usable = floor.elements.filter { it.elementType in STANDABLE_TYPES }
        val pool = usable.ifEmpty { floor.elements }
        return pool.minByOrNull { Geo.distanceMeters(point, it.centroid) }
    }

    /**
     * 中文名搜索。
     *
     * 匹配策略（从强到弱）：
     *  1. 完全相等              —— “物理实验室”
     *  2. 目标名以输入开头/结尾  —— “物理”
     *  3. 目标名包含输入        —— “实验室”
     *  4. 输入包含目标名        —— “物理实验室在哪”
     *
     * 同分时优先“离用户更近”（同楼栋 > 同楼层 > 更近），这样搜“办公室”时会先给本楼的。
     */
    fun search(
        keyword: String,
        current: Position? = null,
        limit: Int = 8,
    ): List<SearchHit> {
        val key = keyword.trim()
        if (key.isEmpty()) return emptyList()

        val hits = mutableListOf<SearchHit>()
        for (building in buildings) {
            for (floor in building.floors) {
                for (element in floor.elements) {
                    // 卫生间不作为导航目标：没人需要「导航到卫生间」，
                    // 而且它在数据里大量重复（每层都有），会淹没真正的目标
                    if (element.elementType == ElementType.Toilet) continue
                    val base = scoreName(element.name, key) ?: continue

                    var score = base
                    if (current != null) {
                        if (building.id == current.building.id) score += 4
                        if (floor.id == current.floor.id) score += 3
                        val distance = Geo.distanceMeters(current.element.centroid, element.centroid)
                        // 越近加越多，最多 3 分
                        score += (3 - (distance / 100.0).toInt()).coerceIn(0, 3)
                    }
                    hits += SearchHit(building, floor, element, score)
                }
            }
        }

        return hits
            .sortedWith(
                compareByDescending<SearchHit> { it.score }
                    .thenBy { it.building.name }
                    .thenBy { it.floor.level }
                    .thenBy { it.element.name },
            )
            .take(limit)
    }

    /** 精确按名字找，搜不到返回 null。 */
    fun findByName(name: String, current: Position? = null): Target? =
        search(name, current, limit = 1).firstOrNull()?.toTarget()

    /** 快捷目标：从数据里挑几个有代表性的目标，避免硬编码到 UI 里。 */
    fun quickTargets(limit: Int = 6): List<SearchHit> {
        val wanted = QUICK_TARGET_NAMES
        val result = mutableListOf<SearchHit>()
        for (name in wanted) {
            val hit = search(name, limit = 1).firstOrNull() ?: continue
            result += hit
            if (result.size >= limit) break
        }
        return result
    }

    /** 数据自检：楼层高度是否递增、多边形的点是否足够。返回问题列表。 */
    fun validate(): List<String> {        val problems = mutableListOf<String>()
        if (buildings.isEmpty()) problems += "没有任何楼栋数据"

        for (building in buildings) {
            if (building.polygon.size < 3) {
                problems += "楼栋 ${building.name} 的多边形少于 3 个点"
            }
            if (building.floors.isEmpty()) {
                problems += "楼栋 ${building.name} 没有楼层"
            }

            val ids = mutableSetOf<String>()
            for (floor in building.floors) {
                if (!ids.add(floor.id)) problems += "楼栋 ${building.name} 存在重复楼层 id：${floor.id}"
                if (floor.elements.isEmpty()) {
                    problems += "${building.name} ${floor.level} 楼没有元素"
                }
                val elementIds = mutableSetOf<String>()
                for (element in floor.elements) {
                    if (!elementIds.add(element.id)) {
                        problems += "${building.name} ${floor.level} 楼存在重复元素 id：${element.id}"
                    }
                    if (element.name.isBlank()) {
                        problems += "${building.name} ${floor.level} 楼有元素缺少中文名"
                    }
                    if (element.points.isEmpty()) {
                        problems += "${building.name} ${floor.level} 楼 ${element.name} 没有几何点"
                    }
                }
            }

            val sorted = building.orderedFloors
            for (i in 1 until sorted.size) {
                if (sorted[i].relativeHeightM <= sorted[i - 1].relativeHeightM) {
                    problems += "${building.name} 楼层相对高度不是递增的：" +
                        "${sorted[i - 1].level}楼(${sorted[i - 1].relativeHeightM}) -> " +
                        "${sorted[i].level}楼(${sorted[i].relativeHeightM})"
                }
            }
            if (sorted.isNotEmpty() && sorted.first().relativeHeightM != 0.0) {
                problems += "${building.name} 的首层相对高度应为 0，实际为 ${sorted.first().relativeHeightM}"
            }
        }
        return problems
    }

    private fun scoreName(name: String, key: String): Int? = when {
        name == key -> 100
        name.startsWith(key) -> 80
        name.endsWith(key) -> 70
        name.contains(key) -> 60
        key.contains(name) -> 50
        else -> null
    }

    companion object {
        /** 默认 JSON 解析配置：忽略未知字段，方便后续数据加字段而不改代码。 */
        val DefaultJson: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * 把编辑器产出的楼栋合并进一份校园数据。
         *
         * 规则（顺序很重要）：
         *  1. **assets 打底**：内置数据始终是完整的（有楼层、有元素）；
         *  2. **配置覆盖同名楼栋**（id 相同视为同一栋楼）：
         *     - 外轮廓：配置里有合法多边形才覆盖，否则保留原轮廓
         *       （用户可能只想补画楼层，没重画轮廓）；
         *     - 楼层与元素：配置里画了才覆盖**该层**，没画的层保留 assets 的；
         *  3. **新增配置里有、assets 里没有的楼栋**：直接加入。
         *
         * 为什么楼层要「按层合并」而不是整栋替换：
         * 用户通常只在编辑器里画一两层，若整栋替换，其余楼层会被清空 ——
         * 表现是「楼栋定位还在、那几层导航全废」，而且不会崩，极难发现。
         *
         * 不做原地修改：数据可能被多处以 `StateFlow` 持有，产出新对象更安全。
         */
        fun mergeEditorBuildings(base: CampusData, outlines: List<EditorBuilding>): CampusData {
            val incoming = outlines.associateBy { it.id }

            val merged = base.buildings.map { existing ->
                val draft = incoming[existing.id] ?: return@map existing
                mergeOne(existing, draft)
            }

            val added = outlines
                .filter { draft -> base.buildings.none { b -> b.id == draft.id } }
                .map { it.toBuilding() }

            return base.copy(buildings = merged + added)
        }

        /** 把一份编辑器草稿合并进一栋已有楼栋。 */
        private fun mergeOne(existing: Building, draft: EditorBuilding): Building {
            val newPolygon = if (draft.hasValidPolygon) draft.polygon else existing.polygon
            if (draft.floors.isEmpty()) {
                // 只改了轮廓 / 或者什么都没画
                return existing.copy(
                    name = draft.name.ifBlank { existing.name },
                    polygon = newPolygon,
                )
            }

            // 按层合并：配置里画过的层替换，没画过的层原样保留。
            // 注意要用 toBuilding() 的结果：跨层元素（楼梯）会在它覆盖的
            // 每一层都展开出一份，直接读 draft.floors 会漏掉展开出来的层。
            val fromDraft = draft.toBuilding()
            val draftByLevel = fromDraft.floors.associateBy { it.level }
            val mergedFloors = existing.floors.map { floor ->
                draftByLevel[floor.level] ?: floor
            }

            // 展开后出现、但 assets 里没有的楼层（例如用户新建了一层，
            // 或楼梯跨到了原数据没有的层）也加进来
            val existingLevels = existing.floors.map { it.level }.toSet()
            val extraFloors = fromDraft.floors.filter { it.level !in existingLevels }

            return existing.copy(
                name = draft.name.ifBlank { existing.name },
                polygon = newPolygon,
                floors = (mergedFloors + extraFloors).sortedBy { it.level },
            )
        }

        /** 合并的产物：仓库本身 + 编辑器配置 + 合并告警。 */
        data class Merged(
            val repository: CampusRepository,
            val editorConfig: EditorConfig,
            val warnings: List<String>,
        )

        /**
         * 按「assets 打底 + 编辑器配置覆盖」构建仓库。
         *
         * @param assetJson        assets 里的 buildings.json
         * @param editorConfigJson 编辑器导出的配置；为空或损坏时只用 assets
         */
        fun create(
            assetJson: String,
            editorConfigJson: String?,
            jsonParser: Json = DefaultJson,
        ): Merged {
            val base = jsonParser.decodeFromString(CampusData.serializer(), assetJson)
            val editorConfig = editorConfigJson
                ?.let { EditorConfigCodec.decode(it) }
                ?: EditorConfig()

            val warnings = mutableListOf<String>()

            // 只保留「能用的」草稿：轮廓必须合法；若完全没有楼层元素，
            // 至少要有合法轮廓，否则这条草稿什么也贡献不了，直接丢掉并说明。
            val usable = editorConfig.buildings.filter { it.hasValidPolygon || it.elementCount > 0 }
            val dropped = editorConfig.buildings.size - usable.size
            if (dropped > 0) {
                warnings += "编辑器配置里有 $dropped 栋既没有合法外轮廓、也没有任何元素，已忽略。"
            }

            val merged = mergeEditorBuildings(base, usable)

            // 新加入的楼栋若还没有任何楼层元素，楼栋定位能用但导航用不了，必须提示
            val incomplete = merged.buildings.filter { b ->
                usable.any { it.id == b.id } && base.buildings.none { it.id == b.id } &&
                    b.floors.none { it.elements.isNotEmpty() }
            }
            if (incomplete.isNotEmpty()) {
                warnings += incomplete.joinToString("、") { it.name } +
                    " 只有外轮廓、还没有楼层元素，暂时只能用于楼栋定位、不能室内导航。"
            }

            return Merged(
                repository = CampusRepository(
                    data = merged,
                    jsonParser = jsonParser,
                    mergeWarnings = warnings,
                ),
                editorConfig = editorConfig.copy(buildings = usable),
                warnings = warnings,
            )
        }

        /** “默认当前位置”允许出现的元素类型：人真正可能站的地方。 */
        private val STANDABLE_TYPES = setOf(
            ElementType.Room,
            ElementType.Office,
        )

        /** 快捷目标候选，按顺序取数据里存在的项。 */
        private val QUICK_TARGET_NAMES = listOf(
            "物理实验室",
            "教务处",
            "图书馆",
            "报告厅",
            "高一(6)班",
            "自习室",
        )
    }
}
