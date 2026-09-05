package com.mysafe.mysafe

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*

class LocationService : Service() {
    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE = "ACTION_UPDATE"
        const val CHANNEL_ID = "gps_channel"
        const val NOTIF_ID = 1001
    }
    private var lm: LocationManager? = null
    private var listener: LocationListener? = null
    private var running = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "GPS", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START && !running) startTracking()
        else if (intent?.action == ACTION_STOP) stopTracking()
        return START_STICKY
    }

    private fun startTracking() {
        startForeground(NOTIF_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MySafe GPS").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentText("Suivi actif").setOngoing(true).build())
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val i = Intent(ACTION_UPDATE)
                i.putExtra("lat", loc.latitude); i.putExtra("lon", loc.longitude)
                i.putExtra("time", SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
                sendBroadcast(i)
            }
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        try { lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60000, 10f, listener!!) }
        catch (e: Exception) { lm?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60000, 10f, listener!!) }
        running = true
    }

    private fun stopTracking() {
        listener?.let { lm?.removeUpdates(it) }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); running = false
    }

    override fun onBind(i: Intent?): IBinder? = null
}
