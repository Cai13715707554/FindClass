package com.school.nav.state

import com.school.nav.core.data.CampusRepository
import com.school.nav.core.data.EditorBuilding
import com.school.nav.core.data.EditorMode
import com.school.nav.core.model.ElementType
import com.school.nav.core.model.LngLat
import com.school.nav.data.ApiKeyStore
import com.school.nav.data.EditorConfigStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 地图编辑器状态机测试。
 *
 * 交互模型（用户要求「别让人靠点三个点来划定位置」之后定的）：
 *
 *  - **拖拽矩形**：教学楼 / 教室 / 办公室 —— 按住从一角拖到对角；
 *  - **点击落点**：楼梯口 / 卫生间 —— 点一下就放置，不用画边界；
 *  - 楼梯还要选起始层与结束层，合并时在覆盖的每一层都生成一份。
 *
 * 绘制逻辑刻意不依赖高德 SDK，所以能在纯 JVM 上测干净；
 * 地图渲染本身需要真机与有效 Key，这里不覆盖。
 */
class MapEditorViewModelTest {

    private lateinit var root: File
    private lateinit var configStore: EditorConfigStore
    private lateinit var keyStore: InMemoryApiKeyStore

    /** 内存版 Key 存储，避免拉起 SharedPreferences。 */
    private class InMemoryApiKeyStore(
        private var key: String = "",
        private var webKey: String = "",
    ) : ApiKeyStore {
        override fun amapKey(): String = key

        override fun amapWebKey(): String = webKey

        override fun saveAmapKey(key: String) {
            this.key = key.trim()
        }

        override fun saveAmapWebKey(key: String) {
            this.webKey = key.trim()
        }

        override fun clearAmapKey() {
            key = ""
        }

        override fun clearAmapWebKey() {
            webKey = ""
        }
    }

    private val emptyRepository = CampusRepository("""{"buildings":[]}""")

    @Before
    fun setUp() {
        root = Files.createTempDirectory("editor-vm-test").toFile()
        configStore = EditorConfigStore(
            filesDir = File(root, "internal").apply { mkdirs() },
            externalFilesDir = File(root, "external").apply { mkdirs() },
        )
        keyStore = InMemoryApiKeyStore()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun viewModel(repository: CampusRepository = emptyRepository) =
        MapEditorViewModel(
            configStore = configStore,
            apiKeyStore = keyStore,
            // 不传 locationSource：定位需要 Android，绘制逻辑不该依赖它
            locationSource = null,
            repository = repository,
        )

    private val dragFrom = LngLat(113.1300, 23.1300)
    private val dragTo = LngLat(113.1304, 23.1303)

    /** 模拟一次拖拽：按下 → 移动 → 抬起。 */
    private fun drag(vm: MapEditorViewModel, from: LngLat = dragFrom, to: LngLat = dragTo) {
        vm.onDragStart(from)
        vm.onDragUpdate(to)
        vm.onDragEnd(to)
    }

    /** 拖出一栋楼并命名（名字必须在成面**之前**设好，finishDraft 会立刻提交）。 */
    private fun drawBuilding(vm: MapEditorViewModel, name: String) {
        vm.setMode(EditorMode.Building)
        vm.setDraftName(name)
        drag(vm)
        vm.finishDraft()
    }

    // ------------------------------------------------------------ 初始状态

    @Test
    fun `初始状态没有草稿也没有已画楼栋`() {
        val vm = viewModel()
        val state = vm.uiState.value
        assertTrue(state.draftPoints.isEmpty())
        assertTrue(state.buildings.isEmpty())
        assertFalse(state.canFinishDraft)
        assertNull(state.dragStart)
        assertEquals("默认应是教学楼模式", EditorMode.Building, state.mode)
    }

    // ------------------------------------------------------------ 拖拽矩形

    @Test
    fun `拖拽会实时生成矩形预览`() {
        val vm = viewModel()
        vm.setMode(EditorMode.Building)

        vm.onDragStart(dragFrom)
        assertEquals("按下瞬间只有一个点", 1, vm.uiState.value.draftPoints.size)

        vm.onDragUpdate(dragTo)
        val preview = vm.uiState.value.draftPoints
        assertEquals("拖拽中应预览矩形四个角", 4, preview.size)
        // 矩形四个角的经纬度范围应与两个对角点一致
        assertEquals(dragFrom.lng, preview.minOf { it.lng }, 1e-9)
        assertEquals(dragTo.lng, preview.maxOf { it.lng }, 1e-9)
        assertEquals(dragFrom.lat, preview.minOf { it.lat }, 1e-9)
        assertEquals(dragTo.lat, preview.maxOf { it.lat }, 1e-9)
    }

    @Test
    fun `拖拽结束会成面并清空草稿`() {
        val vm = viewModel()
        vm.setMode(EditorMode.Building)
        vm.setDraftName("A栋")
        drag(vm)

        val state = vm.uiState.value
        assertEquals(1, state.buildings.size)
        assertEquals("A栋", state.buildings.first().name)
        assertEquals(4, state.buildings.first().polygon.size)
        assertTrue("成面后草稿应清空", state.draftPoints.isEmpty())
        assertNull("拖拽状态应复位", state.dragStart)
    }

    @Test
    fun `拖拽范围太小不会留下针尖大的图形`() {
        val vm = viewModel()
        vm.setMode(EditorMode.Building)

        // 几乎没移动
        vm.onDragStart(dragFrom)
        vm.onDragEnd(LngLat(dragFrom.lng + 1e-9, dragFrom.lat))

        assertTrue("范围太小不应成面", vm.uiState.value.buildings.isEmpty())
        assertTrue("预览应被清掉", vm.uiState.value.draftPoints.isEmpty())
    }

    @Test
    fun `取消草稿会清掉预览`() {
        val vm = viewModel()
        vm.setMode(EditorMode.Building)
        vm.onDragStart(dragFrom)
        vm.onDragUpdate(dragTo)

        vm.cancelDraft()

        assertTrue(vm.uiState.value.draftPoints.isEmpty())
        assertNull(vm.uiState.value.dragStart)
    }

    @Test
    fun `单点模式不会响应拖拽`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val id = vm.uiState.value.buildings.first().id
        vm.setMode(EditorMode.Toilet)
        vm.setTargetBuilding(id)

        vm.onDragStart(dragFrom)
        vm.onDragUpdate(dragTo)

        assertTrue("单点元素不该产生拖拽预览", vm.uiState.value.draftPoints.isEmpty())
    }

