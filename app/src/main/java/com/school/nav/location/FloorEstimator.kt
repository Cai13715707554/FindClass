package com.school.nav.location

import com.school.nav.core.floor.FloorMatcher
import com.school.nav.core.model.Building
import com.school.nav.core.model.Floor
import com.school.nav.core.model.Geo
import com.school.nav.core.model.LngLat

/** 楼层估算结果。 */
sealed interface FloorEstimate {

    /** 已判断出楼层。 */
    data class Known(val floor: Floor) : FloorEstimate

    /** 气压样本还不够，正在稳定中。 */
    data object Stabilizing : FloorEstimate

    /** 设备没有气压计。 */
    data object SensorMissing : FloorEstimate

    /** 有气压计但数据不可信（离所有楼层都太远）。 */
    data object Unreliable : FloorEstimate
}

/**
 * 楼层估算器：把“气压样本流 + 当前楼栋”变成“当前楼层”。
 *
 * 关键设计（对应技术方案第六节与风险对策）：
 *  1. **基准校准**：气压的绝对值受天气影响，不能硬编码。进入某栋楼时以当时的
 *     气压为“地面层基准”，之后只比较相对变化 —— 手机气压计普遍存在的绝对偏差
 *     因此被消掉，剩下的分辨率足够区分楼层（一层约 0.48 hPa）。
 *  2. **滑动平均**：在 core 的 FloorMatcher 里做，降低噪声。
 *  3. **滞后**：切换楼层需要“明显更近”，避免两层之间来回跳。
 *  4. **不硬猜**：样本不足或离所有楼层都太远时返回 [FloorEstimate.Unreliable]，
 *     由 UI 引导用户手动选楼层，而不是给一个错误答案。
 */
class FloorEstimator(
    private val matcher: FloorMatcher = FloorMatcher(),
) {

    private var currentBuildingId: String? = null
    private var lastFloor: Floor? = null

    /** 当前平滑气压（hPa），供重新定位后做基准校准用。 */
    val smoothedPressureHpa: Double? get() = matcher.smoothedPressureHpa

    /**
     * 当前推算的相对高度（米），基准来自用户校准的楼层。
     *
     * 供“我的 → 定位测试”直接展示，便于现场核对气压计读数是否合理。
     * 未校准或样本不足时为 null。
     */
    val estimatedHeightM: Double? get() = matcher.estimatedHeightM

    /** 设备是否有气压计（由上层注入，便于测试）。 */
    var sensorAvailable: Boolean = true

    /** 喂入一个气压样本，返回最新的楼层估算。 */
    fun onPressureSample(pressureHpa: Double, building: Building?): FloorEstimate {
        matcher.onPressureSample(pressureHpa)

        if (!sensorAvailable) return FloorEstimate.SensorMissing
        if (building == null) {
            // 还不知道在哪栋楼，无法确定楼层基准
            return FloorEstimate.Stabilizing
        }

        // 刚进楼 / 换了楼栋：以当前气压重新校准地面层基准
        if (building.id != currentBuildingId) {
            anchor(building, pressureHpa)
        }

        return estimate(building)
    }

    /**
     * 用指定楼层重新建立基准。
     *
     * 两种情况调用：
     *  - 用户手动改楼层（他要为系统提供真值）；
     *  - 用户点“重新定位”后，以当前楼层作为基准重新开始。
     */
    fun recalibrate(floor: Floor, pressureHpa: Double?) {
        val pressure = pressureHpa ?: return
        matcher.calibrate(floor, pressure)
        lastFloor = floor
        currentBuildingId = null
    }

    /** 切换楼栋时清空状态。 */
    fun switchBuilding(building: Building?) {
        currentBuildingId = building?.id
        lastFloor = null
        matcher.reset()
    }

    /** 清空全部状态（重新定位）。 */
    fun reset() {
        matcher.reset()
        currentBuildingId = null
        lastFloor = null
    }

    private fun anchor(building: Building, pressureHpa: Double) {
        currentBuildingId = building.id
        lastFloor = null
        val groundFloor = building.orderedFloors.firstOrNull() ?: return
        matcher.calibrate(groundFloor, pressureHpa)
    }

    private fun estimate(building: Building): FloorEstimate {
        // 未校准或样本还不够：明确告诉上层“正在稳定”，不要给一个可能是错的楼层
        if (!matcher.isCalibrated || !matcher.hasEnoughSamples) return FloorEstimate.Stabilizing

        val floors = building.orderedFloors
        val matched = matcher.matchFloor(floors, lastFloor?.id)
        return if (matched != null) {
            lastFloor = matched
            FloorEstimate.Known(matched)
        } else {
            // 有数据但离所有楼层都太远：气压不可信（电梯里、楼梯间、楼外）
            FloorEstimate.Unreliable
        }
    }
}

