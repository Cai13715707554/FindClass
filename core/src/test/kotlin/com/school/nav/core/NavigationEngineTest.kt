package com.school.nav.core

import com.school.nav.core.Fixtures.element
import com.school.nav.core.model.Position
import com.school.nav.core.model.Target
import com.school.nav.core.navigation.NavigationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航文案测试。
 *
 * 这些断言直接对应《产品设计文档》第十一节 MVP 验收标准：
 * 同层要列出中间经过的中文名、跨层要提示上下到几楼、跨楼要提示先出楼，
 * 且全程不出现米数与教室编号。
 */
class NavigationEngineTest {

    private val engine = NavigationEngine()

    private val buildingA = Fixtures.standardBuilding(id = "A", name = "A栋", centerX = 0.0)
    private val buildingB = Fixtures.standardBuildingB(id = "B", name = "B栋", centerX = 100.0)

    private fun position(building: com.school.nav.core.model.Building, level: Int, name: String): Position {
        val floor = building.floorByLevel(level)!!
        return Position(building, floor, floor.element(name))
    }

    private fun target(building: com.school.nav.core.model.Building, level: Int, name: String): Target {
        val floor = building.floorByLevel(level)!!
        return Target(building, floor, floor.element(name))
    }

    // ------------------------------------------------------------ 同层

    @Test
    fun `同层导航列出中间经过的中文名`() {
        val result = engine.route(
            current = position(buildingA, 1, "语文教研室"),
            target = target(buildingA, 1, "数学教研室"),
        )

        // 两间教室都在走廊北侧、中间隔着计算机房：目标在左手边（同一侧）
        assertEquals(
            "从当前位置出发，沿走廊向东走，经过计算机房，目标数学教研室在左手边。",
            result.plainText,
        )    }

    @Test
    fun `中间元素过多时用等 N 个位置概括`() {
        val result = engine.route(
            current = position(buildingA, 1, "语文教研室"),
            target = target(buildingA, 1, "地理教研室"),
        )

        // 中间有 7 个元素，超过逐个列名的上限
        assertTrue("实际文案：${result.plainText}", result.plainText.contains("等 7 个位置"))
        // 地理教研室在北侧，向东走时在左手边
        assertTrue(result.plainText.contains("目标地理教研室在左手边"))
    }

    @Test
    fun `向西走时仍按目标所在的走廊侧给方位`() {
        val result = engine.route(
            current = position(buildingA, 1, "历史教研室"),
            target = target(buildingA, 1, "数学教研室"),
        )

        // 用 assertEquals 而不是 assertTrue：失败时能直接看到实际文案。
        //
        // 数学教研室在走廊北侧，向西走时北侧仍是左手边 —— 左右取决于目标在走廊哪一侧，
        // 而不是“目标相对当前位置偏东还是偏西”。这一点与直觉一致：同一间教室，
        // 从东边走还是从西边走过去，它都在走道的同一侧。
        assertEquals(
            "从当前位置出发，沿走廊向西走，经过语音教室、英语教研室、多媒体教室，" +
                "目标数学教研室在左手边。",
            result.plainText,
        )
    }

    @Test
    fun `目标与当前位置同在走廊同一侧时按目标所在侧给方位`() {
        // 物理实验室与化学实验室都在走廊北侧、互为正前方。
        // 判据是“目标在走廊哪一侧”，而不是“相对当前位置的横向差”，
        // 后者会被数据里的轻微主轴偏转放大成噪声。
        val result = engine.route(
            current = position(buildingA, 2, "物理实验室"),
            target = target(buildingA, 2, "化学实验室"),
        )
        assertTrue("实际文案：${result.plainText}", result.plainText.contains("在左手边"))
    }

    @Test
    fun `目标就在隔壁时用最短文案`() {
        val result = engine.route(
            current = position(buildingA, 2, "物理实验室"),
            target = target(buildingA, 2, "音乐教室"),
        )

        assertTrue("实际文案：${result.plainText}", result.plainText.contains("就在隔壁"))
    }

    @Test
    fun `起点即终点时不给导航文案`() {
        val result = engine.route(
            current = position(buildingA, 2, "物理实验室"),
            target = target(buildingA, 2, "物理实验室"),
        )

        assertTrue(result.isArrived)
        assertTrue(result.steps.isEmpty())
    }

    @Test
    fun `从走廊入口出发时按目标所在侧给方位`() {
        // 南门位于走廊正中（横向偏移约 0），数学教研室在北侧。
        // 两者不在同一侧，因此按目标的实际方位给出左右。
        val result = engine.route(
            current = position(buildingA, 1, "南门"),
            target = target(buildingA, 1, "数学教研室"),
        )
        assertTrue("实际文案：${result.plainText}", result.plainText.contains("在左手边"))
    }

