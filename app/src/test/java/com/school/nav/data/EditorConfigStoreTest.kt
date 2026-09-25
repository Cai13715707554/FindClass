package com.school.nav.data

import com.school.nav.core.data.BuildingOutline
import com.school.nav.core.data.EditorConfigCodec
import com.school.nav.core.model.LngLat
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
 * 编辑器配置的落盘测试。
 *
 * 全部用真实临时目录读写 —— 这个类刻意不依赖 Android（只接收两个 File 目录），
 * 所以不需要 Robolectric 就能把「内部 + 外部双写」「损坏文件容错」这些
 * 容易出错的地方测干净。
 */
class EditorConfigStoreTest {

    private lateinit var root: File
    private lateinit var filesDir: File
    private lateinit var externalDir: File
    private lateinit var store: EditorConfigStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("editor-store-test").toFile()
        filesDir = File(root, "internal").apply { mkdirs() }
        externalDir = File(root, "external").apply { mkdirs() }
        store = EditorConfigStore(filesDir = filesDir, externalFilesDir = externalDir)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun outline(id: String, name: String, count: Int = 4): BuildingOutline =
        BuildingOutline(
            id = id,
            name = name,
            polygon = (0 until count).map { LngLat(113.13 + it * 0.001, 23.13 + it * 0.001) },
        )

    // ------------------------------------------------------------ 路径

    @Test
    fun `内部路径落在私有目录的 config 文件夹下`() {
        val path = store.internalFile.absolutePath.replace('\\', '/')
        assertTrue("实际路径：$path", path.endsWith("internal/config/editor_buildings.json"))
    }

    @Test
    fun `外部路径落在外部目录的 config 文件夹下`() {
        val file = store.externalFile
        assertNotNull(file)
        val path = file!!.absolutePath.replace('\\', '/')
        assertTrue("实际路径：$path", path.endsWith("external/config/editor_buildings.json"))
    }

    @Test
    fun `外部目录不可用时只写内部且给出告警`() {
        val noExternal = EditorConfigStore(filesDir = filesDir, externalFilesDir = null)
        val result = noExternal.save(listOf(outline("A", "A栋")))

        assertTrue(result.isSuccess)
        assertNotNull(result.internalPath)
        assertNull(result.externalPath)
        assertTrue(
            "应说明外部目录不可用，实际：${result.warnings}",
            result.warnings.any { it.contains("外部目录不可用") },
        )
    }

    // ------------------------------------------------------------ 双写

    @Test
    fun `保存会同时写入内部与外部两份`() {
        val result = store.save(listOf(outline("A", "A栋")), nowMillis = 1234L)

        assertTrue("保存应成功", result.isSuccess)
        assertNotNull("内部路径应存在", result.internalPath)
        assertNotNull("外部路径应存在", result.externalPath)
        assertTrue("内部文件应已生成", store.internalFile.isFile)
        assertTrue("外部文件应已生成", store.externalFile!!.isFile)
        assertTrue("不应有告警：${result.warnings}", result.warnings.isEmpty())
    }

    @Test
    fun `保存后能读回同样的内容`() {
        store.save(listOf(outline("A", "A栋"), outline("B", "B栋")), nowMillis = 999L)

        val loaded = store.load()
        assertEquals(com.school.nav.core.data.EditorConfig.CURRENT_SCHEMA_VERSION, loaded.schemaVersion)
        assertEquals(999L, loaded.exportedAtMillis)
        assertEquals(listOf("A", "B"), loaded.buildings.map { it.id })
    }

    @Test
    fun `再次保存会覆盖旧内容而不是追加`() {
        store.save(listOf(outline("A", "A栋")))
        store.save(listOf(outline("B", "B栋")))

        assertEquals(listOf("B"), store.load().buildings.map { it.id })
    }

    @Test
    fun `无效轮廓不会被写进文件`() {
        val result = store.save(
            listOf(outline("A", "A栋"), outline("C", "C栋", count = 2)),
        )
        assertTrue(result.isSuccess)
        assertEquals("只有 A 栋该被保存", listOf("A"), store.load().buildings.map { it.id })
    }

    @Test
    fun `写完后不留下临时文件`() {
        store.save(listOf(outline("A", "A栋")))
        val tmp = File(store.internalFile.parentFile, "${store.internalFile.name}.tmp")
        assertFalse("临时文件应被改名或删除，实际仍存在：${tmp.absolutePath}", tmp.exists())
    }

    // ------------------------------------------------------------ 读取与容错

    @Test
    fun `没有文件时返回空配置`() {
        val config = store.load()
        assertTrue(config.buildings.isEmpty())
    }

    @Test
    fun `损坏的配置文件返回空配置而不是抛异常`() {
        store.internalFile.parentFile.mkdirs()
        store.internalFile.writeText("这不是 JSON {{{")

        val config = store.load()
        assertTrue("坏文件应被忽略", config.buildings.isEmpty())
    }

    @Test
    fun `内部没有配置时回退读外部配置`() {
        // 模拟用户把导出的文件拷进外部目录
        val external = store.externalFile!!
        external.parentFile.mkdirs()
        external.writeText(
            EditorConfigCodec.encode(
                com.school.nav.core.data.EditorConfig(buildings = listOf(outline("Z", "Z栋"))),
            ),
        )

        assertFalse("内部文件此时不应存在", store.internalFile.exists())
        val json = store.loadConfigJson()
        assertNotNull("应回退读到外部配置", json)
        assertEquals("Z", EditorConfigCodec.decode(json!!)!!.buildings.single().id)
    }

    @Test
    fun `内部优先于外部`() {
        store.save(listOf(outline("A", "A栋")))
        val external = store.externalFile!!
        external.parentFile.mkdirs()
        external.writeText(
            EditorConfigCodec.encode(
                com.school.nav.core.data.EditorConfig(buildings = listOf(outline("Z", "Z栋"))),
            ),
        )

        val loaded = EditorConfigCodec.decode(store.loadConfigJson()!!)!!
        assertEquals("应读内部那份", "A", loaded.buildings.single().id)
    }

    // ------------------------------------------------------------ 清空

    @Test
    fun `clear 会删掉内部与外部两份`() {
        store.save(listOf(outline("A", "A栋")))
        assertTrue(store.internalFile.isFile)
        assertTrue(store.externalFile!!.isFile)

        store.clear()

        assertFalse(store.internalFile.exists())
        assertFalse(store.externalFile!!.exists())
    }
}
