package com.school.nav.core.model

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 几何工具。
 *
 * 全部是纯函数，不依赖 Android，便于单元测试。
 * 只在校园尺度（几百米）使用，因此用等距圆柱近似而非完整大地球面公式。
 */
object Geo {
    /** 每度纬度对应的米数（地球平均子午线弧长）。 */
    const val METERS_PER_DEG_LAT = 110_574.0

    /** 赤道处每度经度对应的米数，需按纬度乘以 cos(lat) 修正。 */
    const val METERS_PER_DEG_LNG_AT_EQUATOR = 111_320.0

    /** 指定纬度上每度经度对应的米数。 */
    fun metersPerDegLng(lat: Double): Double =
        METERS_PER_DEG_LNG_AT_EQUATOR * cos(Math.toRadians(lat))

    /**
     * 点串质心（算术平均）。
     *
     * 教室等元素都是小矩形，算术平均即几何中心，精度足够。
     */
    fun centroid(points: List<LngLat>): LngLat {
        require(points.isNotEmpty()) { "点串不能为空" }
        var sx = 0.0
        var sy = 0.0
        for (p in points) {
            sx += p.lng
            sy += p.lat
        }
        val n = points.size
        return LngLat(sx / n, sy / n)
    }

    /**
     * 两点距离（米）。等距圆柱近似：把经纬度差按当地比例换算成米再求欧氏距离。
     *
     * 在 100 米范围内误差在毫米级，远小于室内定位本身的误差。
     */
    fun distanceMeters(a: LngLat, b: LngLat): Double {
        val midLat = (a.lat + b.lat) / 2.0
        val dx = (b.lng - a.lng) * metersPerDegLng(midLat)
        val dy = (b.lat - a.lat) * METERS_PER_DEG_LAT
        return sqrt(dx * dx + dy * dy)
    }

    /** 点串两两之间的最大距离，用于判断元素尺度是否合理。 */
    fun boundingSpanMeters(points: List<LngLat>): Double {
        if (points.size < 2) return 0.0
        var max = 0.0
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val d = distanceMeters(points[i], points[j])
                if (d > max) max = d
            }
        }
        return max
    }

    /**
     * 射线法判断点是否在多边形内。
     *
     * 做法：从待测点向正东引一条射线，统计与多边形边的交点个数，奇数则在内部。
     * 这是楼栋定位的核心：GPS 落在哪栋楼的多边形里就锁定哪栋楼。
     *
     * 顶点被射线正好穿过的情况用半开区间 (yi > y) != (yj > y) 处理，避免重复计数。
     * 边界上的点视为“在内部”，因为室内 GPS 误差下贴边更可能是真的在楼里。
     */
    fun containsPoint(polygon: List<LngLat>, point: LngLat): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        val x = point.lng
        val y = point.lat
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].lng
            val yi = polygon[i].lat
            val xj = polygon[j].lng
            val yj = polygon[j].lat

            if (isOnSegment(x, y, xi, yi, xj, yj)) return true

            val crosses = (yi > y) != (yj > y)
            if (crosses) {
                val xCross = (xj - xi) * (y - yi) / (yj - yi) + xi
                if (x < xCross) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** 判断点是否落在线段上（含端点），带一个很小的经纬度容差。 */
    private fun isOnSegment(
        x: Double,
        y: Double,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
    ): Boolean {
        val tolerance = 1e-9
        val cross = (x2 - x1) * (y - y1) - (y2 - y1) * (x - x1)
        if (abs(cross) > tolerance) return false
        val withinX = x >= min(x1, x2) - tolerance && x <= maxOf(x1, x2) + tolerance
        val withinY = y >= min(y1, y2) - tolerance && y <= maxOf(y1, y2) + tolerance
        return withinX && withinY
    }

    /** 多边形面积（平方米），用于楼栋规模校验。 */
    fun polygonAreaSqMeters(polygon: List<LngLat>): Double {
        if (polygon.size < 3) return 0.0
        val refLat = polygon.map { it.lat }.average()
        val kx = metersPerDegLng(refLat)
        val ky = METERS_PER_DEG_LAT
        var sum = 0.0
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].lng * kx
            val yi = polygon[i].lat * ky
            val xj = polygon[j].lng * kx
            val yj = polygon[j].lat * ky
            sum += xj * yi - xi * yj
            j = i
        }
        return abs(sum) / 2.0
    }

    /** 把点按给定原点投影到局部平面米坐标（东为 +x，北为 +y）。 */
    fun toLocalMeters(origin: LngLat, point: LngLat): Pair<Double, Double> {
        val kx = metersPerDegLng(origin.lat)
        val ky = METERS_PER_DEG_LAT
        val x = (point.lng - origin.lng) * kx
        val y = (point.lat - origin.lat) * ky
        return x to y
    }

    /**
     * 球面两点距离（Haversine），用于跨楼栋这种上百米的估算。
     * 导航文案不使用米数，这里只服务于内部判断（例如“目标楼栋有多远”）。
     */
    fun haversineMeters(a: LngLat, b: LngLat): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin2(dLat / 2) + cos(lat1) * cos(lat2) * sin2(dLng / 2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }

    private fun sin2(v: Double): Double = kotlin.math.sin(v) * kotlin.math.sin(v)
}
