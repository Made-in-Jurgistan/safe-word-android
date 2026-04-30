package com.safeword.android.service

import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ThermalMonitor — observes device thermal status via [PowerManager].
 *
 * Exposes [thermalStatus] as a [StateFlow] so consumers can react to throttling.
 * At THERMAL_STATUS_MODERATE or above, the STT pipeline may reduce thread count
 * or skip optional stages (e.g. grammar correction) to lower heat generation.
 */
@Singleton
class ThermalMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val powerManager = context.getSystemService(PowerManager::class.java)

    private val _thermalStatus = MutableStateFlow(PowerManager.THERMAL_STATUS_NONE)
    val thermalStatus: StateFlow<Int> = _thermalStatus.asStateFlow()

    /** True when the device is at THERMAL_STATUS_MODERATE or higher. */
    val isThrottled: Boolean get() = _thermalStatus.value >= PowerManager.THERMAL_STATUS_MODERATE

    private var registered = false

    private val listener = PowerManager.OnThermalStatusChangedListener { status ->
        val previous = _thermalStatus.value
        _thermalStatus.value = status
        when {
            status >= PowerManager.THERMAL_STATUS_SEVERE ->
                Timber.w("[THERMAL] ThermalMonitor | status=%s SEVERE+ — consider reducing workload", statusName(status))
            status >= PowerManager.THERMAL_STATUS_MODERATE ->
                Timber.w("[THERMAL] ThermalMonitor | status=%s MODERATE — pipeline may skip grammar correction", statusName(status))
            status < previous ->
                Timber.d("[THERMAL] ThermalMonitor | status=%s — thermal pressure eased", statusName(status))
        }
    }

    /** Start observing thermal status. Idempotent. */
    fun start() {
        if (registered) return
        _thermalStatus.value = powerManager.currentThermalStatus
        powerManager.addThermalStatusListener(listener)
        registered = true
        Timber.i("[INIT] ThermalMonitor.start | initialStatus=%s", statusName(_thermalStatus.value))
    }

    /** Stop observing. Call from service onDestroy. */
    fun stop() {
        if (!registered) return
        powerManager.removeThermalStatusListener(listener)
        registered = false
        Timber.i("[STATE] ThermalMonitor.stop | unregistered")
    }

    private fun statusName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN($status)"
    }
}
