package com.school.nav

import android.content.Context
import com.school.nav.core.data.CampusRepository
import com.school.nav.data.ApiKeyStore
import com.school.nav.data.CampusAssets
import com.school.nav.data.EditorConfigStore
import com.school.nav.data.SharedPrefsApiKeyStore
import com.school.nav.location.AmapLocationSource
import com.school.nav.location.BarometricAltimeter
import com.school.nav.location.LocationSource
import com.school.nav.location.SystemLocationSource
import com.school.nav.state.UserPreferences

/**
 * 极简依赖容器。
 *
 * MVP 规模下用手写容器而不是 DI 框架：依赖关系一目了然，启动开销也最小。
 * 所有对象都是懒加载、进程内单例。
 */
class AppContainer(private val context: Context) {

    /**
     * 编辑器配置的落盘位置。要先于 [repository] 建好：装配楼栋数据时要用它读配置。
     */
    val editorConfigStore: EditorConfigStore by lazy {
        EditorConfigStore(
            filesDir = context.filesDir,
            externalFilesDir = context.getExternalFilesDir(null),
        )
    }

    /** 高德 Key 的存储（用户在“我的 → 设置”里填写）。 */
    val apiKeyStore: ApiKeyStore by lazy { SharedPrefsApiKeyStore(context) }

    /**
     * 楼栋数据：assets 打底 + 编辑器配置覆盖。
     *
     * 读取失败时降级为空数据集，保证 App 仍能启动并给出提示，而不是直接崩。
     */
    val repository: CampusRepository by lazy {
        runCatching { CampusAssets.loadRepository(context, editorConfigStore) }
            .getOrElse { CampusRepository("""{"buildings":[]}""") }
    }

    /** 数据自检结果，非空表示数据有问题（assets 或编辑器配置）。 */
    val dataProblems: List<String> by lazy {
        repository.validate() + repository.mergeWarnings
    }

    /**
     * 定位通道。
     *
     * 用户在设置里填了高德 Key 且 SDK 可用，就优先用高德（国内室内/城市峡谷更稳），
     * 否则落回系统定位。两者都不可用时上层会走手动兜底。
     *
     * 注意：这个值是**惰性**的，所以用户在设置里填完 Key 之后需要重启定位通道才生效；
     * 由于 [AmapLocationSource] 内部会在首次使用时重新读取 Key，实际表现是
     * 首次进入首页时若已配置 Key 就直接用高德。填 Key 后建议重启 App。
     */
    val locationSource: LocationSource by lazy {
        val key = apiKeyStore.amapKey()
        if (key.isNotBlank()) {
            val amap = AmapLocationSource(context, key)
            if (amap.isAvailable()) return@lazy amap
        }
        SystemLocationSource(context)
    }

    val altimeter: BarometricAltimeter by lazy { BarometricAltimeter(context) }

    val preferences: UserPreferences by lazy { UserPreferences(context) }
}
