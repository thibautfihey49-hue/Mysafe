package com.mysafe.mysafe

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.location.Geocoder

data class HistoryItem(
    val lat: Double,
    val lon: Double,
    val time: String,
    val from: String,
    val address: String
)

class MainActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var statusText: TextView
    private lateinit var targetPhoneInput: EditText
    private lateinit var myPhoneInput: EditText
    private lateinit var toggleBtn: Button
    private lateinit var lastUpdateText: TextView
    private lateinit var titleHeader: TextView
    private lateinit var hiddenMenu: LinearLayout
    private lateinit var historyContainer: LinearLayout
    private lateinit var btnClearHistory: Button
    
    private var myPhoneNumber = ""
    private var isMonitoring = false
    private var positionMarker: Marker? = null
    private var titleClickCount = 0
    
    private val history = mutableListOf<HistoryItem>()
    private val MIN_HISTORY_DISTANCE_METERS = 50f

    private val positionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val lat = intent.getDoubleExtra("lat", 0.0)
            val lon = intent.getDoubleExtra("lon", 0.0)
            val time = intent.getStringExtra("time") ?: "--:--:--"
            val from = intent.getStringExtra("from") ?: "???"
            updateMapPosition(lat, lon, time, from)
            getAddressAsync(lat, lon) { address ->
                addToHistory(lat, lon, time, from, address)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val osmdroidDir = File(getExternalFilesDir(null), "osmdroid")
        osmdroidDir.mkdirs()
        Configuration.getInstance().osmdroidBasePath = osmdroidDir
        Configuration.getInstance().osmdroidTileCache = File(osmdroidDir, "tiles")
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.DEFAULT_TILE_SOURCE)
        map.setMultiTouchControls(true)
        map.controller?.setZoom(15.0)

        statusText = findViewById(R.id.status)
        targetPhoneInput = findViewById(R.id.target_phone)
        myPhoneInput = findViewById(R.id.my_phone)
        toggleBtn = findViewById(R.id.toggle_btn)
        lastUpdateText = findViewById(R.id.last_update)
        titleHeader = findViewById(R.id.title_header)
        hiddenMenu = findViewById(R.id.hidden_menu)
        historyContainer = findViewById(R.id.history_container)
        btnClearHistory = findViewById(R.id.btn_clear_history)

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

        btnClearHistory.setOnClickListener {
            history.clear()
            historyContainer.removeAllViews()
            Toast.makeText(this, "🗑️ Historique effacé !", Toast.LENGTH_SHORT).show()
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

    private fun getAddressAsync(lat: Double, lon: Double, callback: (String) -> Unit) {
        Thread {
            var addressStr = "Adresse inconnue"
            try {
                val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    addressStr = addr.thoroughfare ?: addr.featureName ?: "Sans nom de rue"
                    if (addr.subLocality != null) addressStr = addr.subLocality + ", " + addressStr
                }
            } catch (e: Exception) {
                addressStr = "Erreur adresse"
            }
            runOnUiThread { callback(addressStr) }
        }.start()
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
            statusText.text = "🟢 Surveillance active\nPosition toutes les 1min30"
            statusText.setTextColor(Color.GREEN)
            Toast.makeText(this, "Surveillance démarrée !", Toast.LENGTH_SHORT).show()
            LocationService.lastLocation?.let { 
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                updateMapPosition(it.latitude, it.longitude, time, "MOI")
                getAddressAsync(it.latitude, it.longitude) { address ->
                    addToHistory(it.latitude, it.longitude, time, "MOI", address)
                }
            }
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
            map.controller?.animateTo(point)
            map.invalidate()
            lastUpdateText.text = "📍 MàJ : $time — Depuis : $from"
        }
    }

    private fun addToHistory(lat: Double, lon: Double, time: String, from: String, address: String) {
        var tropProche = false
        val lastItem = history.lastOrNull()
        if (lastItem != null) {
            val distance = FloatArray(1)
            android.location.Location.distanceBetween(
                lastItem.lat, lastItem.lon, lat, lon, distance
            )
            if (distance[0] < MIN_HISTORY_DISTANCE_METERS) {
                tropProche = true
            }
        }
        
        if (!tropProche) {
            val item = HistoryItem(lat, lon, time, from, address)
            history.add(item)
            
            runOnUiThread {
                val entry = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(8, 8, 8, 8)
                    setBackgroundColor(0xFFF0F0F0.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 4, 0, 4) }
                }

                val topRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                val timeText = TextView(this@MainActivity).apply {
                    text = time
                    textSize = 12f
                    setTextColor(0xFF666666.toInt())
                    setPadding(0, 0, 8, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                val addrText = TextView(this@MainActivity).apply {
                    text = address
                    textSize = 13f
                    setTextColor(0xFF222222.toInt())
                    setPadding(0, 0, 8, 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    maxLines = 1
                    isSingleLine = true
                }

                topRow.addView(timeText)
                topRow.addView(addrText)

                val miniMap = MapView(this@MainActivity).apply {
                    setTileSource(TileSourceFactory.DEFAULT_TILE_SOURCE)
                    setMultiTouchControls(false)
                    isClickable = false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        120
                    )
                    controller?.setZoom(14.0)
                    controller?.setCenter(GeoPoint(lat, lon))
                    setBackgroundColor(0xFFE8E8E8.toInt())
                    val miniMarker = Marker(this).apply {
                        position = GeoPoint(lat, lon)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        icon = resources.getDrawable(android.R.drawable.ic_menu_mylocation, null)
                    }
                    overlays.add(miniMarker)
                    setOnClickListener {
                        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon")
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                }

                entry.addView(topRow)
                entry.addView(miniMap)
                
                historyContainer.addView(entry, 0)
            }
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
