package com.school.nav.core.data

import com.school.nav.core.model.Building
import com.school.nav.core.model.CampusData
import com.school.nav.core.model.Element
import com.school.nav.core.model.ElementType
import com.school.nav.core.model.Floor
import com.school.nav.core.model.LngLat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 元素在编辑器里的画法。
 *
 * 用户的诉求是「别让人靠点三个点来划定位置」—— 逐点点选又慢又难对齐，
 * PS 那种拖拽才顺手：
 *
 *  - [Rectangle]：**按住拖出一个矩形**（教学楼外轮廓 / 教室 / 办公室）。
 *    矩形是最常见的房间形状，拖一下就有四条边，不需要逐点点选。
 *  - [Point]：**点一下就是一个点**（楼梯口 / 卫生间）。
 *    这两类东西本来就没有明确边界，画矩形纯属白费功夫，
 *    而导航只需要知道它「在哪」。楼梯还额外带一个「结束楼层」表示跨几层。
 *
 * 存进数据时**统一还是点串**（[EditorElement.points]）：矩形是四个角、点是一个点。
 * 这样导航算法（质心、左右判定、走廊排序）完全不需要为形态分叉。
 */
enum class ElementShape {
    Rectangle,
    Point,
}

/**
 * 编辑器绘制模式。
 *
 * 每一种对应 [ElementType] 的一个取值，界面右上角的下拉菜单就是在这几种之间切换。
 * 已随数据类型一起删除「电梯口」「入口」两个模式。
 */
enum class EditorMode(
    val label: String,
    /** 对应的元素类型；教学楼不是一个「元素」，所以为 null。 */
    val elementType: ElementType?,
    /** 是否需要先选定一栋楼（楼层内元素都属于某栋楼）。 */
    val needsBuilding: Boolean = true,
    /** 画法：拖拽矩形还是单点。 */
    val shape: ElementShape = ElementShape.Rectangle,
) {
    Building("教学楼", elementType = null, needsBuilding = false, shape = ElementShape.Rectangle),
    Room("教室", ElementType.Room),
    Office("办公室", ElementType.Office),
    Stair("楼梯口", ElementType.Stair, shape = ElementShape.Point),
    Toilet("卫生间", ElementType.Toilet, shape = ElementShape.Point),
    ;

    /** 是否是单点元素（点一下即可，不需要拖）。 */
    val isPoint: Boolean get() = shape == ElementShape.Point

    companion object {
        /** 下拉菜单里的顺序，最常用的放前面。 */
        val menuOrder: List<EditorMode> = listOf(Building, Room, Office, Stair, Toilet)
    }
}

/** 一层楼。编辑阶段只需要「几楼」和「该层画了哪些元素」。 */
@Serializable
data class FloorDraft(
    val level: Int,
    val elements: List<EditorElement> = emptyList(),
)

/** 编辑器画出来的一个楼层内元素（教室 / 楼梯口 / 卫生间 …）。 */
@Serializable
data class EditorElement(
    val id: String,
    /** 类型原始值，与 assets 里的 `type` 字段同一套取值。 */
    val type: String,
    val name: String,
    val points: List<LngLat>,
    /**
     * 结束楼层，仅对**跨层元素**（楼梯口）有意义；等于 [level] 时表示只占一层。
     *
     * 楼梯是在楼层之间上下走的，物理上会穿过楼板，所以它不属于单独某一层。
     * 编辑器让用户选「起始层 + 结束层」，合并进校园数据时会在每一层都放一份
     * 同名楼梯，这样人在任意一层都能找到它 —— 这也符合导航文案的用法
     * （「先走到东楼梯口，上到 3 楼」）。
     */
    @SerialName("to_level")
    val toLevel: Int? = null,
) {
    val elementType: ElementType get() = ElementType.fromRaw(type)

    /** 是否是单点元素（楼梯口 / 卫生间）。这些类型本来就没有边界。 */
    val isPoint: Boolean get() = points.size == 1

    /**
     * 数据是否可用。
     *
     * 单点元素只需要一个点；矩形/多边形至少三个不重合的点，否则围不成面。
     */
    val isValid: Boolean
        get() = if (isPoint) {
            true
        } else {
            points.size >= MIN_POLYGON_POINTS && points.distinct().size >= MIN_POLYGON_POINTS
        }

    /** 是否跨越多个楼层。层号记在 [FloorDraft.level] 上，这里只能看有没有 [toLevel]。 */
    val isCrossFloor: Boolean get() = toLevel != null

    /**
     * 转成 core 的 [Element]。
     *
     * @param forLevel 生成到哪一层。元素本身不带层号（层号在 [FloorDraft] 上），
     *                 所以必须由调用方传入；跨层元素会被展开成多份，每层一份，
     *                 id 带上层号才不会冲突。
     */
    fun toElement(forLevel: Int): Element = Element(
        id = "${id}-L$forLevel",
        type = type,
        name = name,
        points = points,
    )

    companion object {
        const val MIN_POLYGON_POINTS = 3

        /** 按绘制模式构造一个元素。教学楼模式没有元素类型，兜底为教室。 */
        fun from(
            mode: EditorMode,
            id: String,
            name: String,
            points: List<LngLat>,
            toLevel: Int? = null,
        ): EditorElement =
            EditorElement(
                id = id,
                type = (mode.elementType ?: ElementType.Room).raw,
                name = name,
                points = points,
                toLevel = toLevel,
            )
    }
}

