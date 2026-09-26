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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
fun AppShell(
    container: AppContainer,
    navViewModel: NavViewModel,
    editorViewModel: MapEditorViewModel,
) {
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
            val onMapTab = !showSettings && selectedTab == NavTab.Map

            val contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = topPadding + innerPadding.calculateTopPadding(),
                bottom = NavBarSpace,
            )

            /*
             * 地图**常驻组合树**，只切可见性。
             *
             * 这是「切页签回来地图不刷新」的关键，也是踩过两次坑之后的做法：
             *
             *  1. 最开始把 ViewModel 提到 Activity 作用域 —— 只解决了「状态被重建」，
             *     MapView 还是随页面销毁；
             *  2. 换 movableContentOf 也没成 —— AndroidView 在移动时 View 仍会被重建。
             *
             * 现在反过来：地图永远待在组合树里（`active=false` 时由 MapView 自己
             * 设为 INVISIBLE 并解除点击监听），所以 MapView 对象、相机、overlay
             * 全都不会变；切走的只是别的页面盖在它上面。
             *
             * 放在 Box 最底层、又始终由其它页面或导航栏覆盖，因此不需要额外处理命中测试。
             */
            if (editorState.hasApiKey) {
                MapEditorScreen(
                    state = editorState,
                    onMapClick = { editorViewModel.onTap(it) },
                    onDragStart = { editorViewModel.onDragStart(it) },
                    onDragUpdate = { editorViewModel.onDragUpdate(it) },
                    onDragEnd = { editorViewModel.onDragEnd(it) },
                    onModeChange = { editorViewModel.setMode(it) },
                    onTargetBuildingChange = { editorViewModel.setTargetBuilding(it) },
                    onFloorChange = { editorViewModel.setFloorLevel(it) },
                    onEndFloorChange = { editorViewModel.setEndFloorLevel(it) },
                    onBuildingFloorCountChange = { id, count ->
                        editorViewModel.setBuildingFloorCount(id, count)
                    },
                    onNameChange = { editorViewModel.setDraftName(it) },
                    onFloorCountChange = { editorViewModel.setDraftFloorCount(it) },
                    onUndo = { editorViewModel.cancelDraft() },
                    onFinish = { editorViewModel.finishDraft() },
                    onCancelDraft = { editorViewModel.cancelDraft() },
                    onRemoveBuilding = { editorViewModel.removeBuilding(it) },
                    onRemoveElement = { b, level, e -> editorViewModel.removeElement(b, level, e) },
                    onSave = { editorViewModel.save() },
                    onReload = { editorViewModel.reload() },
                    onGoMyLocation = { editorViewModel.goToMyLocation() },
                    onCenterConsumed = { editorViewModel.consumeCenterRequest(it) },
                    onGoToPoint = { editorViewModel.goTo(it) },
                    onGoSettings = { showSettings = true },
                    contentPadding = contentPadding,
                    active = onMapTab,
                    modifier = Modifier.visible(onMapTab),
                )
            }

            // key 里带上 showSettings：设置页与「我的」是两个不同的可保存状态
            val pageKey = if (showSettings) "settings" else selectedTab.name

            stateHolder.SaveableStateProvider(key = pageKey) {
                when {
                    showSettings -> SettingsScreen(
                        currentKey = editorState.apiKey,
                        currentWebKey = editorState.webKey,
                        onSaveKey = { editorViewModel.saveApiKey(it) },
                        onClearKey = { editorViewModel.clearApiKey() },
                        onSaveWebKey = { editorViewModel.saveWebKey(it) },
                        onClearWebKey = { editorViewModel.clearWebKey() },
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

                    // 地图不做条件分支：它已经常驻在上面了。
                    // 这里只在 Map 页签时补一层透明占位，保证 Box 里始终有内容盖住地图的
                    // 边角（地图自己已设为 INVISIBLE，理论上不需要，但多一层更保险）。
                    selectedTab == NavTab.Map -> Unit

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

/**
 * 页面可见性。
 *
 * 隐藏时只做 alpha，**不消费触摸事件** —— 这一层只在 Map 页签激活时可见，
 * 其余时候上面还盖着别的页面（它们的容器会吃掉命中测试），
 * 所以不需要像早先那样额外挂一个 pointerInput 去吞手势；
 * 而且那个做法当时还引发了「隐藏页挡住可见页」的问题。
 */
private fun Modifier.visible(visible: Boolean): Modifier =
    this.then(if (visible) Modifier else Modifier.alpha(0f))
