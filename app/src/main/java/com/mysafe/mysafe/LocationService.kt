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
        
        const val MIN_DISTANCE_METERS = 999999f
        const val MIN_INTERVAL_SEC = 999999L
        
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "location_service_channel"
    }

    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var isRunning = false
    private var targetPhoneNumber: String? = null
    private var myPhoneNumber: String? = null
    private var lastLocation: Location? = null
    private val smsManager = SmsManager.getDefault()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking(intent)
            ACTION_STOP -> stopTracking()
        }
        return START_STICKY
    }

    private fun startTracking(intent: Intent) {
        if (isRunning) return
        
        targetPhoneNumber = intent.getStringExtra(EXTRA_TARGET_PHONE)
        myPhoneNumber = intent.getStringExtra(EXTRA_MY_PHONE)
        
        startForeground(NOTIFICATION_ID, createNotification())
        
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLocation = location
                val updateIntent = Intent(ACTION_UPDATE)
                updateIntent.putExtra("lat", location.latitude)
                updateIntent.putExtra("lon", location.longitude)
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                updateIntent.putExtra("time", time)
                sendBroadcast(updateIntent)
            }
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                10000,
                5f,
                locationListener!!
            )
        } catch (e: Exception) {
            try {
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    10000,
                    5f,
                    locationListener!!
                )
            } catch (e2: Exception) {
                Toast.makeText(this, "Impossible d'obtenir la localisation", Toast.LENGTH_SHORT).show()
            }
        }
        
        isRunning = true
    }

    private fun stopTracking() {
        if (!isRunning) return
        locationListener?.let { locationManager?.removeUpdates(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        isRunning = false
    }

    private fun createNotification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MySafe — Suivi GPS")
            .setContentText("Suivi actif — Envoi sur demande uniquement")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Suivi GPS",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
