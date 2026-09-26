package com.school.nav.state

import com.school.nav.core.data.CampusRepository
import com.school.nav.core.model.Building
import com.school.nav.core.model.Element
import com.school.nav.core.model.Floor
import com.school.nav.core.model.LngLat
import com.school.nav.location.LocationAvailability
import com.school.nav.location.LocationSource
import com.school.nav.location.LocationUpdate
import com.school.nav.location.PressureSource
import com.school.nav.location.RawLocationFix
import com.school.nav.ui.components.positionHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `NavViewModel` 的定位不可用兜底测试。
 *
 * 回归的是一个真实缺陷：`observeLocation()` 早期只在订阅前检查一次
 * `isAvailable()`，一旦定位取不到点，`isLocating` 会永远停在 true，
 * 界面一直显示「定位中…」，用户既不知道原因，也看不到手动选择的提示。
 *
 * 这些用例锁住两点：
 *  1. 定位不可用时 **`isLocating` 必须为 false**，且原因要能到达界面文案；
 *  2. 定位不可用不影响手动完成导航 —— 对应验收标准
 *     「无气压计或定位失败时，App 仍可手动完成导航」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavViewModelLocationFallbackTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------ 测试替身

    /** 可控的定位来源：指定可用性，并可选地推一个定位点。 */
    private class FakeLocationSource(
        private val state: LocationAvailability,
        private val emitFix: RawLocationFix? = null,
    ) : LocationSource {

        override val channelName: String = "测试定位"

        override fun isAvailable(): Boolean = state == LocationAvailability.Available

        override fun availability(): LocationAvailability = state

        override fun locationUpdates(): Flow<LocationUpdate> = flow {
            val fix = emitFix
            if (state == LocationAvailability.Available && fix != null) {
                emit(LocationUpdate.Fix(fix))
            } else {
                emit(LocationUpdate.Unavailable(state))
            }
        }
    }

    /** 没有气压计的设备。 */
    private class NoBarometer : PressureSource {
        override val hasSensor: Boolean = false
        override fun pressureUpdates(): Flow<Float> = emptyFlow()
    }

    /** 内存版手动位置存储，避免测试拉起 DataStore。 */
    private class InMemoryManualPositions : ManualPositionStore {
        private val state = MutableStateFlow(ManualPosition.None)

        override val manualPosition: Flow<ManualPosition> = state

        override val hasManualOverride: Flow<Boolean> = flow { emit(!state.value.isEmpty) }

        override suspend fun current(): ManualPosition = state.value

        override suspend fun saveManualPosition(position: ManualPosition) {
            state.value = position
        }

        override suspend fun clearManualPosition() {
            state.value = ManualPosition.None
        }
    }

    // ------------------------------------------------------------ 测试数据

    /**
     * 一栋楼的 JSON，字段名与 assets/buildings.json 完全一致。
     *
     * 用 JSON 而不是直接构造对象，是为了顺带覆盖「相对高度字段是 snake_case」这条解析路径。
     */
    private val repository = CampusRepository(
        """
        {
          "buildings": [
            {
              "id": "A",
              "name": "A栋",
              "polygon": [
                {"lng": 113.12300, "lat": 23.12320},
                {"lng": 113.12400, "lat": 23.12320},
                {"lng": 113.12400, "lat": 23.12360},
                {"lng": 113.12300, "lat": 23.12360}
              ],
              "floors": [
                {
                  "id": "A_1F",
                  "level": 1,
                  "relative_height_m": 0.0,
                  "elements": [
                    {
                      "id": "e_语文教研室", "type": "room", "name": "语文教研室",
                      "points": [
                        {"lng": 113.12320, "lat": 23.12350},
                        {"lng": 113.12328, "lat": 23.12350},
                        {"lng": 113.12328, "lat": 23.12352},
                        {"lng": 113.12320, "lat": 23.12352}
                      ]
                    },
                    {
                      "id": "e_物理实验室", "type": "room", "name": "物理实验室",
                      "points": [
                        {"lng": 113.12344, "lat": 23.12350},
                        {"lng": 113.12352, "lat": 23.12350},
                        {"lng": 113.12352, "lat": 23.12352},
                        {"lng": 113.12344, "lat": 23.12352}
                      ]
                    }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent(),
    )

    private fun viewModel(source: LocationSource): NavViewModel = NavViewModel(
        initialRepository = repository,
        locationSource = source,
        altimeter = NoBarometer(),
        preferences = InMemoryManualPositions(),
    )

    /** 便于断言：测试用的楼栋/楼层从仓库里取，id 与 JSON 一致。 */
    private fun repoBuilding(): Building = repository.buildings.first()

    private fun repoFloor(): Floor = repoBuilding().floors.first()

    private fun repoElement(name: String): Element =
        repoFloor().elements.first { it.name == name }

    // ------------------------------------------------------------ 用例

    @Test
    fun `缺少定位权限时不会卡在定位中`() = runTest(dispatcher) {
        val vm = viewModel(FakeLocationSource(LocationAvailability.PermissionDenied))

        val state = vm.uiState.value
        assertFalse("定位不可用时必须停掉 loading", state.isLocating)
        assertEquals(LocationAvailability.PermissionDenied, state.locationUnavailable)

        // 用户实际会看到一句明确的手动兜底提示，而不是永远转圈的「定位中…」
        val hint = positionHint(state)
        assertNotNull("应给出定位不可用的说明", hint)
        assertTrue("提示应说明是权限问题，实际：$hint", hint!!.contains("权限"))
        assertTrue("提示应给出下一步，实际：$hint", hint.contains("修改位置"))
    }

    @Test
    fun `定位服务被关闭时原因能到达界面文案`() = runTest(dispatcher) {
        val vm = viewModel(FakeLocationSource(LocationAvailability.ServiceDisabled))

        val state = vm.uiState.value
        assertFalse(state.isLocating)
        assertEquals(LocationAvailability.ServiceDisabled, state.locationUnavailable)

        val hint = positionHint(state)!!
        assertTrue("提示应说明是定位服务被关闭，实际：$hint", hint.contains("定位服务已关闭"))
        assertTrue(hint.contains("修改位置"))
    }

    @Test
    fun `没有可用 provider 时同样不卡在定位中`() = runTest(dispatcher) {
        val vm = viewModel(FakeLocationSource(LocationAvailability.NoProvider))

        val state = vm.uiState.value
        assertFalse(state.isLocating)
        assertEquals(LocationAvailability.NoProvider, state.locationUnavailable)
        assertTrue(positionHint(state)!!.contains("无法自动定位"))
    }

    @Test
    fun `定位不可用时由位置卡片统一提示而不是两处重复`() = runTest(dispatcher) {
        val vm = viewModel(FakeLocationSource(LocationAvailability.PermissionDenied))

        // 楼层未知，但因为已经说明是定位不可用，导航区域不再重复提示手动兜底
        assertFalse(vm.uiState.value.needsManualFloor)
        org.junit.Assert.assertNull(vm.uiState.value.floor)
    }

    @Test
    fun `定位不可用时仍能手动完成导航`() = runTest(dispatcher) {
        val vm = viewModel(FakeLocationSource(LocationAvailability.PermissionDenied))

        // 定位完全用不了，用户手动指定位置
        vm.applyManualPosition(repoBuilding(), repoFloor(), repoElement("语文教研室"))

        val state = vm.uiState.value
        assertTrue("手动指定后应能组成完整位置", state.isPositionComplete)
        assertEquals("A栋 · 1楼 · 语文教研室", state.positionText)
        // 手动给了位置后，定位不可用的原因被清掉，
        // 位置卡片的提示也从「没有定位权限…」换成「已按你的修改定位…」
        org.junit.Assert.assertNull(state.locationUnavailable)
        val hint = positionHint(state)
        assertFalse(
            "不应再提示定位不可用，实际：$hint",
            hint != null && hint.contains("定位权限"),
        )
        assertTrue(
            "应提示这是用户手动指定的位置，实际：$hint",
            hint != null && hint.contains("已按你的修改定位"),
        )

        // 搜索并导航：仍然能算出文字导航
        vm.navigateToName("物理实验室")
        val route = vm.uiState.value.route
        assertTrue("应生成导航指令，实际：$route", route is RouteUiState.Ready)
        val text = (route as RouteUiState.Ready).route.plainText
        assertTrue("导航文案应指向目标，实际：$text", text.contains("物理实验室"))
        assertFalse("文案不应含米数", text.contains("米"))
    }

    @Test
    fun `定位成功时锁定楼栋且不显示不可用提示`() = runTest(dispatcher) {
        val insidePoint = RawLocationFix(
            point = LngLat(113.12324, 23.12350),
            accuracyMeters = 8.0,
            provider = "test",
        )
        val vm = viewModel(
            FakeLocationSource(LocationAvailability.Available, emitFix = insidePoint),
        )

        val state = vm.uiState.value
        assertFalse(state.isLocating)
        org.junit.Assert.assertNull(state.locationUnavailable)
        assertEquals("A栋", state.building?.name)
        // 没有气压计 -> 楼层需要手动确认，这条提示仍然要给
        assertTrue(positionHint(state)!!.contains("气压计"))
    }
}
