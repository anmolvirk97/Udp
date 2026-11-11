package com.example.udpmockgps.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "udp_mock_gps_settings")

class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    private object Keys {
        val PORT = intPreferencesKey("port")
        val FORMAT = stringPreferencesKey("format")
        val TIMEOUT = intPreferencesKey("timeout_seconds")
        val AUTO_START = booleanPreferencesKey("auto_start_on_boot")
    }

    val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            AppSettings(
                port = (p[Keys.PORT] ?: AppSettings.DEFAULT_PORT)
                    .coerceIn(AppSettings.MIN_PORT, AppSettings.MAX_PORT),
                format = DataFormat.fromName(p[Keys.FORMAT]),
                timeoutSeconds = (p[Keys.TIMEOUT] ?: AppSettings.DEFAULT_TIMEOUT_SECONDS)
                    .coerceIn(AppSettings.MIN_TIMEOUT_SECONDS, AppSettings.MAX_TIMEOUT_SECONDS),
                autoStartOnBoot = p[Keys.AUTO_START] ?: false,
            )
        }

    suspend fun save(settings: AppSettings) {
        dataStore.edit { p ->
            p[Keys.PORT] = settings.port
            p[Keys.FORMAT] = settings.format.name
            p[Keys.TIMEOUT] = settings.timeoutSeconds
            p[Keys.AUTO_START] = settings.autoStartOnBoot
        }
    }
}
