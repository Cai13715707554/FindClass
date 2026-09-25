package com.school.nav.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 高德 Key 的本地存储。
 *
 * **需要两个 Key，它们不能互换**（高德控制台里「服务平台」选错就用不了）：
 *
 * | Key | 服务平台 | 用途 |
 * | --- | --- | --- |
 * | [amapKey] | **Android 平台** | 地图渲染（MapView）、定位 |
 * | [amapWebKey] | **Web服务** | POI 模糊搜索（restapi.amap.com） |
 *
 * 为什么让用户在设置页里填、而不是写死在 BuildConfig：
 *  - Key 属于**开发者凭据**，写进仓库会泄露（打包进 APK 的字段可以被反编译出来）；
 *  - 高德 Key 与「包名 + SHA1 签名」绑定，换机器 / 换签名就要换 Key；
 *  - 没填 Key 时界面给出明确引导，而不是白屏或点了没反应。
 *
 * 用 SharedPreferences 而不是 DataStore：高德 SDK 的初始化发生在
 * `Application.onCreate` 里，是同步调用；DataStore 是挂起 API，用它就得阻塞主线程
 * 或改成异步后置初始化，后者会让地图页冷启动时先白屏再出图。
 */
interface ApiKeyStore {

    /** Android 平台 Key：地图渲染与定位。未设置时返回空字符串。 */
    fun amapKey(): String

    /** Web服务 Key：POI 搜索。未设置时返回空字符串。 */
    fun amapWebKey(): String

    /** 是否已经配置过地图 Key。 */
    fun hasAmapKey(): Boolean = amapKey().isNotBlank()

    /** 是否已经配置过搜索 Key。 */
    fun hasAmapWebKey(): Boolean = amapWebKey().isNotBlank()

    fun saveAmapKey(key: String)

    fun saveAmapWebKey(key: String)

    fun clearAmapKey()

    fun clearAmapWebKey()
}

/** SharedPreferences 实现。 */
class SharedPrefsApiKeyStore(context: Context) : ApiKeyStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun amapKey(): String = prefs.getString(KEY_AMAP, "").orEmpty().trim()

    override fun amapWebKey(): String = prefs.getString(KEY_AMAP_WEB, "").orEmpty().trim()

    override fun saveAmapKey(key: String) {
        prefs.edit().putString(KEY_AMAP, key.trim()).apply()
    }

    override fun saveAmapWebKey(key: String) {
        prefs.edit().putString(KEY_AMAP_WEB, key.trim()).apply()
    }

    override fun clearAmapKey() {
        prefs.edit().remove(KEY_AMAP).apply()
    }

    override fun clearAmapWebKey() {
        prefs.edit().remove(KEY_AMAP_WEB).apply()
    }

    private companion object {
        const val PREFS_NAME = "amap_settings"
        const val KEY_AMAP = "amap_api_key"
        const val KEY_AMAP_WEB = "amap_web_key"
    }
}
