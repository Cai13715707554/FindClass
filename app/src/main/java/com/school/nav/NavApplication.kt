package com.school.nav

import android.app.Application
import android.util.Log
import com.amap.api.maps.MapsInitializer

/**
 * Application：创建进程级依赖容器，做一次数据自检，并声明高德 SDK 的隐私合规。
 *
 * 数据自检失败不会崩溃 —— 室内导航这类工具「能用手动模式走通」比「启动即报错」更有价值，
 * 因此只记录日志，把问题留给开发期发现（`AppContainer.dataProblems`）。
 */
class NavApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // 必须在**任何**地图调用之前声明隐私合规，否则高德 SDK 拒绝出图
        // （表现是白屏 / 只出网格，logcat 里能看到隐私未同意的报错）。
        // 上架前要把这里换成真实的「首次启动隐私政策同意」弹窗结果。
        declareAmapPrivacy()

        val problems = container.dataProblems
        if (problems.isNotEmpty()) {
            Log.w(TAG, "楼栋数据自检发现 ${problems.size} 个问题：")
            problems.forEach { Log.w(TAG, " - $it") }
        }
    }

    /**
     * 声明高德 SDK 的隐私合规与 API Key。
     *
     * 只在**已经配置过 Key** 时才初始化 —— 没配 Key 时调用这些会让 SDK 抛
     * `INVALID_USER_KEY` 并在地图页刷一片错误日志，而此时地图页本来就会显示
     * 「去设置里填写 Key」的引导，不需要 SDK 参与。
     */
    private fun declareAmapPrivacy() {
        val key = container.apiKeyStore.amapKey()
        runCatching {
            MapsInitializer.updatePrivacyShow(this, true, true)
            MapsInitializer.updatePrivacyAgree(this, true)
            if (key.isNotBlank()) {
                MapsInitializer.setApiKey(key)
            }
        }.onFailure { e ->
            Log.w(TAG, "高德 SDK 初始化失败（不影响不使用地图的功能）", e)
        }
    }

    private companion object {
        const val TAG = "FindClass"
    }
}
