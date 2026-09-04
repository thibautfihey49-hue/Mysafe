package com.mysafe.mysafe
import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

@Suppress("DEPRECATION")
class StreamingActivity : AppCompatActivity() {
    private lateinit var mode: String
    private lateinit var surfaceView: SurfaceView
    private lateinit var surfaceHolder: SurfaceHolder
    private var camera: Camera? = null
    private var cameraFacing = Camera.CameraInfo.CAMERA_FACING_BACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_streaming)

        mode = intent.getStringExtra("mode") ?: "video"
        surfaceView = findViewById(R.id.surface_view)
        surfaceHolder = surfaceView.holder

        findViewById<Button>(R.id.stop_btn).setOnClickListener {
            releaseCamera()
            finish()
        }

        findViewById<Button>(R.id.flip_btn).setOnClickListener {
            flipCamera()
        }

        if (mode == "video") {
            findViewById<TextView>(R.id.title_text).text = "📹 CAMÉRA EN DIRECT"
            surfaceHolder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    startCameraPreview(holder)
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    releaseCamera()
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            })
        } else {
            findViewById<TextView>(R.id.title_text).text = "🎙️ AUDIO — Micro actif"
            surfaceView.visibility = View.GONE
            Toast.makeText(this, "🎙️ Micro actif en arrière-plan", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCameraPreview(holder: SurfaceHolder) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
            return
        }

        try {
            releaseCamera()
            val cameraId = findCameraId(cameraFacing)
            if (cameraId == -1) {
                Toast.makeText(this, "❌ Caméra non disponible", Toast.LENGTH_SHORT).show()
                return
            }

            camera = Camera.open(cameraId)
            camera?.setPreviewDisplay(holder)
            camera?.startPreview()

            val camName = if (cameraFacing == Camera.CameraInfo.CAMERA_FACING_BACK) "arrière" else "avant"
            Toast.makeText(this, "✅ Caméra $camName activée !", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Erreur caméra: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findCameraId(facing: Int): Int {
        for (i in 0 until Camera.getNumberOfCameras()) {
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(i, info)
            if (info.facing == facing) return i
        }
        return -1
    }

    private fun flipCamera() {
        cameraFacing = if (cameraFacing == Camera.CameraInfo.CAMERA_FACING_BACK) {
            Camera.CameraInfo.CAMERA_FACING_FRONT
        } else {
            Camera.CameraInfo.CAMERA_FACING_BACK
        }
        if (surfaceHolder.surface != null && surfaceHolder.surface.isValid) {
            startCameraPreview(surfaceHolder)
        }
    }

    private fun releaseCamera() {
        try {
            camera?.stopPreview()
            camera?.release()
            camera = null
        } catch (e: Exception) {}
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startCameraPreview(surfaceHolder)
        } else {
            Toast.makeText(this, "❌ Autorisation caméra refusée", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseCamera()
    }
}
