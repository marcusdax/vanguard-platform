package com.odasi.fieldwright.locations

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.odasi.fieldwright.model.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The app's only source of device position.
 *
 * Real fixes come from the platform provider. When no fix exists (emulator, permission
 * denied, cold GPS) the provider returns a *labelled simulated* fix near the sample
 * district so the demo remains explorable. It is never passed off as a real reading:
 * [GeoPoint.source] carries SIMULATED all the way into the packet and the UI.
 */
interface LocationProvider {
    fun currentFix(): GeoPoint?
    fun observeFix(): Flow<GeoPoint>
}

object DemoDistrict {
    /** Sample-book district, south Austin. All demo sites sit within ~600 m of here. */
    const val LAT = 30.18450
    const val LNG = -97.87950
    const val LABEL = "Creekside · MUD 14"

    fun fix(atEpochMs: Long, driftMeters: Double = 0.0): GeoPoint {
        val lat = LAT + driftMeters / 111_320.0
        val lng = LNG + driftMeters / 97_600.0
        return GeoPoint(lat = lat, lng = lng, recordedAtEpochMs = atEpochMs, accuracyMeters = 6f, source = GeoPoint.FixSource.SIMULATED)
    }
}

class DeviceLocationProvider(private val context: Context) : LocationProvider {

    private val manager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private var lastFix: GeoPoint? = null

    override fun currentFix(): GeoPoint? = lastFix ?: lastKnown() ?: DemoDistrict.fix(System.currentTimeMillis())

    private fun lastKnown(): GeoPoint? {
        val locationManager = manager ?: return null
        if (!hasPermission) return null
        val candidates = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var best: Location? = null
        for (provider in candidates) {
            val location = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            if (location != null && (best == null || location.time > best.time)) best = location
        }
        return best?.toGeoPoint()
    }

    override fun observeFix(): Flow<GeoPoint> {
        val locationManager = manager
        if (locationManager == null || !hasPermission) {
            return flowOf(DemoDistrict.fix(System.currentTimeMillis()))
        }
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return flowOf(DemoDistrict.fix(System.currentTimeMillis()))
        }
        return callbackFlow {
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    val fix = location.toGeoPoint()
                    lastFix = fix
                    trySend(fix)
                }

                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
                @Deprecated("Deprecated in API 29")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            }
            runCatching {
                locationManager.requestLocationUpdates(provider, 5_000L, 5f, listener, context.mainLooper)
            }.onFailure {
                trySend(DemoDistrict.fix(System.currentTimeMillis()))
            }
            awaitClose { runCatching { locationManager.removeUpdates(listener) } }
        }
    }

    private fun Location.toGeoPoint(): GeoPoint = GeoPoint(
        lat = latitude,
        lng = longitude,
        recordedAtEpochMs = time,
        accuracyMeters = if (hasAccuracy()) accuracy else 50f,
        source = GeoPoint.FixSource.GPS,
    )
}
