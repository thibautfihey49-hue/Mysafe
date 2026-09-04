package com.mysafe.mysafe
import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var statusText: TextView
    private lateinit var targetPhoneInput: EditText
    private lateinit var myPhoneInput: EditText
    private lateinit var toggleBtn: Button
    private lateinit var lastUpdateText: TextView
    private lateinit var titleHeader: TextView
    private lateinit var hiddenMenu: LinearLayout
    private var myPhoneNumber = ""
    private var isMonitoring = false
    private val positions = mutableListOf<GeoPoint>()
    private var positionMarker: Marker? = null
    private var pathLine: Polyline? = null
    private var titleClickCount = 0

    private val positionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val lat = intent.getDoubleExtra("lat", 0.0)
            val lon = intent.getDoubleExtra("lon", 0.0)
            val time = intent.getStringExtra("time") ?: "--:--:--"
            val from = intent.getStringExtra("from") ?: "???"
            updateMapPosition(lat, lon, time, from)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.DEFAULT_TILE_SOURCE)
        map.setMultiTouchControls(true)
        map.controller().setZoom(15.0)
        
        statusText = findViewById(R.id.status)
        targetPhoneInput = findViewById(R.id.target_phone)
        myPhoneInput = findViewById(R.id.my_phone)
        toggleBtn = findViewById(R.id.toggle_btn)
        lastUpdateText = findViewById(R.id.last_update)
        titleHeader = findViewById(R.id.title_header)
        hiddenMenu = findViewById(R.id.hidden_menu)

        myPhoneNumber = getMyPhoneNumber()
        myPhoneInput.setText(myPhoneNumber)

        titleHeader.setOnClickListener {
            titleClickCount++
            if (titleClickCount >= 5) {
                titleClickCount = 0
                hiddenMenu.visibility = if (hiddenMenu.visibility == View.GONE) View.VISIBLE else View.GONE
                if (hiddenMenu.visibility == View.VISIBLE) Toast.makeText(this, "📹 Menu Streaming déverrouillé", Toast.LENGTH_SHORT).show()
            }
        }

        toggleBtn.setOnClickListener { toggleMonitoring() }

        findViewById<Button>(R.id.stream_video_btn).setOnClickListener {
            val target = targetPhoneInput.text.toString().trim()
            if (target.isBlank()) { Toast.makeText(this, "Entrez le numéro cible", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val intent = Intent(this, StreamingActivity::class.java)
            intent.putExtra("mode", "video")
            intent.putExtra("target_phone", target)
            startActivity(intent)
        }

        findViewById<Button>(R.id.stream_audio_btn).setOnClickListener {
            val target = targetPhoneInput.text.toString().trim()
            if (target.isBlank()) { Toast.makeText(this, "Entrez le numéro cible", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val intent = Intent(this, StreamingActivity::class.java)
            intent.putExtra("mode", "audio")
            intent.putExtra("target_phone", target)
            startActivity(intent)
        }

        findViewById<Button>(R.id.stream_stop_btn).setOnClickListener {
            sendBroadcast(Intent("STOP_STREAMING"))
            Toast.makeText(this, "⏹ Streaming arrêté", Toast.LENGTH_SHORT).show()
        }

        registerReceiver(positionReceiver, IntentFilter("MYSAFE_POSITION_UPDATE"), RECEIVER_NOT_EXPORTED)
        requestAllPermissions()
    }

    private fun getMyPhoneNumber(): String {
        return try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            tm.line1Number ?: ""
        } catch (e: Exception) { "" }
    }

    private fun toggleMonitoring() {
        val targetPhone = targetPhoneInput.text.toString().trim()
        val myPhone = myPhoneInput.text.toString().trim()
        if (targetPhone.isBlank() || myPhone.isBlank()) { Toast.makeText(this, "Remplissez les deux numéros", Toast.LENGTH_SHORT).show(); return }

        if (!isMonitoring) {
            val intent = Intent(this, LocationService::class.java)
            intent.action = LocationService.ACTION_START
            intent.putExtra(LocationService.EXTRA_TARGET_PHONE, targetPhone)
            intent.putExtra(LocationService.EXTRA_MY_PHONE, myPhone)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            
            isMonitoring = true
            toggleBtn.text = "⏹ ARRETER LA SURVEILLANCE"
            toggleBtn.setBackgroundColor(Color.RED)
            statusText.text = "🟢 Surveillance active\nPosition chaque minute"
            statusText.setTextColor(Color.GREEN)
            Toast.makeText(this, "Surveillance démarrée !", Toast.LENGTH_SHORT).show()
            LocationService.lastLocation?.let { updateMapPosition(it.latitude, it.longitude, "MAINTENANT", "MOI") }
        } else {
            val intent = Intent(this, LocationService::class.java)
            intent.action = LocationService.ACTION_STOP
            startService(intent)
            isMonitoring = false
            toggleBtn.text = "▶ DEMARRER LA SURVEILLANCE"
            toggleBtn.setBackgroundColor(Color.parseColor("#4CAF50"))
            statusText.text = "🔴 Surveillance arrêtée"
            statusText.setTextColor(Color.GRAY)
            Toast.makeText(this, "Surveillance arrêtée", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMapPosition(lat: Double, lon: Double, time: String, from: String) {
        val point = GeoPoint(lat, lon)
        positions.add(point)
        runOnUiThread {
            positionMarker?.let { map.overlays.remove(it) }
            positionMarker = Marker(map).apply {
                position = point
                title = "Position [$from]"
                snippet = "$lat, $lon\n$time"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = resources.getDrawable(android.R.drawable.ic_menu_mylocation, null)
            }
            map.overlays.add(positionMarker)
            pathLine?.let { map.overlays.remove(it) }
            if (positions.size > 1) {
                pathLine = Polyline().apply { setPoints(positions); color = Color.BLUE; width = 3f }
                map.overlays.add(pathLine)
            }
            map.controller().animateTo(point)
            map.invalidate()
            lastUpdateText.text = "📍 MàJ : $time — Depuis : $from"
        }
    }

    private fun requestAllPermissions() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS,
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.CHANGE_NETWORK_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) needed.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val missing = needed.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing, 100)
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
    override fun onDestroy() { super.onDestroy(); unregisterReceiver(positionReceiver) }
}
