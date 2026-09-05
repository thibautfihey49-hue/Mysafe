package com.mysafe.mysafe

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
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

class MainActivity : AppCompatActivity() {
    private val REQUETE_PERMISSIONS = 12345
    private val permissionsRequises = arrayOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.SEND_SMS,
        android.Manifest.permission.RECEIVE_SMS,
        android.Manifest.permission.POST_NOTIFICATIONS
    )
    init {
        // Initialiser le moteur de macros au démarrage
    }
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
        startService(Intent(this, MySafeAgentService::class.java))
        // ✅ Demander TOUTES les permissions au démarrage
        demanderPermissions()
        startService(Intent(this, MySafeAgentService::class.java))
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
            if (estMonNumero(target)) {
                Log.d(TAG, "📹 Même numéro → ouverture directe caméra")
                val camIntent = Intent(this, StreamingActivity::class.java)
                startActivity(camIntent)
            } else {
                envoyerCommande(target, "MYSAFE_CAMERA_ON")
            }
        }

        stream_audio_btn.setOnClickListener {
            val target = targetPhoneInput.text.toString().trim()
            if (target.isBlank()) {
                Toast.makeText(this, "⚠️ Entrez le numéro d'abord !", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            derniereReception = 0
            statusText.text = "📤 Demande position..."

            if (estMonNumero(target)) {
                Log.d(TAG, "📍 Même numéro → récupération directe")
                obtenirMaPositionDirect()
            } else {
                envoyerCommande(target, "MYSAFE_SEND_POS")
                android.os.Handler(mainLooper).postDelayed({
                    if (derniereReception == 0L) {
                        statusText.text = "⚠️ Pas de réponse — vérifiez:\n• Permissions SMS\n• Numéro correct"
                    }
                }, 15000)
            }
        }

        btnFloatMap.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    startService(Intent(this, FloatingMapWindow::class.java))
                    Toast.makeText(this, "🗺️ Carte flottante ouverte !", Toast.LENGTH_LONG).show()
                } else {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName"))
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

    private fun estMonNumero(numero: String): Boolean {
        val nettoye = numero.replace(Regex("[^0-9]"), "").takeLast(9)
        return nettoye.length >= 6
    }

    private fun obtenirMaPositionDirect() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "⚠️ Permission localisation manquante", Toast.LENGTH_SHORT).show()
            return
        }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        if (loc == null) loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        if (loc == null) {
            statusText.text = "❌ Position inconnue — activez le GPS et bougez !"
            Toast.makeText(this, "❌ Position inconnue", Toast.LENGTH_SHORT).show()
            return
        }

        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        Log.d(TAG, "✅ Position récupérée: ${loc.latitude}, ${loc.longitude}")

        val updateIntent = Intent("MYSAFE_POSITION_UPDATE")
        updateIntent.putExtra("lat", loc.latitude)
        updateIntent.putExtra("lon", loc.longitude)
        updateIntent.putExtra("time", time)
        sendBroadcast(updateIntent)
    }

    private fun requestAllPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.SEND_SMS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECEIVE_SMS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.READ_SMS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO)

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            Toast.makeText(this, "✅ Toutes permissions accordées ! Prêt !", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            var allOk = true
            for ((i, p) in permissions.withIndex()) {
                val ok = grantResults.getOrElse(i) { -1 } == PackageManager.PERMISSION_GRANTED
                Log.d(TAG, "$p : ${if(ok) "✅" else "❌"}")
                if (!ok) allOk = false
            }
            if (allOk) {
                Toast.makeText(this, "✅ Permissions accordées !", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun envoyerCommande(numero: String, commande: String) {
        Log.d(TAG, "📤 Envoi à $numero : $commande")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "❌ Permission SMS manquante", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val smsManager = SmsManager.getDefault()
            try {
                smsManager.sendTextMessage(numero, null, commande, null, null)
                Toast.makeText(this, "📩 Commande envoyée !", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                try {
                    smsManager.sendDataMessage(numero, null, 50006.toShort(),
                        commande.toByteArray(Charsets.UTF_8), null, null)
                    Toast.makeText(this, "📩 Commande envoyée !", Toast.LENGTH_SHORT).show()
                } catch (e2: Exception) {
                    Toast.makeText(this, "❌ Échec envoi", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Erreur : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateMapPosition(lat: Double, lon: Double, time: String) {
        val gp = GeoPoint(lat, lon)
        if (otherMarker == null) {
            otherMarker = Marker(map).apply {
                position = gp
                icon = resources.getDrawable(android.R.drawable.ic_menu_compass, null)
                title = "📍 Position"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            map.overlays.add(otherMarker)
        } else {
            otherMarker?.position = gp
        }
        map.invalidate()
        map.controller?.animateTo(gp)
        statusText.text = "✅ Position mise à jour !"

        val entry = "🎯 — $time\n$lat, $lon"
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
