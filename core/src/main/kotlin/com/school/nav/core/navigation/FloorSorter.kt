package com.school.nav.core.navigation

import com.school.nav.core.model.Element
import com.school.nav.core.model.ElementType
import com.school.nav.core.model.Floor
import com.school.nav.core.model.Geo
import com.school.nav.core.model.LngLat
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 楼层走廊主轴的方向，用罗盘八方位表示。
 *
 * 决定文案里的“沿走廊向东走 / 向西走”。
 */
enum class CorridorDirection(val text: String) {
    East("向东"),
    West("向西"),
    North("向北"),
    South("向南"),
    NorthEast("向东北"),
    NorthWest("向西北"),
    SouthEast("向东南"),
    SouthWest("向西南"),
    ;

    companion object {
        /**
         * 由方位角（度，正北为 0，顺时针增大；东为 90）取最接近的八方位。
         */
        fun fromBearingDegrees(bearing: Double): CorridorDirection {
            val normalized = ((bearing % 360) + 360) % 360
            val index = (((normalized + 22.5) / 45.0).toInt()) % 8
            return entries[index]
        }
    }
}

/** 元素在走廊上的左右侧。以“沿走廊前进方向”为参照。 */
enum class Side { Left, Right, Center }

/** 楼层元素的走廊排列结果。 */
data class CorridorOrder(
    /** 沿走廊主轴（自西向东）排好序的元素。 */
    val elements: List<Element>,
    /** 沿走廊前进（东向）时，指向左手边的单位向量，局部米坐标（x=东，y=北）。 */
    val leftVector: Pair<Double, Double>,
    /** 走廊主轴的方向文案。 */
    val direction: CorridorDirection,
    /** 局部平面原点，左右判断需要用到。 */
    val origin: LngLat,
) {
    fun indexOf(elementId: String): Int = elements.indexOfFirst { it.id == elementId }
}

/**
 * 楼层排序器：按“走廊主轴”把同层元素排成一条线。
 *
 * 产品文档要求“计算同层元素的质心，沿走廊主轴方向投影排序”。
 * 这里用 PCA（协方差矩阵主特征向量）自动求主轴，不需要额外录入走廊走向：
 *
 *   1. 把所有元素质心换算到以楼层均值为原点的局部米坐标（x=东，y=北）；
 *   2. 求 2x2 协方差矩阵的最大特征向量，即元素分布最长的方向（= 走廊方向）；
 *   3. 把主轴**吸附到主导方向**（正东西或正南北），丢掉数据小错位带来的偏角；
 *   4. 按元素质心在主轴上的一维投影排序，投影相同时用确定性次级键补齐全序。
 *
 * 为什么第 3、4 步必需，见 `docs/技术方案落地说明.md` 第五节：
 * 偏轴会让同一侧的两间教室产生假横向差，把「左手边」判成「正前方」；
 * 缺少次级键则相等键的顺序不可复现，导航文案不稳定。
 *
 * 兜底：点太少或要素退化时，退回按经度、再按纬度排序，与 Demo 的 sortElements 行为一致。
 */
object FloorSorter {

