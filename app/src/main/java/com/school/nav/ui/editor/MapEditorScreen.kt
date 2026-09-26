package com.school.nav.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.school.nav.core.data.EditorMode
import com.school.nav.core.model.LngLat
import com.school.nav.data.PoiResult
import com.school.nav.data.PoiSearcher
import com.school.nav.state.EditorUiState
import com.school.nav.ui.components.NavBarSpace
import com.school.nav.ui.components.TestTags
import com.school.nav.ui.theme.NavColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 搜索防抖时长：Geocoder 有频率限制、高德 Web API 有配额，逐字查询会被限流。 */
private const val SEARCH_DEBOUNCE_MS = 300L

/** 右上角模式下拉的固定宽度：展开搜索时要把它收缩到 0。 */
private val MODE_DROPDOWN_WIDTH = 84.dp

/**
 * 地图编辑器页（导航栏「地图」）。
 *
 * 布局原则：**地图占满全屏**，控件都是浮在地图上的小卡片，而不是把地图压在下面
 * 留半屏给面板 —— 画轮廓时视野越大越好。
 *
 * 顶部会主动避开状态栏：`windowInsetsPadding(WindowInsets.statusBars)`。
 * 之前搜索框直接顶到屏幕最上沿，被状态栏的时间/信号图标盖住，点不到也看不清。
 */
