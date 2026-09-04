package com.mysafe.mysafe
import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import android.widget.*

class MainActivity : Activity() {
    private lateinit var mapView: WebView
    private lateinit var positionTv: TextView
    private lateinit var historyList: ListView
    private lateinit var historyAdapter: ArrayAdapter<String>
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var cameraBtn: Button

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationService.ACTION_UPDATE) {
                val lat = intent.getDoubleExtra("lat", 0.0)
                val lon = intent.getDoubleExtra("lon", 0.0)
                val time = intent.getStringExtra("time") ?: ""
                updatePosition(lat, lon, time)
                historyAdapter.insert("$time | ${String.format("%.6f", lat)}, ${String.format("%.6f", lon)}", 0)
                if (historyAdapter.count > 50) historyAdapter.remove(historyAdapter.getItem(49))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.map_webview)
        positionTv = findViewById(R.id.position_tv)
        historyList = findViewById(R.id.history_list)
        startBtn = findViewById(R.id.start_btn)
        stopBtn = findViewById(R.id.stop_btn)
        cameraBtn = findViewById(R.id.camera_btn)

        historyAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        historyList.adapter = historyAdapter

        // 🗺️ CONFIGURATION CARTE — SANS OSMDROID, SÛR ET STABLE
        mapView.settings.javaScriptEnabled = true
        mapView.settings.domStorageEnabled = true
        mapView.settings.setGeolocationEnabled(true)
        mapView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }
        }

        // Charge la carte OpenStreetMap Leaflet — TOUT INTÉGRÉ, PAS DE CRASH
        val mapHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes">
                <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
                <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                <style>html,body{margin:0;padding:0;height:100%;overflow:hidden;}#map{height:100%;width:100%;}</style>
            </head>
            <body>
                <div id="map"></div>
                <script>
                    var map = L.map('map').setView([47.47, -0.55], 13);
                    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                        attribution: '© OpenStreetMap'
                    }).addTo(map);
                    var marker = null;
                    function updatePosition(lat, lon) {
                        if (!marker) {
                            marker = L.marker([lat, lon]).addTo(map);
                        } else {
                            marker.setLatLng([lat, lon]);
                        }
                        map.setView([lat, lon], 16);
                        marker.bindPopup('Position actuelle').openPopup();
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        mapView.loadDataWithBaseURL("file:///android_asset/", mapHtml, "text/html", "UTF-8", null)

        // 🎮 BOUTONS
        startBtn.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
            } else {
                startService(Intent(this, LocationService::class.java))
                Toast.makeText(this, "✅ Surveillance démarrée — Position mise à jour toutes les minutes", Toast.LENGTH_SHORT).show()
            }
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, LocationService::class.java))
            positionTv.text = "📍 Surveillance arrêtée"
            Toast.makeText(this, "⏹ Surveillance arrêtée", Toast.LENGTH_SHORT).show()
        }

        cameraBtn.setOnClickListener {
            startActivity(Intent(this, StreamingActivity::class.java))
        }

        registerReceiver(receiver, IntentFilter(LocationService.ACTION_UPDATE))
    }

    private fun updatePosition(lat: Double, lon: Double, time: String) {
        positionTv.text = "📍 $time\nLat: ${String.format("%.6f", lat)}\nLon: ${String.format("%.6f", lon)}"
        // Déplace le marqueur sur la carte
        mapView.evaluateJavascript("updatePosition($lat, $lon);", null)
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 100 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "✅ Permission GPS accordée — clique Démarrer", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "⚠️ Permission GPS refusée", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        (mapView.parent as ViewGroup).removeView(mapView)
        mapView.destroy()
    }
}
