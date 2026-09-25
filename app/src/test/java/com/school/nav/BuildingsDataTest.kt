package com.school.nav

import com.school.nav.core.data.CampusRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 针对真实资源文件 `app/src/main/assets/buildings.json` 的测试。
 *
 * 这是“产品能跑起来”的守门测试：数据文件录错了、或导航算法改了导致验收场景不成立，
 * 这里会第一时间失败。不需要 Android 运行时，直接读文件（相对模块目录解析）。
 */
class BuildingsDataTest {

    private val dataFile: File = sequenceOf(
        File("src/main/assets/buildings.json"),
        File("app/src/main/assets/buildings.json"),
    ).firstOrNull { it.exists() }
        ?: error("找不到 buildings.json，当前工作目录：${File(".").absolutePath}")

    private val repository = CampusRepository(dataFile.readText())

    @Test
    fun `数据文件通过自检`() {
        val problems = repository.validate()
        assertTrue("buildings.json 自检未通过：\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    @Test
    fun `包含验收场景需要的两栋楼`() {
        val names = repository.buildings.map { it.name }
        assertTrue("应有 A栋，实际：$names", names.contains("A栋"))
        assertTrue("应有 B栋，实际：$names", names.contains("B栋"))
    }

    @Test
    fun `每栋楼首层高度为 0 且逐层递增`() {
        for (building in repository.buildings) {
            val floors = building.orderedFloors
            assertEquals("${building.name} 首层应为 0 米", 0.0, floors.first().relativeHeightM, 1e-9)
            for (i in 1 until floors.size) {
                assertTrue(
                    "${building.name} 楼层高度应递增",
                    floors[i].relativeHeightM > floors[i - 1].relativeHeightM,
                )
            }
        }
    }

    @Test
    fun `验收标准 搜索物理实验室能匹配到目标`() {
        val hit = repository.search("物理实验室").firstOrNull()
        assertNotNull("搜不到“物理实验室”", hit)
        assertEquals("物理实验室", hit!!.element.name)
        assertEquals(2, hit.floor.level)
    }

    @Test
    fun `导航文案里不出现元素编号`() {
        // id 只用于内部关联，导航文案只能出现中文名
        for (building in repository.buildings) {
            for (floor in building.floors) {
                for (element in floor.elements) {
                    assertTrue(
                        "${element.name} 的中文名不应包含编号样式",
                        !element.name.contains("_"),
                    )
                }
            }
        }
    }

    @Test
    fun `快捷目标在真实数据里都能命中`() {
        val quick = repository.quickTargets()
        assertTrue("快捷目标不应为空", quick.isNotEmpty())
        assertEquals("物理实验室", quick.first().element.name)
    }

    @Test
    fun `每层都有楼梯口作为跨层换乘点`() {
        for (building in repository.buildings) {
            for (floor in building.floors) {
                val hasVertical = floor.elements.any {
                    it.elementType == com.school.nav.core.model.ElementType.Stair ||
                        it.elementType == com.school.nav.core.model.ElementType.Elevator
                }
                assertTrue("${building.name} ${floor.level} 楼缺少楼梯口/电梯口", hasVertical)
            }
        }
    }
}
