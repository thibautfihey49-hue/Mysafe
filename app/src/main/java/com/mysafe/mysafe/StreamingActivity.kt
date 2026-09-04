package com.mysafe.mysafe
import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.File
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean

class StreamingActivity : AppCompatActivity() {
    private lateinit var mode: String
    private lateinit var targetPhone: String
    private var surfaceView: View? = null
    private var cameraManager: android.hardware.camera2.CameraManager? = null
    private var cameraId: String? = null
    private var recording = AtomicBoolean(false)
    private var running = AtomicBoolean(true)
    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var receiveThread: Thread? = null
    private var sendThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputStream: java.io.OutputStream? = null
    private var inputStream: java.io.InputStream? = null

    private val wifiDirectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!enabled) {
                        Toast.makeText(this@StreamingActivity, "WiFi Direct non activé", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    // ✅ CORRIGÉ : Utilisation de la bonne clé
                    val info = intent.getParcelableExtra<WifiP2pInfo>("wifiP2pInfo")
                    if (info?.groupFormed == true) {
                        if (info.isGroupOwner) startServer()
                        else connectToOwner(info.groupOwnerAddress)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_streaming)

        mode = intent.getStringExtra("mode") ?: "video"
        targetPhone = intent.getStringExtra("target_phone") ?: ""

        surfaceView = findViewById(R.id.surface_view)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager

        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = wifiP2pManager?.initialize(this, Looper.getMainLooper(), null)

        val filter = IntentFilter()
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        registerReceiver(wifiDirectReceiver, filter)

        findViewById<Button>(R.id.stop_btn).setOnClickListener { stopStreaming(); finish() }

        if (mode == "video") {
            findViewById<TextView>(R.id.title_text).text = "📹 Streaming Vidéo (WiFi Direct)"
            surfaceView?.visibility = View.VISIBLE
            startCamera()
        } else {
            findViewById<TextView>(R.id.title_text).text = "🎙️ Streaming Audio (WiFi Direct)"
            surfaceView?.visibility = View.GONE
            startAudio()
        }

        val intentFilter = IntentFilter("STOP_STREAMING")
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { stopStreaming(); finish() }
        }, intentFilter)
    }

    private fun startCamera() {
        try {
            cameraId = cameraManager?.cameraIdList?.firstOrNull {
                cameraManager?.getCameraCharacteristics(it)
                    ?.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                    android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
            } ?: cameraManager?.cameraIdList?.firstOrNull()

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

            mediaRecorder = MediaRecorder()
            cameraManager?.openCamera(cameraId!!, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(cam: android.hardware.camera2.CameraDevice) {
                    cam.createCaptureSession(emptyList(), object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                            startVideoStreaming()
                        }
                        override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {}
                    }, null)
                }
                override fun onDisconnected(cam: android.hardware.camera2.CameraDevice) { cam.close() }
                override fun onError(cam: android.hardware.camera2.CameraDevice, e: Int) { cam.close() }
            }, null)

        } catch (e: android.hardware.camera2.CameraAccessException) { Toast.makeText(this, "Caméra inaccessible", Toast.LENGTH_SHORT).show() }
    }

    private fun startVideoStreaming() {
        try {
            mediaRecorder?.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(320, 240)
                setVideoFrameRate(10)
                setVideoEncodingBitRate(256000)
                // ✅ CORRIGÉ : Utilisation de setOutputFile(FileDescriptor)
                val tempFile = File(externalCacheDir, "stream_temp.mp4")
                setOutputFile(tempFile.absolutePath)
                prepare()
            }
            startServer()
        } catch (e: Exception) { Toast.makeText(this, "Erreur vidéo: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun startAudio() {
        val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        recorder.startRecording()
        recording.set(true)

        startServer()

        sendThread = Thread {
            val buffer = ByteArray(bufferSize)
            while (recording.get() && running.get()) {
                val read = recorder.read(buffer, 0, bufferSize)
                if (read > 0) outputStream?.write(buffer, 0, read)
            }
            recorder.stop()
            recorder.release()
        }.apply { start() }
    }

    private fun startServer() {
        Thread {
            try {
                serverSocket = ServerSocket(8888)
                runOnUiThread { Toast.makeText(this, "En attente de connexion...", Toast.LENGTH_SHORT).show() }
                socket = serverSocket?.accept()
                outputStream = socket?.getOutputStream()
                inputStream = socket?.getInputStream()
                runOnUiThread { Toast.makeText(this, "✅ Connecté ! Streaming en cours", Toast.LENGTH_SHORT).show() }

                if (mode == "audio") startAudioPlayback()

            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Erreur connexion: ${e.message}", Toast.LENGTH_SHORT).show() } }
        }.start()
    }

    private fun connectToOwner(address: InetAddress?) {
        Thread {
            try {
                socket = Socket(address, 8888)
                outputStream = socket?.getOutputStream()
                inputStream = socket?.getInputStream()
                runOnUiThread { Toast.makeText(this, "✅ Connecté ! Réception en cours", Toast.LENGTH_SHORT).show() }
                if (mode == "audio") startAudioPlayback()
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Impossible de se connecter: ${e.message}", Toast.LENGTH_SHORT).show() } }
        }.start()
    }

    private fun startAudioPlayback() {
        val bufferSize = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()

        receiveThread = Thread {
            val buffer = ByteArray(bufferSize)
            while (running.get() && !Thread.currentThread().isInterrupted) {
                try {
                    val read = inputStream?.read(buffer) ?: -1
                    if (read <= 0) break
                    audioTrack?.write(buffer, 0, read)
                } catch (e: Exception) { break }
            }
        }.apply { start() }
    }

    private fun stopStreaming() {
        running.set(false)
        recording.set(false)
        try {
            sendThread?.interrupt()
            receiveThread?.interrupt()
            socket?.close()
            serverSocket?.close()
            audioTrack?.stop()
            audioTrack?.release()
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {}
    }

    override fun onDestroy() { super.onDestroy(); stopStreaming(); unregisterReceiver(wifiDirectReceiver) }
}
