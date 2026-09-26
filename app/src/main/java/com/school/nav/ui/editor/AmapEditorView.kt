package com.school.nav.ui.editor

import android.graphics.Color
import android.graphics.Point
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polygon
import com.amap.api.maps.model.PolygonOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.amap.api.maps.Projection
import com.school.nav.core.data.EditorBuilding
import com.school.nav.core.model.ElementType
import com.school.nav.core.model.Geo
import com.school.nav.core.model.LngLat
import com.school.nav.state.CenterRequest
import com.school.nav.state.SelectedShape

/** 经纬度 -> 高德坐标。注意高德 LatLng 的参数顺序是 (纬度, 经度)。 */
private fun LngLat.toLatLng(): LatLng = LatLng(lat, lng)

/**
 * 各类元素的配色，和首页导航的语义保持一致：
 * 楼梯口是跨层转向点，用暖色突出；卫生间与商铺一样用中性灰，避免抢视线。
 */
private fun strokeColorOf(type: ElementType): String = when (type) {
    ElementType.Stair -> "#FF8A00"
    ElementType.Toilet -> "#8A90A0"
    else -> "#00C2D1"
}

/**
 * 高德地图绘制视图（卫星底图、全屏、无自带控件）。
 *
 * ## 为什么关掉所有自带控件
 *
 * 高德的加减号、比例尺、指南针、定位按钮是它自己的视觉语言，
 * 和本应用的设计（胶囊导航 + 白色圆角卡片 + 蓝绿强调色）摆在一起很割裂。
 * 缩放用双指、罗盘信息对画轮廓没用，所以全部关掉，只保留法律要求的 Logo。
 *
 * ## 生命周期
 *
 * `MapView` 自己管 GL 上下文，必须转发 Activity 生命周期
 * （onCreate/onResume/onPause/onDestroy），否则会黑屏、泄漏、切后台回来不刷新。
 *
 * ## 重绘策略
 *
 * 每次状态变化就清空 overlay 后整体重画。元素只有几十个，重画开销可忽略，
 * 换来的是不需要维护「哪个 overlay 对应哪个顶点」的增量状态 —— 那种写法很容易
 * 留下幽灵多边形。只清 overlay、不动相机，所以用户拖地图时不会被拉回原位；
 * 需要移动相机时走 [centerRequest]（带 token，消费一次就失效）。
 */
@Composable
fun AmapEditorView(
    draftPoints: List<LngLat>,
    buildings: List<EditorBuilding>,
    centerRequest: CenterRequest?,
    onMapClick: (LngLat) -> Unit,
    onDragStart: (LngLat) -> Unit,
    onDragUpdate: (LngLat) -> Unit,
    onDragEnd: (LngLat) -> Unit,
    onCenterConsumed: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** 编辑模式下选中的图形：会把它的顶点画成可拖的手柄。 */
    selection: SelectedShape? = null,
    /**
     * 是否处于拖拽绘制模式（教室 / 办公室 / 教学楼）。
     *
     * 拖拽期间必须**关掉地图平移**，否则手指一拖地图就跟着走，根本框不准。
     * 单点模式（楼梯口 / 卫生间）不需要，保持地图可平移。
     */
    draggingEnabled: Boolean = false,
    /**
     * 这一页当前是否在前台可见。
     *
     * 地图页在 AppShell 里是**常驻组合树**的 —— 一旦离开组合树，MapView 对象就会被
     * 销毁重建，相机位置与 overlay 全丢，表现就是「切回来地图刷新了」。
     * 所以切页签时不移除它，只靠这个标志把 MapView 设为 INVISIBLE 并停掉手势：
     * 既不后台白白渲染 GL，也不会拦住别的页面的点击。
     */
    active: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val overlayState = remember { OverlayState() }

    val mapView = remember {
        MapView(context).apply { onCreate(null) }
    }

    DisposableEffect(lifecycleOwner, active) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (active) mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 离开这一页时暂停：避免后台持续渲染 GL
            if (!active) mapView.onPause()
        }
    }

    // 真正离开组合树时才释放
    DisposableEffect(Unit) {
        onDispose { mapView.onDestroy() }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            // 非激活：INVISIBLE 而不是 GONE —— 不绘制、不接收触摸，但对象存活
            view.visibility = if (active) View.VISIBLE else View.INVISIBLE

            val aMap = view.map ?: return@AndroidView

            if (!overlayState.configured) {
                overlayState.configured = true
                configureMap(aMap)
            }

            if (active) {
                installTouchHandling(
                    aMap = aMap,
                    draggingEnabled = draggingEnabled,
                    onMapClick = onMapClick,
                    onDragStart = onDragStart,
                    onDragUpdate = onDragUpdate,
                    onDragEnd = onDragEnd,
                )
                // 居中请求：带 token，消费一次就通知上层清空，避免每次重组都重置相机
                centerRequest?.let { request ->
                    aMap.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(request.point.toLatLng(), request.zoom),
                    )
                    onCenterConsumed(request.token)
                }
            } else {
                aMap.setOnMapClickListener(null)
                aMap.setOnMapTouchListener(null)
                aMap.uiSettings.isScrollGesturesEnabled = false
            }

            redraw(aMap, overlayState, draftPoints, buildings, selection)
        },
    )
}

