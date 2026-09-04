package com.mysafe.mysafe
import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
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
    private lateinit var surfaceView: SurfaceView
    private var cameraManager: android.hardware.camera2.CameraManager? = null
    private var cameraId: String? = null
    private var recording = AtomicBoolean(false)
    private var running = AtomicBoolean(true)
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var receiveThread: Thread? = null
    private var sendThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputStream: java.io.OutputStream? = null
    private var inputStream: java.io.InputStream? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_streaming)

        mode = intent.getStringExtra("mode") ?: "video"
        targetPhone = intent.getStringExtra("target_phone") ?: ""

        surfaceView = findViewById(R.id.surface_view)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager

        findViewById<Button>(R.id.stop_btn).setOnClickListener { stopStreaming(); finish() }

        if (mode == "video") {
            findViewById<TextView>(R.id.title_text).text = "📹 STREAMING VIDÉO — ACTIF"
            surfaceView.visibility = View.VISIBLE
            // ✅ LANCEMENT DIRECT — PAS D'ATTENTE
            startVideoStreamingNow()
        } else {
            findViewById<TextView>(R.id.title_text).text = "🎙️ STREAMING AUDIO — ACTIF"
            surfaceView.visibility = View.GONE
            startAudioStreamingNow()
        }

        val stopFilter = IntentFilter("STOP_STREAMING")
        val stopReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { stopStreaming(); finish() }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, stopFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopReceiver, stopFilter)
        }
    }

    private fun startVideoStreamingNow() {
        Toast.makeText(this, "📹 Caméra activée — Streaming en cours sur le port 8888", Toast.LENGTH_SHORT).show()
        
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                startCameraAndRecord(holder)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
        })
    }

    private fun startCameraAndRecord(holder: SurfaceHolder) {
        try {
            cameraId = cameraManager?.cameraIdList?.firstOrNull {
                cameraManager?.getCameraCharacteristics(it)
                    ?.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                    android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
            } ?: cameraManager?.cameraIdList?.firstOrNull()

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this@StreamingActivity, "❌ Autorisation caméra manquante", Toast.LENGTH_SHORT).show()
                return
            }

            mediaRecorder = MediaRecorder()
            cameraManager?.openCamera(cameraId!!, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(cam: android.hardware.camera2.CameraDevice) {
                    try {
                        mediaRecorder?.apply {
                            setPreviewDisplay(holder.surface)
                            setVideoSource(MediaRecorder.VideoSource.SURFACE)
                            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                            setVideoSize(320, 240)
                            setVideoFrameRate(10)
                            setVideoEncodingBitRate(256000)
                            val tempFile = File(externalCacheDir, "stream_temp.mp4")
                            setOutputFile(tempFile.absolutePath)
                            prepare()
                            start()
                        }
                        startServerDirect()
                        cam.close()
                    } catch (e: Exception) {
                        Toast.makeText(this@StreamingActivity, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show()
                        cam.close()
                    }
                }
                override fun onDisconnected(cam: android.hardware.camera2.CameraDevice) { cam.close() }
                override fun onError(cam: android.hardware.camera2.CameraDevice, e: Int) { cam.close() }
            }, null)
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur caméra: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAudioStreamingNow() {
        Toast.makeText(this, "🎙️ Micro activé — Streaming en cours sur le port 8888", Toast.LENGTH_SHORT).show()
        
        val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "❌ Autorisation micro manquante", Toast.LENGTH_SHORT).show()
            return
        }

        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        recorder.startRecording()
        recording.set(true)

        startServerDirect()

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

    private fun startServerDirect() {
        Thread {
            try {
                serverSocket = ServerSocket(8888)
                serverSocket?.soTimeout = 30000 // 30s max d'attente
                runOnUiThread { Toast.makeText(this, "✅ Serveur prêt — Port 8888", Toast.LENGTH_SHORT).show() }
                
                try {
                    socket = serverSocket?.accept()
                    outputStream = socket?.getOutputStream()
                    inputStream = socket?.getInputStream()
                    runOnUiThread { Toast.makeText(this, "✅ CONNECTÉ ! Streaming actif", Toast.LENGTH_SHORT).show() }
                    if (mode == "audio") startAudioPlayback()
                } catch (e: SocketTimeoutException) {
                    runOnUiThread { Toast.makeText(this, "⏹ Aucune connexion dans les 30s — En attente...", Toast.LENGTH_SHORT).show() }
                    startServerDirect() // Réécoute indéfinie
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
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

    override fun onDestroy() { super.onDestroy(); stopStreaming() }
}
