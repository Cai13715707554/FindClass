package com.school.nav.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 气压来源抽象。
 *
 * 抽成接口是为了让 `NavViewModel` 不直接依赖 Android 的 `SensorManager`：
 * 单测可以注入「没有气压计」或「固定气压」的替身，不需要真机传感器。
 */
interface PressureSource {

    /** 设备是否具备气压计。 */
    val hasSensor: Boolean

    /** 气压样本流（单位 hPa）。没有传感器时返回空流。 */
    fun pressureUpdates(): Flow<Float>
}

/**
 * 气压计读取。
 *
 * 只负责把 TYPE_PRESSURE 的原始值变成一条 Flow，滤波与楼层判断在
 * core 模块的 `FloorMatcher` 里（纯 Kotlin，可单测）。
 *
 * 没有气压计的设备返回空流，上层据此提示用户手动选择楼层。
 */
class BarometricAltimeter(context: Context) : PressureSource {

    private val sensorManager: SensorManager? =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val pressureSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    /** 设备是否具备气压计。 */
    override val hasSensor: Boolean get() = pressureSensor != null

    /**
     * 气压样本流（单位 hPa）。
     * 没有传感器时返回空流；collect 取消即注销监听。
     */
    override fun pressureUpdates(): Flow<Float> {
        val manager = sensorManager ?: return emptyFlow()
        val sensor = pressureSensor ?: return emptyFlow()

        return callbackFlow {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val value = event.values.firstOrNull() ?: return
                    if (value > 0f) trySend(value)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            val registered = manager.registerListener(
                listener,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
            )
            if (!registered) close()

            awaitClose { manager.unregisterListener(listener) }
        }
    }
}
