package com.school.nav.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.school.nav.AppContainer
import com.school.nav.core.data.CampusRepository
import com.school.nav.core.data.EditorBuilding
import com.school.nav.core.data.EditorElement
import com.school.nav.core.data.EditorMode
import com.school.nav.core.data.ElementShape
import com.school.nav.core.model.Geo
import com.school.nav.core.model.LngLat
import com.school.nav.data.ApiKeyStore
import com.school.nav.data.ConfigEntry
import com.school.nav.data.ConfigStore
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
 * 放成文件级常量而不是 companion 里的成员：这样状态类与 ViewModel 都能直接用。
 */
const val MAX_FLOOR_COUNT: Int = 100

/** 撤销栈的深度上限。够用又不至于把内存撑爆。 */
const val MAX_HISTORY: Int = 50

/**
 * 正在编辑哪个图形。
 *
 * 用于「选中的图形」这一类操作：拖顶点、插点、旋转都作用在它身上。
 */
data class ShapeSelection(
    /** 楼栋 id。 */
    val buildingId: String,
    /** 楼层内元素的 id；为 null 表示选中的是楼栋外轮廓。 */
    val elementId: String?,
    /** 元素所在楼层（选楼栋轮廓时为 null）。 */
    val level: Int?,
)

/** 编辑器界面状态。 */
data class EditorUiState(
    val hasApiKey: Boolean = false,
    val apiKey: String = "",
    /** 高德「Web服务」Key：POI 模糊搜索用；没填时搜索退回 Android Geocoder。 */
    val webKey: String = "",

    // ---- 绘制 ----
    val mode: EditorMode = EditorMode.Building,
    /** 楼层内元素画在哪个楼栋里。 */
    val targetBuildingId: String? = null,
    /** 楼层内元素画在第几层（跨层元素是起始层）。 */
    val floorLevel: Int = 1,
    /** 跨层元素的结束层（楼梯用）。 */
    val endFloorLevel: Int = 1,

    /** 拖拽预览：矩形四个角 / 圆的多边形 / 单点一个点。 */
    val draftPoints: List<LngLat> = emptyList(),
    /** 拖拽起点；非 null 表示正在拖。 */
    val dragStart: LngLat? = null,
    val draftName: String = "",
    /** 新建楼栋时填的楼层数。 */
    val draftFloorCount: Int = EditorBuilding.DEFAULT_FLOOR_COUNT,

    // ---- 成果 ----
    val buildings: List<EditorBuilding> = emptyList(),
    val lastSave: SaveResult? = null,

    // ---- 编辑已有图形 ----
    /** 是否处于「编辑形状」模式（可拖顶点、插点、旋转）。 */
    val editMode: Boolean = false,
    /** 当前选中的图形。 */
    val selection: ShapeSelection? = null,
    /**
     * 编辑模式下最近一次点击的位置。
     *
     * 「加顶点 / 删顶点」这两个按钮本身不知道用户想操作哪儿，
     * 用最近点击处是唯一合理的解释（用户刚点了那里）。
     */
    val editAnchor: LngLat? = null,

    // ---- 撤销 ----
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,

    // ---- 配置（一个学校一份） ----
    val configs: List<ConfigEntry> = emptyList(),
    val activeConfigName: String = "",

    // ---- 定位 ----
    val myLocation: LngLat? = null,
    val locationUnavailable: LocationAvailability? = null,
    val centerRequest: CenterRequest? = null,
) {
    /** 顶点够不够围成一个面。 */
    val canFinishDraft: Boolean get() = draftPoints.size >= MIN_POLYGON_POINTS

    fun building(id: String?): EditorBuilding? = buildings.firstOrNull { it.id == id }

    val targetBuilding: EditorBuilding? get() = building(targetBuildingId)

    /** 当前模式下能否落笔：楼层元素必须先有目标楼栋。 */
    val canDraw: Boolean
        get() = if (mode.needsBuilding) targetBuilding != null else true

    /** 当前模式是否是跨层元素（楼梯），需要选起始层与结束层。 */
    val isCrossFloorMode: Boolean get() = mode.elementType == com.school.nav.core.model.ElementType.Stair

    /**
     * 当前要不要装「按下 → 拖动 → 抬起」这套手势。
     *
     * `AmapEditorView` 里这两条路是**互斥**的：
     *  - 装了拖拽监听，就**收不到**地图点击回调（`setOnMapClickListener(null)`）；
     *  - 不装，就只有点击回调。
     *
     * 所以这个属性决定了「哪些模式还能点」：
     *  - 矩形 / 圆：要拖，不能点 → true
     *  - 单点（楼梯口 / 卫生间）：点一下落点 → false
     *  - 钢笔（自由多边形 / 自由轮廓）：连续点，**每次点击就是落一个顶点** → false
     *    （如果这里判成 true，钢笔就永远收不到点击，画不出任何东西）
     *  - 编辑模式：拖顶点 / 拖整体 → true
     */
    val usesDragGesture: Boolean
        get() = editMode || (!mode.isPoint && !mode.isPen)

    /** 目标楼栋的可选层号。 */
    val floorOptions: IntRange get() = targetBuilding?.levelRange ?: (1..1)

    /** 选中的那个图形（楼栋轮廓或楼层元素）。 */
    val selectedShape: SelectedShape?
        get() {
            val sel = selection ?: return null
            val building = building(sel.buildingId) ?: return null
            if (sel.elementId == null) {
                if (!building.hasValidPolygon) return null
                return SelectedShape(building.id, null, null, building.name, building.polygon)
            }
            val level = sel.level ?: return null
            val element = building.floor(level)?.elements?.firstOrNull { it.id == sel.elementId }
                ?: return null
            return SelectedShape(building.id, element.id, level, element.name, element.points)
        }

    companion object {
        const val MIN_POLYGON_POINTS = 3
    }
}

