package com.mysafe.mysafe
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class StreamingActivity : AppCompatActivity() {
    private lateinit var mode: String
    private lateinit var previewView: PreviewView
    private var cameraFacing = CameraSelector.LENS_FACING_BACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_streaming)

        mode = intent.getStringExtra("mode") ?: "video"
        previewView = findViewById(R.id.preview_view)

        findViewById<Button>(R.id.stop_btn).setOnClickListener { finish() }
        findViewById<Button>(R.id.flip_btn)?.setOnClickListener { flipCamera() }

        if (mode == "video") {
            findViewById<TextView>(R.id.title_text).text = "📹 CAMÉRA — Aperçu en direct"
            startCameraPreview()
        } else {
            findViewById<TextView>(R.id.title_text).text = "🎙️ AUDIO — Enregistrement local"
            previewView.visibility = View.GONE
            Toast.makeText(this, "🎙️ Micro actif — Son enregistré localement", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCameraPreview() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
            return
        }
        bindCameraPreview()
    }

    private fun bindCameraPreview() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(cameraFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview
                )
                Toast.makeText(this, "✅ VIDÉO EN DIRECT — Caméra ${if (cameraFacing == CameraSelector.LENS_FACING_BACK) "arrière" else "avant"}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "❌ Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun flipCamera() {
        cameraFacing = if (cameraFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindCameraPreview()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startCameraPreview()
        } else {
            Toast.makeText(this, "❌ Autorisation caméra refusée", Toast.LENGTH_SHORT).show()
        }
    }
}
