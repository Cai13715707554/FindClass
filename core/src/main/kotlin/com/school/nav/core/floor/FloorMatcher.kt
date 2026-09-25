package com.school.nav.core.floor

import com.school.nav.core.model.Floor
import kotlin.math.abs
import kotlin.math.pow

/**
 * 气压计楼层判断。
 *
 * 思路（对应技术方案第六节）：
 *  1. 气压计原始值噪声大，先做滑动平均；
 *  2. 用国际气压高度公式把气压换算成相对高度：
 *        h = 44330 * (1 - (P / P0) ^ (1 / 5.255))
 *  3. 相对高度 h 与各楼层 `relative_height_m`（以楼栋地面层为 0）比较，取最近的一层；
 *  4. 加切换阈值（滞后），避免人在两层之间或气压抖动时楼层来回跳。
 *
 * 基准气压 P0 不能硬编码 —— 天气、空调、密闭程度都会影响绝对气压。
 * 因此要求先“校准”：用户站在自己确认的楼层上，把当时的气压记为该楼层基准，
 * 之后所有相对高度都以这个基准推算。这样气压计只需要有足够的“相对分辨率”，
 * 不需要绝对精度，对手机气压计普遍存在的偏差更鲁棒。
 *
 * 本类不是线程安全的，按“单线程喂样本”使用（在传感器回调线程里）。
 */
class FloorMatcher(
    private val config: FloorMatcherConfig = FloorMatcherConfig.Default,
) {

    /** 最近 [windowSize] 个气压样本的滑动平均。 */
    private val window = ArrayDeque<Double>()

    private var calibration: Calibration? = null

    /** 上一次给出的楼层，用于滞后判定。 */
    private var lastFloorId: String? = null

    /** 当前滑动平均气压（hPa）。样本不足时为 null。 */
    val smoothedPressureHpa: Double?
        get() = if (window.isEmpty()) null else window.average()

    /** 已累积的样本数量。 */
    val sampleCount: Int get() = window.size

    /**
     * 当前推算出的相对高度（米）。未校准时为 null。
     *
     * 对应楼层 `relative_height_m` 的同一基准，便于在日志/测试里核对
     * “为什么判成了这一层”。
     */
    val estimatedHeightM: Double?
        get() {
            val cal = calibration ?: return null
            val pressure = smoothedPressureHpa ?: return null
            return relativeHeightMeters(cal.pressureHpa, pressure, cal.heightM)
        }

    /** 是否已经攒够样本，可以给出可信判断。 */
    val hasEnoughSamples: Boolean get() = window.size >= config.minSamples

    /** 是否已完成基准校准。 */
    val isCalibrated: Boolean get() = calibration != null

    /**
     * 设置基准：已知用户此刻站在 [referenceFloor] 上，且此刻气压为 [referencePressureHpa]。
     *
     * 重新定位、或用户在“修改位置”里手动选定楼层后调用。
     */
    fun calibrate(referenceFloor: Floor, referencePressureHpa: Double) {
        calibration = Calibration(
            pressureHpa = referencePressureHpa,
            heightM = referenceFloor.relativeHeightM,
        )
        lastFloorId = null
        // 校准后丢弃旧窗口，避免用校准前的数据算相对高度
        window.clear()
        window.addLast(referencePressureHpa)
    }

    /** 清除校准结果（例如传感器不可用、用户要求重新定位）。 */
    fun reset() {
        window.clear()
        calibration = null
        lastFloorId = null
    }

    /**
     * 喂入一个气压样本。
     *
     * @return 平稳后的平滑气压值（hPa）。
     */
    fun onPressureSample(pressureHpa: Double): Double {
        require(pressureHpa > 0) { "气压必须为正数" }
        window.addLast(pressureHpa)
        while (window.size > config.windowSize) window.removeFirst()
        return window.average()
    }

    /**
     * 根据当前平滑气压判断楼层。
     *
     * @param floors 当前楼栋的楼层列表
     * @param currentFloorId 当前已知楼层 id；用于滞后判定，可为 null
     * @return 匹配到的楼层；样本不足或未校准或气压不可信时返回 null（交由用户手动选择）
     */
    fun matchFloor(floors: List<Floor>, currentFloorId: String? = lastFloorId): Floor? {
        val cal = calibration ?: return null
        if (floors.isEmpty()) return null
        if (window.size < config.minSamples) return null

        val pressure = smoothedPressureHpa ?: return null
        val height = relativeHeightMeters(cal.pressureHpa, pressure, cal.heightM)

        val nearest = floors.minByOrNull { abs(it.relativeHeightM - height) } ?: return null
        val nearestDelta = abs(nearest.relativeHeightM - height)

        // 离所有楼层都太远 —— 要么用户在楼外，要么气压计不可信。保持现状，不瞎猜。
        if (nearestDelta > config.maxMatchDeltaM) return null

        val effectiveCurrentId = currentFloorId ?: lastFloorId
        val current = effectiveCurrentId?.let { id -> floors.firstOrNull { it.id == id } }

        // 滞后：除非新楼层明显更近，否则维持当前楼层，避免来回跳
        if (current != null && current.id != nearest.id) {
            val currentDelta = abs(current.relativeHeightM - height)
            val improvement = currentDelta - nearestDelta
            if (improvement < config.switchThresholdM) {
                return current
            }
        }

        lastFloorId = nearest.id
        return nearest
    }

    /** 计算相对高度（米），相对 [referenceHeightM] 所在的基准。 */
    fun relativeHeightMeters(
        referencePressureHpa: Double,
        currentPressureHpa: Double,
        referenceHeightM: Double,
    ): Double {
        val ratio = currentPressureHpa / referencePressureHpa
        return referenceHeightM + BAROMETRIC_CONSTANT * (1.0 - ratio.pow(BAROMETRIC_EXPONENT))
    }

    private data class Calibration(
        val pressureHpa: Double,
        val heightM: Double,
    )

    companion object {
        /**
         * 国际气压高度公式常数：h = 44330 * (1 - (P/P0) ^ (1/5.255))
         * 由于这里算的是“相对”高度，常数直接取自公式本身。
         */
        const val BAROMETRIC_CONSTANT = 44330.0
        const val BAROMETRIC_EXPONENT = 1.0 / 5.255

        /** 一层楼大致的气压差（hPa），约 4 米对应 0.48 hPa，用于文档与测试参考。 */
        const val TYPICAL_FLOOR_PRESSURE_DELTA_HPA = 0.48
    }
}

/** 楼层匹配参数。 */
data class FloorMatcherConfig(
    /** 滑动平均窗口长度。 */
    val windowSize: Int = 8,
    /** 至少累积这么多个样本才开始判断。 */
    val minSamples: Int = 5,
    /** 离最近楼层超过这个高度差（米）就认为气压不可信。 */
    val maxMatchDeltaM: Double = 6.0,
    /** 切换到新楼层需要比当前楼层“近”多少米，用于滞后。 */
    val switchThresholdM: Double = 1.5,
) {
    companion object {
        val Default = FloorMatcherConfig()
    }
}
