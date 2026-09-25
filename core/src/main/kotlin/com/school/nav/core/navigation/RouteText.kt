package com.school.nav.core.navigation

import com.school.nav.core.model.Building
import com.school.nav.core.model.Element
import com.school.nav.core.model.ElementType
import com.school.nav.core.model.Floor
import com.school.nav.core.model.Geo
import com.school.nav.core.model.Position
import com.school.nav.core.model.Target

/**
 * 一条导航文案。一个 [RouteStep] 对应界面上的一行。
 *
 * 文案与算法分离：这里只管“怎么说”，不管“往哪走”。
 * [highlight] 标出需要在 UI 里高亮的部分（通常是目标的中文名）。
 */
data class RouteStep(
    val text: String,
    val highlight: String? = null,
)

/** 导航结果。 */
data class RouteResult(
    val steps: List<RouteStep>,
    val target: Target,
) {
    /** 是否原地不动（起点就是终点）。 */
    val isArrived: Boolean get() = steps.isEmpty()

    /** 纯文本形式，便于日志、测试与无障碍朗读。 */
    val plainText: String get() = steps.joinToString("") { it.text }
}

/** 文案模板的可调参数。放在一处便于后续打磨文案，不必改算法。 */
data class RouteTextConfig(
    /** 中间元素不超过这个数量就逐个列名，超过则概括为“经过 X 等 N 个位置”。 */
    val maxNamedIntermediate: Int = 4,
) {
    companion object {
        val Default = RouteTextConfig()
    }
}

/**
 * 中文导航文案生成器。
 *
 * 硬性约束（来自产品文档与验收标准）：
 *  - 绝不出现米数；
 *  - 绝不出现教室编号 / id；
 *  - 只用元素的中文名；
 *  - 只用“沿走廊向东走、左手边、右手边、正前方、隔壁”这类相对描述；
 *  - 楼梯口、电梯口作为跨层的关键转向点。
 */
object RouteText {

    /** 跨层时，出楼梯后那一段的前置语。 */
    const val AFTER_STAIR_PREFIX = "出楼梯后，"

    /** 同层导航默认前置语。 */
    const val SAME_FLOOR_PREFIX = "从当前位置出发，"

    /** 楼栋间距小于该值时，跨楼文案说“就在旁边”。 */
    const val NEARBY_BUILDING_METERS = 120.0

    // ---------------------------------------------------------------- 同层

    /**
     * 生成同层导航文案。
     *
     * @param prefix 前置语，例如“从当前位置出发，”或“出楼梯后，”。
     */
    fun sameFloor(
        current: Element,
        target: Element,
        order: CorridorOrder,
        prefix: String = SAME_FLOOR_PREFIX,
        config: RouteTextConfig = RouteTextConfig.Default,
        /**
         * 显式指定沿走廊前进的方向。
         *
         * 建议调用方总是传入，因为它是**走廊主轴**的方向；为空时按排序结果推断，
         * 会让文案方向与排序方向不一致（例如排序恰好是反的）。
         */
        walkingDirection: CorridorDirection? = null,
    ): List<RouteStep> {
        if (current.id == target.id) return emptyList()

        val fromIndex = order.indexOf(current.id)
        val toIndex = order.indexOf(target.id)
        if (fromIndex < 0 || toIndex < 0) {
            return listOf(RouteStep("${prefix}位置信息有误，请重新选择当前位置。"))
        }

        val forward = toIndex > fromIndex
        val between = if (forward) {
            order.elements.subList(fromIndex + 1, toIndex).toList()
        } else {
            order.elements.subList(toIndex + 1, fromIndex).reversed()
        }

        val directionText = (walkingDirection ?: order.direction).text
        val side = FloorSorter.sideOf(order, current, target)

        // 隔壁：紧挨着，且与当前位置不在同一侧（例如隔着走廊正对门），
        // 这时“沿走廊走”是多余的，直接说隔壁最短最自然。
        if (between.isEmpty() && side != Side.Center) {
            return listOf(
                RouteStep(
                    "${prefix}目标${target.name}就在隔壁，在${sideText(side)}。",
                    target.name,
                ),
            )
        }

        val lead = "${prefix}沿走廊${directionText}走"
        val middle = when {
            between.isEmpty() -> ""
            between.size <= config.maxNamedIntermediate ->
                "，经过" + between.joinToString("、") { it.name }

            else -> "，经过${between.first().name}等 ${between.size} 个位置"
        }
        val ending = when (side) {
            Side.Left -> "，目标${target.name}在左手边。"
            Side.Right -> "，目标${target.name}在右手边。"
            Side.Center -> "，目标${target.name}就在正前方。"
        }

        return listOf(RouteStep(lead + middle + ending, target.name))
    }

    // ---------------------------------------------------------------- 跨层

