package com.mysafe.mysafe
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.osmdroid.util.GeoPoint
import java.text.SimpleDateFormat
import java.util.*

class LocationService : Service() {
    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE = "ACTION_UPDATE"
        const val EXTRA_TARGET_PHONE = "target_phone"
        const val EXTRA_MY_PHONE = "my_phone"
        var isRunning = false
        var lastLocation: GeoPoint? = null
        var targetPhoneNumber: String = ""
        var myPhoneNumber: String = ""
    }

    private val binder = LocalBinder()
    private lateinit var locationManager: LocationManager
    private lateinit var smsManager: SmsManager
    private var handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private val UPDATE_INTERVAL = 0L

    inner class LocalBinder : Binder()
    override fun onBind(intent: Intent?): IBinder = binder

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = GeoPoint(location.latitude, location.longitude)
            sendLocationBySms(location.latitude, location.longitude)
            broadcastUpdate()
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        smsManager = SmsManager.getDefault()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring(intent)
            ACTION_STOP -> stopMonitoring()
        }
        return START_STICKY
    }

    private fun startMonitoring(intent: Intent) {
        if (isRunning) return
        targetPhoneNumber = intent.getStringExtra(EXTRA_TARGET_PHONE) ?: ""
        myPhoneNumber = intent.getStringExtra(EXTRA_MY_PHONE) ?: ""
        startForeground(9999, createNotification())
        isRunning = true

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_INTERVAL, 10f, locationListener)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, UPDATE_INTERVAL, 10f, locationListener)
        } catch (e: SecurityException) {}

        updateRunnable = object : Runnable {
            override fun run() {
                requestSingleLocation()
                handler.postDelayed(this, UPDATE_INTERVAL)
            }
        }
        handler.postDelayed(updateRunnable!!, 0)
    }

    private fun requestSingleLocation() {
        try {
            val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            lastKnown?.let {
                lastLocation = GeoPoint(it.latitude, it.longitude)
                sendLocationBySms(it.latitude, it.longitude)
                broadcastUpdate()
            }
        } catch (e: SecurityException) {}
    }

    private fun sendLocationBySms(lat: Double, lon: Double) {
        if (targetPhoneNumber.isBlank()) return
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val message = "MYSAFE:$lat:$lon:$time:${myPhoneNumber.takeLast(4)}"
        try {
            // ✅ Corrigé : port = 0 (Short), pas ByteArray
            smsManager.sendDataMessage(targetPhoneNumber, null, 0.toShort(), message.toByteArray(Charsets.UTF_8), null, null)
        } catch (e: Exception) {
            try { smsManager.sendTextMessage(targetPhoneNumber, null, message, null, null) } catch (e2: Exception) {}
        }
    }

    private fun broadcastUpdate() {
        val intent = Intent(ACTION_UPDATE)
        intent.putExtra("lat", lastLocation?.latitude ?: 0.0)
        intent.putExtra("lon", lastLocation?.longitude ?: 0.0)
        sendBroadcast(intent)
    }

    private fun stopMonitoring() {
        isRunning = false
        updateRunnable?.let { handler.removeCallbacks(it) }
        try { locationManager.removeUpdates(locationListener) } catch (e: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel("MYSAFE_SVC", "Surveillance", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                // ✅ Corrigé : utiliser la bonne constante
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "MYSAFE_SVC")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("")
            .setContentText("")
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
    }
}
