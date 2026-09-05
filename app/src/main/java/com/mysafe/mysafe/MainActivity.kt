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
    private lateinit var myPhoneInput: EditText
    private lateinit var toggleBtn: Button
    private lateinit var btnClearHistory: Button
    private lateinit var stream_video_btn: Button
    private lateinit var stream_audio_btn: Button
    private lateinit var btnFloatMap: Button
    private lateinit var historyListView: ListView
    
    private var myMarker: Marker? = null
    private var otherMarker: Marker? = null
    private var isTracking = false
    private lateinit var locationManager: LocationManager
    private val historyList = mutableListOf<String>()
    private lateinit var historyAdapter: ArrayAdapter<String>
    private var lastLocation: Location? = null

    private val positionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "MYSAFE_POSITION_UPDATE") {
                val lat = intent.getDoubleExtra("lat", 0.0)
                val lon = intent.getDoubleExtra("lon", 0.0)
                val time = intent.getStringExtra("time") ?: "??:??:??"
                Log.d(TAG, "📨 Position reçue: $lat, $lon")
                updateMapPosition(lat, lon, time)
                Toast.makeText(this@MainActivity, "✅ Position reçue !", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val envoyerPosReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val target = intent?.getStringExtra("target_phone") ?: return
            Log.d(TAG, "📤 Demande d'envoyer position à: $target")
            envoyerMaPosition(target)
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
        myPhoneInput = findViewById(R.id.my_phone_input)
        toggleBtn = findViewById(R.id.toggle_tracking_btn)
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

        toggleBtn.setOnClickListener { toggleTracking() }
        btnClearHistory.setOnClickListener {
            historyList.clear()
            historyAdapter.notifyDataSetChanged()
            Toast.makeText(this, "🗑️ Historique effacé", Toast.LENGTH_SHORT).show()
        }

        stream_video_btn.setOnClickListener {
            val target = targetPhoneInput.text.toString().trim()
            if (target.isBlank()) {
                Toast.makeText(this, "⚠️ Entrez le numéro cible d'abord !", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            envoyerCommande(target, "MYSAFE_CAMERA_ON")
        }

        stream_audio_btn.setOnClickListener {
            val target = targetPhoneInput.text.toString().trim()
            if (target.isBlank()) {
                Toast.makeText(this, "⚠️ Entrez le numéro cible d'abord !", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            envoyerCommande(target, "MYSAFE_SEND_POS")
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
        registerReceiver(envoyerPosReceiver, IntentFilter("ENVOYER_POSITION"), RECEIVER_NOT_EXPORTED)
        
        Log.d(TAG, "✅ MainActivity prête !")
    }

    private fun requestAllPermissions() {
        val needed = mutableListOf<String>()
        
        // 📍 Localisation
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // 📩 SMS — CRUCIAL POUR LA RÉCEPTION
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.SEND_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECEIVE_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_SMS)
        }
        // 📹 Streaming
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            Toast.makeText(this, "✅ Toutes permissions déjà accordées !", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "✅ TOUTES les permissions accordées ! Réception SMS active ✅", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "⚠️ Certaines permissions manquent — Réception SMS peut ne pas fonctionner", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun envoyerCommande(numero: String, commande: String) {
        Log.d(TAG, "📤 Envoi à $numero : $commande")
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "❌ Permission SEND_SMS manquante", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            val smsManager = SmsManager.getDefault()
            try {
                val donnees = commande.toByteArray(Charsets.UTF_8)
                smsManager.sendDataMessage(numero, null, 50006.toShort(), donnees, null, null)
                Toast.makeText(this, "📩 Commande envoyée (SMS de données) !", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "✅ SMS de données envoyé")
                return
            } catch (e: Exception) {
                Log.w(TAG, "SMS de données impossible, essai en SMS normal", e)
            }
            smsManager.sendTextMessage(numero, null, commande, null, null)
            Toast.makeText(this, "📩 Commande envoyée (SMS normal) !", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "✅ SMS normal envoyé")
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Échec envoi SMS : ${e.message}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Erreur envoi SMS", e)
        }
    }

    private fun envoyerMaPosition(numero: String) {
        try {
            val loc = lastLocation ?: run {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
            if (loc == null) {
                Toast.makeText(this, "❌ Position inconnue — activez le GPS", Toast.LENGTH_SHORT).show()
                return
            }
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val msg = "MYSAFE:${loc.latitude}:${loc.longitude}:$time:ME"
            Log.d(TAG, "📤 Envoi ma position à $numero : $msg")
            
            try {
                SmsManager.getDefault().sendDataMessage(numero, null, 50006.toShort(), msg.toByteArray(Charsets.UTF_8), null, null)
            } catch (e: Exception) {
                SmsManager.getDefault().sendTextMessage(numero, null, msg, null, null)
            }
            Toast.makeText(this, "📩 Position envoyée !", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Échec envoi position : ${e.message}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Erreur envoi position", e)
        }
    }

    private fun toggleTracking() {
        if (isTracking) stopTracking() else startTracking()
    }

    private fun startTracking() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "⚠️ Autorisez la localisation d'abord", Toast.LENGTH_SHORT).show()
            return
        }
        isTracking = true
        toggleBtn.text = "⏹️ ARRÊTER LE SUIVI"
        statusText.text = "✅ Suivi démarré — Envoi sur demande"
        
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60000, 10f, this)
        } catch (e: Exception) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60000, 10f, this)
        }
    }

    private fun stopTracking() {
        isTracking = false
        toggleBtn.text = "▶️ DÉMARRER LE SUIVI"
        statusText.text = "⏸️ Suivi arrêté"
        locationManager.removeUpdates(this)
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val gp = GeoPoint(location.latitude, location.longitude)
        if (myMarker == null) {
            myMarker = Marker(map).apply {
                position = gp
                icon = resources.getDrawable(android.R.drawable.ic_menu_mylocation, null)
                title = "Ma position"
            }
            map.overlays.add(myMarker)
        } else {
            myMarker?.position = gp
        }
        map.invalidate()
        map.controller?.animateTo(gp)

        val entry = "📍 MOI — $time\n${location.latitude}, ${location.longitude}"
        if (!historyList.contains(entry)) {
            historyList.add(0, entry)
            historyAdapter.notifyDataSetChanged()
        }
    }

    private fun updateMapPosition(lat: Double, lon: Double, time: String) {
        val gp = GeoPoint(lat, lon)
        if (otherMarker == null) {
            otherMarker = Marker(map).apply {
                position = gp
                icon = resources.getDrawable(android.R.drawable.ic_menu_compass, null)
                title = "Position cible"
            }
            map.overlays.add(otherMarker)
        } else {
            otherMarker?.position = gp
        }
        map.invalidate()

        val entry = "🎯 CIBLE — $time\n$lat, $lon"
        if (!historyList.contains(entry)) {
            historyList.add(0, entry)
            historyAdapter.notifyDataSetChanged()
        }
    }

    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
    override fun onPause() { super.onPause(); map.onPause() }
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(positionReceiver)
        unregisterReceiver(envoyerPosReceiver)
    }
}
