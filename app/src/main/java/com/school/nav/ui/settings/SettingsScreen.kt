package com.school.nav.ui.settings

import androidx.compose.foundation.border
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.school.nav.data.ConfigEntry
import com.school.nav.ui.components.NavBarSpace
import com.school.nav.ui.components.NavCard
import com.school.nav.ui.components.SectionLabel
import com.school.nav.ui.components.TestTags
import com.school.nav.ui.theme.NavColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页（从「我的」进入）。
 *
 * 目前只有一项：高德地图 Key。
 *
 * 为什么让用户自己填 Key，而不是把它写进代码 / BuildConfig：
 *  - Key 是开发者凭据，打进 APK 能被反编译出来；
 *  - 高德 Key 与「包名 + 签名 SHA1」绑定，换机器、换签名就要换 Key，
 *    每次改代码重新打包太不务实。
 *
 * 输入框默认用密码样式遮蔽 —— 设置页经常被人围观，Key 泄漏等于别人能用你的配额。
 */
@Composable
fun SettingsScreen(
    currentKey: String,
    currentWebKey: String,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onSaveWebKey: (String) -> Unit,
    onClearWebKey: () -> Unit,
    onBack: () -> Unit,
    configs: List<ConfigEntry> = emptyList(),
    activeConfigName: String = "",
    onSwitchConfig: (String) -> Unit = {},
    onCreateConfig: (String) -> Unit = {},
    onDuplicateConfig: (String) -> Unit = {},
    onRenameConfig: (String, String) -> Unit = { _, _ -> },
    onDeleteConfig: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = 12.dp,
        end = 12.dp,
        top = 24.dp,
        bottom = NavBarSpace,
    ),
) {
    // 用 Key 做 remember 的输入：外部清空后输入框要跟着清空
    var input by rememberSaveable(currentKey) { mutableStateOf(currentKey) }
    var webInput by rememberSaveable(currentWebKey) { mutableStateOf(currentWebKey) }
    var visible by rememberSaveable { mutableStateOf(false) }

    /** 正在改名 / 新建的那一份配置；null 表示弹窗关着。 */
    var nameDialogTarget by remember { mutableStateOf<ConfigNameDialog?>(null) }
    /** 待删除确认的那一份。 */
    var deleteTarget by remember { mutableStateOf<ConfigEntry?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .testTag(TestTags.SettingsScreen),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleLarge,
                color = NavColors.TextPrimary,
            )
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.testTag(TestTags.SettingsBack),
            ) { Text("返回") }
        }

        // ---- 配置管理（一个学校一份）----
        ConfigCard(
            configs = configs,
            activeConfigName = activeConfigName,
            onSwitch = onSwitchConfig,
            onNew = { nameDialogTarget = ConfigNameDialog.Create },
            onDuplicate = onDuplicateConfig,
            onRename = { entry -> nameDialogTarget = ConfigNameDialog.Rename(entry) },
            onDelete = { entry -> deleteTarget = entry },
        )

        // ---- 地图 Key（Android 平台）----
        KeyCard(
            title = "高德地图 Key",
            platform = "服务平台选「Android 平台」",
            purpose = "地图渲染与定位。没填地图页会显示引导，不会白屏。",
            currentKey = currentKey,
            input = input,
            visible = visible,
            testTagField = TestTags.SettingsKeyField,
            testTagSave = TestTags.SettingsSaveKey,
            testTagClear = TestTags.SettingsClearKey,
            testTagState = TestTags.SettingsKeyState,
            onInputChange = { input = it },
            onToggleVisible = { visible = !visible },
            onSave = { onSaveKey(input) },
            onClear = {
                input = ""
                onClearKey()
            },
        )

        // ---- 搜索 Key（Web 服务）----
        KeyCard(
            title = "高德搜索 Key（可选，强烈建议）",
            platform = "服务平台选「Web服务」",
            purpose = "POI 模糊搜索。填了之后搜索能容忍少写字、同音字；" +
                "不填则退回系统地理编码，必须写全名且不能有同音字。",
            currentKey = currentWebKey,
            input = webInput,
            visible = visible,
            testTagField = TestTags.SettingsWebKeyField,
            testTagSave = TestTags.SettingsSaveWebKey,
            testTagClear = TestTags.SettingsClearWebKey,
            testTagState = TestTags.SettingsWebKeyState,
            onInputChange = { webInput = it },
            onToggleVisible = { visible = !visible },
            onSave = { onSaveWebKey(webInput) },
            onClear = {
                webInput = ""
                onClearWebKey()
            },
        )

        NavCard {
            SectionLabel(text = "两个 Key 为什么要分开")
            Text(
                text = "高德的 Key 按「服务平台」区分用途，**Android 平台的 Key 不能用于 Web 服务**，" +
                    "反之也一样。所以地图用一个、搜索用一个，各建一个即可（同一个应用下可以建多个 Key）。\n\n" +
                    "建 Key 时服务平台选错，会报 INVALID_USER_KEY —— 那不是 Key 失效，是类型不对。",
                fontSize = 13.sp,
                color = NavColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        NavCard {
            SectionLabel(text = "怎么申请 Key")
            Text(
                text = "1. 打开高德开放平台（lbs.amap.com）并登录\n" +
                    "2. 控制台 → 应用管理 → 创建应用\n" +
                    "3. 添加 Key：地图选「Android 平台」（填包名 + 签名 SHA1）；" +
                    "搜索选「Web服务」（不需要包名）\n" +
                    "4. 把两个 Key 分别粘到上面保存\n\n" +
                    "注意：debug 与 release 签名不同，两个签名都要各自添加，" +
                    "否则地图会报 INVALID_USER_KEY。",
                fontSize = 13.sp,
                color = NavColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        NavCard {
            SectionLabel(text = "Key 存在哪")
            Text(
                text = "Key 保存在本机的应用私有配置里，不会上传，也不会写进代码仓库。" +
                    "它只在你手动清除或卸载应用时消失。",
                fontSize = 13.sp,
                color = NavColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        NavCard {
            SectionLabel(text = "配置文件存在哪")
            Text(
                text = "每个学校一份配置文件，保存在应用私有目录：\n" +
                    "  /data/data/包名/files/config/名称.json\n\n" +
                    "同时会在外部目录镜像一份，方便用数据线或文件管理器取走：\n" +
                    "  /sdcard/Android/data/包名/files/config/名称.json\n\n" +
                    "把别人的配置文件拷进外部目录再点上面的「切换」，就能换成他们学校的数据。",
                fontSize = 13.sp,
                color = NavColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    // ---- 新建 / 重命名弹窗 ----
    nameDialogTarget?.let { target ->
        ConfigNameDialogHost(
            target = target,
            onDismiss = { nameDialogTarget = null },
            onConfirm = { name ->
                when (target) {
                    is ConfigNameDialog.Create -> onCreateConfig(name)
                    is ConfigNameDialog.Rename -> onRenameConfig(target.entry.fileName, name)
                }
                nameDialogTarget = null
            },
        )
    }

    // ---- 删除确认弹窗 ----
    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除配置") },
            text = {
                Text(
                    "确定删除「${entry.displayName}」吗？该文件会从应用私有目录和外部目录一起删掉，" +
                        "删了没法恢复。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteConfig(entry.fileName)
                        deleteTarget = null
                    },
                    modifier = Modifier.testTag(TestTags.SettingsConfigDeleteConfirm),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

/** 新建 / 重命名共用的弹窗状态。 */
private sealed interface ConfigNameDialog {
    data object Create : ConfigNameDialog
    data class Rename(val entry: ConfigEntry) : ConfigNameDialog
}

/**
 * 配置管理卡片。
 *
 * 一行一份配置，点整行即切换；右侧三个小按钮分别是复制 / 改名 / 删除。
 * 当前生效的那份用主色边框 + 「使用中」标记，避免删错。
 */
@Composable
private fun ConfigCard(
    configs: List<ConfigEntry>,
    activeConfigName: String,
    onSwitch: (String) -> Unit,
    onNew: () -> Unit,
    onDuplicate: (String) -> Unit,
    onRename: (ConfigEntry) -> Unit,
    onDelete: (ConfigEntry) -> Unit,
) {
    NavCard(modifier = Modifier.testTag(TestTags.SettingsConfigSection)) {
        SectionLabel(text = "配置（一个学校一份）")
        Text(
            text = "这份软件不针对某一个学校：换学校就是换一份配置。" +
                "当前正在用：「${activeConfigName.removeSuffix(".json").ifBlank { "无" }}」",
            fontSize = 12.sp,
            color = NavColors.TextSecondary,
            modifier = Modifier
                .padding(top = 6.dp)
                .testTag(TestTags.SettingsConfigActiveName),
        )

        if (configs.isEmpty()) {
            Text(
                text = "还没有任何配置文件。点下面的「新建」开始画第一个学校；" +
                    "什么都不建也可以，应用会用内置的示例数据。",
                fontSize = 13.sp,
                color = NavColors.TextSecondary,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                configs.forEach { entry ->
                    ConfigRow(
                        entry = entry,
                        onSwitch = { onSwitch(entry.fileName) },
                        onDuplicate = { onDuplicate(entry.fileName) },
                        onRename = { onRename(entry) },
                        onDelete = { onDelete(entry) },
                    )
                }
            }
        }

        Button(
            onClick = onNew,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .testTag(TestTags.SettingsConfigNew),
        ) { Text("新建配置") }
    }
}

/** 配置列表里的一行。 */
@Composable
private fun ConfigRow(
    entry: ConfigEntry,
    onSwitch: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val border = if (entry.isActive) NavColors.Brand else NavColors.Divider
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, border, MaterialTheme.shapes.small)
            .clickableText(onSwitch)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("${TestTags.SettingsConfigRow}_${entry.fileName}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = entry.displayName,
                fontSize = 15.sp,
                color = NavColors.TextPrimary,
            )
            if (entry.isActive) {
                Text(text = "使用中", fontSize = 12.sp, color = NavColors.Brand)
            }
        }

        Text(
            text = buildString {
                append(
                    when {
                        entry.isBroken -> "文件读不出来（可能不是配置文件的 JSON）"
                        else -> "${entry.buildingCount} 栋楼"
                    },
                )
                append(" · ")
                append(formatTime(entry.lastModified))
            },
            fontSize = 12.sp,
            color = NavColors.TextSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallConfigButton(
                text = "复制",
                testTag = "${TestTags.SettingsConfigDuplicate}_${entry.fileName}",
                onClick = onDuplicate,
            )
            SmallConfigButton(
                text = "改名",
                testTag = "${TestTags.SettingsConfigRename}_${entry.fileName}",
                onClick = onRename,
            )
            SmallConfigButton(
                text = "删除",
                testTag = "${TestTags.SettingsConfigDelete}_${entry.fileName}",
                onClick = onDelete,
            )
        }
    }
}

/** 行内小按钮：用描边按钮，比 TextButton 更好点。 */
@Composable
private fun SmallConfigButton(text: String, testTag: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(text = text, fontSize = 13.sp)
    }
}

/** 新建 / 重命名的输入弹窗。 */
@Composable
private fun ConfigNameDialogHost(
    target: ConfigNameDialog,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val initial = when (target) {
        is ConfigNameDialog.Create -> ""
        is ConfigNameDialog.Rename -> target.entry.displayName
    }
    var name by remember(target) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (target is ConfigNameDialog.Create) "新建配置" else "重命名配置")
        },
        text = {
            Column {
                Text(
                    text = "名字会直接当作文件名（自动加 .json）。比如填「实验中学」，" +
                        "就生成 实验中学.json。",
                    fontSize = 12.sp,
                    color = NavColors.TextSecondary,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .testTag(TestTags.SettingsConfigNameField),
                    singleLine = true,
                    label = { Text("配置名称") },
                    shape = MaterialTheme.shapes.small,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag(TestTags.SettingsConfigNameConfirm),
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 毫秒时间戳 -> `2025-01-31 14:05`；只做展示，不参与逻辑。 */
private fun formatTime(millis: Long): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return fmt.format(Date(millis))
}

/** 一个 Key 的输入卡片。地图 Key 与搜索 Key 结构相同，抽出来避免两处重复。 */
@Composable
private fun KeyCard(
    title: String,
    platform: String,
    purpose: String,
    currentKey: String,
    input: String,
    visible: Boolean,
    testTagField: String,
    testTagSave: String,
    testTagClear: String,
    testTagState: String,
    onInputChange: (String) -> Unit,
    onToggleVisible: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    NavCard {
        SectionLabel(text = title)

        Text(
            text = "服务平台：$platform",
            fontSize = 12.sp,
            color = NavColors.Brand,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = purpose,
            fontSize = 12.sp,
            color = NavColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = if (currentKey.isBlank()) {
                "当前未配置"
            } else {
                "当前已配置：${maskKey(currentKey)}"
            },
            fontSize = 13.sp,
            color = NavColors.TextSecondary,
            modifier = Modifier
                .padding(top = 8.dp, bottom = 10.dp)
                .testTag(testTagState),
        )

        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTagField),
            singleLine = true,
            label = { Text("粘贴 Key") },
            shape = MaterialTheme.shapes.small,
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                Text(
                    text = if (visible) "隐藏" else "显示",
                    fontSize = 13.sp,
                    color = NavColors.Brand,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .clickableText(onToggleVisible),
                )
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onSave,
                modifier = Modifier
                    .weight(1f)
                    .testTag(testTagSave),
            ) { Text("保存") }

            OutlinedButton(
                onClick = onClear,
                modifier = Modifier
                    .weight(1f)
                    .testTag(testTagClear),
            ) { Text("清除") }
        }
    }
}

/** 打码显示：保留前 4 后 4，中间用星号。 */
internal fun maskKey(key: String): String =
    if (key.length <= 8) {
        "*".repeat(key.length)
    } else {
        key.take(4) + "****" + key.takeLast(4)
    }

/** 让「显示/隐藏」这种行内文字可点，且不引入 Material 水波纹。 */
private fun Modifier.clickableText(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}
