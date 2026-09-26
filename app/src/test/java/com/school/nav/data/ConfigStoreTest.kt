package com.school.nav.data

import com.school.nav.core.data.EditorBuilding
import com.school.nav.core.data.EditorConfig
import com.school.nav.core.data.EditorConfigCodec
import com.school.nav.core.data.EditorElement
import com.school.nav.core.data.EditorMode
import com.school.nav.core.data.FloorDraft
import com.school.nav.core.model.ElementType
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
 * 多份编辑器配置的落盘与管理测试。
 *
 * 全部用真实临时目录读写 —— 这个类刻意不依赖 Android（只接收两个 File 目录
 * 和一个 [ActiveConfigStore] 接口），所以不需要 Robolectric 就能把
 * 「内部 + 外部双写」「损坏文件容错」「切换 / 新建 / 复制 / 改名 / 删除」
 * 这些容易出错的地方测干净。
 *
 * 「一个学校一份配置」是用户明确提的需求：软件不针对单独一个学校，
 * 所以设置页必须能换配置，而不是永远只有一个文件。
 */
class ConfigStoreTest {

    private lateinit var root: File
    private lateinit var filesDir: File
    private lateinit var externalDir: File
    private lateinit var activeStore: InMemoryActiveConfigStore
    private lateinit var store: ConfigStore

    /** 内存版「当前用哪一份」，避免 SharedPreferences。 */
    private class InMemoryActiveConfigStore(private var name: String? = null) : ActiveConfigStore {
        var writes = 0
            private set

        override fun activeFileName(): String? = name

        override fun setActiveFileName(name: String?) {
            this.name = name
            writes++
        }
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("config-store-test").toFile()
        filesDir = File(root, "internal").apply { mkdirs() }
        externalDir = File(root, "external").apply { mkdirs() }
        activeStore = InMemoryActiveConfigStore()
        store = ConfigStore(
            filesDir = filesDir,
            externalFilesDir = externalDir,
            activeConfigStore = activeStore,
        )
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun outline(id: String, name: String, count: Int = 4): EditorBuilding =
        EditorBuilding(
            id = id,
            name = name,
            polygon = (0 until count).map { LngLat(113.13 + it * 0.001, 23.13 + it * 0.001) },
        )

    /** 一栋带楼层元素的楼，用于验证元素也能落盘。 */
    private fun withElements(id: String, name: String): EditorBuilding = EditorBuilding(
        id = id,
        name = name,
        polygon = (0 until 4).map { LngLat(113.13 + it * 0.001, 23.13 + it * 0.001) },
        floors = listOf(
            FloorDraft(
                level = 1,
                elements = listOf(
                    EditorElement.from(
                        mode = EditorMode.Room,
                        id = "room-101",
                        name = "101",
                        points = (0 until 3).map { LngLat(113.131 + it * 0.0002, 23.131) },
                    ),
                ),
            ),
        ),
    )

    /** 直接往内部目录塞一份配置，模拟「用户拷进来的」或「上次画好的」。 */
    private fun seed(fileName: String, config: EditorConfig): File {
        val file = File(store.internalDir, fileName)
        file.parentFile?.mkdirs()
        file.writeText(EditorConfigCodec.encode(config))
        return file
    }

    // ------------------------------------------------------------ 路径

    @Test
    fun `配置目录落在私有目录的 config 文件夹下`() {
        val path = store.internalDir.absolutePath.replace('\\', '/')
        assertTrue("实际路径：$path", path.endsWith("internal/config"))
    }

    @Test
    fun `外部镜像目录落在外部目录的 config 文件夹下`() {
        val dir = store.externalDir
        assertNotNull(dir)
        val path = dir!!.absolutePath.replace('\\', '/')
        assertTrue("实际路径：$path", path.endsWith("external/config"))
    }

    @Test
    fun `默认文件名是 editor_buildings_json`() {
        assertEquals("editor_buildings.json", store.activeFileName())
        assertTrue(store.activeFile().absolutePath.replace('\\', '/').endsWith("config/editor_buildings.json"))
    }

    // ------------------------------------------------------------ 双写

