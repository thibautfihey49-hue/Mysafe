package com.mysafe.mysafe

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat

class MySafeAgentService : Service() {
    companion object {
        const val TAG = "MySafe_Agent"
        const val CHANNEL_ID = "MySafeAgent"
        
        private var instance: MySafeAgentService? = null
        
        fun demarrerGPS(context: Context) {
            val intent = Intent(context, MySafeAgentService::class.java)
            context.startService(intent)
            instance?.demarrerSuiviGPSInterne()
        }
        
        fun arreterGPS(context: Context) {
            instance?.arreterSuiviGPSInterne()
        }
    }

    private val MIN_DISTANCE_METERS = 10f
    private val MIN_TIME_INTERVAL = 90000L // 1min30s
    private var isTracking = false
    private var lastSentTime = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            MacroEngine.mettreAJourPosition(location)
            verifierEtNotifierPosition(location)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        MacroEngine.initialiser(this)
        creerNotificationCanal()
        startForeground(1, creerNotificationDiscrete())
        Log.d(TAG, "🔒 AGENT + MOTEUR DE MACROS DÉMARRÉ")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    fun demarrerSuiviGPSInterne() {
        if (isTracking) return
        isTracking = true
        
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME_INTERVAL,
                MIN_DISTANCE_METERS,
                locationListener
            )
            lm.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                MIN_TIME_INTERVAL,
                MIN_DISTANCE_METERS,
                locationListener
            )
            Log.d(TAG, "✅ SUIVI GPS ACTIF — 10m / 1min30")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur GPS", e)
        }
    }

    fun arreterSuiviGPSInterne() {
        isTracking = false
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.removeUpdates(locationListener)
        Log.d(TAG, "✅ SUIVI GPS ARRÊTÉ")
    }

    private fun verifierEtNotifierPosition(location: Location) {
        val maintenant = System.currentTimeMillis()
        val derniere = MacroEngine.dernierPosition ?: return
        
        val distance = location.distanceTo(derniere)
        val tempsEcoule = maintenant - lastSentTime

        if (distance >= MIN_DISTANCE_METERS || tempsEcoule >= MIN_TIME_INTERVAL) {
            lastSentTime = maintenant
            Log.d(TAG, "📍 Déplacement détecté : ${distance.toInt()}m — prêt à envoyer")
        }
    }

    private fun creerNotificationCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(CHANNEL_ID, "MySafe Service", NotificationManager.IMPORTANCE_LOW)
            canal.setShowBadge(false)
            canal.enableVibration(false)
            canal.setSound(null, null)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(canal)
        }
    }

    private fun creerNotificationDiscrete(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MySafe")
            .setContentText("Service actif")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        arreterSuiviGPSInterne()
        instance = null
        Log.d(TAG, "🔒 AGENT ARRÊTÉ")
    }
}
