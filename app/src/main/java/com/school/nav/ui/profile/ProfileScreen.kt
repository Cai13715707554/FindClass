package com.school.nav.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.school.nav.state.NavUiState
import com.school.nav.ui.components.NavCard
import com.school.nav.ui.components.NavBarSpace
import com.school.nav.ui.components.SectionLabel
import com.school.nav.ui.components.TestTags
import com.school.nav.ui.theme.NavColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * “我的”页。
 *
 * 目前只放一个**定位测试入口**：把当前拿到的原始经纬度与高度/气压直接摊开显示，
 * 用来在真机上确认「到底有没有取到点、气压计读数是否正常」。
 *
 * 之所以需要它：室内导航出问题时，第一件要判断的事是「传感器有没有数据」，
 * 而首页只展示经过业务解读的结论（A栋 · 3楼 · 语文教研室），看不出原始值。
 */
@Composable
fun ProfileScreen(
    state: NavUiState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = 12.dp,
        end = 12.dp,
        top = 24.dp,
        bottom = NavBarSpace,
    ),
) {
    var testExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .testTag(TestTags.ProfileScreen),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "我的",
            style = MaterialTheme.typography.titleLarge,
            color = NavColors.TextPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
        )

        // ---- 测试入口 ----
        TestEntryCard(
            expanded = testExpanded,
            onToggle = { testExpanded = !testExpanded },
        )

        if (testExpanded) {
            SensorTestCard(state = state)
        }

        // ---- 关于 ----
        NavCard {
            SectionLabel(text = "关于")
            InfoRow(label = "应用", value = "校园教学楼导航")
            InfoRow(label = "版本", value = "1.0.0 (MVP)")
            InfoRow(label = "导航算法", value = "本地运行 · 零后端")
            InfoRow(
                label = "定位通道",
                value = state.locationChannel.ifBlank { "未启用" },
            )
        }
    }
}

/**
 * 测试入口。
 *
 * 用一行可点击的条目而不是按钮：它更像“设置项”，展开后直接在同页面看数据，
 * 不需要跳转，调完就能切回首页。
 */
@Composable
private fun TestEntryCard(
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val chevron = if (expanded) "收起" else "展开"

    NavCard(modifier = Modifier.testTag(TestTags.TestEntry)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(
                    // 去掉水波纹：整行是设置项，不需要 Material 的涟漪反馈
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle,
                )
                .padding(vertical = 2.dp)
                .testTag(TestTags.TestEntryRow),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "定位测试",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NavColors.TextPrimary,
                )
                Text(
                    text = "查看当前经纬度与高度 / 气压原始值",
                    fontSize = 13.sp,
                    color = NavColors.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = chevron,
                fontSize = 14.sp,
                color = NavColors.Brand,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * 传感器读数卡片。
 *
 * 经纬度给到小数点后 6 位（约 0.1 米），经纬度差的第 4 位大约就是 10 米量级，
 * 位数不够就看不出漂移。同时给出**更新时刻 + 精度 + 来源**：
 * 只有坐标数字在变，看不出是“没在更新”还是“站在那儿没动”，
 * 有时间戳和精度才能判断定位到底活没活着。
 *
 * 高度**不依赖气压计**：[NavUiState.displayHeightM] 在没气压计时退回楼层高度，
 * 所以一定会有数字；来源用副标题写清楚，避免把“楼层高度”误读成“实测高度”。
 */
@Composable
private fun SensorTestCard(state: NavUiState) {
    NavCard(modifier = Modifier.testTag(TestTags.SensorTestCard)) {
        SectionLabel(text = "当前传感器读数", dotColor = NavColors.Green)

        val point = state.positionPoint
        if (point == null) {
            Text(
                text = when (state.locationUnavailable) {
                    null -> "还没有取到定位点…"
                    else -> "定位不可用，暂时没有经纬度。"
                },
                fontSize = 14.sp,
                color = NavColors.TextSecondary,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag(TestTags.SensorNoData),
            )
        } else {
            SensorRow(
                label = "经度 lng",
                value = formatCoordinate(point.lng),
                testTag = TestTags.SensorLongitude,
            )
            SensorRow(
                label = "纬度 lat",
                value = formatCoordinate(point.lat),
                testTag = TestTags.SensorLatitude,
            )
            SensorRow(
                label = "最近更新",
                value = formatClock(state.positionUpdatedAtMillis),
                testTag = TestTags.SensorUpdatedAt,
            )
            SensorRow(
                label = "精度",
                value = state.positionAccuracyMeters
                    ?.let { "±%.0f 米".format(it) }
                    ?: "未知",
                testTag = TestTags.SensorAccuracy,
            )
            SensorRow(
                label = "来源",
                value = state.positionProvider ?: "未知",
                testTag = TestTags.SensorProvider,
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 10.dp),
            color = NavColors.Divider,
        )

        // ---- 高度：没有气压计也一定给值 ----
        val height = state.displayHeightM
        SensorRow(
            label = "高度",
            value = height?.let { "%.1f 米".format(it) } ?: "待确定楼层",
            testTag = TestTags.SensorAltitude,
        )
        state.heightSourceText?.let { source ->
            Text(
                text = source,
                fontSize = 12.sp,
                color = NavColors.TextSecondary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .testTag(TestTags.SensorAltitudeSource),
            )
        }

        if (!state.barometerAvailable) {
            Text(
                text = "本机没有气压计，高度按楼层高度估算；有气压计时会换成实测相对高度。",
                fontSize = 12.sp,
                color = NavColors.TextSecondary,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .testTag(TestTags.SensorNoBarometer),
            )
        } else {
            SensorRow(
                label = "气压",
                value = state.pressureHpa?.let { "%.2f hPa".format(it) } ?: "读取中…",
                testTag = TestTags.SensorPressure,
            )
        }

        // 把业务判断结果也列出来，方便和原始值对照：“原始值看起来对，但楼层判错了”
        // 和“原始值本身就是空的”是两类完全不同的问题。
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 10.dp),
            color = NavColors.Divider,
        )
        SensorRow(
            label = "判定楼栋",
            value = state.building?.name ?: "未锁定",
            testTag = TestTags.SensorBuilding,
        )
        SensorRow(
            label = "判定楼层",
            value = state.floor?.displayName ?: "待确认",
            testTag = TestTags.SensorFloor,
        )
        SensorRow(
            label = "当前位置",
            value = state.element?.name ?: "未确定",
            testTag = TestTags.SensorElement,
        )
    }
}

/** 经纬度：6 位小数（约 0.1 米），够看出 GPS 漂移。 */
private fun formatCoordinate(value: Double): String = "%.6f".format(value)

/** 更新时刻：精确到秒，用来确认坐标是不是真的在实时刷新。 */
private fun formatClock(millis: Long): String =
    if (millis <= 0L) {
        "—"
    } else {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
    }

@Composable
private fun SensorRow(
    label: String,
    value: String,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = NavColors.TextSecondary,
        )
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            // 等宽字体：数值刷新时不会左右跳动，便于盯读数
            fontFamily = FontFamily.Monospace,
            color = NavColors.TextPrimary,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, fontSize = 14.sp, color = NavColors.TextSecondary)
        Text(text = value, fontSize = 14.sp, color = NavColors.TextPrimary)
    }
}
