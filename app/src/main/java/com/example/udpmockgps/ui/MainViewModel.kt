package com.example.udpmockgps.ui

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.udpmockgps.data.AppSettings
import com.example.udpmockgps.data.DataFormat
import com.example.udpmockgps.data.ServiceStatusHolder
import com.example.udpmockgps.data.SettingsRepository
import com.example.udpmockgps.mock.MockAppState
import com.example.udpmockgps.mock.MockLocationChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FormState(
    val portText: String = AppSettings.DEFAULT_PORT.toString(),
    val timeoutText: String = AppSettings.DEFAULT_TIMEOUT_SECONDS.toString(),
    val format: DataFormat = DataFormat.NMEA,
    val autoStart: Boolean = false,
    val loaded: Boolean = false,
) {
    val port: Int?
        get() = portText.toIntOrNull()?.takeIf { it in AppSettings.MIN_PORT..AppSettings.MAX_PORT }
    val timeoutSeconds: Int?
        get() = timeoutText.toIntOrNull()?.takeIf { it in AppSettings.MIN_TIMEOUT_SECONDS..AppSettings.MAX_TIMEOUT_SECONDS }
    val portError: String?
        get() = if (port == null) "Enter a port from ${AppSettings.MIN_PORT} to ${AppSettings.MAX_PORT}" else null
    val timeoutError: String?
        get() = if (timeoutSeconds == null) {
            "Enter ${AppSettings.MIN_TIMEOUT_SECONDS}-${AppSettings.MAX_TIMEOUT_SECONDS} seconds"
        } else null

    fun toSettings(): AppSettings? {
        val p = port ?: return null
        val t = timeoutSeconds ?: return null
        return AppSettings(p, format, t, autoStart)
    }
}

data class EnvState(
    val mockState: MockAppState = MockAppState.UNKNOWN,
    val batteryExempt: Boolean = true,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SettingsRepository(application)

    private val _form = MutableStateFlow(FormState())
    val form: StateFlow<FormState> = _form.asStateFlow()

    private val _env = MutableStateFlow(EnvState())
    val env: StateFlow<EnvState> = _env.asStateFlow()

    /** The single Service -> UI status flow. */
    val status = ServiceStatusHolder.status

    init {
        viewModelScope.launch {
            val s = repo.settings.first()
            _form.value = FormState(
                portText = s.port.toString(),
                timeoutText = s.timeoutSeconds.toString(),
                format = s.format,
                autoStart = s.autoStartOnBoot,
                loaded = true,
            )
        }
    }

    fun onPortChange(text: String) = edit { it.copy(portText = text.filter(Char::isDigit).take(5)) }
    fun onTimeoutChange(text: String) = edit { it.copy(timeoutText = text.filter(Char::isDigit).take(4)) }
    fun onFormatChange(format: DataFormat) = edit { it.copy(format = format) }
    fun onAutoStartChange(enabled: Boolean) = edit { it.copy(autoStart = enabled) }

    private fun edit(transform: (FormState) -> FormState) {
        _form.update(transform)
        val current = _form.value
        if (!current.loaded) return
        current.toSettings()?.let { settings -> viewModelScope.launch { repo.save(settings) } }
    }

    /** Persists the settings and then runs [onSaved] (used to start the service). No-op if the form is invalid. */
    fun commitSettings(onSaved: () -> Unit) {
        val settings = _form.value.toSettings() ?: return
        viewModelScope.launch {
            repo.save(settings)
            onSaved()
        }
    }

    fun refreshEnvironment() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val mock = withContext(Dispatchers.Default) { MockLocationChecker.check(app) }
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            _env.value = EnvState(mock, pm.isIgnoringBatteryOptimizations(app.packageName))
        }
    }
}