    /**
     * 生成“先走到某楼梯口，上/下到 N 楼”这一段。
     *
     * 三种情况：
     *  - 人已经在楼梯口：`你在东楼梯口，直接上到 3 楼。`
     *  - 同层可达且中途有元素：`先沿走廊向东走，经过计算机房，到东楼梯口，上到 3 楼。`
     *  - 说不清中间元素：`先走到东楼梯口，上到 3 楼。`
     *
     * 楼梯口是关键转向点，因此这一段始终显式点出楼梯口的中文名。
     */
    fun verticalTransition(
        currentFloor: Floor,
        targetFloor: Floor,
        currentElement: Element,
        stair: Element,
        order: CorridorOrder? = null,
        config: RouteTextConfig = RouteTextConfig.Default,
    ): List<RouteStep> {
        val verb = if (targetFloor.level > currentFloor.level) "上" else "下"

        if (currentElement.id == stair.id) {
            return listOf(RouteStep("你在${stair.name}，直接${verb}到 ${targetFloor.level} 楼。"))
        }

        val walkPart = order?.let { buildWalkToStair(currentElement, stair, it, config) }
        val lead = walkPart ?: "先走到${stair.name}"
        return listOf(RouteStep("${lead}，${verb}到 ${targetFloor.level} 楼。"))
    }

    /** 拼出“先沿走廊向东走，经过计算机房，到东楼梯口”。返回 null 表示数据不足以描述。 */
    private fun buildWalkToStair(
        current: Element,
        stair: Element,
        order: CorridorOrder,
        config: RouteTextConfig,
    ): String? {
        val fromIndex = order.indexOf(current.id)
        val stairIndex = order.indexOf(stair.id)
        if (fromIndex < 0 || stairIndex < 0) return null

        val forward = stairIndex > fromIndex
        val between = if (forward) {
            order.elements.subList(fromIndex + 1, stairIndex).toList()
        } else {
            order.elements.subList(stairIndex + 1, fromIndex).reversed()
        }

        val direction = FloorSorter.directionFrom(order, current, stair)
        val middle = when {
            between.isEmpty() -> ""
            between.size <= config.maxNamedIntermediate ->
                "，经过" + between.joinToString("、") { it.name }

            else -> "，经过${between.first().name}等 ${between.size} 个位置"
        }
        return "先沿走廊${direction.text}走${middle}，到${stair.name}"
    }

    // ---------------------------------------------------------------- 跨楼

    /**
     * 生成“先出 A 栋，前往 B 栋，进楼后上到 2 楼”这一段。
     *
     * 跨楼时无法知道用户会走哪个门，因此只给楼栋级别的指引，
     * 不编造“出哪个门”这种没有数据支撑的说法。
     */
    fun crossBuilding(
        current: Position,
        target: Target,
        distanceMeters: Double,
    ): List<RouteStep> {
        val verb = if (target.floor.level > current.floor.level) "上" else "下"
        val levelPart = if (target.floor.level == 1) {
            "进楼后到 1 楼。"
        } else {
            "进楼后${verb}到 ${target.floor.level} 楼。"
        }
        val proximity = if (distanceMeters <= NEARBY_BUILDING_METERS) {
            "${target.building.name}就在旁边，"
        } else {
            "前往${target.building.name}，"
        }
        return listOf(RouteStep("先出${current.building.name}，${proximity}${levelPart}"))
    }

    /** 跨楼时，若能识别出口元素，先引导用户走到出口。 */
    fun exitHint(current: Position): List<RouteStep> {
        val entrance = current.floor.elements.firstOrNull { it.elementType == ElementType.Entrance }
            ?: return emptyList()
        if (entrance.id == current.element.id) return emptyList()
        return listOf(RouteStep("先走到${entrance.name}。"))
    }

    // ---------------------------------------------------------------- 工具

    fun sideText(side: Side): String = when (side) {
        Side.Left -> "左手边"
        Side.Right -> "右手边"
        Side.Center -> "正前方"
    }

    /** 走廊主轴反向文案：向东 <-> 向西，向东北 <-> 向西南 … */
    fun opposite(direction: CorridorDirection): CorridorDirection = when (direction) {
        CorridorDirection.East -> CorridorDirection.West
        CorridorDirection.West -> CorridorDirection.East
        CorridorDirection.North -> CorridorDirection.South
        CorridorDirection.South -> CorridorDirection.North
        CorridorDirection.NorthEast -> CorridorDirection.SouthWest
        CorridorDirection.SouthWest -> CorridorDirection.NorthEast
        CorridorDirection.NorthWest -> CorridorDirection.SouthEast
        CorridorDirection.SouthEast -> CorridorDirection.NorthWest
    }

    /** 两栋楼之间的代表距离，用于判断是不是“就在旁边”。 */
    fun buildingDistance(a: Building, b: Building): Double =
        Geo.haversineMeters(Geo.centroid(a.polygon), Geo.centroid(b.polygon))
}
