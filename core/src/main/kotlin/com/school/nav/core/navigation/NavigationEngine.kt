package com.school.nav.core.navigation

import com.school.nav.core.model.Element
import com.school.nav.core.model.Position
import com.school.nav.core.model.Target

/**
 * 导航层入口。
 *
 * 负责把“我现在在哪 / 我要去哪”组装成中文文字导航，覆盖三种情况：
 *
 *  1. 同楼同层：沿走廊走 + 中间经过的元素 + 目标在左手边/右手边；
 *  2. 同楼跨层：先走到楼梯口 -> 上/下到 N 楼 -> 出楼梯后按同层走；
 *  3. 跨楼：先出楼 -> 前往目标楼栋 -> 进楼上/下到 N 楼 -> 出楼梯后按同层走。
 *
 * 纯 Kotlin、无副作用，可以被单元测试完整覆盖。
 */
class NavigationEngine(
    private val config: RouteTextConfig = RouteTextConfig.Default,
) {

    /**
     * 生成从 [current] 到 [target] 的文字导航。
     *
     * [current] 的三级（楼栋、楼层、元素）都必须已经确定 —— 楼层未知时
     * 由 UI 先让用户补全，而不是在这里猜。
     */
    fun route(current: Position, target: Target): RouteResult {
        val steps = when {
            current.building.id == target.building.id && current.floor.id == target.floor.id ->
                sameFloorRoute(current, target)

            current.building.id == target.building.id ->
                crossFloorRoute(current, target)

            else -> crossBuildingRoute(current, target)
        }
        return RouteResult(steps = steps, target = target)
    }

    // ------------------------------------------------------------ 同层

    private fun sameFloorRoute(current: Position, target: Target): List<RouteStep> {
        val order = FloorSorter.sort(current.floor)
        return RouteText.sameFloor(
            current = current.element,
            target = target.element,
            order = order,
            prefix = RouteText.SAME_FLOOR_PREFIX,
            config = config,
            walkingDirection = walkingDirection(order, current.element, target.element),
        )
    }

    /**
     * 同层前进方向。
     *
     * 用走廊主轴（[CorridorOrder.direction]）而不是两个元素质心的精确方位角：
     * 走廊是一条线，用户沿它走，“向东”永远是向东。取两点连线会因为元素在南北两侧
     * 错开几米而算出“向东北”这种不自然的说法。
     */
    private fun walkingDirection(
        order: CorridorOrder,
        current: Element,
        target: Element,
    ): CorridorDirection {
        val fromIndex = order.indexOf(current.id)
        val toIndex = order.indexOf(target.id)
        val forward = fromIndex < 0 || toIndex < 0 || toIndex > fromIndex
        return if (forward) order.direction else RouteText.opposite(order.direction)
    }

    // ------------------------------------------------------------ 跨层

    private fun crossFloorRoute(current: Position, target: Target): List<RouteStep> {
        val steps = mutableListOf<RouteStep>()

        val currentOrder = FloorSorter.sort(current.floor)
        val stairHere = FloorSorter.findStair(current.floor, current.element.centroid)
            ?: return listOf(RouteStep("这一层没有楼梯口数据，暂时无法跨层导航。"))

        steps += RouteText.verticalTransition(
            currentFloor = current.floor,
            targetFloor = target.floor,
            currentElement = current.element,
            stair = stairHere,
            order = currentOrder,
            config = config,
        )

        // 出楼梯后按目标楼层的同层导航继续
        val targetOrder = FloorSorter.sort(target.floor)
        val stairThere = FloorSorter.findStair(target.floor, target.element.centroid) ?: stairHere
        steps += RouteText.sameFloor(
            current = stairThere,
            target = target.element,
            order = targetOrder,
            prefix = RouteText.AFTER_STAIR_PREFIX,
            config = config,
            walkingDirection = walkingDirection(targetOrder, stairThere, target.element),
        )
        return steps
    }

    // ------------------------------------------------------------ 跨楼

    private fun crossBuildingRoute(current: Position, target: Target): List<RouteStep> {
        val steps = mutableListOf<RouteStep>()

        // 第一步：出楼 + 前往目标楼栋 + 到达目标楼层
        steps += RouteText.crossBuilding(
            current = current,
            target = target,
            distanceMeters = RouteText.buildingDistance(current.building, target.building),
        )

        // 第二步：目标楼栋内，从楼梯口走到目标
        val targetOrder = FloorSorter.sort(target.floor)
        val stairThere = FloorSorter.findStair(target.floor, target.element.centroid)
            ?: return steps + listOf(RouteStep("目标楼层没有楼梯口数据，暂时无法给出楼内路线。"))

        steps += RouteText.sameFloor(
            current = stairThere,
            target = target.element,
            order = targetOrder,
            prefix = RouteText.AFTER_STAIR_PREFIX,
            config = config,
            walkingDirection = walkingDirection(targetOrder, stairThere, target.element),
        )
        return steps
    }
}
