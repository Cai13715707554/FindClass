package com.school.nav.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.school.nav.AppContainer
import com.school.nav.core.data.CampusRepository
import com.school.nav.core.data.SearchHit
import com.school.nav.core.model.Building
import com.school.nav.core.model.Element
import com.school.nav.core.model.Floor
import com.school.nav.core.model.LngLat
import com.school.nav.core.model.Position
import com.school.nav.core.model.Target
import com.school.nav.core.navigation.NavigationEngine
import com.school.nav.location.BuildingLocator
import com.school.nav.location.FloorEstimate
import com.school.nav.location.FloorEstimator
import com.school.nav.location.LocationAvailability
import com.school.nav.location.LocationSource
import com.school.nav.location.LocationUpdate
import com.school.nav.location.PressureSource
import com.school.nav.location.RawLocationFix
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页状态机。
 *
 * 职责边界（对应技术方案“状态层”）：
 *  - 保存当前楼栋、楼层、元素；
 *  - 保存目标；
 *  - 保存“手动修改”标志：手动优先，不被自动定位覆盖；
 *  - 保存气压基准；
 *  - 用户点“重新定位”后才重新采用 GPS 与气压计结果。
 *
 * 自动定位结果进 [autoBuilding]/[autoFloor]，手动修改进 [manual]，
 * 两者在 [uiState] 里按“手动优先”合并。导航结果是由 (位置, 目标) 纯函数派生出来的，
 * 所以任何一方变化都会立即重算 —— 这正是验收标准
 * “用户改完位置后，导航立即重算”的实现方式。
 */
