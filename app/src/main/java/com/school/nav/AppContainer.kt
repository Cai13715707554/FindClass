package com.school.nav

import android.content.Context
import com.school.nav.core.data.CampusRepository
import com.school.nav.data.ApiKeyStore
import com.school.nav.data.CampusAssets
import com.school.nav.data.ConfigStore
import com.school.nav.data.SharedPrefsActiveConfigStore
import com.school.nav.data.SharedPrefsApiKeyStore
import com.school.nav.location.AmapLocationSource
import com.school.nav.location.BarometricAltimeter
import com.school.nav.location.LocationSource
import com.school.nav.location.SystemLocationSource
import com.school.nav.state.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 极简依赖容器。
 *
 * MVP 规模下用手写容器而不是 DI 框架：依赖关系一目了然，启动开销也最小。
 * 所有对象都是懒加载、进程内单例。
 */
class AppContainer(private val context: Context) {

    /**
     * 多份配置的管理。要先于 [repository] 建好：装配楼栋数据时要用它读当前配置。
     */
    val configStore: ConfigStore by lazy {
        ConfigStore(
            filesDir = context.filesDir,
            externalFilesDir = context.getExternalFilesDir(null),
            activeConfigStore = SharedPrefsActiveConfigStore(context),
        )
    }

    /** 高德 Key 的存储（用户在“我的 → 设置”里填写）。 */
    val apiKeyStore: ApiKeyStore by lazy { SharedPrefsApiKeyStore(context) }

    /**
     * 按**当前生效的配置**装配楼栋数据（assets 打底 + 配置覆盖）。
     *
     * 做成方法而不是只在 `by lazy` 里算一次：用户可能在设置页切换配置，
     * 切换后必须重新装配，否则界面还是上一份数据。
     */
    fun loadRepository(): CampusRepository =
        runCatching { CampusAssets.loadRepository(context, configStore) }
            .getOrElse { CampusRepository("""{"buildings":[]}""") }

    /**
     * 当前仓库。用 `MutableStateFlow` 而不是只读 val：
     *
     * 切换配置 / 编辑器存盘后数据会变，首页的搜索与位置、编辑器的参考楼栋
     * 都得跟着换一份。与其让两个 ViewModel 互相通知，不如让它们都订阅这一个源。
     */
    private val _repository = MutableStateFlow(loadRepository())
    val repositoryFlow: StateFlow<CampusRepository> = _repository.asStateFlow()

    val repository: CampusRepository get() = _repository.value

    /** 重新装配当前配置并通知订阅者。切换配置、或存盘之后调用。 */
    fun reloadRepository() {
        _repository.value = loadRepository()
    }

    /** 数据自检结果，非空表示数据有问题（assets 或编辑器配置）。 */
    val dataProblems: List<String> get() = repository.validate() + repository.mergeWarnings

    /**
     * 定位通道。
     *
     * 用户在设置里填了高德 Key 且 SDK 可用，就优先用高德（国内室内/城市峡谷更稳），
     * 否则落回系统定位。两者都不可用时上层会走手动兜底。
     *
     * 注意：这个值是**惰性**的，填完 Key 需要重启 App 才生效。
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
