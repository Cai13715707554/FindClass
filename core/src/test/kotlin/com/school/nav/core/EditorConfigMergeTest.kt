package com.school.nav.core

import com.school.nav.core.data.BuildingOutline
import com.school.nav.core.data.CampusRepository
import com.school.nav.core.data.EditorConfig
import com.school.nav.core.data.EditorConfigCodec
import com.school.nav.core.model.LngLat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地图编辑器配置的编解码，以及「assets 打底 + 配置覆盖」的合并规则。
 *
 * 合并规则是这次改动的核心风险点：写错了会把内置数据的楼层清空，
 * 表现是「楼栋还在、导航全废」，而且不会崩，很难发现。这里的断言就是防这个。
 */
class EditorConfigMergeTest {

    private val baseJson = """
        {
          "buildings": [
            {
              "id": "A",
              "name": "A栋",
              "polygon": [
                {"lng": 113.1230, "lat": 23.1232},
                {"lng": 113.1240, "lat": 23.1232},
                {"lng": 113.1240, "lat": 23.1236},
                {"lng": 113.1230, "lat": 23.1236}
              ],
              "floors": [
                {
                  "id": "A_1F", "level": 1, "relative_height_m": 0.0,
                  "elements": [
                    {
                      "id": "e1", "type": "room", "name": "语文教研室",
                      "points": [
                        {"lng": 113.1232, "lat": 23.1234},
                        {"lng": 113.1233, "lat": 23.1234},
                        {"lng": 113.1233, "lat": 23.1235},
                        {"lng": 113.1232, "lat": 23.1235}
                      ]
                    }
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    private fun outline(id: String, name: String, count: Int = 4): BuildingOutline =
        BuildingOutline(
            id = id,
            name = name,
            polygon = (0 until count).map {
                LngLat(113.130 + it * 0.001, 23.130 + it * 0.001)
            },
        )

    // ------------------------------------------------------------ 编解码

    @Test
    fun `配置能序列化再反序列化且内容不变`() {
        val config = EditorConfig(
            exportedAtMillis = 1_700_000_000_000L,
            buildings = listOf(outline("A", "A栋")),
        )
        val decoded = EditorConfigCodec.decode(EditorConfigCodec.encode(config))

        assertNotNull(decoded)
        assertEquals(config.exportedAtMillis, decoded!!.exportedAtMillis)
        assertEquals(1, decoded.buildings.size)
        assertEquals("A", decoded.buildings.first().id)
        assertEquals(4, decoded.buildings.first().polygon.size)
    }

    @Test
    fun `非法 JSON 返回 null 而不是抛异常`() {
        assertNull(EditorConfigCodec.decode("这不是 JSON"))
        assertNull(EditorConfigCodec.decode(""))
        assertNull(EditorConfigCodec.decode("{{{ 半截"))
    }

    @Test
    fun `空对象解析成默认配置而不是 null`() {
        // {} 是合法 JSON，且 EditorConfig 所有字段都有默认值，
        // 所以它应该解析成「空配置」——这比判定为坏文件更合理：
        // 空配置等价于「编辑器还没画过东西」，App 照常有内置数据可用。
        val decoded = EditorConfigCodec.decode("{}")
        assertNotNull(decoded)
        assertEquals(EditorConfig.CURRENT_SCHEMA_VERSION, decoded!!.schemaVersion)
        assertTrue(decoded.buildings.isEmpty())
    }

    @Test
    fun `字段缺失时用默认值`() {
        val decoded = EditorConfigCodec.decode("""{"buildings":[]}""")
        assertNotNull(decoded)
        assertEquals(EditorConfig.CURRENT_SCHEMA_VERSION, decoded!!.schemaVersion)
        assertTrue(decoded.buildings.isEmpty())
    }

    // ------------------------------------------------------------ 有效性

    @Test
    fun `少于三个点或点重合的轮廓判为无效`() {
        assertTrue(outline("A", "A栋", count = 3).isValid)
        assertTrue(!outline("A", "A栋", count = 2).isValid)
        assertTrue(!outline("A", "A栋", count = 0).isValid)

        val degenerate = BuildingOutline(
            id = "A",
            name = "A栋",
            polygon = List(4) { LngLat(113.13, 23.13) },
        )
        assertTrue("四个点全重合也应判为无效", !degenerate.isValid)
    }

    // ------------------------------------------------------------ 合并

    @Test
    fun `没有配置时原样返回 assets 数据`() {
        val merged = CampusRepository.create(baseJson, editorConfigJson = null)
        assertEquals(1, merged.repository.buildings.size)
        assertEquals("A栋", merged.repository.buildings.first().name)
        assertTrue(merged.warnings.isEmpty())
    }

    @Test
    fun `配置覆盖同名楼栋的几何但保留楼层`() {
        val config = EditorConfigCodec.encode(
            EditorConfig(buildings = listOf(outline("A", "A栋新名"))),
        )
        val merged = CampusRepository.create(baseJson, config)
        val building = merged.repository.buildings.single()

        assertEquals("A栋新名", building.name)
        assertEquals(4, building.polygon.size)
        // 关键断言：楼层必须还在，否则“覆盖”等于把楼废掉
        assertEquals("覆盖后楼层不能丢", 1, building.floors.size)
        assertEquals("A_1F", building.floors.first().id)
        assertEquals(1, building.floors.first().elements.size)
    }

    @Test
    fun `配置里空格名字不会把原名字覆盖成空白`() {
        val config = EditorConfigCodec.encode(
            EditorConfig(buildings = listOf(outline("A", "   "))),
        )
        val merged = CampusRepository.create(baseJson, config)
        assertEquals("A栋", merged.repository.buildings.single().name)
    }

    @Test
    fun `配置新增的楼栋会加进来但没有楼层并给出告警`() {
        val config = EditorConfigCodec.encode(
            EditorConfig(buildings = listOf(outline("B", "B栋"))),
        )
        val merged = CampusRepository.create(baseJson, config)

        assertEquals(2, merged.repository.buildings.size)
        val added = merged.repository.buildings.first { it.id == "B" }
        assertTrue("新楼栋暂时没有楼层", added.floors.isEmpty())
        assertTrue(
            "应告警说明新楼栋不能导航，实际：${merged.warnings}",
            merged.warnings.any { it.contains("B栋") && it.contains("楼层") },
        )
    }

    @Test
    fun `无效轮廓被忽略并给出告警`() {
        val config = EditorConfigCodec.encode(
            EditorConfig(
                buildings = listOf(
                    outline("A", "A栋"),
                    outline("C", "C栋", count = 2),  // 只有两个点
                ),
            ),
        )
        val merged = CampusRepository.create(baseJson, config)

        assertEquals("无效的 C 栋不应被加入", 1, merged.repository.buildings.size)
        assertTrue(
            "应告警说明丢弃了无效轮廓，实际：${merged.warnings}",
            merged.warnings.any { it.contains("无效") },
        )
    }

    @Test
    fun `损坏的配置文本不会影响 assets 数据`() {
        val merged = CampusRepository.create(baseJson, editorConfigJson = "{{{ 坏文件")
        assertEquals(1, merged.repository.buildings.size)
        assertEquals("A栋", merged.repository.buildings.first().name)
        assertEquals(1, merged.repository.buildings.first().floors.size)
        assertTrue(merged.editorConfig.buildings.isEmpty())
    }

    @Test
    fun `合并后搜索与楼栋定位仍然可用`() {
        val config = EditorConfigCodec.encode(
            EditorConfig(buildings = listOf(outline("A", "A栋"))),
        )
        val repo = CampusRepository.create(baseJson, config).repository

        // 楼栋定位：新多边形的内部点应命中 A 栋
        assertEquals("A", repo.buildingAt(LngLat(113.1305, 23.1305))?.id)
        // 中文名搜索：元素还在，所以还能搜到
        assertTrue(repo.search("语文教研室").isNotEmpty())
    }

    @Test
    fun `数据自检能发现新楼栋没有元素`() {
        val config = EditorConfigCodec.encode(
            EditorConfig(buildings = listOf(outline("B", "B栋"))),
        )
        val repo = CampusRepository.create(baseJson, config).repository
        // B 栋没有楼层，自检应把它报出来（面向上架前的数据质量把关）
        val problems = repo.validate()
        assertTrue("自检应报出 B 栋缺楼层，实际：$problems", problems.any { it.contains("B栋") })
    }
}
