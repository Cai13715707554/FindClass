package com.school.nav.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.school.nav.core.data.BuildingOutline
import com.school.nav.state.EditorUiState
import com.school.nav.ui.components.EmptyHint
import com.school.nav.ui.components.NavBarSpace
import com.school.nav.ui.components.NavCard
import com.school.nav.ui.components.SectionLabel
import com.school.nav.ui.components.TestTags
import com.school.nav.ui.theme.NavColors

/**
 * 地图编辑器页（导航栏「地图」）。
 *
 * 第一版只画**楼栋外轮廓**：在真实地图上依次点出顶点，围成多边形，命名后保存成配置文件。
 * 楼层与房间元素留给后续版本。
 *
 * 没配高德 Key 时不渲染地图，而是给一段明确的引导 —— 地图 SDK 缺 Key 时只会白屏，
 * 那种「什么都不显示」的失败方式对用户毫无帮助。
 */
@Composable
fun MapEditorScreen(
    state: EditorUiState,
    referenceBuildings: List<BuildingOutline>,
    onMapClick: (com.school.nav.core.model.LngLat) -> Unit,
    onUndo: () -> Unit,
    onFinish: () -> Unit,
    onCancelDraft: () -> Unit,
    onNameChange: (String) -> Unit,
    onRemove: (String) -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
    onGoSettings: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = 12.dp,
        end = 12.dp,
        top = 24.dp,
        bottom = NavBarSpace,
    ),
) {
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 12.dp, end = 12.dp),
    ) {
        // ---- 标题 ----
        Column(modifier = Modifier.padding(top = contentPadding.calculateTopPadding())) {
            Text(
                text = "地图编辑器",
                style = MaterialTheme.typography.titleLarge,
                color = NavColors.TextPrimary,
            )
            Text(
                text = "在真实地图上点选楼栋外轮廓，保存后自动导入",
                style = MaterialTheme.typography.bodyMedium,
                color = NavColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
        }

        if (!state.hasApiKey) {
            // ---- 没配 Key：给引导，而不是白屏地图 ----
            NavCard(modifier = Modifier.testTag(TestTags.EditorNoKey)) {
                SectionLabel(text = "需要先配置高德地图 Key")
                EmptyHint(
                    text = "地图渲染依赖高德 SDK。Key 与「包名 + 签名 SHA1」绑定，" +
                        "需要在高德开放平台申请后填到设置里。",
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
                Button(
                    onClick = onGoSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.EditorGoSettings),
                ) { Text("去设置里填写 Key") }
            }
            return@Column
        }

        // ---- 地图 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 240.dp),
        ) {
            AmapEditorView(
                apiKey = state.apiKey,
                draftPoints = state.draftPoints,
                outlines = state.outlines,
                referenceBuildings = referenceBuildings,
                onMapClick = onMapClick,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(TestTags.EditorMap),
            )
        }

        // ---- 绘制操作区 ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 10.dp, bottom = contentPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DraftCard(
                state = state,
                onNameChange = onNameChange,
                onUndo = onUndo,
                onFinish = onFinish,
                onCancelDraft = onCancelDraft,
            )

            OutlinesCard(
                outlines = state.outlines,
                pendingDelete = pendingDelete,
                onAskDelete = { pendingDelete = it },
                onCancelDelete = { pendingDelete = null },
                onConfirmDelete = { id ->
                    onRemove(id)
                    pendingDelete = null
                },
            )

            SaveCard(state = state, onSave = onSave, onReload = onReload)
        }
    }
}

