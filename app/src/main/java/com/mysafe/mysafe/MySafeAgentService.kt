package com.mysafe.mysafe

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlin.math.roundToInt

class MySafeAgentService : Service() {
    companion object {
        fun demarrerGPS(context: Context) { instance?.demarrerSuiviGPSInterne() }
        fun arreterGPS(context: Context) { instance?.arreterSuiviGPSInterne() }
        const val ACTION_ORDRE = "ACTION_ORDRE"
        const val ACTION_DEMARRER = "DEMARRER"
        const val ACTION_ARRETER = "ARRETER"
        private const val CHANNEL_ID = "MySafeAgent"
        private const val MIN_DISTANCE = 10f
        private const val MIN_INTERVALLE = 90000L
        var instance: MySafeAgentService? = null
        var dernierePosition: Location? = null
        var numeroMaitre: String? = null
    }

    private var lm: LocationManager? = null
    private var estActif = false
    private var dernierEnvoi = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = verifierEtEnvoyer(loc)
        override fun onStatusChanged(p0: String?, p1: Int, p2: android.os.Bundle?) {}
        override fun onProviderEnabled(p0: String) {}
        override fun onProviderDisabled(p0: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        creerCanal()
        demarrerService()
        Log.d("Fantome", "👻 Agent actif")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DEMARRER -> demarrerSuivi()
            ACTION_ARRETER -> arreterSuivi()
            ACTION_ORDRE -> {
                val ordre = intent.getStringExtra("ORDRE") ?: ""
                numeroMaitre = intent.getStringExtra("NUMERO")
                executerOrdre(ordre)
            }
        }
        return START_STICKY
    }

    private fun executerOrdre(ordre: String) {
        val o = ordre.trim().uppercase()
        when {
            o == "POSITION" || o == "LOC" -> envoyerPosition()
            o == "DEMARRER" || o == "START" -> { demarrerSuivi(); repondre("OK-SUIVI") }
            o == "STOP" || o == "ARRETER" -> { arreterSuivi(); repondre("OK-STOP") }
        }
    }

    private fun demarrerSuivi() {
        if (estActif || !aPermissionGPS()) return
        estActif = true
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_INTERVALLE, MIN_DISTANCE, locationListener)
            lm?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, MIN_INTERVALLE, MIN_DISTANCE, locationListener)
            dernierePosition?.let { verifierEtEnvoyer(it, true) }
        } catch (e: Exception) { Log.e("Fantome", "GPS erreur", e) }
    }

    private fun arreterSuivi() { estActif = false; lm?.removeUpdates(locationListener) }

    private fun verifierEtEnvoyer(loc: Location, forcer: Boolean = false) {
        val maintenant = System.currentTimeMillis()
        val derniere = dernierePosition
        val distance = derniere?.distanceTo(loc) ?: 999f
        val temps = maintenant - dernierEnvoi
        if (forcer || distance >= MIN_DISTANCE || temps >= MIN_INTERVALLE) {
            dernierePosition = loc
            dernierEnvoi = maintenant
            numeroMaitre?.let { envoyerSmsPosition(it, loc) }
        }
    }

    private fun envoyerPosition() {
        val loc = dernierePosition ?: lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        loc?.let { numeroMaitre?.let { n -> envoyerSmsPosition(n, it) } }
    }

    private fun envoyerSmsPosition(dest: String, loc: Location) {
        val msg = "!!${"%.6f".format(loc.latitude)},${"%.6f".format(loc.longitude)},${loc.altitude.roundToInt()}"
        SmsManager.getDefault().sendTextMessage(dest, null, msg, null, null)
    }

    private fun repondre(msg: String) {
        numeroMaitre?.let {
            SmsManager.getDefault().sendTextMessage(it, null, "!!$msg", null, null)
        }
    }

    private fun aPermissionGPS(): Boolean {
        return ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun demarrerService() {
        if (!aPermissionGPS()) return
        try {
            startForeground(1, NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MySafe").setContentText("Actif")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setPriority(NotificationCompat.PRIORITY_LOW).setSilent(true).build())
        } catch (e: Exception) {}
    }

    private fun creerCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MySafe", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false); enableVibration(false); setSound(null, null)
                })
        }
    }

    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { super.onDestroy(); arreterSuivi(); instance = null }
}
