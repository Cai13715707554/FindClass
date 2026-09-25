package com.school.nav.state

import com.school.nav.core.data.CampusRepository
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
 * 绘制逻辑（增删顶点、成面门槛、命名、id 冲突、楼层归属、存盘）刻意不依赖高德 SDK，
 * 所以能在纯 JVM 上测干净 —— 地图渲染本身需要真机与有效 Key，这里不覆盖。
 */
class MapEditorViewModelTest {

    private lateinit var root: File
    private lateinit var configStore: EditorConfigStore
    private lateinit var keyStore: InMemoryApiKeyStore

    /** 内存版 Key 存储，避免拉起 SharedPreferences。 */
    private class InMemoryApiKeyStore(private var key: String = "") : ApiKeyStore {
        override fun amapKey(): String = key
        override fun saveAmapKey(key: String) {
            this.key = key.trim()
        }

        override fun clearAmapKey() {
            key = ""
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

    private fun point(i: Int) = LngLat(113.13 + i * 0.0005, 23.13 + i * 0.0004)

    private fun addPoints(vm: MapEditorViewModel, count: Int) {
        repeat(count) { vm.addPoint(point(it)) }
    }

    /** 画一栋楼并命名。 */
    private fun drawBuilding(vm: MapEditorViewModel, name: String, points: Int = 3) {
        vm.setMode(EditorMode.Building)
        addPoints(vm, points)
        vm.setDraftName(name)
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
        assertEquals("默认应是教学楼模式", EditorMode.Building, state.mode)
    }

    // ------------------------------------------------------------ 绘制教学楼

    @Test
    fun `点击会累积顶点`() {
        val vm = viewModel()
        addPoints(vm, 3)
        assertEquals(3, vm.uiState.value.draftPoints.size)
        assertEquals(point(0), vm.uiState.value.draftPoints.first())
    }

    @Test
    fun `撤销会移除最后一个顶点`() {
        val vm = viewModel()
        addPoints(vm, 3)
        vm.undoPoint()
        assertEquals(2, vm.uiState.value.draftPoints.size)
        assertEquals(point(1), vm.uiState.value.draftPoints.last())
    }

    @Test
    fun `空顶点时撤销不会出错`() {
        val vm = viewModel()
        vm.undoPoint()
        assertTrue(vm.uiState.value.draftPoints.isEmpty())
    }

    @Test
    fun `少于三个点时不允许成面`() {
        val vm = viewModel()
        addPoints(vm, 2)
        assertFalse(vm.uiState.value.canFinishDraft)

        vm.finishDraft()

        assertTrue("顶点不足时不应生成楼栋", vm.uiState.value.buildings.isEmpty())
        assertEquals("顶点应保留，不该被清掉", 2, vm.uiState.value.draftPoints.size)
    }

    @Test
    fun `三个点可以成面并清空草稿`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋", points = 3)

        val state = vm.uiState.value
        assertEquals(1, state.buildings.size)
        assertEquals("A栋", state.buildings.first().name)
        assertEquals(3, state.buildings.first().polygon.size)
        assertTrue("成面后草稿应清空", state.draftPoints.isEmpty())
    }

    @Test
    fun `未命名时给一个默认名字而不是空字符串`() {
        val vm = viewModel()
        addPoints(vm, 3)
        vm.finishDraft()
        assertTrue(vm.uiState.value.buildings.first().name.isNotBlank())
    }

    @Test
    fun `同名楼栋再次成面视为重画轮廓并保留楼层`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋", points = 3)

        // 给 A 栋补一个教室
        vm.setMode(EditorMode.Room)
        vm.setTargetBuilding(vm.uiState.value.buildings.first().id)
        addPoints(vm, 3)
        vm.setDraftName("101")
        vm.finishDraft()
        assertEquals(1, vm.uiState.value.buildings.first().elementCount)

        // 重画外轮廓
        drawBuilding(vm, "A栋", points = 4)

        val building = vm.uiState.value.buildings.single()
        assertEquals("应仍是一栋楼，不是新增一栋", 1, vm.uiState.value.buildings.size)
        assertEquals(4, building.polygon.size)
        assertEquals("重画轮廓不能把楼层元素弄丢", 1, building.elementCount)
    }

    @Test
    fun `重画会清掉草稿但不影响已画楼栋`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        addPoints(vm, 4)