/** 正在画的多边形：顶点数、命名、撤销/成面。 */
@Composable
private fun DraftCard(
    state: EditorUiState,
    onNameChange: (String) -> Unit,
    onUndo: () -> Unit,
    onFinish: () -> Unit,
    onCancelDraft: () -> Unit,
) {
    NavCard(modifier = Modifier.testTag(TestTags.EditorDraftCard)) {
        SectionLabel(text = "正在绘制", dotColor = NavColors.Brand)

        Text(
            text = if (state.draftPoints.isEmpty()) {
                "在地图上依次点选楼栋的拐角。至少 ${EditorUiState.MIN_POLYGON_POINTS} 个点。"
            } else {
                "已选 ${state.draftPoints.size} 个点" +
                    if (!state.canFinishDraft) "，还需要 ${EditorUiState.MIN_POLYGON_POINTS - state.draftPoints.size} 个" else ""
            },
            fontSize = 14.sp,
            color = NavColors.TextSecondary,
            modifier = Modifier
                .padding(top = 8.dp, bottom = 8.dp)
                .testTag(TestTags.EditorDraftHint),
        )

        OutlinedTextField(
            value = state.draftName,
            onValueChange = onNameChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.EditorNameField),
            singleLine = true,
            label = { Text("楼栋名称，如 A栋") },
            shape = MaterialTheme.shapes.small,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onUndo,
                enabled = state.draftPoints.isNotEmpty(),
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.EditorUndo),
            ) { Text("撤销点") }

            OutlinedButton(
                onClick = onCancelDraft,
                enabled = state.draftPoints.isNotEmpty(),
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.EditorCancelDraft),
            ) { Text("重画") }

            Button(
                onClick = onFinish,
                enabled = state.canFinishDraft,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.EditorFinish),
            ) { Text("成面") }
        }
    }
}

/** 已画好的楼栋列表。 */
@Composable
private fun OutlinesCard(
    outlines: List<BuildingOutline>,
    pendingDelete: String?,
    onAskDelete: (String) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: (String) -> Unit,
) {
    NavCard(modifier = Modifier.testTag(TestTags.EditorOutlineList)) {
        SectionLabel(text = "已绘制（${outlines.size}）", dotColor = NavColors.Green)

        if (outlines.isEmpty()) {
            EmptyHint(
                text = "还没有画好的楼栋。",
                modifier = Modifier.padding(top = 8.dp),
            )
            return@NavCard
        }

        outlines.forEach { outline ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = outline.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = NavColors.TextPrimary,
                    )
                    Text(
                        text = "${outline.polygon.size} 个顶点 · id=${outline.id}",
                        fontSize = 12.sp,
                        color = NavColors.TextSecondary,
                    )
                }

                if (pendingDelete == outline.id) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onCancelDelete) { Text("取消") }
                        Button(
                            onClick = { onConfirmDelete(outline.id) },
                            modifier = Modifier.testTag(TestTags.EditorDeleteConfirm),
                        ) { Text("删除") }
                    }
                } else {
                    OutlinedButton(
                        onClick = { onAskDelete(outline.id) },
                        modifier = Modifier.testTag("${TestTags.EditorDelete}_${outline.id}"),
                    ) { Text("删除") }
                }
            }
        }
    }
}

/** 保存区：把结果写到内部 + 外部两份配置，并把真实路径显示出来。 */
@Composable
private fun SaveCard(
    state: EditorUiState,
    onSave: () -> Unit,
    onReload: () -> Unit,
) {
    NavCard(modifier = Modifier.testTag(TestTags.EditorSaveCard)) {
        SectionLabel(text = "保存", dotColor = NavColors.Brand)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onSave,
                enabled = state.hasSomethingToSave,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.EditorSave),
            ) { Text("保存并导入") }

            OutlinedButton(
                onClick = onReload,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.EditorReload),
            ) { Text("重新加载") }
        }

        val save = state.lastSave
        if (save != null) {
            Text(
                text = buildString {
                    append("内部：")
                    append(save.internalPath ?: "写入失败")
                    append("\n外部：")
                    append(save.externalPath ?: "不可用")
                },
                fontSize = 12.sp,
                color = NavColors.TextSecondary,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .testTag(TestTags.EditorSavePath),
            )
            save.warnings.forEach { warning ->
                Text(
                    text = "⚠ $warning",
                    fontSize = 12.sp,
                    color = NavColors.Green,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Text(
            text = "保存后会写入应用私有目录，并同时在外部目录留一份，" +
                "可用数据线或文件管理器取出。下次启动 App 时自动生效。",
            fontSize = 12.sp,
            color = NavColors.TextSecondary,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}
