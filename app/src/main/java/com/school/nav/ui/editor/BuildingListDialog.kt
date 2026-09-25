package com.school.nav.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.school.nav.ui.components.TestTags
import com.school.nav.ui.theme.NavColors

/**
 * 楼栋与元素清单弹层。
 *
 * 收进弹层而不是常驻在页面上：画轮廓时屏幕空间很宝贵，
 * 而清单只在「选目标楼栋」和「删掉画错的东西」时才需要。
 *
 * 这里同时承担两件事：
 *  - **选目标楼栋 + 选楼层**：楼层内元素必须先有宿主楼栋；
 *  - **删除**：删楼栋或删单个元素。
 */
@Composable
fun BuildingListDialog(
    state: EditorUiState,
    onDismiss: () -> Unit,
    onTargetBuildingChange: (String?) -> Unit,
    onFloorChange: (Int) -> Unit,
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
                    .heightIn(max = 420.dp),
            ) {
                if (state.buildings.isEmpty()) {
                    Text(
                        text = "还没有画好的楼栋。先在地图上点选外轮廓。",
                        fontSize = 13.sp,
                        color = NavColors.TextSecondary,
                    )
                    return@Column
                }

                if (state.mode.needsBuilding) {
                    Text(
                        text = "当前是「${state.mode.label}」模式，点一栋楼作为它的宿主：",
                        fontSize = 12.sp,
                        color = NavColors.TextSecondary,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.buildings, key = { it.id }) { building ->
                        BuildingRow(
                            building = building,
                            selected = building.id == state.targetBuildingId,
                            currentFloor = state.floorLevel,
                            selectable = state.mode.needsBuilding,
                            pendingDelete = pendingDeleteBuilding == building.id,
                            onSelect = { onTargetBuildingChange(building.id) },
                            onFloorChange = onFloorChange,
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

/** 一栋楼：名称、轮廓点数、楼层元素、以及选中/删除操作。 */
@Composable
private fun BuildingRow(
    building: EditorBuilding,
    selected: Boolean,
    currentFloor: Int,
    selectable: Boolean,
    pendingDelete: Boolean,
    onSelect: () -> Unit,
    onFloorChange: (Int) -> Unit,
    onAskDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onRemoveElement: (Int, String) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) NavColors.HighlightBackground else NavColors.FieldBackground,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .then(
                if (selectable) {
                    Modifier.clickable(onClick = onSelect)
                } else {
                    Modifier
                },
            )
            .testTag("${TestTags.EditorBuildingRow}_${building.id}"),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = building.name + if (selected) "  ✓ 目标" else "",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (selected) NavColors.Brand else NavColors.TextPrimary,
                    )
                    Text(
                        text = buildString {
                            append(if (building.hasValidPolygon) "${building.polygon.size} 个轮廓点" else "无轮廓")
                            append(" · ${building.elementCount} 个元素")
                        },
                        fontSize = 11.sp,
                        color = NavColors.TextSecondary,
                    )
                }

                if (pendingDelete) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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

            // 楼层内元素：按层展示，可逐条删除
            building.floors.sortedBy { it.level }.forEach { floor ->
                if (floor.elements.isEmpty()) return@forEach
                Text(
                    text = "${floor.level} 楼 · 点一下切换当前楼层",
                    fontSize = 11.sp,
                    color = if (floor.level == currentFloor && selected) {
                        NavColors.Brand
                    } else {
                        NavColors.TextSecondary
                    },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clickable(
                            enabled = selectable,
                            onClick = {
                                onSelect()
                                onFloorChange(floor.level)
                            },
                        ),
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
                            text = "  ${element.elementType.displayName} · ${element.name}",
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