    fun sort(floor: Floor): CorridorOrder {
        val elements = floor.elements
        if (elements.isEmpty()) {
            return CorridorOrder(emptyList(), 1.0 to 0.0, CorridorDirection.East, LngLat(0.0, 0.0))
        }

        val centroids = elements.map { it.centroid }
        val origin = Geo.centroid(centroids)

        // 局部米坐标：x = 东，y = 北
        val local = centroids.map { Geo.toLocalMeters(origin, it) }
        val axis = principalAxis(local)

        if (axis == null) {
            // 退化：按经度升序，经度相同则北侧优先（与 Demo 一致）。
            // direction 在这里没有物理意义，导航层会用 directionFrom() 按元素相对位置重新计算。
            val sorted = elements.sortedWith(
                compareBy({ it.centroid.lng }, { -it.centroid.lat }),
            )
            return CorridorOrder(
                elements = sorted,
                leftVector = 1.0 to 0.0,
                direction = CorridorDirection.East,
                origin = origin,
            )
        }

        // 把主轴吸附到主导方向（正东西 / 正南北）。
        //
        // 为什么必须吸附：PCA 出来的轴会被数据里的小错位带偏几度（例如楼梯口与教室列
        // 在 x 上错开 4 米，主轴就偏了约 6 度）。用偏轴算“左手边”，会让同一侧的两间教室
        // 产生 1 米级的假横向差，把“在左手边”判成“正前方”；反过来又会让左右互换。
        // 吸附之后 leftVector 是纯净的 ±(0,1) 或 ±(1,0)，与人的直觉一致，排序也随之稳定。
        val snapped = snapToDominantAxis(axis)
        val (ux, uy) = snapped

        // 排序键用**显式 Comparator**，不用 compareBy(a, b, c, d)。
        //
        // 原因：刻意踩过一次 —— compareBy 的多 selector 重载在这里没有按预期生效，
        // 次级键被丢掉，导致「卫生间 / 地理教研室」这种投影坐标相同的元素
        // 顺序随机（同一份数据两次运行结果不同）。显式写出来最不容易出错。
        //
        // 键的顺序：
        //  1. 沿走廊主轴的投影坐标 —— 决定走廊上的先后；
        //  2. 北侧优先（-lat）—— 投影相同时北边的先经过；
        //  3. 自西向东（lng）；
        //  4. id —— 终极兜底，保证全序、保证可复现。
        val sorted = elements.indices
            .sortedWith { ia, ib ->
                val (ax, ay) = local[ia]
                val (bx, by) = local[ib]

                // 投影坐标**量化到微米**再比较。
                //
                // 实测原因：同一经度的教室与卫生间，理论投影完全相等，但经过
                // 「经纬度 <-> 米」两次换算后会差出约 1.5e-9 米（纯浮点噪声）。
                // 直接 compareTo 会把这个噪声当成真实差异，于是「北侧优先」这次级键
                // 永远不会被执行，顺序看上去就像是随机的。
                // 走廊元素间距是米级，量化到 1e-6 米不会影响任何真实排序。
                val pa = quantize(ax * ux + ay * uy)
                val pb = quantize(bx * ux + by * uy)

                var result = pa.compareTo(pb)
                // 北侧优先：直接按纬度降序比较
                if (result == 0) result = centroids[ib].lat.compareTo(centroids[ia].lat)
                if (result == 0) result = centroids[ia].lng.compareTo(centroids[ib].lng)
                if (result == 0) result = elements[ia].id.compareTo(elements[ib].id)
                result
            }
            .map { elements[it] }

        return CorridorOrder(
            elements = sorted,
            // 沿 (ux, uy) 前进时的左手边法线
            leftVector = -uy to ux,
            direction = bearingOf(ux, uy),
            origin = origin,
        )
    }

    /** 把投影坐标量化到微米，抹掉「经纬度 <-> 米」换算产生的浮点噪声。 */
    private fun quantize(meters: Double): Double = kotlin.math.round(meters * 1e6) / 1e6

    /** 把方向吸附到四个正方向中更接近的那个（只保留主导分量，丢弃噪声分量）。 */
    private fun snapToDominantAxis(axis: Pair<Double, Double>): Pair<Double, Double> {
        val (ux, uy) = axis
        return if (kotlin.math.abs(ux) >= kotlin.math.abs(uy)) {
            (if (ux >= 0) 1.0 else -1.0) to 0.0
        } else {
            0.0 to (if (uy >= 0) 1.0 else -1.0)
        }
    }

    /**
     * 判断 [other] 相对 [reference] 的左右方位。
     *
     * 关键是看**目标落在走廊的哪一侧**，而不是「相对当前位置的横向分量」：
     *
     *  - 两侧不同（一个在北边一个在南边）：目标在前进方向的某一侧，直接按符号给左右；
     *  - 两侧相同（都在北边或都在南边）：两者横向位置几乎相同，横向差值会被数据里的
     *    轻微主轴偏转放大成噪声，所以改用「目标自身在走廊哪一侧」来判断 ——
     *    沿走廊前进时，左手边的教室始终在左手边，与从哪里出发无关。
     *
     * 这个改动修掉了一个真实 bug：楼梯口与教室列在 x 上错开约 4 米时，主轴被 PCA
     * 拉偏约 6 度，两间相邻的北侧教室横向差只有 0.84 米，会被误判成“正前方”。
     */
    fun sideOf(order: CorridorOrder, reference: Element, other: Element): Side {
        val (lx, ly) = order.leftVector
        val (rx, ry) = Geo.toLocalMeters(order.origin, reference.centroid)
        val (ox, oy) = Geo.toLocalMeters(order.origin, other.centroid)

        // 参考点与目标点各自在走廊主轴上的横向偏移
        val lateralReference = rx * lx + ry * ly
        val lateralOther = ox * lx + oy * ly

        val refSide = sideSign(lateralReference)
        val otherSide = sideSign(lateralOther)

        // 同一侧：左右由目标自身所在的一侧决定
        if (refSide == otherSide && otherSide != Side.Center) return otherSide

        // 不同侧：目标在前进方向的哪一边，就是哪一边
        return otherSide
    }

