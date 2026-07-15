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
import android.view.WindowManager
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
import kotlin.math.sqrt

class MainActivity : Activity() {

    private lateinit var map: MapView
    private lateinit var statusText: TextView

    private var centerPoint: GeoPoint? = null
    private var radiusMeters: Int = 500
    private var speedKmh: Int = 5
    private var isSquare: Boolean = false

    // scala raggio logaritmica: barra 0..1000 -> 20 m .. 10.000.000 m
    private val minRadius = 20.0
    private val maxRadius = 10_000_000.0
    private val radiusSteps = 1000

    // corsie serpentina: fitte = attaccate (niente buchi), con tetto per non esplodere la memoria
    private val laneWidthM = 8.0
    private val maxLanes = 4000

    private var centerMarker: Marker? = null
    private var shapePolygon: Polygon? = null

    private var posMarker: Marker? = null
    private var plannedLine: Polyline? = null

    private var receiverRegistered = false
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i ?: return
            val lat = i.getDoubleExtra(MockLocationService.EXTRA_LAT, Double.NaN)
            val lng = i.getDoubleExtra(MockLocationService.EXTRA_LNG, Double.NaN)
            val cov = i.getIntExtra(MockLocationService.EXTRA_COV, -1)
            if (lat.isNaN() || lng.isNaN()) return
            ensurePosMarker()
            posMarker?.position = GeoPoint(lat, lng)
            map.invalidate()
            if (cov >= 0) setStatus("In movimento — completato $cov%")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        // schermo sempre acceso finché l'app è aperta: niente standby dopo 10 min
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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

        radiusSeek.max = radiusSteps
        radiusSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                radiusMeters = progressToRadius(p)
                radiusLabel.text = formatRadius(radiusMeters)
                redraw(false)
                updateEta()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        speedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                speedKmh = p.coerceAtLeast(1)
                speedLabel.text = "Velocita: $speedKmh km/h"
                updateEta()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        shapeSwitch.setOnCheckedChangeListener { _, checked ->
            isSquare = checked
            redraw(false)
            updateEta()
        }

        updateEta()

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

    // barra -> metri, scala logaritmica (fine sui piccoli, enorme in cima)
    private fun progressToRadius(p: Int): Int {
        val frac = p.toDouble() / radiusSteps
        return Math.round(minRadius * Math.pow(maxRadius / minRadius, frac)).toInt().coerceAtLeast(20)
    }

    private fun formatRadius(m: Int): String {
        return if (m >= 1000) "Raggio: %.1f km".format(m / 1000.0) else "Raggio: $m m"
    }

    // numero di corsie: spaziatura fitta (attaccate) ma con tetto maxLanes
    private fun laneCountFor(r: Int): Int {
        val spacing = Math.max(laneWidthM, (2.0 * r) / maxLanes)
        return Math.max(2, Math.ceil((2.0 * r) / spacing).toInt())
    }

    // lunghezza totale del giro serpentina in metri (verticali + collegamenti orizzontali)
    private fun estimatePathMeters(r: Int, square: Boolean): Double {
        if (r <= 0) return 0.0
        val lanes = laneCountFor(r)
        var vertical = 0.0
        for (li in 0..lanes) {
            val t = -1.0 + 2.0 * li / lanes
            val half = if (square) 1.0 else {
                if (Math.abs(t) >= 0.999) continue
                sqrt(1 - t * t)
            }
            vertical += 2.0 * half * r    // altezza corsia in metri
        }
        val horizontal = 2.0 * r          // collegamenti da un lato all'altro
        return vertical + horizontal
    }

    private fun formatEta(r: Int, square: Boolean, kmh: Int): String {
        val speedMs = Math.max(0.3, kmh / 3.6)
        val meters = estimatePathMeters(r, square)
        val secs = meters / speedMs
        val forma = if (square) "quadrato" else "cerchio"
        return "Giro $forma completo: ~${humanTime(secs)} (${"%.0f".format(meters / 1000.0)} km)"
    }

    private fun humanTime(totalSecs: Double): String {
        if (totalSecs.isInfinite() || totalSecs.isNaN()) return "?"
        var s = totalSecs.toLong()
        val d = s / 86400; s %= 86400
        val h = s / 3600; s %= 3600
        val m = s / 60; s %= 60
        return when {
            d > 0 -> "${d}g ${h}h ${m}min"
            h > 0 -> "${h}h ${m}min"
            m > 0 -> "${m}min ${s}s"
            else -> "${s}s"
        }
    }

    private fun updateEta() {
        val label = findViewById<TextView>(R.id.etaLabel) ?: return
        label.text = formatEta(radiusMeters, isSquare, speedKmh)
    }

