package com.mysafe.mysafe
import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.hardware.Camera
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")
class StreamingActivity : AppCompatActivity() {
    private lateinit var surfaceView: SurfaceView
    private lateinit var surfaceHolder: SurfaceHolder
    private lateinit var flipBtn: Button
    private lateinit var micBtn: Button
    private lateinit var testSoundBtn: Button
    private lateinit var statusText: TextView
    private var camera: Camera? = null
    private var cameraFacing = Camera.CameraInfo.CAMERA_FACING_BACK
    private var microphoneActive = AtomicBoolean(true)
    private var soundTestActive = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioJob: Job? = null
    private val sampleRate = 16000
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_streaming)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = findViewById(R.id.surface_view)
        surfaceHolder = surfaceView.holder
        flipBtn = findViewById(R.id.flip_btn)
        micBtn = findViewById(R.id.mic_btn)
        testSoundBtn = findViewById(R.id.test_sound_btn)
        statusText = findViewById(R.id.status_text)

        findViewById<Button>(R.id.stop_btn).setOnClickListener {
            stopAll()
            finish()
        }

        flipBtn.setOnClickListener { flipCamera() }

        micBtn.setOnClickListener { toggleMicrophone() }

        testSoundBtn.setOnClickListener { toggleSoundTest() }

        findViewById<Button>(R.id.hide_indicators_btn).setOnClickListener {
            showHideIndicatorsGuide()
        }

        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 100)
        } else {
            startEverything()
        }

        surfaceHolder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                startCameraPreview(holder)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                releaseCamera()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
        })
    }

    private fun startEverything() {
        startCameraPreview(surfaceHolder)
        startMicrophone()
        microphoneActive.set(true)
        micBtn.text = "🎙️ Micro: ON"
        micBtn.setBackgroundColor(0xFF66BB6A.toInt())
        statusText.text = "✅ Caméra + Micro actifs — Cliquez 'Tester Son' pour entendre"
    }

    private fun startCameraPreview(holder: SurfaceHolder) {
        try {
            releaseCamera()
            val cameraId = findCameraId(cameraFacing)
            if (cameraId == -1) {
                Toast.makeText(this, "❌ Caméra non disponible", Toast.LENGTH_SHORT).show()
                return
            }

            camera = Camera.open(cameraId)
            
            // ✅ CORRECTION DEFINITIVE DE L'ORIENTATION
            val rotation = windowManager.defaultDisplay.rotation
            val degrees = when (rotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(cameraId, info)
            var result = when (info.facing) {
                Camera.CameraInfo.CAMERA_FACING_FRONT -> (info.orientation + degrees) % 360
                Camera.CameraInfo.CAMERA_FACING_BACK -> (info.orientation - degrees + 360) % 360
                else -> 0
            }
            camera?.setDisplayOrientation(result)
            
            camera?.setPreviewDisplay(holder)
            camera?.startPreview()

            val camName = if (cameraFacing == Camera.CameraInfo.CAMERA_FACING_BACK) "arrière" else "avant"
            statusText.text = "✅ Caméra $camName active — Micro prêt"
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Erreur caméra: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startMicrophone() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            statusText.text = "❌ Autorisation micro manquante"
            return
        }
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfigIn,
            audioFormat,
            bufferSize
        )
        audioRecord?.startRecording()
        microphoneActive.set(true)
        statusText.text = "✅ Micro ENREGISTRE — cliquez 'Tester Son' pour ECOUTER"
    }

    private fun toggleSoundTest() {
        if (soundTestActive.get()) {
            stopSoundTest()
        } else {
            startSoundTest()
        }
    }

    private fun startSoundTest() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return

        soundTestActive.set(true)
        testSoundBtn.text = "🔊 Arrêter"
        testSoundBtn.setBackgroundColor(0xFF4CAF50.toInt())
        statusText.text = "🔊 ECOUTE EN DIRECT — Parle ! Tu devrais t'entendre !"

        // ✅ Initialisation du lecteur audio pour DIFFUSER le son capturé
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfigOut)
                    .setEncoding(audioFormat)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()

        // ✅ BOUCLE EN TEMPS RÉEL : Capturer -> Diffuser = tu t'entends INSTANTANÉMENT
        audioJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(bufferSize)
            while (soundTestActive.get() && isActive) {
                val read = audioRecord?.read(buffer, 0, bufferSize) ?: -1
                if (read > 0) {
                    audioTrack?.write(buffer, 0, read)
                }
            }
        }
    }

    private fun stopSoundTest() {
        soundTestActive.set(false)
        audioJob?.cancel()
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {}
        testSoundBtn.text = "🔊 Tester Son"
        testSoundBtn.setBackgroundColor(0xFFFF9800.toInt())
        statusText.text = "✅ Test son terminé — Micro toujours actif"
    }

    private fun toggleMicrophone() {
        if (microphoneActive.get()) {
            stopSoundTest()
            stopMicrophone()
            microphoneActive.set(false)
            micBtn.text = "🎙️ Micro: OFF"
            micBtn.setBackgroundColor(0xFF777777.toInt())
            statusText.text = "⏹ Micro désactivé"
        } else {
            startMicrophone()
            microphoneActive.set(true)
            micBtn.text = "🎙️ Micro: ON"
            micBtn.setBackgroundColor(0xFF66BB6A.toInt())
        }
    }

    private fun stopMicrophone() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {}
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

    private fun stopAll() {
        stopSoundTest()
        stopMicrophone()
        releaseCamera()
    }

    private fun showHideIndicatorsGuide() {
        AlertDialog.Builder(this)
            .setTitle("👁️ Masquer les témoins")
            .setMessage(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "⚠️ Ces points verts sont des indicateurs système.\n\n" +
                    "Pour les masquer :\n" +
                    "⚙️ Paramètres → Sécurité et confidentialité → Indicateurs et autorisations → Désactiver \"Afficher les indicateurs d'utilisation\""
                } else {
                    "✅ Sur cette version d'Android, pas de témoins visibles."
                }
            )
            .setPositiveButton("⚙️ Paramètres") { _, _ ->
                startActivity(Intent(Settings.ACTION_PRIVACY_SETTINGS))
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val allGranted = grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                startEverything()
            } else {
                Toast.makeText(this, "❌ Autorisations requises", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAll()
    }
}
