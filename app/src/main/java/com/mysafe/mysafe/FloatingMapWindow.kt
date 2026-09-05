package com.mysafe.mysafe

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import android.preference.PreferenceManager
import java.io.File

class FloatingMapWindow : Service() {
    private lateinit var wm: WindowManager
    private var view: View? = null
    private var map: MapView? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        view = (getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater)
            .inflate(R.layout.floating_map_window, null)
        val params = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams(280, 260, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        else @Suppress("DEPRECATION") WindowManager.LayoutParams(280, 260,
            WindowManager.LayoutParams.TYPE_PHONE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.END; params.x = 16; params.y = 80
        wm.addView(view, params)

        val dir = File(getExternalFilesDir(null), "osmdroid")
        Configuration.getInstance().osmdroidBasePath = dir
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))

        map = view?.findViewById(R.id.float_map)
        map?.setTileSource(TileSourceFactory.DEFAULT_TILE_SOURCE)
        map?.setMultiTouchControls(true)
        map?.controller?.setZoom(14.0)
        map?.controller?.setCenter(GeoPoint(47.47, -0.55))

        view?.findViewById<Button>(R.id.btn_close_float)?.setOnClickListener { stopSelf() }
        var x = params.x; var y = params.y; var ix = 0f; var iy = 0f
        view?.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = e.rawX; iy = e.rawY; true }
                MotionEvent.ACTION_MOVE -> { params.x -= (e.rawX - ix).toInt(); params.y += (e.rawY - iy).toInt(); wm.updateViewLayout(view, params); true }
                else -> false
            }
        }
    }

    override fun onDestroy() { super.onDestroy(); view?.let { wm.removeView(it) }; map?.onDetach(); view = null }
    override fun onBind(i: Intent?): IBinder? = null
}
