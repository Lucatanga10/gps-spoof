package com.gpsspoof.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos

class MainActivity : Activity() {

    private lateinit var map: MapView
    private lateinit var statusText: TextView

    private var centerPoint: GeoPoint? = null
    private var radiusMeters: Int = 500
    private var speedKmh: Int = 5
    private var isSquare: Boolean = false

    private var centerMarker: Marker? = null
    private var shapePolygon: Polygon? = null

    private var posMarker: Marker? = null
    private var pathLine: Polyline? = null
    private val pathPoints = ArrayList<GeoPoint>()

    private var receiverRegistered = false
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i ?: return
            val lat = i.getDoubleExtra(MockLocationService.EXTRA_LAT, Double.NaN)
            val lng = i.getDoubleExtra(MockLocationService.EXTRA_LNG, Double.NaN)
            val cov = i.getIntExtra(MockLocationService.EXTRA_COV, -1)
            if (lat.isNaN() || lng.isNaN()) return
            val gp = GeoPoint(lat, lng)
            ensureLiveOverlays()
            pathPoints.add(gp)
            if (pathPoints.size > 6000) pathPoints.removeAt(0)
            pathLine?.setPoints(pathPoints)
            posMarker?.position = gp
            map.invalidate()
            if (cov >= 0) setStatus("Vaga area — copertura $cov%")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        map = findViewById(R.id.map)
        setupMap()

        requestPermissions()

        val radiusSeek = findViewById<SeekBar>(R.id.radiusSeek)
        val radiusLabel = findViewById<TextView>(R.id.radiusLabel)
        val speedSeek = findViewById<SeekBar>(R.id.speedSeek)
        val speedLabel = findViewById<TextView>(R.id.speedLabel)
        val shapeSwitch = findViewById<Switch>(R.id.shapeSwitch)
        val searchInput = findViewById<EditText>(R.id.searchInput)

        radiusSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                radiusMeters = p.coerceAtLeast(20)
                radiusLabel.text = "Raggio: $radiusMeters m"
                redraw(false)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        speedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                speedKmh = p.coerceAtLeast(1)
                speedLabel.text = "Velocita: $speedKmh km/h"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        shapeSwitch.setOnCheckedChangeListener { _, checked ->
            isSquare = checked
            redraw(false)
        }

