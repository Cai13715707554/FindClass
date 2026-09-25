package com.school.nav.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.school.nav.AppContainer
import com.school.nav.state.NavViewModel
import com.school.nav.ui.components.BottomNavBar
import com.school.nav.ui.components.NavBarSpace
import com.school.nav.ui.components.NavTab
import com.school.nav.ui.components.TestTags
import com.school.nav.ui.profile.ProfileScreen
import com.school.nav.ui.theme.NavColors

/**
 * 应用外壳：底部导航 + 两个页面（首页 / 我的）。
 *
 * ## 为什么不用「两个页面都留在组合树里、只切 alpha」
 *
 * 一开始是那么做的，结果是**隐藏页把可见页的控件全挡住了**：
 * `alpha(0f)` 只影响绘制，不影响命中测试 —— 被隐藏的页面依然铺满全屏、
 * 依然接收触摸。补一个 `pointerInput { detectTapGestures { } }` 也没用，
 * 那只吞掉点击，滚动和子控件手势照样穿透不到下面那一页。
 *
 * ## 现在的方式
 *
 * 用 `when` 只组合当前页，外面套一层 `SaveableStateHolder`：
 * 切走时把该页的 `rememberSaveable` 状态存起来，切回来再还原，
 * 于是滚动位置、搜索框内容都不会丢 —— 既有正确的命中测试，
 * 又不需要用可见性 hack 去骗触摸系统。
 *
 * 注意：`SaveableStateHolder` 只保存 `rememberSaveable` 的状态。
 * 页面里用普通 `remember` 的东西（比如“定位测试”的展开状态）应该改成
 * `rememberSaveable`，否则切页会重置。
 */
@Composable
fun AppShell(container: AppContainer) {
    val viewModel: NavViewModel = viewModel(factory = NavViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 存序号而不是直接存枚举：枚举不是自动可保存类型，存序号再还原最简单
    var selectedIndex by rememberSaveable { mutableIntStateOf(NavTab.Home.ordinal) }
    val selectedTab = NavTab.entries[selectedIndex]

    val stateHolder = rememberSaveableStateHolder()

    // 两个页面共用同一套内边距：顶部留出系统状态栏 + 标题呼吸位，底部留出悬浮导航栏
    val horizontalPadding = 12.dp
    val topPadding = 24.dp

    LaunchedEffect(viewModel) {
        viewModel.toasts.collect { message ->
            snackbarHostState.showSnackbar(message.text)
        }
    }

    Scaffold(
        containerColor = NavColors.PageBackground,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = Modifier.testTag(TestTags.AppShell),
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // 只组合当前页；用 key 让 SaveableStateHolder 区分两个页面的状态
            stateHolder.SaveableStateProvider(key = selectedTab.name) {
                val contentPadding = PaddingValues(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = topPadding + innerPadding.calculateTopPadding(),
                    bottom = NavBarSpace,
                )

                when (selectedTab) {
                    NavTab.Home -> HomeScreen(
                        state = state,
                        buildings = viewModel.buildings(),
                        quickTargets = remember { viewModel.quickTargets() },
                        onSuggest = { keyword -> viewModel.search(keyword) },
                        onNavigate = { name -> viewModel.navigateToName(name) },
                        onEditConfirm = { building, floor, element ->
                            viewModel.applyManualPosition(building, floor, element)
                        },
                        onRelocate = { viewModel.relocate() },
                        contentPadding = contentPadding,
                    )

                    NavTab.Profile -> ProfileScreen(
                        state = state,
                        contentPadding = contentPadding,
                    )
                }
            }

            // 悬浮底部导航：放在页面之后，保证它画在最上层且始终可点
            BottomNavBar(
                selected = selectedTab,
                onSelect = { selectedIndex = it.ordinal },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
