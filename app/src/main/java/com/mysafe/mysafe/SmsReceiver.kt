package com.mysafe.mysafe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MySafe_SMS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") {
            Log.d(TAG, "Action ignorée: ${intent.action}")
            return
        }

        Log.d(TAG, "📨 SMS reçu !")
        
        val bundle = intent.extras ?: return
        val pdus = bundle["pdus"] as? Array<*> ?: return
        
        for (pdu in pdus) {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                SmsMessage.createFromPdu(pdu as ByteArray, "3gpp")
            } else {
                @Suppress("DEPRECATION")
                SmsMessage.createFromPdu(pdu as ByteArray)
            }

            val messageBody = sms.messageBody ?: ""
            val origin = sms.originatingAddress ?: "INCONNU"
            val port = sms.port

            Log.d(TAG, "De: $origin | Port: $port | Contenu: $messageBody")

            // ✅ TRAITER LES COMMANDES — SMS NORMAUX ET SMS DE DONNÉES
            when {
                messageBody.startsWith("MYSAFE:", ignoreCase = true) -> {
                    Log.d(TAG, "✅ Commande POSITION détectée !")
                    abortBroadcast() // ✅ NE PAS AFFICHER DANS LA MESSAGERIE
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
                            Log.d(TAG, "📍 Position mise à jour: $lat, $lon")
                        }
                    }
                }
                messageBody.equals("MYSAFE_CAMERA_ON", ignoreCase = true) -> {
                    Log.d(TAG, "✅ Commande CAMÉRA détectée !")
                    abortBroadcast()
                    val camIntent = Intent(context, StreamingActivity::class.java)
                    camIntent.putExtra("target_phone", origin)
                    camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(camIntent)
                }
                messageBody.equals("MYSAFE_SEND_POS", ignoreCase = true) -> {
                    Log.d(TAG, "✅ Demande ENVOYER POSITION détectée !")
                    abortBroadcast()
                    val posIntent = Intent("ENVOYER_POSITION")
                    posIntent.putExtra("target_phone", origin)
                    context.sendBroadcast(posIntent)
                }
            }
        }
    }
}