class NavViewModel(
    initialRepository: CampusRepository,
    private val locationSource: LocationSource,
    private val altimeter: PressureSource,
    private val preferences: ManualPositionStore,
    private val engine: NavigationEngine = NavigationEngine(),
    private val buildingLocator: BuildingLocator = BuildingLocator(),
    private val floorEstimator: FloorEstimator = FloorEstimator(),
    /**
     * 仓库的更新流。
     *
     * 用户在设置页切换了配置文件之后，整个校园数据都换了 —— 首页必须跟着变，
     * 否则会出现「地图上是 A 学校、首页还在讲 B 学校」这种自相矛盾的状态。
     *
     * 默认 null 时退回一个只含 [initialRepository] 的常量流，这样单测可以直接传
     * 一个仓库对象，不必额外包一层 StateFlow。
     */
    repositoryUpdates: StateFlow<CampusRepository>? = null,
) : ViewModel() {

    /** 当前仓库。做成派生值，切换配置后所有读取都会拿到新数据。 */
    private val repositoryFlow: StateFlow<CampusRepository> =
        repositoryUpdates ?: MutableStateFlow(initialRepository)

    private val repository: CampusRepository get() = repositoryFlow.value

    // ---- 自动定位结果 ----
    private val autoBuilding = MutableStateFlow<Building?>(null)
    private val autoFloor = MutableStateFlow<Floor?>(null)
    private val autoFloorState = MutableStateFlow<FloorUiState>(FloorUiState.Stabilizing)
    private val autoSource = MutableStateFlow(CurrentSource.Unknown)

    // ---- 用户输入 ----
    private val manual = MutableStateFlow(ManualPosition.None)
    private val target = MutableStateFlow<Target?>(null)
    private val isLocating = MutableStateFlow(true)

    /** 最近一次已知经纬度，用于挑“离我最近的元素”。 */
    private val lastKnownPoint = MutableStateFlow<LngLat?>(null)

    /**
     * 定位是否当前不可用，以及原因。
     *
     * 非 null 表示「自动定位用不了，只能手动选位置」。这个状态必须能到达 UI，
     * 否则用户看到的是一个永远转圈的「定位中…」。
     */
    private val locationUnavailable = MutableStateFlow<LocationAvailability?>(null)

    /** 最近一次 GPS 原始经纬度，供“我的 → 定位测试”展示。 */
    private val positionPoint = MutableStateFlow<LngLat?>(null)

    /** 最近一次定位点的水平精度（米）与来源 provider，用于判断“这个点可不可信”。 */
    private val positionAccuracy = MutableStateFlow<Double?>(null)
    private val positionProvider = MutableStateFlow<String?>(null)

    /** 每次收到定位点都刷新，供定位测试页显示“最近更新时刻”，让实时性可见。 */
    private val positionUpdatedAt = MutableStateFlow(0L)

    /** 平滑后的气压读数（hPa），供定位测试页展示。 */
    private val pressureHpa = MutableStateFlow<Double?>(null)

    /** 本机是否有气压计，供定位测试页展示。 */
    private val barometerAvailable = MutableStateFlow(true)

    /** 推算的相对高度（米），供定位测试页展示。 */
    private val estimatedHeightM = MutableStateFlow<Double?>(null)

    private val toastFlow = MutableSharedFlow<ToastMessage>(extraBufferCapacity = 4)
    val toasts: Flow<ToastMessage> = toastFlow.asSharedFlow()

    private var toastSeq = 0L

    /** 自动定位这一组的结果，作为 [resolve] 的一个输入。 */
    private data class AutoLocate(
        val building: Building?,
        val floor: Floor?,
        val floorState: FloorUiState,
        val source: CurrentSource,
        val unavailable: LocationAvailability?,
        val locating: Boolean,
    )

    /**
     * 传感器原始读数分组，供“我的 → 定位测试”展示。
     *
     * 与 [AutoLocate] 分开：这些值不参与导航判断，只是给现场调试看的，
     * 混进业务状态会让 [resolve] 越来越难读。
     */
    private data class SensorReadings(
        val point: LngLat?,
        val accuracyMeters: Double?,
        val provider: String?,
        val updatedAtMillis: Long,
        val pressureHpa: Double?,
        val estimatedHeightM: Double?,
        val barometerAvailable: Boolean,
    )

    /**
     * 自动定位结果的聚合。
     *
     * 为什么不用 `combine`：这个派生状态需要 8 个输入，而 `combine` 最多 5 路；
     * 6 路以上只剩「接收 Array<Any?>」那种重载，实测会让类型推断失败
     * （Cannot infer type / Not enough information to infer type argument）。
     *
     * 因此这里直接手动把 6 个 StateFlow 收进一个 MutableStateFlow。
     * 每个来源都是 StateFlow，一定有当前值，所以合并结果不会「先给旧值」。
     */
    private val autoLocate: StateFlow<AutoLocate> = MutableStateFlow(
        AutoLocate(
            building = null,
            floor = null,
            floorState = FloorUiState.Stabilizing,
            source = CurrentSource.Unknown,
            unavailable = null,
            locating = true,
        ),
    ).also { merged ->
        viewModelScope.launch {
            combine(
                autoBuilding,
                autoFloor,
                autoFloorState,
                autoSource,
                locationUnavailable,
                isLocating,
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                AutoLocate(
                    building = values[0] as Building?,
                    floor = values[1] as Floor?,
                    floorState = values[2] as FloorUiState,
                    source = values[3] as CurrentSource,
                    unavailable = values[4] as LocationAvailability?,
                    locating = values[5] as Boolean,
                )
            }.collect { merged.value = it }
        }
    }

    /** 传感器原始读数：定位点信息 + 气压计信息，手动合并（与上面保持一致的写法）。 */
    private val sensorReadings: StateFlow<SensorReadings> = MutableStateFlow(
        SensorReadings(
            point = null,
            accuracyMeters = null,
            provider = null,
            updatedAtMillis = 0L,
            pressureHpa = null,
            estimatedHeightM = null,
            barometerAvailable = true,
        ),
    ).also { merged ->
        viewModelScope.launch {
            combine(
                positionPoint,
                positionAccuracy,
                positionProvider,
                positionUpdatedAt,
                pressureHpa,
                estimatedHeightM,
                barometerAvailable,
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                SensorReadings(
                    point = values[0] as LngLat?,
                    accuracyMeters = values[1] as Double?,
                    provider = values[2] as String?,
                    updatedAtMillis = values[3] as Long,
                    pressureHpa = values[4] as Double?,
                    estimatedHeightM = values[5] as Double?,
                    barometerAvailable = values[6] as Boolean,
                )
            }.collect { merged.value = it }
        }
    }

    val uiState: StateFlow<NavUiState> = combine(
        autoLocate,
        sensorReadings,
        manual,
        target,
        // 第 5 路：配置切换后重算 —— combine 最多 5 路，正好用满
        repositoryFlow,
    ) { auto, sensors, manualValue, targetValue, _ ->
        resolve(
            auto = auto,
            sensors = sensors,
            manualValue = manualValue,
            targetValue = targetValue,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = NavUiState(),
    )

    init {
        observePreferences()
        observeLocation()
        observePressure()
    }

    // ------------------------------------------------------------ 用户动作

    /**
     * 用户点“重新定位”。
     *
     * 这是唯一会重新采用自动结果的动作：清空手动修正、楼栋锁定与气压基准，
     * 重新等待 GPS 与气压计。对应验收标准“重新定位后状态恢复”。
     */
    fun relocate() {
        // 需要在协程外先判断：清空状态之后这个值会被置回 null
        val resubscribeLocation = locationUnavailable.value != null

        viewModelScope.launch {
            manual.value = ManualPosition.None
            autoBuilding.value = null
            autoFloor.value = null
            autoFloorState.value = FloorUiState.Stabilizing
            autoSource.value = CurrentSource.Unknown
            lastKnownPoint.value = null
            target.value = null
            isLocating.value = true
            // 用户主动要求重新定位：清掉上一次的“不可用”判定，重新等待流给出结论
            locationUnavailable.value = null
            buildingLocator.reset()
            floorEstimator.reset()
            preferences.clearManualPosition()
            toast("已重新定位")
        }
        // 定位与气压的订阅一直在跑的时候，这里只是清空状态、等下一个点重新锁定楼栋，
        // 不需要（也不能）重复订阅，否则会出现多次回调。
        //
        // 但如果之前判定过“定位不可用”，原订阅已经结束了（例如用户去系统设置
        // 重新打开了定位服务）—— 这时必须重新订阅，否则点“重新定位”永远不会恢复。
        if (resubscribeLocation) observeLocation()
    }

    /**
     * 用户在“修改位置”弹层里点确定。
     *
     * 手动优先：写入 [manual]（并持久化），同时把用户选的楼层作为气压基准，
     * 这样后续自动判断也会更准，但不会覆盖用户的选择。
     */
    fun applyManualPosition(building: Building, floor: Floor, element: Element) {
        val value = ManualPosition(
            buildingId = building.id,
            floorId = floor.id,
            elementId = element.id,
        )
        manual.value = value
        autoSource.value = CurrentSource.Manual
        // 用户已经手动给出位置：定位不可用的提示不再有意义，清掉以免位置卡片显示矛盾信息
        locationUnavailable.value = null
        floorEstimator.recalibrate(floor, floorEstimator.smoothedPressureHpa)

        viewModelScope.launch { preferences.saveManualPosition(value) }
        toast("位置已更新")
    }

    /** 按搜索结果设定目标。 */
    fun navigateTo(hit: SearchHit) {
        target.value = hit.toTarget()
    }

    /** 按关键字导航；找不到给 toast，不静默失败。 */
    fun navigateToName(name: String) {
        val hit = search(name).firstOrNull()
        if (hit == null) {
            toast("没找到这个位置")
            return
        }
        navigateTo(hit)
    }

    fun clearTarget() {
        target.value = null
    }

    /** 搜索候选，供输入框与结果列表使用。 */
    fun search(keyword: String): List<SearchHit> =
        repository.search(keyword, current = currentPosition(), limit = 8)

    /** 快捷目标。 */
    fun quickTargets(): List<SearchHit> = repository.quickTargets()

    /** 全部楼栋，供修改位置弹层使用。 */
    fun buildings(): List<Building> = repository.buildings

    /** 定位通道名称，用于界面说明。 */
    fun locationChannelName(): String = locationSource.channelName

    // ------------------------------------------------------------ 状态合并

    private fun resolve(
        auto: AutoLocate,
        sensors: SensorReadings,
        manualValue: ManualPosition,
        targetValue: Target?,
    ): NavUiState {
        // 手动修正优先于自动结果
        val manualBuilding = manualValue.buildingId?.let { repository.buildingById(it) }
        val usingManual = manualBuilding != null

        val building = manualBuilding ?: auto.building

        // 只有在“没有手动指定楼栋”时才允许自动楼层生效，避免出现
        // “手动选了 B 栋、楼层却还是 A 栋的 3 楼”这种自相矛盾的状态。
        val floor = when {
            usingManual -> manualValue.floorId?.let { manualBuilding?.floorById(it) }
            else -> auto.floor
        }

        val element = when {
            usingManual && floor != null -> manualValue.elementId?.let { floor.elementById(it) }
            else -> resolveNearestElement(building, floor)
        }

        val position = if (building != null && floor != null && element != null) {
            Position(building = building, floor = floor, element = element)
        } else {
            null
        }

        val source = when {
            usingManual -> CurrentSource.Manual
            auto.source == CurrentSource.LastLocked -> CurrentSource.LastLocked
            auto.source == CurrentSource.Unknown -> CurrentSource.Unknown
            else -> CurrentSource.Auto
        }

        return NavUiState(
            // 定位不可用时必须停掉 loading，否则界面会一直显示“定位中…”
            isLocating = auto.locating && building == null && auto.unavailable == null,
            building = building,
            floor = floor,
            element = element,
            floorState = if (usingManual && floor != null) {
                FloorUiState.Known(floor)
            } else {
                auto.floorState
            },
            source = source,
            locationChannel = locationSource.channelName,
            locationUnavailable = auto.unavailable,
            // 传感器原始读数：只用于“我的 → 定位测试”，不参与导航判断
            positionPoint = sensors.point,
            positionAccuracyMeters = sensors.accuracyMeters,
            positionProvider = sensors.provider,
            positionUpdatedAtMillis = sensors.updatedAtMillis,
            pressureHpa = sensors.pressureHpa,
            estimatedHeightM = sensors.estimatedHeightM,
            barometerAvailable = sensors.barometerAvailable,
            target = targetValue,
            route = resolveRoute(position, targetValue, floor),
            // 定位不可用时由位置卡片统一提示「为什么 + 怎么办」，
            // 不再让导航区域重复给一句手动兜底的提示，避免两处说法打架。
            needsManualFloor = floor == null && auto.unavailable == null,
        )
    }

    /**
     * 默认当前位置：当前楼层里离用户最近的元素。
     *
     * 产品文档要求“直接作为默认当前位置，不弹窗、不推荐、不打断”，
     * 因此这里静默取最近的一个；入口、楼梯口等不作为“人站的地方”参与选择。
     */
    private fun resolveNearestElement(building: Building?, floor: Floor?): Element? {
        if (building == null || floor == null) return null
        val point = lastKnownPoint.value ?: return floor.elements.firstOrNull()
        return repository.nearestStandableElement(floor, point) ?: floor.elements.firstOrNull()
    }
    private fun resolveRoute(
        position: Position?,
        targetValue: Target?,
        floor: Floor?,
    ): RouteUiState = when {
        targetValue == null -> RouteUiState.Empty
        position == null && floor == null -> RouteUiState.Loading
        position == null -> RouteUiState.Unavailable("还不知道你在几楼，请先手动选择楼层")
        else -> {
            val result = engine.route(position, targetValue)
            if (result.isArrived) RouteUiState.Arrived(targetValue) else RouteUiState.Ready(result)
        }
    }

    private fun currentPosition(): Position? {
        val state = uiState.value
        val b = state.building ?: return null
        val f = state.floor ?: return null
        val e = state.element ?: return null
        return Position(b, f, e)
    }

    // ------------------------------------------------------------ 数据订阅

    private fun observePreferences() {
        preferences.manualPosition
            .distinctUntilChanged()
            .onEach { stored ->
                // 首次读取时恢复持久化的手动修正。
                // 只在内存中还没有手动值时才写入，避免与用户刚做的修改打架。
                if (manual.value == ManualPosition.None && !stored.isEmpty) {
                    manual.value = stored
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * 订阅定位。
     *
     * 关键点：**定位不可用时必须把 [isLocating] 置回 false**。
     * 早期实现只在订阅前检查一次 `isAvailable()`，如果用户是在 App 运行中途去系统设置
     * 关掉定位，流会结束而 `isLocating` 永远停在 true，界面一直显示「定位中…」，
     * 用户既不知道出了什么事，也看不到「请手动选择位置」的提示。
     *
     * 现在流本身会发 [LocationUpdate.Unavailable]，并且流结束时也会兜底复查一次可用性。
     */
    private fun observeLocation() {
        val initial = locationSource.availability()
        if (initial != LocationAvailability.Available) {
            onLocationUnavailable(initial)
            return
        }

        locationSource.locationUpdates()
            .onCompletion {
                // 流结束（例如用户关掉定位服务导致 provider 被移除）时兜底复查，
                // 避免界面卡在「定位中…」。
                val reason = locationSource.availability()
                if (reason != LocationAvailability.Available) onLocationUnavailable(reason)
            }
            .onEach { update ->
                when (update) {
                    is LocationUpdate.Unavailable -> onLocationUnavailable(update.reason)
                    is LocationUpdate.Fix -> onFix(update.fix)
                }
            }
            .launchIn(viewModelScope)
    }

    /** 定位不可用：停掉 loading，把原因交给 UI，用户仍可手动完成导航。 */
    private fun onLocationUnavailable(reason: LocationAvailability) {
        isLocating.value = false
        autoSource.value = CurrentSource.Unknown
        if (locationUnavailable.value == null) {
            // 只提示一次，避免 provider 反复开关时刷屏
            toast(unavailableToast(reason))
        }
        locationUnavailable.value = reason
    }

    private fun onFix(fix: RawLocationFix) {
        locationUnavailable.value = null
        lastKnownPoint.value = fix.point
        // 同步给“定位测试”页展示的原始经纬度，并记下精度/来源/时刻
        positionPoint.value = fix.point
        positionAccuracy.value = fix.accuracyMeters
        positionProvider.value = fix.provider
        positionUpdatedAt.value = System.currentTimeMillis()
        val inside = repository.buildingAt(fix.point)
        val nearest = repository.nearestBuilding(fix.point)
        val result = buildingLocator.onLocation(fix.point, inside, nearest)

        val locked = result.building
        if (locked != null) {
            if (autoBuilding.value?.id != locked.id) {
                // 换了楼栋：楼层与气压基准都要重来
                autoBuilding.value = locked
                autoFloor.value = null
                autoFloorState.value = FloorUiState.Stabilizing
                floorEstimator.switchBuilding(locked)
            }
            autoSource.value =
                if (result.source == BuildingLocator.LockSource.LastLocked) {
                    CurrentSource.LastLocked
                } else {
                    CurrentSource.Auto
                }
        }
        isLocating.value = false
    }

    private fun unavailableToast(reason: LocationAvailability): String = when (reason) {
        LocationAvailability.PermissionDenied -> "没有定位权限，请手动选择当前位置"
        LocationAvailability.ServiceDisabled -> "定位服务已关闭，请手动选择当前位置"
        LocationAvailability.NoProvider -> "本机无法定位，请手动选择当前位置"
        LocationAvailability.Available -> "定位不可用，请手动选择当前位置"
    }

    private fun observePressure() {
        if (!altimeter.hasSensor) {
            floorEstimator.sensorAvailable = false
            barometerAvailable.value = false
            autoFloorState.value = FloorUiState.SensorMissing
            return
        }
        floorEstimator.sensorAvailable = true
        barometerAvailable.value = true

        altimeter.pressureUpdates()
            .onEach { pressure ->
                val estimate = floorEstimator.onPressureSample(
                    pressureHpa = pressure.toDouble(),
                    building = autoBuilding.value,
                )
                // 平滑后的气压与推算高度，供“定位测试”页展示
                // （原始气压噪声大，页面要的是一个稳定可读的数字）
                pressureHpa.value = floorEstimator.smoothedPressureHpa
                estimatedHeightM.value = floorEstimator.estimatedHeightM
                when (estimate) {
                    is FloorEstimate.Known -> {
                        autoFloor.value = estimate.floor
                        autoFloorState.value = FloorUiState.Known(estimate.floor)
                    }

                    FloorEstimate.Stabilizing ->
                        autoFloorState.value = FloorUiState.Stabilizing

                    FloorEstimate.SensorMissing ->
                        autoFloorState.value = FloorUiState.SensorMissing

                    FloorEstimate.Unreliable ->
                        autoFloorState.value = FloorUiState.Unreliable
                }
            }
            .launchIn(viewModelScope)
    }

    private fun toast(text: String) {
        toastSeq += 1
        toastFlow.tryEmit(ToastMessage(toastSeq, text))
    }

    companion object {
        /**
         * 手写 ViewModel 工厂。
         *
         * MVP 只有一个 ViewModel，比引入 Hilt 更轻；后续 ViewModel 变多再上 DI 框架。
         */
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(NavViewModel::class.java)) {
                        "未知的 ViewModel：${modelClass.name}"
                    }
                    return NavViewModel(
                        initialRepository = container.repository,
                        locationSource = container.locationSource,
                        altimeter = container.altimeter,
                        preferences = container.preferences,
                        // 切换配置文件后首页要跟着换数据
                        repositoryUpdates = container.repositoryFlow,
                    ) as T
                }
            }
    }
}