/** 编辑器画出来的一栋楼：外轮廓 + 楼层数 + 各层元素。 */
@Serializable
data class EditorBuilding(
    val id: String,
    val name: String,
    /** 楼栋外轮廓（多边形）。可以为空 —— 用户可能只想给已有楼栋补画楼层。 */
    val polygon: List<LngLat> = emptyList(),
    /**
     * 楼栋一共有几层。
     *
     * 用途：给楼层选择器一个上限（不然用户要手动输入层号），
     * 以及在没有画任何元素时也能知道这栋楼的名义层数。
     * 与 [floors] 的区别：floors 是「实际画过元素的层」，一层都没画时 floors 为空，
     * 而 floorCount 是数据本身的一部分。
     */
    @SerialName("floor_count")
    val floorCount: Int = DEFAULT_FLOOR_COUNT,
    val floors: List<FloorDraft> = emptyList(),
) {
    /** 轮廓是否构成有效多边形。 */
    val hasValidPolygon: Boolean
        get() = EditorBuilding.isValidPolygon(polygon)

    fun floor(level: Int): FloorDraft? = floors.firstOrNull { it.level == level }

    /**
     * 往某一层追加一个元素。
     *
     * 该层还没有 FloorDraft 时会新建一个。楼层按 level 排序，保证配置文件的
     * 字段顺序稳定（便于 diff 与人工查看）。
     */
    fun withAddedElement(level: Int, element: EditorElement): EditorBuilding {
        val existing = floor(level)
        val newFloors = if (existing == null) {
            floors + FloorDraft(level = level, elements = listOf(element))
        } else {
            floors.map {
                if (it.level == level) it.copy(elements = it.elements + element) else it
            }
        }
        return copy(floors = newFloors.sortedBy { it.level })
    }

    /** 该楼一共画了多少个元素（跨层统计，楼梯只算一次）。 */
    val elementCount: Int get() = floors.sumOf { it.elements.size }

    /** 楼层选择器的可选层号：1..floorCount，至少给到已画过的最高层。 */
    val levelRange: IntRange
        get() {
            val highest = maxOf(floorCount, floors.maxOfOrNull { it.level } ?: 1)
            return 1..highest.coerceAtLeast(1)
        }

    /**
     * 该楼需要用到的所有楼层（含跨层元素展开出来的层）。
     *
     * 例：只在 3 楼画了一个「3→5 楼」的楼梯，就需要生成 3、4、5 三层。
     */
    val requiredLevels: Set<Int>
        get() = floors.flatMap { floor ->
            floor.elements.flatMap { element ->
                val end = element.toLevel ?: floor.level
                val from = minOf(floor.level, end)
                val to = maxOf(floor.level, end)
                (from..to).toList()
            }
        }.toSet()

    /**
     * 转成 core 的 [Building]。
     *
     * 跨层元素（楼梯）会在它覆盖的**每一层**都放一份同名元素 ——
     * 楼梯是上下楼用的，人在任意一层都要能找到它，导航文案也是这么用的。
     * 没有任何元素的楼层会被丢掉（空楼层在导航里没有意义）。
     */
    fun toBuilding(): Building {
        val byLevel = mutableMapOf<Int, MutableList<EditorElement>>()

        floors.forEach { floor ->
            floor.elements.filter { it.isValid }.forEach { element ->
                val end = element.toLevel ?: floor.level
                val from = minOf(floor.level, end)
                val to = maxOf(floor.level, end)
                for (level in from..to) {
                    byLevel.getOrPut(level) { mutableListOf() } += element
                }
            }
        }

        return Building(
            id = id,
            name = name,
            polygon = polygon,
            floors = byLevel
                .map { (level, elements) ->
                    Floor(
                        id = "${id}_${level}F",
                        level = level,
                        relativeHeightM = level * ASSUMED_FLOOR_HEIGHT_M,
                        elements = elements.map { it.toElement(forLevel = level) },
                    )
                }
                .sortedBy { it.level },
        )
    }

    companion object {
        const val MIN_POLYGON_POINTS = 3

        /** 新建楼栋时的默认层数。 */
        const val DEFAULT_FLOOR_COUNT = 5

        /** 几个点够围成一个面（不与任何点重合、且至少三个）。 */
        fun isValidPolygon(points: List<LngLat>): Boolean =
            points.size >= MIN_POLYGON_POINTS && points.distinct().size >= MIN_POLYGON_POINTS

        /**
         * 由两个对角点生成矩形的四个角（西南 → 东南 → 东北 → 西北）。
         *
         * 拖拽绘制用：用户从一角拖到另一角就得到这个矩形。
         * 点串顺序与 `tools/gen-buildings-json.mjs` 里 `rect()` 的产出一致，
         * 这样资产数据与编辑器数据在算法眼里是同样的形状。
         */
        fun rectangleFromCorners(a: LngLat, b: LngLat): List<LngLat> {
            val westLng = minOf(a.lng, b.lng)
            val eastLng = maxOf(a.lng, b.lng)
            val southLat = minOf(a.lat, b.lat)
            val northLat = maxOf(a.lat, b.lat)
            return listOf(
                LngLat(westLng, southLat),
                LngLat(eastLng, southLat),
                LngLat(eastLng, northLat),
                LngLat(westLng, northLat),
            )
        }

        /**
         * 编辑器新增楼层时假定的层高（米）。
         *
         * 是估算值：编辑器只画平面位置、不测高度。留这个常量是为了让
         * `relative_height_m` 保持递增，否则楼层自检会报错、气压计判层也会错。
         * 要精确值请改 assets，或等后续版本支持录入层高。
         */
        const val ASSUMED_FLOOR_HEIGHT_M = 4.0
    }
}

