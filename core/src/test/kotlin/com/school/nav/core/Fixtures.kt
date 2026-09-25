package com.school.nav.core

import com.school.nav.core.model.Building
import com.school.nav.core.model.Element
import com.school.nav.core.model.ElementType
import com.school.nav.core.model.Floor
import com.school.nav.core.model.LngLat

/**
 * 测试用的几何与数据构造工具。
 *
 * 用“局部米坐标 + 矩形”构造数据，和 app/src/main/assets/buildings.json 的生成方式一致，
 * 这样测试里的相对关系（东/西/南/北）是明确的，断言才能写成人能读懂的句子。
 */
object Fixtures {

    const val METERS_PER_DEG_LAT = 110_574.0

    fun metersPerDegLng(lat: Double): Double = 111_320.0 * kotlin.math.cos(Math.toRadians(lat))

    /** 测试场地原点：任意固定的 GCJ-02 坐标，只需内部自洽。 */
    val ORIGIN = LngLat(113.1234, 23.1234)

    fun ll(xM: Double, yM: Double): LngLat = LngLat(
        lng = ORIGIN.lng + xM / metersPerDegLng(ORIGIN.lat),
        lat = ORIGIN.lat + yM / METERS_PER_DEG_LAT,
    )

    /** 矩形：左下、右下、右上、左上。 */
    fun rect(x1: Double, y1: Double, x2: Double, y2: Double): List<LngLat> = listOf(
        ll(x1, y1),
        ll(x2, y1),
        ll(x2, y2),
        ll(x1, y2),
    )

    private var seq = 0

    /** 构造一个矩形元素。 */
    fun makeElement(
        name: String,
        type: ElementType,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
    ): Element = Element(
        id = "e${seq++}",
        type = type.raw,
        name = name,
        points = rect(x1, y1, x2, y2),
    )

    /** 北侧教室：y 从 1 到 9。 */
    fun northRoom(name: String, x1: Double, width: Double = 8.0): Element =
        makeElement(name, ElementType.Room, x1, 1.0, x1 + width, 9.0)

    /** 南侧教室：y 从 -9 到 -1。 */
    fun southRoom(name: String, x1: Double, width: Double = 8.0): Element =
        makeElement(name, ElementType.Room, x1, -9.0, x1 + width, -1.0)

    fun stair(name: String, x1: Double, width: Double = 4.0): Element =
        makeElement(name, ElementType.Stair, x1, -9.0, x1 + width, -1.0)

    fun elevator(name: String, x1: Double, width: Double = 4.0): Element =
        makeElement(name, ElementType.Elevator, x1, -9.0, x1 + width, -1.0)

    fun entrance(name: String, x1: Double, width: Double = 4.0): Element =
        makeElement(name, ElementType.Entrance, x1, -1.0, x1 + width, 1.0)

    /**
     * 一层的标准布局，与 assets 数据保持同样的相对顺序：
     *
     *       北侧：语文教研室 数学教研室 英语教研室 历史教研室 地理教研室
     *   ─────────────────── 走廊 ───────────────────
     *       南侧：南门 东楼梯口 计算机房 多媒体教室 语音教室 电梯口
     *
     * 从西向东的完整走廊顺序是：
     *   南门 -> 东楼梯口 -> 语文教研室 -> 计算机房 -> 数学教研室 -> 多媒体教室
     *   -> 英语教研室 -> 语音教室 -> 历史教研室 -> 电梯口 -> 地理教研室
     */
    fun standardFloor(
        id: String,
        level: Int,
        relativeHeightM: Double,
        northNames: List<String>,
        southRooms: List<String>,
    ): Floor {
        val elements = mutableListOf<Element>()
        northNames.forEachIndexed { index, name ->
            elements += northRoom(name, -20.0 + index * 8.0)
        }
        elements += entrance("南门", -26.0)
        elements += stair("东楼梯口", -22.0)
        southRooms.forEachIndexed { index, name ->
            elements += southRoom(name, -18.0 + index * 8.0)
        }
        elements += elevator("电梯口", -18.0 + southRooms.size * 8.0)

        return Floor(
            id = id,
            level = level,
            relativeHeightM = relativeHeightM,
            elements = elements,
        )
    }

    fun building(
        id: String,
        name: String,
        floors: List<Floor>,
        centerX: Double = 0.0,
    ): Building = Building(
        id = id,
        name = name,
        polygon = rect(centerX - 32.0, -12.0, centerX + 32.0, 12.0),
        floors = floors,
    )

    /** 一栋三层的标准楼栋，楼层高度 0 / 4 / 8。 */
    fun standardBuilding(
        id: String = "A",
        name: String = "A栋",
        centerX: Double = 0.0,
    ): Building = building(
        id = id,
        name = name,
        centerX = centerX,
        floors = listOf(
            standardFloor(
                id = "${id}_1F",
                level = 1,
                relativeHeightM = 0.0,
                northNames = listOf("语文教研室", "数学教研室", "英语教研室", "历史教研室", "地理教研室"),
                southRooms = listOf("计算机房", "多媒体教室", "语音教室"),
            ),
            standardFloor(
                id = "${id}_2F",
                level = 2,
                relativeHeightM = 4.0,
                northNames = listOf("物理实验室", "化学实验室", "生物实验室", "科学探究室", "创客空间"),
                southRooms = listOf("音乐教室", "美术教室", "书法教室"),
            ),
            standardFloor(
                id = "${id}_3F",
                level = 3,
                relativeHeightM = 8.0,
                northNames = listOf("高一(1)班", "高一(2)班", "高一(3)班", "高一(4)班", "高一(5)班"),
                southRooms = listOf("高一(6)班", "高一(7)班", "教务处"),
            ),
        ),
    )

    /**
     * 另一栋楼：房间名与 A 栋完全不同，用来验证“跨楼导航会去目标楼栋的正确楼层”。
     *
     * 复用 A 栋的房间名会让测试失去意义 —— 那样 B 栋的“图书馆”会出现在 A 栋的布局里，
     * 断言就分辨不出导航到底进了哪栋楼。
     */
    fun standardBuildingB(
        id: String = "B",
        name: String = "B栋",
        centerX: Double = 100.0,
    ): Building = building(
        id = id,
        name = name,
        centerX = centerX,
        floors = listOf(
            standardFloor(
                id = "${id}_1F",
                level = 1,
                relativeHeightM = 0.0,
                northNames = listOf("报告厅", "接待室", "校史馆"),
                southRooms = listOf("值班室"),
            ),
            standardFloor(
                id = "${id}_2F",
                level = 2,
                relativeHeightM = 4.0,
                northNames = listOf("图书馆", "阅览室", "电子阅览室"),
                southRooms = listOf("自习室", "研讨室"),
            ),
            standardFloor(
                id = "${id}_3F",
                level = 3,
                relativeHeightM = 8.0,
                northNames = listOf("机房", "网络中心", "多媒体报告厅"),
                southRooms = listOf("实验室", "准备室"),
            ),
        ),
    )

    /** 在楼层里按名字找元素，找不到直接让测试失败。 */
    fun Floor.element(name: String): Element =
        elements.firstOrNull { it.name == name }
            ?: error("楼层 ${this.id} 里没有名为“$name”的元素；现有：" +
                elements.joinToString("、") { it.name })
}