@Composable
fun MapEditorScreen(
    state: EditorUiState,
    onMapClick: (LngLat) -> Unit,
    onDragStart: (LngLat) -> Unit,
    onDragUpdate: (LngLat) -> Unit,
    onDragEnd: (LngLat) -> Unit,
    onModeChange: (EditorMode) -> Unit,
    onTargetBuildingChange: (String?) -> Unit,
    onFloorChange: (Int) -> Unit,
    onEndFloorChange: (Int) -> Unit,
    onBuildingFloorCountChange: (String, Int) -> Unit,
    onNameChange: (String) -> Unit,
    onFloorCountChange: (Int) -> Unit,
    onUndo: () -> Unit,
    onFinish: () -> Unit,
    onCancelDraft: () -> Unit,
    onRemoveBuilding: (String) -> Unit,
    onRemoveElement: (String, Int, String) -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
    onGoMyLocation: () -> Unit,
    onCenterConsumed: (Long) -> Unit,
    onGoToPoint: (LngLat) -> Unit,
    onGoSettings: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** 这一页是否在前台可见；地图常驻组合树，靠它控制 MapView 的可见性与手势。 */
    active: Boolean = true,
) {
    val context = LocalContext.current
    // 配了 Web服务 Key 就用高德 POI 模糊搜索，否则退回 Android Geocoder
    val searcher = remember(state.webKey) { PoiSearcher(context, state.webKey) }
    val scope = rememberCoroutineScope()

    var showList by rememberSaveable { mutableStateOf(false) }
    var showNameDialog by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<PoiResult>()) }
    var searchError by remember { mutableStateOf<String?>(null) }

    if (!state.hasApiKey) {
        EditorNeedsKey(onGoSettings = onGoSettings, modifier = modifier)
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(TestTags.EditorScreen),
    ) {
        // ---- 全屏地图 ----
        AmapEditorView(
            draftPoints = state.draftPoints,
            buildings = state.buildings,
            centerRequest = state.centerRequest,
            onMapClick = onMapClick,
            onDragStart = onDragStart,
            onDragUpdate = onDragUpdate,
            onDragEnd = onDragEnd,
            onCenterConsumed = onCenterConsumed,
            draggingEnabled = !state.mode.isPoint,
            active = active,
            modifier = Modifier
                .fillMaxSize()
                .testTag(TestTags.EditorMap),
        )

        // ---- 顶部栏（避开状态栏）----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchBar(
                    text = searchText,
                    expanded = searchOpen,
                    searching = searching,
                    enabled = searcher.isAvailable(),
                    modifier = Modifier.weight(1f),
                    onExpand = { searchOpen = true },
                    onTextChange = { query ->
                        searchText = query
                        searchError = null
                        if (query.isBlank()) {
                            results = emptyList()
                        } else {
                            // 边输边搜 + 300ms 防抖：高德 Web API 有配额、
                            // Geocoder 有频率限制，每敲一个字查一次容易被限流。
                            searching = true
                            scope.launch {
                                delay(SEARCH_DEBOUNCE_MS)
                                val outcome = searcher.search(query)
                                results = outcome.results
                                searchError = outcome.message
                                searching = false
                            }
                        }
                    },
                    onSubmit = { query ->
                        searching = true
                        scope.launch {
                            val outcome = searcher.search(query)
                            results = outcome.results
                            searchError = outcome.message
                            searching = false
                        }
                    },
                    onCollapse = {
                        searchOpen = false
                        results = emptyList()
                    },
                )

                // 模式下拉：搜索展开时淡出收缩，把宽度让给正在扩展的搜索框
                ModeDropdown(
                    current = state.mode,
                    onSelect = onModeChange,
                    visible = !searchOpen,
                )
            }

            // 结果面板：从搜索框**下面拉出来**，而不是盖一层半透明白屏
            AnimatedVisibility(
                visible = searchOpen,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                SearchResultsPanel(
                    results = results,
                    message = searchError,
                    modifier = Modifier.padding(top = 8.dp),
                    onPick = { result ->
                        onGoToPoint(result.point)
                        searchText = result.name
                        results = emptyList()
                        searchOpen = false
                    },
                )
            }
        }

        // ---- 右侧圆形工具 ----
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundTool(
                icon = Icons.Filled.LocationOn,
                contentDescription = "回到我的位置",
                testTag = TestTags.EditorMyLocation,
                onClick = onGoMyLocation,
            )
            RoundTool(
                icon = Icons.AutoMirrored.Filled.Undo,
                contentDescription = "撤销最后一个点",
                testTag = TestTags.EditorUndo,
                enabled = state.draftPoints.isNotEmpty(),
                onClick = onUndo,
            )
            RoundTool(
                icon = Icons.Filled.Close,
                contentDescription = "放弃当前绘制",
                testTag = TestTags.EditorCancelDraft,
                enabled = state.draftPoints.isNotEmpty(),
                onClick = onCancelDraft,
            )
        }

        // ---- 底部：状态 + 主操作 ----
        BottomBar(
            state = state,
            modifier = Modifier.align(Alignment.BottomCenter),
            onShowList = { showList = true },
            onFinish = { showNameDialog = true },
            onSave = onSave,
        )
    }

    // 搜索不再单开一层全屏页面：它就地展开，结果面板从搜索框下面拉出来，
    // 所以这里不需要任何 SearchOverlay 分支。

    if (showList) {
        BuildingListDialog(
            state = state,
            onDismiss = { showList = false },
            onTargetBuildingChange = onTargetBuildingChange,
            onFloorChange = onFloorChange,
            onEndFloorChange = onEndFloorChange,
            onBuildingFloorCountChange = onBuildingFloorCountChange,
            onRemoveBuilding = onRemoveBuilding,
            onRemoveElement = onRemoveElement,
            onReload = onReload,
        )
    }

    if (showNameDialog) {
        NameDialog(
            state = state,
            onDismiss = { showNameDialog = false },
            onConfirm = { name, floorCount ->
                onNameChange(name)
                if (floorCount != null) onFloorCountChange(floorCount)
                showNameDialog = false
                onFinish()
            },
        )
    }
}

/** 没配 Key 时的引导。地图 SDK 缺 Key 只会白屏，那种失败方式对用户毫无帮助。 */
@Composable
private fun EditorNeedsKey(onGoSettings: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(24.dp)
            .testTag(TestTags.EditorNoKey),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = NavColors.Card,
            shadowElevation = 4.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "需要先配置高德地图 Key",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NavColors.TextPrimary,
                )
                Text(
                    text = "地图渲染依赖高德 SDK。Key 与「包名 + 签名 SHA1」绑定，" +
                        "需要在高德开放平台申请后填到设置里。",
                    fontSize = 13.sp,
                    color = NavColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
                )
                Button(
                    onClick = onGoSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.EditorGoSettings),
                ) { Text("去设置里填写 Key") }
            }
        }
    }
}

/**
 * 搜索框：收起态是一个圆角胶囊，点一下就地展开成输入框（不新开一层页面）。
 *
 * 展开时会自动聚焦并弹出键盘；右侧出现「取消」，收起时清空结果。
 */