/**
 * 装触摸处理：点一下 = 放单点元素；按住拖 = 框矩形。
 *
 * 为什么用 `setOnMapTouchListener` 而不是 `setOnMapClickListener`：
 * 后者只有「点击」这一个语义，拿不到按下 / 移动 / 抬起，没法做拖拽框选。
 * 这里用 DOWN / MOVE / UP 自己区分「点」和「拖」，并且把屏幕坐标经
 * `Projection` 换算成经纬度。
 *
 * 拖拽期间关掉滚动手势，拖完再恢复 —— 否则地图会跟着手指平移，框不准。
 */
private fun installTouchHandling(
    aMap: AMap,
    draggingEnabled: Boolean,
    onMapClick: (LngLat) -> Unit,
    onDragStart: (LngLat) -> Unit,
    onDragUpdate: (LngLat) -> Unit,
    onDragEnd: (LngLat) -> Unit,
) {
    if (!draggingEnabled) {
        aMap.setOnMapTouchListener(null)
        aMap.uiSettings.isScrollGesturesEnabled = true
        aMap.setOnMapClickListener { latLng ->
            onMapClick(LngLat(lng = latLng.longitude, lat = latLng.latitude))
        }
        return
    }

    aMap.setOnMapClickListener(null)
    aMap.setOnMapTouchListener { event ->
        val projection = aMap.projection ?: return@setOnMapTouchListener
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 按下就先别让地图动，等看清是点还是拖
                aMap.uiSettings.isScrollGesturesEnabled = false
                toLngLat(projection, event.x, event.y)?.let(onDragStart)
            }

            MotionEvent.ACTION_MOVE ->
                toLngLat(projection, event.x, event.y)?.let(onDragUpdate)

            MotionEvent.ACTION_UP -> {
                aMap.uiSettings.isScrollGesturesEnabled = true
                toLngLat(projection, event.x, event.y)?.let(onDragEnd)
            }

            MotionEvent.ACTION_CANCEL -> {
                aMap.uiSettings.isScrollGesturesEnabled = true
            }
        }
        false
    }
}

/** 屏幕坐标 -> 经纬度。转换失败返回 null。 */
private fun toLngLat(projection: Projection, x: Float, y: Float): LngLat? =
    runCatching {
        val latLng = projection.fromScreenLocation(Point(x.toInt(), y.toInt()))
        LngLat(lng = latLng.longitude, lat = latLng.latitude)
    }.getOrNull()

/** overlay 句柄 + 一次性初始化标记。 */
private class OverlayState {
    var configured: Boolean = false
    val overlays = mutableListOf<Any>()
}

/**
 * 地图基础设置：卫星图 + 关掉全部自带控件。
 *
 * 为什么用卫星图：写实底图能看到真实楼顶轮廓，比矢量图更容易对着描边界 ——
 * 这正是「画楼栋外轮廓」这个任务需要的。
 */
private fun configureMap(aMap: AMap) {
    aMap.mapType = AMap.MAP_TYPE_SATELLITE
    aMap.uiSettings.apply {
        isZoomControlsEnabled = false
        isCompassEnabled = false
        isScaleControlsEnabled = false
        isMyLocationButtonEnabled = false
        isIndoorSwitchEnabled = false
        // 画轮廓时误触旋转/倾斜会让人非常难受，关掉
        isRotateGesturesEnabled = false
        isTiltGesturesEnabled = false
        // 平移与双指缩放保留
        isScrollGesturesEnabled = true
        isZoomGesturesEnabled = true
    }
}

