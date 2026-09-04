package com.mysafe.mysafe
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MainActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var cameraBtn: Button
    private lateinit var historyList: RecyclerView
    private lateinit var emptyHistory: TextView
    private var marker: Marker? = null
    private lateinit var historyAdapter: HistoryAdapter

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationService.ACTION_UPDATE) {
                val lat = intent.getDoubleExtra("lat", 0.0)
                val lon = intent.getDoubleExtra("lon", 0.0)
                val time = intent.getStringExtra("time") ?: ""
                updateMap(lat, lon, time)
                refreshHistory()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller?.setZoom(15.0)

        startBtn = findViewById(R.id.start_btn)
        stopBtn = findViewById(R.id.stop_btn)
        cameraBtn = findViewById(R.id.camera_btn)
        historyList = findViewById(R.id.history_list)
        emptyHistory = findViewById(R.id.empty_history)

        historyAdapter = HistoryAdapter()
        historyList.adapter = historyAdapter
        historyList.layoutManager = LinearLayoutManager(this)

        startBtn.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.INTERNET), 100)
            } else {
                startService(Intent(this, LocationService::class.java))
                Toast.makeText(this, "✅ Surveillance démarrée", Toast.LENGTH_SHORT).show()
            }
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, LocationService::class.java))
            Toast.makeText(this, "⏹ Surveillance arrêtée", Toast.LENGTH_SHORT).show()
        }

        cameraBtn.setOnClickListener {
            startActivity(Intent(this, StreamingActivity::class.java))
        }

        registerReceiver(receiver, IntentFilter(LocationService.ACTION_UPDATE))
        refreshHistory()
    }

    private fun updateMap(lat: Double, lon: Double, time: String) {
        runOnUiThread {
            val point = GeoPoint(lat, lon)
            map.controller?.animateTo(point)
            if (marker == null) {
                marker = Marker(map)
                marker?.icon = getDrawable(android.R.drawable.ic_menu_mylocation)
                map.overlays.add(marker)
            }
            marker?.position = point
            marker?.title = "$time\n$lat, $lon"
        }
    }

    private fun refreshHistory() {
        val positions = LocationService.positions
        if (positions.isEmpty()) {
            emptyHistory.visibility = View.VISIBLE
            historyList.visibility = View.GONE
        } else {
            emptyHistory.visibility = View.GONE
            historyList.visibility = View.VISIBLE
            historyAdapter.setData(positions)
        }
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
    override fun onDestroy() { super.onDestroy(); unregisterReceiver(receiver) }

    inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
        private var data = listOf<LocationService.Position>()

        fun setData(list: List<LocationService.Position>) {
            data = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_position, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val pos = data[position]
            holder.textView.text = "${pos.time} | ${String.format("%.6f", pos.lat)}, ${String.format("%.6f", pos.lon)}"
        }

        override fun getItemCount(): Int = data.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(R.id.item_text)
        }
    }
}
