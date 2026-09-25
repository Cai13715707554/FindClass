package com.school.nav.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 高德 API Key 的本地存储。
 *
 * 为什么由用户在设置页里填、而不是写死在 BuildConfig：
 *
 *  - Key 属于**开发者凭据**，写进仓库会泄露（本项目的 local.properties 虽然已忽略，
 *    但打包进 APK 的 BuildConfig 字段是可以被反编译出来的）；
 *  - 高德 Key 与「包名 + SHA1 签名」绑定，换机器 / 换签名就要换 Key，
 *    让用户现场填比每次改代码重新打包务实得多；
 *  - 没填 Key 时地图页给出明确引导，而不是白屏让人猜。
 *
 * 用 SharedPreferences 而不是 DataStore：见 [ApiKeyStore] 的说明。
 */
interface ApiKeyStore {

    /** 已保存的高德 Key；未设置时返回空字符串。 */
    fun amapKey(): String

    /** 是否已经配置过 Key。 */
    fun hasAmapKey(): Boolean = amapKey().isNotBlank()

    /** 保存 Key（会自动去掉首尾空白，避免粘贴时带空格）。 */
    fun saveAmapKey(key: String)

    /** 清空 Key。 */
    fun clearAmapKey()
}

/**
 * SharedPreferences 实现。
 *
 * 刻意**不用** DataStore：高德 SDK 初始化发生在 Application.onCreate 里，
 * 而 DataStore 是挂起 API，要用它就得阻塞主线程或改成异步后置初始化，
 * 后者在地图页冷启动时会出现「先白屏再出图」。Key 只有一个字符串、
 * 读取必须在启动时同步完成，SharedPreferences 是这里的正确工具。
 */
class SharedPrefsApiKeyStore(context: Context) : ApiKeyStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun amapKey(): String = prefs.getString(KEY_AMAP, "").orEmpty().trim()

    override fun saveAmapKey(key: String) {
        prefs.edit().putString(KEY_AMAP, key.trim()).apply()
    }

    override fun clearAmapKey() {
        prefs.edit().remove(KEY_AMAP).apply()
    }

    private companion object {
        const val PREFS_NAME = "amap_settings"
        const val KEY_AMAP = "amap_api_key"
    }
}