/**
 * 楼栋定位：用 GPS 点判断在哪栋楼，并对室内漂移做“保持上一次锁定”的兜底。
 *
 * 对应技术方案的两条要求：
 *  - 在哪个楼栋多边形内就锁定哪栋楼；
 *  - 室内 GPS 漂移时保持最后锁定的楼栋，不频繁跳楼栋。
 */
class BuildingLocator(
    /** 点落不到任何多边形时，允许“就近吸附”的最大距离（米）。 */
    private val snapDistanceMeters: Double = 60.0,
    /** 判定“真的移动了”的位移阈值（米），小于它认为还是原地抖动。 */
    private val movementThresholdMeters: Double = 12.0,
) {

    private var lockedBuilding: Building? = null
    private var lastAcceptedPoint: LngLat? = null

    /** 当前锁定的楼栋，未锁定时为 null。 */
    val locked: Building? get() = lockedBuilding

    /** 清空锁定状态（重新定位）。 */
    fun reset() {
        lockedBuilding = null
        lastAcceptedPoint = null
    }

    /**
     * 处理一个 GPS 点。
     *
     * @param point 原始定位点（GCJ-02）
     * @param inside 命中多边形的楼栋（由 CampusRepository.buildingAt 提供）
     * @param nearest 最近楼栋（用于吸附兜底）
     */
    fun onLocation(
        point: LngLat,
        inside: Building?,
        nearest: Building?,
    ): BuildingLocatorResult {
        if (inside != null) {
            lockedBuilding = inside
            lastAcceptedPoint = point
            return BuildingLocatorResult(inside, LockSource.Gps)
        }

        val current = lockedBuilding

        // 已锁定：只有在明显走远（说明真的离开了这栋楼）时才考虑换楼栋
        if (current != null) {
            val movedEnough = lastAcceptedPoint?.let {
                Geo.distanceMeters(it, point) >= movementThresholdMeters
            } ?: false
            if (!movedEnough) {
                return BuildingLocatorResult(current, LockSource.LastLocked)
            }
            // 走远了但没进任何多边形：如果另一个楼栋更近，就换；否则继续保持
            val candidate = nearest
            if (candidate != null && candidate.id != current.id) {
                val dCurrent = Geo.distanceMeters(point, Geo.centroid(current.polygon))
                val dCandidate = Geo.distanceMeters(point, Geo.centroid(candidate.polygon))
                if (dCandidate < dCurrent) {
                    lockedBuilding = candidate
                    lastAcceptedPoint = point
                    return BuildingLocatorResult(candidate, LockSource.Gps)
                }
            }
            lastAcceptedPoint = point
            return BuildingLocatorResult(current, LockSource.LastLocked)
        }

        // 尚未锁定：尝试就近吸附，避免“第一次定位就落在楼外一点点”导致完全没结果
        if (nearest != null) {
            val distance = Geo.distanceMeters(point, Geo.centroid(nearest.polygon))
            if (distance <= snapDistanceMeters) {
                lockedBuilding = nearest
                lastAcceptedPoint = point
                return BuildingLocatorResult(nearest, LockSource.Gps)
            }
        }

        return BuildingLocatorResult(null, LockSource.Gps)
    }

    /** 锁定结果的来源，用于 UI 文案。 */
    enum class LockSource { Gps, LastLocked }
}

/** [BuildingLocator.onLocation] 的结果。 */
data class BuildingLocatorResult(
    val building: Building?,
    val source: BuildingLocator.LockSource,
)
