package com.enderthor.kSafe.extension.managers

import com.enderthor.kSafe.extension.streamLocation
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

class LocationManager(
    private val karooSystem: KarooSystemService,
    private val scope: CoroutineScope
) {
    private var lastLat: Double = 0.0
    private var lastLng: Double = 0.0
    private var locationJob: Job? = null

    fun start() {
        locationJob = scope.launch {
            karooSystem.streamLocation().collect { event ->
                lastLat = event.lat
                lastLng = event.lng
                Timber.d("Location updated: $lastLat, $lastLng")
            }
        }
    }

    fun stop() {
        locationJob?.cancel()
    }

    /** Returns a Google Maps link with current coordinates, or null if no fix yet. */
    fun getLocationLink(): String? {
        if (lastLat == 0.0 && lastLng == 0.0) return null
        return "https://maps.google.com/?q=$lastLat,$lastLng"
    }
}