    // ------------------------------------------------------------ 跨层

    @Test
    fun `跨层导航提示先上到几楼并说明出楼梯后怎么走`() {
        val result = engine.route(
            current = position(buildingA, 3, "高一(1)班"),
            target = target(buildingA, 2, "物理实验室"),
        )

        assertEquals(2, result.steps.size)
        val first = result.steps[0].text
        val second = result.steps[1].text

        // 第一步：走到楼梯口 + 说明下到 2 楼（楼梯口是关键转向点）
        assertTrue("实际文案：$first", first.contains("东楼梯口"))
        assertTrue("实际文案：$first", first.contains("下到 2 楼"))

        // 第二步：出楼梯后按同层导航走
        assertTrue("实际文案：$second", second.startsWith("出楼梯后，"))
        assertTrue("实际文案：$second", second.contains("目标物理实验室"))
        assertTrue("实际文案：$second", second.contains("左手边"))
    }

    @Test
    fun `上楼时用上到文案`() {
        val result = engine.route(
            current = position(buildingA, 1, "语文教研室"),
            target = target(buildingA, 3, "教务处"),
        )

        assertTrue("实际文案：${result.plainText}", result.plainText.contains("上到 3 楼"))
    }

    @Test
    fun `人已经在楼梯口时不说先走到`() {
        val result = engine.route(
            current = position(buildingA, 1, "东楼梯口"),
            target = target(buildingA, 2, "物理实验室"),
        )

        assertTrue(
            "实际文案：${result.plainText}",
            result.plainText.startsWith("你在东楼梯口，直接上到 2 楼。"),
        )
    }

    // ------------------------------------------------------------ 跨楼

    @Test
    fun `跨楼导航提示先出楼并前往目标楼栋`() {
        // A 栋 3 楼 -> B 栋 2 楼：需要下楼
        val result = engine.route(
            current = position(buildingA, 3, "高一(1)班"),
            target = target(buildingB, 2, "图书馆"),
        )

        val text = result.plainText
        assertTrue("实际文案：$text", text.contains("先出A栋"))
        assertTrue("实际文案：$text", text.contains("B栋"))
        assertTrue("实际文案：$text", text.contains("进楼后下到 2 楼"))
        assertTrue("实际文案：$text", text.contains("出楼梯后，"))
        assertTrue("实际文案：$text", text.contains("目标图书馆"))
    }

    @Test
    fun `跨楼上楼时提示上到几楼`() {
        val result = engine.route(
            current = position(buildingA, 1, "语文教研室"),
            target = target(buildingB, 2, "图书馆"),
        )

        assertTrue("实际文案：${result.plainText}", result.plainText.contains("进楼后上到 2 楼"))
    }

    @Test
    fun `跨楼到一楼时不说上楼`() {
        val result = engine.route(
            current = position(buildingA, 3, "教务处"),
            target = target(buildingB, 1, "报告厅"),
        )

        assertTrue("实际文案：${result.plainText}", result.plainText.contains("进楼后到 1 楼"))
    }

    // ------------------------------------------------------------ 文案原则

    @Test
    fun `所有导航文案都不出现米数`() {
        val cases = listOf(
            position(buildingA, 1, "语文教研室") to target(buildingA, 1, "历史教研室"),
            position(buildingA, 3, "高一(1)班") to target(buildingA, 2, "物理实验室"),
            position(buildingA, 1, "语文教研室") to target(buildingB, 2, "图书馆"),
            position(buildingA, 2, "物理实验室") to target(buildingA, 2, "音乐教室"),
        )

        for ((current, goal) in cases) {
            val text = engine.route(current, goal).plainText
            assertFalse("文案里出现了“米”：$text", text.contains("米"))
            assertFalse("文案里出现了数字+米：$text", Regex("""\d+\s*(m|M|米)""").containsMatchIn(text))
        }
    }

    @Test
    fun `所有导航文案都不出现元素 id`() {
        val current = position(buildingA, 3, "高一(1)班")
        val goal = target(buildingA, 2, "物理实验室")
        val text = engine.route(current, goal).plainText

        assertFalse("文案里出现了 id：$text", text.contains(current.element.id))
        assertFalse("文案里出现了 id：$text", text.contains(goal.element.id))
        assertFalse("文案里出现了下划线 id 形态：$text", text.contains("A_2F"))
    }

    @Test
    fun `高亮字段就是目标中文名`() {
        val result = engine.route(
            current = position(buildingA, 1, "语文教研室"),
            target = target(buildingA, 1, "历史教研室"),
        )
        assertTrue(result.steps.all { it.highlight == "历史教研室" })
    }
}
