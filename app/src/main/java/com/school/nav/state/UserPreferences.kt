package com.school.nav.state

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 用户手动修正过的位置。
 *
 * id 只用于内部关联，不展示。全部为 null 表示“没有手动修正，以自动定位为准”。
 */
data class ManualPosition(
    val buildingId: String? = null,
    val floorId: String? = null,
    val elementId: String? = null,
) {
    val isEmpty: Boolean
        get() = buildingId == null && floorId == null && elementId == null

    companion object {
        val None = ManualPosition()
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nav_prefs")

/**
 * 手动位置修正的存储抽象。
 *
 * 抽成接口是为了让 `NavViewModel` 的单测不必拉起 DataStore / Android Context，
 * 直接注入内存实现即可。
 */
interface ManualPositionStore {

    /** 持久化的手动修正。 */
    val manualPosition: Flow<ManualPosition>

    /** 是否曾经手动改过位置（用于决定要不要立即覆盖自动定位结果）。 */
    val hasManualOverride: Flow<Boolean>

    suspend fun current(): ManualPosition

    suspend fun saveManualPosition(position: ManualPosition)

    suspend fun clearManualPosition()
}

/**
 * 用户偏好持久化（DataStore 实现）。
 *
 * 技术方案指定 DataStore。这里只存两类东西：
 *  1. 用户手动修正的位置（楼栋 / 楼层 / 元素三级）；
 *  2. 最近一次使用的楼栋，用于下次启动时更快给出合理的默认位置。
 *
 * 气压基准不持久化 —— 它依赖当时的天气与气压，隔一段时间就该重新校准。
 */
class UserPreferences(private val context: Context) : ManualPositionStore {

    override val manualPosition: Flow<ManualPosition> = context.dataStore.data.map { prefs ->
        ManualPosition(
            buildingId = prefs[KEY_BUILDING],
            floorId = prefs[KEY_FLOOR],
            elementId = prefs[KEY_ELEMENT],
        )
    }

    /** 是否曾经手动改过位置（用于决定要不要立即覆盖自动定位结果）。 */
    override val hasManualOverride: Flow<Boolean> = manualPosition.map { !it.isEmpty }

    override suspend fun current(): ManualPosition = manualPosition.first()

    override suspend fun saveManualPosition(position: ManualPosition) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BUILDING] = position.buildingId.orEmpty()
            prefs[KEY_FLOOR] = position.floorId.orEmpty()
            prefs[KEY_ELEMENT] = position.elementId.orEmpty()
        }
    }

    override suspend fun clearManualPosition() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_BUILDING)
            prefs.remove(KEY_FLOOR)
            prefs.remove(KEY_ELEMENT)
        }
    }

    private companion object {
        val KEY_BUILDING = stringPreferencesKey("manual_building_id")
        val KEY_FLOOR = stringPreferencesKey("manual_floor_id")
        val KEY_ELEMENT = stringPreferencesKey("manual_element_id")
    }
}
