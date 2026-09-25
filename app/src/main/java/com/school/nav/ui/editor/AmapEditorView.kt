package com.school.nav.ui.editor

import android.graphics.Color
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
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polygon
import com.amap.api.maps.model.PolygonOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.school.nav.core.data.EditorBuilding
import com.school.nav.core.model.ElementType
import com.school.nav.core.model.Geo
import com.school.nav.core.model.LngLat
import com.school.nav.state.CenterRequest

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
    onCenterConsumed: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val overlayState = remember { OverlayState() }

    val mapView = remember {
        MapView(context).apply { onCreate(null) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            val aMap = view.map ?: return@AndroidView

            if (!overlayState.configured) {
                overlayState.configured = true
                configureMap(aMap)
            }

            // 每次重组都重设监听，回调里用的 onMapClick 才是最新的
            aMap.setOnMapClickListener { latLng ->
                onMapClick(LngLat(lng = latLng.longitude, lat = latLng.latitude))
            }

            // 居中请求：带 token，消费一次就通知上层清空，避免每次重组都重置相机
            centerRequest?.let { request ->
                aMap.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(request.point.toLatLng(), request.zoom),
                )
                onCenterConsumed(request.token)
            }

            redraw(aMap, overlayState, draftPoints, buildings)
        },
    )
}

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
) {
    state.overlays.forEach { overlay ->
        when (overlay) {
            is Polygon -> overlay.remove()
            is Marker -> overlay.remove()
            is Polyline -> overlay.remove()
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
                val points = element.points.map { it.toLatLng() }
                if (points.size < 3) return@forEach
                val color = Color.parseColor(strokeColorOf(element.elementType))
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
                        .title("${floor.level}F ${element.name}"),
                )
            }
        }
    }

    // 3) 正在画的：蓝色折线 + 顶点标记，满 3 点补一个填充面
    if (draftPoints.isNotEmpty()) {
        val points = draftPoints.map { it.toLatLng() }
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
        draftPoints.forEachIndexed { index, point ->
            state.overlays += aMap.addMarker(
                MarkerOptions().position(point.toLatLng()).title("顶点 ${index + 1}"),
            )
        }
    }
}
