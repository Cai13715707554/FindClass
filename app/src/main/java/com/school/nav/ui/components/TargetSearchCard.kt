package com.school.nav.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.school.nav.core.data.SearchHit
import com.school.nav.ui.theme.NavColors

/**
 * 目标搜索卡片。
 *
 * 支持两种用法（产品文档 6.2）：
 *  - 输入中文名后回车 / 点“导航”，取最佳匹配；
 *  - 直接点快捷目标按钮。
 *
 * 边输入边给候选，避免用户不知道数据里到底有哪些教室名。MVP 不要求输入教室编号。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TargetSearchCard(
    onNavigate: (String) -> Unit,
    quickTargets: List<SearchHit>,
    onSuggest: (String) -> List<SearchHit>,
    modifier: Modifier = Modifier,
) {
    var keyword by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    // 提交后强制刷新一次候选（同样的关键字也要重新算，因为当前位置可能变了）
    var refreshTick by remember { mutableIntStateOf(0) }

    val suggestions = remember(keyword, refreshTick) {
        if (keyword.isBlank()) emptyList() else onSuggest(keyword)
    }

    val submit: (String) -> Unit = { value ->
        val trimmed = value.trim()
        if (trimmed.isNotEmpty()) {
            onNavigate(trimmed)
            keyword = ""
            focused = false
            refreshTick++
        }
    }

    NavCard(modifier = modifier) {
        SectionLabel(text = "要去哪里", dotColor = NavColors.Green)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = keyword,
                onValueChange = {
                    keyword = it
                    focused = true
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.SearchField),
                singleLine = true,
                placeholder = {
                    Text(
                        text = "输入教室名，如 物理实验室",
                        color = NavColors.TextSecondary,
                        fontSize = 15.sp,
                    )
                },
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = NavColors.Card,
                    unfocusedContainerColor = NavColors.FieldBackground,
                    focusedBorderColor = NavColors.Brand,
                    unfocusedBorderColor = NavColors.Divider,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(keyword) }),
            )

            Button(
                onClick = { submit(keyword) },
                modifier = Modifier.testTag(TestTags.SearchButton),
                shape = MaterialTheme.shapes.small,
            ) { Text("导航") }
        }

        if (focused && suggestions.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                suggestions.forEach { hit ->
                    SuggestionRow(hit = hit, onClick = { submit(hit.element.name) })
                }
            }
        }

        if (quickTargets.isNotEmpty()) {
            Text(
                text = "快捷目标",
                style = MaterialTheme.typography.labelMedium,
                color = NavColors.TextSecondary,
                modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                quickTargets.forEach { hit ->
                    QuickChip(
                        label = hit.element.name,
                        onClick = { submit(hit.element.name) },
                    )
                }
            }
        }
    }
}

/** 一条搜索候选：中文名 + 所属楼栋楼层。 */
@Composable
private fun SuggestionRow(hit: SearchHit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .testTag(TestTags.SuggestionItem),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = hit.element.name,
            style = MaterialTheme.typography.bodyLarge,
            color = NavColors.TextPrimary,
        )
        Text(
            text = "${hit.building.name} · ${hit.floor.displayName}",
            style = MaterialTheme.typography.bodyMedium,
            color = NavColors.TextSecondary,
        )
    }
}

/** 快捷目标胶囊按钮。 */
@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(NavColors.ChipBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp)
            .testTag("${TestTags.QuickTarget}_$label"),
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            color = NavColors.ChipText,
        )
    }
}
