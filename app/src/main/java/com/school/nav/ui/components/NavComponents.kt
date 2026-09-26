package com.school.nav.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.school.nav.core.navigation.RouteStep
import com.school.nav.location.LocationAvailability
import com.school.nav.state.CurrentSource
import com.school.nav.state.FloorUiState
import com.school.nav.state.NavUiState
import com.school.nav.ui.theme.NavColors

/** 统一的卡片容器，避免每个卡片各写一遍样式。 */
@Composable
fun NavCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = NavColors.Card),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

/** 卡片小标题：一个圆点 + 文字。 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    dotColor: Color = NavColors.Brand,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = NavColors.TextSecondary,
        )
    }
}

/**
 * 当前位置卡片。
 *
 * 展示 `A栋 · 3楼 · 语文教研室`，并提供“修改位置 / 重新定位”。
 */
@Composable
fun PositionCard(
    state: NavUiState,
    onEdit: () -> Unit,
    onRelocate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavCard(modifier = modifier.testTag(TestTags.PositionCard)) {
        SectionLabel(text = "当前位置")

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isLocating) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(14.dp)
                        .testTag(TestTags.LocatingSpinner),
                    strokeWidth = 2.dp,
                    color = NavColors.Brand,
                )
                Text(
                    text = "  定位中…",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NavColors.TextPrimary,
                )
            } else {
                PositionText(state)
            }
        }

        val hint = positionHint(state)
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = NavColors.TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .testTag(TestTags.PositionHint),
            )
        } else {
            Spacer(modifier = Modifier.height(4.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onEdit,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.EditPositionButton),
            ) { Text("修改位置") }

            OutlinedButton(
                onClick = onRelocate,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.RelocateButton),
            ) { Text("重新定位") }
        }
    }
}

/** 位置主文案：楼栋与楼层用主文字色，元素名用主色强调。 */
@Composable
private fun PositionText(state: NavUiState) {
    val building = state.building
    val floor = state.floor
    val element = state.element

    if (building == null) {
        Text(
            text = "尚未定位",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = NavColors.TextSecondary,
            modifier = Modifier.testTag(TestTags.PositionValue),
        )
        return
    }

    Row(
        modifier = Modifier.testTag(TestTags.PositionValue),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = building.name,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = NavColors.TextPrimary,
        )
        Text(text = " · ", fontSize = 18.sp, color = NavColors.Divider)
        Text(
            text = floor?.displayName ?: "楼层待确认",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (floor == null) NavColors.TextSecondary else NavColors.TextPrimary,
        )
        if (floor != null && element != null) {
            Text(text = " · ", fontSize = 18.sp, color = NavColors.Divider)
            Text(
                text = element.name,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = NavColors.Brand,
            )
        }
    }
}

/**
 * 位置卡片下方的状态说明。
 *
 * 楼层未知或定位不可用时，必须明确告诉用户「为什么」和「怎么办」，
 * 而不是默默转圈或显示一个猜测值。
 *
 * 改成 public（而不是留在文件内私有）是为了让 `NavViewModel` 的测试可以直接断言
 * 「用户到底会看到哪句话」—— 定位不可用这个回归点，症状就是这句提示不出现。
 */
fun positionHint(state: NavUiState): String? = when {
    // 定位不可用优先于其他提示：这时候「定位中…」是假象，必须说清原因
    state.locationUnavailable != null -> when (state.locationUnavailable) {
        LocationAvailability.PermissionDenied ->
            "没有定位权限，请点“修改位置”手动选择当前位置。"

        LocationAvailability.ServiceDisabled ->
            "系统的定位服务已关闭，请点“修改位置”手动选择当前位置。"

        LocationAvailability.NoProvider ->
            "本机无法自动定位，请点“修改位置”手动选择当前位置。"

        LocationAvailability.Available -> null
    }

    state.isLocating -> null

    state.building == null -> "没有取到位置，可以点“修改位置”手动指定。"

    state.floorState is FloorUiState.SensorMissing ->
        "本机没有气压计，请在“修改位置”里选择楼层。"

    state.floorState is FloorUiState.Stabilizing ->
        "正在读取气压计判断楼层…"

    state.floorState is FloorUiState.Unreliable ->
        "气压数据不太准，建议点“修改位置”确认楼层。"

    state.floor == null -> "还无法判断楼层，请点“修改位置”选择。"

    state.source == CurrentSource.Manual ->
        "已按你的修改定位，点“重新定位”才会重新采用 GPS。"

    state.source == CurrentSource.LastLocked -> "室内信号弱，沿用上次锁定的楼栋。"

    else -> null
}

/** 导航指令卡片。 */
@Composable
fun RouteCard(
    steps: List<RouteStep>,
    modifier: Modifier = Modifier,
) {
    NavCard(modifier = modifier.testTag(TestTags.RouteCard)) {
        SectionLabel(text = "导航指令", dotColor = NavColors.Green)
        Column(modifier = Modifier.padding(top = 10.dp)) {
            steps.forEach { step -> HighlightedText(step = step) }
        }
    }
}