/**
 * 地图编辑器导出的一份配置。
 *
 * 与 [CampusData] 的区别：
 *  - [CampusData] 是编译期打包在 assets 里的**完整**数据（楼栋 + 楼层 + 元素）；
 *  - [EditorConfig] 是编辑器在**运行期**产出的数据，最终由 [CampusRepository.create]
 *    合并进去：assets 打底，配置覆盖同名楼栋的外轮廓、以及**画过的那些层**。
 *
 * 这样编辑器的改动不需要重新打包就能生效，而内置示例数据也不会被写坏。
 */
@Serializable
data class EditorConfig(
    /** 配置格式版本，便于以后改结构时做迁移。 */
    @SerialName("schema_version")
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,

    /** 导出时间（毫秒时间戳），仅用于排查「这份文件是什么时候导出的」。 */
    @SerialName("exported_at")
    val exportedAtMillis: Long = 0L,

    /** 本次绘制的楼栋。 */
    val buildings: List<EditorBuilding> = emptyList(),
) {
    companion object {
        /**
         * 当前格式版本。
         *
         * v1：只有楼栋外轮廓。
         * v2：楼栋可带楼层与元素。
         * 旧文件仍能解析 —— floors 缺省为空，等价于 v1，因此不需要迁移代码。
         */
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

/** 编辑器配置的读写。 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object EditorConfigCodec {

    /** 保存时用的 JSON：带缩进，方便用户直接看 / 手改导出的文件。 */
    val PrettyJson: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(config: EditorConfig): String =
        PrettyJson.encodeToString(EditorConfig.serializer(), config)

    /**
     * 解析配置。
     *
     * 容错策略：解析失败返回 null，由调用方决定怎么处理
     * （App 里是「忽略这份文件并记一条日志」，绝不因为一个坏文件就崩）。
     */
    fun decode(text: String): EditorConfig? =
        runCatching { PrettyJson.decodeFromString(EditorConfig.serializer(), text) }.getOrNull()

    /** 默认文件名。 */
    const val FILE_NAME = "editor_buildings.json"

    /** 配置文件夹名（放在应用私有目录下）。 */
    const val DIR_NAME = "config"
}
