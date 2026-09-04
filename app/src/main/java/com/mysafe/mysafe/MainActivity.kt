package com.mysafe.mysafe
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.start_btn).setOnClickListener {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
            } else {
                startService(Intent(this, LocationService::class.java))
                Toast.makeText(this, "✅ Démarré", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.stop_btn).setOnClickListener {
            stopService(Intent(this, LocationService::class.java))
            Toast.makeText(this, "⏹ Arrêté", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        code: Int,
        permissions: Array<out String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 100 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "✅ Permission OK — clique Démarrer", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "⚠️ Permission refusée", Toast.LENGTH_SHORT).show()
        }
    }
}
