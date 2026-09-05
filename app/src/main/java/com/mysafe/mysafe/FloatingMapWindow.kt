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
import org.osmdroid.views.overlay.Marker
import android.preference.PreferenceManager
import java.io.File

class FloatingMapWindow : Service() {
    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var mapView: MapView? = null
    private var myMarker: Marker? = null
    private var otherMarker: Marker? = null

    override fun onCreate() {
        super.onCreate()
        createFloatingWindow()
    }

    private fun createFloatingWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.floating_map_window, null)

        val params = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams(
                280, 260,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams(
                280, 260,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
        }

        params.gravity = Gravity.TOP or Gravity.END
        params.x = 16
        params.y = 80
        windowManager.addView(floatingView, params)

        val osmdroidDir = File(getExternalFilesDir(null), "osmdroid")
        Configuration.getInstance().osmdroidBasePath = osmdroidDir
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))

        mapView = floatingView?.findViewById(R.id.float_map)
        mapView?.setTileSource(TileSourceFactory.DEFAULT_TILE_SOURCE)
        mapView?.setMultiTouchControls(true)
        mapView?.controller?.setZoom(14.0)
        mapView?.controller?.setCenter(GeoPoint(47.47, -0.55))

        floatingView?.findViewById<Button>(R.id.btn_close_float)?.setOnClickListener { stopSelf() }

        var initialX = params.x
        var initialY = params.y
        var ix = 0f
        var iy = 0f
        floatingView?.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    ix = e.rawX
                    iy = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (e.rawX - ix).toInt()
                    params.y = initialY + (e.rawY - iy).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }
    }

    fun updateMyPosition(lat: Double, lon: Double) {
        val gp = GeoPoint(lat, lon)
        mapView?.let { map ->
            if (myMarker == null) {
                myMarker = Marker(map).apply {
                    position = gp
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = resources.getDrawable(android.R.drawable.ic_menu_mylocation, null)
                    title = "Moi"
                }
                map.overlays.add(myMarker)
            } else {
                myMarker?.position = gp
            }
            map.invalidate()
        }
    }

    fun updateOtherPosition(lat: Double, lon: Double) {
        val gp = GeoPoint(lat, lon)
        mapView?.let { map ->
            if (otherMarker == null) {
                otherMarker = Marker(map).apply {
                    position = gp
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = resources.getDrawable(android.R.drawable.ic_menu_compass, null)
                    title = "Cible"
                }
                map.overlays.add(otherMarker)
            } else {
                otherMarker?.position = gp
            }
            map.invalidate()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingView?.let { windowManager.removeView(it) }
        mapView?.onDetach()
        floatingView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
