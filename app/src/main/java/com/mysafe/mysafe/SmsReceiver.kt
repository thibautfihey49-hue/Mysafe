package com.mysafe.mysafe

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
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
        private var locationManager: LocationManager? = null
        private var lastLocation: Location? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") {
            return
        }

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
            val origin = sms.originatingAddress ?: ""

            Log.d(TAG, "📨 De $origin : $messageBody")

            when {
                // 📤 DEMANDE DE POSITION — RÉPONDRE AVEC LA POSITION DE CE TÉLÉPHONE
                messageBody.equals("MYSAFE_SEND_POS", ignoreCase = true) -> {
                    Log.d(TAG, "✅ Demande de position reçue — ENVOYER MA POSITION !")
                    abortBroadcast()
                    envoyerMaPosition(context, origin)
                }
                // 📹 DEMANDE DE CAMÉRA — OUVRIR LA CAMÉRA SUR CE TÉLÉPHONE
                messageBody.equals("MYSAFE_CAMERA_ON", ignoreCase = true) -> {
                    Log.d(TAG, "✅ Demande caméra reçue — OUVRIR MA CAMÉRA !")
                    abortBroadcast()
                    val camIntent = Intent(context, StreamingActivity::class.java)
                    camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(camIntent)
                }
            }
        }
    }

    private fun envoyerMaPosition(context: Context, numero: String) {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "❌ Permission localisation manquante")
                return
            }

            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            // Obtenir la dernière position connue
            var loc = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc == null) {
                loc = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
            
            if (loc == null) {
                Log.e(TAG, "❌ Position inconnue")
                return
            }

            lastLocation = loc
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val msg = "MYSAFE:${loc.latitude}:${loc.longitude}:$time:AUTRE"
            
            Log.d(TAG, "📤 Envoyer ma position à $numero : $msg")
            
            try {
                SmsManager.getDefault().sendDataMessage(numero, null, 50006.toShort(), msg.toByteArray(Charsets.UTF_8), null, null)
            } catch (e: Exception) {
                SmsManager.getDefault().sendTextMessage(numero, null, msg, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur envoi position", e)
        }
    }
}
