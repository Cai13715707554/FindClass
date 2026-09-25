package com.school.nav.core

import com.school.nav.core.data.CampusRepository
import com.school.nav.core.data.EditorBuilding
import com.school.nav.core.data.EditorConfig
import com.school.nav.core.data.EditorConfigCodec
import com.school.nav.core.data.EditorElement
import com.school.nav.core.data.EditorMode
import com.school.nav.core.data.FloorDraft
import com.school.nav.core.model.LngLat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地图编辑器配置的编解码，以及「assets 打底 + 配置覆盖」的合并规则。
 *
 * 合并规则是这套功能里最容易出事的地方：写错了会把内置数据的楼层清空，
 * 表现是「楼栋还在、导航全废」，而且不会崩、不会报错，极难发现。
 * 这里的断言就是防这个。
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
                },
                {
                  "id": "A_2F", "level": 2, "relative_height_m": 4.0,
                  "elements": [
                    {
                      "id": "e2", "type": "room", "name": "物理实验室",
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

    private fun outline(id: String, name: String, count: Int = 4): EditorBuilding =
        EditorBuilding(
            id = id,
            name = name,
            polygon = (0 until count).map { LngLat(113.130 + it * 0.001, 23.130 + it * 0.001) },
        )

    private fun element(name: String, id: String = "el-$name"): EditorElement =
        EditorElement.from(
            mode = EditorMode.Room,
            id = id,
            name = name,
            points = (0 until 3).map { LngLat(113.131 + it * 0.0002, 23.131 + it * 0.0002) },
        )

    private fun encode(config: EditorConfig): String = EditorConfigCodec.encode(config)

    // ------------------------------------------------------------ 编解码

    @Test
    fun `配置能序列化再反序列化且内容不变`() {
        val config = EditorConfig(
            exportedAtMillis = 1_700_000_000_000L,
            buildings = listOf(outline("A", "A栋")),
        )
        val decoded = EditorConfigCodec.decode(encode(config))

        assertNotNull(decoded)
        assertEquals(config.exportedAtMillis, decoded!!.exportedAtMillis)
        assertEquals("A", decoded.buildings.single().id)
        assertEquals(4, decoded.buildings.single().polygon.size)
    }

    @Test
    fun `带楼层元素的配置能完整往返`() {
        val config = EditorConfig(
            buildings = listOf(
                outline("A", "A栋").copy(
                    floors = listOf(FloorDraft(level = 3, elements = listOf(element("高一(1)班")))),
                ),
            ),
        )
        val decoded = EditorConfigCodec.decode(encode(config))!!

        val floor = decoded.buildings.single().floor(3)
        assertNotNull("楼层应被保留", floor)
        assertEquals("高一(1)班", floor!!.elements.single().name)
    }

    @Test
    fun `非法 JSON 返回 null 而不是抛异常`() {
        assertNull(EditorConfigCodec.decode("这不是 JSON"))
        assertNull(EditorConfigCodec.decode(""))
        assertNull(EditorConfigCodec.decode("{{{ 半截"))
    }

    @Test
    fun `空对象解析成默认配置而不是 null`() {
        // {} 是合法 JSON，且所有字段都有默认值，所以应解析成「空配置」——
        // 等价于「编辑器还没画过东西」，App 照常有内置数据可用。
        val decoded = EditorConfigCodec.decode("{}")
        assertNotNull(decoded)
        assertEquals(EditorConfig.CURRENT_SCHEMA_VERSION, decoded!!.schemaVersion)
        assertTrue(decoded.buildings.isEmpty())
    }

    @Test
    fun `v1 老文件（只有轮廓、没有 floors 字段）仍能解析`() {
        val v1 = """
            {
              "schema_version": 1,
              "buildings": [
                {
                  "id": "A", "name": "A栋",
                  "polygon": [
                    {"lng": 113.13, "lat": 23.13},
                    {"lng": 113.14, "lat": 23.13},
                    {"lng": 113.14, "lat": 23.14}
                  ]
                }
              ]
            }
        """.trimIndent()

        val decoded = EditorConfigCodec.decode(v1)
        assertNotNull("老版本文件必须还能读", decoded)
        assertEquals(3, decoded!!.buildings.single().polygon.size)
        assertTrue("缺省楼层应为空", decoded.buildings.single().floors.isEmpty())
    }

    // ------------------------------------------------------------ 有效性

    @Test
    fun `少于三个点或点重合的轮廓判为无效`() {
        assertTrue(outline("A", "A栋", count = 3).hasValidPolygon)
        assertFalse(outline("A", "A栋", count = 2).hasValidPolygon)
        assertFalse(outline("A", "A栋", count = 0).hasValidPolygon)

        val degenerate = EditorBuilding(
            id = "A",
            name = "A栋",
            polygon = List(4) { LngLat(113.13, 23.13) },
        )
        assertFalse("四个点全重合也应判为无效", degenerate.hasValidPolygon)
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
    fun `配置覆盖同名楼栋的外轮廓但保留原有楼层`() {
        val config = encode(EditorConfig(buildings = listOf(outline("A", "A栋新名"))))
        val merged = CampusRepository.create(baseJson, config)
        val building = merged.repository.buildings.single()

        assertEquals("A栋新名", building.name)
        assertEquals(4, building.polygon.size)
        // 关键断言：楼层必须还在，否则「覆盖」等于把楼废掉
        assertEquals("覆盖后楼层不能丢", 2, building.floors.size)
        assertEquals("A_1F", building.floors.first().id)
        assertEquals(1, building.floors.first().elements.size)
    }

    @Test
    fun `只补画某一层时其它层保留`() {
        // 配置里只画了 2 楼（用户只想改这一层），1 楼必须原样留着
        val config = encode(
            EditorConfig(
                buildings = listOf(
                    outline("A", "A栋").copy(
                        floors = listOf(FloorDraft(level = 2, elements = listOf(element("新物理实验室")))),
                    ),
                ),
            ),
        )
        val building = CampusRepository.create(baseJson, config).repository.buildings.single()

        assertEquals(2, building.floors.size)
        assertEquals("1 楼应保留 assets 的元素", "语文教研室", building.floorByLevel(1)!!.elements.single().name)
        assertEquals("2 楼应变成编辑器画的那间", "新物理实验室", building.floorByLevel(2)!!.elements.single().name)
    }

    @Test
    fun `配置新增楼层会按假定层高生成递增的相对高度`() {
        val config = encode(
            EditorConfig(
                buildings = listOf(
                    outline("A", "A栋").copy(
                        floors = listOf(FloorDraft(level = 5, elements = listOf(element("501")))),
                    ),
                ),
            ),
        )
        val building = CampusRepository.create(baseJson, config).repository.buildings.single()

        val floor5 = building.floorByLevel(5)
        assertNotNull("新楼层应被加入", floor5)
        assertEquals(
            "相对高度应按假定层高推算",
            5 * EditorBuilding.ASSUMED_FLOOR_HEIGHT_M,
            floor5!!.relativeHeightM,
            1e-9,
        )
        // 高度必须递增，否则楼层自检会报错、气压计判层也会错
        val heights = building.orderedFloors.map { it.relativeHeightM }
        assertEquals(heights.sorted(), heights)
    }

    @Test
    fun `配置里空格名字不会把原名字覆盖成空白`() {
        val config = encode(EditorConfig(buildings = listOf(outline("A", "   "))))
        val merged = CampusRepository.create(baseJson, config)
        assertEquals("A栋", merged.repository.buildings.single().name)
    }

    @Test
    fun `没有合法轮廓但有元素的草稿不会被整条丢掉`() {
        // 用户只想给已有楼栋补画教室，没重画轮廓
        val config = encode(
            EditorConfig(
                buildings = listOf(
                    EditorBuilding(
                        id = "A",
                        name = "A栋",
                        polygon = emptyList(),
                        floors = listOf(FloorDraft(level = 1, elements = listOf(element("新教室")))),
                    ),
                ),
            ),
        )
        val building = CampusRepository.create(baseJson, config).repository.buildings.single()

        assertEquals("轮廓缺失时应保留原轮廓", 4, building.polygon.size)
        assertEquals("新教室应被合并进 1 楼", "新教室", building.floorByLevel(1)!!.elements.single().name)
    }

    @Test
    fun `配置新增的楼栋会加进来但没有楼层并给出告警`() {
        val config = encode(EditorConfig(buildings = listOf(outline("B", "B栋"))))
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
    fun `完全没有内容的草稿被忽略并给出告警`() {
        val config = encode(
            EditorConfig(
                buildings = listOf(
                    outline("A", "A栋"),
                    EditorBuilding(id = "C", name = "C栋", polygon = emptyList()),
                ),
            ),
        )
        val merged = CampusRepository.create(baseJson, config)

        assertEquals("空的 C 栋不应被加入", 1, merged.repository.buildings.size)
        assertTrue(
            "应告警说明丢弃了空草稿，实际：${merged.warnings}",
            merged.warnings.any { it.contains("忽略") },
        )
    }

    @Test
    fun `损坏的配置文本不会影响 assets 数据`() {
        val merged = CampusRepository.create(baseJson, editorConfigJson = "{{{ 坏文件")
        val building = merged.repository.buildings.single()

        assertEquals("A栋", building.name)
        assertEquals(2, building.floors.size)
        assertTrue(merged.editorConfig.buildings.isEmpty())
    }

    @Test
    fun `合并后搜索与楼栋定位仍然可用`() {
        val config = encode(EditorConfig(buildings = listOf(outline("A", "A栋"))))
        val repo = CampusRepository.create(baseJson, config).repository

        assertEquals("A", repo.buildingAt(LngLat(113.1305, 23.1305))?.id)
        assertTrue("元素还在，所以还能搜到", repo.search("语文教研室").isNotEmpty())
    }

    @Test
    fun `数据自检能发现新楼栋没有元素`() {
        val config = encode(EditorConfig(buildings = listOf(outline("B", "B栋"))))
        val repo = CampusRepository.create(baseJson, config).repository
        val problems = repo.validate()
        assertTrue("自检应报出 B 栋缺楼层，实际：$problems", problems.any { it.contains("B栋") })
    }
}