/** 选中的图形，供 UI 画顶点手柄、也供 ViewModel 做变换。 */
data class SelectedShape(
    val buildingId: String,
    val elementId: String?,
    val level: Int?,
    val name: String,
    val points: List<LngLat>,
) {
    val isBuildingOutline: Boolean get() = elementId == null
}

/**
 * 地图编辑器的状态机。
 *
 * 职责：
 *  - 管理绘制模式、目标楼栋、楼层、拖拽预览；
 *  - **撤销 / 重做**：所有改动先入历史再落地，所以连已写进配置的楼栋也能撤回来；
 *  - **多配置**：一个学校一份配置，能列出 / 新建 / 复制 / 重命名 / 删除 / 切换；
 *  - 编辑已有图形：拖顶点、边上插点、旋转；
 *  - 定位。
 *
 * **刻意不依赖高德 SDK**：地图渲染在 `AmapEditorView` 里，通过回调把
 * 「用户点了哪 / 拖到哪」传进来。绘制与编辑逻辑因此能在纯 JVM 上单测。
 */
class MapEditorViewModel(
    private val configStore: ConfigStore,
    private val apiKeyStore: ApiKeyStore,
    private val locationSource: LocationSource? = null,
    private val onDataChanged: () -> Unit = {},
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        EditorUiState(
            hasApiKey = apiKeyStore.hasAmapKey(),
            apiKey = apiKeyStore.amapKey(),
            webKey = apiKeyStore.amapWebKey(),
        ),
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val messageFlow = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = messageFlow.asSharedFlow()

    /** 撤销栈：每次改动前压入当前 buildings 快照。 */
    private val history = ArrayDeque<List<EditorBuilding>>()

    /** 重做栈：撤销时把「撤销前」的快照压进来。 */
    private val redoStack = ArrayDeque<List<EditorBuilding>>()

    private var centerToken = 0L

    init {
        loadActiveConfig()
        startLocating()
    }

    // ------------------------------------------------------------ 配置

    /** 读入当前生效的配置并刷新配置列表。 */
    private fun loadActiveConfig() {
        val config = configStore.loadActive()
        history.clear()
        redoStack.clear()
        _uiState.value = _uiState.value.copy(
            buildings = config.buildings,
            configs = configStore.list(),
            activeConfigName = configStore.activeFileName(),
            targetBuildingId = null,
            draftPoints = emptyList(),
            dragStart = null,
            selection = null,
            canUndo = false,
            canRedo = false,
        )
    }

    /** 切换配置：换一个学校的数据。 */
    fun switchConfig(fileName: String) {
        if (fileName == _uiState.value.activeConfigName) return
        configStore.setActive(fileName)
        loadActiveConfig()
        onDataChanged()
        emit("已切换到「${fileName.removeSuffix(".json")}」")
    }

    /** 新建一份空配置并切过去。 */
    fun createConfig(name: String) {
        val fileName = configStore.create(name)
        loadActiveConfig()
        onDataChanged()
        emit("已新建配置「${fileName.removeSuffix(".json")}」")
    }

    /** 复制当前配置（照着改比从零画快）。 */
    fun duplicateConfig(fileName: String) {
        val newName = configStore.duplicate(fileName) ?: run {
            emit("复制失败：读不到「${fileName.removeSuffix(".json")}」")
            return
        }
        configStore.setActive(newName)
        loadActiveConfig()
        onDataChanged()
        emit("已复制为「${newName.removeSuffix(".json")}」")
    }

    fun renameConfig(fileName: String, newName: String) {
        val result = configStore.rename(fileName, newName) ?: run {
            emit("重命名失败")
            return
        }
        loadActiveConfig()
        onDataChanged()
        emit("已重命名为「${result.removeSuffix(".json")}」")
    }

    /**
     * 删除配置。
     *
     * 若删的是当前生效的那份，[ConfigStore] 会自动切到剩下最新的一份，
     * 所以这里直接重新加载即可；一份都不剩时仓库会退回只用 assets 内置数据。
     */
    fun deleteConfig(fileName: String) {
        val display = fileName.removeSuffix(".json")
        if (!configStore.delete(fileName)) {
            emit("删除失败")
            return
        }
        loadActiveConfig()
        onDataChanged()
        emit("已删除「$display」")
    }

    /** 重新从文件加载当前配置（外部目录被替换过时用）。 */
    fun reload() {
        loadActiveConfig()
        onDataChanged()
        emit("已重新加载「${_uiState.value.activeConfigName.removeSuffix(".json")}」")
    }

    // ------------------------------------------------------------ 撤销 / 重做

    /**
     * 记录一次改动前的快照。
     *
     * 所有会改 [EditorUiState.buildings] 的操作都必须先过这里 ——
     * 这是「已写进配置的楼栋也能撤回」的实现方式：历史里存的是数据快照，
     * 不区分「本次新画的」和「上次配置里读进来的」。
     */
    private fun recordHistory() {
        history.addLast(_uiState.value.buildings)
        while (history.size > MAX_HISTORY) history.removeFirst()
        // 新的改动会让「重做」失去意义
        redoStack.clear()
        syncUndoFlags()
    }

    private fun syncUndoFlags() {
        val state = _uiState.value
        _uiState.value = state.copy(
            // 正在用钢笔连点：此时「撤销」要能退掉刚落的那个点，
            // 所以哪怕历史栈是空的也得把按钮点亮
            canUndo = history.isNotEmpty() || hasPenDraft(state),
            canRedo = redoStack.isNotEmpty(),
        )
    }

    /** 是否有「画到一半的钢笔草稿」。 */
    private fun hasPenDraft(state: EditorUiState): Boolean =
        state.mode.isPen && state.draftPoints.isNotEmpty()

    /** 撤销上一步。 */
    fun undo() {
        val state = _uiState.value

        // 钢笔草稿优先退点：用户说「撤销不好用」，最常见的就是画多边形时点错一个
        if (hasPenDraft(state)) {
            val rest = state.draftPoints.dropLast(1)
            _uiState.value = state.copy(draftPoints = rest)
            syncUndoFlags()
            emit(if (rest.isEmpty()) "已清掉草稿" else "已退回上一个点（还剩 ${rest.size} 个）")
            return
        }

        if (history.isEmpty()) {
            emit("没有可撤销的操作")
            return
        }
        redoStack.addLast(_uiState.value.buildings)
        val restored = history.removeLast()
        _uiState.value = _uiState.value.copy(
            buildings = restored,
            draftPoints = emptyList(),
            dragStart = null,
            selection = _uiState.value.selection?.takeIf { sel ->
                restored.any { it.id == sel.buildingId }
            },
        )
        syncUndoFlags()
        emit("已撤销")
    }

    /** 重做。 */
    fun redo() {
        if (redoStack.isEmpty()) {
            emit("没有可重做的操作")
            return
        }
        history.addLast(_uiState.value.buildings)
        val restored = redoStack.removeLast()
        _uiState.value = _uiState.value.copy(buildings = restored)
        syncUndoFlags()
        emit("已重做")
    }

    // ------------------------------------------------------------ 模式与选择

    fun setMode(mode: EditorMode) {
        val state = _uiState.value
        if (state.mode == mode) return
        _uiState.value = state.copy(
            mode = mode,
            draftPoints = emptyList(),
            dragStart = null,
            targetBuildingId = if (mode.needsBuilding) state.targetBuildingId else null,
            // 换工具就退出编辑态，避免「以为在画新的、结果在改旧的」
            editMode = false,
            selection = null,
        )
        // 草稿被清掉了，撤销按钮的可用性要跟着重算（钢笔草稿也算一步）
        syncUndoFlags()
    }

    fun setTargetBuilding(id: String?) {
        _uiState.value = _uiState.value.copy(targetBuildingId = id, draftPoints = emptyList())
    }

    fun setFloorLevel(level: Int) {
        val coerced = level.coerceAtLeast(1)
        _uiState.value = _uiState.value.copy(
            floorLevel = coerced,
            endFloorLevel = maxOf(coerced, _uiState.value.endFloorLevel),
            selection = null,
        )
    }

    fun setEndFloorLevel(level: Int) {
        val state = _uiState.value
        _uiState.value = state.copy(endFloorLevel = level.coerceIn(state.floorLevel, Int.MAX_VALUE))
    }

    fun setDraftName(name: String) {
        _uiState.value = _uiState.value.copy(draftName = name)
    }

    fun setDraftFloorCount(count: Int) {
        _uiState.value = _uiState.value.copy(draftFloorCount = count.coerceIn(1, MAX_FLOOR_COUNT))
    }

    fun setBuildingFloorCount(id: String, count: Int) {
        val safe = count.coerceIn(1, MAX_FLOOR_COUNT)
        recordHistory()
        _uiState.value = _uiState.value.copy(
            buildings = _uiState.value.buildings.map {
                if (it.id == id) it.copy(floorCount = safe) else it
            },
        )
    }

    /** 开关「编辑形状」模式。 */
    fun setEditMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            editMode = enabled,
            selection = if (enabled) _uiState.value.selection else null,
            draftPoints = emptyList(),
            dragStart = null,
            editAnchor = null,
        )
        if (enabled) {
            emit("点一下图形选中它；再点一下某个顶点，就能在那附近加点 / 删点")
        }
    }

    /**
     * 在「上次点击处」插入顶点。
     *
     * 供工具按钮调用：按钮本身不知道用户想在哪加点，所以用最近一次点击的位置 ——
     * 这也是唯一合理的解释（用户刚点了那里）。
     */
    fun insertVertexAtAnchor() {
        val anchor = _uiState.value.editAnchor ?: run {
            emit("先在图上点一下你要加点的位置（靠近边线）")
            return
        }
        insertVertexAt(anchor)
    }

    /** 在「上次点击处」删除顶点。 */
    fun deleteVertexAtAnchor() {
        val anchor = _uiState.value.editAnchor ?: run {
            emit("先点一下要删掉的那个顶点")
            return
        }
        deleteVertexAt(anchor)
    }

    /**
     * 选中一个图形。
     *
     * 命中优先级：**楼层元素 > 楼栋外轮廓** —— 元素画在轮廓里面，
     * 用户想点的多半是那个小的。
     */
    fun selectAt(point: LngLat) {
        val state = _uiState.value
        val tolerance = SELECT_TOLERANCE_M

        // 先找元素：只找当前目标楼栋（或第一栋）当前楼层附近的
        val candidates = state.buildings.flatMap { building ->
            building.floors.flatMap { floor ->
                floor.elements.map { Triple(building, floor.level, it) }
            }
        }
        val hitElement = candidates
            .map { (b, level, e) -> Triple(b, level, e) to distanceToShape(point, e.points) }
            .filter { it.second <= tolerance }
            .minByOrNull { it.second }

        if (hitElement != null) {
            val (b, level, e) = hitElement.first
            _uiState.value = state.copy(
                selection = ShapeSelection(b.id, e.id, level),
                targetBuildingId = b.id,
                floorLevel = level,
                draftName = e.name,
            )
            emit("已选中「${e.name}」")
            return
        }

        // 再找楼栋轮廓
        val hitBuilding = state.buildings
            .filter { it.hasValidPolygon }
            .map { it to distanceToShape(point, it.polygon) }
            .filter { it.second <= tolerance }
            .minByOrNull { it.second }

        if (hitBuilding != null) {
            val building = hitBuilding.first
            _uiState.value = state.copy(
                selection = ShapeSelection(building.id, null, null),
                draftName = building.name,
            )
            emit("已选中「${building.name}」的外轮廓")
            return
        }

        _uiState.value = state.copy(selection = null)
        emit("这里没有图形，点在图上的色块里试试")
    }

    /** 点到图形边界或内部的最近距离（米）。点在图形内部算 0。 */
    private fun distanceToShape(point: LngLat, points: List<LngLat>): Double {
        if (points.isEmpty()) return Double.MAX_VALUE
        if (points.size == 1) return Geo.distanceMeters(point, points.first())
        if (points.size >= 3 && Geo.containsPoint(points, point)) return 0.0
        var best = Double.MAX_VALUE
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val d = distanceToSegment(point, a, b)
            if (d < best) best = d
        }
        return best
    }

    private fun distanceToSegment(p: LngLat, a: LngLat, b: LngLat): Double {
        val kx = Geo.metersPerDegLng(p.lat)
        val ky = Geo.METERS_PER_DEG_LAT
        val px = (p.lng - a.lng) * kx
        val py = (p.lat - a.lat) * ky
        val bx = (b.lng - a.lng) * kx
        val by = (b.lat - a.lat) * ky
        val lenSq = bx * bx + by * by
        if (lenSq < 1e-9) return kotlin.math.hypot(px, py)
        val t = ((px * bx + py * by) / lenSq).coerceIn(0.0, 1.0)
        return kotlin.math.hypot(px - t * bx, py - t * by)
    }

    // ------------------------------------------------------------ 形状编辑

    /**
     * 一次「编辑形状」拖拽的上下文。
     *
     * 记下手势开始时的**原始点串**与起点：每次移动都用「原始点串 + 总位移」重算，
     * 而不是在上一帧结果上继续偏移 —— 后者会把浮点误差一帧帧累积，图形会慢慢飘走。
     */
    private data class ShapeDrag(
        val start: LngLat,
        val originPoints: List<LngLat>,
        /** 非 null 表示拖的是某个顶点；null 表示整体平移。 */
        val vertexIndex: Int?,
    )

    private var shapeDrag: ShapeDrag? = null

    /**
     * 编辑模式下按下。
     *
     * 命中顶点就拖那个顶点，否则整体平移这个图形。
     * 无论哪种都先记一次历史快照 —— 整个手势算**一步**，
     * 否则撤销一下只退回一个像素。
     */
    fun onShapeDragStart(point: LngLat) {
        val shape = _uiState.value.selectedShape
        if (shape == null) {
            selectAt(point)
            return
        }
        val vertex = EditorBuilding.nearestVertexIndex(shape.points, point, VERTEX_HIT_M)
        recordHistory()
        shapeDrag = ShapeDrag(start = point, originPoints = shape.points, vertexIndex = vertex)
    }

    /** 编辑模式下拖动。 */
    fun onShapeDragUpdate(point: LngLat) {
        val drag = shapeDrag ?: return
        val vertex = drag.vertexIndex
        if (vertex != null) {
            drag.originPoints.toMutableList().also { it[vertex] = point }.let(::applyPointsToSelection)
            return
        }
        val kx = Geo.metersPerDegLng(drag.start.lat)
        val ky = Geo.METERS_PER_DEG_LAT
        val deltaEast = (point.lng - drag.start.lng) * kx
        val deltaNorth = (point.lat - drag.start.lat) * ky
        applyPointsToSelection(
            EditorBuilding.translate(drag.originPoints, deltaEast, deltaNorth),
        )
    }

    /** 编辑模式下松手。 */
    fun onShapeDragEnd() {
        shapeDrag = null
    }

    /**
     * 在离 [at] 最近的边上插入一个顶点。
     *
     * 单点元素不能插点（一个点没有「边」），会提示用户。
     */
    fun insertVertexAt(at: LngLat) {
        val shape = _uiState.value.selectedShape ?: run {
            emit("先点一下图形选中它")
            return
        }
        if (shape.points.size < 2) {
            emit("单点元素没有边，不能加顶点")
            return
        }
        val edgeStart = EditorBuilding.nearestEdgeStartIndex(shape.points, at, INSERT_TOLERANCE_M)
            ?: run {
                emit("离边线太远，点到图形的边上再加顶点")
                return
            }
        recordHistory()
        applyPointsToSelection(EditorBuilding.insertVertex(shape.points, edgeStart, at))
        emit("已插入顶点")
    }

    /** 删掉离 [at] 最近的顶点。 */
    fun deleteVertexAt(at: LngLat) {
        val shape = _uiState.value.selectedShape ?: run {
            emit("先点一下图形选中它")
            return
        }
        val multiPoint = shape.points.size > 1
        if (multiPoint && shape.points.size <= EditorBuilding.MIN_POLYGON_POINTS) {
            emit("再删就不成面了（至少要 ${EditorBuilding.MIN_POLYGON_POINTS} 个顶点）")
            return
        }
        val index = EditorBuilding.nearestVertexIndex(shape.points, at, INSERT_TOLERANCE_M)
            ?: run {
                emit("附近没有顶点")
                return
            }
        recordHistory()
        applyPointsToSelection(shape.points.filterIndexed { i, _ -> i != index })
        emit("已删除顶点")
    }

    /** 旋转选中的图形。 */
    fun rotateSelection(degrees: Double = DEFAULT_ROTATE_STEP) {
        val shape = _uiState.value.selectedShape ?: run {
            emit("先点一下图形选中它")
            return
        }
        if (shape.points.size < 2) {
            emit("单点元素没有方向，不需要旋转")
            return
        }
        recordHistory()
        applyPointsToSelection(EditorBuilding.rotate(shape.points, degrees))
        emit("已旋转 ${degrees.toInt()}°")
    }

    /** 把新的点串写回选中的图形（楼栋轮廓或楼层元素）。 */
    private fun applyPointsToSelection(points: List<LngLat>) {
        val shape = _uiState.value.selectedShape ?: return
        val buildings = _uiState.value.buildings.map { building ->
            if (building.id != shape.buildingId) {
                building
            } else if (shape.isBuildingOutline) {
                building.copy(polygon = points)
            } else {
                building.copy(
                    floors = building.floors.map { floor ->
                        if (floor.level != shape.level) {
                            floor
                        } else {
                            floor.copy(
                                elements = floor.elements.map { element ->
                                    if (element.id == shape.elementId) {
                                        element.copy(points = points)
                                    } else {
                                        element
                                    }
                                },
                            )
                        }
                    },
                )
            }
        }
        _uiState.value = _uiState.value.copy(buildings = buildings)
        syncUndoFlags()
    }

    // ------------------------------------------------------------ 绘制

    /**
     * 点了一下地图（没有拖动）。
     *
     * 三个分支：
     *  - **编辑模式**：选中图形（还要看是不是点了某个顶点，那走拖顶点）；
     *  - **单点元素**：直接落点并结束；
     *  - **其他**：提示要按住拖动框范围。
     */
    fun onTap(point: LngLat) {
        val state = _uiState.value
        if (state.editMode) {
            // 记住点击位置：工具按钮「加点 / 删点」要靠它决定操作哪儿
            _uiState.value = state.copy(editAnchor = point)
            selectAt(point)
            return
        }
        if (!state.canDraw) {
            emit("请先选定要画在哪栋楼，再开始绘制")
            return
        }
        // 钢笔：每点一下加一个顶点，点够三个再按「成面」闭合
        if (state.mode.isPen) {
            appendPenPoint(state, point)
            return
        }
        if (!state.mode.isPoint) {
            emit("按住拖动框出范围（不用逐点点选）")
            return
        }
        commitPoints(points = listOf(point), name = state.draftName)
    }

    /**
     * 钢笔模式下落一个顶点。
     *
     * 会挡掉「和上一个点几乎重合」的点击 —— 手指点两下同一个位置的抖动很常见，
     * 那些重复点会让多边形出现零长度边，`isValidPolygon` 判不过，用户却看不出原因。
     */
    private fun appendPenPoint(state: EditorUiState, point: LngLat) {
        val last = state.draftPoints.lastOrNull()
        if (last != null && Geo.distanceMeters(last, point) < PEN_MIN_STEP_M) {
            emit("和上一个点太近了，往外点一点")
            return
        }
        val points = state.draftPoints + point
        _uiState.value = state.copy(draftPoints = points)
        syncUndoFlags()
        emit(
            if (points.size >= EditorUiState.MIN_POLYGON_POINTS) {
                "已落下第 ${points.size} 个点，点「成面」闭合；还能继续加点"
            } else {
                "已落下第 ${points.size} 个点，至少还要 " +
                    "${EditorUiState.MIN_POLYGON_POINTS - points.size} 个"
            },
        )
    }

    /** 拖拽开始。 */
    fun onDragStart(point: LngLat) {
        val state = _uiState.value

        // 编辑模式：拖选中图形的顶点或整体平移
        if (state.editMode) {
            onShapeDragStart(point)
            return
        }

        // 钢笔模式不吃拖拽：它是「连续点」，拖出来的矩形会让人以为画错了工具
        if (state.mode.isPen) {
            emit("自由多边形是「连续点」画法：点一下落一个点，点够 3 个再按「成面」")
            return
        }

        if (state.mode.isPoint) return

        // 这里必须吭声：这个模式下地图点击回调是关掉的，
        // 静默 return 的话用户拖了半天完全没有任何反馈
        if (!state.canDraw) {
            emit("请先选定要画在哪栋楼，再开始绘制")
            return
        }

        _uiState.value = state.copy(dragStart = point, draftPoints = listOf(point))
    }

    /** 拖拽中。 */
    fun onDragUpdate(point: LngLat) {
        val state = _uiState.value

        if (state.editMode) {
            onShapeDragUpdate(point)
            return
        }

        val start = state.dragStart ?: return
        _uiState.value = state.copy(draftPoints = previewPoints(state.mode, start, point))
    }

    /** 拖拽结束。 */
    fun onDragEnd(point: LngLat) {
        val state = _uiState.value

        if (state.editMode) {
            onShapeDragEnd()
            return
        }

        val start = state.dragStart ?: return
        val points = previewPoints(state.mode, start, point)

        // 圆只需「圆心 + 半径」，不适用对角范围判定；矩形/点才看跨度
        if (state.mode.shape != ElementShape.Circle && spanTooSmall(start, point)) {
            _uiState.value = state.copy(draftPoints = emptyList(), dragStart = null)
            emit("拖动范围太小，按住往对角方向拖出一片区域")
            return
        }
        if (points.size < EditorUiState.MIN_POLYGON_POINTS) {
            _uiState.value = state.copy(draftPoints = emptyList(), dragStart = null)
            emit("拖动范围太小，按住往外拖出半径")
            return
        }

        _uiState.value = state.copy(draftPoints = points, dragStart = null)
        finishDraft()
    }

    /** 放弃当前预览 / 取消选中。 */
    fun cancelDraft() {
        _uiState.value = _uiState.value.copy(
            draftPoints = emptyList(),
            dragStart = null,
            selection = null,
        )
        // 草稿没了，钢笔那条「可以撤销」的理由也没了
        syncUndoFlags()
    }

    /** 按当前形状工具算出预览点串。 */
    private fun previewPoints(mode: EditorMode, start: LngLat, end: LngLat): List<LngLat> =
        when (mode.shape) {
            ElementShape.Rectangle -> EditorBuilding.rectangleFromCorners(start, end)
            ElementShape.Circle -> EditorBuilding.circleFromCenter(start, end)
            // 点和钢笔都不靠拖拽累计点串（钢笔走 onTap），这里只是兜底
            ElementShape.Point, ElementShape.Polygon -> listOf(start)
        }

    /** 结束当前图形。 */
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
        // 需不需要先选楼栋，正好区分「这是外轮廓」还是「这是楼里的元素」，
        // 所以这里不问 mode == Building，而是问 needsBuilding —— 自由轮廓也能走通
        if (state.mode.isOutline) {
            finishBuildingOutline(state)
        } else {
            finishFloorElement(state)
        }
    }

    private fun finishBuildingOutline(state: EditorUiState) {
        val name = state.draftName.trim().ifBlank { "未命名楼栋" }
        val existing = state.buildings.firstOrNull { it.name == name }
        recordHistory()

        val updated = if (existing != null) {
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
            dragStart = null,
            draftName = "",
        )
        syncUndoFlags()
        emit(if (existing != null) "已更新「$name」的外轮廓" else "已添加「$name」，记得点保存")
    }

    private fun finishFloorElement(state: EditorUiState) {
        val target = state.targetBuilding ?: run {
            emit("请先选定要画在哪栋楼")
            return
        }
        val mode = state.mode
        val name = state.draftName.trim().ifBlank { mode.label }
        val existingElements = target.floor(state.floorLevel)?.elements.orEmpty()

        recordHistory()
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
        syncUndoFlags()
        emit(
            if (element.isCrossFloor) {
                "已在 ${target.name} 添加「$name」（${state.floorLevel}→${element.toLevel} 楼，每层都会生成）"
            } else {
                "已在 ${target.name} ${state.floorLevel} 楼添加「$name」"
            },
        )
    }

    /** 落一个点就完成的元素（楼梯口 / 卫生间）。 */
    private fun commitPoints(points: List<LngLat>, name: String) {
        val state = _uiState.value
        val target = state.targetBuilding ?: run {
            emit("请先选定要画在哪栋楼")
            return
        }
        val mode = state.mode
        val label = name.trim().ifBlank { mode.label }
        val existingElements = target.floor(state.floorLevel)?.elements.orEmpty()

        recordHistory()
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
        syncUndoFlags()
        emit("已放置「$label」在 ${target.name} ${state.floorLevel} 楼")
    }

    private fun spanTooSmall(start: LngLat, end: LngLat): Boolean =
        kotlin.math.abs(end.lng - start.lng) < EditorBuilding.MIN_DRAG_SPAN_DEGREES &&
            kotlin.math.abs(end.lat - start.lat) < EditorBuilding.MIN_DRAG_SPAN_DEGREES

    // ------------------------------------------------------------ 删除

    fun removeBuilding(id: String) {
        recordHistory()
        _uiState.value = _uiState.value.copy(
            buildings = _uiState.value.buildings.filterNot { it.id == id },
            targetBuildingId = _uiState.value.targetBuildingId?.takeIf { it != id },
            selection = _uiState.value.selection?.takeIf { it.buildingId != id },
        )
        syncUndoFlags()
    }

    fun removeElement(buildingId: String, level: Int, elementId: String) {
        recordHistory()
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
            selection = _uiState.value.selection
                ?.takeIf { !(it.buildingId == buildingId && it.elementId == elementId) },
        )
        syncUndoFlags()
    }

    /** 删掉当前选中的图形。 */
    fun removeSelection() {
        val shape = _uiState.value.selectedShape ?: run {
            emit("先点一下图形选中它")
            return
        }
        if (shape.isBuildingOutline) {
            removeBuilding(shape.buildingId)
            emit("已删除「${shape.name}」")
        } else {
            removeElement(shape.buildingId, shape.level!!, shape.elementId!!)
            emit("已删除「${shape.name}」")
        }
    }

    fun clearAll() {
        recordHistory()
        _uiState.value = _uiState.value.copy(
            buildings = emptyList(),
            draftPoints = emptyList(),
            targetBuildingId = null,
            selection = null,
        )
        syncUndoFlags()
    }

    // ------------------------------------------------------------ 存盘

    fun save() {
        val buildings = _uiState.value.buildings
        if (buildings.isEmpty()) {
            emit("还没有画好的楼栋，先在地图上拖出轮廓")
            return
        }
        val result = configStore.saveActive(buildings)
        _uiState.value = _uiState.value.copy(lastSave = result, configs = configStore.list())
        // 存盘后要让首页/导航用上新数据
        onDataChanged()
        emit(
            buildString {
                append("已保存 ${buildings.size} 栋到「")
                append(configStore.activeFileName().removeSuffix(".json"))
                append("」")
                result.internalPath?.let { append("\n内部：$it") }
                result.externalPath?.let { append("\n外部：$it") }
                result.warnings.forEach { append("\n⚠ $it") }
            },
        )
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
                            centerRequest = if (first) {
                                nextCenter(point, ZOOM_STREET)
                            } else {
                                _uiState.value.centerRequest
                            },
                        )
                    }
                }
            }
            .launchIn(viewModelScope)
    }

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

    fun goTo(point: LngLat, zoom: Float = ZOOM_STREET) {
        _uiState.value = _uiState.value.copy(centerRequest = nextCenter(point, zoom))
    }

    fun consumeCenterRequest(token: Long) {
        if (_uiState.value.centerRequest?.token == token) {
            _uiState.value = _uiState.value.copy(centerRequest = null)
        }
    }

    private fun nextCenter(point: LngLat, zoom: Float): CenterRequest {
        centerToken += 1
        return CenterRequest(token = centerToken, point = point, zoom = zoom)
    }

    // ------------------------------------------------------------ Key

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

    fun refreshApiKey() {
        _uiState.value = _uiState.value.copy(
            hasApiKey = apiKeyStore.hasAmapKey(),
            apiKey = apiKeyStore.amapKey(),
            webKey = apiKeyStore.amapWebKey(),
        )
    }

    // ------------------------------------------------------------ 内部

    private fun generateBuildingId(name: String, existing: List<EditorBuilding>): String {
        val taken = existing.map { it.id }.toSet()
        return uniqueId("editor-" + name.slug(), taken)
    }

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

        /** 点击命中图形的容差（米）。 */
        const val SELECT_TOLERANCE_M = 8.0

        /** 点顶点 / 边线的命中容差（米）。 */
        const val VERTEX_HIT_M = 12.0
        const val INSERT_TOLERANCE_M = 12.0

        /**
         * 钢笔连点时两个顶点之间的最小间距（米）。
         *
         * 手指在同一个位置点两下会落出几乎重合的点，那些零长度边会让
         * `isValidPolygon` 判不过，而用户完全看不出为什么。
         */
        const val PEN_MIN_STEP_M = 1.0

        /** 每次点旋转按钮转多少度。 */
        const val DEFAULT_ROTATE_STEP = 15.0

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(MapEditorViewModel::class.java)) {
                        "未知的 ViewModel：${modelClass.name}"
                    }
                    return MapEditorViewModel(
                        configStore = container.configStore,
                        apiKeyStore = container.apiKeyStore,
                        locationSource = container.locationSource,
                        onDataChanged = { container.reloadRepository() },
                    ) as T
                }
            }
    }
}
