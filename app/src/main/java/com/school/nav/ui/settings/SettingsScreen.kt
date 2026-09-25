package com.school.nav.ui.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.school.nav.ui.components.NavBarSpace
import com.school.nav.ui.components.NavCard
import com.school.nav.ui.components.SectionLabel
import com.school.nav.ui.components.TestTags
import com.school.nav.ui.theme.NavColors

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
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onBack: () -> Unit,
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
    var visible by rememberSaveable { mutableStateOf(false) }

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

        NavCard {
            SectionLabel(text = "高德地图 Key")

            Text(
                text = if (currentKey.isBlank()) {
                    "当前未配置。地图编辑器需要 Key 才能显示地图。"
                } else {
                    "当前已配置：${maskKey(currentKey)}"
                },
                fontSize = 13.sp,
                color = NavColors.TextSecondary,
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 10.dp)
                    .testTag(TestTags.SettingsKeyState),
            )

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.SettingsKeyField),
                singleLine = true,
                label = { Text("粘贴高德 Key") },
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
                            .clickableText { visible = !visible },
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
                    onClick = { onSaveKey(input) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TestTags.SettingsSaveKey),
                ) { Text("保存") }

                OutlinedButton(
                    onClick = {
                        input = ""
                        onClearKey()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TestTags.SettingsClearKey),
                ) { Text("清除") }
            }
        }

        NavCard {
            SectionLabel(text = "怎么申请 Key")
            Text(
                text = "1. 打开高德开放平台（lbs.amap.com）并登录\n" +
                    "2. 控制台 → 应用管理 → 创建应用\n" +
                    "3. 添加 Key，服务平台选「Android 平台」\n" +
                    "4. 填入本应用的包名与签名 SHA1\n" +
                    "5. 把生成的 Key 粘到上面保存\n\n" +
                    "注意：debug 与 release 签名不同，两个包名/签名都要各自添加，" +
                    "否则会报 INVALID_USER_KEY。",
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
