package com.mysafe.mysafe
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.app.Activity

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val testTv = findViewById<TextView>(R.id.test_tv)
        val startBtn = findViewById<Button>(R.id.start_btn)
        val stopBtn = findViewById<Button>(R.id.stop_btn)

        testTv.text = "✅ L'APPLI S'OUVRE PARFAITEMENT !"

        startBtn.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
            } else {
                startService(Intent(this, LocationService::class.java))
                Toast.makeText(this, "✅ Service démarré", Toast.LENGTH_SHORT).show()
            }
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, LocationService::class.java))
            Toast.makeText(this, "⏹ Service arrêté", Toast.LENGTH_SHORT).show()
        }
    }
}
