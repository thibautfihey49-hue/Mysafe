package com.mysafe.mysafe

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MySafe_Main"
        private const val REQUEST_PERMISSIONS = 1001
    }

    private lateinit var map: MapView
    private lateinit var statusText: TextView
    private lateinit var targetPhoneInput: EditText
    private lateinit var btnClearHistory: Button
    private lateinit var stream_video_btn: Button
    private lateinit var stream_audio_btn: Button
    private lateinit var btnFloatMap: Button
    private lateinit var historyListView: ListView
    
    private var otherMarker: Marker? = null
    private val historyList = mutableListOf<String>()
    private lateinit var historyAdapter: ArrayAdapter<String>
    private var derniereReception = 0L

    private val positionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "MYSAFE_POSITION_UPDATE") {
                val lat = intent.getDoubleExtra("lat", 0.0)
                val lon = intent.getDoubleExtra("lon", 0.0)
                val time = intent.getStringExtra("time") ?: "??:??:??"
                derniereReception = System.currentTimeMillis()
                Log.d(TAG, "✅ ✅ POSITION REÇUE ! $lat, $lon")
                updateMapPosition(lat, lon, time)
                Toast.makeText(this@MainActivity, "✅ ✅ Position reçue !", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val osmdroidDir = File(getExternalFilesDir(null), "osmdroid")
        Configuration.getInstance().osmdroidBasePath = osmdroidDir
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))

        map = findViewById(R.id.map)
        statusText = findViewById(R.id.status_text)
        targetPhoneInput = findViewById(R.id.target_phone_input)
        btnClearHistory = findViewById(R.id.btn_clear_history)
        stream_video_btn = findViewById(R.id.stream_video_btn)
        stream_audio_btn = findViewById(R.id.stream_audio_btn)
        btnFloatMap = findViewById(R.id.btn_float_map)
        historyListView = findViewById(R.id.history_list_view)

        map.setTileSource(TileSourceFactory.DEFAULT_TILE_SOURCE)
        map.setMultiTouchControls(true)
        map.controller?.setZoom(15.0)
        map.controller?.setCenter(GeoPoint(47.47, -0.55))

        historyAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, historyList)
        historyListView.adapter = historyAdapter

        statusText.text = "⏳ En attente de commande..."

        requestAllPermissions()

        btnClearHistory.setOnClickListener {
            historyList.clear()
            historyAdapter.notifyDataSetChanged()
            Toast.makeText(this, "🗑️ Historique effacé", Toast.LENGTH_SHORT).show()
        }

        stream_video_btn.setOnClickListener {
            val target = targetPhoneInput.text.toString().trim()
            if (target.isBlank()) {
                Toast.makeText(this, "⚠️ Entrez le numéro d'abord !", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            envoyerCommande(target, "MYSAFE_CAMERA_ON")
        }

        stream_audio_btn.setOnClickListener {
            val target = targetPhoneInput.text.toString().trim()
            if (target.isBlank()) {
                Toast.makeText(this, "⚠️ Entrez le numéro d'abord !", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            derniereReception = 0
            statusText.text = "📤 Envoi demande position..."
            envoyerCommande(target, "MYSAFE_SEND_POS")
            
            // ⏱️ Vérifier si on reçoit une réponse dans 15 secondes
            android.os.Handler(mainLooper).postDelayed({
                if (derniereReception == 0L) {
                    statusText.text = "⚠️ Pas de réponse — vérifiez:\n• Permissions SMS\n• Numéro correct\n• SMS normal activé"
                    Toast.makeText(this, "⚠️ Pas de réponse reçue", Toast.LENGTH_LONG).show()
                }
            }, 15000)
        }

        btnFloatMap.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    startService(Intent(this, FloatingMapWindow::class.java))
                    Toast.makeText(this, "🗺️ Carte flottante ouverte !", Toast.LENGTH_LONG).show()
                } else {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))
                    startActivity(intent)
                    Toast.makeText(this, "⚠️ Autorisez l'affichage par-dessus les apps", Toast.LENGTH_LONG).show()
                }
            } else {
                startService(Intent(this, FloatingMapWindow::class.java))
            }
        }

        registerReceiver(positionReceiver, IntentFilter("MYSAFE_POSITION_UPDATE"), RECEIVER_NOT_EXPORTED)
        
        Log.d(TAG, "✅ MainActivity prête !")
    }

    private fun requestAllPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.SEND_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECEIVE_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            Toast.makeText(this, "✅ Toutes permissions accordées !", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            var allOk = true
            for ((i, p) in permissions.withIndex()) {
                val ok = grantResults.getOrElse(i) { -1 } == PackageManager.PERMISSION_GRANTED
                Log.d(TAG, "Permission $p : ${if(ok) "✅" else "❌"}")
                if (!ok) allOk = false
            }
            if (allOk) {
                Toast.makeText(this, "✅ TOUTES permissions accordées ! Prêt à l'emploi !", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "⚠️ Certaines permissions manquent — vérifiez dans les paramètres", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun envoyerCommande(numero: String, commande: String) {
        Log.d(TAG, "📤 ENVOYER à $numero : \"$commande\"")
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "❌ Permission SEND_SMS manquante", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val smsManager = SmsManager.getDefault()
            
            // ✅ D'abord en SMS NORMAL — plus fiable pour les tests sur le même numéro
            try {
                smsManager.sendTextMessage(numero, null, commande, null, null)
                Toast.makeText(this, "📩 Commande envoyée ! Attendez la réponse...", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "✅ SMS normal envoyé")
                return
            } catch (e: Exception) {
                Log.w(TAG, "SMS normal échec", e)
            }
            
            // ✅ Puis essayer en SMS de données
            try {
                smsManager.sendDataMessage(numero, null, 50006.toShort(), commande.toByteArray(Charsets.UTF_8), null, null)
                Toast.makeText(this, "📩 Commande envoyée (données) !", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "✅ SMS de données envoyé")
            } catch (e: Exception) {
                Toast.makeText(this, "❌ Échec envoi : ${e.message}", Toast.LENGTH_LONG).show()
                Log.e(TAG, "❌ Échec total envoi", e)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Erreur : ${e.message}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Erreur envoi", e)
        }
    }

    private fun updateMapPosition(lat: Double, lon: Double, time: String) {
        val gp = GeoPoint(lat, lon)
        if (otherMarker == null) {
            otherMarker = Marker(map).apply {
                position = gp
                icon = resources.getDrawable(android.R.drawable.ic_menu_compass, null)
                title = "📍 Position de l'autre"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            map.overlays.add(otherMarker)
        } else {
            otherMarker?.position = gp
        }
        map.invalidate()
        map.controller?.animateTo(gp)
        statusText.text = "✅ Position reçue !"

        val entry = "🎯 AUTRE — $time\n$lat, $lon"
        if (!historyList.contains(entry)) {
            historyList.add(0, entry)
            historyAdapter.notifyDataSetChanged()
        }
    }

    override fun onPause() { super.onPause(); map.onPause() }
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(positionReceiver)
    }
}
