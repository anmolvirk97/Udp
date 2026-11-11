package com.example.udpmockgps.data

enum class DataFormat(val label: String) {
    NMEA("NMEA 0183"),
    JSON("JSON"),
    CSV("CSV (lat,lon,alt,speed,bearing,accuracy)");

    companion object {
        fun fromName(name: String?): DataFormat = entries.firstOrNull { it.name == name } ?: NMEA
    }
}

data class AppSettings(
    val port: Int = DEFAULT_PORT,
    val format: DataFormat = DataFormat.NMEA,
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val autoStartOnBoot: Boolean = false,
) {
    companion object {
        const val DEFAULT_PORT = 10110
        const val DEFAULT_TIMEOUT_SECONDS = 5
        const val MIN_PORT = 1
        const val MAX_PORT = 65535
        const val MIN_TIMEOUT_SECONDS = 1
        const val MAX_TIMEOUT_SECONDS = 3600
    }
}

/** A position fix as received from the wire. Optional fields are null when the sender did not provide them. */
data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val accuracyM: Float? = null,
)

enum class SignalState { STOPPED, WAITING, ACTIVE, LOST }

data class PacketLogEntry(val timeMs: Long, val source: String, val text: String)

/** The single Service -> UI status object. */
data class ServiceStatus(
    val running: Boolean = false,
    val signal: SignalState = SignalState.STOPPED,
    val port: Int? = null,
    val format: DataFormat? = null,
    val timeoutSeconds: Int = AppSettings.DEFAULT_TIMEOUT_SECONDS,
    val lastPacketTimeMs: Long? = null,
    val lastFixTimeMs: Long? = null,
    val packetsReceived: Long = 0,
    val fixesReceived: Long = 0,
    val fixesInjected: Long = 0,
    val parseErrors: Long = 0,
    val lastFix: GpsFix? = null,
    val lastError: String? = null,
    val message: String? = null,
    val mockProvidersActive: Boolean = false,
    val fusedActive: Boolean = false,
    val recentPackets: List<PacketLogEntry> = emptyList(),
)
