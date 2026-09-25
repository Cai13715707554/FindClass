package com.school.nav.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.school.nav.core.model.LngLat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 高德定位 SDK 接入点。
 *
 * 技术方案要求用高德定位，但高德 SDK 的 aar 只在自己的 Maven 仓库分发，
 * 网络受限的环境里不一定拉得到。为了不让“一个 SDK 拉不下来”卡死整个工程，
 * 这里用**反射**接入：
 *
 *  - 未引入高德依赖时 [isAvailable] 返回 false，App 自动落回系统定位，功能完整；
 *  - 引入依赖后（见 README 的接入步骤）本类不需要改一行代码即可生效。
 *
 * 反射只在初始化时做一次类/方法查找，逐点回调直接使用查好的句柄。
 */
class AmapLocationSource(
    context: Context,
    private val apiKey: String,
) : LocationSource {

    private val appContext: Context = context.applicationContext

    override val channelName: String = "高德定位"

    private val handle: AmapHandle? by lazy { AmapHandle.load(appContext, apiKey) }

    override fun isAvailable(): Boolean = availability() == LocationAvailability.Available

    override fun availability(): LocationAvailability {
        if (!hasLocationPermission()) return LocationAvailability.PermissionDenied
        // 没配 Key、或反射初始化失败（SDK 未引入 / 版本不匹配）
        if (apiKey.isBlank() || handle == null) return LocationAvailability.NoProvider
        return LocationAvailability.Available
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    override fun locationUpdates(): Flow<LocationUpdate> {
        val handle = handle ?: return flowOf(
            LocationUpdate.Unavailable(LocationAvailability.NoProvider),
        )
        return callbackFlow {
            val client = handle.newClient(appContext, apiKey) ?: run {
                trySend(LocationUpdate.Unavailable(LocationAvailability.NoProvider))
                close()
                return@callbackFlow
            }

            val listener = handle.newListener { lat, lng, accuracy ->
                trySend(
                    LocationUpdate.Fix(
                        RawLocationFix(
                            point = LngLat(lng = lng, lat = lat),
                            accuracyMeters = accuracy,
                            provider = "amap",
                        ),
                    ),
                )
            }

            handle.attach(client, listener)
            if (!handle.start(client)) {
                // 启动失败通常是 Key 无效、配额用尽或网络不可达
                trySend(LocationUpdate.Unavailable(LocationAvailability.NoProvider))
                close()
                return@callbackFlow
            }

            awaitClose {
                handle.stop(client)
                handle.detach(client, listener)
            }
        }
    }

    /**
     * 反射句柄。类型全部以字符串类名出现，因此没有高德依赖时本文件也能编译。
     */
    private class AmapHandle(
        private val clientConstructor: Constructor<*>,
        private val setApiKey: Method?,
        private val setLocationOption: Method,
        private val startLocation: Method,
        private val stopLocation: Method,
        private val setLocationListener: Method,
        private val unregisterListener: Method?,
        private val optionConstructor: Constructor<*>,
        private val setOnceLocation: Method,
        private val getLatitude: Method,
        private val getLongitude: Method,
        private val getAccuracy: Method,
        private val listenerClass: Class<*>,
    ) {

        fun newClient(context: Context, apiKey: String): Any? = runCatching {
            clientConstructor.newInstance(context).also { client ->
                setApiKey?.invoke(client, apiKey)
            }
        }.getOrNull()

        fun start(client: Any): Boolean = runCatching {
            val option = optionConstructor.newInstance()
            // 连续定位：室内导航需要持续取点，once 模式不够用
            runCatching { setOnceLocation.invoke(option, false) }
            setLocationOption.invoke(client, option)
            startLocation.invoke(client)
            true
        }.getOrDefault(false)

        fun stop(client: Any) {
            runCatching { stopLocation.invoke(client) }
        }

        fun attach(client: Any, listener: Any) {
            runCatching { setLocationListener.invoke(client, listener) }
        }

        fun detach(client: Any, listener: Any) {
            unregisterListener?.let { method -> runCatching { method.invoke(client, listener) } }
        }

        /** 用动态代理实现高德定位回调接口，避免编译期依赖接口定义。 */
        fun newListener(onFix: (lat: Double, lng: Double, accuracy: Double?) -> Unit): Any =
            Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { _, method, args ->
                if (method.name == "onLocationChanged") {
                    val location = args?.firstOrNull()
                    if (location != null) {
                        val lat = getLatitude.invoke(location) as? Double
                        val lng = getLongitude.invoke(location) as? Double
                        if (lat != null && lng != null && !(lat == 0.0 && lng == 0.0)) {
                            val accuracy = runCatching {
                                (getAccuracy.invoke(location) as? Float)?.toDouble()
                            }.getOrNull()
                            onFix(lat, lng, accuracy)
                        }
                    }
                }
                null
            }

        companion object {
            fun load(context: Context, apiKey: String): AmapHandle? = runCatching {
                val clientClass = Class.forName("com.amap.api.location.AMapLocationClient")
                val optionClass = Class.forName("com.amap.api.location.AMapLocationClientOption")
                val listenerClass = Class.forName("com.amap.api.location.AMapLocationListener")
                val locationClass = Class.forName("com.amap.api.location.AMapLocation")

                // 隐私合规：必须在任何定位调用之前声明，否则高德 SDK 不返回定位结果
                clientClass.getMethod(
                    "updatePrivacyShow",
                    Context::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                ).invoke(null, context, true, true)
                clientClass.getMethod(
                    "updatePrivacyAgree",
                    Context::class.java,
                    Boolean::class.javaPrimitiveType,
                ).invoke(null, context, true)

                AmapHandle(
                    clientConstructor = clientClass.getConstructor(Context::class.java),
                    setApiKey = runCatching {
                        clientClass.getMethod("setApiKey", String::class.java)
                    }.getOrNull(),
                    setLocationOption = clientClass.getMethod("setLocationOption", optionClass),
                    startLocation = clientClass.getMethod("startLocation"),
                    stopLocation = clientClass.getMethod("stopLocation"),
                    setLocationListener = clientClass.getMethod(
                        "setLocationListener",
                        listenerClass,
                    ),
                    unregisterListener = runCatching {
                        clientClass.getMethod("unRegisterLocationListener", listenerClass)
                    }.getOrNull(),
                    optionConstructor = optionClass.getConstructor(),
                    setOnceLocation = optionClass.getMethod(
                        "setOnceLocation",
                        Boolean::class.javaPrimitiveType,
                    ),
                    getLatitude = locationClass.getMethod("getLatitude"),
                    getLongitude = locationClass.getMethod("getLongitude"),
                    getAccuracy = locationClass.getMethod("getAccuracy"),
                    listenerClass = listenerClass,
                )
            }.getOrNull()
        }
    }
}
