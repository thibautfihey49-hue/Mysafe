package com.mysafe.mysafe
import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MainActivity : Activity() {
    private lateinit var map: MapView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var cameraBtn: Button
    private lateinit var historyList: ListView
    private lateinit var historyAdapter: ArrayAdapter<String>
    private var marker: Marker? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationService.ACTION_UPDATE) {
                val lat = intent.getDoubleExtra("lat", 0.0)
                val lon = intent.getDoubleExtra("lon", 0.0)
                val time = intent.getStringExtra("time") ?: ""
                updateMap(lat, lon, time)
                historyAdapter.insert("$time | ${String.format("%.6f", lat)}, ${String.format("%.6f", lon)}", 0)
                if (historyAdapter.count > 50) historyAdapter.remove(historyAdapter.getItem(49))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller?.setZoom(15.0)

        startBtn = findViewById(R.id.start_btn)
        stopBtn = findViewById(R.id.stop_btn)
        cameraBtn = findViewById(R.id.camera_btn)
        historyList = findViewById(R.id.history_list)

        historyAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        historyList.adapter = historyAdapter

        startBtn.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
            } else {
                startService(Intent(this, LocationService::class.java))
                Toast.makeText(this, "Démarré", Toast.LENGTH_SHORT).show()
            }
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, LocationService::class.java))
            Toast.makeText(this, "Arrêté", Toast.LENGTH_SHORT).show()
        }

        cameraBtn.setOnClickListener {
            startActivity(Intent(this, StreamingActivity::class.java))
        }

        registerReceiver(receiver, IntentFilter(LocationService.ACTION_UPDATE))
    }

    private fun updateMap(lat: Double, lon: Double, time: String) {
        runOnUiThread {
            val point = GeoPoint(lat, lon)
            map.controller?.animateTo(point)
            if (marker == null) {
                marker = Marker(map)
                marker?.icon = getDrawable(android.R.drawable.ic_menu_mylocation)
                map.overlays.add(marker)
            }
            marker?.position = point
            marker?.title = "$time\n$lat, $lon"
        }
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
    override fun onDestroy() { super.onDestroy(); unregisterReceiver(receiver) }
}
