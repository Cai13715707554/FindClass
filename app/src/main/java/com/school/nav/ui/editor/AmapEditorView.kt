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
import com.school.nav.core.data.BuildingOutline
import com.school.nav.core.model.Geo
import com.school.nav.core.model.LngLat

/** 经纬度 -> 高德坐标。注意高德 LatLng 的参数顺序是 (纬度, 经度)。 */
private fun LngLat.toLatLng(): LatLng = LatLng(lat, lng)

/**
 * 高德地图绘制视图。
 *
 * ## 生命周期
 *
 * `MapView` 不是普通 View：它自己管着 GL 上下文，必须把 Activity 生命周期转发给它
 * （onCreate/onResume/onPause/onDestroy），否则会出现黑屏、内存泄漏，
 * 或者切到后台再回来地图不刷新。这里用 [LocalLifecycleOwner] + [DisposableEffect]。
 *
 * ## 与 Compose 的边界
 *
 * 地图是命令式的（`aMap.addPolygon`），Compose 是声明式的。做法是：
 * 每次 `draftPoints` / `outlines` 变化就**清空重画**当前应该显示的全部 overlay。
 *
 * 元素数量是几十个量级，整体重画开销可忽略，换来的是不必维护
 * 「哪个 overlay 对应哪个顶点」的增量状态 —— 那种写法很容易留下幽灵多边形。
 * 而且只清 overlay、不动相机，所以用户正在拖地图时不会被拉回原位。
 */
@Composable
fun AmapEditorView(
    apiKey: String,
    draftPoints: List<LngLat>,
    outlines: List<BuildingOutline>,
    referenceBuildings: List<BuildingOutline>,
    onMapClick: (LngLat) -> Unit,
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

    // Key 变了要让地图重新初始化；用前 8 位做指纹足够区分不同 Key
    val keyTag = apiKey.take(8)

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            val aMap = view.map ?: return@AndroidView

            if (overlayState.keyTag != keyTag) {
                overlayState.keyTag = keyTag
                overlayState.initialized = false
            }

            if (!overlayState.initialized) {
                overlayState.initialized = true
                configureMap(aMap)
                // 首次进入定位到内置数据的第一栋楼，
                // 否则地图停在世界视图上，用户不知道要往哪拖
                referenceBuildings.firstOrNull()
                    ?.polygon
                    ?.takeIf { it.size >= 3 }
                    ?.let { polygon ->
                        aMap.moveCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                Geo.centroid(polygon).toLatLng(),
                                17f,
                            ),
                        )
                    }
            }

            // 每次重组都重设一次监听，回调里用的 onMapClick 才是最新的
            aMap.setOnMapClickListener { latLng ->
                onMapClick(LngLat(lng = latLng.longitude, lat = latLng.latitude))
            }

            redraw(aMap, overlayState, draftPoints, outlines, referenceBuildings)
        },
    )
}

/** overlay 句柄 + 一次性初始化标记。 */
private class OverlayState {
    var keyTag: String? = null
    var initialized: Boolean = false
    val overlays = mutableListOf<Any>()
}

/** 地图基础设置。 */
private fun configureMap(aMap: AMap) {
    aMap.mapType = AMap.MAP_TYPE_NORMAL
    aMap.uiSettings.apply {
        isZoomControlsEnabled = true
        isCompassEnabled = true
        isScaleControlsEnabled = true
        // 关掉倾斜与旋转手势：画楼栋轮廓时误触旋转会让人非常难受
        isTiltGesturesEnabled = false
        isRotateGesturesEnabled = false
    }
}

/** 清空后按当前状态整体重画。 */
private fun redraw(
    aMap: AMap,
    state: OverlayState,
    draftPoints: List<LngLat>,
    outlines: List<BuildingOutline>,
    referenceBuildings: List<BuildingOutline>,
) {
    state.overlays.forEach { overlay ->
        when (overlay) {
            is Polygon -> overlay.remove()
            is Marker -> overlay.remove()
            is Polyline -> overlay.remove()
        }
    }
    state.overlays.clear()

    // 1) 内置/已保存的楼栋：灰色半透明参考，方便照着描
    referenceBuildings.forEach { building ->
        if (building.polygon.size < 3) return@forEach
        state.overlays += aMap.addPolygon(
            PolygonOptions()
                .addAll(building.polygon.map { it.toLatLng() })
                .strokeWidth(4f)
                .strokeColor(Color.parseColor("#8A90A0"))
                .fillColor(Color.parseColor("#228A90A0")),
        )
    }

    // 2) 本次已画好的楼栋：绿色
    outlines.forEach { outline ->
        if (outline.polygon.size < 3) return@forEach
        state.overlays += aMap.addPolygon(
            PolygonOptions()
                .addAll(outline.polygon.map { it.toLatLng() })
                .strokeWidth(6f)
                .strokeColor(Color.parseColor("#16B364"))
                .fillColor(Color.parseColor("#3316B364")),
        )
        state.overlays += aMap.addMarker(
            MarkerOptions()
                .position(Geo.centroid(outline.polygon).toLatLng())
                .title(outline.name),
        )
    }

    // 3) 正在画的：蓝色折线 + 顶点标记，满 3 点再补一个填充面
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
                MarkerOptions()
                    .position(point.toLatLng())
                    .title("顶点 ${index + 1}"),
            )
        }
    }
}
