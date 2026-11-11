package com.example.udpmockgps.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.udpmockgps.MainActivity
import com.example.udpmockgps.R
import com.example.udpmockgps.data.AppSettings
import com.example.udpmockgps.data.DataFormat
import com.example.udpmockgps.data.GpsFix
import com.example.udpmockgps.data.PacketLogEntry
import com.example.udpmockgps.data.ServiceStatus
import com.example.udpmockgps.data.ServiceStatusHolder
import com.example.udpmockgps.data.SettingsRepository
import com.example.udpmockgps.data.SignalState
import com.example.udpmockgps.mock.MockLocationInjector
import com.example.udpmockgps.parser.PacketParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

class MockGpsService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()

    private lateinit var injector: MockLocationInjector
    private var workJob: Job? = null
    private var notificationJob: Job? = null

    @Volatile private var socket: DatagramSocket? = null
    private var wifiLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var started = false
    @Volatile private var cleanedUp = false
    @Volatile private var lastFixElapsedMs = 0L

    // NMEA sends speed/bearing (RMC) and altitude/accuracy (GGA) in separate sentences, possibly separate packets.
    private var carryAltitude: Double? = null
    private var carrySpeed: Float? = null
    private var carryBearing: Float? = null
    private var carryAccuracy: Float? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        injector = MockLocationInjector(applicationContext)
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(getString(R.string.notif_starting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: Exception) {
            ServiceStatusHolder.set(
                ServiceStatus(message = "Cannot start foreground service: ${e.message ?: e.javaClass.simpleName}"),
            )
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        ServiceStatusHolder.set(ServiceStatus(running = true, signal = SignalState.WAITING))

        val nm = getSystemService(NotificationManager::class.java)
        notificationJob = serviceScope.launch {
            ServiceStatusHolder.status
                .map { notificationText(it) }
                .distinctUntilChanged()
                .collect { text -> nm.notify(NOTIFICATION_ID, buildNotification(text)) }
        }
        workJob = serviceScope.launch {
            try {
                run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failAndStop("Service error: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanup()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- lifecycle

    private suspend fun run() {
        val settings = SettingsRepository(applicationContext).settings.first()
        ServiceStatusHolder.update {
            it.copy(port = settings.port, format = settings.format, timeoutSeconds = settings.timeoutSeconds)
        }

        val sock: DatagramSocket = try {
            synchronized(stateLock) {
                if (cleanedUp) return
                acquireLocks()
                openSocket(settings.port).also { socket = it }
            }
        } catch (e: Exception) {
            failAndStop("Cannot listen on UDP port ${settings.port}: ${e.message ?: e.javaClass.simpleName}")
            return
        }

        injector.prepare()
        publishInjectorState()

        coroutineScope {
            val watchdog = launch { watchdogLoop(settings.timeoutSeconds * 1000L) }
            try {
                withContext(Dispatchers.IO) { receiveLoop(sock, settings) }
            } finally {
                watchdog.cancel()
            }
        }
    }

    private fun openSocket(port: Int): DatagramSocket {
        val s = DatagramSocket(null as SocketAddress?)
        try {
            s.reuseAddress = true
            s.broadcast = true
            s.soTimeout = SOCKET_TIMEOUT_MS
            s.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
        } catch (e: Exception) {
            s.close()
            throw e
        }
        return s
    }

    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wifi?.createMulticastLock("udpmockgps:multicast")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("WakelockTimeout") // Held for the lifetime of the foreground service; released in cleanup().
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "udpmockgps:wakelock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            // Already released.
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            // Already released.
        }
        wifiLock = null
        wakeLock = null
    }

    /** Idempotent. Releases the socket, test providers, fused mock mode and locks. */
    private fun cleanup() {
        synchronized(stateLock) {
            if (cleanedUp) return
            cleanedUp = true
            notificationJob?.cancel()
            try {
                socket?.close()
            } catch (e: Exception) {
                // Ignore.
            }
            socket = null
            workJob?.cancel()
            injector.stop()
            releaseLocks()
            ServiceStatusHolder.update {
                it.copy(running = false, signal = SignalState.STOPPED, mockProvidersActive = false, fusedActive = false)
            }
        }
    }

    private fun shutdown() {
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failAndStop(message: String) {
        ServiceStatusHolder.update { it.copy(message = message) }
        mainHandler.post { shutdown() }
    }

    // ---------------------------------------------------------------- receiving

    private suspend fun receiveLoop(sock: DatagramSocket, settings: AppSettings) {
        val buffer = ByteArray(MAX_PACKET_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        while (currentCoroutineContext().isActive) {
            packet.length = buffer.size
            try {
                sock.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: SocketException) {
                if (sock.isClosed) break
                ServiceStatusHolder.update { it.copy(lastError = "Socket error: ${e.message}") }
                delay(250)
                continue
            }
            handlePacket(packet, settings)
        }
    }

    private fun handlePacket(packet: DatagramPacket, settings: AppSettings) {
        val now = System.currentTimeMillis()
        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val source = packet.address?.hostAddress ?: "?"
        val result = PacketParser.parse(text, settings.format)

        val logText = text.trim().replace(Regex("\\s*[\\r\\n]+\\s*"), " ⏎ ").take(LOG_TEXT_MAX)
        val entry = PacketLogEntry(now, source, logText)
        ServiceStatusHolder.update { s ->
            s.copy(
                packetsReceived = s.packetsReceived + 1,
                lastPacketTimeMs = now,
                parseErrors = s.parseErrors + result.errors.size,
                lastError = result.errors.lastOrNull() ?: s.lastError,
                recentPackets = (listOf(entry) + s.recentPackets).take(LOG_SIZE),
            )
        }

        for (raw in result.fixes) {
            val fix = applyCarryForward(raw, settings.format)
            lastFixElapsedMs = SystemClock.elapsedRealtime()
            val delivered = injector.inject(fix)
            ServiceStatusHolder.update { s ->
                s.copy(
                    signal = SignalState.ACTIVE,
                    lastFix = fix,
                    lastFixTimeMs = now,
                    fixesReceived = s.fixesReceived + 1,
                    fixesInjected = s.fixesInjected + if (delivered) 1 else 0,
                    mockProvidersActive = injector.platformReady,
                    fusedActive = injector.fusedReady,
                    message = injector.problem,
                )
            }
        }
    }

    private fun applyCarryForward(fix: GpsFix, format: DataFormat): GpsFix {
        if (format != DataFormat.NMEA) return fix
        fix.altitude?.let { carryAltitude = it }
        fix.speedMps?.let { carrySpeed = it }
        fix.bearingDeg?.let { carryBearing = it }
        fix.accuracyM?.let { carryAccuracy = it }
        return fix.copy(
            altitude = fix.altitude ?: carryAltitude,
            speedMps = fix.speedMps ?: carrySpeed,
            bearingDeg = fix.bearingDeg ?: carryBearing,
            accuracyM = fix.accuracyM ?: carryAccuracy,
        )
    }

    /** Detects signal loss and periodically retries registering mock providers. */
    private suspend fun watchdogLoop(timeoutMs: Long) {
        var lastPrepare = SystemClock.elapsedRealtime()
        while (currentCoroutineContext().isActive) {
            delay(WATCHDOG_INTERVAL_MS)
            val now = SystemClock.elapsedRealtime()
            val last = lastFixElapsedMs
            if (last != 0L && now - last > timeoutMs) {
                ServiceStatusHolder.update {
                    if (it.signal == SignalState.ACTIVE) it.copy(signal = SignalState.LOST) else it
                }
            }
            if (now - lastPrepare >= PREPARE_RETRY_MS) {
                lastPrepare = now
                injector.prepare()
                publishInjectorState()
            }
        }
    }

    private fun publishInjectorState() {
        ServiceStatusHolder.update {
            it.copy(
                mockProvidersActive = injector.platformReady,
                fusedActive = injector.fusedReady,
                message = injector.problem,
            )
        }
    }

    // ---------------------------------------------------------------- notification

    private fun notificationText(s: ServiceStatus): String {
        val port = s.port ?: return getString(R.string.notif_starting)
        val problem = s.message?.takeIf { !s.mockProvidersActive }
        return when (s.signal) {
            SignalState.LOST -> getString(R.string.notif_lost, s.timeoutSeconds)
            SignalState.ACTIVE -> problem ?: getString(R.string.notif_active, port, s.format?.label ?: "")
            SignalState.WAITING -> problem ?: getString(R.string.notif_waiting, port)
            SignalState.STOPPED -> getString(R.string.notif_starting)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MockGpsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gps)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.example.udpmockgps.action.STOP"
        private const val CHANNEL_ID = "udp_mock_gps_status"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_PACKET_BYTES = 8192
        private const val SOCKET_TIMEOUT_MS = 1000
        private const val WATCHDOG_INTERVAL_MS = 500L
        private const val PREPARE_RETRY_MS = 2000L
        private const val LOG_SIZE = 20
        private const val LOG_TEXT_MAX = 400

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MockGpsService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MockGpsService::class.java))
        }
    }
}
