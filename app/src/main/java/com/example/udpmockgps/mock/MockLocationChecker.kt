package com.example.udpmockgps.mock

import android.content.Context
import android.location.LocationManager

enum class MockAppState { SELECTED, NOT_SELECTED, UNKNOWN }

/**
 * Determines whether this app is the selected mock location app by briefly registering (and removing)
 * a throw-away test provider: the platform throws SecurityException iff we are not selected.
 */
object MockLocationChecker {
    private const val PROBE_PROVIDER = "udpmockgps_probe"

    fun check(context: Context): MockAppState {
        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return MockAppState.UNKNOWN
        return try {
            lm.addMockProvider(PROBE_PROVIDER, isNetwork = false)
            MockAppState.SELECTED
        } catch (e: SecurityException) {
            MockAppState.NOT_SELECTED
        } catch (e: Exception) {
            MockAppState.UNKNOWN
        } finally {
            try {
                lm.removeTestProvider(PROBE_PROVIDER)
            } catch (e: Exception) {
                // Nothing registered.
            }
        }
    }
}
