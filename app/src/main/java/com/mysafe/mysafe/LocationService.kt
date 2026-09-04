package com.mysafe.mysafe
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class LocationService : android.app.Service() {
    private var lm: LocationManager? = null
    private var listener: LocationListener? = null
    private var running = AtomicBoolean(false)
    private val CHANNEL = "mysafe"

    data class Position(val lat: Double, val lon: Double, val time: String) {
        fun sameAs(other: Position?) = other != null &&
            Math.abs(lat - other.lat) < 0.00005 &&
            Math.abs(lon - other.lon) < 0.00005
    }

    companion object {
        val positions = mutableListOf<Position>()
        var lastPos: Position? = null
        const val ACTION_UPDATE = "com.mysafe.UPDATE"
    }

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "MySafe", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running.get()) {
            startForeground(1, NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("Surveillance active")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build())
            startLoc()
            running.set(true)
        }
        return START_STICKY
    }

    private fun startLoc() {
        listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val t = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date())
                val pos = Position(loc.latitude, loc.longitude, t)
                if (pos.sameAs(lastPos)) return
                synchronized(positions) {
                    positions.add(0, pos)
                    if (positions.size > 50) positions.removeAt(positions.size - 1)
                }
                lastPos = pos
                sendBroadcast(Intent(ACTION_UPDATE).apply {
                    setPackage(packageName)
                    putExtra("lat", loc.latitude)
                    putExtra("lon", loc.longitude)
                    putExtra("time", t)
                })
            }
            override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60000L, 0f, listener!!)
            lm?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60000L, 0f, listener!!)
        }
    }

    override fun onDestroy() {
        running.set(false)
        try { lm?.removeUpdates(listener!!) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
