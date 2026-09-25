package com.school.nav.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.school.nav.core.model.LngLat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

/** 一次原始定位结果。 */
data class RawLocationFix(
    val point: LngLat,
    /** 水平精度（米），越小越可信；系统没有给定时为 null。 */
    val accuracyMeters: Double?,
    val provider: String?,
)

/**
 * 定位可用性。
 *
 * 为什么要区分原因而不是一个 boolean：
 * 「没授权限」和「用户把定位服务关了」对用户的提示完全不同，
 * 而且后者可能在 App 运行中途发生（用户切到系统设置关掉定位）。
 * 只返回 boolean 时上层无法区分，只能给一句笼统的提示。
 */
enum class LocationAvailability {
    /** 可以定位。 */
    Available,

    /** 缺少定位权限。 */
    PermissionDenied,

    /** 定位服务被系统/用户关闭。 */
    ServiceDisabled,

    /** 设备没有定位能力（或第三方地图 SDK 初始化失败）。 */
    NoProvider,
}

/**
 * 定位流的一次事件。
 *
 * 流里同时承载「取到点」和「取不到点」两种结果。为什么不用空流表达失败：
 * 空流 collect 会立刻结束，上层只看到「没有数据」，无法区分是权限被拒、
 * 定位服务被关，还是 provider 不可用，界面就会一直停在「定位中…」。
 */
sealed interface LocationUpdate {

    /** 取到一个定位点。 */
    data class Fix(val fix: RawLocationFix) : LocationUpdate

    /** 当前取不到点，附带原因。 */
    data class Unavailable(val reason: LocationAvailability) : LocationUpdate
}

/** 定位来源抽象。 */
interface LocationSource {
    /** 是否具备提供定位的条件（权限、服务开关、传感器）。 */
    fun isAvailable(): Boolean

    /** 不可用的具体原因；[isAvailable] 为 true 时应返回 [LocationAvailability.Available]。 */
    fun availability(): LocationAvailability

    /** 定位事件流；上层负责生命周期，collect 取消即停止更新。 */
    fun locationUpdates(): Flow<LocationUpdate>

    /** 供 UI 显示当前使用的定位通道名称。 */
    val channelName: String
}

/**
 * 基于 Android 系统定位（LocationManager）的实现。
 *
 * 选择它作为默认通道的原因：
 *  - 不依赖 Google GMS，国内 ROM 可用；
 *  - 不引入第三方 SDK，MVP 可以先跑通“GPS 判楼栋”这件事。
 *
 * 高德 SDK 通过反射接入（见 [AmapLocationSource]），有 Key 时优先使用，
 * 因为高德在国内室内/城市峡谷场景的取点明显比系统 provider 稳定。
 *
 * 室内 GPS 漂移的处理不在这里 —— 这里只负责“给点”；
 * “保持最后锁定楼栋、不频繁跳楼栋”是 [BuildingLocator] 的职责。
 */
class SystemLocationSource(
    private val context: Context,
) : LocationSource {

    override val channelName: String = "系统定位"

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override fun isAvailable(): Boolean = availability() == LocationAvailability.Available

    override fun availability(): LocationAvailability {
        if (!hasPermission()) return LocationAvailability.PermissionDenied
        val manager = locationManager ?: return LocationAvailability.NoProvider

        val enabled = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            manager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        if (!enabled) return LocationAvailability.ServiceDisabled

        // 有权限、服务也开着，但没有任何可用 provider：仍然取不到点
        val hasProvider = PROVIDER_PRIORITY.any { manager.allProviders.contains(it) }
        return if (hasProvider) LocationAvailability.Available else LocationAvailability.NoProvider
    }

    private fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    override fun locationUpdates(): Flow<LocationUpdate> {
        val manager = locationManager
        val current = availability()
        if (manager == null || current != LocationAvailability.Available) {
            // 一开始就拿不到点：明确把原因发出去，而不是静默结束
            return flowOf(LocationUpdate.Unavailable(current))
        }

        return callbackFlow {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    trySend(
                        LocationUpdate.Fix(
                            RawLocationFix(
                                point = LngLat(lng = location.longitude, lat = location.latitude),
                                accuracyMeters = if (location.hasAccuracy()) {
                                    location.accuracy.toDouble()
                                } else {
                                    null
                                },
                                provider = location.provider,
                            ),
                        ),
                    )
                }

                @Deprecated("Android 29 起不再回调")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }

            // 门限放宽一些：校园场景只关心“落在哪栋楼的轮廓里”，不需要厘米级精度。
            val minTimeMs = 2_000L
            val minDistanceM = 3f

            var registered = false
            for (provider in PROVIDER_PRIORITY) {
                if (!manager.allProviders.contains(provider)) continue
                runCatching {
                    manager.requestLocationUpdates(
                        provider,
                        minTimeMs,
                        minDistanceM,
                        listener,
                        Looper.getMainLooper(),
                    )
                }.onSuccess { registered = true }
            }

            if (!registered) {
                // 一个 provider 都没注册上：说清原因再结束
                trySend(LocationUpdate.Unavailable(LocationAvailability.NoProvider))
                close()
            }

            awaitClose {
                runCatching { manager.removeUpdates(listener) }
            }
        }
    }

    private companion object {
        /** 优先用 GPS，其次网络定位；两个都注册，谁先给出可信点就用谁。 */
        val PROVIDER_PRIORITY = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
    }
}
