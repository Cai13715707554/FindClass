package com.school.nav

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.school.nav.ui.home.AppShell
import com.school.nav.ui.theme.FindClassTheme

/**
 * 单 Activity 架构（技术方案要求）。
 *
 * 只做三件事：请求定位权限、建立依赖容器、挂载 Compose 首页。
 * 业务逻辑全部在 ViewModel 与 core 模块里。
 */
class MainActivity : ComponentActivity() {

    /**
     * Android 12+ 用户可能只授予“大致位置”。这里请求 precise + coarse 两个权限，
     * 用户给哪个都能继续用：室内判楼栋本来就不需要厘米级精度。
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* 结果由 ViewModel 通过 locationSource.isAvailable() 感知，无需在此处理 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 冷启动即请求定位权限；拒绝后仍可手动选择位置完成导航
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )

        val container = (application as NavApplication).container

        setContent {
            FindClassTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppShell(container = container)
                }
            }
        }
    }
}
