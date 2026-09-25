package com.school.nav.core

import com.school.nav.core.floor.FloorMatcher
import com.school.nav.core.floor.FloorMatcherConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气压计楼层判断测试。
 *
 * 对应技术方案第十节“测试重点：气压计楼层匹配”与风险对策
 * （滑动平均、基准校准、楼层滞后）。
 *
 * 注意：滑窗长度为 8，只喂 1~2 个样本时滑动平均尚未到达新值
 * （例如窗口里 8 个旧值 + 1 个新值，均值只移动了 1/9）。
 * 因此下面凡是断言“到某一层”的用例，都用 [settle] 让窗口稳定到目标气压，
 * 否则测的是瞬态而不是算法本身。
 */
class FloorMatcherTest {

    private val building = Fixtures.standardBuilding()
    private val floors = building.orderedFloors  // 0m / 4m / 8m

    /** 标准大气压附近的任意正值都可以，因为算法只用相对变化。 */
    private val basePressure = 1000.0

    /** 让滑窗完全稳定到指定气压（窗口长度 8，喂满即到位）。 */
    private fun FloorMatcher.settle(pressure: Double) {
        repeat(8) { onPressureSample(pressure) }
        check(smoothedPressureHpa?.let { kotlin.math.abs(it - pressure) < 1e-9 } == true) {
            "滑窗未稳定到 $pressure，实际 ${smoothedPressureHpa}"
        }
    }

    /** 从气压差反算“到达目标高度需要的气压变化”，避免手算错。 */
    private fun FloorMatcher.pressureDropFor(targetHeightM: Double): Double {
        var low = 0.0
        var high = 20.0
        repeat(60) {
            val mid = (low + high) / 2
            if (relativeHeightMeters(basePressure, basePressure - mid, 0.0) < targetHeightM) {
                low = mid
            } else {
                high = mid
            }
        }
        return (low + high) / 2
    }

    @Test
    fun `滑窗采样不改变恒定输入`() {
        val matcher = FloorMatcher()
        matcher.settle(1000.0)
        assertEquals(1000.0, matcher.smoothedPressureHpa!!, 1e-9)
    }

    @Test
    fun `滑动平均能抑制单个噪声点`() {
        val matcher = FloorMatcher()
        matcher.settle(1000.0)
        val smoothed = matcher.onPressureSample(1005.0)  // 突然来一个坏点
        // 8 个 1000 加 1 个 1005，均值只应移动约 0.55
        assertTrue("平滑值应被拉回，实际 $smoothed", smoothed < 1001.0)
    }

    @Test
    fun `未校准时不给出楼层`() {
        val matcher = FloorMatcher()
        matcher.settle(basePressure)
        assertNull(matcher.matchFloor(floors))
    }

    @Test
    fun `样本不足时不给出楼层`() {
        val matcher = FloorMatcher(FloorMatcherConfig(minSamples = 5))
        matcher.calibrate(floors[0], basePressure)
        matcher.onPressureSample(basePressure)
        matcher.onPressureSample(basePressure)
        assertNull("只喂了 2 个样本，不该下结论", matcher.matchFloor(floors))
    }

    @Test
    fun `基准楼层上判断为同一层`() {
        val matcher = FloorMatcher()
        matcher.calibrate(floors[0], basePressure)
        matcher.settle(basePressure)
        assertEquals(1, matcher.matchFloor(floors)?.level)
    }

    @Test
    fun `气压下降约 0_48 hPa 判断为上到二楼`() {
        val matcher = FloorMatcher()
        matcher.calibrate(floors[0], basePressure)
        matcher.settle(basePressure - FloorMatcher.TYPICAL_FLOOR_PRESSURE_DELTA_HPA)
        assertEquals(2, matcher.matchFloor(floors)?.level)
    }

