package com.school.nav.core.data

import com.school.nav.core.model.Building
import com.school.nav.core.model.CampusData
import com.school.nav.core.model.LngLat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 地图编辑器导出的一份配置。
 *
 * 与 [CampusData] 的区别：
 *  - [CampusData] 是编译期打包在 assets 里的**完整**数据（楼栋 + 楼层 + 元素）；
 *  - [EditorConfig] 是编辑器在**运行期**产出的数据。当前版本只支持画**楼栋外轮廓**，
 *    所以里面只有楼栋的多边形，没有楼层和元素。
 *
 * 两者最终会被 [CampusRepository] 合并：assets 打底，配置文件覆盖同名楼栋。
 * 这样内置的示例数据不会被编辑器写坏，而用户画的楼栋能立即生效、不需要重新打包。
 */
@Serializable
data class EditorConfig(
    /** 配置格式版本，便于以后改结构时做迁移。 */
    @SerialName("schema_version")
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,

    /** 导出时间（毫秒时间戳），仅用于排查「这份文件是什么时候导出的」。 */
    @SerialName("exported_at")
    val exportedAtMillis: Long = 0L,

    /** 本次导出的楼栋（只有外轮廓）。 */
    val buildings: List<BuildingOutline> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * 编辑器画出来的楼栋轮廓。
 *
 * 只描述「这个楼叫什么、边界在哪」，楼层与元素留给后续版本（或手工补进 assets）。
 * [id] 与 assets 里的楼栋 id 相同则视为同一栋楼，合并时以配置文件为准。
 */
@Serializable
data class BuildingOutline(
    val id: String,
    val name: String,
    /** 多边形顶点，经纬度，GCJ-02。至少 3 个点才有意义。 */
    val polygon: List<LngLat>,
) {
    /** 是否构成有效多边形（至少三个顶点且不退化）。 */
    val isValid: Boolean
        get() = polygon.size >= 3 && polygon.distinct().size >= 3

    /**
     * 转换成 core 的 [Building]。
     *
     * [floors] 由调用方提供：编辑阶段只画轮廓，还没有楼层数据；
     * 合并进现有数据时应当沿用原有楼栋的楼层，而不是给一个空列表
     * （空楼层会让导航直接失效）。
     */
    fun toBuilding(floors: List<com.school.nav.core.model.Floor> = emptyList()): Building = Building(
        id = id,
        name = name,
        polygon = polygon,
        floors = floors,
    )
}

/** 编辑器配置的读写。 */
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
     * 容错策略：解析失败或结构不认识时返回 null，由调用方决定怎么处理
     * （App 里是「忽略这份文件并记一条日志」，绝不因为一个坏文件就崩）。
     */
    fun decode(text: String): EditorConfig? =
        runCatching { PrettyJson.decodeFromString(EditorConfig.serializer(), text) }.getOrNull()

    /** 默认文件名。 */
    const val FILE_NAME = "editor_buildings.json"

    /** 配置文件夹名（放在应用私有目录下）。 */
    const val DIR_NAME = "config"
}
