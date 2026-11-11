package com.example.udpmockgps.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.udpmockgps.R
import com.example.udpmockgps.data.DataFormat
import com.example.udpmockgps.data.GpsFix
import com.example.udpmockgps.data.ServiceStatus
import com.example.udpmockgps.data.SignalState
import com.example.udpmockgps.mock.MockAppState
import com.example.udpmockgps.service.MockGpsService
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private fun formatTime(ms: Long): String =
    timeFormatter.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun requiredPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

private fun Context.isGranted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.hasLocationPermission() =
    isGranted(Manifest.permission.ACCESS_FINE_LOCATION) || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

/** @return a hint for the user if developer options could not be opened directly, else null. */
private fun Context.openDeveloperOptions(): String? {
    return try {
        startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        null
    } catch (e: Exception) {
        try {
            startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            "Developer options are not enabled. Tap \"Build number\" 7 times here, then go to " +
                "Developer options > Select mock location app."
        } catch (e2: ActivityNotFoundException) {
            "Open Settings > System > Developer options > Select mock location app manually."
        }
    }
}

private fun Context.requestBatteryExemption() {
    try {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData("package:$packageName".toUri()),
        )
    } catch (e: Exception) {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e2: ActivityNotFoundException) {
            // Nothing else we can do.
        }
    }
}

private fun Context.openAppSettings() {
    try {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    } catch (e: ActivityNotFoundException) {
        // Ignore.
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val form by vm.form.collectAsStateWithLifecycle()
    val env by vm.env.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(1000)
            value = System.currentTimeMillis()
        }
    }
    var hint by remember { mutableStateOf<String?>(null) }
    var showAppSettings by remember { mutableStateOf(false) }
    var batteryPrompted by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshEnvironment() }

    val startService: () -> Unit = {
        vm.commitSettings {
            MockGpsService.start(context)
            if (!env.batteryExempt && !batteryPrompted) {
                batteryPrompted = true
                context.requestBatteryExemption()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (context.hasLocationPermission()) {
            hint = null
            showAppSettings = false
            startService()
        } else {
            hint = context.getString(R.string.hint_location_denied)
            showAppSettings = true
        }
    }

    val onStartClicked: () -> Unit = {
        val missing = requiredPermissions().filter { !context.isGranted(it) }
        if (missing.isEmpty()) startService() else permissionLauncher.launch(missing.toTypedArray())
    }

    Scaffold(topBar = { TopAppBar(title = { Text("UDP Mock GPS") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConfigCard(
                form = form,
                running = status.running,
                onPort = vm::onPortChange,
                onTimeout = vm::onTimeoutChange,
                onFormat = vm::onFormatChange,
                onAutoStart = vm::onAutoStartChange,
                onStart = onStartClicked,
                onStop = { MockGpsService.stop(context) },
            )

            hint?.let {
                InfoCard(it) {
                    if (showAppSettings) {
                        OutlinedButton(onClick = { context.openAppSettings() }) { Text("Open app settings") }
                    }
                }
            }

            MockLocationCard(
                state = env.mockState,
                onOpenDeveloperOptions = { hint = context.openDeveloperOptions(); showAppSettings = false },
                onRecheck = vm::refreshEnvironment,
            )

            if (!env.batteryExempt) {
                InfoCard("Battery optimization is active for this app. Android may stop the service or throttle the network in the background.") {
                    OutlinedButton(onClick = { context.requestBatteryExemption() }) {
                        Text("Disable battery optimization")
                    }
                }
            }

            StatusCard(status, now)
            LogCard(status)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigCard(
    form: FormState,
    running: Boolean,
    onPort: (String) -> Unit,
    onTimeout: (String) -> Unit,
    onFormat: (DataFormat) -> Unit,
    onAutoStart: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Configuration", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = form.portText,
                onValueChange = onPort,
                label = { Text("UDP port") },
                singleLine = true,
                enabled = !running,
                isError = form.portError != null,
                supportingText = form.portError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded && !running,
                onExpandedChange = { if (!running) expanded = it },
            ) {
                OutlinedTextField(
                    value = form.format.label,
                    onValueChange = {},
                    readOnly = true,
                    enabled = !running,
                    label = { Text("Data format") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DataFormat.entries.forEach { f ->
                        DropdownMenuItem(
                            text = { Text(f.label) },
                            onClick = { onFormat(f); expanded = false },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = form.timeoutText,
                onValueChange = onTimeout,
                label = { Text("Signal-lost timeout (seconds)") },
                singleLine = true,
                enabled = !running,
                isError = form.timeoutError != null,
                supportingText = form.timeoutError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Start on boot")
                    Text(
                        "Best effort; Android may block background location services.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = form.autoStart, onCheckedChange = onAutoStart)
            }

            if (running) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Stop") }
            } else {
                Button(
                    onClick = onStart,
                    enabled = form.loaded && form.toSettings() != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start") }
            }
        }
    }
}

@Composable
private fun MockLocationCard(
    state: MockAppState,
    onOpenDeveloperOptions: () -> Unit,
    onRecheck: () -> Unit,
) {
    val ok = state == MockAppState.SELECTED
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state == MockAppState.NOT_SELECTED) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mock location app", style = MaterialTheme.typography.titleMedium)
            Text(
                when (state) {
                    MockAppState.SELECTED -> "✓ UDP Mock GPS is selected as the mock location app."
                    MockAppState.NOT_SELECTED ->
                        "✗ Not selected. In Developer options, tap \"Select mock location app\" and choose UDP Mock GPS."
                    MockAppState.UNKNOWN -> "Could not determine whether this app is the mock location app."
                },
                fontWeight = if (ok) FontWeight.Medium else FontWeight.Normal,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenDeveloperOptions) { Text("Open Developer Options") }
                OutlinedButton(onClick = onRecheck) { Text("Re-check") }
            }
        }
    }
}

@Composable
private fun InfoCard(text: String, actions: @Composable () -> Unit = {}) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text)
            actions()
        }
    }
}

