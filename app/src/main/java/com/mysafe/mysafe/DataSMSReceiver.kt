package com.mysafe.mysafe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class DataSMSReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "Fantome"
        var dernierNumero: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION != intent.action) return

            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (msg in messages) {
                val contenu = msg.messageBody?.trim() ?: ""
                val numero = msg.displayOriginatingAddress

                if (!contenu.startsWith("!!")) continue

                abortBroadcast()
                dernierNumero = numero

                val serviceIntent = Intent(context, MySafeAgentService::class.java)
                serviceIntent.action = MySafeAgentService.ACTION_ORDRE
                serviceIntent.putExtra("ORDRE", contenu.removePrefix("!!").trim())
                serviceIntent.putExtra("NUMERO", numero)
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur récepteur", e)
        }
    }
}
