package com.school.nav

import android.content.Context
import com.school.nav.core.data.CampusRepository
import com.school.nav.data.CampusAssets
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

    /** 楼栋静态数据。读取失败时降级为空数据集，保证 App 仍能启动并给出提示。 */
    val repository: CampusRepository by lazy {
        runCatching { CampusAssets.loadRepository(context) }
            .getOrElse {
                // 数据文件损坏不应该让 App 直接崩，退化为空数据 + 自检报错
                CampusRepository("""{"buildings":[]}""")
            }
    }

    /** 数据自检结果，非空表示 assets 里的数据有问题。 */
    val dataProblems: List<String> by lazy { repository.validate() }

    /**
     * 定位通道。
     *
     * 配了高德 Key 且 SDK 可用就优先用高德（国内室内/城市峡谷更稳），
     * 否则落回系统定位。两者都不可用时上层会走手动兜底。
     */
    val locationSource: LocationSource by lazy {
        if (BuildConfig.USE_AMAP) {
            val amap = AmapLocationSource(context, BuildConfig.AMAP_KEY)
            if (amap.isAvailable()) return@lazy amap
        }
        SystemLocationSource(context)
    }

    val altimeter: BarometricAltimeter by lazy { BarometricAltimeter(context) }

    val preferences: UserPreferences by lazy { UserPreferences(context) }
}
