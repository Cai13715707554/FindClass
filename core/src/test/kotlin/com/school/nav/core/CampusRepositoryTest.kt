package com.school.nav.core

import com.school.nav.core.Fixtures.element
import com.school.nav.core.data.CampusRepository
import com.school.nav.core.model.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 数据仓库测试：搜索与自检是“搜不到目标”和“数据录错”两类问题的唯一防线。 */
class CampusRepositoryTest {

    private val building = Fixtures.standardBuilding()

    /** 只保留一栋楼的 JSON，字段名与 assets/buildings.json 完全一致。 */
    private val json: String = buildJson()

    private val repository = CampusRepository(json)

    @Test
    fun `解析 snake_case 的 relative_height_m`() {
        val floor = repository.buildings.first().floorByLevel(2)
        assertNotNull(floor)
        assertEquals(4.0, floor!!.relativeHeightM, 1e-9)
    }

    @Test
    fun `搜索中文名精确命中`() {
        val hit = repository.search("物理实验室").firstOrNull()
        assertNotNull(hit)
        assertEquals("物理实验室", hit!!.element.name)
        assertEquals(2, hit.floor.level)
    }

    @Test
    fun `搜索支持前缀与包含匹配`() {
        assertTrue(repository.search("物理").any { it.element.name == "物理实验室" })
        assertTrue(repository.search("教研室").any { it.element.name == "语文教研室" })
        assertTrue(repository.search("实验室").isNotEmpty())
    }

    @Test
    fun `搜索不到时返回空列表`() {
        assertTrue(repository.search("不存在的地方").isEmpty())
        assertTrue(repository.search("   ").isEmpty())
    }

    @Test
    fun `同名元素优先返回本楼本层的结果`() {
        // 东楼梯口在 1、2、3 楼都存在
        val current = Position(
            building = building,
            floor = building.floorByLevel(3)!!,
            element = building.floorByLevel(3)!!.element("教务处"),
        )
        val hit = repository.search("东楼梯口", current = current).first()
        assertEquals(3, hit.floor.level)
    }

    @Test
    fun `卫生间不作为导航目标`() {
        // 卫生间在数据里存在，但不该被当成要去的地方
        assertTrue(repository.search("卫生间").isEmpty())
    }

    @Test
    fun `默认当前位置排除楼梯口与卫生间`() {
        val floor = building.floorByLevel(1)!!
        val point = Fixtures.ll(-20.0, -5.0)  // 站在东楼梯口里
        val element = repository.nearestStandableElement(floor, point)
        assertNotNull(element)
        assertTrue(
            "不应把楼梯口当作人的位置，实际得到：${element!!.name}",
            element.name != "东楼梯口",
        )
    }

    @Test
    fun `GPS 点落在楼栋多边形内能识别楼栋`() {
        val inside = repository.buildingAt(Fixtures.ll(0.0, 0.0))
        assertEquals("A", inside?.id)
    }

    @Test
    fun `GPS 点在楼外时返回 null`() {
        assertNull(repository.buildingAt(Fixtures.ll(500.0, 500.0)))
    }

    @Test
    fun `数据自检通过合法数据`() {
        assertTrue("自检不应报错：${repository.validate()}", repository.validate().isEmpty())
    }

    @Test
    fun `数据自检能发现楼层高度倒挂`() {
        val broken = CampusRepository(
            json.replace("\"relative_height_m\": 8.0", "\"relative_height_m\": -1.0"),
        )
        val problems = broken.validate()
        assertTrue(
            "应报出楼层高度问题，实际：$problems",
            problems.any { it.contains("相对高度") || it.contains("首层") },
        )
    }

    @Test
    fun `快捷目标只返回数据里真实存在的项`() {
        val quick = repository.quickTargets()
        assertTrue(quick.isNotEmpty())
        assertTrue(quick.all { hit -> repository.search(hit.element.name).isNotEmpty() })
    }

    private fun buildJson(): String {
        val elements = building.floors.joinToString(",") { floor ->
            val items = floor.elements.joinToString(",") { element ->
                """
                {
                  "id": "${element.id}",
                  "type": "${element.type}",
                  "name": "${element.name}",
                  "points": [${element.points.joinToString(",") {
                    """{"lng": ${it.lng}, "lat": ${it.lat}}"""
                }}]
                }
                """.trimIndent()
            }
            """
            {
              "id": "${floor.id}",
              "level": ${floor.level},
              "relative_height_m": ${floor.relativeHeightM},
              "elements": [$items]
            }
            """.trimIndent()
        }

        val polygon = building.polygon.joinToString(",") {
            """{"lng": ${it.lng}, "lat": ${it.lat}}"""
        }

        return """
        {
          "buildings": [
            {
              "id": "${building.id}",
              "name": "${building.name}",
              "polygon": [$polygon],
              "floors": [$elements]
            }
          ]
        }
        """.trimIndent()
    }
}