@Composable
private fun SearchBar(
    text: String,
    expanded: Boolean,
    searching: Boolean,
    enabled: Boolean,
    onExpand: () -> Unit,
    onTextChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 展开即聚焦弹键盘，这样点一下就能直接打字
    LaunchedEffect(expanded) {
        if (expanded) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .then(
                if (expanded) {
                    Modifier
                } else {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onExpand,
                    )
                },
            )
            .testTag(TestTags.EditorSearchField),
        shape = RoundedCornerShape(24.dp),
        color = NavColors.Card,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = if (expanded) 0.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = NavColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )

            if (expanded) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .testTag(TestTags.EditorSearchInput),
                    singleLine = true,
                    placeholder = {
                        Text("搜索地点，如 某某大学", fontSize = 14.sp, color = NavColors.TextSecondary)
                    },
                    trailingIcon = {
                        when {
                            searching -> CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = NavColors.Brand,
                            )

                            text.isNotEmpty() -> IconButton(
                                onClick = { onTextChange("") },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "清空",
                                    tint = NavColors.TextSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit(text) }),
                )
                TextButton(
                    onClick = onCollapse,
                    modifier = Modifier.testTag(TestTags.EditorSearchClose),
                ) { Text("取消", fontSize = 13.sp) }
            } else {
                Text(
                    text = text.ifBlank { if (enabled) "搜索地点" else "本机不支持地点搜索" },
                    fontSize = 14.sp,
                    color = if (text.isBlank()) NavColors.TextSecondary else NavColors.TextPrimary,
                    maxLines = 1,
                    modifier = Modifier.padding(vertical = 13.dp),
                )
            }
        }
    }
}

/**
 * 搜索结果面板：从搜索框下面拉出来的白卡片。
 *
 * 刻意**不用半透明全屏遮罩** —— 那样会把地图糊掉一层白，用户在挑地点时
 * 反而看不到地图。这里只是一块贴着搜索框的圆角面板，地图其余部分保持原样。
 */
@Composable
private fun SearchResultsPanel(
    results: List<PoiResult>,
    message: String?,
    onPick: (PoiResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .testTag(TestTags.EditorSearchResults),
        shape = MaterialTheme.shapes.medium,
        color = NavColors.Card,
        shadowElevation = 8.dp,
    ) {
        when {
            results.isNotEmpty() -> LazyColumn {
                items(results) { result ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(result) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag(TestTags.EditorSearchResultItem),
                    ) {
                        Text(
                            text = result.name,
                            fontSize = 15.sp,
                            color = NavColors.TextPrimary,
                            maxLines = 1,
                        )
                        Text(
                            text = result.subtitle,
                            fontSize = 11.sp,
                            color = if (result.isFuzzy) NavColors.Brand else NavColors.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }

            message != null -> Text(
                text = message,
                fontSize = 13.sp,
                color = NavColors.TextSecondary,
                modifier = Modifier.padding(16.dp),
            )

            else -> Text(
                text = "输入地点名开始搜索",
                fontSize = 13.sp,
                color = NavColors.TextSecondary,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/**
 * 右上角绘制模式下拉。
 *
 * [visible] 为 false 时淡出并横向收缩到 0 —— 搜索框展开时把这块宽度让出去，
 * 看起来就是「搜索框把下拉菜单吞并了」。
 */
@Composable
private fun ModeDropdown(
    current: EditorMode,
    onSelect: (EditorMode) -> Unit,
    visible: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "modeAlpha")
    val width by animateDpAsState(if (visible) MODE_DROPDOWN_WIDTH else 0.dp, label = "modeWidth")

    if (!visible) expanded = false

    Box(
        modifier = Modifier
            .width(width)
            .alpha(alpha),
    ) {
        if (width > 0.dp) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = NavColors.Card,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { expanded = true }
                    .testTag(TestTags.EditorModeDropdown),
            ) {
                Row(
                    modifier = Modifier
                        .width(MODE_DROPDOWN_WIDTH)
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = current.label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = NavColors.Brand,
                        maxLines = 1,
                    )
                    Text(text = " ▾", fontSize = 13.sp, color = NavColors.Brand)
                }
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            EditorMode.menuOrder.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = mode.label,
                            color = if (mode == current) NavColors.Brand else NavColors.TextPrimary,
                            fontWeight = if (mode == current) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    },
                    modifier = Modifier.testTag("${TestTags.EditorModeItem}_${mode.name}"),
                )
            }
        }
    }
}

