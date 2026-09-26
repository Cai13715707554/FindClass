package com.school.nav.state

import com.school.nav.core.data.EditorBuilding
import com.school.nav.core.data.EditorMode
import com.school.nav.core.model.ElementType
import com.school.nav.core.model.LngLat
import com.school.nav.data.ActiveConfigStore
import com.school.nav.data.ApiKeyStore
import com.school.nav.data.ConfigStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 地图编辑器状态机测试。
 *
 * 交互模型（用户要求「别让人靠点三个点来划定位置」之后定的）：
 *
 *  - **拖拽矩形**：教学楼 / 教室 / 办公室 —— 按住从一角拖到对角；
 *  - **拖拽圆**：圆形区域 —— 按下的点当圆心，拖出去的距离当半径；
 *  - **点击落点**：楼梯口 / 卫生间 —— 点一下就放置，不用画边界；
 *  - **编辑形状**：选中后拖顶点 / 拖整体平移 / 边上插点 / 删点 / 旋转。
 *
 * 另外覆盖三件用户明确提过的事：
 *  1. 撤销必须能把**已经写进配置文件**的楼栋也撤回来（历史里存的是整份数据快照）；
 *  2. 配置要有**多份**，能在设置页切换 / 新建 / 复制 / 改名 / 删除；
 *  3. 切换配置后编辑器里的楼栋要整批换掉，不能还留着上一所学校的。
 *
 * 绘制逻辑刻意不依赖高德 SDK，所以能在纯 JVM 上测干净；
 * 地图渲染本身需要真机与有效 Key，这里不覆盖。
 */
class MapEditorViewModelTest {

    private lateinit var root: File
    private lateinit var configStore: ConfigStore
    private lateinit var activeStore: InMemoryActiveConfigStore
    private lateinit var keyStore: InMemoryApiKeyStore

    /** 内存版 Key 存储，避免拉起 SharedPreferences。 */
    private class InMemoryApiKeyStore(
        private var key: String = "",
        private var webKey: String = "",
    ) : ApiKeyStore {
        override fun amapKey(): String = key

        override fun amapWebKey(): String = webKey

        override fun saveAmapKey(key: String) {
            this.key = key.trim()
        }

        override fun saveAmapWebKey(key: String) {
            this.webKey = key.trim()
        }

        override fun clearAmapKey() {
            key = ""
        }

        override fun clearAmapWebKey() {
            webKey = ""
        }
    }

    /** 内存版「当前用哪一份配置」，同样避免 SharedPreferences。 */
    private class InMemoryActiveConfigStore(private var name: String? = null) : ActiveConfigStore {
        override fun activeFileName(): String? = name

        override fun setActiveFileName(name: String?) {
            this.name = name
        }
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("editor-vm-test").toFile()
        activeStore = InMemoryActiveConfigStore()
        configStore = ConfigStore(
            filesDir = File(root, "internal").apply { mkdirs() },
            externalFilesDir = File(root, "external").apply { mkdirs() },
            activeConfigStore = activeStore,
        )
        keyStore = InMemoryApiKeyStore()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** `onDataChanged` 被调了几次 —— 用来验证「切换配置会通知外层重新装配仓库」。 */
    private var dataChanged = 0

    private fun viewModel() =
        MapEditorViewModel(
            configStore = configStore,
            apiKeyStore = keyStore,
            // 不传 locationSource：定位需要 Android，绘制逻辑不该依赖它
            locationSource = null,
            onDataChanged = { dataChanged++ },
        )

    private val dragFrom = LngLat(113.1300, 23.1300)
    private val dragTo = LngLat(113.1304, 23.1303)

    /** 模拟一次拖拽：按下 → 移动 → 抬起。 */
    private fun drag(vm: MapEditorViewModel, from: LngLat = dragFrom, to: LngLat = dragTo) {
        vm.onDragStart(from)
        vm.onDragUpdate(to)
        vm.onDragEnd(to)
    }

    /** 拖出一栋楼并命名（名字必须在成面**之前**设好，finishDraft 会立刻提交）。 */
    private fun drawBuilding(vm: MapEditorViewModel, name: String) {
        vm.setMode(EditorMode.Building)
        vm.setDraftName(name)
        drag(vm)
        vm.finishDraft()
    }

    /** 当前生效配置在外部目录里的那份文件。 */
    private fun externalFile(): File? =
        configStore.externalDir?.let { File(it, configStore.activeFileName()) }

    /**
     * 预置一份含「A栋」的配置，模拟「上次编辑保存过」。
     *
     * 用它建出来的 ViewModel，`buildings` 是**从文件读进来的**，
     * 历史栈为空 —— 正好用来区分「数据本身」和「本次改动」。
     */
    private fun seedBuilding() {
        configStore.saveActive(
            listOf(
                EditorBuilding(
                    id = "editor-a",
                    name = "A栋",
                    polygon = listOf(
                        LngLat(113.1000, 23.1000),
                        LngLat(113.1004, 23.1000),
                        LngLat(113.1004, 23.1003),
                        LngLat(113.1000, 23.1003),
                    ),
                ),
            ),
        )
    }

