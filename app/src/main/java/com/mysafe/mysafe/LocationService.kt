package com.mysafe.mysafe
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class LocationService : android.app.Service() {
    private val TAG = "MySafeLocation"
    private var locationManager: LocationManager? = null
    private var listener: LocationListener? = null
    private var running = AtomicBoolean(false)
    private val CHANNEL_ID = "mysafe_location_channel"
    private val NOTIFICATION_ID = 12345

    data class Position(
        val latitude: Double,
        val longitude: Double,
        val time: String,
        val address: String
    ) {
        fun isSameAs(other: Position?): Boolean {
            if (other == null) return false
            val latDiff = Math.abs(this.latitude - other.latitude)
            val lonDiff = Math.abs(this.longitude - other.longitude)
            return latDiff < 0.00005 && lonDiff < 0.00005 && this.address == other.address
        }
    }

    companion object {
        val positions = mutableListOf<Position>()
        var isRunning = AtomicBoolean(false)
        var latestPosition: Position? = null
        const val ACTION_NEW_POSITION = "com.mysafe.mysafe.NEW_POSITION"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_TIME = "time"
        const val EXTRA_ADDRESS = "address"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running.get()) {
            startForeground(NOTIFICATION_ID, buildNotification())
            startLocationUpdates()
            running.set(true)
            isRunning.set(true)
            Log.d(TAG, "✅ Service DÉMARRÉ")
        }
        return START_STICKY
    }

    private fun startLocationUpdates() {
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handleNewLocation(location)
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "❌ Autorisations manquantes")
            return
        }

        locationManager?.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            60000L,
            0f,
            listener!!
        )
        locationManager?.requestLocationUpdates(
            LocationManager.NETWORK_PROVIDER,
            60000L,
            0f,
            listener!!
        )
    }

    private fun handleNewLocation(location: Location) {
        CoroutineScope(Dispatchers.IO).launch {
            val time = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date())
            val address = getAddress(location.latitude, location.longitude)

            val position = Position(
                latitude = location.latitude,
                longitude = location.longitude,
                time = time,
                address = address
            )

            // ✅ IGNORER SI MÊME POSITION — PAS DE DOUBLON
            if (position.isSameAs(latestPosition)) {
                Log.d(TAG, "⏭ Position identique — ignorée")
                return@launch
            }

            // ✅ NOUVELLE POSITION DIFFÉRENTE → AJOUT
            synchronized(positions) {
                positions.add(0, position)
                if (positions.size > 100) positions.removeAt(positions.size - 1)
            }

            latestPosition = position
            Log.d(TAG, "📍 Nouvelle position: $time - $address")

            val broadcast = Intent(ACTION_NEW_POSITION).apply {
                setPackage(packageName)
                putExtra(EXTRA_LAT, location.latitude)
                putExtra(EXTRA_LON, location.longitude)
                putExtra(EXTRA_TIME, time)
                putExtra(EXTRA_ADDRESS, address)
            }
            sendBroadcast(broadcast)
        }
    }

    private fun getAddress(lat: Double, lon: Double): String {
        return try {
            val geocoder = Geocoder(this, Locale.FRANCE)
            val addresses: MutableList<Address>? = geocoder.getFromLocation(lat, lon, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val parts = mutableListOf<String>()
                for (i in 0..addr.maxAddressLineIndex) {
                    parts.add(addr.getAddressLine(i))
                }
                if (parts.isNotEmpty()) parts.joinToString(", ") else "Coordonnées seulement"
            } else "Adresse introuvable"
        } catch (e: IOException) {
            "Erreur adresse: ${e.message}"
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MySafe — Surveillance active")
            .setContentText("Mise à jour toutes les minutes")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MySafe Localisation",
                android.app.NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Service de suivi de position"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running.set(false)
        isRunning.set(false)
        try {
            locationManager?.removeUpdates(listener!!)
        } catch (e: Exception) {}
        Log.d(TAG, "⏹ Service arrêté")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
