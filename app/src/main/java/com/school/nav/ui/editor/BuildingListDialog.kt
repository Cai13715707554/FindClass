package com.school.nav.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.school.nav.core.data.EditorBuilding
import com.school.nav.state.EditorUiState
import com.school.nav.state.MAX_FLOOR_COUNT
import com.school.nav.ui.components.TestTags
import com.school.nav.ui.theme.NavColors

/**
 * 楼栋与元素清单弹层。
 *
 * 承担三件事：
 *  - **选目标楼栋 + 选楼层**：楼层内元素必须先有宿主楼栋与楼层，不选就不让画；
 *  - **改楼层数**：楼栋的名义层数，决定层号选择器的上限；
 *  - **删除**：删楼栋或删单个元素。
 *
 * 收进弹层而不是常驻页面：画轮廓时屏幕空间很宝贵，清单只在上面这些操作时才需要。
 */
@Composable
fun BuildingListDialog(
    state: EditorUiState,
    onDismiss: () -> Unit,
    onTargetBuildingChange: (String?) -> Unit,
    onFloorChange: (Int) -> Unit,
    onEndFloorChange: (Int) -> Unit,
    onBuildingFloorCountChange: (String, Int) -> Unit,
    onRemoveBuilding: (String) -> Unit,
    onRemoveElement: (String, Int, String) -> Unit,
    onReload: () -> Unit,
) {
    var pendingDeleteBuilding by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("已绘制清单") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
            ) {
                if (state.buildings.isEmpty()) {
                    Text(
                        text = "还没有画好的楼栋。先在图上点选楼栋外轮廓。",
                        fontSize = 13.sp,
                        color = NavColors.TextSecondary,
                    )
                    return@Column
                }

                if (state.mode.needsBuilding) {
                    Text(
                        text = "「${state.mode.label}」必须画在楼里：先点一栋楼作为宿主，" +
                            "再选楼层。",
                        fontSize = 12.sp,
                        color = NavColors.Brand,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.buildings, key = { it.id }) { building ->
                        BuildingRow(
                            building = building,
                            state = state,
                            pendingDelete = pendingDeleteBuilding == building.id,
                            onSelect = { onTargetBuildingChange(building.id) },
                            onFloorChange = onFloorChange,
                            onEndFloorChange = onEndFloorChange,
                            onFloorCountChange = { onBuildingFloorCountChange(building.id, it) },
                            onAskDelete = { pendingDeleteBuilding = building.id },
                            onCancelDelete = { pendingDeleteBuilding = null },
                            onConfirmDelete = {
                                onRemoveBuilding(building.id)
                                pendingDeleteBuilding = null
                            },
                            onRemoveElement = { level, elementId ->
                                onRemoveElement(building.id, level, elementId)
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onReload,
                    modifier = Modifier.testTag(TestTags.EditorReload),
                ) { Text("重新加载文件") }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(TestTags.EditorListClose),
                ) { Text("关闭") }
            }
        },
    )
}

