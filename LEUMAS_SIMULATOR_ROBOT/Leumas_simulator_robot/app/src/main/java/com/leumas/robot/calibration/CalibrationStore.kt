package com.leumas.robot.calibration

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.calibrationDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "leumas_calibration")

/**
 * Persists calibration locally.
 *
 * Stores normalised edge coordinates, never pixels, so the layout survives a different screen
 * (spec §6). Entirely local — the app requires no network for anything (spec §29), and calibration
 * must outlive app restarts, BLE disconnects and configuration changes (spec §25).
 *
 * A corrupt or older payload falls back to defaults rather than throwing: losing calibration is
 * recoverable in a minute, a crash loop at startup is not.
 */
class CalibrationStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    val calibration: Flow<Calibration> = context.calibrationDataStore.data.map { prefs ->
        val raw = prefs[KEY] ?: return@map Calibration.DEFAULT
        runCatching { json.decodeFromString<Calibration>(raw) }
            .getOrElse {
                Log.w(TAG, "stored calibration was unreadable; falling back to defaults", it)
                Calibration.DEFAULT
            }
    }

    suspend fun save(calibration: Calibration) {
        val encoded = runCatching { json.encodeToString(Calibration.serializer(), calibration) }.getOrNull() ?: return
        context.calibrationDataStore.edit { it[KEY] = encoded }
    }

    suspend fun resetToDefaults() {
        save(Calibration.DEFAULT)
    }

    private companion object {
        const val TAG = "CalibrationStore"
        val KEY = stringPreferencesKey("calibration_json")
    }
}
