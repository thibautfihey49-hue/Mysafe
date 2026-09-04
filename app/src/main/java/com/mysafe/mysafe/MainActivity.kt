package com.mysafe.mysafe
import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var latTv: TextView
    private lateinit var lonTv: TextView
    private lateinit var timeTv: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationService.ACTION_UPDATE) {
                val lat = intent.getDoubleExtra("lat", 0.0)
                val lon = intent.getDoubleExtra("lon", 0.0)
                val time = intent.getStringExtra("time") ?: ""
                latTv.text = "Latitude : $lat"
                lonTv.text = "Longitude : $lon"
                timeTv.text = "Dernière MAJ : $time"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        latTv = findViewById(R.id.lat_tv)
        lonTv = findViewById(R.id.lon_tv)
        timeTv = findViewById(R.id.time_tv)
        startBtn = findViewById(R.id.start_btn)
        stopBtn = findViewById(R.id.stop_btn)

        startBtn.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
            } else {
                startService(Intent(this, LocationService::class.java))
                Toast.makeText(this, "✅ Surveillance DÉMARRÉE", Toast.LENGTH_SHORT).show()
            }
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, LocationService::class.java))
            latTv.text = "Latitude : --"
            lonTv.text = "Longitude : --"
            timeTv.text = "Dernière MAJ : --"
            Toast.makeText(this, "⏹ Surveillance ARRÊTÉE", Toast.LENGTH_SHORT).show()
        }

        registerReceiver(receiver, IntentFilter(LocationService.ACTION_UPDATE))
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 100 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "✅ Permission GPS OK — clique DÉMARRER", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "⚠️ Permission GPS NÉCESSAIRE", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }
}