/** 清空后按当前状态整体重画。 */
private fun redraw(
    aMap: AMap,
    state: OverlayState,
    draftPoints: List<LngLat>,
    buildings: List<EditorBuilding>,
    selection: SelectedShape?,
) {
    state.overlays.forEach { overlay ->
        when (overlay) {
            is Polygon -> overlay.remove()
            is Marker -> overlay.remove()
            is Polyline -> overlay.remove()
            is Circle -> overlay.remove()
        }
    }
    state.overlays.clear()

    buildings.forEach { building ->
        // 1) 楼栋外轮廓：绿色填充
        if (building.hasValidPolygon) {
            state.overlays += aMap.addPolygon(
                PolygonOptions()
                    .addAll(building.polygon.map { it.toLatLng() })
                    .strokeWidth(6f)
                    .strokeColor(Color.parseColor("#16B364"))
                    .fillColor(Color.parseColor("#3316B364")),
            )
            state.overlays += aMap.addMarker(
                MarkerOptions()
                    .position(Geo.centroid(building.polygon).toLatLng())
                    .title(building.name),
            )
        }

        // 2) 楼层元素：按类型配色
        building.floors.forEach { floor ->
            floor.elements.forEach { element ->
                val color = Color.parseColor(strokeColorOf(element.elementType))
                val title = "${floor.level}F ${element.name}"

                if (element.points.size == 1) {
                    // 单点元素（楼梯口 / 卫生间）：**必须用圆点画**。
                    //
                    // 之前按多边形处理，一个点的多边形在 GL 里几乎什么都画不出来 ——
                    // 用户添加完完全看不到东西，不知道该放哪了，这正是要修的问题。
                    // 这里画一个带白边的实心圆，在卫星图上也够显眼。
                    val center = element.points.first().toLatLng()
                    state.overlays += aMap.addCircle(
                        CircleOptions()
                            .center(center)
                            .radius(POINT_ELEMENT_RADIUS_M)
                            .fillColor(color)
                            .strokeColor(Color.WHITE)
                            .strokeWidth(3f),
                    )
                    state.overlays += aMap.addMarker(
                        MarkerOptions().position(center).title(title),
                    )
                } else {
                    val points = element.points.map { it.toLatLng() }
                    if (points.size < 3) return@forEach
                    state.overlays += aMap.addPolygon(
                        PolygonOptions()
                            .addAll(points)
                            .strokeWidth(5f)
                            .strokeColor(color)
                            .fillColor((color and 0x00FFFFFF) or 0x33000000),
                    )
                    state.overlays += aMap.addMarker(
                        MarkerOptions()
                            .position(Geo.centroid(element.points).toLatLng())
                            .title(title),
                    )
                }
            }
        }
    }

    // 3) 正在画的：蓝色折线 + 顶点标记，满 3 点补一个填充面
    if (draftPoints.isNotEmpty()) {
        val points = draftPoints.map { it.toLatLng() }
        if (points.size == 1) {
            // 单点预览也要看得见，否则点下去像是没反应
            state.overlays += aMap.addCircle(
                CircleOptions()
                    .center(points.first())
                    .radius(POINT_ELEMENT_RADIUS_M)
                    .fillColor(Color.parseColor("#2F6BFF"))
                    .strokeColor(Color.WHITE)
                    .strokeWidth(3f),
            )
        } else {
            state.overlays += aMap.addPolyline(
                PolylineOptions().addAll(points).width(8f).color(Color.parseColor("#2F6BFF")),
            )
            if (points.size >= 3) {
                state.overlays += aMap.addPolygon(
                    PolygonOptions()
                        .addAll(points)
                        .strokeWidth(4f)
                        .strokeColor(Color.parseColor("#2F6BFF"))
                        .fillColor(Color.parseColor("#332F6BFF")),
                )
            }
            // 闭合提示：把最后一个点连回起点，让「范围已经围起来」一眼可见
            draftPoints.first().let { first ->
                state.overlays += aMap.addPolyline(
                    PolylineOptions()
                        .addAll(listOf(points.last(), first.toLatLng()))
                        .width(4f)
                        .color(Color.parseColor("#802F6BFF")),
                )
            }
        }
        draftPoints.forEachIndexed { index, point ->
            state.overlays += aMap.addCircle(
                CircleOptions()
                    .center(point.toLatLng())
                    .radius(VERTEX_DOT_RADIUS_M)
                    .fillColor(Color.WHITE)
                    .strokeColor(Color.parseColor("#2F6BFF"))
                    .strokeWidth(4f),
            )
            if (index == 0) {
                state.overlays += aMap.addMarker(
                    MarkerOptions().position(point.toLatLng()).title("起点"),
                )
            }
        }
    }

    // 4) 编辑模式：把选中图形的每个顶点画成可拖的圆点，让「能拖哪」一目了然
    selection?.let { shape ->
        shape.points.forEachIndexed { index, point ->
            state.overlays += aMap.addCircle(
                CircleOptions()
                    .center(point.toLatLng())
                    .radius(VERTEX_DOT_RADIUS_M)
                    .fillColor(Color.parseColor("#FF8A00"))
                    .strokeColor(Color.WHITE)
                    .strokeWidth(4f),
            )
        }
        state.overlays += aMap.addMarker(
            MarkerOptions()
                .position(Geo.centroid(shape.points).toLatLng())
                .title("编辑中：${shape.name}"),
        )
    }
}

/** 单点元素的显示半径（米）。取 6 米，在 17 级缩放下是一个清晰的小圆点。 */
private const val POINT_ELEMENT_RADIUS_M = 6.0

/** 顶点手柄的显示半径（米）。 */
private const val VERTEX_DOT_RADIUS_M = 3.5
