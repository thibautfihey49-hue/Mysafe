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
        fun demanderPosition() {
            val intent = Intent("DEMANDER_POSITION")
            sendBroadcast(intent)
        }

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE = "ACTION_UPDATE"
        const val EXTRA_TARGET_PHONE = "target_phone"
        const val EXTRA_MY_PHONE = "my_phone"
        var isRunning = false
        var lastLocation: GeoPoint? = null
        var lastSentLocation: Location? = null
        
        // ✅ RÈGLES DÉFINITIVES :
        const val MIN_DISTANCE_METERS = 100000f    // 📏 Au moins 10m
        const val MIN_INTERVAL_SEC = 999999L        // ⏳ 1 MINUTE 30 entre CHAQUE SMS
        var lastSentTime = 0L
        
        var targetPhoneNumber: String = ""
        var myPhoneNumber: String = ""
    }

    private val binder = LocalBinder()
    private lateinit var locationManager: LocationManager
    private lateinit var smsManager: SmsManager
    private var handler = Handler(Looper.getMainLooper())
    private var periodicRunnable: Runnable? = null

    inner class LocalBinder : Binder()
    override fun onBind(intent: Intent?): IBinder = binder

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            verifierEtEnvoyer(location)
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
        
        lastSentLocation = null
        lastSentTime = 0L
        
        startForeground(9999, createNotification())
        isRunning = true

        sendInitialLocation()

        try {

            }
        }
    }

    private fun verifierEtEnvoyer(location: Location) {
        val maintenant = System.currentTimeMillis()
        
        if (maintenant - lastSentTime < MIN_INTERVAL_SEC * 1000) return
        
        lastSentLocation?.let { derniere ->
            if (location.distanceTo(derniere) < MIN_DISTANCE_METERS) return
        }

        envoyerPosition(location)
    }

    private fun envoyerPosition(location: Location) {
        lastSentLocation = location
        lastSentTime = System.currentTimeMillis()
        lastLocation = GeoPoint(location.latitude, location.longitude)
        
        sendLocationBySms(location.latitude, location.longitude)
        broadcastUpdate()
    }

    private fun sendInitialLocation() {
        try {
            val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            lastKnown?.let { envoyerPosition(it) }
        } catch (e: SecurityException) {}
    }

    private fun requestSingleLocation() {
        try {
            val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            lastKnown?.let { verifierEtEnvoyer(it) }
        } catch (e: SecurityException) {}
    }

    private fun sendLocationBySms(lat: Double, lon: Double) {
        if (targetPhoneNumber.isBlank()) return
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val message = "MYSAFE:$lat:$lon:$time:${myPhoneNumber.takeLast(4)}"
        try {
            smsManager.sendDataMessage(
                targetPhoneNumber,
                null,
                50006.toShort(),
                message.toByteArray(Charsets.UTF_8),
                null,
                null
            )
        } catch (e: Exception) {
            try {
                smsManager.sendTextMessage(targetPhoneNumber, null, message, null, null)
            } catch (e2: Exception) {}
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
        lastSentLocation = null
        lastSentTime = 0L
        periodicRunnable?.let { handler.removeCallbacks(it) }
        try { locationManager.removeUpdates(locationListener) } catch (e: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                "MYSAFE_SVC",
                "Surveillance",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
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
