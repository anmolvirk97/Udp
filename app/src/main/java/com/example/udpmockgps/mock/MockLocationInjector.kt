package com.example.udpmockgps.mock

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import com.example.udpmockgps.data.GpsFix
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

const val NOT_MOCK_APP_MESSAGE =
    "UDP Mock GPS is not selected as the mock location app. " +
        "Open Developer options > \"Select mock location app\" and choose UDP Mock GPS."

/** Registers a test provider with sensible properties. Throws SecurityException if we are not the mock app. */
internal fun LocationManager.addMockProvider(name: String, isNetwork: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val properties = ProviderProperties.Builder()
            .setHasNetworkRequirement(isNetwork)
            .setHasSatelliteRequirement(!isNetwork)
            .setHasCellRequirement(false)
            .setHasMonetaryCost(false)
            .setHasAltitudeSupport(true)
            .setHasSpeedSupport(true)
            .setHasBearingSupport(true)
            .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
            .setAccuracy(if (isNetwork) ProviderProperties.ACCURACY_COARSE else ProviderProperties.ACCURACY_FINE)
            .build()
        addTestProvider(name, properties)
    } else {
        addTestProviderLegacy(name, isNetwork)
    }
}

// The ProviderProperties overload only exists on API 31+; this is the only option below that.
@Suppress("DEPRECATION")
private fun LocationManager.addTestProviderLegacy(name: String, isNetwork: Boolean) {
    addTestProvider(
        name,
        isNetwork,       // requiresNetwork
        !isNetwork,      // requiresSatellite
        false,           // requiresCell
        false,           // hasMonetaryCost
        true,            // supportsAltitude
        true,            // supportsSpeed
        true,            // supportsBearing
        Criteria.POWER_LOW,
        if (isNetwork) Criteria.ACCURACY_COARSE else Criteria.ACCURACY_FINE,
    )
}

/**
 * Owns the GPS + NETWORK test providers and the Fused mock mode.
 * Thread-safe; [prepare] and [inject] may be called from different coroutines.
 */
class MockLocationInjector(context: Context) {

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fused: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(appContext)
    }
    private val lock = Any()

    @Volatile private var closed = false
    @Volatile private var fusedUnavailable = false

    @Volatile var platformReady = false
        private set
    @Volatile var fusedReady = false
        private set
    @Volatile var platformError: String? = null
        private set
    @Volatile var fusedWarning: String? = null
        private set

    /** The most relevant user-visible problem, or null when everything works. */
    val problem: String? get() = platformError ?: fusedWarning

    /** Idempotent: registers whatever is not registered yet. Cheap when everything is ready. */
    suspend fun prepare() {
        if (closed) return
        if (!platformReady) preparePlatform()
        if (platformReady && !fusedReady && !fusedUnavailable) prepareFused()
    }

    private fun preparePlatform() {
        synchronized(lock) {
            if (closed || platformReady) return
            try {
                for (name in PROVIDERS) {
                    removeQuietly(name)
                    locationManager.addMockProvider(name, isNetwork = name == LocationManager.NETWORK_PROVIDER)
                    locationManager.setTestProviderEnabled(name, true)
                }
                platformReady = true
                platformError = null
            } catch (e: SecurityException) {
                removeAllQuietly()
                platformError = NOT_MOCK_APP_MESSAGE
            } catch (e: Exception) {
                removeAllQuietly()
                platformError = "Could not register test providers: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private suspend fun prepareFused() {
        try {
            val done = withTimeoutOrNull(FUSED_TIMEOUT_MS) {
                fused.setMockMode(true).await()
                true
            }
            if (done == null) {
                fusedUnavailable = true
                fusedWarning = "Fused provider did not respond (Google Play services missing?). " +
                    "GPS/Network mock providers still work."
                return
            }
            fusedReady = true
            fusedWarning = null
            if (closed) disableFused()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            fusedWarning = "Fused provider: $NOT_MOCK_APP_MESSAGE"
        } catch (e: Exception) {
            fusedUnavailable = true
            fusedWarning = "Fused provider unavailable (${e.message ?: e.javaClass.simpleName}). " +
                "GPS/Network mock providers still work."
        }
    }

    /** @return true if the fix was delivered to the platform test providers. */
    fun inject(fix: GpsFix): Boolean {
        if (closed || !platformReady) return false
        synchronized(lock) {
            if (closed || !platformReady) return false
            try {
                for (name in PROVIDERS) {
                    locationManager.setTestProviderLocation(name, buildLocation(name, fix))
                }
            } catch (e: SecurityException) {
                markPlatformLost(NOT_MOCK_APP_MESSAGE)
                return false
            } catch (e: IllegalArgumentException) {
                markPlatformLost("Test provider was removed by the system; re-registering...")
                return false
            }
        }
        if (fusedReady) {
            try {
                fused.setMockLocation(buildLocation(FUSED_PROVIDER, fix))
                    .addOnFailureListener { e ->
                        fusedReady = false
                        fusedWarning = "Fused mock location failed: ${e.message ?: e.javaClass.simpleName}"
                    }
            } catch (e: Exception) {
                fusedReady = false
                fusedWarning = "Fused mock location failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return true
    }

    /** Removes the test providers and turns the fused mock mode off. Safe to call more than once. */
    fun stop() {
        val wasFused: Boolean
        synchronized(lock) {
            closed = true
            for (name in PROVIDERS) {
                try {
                    locationManager.removeTestProvider(name)
                } catch (e: Exception) {
                    // Not registered / not the mock app anymore: nothing to clean up.
                }
            }
            platformReady = false
            wasFused = fusedReady
            fusedReady = false
        }
        if (wasFused) disableFused()
    }

    private fun disableFused() {
        try {
            fused.setMockMode(false)
        } catch (e: Exception) {
            // Best effort.
        }
    }

    private fun markPlatformLost(message: String) {
        removeAllQuietly()
        platformReady = false
        fusedReady = false
        platformError = message
    }

    private fun removeAllQuietly() {
        for (name in PROVIDERS) removeQuietly(name)
    }

    private fun removeQuietly(name: String) {
        try {
            locationManager.removeTestProvider(name)
        } catch (e: Exception) {
            // Ignore: provider was not registered.
        }
    }

    private fun buildLocation(provider: String, fix: GpsFix): Location {
        val accuracy = fix.accuracyM?.takeIf { it > 0f } ?: DEFAULT_ACCURACY_M
        return Location(provider).apply {
            latitude = fix.latitude
            longitude = fix.longitude
            altitude = fix.altitude ?: 0.0
            speed = (fix.speedMps ?: 0f).coerceAtLeast(0f)
            bearing = normalizeBearing(fix.bearingDeg ?: 0f)
            this.accuracy = accuracy
            verticalAccuracyMeters = accuracy
            speedAccuracyMetersPerSecond = 1f
            bearingAccuracyDegrees = 10f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
    }

    private fun normalizeBearing(b: Float): Float = ((b % 360f) + 360f) % 360f

    companion object {
        private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        private const val FUSED_PROVIDER = "fused"
        private const val DEFAULT_ACCURACY_M = 5f
        private const val FUSED_TIMEOUT_MS = 5_000L
    }
}
