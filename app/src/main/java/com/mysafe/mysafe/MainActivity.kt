package com.mysafe.mysafe
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MainActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var positionText: TextView
    private lateinit var historyList: RecyclerView
    private lateinit var emptyHistory: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var streamBtn: Button
    private var positionMarker: Marker? = null
    private lateinit var historyAdapter: PositionHistoryAdapter

    private val positionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationService.ACTION_NEW_POSITION) {
                val lat = intent.getDoubleExtra(LocationService.EXTRA_LAT, 0.0)
                val lon = intent.getDoubleExtra(LocationService.EXTRA_LON, 0.0)
                val time = intent.getStringExtra(LocationService.EXTRA_TIME) ?: "--:--"
                val address = intent.getStringExtra(LocationService.EXTRA_ADDRESS) ?: ""
                updateUI(lat, lon, time, address)
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
        map.isTilesScaledToDpi = true

        positionText = findViewById(R.id.position_text)
        historyList = findViewById(R.id.history_list)
        emptyHistory = findViewById(R.id.empty_history)
        startBtn = findViewById(R.id.start_btn)
        stopBtn = findViewById(R.id.stop_btn)
        streamBtn = findViewById(R.id.stream_btn)

        historyAdapter = PositionHistoryAdapter { pos -> openInGoogleMaps(pos.latitude, pos.longitude) }
        historyList.adapter = historyAdapter
        historyList.layoutManager = LinearLayoutManager(this)

        startBtn.setOnClickListener { startService() }
        stopBtn.setOnClickListener { stopService() }
        streamBtn.setOnClickListener { startActivity(Intent(this, StreamingActivity::class.java)) }

        updateButtons()
        requestAllPermissions()

        val filter = IntentFilter(LocationService.ACTION_NEW_POSITION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(positionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(positionReceiver, filter)

        refreshHistory()
    }

    private fun updateUI(lat: Double, lon: Double, time: String, address: String) {
        runOnUiThread {
            val point = GeoPoint(lat, lon)
            map.controller?.animateTo(point)
            if (positionMarker == null) {
                positionMarker = Marker(map)
                positionMarker?.icon = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_mylocation)
                positionMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map.overlays.add(positionMarker)
            }
            positionMarker?.position = point
            positionMarker?.title = "Position actuelle"
            positionMarker?.snippet = "$address\n$time"
            positionText.text = "📍 $lat, $lon"
            refreshHistory()
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
            historyAdapter.updateData(positions)
        }
    }

    private fun openInGoogleMaps(lat: Double, lon: Double) {
        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.setPackage("com.google.android.apps.maps")
        try { startActivity(intent) }
        catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lon"))) }
    }

    private fun startService() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "❌ Autorisation de localisation requise", Toast.LENGTH_SHORT).show()
            requestAllPermissions()
            return
        }
        val intent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        Toast.makeText(this, "✅ Surveillance DÉMARRÉE", Toast.LENGTH_SHORT).show()
        updateButtons()
    }

    private fun stopService() {
        stopService(Intent(this, LocationService::class.java))
        Toast.makeText(this, "⏹ Surveillance ARRÊTÉE", Toast.LENGTH_SHORT).show()
        updateButtons()
    }

    private fun updateButtons() {
        val running = LocationService.isRunning.get()
        startBtn.isEnabled = !running
        startBtn.alpha = if (running) 0.5f else 1.0f
        stopBtn.isEnabled = running
        stopBtn.alpha = if (running) 1.0f else 0.5f
    }

    private fun requestAllPermissions() {
        val needed = mutableListOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) needed.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val toRequest = needed.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
        if (toRequest.isNotEmpty()) ActivityCompat.requestPermissions(this, toRequest, 100)
    }

    override fun onRequestPermissionsResult(rq: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rq, perms, res)
        if (rq == 100) Toast.makeText(this, if (res.all { it == PackageManager.PERMISSION_GRANTED }) "✅ Autorisations accordées" else "⚠️ Certaines autorisations manquent", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() { super.onResume(); map.onResume(); updateButtons(); refreshHistory() }
    override fun onPause() { super.onPause(); map.onPause() }
    override fun onDestroy() { super.onDestroy(); unregisterReceiver(positionReceiver) }

    inner class PositionHistoryAdapter(private val onClick: (LocationService.Position) -> Unit) : RecyclerView.Adapter<PositionHistoryAdapter.ViewHolder>() {
        private var data = listOf<LocationService.Position>()
        fun updateData(newData: List<LocationService.Position>) { data = newData; notifyDataSetChanged() }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_position, p, false))
        override fun onBindViewHolder(h: ViewHolder, i: Int) {
            val pos = data[i]
            h.time.text = pos.time
            h.address.text = "🏠 ${pos.address}"
            h.coords.text = "📍 ${String.format("%.6f", pos.latitude)}, ${String.format("%.6f", pos.longitude)}"
            h.mapsLink.setOnClickListener { onClick(pos) }
        }
        override fun getItemCount() = data.size
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val time: TextView = v.findViewById(R.id.item_time)
            val address: TextView = v.findViewById(R.id.item_address)
            val coords: TextView = v.findViewById(R.id.item_coords)
            val mapsLink: TextView = v.findViewById(R.id.item_maps_link)
        }
    }
}