        vm.cancelDraft()

        assertTrue(vm.uiState.value.draftPoints.isEmpty())
        assertEquals(1, vm.uiState.value.buildings.size)
    }

    @Test
    fun `删除楼栋`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val id = vm.uiState.value.buildings.first().id

        vm.removeBuilding(id)

        assertTrue(vm.uiState.value.buildings.isEmpty())
    }

    // ------------------------------------------------------------ 楼层元素

    @Test
    fun `楼层元素模式必须先选目标楼栋`() {
        val vm = viewModel()
        vm.setMode(EditorMode.Room)

        assertFalse("没选楼时不应该能画", vm.uiState.value.canDraw)

        addPoints(vm, 3)
        vm.finishDraft()

        assertTrue("没选楼栋时不应落下元素", vm.uiState.value.buildings.isEmpty())
    }

    @Test
    fun `选定楼栋后可以画教室并挂到指定楼层`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val buildingId = vm.uiState.value.buildings.first().id

        vm.setMode(EditorMode.Room)
        vm.setTargetBuilding(buildingId)
        vm.setFloorLevel(3)
        assertTrue(vm.uiState.value.canDraw)

        addPoints(vm, 3)
        vm.setDraftName("高一(1)班")
        vm.finishDraft()

        val building = vm.uiState.value.buildings.single()
        val floor = building.floor(3)
        assertNotNull("应落在 3 楼", floor)
        assertEquals(1, floor!!.elements.size)
        assertEquals("高一(1)班", floor.elements.first().name)
        assertEquals(ElementType.Room, floor.elements.first().elementType)
    }

    @Test
    fun `不同模式的元素类型正确落库`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val id = vm.uiState.value.buildings.first().id
        vm.setTargetBuilding(id)

        val cases = listOf(
            EditorMode.Stair to ElementType.Stair,
            EditorMode.Elevator to ElementType.Elevator,
            EditorMode.Entrance to ElementType.Entrance,
            EditorMode.Toilet to ElementType.Toilet,
            EditorMode.Office to ElementType.Office,
        )
        for ((mode, expected) in cases) {
            vm.setMode(mode)
            addPoints(vm, 3)
            vm.setDraftName(mode.label + "1")
            vm.finishDraft()
            val elements = vm.uiState.value.buildings.single().floor(1)!!.elements
            assertEquals(
                "${mode.label} 的类型应落成 $expected",
                expected,
                elements.last().elementType,
            )
        }
    }

    @Test
    fun `切换模式会清空草稿避免串模式`() {
        val vm = viewModel()
        addPoints(vm, 2)
        vm.setMode(EditorMode.Stair)
        assertTrue("切模式不该把上一模式的顶点带过来", vm.uiState.value.draftPoints.isEmpty())
    }

    @Test
    fun `删除单个元素`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val id = vm.uiState.value.buildings.first().id
        vm.setMode(EditorMode.Room)
        vm.setTargetBuilding(id)
        addPoints(vm, 3)
        vm.setDraftName("101")
        vm.finishDraft()

        val elementId = vm.uiState.value.buildings.single().floor(1)!!.elements.first().id
        vm.removeElement(id, 1, elementId)

        assertTrue(
            "元素应被删除",
            vm.uiState.value.buildings.single().floor(1)!!.elements.isEmpty(),
        )
    }

    // ------------------------------------------------------------ id 冲突

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
    fun `保存后重新加载能读回楼栋与楼层元素`() {
        val vm = viewModel()
        drawBuilding(vm, "B栋", points = 4)
        val id = vm.uiState.value.buildings.first().id
        vm.setMode(EditorMode.Room)
        vm.setTargetBuilding(id)
        vm.setFloorLevel(2)
        addPoints(vm, 3)
        vm.setDraftName("201")
        vm.finishDraft()
        vm.save()

        // 新建一个 VM 模拟重启 App
        val reopened = viewModel().uiState.value.buildings

        assertEquals(1, reopened.size)
        assertEquals("B栋", reopened.first().name)
        assertEquals(4, reopened.first().polygon.size)
        assertEquals("201", reopened.first().floor(2)!!.elements.single().name)
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
        val vm = viewModel()
        assertTrue(vm.uiState.value.hasApiKey)
        assertEquals("existing-key", vm.uiState.value.apiKey)
    }
}
