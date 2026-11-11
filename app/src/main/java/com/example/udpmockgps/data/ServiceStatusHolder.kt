package com.example.udpmockgps.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Process-wide holder of the one StateFlow the service publishes and the UI observes. */
object ServiceStatusHolder {
    private val _status = MutableStateFlow(ServiceStatus())
    val status: StateFlow<ServiceStatus> = _status.asStateFlow()

    fun set(newStatus: ServiceStatus) {
        _status.value = newStatus
    }

    fun update(transform: (ServiceStatus) -> ServiceStatus) {
        _status.update(transform)
    }
}