/** 一条文案，把目标中文名高亮出来。 */
@Composable
private fun HighlightedText(step: RouteStep) {
    val highlight = step.highlight
    val annotated = if (highlight.isNullOrEmpty() || !step.text.contains(highlight)) {
        buildAnnotatedString { append(step.text) }
    } else {
        buildAnnotatedString {
            val parts = step.text.split(highlight)
            parts.forEachIndexed { index, part ->
                append(part)
                if (index != parts.lastIndex) {
                    withStyle(SpanStyle(color = NavColors.Brand, fontWeight = FontWeight.SemiBold)) {
                        append(highlight)
                    }
                }
            }
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge,
        color = NavColors.TextPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
    )
}

/** 空态/提示文案。 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = NavColors.TextSecondary,
        modifier = modifier.fillMaxWidth(),
    )
}

/** 测试用语义标签，集中一处，避免测试里散落魔法字符串。 */
object TestTags {
    /** 外壳容器（含底部导航）。 */
    const val AppShell = "app_shell"

    const val HomeScreen = "home_screen"
    const val PositionCard = "position_card"
    const val PositionValue = "position_value"
    const val PositionHint = "position_hint"
    const val LocatingSpinner = "locating_spinner"
    const val EditPositionButton = "edit_position_button"
    const val RelocateButton = "relocate_button"
    const val SearchField = "search_field"
    const val SearchButton = "search_button"
    const val QuickTarget = "quick_target"
    const val SuggestionItem = "suggestion_item"
    const val RouteCard = "route_card"
    const val RouteEmpty = "route_empty"
    const val EditSheet = "edit_sheet"
    const val EditBuilding = "edit_building"
    const val EditFloor = "edit_floor"
    const val EditElement = "edit_element"
    const val EditConfirm = "edit_confirm"
    const val EditCancel = "edit_cancel"

    // ---- 底部导航 ----
    const val BottomNavBar = "bottom_nav_bar"

    /** 具体页签用 `${BottomNavItem}_${NavTab.name}` 拼，例如 bottom_nav_item_Profile。 */
    const val BottomNavItem = "bottom_nav_item"

    // ---- 我的 · 定位测试 ----
    const val ProfileScreen = "profile_screen"
    const val TestEntry = "test_entry"
    const val TestEntryRow = "test_entry_row"
    const val SensorTestCard = "sensor_test_card"
    const val SensorNoData = "sensor_no_data"
    const val SensorNoBarometer = "sensor_no_barometer"
    const val SensorLongitude = "sensor_longitude"
    const val SensorLatitude = "sensor_latitude"
    const val SensorUpdatedAt = "sensor_updated_at"
    const val SensorAccuracy = "sensor_accuracy"
    const val SensorProvider = "sensor_provider"
    const val SensorAltitude = "sensor_altitude"
    const val SensorAltitudeSource = "sensor_altitude_source"
    const val SensorPressure = "sensor_pressure"
    const val SensorBuilding = "sensor_building"
    const val SensorFloor = "sensor_floor"
    const val SensorElement = "sensor_element"

    // ---- 我的 · 设置 ----
    const val SettingsEntry = "settings_entry"
    const val SettingsScreen = "settings_screen"
    const val SettingsBack = "settings_back"
    const val SettingsKeyField = "settings_key_field"
    const val SettingsSaveKey = "settings_save_key"
    const val SettingsClearKey = "settings_clear_key"
    const val SettingsKeyState = "settings_key_state"
    const val SettingsWebKeyField = "settings_web_key_field"
    const val SettingsSaveWebKey = "settings_save_web_key"
    const val SettingsClearWebKey = "settings_clear_web_key"
    const val SettingsWebKeyState = "settings_web_key_state"

    // ---- 地图编辑器 ----
    const val EditorScreen = "editor_screen"
    const val EditorMap = "editor_map"
    const val EditorNoKey = "editor_no_key"
    const val EditorGoSettings = "editor_go_settings"

    /** 右上角绘制模式下拉；具体项用 `${EditorModeItem}_${EditorMode.name}`。 */
    const val EditorModeDropdown = "editor_mode_dropdown"
    const val EditorModeItem = "editor_mode_item"

    const val EditorSearchField = "editor_search_field"
    const val EditorSearchInput = "editor_search_input"
    const val EditorSearchClose = "editor_search_close"
    const val EditorSearchResults = "editor_search_results"
    const val EditorSearchResultItem = "editor_search_result_item"
    const val EditorMyLocation = "editor_my_location"

    const val EditorFloorCountField = "editor_floor_count_field"
    const val EditorFloorCountEdit = "editor_floor_count_edit"
    const val EditorFloorCountSave = "editor_floor_count_save"
    const val EditorFloorPicker = "editor_floor_picker"
    const val EditorEndFloorPicker = "editor_end_floor_picker"

    const val EditorDraftHint = "editor_draft_hint"
    const val EditorNameField = "editor_name_field"
    const val EditorUndo = "editor_undo"
    const val EditorCancelDraft = "editor_cancel_draft"
    const val EditorFinish = "editor_finish"
    const val EditorFinishConfirm = "editor_finish_confirm"
    const val EditorOutlineList = "editor_outline_list"
    const val EditorListClose = "editor_list_close"
    const val EditorSave = "editor_save"
    const val EditorReload = "editor_reload"
    const val EditorSavePath = "editor_save_path"

    /** 每行按钮用 `${EditorDelete}_${id}` 之类拼。 */
    const val EditorDelete = "editor_delete"
    const val EditorDeleteConfirm = "editor_delete_confirm"
    const val EditorBuildingRow = "editor_building_row"
    const val EditorRemoveElement = "editor_remove_element"
}