    @Test
    fun `气压下降约 0_96 hPa 判断为上到三楼`() {
        val matcher = FloorMatcher()
        matcher.calibrate(floors[0], basePressure)
        matcher.settle(basePressure - 2 * FloorMatcher.TYPICAL_FLOOR_PRESSURE_DELTA_HPA)
        assertEquals(3, matcher.matchFloor(floors)?.level)
    }

    @Test
    fun `离所有楼层都太远时不猜楼层`() {
        val matcher = FloorMatcher(FloorMatcherConfig(maxMatchDeltaM = 6.0))
        matcher.calibrate(floors[0], basePressure)
        // 稳定到约 25 米高，远超楼顶（8 米）
        matcher.settle(basePressure - 3.0)
        assertTrue("用例前提：高度应远超楼顶", matcher.estimatedHeightM!! > 20.0)
        assertNull("不应把楼外/电梯井里的高度硬套成某个楼层", matcher.matchFloor(floors))
    }

    @Test
    fun `偏差不大时保持当前楼层不抖动`() {
        val matcher = FloorMatcher(FloorMatcherConfig(switchThresholdM = 1.5))
        matcher.calibrate(floors[0], basePressure)
        matcher.settle(basePressure)

        // 稳定到 2.2 米：离 1 楼差 2.2 米、离 2 楼差 1.8 米，改善只有 0.4 米
        val drop = matcher.pressureDropFor(2.2)
        matcher.settle(basePressure - drop)
        assertEquals("推算高度 ${matcher.estimatedHeightM} 米", 2.2, matcher.estimatedHeightM!!, 0.05)

        assertEquals(
            "两层之间应保持当前楼层，避免来回跳",
            1,
            matcher.matchFloor(floors, currentFloorId = floors[0].id)?.level,
        )
    }

    @Test
    fun `明确到达上层时允许切换楼层`() {
        val matcher = FloorMatcher(FloorMatcherConfig(switchThresholdM = 1.5))
        matcher.calibrate(floors[0], basePressure)
        matcher.settle(basePressure)

        // 稳定到 6.2 米：离 1 楼差 6.2 米、离 3 楼差 1.8 米，改善 4.4 米，远超阈值
        val drop = matcher.pressureDropFor(6.2)
        matcher.settle(basePressure - drop)
        assertEquals(6.2, matcher.estimatedHeightM!!, 0.05)

        assertEquals(
            "推算高度 ${matcher.estimatedHeightM} 米，应判为 3 楼",
            3,
            matcher.matchFloor(floors, currentFloorId = floors[0].id)?.level,
        )
    }

    @Test
    fun `相对高度公式与气压高度关系一致`() {
        val matcher = FloorMatcher()
        val zero = matcher.relativeHeightMeters(basePressure, basePressure, 0.0)
        val up = matcher.relativeHeightMeters(basePressure, basePressure - 1.0, 0.0)
        assertEquals(0.0, zero, 1e-9)
        // 海平面附近，气压每降低 1 hPa 高度约上升 8.4 米
        assertEquals(8.4, up - zero, 0.5)
    }

    @Test
    fun `重新校准后基准楼层可以不是一楼`() {
        val matcher = FloorMatcher()
        // 用户手动告诉系统“我现在在 3 楼”，当时的读数是 basePressure
        matcher.calibrate(floors[2], basePressure)
        matcher.settle(basePressure)
        assertEquals(3, matcher.matchFloor(floors)?.level)

        // 下到 1 楼：气压升高约 0.96 hPa
        matcher.settle(basePressure + 2 * FloorMatcher.TYPICAL_FLOOR_PRESSURE_DELTA_HPA)
        assertEquals(1, matcher.matchFloor(floors, currentFloorId = floors[2].id)?.level)
    }

    @Test
    fun `重置后回到未校准状态`() {
        val matcher = FloorMatcher()
        matcher.calibrate(floors[0], basePressure)
        matcher.settle(basePressure)
        matcher.reset()
        assertNull(matcher.smoothedPressureHpa)
        assertNull(matcher.estimatedHeightM)
        assertNull(matcher.matchFloor(floors))
    }
}
