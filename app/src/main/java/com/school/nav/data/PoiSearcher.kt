package com.school.nav.data

import android.content.Context
import android.location.Geocoder
import android.os.Build
import com.school.nav.core.model.LngLat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

/** 一条地点搜索结果。 */
data class PoiResult(
    val name: String,
    val point: LngLat,
)

/**
 * 地点搜索。
 *
 * ## 为什么用 Android 内置 Geocoder，而不是高德搜索
 *
 * 试过两条高德路线，都不可行：
 *  1. **SDK 内置搜索**：3dmap 的 jar 里 `com.amap.api.search` 一个类都没有；
 *  2. **独立的搜索 SDK**（`com.amap.api:search`）：Maven 上最高只到 **9.7.1**（2017 年），
 *     太老，且会再引入一套 so。
 *
 * 第三条路是**高德 Web 服务 API**（`restapi.amap.com/v3/place/text`），需要单独申请
 * 一个「Web服务」类型的 Key。它更准，但要求用户再配一个 Key。
 *
 * 所以这里选了**零配置**的方案：Android 自带的 [Geocoder]。
 *  - 不需要任何额外 Key；
 *  - 覆盖和精度不如高德 POI，但对「在学校附近找到那栋楼」这个用途是够的；
 *  - 设备没有地理编码服务时 [isAvailable] 返回 false，界面会直接隐藏搜索框，
 *    而不是给一个点了没反应的输入框。
 *
 * 以后要换成高德 Web API，只需要另写一个实现替掉这个类，上层不用改。
 */
class PoiSearcher(private val context: Context) {

    private val geocoder: Geocoder? =
        if (Geocoder.isPresent()) Geocoder(context, Locale.CHINA) else null

    /** 设备是否具备地理编码能力。 */
    fun isAvailable(): Boolean = geocoder != null

    /**
     * 按关键字搜索地点。
     *
     * 结果可能为空（找不到），也可能是空列表（服务暂时不可用）——
     * 两种情况都返回空列表，由上层统一提示「没找到」。
     */
    suspend fun search(keyword: String, limit: Int = 8): List<PoiResult> {
        val key = keyword.trim()
        if (key.isEmpty()) return emptyList()
        val coder = geocoder ?: return emptyList()

        return withContext(Dispatchers.IO) {
            runCatching { query(coder, key, limit) }
                .getOrElse { if (it is IOException) emptyList() else emptyList() }
        }
    }

    private suspend fun query(coder: Geocoder, key: String, limit: Int): List<PoiResult> {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+ 必须用带回调的异步版本，同步版本已被移除
            suspendCancellableCoroutine { cont ->
                coder.getFromLocationName(
                    key,
                    limit,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(results: MutableList<android.location.Address>) {
                            if (cont.isActive) cont.resume(results)
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    },
                )
            }
        } else {
            @Suppress("DEPRECATION")
            coder.getFromLocationName(key, limit).orEmpty()
        }

        return addresses.mapNotNull { address ->
            val lat = address.latitude
            val lng = address.longitude
            // 高德/系统都可能返回 (0,0) 表示无效
            if (lat == 0.0 && lng == 0.0) return@mapNotNull null
            PoiResult(
                name = describe(address),
                point = LngLat(lng = lng, lat = lat),
            )
        }
    }

    /** 拼一个人能看懂的标题：优先「名称 + 简要地址」。 */
    private fun describe(address: android.location.Address): String {
        val parts = mutableListOf<String>()
        address.featureName?.takeIf { it.isNotBlank() && !it.all { c -> c.isDigit() } }
            ?.let { parts += it }
        address.thoroughfare?.let { parts += it }
        address.locality?.let { parts += it }
        if (parts.isEmpty()) {
            address.getAddressLine(0)?.let { parts += it }
        }
        return parts.distinct().joinToString(" ").ifBlank { "未知地点" }
    }
}