    @Test
    fun `保存会同时写入内部与外部两份`() {
        val result = store.saveActive(listOf(outline("A", "A栋")), nowMillis = 1234L)

        assertTrue("保存应成功", result.isSuccess)
        assertNotNull("内部路径应存在", result.internalPath)
        assertNotNull("外部路径应存在", result.externalPath)
        assertTrue("内部文件应已生成", store.activeFile().isFile)
        assertTrue(
            "外部文件应已生成",
            File(store.externalDir, store.activeFileName()).isFile,
        )
        assertTrue("不应有告警：${result.warnings}", result.warnings.isEmpty())
    }

    @Test
    fun `外部目录不可用时只写内部且给出告警`() {
        val noExternal = ConfigStore(
            filesDir = filesDir,
            externalFilesDir = null,
            activeConfigStore = activeStore,
        )
        val result = noExternal.saveActive(listOf(outline("A", "A栋")))

        assertTrue(result.isSuccess)
        assertNotNull(result.internalPath)
        assertNull(result.externalPath)
        assertTrue(
            "应说明外部目录不可用，实际：${result.warnings}",
            result.warnings.any { it.contains("外部目录不可用") },
        )
    }

    @Test
    fun `保存后能读回同样的内容`() {
        store.saveActive(listOf(outline("A", "A栋"), outline("B", "B栋")), nowMillis = 999L)

        val loaded = store.loadActive()
        assertEquals(EditorConfig.CURRENT_SCHEMA_VERSION, loaded.schemaVersion)
        assertEquals(999L, loaded.exportedAtMillis)
        assertEquals(listOf("A", "B"), loaded.buildings.map { it.id })
    }

    @Test
    fun `再次保存会覆盖旧内容而不是追加`() {
        store.saveActive(listOf(outline("A", "A栋")))
        store.saveActive(listOf(outline("B", "B栋")))

        assertEquals(listOf("B"), store.loadActive().buildings.map { it.id })
    }

    @Test
    fun `楼层元素也会被保存并读回`() {
        store.saveActive(listOf(withElements("A", "A栋")))

        val building = store.loadActive().buildings.single()
        assertEquals(1, building.elementCount)
        val element = building.floor(1)!!.elements.single()
        assertEquals("101", element.name)
        assertEquals(ElementType.Room, element.elementType)
        assertEquals(3, element.points.size)
    }

    @Test
    fun `什么都没画的草稿不会被写进文件`() {
        val empty = EditorBuilding(id = "X", name = "空楼", polygon = emptyList())
        val result = store.saveActive(listOf(outline("A", "A栋"), empty))

        assertTrue(result.isSuccess)
        assertEquals("只有 A 栋该被保存", listOf("A"), store.loadActive().buildings.map { it.id })
    }

    @Test
    fun `写完后不留下临时文件`() {
        store.saveActive(listOf(outline("A", "A栋")))
        val file = store.activeFile()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        assertFalse("临时文件应被改名或删除，实际仍存在：${tmp.absolutePath}", tmp.exists())
    }

    @Test
    fun `存盘只影响当前那一份不会碰到别的配置`() {
        seed("第一中学.json", EditorConfig(buildings = listOf(outline("A", "A栋"))))
        store.setActive("第一中学.json")
        store.create("第二中学")

        store.saveActive(listOf(outline("B", "B栋")))

        assertEquals("第二中学.json", store.activeFileName())
        assertEquals(listOf("B"), store.loadActive().buildings.map { it.id })
        // 第一份应该原封不动
        val first = EditorConfigCodec.decode(File(store.internalDir, "第一中学.json").readText())!!
        assertEquals(listOf("A"), first.buildings.map { it.id })
    }

    // ------------------------------------------------------------ 读取与容错

    @Test
    fun `没有文件时返回空配置`() {
        assertTrue(store.loadActive().buildings.isEmpty())
    }

    @Test
    fun `损坏的配置文件返回空配置而不是抛异常`() {
        val file = store.activeFile()
        file.parentFile?.mkdirs()
        file.writeText("这不是 JSON {{{")

        assertTrue("坏文件应被忽略", store.loadActive().buildings.isEmpty())
    }

