package com.school.nav.data

import android.content.Context
import android.location.Geocoder
import android.os.Build
import com.school.nav.core.model.LngLat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/** 一条地点搜索结果。 */
data class PoiResult(
    val name: String,
    val point: LngLat,
    /**
     * 这条结果是**用哪个关键词搜出来的**。
     *
     * 与用户输入不一致时说明做了模糊兜底（例如输入「佛山大学」、实际用「佛山」搜到），
     * 界面会把它标出来，免得用户以为搜错了。
     */
    val matchedKeyword: String = "",
    /** 是否是模糊兜底的结果。 */
    val isFuzzy: Boolean = false,
) {
    /** 展示用的地点名 + 坐标。 */
    val coordinateText: String get() = "%.5f, %.5f".format(point.lat, point.lng)
}

/**
 * 地点搜索。
 *
 * ## 为什么用 Android 内置 Geocoder
 *
 * 三条高德路线都不可行或代价过高：
 *  1. **SDK 内置搜索**：3dmap 的 jar 里 `com.amap.api.search` 一个类都没有（已用工具核对）；
 *  2. **独立搜索 SDK**（`com.amap.api:search`）：Maven 上最高只到 9.7.1（2017 年），太老；
 *  3. **高德 Web 服务 API**（`restapi.amap.com/v3/place/text`）：POI 模糊搜索最准，
 *     但需要用户**再申请一个「Web服务」类型的 Key**。
 *
 * 所以选零配置的 [Geocoder]。
 *
 * ## 但 Geocoder 是「精确地址匹配」，不是 POI 模糊搜索
 *
 * 这是实测出来的问题：输入全名能搜到，少一个字、或有一个同音字就完全搜不到。
 * Geocoder 的语义是「把地址串解析成坐标」，不是「按关键词找地点」。
 *
 * 缓解办法是**多轮降级重试**（见 [candidates]）：精确搜不到就逐步放宽 ——
 * 去掉末尾的字（中文地名越靠后越具体，去掉尾字相当于放宽到上一级），
 * 再尝试补上常见地点后缀。这样「佛山大学」搜不到时会退到「佛山」，
 * 至少能把地图带到一个有意义的位置。
 *
 * 要真正解决 POI 模糊搜索，只能上高德 Web API 或多装一个搜索 SDK。
 */
class PoiSearcher(private val context: Context) {

    private val geocoder: Geocoder? =
        if (Geocoder.isPresent()) Geocoder(context, Locale.CHINA) else null

    /** 设备是否具备地理编码能力。 */
    fun isAvailable(): Boolean = geocoder != null

    /**
     * 按关键字搜索地点，带模糊兜底。
     *
     * @return 结果列表（可能为空）。每条结果都带 [PoiResult.matchedKeyword]，
     *         用来告诉用户「这是用什么词搜到的」。
     */
    suspend fun search(keyword: String, limit: Int = 8): List<PoiResult> {
        val key = keyword.trim()
        if (key.isEmpty()) return emptyList()
        val coder = geocoder ?: return emptyList()

        return withContext(Dispatchers.IO) {
            val exact = query(coder, key, limit)
            if (exact.isNotEmpty()) {
                return@withContext exact.map { it.copy(matchedKeyword = key, isFuzzy = false) }
            }

            // 精确搜不到 -> 逐个尝试放宽后的关键词，用第一个有结果的
            for (candidate in candidates(key)) {
                val relaxed = query(coder, candidate, limit)
                if (relaxed.isNotEmpty()) {
                    return@withContext relaxed.map {
                        it.copy(matchedKeyword = candidate, isFuzzy = candidate != key)
                    }
                }
            }
            emptyList()
        }
    }

    /**
     * 生成放宽后的候选关键词。
     *
     * 规则（按优先级）：
     *  1. **去尾字**：中文地名的限定语通常在后面（「佛山大学」→「佛山」、
     *     「广东省实验中学」→「广东省实验」）。从尾往前砍，最多砍到剩 2 个字，
     *     并且最多试 [MAX_TRUNCATIONS] 次，避免触发 Geocoder 的调用频率限制。
     *  2. **补后缀**：用户可能只记得主体名（「佛山」），补上常见机构后缀再试一遍。
     *
     * 这些都不改地名内部，所以**同音字仍然搜不到** ——
     * 那需要拼音/模糊匹配能力，Geocoder 没有。
     */
    private fun candidates(key: String): List<String> {
        val result = LinkedHashSet<String>()

        // 1) 去尾字
        var truncations = 0
        var current = key
        while (current.length > MIN_KEYWORD_LENGTH && truncations < MAX_TRUNCATIONS) {
            current = current.dropLast(1)
            if (current.length >= MIN_KEYWORD_LENGTH) {
                result += current
                truncations++
            }
        }

        // 2) 主体名 + 常见后缀
        val stem = key.take(2).takeIf { it.length == 2 && it != key }
        if (stem != null) {
            COMMON_SUFFIXES.forEach { suffix -> result += stem + suffix }
        }

        result.remove(key)
        return result.toList()
    }

    private suspend fun query(coder: Geocoder, key: String, limit: Int): List<PoiResult> {
        val addresses = runCatching { fromLocationName(coder, key, limit) }.getOrDefault(emptyList())
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

    private suspend fun fromLocationName(
        coder: Geocoder,
        key: String,
        limit: Int,
    ): List<android.location.Address> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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

    private companion object {
        /** 放宽时至少保留几个字。少于这个长度就退化成搜省/市，意义不大。 */
        const val MIN_KEYWORD_LENGTH = 2

        /** 最多砍几次尾字，避免触发地理编码服务的调用频率限制。 */
        const val MAX_TRUNCATIONS = 3

        /** 主体名后面可能被省略的后缀。 */
        val COMMON_SUFFIXES = listOf(
            "大学", "学院", "学校", "中学", "小学", "职业技术学院",
            "医院", "公园", "广场", "地铁站", "火车站", "汽车站",
        )
    }
}
