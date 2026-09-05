package com.mysafe.mysafe

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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

class MainActivity : AppCompatActivity(), LocationListener {
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
    private lateinit var locationManager: LocationManager
    private val historyList = mutableListOf<String>()
    private lateinit var historyAdapter: ArrayAdapter<String>

    private val positionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "MYSAFE_POSITION_UPDATE") {
                val lat = intent.getDoubleExtra("lat", 0.0)
                val lon = intent.getDoubleExtra("lon", 0.0)
                val time = intent.getStringExtra("time") ?: "??:??:??"
                Log.d(TAG, "📨 Position reçue DE L'AUTRE: $lat, $lon")
                updateMapPosition(lat, lon, time)
                Toast.makeText(this@MainActivity, "✅ Position reçue DE L'AUTRE !", Toast.LENGTH_SHORT).show()
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

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        requestAllPermissions()

        statusText.text = "⏳ En attente de la position de l'autre..."

        btnClearHistory.setOnClickListener {
            historyList.clear()
            historyAdapter.notifyDataSetChanged()
            Toast.makeText(this, "🗑️ Historique effacé", Toast.LENGTH_SHORT).show()
        }

        // 📹 DEMANDER LA CAMÉRA DE L'AUTRE
        stream_video_btn.setOnClickListener {
            val target = targetPhoneInput.text.toString().trim()
            if (target.isBlank()) {
                Toast.makeText(this, "⚠️ Entrez le numéro de l'autre d'abord !", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            envoyerCommande(target, "MYSAFE_CAMERA_ON")
            Toast.makeText(this, "📩 Demande CAMÉRA envoyée à l'autre !", Toast.LENGTH_SHORT).show()
        }

        // 📍 DEMANDER LA POSITION DE L'AUTRE
        stream_audio_btn.setOnClickListener {
            val target = targetPhoneInput.text.toString().trim()
            if (target.isBlank()) {
                Toast.makeText(this, "⚠️ Entrez le numéro de l'autre d'abord !", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            envoyerCommande(target, "MYSAFE_SEND_POS")
            Toast.makeText(this, "📩 Demande POSITION envoyée à l'autre !", Toast.LENGTH_SHORT).show()
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
        
        Log.d(TAG, "✅ Émetteur prêt — commande uniquement")
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
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            var allOk = true
            for (r in grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) allOk = false
            }
            if (allOk) {
                Toast.makeText(this, "✅ Prêt ! Envoyez des commandes à l'autre téléphone", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun envoyerCommande(numero: String, commande: String) {
        Log.d(TAG, "📤 Envoi à $numero : $commande")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "❌ Permission SMS manquante", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val smsManager = SmsManager.getDefault()
            try {
                smsManager.sendDataMessage(numero, null, 50006.toShort(), commande.toByteArray(Charsets.UTF_8), null, null)
            } catch (e: Exception) {
                smsManager.sendTextMessage(numero, null, commande, null, null)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Échec envoi : ${e.message}", Toast.LENGTH_LONG).show()
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
        statusText.text = "✅ Position de l'autre mise à jour"

        val entry = "🎯 AUTRE — $time\n$lat, $lon"
        if (!historyList.contains(entry)) {
            historyList.add(0, entry)
            historyAdapter.notifyDataSetChanged()
        }
    }

    override fun onLocationChanged(location: Location) {}
    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
    override fun onPause() { super.onPause(); map.onPause() }
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(positionReceiver)
    }
}