    @Test
    fun `教学楼未命名时给一个默认名字`() {
        val vm = viewModel()
        vm.setMode(EditorMode.Building)
        drag(vm)
        vm.finishDraft()
        assertTrue(vm.uiState.value.buildings.first().name.isNotBlank())
    }

    @Test
    fun `同名楼栋再次成面视为重画轮廓并保留楼层`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")

        // 给 A 栋补一个教室（用另一个位置，避免与楼栋轮廓重叠）
        vm.setMode(EditorMode.Room)
        vm.setTargetBuilding(vm.uiState.value.buildings.first().id)
        vm.setDraftName("101")
        drag(vm, LngLat(113.1400, 23.1400), LngLat(113.1404, 23.1403))
        vm.finishDraft()
        assertEquals(1, vm.uiState.value.buildings.first().elementCount)

        // 重画外轮廓（换个位置，仍然同名）
        vm.setMode(EditorMode.Building)
        vm.setDraftName("A栋")
        drag(vm, LngLat(113.1500, 23.1500), LngLat(113.1504, 23.1503))
        vm.finishDraft()

        val building = vm.uiState.value.buildings.single()
        assertEquals("应仍是一栋楼，不是新增一栋", 1, vm.uiState.value.buildings.size)
        assertEquals(4, building.polygon.size)
        assertEquals("重画轮廓不能把楼层元素弄丢", 1, building.elementCount)
    }

    @Test
    fun `删除楼栋`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val id = vm.uiState.value.buildings.first().id

        vm.removeBuilding(id)

        assertTrue(vm.uiState.value.buildings.isEmpty())
    }

    // ------------------------------------------------------------ 单点元素

    @Test
    fun `点一下就能放置卫生间`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val id = vm.uiState.value.buildings.first().id

        vm.setMode(EditorMode.Toilet)
        vm.setTargetBuilding(id)
        vm.setDraftName("卫生间1")
        vm.onTap(LngLat(113.1302, 23.1301))

        val building = vm.uiState.value.buildings.single()
        val element = building.floor(1)!!.elements.single()
        assertEquals(ElementType.Toilet, element.elementType)
        assertEquals("卫生间1", element.name)
        assertEquals("单点元素只存一个点", 1, element.points.size)
        assertTrue("单点元素应判为有效", element.isValid)
    }

    @Test
    fun `单点元素模式下点地图不会提示要拖拽`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.setMode(EditorMode.Stair)
        vm.setTargetBuilding(vm.uiState.value.buildings.first().id)
        vm.setDraftName("东楼梯口")

        vm.onTap(LngLat(113.1302, 23.1301))

        assertEquals(
            "点一下就应落点",
            1,
            vm.uiState.value.buildings.single().floor(1)!!.elements.size,
        )
    }

    @Test
    fun `矩形模式下点地图不会落点`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.setMode(EditorMode.Room)
        vm.setTargetBuilding(vm.uiState.value.buildings.first().id)

        vm.onTap(LngLat(113.1302, 23.1301))

        assertTrue(
            "矩形模式应提示拖动而不是落一个点",
            vm.uiState.value.buildings.single().elementCount == 0,
        )
    }

    @Test
    fun `楼梯可选起止楼层并在每一层都生成`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val id = vm.uiState.value.buildings.first().id

        vm.setMode(EditorMode.Stair)
        vm.setTargetBuilding(id)
        vm.setFloorLevel(2)
        vm.setEndFloorLevel(4)
        vm.onTap(LngLat(113.1302, 23.1301))

        val draft = vm.uiState.value.buildings.single()
        val element = draft.floor(2)!!.elements.single()
        assertEquals(4, element.toLevel)
        assertTrue("2→4 应判定为跨层", element.isCrossFloor)

        // 合并成导航数据时，2、3、4 楼都要有这份楼梯
        val merged = draft.toBuilding()
        for (level in 2..4) {
            val floor = merged.floorByLevel(level)
            assertNotNull("$level 楼应生成楼梯", floor)
            assertTrue(
                "$level 楼应能找到同名楼梯",
                floor!!.elements.any { it.name == element.name },
            )
        }
        assertNull("1 楼不应有这个楼梯", merged.floorByLevel(1))
    }

    @Test
    fun `结束楼层不会低于起始楼层`() {
        val vm = viewModel()
        vm.setFloorLevel(3)
        vm.setEndFloorLevel(1)
        assertEquals("结束层应被抬到起始层", 3, vm.uiState.value.endFloorLevel)
    }

    // ------------------------------------------------------------ 楼栋与楼层

    @Test
    fun `非单点元素必须先选目标楼栋`() {
        val vm = viewModel()
        vm.setMode(EditorMode.Room)
        assertFalse("没选楼时不应该能画", vm.uiState.value.canDraw)

        drag(vm)

        assertTrue("没选楼栋时不应落下元素", vm.uiState.value.buildings.isEmpty())
    }

    @Test
    fun `不同模式的元素类型正确落库`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val id = vm.uiState.value.buildings.first().id
        vm.setTargetBuilding(id)

        // 矩形类
        for ((mode, expected) in listOf(
            EditorMode.Room to ElementType.Room,
            EditorMode.Office to ElementType.Office,
        )) {
            vm.setMode(mode)
            drag(vm, LngLat(113.1300 + mode.ordinal * 1e-4, 23.1300), dragTo)
            vm.setDraftName(mode.label + "1")
            vm.finishDraft()
            val elements = vm.uiState.value.buildings.single().floor(1)!!.elements
            assertEquals("${mode.label} 类型应落成 $expected", expected, elements.last().elementType)
        }

        // 单点类
        vm.setMode(EditorMode.Toilet)
        vm.setDraftName("卫生间1")
        vm.onTap(LngLat(113.1309, 23.1302))
        val toilet = vm.uiState.value.buildings.single().floor(1)!!.elements
            .first { it.elementType == ElementType.Toilet }
        assertEquals("卫生间1", toilet.name)
        assertEquals("单点元素只存一个点", 1, toilet.points.size)
    }

    @Test
    fun `切换模式会清空草稿避免串模式`() {
        val vm = viewModel()
        vm.setMode(EditorMode.Building)
        vm.onDragStart(dragFrom)

        vm.setMode(EditorMode.Stair)

        assertTrue("切模式不该把上一模式的预览带过来", vm.uiState.value.draftPoints.isEmpty())
    }

    @Test
    fun `删除单个元素`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val id = vm.uiState.value.buildings.first().id
        vm.setMode(EditorMode.Room)
        vm.setTargetBuilding(id)
        drag(vm)
        vm.setDraftName("101")
        vm.finishDraft()

        val elementId = vm.uiState.value.buildings.single().floor(1)!!.elements.first().id
        vm.removeElement(id, 1, elementId)

        assertTrue(
            "元素应被删除",
            vm.uiState.value.buildings.single().floor(1)!!.elements.isEmpty(),
        )
    }

    @Test
    fun `楼层数可以修改且会夹在合理区间`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val id = vm.uiState.value.buildings.first().id
        assertEquals(EditorBuilding.DEFAULT_FLOOR_COUNT, vm.uiState.value.buildings.first().floorCount)

        vm.setBuildingFloorCount(id, 6)
        assertEquals(6, vm.uiState.value.buildings.first().floorCount)

        vm.setBuildingFloorCount(id, 0)
        assertEquals("不允许 0 层", 1, vm.uiState.value.buildings.first().floorCount)

        vm.setBuildingFloorCount(id, 99_999)
        assertEquals(
            "上限应被夹住",
            MAX_FLOOR_COUNT,
            vm.uiState.value.buildings.first().floorCount,
        )
    }

    @Test
    fun `与内置楼栋同名的 id 会自动加后缀避免覆盖`() {
        val baseJson = """
            {
              "buildings": [
                {
                  "id": "A栋", "name": "A栋",
                  "polygon": [
                    {"lng": 113.0, "lat": 23.0},
                    {"lng": 113.1, "lat": 23.0},
                    {"lng": 113.1, "lat": 23.1}
                  ],
                  "floors": []
                }
              ]
            }
        """.trimIndent()
        val vm = viewModel(CampusRepository(baseJson))

        drawBuilding(vm, "A栋")

        val id = vm.uiState.value.buildings.first().id
        assertFalse("id 与内置楼栋相同会导致覆盖，必须自动避让，实际：$id", id == "A栋")
    }

    // ------------------------------------------------------------ 存盘

    @Test
    fun `没有画好的楼栋时保存不会写文件`() {
        val vm = viewModel()
        vm.save()

        assertNull(vm.uiState.value.lastSave)
        assertFalse(configStore.internalFile.exists())
    }

    @Test
    fun `保存会写入内部与外部两份并返回路径`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")

        vm.save()

        val save = vm.uiState.value.lastSave
        assertNotNull(save)
        assertTrue(save!!.isSuccess)
        assertNotNull(save.internalPath)
        assertNotNull("外部目录可用时应同时写一份", save.externalPath)
        assertTrue(configStore.internalFile.isFile)
        assertTrue(configStore.externalFile!!.isFile)
    }

    @Test
    fun `保存后重新加载能读回楼栋与单点元素`() {
        val vm = viewModel()
        drawBuilding(vm, "B栋")
        val id = vm.uiState.value.buildings.first().id
        vm.setMode(EditorMode.Toilet)
        vm.setTargetBuilding(id)
        vm.setFloorLevel(2)
        vm.setDraftName("卫生间")
        vm.onTap(LngLat(113.1302, 23.1301))
        vm.save()

        // 新建一个 VM 模拟重启 App
        val reopened = viewModel().uiState.value.buildings

        assertEquals(1, reopened.size)
        assertEquals("B栋", reopened.first().name)
        assertEquals(4, reopened.first().polygon.size)
        val element = reopened.first().floor(2)!!.elements.single()
        assertEquals(ElementType.Toilet, element.elementType)
        assertEquals(1, element.points.size)
    }

    // ------------------------------------------------------------ Key

    @Test
    fun `保存与清除高德 Key 会同步到状态`() {
        val vm = viewModel()
        assertFalse(vm.uiState.value.hasApiKey)

        vm.saveApiKey("  abcdef123456  ")
        assertTrue(vm.uiState.value.hasApiKey)
        assertEquals("应去掉首尾空白", "abcdef123456", vm.uiState.value.apiKey)

        vm.clearApiKey()
        assertFalse(vm.uiState.value.hasApiKey)
        assertTrue(vm.uiState.value.apiKey.isEmpty())
    }

    @Test
    fun `空 Key 不会被保存`() {
        val vm = viewModel()
        vm.saveApiKey("   ")
        assertFalse(vm.uiState.value.hasApiKey)
        assertTrue(keyStore.amapKey().isEmpty())
    }

    @Test
    fun `已有 Key 时初始状态即标记为已配置`() {
        keyStore.saveAmapKey("existing-key")
        keyStore.saveAmapWebKey("existing-web-key")
        val vm = viewModel()
        assertTrue(vm.uiState.value.hasApiKey)
        assertEquals("existing-key", vm.uiState.value.apiKey)
        assertEquals("existing-web-key", vm.uiState.value.webKey)
    }

    @Test
    fun `Web服务 Key 可保存与清除且与地图 Key 互不影响`() {
        val vm = viewModel()
        vm.saveApiKey("map-key")
        vm.saveWebKey("  web-key  ")

        assertEquals("map-key", vm.uiState.value.apiKey)
        assertEquals("应去掉首尾空白", "web-key", vm.uiState.value.webKey)

        vm.clearWebKey()
        assertTrue("清掉 Web Key 不该影响地图 Key", vm.uiState.value.apiKey == "map-key")
        assertTrue(vm.uiState.value.webKey.isEmpty())
    }

    @Test
    fun `空 Web服务 Key 不会被保存`() {
        val vm = viewModel()
        vm.saveWebKey("   ")
        assertTrue(vm.uiState.value.webKey.isEmpty())
        assertTrue(keyStore.amapWebKey().isEmpty())
    }
}
