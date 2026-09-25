package com.school.nav

import android.app.Application

/**
 * Application：创建进程级依赖容器，并做一次数据自检。
 *
 * 数据自检失败不会崩溃 —— 室内导航这类工具“能用手动模式走通”比“启动即报错”更有价值，
 * 因此只记录日志，把问题留给开发期发现（`AppContainer.dataProblems`）。
 */
class NavApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        val problems = container.dataProblems
        if (problems.isNotEmpty()) {
            android.util.Log.w(TAG, "楼栋数据自检发现 ${problems.size} 个问题：")
            problems.forEach { android.util.Log.w(TAG, " - $it") }
        }
    }

    private companion object {
        const val TAG = "FindClass"
    }
}
