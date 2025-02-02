package io.homeassistant.companion.android.background

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import io.homeassistant.companion.android.common.dagger.GraphComponentAccessor
import io.homeassistant.companion.android.domain.integration.IntegrationUseCase
import io.homeassistant.companion.android.domain.integration.UpdateLocation
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class LocationBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REQUEST_LOCATION_UPDATES =
            "io.homeassistant.companion.android.background.REQUEST_UPDATES"
        const val ACTION_PROCESS_LOCATION =
            "io.homeassistant.companion.android.background.PROCESS_UPDATES"
        const val ACTION_PROCESS_GEO =
            "io.homeassistant.companion.android.background.PROCESS_GEOFENCE"

        private const val TAG = "LocBroadcastReceiver"
    }

    @Inject
    lateinit var integrationUseCase: IntegrationUseCase

    private val mainScope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onReceive(context: Context, intent: Intent) {
        ensureInjected(context)

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> setupLocationTracking(context)
            ACTION_REQUEST_LOCATION_UPDATES -> setupLocationTracking(context)
            ACTION_PROCESS_LOCATION -> handleLocationUpdate(context, intent)
            ACTION_PROCESS_GEO -> handleGeoUpdate(context, intent)
            else -> Log.w(TAG, "Unknown intent action: ${intent.action}!")
        }
    }

    private fun ensureInjected(context: Context) {
        if (context.applicationContext is GraphComponentAccessor) {
            DaggerReceiverComponent.builder()
                .appComponent((context.applicationContext as GraphComponentAccessor).appComponent)
                .build()
                .inject(this)
        } else {
            throw Exception("Application Context passed is not of our application!")
        }
    }

    private fun setupLocationTracking(context: Context) {
        if (ActivityCompat.checkSelfPermission(
                context,
                ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Not starting location reporting because of permissions.")
            return
        }

        mainScope.launch {
            if (integrationUseCase.isBackgroundTrackingEnabled())
                requestLocationUpdates(context)
            if (integrationUseCase.isZoneTrackingEnabled())
                requestZoneUpdates(context)
        }
    }

    private fun requestLocationUpdates(context: Context) {
        Log.d(TAG, "Registering for location updates.")

        val fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
        val intent = getLocationUpdateIntent(context, false)

        fusedLocationProviderClient.removeLocationUpdates(intent)

        fusedLocationProviderClient.requestLocationUpdates(
            createLocationRequest(),
            intent
        )
    }

    private suspend fun requestZoneUpdates(context: Context) {
        Log.d(TAG, "Registering for zone based location updates")

        val geofencingClient = LocationServices.getGeofencingClient(context)
            val intent = getLocationUpdateIntent(context, true)
            geofencingClient.removeGeofences(intent)
            geofencingClient.addGeofences(
                createGeofencingRequest(),
                intent
            )
    }

    private fun handleLocationUpdate(context: Context, intent: Intent) {
        Log.d(TAG, "Received location update.")
        LocationResult.extractResult(intent)?.lastLocation?.let {
            sendLocationUpdate(it, context)
        }
    }

    private fun handleGeoUpdate(context: Context, intent: Intent) {
        Log.d(TAG, "Received geofence update.")
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent.hasError()) {
            Log.e(TAG, "Error getting geofence broadcast status code: ${geofencingEvent.errorCode}")
            return
        }

        sendLocationUpdate(geofencingEvent.triggeringLocation, context)
    }

    private fun sendLocationUpdate(location: Location, context: Context) {
        Log.d(
            TAG, "Last Location: " +
                "\nCoords:(${location.latitude}, ${location.longitude})" +
                "\nAccuracy: ${location.accuracy}" +
                "\nBearing: ${location.bearing}"
        )
        val updateLocation = UpdateLocation(
            "",
            arrayOf(location.latitude, location.longitude),
            location.accuracy.toInt(),
            getBatteryLevel(context),
            location.speed.toInt(),
            location.altitude.toInt(),
            location.bearing.toInt(),
            if (Build.VERSION.SDK_INT >= 26) location.verticalAccuracyMeters.toInt() else 0
        )

        mainScope.launch {
            try {
                integrationUseCase.updateLocation(updateLocation)
            } catch (e: Exception) {
                Log.e(TAG, "Could not update location.", e)
            }
        }
    }

    private fun getLocationUpdateIntent(context: Context, isGeofence: Boolean): PendingIntent {
        val intent = Intent(context, LocationBroadcastReceiver::class.java)
        intent.action = if (isGeofence) ACTION_PROCESS_GEO else ACTION_PROCESS_LOCATION
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createLocationRequest(): LocationRequest {
        val locationRequest = LocationRequest()

        locationRequest.interval = 60000 // Every 60 seconds
        locationRequest.fastestInterval = 30000 // Every 30 seconds
        locationRequest.maxWaitTime = 200000 // Every 5 minutes

        locationRequest.priority = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY

        return locationRequest
    }

    private suspend fun createGeofencingRequest(): GeofencingRequest {
        val geofencingRequestBuilder = GeofencingRequest.Builder()
        integrationUseCase.getZones().forEach {
            geofencingRequestBuilder.addGeofence(Geofence.Builder()
                .setRequestId(it.entityId)
                .setCircularRegion(
                    it.attributes.latitude,
                    it.attributes.longitude,
                    it.attributes.radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .build())
        }
        return geofencingRequestBuilder.build()
    }

    private fun getBatteryLevel(context: Context): Int? {
        val batteryIntent =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent!!.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

        if (level == -1 || scale == -1) {
            Log.e(TAG, "Issue getting battery level!")
            return null
        }

        return (level.toFloat() / scale.toFloat() * 100.0f).toInt()
    }
}