    // ------------------------------------------------------------ 初始状态

    @Test
    fun `初始状态没有草稿也没有已画楼栋`() {
        val vm = viewModel()
        val state = vm.uiState.value
        assertTrue(state.draftPoints.isEmpty())
        assertTrue(state.buildings.isEmpty())
        assertFalse(state.canFinishDraft)
        assertNull(state.dragStart)
        assertEquals("默认应是教学楼模式", EditorMode.Building, state.mode)
    }

    // ------------------------------------------------------------ 拖拽矩形

    @Test
    fun `拖拽会实时生成矩形预览`() {
        val vm = viewModel()
        vm.setMode(EditorMode.Building)

        vm.onDragStart(dragFrom)
        assertEquals("按下瞬间只有一个点", 1, vm.uiState.value.draftPoints.size)

        vm.onDragUpdate(dragTo)
        val preview = vm.uiState.value.draftPoints
        assertEquals("拖拽中应预览矩形四个角", 4, preview.size)
        // 矩形四个角的经纬度范围应与两个对角点一致
        assertEquals(dragFrom.lng, preview.minOf { it.lng }, 1e-9)
        assertEquals(dragTo.lng, preview.maxOf { it.lng }, 1e-9)
        assertEquals(dragFrom.lat, preview.minOf { it.lat }, 1e-9)
        assertEquals(dragTo.lat, preview.maxOf { it.lat }, 1e-9)
    }

    @Test
    fun `拖拽结束会成面并清空草稿`() {
        val vm = viewModel()
        vm.setMode(EditorMode.Building)
        vm.setDraftName("A栋")
        drag(vm)

        val state = vm.uiState.value
        assertEquals(1, state.buildings.size)
        assertEquals("A栋", state.buildings.first().name)
        assertEquals(4, state.buildings.first().polygon.size)
        assertTrue("成面后草稿应清空", state.draftPoints.isEmpty())
        assertNull("拖拽状态应复位", state.dragStart)
    }

    @Test
    fun `拖拽范围太小不会留下针尖大的图形`() {
        val vm = viewModel()
        vm.setMode(EditorMode.Building)

        // 几乎没移动
        vm.onDragStart(dragFrom)
        vm.onDragEnd(LngLat(dragFrom.lng + 1e-9, dragFrom.lat))

        assertTrue("范围太小不应成面", vm.uiState.value.buildings.isEmpty())
        assertTrue("预览应被清掉", vm.uiState.value.draftPoints.isEmpty())
    }

    @Test
    fun `取消草稿会清掉预览`() {
        val vm = viewModel()
        vm.setMode(EditorMode.Building)
        vm.onDragStart(dragFrom)
        vm.onDragUpdate(dragTo)

        vm.cancelDraft()

        assertTrue(vm.uiState.value.draftPoints.isEmpty())
        assertNull(vm.uiState.value.dragStart)
    }

    // ------------------------------------------------------------ 圆形工具

    @Test
    fun `圆形工具按住拖出圆`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.setMode(EditorMode.CircleRoom)
        vm.setTargetBuilding(vm.uiState.value.buildings.first().id)
        vm.setDraftName("圆形大厅")

        // 圆心按下，往外拖 = 半径
        drag(vm, LngLat(113.1350, 23.1350), LngLat(113.1351, 23.1351))

        val element = vm.uiState.value.buildings.single().floor(1)!!.elements.single()
        assertEquals("圆形区域应落成教室类型", ElementType.Room, element.elementType)
        assertEquals(EditorBuilding.CIRCLE_SEGMENTS, element.points.size)
        // 圆上的点应该离圆心差不多远（正多边形，允许一点误差）
        val center = LngLat(113.1350, 23.1350)
        val radii = element.points.map { com.school.nav.core.model.Geo.distanceMeters(center, it) }
        assertEquals(radii.first(), radii.max(), 0.05)
        assertEquals(radii.first(), radii.min(), 0.05)
        assertTrue("半径应该是个正数", radii.first() > 1.0)
    }

    @Test
    fun `圆形只看半径不看对角跨度`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.setMode(EditorMode.CircleRoom)
        vm.setTargetBuilding(vm.uiState.value.buildings.first().id)

        // 这一拖如果按「矩形对角跨度」判会算太小（只动了 1e-6 度 ≈ 0.11 m），
        // 但圆的判定标准是半径，半径比阈值大就应该成面。
        val center = LngLat(113.1360, 23.1360)
        vm.onDragStart(center)
        vm.onDragEnd(LngLat(center.lng, center.lat + 3e-5))

