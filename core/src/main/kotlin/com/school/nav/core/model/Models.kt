package com.school.nav.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 经纬度点。
 *
 * 全工程坐标系统一使用 GCJ-02（火星坐标系，国内地图通用）。
 * 如果数据源是 WGS84，必须在入库（assets/buildings.json）之前完成转换，运行时不再做转换。
 */
@Serializable
data class LngLat(
    val lng: Double,
    val lat: Double,
)

/**
 * 楼层内的元素类型。
 *
 * 说明：JSON 里 `type` 是字符串，解析时用 [ElementType.fromRaw] 做容错，
 * 遇到未知类型降级为 [ElementType.Room]，避免一份脏数据导致整栋楼不可用。
 *
 * **已移除 `elevator`（电梯口）与 `entrance`（入口）**：
 * 电梯口在 MVP 里没有数据来源、也没被导航算法真正用到；
 * 入口既不能作为导航目标、作为中途转向点也没有路径数据支撑。
 * 保留它们只会让编辑器多两个画了没用的选项，所以连同数据一起删掉。
 * 旧数据里若还有这两种类型，会被 [fromRaw] 降级成教室，不会崩。
 */
enum class ElementType(val raw: String, val displayName: String) {
    Room("room", "教室"),
    Office("office", "办公室"),
    Stair("stair", "楼梯口"),
    Toilet("toilet", "卫生间"),
    ;

    companion object {
        fun fromRaw(raw: String): ElementType =
            entries.firstOrNull { it.raw.equals(raw, ignoreCase = true) } ?: Room
    }
}

/** 楼层内的一个元素：教室、办公室、楼梯口、卫生间。 */
@Serializable
data class Element(
    val id: String,
    val type: String,
    /** 中文名，导航文案里直接使用，例如“物理实验室”“东楼梯口”。 */
    val name: String,
    /** 几何形状点串。MVP 里统一用点串表示，不做平面图渲染。 */
    val points: List<LngLat>,
) {
    /** 解析后的元素类型。 */
    val elementType: ElementType get() = ElementType.fromRaw(type)

    /** 质心（点串的算术平均）。用于排序、测距与左右方位判断。 */
    val centroid: LngLat get() = Geo.centroid(points)
}

/** 楼层。 */
@Serializable
data class Floor(
    val id: String,
    /** 楼层号，用于文案“上到 3 楼”，也是展示单位的来源。 */
    val level: Int,
    /**
     * 相对地面高度（米）。
     *
     * 基准是楼栋地面层：1 楼为 0，2 楼约 4，3 楼约 8。
     * 与气压计换算出的“相对高度”直接比较即可得到楼层。
     */
    @SerialName("relative_height_m")
    val relativeHeightM: Double,
    val elements: List<Element>,
) {
    /** 楼层展示名，例如“3楼”。 */
    val displayName: String get() = "${level}楼"

    fun elementById(id: String): Element? = elements.firstOrNull { it.id == id }
}

/** 楼栋。 */
@Serializable
data class Building(
    val id: String,
    /** 中文名，例如“A栋”。 */
    val name: String,
    /** 楼栋外轮廓多边形，用于判断用户是否在楼内。 */
    val polygon: List<LngLat>,
    val floors: List<Floor>,
) {
    fun floorById(id: String): Floor? = floors.firstOrNull { it.id == id }

    fun floorByLevel(level: Int): Floor? = floors.firstOrNull { it.level == level }

    /** 楼层按 level 升序，供“上/下楼”选择使用。 */
    val orderedFloors: List<Floor> get() = floors.sortedBy { it.level }
}

/** 整份静态数据。 */
@Serializable
data class CampusData(
    val buildings: List<Building>,
) {
    fun buildingById(id: String): Building? = buildings.firstOrNull { it.id == id }
}

/**
 * 一次定位的结果：我在哪栋楼的哪一层。
 *
 * [floor] 可以为 null —— 气压计缺失或数据不可信时无法判断楼层，
 * 此时由用户在“修改位置”里手动补齐，而不是硬猜一个楼层。
 */
data class LocationFix(
    val building: Building,
    val floor: Floor?,
    /** 触发这次定位的来源，用于 UI 提示文案。 */
    val source: Source,
) {
    enum class Source {
        /** GPS 命中了楼栋多边形。 */
        Gps,

        /** GPS 漂移，沿用上一次锁定的楼栋。 */
        LastLocked,

        /** 用户手动指定。 */
        Manual,
    }
}

/** 当前位置：楼栋 + 楼层 + 元素，三级都确定后的完整状态。 */
data class Position(
    val building: Building,
    val floor: Floor,
    val element: Element,
) {
    /** 位置卡片文案：A栋 · 3楼 · 语文教研室。 */
    val displayText: String get() = "${building.name} · ${floor.displayName} · ${element.name}"
}

/** 导航目标。 */
data class Target(
    val building: Building,
    val floor: Floor,
    val element: Element,
) {
    val displayText: String get() = "${building.name} · ${floor.displayName} · ${element.name}"
}