@Composable
private fun StatusCard(status: ServiceStatus, now: Long) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Status", style = MaterialTheme.typography.titleMedium)

            val (signalText, signalColor) = when (status.signal) {
                SignalState.STOPPED -> "Stopped" to MaterialTheme.colorScheme.onSurfaceVariant
                SignalState.WAITING -> "Waiting for data…" to MaterialTheme.colorScheme.onSurfaceVariant
                SignalState.ACTIVE -> "Receiving" to Color(0xFF2E7D32)
                SignalState.LOST -> "SIGNAL LOST (no valid fix for ${status.timeoutSeconds} s)" to MaterialTheme.colorScheme.error
            }
            StatRow("Service", if (status.running) "Running" else "Stopped")
            StatRow("Signal", signalText, signalColor)
            status.port?.let { StatRow("Listening", "UDP 0.0.0.0:$it · ${status.format?.label ?: ""}") }
            StatRow(
                "Last packet",
                status.lastPacketTimeMs?.let { "${formatTime(it)} (${((now - it) / 1000).coerceAtLeast(0)} s ago)" } ?: "—",
            )
            StatRow("Packets received", status.packetsReceived.toString())
            StatRow("Fixes parsed / injected", "${status.fixesReceived} / ${status.fixesInjected}")
            StatRow("Parse errors", status.parseErrors.toString())
            StatRow(
                "Mock providers",
                "GPS+Network: " + (if (status.mockProvidersActive) "active" else "inactive") +
                    " · Fused: " + (if (status.fusedActive) "active" else "inactive"),
            )

            val fix: GpsFix? = status.lastFix
            if (fix != null) {
                StatRow("Last lat / lon", String.format(Locale.US, "%.6f, %.6f", fix.latitude, fix.longitude))
                StatRow("Altitude", fix.altitude?.let { String.format(Locale.US, "%.1f m", it) } ?: "—")
                StatRow("Speed", fix.speedMps?.let { String.format(Locale.US, "%.2f m/s", it) } ?: "—")
                StatRow("Bearing", fix.bearingDeg?.let { String.format(Locale.US, "%.1f°", it) } ?: "—")
                StatRow("Accuracy", fix.accuracyM?.let { String.format(Locale.US, "%.1f m", it) } ?: "—")
            } else {
                StatRow("Last fix", "—")
            }

            status.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            status.lastError?.let {
                Text("Last parse error: $it", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(0.42f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            modifier = Modifier.weight(0.58f),
            color = valueColor,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LogCard(status: ServiceStatus) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Last ${status.recentPackets.size} raw packets (max 20)", style = MaterialTheme.typography.titleMedium)
            if (status.recentPackets.isEmpty()) {
                Text("No packets received yet.", style = MaterialTheme.typography.bodySmall)
            }
            status.recentPackets.forEach { p ->
                Text(
                    "${formatTime(p.timeMs)}  ${p.source}\n${p.text}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}
