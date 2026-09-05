package com.mysafe.mysafe

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MySafe_SMS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") {
            Log.d(TAG, "❌ Action ignorée: ${intent.action}")
            return
        }

        Log.d(TAG, "📨 SMS REÇU ! Lecture en cours...")

        val bundle = intent.extras ?: return
        val pdus = bundle["pdus"] as? Array<*> ?: return
        
        var commandeTraitee = false
        
        for (pdu in pdus) {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                SmsMessage.createFromPdu(pdu as ByteArray, "3gpp")
            } else {
                @Suppress("DEPRECATION")
                SmsMessage.createFromPdu(pdu as ByteArray)
            }

            val messageBody = sms.messageBody ?: ""
            val origin = sms.originatingAddress ?: ""

            Log.d(TAG, "📨 De: $origin | Contenu: $messageBody")

            when {
                messageBody.equals("MYSAFE_SEND_POS", ignoreCase = true) -> {
                    Log.d(TAG, "✅ COMMANDE: ENVOYER POSITION")
                    envoyerMaPosition(context, origin)
                    commandeTraitee = true
                }
                messageBody.equals("MYSAFE_CAMERA_ON", ignoreCase = true) -> {
                    Log.d(TAG, "✅ COMMANDE: OUVRIR CAMÉRA")
                    val camIntent = Intent(context, StreamingActivity::class.java)
                    camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(camIntent)
                    commandeTraitee = true
                }
                messageBody.startsWith("MYSAFE:", ignoreCase = true) -> {
                    Log.d(TAG, "✅ RÉPONSE: POSITION REÇUE !")
                    val parts = messageBody.split(":")
                    if (parts.size >= 4) {
                        val lat = parts[1].toDoubleOrNull()
                        val lon = parts[2].toDoubleOrNull()
                        val time = parts[3]
                        if (lat != null && lon != null) {
                            val updateIntent = Intent("MYSAFE_POSITION_UPDATE")
                            updateIntent.putExtra("lat", lat)
                            updateIntent.putExtra("lon", lon)
                            updateIntent.putExtra("time", time)
                            context.sendBroadcast(updateIntent)
                            Log.d(TAG, "📍 Position affichée: $lat, $lon")
                        }
                    }
                    commandeTraitee = true
                }
            }
        }

        // ✅ ANNULE SEULEMENT SI ON A TRAITÉ LA COMMANDE
        if (commandeTraitee) {
            Log.d(TAG, "🔕 Commande traitée — masquer de la messagerie système")
            abortBroadcast()
        }
    }

    private fun envoyerMaPosition(context: Context, numero: String) {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "❌ Permission localisation manquante")
                return
            }

            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            var loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc == null) {
                loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
            
            if (loc == null) {
                Log.e(TAG, "❌ Position inconnue — activez le GPS et bougez un peu !")
                return
            }

            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val msg = "MYSAFE:${loc.latitude}:${loc.longitude}:$time:AUTRE"
            
            Log.d(TAG, "📤 RÉPONDRE à $numero : $msg")
            
            try {
                SmsManager.getDefault().sendDataMessage(numero, null, 50006.toShort(), msg.toByteArray(Charsets.UTF_8), null, null)
                Log.d(TAG, "✅ Réponse envoyée en SMS de données")
            } catch (e: Exception) {
                Log.w(TAG, "SMS de données échec, essai en SMS normal", e)
                SmsManager.getDefault().sendTextMessage(numero, null, msg, null, null)
                Log.d(TAG, "✅ Réponse envoyée en SMS normal")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur envoi réponse", e)
        }
    }
}
