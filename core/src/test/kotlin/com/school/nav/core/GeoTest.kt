package com.school.nav.core

import com.school.nav.core.model.ElementType
import com.school.nav.core.model.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 几何工具测试：楼栋定位完全依赖点与多边形的关系，这里必须可信。 */
class GeoTest {

    private val square = Fixtures.rect(-10.0, -10.0, 10.0, 10.0)

    @Test
    fun `点在多边形内部返回 true`() {
        assertTrue(Geo.containsPoint(square, Fixtures.ll(0.0, 0.0)))
        assertTrue(Geo.containsPoint(square, Fixtures.ll(9.0, -9.0)))
    }

    @Test
    fun `点在多边形外部返回 false`() {
        assertFalse(Geo.containsPoint(square, Fixtures.ll(11.0, 0.0)))
        assertFalse(Geo.containsPoint(square, Fixtures.ll(0.0, 11.0)))
        assertFalse(Geo.containsPoint(square, Fixtures.ll(-30.0, -30.0)))
    }

    @Test
    fun `点在边上视为在楼内`() {
        // 室内 GPS 贴边时更可能是真的在楼里，所以边界算内部
        assertTrue(Geo.containsPoint(square, Fixtures.ll(-10.0, 0.0)))
        assertTrue(Geo.containsPoint(square, Fixtures.ll(10.0, 10.0)))
    }

    @Test
    fun `凹多边形的凹陷处算外部`() {
        // U 形：上方中间挖空
        val u = listOf(
            Fixtures.ll(-10.0, -10.0),
            Fixtures.ll(10.0, -10.0),
            Fixtures.ll(10.0, 10.0),
            Fixtures.ll(5.0, 10.0),
            Fixtures.ll(5.0, 0.0),
            Fixtures.ll(-5.0, 0.0),
            Fixtures.ll(-5.0, 10.0),
            Fixtures.ll(-10.0, 10.0),
        )
        assertTrue(Geo.containsPoint(u, Fixtures.ll(0.0, -5.0)))
        assertFalse(Geo.containsPoint(u, Fixtures.ll(0.0, 5.0)))
    }

    @Test
    fun `质心是点串的算术平均`() {
        val centroid = Geo.centroid(square)
        val expected = Fixtures.ll(0.0, 0.0)
        assertEquals(expected.lng, centroid.lng, 1e-9)
        assertEquals(expected.lat, centroid.lat, 1e-9)
    }

    @Test
    fun `距离换算与实际米数一致`() {
        // 向东 10 米
        val a = Fixtures.ll(0.0, 0.0)
        val b = Fixtures.ll(10.0, 0.0)
        assertEquals(10.0, Geo.distanceMeters(a, b), 0.05)

        // 向北 25 米
        val c = Fixtures.ll(0.0, 25.0)
        assertEquals(25.0, Geo.distanceMeters(a, c), 0.05)

        // 勾股：30 / 40 -> 50
        val d = Fixtures.ll(30.0, 40.0)
        assertEquals(50.0, Geo.distanceMeters(a, d), 0.1)
    }

    @Test
    fun `多边形面积按平方米计算`() {
        // 20m x 20m = 400 平方米
        assertEquals(400.0, Geo.polygonAreaSqMeters(square), 1.0)
    }

    @Test
    fun `haversine 在短距离上与平面距离接近`() {
        val a = Fixtures.ll(0.0, 0.0)
        val b = Fixtures.ll(100.0, 0.0)
        assertEquals(100.0, Geo.haversineMeters(a, b), 0.5)
    }

    @Test
    fun `元素质心落在矩形中心`() {
        val room = Fixtures.northRoom("语文教研室", -20.0, width = 8.0)
        val centroid = room.centroid
        val expected = Fixtures.ll(-16.0, 5.0)
        assertEquals(expected.lng, centroid.lng, 1e-9)
        assertEquals(expected.lat, centroid.lat, 1e-9)
        assertEquals(ElementType.Room, room.elementType)
    }

    @Test
    fun `未知类型降级为教室而不是崩溃`() {
        val element = com.school.nav.core.model.Element(
            id = "x",
            type = "unknown_type",
            name = "某处",
            points = Fixtures.rect(0.0, 0.0, 1.0, 1.0),
        )
        assertEquals(ElementType.Room, element.elementType)
    }
}
