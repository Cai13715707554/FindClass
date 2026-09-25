package com.school.nav.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.school.nav.core.model.Building
import com.school.nav.core.model.Element
import com.school.nav.core.model.Floor
import com.school.nav.ui.theme.NavColors

/**
 * 修改当前位置的底部弹层：楼栋 -> 楼层 -> 当前位置 三级联动。
 *
 * 行为约定（产品文档 6.1「用户修正」）：
 *  - 三级都可以改；
 *  - 点“确定”后立即更新当前位置并重算导航（由 ViewModel 的派生状态自动完成）；
 *  - 手动修改优先，短时间内不被自动定位覆盖。
 *
 * 弹层内部用独立的草稿状态，只有点“确定”才写回，避免用户中途改主意时位置被改掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPositionSheet(
    buildings: List<Building>,
    currentBuilding: Building?,
    currentFloor: Floor?,
    currentElement: Element?,
    onDismiss: () -> Unit,
    onConfirm: (building: Building, floor: Floor, element: Element) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 草稿状态：默认跟随当前定位结果
    var draftBuilding by remember {
        mutableStateOf(currentBuilding ?: buildings.firstOrNull())
    }
    var draftFloor by remember {
        mutableStateOf(currentFloor ?: draftBuilding?.orderedFloors?.firstOrNull())
    }
    var draftElement by remember {
        mutableStateOf(currentElement ?: draftFloor?.elements?.firstOrNull())
    }

    // 楼层随楼栋联动；元素随楼层联动
    val floors = draftBuilding?.orderedFloors.orEmpty()
    val elements = draftFloor?.elements.orEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NavColors.Card,
        modifier = Modifier.testTag(TestTags.EditSheet),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, bottom = 28.dp),
        ) {
            Text(
                text = "修改当前位置",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Text(
                text = "手动修改后不会被自动定位覆盖，点“重新定位”才会重新采用 GPS 和气压计。",
                style = MaterialTheme.typography.bodyMedium,
                color = NavColors.TextSecondary,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // ---- 教学楼 ----
            DropdownField(
                label = "教学楼",
                value = draftBuilding?.name.orEmpty(),
                options = buildings.map { it.name },
                testTag = TestTags.EditBuilding,
                onSelect = { index ->
                    val building = buildings[index]
                    draftBuilding = building
                    draftFloor = building.orderedFloors.firstOrNull()
                    draftElement = draftFloor?.elements?.firstOrNull()
                },
            )

            // ---- 楼层 ----
            DropdownField(
                label = "楼层",
                value = draftFloor?.displayName.orEmpty(),
                options = floors.map { it.displayName },
                testTag = TestTags.EditFloor,
                onSelect = { index ->
                    val floor = floors[index]
                    draftFloor = floor
                    draftElement = floor.elements.firstOrNull()
                },
            )

            // ---- 当前位置 ----
            DropdownField(
                label = "当前位置",
                value = draftElement?.name.orEmpty(),
                options = elements.map { it.name },
                testTag = TestTags.EditElement,
                onSelect = { index -> draftElement = elements[index] },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 17.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TestTags.EditCancel),
                ) { Text("取消") }

                Button(
                    onClick = {
                        val b = draftBuilding
                        val f = draftFloor
                        val e = draftElement
                        if (b != null && f != null && e != null) onConfirm(b, f, e)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TestTags.EditConfirm),
                ) { Text("确定") }
            }
        }
    }
}

/** 统一样式的一级下拉选择。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    testTag: String,
    onSelect: (index: Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = NavColors.TextSecondary,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .testTag(testTag),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onSelect(index)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