    private fun shapePointsFor(cp: GeoPoint): List<GeoPoint> =
        if (isSquare) squarePoints(cp, radiusMeters) else Polygon.pointsAsCircle(cp, radiusMeters.toDouble())

    // aggiorna solo il cerchio/quadrato (usato mentre trascini il pin, tiene il pin sopra)
    private fun updateShapePolygon() {
        val cp = centerPoint ?: return
        shapePolygon?.points = shapePointsFor(cp)
        map.invalidate()
    }

    private fun redraw(moveMap: Boolean) {
        val cp = centerPoint ?: return
        centerMarker?.let { map.overlays.remove(it) }
        shapePolygon?.let { map.overlays.remove(it) }

        val poly = Polygon(map)
        poly.outlinePaint.color = Color.YELLOW
        poly.outlinePaint.strokeWidth = 5f
        poly.fillPaint.color = Color.argb(50, 0, 150, 255)
        poly.points = shapePointsFor(cp)
        shapePolygon = poly
        map.overlays.add(poly)

        val m = Marker(map)
        m.position = cp
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        m.title = "Trascina per spostare"
        m.isDraggable = true
        m.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: Marker) {}
            override fun onMarkerDrag(marker: Marker) {
                // il pin si muove col dito: il cerchio/quadrato lo segue
                centerPoint = marker.position
                updateShapePolygon()
            }
            override fun onMarkerDragEnd(marker: Marker) {
                setCenter(marker.position, false)
            }
        })
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

    // stessa serpentina del service: disegna tutte le corsie rosse = area piena
    private fun buildSerpentine(c: GeoPoint, r: Int, square: Boolean): List<GeoPoint> {
        val cosLat = cos(Math.toRadians(c.latitude))
        val dLatMax = r / 111320.0
        val dLngMax = r / (111320.0 * cosLat)
        val lanes = laneCountFor(r)
        val out = ArrayList<GeoPoint>()
        for (li in 0..lanes) {
            val rLng = -dLngMax + (2 * dLngMax) * li / lanes
            val half: Double = if (square) {
                dLatMax
            } else {
                val t = rLng / dLngMax
                if (Math.abs(t) >= 0.999) continue
                dLatMax * sqrt(1 - t * t)
            }
            if (half <= 0) continue
            val bottom = GeoPoint(c.latitude - half, c.longitude + rLng)
            val top = GeoPoint(c.latitude + half, c.longitude + rLng)
            if (li % 2 == 0) { out.add(top); out.add(bottom) } else { out.add(bottom); out.add(top) }
        }
        return out
    }

    // ---------------- live overlays ----------------

    private fun ensurePosMarker() {
        if (posMarker == null) {
            val pm = Marker(map)
            pm.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            pm.title = "Posizione finta"
            posMarker = pm
            map.overlays.add(pm)
        }
    }

    private fun drawPlanned(serpentine: Boolean) {
        plannedLine?.let { map.overlays.remove(it) }
        plannedLine = null
        val cp = centerPoint ?: return
        if (!serpentine) return
        val pts = buildSerpentine(cp, radiusMeters, isSquare)
        if (pts.size < 2) return
        val pl = Polyline(map)
        pl.outlinePaint.color = Color.argb(200, 255, 0, 0)
        pl.outlinePaint.strokeWidth = 16f
        pl.setPoints(pts)
        plannedLine = pl
        map.overlays.add(pl)
        map.invalidate()
    }

    private fun clearLiveOverlays() {
        plannedLine?.let { map.overlays.remove(it) }
        posMarker?.let { map.overlays.remove(it) }
        plannedLine = null
        posMarker = null
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
        val serpentine = findViewById<Switch>(R.id.sweepSwitch).isChecked
        clearLiveOverlays()
        drawPlanned(serpentine)
        val i = Intent(this, MockLocationService::class.java).apply {
            putExtra(MockLocationService.EXTRA_MODE, MockLocationService.MODE_ROAM)
            putExtra(MockLocationService.EXTRA_LAT, cp.latitude)
            putExtra(MockLocationService.EXTRA_LNG, cp.longitude)
            putExtra(MockLocationService.EXTRA_RADIUS, radiusMeters)
            putExtra(MockLocationService.EXTRA_SQUARE, isSquare)
            putExtra(MockLocationService.EXTRA_SPEED_KMH, speedKmh.toDouble())
            putExtra(MockLocationService.EXTRA_SERPENTINE, serpentine)
        }
        startForegroundService(i)
        setStatus("Avviato ($speedKmh km/h, $radiusMeters m)")
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
