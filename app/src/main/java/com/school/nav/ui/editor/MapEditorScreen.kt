package com.school.nav.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.school.nav.core.data.EditorMode
import com.school.nav.core.model.LngLat
import com.school.nav.data.PoiResult
import com.school.nav.data.PoiSearcher
import com.school.nav.state.EditorUiState
import com.school.nav.ui.components.NavBarSpace
import com.school.nav.ui.components.TestTags
import com.school.nav.ui.theme.NavColors
import kotlinx.coroutines.launch

/**
 * 地图编辑器页（导航栏「地图」）。
 *
 * 布局原则：**地图占满全屏**，所有控件都是浮在地图上的小卡片，
 * 而不是把地图压在下面留半屏给面板 —— 画轮廓时视野越大越好。
 *
 * 控件分布：
 *  - 顶部：搜索框（找地点，用户在家也能定位到学校）+ 右上角绘制模式下拉；
 *  - 右侧：定位、撤销、放弃当前绘制的圆形按钮；
 *  - 底部：状态一行 + 「成面」「保存导入」；
 *  - 楼栋/元素清单收进弹层，不占常驻空间。
 */
@Composable
fun MapEditorScreen(
    state: EditorUiState,
    onMapClick: (LngLat) -> Unit,
    onModeChange: (EditorMode) -> Unit,
    onTargetBuildingChange: (String?) -> Unit,
    onFloorChange: (Int) -> Unit,
    onNameChange: (String) -> Unit,
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
) {
    val context = LocalContext.current
    val searcher = remember { PoiSearcher(context) }
    val scope = rememberCoroutineScope()

    var showList by rememberSaveable { mutableStateOf(false) }
    var showNameDialog by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<PoiResult>()) }

    if (!state.hasApiKey) {
        EditorNeedsKey(onGoSettings = onGoSettings, modifier = modifier)
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.EditorScreen),
    ) {
        // ---- 全屏地图 ----
        AmapEditorView(
            draftPoints = state.draftPoints,
            buildings = state.buildings,
            centerRequest = state.centerRequest,
            onMapClick = onMapClick,
            onCenterConsumed = onCenterConsumed,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .testTag(TestTags.EditorMap),
        )

        // ---- 顶部：搜索 + 模式下拉 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SearchBox(
                text = searchText,
                searching = searching,
                enabled = searcher.isAvailable(),
                modifier = Modifier.weight(1f),
                onTextChange = {
                    searchText = it
                    results = emptyList()
                },
                onSubmit = { query ->
                    if (query.isBlank()) return@SearchBox
                    searching = true
                    scope.launch {
                        results = searcher.search(query)
                        searching = false
                    }
                },
            )

            ModeDropdown(current = state.mode, onSelect = onModeChange)
        }

        // 搜索结果浮层
        if (results.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, end = 96.dp, top = 76.dp)
                    .heightIn(max = 260.dp)
                    .testTag(TestTags.EditorSearchResults),
                shape = MaterialTheme.shapes.medium,
                color = NavColors.Card,
                shadowElevation = 6.dp,
            ) {
                LazyColumn {
                    items(results) { result ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onGoToPoint(result.point)
                                    searchText = result.name
                                    results = emptyList()
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = result.name,
                                fontSize = 14.sp,
                                color = NavColors.TextPrimary,
                                maxLines = 1,
                            )
                            Text(
                                text = "%.5f, %.5f".format(result.point.lat, result.point.lng),
                                fontSize = 11.sp,
                                color = NavColors.TextSecondary,
                            )
                        }
                    }
                }
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

    if (showList) {
        BuildingListDialog(
            state = state,
            onDismiss = { showList = false },
            onTargetBuildingChange = onTargetBuildingChange,
            onFloorChange = onFloorChange,
            onRemoveBuilding = onRemoveBuilding,
            onRemoveElement = onRemoveElement,
            onReload = onReload,
        )
    }

    if (showNameDialog) {
        NameDialog(
            mode = state.mode,
            initial = state.draftName,
            onDismiss = { showNameDialog = false },
            onConfirm = { name ->
                onNameChange(name)
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
            .fillMaxWidth()
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

/** 顶部搜索框。用于用户不在学校时定位到目标地点。 */
@Composable
private fun SearchBox(
    text: String,
    searching: Boolean,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = NavColors.Card,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = NavColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.EditorSearchField),
                singleLine = true,
                enabled = enabled,
                placeholder = {
                    Text(
                        text = if (enabled) "搜索地点，如 某某大学" else "本机不支持地点搜索",
                        fontSize = 13.sp,
                        color = NavColors.TextSecondary,
                    )
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
        }
    }
}

/** 右上角绘制模式下拉。 */
@Composable
private fun ModeDropdown(current: EditorMode, onSelect: (EditorMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box {
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
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = current.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = NavColors.Brand,
                )
                Text(text = "▾", fontSize = 13.sp, color = NavColors.Brand)
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
    state.draftPoints.isEmpty() && state.mode == EditorMode.Building ->
        "点选楼栋拐角，至少 ${EditorUiState.MIN_POLYGON_POINTS} 个点"

    state.draftPoints.isEmpty() ->
        "在「${state.targetBuilding?.name}」${state.floorLevel} 楼点选${state.mode.label}边界"

    !state.canFinishDraft ->
        "已选 ${state.draftPoints.size} 个点，还需 ${EditorUiState.MIN_POLYGON_POINTS - state.draftPoints.size} 个"

    else -> "已选 ${state.draftPoints.size} 个点，可以成面"
}

/** 命名弹窗。成面之后再命名，避免打断绘制节奏。 */
@Composable
private fun NameDialog(
    mode: EditorMode,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(initial) { mutableStateOf(initial.ifBlank { mode.label }) }

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
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.EditorNameField),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                modifier = Modifier.testTag(TestTags.EditorFinishConfirm),
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
