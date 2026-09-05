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

    private val positionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "MYSAFE_POSITION_UPDATE") {
                val lat = intent.getDoubleExtra("lat", 0.0)
                val lon = intent.getDoubleExtra("lon", 0.0)
                val time = intent.getStringExtra("time") ?: "??:??:??"
                updateMapPosition(lat, lon, time)
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
        requestPermissions()

        toggleBtn.setOnClickListener { toggleTracking() }
        btnClearHistory.setOnClickListener {
            historyList.clear()
            historyAdapter.notifyDataSetChanged()
            Toast.makeText(this, "🗑️ Historique effacé", Toast.LENGTH_SHORT).show()
        }
        stream_video_btn.setOnClickListener {
            val target = targetPhoneInput.text.toString().trim()
            if (target.isBlank()) { Toast.makeText(this, "Entrez le numéro cible", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            try { SmsManager.getDefault().sendDataMessage(target, null, 50006.toShort(), "MYSAFE_CAMERA_ON".toByteArray(Charsets.UTF_8), null, null) }
            catch (e: Exception) { SmsManager.getDefault().sendTextMessage(target, null, "MYSAFE_CAMERA_ON", null, null) }
            Toast.makeText(this, "📩 Commande envoyée !", Toast.LENGTH_SHORT).show()
        }
        stream_audio_btn.setOnClickListener {
            val target = targetPhoneInput.text.toString().trim()
            if (target.isBlank()) { Toast.makeText(this, "Entrez le numéro cible", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            try { SmsManager.getDefault().sendDataMessage(target, null, 50006.toShort(), "MYSAFE_SEND_POS".toByteArray(Charsets.UTF_8), null, null) }
            catch (e: Exception) { SmsManager.getDefault().sendTextMessage(target, null, "MYSAFE_SEND_POS", null, null) }
            Toast.makeText(this, "📩 Demande envoyée !", Toast.LENGTH_SHORT).show()
        }
        btnFloatMap.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startService(Intent(this, FloatingMapWindow::class.java))
                Toast.makeText(this, "🗺️ Carte flottante ouverte !", Toast.LENGTH_LONG).show()
            } else {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
            }
        }
        registerReceiver(positionReceiver, IntentFilter("MYSAFE_POSITION_UPDATE"), RECEIVER_NOT_EXPORTED)
    }

    private fun requestPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.SEND_SMS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECEIVE_SMS)
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
    }

    private fun toggleTracking() { if (isTracking) stopTracking() else startTracking() }
    private fun startTracking() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Autorisez la localisation", Toast.LENGTH_SHORT).show(); return }
        isTracking = true; toggleBtn.text = "⏹️ ARRÊTER"; statusText.text = "✅ Suivi actif"
        try { locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000, 5f, this) }
        catch (e: Exception) { locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000, 5f, this) }
    }
    private fun stopTracking() {
        isTracking = false; toggleBtn.text = "▶️ DÉMARRER"; statusText.text = "⏸️ Arrêté"
        locationManager.removeUpdates(this)
    }

    override fun onLocationChanged(location: Location) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val gp = GeoPoint(location.latitude, location.longitude)
        if (myMarker == null) {
            myMarker = Marker(map).apply { position = gp; icon = resources.getDrawable(android.R.drawable.ic_menu_mylocation, null); title = "Moi" }
            map.overlays.add(myMarker)
        } else myMarker?.position = gp
        map.invalidate()
        val entry = "📍 MOI — $time\n${location.latitude}, ${location.longitude}"
        if (!historyList.contains(entry)) { historyList.add(0, entry); historyAdapter.notifyDataSetChanged() }
    }

    private fun updateMapPosition(lat: Double, lon: Double, time: String) {
        val gp = GeoPoint(lat, lon)
        if (otherMarker == null) {
            otherMarker = Marker(map).apply { position = gp; icon = resources.getDrawable(android.R.drawable.ic_menu_compass, null); title = "Cible" }
            map.overlays.add(otherMarker)
        } else otherMarker?.position = gp
        map.invalidate()
        val entry = "🎯 CIBLE — $time\n$lat, $lon"
        if (!historyList.contains(entry)) { historyList.add(0, entry); historyAdapter.notifyDataSetChanged() }
    }

    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
    override fun onPause() { super.onPause(); map.onPause() }
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onDestroy() { super.onDestroy(); unregisterReceiver(positionReceiver) }
}
