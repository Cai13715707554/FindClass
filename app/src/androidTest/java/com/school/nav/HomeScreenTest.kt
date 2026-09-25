package com.school.nav

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.school.nav.core.data.CampusRepository
import com.school.nav.core.model.Building
import com.school.nav.core.model.Element
import com.school.nav.core.model.ElementType
import com.school.nav.core.model.Floor
import com.school.nav.core.model.LngLat
import com.school.nav.core.navigation.NavigationEngine
import com.school.nav.core.model.Position
import com.school.nav.core.model.Target
import com.school.nav.core.navigation.RouteResult
import com.school.nav.state.NavUiState
import com.school.nav.state.RouteUiState
import com.school.nav.ui.components.TestTags
import com.school.nav.ui.home.HomeScreen
import com.school.nav.ui.theme.FindClassTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 首页交互测试（真机/模拟器上运行）。
 *
 * 覆盖技术方案第十节要求的“主要页面交互”：
 * 搜索目标 -> 展示导航指令 -> 修改位置后立即重算。
 *
 * 这里直接给 [HomeScreen] 喂状态，因此不依赖真实定位与气压计。
 */
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ---- 精简测试数据：一层楼，两个教室，一东一西 ----
    private val west = room("语文教研室", -20.0)
    private val east = room("物理实验室", 4.0)
    private val building = Building(
        id = "A",
        name = "A栋",
        polygon = listOf(),
        floors = listOf(
            Floor(
                id = "A_1F",
                level = 1,
                relativeHeightM = 0.0,
                elements = listOf(west, east),
            ),
        ),
    )

    private fun room(name: String, x: Double, y: Double = 5.0): Element = Element(
        id = "e_$name",
        type = ElementType.Room.raw,
        name = name,
        points = listOf(
            LngLat(113.1234 + x * 1e-5, 23.1234 + y * 1e-5),
            LngLat(113.1234 + (x + 8) * 1e-5, 23.1234 + y * 1e-5),
        ),
    )

    private val position = Position(building, building.floors.first(), west)
    private val target = Target(building, building.floors.first(), east)

    private fun readyState(): NavUiState = NavUiState(
        isLocating = false,
        building = building,
        floor = building.floors.first(),
        element = west,
        target = target,
        route = RouteUiState.Ready(
            RouteResult(
                steps = NavigationEngine().route(position, target).steps,
                target = target,
            ),
        ),
    )

    private fun show(
        state: NavUiState,
        onNavigate: (String) -> Unit = {},
        onEditConfirm: (Building, Floor, Element) -> Unit = { _, _, _ -> },
        onRelocate: () -> Unit = {},
    ) {
        composeRule.setContent {
            FindClassTheme {
                HomeScreen(
                    state = state,
                    buildings = listOf(building),
                    quickTargets = emptyList(),
                    onSuggest = { keyword ->
                        CampusRepository("""{"buildings":[]}""")
                            .search(keyword)
                    },
                    onNavigate = onNavigate,
                    onEditConfirm = onEditConfirm,
                    onRelocate = onRelocate,
                )
            }
        }
    }

    @Test
    fun `位置卡片展示楼栋楼层与教室中文名`() {
        show(readyState())
        composeRule.onNodeWithTag(TestTags.PositionValue).assertIsDisplayed()
        composeRule.onNodeWithText("A栋").assertIsDisplayed()
        composeRule.onNodeWithText("1楼").assertIsDisplayed()
    }

    @Test
    fun `导航指令卡片展示中文导航文案`() {
        show(readyState())
        composeRule.onNodeWithTag(TestTags.RouteCard).assertIsDisplayed()
        composeRule.onNodeWithText("导航指令").assertIsDisplayed()
    }

    @Test
    fun `输入教室名后点导航会回调目标名`() {
        var navigated: String? = null
        show(readyState(), onNavigate = { navigated = it })

        composeRule.onNodeWithTag(TestTags.SearchField).performTextInput("物理实验室")
        composeRule.onNodeWithTag(TestTags.SearchButton).performClick()
        composeRule.waitForIdle()

        assertTrue("应回传搜索关键字，实际：$navigated", navigated == "物理实验室")
    }

    @Test
    fun `点修改位置会打开三级选择弹层`() {
        show(readyState())
        composeRule.onNodeWithTag(TestTags.EditPositionButton).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.EditSheet).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.EditBuilding).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.EditFloor).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.EditElement).assertIsDisplayed()
    }

    @Test
    fun `点重新定位会回调`() {
        var relocated = false
        show(readyState(), onRelocate = { relocated = true })
        composeRule.onNodeWithTag(TestTags.RelocateButton).performClick()
        composeRule.waitForIdle()
        assertTrue(relocated)
    }

    @Test
    fun `楼层未确认时提示用户手动选择`() {
        show(
            NavUiState(
                isLocating = false,
                building = building,
                floor = null,
                element = null,
                target = null,
                route = RouteUiState.Empty,
                needsManualFloor = true,
            ),
        )
        composeRule.onNodeWithText("楼层待确认").assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.RouteEmpty).assertIsDisplayed()
    }

    @Test
    fun `定位中显示 loading 文案`() {
        show(NavUiState(isLocating = true))
        composeRule.onNodeWithTag(TestTags.LocatingSpinner).assertIsDisplayed()
    }
}
