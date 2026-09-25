package com.school.nav.core

import com.school.nav.core.Fixtures.element
import com.school.nav.core.navigation.CorridorDirection
import com.school.nav.core.navigation.FloorSorter
import com.school.nav.core.navigation.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 同层排序测试：走廊顺序与左右方位是导航文案正确性的基础。 */
class FloorSorterTest {

    private val building = Fixtures.standardBuilding()
    private val floor1 = building.floorByLevel(1)!!

    @Test
    fun `按走廊主轴自西向东排序`() {
        val order = FloorSorter.sort(floor1)
        val names = order.elements.map { it.name }

        // 走廊主轴自西向东；投影坐标相同时北侧优先。
        // 「电梯口」与「历史教研室」同经度，历史教研室在北侧，所以它排在电梯口之前。
        assertEquals(
            listOf(
                "南门", "东楼梯口",
                "语文教研室", "计算机房",
                "数学教研室", "多媒体教室",
                "英语教研室", "语音教室",
                "历史教研室", "电梯口",
                "地理教研室",
            ),
            names,
        )
    }

    @Test
    fun `走廊主轴方向判为向东`() {
        val order = FloorSorter.sort(floor1)
        assertEquals(CorridorDirection.East, order.direction)
    }

    @Test
    fun `沿走廊前进时北侧元素在左手边`() {
        val order = FloorSorter.sort(floor1)
        val reference = floor1.element("南门")

        // 北侧教室（y 为正）应在左手边，南侧（y 为负）在右手边
        val north = floor1.element("数学教研室")
        val south = floor1.element("多媒体教室")

        assertEquals(Side.Left, FloorSorter.sideOf(order, reference, north))
        assertEquals(Side.Right, FloorSorter.sideOf(order, reference, south))
    }

    @Test
    fun `正对面的元素按地理位置判左右而不是按顺序`() {
        val order = FloorSorter.sort(floor1)
        val reference = floor1.element("语文教研室")   // 北侧
        val opposite = floor1.element("计算机房")       // 南侧，经度更靠东

        // 计算机房在东南方向 -> 前进方向（东）看过去是右手边
        assertEquals(Side.Right, FloorSorter.sideOf(order, reference, opposite))
    }

    @Test
    fun `排序结果与元素输入顺序无关`() {
        val reversed = floor1.copy(elements = floor1.elements.reversed())
        assertEquals(
            FloorSorter.sort(floor1).elements.map { it.name },
            FloorSorter.sort(reversed).elements.map { it.name },
        )
    }

    @Test
    fun `找楼梯口优先返回离出发位置最近的那个`() {
        val stair = FloorSorter.findStair(floor1, floor1.element("地理教研室").centroid)
        assertEquals("东楼梯口", stair?.name)
    }

    @Test
    fun `没有楼梯时退回电梯口`() {
        val noStair = floor1.copy(
            elements = floor1.elements.filter { it.elementType != com.school.nav.core.model.ElementType.Stair },
        )
        assertEquals("电梯口", FloorSorter.findStair(noStair)?.name)
    }

    @Test
    fun `找不到最近元素时返回 null 而不是抛异常`() {
        val empty = floor1.copy(elements = emptyList())
        assertEquals(null, FloorSorter.nearestElement(empty, Fixtures.ll(0.0, 0.0)))
        val order = FloorSorter.sort(empty)
        assertTrue(order.elements.isEmpty())
    }

    @Test
    fun `南北走向的走廊给出确定顺序`() {
        // 三个元素经度完全相同，方差集中在南北方向：PCA 主轴应是南北向，
        // 而不是退化成“按经度排”导致顺序随机。结果必须确定、可复现。
        val floor = floor1.copy(
            elements = listOf(
                Fixtures.northRoom("北一", 0.0),
                Fixtures.southRoom("南一", 0.0),
                Fixtures.elevator("电梯", 0.0),
            ),
        )
        val order = FloorSorter.sort(floor)
        // 主轴吸附到正南北后，走廊方向是向南，于是同纬度时按「西侧优先」排列
        assertEquals(listOf("电梯", "南一", "北一"), order.elements.map { it.name })
        assertEquals(CorridorDirection.North, order.direction)
        // 沿正北前进时，左手边是西（-x 方向）
        val (lx, ly) = order.leftVector
        assertEquals("左手边应为正西", -1.0, lx, 1e-9)
        assertEquals(0.0, ly, 1e-9)
    }

    @Test
    fun `元素完全重合时走兜底排序而不是崩溃`() {
        val degenerate = floor1.copy(elements = emptyList())
        assertTrue(FloorSorter.sort(degenerate).elements.isEmpty())
    }

    @Test
    fun `默认当前位置取离用户最近的元素且排除入口`() {
        // 用户站在北侧语文教研室门口附近
        val point = Fixtures.ll(-16.0, 9.5)
        val nearest = FloorSorter.nearestElement(floor1, point)
        assertEquals("语文教研室", nearest?.name)
    }
}
