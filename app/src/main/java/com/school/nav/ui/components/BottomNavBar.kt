package com.school.nav.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.school.nav.ui.theme.NavColors

/** 底部导航的页签。 */
enum class NavTab(
    val label: String,
    val icon: ImageVector,
) {
    Home("首页", Icons.Filled.Home),
    Map("地图", Icons.Filled.Edit),
    Profile("我的", Icons.Filled.Person),
}

/**
 * 底部导航栏，参照设计稿做成**悬浮胶囊**：
 *
 *  - 整体是一个底部悬浮的浅色圆角胶囊，左右留出边距，不贴屏幕两边；
 *  - 选中项包一层更亮的白色胶囊底色（图中“首页”那一块）；
 *  - 选中项带图标、文字加粗、颜色更深；未选中项只有文字，颜色更淡。
 *
 * 刻意不用 Material3 的 `NavigationBar`：它的高度、击中区域和选中指示器样式
 * 都按 Material 规范固定，改造成这个悬浮胶囊要覆盖的属性比直接画还多。
 */
@Composable
fun BottomNavBar(
    selected: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .height(NavBarHeight)
            .testTag(TestTags.BottomNavBar),
        shape = RoundedCornerShape(percent = 50),
        color = NavColors.NavBarBackground,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            NavTab.entries.forEach { tab ->
                NavBarItem(
                    tab = tab,
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 单个页签：选中时是「胶囊底 + 图标 + 加粗文字」，未选中时只有文字。 */
@Composable
private fun NavBarItem(
    tab: NavTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor by animateColorAsState(
        targetValue = if (selected) NavColors.NavBarSelectedContent else NavColors.NavBarContent,
        label = "navItemColor",
    )
    val horizontalPadding by animateDpAsState(
        targetValue = if (selected) 20.dp else 12.dp,
        label = "navItemPadding",
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(
                    if (selected) NavColors.NavBarSelectedBackground else Color.Transparent,
                )
                .clickable(
                    // 去掉点击涟漪：设计稿里是纯色胶囊，不需要 Material 的水波纹
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = horizontalPadding, vertical = 10.dp)
                .testTag("${TestTags.BottomNavItem}_${tab.name}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (selected) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = tab.label,
                fontSize = 16.sp,
                // 选中态加粗，未选中常规
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor,
            )
        }
    }
}

/** 导航栏高度，页面需要据此预留底部间距。 */
val NavBarHeight = 56.dp

/** 悬浮导航栏占用的总高度（含外边距），供页面底部留白使用。 */
val NavBarSpace = 88.dp
