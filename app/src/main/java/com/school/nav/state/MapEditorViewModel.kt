package com.school.nav.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.school.nav.AppContainer
import com.school.nav.core.data.BuildingOutline
import com.school.nav.core.data.CampusRepository
import com.school.nav.core.data.EditorConfig
import com.school.nav.core.model.LngLat
import com.school.nav.data.ApiKeyStore
import com.school.nav.data.EditorConfigStore
import com.school.nav.data.SaveResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 编辑器界面状态。 */
data class EditorUiState(
    /** 高德 Key 是否已配置（没配就没法显示地图）。 */
    val hasApiKey: Boolean = false,
    /** 已配置的高德 Key，用于初始化地图视图。 */
    val apiKey: String = "",
    /** 正在绘制的顶点（世界坐标）。 */
    val draftPoints: List<LngLat> = emptyList(),
    /** 正在编辑的楼栋名。 */
    val draftName: String = "",
    /** 已画好、待保存的楼栋。 */
    val outlines: List<BuildingOutline> = emptyList(),
    /** 上一次保存的结果，用于展示“存到哪了”。 */
    val lastSave: SaveResult? = null,
    /** 一次性提示文本。 */
    val message: String? = null,
) {
    /** 顶点够不够围成一个面。 */
    val canFinishDraft: Boolean get() = draftPoints.size >= MIN_POLYGON_POINTS

    /** 有没有未完成/未保存的东西，用于决定「保存」按钮是否可点。 */
    val hasSomethingToSave: Boolean get() = outlines.isNotEmpty()

    companion object {
        const val MIN_POLYGON_POINTS = 3
    }
}

/**
 * 地图编辑器的状态机。
 *
 * 职责：
 *  - 管理「正在画的多边形」与「已完成的楼栋列表」；
 *  - 保存到配置文件（内部 + 外部双写，实现在 [EditorConfigStore]）；
 *  - 把高德 Key 的读写接过来，供设置页与地图页共用。
 *
 * 不负责地图渲染 —— 渲染在 `AmapEditorView` 里，通过回调把「用户点了哪」传进来，
 * 这样绘制逻辑（增删顶点、成面、命名、存盘）不依赖高德 SDK，便于单独推理。
 */
class MapEditorViewModel(
    private val configStore: EditorConfigStore,
    private val apiKeyStore: ApiKeyStore,
    repository: CampusRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        EditorUiState(
            hasApiKey = apiKeyStore.hasAmapKey(),
            apiKey = apiKeyStore.amapKey(),
            // 已经存在的楼栋直接列出来，画新楼时能看到已有进度
            outlines = configStore.load().buildings,
        ),
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    /** 已有楼栋，供地图上把它们的轮廓也画出来做参考。 */
    val existingBuildings = MutableStateFlow(repository.buildings)

    private val messageFlow = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = messageFlow.asSharedFlow()

    // ------------------------------------------------------------ 绘制

    /** 地图上点了一下：追加一个顶点。 */
    fun addPoint(point: LngLat) {
        _uiState.value = _uiState.value.copy(
            draftPoints = _uiState.value.draftPoints + point,
            message = null,
        )
    }

    /** 撤销最后一个顶点。 */
    fun undoPoint() {
        val points = _uiState.value.draftPoints
        if (points.isEmpty()) return
        _uiState.value = _uiState.value.copy(draftPoints = points.dropLast(1))
    }

    /** 放弃当前正在画的多边形。 */
    fun cancelDraft() {
        _uiState.value = _uiState.value.copy(draftPoints = emptyList(), message = null)
    }

    fun setDraftName(name: String) {
        _uiState.value = _uiState.value.copy(draftName = name)
    }

    /**
     * 结束当前多边形，转成一条待保存的楼栋。
     *
     * 顶点不足 3 个时不允许成面，并给出提示 —— 少于三个点围不成面，
     * 存进去也只会被 [BuildingOutline.isValid] 判为无效然后丢掉。
     */
    fun finishDraft() {
        val state = _uiState.value
        if (!state.canFinishDraft) {
            emit("至少需要 ${EditorUiState.MIN_POLYGON_POINTS} 个点才能围成楼栋轮廓")
            return
        }

        val name = state.draftName.trim().ifBlank { "未命名楼栋" }
        val id = generateId(name, state.outlines)

        val outline = BuildingOutline(id = id, name = name, polygon = state.draftPoints)
        _uiState.value = state.copy(
            outlines = state.outlines + outline,
            draftPoints = emptyList(),
            draftName = "",
            message = null,
        )
        emit("已添加「$name」，记得点保存")
    }

    /** 删掉一条待保存的楼栋。 */
    fun removeOutline(id: String) {
        _uiState.value = _uiState.value.copy(
            outlines = _uiState.value.outlines.filterNot { it.id == id },
        )
    }

    /** 清空所有待保存的楼栋（不影响已写盘的文件，直到下次保存）。 */
    fun clearAll() {
        _uiState.value = _uiState.value.copy(outlines = emptyList(), draftPoints = emptyList())
    }

    // ------------------------------------------------------------ 存盘

    /**
     * 保存到配置文件。
     *
     * 写两份（内部权威 + 外部可取出），结果通过 [EditorUiState.lastSave] 暴露给 UI，
     * 让用户看到具体路径 —— 否则「保存成功」但找不到文件是很常见的困惑。
     */
    fun save() {
        val outlines = _uiState.value.outlines
        if (outlines.isEmpty()) {
            emit("还没有画好的楼栋，先在图上点几个点围成轮廓")
            return
        }
        val result = configStore.save(outlines)
        _uiState.value = _uiState.value.copy(lastSave = result)
        val summary = buildString {
            append("已保存 ${outlines.size} 栋")
            result.internalPath?.let { append("\n内部：$it") }
            result.externalPath?.let { append("\n外部：$it") }
            result.warnings.forEach { append("\n⚠ $it") }
        }
        emit(summary)
    }

    /** 重新从文件加载（例如外部目录被替换过）。 */
    fun reload() {
        val config: EditorConfig = configStore.load()
        _uiState.value = _uiState.value.copy(outlines = config.buildings, lastSave = null)
        emit("已从配置文件重新加载 ${config.buildings.size} 栋")
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
        emit("已保存高德 Key，返回地图页即可加载地图")
    }

    fun clearApiKey() {
        apiKeyStore.clearAmapKey()
        _uiState.value = _uiState.value.copy(hasApiKey = false, apiKey = "")
        emit("已清除高德 Key")
    }

    // ------------------------------------------------------------ 内部

    /**
     * 生成楼栋 id。
     *
     * 与 assets 里已有的楼栋重名时加后缀 —— id 相同会被判定为「覆盖同一栋楼」，
     * 用户画一栋新的「A栋」却把内置的 A 栋楼层覆盖掉，是个很难排查的坑。
     */
    private fun generateId(name: String, existing: List<BuildingOutline>): String {
        val taken = (existing.map { it.id } + existingBuildings.value.map { it.id }).toSet()
        val base = "editor-" + name.filter { it.isLetterOrDigit() }.ifBlank { "building" }
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    private fun emit(text: String) {
        messageFlow.tryEmit(text)
    }

    companion object {
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
                    ) as T
                }
            }
    }
}
