package com.school.nav.data

import com.school.nav.core.model.LngLat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * 高德 Web 服务 POI 搜索的响应体。
 *
 * 只声明用得到的字段：`ignoreUnknownKeys = true`，高德加字段不会导致解析失败。
 */
@Serializable
private data class AmapPoiResponse(
    /** "1" 成功；"0" 失败，失败原因在 [info] / [infoCode] 里。 */
    val status: String = "",
    val info: String = "",
    @SerialName("infocode") val infoCode: String = "",
    /** 结果总数（字符串）。 */
    val count: String = "0",
    val pois: List<AmapPoi> = emptyList(),
)

@Serializable
private data class AmapPoi(
    val name: String = "",
    /** "经度,纬度"，GCJ-02。 */
    val location: String = "",
    val address: String = "",
    val cityname: String = "",
    val adname: String = "",
    val type: String = "",
)

/** 搜索结果来源，用于给用户解释「这条是用什么搜出来的」。 */
enum class PoiSearchSource {
    /** 高德 POI 搜索（模糊匹配，推荐）。 */
    AmapWeb,

    /** Android 内置地理编码（精确地址匹配）。 */
    Geocoder,
}

/**
 * 高德 Web 服务 POI 搜索。
 *
 * ## 为什么需要它
 *
 * 之前用 Android 内置的 `Geocoder`，但它是**精确地址解析**、不做模糊匹配：
 * 少一个字或有一个同音字就完全搜不到。高德的 POI 搜索就是为这个场景设计的，
 * 支持模糊匹配、别名、错字容忍。
 *
 * ## 需要单独的 Key
 *
 * 高德的 Key 按「服务平台」区分，**Android 平台的 Key 不能用于 Web 服务**。
 * 所以用户要在控制台再建一个「Web服务」类型的 Key 填进设置页。
 * 没填时会自动退回 `Geocoder`，功能不至于不可用（见 [PoiSearcher]）。
 *
 * ## 接口
 *
 * `GET https://restapi.amap.com/v3/place/text?key=..&keywords=..&offset=..&page=1&extensions=base`
 *
 * 用 HttpURLConnection 而不是 `java.net.http`：后者需要 API 34+，
 * 而本工程 minSdk 是 26，用不了。
 */
class AmapPoiSearcher(private val webKey: String) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 没有 Key 就不能用。 */
    fun isAvailable(): Boolean = webKey.isNotBlank()

    /**
     * 按关键词搜 POI。
     *
     * @return 结果列表 + 来源。搜索失败（网络、配额、Key 无效）时返回空列表与
     *         一段给用户看的原因，由上层决定是提示还是静默退回 Geocoder。
     */
    suspend fun search(
        keyword: String,
        limit: Int = 10,
    ): Pair<List<PoiResult>, String?> = withContext(Dispatchers.IO) {
        val key = keyword.trim()
        if (key.isEmpty()) return@withContext emptyList<PoiResult>() to null
        if (!isAvailable()) return@withContext emptyList<PoiResult>() to null

        val url = buildUrl(key, limit)
        val body = runCatching { get(url) }.getOrElse { e ->
            return@withContext emptyList<PoiResult>() to "搜索请求失败：${e.message ?: "网络不可用"}"
        }

        val parsed = runCatching { json.decodeFromString(AmapPoiResponse.serializer(), body) }
            .getOrElse { return@withContext emptyList<PoiResult>() to "搜索结果解析失败" }

        if (parsed.status != "1") {
            // 常见：INVALID_USER_KEY（Key 类型不对）、DAILY_QUERY_OVER_LIMIT（配额用尽）
            return@withContext emptyList<PoiResult>() to
                "高德搜索返回错误：${parsed.info.ifBlank { parsed.infoCode }}"
        }

        val results = parsed.pois.mapNotNull { poi ->
            val point = parseLocation(poi.location) ?: return@mapNotNull null
            PoiResult(
                name = poi.name.ifBlank { poi.address },
                point = point,
                matchedKeyword = key,
                isFuzzy = false,
                detail = listOf(poi.cityname, poi.adname, poi.address)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" "),
                source = PoiSearchSource.AmapWeb,
            )
        }
        results to null
    }

    private fun buildUrl(keyword: String, limit: Int): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        // extensions=base 只返回基础字段，响应更小；offset 上限 25
        return "https://restapi.amap.com/v3/place/text" +
            "?key=$webKey" +
            "&keywords=$encoded" +
            "&offset=${limit.coerceIn(1, 25)}" +
            "&page=1" +
            "&extensions=base"
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }
                ?: error("HTTP $code 无响应体")
        } finally {
            connection.disconnect()
        }
    }

    /** 把高德的 "经度,纬度" 解析成 [LngLat]。格式不对返回 null。 */
    private fun parseLocation(location: String): LngLat? {
        val parts = location.split(',')
        if (parts.size != 2) return null
        val lng = parts[0].trim().toDoubleOrNull() ?: return null
        val lat = parts[1].trim().toDoubleOrNull() ?: return null
        if (lng == 0.0 || lat == 0.0) return null
        return LngLat(lng = lng, lat = lat)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 8_000
    }
}