/** 浮在地图上的圆形工具按钮。 */
@Composable
private fun RoundTool(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        shape = CircleShape,
        color = NavColors.Card,
        shadowElevation = 4.dp,
        modifier = Modifier.size(44.dp),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.testTag(testTag),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) NavColors.TextPrimary else NavColors.TextSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 底部状态条 + 主操作。 */
@Composable
private fun BottomBar(
    state: EditorUiState,
    onShowList: () -> Unit,
    onFinish: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = NavBarSpace),
        shape = MaterialTheme.shapes.medium,
        color = NavColors.Card,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusLine(state),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = NavColors.TextPrimary,
                        modifier = Modifier.testTag(TestTags.EditorDraftHint),
                    )
                    Text(
                        text = "${state.buildings.size} 栋 / " +
                            "${state.buildings.sumOf { it.elementCount }} 个元素",
                        fontSize = 11.sp,
                        color = NavColors.TextSecondary,
                    )
                }
                TextButton(
                    onClick = onShowList,
                    modifier = Modifier.testTag(TestTags.EditorOutlineList),
                ) { Text("清单") }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onFinish,
                    enabled = state.canFinishDraft && state.canDraw,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TestTags.EditorFinish),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(text = "  成面", fontSize = 14.sp)
                }
                Button(
                    onClick = onSave,
                    enabled = state.buildings.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TestTags.EditorSave),
                ) { Text("保存导入", fontSize = 14.sp) }
            }
        }
    }
}

/** 一行状态说明，告诉用户「现在在画什么、还差什么」。 */
private fun statusLine(state: EditorUiState): String = when {
    !state.canDraw -> "先点「清单」选一栋楼，${state.mode.label}要画在楼里"

    // 单点元素：点一下就行
    state.mode.isPoint && state.isCrossFloorMode ->
        "点一下放置${state.mode.label}，将覆盖 " +
            "${state.floorLevel}→${state.endFloorLevel} 楼"

    state.mode.isPoint -> "点一下放置${state.mode.label}（不用拖、不用画边界）"

    // 拖拽绘制
    state.draftPoints.isEmpty() && state.mode == EditorMode.Building ->
        "按住拖动框出楼栋外轮廓（也可以只拖一个大概范围）"

    state.draftPoints.isEmpty() ->
        "在「${state.targetBuilding?.name}」${state.floorLevel} 楼按住拖出${state.mode.label}范围"

    !state.canFinishDraft -> "拖动的范围太小，往对角方向多拖一点"

    else -> "范围已框好，点「成面」确认"
}

/**
 * 命名弹窗。
 *
 * 教学楼模式额外让用户填「楼层数」—— 它是楼栋数据的一部分，
 * 后续楼层选择器要用它做上限，不然用户得手动敲层号。
 */
@Composable
private fun NameDialog(
    state: EditorUiState,
    onDismiss: () -> Unit,
    onConfirm: (name: String, floorCount: Int?) -> Unit,
) {
    val mode = state.mode
    var name by rememberSaveable(mode.name) {
        mutableStateOf(
            state.buildings.firstOrNull { it.name == state.draftName.trim() }?.name
                ?: state.draftName.ifBlank { mode.label },
        )
    }
    var floorCountText by rememberSaveable(mode.name) {
        mutableStateOf(
            state.buildings.firstOrNull { it.name == state.draftName.trim() }?.floorCount?.toString()
                ?: com.school.nav.core.data.EditorBuilding.DEFAULT_FLOOR_COUNT.toString(),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("给这个${mode.label}起个名字") },
        text = {
            Column {
                Text(
                    text = "名字会出现在导航文案里，所以要填真实中文名。",
                    fontSize = 12.sp,
                    color = NavColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("名称") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.EditorNameField),
                )
                if (mode == EditorMode.Building) {
                    OutlinedTextField(
                        value = floorCountText,
                        onValueChange = { floorCountText = it.filter { c -> c.isDigit() }.take(3) },
                        singleLine = true,
                        label = { Text("楼层数") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .testTag(TestTags.EditorFloorCountField),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, floorCountText.toIntOrNull()) },
                modifier = Modifier.testTag(TestTags.EditorFinishConfirm),
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
