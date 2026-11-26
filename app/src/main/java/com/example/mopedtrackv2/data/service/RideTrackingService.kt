package com.example.mopedtrackv2.data.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.mopedtrackv2.MainActivity
import com.example.mopedtrackv2.MopedApplication
import com.example.mopedtrackv2.R
import com.example.mopedtrackv2.data.preferences.SettingsDataStore
import com.example.mopedtrackv2.data.preferences.SettingsDataStoreImpl
import com.example.mopedtrackv2.domain.model.RideRecordingState
import com.example.mopedtrackv2.domain.model.TrackPoint
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

internal interface LocationClientAdapter {
    fun requestHighAccuracyUpdates(intervalMs: Long, minDisplacementMeters: Float): Boolean
    fun removeUpdates()
}

internal class RideLocationCoordinator(
    private val controller: RideTrackingController,
    private val settingsDataStore: SettingsDataStore,
    private val locationClient: LocationClientAdapter,
    private val scope: CoroutineScope,
    private val onStateChanged: (RideRecordingState) -> Unit = {}
) {
    private var job: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        job?.cancel()
        job = scope.launch {
            controller.recordingState
                .flatMapLatest { state ->
                    onStateChanged(state)
                    if (state == RideRecordingState.RECORDING) {
                        combine(
                            settingsDataStore.pollingIntervalMsFlow,
                            settingsDataStore.minDisplacementMetersFlow
                        ) { intervalMs, minDisplacementMeters ->
                            intervalMs to minDisplacementMeters
                        }.distinctUntilChanged()
                    } else {
                        locationClient.removeUpdates()
                        flowOf(null)
                    }
                }
                .collect { config ->
                    if (config != null && controller.recordingState.value == RideRecordingState.RECORDING) {
                        val started = locationClient.requestHighAccuracyUpdates(
                            intervalMs = config.first,
                            minDisplacementMeters = config.second
                        )
                        if (!started) {
                            locationClient.removeUpdates()
                            controller.stopRecording()
                        }
                    } else {
                        locationClient.removeUpdates()
                    }
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        locationClient.removeUpdates()
    }
}

internal fun createTrackPoint(
    lat: Double,
    lon: Double,
    hasAltitude: Boolean,
    altitude: Double,
    timeMs: Long,
    hasSpeed: Boolean,
    speed: Float,
    hasAccuracy: Boolean,
    accuracy: Float,
    fallbackTimeMs: Long = System.currentTimeMillis()
): TrackPoint {
    return TrackPoint(
        lat = lat,
        lon = lon,
        altitude = if (hasAltitude) altitude else null,
        timestamp = if (timeMs > 0L) timeMs else fallbackTimeMs,
        speed = if (hasSpeed) speed else null,
        accuracy = if (hasAccuracy) accuracy else null
    )
}

internal fun Location.toTrackPoint(): TrackPoint {
    return createTrackPoint(
        lat = latitude,
        lon = longitude,
        hasAltitude = hasAltitude(),
        altitude = altitude,
        timeMs = time,
        hasSpeed = hasSpeed(),
        speed = speed,
        hasAccuracy = hasAccuracy(),
        accuracy = accuracy
    )
}

internal fun buildHighAccuracyLocationRequest(
    intervalMs: Long,
    minDisplacementMeters: Float
): LocationRequest {
    return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
        .setMinUpdateIntervalMillis(intervalMs)
        .setMinUpdateDistanceMeters(minDisplacementMeters)
        .build()
}

class RideTrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var controller: RideTrackingController
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var notificationManager: NotificationManager
    private lateinit var locationCoordinator: RideLocationCoordinator

    private var isLocationUpdatesActive = false
    private var isForegroundStarted = false
    private var lastNotificationUpdateMs = 0L
    private var traceObserverJob: Job? = null
    private var liveMatchJob: Job? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (controller.recordingState.value != RideRecordingState.RECORDING) {
                return
            }
            for (location in result.locations) {
                controller.appendTrackPoint(location.toTrackPoint())
            }
        }
    }

    private val locationAdapter = object : LocationClientAdapter {
        @SuppressLint("MissingPermission")
        override fun requestHighAccuracyUpdates(intervalMs: Long, minDisplacementMeters: Float): Boolean {
            if (controller.recordingState.value != RideRecordingState.RECORDING) {
                removeUpdates()
                return false
            }
            if (!hasLocationPermission()) {
                Log.w(TAG, "Location permission missing; releasing high-accuracy GPS.")
                return false
            }

            val request = buildHighAccuracyLocationRequest(intervalMs, minDisplacementMeters)
            return try {
                if (isLocationUpdatesActive) {
                    fusedLocationClient.removeLocationUpdates(locationCallback)
                }
                fusedLocationClient.requestLocationUpdates(
                    request,
                    locationCallback,
                    Looper.getMainLooper()
                )
                isLocationUpdatesActive = true
                Log.d(
                    TAG,
                    "Started PRIORITY_HIGH_ACCURACY updates (interval=${intervalMs}ms, minDisplacement=${minDisplacementMeters}m)"
                )
                true
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException requesting location updates", e)
                isLocationUpdatesActive = false
                false
            }
        }

        override fun removeUpdates() {
            if (isLocationUpdatesActive) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                isLocationUpdatesActive = false
                Log.d(TAG, "Removed LocationCallback; high-accuracy GPS hardware released.")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        notificationManager = getSystemService(NotificationManager::class.java)

        val app = application as? MopedApplication
        controller = app?.rideTrackingController ?: RideTrackingController.getInstance(applicationContext)
        settingsDataStore = app?.settingsDataStore ?: SettingsDataStoreImpl(applicationContext)

        createNotificationChannel()

        locationCoordinator = RideLocationCoordinator(
            controller = controller,
            settingsDataStore = settingsDataStore,
            locationClient = locationAdapter,
            scope = serviceScope,
            onStateChanged = { state -> handleRecordingStateChanged(state) }
        )
        locationCoordinator.start()

        traceObserverJob = serviceScope.launch {
            controller.activeTrace.collect { trace ->
                val state = controller.recordingState.value
                if (state == RideRecordingState.RECORDING || state == RideRecordingState.PAUSED) {
                    val now = System.currentTimeMillis()
                    if (now - lastNotificationUpdateMs >= NOTIFICATION_UPDATE_THROTTLE_MS) {
                        ensureForeground(state, trace.size)
                    }
                }
            }
        }

        val repository = app?.repository
        liveMatchJob = if (repository != null) {
            serviceScope.launch(Dispatchers.Default) {
                var lastEvaluatedPointCount = 0
                controller.activeTrace
                    .collect { trace ->
                        val state = controller.recordingState.value
                        if (state == RideRecordingState.RECORDING &&
                            trace.size >= 2 &&
                            trace.size > lastEvaluatedPointCount
                        ) {
                            lastEvaluatedPointCount = trace.size
                            try {
                                repository.matchLiveTraceWindow(trace)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error matching live trace window", e)
                            }
                        } else if (state == RideRecordingState.IDLE || trace.size < lastEvaluatedPointCount) {
                            lastEvaluatedPointCount = trace.size
                        }
                    }
            }
        } else {
            null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (controller.recordingState.value == RideRecordingState.IDLE) {
                    controller.startRecording(notifyService = false)
                }
                val state = controller.recordingState.value
                if (state == RideRecordingState.IDLE) {
                    ensureForeground(RideRecordingState.IDLE, 0)
                    stopTrackingService()
                } else {
                    ensureForeground(state, controller.activeTrace.value.size)
                }
            }
            ACTION_PAUSE -> {
                controller.pauseRecording()
                locationAdapter.removeUpdates()
                val state = controller.recordingState.value
                if (state == RideRecordingState.PAUSED) {
                    ensureForeground(state, controller.activeTrace.value.size)
                } else if (state == RideRecordingState.IDLE) {
                    stopTrackingService()
                }
            }
            ACTION_RESUME -> {
                controller.resumeRecording(notifyService = false)
                val state = controller.recordingState.value
                if (state != RideRecordingState.IDLE) {
                    ensureForeground(state, controller.activeTrace.value.size)
                } else {
                    stopTrackingService()
                }
            }
            ACTION_STOP -> {
                locationAdapter.removeUpdates()
                controller.stopRecording()
                stopTrackingService()
            }
            ACTION_DISCARD -> {
                locationAdapter.removeUpdates()
                controller.discardRecording()
                stopTrackingService()
            }
            else -> {
                val state = controller.recordingState.value
                if (state == RideRecordingState.IDLE) {
                    stopTrackingService()
                } else {
                    ensureForeground(state, controller.activeTrace.value.size)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        locationCoordinator.stop()
        traceObserverJob?.cancel()
        liveMatchJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handleRecordingStateChanged(state: RideRecordingState) {
        when (state) {
            RideRecordingState.RECORDING -> {
                ensureForeground(state, controller.activeTrace.value.size)
            }
            RideRecordingState.PAUSED -> {
                locationAdapter.removeUpdates()
                ensureForeground(state, controller.activeTrace.value.size)
            }
            RideRecordingState.IDLE -> {
                locationAdapter.removeUpdates()
                stopTrackingService()
            }
        }
    }

    private fun ensureForeground(state: RideRecordingState, pointCount: Int) {
        val notification = buildNotification(state, pointCount)
        lastNotificationUpdateMs = System.currentTimeMillis()
        if (!isForegroundStarted) {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
                isForegroundStarted = true
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException starting foreground location service", e)
                locationAdapter.removeUpdates()
                controller.stopRecording()
                stopSelf()
            }
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun stopTrackingService() {
        locationAdapter.removeUpdates()
        if (isForegroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
        }
        stopSelf()
    }

    private fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ride Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification while recording a moped ride"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(state: RideRecordingState, pointCount: Int): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_APP,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (state) {
            RideRecordingState.RECORDING -> "Recording Ride"
            RideRecordingState.PAUSED -> "Ride Paused"
            RideRecordingState.IDLE -> "Ride Tracking"
        }
        val contentText = when (state) {
            RideRecordingState.RECORDING -> "GPS active • $pointCount track points"
            RideRecordingState.PAUSED -> "GPS paused • $pointCount track points"
            RideRecordingState.IDLE -> "Stopping ride tracker..."
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_ride_tracking)
            .setOngoing(state == RideRecordingState.RECORDING || state == RideRecordingState.PAUSED)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentPendingIntent)

        when (state) {
            RideRecordingState.RECORDING -> {
                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "Pause",
                    createServiceActionPendingIntent(ACTION_PAUSE, REQUEST_CODE_PAUSE)
                )
                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    createServiceActionPendingIntent(ACTION_STOP, REQUEST_CODE_STOP)
                )
            }
            RideRecordingState.PAUSED -> {
                builder.addAction(
                    android.R.drawable.ic_media_play,
                    "Resume",
                    createServiceActionPendingIntent(ACTION_RESUME, REQUEST_CODE_RESUME)
                )
                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    createServiceActionPendingIntent(ACTION_STOP, REQUEST_CODE_STOP)
                )
            }
            RideRecordingState.IDLE -> Unit
        }

        return builder.build()
    }

    private fun createServiceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, RideTrackingService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "RideTrackingService"
        private const val CHANNEL_ID = "ride_tracking_channel"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_UPDATE_THROTTLE_MS = 2000L

        const val ACTION_START = "com.example.mopedtrackv2.action.START_RIDE_TRACKING"
        const val ACTION_PAUSE = "com.example.mopedtrackv2.action.PAUSE_RIDE_TRACKING"
        const val ACTION_RESUME = "com.example.mopedtrackv2.action.RESUME_RIDE_TRACKING"
        const val ACTION_STOP = "com.example.mopedtrackv2.action.STOP_RIDE_TRACKING"
        const val ACTION_DISCARD = "com.example.mopedtrackv2.action.DISCARD_RIDE_TRACKING"

        private const val REQUEST_CODE_OPEN_APP = 100
        private const val REQUEST_CODE_PAUSE = 101
        private const val REQUEST_CODE_RESUME = 102
        private const val REQUEST_CODE_STOP = 103

        fun start(context: Context) {
            val intent = Intent(context, RideTrackingService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