    /** 只有一个很小的横向偏移时视为“走廊正中”（例如入口、正对走廊的位置）。 */
    private fun sideSign(lateral: Double): Side = when {
        lateral > SIDE_TOLERANCE_M -> Side.Left
        lateral < -SIDE_TOLERANCE_M -> Side.Right
        else -> Side.Center
    }

    /**
     * 找该层的楼梯口，作为跨层换乘点。
     *
     * 优先选离 [from] 最近的楼梯；没有楼梯数据时退回该层第一个元素。
     */
    fun findStair(floor: Floor, from: LngLat? = null): Element? {
        val stairs = floor.elements.filter { it.elementType == ElementType.Stair }
        val candidates = stairs.ifEmpty { floor.elements }
        if (candidates.isEmpty()) return null
        if (from == null) return candidates.first()
        return candidates.minBy { Geo.distanceMeters(from, it.centroid) }
    }

    /** 找离给定经纬度最近的元素，作为“默认当前位置”。 */
    fun nearestElement(floor: Floor, point: LngLat): Element? =
        floor.elements.minByOrNull { Geo.distanceMeters(point, it.centroid) }

    /** 找离给定经纬度最近的、且类型在 [types] 之内的元素。 */
    fun nearestElement(floor: Floor, point: LngLat, types: Set<ElementType>): Element? =
        floor.elements
            .filter { it.elementType in types }
            .minByOrNull { Geo.distanceMeters(point, it.centroid) }

    /**
     * 从 [from] 走向 [to] 时，走廊主轴方向文案。
     *
     * 用于两种情况：
     *  1. 跨层时“先走到楼梯口”这一段（起点是当前位置，重点是楼梯口）；
     *  2. 跨层时“出楼梯后”这一段（起点是目标层的楼梯口，重点是目标）。
     *
     * 判据是两者在局部米坐标里的相对位移，与元素排序无关，因此比直接用
     * [CorridorOrder.direction] 更稳（PCA 退化时也正确）。
     */
    fun directionFrom(order: CorridorOrder, from: Element, to: Element): CorridorDirection {
        val (fx, fy) = Geo.toLocalMeters(order.origin, from.centroid)
        val (tx, ty) = Geo.toLocalMeters(order.origin, to.centroid)
        return bearingOf(tx - fx, ty - fy)
    }

    /**
     * 局部米坐标下，方向向量 (ux, uy) 的罗盘方位角（度）。
     *
     * 约定：atan2(y, x) 的两个参数是 (北分量, 东分量) —— 从正北开始顺时针增大，
     * 因此东为 90、南为 180、西为 270。写成 atan2(ux, uy) 会差 90 度（把东认成北）。
     */
    private fun bearingOf(ux: Double, uy: Double): CorridorDirection {
        val bearing = Math.toDegrees(atan2(uy, ux))
        return CorridorDirection.fromBearingDegrees(bearing)
    }

    /**
     * 求点集的主方向（PCA 最大特征向量），已统一朝向“东向为正”。
     * 退化时返回 null。
     */
    private fun principalAxis(points: List<Pair<Double, Double>>): Pair<Double, Double>? {
        if (points.size < 2) return null

        val cx = points.map { it.first }.average()
        val cy = points.map { it.second }.average()

        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for ((x, y) in points) {
            val dx = x - cx
            val dy = y - cy
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        val n = points.size.toDouble()
        sxx /= n
        syy /= n
        sxy /= n

        // 2x2 对称矩阵 [[sxx, sxy], [sxy, syy]] 的主特征向量
        val trace = sxx + syy
        val det = sxx * syy - sxy * sxy
        val disc = (trace * trace / 4.0 - det).coerceAtLeast(0.0)
        val lambda = trace / 2.0 + kotlin.math.sqrt(disc)

        var vx: Double
        var vy: Double
        if (kotlin.math.abs(sxy) > 1e-12) {
            vx = lambda - syy
            vy = sxy
        } else if (sxx >= syy) {
            vx = 1.0
            vy = 0.0
        } else {
            vx = 0.0
            vy = 1.0
        }

        val len = hypot(vx, vy)
        if (len < 1e-9) return null
        vx /= len
        vy /= len

        // 轴的符号没有物理意义（PCA 的两个方向等价），统一成“东分量非负”，
        // 这样排序方向确定、可测试。纯南北走廊（vx == 0）不受影响。
        if (vx < 0) {
            vx = -vx
            vy = -vy
        }
        return vx to vy
    }

    /** 左右判定容差：小于该横向偏移视为“正前方”而不是左右两侧。 */
    private const val SIDE_TOLERANCE_M = 1.0
}
