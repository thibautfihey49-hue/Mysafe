package com.mysafe.mysafe
import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*

class MainActivity : Activity() {
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
                positionTv.text = "📍 $time\nLat: $lat\nLon: $lon"
                historyAdapter.insert("$time | ${String.format("%.6f", lat)}, ${String.format("%.6f", lon)}", 0)
                if (historyAdapter.count > 50) historyAdapter.remove(historyAdapter.getItem(49))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        positionTv = findViewById(R.id.position_tv)
        historyList = findViewById(R.id.history_list)
        startBtn = findViewById(R.id.start_btn)
        stopBtn = findViewById(R.id.stop_btn)
        cameraBtn = findViewById(R.id.camera_btn)

        historyAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        historyList.adapter = historyAdapter

        startBtn.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
            } else {
                startService(Intent(this, LocationService::class.java))
                Toast.makeText(this, "✅ Surveillance démarrée", Toast.LENGTH_SHORT).show()
            }
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, LocationService::class.java))
            positionTv.text = "Surveillance arrêtée"
            Toast.makeText(this, "⏹ Surveillance arrêtée", Toast.LENGTH_SHORT).show()
        }

        cameraBtn.setOnClickListener {
            startActivity(Intent(this, StreamingActivity::class.java))
        }

        registerReceiver(receiver, IntentFilter(LocationService.ACTION_UPDATE))
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }
}