    @Test
    fun `内部没有配置时回退读外部配置`() {
        // 模拟用户把导出的文件拷进外部目录
        val external = File(store.externalDir, store.activeFileName())
        external.parentFile?.mkdirs()
        external.writeText(
            EditorConfigCodec.encode(EditorConfig(buildings = listOf(outline("Z", "Z栋")))),
        )

        assertFalse("内部文件此时不应存在", store.activeFile().exists())
        val json = store.loadActiveJson()
        assertNotNull("应回退读到外部配置", json)
        assertEquals("Z", EditorConfigCodec.decode(json!!)!!.buildings.single().id)
    }

    @Test
    fun `内部优先于外部`() {
        store.saveActive(listOf(outline("A", "A栋")))
        val external = File(store.externalDir, store.activeFileName())
        external.parentFile?.mkdirs()
        external.writeText(
            EditorConfigCodec.encode(EditorConfig(buildings = listOf(outline("Z", "Z栋")))),
        )

        val loaded = EditorConfigCodec.decode(store.loadActiveJson()!!)!!
        assertEquals("应读内部那份", "A", loaded.buildings.single().id)
    }

    // ------------------------------------------------------------ 列表

    @Test
    fun `列表按修改时间从新到旧`() {
        seed("旧.json", EditorConfig(buildings = listOf(outline("A", "A栋"))))
        seed("新.json", EditorConfig(buildings = listOf(outline("B", "B栋"))))
        // 明确设一下时间，别依赖两次写入的实际间隔（可能只有几毫秒甚至相同）
        File(store.internalDir, "旧.json").setLastModified(1_000L)
        File(store.internalDir, "新.json").setLastModified(2_000L)

        assertEquals(listOf("新.json", "旧.json"), store.list().map { it.fileName })
    }

    @Test
    fun `列表会标出当前生效的那一份和损坏的文件`() {
        seed("好的.json", EditorConfig(buildings = listOf(outline("A", "A栋"))))
        seed("坏的.json", EditorConfig()).let { it.writeText("{ 坏掉的") }
        store.setActive("好的.json")

        val list = store.list()
        assertTrue("生效的那份要被标出来", list.first { it.fileName == "好的.json" }.isActive)
        assertTrue("读不出来的要被标成损坏", list.first { it.fileName == "坏的.json" }.isBroken)
        assertEquals(1, list.first { it.fileName == "好的.json" }.buildingCount)
        assertNull(list.first { it.fileName == "坏的.json" }.buildingCount)
    }

    @Test
    fun `目录不存在时列表为空而不是报错`() {
        store.internalDir.deleteRecursively()
        assertTrue(store.list().isEmpty())
    }

    // ------------------------------------------------------------ 激活与回退

    @Test
    fun `设置里指定的那一份优先`() {
        seed("第一中学.json", EditorConfig(buildings = listOf(outline("A", "A栋"))))
        seed("第二中学.json", EditorConfig(buildings = listOf(outline("B", "B栋"))))
        store.setActive("第一中学.json")

        assertEquals("第一中学.json", store.activeFileName())
        assertEquals(listOf("A"), store.loadActive().buildings.map { it.id })
    }

    @Test
    fun `指定的文件被删掉后回退到最新的一份`() {
        seed("第一中学.json", EditorConfig(buildings = listOf(outline("A", "A栋"))))
        seed("第二中学.json", EditorConfig(buildings = listOf(outline("B", "B栋"))))
        File(store.internalDir, "第一中学.json").setLastModified(1_000L)
        File(store.internalDir, "第二中学.json").setLastModified(2_000L)
        store.setActive("第一中学.json")

        File(store.internalDir, "第一中学.json").delete()

        assertEquals("应回退到剩下最新的一份", "第二中学.json", store.activeFileName())
        assertEquals(listOf("B"), store.loadActive().buildings.map { it.id })
    }

    @Test
    fun `一份都没有时回退到默认文件名`() {
        store.setActive("不存在的.json")
        assertEquals("editor_buildings.json", store.activeFileName())
    }

    // ------------------------------------------------------------ 新建 / 复制 / 改名 / 删除

    @Test
    fun `新建会落出一个空配置并切过去`() {
        val fileName = store.create("实验中学")

        assertEquals("实验中学.json", fileName)
        assertEquals("新建后应直接切过去", "实验中学.json", store.activeFileName())
        assertTrue(store.loadActive().buildings.isEmpty())
        assertTrue(File(store.internalDir, fileName).isFile)
    }

