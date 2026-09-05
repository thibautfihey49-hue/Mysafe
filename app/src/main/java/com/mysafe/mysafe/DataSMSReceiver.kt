package com.mysafe.mysafe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage
import android.util.Log

class DataSMSReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MySafe_SMS"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != "android.provider.Telephony.SMS_RECEIVED") return
        context ?: return

        Log.d(TAG, "📨 SMS REÇU — LECTURE EN COURS...")

        val bundle = intent.extras ?: return
        val pdus = bundle["pdus"] as? Array<*> ?: return

        var commandeReconnue = false
        var derniereCommande = ""
        var dernierNumero = ""

        for (pdu in pdus) {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                SmsMessage.createFromPdu(pdu as ByteArray, "3gpp")
            } else {
                @Suppress("DEPRECATION")
                SmsMessage.createFromPdu(pdu as ByteArray)
            }

            val corps = sms.messageBody ?: ""
            val numero = sms.originatingAddress ?: ""

            Log.d(TAG, "📨 De: $numero | Contenu: $corps")

            when {
                corps.trim() == "MYSAFE_SEND_POS" ||
                corps.trim() == "MYSAFE_START_TRACK" ||
                corps.trim() == "MYSAFE_STOP_TRACK" ||
                corps.trim() == "MYSAFE_CAMERA_ON" -> {
                    commandeReconnue = true
                    derniereCommande = corps.trim()
                    dernierNumero = numero
                }
                corps.startsWith("MYSAFE_POS:") -> {
                    commandeReconnue = true
                    val data = corps.removePrefix("MYSAFE_POS:").split(",")
                    if (data.size >= 2) {
                        try {
                            val lat = data[0].toDouble()
                            val lon = data[1].toDouble()
                            val time = if (data.size >= 3) data[2] else ""
                            
                            val updateIntent = Intent("MYSAFE_POSITION_UPDATE")
                            updateIntent.setPackage(context.packageName)
                            updateIntent.putExtra("lat", lat)
                            updateIntent.putExtra("lon", lon)
                            updateIntent.putExtra("time", time)
                            context.sendBroadcast(updateIntent)
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Erreur parsing position", e)
                        }
                    }
                }
            }
        }

        if (commandeReconnue && derniereCommande.isNotEmpty()) {
            Log.d(TAG, "🔕 COMMANDE RECONNUE — SMS MASQUÉ !")
            abortBroadcast()
            
            MySafeAgentService.commandeRecue?.invoke(derniereCommande, dernierNumero)
        }
    }
}
