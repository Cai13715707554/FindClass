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
    /** 补充说明（城市、区、地址等），直接展示在名称下面。 */
    val detail: String = "",
    /** 结果来源，用于向用户解释为什么搜出的东西不一样。 */
    val source: PoiSearchSource = PoiSearchSource.Geocoder,
) {
    /** 坐标文本，详情为空时的兜底展示。 */
    val coordinateText: String get() = "%.5f, %.5f".format(point.lat, point.lng)

    /** 副标题：优先展示地点详情，没有就展示坐标。 */
    val subtitle: String
        get() = when {
            isFuzzy -> "近似匹配「$matchedKeyword」· ${detail.ifBlank { coordinateText }}"
            detail.isNotBlank() -> detail
            else -> coordinateText
        }
}

/** 一次搜索的结果与状态。 */
data class PoiSearchOutcome(
    val results: List<PoiResult> = emptyList(),
    /** 非 null 表示这次搜索有问题，界面可以直接提示。 */
    val message: String? = null,
    /** 实际使用的后端，用于向用户解释能力差异。 */
    val source: PoiSearchSource = PoiSearchSource.Geocoder,
)

/**
 * 地点搜索（统一入口）。
 *
 * ## 两个后端，优先高德
 *
 * | 后端 | 能力 | 前置条件 |
 * | --- | --- | --- |
 * | [AmapPoiSearcher] | **POI 模糊搜索**：少写字、同音字、错字都能匹配 | 需要「Web服务」类型的 Key |
 * | Android [Geocoder] | 精确地址解析：**不做模糊匹配** | 零配置，但能力弱 |
 *
 * 配了 Web 服务 Key 就用高德；没配或搜索失败时退回 Geocoder，
 * 让功能不至于不可用，同时在结果里标出来源。
 *
 * ## Geocoder 的降级重试
 *
 * Geocoder 精确搜不到时会逐步放宽：去掉末尾的字（中文地名限定语通常在后面，
 * 「佛山大学」→「佛山」），再尝试补常见地点后缀。这只在退回 Geocoder 时才会跑，
 * 高德后端本身就能模糊匹配，不需要这些补丁。
 */
class PoiSearcher(
    private val context: Context,
    webKey: String = "",
) {

    private val amap = AmapPoiSearcher(webKey)

    private val geocoder: Geocoder? =
        if (Geocoder.isPresent()) Geocoder(context, Locale.CHINA) else null

    /** 是否具备**任何**搜索能力。都没有时界面会把搜索入口禁用掉。 */
    fun isAvailable(): Boolean = amap.isAvailable() || geocoder != null

    /** 当前用的是哪个后端，用于界面上给一句说明。 */
    fun activeSource(): PoiSearchSource =
        if (amap.isAvailable()) PoiSearchSource.AmapWeb else PoiSearchSource.Geocoder

    /**
     * 搜索地点。
     *
     * 先试高德 POI；高德不可用、失败、或没结果时退回 Geocoder（含降级重试）。
     */
    suspend fun search(keyword: String, limit: Int = 10): PoiSearchOutcome {
        val key = keyword.trim()
        if (key.isEmpty()) return PoiSearchOutcome()

        // ---- 1) 高德 POI 模糊搜索 ----
        if (amap.isAvailable()) {
            val (results, error) = amap.search(key, limit)
            if (results.isNotEmpty()) {
                return PoiSearchOutcome(
                    results = results,
                    source = PoiSearchSource.AmapWeb,
                )
            }
            // 有错就带出去提示；只是没结果的话交给 Geocoder 再试一次
            if (error != null) {
                return PoiSearchOutcome(message = error, source = PoiSearchSource.AmapWeb)
            }
        }

        // ---- 2) 退回 Geocoder ----
        val coder = geocoder
            ?: return PoiSearchOutcome(
                message = if (amap.isAvailable()) {
                    "没找到「$key」"
                } else {
                    "本机不支持地点搜索，可在设置里填「Web服务」Key 启用高德搜索"
                },
                source = PoiSearchSource.Geocoder,
            )

        val exact = withContext(Dispatchers.IO) { queryGeocoder(coder, key, limit) }
        if (exact.isNotEmpty()) {
            return PoiSearchOutcome(
                results = exact.map { it.copy(matchedKeyword = key, isFuzzy = false) },
                source = PoiSearchSource.Geocoder,
            )
        }

        // 精确搜不到 -> 逐个尝试放宽后的关键词，用第一个有结果的
        for (candidate in relaxedCandidates(key)) {
            val relaxed = withContext(Dispatchers.IO) { queryGeocoder(coder, candidate, limit) }
            if (relaxed.isNotEmpty()) {
                return PoiSearchOutcome(
                    results = relaxed.map {
                        it.copy(matchedKeyword = candidate, isFuzzy = candidate != key)
                    },
                    source = PoiSearchSource.Geocoder,
                )
            }
        }

        return PoiSearchOutcome(
            message = "没找到「$key」。可以试试少写几个字，或在设置里配「Web服务」Key " +
                "启用高德的模糊搜索（能容忍少字、同音字）。",
            source = PoiSearchSource.Geocoder,
        )
    }

    // ------------------------------------------------------------ Geocoder 兜底

    /**
     * 生成放宽后的候选关键词（只在退回 Geocoder 时使用）。
     *
     * 规则按优先级：
     *  1. **去尾字**：中文地名限定语通常在后面。从尾往前砍，最多砍
     *     [MAX_TRUNCATIONS] 次、至少留 [MIN_KEYWORD_LENGTH] 个字，
     *     避免触发地理编码服务的调用频率限制。
     *  2. **补后缀**：用户可能只记得主体名（「佛山」），补上常见机构后缀再试。
     *
     * 注意这不改地名内部，所以**同音字仍然搜不到** —— 那需要拼音/模糊匹配能力，
     * Geocoder 没有；要这个能力只能用高德 POI 搜索。
     */
    private fun relaxedCandidates(key: String): List<String> {
        val result = LinkedHashSet<String>()

        var truncations = 0
        var current = key
        while (current.length > MIN_KEYWORD_LENGTH && truncations < MAX_TRUNCATIONS) {
            current = current.dropLast(1)
            if (current.length >= MIN_KEYWORD_LENGTH) {
                result += current
                truncations++
            }
        }

        val stem = key.take(2).takeIf { it.length == 2 && it != key }
        if (stem != null) {
            COMMON_SUFFIXES.forEach { suffix -> result += stem + suffix }
        }

        result.remove(key)
        return result.toList()
    }

    private suspend fun queryGeocoder(
        coder: Geocoder,
        key: String,
        limit: Int,
    ): List<PoiResult> {
        val addresses = runCatching { fromLocationName(coder, key, limit) }.getOrDefault(emptyList())
        return addresses.mapNotNull { address ->
            val lat = address.latitude
            val lng = address.longitude
            // 高德/系统都可能返回 (0,0) 表示无效
            if (lat == 0.0 && lng == 0.0) return@mapNotNull null
            PoiResult(
                name = describe(address),
                point = LngLat(lng = lng, lat = lat),
                detail = address.getAddressLine(0).orEmpty(),
                source = PoiSearchSource.Geocoder,
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

    /** 拼一个人能看懂的标题：优先「名称」，没有就用地址首行。 */
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
        /** 放宽时至少保留几个字。再短就退化成搜省/市，意义不大。 */
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
