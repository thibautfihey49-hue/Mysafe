package com.mysafe.mysafe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return
        val bundle = intent.extras ?: return
        val pdus = bundle["pdus"] as? Array<*> ?: return
        
        for (pdu in pdus) {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                SmsMessage.createFromPdu(pdu as ByteArray, "3gpp")
            } else {
                @Suppress("DEPRECATION")
                SmsMessage.createFromPdu(pdu as ByteArray)
            }
            val msg = sms.messageBody ?: continue
            val expediteur = sms.originatingAddress ?: continue
            
            when {
                msg.startsWith("MYSAFE:") -> {
                    val parts = msg.split(":")
                    if (parts.size >= 4) {
                        val lat = parts[1].toDoubleOrNull()
                        val lon = parts[2].toDoubleOrNull()
                        val time = parts[3]
                        if (lat != null && lon != null) {
                            val updateIntent = Intent("MYSAFE_POSITION_UPDATE")
                            updateIntent.putExtra("lat", lat)
                            updateIntent.putExtra("lon", lon)
                            updateIntent.putExtra("time", time)
                            updateIntent.putExtra("from", "AUTRE")
                            context.sendBroadcast(updateIntent)
                        }
                    }
                }
                msg == "MYSAFE_CAMERA_ON" -> {
                    val camIntent = Intent(context, StreamingActivity::class.java)
                    camIntent.putExtra("mode", "video")
                    camIntent.putExtra("target_phone", expediteur)
                    camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(camIntent)
                }
                msg == "MYSAFE_SEND_POS" -> {
                    val posIntent = Intent("ENVOYER_POSITION")
                    posIntent.putExtra("target_phone", expediteur)
                    context.sendBroadcast(posIntent)
                }
            }
        }
    }
}