/** 一栋楼：名称、轮廓、楼层数、选中目标、楼层选择、删除。 */
@Composable
private fun BuildingRow(
    building: EditorBuilding,
    state: EditorUiState,
    pendingDelete: Boolean,
    onSelect: () -> Unit,
    onFloorChange: (Int) -> Unit,
    onEndFloorChange: (Int) -> Unit,
    onFloorCountChange: (Int) -> Unit,
    onAskDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onRemoveElement: (Int, String) -> Unit,
) {
    val selected = building.id == state.targetBuildingId
    var editingCount by remember { mutableStateOf(false) }
    var countText by remember(building.floorCount) { mutableStateOf(building.floorCount.toString()) }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) NavColors.HighlightBackground else NavColors.FieldBackground,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .testTag("${TestTags.EditorBuildingRow}_${building.id}"),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = state.mode.needsBuilding, onClick = onSelect),
                ) {
                    Text(
                        text = building.name + if (selected) "  ✓ 当前目标" else "",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (selected) NavColors.Brand else NavColors.TextPrimary,
                    )
                    Text(
                        text = buildString {
                            append(building.floorCount).append(" 层")
                            append(" · ")
                            append(if (building.hasValidPolygon) "${building.polygon.size} 个轮廓点" else "无轮廓")
                            append(" · ${building.elementCount} 个元素")
                        },
                        fontSize = 11.sp,
                        color = NavColors.TextSecondary,
                    )
                }

                if (pendingDelete) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = onCancelDelete) { Text("取消", fontSize = 13.sp) }
                        TextButton(
                            onClick = onConfirmDelete,
                            modifier = Modifier.testTag(TestTags.EditorDeleteConfirm),
                        ) {
                            Text("删除", fontSize = 13.sp, color = Color(0xFFD92D20))
                        }
                    }
                } else {
                    IconButton(
                        onClick = onAskDelete,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("${TestTags.EditorDelete}_${building.id}"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "删除这栋楼",
                            tint = NavColors.TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // 楼层数：决定层号选择器的上限
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "楼层数", fontSize = 12.sp, color = NavColors.TextSecondary)
                if (editingCount) {
                    OutlinedTextField(
                        value = countText,
                        onValueChange = { countText = it.filter { c -> c.isDigit() }.take(3) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("${TestTags.EditorFloorCountField}_${building.id}"),
                    )
                    TextButton(
                        onClick = {
                            onFloorCountChange(countText.toIntOrNull() ?: building.floorCount)
                            editingCount = false
                        },
                        modifier = Modifier.testTag("${TestTags.EditorFloorCountSave}_${building.id}"),
                    ) { Text("保存", fontSize = 13.sp) }
                } else {
                    Text(
                        text = "${building.floorCount} 层",
                        fontSize = 13.sp,
                        color = NavColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { editingCount = true },
                        modifier = Modifier.testTag("${TestTags.EditorFloorCountEdit}_${building.id}"),
                    ) { Text("修改", fontSize = 13.sp) }
                }
            }

            // 只在被选为目标时才给楼层选择，避免清单里到处是下拉框
            if (selected && state.mode.needsBuilding) {
                FloorPicker(
                    state = state,
                    building = building,
                    onFloorChange = onFloorChange,
                    onEndFloorChange = onEndFloorChange,
                )
            }

            // 已画的元素：按层列出，可逐条删除
            building.floors.sortedBy { it.level }.forEach { floor ->
                if (floor.elements.isEmpty()) return@forEach
                Text(
                    text = "${floor.level} 楼",
                    fontSize = 11.sp,
                    color = NavColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                floor.elements.forEach { element ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = buildString {
                                append("  ${element.elementType.displayName} · ${element.name}")
                                element.toLevel?.let { append("（$it 楼止）") }
                            },
                            fontSize = 12.sp,
                            color = NavColors.TextPrimary,
                        )
                        IconButton(
                            onClick = { onRemoveElement(floor.level, element.id) },
                            modifier = Modifier
                                .size(26.dp)
                                .testTag("${TestTags.EditorRemoveElement}_${element.id}"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "删除 ${element.name}",
                                tint = NavColors.TextSecondary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 楼层选择。
 *
 * 跨层模式（楼梯）给**两个**选择器：起始层与结束层 ——
 * 楼梯是上下楼用的，物理上穿过楼板，不属于单独某一层，
 * 所以合并进导航数据时会在它覆盖的每一层都生成一份同名楼梯。
 */
@Composable
private fun FloorPicker(
    state: EditorUiState,
    building: EditorBuilding,
    onFloorChange: (Int) -> Unit,
    onEndFloorChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FloorDropdown(
            label = if (state.isCrossFloorMode) "起始层" else "楼层",
            current = state.floorLevel,
            options = building.levelRange,
            testTag = TestTags.EditorFloorPicker,
            onSelect = onFloorChange,
        )
        if (state.isCrossFloorMode) {
            FloorDropdown(
                label = "结束层",
                current = state.endFloorLevel,
                // 结束层不能低于起始层
                options = state.floorLevel..building.levelRange.last,
                testTag = TestTags.EditorEndFloorPicker,
                onSelect = onEndFloorChange,
            )
        }
    }
}

/** 单个楼层下拉。`options` 为空区间时也要能正常显示。 */
@Composable
private fun FloorDropdown(
    label: String,
    current: Int,
    options: IntRange,
    testTag: String,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(text = label, fontSize = 11.sp, color = NavColors.TextSecondary)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = NavColors.Card,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .testTag(testTag),
        ) {
            Text(
                text = "$current 楼 ▾",
                fontSize = 13.sp,
                color = NavColors.Brand,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.take(MAX_FLOOR_COUNT).forEach { level ->
                DropdownMenuItem(
                    text = { Text("$level 楼") },
                    onClick = {
                        onSelect(level)
                        expanded = false
                    },
                    modifier = Modifier.testTag("${testTag}_$level"),
                )
            }
        }
    }
}
