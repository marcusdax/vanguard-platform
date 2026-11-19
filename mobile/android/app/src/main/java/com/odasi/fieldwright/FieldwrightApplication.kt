package com.odasi.fieldwright

import android.app.Application
import android.content.Context
import android.location.Geocoder
import com.odasi.fieldwright.data.FieldwrightContainer
import com.odasi.fieldwright.locations.DeviceLocationProvider
import com.odasi.fieldwright.locations.LocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Composition root.
 *
 * The container owns Room, the demo market, the outbox and the crypto signer. It is
 * created once here and handed to the Compose tree, which keeps every feature free of
 * Android context plumbing and makes the whole app testable with a fake container.
 */
class FieldwrightApplication : Application() {

    lateinit var container: FieldwrightContainer
        private set

    lateinit var location: LocationProvider
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = FieldwrightContainer(context = this)
        location = DeviceLocationProvider(this)
        // Seeding and WorkManager registration are idempotent; the UI observes Flows and
        // renders its own loading state, so this must never block the main thread.
        applicationScope.launch { runCatching { container.bootstrap() } }
    }

    override fun onTerminate() {
        container.close()
        super.onTerminate()
    }

    companion object {
        fun from(context: Context): FieldwrightApplication =
            context.applicationContext as FieldwrightApplication
    }
}