        assertEquals(
            "半径够大就该成圆，不该被矩形那套跨度阈值卡掉",
            1,
            vm.uiState.value.buildings.single().floor(1)!!.elements.size,
        )
    }

    // ------------------------------------------------------------ 单点元素

    @Test
    fun `点一下就能放置卫生间`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val id = vm.uiState.value.buildings.first().id

        vm.setMode(EditorMode.Toilet)
        vm.setTargetBuilding(id)
        vm.setDraftName("卫生间1")
        vm.onTap(LngLat(113.1302, 23.1301))

        val building = vm.uiState.value.buildings.single()
        val element = building.floor(1)!!.elements.single()
        assertEquals(ElementType.Toilet, element.elementType)
        assertEquals("卫生间1", element.name)
        assertEquals("单点元素只存一个点", 1, element.points.size)
        assertTrue("单点元素应判为有效", element.isValid)
    }

    @Test
    fun `单点元素模式下点地图不会提示要拖拽`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.setMode(EditorMode.Stair)
        vm.setTargetBuilding(vm.uiState.value.buildings.first().id)
        vm.setDraftName("东楼梯口")

        vm.onTap(LngLat(113.1302, 23.1301))

        assertEquals(
            "点一下就应落点",
            1,
            vm.uiState.value.buildings.single().floor(1)!!.elements.size,
        )
    }

    @Test
    fun `矩形模式下点地图不会落点`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.setMode(EditorMode.Room)
        vm.setTargetBuilding(vm.uiState.value.buildings.first().id)

        vm.onTap(LngLat(113.1302, 23.1301))

        assertTrue(
            "矩形模式应提示拖动而不是落一个点",
            vm.uiState.value.buildings.single().elementCount == 0,
        )
    }

    @Test
    fun `楼梯可选起止楼层并在每一层都生成`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val id = vm.uiState.value.buildings.first().id

        vm.setMode(EditorMode.Stair)
        vm.setTargetBuilding(id)
        vm.setFloorLevel(2)
        vm.setEndFloorLevel(4)
        vm.onTap(LngLat(113.1302, 23.1301))

        val draft = vm.uiState.value.buildings.single()
        val element = draft.floor(2)!!.elements.single()
        assertEquals(4, element.toLevel)
        assertTrue("2→4 应判定为跨层", element.isCrossFloor)

        // 合并成导航数据时，2、3、4 楼都要有这份楼梯
        val merged = draft.toBuilding()
        for (level in 2..4) {
            val floor = merged.floorByLevel(level)
            assertNotNull("$level 楼应生成楼梯", floor)
            assertTrue(
                "$level 楼应能找到同名楼梯",
                floor!!.elements.any { it.name == element.name },
            )
        }
        assertNull("1 楼不应有这个楼梯", merged.floorByLevel(1))
    }

    @Test
    fun `结束楼层不会低于起始楼层`() {
        val vm = viewModel()
        vm.setFloorLevel(3)
        vm.setEndFloorLevel(1)
        assertEquals("结束层应被抬到起始层", 3, vm.uiState.value.endFloorLevel)
    }

    // ------------------------------------------------------------ 楼栋与楼层

    @Test
    fun `非单点元素必须先选目标楼栋`() {
        val vm = viewModel()
        vm.setMode(EditorMode.Room)
        assertFalse("没选楼时不应该能画", vm.uiState.value.canDraw)

        drag(vm)

        assertTrue("没选楼栋时不应落下元素", vm.uiState.value.buildings.isEmpty())
    }

    @Test
    fun `不同模式的元素类型正确落库`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val id = vm.uiState.value.buildings.first().id
        vm.setTargetBuilding(id)

        // 矩形类
        for ((mode, expected) in listOf(
            EditorMode.Room to ElementType.Room,
            EditorMode.Office to ElementType.Office,
        )) {
            vm.setMode(mode)
            drag(vm, LngLat(113.1300 + mode.ordinal * 1e-4, 23.1300), dragTo)
            vm.setDraftName(mode.label + "1")
            vm.finishDraft()
            val elements = vm.uiState.value.buildings.single().floor(1)!!.elements
            assertEquals("${mode.label} 类型应落成 $expected", expected, elements.last().elementType)
        }

        // 单点类
        vm.setMode(EditorMode.Toilet)
        vm.setDraftName("卫生间1")
        vm.onTap(LngLat(113.1309, 23.1302))
        val toilet = vm.uiState.value.buildings.single().floor(1)!!.elements
            .first { it.elementType == ElementType.Toilet }
        assertEquals("卫生间1", toilet.name)
        assertEquals("单点元素只存一个点", 1, toilet.points.size)
    }

    @Test
    fun `切换模式会清空草稿避免串模式`() {
        val vm = viewModel()
        vm.setMode(EditorMode.Building)
        vm.onDragStart(dragFrom)

        vm.setMode(EditorMode.Stair)

        assertTrue("切模式不该把上一模式的预览带过来", vm.uiState.value.draftPoints.isEmpty())
    }

    @Test
    fun `删除单个元素`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val id = vm.uiState.value.buildings.first().id
        vm.setMode(EditorMode.Room)
        vm.setTargetBuilding(id)
        drag(vm)
        vm.setDraftName("101")
        vm.finishDraft()

        val elementId = vm.uiState.value.buildings.single().floor(1)!!.elements.first().id
        vm.removeElement(id, 1, elementId)

        assertTrue(
            "元素应被删除",
            vm.uiState.value.buildings.single().floor(1)!!.elements.isEmpty(),
        )
    }

    @Test
    fun `楼层数可以修改且会夹在合理区间`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val id = vm.uiState.value.buildings.first().id
        assertEquals(EditorBuilding.DEFAULT_FLOOR_COUNT, vm.uiState.value.buildings.first().floorCount)

        vm.setBuildingFloorCount(id, 6)
        assertEquals(6, vm.uiState.value.buildings.first().floorCount)

        vm.setBuildingFloorCount(id, 0)
        assertEquals("不允许 0 层", 1, vm.uiState.value.buildings.first().floorCount)

        vm.setBuildingFloorCount(id, 99_999)
        assertEquals(
            "上限应被夹住",
            MAX_FLOOR_COUNT,
            vm.uiState.value.buildings.first().floorCount,
        )
    }

    @Test
    fun `同名楼栋再次成面视为重画轮廓并保留楼层`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")

        // 给 A 栋补一个教室（用另一个位置，避免与楼栋轮廓重叠）
        vm.setMode(EditorMode.Room)
        vm.setTargetBuilding(vm.uiState.value.buildings.first().id)
        vm.setDraftName("101")
        drag(vm, LngLat(113.1400, 23.1400), LngLat(113.1404, 23.1403))
        vm.finishDraft()
        assertEquals(1, vm.uiState.value.buildings.first().elementCount)

        // 重画外轮廓（换个位置，仍然同名）
        vm.setMode(EditorMode.Building)
        vm.setDraftName("A栋")
        drag(vm, LngLat(113.1500, 23.1500), LngLat(113.1504, 23.1503))
        vm.finishDraft()

        val building = vm.uiState.value.buildings.single()
        assertEquals("应仍是一栋楼，不是新增一栋", 1, vm.uiState.value.buildings.size)
        assertEquals(4, building.polygon.size)
        assertEquals("重画轮廓不能把楼层元素弄丢", 1, building.elementCount)
    }

    @Test
    fun `配置里已有的楼栋不会与新画的楼栋撞 id`() {
        // 先在配置里放一栋楼，模拟「上次编辑保存过」
        seedBuilding()

        val vm = viewModel()
        assertEquals("应把配置里的楼栋读进来", 1, vm.uiState.value.buildings.size)

        drawBuilding(vm, "A栋")

        // 同名 = 重画轮廓，仍然是一栋楼
        assertEquals(1, vm.uiState.value.buildings.size)
        assertTrue("id 不该变", vm.uiState.value.buildings.first().id.isNotBlank())

        // 换一个名字 = 新增一栋，两栋 id 必须不同
        drawBuilding(vm, "B栋")
        val buildings = vm.uiState.value.buildings
        assertEquals(2, buildings.size)
        assertEquals("两栋楼的 id 不该重复", 2, buildings.map { it.id }.toSet().size)
    }

    // ------------------------------------------------------------ 撤销 / 重做

    @Test
    fun `撤销能把配置里读进来的楼栋也撤回来`() {
        // 这份楼栋「已经写进配置」，正是用户抱怨撤不掉的那种
        seedBuilding()

        val vm = viewModel()
        val id = vm.uiState.value.buildings.single().id
        assertFalse("没有改动时不该能撤销", vm.uiState.value.canUndo)

        vm.removeBuilding(id)

        assertTrue("删掉之后历史里应该有东西", vm.uiState.value.canUndo)
        assertTrue("删除应立即生效", vm.uiState.value.buildings.isEmpty())

        vm.undo()

        assertEquals("撤销应把已保存的楼栋找回来", 1, vm.uiState.value.buildings.size)
        assertEquals("A栋", vm.uiState.value.buildings.single().name)
    }

    @Test
    fun `撤销与重做成对工作`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        assertTrue(vm.uiState.value.canUndo)

        vm.undo()
        assertTrue("撤销后应没有楼栋", vm.uiState.value.buildings.isEmpty())
        assertTrue("撤销后应能重做", vm.uiState.value.canRedo)

        vm.redo()
        assertEquals("重做应把楼栋放回来", 1, vm.uiState.value.buildings.size)
        assertFalse("重做完重做栈应清空", vm.uiState.value.canRedo)
    }

    @Test
    fun `新的改动会让重做失效`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.undo()
        assertTrue(vm.uiState.value.canRedo)

        drawBuilding(vm, "B栋")

        assertFalse("走了新分支之后旧的重做记录必须丢掉", vm.uiState.value.canRedo)
    }

    @Test
    fun `一次拖拽编辑只记一步历史`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val before = vm.uiState.value.buildings.single().polygon

        // 选中轮廓后整体拖走（模拟手指一路移动很多帧）
        vm.setEditMode(true)
        vm.onTap(before.first())
        vm.onShapeDragStart(before.first())
        for (i in 1..20) {
            vm.onShapeDragUpdate(LngLat(before.first().lng + i * 1e-5, before.first().lat))
        }
        vm.onShapeDragEnd()

        val moved = vm.uiState.value.buildings.single().polygon
        assertNotEquals("图形应该被拖走了", before.first().lng, moved.first().lng, 1e-9)

        vm.undo()
        assertEquals(
            "一次手势只该占一步历史，撤销一次就应完全回位",
            before.first().lng,
            vm.uiState.value.buildings.single().polygon.first().lng,
            1e-9,
        )
    }

    // ------------------------------------------------------------ 自由多边形（钢笔）

    @Test
    fun `钢笔连点三次就能成面`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.setMode(EditorMode.PolygonRoom)
        vm.setTargetBuilding(vm.uiState.value.buildings.first().id)
        vm.setDraftName("异形教室")

        vm.onTap(LngLat(113.1410, 23.1410))
        vm.onTap(LngLat(113.1414, 23.1410))
        assertFalse("两个点还围不成面", vm.uiState.value.canFinishDraft)

        vm.onTap(LngLat(113.1412, 23.1413))
        assertTrue("三个点就该能成面", vm.uiState.value.canFinishDraft)

        vm.finishDraft()

        val element = vm.uiState.value.buildings.single().floor(1)!!.elements.single()
        assertEquals("异形教室", element.name)
        assertEquals("顶点应原样保留，不做矩形化", 3, element.points.size)
        assertEquals(ElementType.Room, element.elementType)
    }

    @Test
    fun `钢笔画的外轮廓不是矩形`() {
        val vm = viewModel()
        vm.setMode(EditorMode.BuildingPolygon)
        vm.setDraftName("三角形楼")
        vm.onTap(LngLat(113.1320, 23.1320))
        vm.onTap(LngLat(113.1324, 23.1320))
        vm.onTap(LngLat(113.1322, 23.1323))
        vm.finishDraft()

        val polygon = vm.uiState.value.buildings.single().polygon
        assertEquals("钢笔不该被矩形化", 3, polygon.size)
    }

    @Test
    fun `钢笔模式下拖拽不会画出图形`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.setMode(EditorMode.PolygonRoom)
        vm.setTargetBuilding(vm.uiState.value.buildings.first().id)

        drag(vm)

        assertTrue("拖拽不该落下草稿", vm.uiState.value.draftPoints.isEmpty())
        assertEquals(
            "也不该凭空生成一个元素",
            0,
            vm.uiState.value.buildings.single().floor(1)?.elements?.size ?: 0,
        )
    }

    @Test
    fun `同一个位置连点两下不会被记成两个顶点`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.setMode(EditorMode.PolygonRoom)
        vm.setTargetBuilding(vm.uiState.value.buildings.first().id)

        val p = LngLat(113.1420, 23.1420)
        vm.onTap(p)
        vm.onTap(p)

        assertEquals("手指抖动不该产生零长度边", 1, vm.uiState.value.draftPoints.size)
    }

    @Test
    fun `撤销会先退掉钢笔刚落的那个点`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.setMode(EditorMode.PolygonRoom)
        vm.setTargetBuilding(vm.uiState.value.buildings.first().id)

        vm.onTap(LngLat(113.1420, 23.1420))
        vm.onTap(LngLat(113.1424, 23.1420))
        vm.onTap(LngLat(113.1422, 23.1423))
        assertTrue("有草稿时撤销按钮要是亮的", vm.uiState.value.canUndo)

        vm.undo()
        assertEquals("应退掉最后一个点", 2, vm.uiState.value.draftPoints.size)

        vm.undo()
        vm.undo()
        assertTrue("退光了就清空草稿", vm.uiState.value.draftPoints.isEmpty())

        // 草稿退干净之后，再按撤销才轮到「画楼栋」那一步 ——
        // 顺序错了的话用户会一下子丢掉整栋楼
        vm.undo()
        assertTrue("草稿退光后，撤销才轮到上一步的楼栋", vm.uiState.value.buildings.isEmpty())
    }

    @Test
    fun `切走钢笔模式会清掉草稿并复位撤销可用性`() {
        // 预置一份配置：这样历史栈本来就是空的，canUndo 只可能由草稿撑着
        seedBuilding()
        val vm = viewModel()
        assertFalse("刚从文件读进来时没有可撤销的东西", vm.uiState.value.canUndo)

        vm.setMode(EditorMode.PolygonRoom)
        vm.setTargetBuilding(vm.uiState.value.buildings.first().id)
        vm.onTap(LngLat(113.1420, 23.1420))
        assertTrue("只有草稿撑着，撤销按钮也该亮", vm.uiState.value.canUndo)

        vm.setMode(EditorMode.Room)

        assertTrue(vm.uiState.value.draftPoints.isEmpty())
        assertFalse("草稿没了，撤销按钮要跟着灰掉", vm.uiState.value.canUndo)
    }

    // ------------------------------------------------------------ 编辑形状

    @Test
    fun `编辑模式能选中图形并拖单个顶点`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val polygon = vm.uiState.value.buildings.single().polygon

        vm.setEditMode(true)
        vm.onTap(polygon.first())
        assertNotNull("点图形内部应选中它", vm.uiState.value.selection)
        assertEquals("选中的应是楼栋外轮廓", true, vm.uiState.value.selectedShape!!.isBuildingOutline)

        val moved = LngLat(polygon.first().lng + 1e-4, polygon.first().lat + 1e-4)
        vm.onShapeDragStart(polygon.first())
        vm.onShapeDragUpdate(moved)
        vm.onShapeDragEnd()

        assertEquals(
            "只有被拖的那个顶点该动",
            moved.lng,
            vm.uiState.value.buildings.single().polygon.first().lng,
            1e-9,
        )
        assertEquals(
            "其他顶点不该跟着动",
            polygon[1].lng,
            vm.uiState.value.buildings.single().polygon[1].lng,
            1e-9,
        )
    }

    @Test
    fun `在边上插点会增加顶点数`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val polygon = vm.uiState.value.buildings.single().polygon

        // 选中轮廓，然后点在第一条边的中点上
        vm.setEditMode(true)
        vm.onTap(polygon.first())
        val mid = LngLat(
            (polygon[0].lng + polygon[1].lng) / 2,
            (polygon[0].lat + polygon[1].lat) / 2,
        )
        vm.insertVertexAt(mid)

        assertEquals(
            "四边形插一个点应变成五边形",
            5,
            vm.uiState.value.buildings.single().polygon.size,
        )
    }

    @Test
    fun `在顶点上删点会减少顶点数`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val polygon = vm.uiState.value.buildings.single().polygon

        vm.setEditMode(true)
        vm.onTap(polygon.first())
        vm.deleteVertexAt(polygon[1])

        assertEquals(
            "四边形删一个点应变成三角形",
            3,
            vm.uiState.value.buildings.single().polygon.size,
        )
    }

    @Test
    fun `三角形上再删点会被拦住`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val polygon = vm.uiState.value.buildings.single().polygon

        vm.setEditMode(true)
        vm.onTap(polygon.first())
        vm.deleteVertexAt(polygon[1])
        assertEquals(3, vm.uiState.value.buildings.single().polygon.size)

        vm.deleteVertexAt(vm.uiState.value.buildings.single().polygon[0])

        assertEquals(
            "至少要留三个顶点，否则围不成面",
            3,
            vm.uiState.value.buildings.single().polygon.size,
        )
    }

    @Test
    fun `旋转会转角度但不改变图形的外接矩形尺寸`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val polygon = vm.uiState.value.buildings.single().polygon
        val widthBefore = polygon.maxOf { it.lng } - polygon.minOf { it.lng }
        val heightBefore = polygon.maxOf { it.lat } - polygon.minOf { it.lat }

        vm.setEditMode(true)
        vm.onTap(polygon.first())
        vm.rotateSelection(90.0)

        val rotated = vm.uiState.value.buildings.single().polygon
        val widthAfter = rotated.maxOf { it.lng } - rotated.minOf { it.lng }
        val heightAfter = rotated.maxOf { it.lat } - rotated.minOf { it.lat }

        // 转 90 度之后长宽应该互换（经纬度尺度不同，换算成米再比）
        val kx = com.school.nav.core.model.Geo.metersPerDegLng(23.13)
        val ky = com.school.nav.core.model.Geo.METERS_PER_DEG_LAT
        val widthBeforeM = widthBefore * kx
        val heightBeforeM = heightBefore * ky
        val widthAfterM = widthAfter * kx
        val heightAfterM = heightAfter * ky

        assertEquals("转 90 度后宽度应约等于原来的高度", heightBeforeM, widthAfterM, 1.0)
        assertEquals("转 90 度后高度应约等于原来的宽度", widthBeforeM, heightAfterM, 1.0)
    }

    @Test
    fun `单点元素不会响应旋转和插点`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val id = vm.uiState.value.buildings.first().id
        vm.setMode(EditorMode.Toilet)
        vm.setTargetBuilding(id)
        vm.setDraftName("卫生间1")
        vm.onTap(LngLat(113.1302, 23.1301))

        vm.setEditMode(true)
        vm.onTap(LngLat(113.1302, 23.1301))
        assertEquals("应选中那个点元素", "toilet-卫生间1", vm.uiState.value.selection?.elementId)

        vm.rotateSelection(90.0)
        vm.insertVertexAt(LngLat(113.1302, 23.1301))

        val element = vm.uiState.value.buildings.single().floor(1)!!.elements.single()
        assertEquals("单点元素既没有方向也没有边，点串不该被改动", 1, element.points.size)
    }

    @Test
    fun `编辑模式下删除选中图形`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        val polygon = vm.uiState.value.buildings.single().polygon

        vm.setEditMode(true)
        vm.onTap(polygon.first())
        vm.removeSelection()

        assertTrue("选中的图形应被删掉", vm.uiState.value.buildings.isEmpty())
        assertNull("删完应清掉选中态", vm.uiState.value.selection)

        vm.undo()
        assertEquals("删图形也要能撤销", 1, vm.uiState.value.buildings.size)
    }

    @Test
    fun `退出编辑模式会清掉选中与锚点`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.setEditMode(true)
        vm.onTap(vm.uiState.value.buildings.single().polygon.first())
        assertNotNull(vm.uiState.value.selection)
        assertNotNull(vm.uiState.value.editAnchor)

        vm.setEditMode(false)

        assertNull(vm.uiState.value.selection)
        assertNull("锚点也必须清掉，否则工具按钮会作用到看不见的位置", vm.uiState.value.editAnchor)
    }

    // ------------------------------------------------------------ 多配置

    @Test
    fun `配置列表初始就包含当前生效的那一份`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.save()

        assertEquals("保存应落出一份配置文件", 1, vm.uiState.value.configs.size)
        assertEquals(configStore.activeFileName(), vm.uiState.value.activeConfigName)
    }

    @Test
    fun `新建配置会立刻切过去而且是空的`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.save()
        assertEquals(1, vm.uiState.value.buildings.size)

        vm.createConfig("实验中学")

        assertEquals("新建后应切到新文件", "实验中学.json", vm.uiState.value.activeConfigName)
        assertTrue("新配置里不该有上一所学校的楼栋", vm.uiState.value.buildings.isEmpty())
        assertEquals(2, vm.uiState.value.configs.size)
        assertTrue("应通知外层重新装配仓库", dataChanged > 0)
    }

    @Test
    fun `切换配置会整批换掉编辑器里的数据`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.save()
        val firstFile = vm.uiState.value.activeConfigName

        vm.createConfig("实验中学")
        drawBuilding(vm, "实验楼")
        vm.save()

        vm.switchConfig(firstFile)

        assertEquals("应切回第一份", firstFile, vm.uiState.value.activeConfigName)
        assertEquals(1, vm.uiState.value.buildings.size)
        assertEquals("A栋", vm.uiState.value.buildings.single().name)

        vm.switchConfig("实验中学.json")
        assertEquals("实验楼", vm.uiState.value.buildings.single().name)
    }

    @Test
    fun `切换配置会清掉撤销栈避免跨学校撤销`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.save()
        val firstFile = vm.uiState.value.activeConfigName
        vm.createConfig("实验中学")
        assertTrue("刚切完不该能撤销", !vm.uiState.value.canUndo)

        vm.switchConfig(firstFile)

        assertFalse("切换配置后撤销栈必须清空", vm.uiState.value.canUndo)
        assertFalse(vm.uiState.value.canRedo)
    }

    @Test
    fun `复制配置会带着楼栋一起复制`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.save()
        val source = vm.uiState.value.activeConfigName

        vm.duplicateConfig(source)

        assertEquals("复制完应切到副本", "editor_buildings 副本.json", vm.uiState.value.activeConfigName)
        assertEquals("副本里应该有原来的楼栋", 1, vm.uiState.value.buildings.size)
        assertEquals("A栋", vm.uiState.value.buildings.single().name)
        assertEquals("原文件还在", 2, vm.uiState.value.configs.size)
    }

    @Test
    fun `重命名配置会改文件名并保持内容`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.save()

        vm.renameConfig(vm.uiState.value.activeConfigName, "第一中学")

        assertEquals("第一中学.json", vm.uiState.value.activeConfigName)
        assertEquals("改名不该丢内容", 1, vm.uiState.value.buildings.size)
        assertTrue(File(configStore.internalDir, "第一中学.json").isFile)
    }

    @Test
    fun `删除当前配置会自动切到剩下的一份`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.save()
        val firstFile = vm.uiState.value.activeConfigName

        vm.createConfig("实验中学")
        drawBuilding(vm, "实验楼")
        vm.save()

        vm.deleteConfig("实验中学.json")

        assertEquals("删掉当前那份应自动切到剩下的", firstFile, vm.uiState.value.activeConfigName)
        assertEquals(1, vm.uiState.value.configs.size)
        assertEquals("A栋", vm.uiState.value.buildings.single().name)
    }

    @Test
    fun `把最后一份删掉之后编辑器清空但仓库会退回内置数据`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.save()

        vm.deleteConfig(vm.uiState.value.activeConfigName)

        assertTrue(vm.uiState.value.configs.isEmpty())
        assertTrue("没有配置文件时应是空的编辑器", vm.uiState.value.buildings.isEmpty())
        assertTrue("应通知外层重新装配（此时退回 assets 内置数据）", dataChanged > 0)
    }

    @Test
    fun `重名的新建会自动加序号不覆盖已有配置`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")
        vm.save()
        val existing = vm.uiState.value.activeConfigName

        // 故意用已有配置的显示名再建一份
        vm.createConfig(existing.removeSuffix(".json"))

        assertEquals(
            "重名应自动避让而不是覆盖别人的数据",
            existing.removeSuffix(".json") + "-2.json",
            vm.uiState.value.activeConfigName,
        )
        assertEquals(2, vm.uiState.value.configs.size)
    }
    @Test
    fun `损坏的配置文件在列表里被标出来而不是让编辑器崩掉`() {
        val file = File(configStore.internalDir, "坏的.json")
        file.parentFile?.mkdirs()
        file.writeText("{ 这不是 JSON")

        val vm = viewModel()

        val broken = vm.uiState.value.configs.first { it.fileName == "坏的.json" }
        assertTrue("读不出来的文件应标记为损坏", broken.isBroken)
    }

    // ------------------------------------------------------------ 存盘

    @Test
    fun `没有画好的楼栋时保存不会写文件`() {
        val vm = viewModel()
        vm.save()

        assertNull(vm.uiState.value.lastSave)
        assertFalse(configStore.activeFile().exists())
    }

    @Test
    fun `保存会写入内部与外部两份并返回路径`() {
        val vm = viewModel()
        drawBuilding(vm, "A栋")

        vm.save()

        val save = vm.uiState.value.lastSave
        assertNotNull(save)
        assertTrue(save!!.isSuccess)
        assertNotNull(save.internalPath)
        assertNotNull("外部目录可用时应同时写一份", save.externalPath)
        assertTrue(configStore.activeFile().isFile)
        assertTrue(externalFile()!!.isFile)
    }

    @Test
    fun `保存后重新加载能读回楼栋与单点元素`() {
        val vm = viewModel()
        drawBuilding(vm, "B栋")
        val id = vm.uiState.value.buildings.first().id
        vm.setMode(EditorMode.Toilet)
        vm.setTargetBuilding(id)
        vm.setFloorLevel(2)
        vm.setDraftName("卫生间")
        vm.onTap(LngLat(113.1302, 23.1301))
        vm.save()

        // 新建一个 VM 模拟重启 App
        val reopened = viewModel().uiState.value.buildings

        assertEquals(1, reopened.size)
        assertEquals("B栋", reopened.first().name)
        assertEquals(4, reopened.first().polygon.size)
        val element = reopened.first().floor(2)!!.elements.single()
        assertEquals(ElementType.Toilet, element.elementType)
        assertEquals(1, element.points.size)
    }

    @Test
    fun `保存会丢掉什么都没画的空壳楼栋`() {
        val vm = viewModel()
        // 只点了一下，没拖出面积 —— 不该留下一个空楼栋
        vm.setMode(EditorMode.Building)
        vm.onDragStart(dragFrom)
        vm.onDragEnd(dragFrom)

        vm.save()

        assertNull("没有有效内容时不该写文件", vm.uiState.value.lastSave)
    }

    // ------------------------------------------------------------ Key

    @Test
    fun `保存与清除高德 Key 会同步到状态`() {
        val vm = viewModel()
        assertFalse(vm.uiState.value.hasApiKey)

        vm.saveApiKey("  abcdef123456  ")
        assertTrue(vm.uiState.value.hasApiKey)
        assertEquals("应去掉首尾空白", "abcdef123456", vm.uiState.value.apiKey)

        vm.clearApiKey()
        assertFalse(vm.uiState.value.hasApiKey)
        assertTrue(vm.uiState.value.apiKey.isEmpty())
    }

    @Test
    fun `空 Key 不会被保存`() {
        val vm = viewModel()
        vm.saveApiKey("   ")
        assertFalse(vm.uiState.value.hasApiKey)
        assertTrue(keyStore.amapKey().isEmpty())
    }

    @Test
    fun `已有 Key 时初始状态即标记为已配置`() {
        keyStore.saveAmapKey("existing-key")
        keyStore.saveAmapWebKey("existing-web-key")
        val vm = viewModel()
        assertTrue(vm.uiState.value.hasApiKey)
        assertEquals("existing-key", vm.uiState.value.apiKey)
        assertEquals("existing-web-key", vm.uiState.value.webKey)
    }

    @Test
    fun `Web服务 Key 可保存与清除且与地图 Key 互不影响`() {
        val vm = viewModel()
        vm.saveApiKey("map-key")
        vm.saveWebKey("  web-key  ")

        assertEquals("map-key", vm.uiState.value.apiKey)
        assertEquals("应去掉首尾空白", "web-key", vm.uiState.value.webKey)

        vm.clearWebKey()
        assertTrue("清掉 Web Key 不该影响地图 Key", vm.uiState.value.apiKey == "map-key")
        assertTrue(vm.uiState.value.webKey.isEmpty())
    }

    @Test
    fun `空 Web服务 Key 不会被保存`() {
        val vm = viewModel()
        vm.saveWebKey("   ")
        assertTrue(vm.uiState.value.webKey.isEmpty())
        assertTrue(keyStore.amapWebKey().isEmpty())
    }
}
