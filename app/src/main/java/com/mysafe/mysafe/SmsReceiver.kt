package com.mysafe.mysafe
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != "android.provider.Telephony.SMS_RECEIVED") return
        context ?: return

        val messages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } else {
            @Suppress("DEPRECATION")
            val pdus = intent.extras?.get("pdus") as? Array<*>
            pdus?.map { SmsMessage.createFromPdu(it as ByteArray) } ?: emptyList()
        }

        for (msg in messages) {
            val body = msg.messageBody ?: ""
            val sender = msg.originatingAddress ?: ""
            if (body.startsWith("MYSAFE:")) {
                abortBroadcast()
                val parts = body.split(":")
                if (parts.size >= 4) {
                    try {
                        val updateIntent = Intent("MYSAFE_POSITION_UPDATE")
                        updateIntent.putExtra("lat", parts[1].toDouble())
                        updateIntent.putExtra("lon", parts[2].toDouble())
                        updateIntent.putExtra("time", parts[3])
                        updateIntent.putExtra("from", if (parts.size >= 5) parts[4] else sender.takeLast(4))
                        context.sendBroadcast(updateIntent)
                    } catch (e: Exception) {}
                }
            }
        }
    }
}
