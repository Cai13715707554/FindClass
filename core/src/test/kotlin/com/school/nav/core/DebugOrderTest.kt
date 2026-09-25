package com.school.nav.core

import org.junit.Ignore
import org.junit.Test

/**
 * 已停用的临时诊断类。
 *
 * 曾经用它 dump 走廊排序与左右判定的真实数值，定位过两个真实 bug：
 *  1. PCA 主轴被数据里的小错位带偏约 6 度，导致同一侧的两间教室被判成“正前方”；
 *  2. 主轴方向的可复现性。
 * 两者现在都已被 [FloorSorterTest] 与 [NavigationEngineTest] 覆盖。
 *
 * 本机沙箱不允许删除文件，因此保留这个空壳：它没有断言、也不参与测试统计。
 * 需要再次手工调试时，把 dump 逻辑写进 [debug] 并去掉类上的 @Ignore 即可。
 */
@Ignore("诊断占位，正式断言见 FloorSorterTest / NavigationEngineTest")
class DebugOrderTest {

    @Test
    fun debug() = Unit
}