    @Test
    fun `新建重名会自动加序号`() {
        store.create("实验中学")
        val second = store.create("实验中学")

        assertEquals("实验中学-2.json", second)
        assertEquals(2, store.list().size)
    }

    @Test
    fun `新建会过滤掉文件名里的非法字符`() {
        val fileName = store.create("高一/二班:实验?")

        assertFalse("不能出现路径分隔符", fileName.contains('/'))
        assertFalse(fileName.contains(':'))
        assertFalse(fileName.contains('?'))
        assertTrue(File(store.internalDir, fileName).isFile)
    }

    @Test
    fun `新建时带上 json 后缀不会变成双后缀`() {
        assertEquals("实验中学.json", store.create("实验中学.json"))
    }

    @Test
    fun `名字为空时给一个兜底名字`() {
        val fileName = store.create("   ")
        assertEquals("${ConfigStore.DEFAULT_CONFIG_NAME}.json", fileName)
    }

    @Test
    fun `复制会带上原来的楼栋`() {
        store.saveActive(listOf(outline("A", "A栋")))

        val copy = store.duplicate(store.activeFileName())

        assertNotNull(copy)
        assertEquals(listOf("A"), EditorConfigCodec.decode(
            File(store.internalDir, copy!!).readText(),
        )!!.buildings.map { it.id })
        assertEquals("原文件应保留", 2, store.list().size)
    }

    @Test
    fun `复制一个读不出来的文件会返回 null`() {
        seed("坏的.json", EditorConfig()).let { it.writeText("{ 坏掉的") }
        assertNull(store.duplicate("坏的.json"))
    }

    @Test
    fun `改名会换文件名并保留内容`() {
        store.saveActive(listOf(outline("A", "A栋")))
        val before = store.activeFileName()

        val renamed = store.rename(before, "第一中学")

        assertEquals("第一中学.json", renamed)
        assertEquals("改名后仍然生效", "第一中学.json", store.activeFileName())
        assertFalse("旧文件应消失", File(store.internalDir, before).exists())
        assertEquals(listOf("A"), store.loadActive().buildings.map { it.id })
    }

    @Test
    fun `改名会同步外部那份`() {
        store.saveActive(listOf(outline("A", "A栋")))
        val before = store.activeFileName()

        store.rename(before, "第一中学")

        assertFalse(File(store.externalDir, before).exists())
        assertTrue(File(store.externalDir, "第一中学.json").isFile)
    }

    @Test
    fun `改名到同一个名字是空操作`() {
        store.saveActive(listOf(outline("A", "A栋")))
        val before = store.activeFileName()

        assertEquals(before, store.rename(before, before))
        assertTrue(File(store.internalDir, before).isFile)
    }

    @Test
    fun `删除会把内部与外部两份都清掉`() {
        store.saveActive(listOf(outline("A", "A栋")))
        val fileName = store.activeFileName()

        assertTrue(store.delete(fileName))

        assertFalse(store.activeFile().exists())
        assertFalse(File(store.externalDir, fileName).exists())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `删掉当前那份会自动切到剩下最新的一份`() {
        store.create("第一中学")
        store.create("第二中学")
        store.delete("第二中学.json")

        assertEquals("第一中学.json", store.activeFileName())
    }

    @Test
    fun `把最后一份删掉之后没有当前配置`() {
        store.saveActive(listOf(outline("A", "A栋")))
        store.delete(store.activeFileName())

        assertEquals("应回到默认文件名（此时读出来是空的）", "editor_buildings.json", store.activeFileName())
        assertTrue(store.loadActive().buildings.isEmpty())
        assertNull(store.list().firstOrNull())
    }

    // ------------------------------------------------------------ 清空

    @Test
    fun `清空只清内容不删文件`() {
        store.saveActive(listOf(outline("A", "A栋")))

        store.clearActive()

        assertTrue("文件本身要留着（用户在设置页还能看到这份配置）", store.activeFile().isFile)
        assertTrue(store.loadActive().buildings.isEmpty())
    }
}
