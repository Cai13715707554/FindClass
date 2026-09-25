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
 * 编辑器绘制模式。
 *
 * 每一种对应 [ElementType] 的一个取值，界面右上角的下拉菜单就是在这几种之间切换。
 *
 * 之所以要区分「教学楼」和「教室」：画楼栋外轮廓和画楼层内的房间用的是同一套交互
 * （点选顶点围多边形），但产出物完全不同 ——
 * 前者进 [EditorBuilding.polygon]，后者进某栋楼某层的 [EditorElement]。
 */
enum class EditorMode(
    val label: String,
    /** 对应的元素类型；教学楼不是一个「元素」，所以为 null。 */
    val elementType: ElementType?,
    /** 是否需要先选定一栋楼（楼层内元素都属于某栋楼）。 */
    val needsBuilding: Boolean = true,
) {
    Building("教学楼", elementType = null, needsBuilding = false),
    Room("教室", ElementType.Room),
    Office("办公室", ElementType.Office),
    Stair("楼梯口", ElementType.Stair),
    Elevator("电梯口", ElementType.Elevator),
    Entrance("入口", ElementType.Entrance),
    Toilet("卫生间", ElementType.Toilet),
    ;

    companion object {
        /** 下拉菜单里的顺序，最常用的放前面。 */
        val menuOrder: List<EditorMode> = listOf(
            Building, Room, Office, Stair, Elevator, Entrance, Toilet,
        )
    }
}

/** 一层楼。编辑阶段只需要「几楼」和「该层画了哪些元素」。 */
@Serializable
data class FloorDraft(
    val level: Int,
    val elements: List<EditorElement> = emptyList(),
)

/** 编辑器画出来的一个楼层内元素（教室 / 楼梯口 / 电梯口 …）。 */
@Serializable
data class EditorElement(
    val id: String,
    /** 类型原始值，与 assets 里的 `type` 字段同一套取值。 */
    val type: String,
    val name: String,
    val points: List<LngLat>,
) {
    val elementType: ElementType get() = ElementType.fromRaw(type)

    /** 少于三个点、或点全重合时围不成面，存进去也没意义。 */
    val isValid: Boolean
        get() = points.size >= MIN_POLYGON_POINTS && points.distinct().size >= MIN_POLYGON_POINTS

    /** 转成 core 的 [Element]，供合并进校园数据。 */
    fun toElement(): Element = Element(
        id = id,
        type = type,
        name = name,
        points = points,
    )

    companion object {
        const val MIN_POLYGON_POINTS = 3

        /** 按绘制模式构造一个元素。教学楼模式没有元素类型，兜底为教室。 */
        fun from(mode: EditorMode, id: String, name: String, points: List<LngLat>): EditorElement =
            EditorElement(
                id = id,
                type = (mode.elementType ?: ElementType.Room).raw,
                name = name,
                points = points,
            )
    }
}

/** 编辑器画出来的一栋楼：外轮廓 + 各层元素。 */
@Serializable
data class EditorBuilding(
    val id: String,
    val name: String,
    /** 楼栋外轮廓（多边形）。可以为空 —— 用户可能只想给已有楼栋补画楼层。 */
    val polygon: List<LngLat> = emptyList(),
    val floors: List<FloorDraft> = emptyList(),
) {
    /** 轮廓是否构成有效多边形。 */
    val hasValidPolygon: Boolean
        get() = polygon.size >= MIN_POLYGON_POINTS && polygon.distinct().size >= MIN_POLYGON_POINTS

    fun floor(level: Int): FloorDraft? = floors.firstOrNull { it.level == level }

    /** 该楼一共画了多少个元素（跨层统计）。 */
    val elementCount: Int get() = floors.sumOf { it.elements.size }

    /**
     * 转成 core 的 [Building]。
     *
     * 没有任何有效元素的楼层会被丢掉 —— 空楼层在导航里没有意义，
     * 还会让数据自检报「某层没有元素」。
     */
    fun toBuilding(): Building = Building(
        id = id,
        name = name,
        polygon = polygon,
        floors = floors
            .mapNotNull { draft ->
                val elements = draft.elements.filter { it.isValid }.map { it.toElement() }
                if (elements.isEmpty()) {
                    null
                } else {
                    Floor(
                        id = "${id}_${draft.level}F",
                        level = draft.level,
                        relativeHeightM = draft.level * ASSUMED_FLOOR_HEIGHT_M,
                        elements = elements,
                    )
                }
            }
            .sortedBy { it.level },
    )

    companion object {
        const val MIN_POLYGON_POINTS = 3

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
