package com.gpsspoof.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.TilesOverlay
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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
    private val plannedOverlays = ArrayList<Overlay>()

    // confine reale del comune cercato (anelli). Se != null, roam usa questo al posto del cerchio.
    private var boundaryRings: List<List<GeoPoint>>? = null
    private val boundaryOverlays = ArrayList<Overlay>()

    private var receiverRegistered = false
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i ?: return
            val lat = i.getDoubleExtra(MockLocationService.EXTRA_LAT, Double.NaN)
            val lng = i.getDoubleExtra(MockLocationService.EXTRA_LNG, Double.NaN)
            val cov = i.getIntExtra(MockLocationService.EXTRA_COV, -1)
            val remaining = i.getLongExtra(MockLocationService.EXTRA_REMAINING, -1L)
            if (lat.isNaN() || lng.isNaN()) return
            ensurePosMarker()
            posMarker?.position = GeoPoint(lat, lng)
            map.invalidate()
            if (cov >= 0) setStatus("In movimento — completato $cov%")
            // countdown vero: il tempo rimanente scende ad ogni secondo
            if (remaining >= 0) {
                findViewById<TextView>(R.id.etaLabel)?.text =
                    "Giro al $cov% — mancano ${humanTime(remaining.toDouble())}"
            }
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
            askStopConfirm(1)
        }
    }

    // STOP protetto: 5 conferme di fila. Basta un "No" o chiudere (X / fuori / indietro)
    // per NON fermare. Solo il 5° "Si" ferma davvero: evita gli stop per sbaglio.
    private fun askStopConfirm(step: Int) {
        AlertDialog.Builder(this)
            .setTitle("Fermare lo spoof? ($step di 5)")
            .setMessage(
                if (step < 5) "Vuoi VERAMENTE fermare?\nDevi confermare 5 volte. Manca ancora ${5 - step + 1}."
                else "ULTIMA conferma.\nPremi Si e lo spoof si ferma DAVVERO."
            )
            .setPositiveButton("Si") { _, _ ->
                if (step >= 5) doStop() else askStopConfirm(step + 1)
            }
            .setNegativeButton("No") { d, _ -> d.dismiss() }  // non ferma
            .setCancelable(true)                               // X / fuori / indietro = non ferma
            .show()
    }

    private fun doStop() {
        stopService(Intent(this, MockLocationService::class.java))
        clearLiveOverlays()
        setStatus("Fermo")
        toast("Spoof fermato")
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
                if (boundaryRings != null) { boundaryRings = null; clearBoundaryOverlays() }
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
        val b = boundaryRings
        if (b != null) {
            val pts = buildSerpentineBoundary(b)
            var m = 0.0
            for (i in 1 until pts.size) m += distMeters(pts[i - 1], pts[i])
            val speedMs = max(0.3, speedKmh / 3.6)
            label.text = "Giro citta completo: ~${humanTime(m / speedMs)} (${"%.0f".format(m / 1000.0)} km)"
        } else {
            label.text = formatEta(radiusMeters, isSquare, speedKmh)
        }
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
        if (boundaryRings != null) { drawBoundary(moveMap); return }
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

    // ---------------- confine reale del comune ----------------

    private fun applyBoundary(rings: List<List<GeoPoint>>) {
        boundaryRings = rings
        var minLat = 90.0; var maxLat = -90.0; var minLng = 180.0; var maxLng = -180.0
        for (ring in rings) for (p in ring) {
            minLat = min(minLat, p.latitude); maxLat = max(maxLat, p.latitude)
            minLng = min(minLng, p.longitude); maxLng = max(maxLng, p.longitude)
        }
        centerPoint = GeoPoint((minLat + maxLat) / 2.0, (minLng + maxLng) / 2.0)
        // via cerchio/pin, mostra il confine
        centerMarker?.let { map.overlays.remove(it) }; centerMarker = null
        shapePolygon?.let { map.overlays.remove(it) }; shapePolygon = null
        clearLiveOverlays()
        drawBoundary(true)
        updateEta()
        setStatus("Confine citta pronto — premi Avvia percorso")
    }

    private fun clearBoundaryOverlays() {
        for (o in boundaryOverlays) map.overlays.remove(o)
        boundaryOverlays.clear()
        map.invalidate()
    }

    private fun drawBoundary(moveMap: Boolean) {
        clearBoundaryOverlays()
        val rings = boundaryRings ?: return
        var minLat = 90.0; var maxLat = -90.0; var minLng = 180.0; var maxLng = -180.0
        for (ring in rings) {
            val poly = Polygon(map)
            poly.outlinePaint.color = Color.YELLOW
            poly.outlinePaint.strokeWidth = 5f
            poly.fillPaint.color = Color.argb(40, 0, 150, 255)
            poly.points = ring
            boundaryOverlays.add(poly)
            map.overlays.add(poly)
            for (p in ring) {
                minLat = min(minLat, p.latitude); maxLat = max(maxLat, p.latitude)
                minLng = min(minLng, p.longitude); maxLng = max(maxLng, p.longitude)
            }
        }
        map.invalidate()
        if (moveMap && maxLat > minLat) {
            map.zoomToBoundingBox(BoundingBox(maxLat, maxLng, minLat, minLng), true, 80)
        }
    }

    // stessa serpentina, ma tagliata sul confine reale (even-odd). Solo per disegno/ETA.
    private fun buildSerpentineBoundary(rings: List<List<GeoPoint>>): List<GeoPoint> {
        var minLat = 90.0; var maxLat = -90.0; var minLng = 180.0; var maxLng = -180.0
        for (ring in rings) for (p in ring) {
            minLat = min(minLat, p.latitude); maxLat = max(maxLat, p.latitude)
            minLng = min(minLng, p.longitude); maxLng = max(maxLng, p.longitude)
        }
        val out = ArrayList<GeoPoint>()
        val spanLng = maxLng - minLng
        if (spanLng <= 0 || maxLat <= minLat) return out
        val cLat = (minLat + maxLat) / 2.0
        val cosLat = cos(Math.toRadians(cLat))
        val widthM = spanLng * 111320.0 * cosLat
        val laneSpacingM = max(laneWidthM, widthM / maxLanes)
        val lanes = max(2, Math.ceil(widthM / laneSpacingM).toInt())
        val spacing = spanLng / lanes
        for (li in 0 until lanes) {
            val x = minLng + spacing * (li + 0.5)
            val ys = scanlineLat(rings, x)
            if (ys.size < 2) continue
            val intervals = ArrayList<DoubleArray>()
            var k = 0
            while (k + 1 < ys.size) { intervals.add(doubleArrayOf(ys[k], ys[k + 1])); k += 2 }
            if (intervals.isEmpty()) continue
            if (li % 2 == 0) {
                for (iv in intervals.indices.reversed()) {
                    out.add(GeoPoint(intervals[iv][1], x)); out.add(GeoPoint(intervals[iv][0], x))
                }
            } else {
                for (iv in intervals.indices) {
                    out.add(GeoPoint(intervals[iv][0], x)); out.add(GeoPoint(intervals[iv][1], x))
                }
            }
        }
        return out
    }

    private fun scanlineLat(rings: List<List<GeoPoint>>, x: Double): DoubleArray {
        val ys = ArrayList<Double>()
        for (ring in rings) {
            val n = ring.size
            if (n < 2) continue
            for (i in 0 until n) {
                val a = ring[i]
                val b = ring[(i + 1) % n]
                val x1 = a.longitude; val x2 = b.longitude
                if ((x1 <= x && x2 > x) || (x2 <= x && x1 > x)) {
                    val t = (x - x1) / (x2 - x1)
                    ys.add(a.latitude + t * (b.latitude - a.latitude))
                }
            }
        }
        ys.sort()
        return ys.toDoubleArray()
    }

    private fun encodeRings(rings: List<List<GeoPoint>>): String =
        rings.joinToString(";") { ring ->
            ring.joinToString(" ") { p -> "${p.latitude},${p.longitude}" }
        }

    private fun distMeters(a: GeoPoint, b: GeoPoint): Double {
        val cosLat = cos(Math.toRadians(a.latitude))
        val dxm = (b.longitude - a.longitude) * 111320.0 * cosLat
        val dym = (b.latitude - a.latitude) * 111320.0
        return Math.hypot(dxm, dym)
    }

    // GeoJSON (Polygon / MultiPolygon) -> lista di anelli in GeoPoint
    private fun parseGeoJson(gj: JSONObject): List<List<GeoPoint>>? {
        val type = gj.optString("type")
        val coords = gj.optJSONArray("coordinates") ?: return null
        val rings = ArrayList<List<GeoPoint>>()
        when (type) {
            "Polygon" -> parsePolygon(coords, rings)
            "MultiPolygon" -> for (i in 0 until coords.length()) parsePolygon(coords.getJSONArray(i), rings)
            else -> return null
        }
        return if (rings.isEmpty()) null else rings
    }

    private fun parsePolygon(polyCoords: JSONArray, out: ArrayList<List<GeoPoint>>) {
        for (r in 0 until polyCoords.length()) {
            val ringArr = polyCoords.getJSONArray(r)
            val pts = ArrayList<GeoPoint>()
            for (k in 0 until ringArr.length()) {
                val c = ringArr.getJSONArray(k)
                val lng = c.getDouble(0); val lat = c.getDouble(1)
                pts.add(GeoPoint(lat, lng))
            }
            if (pts.size >= 3) out.add(pts)
        }
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

    // area coperta disegnata PIENA di rosso: niente linee staccate, niente pixel fuori.
    // riempie esattamente la zona percorsa (confine citta o cerchio) = tutto attaccato.
    private fun drawPlanned(serpentine: Boolean) {
        clearPlannedOverlays()
        val b = boundaryRings
        if (b != null) {
            for (ring in b) {
                if (ring.size < 3) continue
                val poly = Polygon(map)
                poly.outlinePaint.color = Color.argb(255, 200, 0, 0)
                poly.outlinePaint.strokeWidth = 3f
                poly.fillPaint.color = Color.argb(150, 220, 0, 0)
                poly.points = ring
                plannedOverlays.add(poly)
                map.overlays.add(poly)
            }
        } else {
            val cp = centerPoint ?: return
            val poly = Polygon(map)
            poly.outlinePaint.color = Color.argb(255, 200, 0, 0)
            poly.outlinePaint.strokeWidth = 3f
            poly.fillPaint.color = Color.argb(150, 220, 0, 0)
            poly.points = shapePointsFor(cp)
            plannedOverlays.add(poly)
            map.overlays.add(poly)
        }
        // pin sopra il rosso, cosi restano visibili
        posMarker?.let { map.overlays.remove(it); map.overlays.add(it) }
        map.invalidate()
    }

    private fun clearPlannedOverlays() {
        for (o in plannedOverlays) map.overlays.remove(o)
        plannedOverlays.clear()
    }

    private fun clearLiveOverlays() {
        clearPlannedOverlays()
        posMarker?.let { map.overlays.remove(it) }
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
                val url = URL(
                    "https://nominatim.openstreetmap.org/search?q=$q&format=json" +
                        "&polygon_geojson=1&polygon_threshold=0.0004&limit=5"
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "gps-spoof-app/1.0")
                conn.connectTimeout = 12000
                conn.readTimeout = 12000
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(txt)
                var chosen: List<List<GeoPoint>>? = null
                var fLat = Double.NaN; var fLng = Double.NaN
                for (idx in 0 until arr.length()) {
                    val o = arr.getJSONObject(idx)
                    if (fLat.isNaN()) {
                        fLat = o.getString("lat").toDouble()
                        fLng = o.getString("lon").toDouble()
                    }
                    val gj = o.optJSONObject("geojson") ?: continue
                    val rings = parseGeoJson(gj) ?: continue
                    chosen = rings
                    break
                }
                val foundRings = chosen
                val okLat = fLat; val okLng = fLng
                runOnUiThread {
                    when {
                        foundRings != null -> applyBoundary(foundRings)
                        !okLat.isNaN() -> {
                            boundaryRings = null; clearBoundaryOverlays()
                            map.controller.setZoom(15.0)
                            setCenter(GeoPoint(okLat, okLng), true)
                        }
                        else -> toast("Non trovato: $query")
                    }
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
        if (!hasLocationPermission()) { requestPermissions(); toast("Concedi il permesso posizione"); return }
        if (boundaryRings == null && centerPoint == null) return

        val sig = computeSig()
        val p = getSharedPreferences(MockLocationService.PROGRESS_PREFS, Context.MODE_PRIVATE)
        val savedSig = p.getString(MockLocationService.KEY_SIG, null)
        val traveled = if (p.contains(MockLocationService.KEY_TRAVELED))
            java.lang.Double.longBitsToDouble(p.getLong(MockLocationService.KEY_TRAVELED, 0)) else 0.0
        val total = if (p.contains(MockLocationService.KEY_TOTAL))
            java.lang.Double.longBitsToDouble(p.getLong(MockLocationService.KEY_TOTAL, 0)) else 0.0
        val hasSaved = savedSig == sig && traveled > 1.0 && total > 0.0
        val pct = if (total > 0.0) (traveled * 100 / total).toInt().coerceIn(0, 100) else 0

        if (!hasSaved) { doStartRoam(0.0, sig); return }

        // c'e' un giro salvato di questa stessa zona: chiedi da zero o ripresa
        AlertDialog.Builder(this)
            .setTitle("Ripartire da zero?")
            .setMessage("Hai gia un giro salvato al $pct%.\nVuoi RICOMINCIARE tutto da zero?")
            .setPositiveButton("Si, da zero") { _, _ -> clearProgress(); doStartRoam(0.0, sig) }
            .setNegativeButton("No") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Riprendo da dove eri")
                    .setMessage("Ottimo! Riparto dall'ultima posizione salvata (sei al $pct%).")
                    .setPositiveButton("Si, riprendi") { _, _ -> doStartRoam(traveled, sig) }
                    .setNegativeButton("Annulla") { d, _ -> d.dismiss() }
                    .setCancelable(true)
                    .show()
            }
            .setCancelable(true)
            .show()
    }

    // avvia davvero la serpentina. startTraveled>0 = ripresa dal punto salvato.
    private fun doStartRoam(startTraveled: Double, sig: String) {
        val cp = centerPoint ?: return
        clearLiveOverlays()
        drawPlanned(true)
        val b = boundaryRings
        val i = Intent(this, MockLocationService::class.java).apply {
            putExtra(MockLocationService.EXTRA_MODE, MockLocationService.MODE_ROAM)
            putExtra(MockLocationService.EXTRA_LAT, cp.latitude)
            putExtra(MockLocationService.EXTRA_LNG, cp.longitude)
            putExtra(MockLocationService.EXTRA_SPEED_KMH, speedKmh.toDouble())
            putExtra(MockLocationService.EXTRA_SERPENTINE, true)
            putExtra(MockLocationService.EXTRA_START_TRAVELED, startTraveled)
            putExtra(MockLocationService.EXTRA_SIG, sig)
            if (b != null) {
                putExtra(MockLocationService.EXTRA_BOUNDARY, encodeRings(b))
            } else {
                putExtra(MockLocationService.EXTRA_RADIUS, radiusMeters)
                putExtra(MockLocationService.EXTRA_SQUARE, false)
            }
        }
        startForegroundService(i)
        val resume = if (startTraveled > 0) " — RIPRESA" else ""
        setStatus(if (b != null) "Serpentina citta avviata$resume ($speedKmh km/h)"
                  else "Avviato$resume ($speedKmh km/h, $radiusMeters m)")
    }

    // firma della zona: distingue un giro salvato per confine citta o per cerchio
    private fun computeSig(): String {
        val b = boundaryRings
        if (b != null) {
            var minLat = 90.0; var maxLat = -90.0; var minLng = 180.0; var maxLng = -180.0
            for (ring in b) for (pt in ring) {
                minLat = min(minLat, pt.latitude); maxLat = max(maxLat, pt.latitude)
                minLng = min(minLng, pt.longitude); maxLng = max(maxLng, pt.longitude)
            }
            return "b:%.4f,%.4f,%.4f,%.4f".format(minLat, minLng, maxLat, maxLng)
        }
        val cp = centerPoint ?: return "none"
        return "c:%.5f,%.5f,r%d".format(cp.latitude, cp.longitude, radiusMeters)
    }

    private fun clearProgress() {
        getSharedPreferences(MockLocationService.PROGRESS_PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
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
