package com.mysafe.mysafe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return
        val pduArr = intent.extras?.get("pdus") as? Array<*> ?: return
        for (pdu in pduArr) {
            val sms = if (Build.VERSION.SDK_INT >= 23) SmsMessage.createFromPdu(pdu as ByteArray, "3gpp")
            else @Suppress("DEPRECATION") SmsMessage.createFromPdu(pdu as ByteArray)
            val body = sms.messageBody ?: continue
            val from = sms.originatingAddress ?: continue
            when {
                body.startsWith("MYSAFE:") -> {
                    val p = body.split(":")
                    if (p.size >= 4) {
                        val lat = p[1].toDoubleOrNull()
                        val lon = p[2].toDoubleOrNull()
                        if (lat != null && lon != null) {
                            val i = Intent("MYSAFE_POSITION_UPDATE")
                            i.putExtra("lat", lat); i.putExtra("lon", lon); i.putExtra("time", p[3])
                            ctx.sendBroadcast(i)
                        }
                    }
                }
                body == "MYSAFE_CAMERA_ON" -> {
                    val i = Intent(ctx, StreamingActivity::class.java)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i)
                }
            }
        }
    }
}
