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
import androidx.compose.runtime.mutableStateOf
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
import com.school.nav.state.MapEditorViewModel
import com.school.nav.state.NavViewModel
import com.school.nav.ui.components.BottomNavBar
import com.school.nav.ui.components.NavBarSpace
import com.school.nav.ui.components.NavTab
import com.school.nav.ui.components.TestTags
import com.school.nav.ui.editor.MapEditorScreen
import com.school.nav.ui.profile.ProfileScreen
import com.school.nav.ui.settings.SettingsScreen
import com.school.nav.ui.theme.NavColors

/**
 * 应用外壳：底部导航 + 三个页面（首页 / 地图 / 我的），外加一个设置子页。
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
 * 设置页作为「我的」的子页面，用独立的 `showSettings` 标记；
 * 点底部导航会关掉它，符合用户对 Tab 的预期。
 */
@Composable
fun AppShell(container: AppContainer) {
    val navViewModel: NavViewModel = viewModel(factory = NavViewModel.factory(container))
    val editorViewModel: MapEditorViewModel =
        viewModel(factory = MapEditorViewModel.factory(container))

    val navState by navViewModel.uiState.collectAsStateWithLifecycle()
    val editorState by editorViewModel.uiState.collectAsStateWithLifecycle()

    // 存序号而不是直接存枚举：枚举不是自动可保存类型，存序号再还原最简单
    var selectedIndex by rememberSaveable { mutableIntStateOf(NavTab.Home.ordinal) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val selectedTab = NavTab.entries[selectedIndex]

    val stateHolder = rememberSaveableStateHolder()

    // 从设置页返回时刷新 Key 状态（用户可能在设置里刚填/清了 Key）
    LaunchedEffect(showSettings) {
        if (!showSettings) editorViewModel.refreshApiKey()
    }

    val snackbarHostState = remember { SnackbarHostState() }

    // 两个页面共用同一套内边距：顶部留出系统状态栏 + 标题呼吸位，底部留出悬浮导航栏
    val horizontalPadding = 12.dp
    val topPadding = 24.dp

    // 导航与地图编辑器的提示都走同一个 Snackbar
    LaunchedEffect(navViewModel) {
        navViewModel.toasts.collect { snackbarHostState.showSnackbar(it.text) }
    }
    LaunchedEffect(editorViewModel) {
        editorViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        containerColor = NavColors.PageBackground,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = Modifier.testTag(TestTags.AppShell),
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // key 里带上 showSettings：设置页与「我的」是两个不同的可保存状态
            val pageKey = if (showSettings) "settings" else selectedTab.name

            stateHolder.SaveableStateProvider(key = pageKey) {
                val contentPadding = PaddingValues(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = topPadding + innerPadding.calculateTopPadding(),
                    bottom = NavBarSpace,
                )

                when {
                    showSettings -> SettingsScreen(
                        currentKey = editorState.apiKey,
                        onSaveKey = { editorViewModel.saveApiKey(it) },
                        onClearKey = { editorViewModel.clearApiKey() },
                        onBack = { showSettings = false },
                        contentPadding = contentPadding,
                    )

                    selectedTab == NavTab.Home -> HomeScreen(
                        state = navState,
                        buildings = navViewModel.buildings(),
                        quickTargets = remember { navViewModel.quickTargets() },
                        onSuggest = { keyword -> navViewModel.search(keyword) },
                        onNavigate = { name -> navViewModel.navigateToName(name) },
                        onEditConfirm = { building, floor, element ->
                            navViewModel.applyManualPosition(building, floor, element)
                        },
                        onRelocate = { navViewModel.relocate() },
                        contentPadding = contentPadding,
                    )

                    selectedTab == NavTab.Map -> MapEditorScreen(
                        state = editorState,
                        onMapClick = { editorViewModel.addPoint(it) },
                        onModeChange = { editorViewModel.setMode(it) },
                        onTargetBuildingChange = { editorViewModel.setTargetBuilding(it) },
                        onFloorChange = { editorViewModel.setFloorLevel(it) },
                        onNameChange = { editorViewModel.setDraftName(it) },
                        onUndo = { editorViewModel.undoPoint() },
                        onFinish = { editorViewModel.finishDraft() },
                        onCancelDraft = { editorViewModel.cancelDraft() },
                        onRemoveBuilding = { editorViewModel.removeBuilding(it) },
                        onRemoveElement = { b, level, e ->
                            editorViewModel.removeElement(b, level, e)
                        },
                        onSave = { editorViewModel.save() },
                        onReload = { editorViewModel.reload() },
                        onGoMyLocation = { editorViewModel.goToMyLocation() },
                        onCenterConsumed = { editorViewModel.consumeCenterRequest(it) },
                        onGoToPoint = { editorViewModel.goTo(it) },
                        onGoSettings = { showSettings = true },
                    )

                    else -> ProfileScreen(
                        state = navState,
                        onOpenSettings = { showSettings = true },
                        contentPadding = contentPadding,
                    )
                }
            }

            // 悬浮底部导航：放在页面之后，保证它画在最上层且始终可点。
            // 在设置页里也显示（点任意 Tab 会退出设置页），避免出现「没有退路」的死角。
            BottomNavBar(
                selected = selectedTab,
                onSelect = {
                    // 切 Tab 时关闭设置子页，符合对底部导航的预期
                    showSettings = false
                    selectedIndex = it.ordinal
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
