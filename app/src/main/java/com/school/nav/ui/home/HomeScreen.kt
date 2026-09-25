package com.school.nav.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.school.nav.core.data.SearchHit
import com.school.nav.core.model.Building
import com.school.nav.core.model.Element
import com.school.nav.core.model.Floor
import com.school.nav.core.navigation.RouteStep
import com.school.nav.state.NavUiState
import com.school.nav.state.RouteUiState
import com.school.nav.ui.components.EditPositionSheet
import com.school.nav.ui.components.EmptyHint
import com.school.nav.ui.components.NavBarSpace
import com.school.nav.ui.components.PositionCard
import com.school.nav.ui.components.RouteCard
import com.school.nav.ui.components.TargetSearchCard
import com.school.nav.ui.components.TestTags
import com.school.nav.ui.theme.NavColors

/**
 * 首页。
 *
 * 结构对应产品文档第八节：
 *   当前位置卡片 -> 目标搜索卡片 -> 导航指令卡片。
 *
 * 完全由入参决定渲染结果（不持有 ViewModel、不带 Scaffold）：导航容器与 Snackbar
 * 由 [AppShell] 统一负责，这里只画这一页的内容，便于 Compose UI 测试直接喂状态。
 */
@Composable
fun HomeScreen(
    state: NavUiState,
    buildings: List<Building>,
    quickTargets: List<SearchHit>,
    onSuggest: (String) -> List<SearchHit>,
    onNavigate: (String) -> Unit,
    onEditConfirm: (Building, Floor, Element) -> Unit,
    onRelocate: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = 12.dp,
        end = 12.dp,
        top = 24.dp,
        bottom = NavBarSpace,
    ),
) {
    var editing by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(TestTags.HomeScreen),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header()

            PositionCard(
                state = state,
                onEdit = { editing = true },
                onRelocate = onRelocate,
            )

            TargetSearchCard(
                onNavigate = onNavigate,
                quickTargets = quickTargets,
                onSuggest = onSuggest,
            )

            RouteSection(state = state)
        }

        if (editing) {
            EditPositionSheet(
                buildings = buildings,
                currentBuilding = state.building,
                currentFloor = state.floor,
                currentElement = state.element,
                onDismiss = { editing = false },
                onConfirm = { building, floor, element ->
                    onEditConfirm(building, floor, element)
                    editing = false
                },
            )
        }
    }
}

/** 顶部标题。 */
@Composable
private fun Header() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "校园教学楼导航",
            style = MaterialTheme.typography.titleLarge,
            color = NavColors.TextPrimary,
        )
        Text(
            text = "GPS 判楼栋 · 气压计判楼层 · 文字导航",
            style = MaterialTheme.typography.bodyMedium,
            color = NavColors.TextSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 导航结果区域，按状态分支渲染。 */
@Composable
private fun RouteSection(state: NavUiState) {
    when (val route = state.route) {
        RouteUiState.Empty -> {
            // 还没选目标：不显示卡片，避免首页出现空置的大块区域
            if (state.needsManualFloor) {
                EmptyHint(
                    text = "还不知道你在几楼。可以点上面的“修改位置”先选好位置，" +
                        "再搜索目标教室。",
                    modifier = Modifier.testTag(TestTags.RouteEmpty),
                )
            }
        }

        RouteUiState.Loading -> EmptyHint(text = "定位中，稍后给出导航指令…")

        is RouteUiState.Arrived -> RouteCard(
            steps = listOf(RouteStep("你已经在${route.target.element.name}了。")),
        )

        is RouteUiState.Unavailable -> EmptyHint(text = route.reason)

        is RouteUiState.Ready -> RouteCard(steps = route.route.steps)
    }
}
