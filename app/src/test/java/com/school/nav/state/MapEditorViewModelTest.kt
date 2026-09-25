package com.school.nav.state

import com.school.nav.core.data.CampusRepository
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
 * 绘制逻辑（增删顶点、成面门槛、命名、id 冲突、存盘）刻意不依赖高德 SDK，
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
        MapEditorViewModel(configStore = configStore, apiKeyStore = keyStore, repository = repository)

    private fun point(i: Int) = LngLat(113.13 + i * 0.0005, 23.13 + i * 0.0004)

    private fun addPoints(vm: MapEditorViewModel, count: Int) {
        repeat(count) { vm.addPoint(point(it)) }
    }

    // ------------------------------------------------------------ 绘制

    @Test
    fun `初始状态没有顶点也没有已画楼栋`() {
        val vm = viewModel()
        val state = vm.uiState.value
        assertTrue(state.draftPoints.isEmpty())
        assertTrue(state.outlines.isEmpty())
        assertFalse(state.hasSomethingToSave)
        assertFalse(state.canFinishDraft)
    }

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

        assertTrue("顶点不足时不应生成楼栋", vm.uiState.value.outlines.isEmpty())
        assertEquals("顶点应保留，不该被清掉", 2, vm.uiState.value.draftPoints.size)
    }

    @Test
    fun `三个点可以成面并清空草稿`() {
        val vm = viewModel()
        addPoints(vm, 3)
        vm.setDraftName("A栋")
        vm.finishDraft()

        val state = vm.uiState.value
        assertEquals(1, state.outlines.size)
        assertEquals("A栋", state.outlines.first().name)
        assertEquals(3, state.outlines.first().polygon.size)
        assertTrue("成面后草稿应清空", state.draftPoints.isEmpty())
        assertTrue("成面后名字应清空", state.draftName.isBlank())
    }

    @Test
    fun `未命名时给一个默认名字而不是空字符串`() {
        val vm = viewModel()
        addPoints(vm, 3)
        vm.finishDraft()
        assertTrue(vm.uiState.value.outlines.first().name.isNotBlank())
    }

    @Test
    fun `重画会清掉草稿但不影响已画楼栋`() {
        val vm = viewModel()
        addPoints(vm, 3)
        vm.finishDraft()
        addPoints(vm, 4)

        vm.cancelDraft()

        assertTrue(vm.uiState.value.draftPoints.isEmpty())
        assertEquals(1, vm.uiState.value.outlines.size)
    }

    @Test
    fun `删除已画楼栋`() {
        val vm = viewModel()
        addPoints(vm, 3)
        vm.setDraftName("A栋")
        vm.finishDraft()
        val id = vm.uiState.value.outlines.first().id

        vm.removeOutline(id)

        assertTrue(vm.uiState.value.outlines.isEmpty())
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

        addPoints(vm, 3)
        vm.setDraftName("A栋")
        vm.finishDraft()

        val id = vm.uiState.value.outlines.first().id
        assertFalse(
            "id 与内置楼栋相同会导致覆盖，必须自动避让，实际：$id",
            id == "A栋",
        )
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
        addPoints(vm, 3)
        vm.setDraftName("A栋")
        vm.finishDraft()

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
    fun `保存后重新加载能读回同样的楼栋`() {
        val vm = viewModel()
        addPoints(vm, 4)
        vm.setDraftName("B栋")
        vm.finishDraft()
        vm.save()

        // 新建一个 VM 模拟重启 App
        val reopened = viewModel()
        val outlines = reopened.uiState.value.outlines

        assertEquals(1, outlines.size)
        assertEquals("B栋", outlines.first().name)
        assertEquals(4, outlines.first().polygon.size)
    }

    @Test
    fun `保存会丢掉顶点不足的楼栋`() {
        val vm = viewModel()
        addPoints(vm, 3)
        vm.setDraftName("A栋")
        vm.finishDraft()
        vm.save()

        // 顶点不足的本来也进不了 outlines（finishDraft 会拦），
        // 这里确认文件里只有有效的那一条
        val reloaded = configStore.load()
        assertEquals(1, reloaded.buildings.size)
        assertTrue(reloaded.buildings.all { it.isValid })
    }

    // ------------------------------------------------------------ Key

    @Test
    fun `保存与清除高德 Key 会同步到状态`() {
        val vm = viewModel()
        assertFalse(vm.uiState.value.hasApiKey)

        vm.saveApiKey("  abcdef123456  ")
        assertTrue(vm.uiState.value.hasApiKey)
        assertEquals("应去掉首尾空白", "abcdef123456", vm.uiState.value.apiKey)
        assertEquals("abcdef123456", keyStore.amapKey())

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
