package com.school.nav.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.school.nav.AppContainer
import com.school.nav.core.data.CampusRepository
import com.school.nav.core.data.EditorBuilding
import com.school.nav.core.data.EditorConfig
import com.school.nav.core.data.EditorElement
import com.school.nav.core.data.EditorMode
import com.school.nav.core.data.FloorDraft
import com.school.nav.core.model.LngLat
import com.school.nav.data.ApiKeyStore
import com.school.nav.data.EditorConfigStore
import com.school.nav.data.SaveResult
import com.school.nav.location.LocationAvailability
import com.school.nav.location.LocationSource
import com.school.nav.location.LocationUpdate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** 一次「把地图移到某处」的请求。用序号做 token，保证同一个坐标也能重复触发。 */
data class CenterRequest(
    val token: Long,
    val point: LngLat,
    val zoom: Float,
)

/**
 * 楼层数上限，避免用户手滑输入 99999 把层号选择器撑爆。
 *
 * 放成文件级常量而不是 companion 里的成员：这样状态类和 ViewModel 都能直接用，
 * 不会出现「同一个常量要在两处引用」的作用域麻烦。
 */
const val MAX_FLOOR_COUNT: Int = 100

/**
 * 拖拽矩形的两条边最短要多少度（约 1e-5 度 ≈ 1 米）。
 *
 * 低于这个值说明用户只是点了一下、没真拖开 —— 那种情况下应该提示而不是
 * 存一个针尖大的图形进去。也顺便过滤掉手指抖动引起的误触。
 */
private const val MIN_SPAN_DEGREES = 1e-5

/** 编辑器界面状态。 */
data class EditorUiState(
    val hasApiKey: Boolean = false,
    val apiKey: String = "",
    /** 高德「Web服务」Key：POI 模糊搜索用；没填时搜索退回 Android Geocoder。 */
    val webKey: String = "",

    // ---- 绘制模式 ----
    val mode: EditorMode = EditorMode.Building,
    /** 当前正在画哪栋楼的楼层元素；教学楼模式下用 [draftName] 新建/覆盖。 */
    val targetBuildingId: String? = null,
    /** 楼层内元素画在第几层（跨层元素是起始层）。 */
    val floorLevel: Int = 1,
    /**
     * 跨层元素的结束层。
     *
     * 只有楼梯这类会穿过楼板的元素才用得到：它不属于单独某一层，
     * 所以让用户选「起始层 → 结束层」，合并时在每一层都生成一份同名楼梯。
     */
    val endFloorLevel: Int = 1,

    // ---- 草稿 ----
    /** 拖拽预览：当前图形（矩形四个角 / 单点一个点）。 */
    val draftPoints: List<LngLat> = emptyList(),
    /** 拖拽起点；非 null 表示正在拖。 */
    val dragStart: LngLat? = null,
    val draftName: String = "",
    /** 新建楼栋时填的楼层数，成面后落到 [EditorBuilding.floorCount]。 */
    val draftFloorCount: Int = com.school.nav.core.data.EditorBuilding.DEFAULT_FLOOR_COUNT,

    // ---- 成果 ----
    val buildings: List<EditorBuilding> = emptyList(),
    /** 上一次保存的结果（含内部/外部真实路径），用于界面展示「存到哪了」。 */
    val lastSave: SaveResult? = null,

    // ---- 定位 ----
    val myLocation: LngLat? = null,
    val locationUnavailable: LocationAvailability? = null,
    /** 请求地图移动到某处；地图消费后由 [MapEditorViewModel.consumeCenterRequest] 清空。 */
    val centerRequest: CenterRequest? = null,
) {
    /** 顶点够不够围成一个面。 */
    val canFinishDraft: Boolean get() = draftPoints.size >= MIN_POLYGON_POINTS

    fun building(id: String?): EditorBuilding? = buildings.firstOrNull { it.id == id }

    /** 当前选中的目标楼栋。 */
    val targetBuilding: EditorBuilding? get() = building(targetBuildingId)

    /** 当前模式下能否落笔：楼层元素必须先有目标楼栋。 */
    val canDraw: Boolean
        get() = if (mode.needsBuilding) targetBuilding != null else true

    /** 当前模式是否是跨层元素（楼梯），需要用户选择起始层与结束层。 */
    val isCrossFloorMode: Boolean get() = mode.elementType == com.school.nav.core.model.ElementType.Stair

    /** 目标楼栋的可选层号。 */
    val floorOptions: IntRange get() = targetBuilding?.levelRange ?: (1..1)

    companion object {
        const val MIN_POLYGON_POINTS = 3
    }
}