        findViewById<Button>(R.id.searchButton).setOnClickListener {
            search(searchInput.text.toString().trim())
        }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(searchInput.text.toString().trim()); true
            } else false
        }

        findViewById<Button>(R.id.fixedButton).setOnClickListener { startFixed() }
        findViewById<Button>(R.id.roamButton).setOnClickListener { startRoam() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, MockLocationService::class.java))
            clearLiveOverlays()
            setStatus("Fermo")
        }
    }

    // ---------------- map ----------------

    private fun setupMap() {
        val sat = object : OnlineTileSourceBase(
            "EsriSat", 0, 19, 256, "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
                        MapTileIndex.getY(pMapTileIndex) + "/" +
                        MapTileIndex.getX(pMapTileIndex)
            }
        }
        map.setTileSource(sat)
        map.setMultiTouchControls(true)

        val labelSource = object : OnlineTileSourceBase(
            "EsriLabels", 0, 19, 256, "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
                        MapTileIndex.getY(pMapTileIndex) + "/" +
                        MapTileIndex.getX(pMapTileIndex)
            }
        }
        val labelsOverlay = TilesOverlay(MapTileProviderBasic(applicationContext, labelSource), applicationContext)
        labelsOverlay.loadingBackgroundColor = Color.TRANSPARENT
        labelsOverlay.loadingLineColor = Color.TRANSPARENT
        map.overlays.add(labelsOverlay)

        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                setCenter(p, false)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }
        map.overlays.add(MapEventsOverlay(receiver))

        val start = GeoPoint(44.6469, 10.9252) // Modena default
        map.controller.setZoom(16.0)
        map.controller.setCenter(start)
        setCenter(start, false)
    }

    private fun setCenter(p: GeoPoint, moveMap: Boolean) {
        centerPoint = p
        redraw(moveMap)
        setStatus("Punto: %.5f, %.5f".format(p.latitude, p.longitude))
    }

    private fun redraw(moveMap: Boolean) {
        val cp = centerPoint ?: return
        centerMarker?.let { map.overlays.remove(it) }
        shapePolygon?.let { map.overlays.remove(it) }

        val poly = Polygon(map)
        poly.outlinePaint.color = Color.YELLOW
        poly.outlinePaint.strokeWidth = 5f
        poly.fillPaint.color = Color.argb(60, 0, 150, 255)
        poly.points = if (isSquare) squarePoints(cp, radiusMeters) else Polygon.pointsAsCircle(cp, radiusMeters.toDouble())
        shapePolygon = poly
        map.overlays.add(poly)

        val m = Marker(map)
        m.position = cp
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        m.title = "Centro"
        centerMarker = m
        map.overlays.add(m)

        if (moveMap) map.controller.animateTo(cp)
        map.invalidate()
    }

    private fun squarePoints(c: GeoPoint, r: Int): List<GeoPoint> {
        val dLat = r / 111320.0
        val dLng = r / (111320.0 * cos(Math.toRadians(c.latitude)))
        val la = c.latitude
        val ln = c.longitude
        return listOf(
            GeoPoint(la + dLat, ln - dLng),
            GeoPoint(la + dLat, ln + dLng),
            GeoPoint(la - dLat, ln + dLng),
            GeoPoint(la - dLat, ln - dLng),
            GeoPoint(la + dLat, ln - dLng)
        )
    }

    // ---------------- live overlays (puntino + scia) ----------------

    private fun ensureLiveOverlays() {
        if (pathLine == null) {
            val pl = Polyline(map)
            pl.outlinePaint.color = Color.RED
            pl.outlinePaint.strokeWidth = 6f
            pathLine = pl
            map.overlays.add(pl)
        }
        if (posMarker == null) {
            val pm = Marker(map)
            pm.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            pm.title = "Posizione finta"
            posMarker = pm
            map.overlays.add(pm)
        }
    }

    private fun clearLiveOverlays() {
        pathLine?.let { map.overlays.remove(it) }
        posMarker?.let { map.overlays.remove(it) }
        pathLine = null
        posMarker = null
        pathPoints.clear()
        map.invalidate()
    }

    // ---------------- search ----------------

    private fun search(query: String) {
        if (query.isBlank()) {
            toast("Scrivi una citta o via")
            return
        }
        setStatus("Cerco \"$query\"...")
        Thread {
            try {
                val q = URLEncoder.encode(query, "UTF-8")
                val url = URL("https://nominatim.openstreetmap.org/search?q=$q&format=json&limit=1")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "gps-spoof-app/1.0")
                conn.connectTimeout = 12000
                conn.readTimeout = 12000
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(txt)
                if (arr.length() > 0) {
                    val o = arr.getJSONObject(0)
                    val lat = o.getString("lat").toDouble()
                    val lon = o.getString("lon").toDouble()
                    runOnUiThread {
                        map.controller.setZoom(15.0)
                        setCenter(GeoPoint(lat, lon), true)
                    }
                } else {
                    runOnUiThread { toast("Non trovato: $query") }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Errore ricerca: ${e.message}") }
            }
        }.start()
    }

    // ---------------- start spoof ----------------

    private fun startFixed() {
        val cp = centerPoint ?: return
        if (!hasLocationPermission()) { requestPermissions(); toast("Concedi il permesso posizione"); return }
        clearLiveOverlays()
        val i = Intent(this, MockLocationService::class.java).apply {
            putExtra(MockLocationService.EXTRA_MODE, MockLocationService.MODE_FIXED)
            putExtra(MockLocationService.EXTRA_LAT, cp.latitude)
            putExtra(MockLocationService.EXTRA_LNG, cp.longitude)
        }
        startForegroundService(i)
        setStatus("Punto fisso attivo")
    }

    private fun startRoam() {
        val cp = centerPoint ?: return
        if (!hasLocationPermission()) { requestPermissions(); toast("Concedi il permesso posizione"); return }
        clearLiveOverlays()
        val i = Intent(this, MockLocationService::class.java).apply {
            putExtra(MockLocationService.EXTRA_MODE, MockLocationService.MODE_ROAM)
            putExtra(MockLocationService.EXTRA_LAT, cp.latitude)
            putExtra(MockLocationService.EXTRA_LNG, cp.longitude)
            putExtra(MockLocationService.EXTRA_RADIUS, radiusMeters)
            putExtra(MockLocationService.EXTRA_SQUARE, isSquare)
            putExtra(MockLocationService.EXTRA_SPEED_KMH, speedKmh.toDouble())
        }
        startForegroundService(i)
        setStatus("Vaga area avviato ($speedKmh km/h, $radiusMeters m)")
    }

    // ---------------- misc ----------------

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val perms = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) requestPermissions(perms.toTypedArray(), 1)
    }

    private fun setStatus(s: String) { statusText.text = "Stato: $s" }
    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    override fun onResume() {
        super.onResume()
        map.onResume()
        if (!receiverRegistered) {
            val f = IntentFilter(MockLocationService.ACTION_UPDATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(updateReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(updateReceiver, f)
            }
            receiverRegistered = true
        }
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        if (receiverRegistered) {
            try {
                unregisterReceiver(updateReceiver)
            } catch (e: Exception) {
            }
            receiverRegistered = false
        }
    }
}