/**
 * 地图编辑器的状态机。
 *
 * 职责：
 *  - 管理绘制模式、目标楼栋、楼层、正在画的顶点；
 *  - 把结果保存成配置文件（内部 + 外部双写，实现在 [EditorConfigStore]）；
 *  - 转发定位，供「回到我的位置」用。
 *
 * **刻意不依赖高德 SDK**：地图渲染在 `AmapEditorView` 里，通过回调把「用户点了哪」
 * 传进来。绘制逻辑（增删顶点、成面、命名、存盘、合并楼层）因此能在纯 JVM 上单测。
 */
class MapEditorViewModel(
    private val configStore: EditorConfigStore,
    private val apiKeyStore: ApiKeyStore,
    repository: CampusRepository,
    private val locationSource: LocationSource? = null,
    /**
     * 是否允许使用匿名地点搜索。
     *
     * 搜索走 Android 内置 [android.location.Geocoder]，不需要额外 Key，
     * 但精度与覆盖不如高德 Web 服务。这里保留开关是为了在 Geocoder 不可用的
     * 设备上直接隐藏搜索框，而不是给用户一个点了没反应的输入框。
     */
    val searchAvailable: Boolean = true,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        EditorUiState(
            hasApiKey = apiKeyStore.hasAmapKey(),
            apiKey = apiKeyStore.amapKey(),
            webKey = apiKeyStore.amapWebKey(),
            buildings = configStore.load().buildings,
        ),
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    /** 已有（内置 + 已保存）楼栋，供地图上画灰色参考。 */
    val referenceBuildings = MutableStateFlow(repository.buildings)

    private val messageFlow = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = messageFlow.asSharedFlow()

    private var centerToken = 0L

    init {
        startLocating()
    }

    // ------------------------------------------------------------ 模式

    /** 切换绘制模式。切换会放弃当前草稿的顶点，避免「上一模式的点」混进来。 */
    fun setMode(mode: EditorMode) {
        val state = _uiState.value
        if (state.mode == mode) return
        _uiState.value = state.copy(
            mode = mode,
            draftPoints = emptyList(),
            // 教学楼模式用名字新建，其余模式必须挂在某栋楼上
            targetBuildingId = if (mode.needsBuilding) state.targetBuildingId else null,
        )
    }

    /** 选一个已存在的楼栋作为楼层元素的宿主。 */
    fun setTargetBuilding(id: String?) {
        _uiState.value = _uiState.value.copy(targetBuildingId = id, draftPoints = emptyList())
    }

    fun setFloorLevel(level: Int) {
        val coerced = level.coerceAtLeast(1)
        // 起始层不能超过结束层，否则跨层元素会算出反的区间
        _uiState.value = _uiState.value.copy(
            floorLevel = coerced,
            endFloorLevel = maxOf(coerced, _uiState.value.endFloorLevel),
        )
    }

    /** 设置跨层元素的结束层，不允许低于起始层。 */
    fun setEndFloorLevel(level: Int) {
        val state = _uiState.value
        _uiState.value = state.copy(endFloorLevel = level.coerceIn(state.floorLevel, Int.MAX_VALUE))
    }

    /** 修改某栋楼的名义楼层数。 */
    fun setBuildingFloorCount(id: String, count: Int) {
        val safe = count.coerceIn(1, MAX_FLOOR_COUNT)
        _uiState.value = _uiState.value.copy(
            buildings = _uiState.value.buildings.map {
                if (it.id == id) it.copy(floorCount = safe) else it
            },
        )
    }

    fun setDraftName(name: String) {
        _uiState.value = _uiState.value.copy(draftName = name)
    }

    /** 新建楼栋时填的楼层数。 */
    fun setDraftFloorCount(count: Int) {
        _uiState.value = _uiState.value.copy(
            draftFloorCount = count.coerceIn(1, MAX_FLOOR_COUNT),
        )
    }

    // ------------------------------------------------------------ 绘制

    /**
     * 点了一下地图（没有拖动）。
     *
     * 单点元素（楼梯口 / 卫生间）：**直接落点并结束** —— 点一下就是它，
     * 不需要拖也不需要命名弹窗，再弹个窗反而啰嗦。
     *
     * 非单点元素：不落点，只提示要按住拖动框选范围。这是用户明确要求的
     * 「别让人靠点三个点来划定位置」：逐点点选又慢又难对齐。
     */
    fun onTap(point: LngLat) {
        val state = _uiState.value
        if (!state.canDraw) {
            emit("请先选定要画在哪栋楼，再开始绘制")
            return
        }
        if (!state.mode.isPoint) {
            emit("按住拖动框出范围（不用逐点点选）")
            return
        }
        commitPoints(points = listOf(point), name = state.draftName)
    }

    /** 拖拽开始：记下起点。 */
    fun onDragStart(point: LngLat) {
        val state = _uiState.value
        if (!state.canDraw || state.mode.isPoint) return
        _uiState.value = state.copy(dragStart = point, draftPoints = listOf(point))
    }

    /**
     * 拖拽中：按「起点 → 当前点」实时刷新矩形预览。
     *
     * 预览直接写进 draftPoints（四个角），地图那层不需要知道有「拖拽」这回事，
     * 照常把 draftPoints 画成多边形就行。
     */
    fun onDragUpdate(point: LngLat) {
        val state = _uiState.value
        val start = state.dragStart ?: return
        _uiState.value = state.copy(
            draftPoints = EditorBuilding.rectangleFromCorners(start, point),
        )
    }

    /** 拖拽结束：把矩形定下来并走正常的成面流程。 */
    fun onDragEnd(point: LngLat) {
        val state = _uiState.value
        val start = state.dragStart ?: return
        val rect = EditorBuilding.rectangleFromCorners(start, point)

        if (spanTooSmall(rect)) {
            // 只是点了一下、没真正拖开：清掉预览并提示，别留下针尖大的图形
            _uiState.value = state.copy(draftPoints = emptyList(), dragStart = null)
            emit("拖动范围太小，按住往对角方向拖出一片区域")
            return
        }

        _uiState.value = state.copy(draftPoints = rect, dragStart = null)
        finishDraft()
    }

    /** 放弃当前预览。 */
    fun cancelDraft() {
        _uiState.value = _uiState.value.copy(draftPoints = emptyList(), dragStart = null)
    }

    /**
     * 结束当前图形。
     *
     * 两种落点：
     *  - 教学楼模式：轮廓挂到「同名楼栋」上（不存在就新建）；
     *  - 楼层元素模式：挂到目标楼栋的目标楼层。
     */
    fun finishDraft() {
        val state = _uiState.value
        if (!state.canFinishDraft) {
            emit(
                if (state.mode.isPoint) {
                    "在地图上点一下放置${state.mode.label}"
                } else {
                    "至少需要 ${EditorUiState.MIN_POLYGON_POINTS} 个点才能围成一个面"
                },
            )
            return
        }

        if (state.mode == EditorMode.Building) {
            finishBuildingOutline(state)
        } else {
            finishFloorElement(state)
        }
    }

    /**
     * 落一个点就完成的元素（楼梯口 / 卫生间）。
     *
     * 这两个类型没有确定边界，画矩形纯属白费功夫，导航也只需要知道它「在哪」。
     * 楼梯还会带上「结束楼层」表示跨几层。
     */
    private fun commitPoints(points: List<LngLat>, name: String) {
        val state = _uiState.value
        val target = state.targetBuilding ?: run {
            emit("请先选定要画在哪栋楼")
            return
        }
        val mode = state.mode
        val label = name.trim().ifBlank { mode.label }

        val existingElements = target.floor(state.floorLevel)?.elements.orEmpty()
        val element = EditorElement.from(
            mode = mode,
            id = generateElementId(mode, label, existingElements),
            name = label,
            points = points,
            toLevel = if (state.isCrossFloorMode) state.endFloorLevel else null,
        )

        _uiState.value = state.copy(
            buildings = state.buildings.map {
                if (it.id != target.id) it else it.withAddedElement(state.floorLevel, element)
            },
            draftPoints = emptyList(),
            dragStart = null,
            draftName = "",
        )
        emit("已放置「$label」在 ${target.name} ${state.floorLevel} 楼")
    }

    /** 矩形面积太小就认为用户只是想点一下、没真拖。 */
    private fun spanTooSmall(rect: List<LngLat>): Boolean {
        if (rect.size < 4) return true
        val minLng = rect.minOf { it.lng }
        val maxLng = rect.maxOf { it.lng }
        val minLat = rect.minOf { it.lat }
        val maxLat = rect.maxOf { it.lat }
        return (maxLng - minLng) < MIN_SPAN_DEGREES || (maxLat - minLat) < MIN_SPAN_DEGREES
    }

    private fun finishBuildingOutline(state: EditorUiState) {
        val name = state.draftName.trim().ifBlank { "未命名楼栋" }
        val existing = state.buildings.firstOrNull { it.name == name }

        val updated = if (existing != null) {
            // 同名视为「重画轮廓」，保留它已有的楼层；楼层数按这次填的更新
            state.buildings.map {
                if (it.id == existing.id) {
                    it.copy(polygon = state.draftPoints, floorCount = state.draftFloorCount)
                } else {
                    it
                }
            }
        } else {
            val id = generateBuildingId(name, state.buildings)
            state.buildings + EditorBuilding(
                id = id,
                name = name,
                polygon = state.draftPoints,
                floorCount = state.draftFloorCount,
            )
        }

        _uiState.value = state.copy(
            buildings = updated,
            draftPoints = emptyList(),
            draftName = "",
        )
        emit(if (existing != null) "已更新「$name」的外轮廓" else "已添加「$name」，记得点保存")
    }

    private fun finishFloorElement(state: EditorUiState) {
        val target = state.targetBuilding ?: run {
            emit("请先选定要画在哪栋楼")
            return
        }
        val mode = state.mode
        val name = state.draftName.trim().ifBlank { mode.label }

        // target 已经过非空检查，后续都用它而不是再读一次 state.targetBuilding ——
        // 读属性会丢掉智能转换，编译器只能当成可空类型
        val existingElements = target.floor(state.floorLevel)?.elements.orEmpty()
        val element = EditorElement.from(
            mode = mode,
            id = generateElementId(mode, name, existingElements),
            name = name,
            points = state.draftPoints,
            toLevel = if (state.isCrossFloorMode) state.endFloorLevel else null,
        )

        _uiState.value = state.copy(
            buildings = state.buildings.map {
                if (it.id == target.id) it.withAddedElement(state.floorLevel, element) else it
            },
            draftPoints = emptyList(),
            dragStart = null,
            draftName = "",
        )
        emit(
            if (element.isCrossFloor) {
                "已在 ${target.name} 添加「$name」（${state.floorLevel}→${element.toLevel} 楼，每层都会生成）"
            } else {
                "已在 ${target.name} ${state.floorLevel} 楼添加「$name」"
            },
        )
    }

    /** 删掉一栋楼（连带它的楼层元素）。 */
    fun removeBuilding(id: String) {
        _uiState.value = _uiState.value.copy(
            buildings = _uiState.value.buildings.filterNot { it.id == id },
            targetBuildingId = if (_uiState.value.targetBuildingId == id) null else _uiState.value.targetBuildingId,
        )
    }

    /** 删掉某个元素。 */
    fun removeElement(buildingId: String, level: Int, elementId: String) {
        _uiState.value = _uiState.value.copy(
            buildings = _uiState.value.buildings.map { building ->
                if (building.id != buildingId) {
                    building
                } else {
                    building.copy(
                        floors = building.floors.map { floor ->
                            if (floor.level != level) {
                                floor
                            } else {
                                floor.copy(elements = floor.elements.filterNot { it.id == elementId })
                            }
                        },
                    )
                }
            },
        )
    }

    fun clearAll() {
        _uiState.value = _uiState.value.copy(
            buildings = emptyList(),
            draftPoints = emptyList(),
            targetBuildingId = null,
        )
    }

    // ------------------------------------------------------------ 存盘

    fun save() {
        val buildings = _uiState.value.buildings
        if (buildings.isEmpty()) {
            emit("还没有画好的楼栋，先在图上点几个点围成轮廓")
            return
        }
        val result = configStore.save(buildings)
        _uiState.value = _uiState.value.copy(lastSave = result)
        emit(
            buildString {
                append("已保存 ${buildings.size} 栋")
                result.internalPath?.let { append("\n内部：$it") }
                result.externalPath?.let { append("\n外部：$it") }
                result.warnings.forEach { append("\n⚠ $it") }
            },
        )
    }

    fun reload() {
        val config: EditorConfig = configStore.load()
        _uiState.value = _uiState.value.copy(
            buildings = config.buildings,
            targetBuildingId = null,
            draftPoints = emptyList(),
        )
        emit("已从配置文件重新加载 ${config.buildings.size} 栋")
    }

    // ------------------------------------------------------------ 定位与搜索

    private fun startLocating() {
        val source = locationSource ?: return
        val availability = source.availability()
        if (availability != LocationAvailability.Available) {
            _uiState.value = _uiState.value.copy(locationUnavailable = availability)
            return
        }
        source.locationUpdates()
            .onEach { update ->
                when (update) {
                    is LocationUpdate.Unavailable ->
                        _uiState.value = _uiState.value.copy(locationUnavailable = update.reason)

                    is LocationUpdate.Fix -> {
                        val point = update.fix.point
                        val first = _uiState.value.myLocation == null
                        _uiState.value = _uiState.value.copy(
                            myLocation = point,
                            locationUnavailable = null,
                            // 首次拿到定位时自动把地图移过去
                            centerRequest = if (first) nextCenter(point, ZOOM_STREET) else _uiState.value.centerRequest,
                        )
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    /** 「回到我的位置」。 */
    fun goToMyLocation() {
        val point = _uiState.value.myLocation
        if (point == null) {
            emit(
                when (_uiState.value.locationUnavailable) {
                    null -> "还没取到定位，稍后再试或直接用搜索"
                    else -> "定位不可用，请用搜索框找地点"
                },
            )
            return
        }
        _uiState.value = _uiState.value.copy(centerRequest = nextCenter(point, ZOOM_STREET))
    }

    /** 搜索到某个地点后把地图移过去。 */
    fun goTo(point: LngLat, zoom: Float = ZOOM_STREET) {
        _uiState.value = _uiState.value.copy(centerRequest = nextCenter(point, zoom))
    }

    /** 地图消费完居中请求后调用，避免每次重组都重置相机。 */
    fun consumeCenterRequest(token: Long) {
        if (_uiState.value.centerRequest?.token == token) {
            _uiState.value = _uiState.value.copy(centerRequest = null)
        }
    }

    private fun nextCenter(point: LngLat, zoom: Float): CenterRequest {
        centerToken += 1
        return CenterRequest(token = centerToken, point = point, zoom = zoom)
    }

    // ------------------------------------------------------------ 高德 Key

    fun saveApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            emit("Key 不能为空")
            return
        }
        apiKeyStore.saveAmapKey(trimmed)
        _uiState.value = _uiState.value.copy(hasApiKey = true, apiKey = trimmed)
        emit("已保存高德 Key")
    }

    fun clearApiKey() {
        apiKeyStore.clearAmapKey()
        _uiState.value = _uiState.value.copy(hasApiKey = false, apiKey = "")
        emit("已清除高德 Key")
    }

    /** 保存 Web服务 Key（POI 搜索用）。 */
    fun saveWebKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            emit("Web服务 Key 不能为空")
            return
        }
        apiKeyStore.saveAmapWebKey(trimmed)
        _uiState.value = _uiState.value.copy(webKey = trimmed)
        emit("已保存 Web服务 Key，搜索将使用高德 POI 模糊匹配")
    }

    fun clearWebKey() {
        apiKeyStore.clearAmapWebKey()
        _uiState.value = _uiState.value.copy(webKey = "")
        emit("已清除 Web服务 Key，搜索退回系统地理编码")
    }

    /** 设置页返回后刷新 Key 状态（Key 可能在设置页里被改过）。 */
    fun refreshApiKey() {
        _uiState.value = _uiState.value.copy(
            hasApiKey = apiKeyStore.hasAmapKey(),
            apiKey = apiKeyStore.amapKey(),
            webKey = apiKeyStore.amapWebKey(),
        )
    }

    // ------------------------------------------------------------ 内部

    /**
     * 生成楼栋 id。
     *
     * 与已有楼栋重名时加后缀 —— id 相同会被判定为「覆盖同一栋楼」，
     * 用户画一栋新「A栋」却把内置 A 栋的楼层覆盖掉，是个很难排查的坑。
     */
    private fun generateBuildingId(name: String, existing: List<EditorBuilding>): String {
        val taken = (existing.map { it.id } + referenceBuildings.value.map { it.id }).toSet()
        return uniqueId("editor-" + name.slug(), taken)
    }

    /** 生成元素 id，只在本楼本层内唯一即可。 */
    private fun generateElementId(
        mode: EditorMode,
        name: String,
        existing: List<EditorElement>,
    ): String = uniqueId(
        base = "${mode.name.lowercase()}-${name.slug()}",
        taken = existing.map { it.id }.toSet(),
    )

    private fun uniqueId(base: String, taken: Set<String>): String {
        val safe = base.ifBlank { "item" }
        if (safe !in taken) return safe
        var n = 2
        while ("$safe-$n" in taken) n++
        return "$safe-$n"
    }

    private fun String.slug(): String = filter { it.isLetterOrDigit() }

    private fun emit(text: String) {
        messageFlow.tryEmit(text)
    }

    companion object {
        /** 街道级缩放，画楼栋够用。 */
        const val ZOOM_STREET = 17f

        /** 搜索结果跳转用的缩放。 */
        const val ZOOM_SEARCH = 16f

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(MapEditorViewModel::class.java)) {
                        "未知的 ViewModel：${modelClass.name}"
                    }
                    return MapEditorViewModel(
                        configStore = container.editorConfigStore,
                        apiKeyStore = container.apiKeyStore,
                        repository = container.repository,
                        locationSource = container.locationSource,
                    ) as T
                }
            }
    }
}
