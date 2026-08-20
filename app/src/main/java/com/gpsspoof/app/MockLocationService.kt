package com.gpsspoof.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class MockLocationService : Service() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_RADIUS = "radius"
        const val EXTRA_SQUARE = "square"
        const val EXTRA_SPEED_KMH = "speed_kmh"
        const val EXTRA_SERPENTINE = "serpentine"
        const val EXTRA_BOUNDARY = "boundary"
        const val EXTRA_COV = "coverage"
        const val EXTRA_REMAINING = "remaining_secs"
        const val EXTRA_START_TRAVELED = "start_traveled"
        const val EXTRA_SIG = "sig"
        const val EXTRA_HEX_STEP = "hex_step"
        const val EXTRA_PUSH_MS = "push_ms"
        const val EXTRA_PUSHES_PER_HEX = "pushes_per_hex"
        const val EXTRA_WALK_FROM_LAT = "walk_from_lat"
        const val EXTRA_WALK_FROM_LNG = "walk_from_lng"
        // POI loop farmer: lista target "lat,lng;lat,lng;..." + dwell + loops
        const val EXTRA_POI_TARGETS = "poi_targets"
        const val EXTRA_POI_HOME_LAT = "poi_home_lat"
        const val EXTRA_POI_HOME_LNG = "poi_home_lng"
        const val EXTRA_POI_DWELL_SEC = "poi_dwell_sec"
        const val EXTRA_POI_LOOPS = "poi_loops"
        const val EXTRA_POI_RETURN_HOME = "poi_return_home"
        const val MODE_FIXED = "fixed"
        const val MODE_ROAM = "roam"
        const val MODE_TURBO = "turbo"
        const val MODE_WALK = "walk"
        const val MODE_POI_LOOP = "poi_loop"
        const val MODE_COUNTRY_TOUR = "country_tour"
        const val EXTRA_TOUR_CODES = "tour_codes"        // "IT;DE;FR;..."
        const val EXTRA_TOUR_TARGETS = "tour_targets"    // "lat,lng;lat,lng;..."
        const val EXTRA_TOUR_DWELL_SEC = "tour_dwell_sec"
        const val EXTRA_TOUR_FROM_LAT = "tour_from_lat"
        const val EXTRA_TOUR_FROM_LNG = "tour_from_lng"
        const val EXTRA_TOUR_TELEPORT = "tour_teleport"  // true = skip walk, jump direct
        const val ACTION_UPDATE = "com.gpsspoof.app.UPDATE"
        const val ACTION_COUNTRY_DONE = "com.gpsspoof.app.COUNTRY_DONE"
        const val EXTRA_COUNTRY_CODE = "country_code"

        // progresso salvato: sopravvive a stop/chiusura app -> permette la ripresa
        const val PROGRESS_PREFS = "spoof_progress"
        const val KEY_SIG = "sig"
        const val KEY_TRAVELED = "traveled"
        const val KEY_TOTAL = "total"
        const val KEY_LAT = "lat"
        const val KEY_LNG = "lng"

        private const val CHANNEL_ID = "gps_spoof"
        private const val NOTIF_ID = 1
        // ROAM SERPENTINA: intatto rispetto alla versione originale, cammino continuo
        private const val UPDATE_MS = 1000L
        private const val METERS_PER_DEG = 111320.0
        private const val GRID_N = 40

        // corsie serpentina originali (8m attaccate) — non toccare, motore Bump funziona cosi
        private const val LANE_WIDTH_M = 8.0
        private const val MAX_LANES = 4000

        // turbo hex grid: passo default. 130m sicuro per H3 res 9 (max diametro ~348m -> spacing < 300m ok)
        private const val DEFAULT_HEX_STEP_M = 130.0
        private const val DEFAULT_TURBO_MS = 150L
    }

    private lateinit var lm: LocationManager
    private var fused: FusedLocationProviderClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    private var worker: Thread? = null

    private val testProviders = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            fused = LocationServices.getFusedLocationProviderClient(this)
        } catch (e: Throwable) {
            fused = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIF_ID, buildNotification("Avvio..."))
        } catch (e: Exception) {
            toast("Impossibile avviare: concedi il permesso di posizione")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!enableProviders()) {
            toast("Seleziona questa app come 'App per posizioni fittizie' nelle Opzioni sviluppatore")
            updateNotification("ERRORE: app non selezionata come mock location")
            stopSelf()
            return START_NOT_STICKY
        }

        stopWorker()

        val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
        val lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_FIXED -> startFixed(lat, lng)
            MODE_ROAM -> {
                val speedKmh = intent.getDoubleExtra(EXTRA_SPEED_KMH, 5.0)
                val startTraveled = intent.getDoubleExtra(EXTRA_START_TRAVELED, 0.0)
                val sig = intent.getStringExtra(EXTRA_SIG) ?: ""
                val boundary = intent.getStringExtra(EXTRA_BOUNDARY)
                if (!boundary.isNullOrEmpty()) {
                    startSweepBoundary(parseRings(boundary), speedKmh, startTraveled, sig)
                } else {
                    val radius = intent.getIntExtra(EXTRA_RADIUS, 500)
                    val square = intent.getBooleanExtra(EXTRA_SQUARE, false)
                    val serpentine = intent.getBooleanExtra(EXTRA_SERPENTINE, true)
                    if (serpentine) startSweep(lat, lng, radius, square, speedKmh, startTraveled, sig)
                    else startRandom(lat, lng, radius, square, speedKmh)
                }
            }
            MODE_TURBO -> {
                val sig = intent.getStringExtra(EXTRA_SIG) ?: ""
                val boundary = intent.getStringExtra(EXTRA_BOUNDARY)
                val hexStep = intent.getDoubleExtra(EXTRA_HEX_STEP, DEFAULT_HEX_STEP_M)
                val pushMs = intent.getLongExtra(EXTRA_PUSH_MS, DEFAULT_TURBO_MS)
                val pushesPerHex = intent.getIntExtra(EXTRA_PUSHES_PER_HEX, 3)
                val rings = if (!boundary.isNullOrEmpty()) parseRings(boundary)
                            else buildCircleRing(lat, lng, intent.getIntExtra(EXTRA_RADIUS, 500))
                startTurbo(rings, hexStep, pushMs, pushesPerHex, sig)
            }
            MODE_WALK -> {
                val speedKmh = intent.getDoubleExtra(EXTRA_SPEED_KMH, 120.0)
                val fromLat = intent.getDoubleExtra(EXTRA_WALK_FROM_LAT, lat)
                val fromLng = intent.getDoubleExtra(EXTRA_WALK_FROM_LNG, lng)
                startWalk(fromLat, fromLng, lat, lng, speedKmh)
            }
            MODE_COUNTRY_TOUR -> {
                val speedKmh = intent.getDoubleExtra(EXTRA_SPEED_KMH, 1000.0)
                val fromLat = intent.getDoubleExtra(EXTRA_TOUR_FROM_LAT, lat)
                val fromLng = intent.getDoubleExtra(EXTRA_TOUR_FROM_LNG, lng)
                val dwellSec = intent.getIntExtra(EXTRA_TOUR_DWELL_SEC, 30)
                val teleport = intent.getBooleanExtra(EXTRA_TOUR_TELEPORT, false)
                val targetsStr = intent.getStringExtra(EXTRA_TOUR_TARGETS) ?: ""
                val codesStr = intent.getStringExtra(EXTRA_TOUR_CODES) ?: ""
                val targets = parsePoiList(targetsStr)
                val codes = codesStr.split(";").filter { it.isNotBlank() }
                if (targets.isEmpty()) {
                    updateNotification("TOUR: nessun paese selezionato")
                    stopSelf()
                } else if (teleport) {
                    startCountryTourTeleport(targets, codes, dwellSec)
                } else {
                    startCountryTour(fromLat, fromLng, targets, codes, speedKmh, dwellSec)
                }
            }
            MODE_POI_LOOP -> {
                val speedKmh = intent.getDoubleExtra(EXTRA_SPEED_KMH, 500.0)
                val homeLat = intent.getDoubleExtra(EXTRA_POI_HOME_LAT, lat)
                val homeLng = intent.getDoubleExtra(EXTRA_POI_HOME_LNG, lng)
                val dwellSec = intent.getIntExtra(EXTRA_POI_DWELL_SEC, 90)
                val loops = intent.getIntExtra(EXTRA_POI_LOOPS, 0)  // 0 = infinito
                val returnHome = intent.getBooleanExtra(EXTRA_POI_RETURN_HOME, true)
                val targets = parsePoiList(intent.getStringExtra(EXTRA_POI_TARGETS) ?: "")
                if (targets.isEmpty()) {
                    updateNotification("Nessun POI selezionato")
                    stopSelf()
                } else {
                    startPoiLoop(homeLat, homeLng, targets, dwellSec, speedKmh, loops, returnHome)
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------------- fisso ----------------

    private fun startFixed(lat: Double, lng: Double) {
        running = true
        worker = Thread {
            while (running) {
                push(lat, lng, 0f, 0f)
                sleep(UPDATE_MS)
            }
        }.also { it.start() }
        updateNotification("Posizione fissa: $lat, $lng")
    }

    // ---------------- serpentina (su/giu) ----------------

    private fun startSweep(cLat: Double, cLng: Double, radius: Int, square: Boolean, speedKmh: Double, startTraveled: Double, sig: String) {
        val speedMs = (speedKmh / 3.6).coerceAtLeast(0.3)
        val cosLat = cos(Math.toRadians(cLat))
        val dLatMax = radius / METERS_PER_DEG
        val dLngMax = radius / (METERS_PER_DEG * cosLat)

        // corsie verticali fitte (nord-sud), da ovest a est, attaccate = area tutta piena
        val laneSpacing = Math.max(LANE_WIDTH_M, (2.0 * radius) / MAX_LANES)
        val lanes = Math.max(2, Math.ceil((2.0 * radius) / laneSpacing).toInt())
        val verts = ArrayList<DoubleArray>()
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
            val bottom = doubleArrayOf(cLat - half, cLng + rLng)
            val top = doubleArrayOf(cLat + half, cLng + rLng)
            if (li % 2 == 0) {
                verts.add(top); verts.add(bottom)   // dall'alto verso il basso
            } else {
                verts.add(bottom); verts.add(top)   // dal basso verso l'alto
            }
        }

        if (verts.size < 2) {
            startRandom(cLat, cLng, radius, square, speedKmh)
            return
        }

        walk(verts, speedMs, cosLat, startTraveled, sig)
        updateNotification("Serpentina avviata (r=${radius}m, ${speedKmh} km/h)")
    }

    // ---------------- serpentina dentro un confine reale (citta) ----------------

    private fun startSweepBoundary(rings: List<List<DoubleArray>>, speedKmh: Double, startTraveled: Double, sig: String) {
        if (rings.isEmpty()) {
            updateNotification("Confine non valido")
            stopSelf()
            return
        }
        val speedMs = (speedKmh / 3.6).coerceAtLeast(0.3)

        var minLat = 90.0; var maxLat = -90.0; var minLng = 180.0; var maxLng = -180.0
        for (ring in rings) for (p in ring) {
            if (p[0] < minLat) minLat = p[0]
            if (p[0] > maxLat) maxLat = p[0]
            if (p[1] < minLng) minLng = p[1]
            if (p[1] > maxLng) maxLng = p[1]
        }
        val cLat = (minLat + maxLat) / 2.0
        val cosLat = cos(Math.toRadians(cLat))

        val verts = buildBoundaryVerts(rings, minLat, maxLat, minLng, maxLng, cosLat)
        if (verts.size < 2) {
            updateNotification("Confine troppo piccolo o non valido")
            stopSelf()
            return
        }
        walk(verts, speedMs, cosLat, startTraveled, sig)
        updateNotification("Serpentina citta avviata (${verts.size} punti, ${speedKmh} km/h)")
    }

    // ---------------- TURBO: teleport hex-by-hex STREAMING (no OOM su continenti) ----------------

    // Enumera griglia esagonale dentro poligono. Streaming: mai piu di 1 riga in RAM.
    // Africa / mondo intero = ok, non alloca tutti gli hex upfront.
    private fun startTurbo(rings: List<List<DoubleArray>>, hexStepM: Double, pushMs: Long, pushesPerHex: Int, sig: String) {
        val stepM = if (hexStepM > 20.0) hexStepM else DEFAULT_HEX_STEP_M
        val tickMs = if (pushMs in 30..5000) pushMs else DEFAULT_TURBO_MS
        val repeats = pushesPerHex.coerceIn(1, 20)

        var minLat = 90.0; var maxLat = -90.0; var minLng = 180.0; var maxLng = -180.0
        for (ring in rings) for (p in ring) {
            if (p[0] < minLat) minLat = p[0]; if (p[0] > maxLat) maxLat = p[0]
            if (p[1] < minLng) minLng = p[1]; if (p[1] > maxLng) maxLng = p[1]
        }
        if (maxLat <= minLat || maxLng <= minLng) { stopSelf(); return }
        val cLat = (minLat + maxLat) / 2.0
        val cosLat = cos(Math.toRadians(cLat))
        val stepLat = stepM / METERS_PER_DEG
        val stepLngAtCenter = stepM / (METERS_PER_DEG * cosLat)
        val rowHeight = stepLat * 0.866  // sqrt(3)/2

        // stima total hex (per progress %) — non alloca nulla, solo conteggio dimensionale
        val bboxAreaM2 = (maxLat - minLat) * METERS_PER_DEG * (maxLng - minLng) * METERS_PER_DEG * cosLat
        val hexAreaM2 = stepM * stepM * 0.866  // hex area approx
        val estTotal = (bboxAreaM2 / hexAreaM2).toLong().coerceAtLeast(1)

        // SPATIAL INDEX per point-in-polygon veloce su poligoni giganti (Africa 5000+ vertici)
        // Per ogni ring, precalcola bbox. Cella fuori bbox = skip poly test.
        val ringBboxes = rings.map { ring ->
            var rMinLat = 90.0; var rMaxLat = -90.0; var rMinLng = 180.0; var rMaxLng = -180.0
            for (p in ring) {
                if (p[0] < rMinLat) rMinLat = p[0]; if (p[0] > rMaxLat) rMaxLat = p[0]
                if (p[1] < rMinLng) rMinLng = p[1]; if (p[1] > rMaxLng) rMaxLng = p[1]
            }
            doubleArrayOf(rMinLat, rMaxLat, rMinLng, rMaxLng)
        }

        // Per zone MOLTO grandi (continenti), skippa point-in-polygon del tutto — usa solo bbox.
        // Anche se coord finisce in mare/deserto vuoto amo grattera se in zona reale.
        val skipPolyTest = bboxAreaM2 > 500_000_000_000.0  // > 500,000 km²

        running = true
        worker = Thread {
            var idx = 0L
            var lastBearing = 0f
            var currentLat = minLat
            var currentRow = 0
            var rowPts = ArrayList<DoubleArray>()
            var idxInRow = 0
            var prevCell: DoubleArray? = null

            // genera prossima cella streaming (mai memoria per tutto)
            fun nextCell(): DoubleArray? {
                while (rowPts.isEmpty() || idxInRow >= rowPts.size) {
                    if (currentLat > maxLat) {
                        // wrap around: ricomincia dall'inizio
                        currentLat = minLat
                        currentRow = 0
                    }
                    rowPts.clear()
                    val cosLatRow = cos(Math.toRadians(currentLat)).coerceAtLeast(0.01)
                    val stepLngRow = stepM / (METERS_PER_DEG * cosLatRow)
                    var lng = minLng + (if (currentRow % 2 == 1) stepLngRow / 2.0 else 0.0)
                    while (lng <= maxLng) {
                        val inZone = if (skipPolyTest) {
                            true
                        } else {
                            // check ring bboxes prima (veloce)
                            var maybe = false
                            for (bb in ringBboxes) {
                                if (currentLat in bb[0]..bb[1] && lng in bb[2]..bb[3]) { maybe = true; break }
                            }
                            if (maybe) pointInRings(currentLat, lng, rings) else false
                        }
                        if (inZone) rowPts.add(doubleArrayOf(currentLat, lng))
                        lng += stepLngRow
                    }
                    if (currentRow % 2 == 1) rowPts.reverse()
                    idxInRow = 0
                    currentLat += rowHeight
                    currentRow++
                    if (rowPts.isNotEmpty()) break
                }
                if (idxInRow < rowPts.size) return rowPts[idxInRow++]
                return null
            }

            updateNotification("Turbo streaming: ~${estTotal / 1000}k hex stimati, ${1000L/tickMs}push/sec x$repeats")

            while (running) {
                val p = nextCell() ?: run {
                    updateNotification("Turbo: nessuna cella nella zona")
                    return@Thread
                }
                prevCell?.let { lastBearing = bearingOf(it, p, cos(Math.toRadians(p[0]))) }
                for (r in 0 until repeats) {
                    if (!running) break
                    val jitLat = p[0] + (Math.random() - 0.5) * 0.00003
                    val jitLng = p[1] + (Math.random() - 0.5) * 0.00003
                    push(jitLat, jitLng, 8f, lastBearing)
                    if (r == 0) {
                        saveProgress(sig, idx.toDouble(), estTotal.toDouble(), jitLat, jitLng)
                        val cov = ((idx * 100 / estTotal.coerceAtLeast(1)).toInt()).coerceIn(0, 100)
                        val remaining = ((estTotal - idx) * repeats * tickMs / 1000L).coerceAtLeast(0)
                        broadcast(jitLat, jitLng, cov, remaining)
                        if (idx % 50L == 0L) {
                            updateNotification("Turbo: ${idx}/~${estTotal} hex ($cov%, ${repeats}x/hex)")
                        }
                    }
                    sleep(tickMs)
                }
                prevCell = p
                idx++
            }
        }.also { it.start() }
    }

    // ray casting even-odd point-in-polygon (buchi supportati via anelli multipli)
    private fun pointInRings(lat: Double, lng: Double, rings: List<List<DoubleArray>>): Boolean {
        var inside = false
        for (ring in rings) {
            val n = ring.size
            if (n < 3) continue
            var j = n - 1
            for (i in 0 until n) {
                val yi = ring[i][0]; val xi = ring[i][1]
                val yj = ring[j][0]; val xj = ring[j][1]
                if (((yi > lat) != (yj > lat)) &&
                    (lng < (xj - xi) * (lat - yi) / (yj - yi + 1e-12) + xi)) {
                    inside = !inside
                }
                j = i
            }
        }
        return inside
    }

    // ---------------- POI LOOP: farming visite ristoranti/negozi ----------------

    // ciclo:  home -> POI1 -> dwell -> home -> POI2 -> dwell -> home ...
    //         (o home -> POI1 -> dwell -> POI2 -> dwell ... senza tornare, se returnHome=false)
    //   speedKmh = velocita di walk simulata tra due punti
    //   dwellSec = quanto restare fermi sul POI (=  quanto amo vuole per contare la visita, ~90s tipico)
    //   loops = 0 → infinito, altrimenti quante volte fare il giro completo di tutti i POI
    private fun startPoiLoop(
        homeLat: Double, homeLng: Double,
        targets: List<DoubleArray>,
        dwellSec: Int, speedKmh: Double,
        loops: Int, returnHome: Boolean
    ) {
        val speedMs = (speedKmh / 3.6).coerceAtLeast(1.0)
        val stepM = speedMs * (UPDATE_MS / 1000.0)

        running = true
        worker = Thread {
            var loopIdx = 0
            var totalVisits = 0
            var curLat = homeLat
            var curLng = homeLng

            while (running && (loops == 0 || loopIdx < loops)) {
                loopIdx++
                for ((tIdx, target) in targets.withIndex()) {
                    if (!running) break
                    val tLat = target[0]; val tLng = target[1]

                    // walk verso POI
                    val cosLat = cos(Math.toRadians((curLat + tLat) / 2.0))
                    val totalM = distM(doubleArrayOf(curLat, curLng), doubleArrayOf(tLat, tLng), cosLat)
                    val bearing = bearingOf(doubleArrayOf(curLat, curLng), doubleArrayOf(tLat, tLng), cosLat)
                    val fromLat = curLat; val fromLng = curLng
                    var traveled = 0.0
                    updateNotification("POI loop $loopIdx: → ${tIdx + 1}/${targets.size} (${(totalM/1000).toInt()} km)")
                    while (running && traveled < totalM) {
                        val f = (traveled / totalM).coerceIn(0.0, 1.0)
                        curLat = fromLat + (tLat - fromLat) * f
                        curLng = fromLng + (tLng - fromLng) * f
                        push(curLat, curLng, speedMs.toFloat(), bearing)
                        val cov = (traveled * 100 / totalM).toInt().coerceIn(0, 100)
                        val remaining = ((totalM - traveled) / speedMs).toLong()
                        broadcast(curLat, curLng, cov, remaining)
                        traveled += stepM
                        sleep(UPDATE_MS)
                    }
                    // arrivato: fissa esatto sul POI
                    if (!running) break
                    curLat = tLat; curLng = tLng
                    push(curLat, curLng, 0f, bearing)

                    // dwell: resta fermo (con micro-jitter GPS realistico) dwellSec secondi
                    updateNotification("POI $loopIdx/${targets.size}: dwell ${dwellSec}s su POI ${tIdx + 1}")
                    val dwellStart = System.currentTimeMillis()
                    while (running && (System.currentTimeMillis() - dwellStart) < dwellSec * 1000L) {
                        val jLat = tLat + (Math.random() - 0.5) * 0.00002  // ~1-2m
                        val jLng = tLng + (Math.random() - 0.5) * 0.00002
                        push(jLat, jLng, 0f, bearing)
                        val leftSec = (dwellSec * 1000L - (System.currentTimeMillis() - dwellStart)) / 1000L
                        broadcast(tLat, tLng, 100, leftSec)
                        sleep(UPDATE_MS)
                    }
                    totalVisits++
                    updateNotification("POI loop: $totalVisits visite completate (giro $loopIdx)")

                    if (!running) break

                    // ritorno a casa (opzionale, tra un POI e il prossimo)
                    if (returnHome && (tIdx < targets.size - 1 || (loops == 0 || loopIdx < loops))) {
                        val cosLat2 = cos(Math.toRadians((curLat + homeLat) / 2.0))
                        val backM = distM(doubleArrayOf(curLat, curLng), doubleArrayOf(homeLat, homeLng), cosLat2)
                        val backBrg = bearingOf(doubleArrayOf(curLat, curLng), doubleArrayOf(homeLat, homeLng), cosLat2)
                        val fLat = curLat; val fLng = curLng
                        var backTrav = 0.0
                        updateNotification("POI loop $loopIdx: ← rientro a casa (${(backM/1000).toInt()} km)")
                        while (running && backTrav < backM) {
                            val f = (backTrav / backM).coerceIn(0.0, 1.0)
                            curLat = fLat + (homeLat - fLat) * f
                            curLng = fLng + (homeLng - fLng) * f
                            push(curLat, curLng, speedMs.toFloat(), backBrg)
                            broadcast(curLat, curLng, (backTrav * 100 / backM).toInt(), ((backM - backTrav) / speedMs).toLong())
                            backTrav += stepM
                            sleep(UPDATE_MS)
                        }
                        curLat = homeLat; curLng = homeLng
                        push(curLat, curLng, 0f, backBrg)
                        // pausa breve a casa (5s) per non spammare
                        sleep(5000)
                    }
                }
            }
            updateNotification("POI loop finito: $totalVisits visite totali in $loopIdx giri")
            // rimane fisso su ultima posizione finche non stoppi
            while (running) {
                push(curLat, curLng, 0f, 0f)
                sleep(UPDATE_MS * 3)
            }
        }.also { it.start() }
        updateNotification("POI loop avviato: ${targets.size} POI, dwell ${dwellSec}s, ${if (loops == 0) "∞" else loops.toString()} giri")
    }

    // decodifica "lat,lng;lat,lng;..." -> lista di [lat,lng]
    private fun parsePoiList(s: String): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for (pStr in s.split(';')) {
            if (pStr.isBlank()) continue
            val c = pStr.split(',')
            if (c.size < 2) continue
            val la = c[0].toDoubleOrNull() ?: continue
            val ln = c[1].toDoubleOrNull() ?: continue
            out.add(doubleArrayOf(la, ln))
        }
        return out
    }

    // ---------------- COUNTRY TOUR: WALK sequenziale dalle attuali coord a N capitali ----------------

    // Ciclo:  da (fromLat,fromLng) -> capitale[0] -> capitale[1] -> ... -> capitale[N-1]
    //   Ogni tratto: interpolazione lineare a speedKmh (default 1000). Dwell 30s su ogni capitale.
    //   Notifica progressiva. Broadcast countryCode al completamento tappa.
    private fun startCountryTour(
        fromLat: Double, fromLng: Double,
        targets: List<DoubleArray>,   // [ [lat, lng], ... ] in ordine
        codes: List<String>,          // codici ISO paralleli a targets, per notifiche
        speedKmh: Double,
        dwellSec: Int
    ) {
        val speedMs = (speedKmh / 3.6).coerceAtLeast(1.0)
        val stepM = speedMs * (UPDATE_MS / 1000.0)
        running = true

        worker = Thread {
            var curLat = fromLat; var curLng = fromLng
            for ((idx, tgt) in targets.withIndex()) {
                if (!running) break
                val tLat = tgt[0]; val tLng = tgt[1]
                val code = codes.getOrNull(idx) ?: "?"
                val cosLat = cos(Math.toRadians((curLat + tLat) / 2.0))
                val totalM = distM(doubleArrayOf(curLat, curLng), doubleArrayOf(tLat, tLng), cosLat)
                val bearing = bearingOf(doubleArrayOf(curLat, curLng), doubleArrayOf(tLat, tLng), cosLat)
                val startLat = curLat; val startLng = curLng
                var traveled = 0.0
                updateNotification("TOUR ${idx + 1}/${targets.size}: → $code (${(totalM/1000).toInt()} km)")
                while (running && traveled < totalM) {
                    val f = (traveled / totalM).coerceIn(0.0, 1.0)
                    curLat = startLat + (tLat - startLat) * f
                    curLng = startLng + (tLng - startLng) * f
                    push(curLat, curLng, speedMs.toFloat(), bearing)
                    val cov = (traveled * 100 / totalM.coerceAtLeast(1.0)).toInt().coerceIn(0, 100)
                    val remaining = ((totalM - traveled) / speedMs).toLong().coerceAtLeast(0)
                    broadcast(curLat, curLng, cov, remaining)
                    traveled += stepM
                    sleep(UPDATE_MS)
                }
                if (!running) break
                curLat = tLat; curLng = tLng
                push(curLat, curLng, 0f, bearing)

                // dwell su capitale
                updateNotification("TOUR ${idx + 1}/${targets.size}: gratto $code (dwell ${dwellSec}s)")
                val t0 = System.currentTimeMillis()
                while (running && (System.currentTimeMillis() - t0) < dwellSec * 1000L) {
                    val jLat = tLat + (Math.random() - 0.5) * 0.00002
                    val jLng = tLng + (Math.random() - 0.5) * 0.00002
                    push(jLat, jLng, 0f, bearing)
                    val left = (dwellSec * 1000L - (System.currentTimeMillis() - t0)) / 1000L
                    broadcast(tLat, tLng, 100, left)
                    sleep(UPDATE_MS)
                }

                // notifica UI che questo paese e' fatto (per marcarlo come done in prefs)
                broadcastCountryDone(code, tLat, tLng)
            }
            updateNotification("TOUR completato: ${targets.size} paesi grattati")
            // resta fisso sull'ultima capitale
            while (running) {
                push(curLat, curLng, 0f, 0f)
                sleep(UPDATE_MS * 3)
            }
        }.also { it.start() }
        updateNotification("TOUR avviato: ${targets.size} paesi da ${fromLat.format(3)},${fromLng.format(3)} @ ${speedKmh.toInt()} km/h")
    }

    // TELEPORT: nessun walk, salta direttamente sulla capitale + push burst intensivo
    // Amo probabilmente vuole cluster di N sample nella stessa hex per committare scratch.
    // Push a 5Hz (200ms) durante tutto dwell = tanti sample → cluster satisfatto.
    private fun startCountryTourTeleport(
        targets: List<DoubleArray>,
        codes: List<String>,
        dwellSec: Int
    ) {
        running = true
        worker = Thread {
            var totalDone = 0
            val pushIntervalMs = 200L  // 5 push/sec
            for ((idx, tgt) in targets.withIndex()) {
                if (!running) break
                val tLat = tgt[0]; val tLng = tgt[1]
                val code = codes.getOrNull(idx) ?: "?"

                // Burst iniziale: 10 push rapidi (50ms apart) per fix immediato
                for (r in 0 until 10) {
                    if (!running) break
                    val jLat = tLat + (Math.random() - 0.5) * 0.00002
                    val jLng = tLng + (Math.random() - 0.5) * 0.00002
                    push(jLat, jLng, 0f, 0f)
                    sleep(50)
                }
                if (!running) break

                updateNotification("TELEPORT ${idx + 1}/${targets.size}: $code (dwell ${dwellSec}s, burst 5Hz)")

                // dwell con push 5Hz sulla capitale (satisfare cluster N di amo)
                val t0 = System.currentTimeMillis()
                var pushCount = 0
                while (running && (System.currentTimeMillis() - t0) < dwellSec * 1000L) {
                    val jLat = tLat + (Math.random() - 0.5) * 0.00002
                    val jLng = tLng + (Math.random() - 0.5) * 0.00002
                    push(jLat, jLng, 0f, 0f)
                    pushCount++
                    val left = (dwellSec * 1000L - (System.currentTimeMillis() - t0)) / 1000L
                    broadcast(tLat, tLng, 100, left)
                    sleep(pushIntervalMs)
                }
                if (!running) break

                totalDone++
                broadcastCountryDone(code, tLat, tLng)
            }
            updateNotification("TELEPORT TOUR completato: $totalDone/${targets.size} paesi")
            val last = targets.lastOrNull()
            while (running && last != null) {
                push(last[0], last[1], 0f, 0f)
                sleep(UPDATE_MS * 3)
            }
        }.also { it.start() }
        val eta = targets.size * dwellSec / 60
        updateNotification("TELEPORT TOUR avviato: ${targets.size} paesi, dwell ${dwellSec}s (~${eta} min totali)")
    }

    private fun Double.format(digits: Int): String = "%.${digits}f".format(this)

    private fun broadcastCountryDone(code: String, lat: Double, lng: Double) {
        val i = Intent(ACTION_COUNTRY_DONE).setPackage(packageName)
        i.putExtra(EXTRA_COUNTRY_CODE, code)
        i.putExtra(EXTRA_LAT, lat)
        i.putExtra(EXTRA_LNG, lng)
        sendBroadcast(i)
    }

    // ---------------- WALK: cammino graduale casa -> target per Bump confidence ----------------

    // interpola linea retta da (fromLat, fromLng) a (toLat, toLng) a velocita realistica.
    // Bump vede utente che si sposta continuamente senza teletrasporti -> confidence sale.
    private fun startWalk(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double, speedKmh: Double) {
        val speedMs = (speedKmh / 3.6).coerceAtLeast(1.0)
        val stepM = speedMs * (UPDATE_MS / 1000.0)
        val cosLat = cos(Math.toRadians((fromLat + toLat) / 2.0))
        val totalM = distM(doubleArrayOf(fromLat, fromLng), doubleArrayOf(toLat, toLng), cosLat)
        val bearing = bearingOf(doubleArrayOf(fromLat, fromLng), doubleArrayOf(toLat, toLng), cosLat)

        running = true
        worker = Thread {
            var traveled = 0.0
            var lat = fromLat; var lng = fromLng
            while (running && traveled < totalM) {
                val f = (traveled / totalM).coerceIn(0.0, 1.0)
                lat = fromLat + (toLat - fromLat) * f
                lng = fromLng + (toLng - fromLng) * f
                push(lat, lng, speedMs.toFloat(), bearing)
                val cov = (traveled * 100 / totalM).toInt().coerceIn(0, 100)
                val remaining = ((totalM - traveled) / speedMs).toLong()
                broadcast(lat, lng, cov, remaining)
                if ((traveled / stepM).toInt() % 20 == 0) {
                    updateNotification("Walk: ${(traveled/1000).toInt()}/${(totalM/1000).toInt()} km ($cov%)")
                }
                traveled += stepM
                sleep(UPDATE_MS)
            }
            // arrivato: rimane fermo sulla destinazione finche' l'utente non stoppa
            updateNotification("Walk completato. Fermo a destinazione — pronto per Turbo.")
            while (running) {
                push(toLat, toLng, 0f, bearing)
                broadcast(toLat, toLng, 100, 0)
                sleep(UPDATE_MS * 5)
            }
        }.also { it.start() }
        updateNotification("Walk avviato: ${(totalM/1000).toInt()} km a ${speedKmh.toInt()} km/h")
    }

    // corsie verticali (nord-sud) tagliate sul confine reale con regola even-odd
    private fun buildBoundaryVerts(
        rings: List<List<DoubleArray>>,
        minLat: Double, maxLat: Double, minLng: Double, maxLng: Double,
        cosLat: Double
    ): ArrayList<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val spanLng = maxLng - minLng
        if (spanLng <= 0 || maxLat <= minLat) return out
        val widthM = spanLng * METERS_PER_DEG * cosLat
        val laneSpacingM = Math.max(LANE_WIDTH_M, widthM / MAX_LANES)
        val lanes = Math.max(2, Math.ceil(widthM / laneSpacingM).toInt())
        val spacing = spanLng / lanes
        for (li in 0 until lanes) {
            val x = minLng + spacing * (li + 0.5)
            val ys = scanline(rings, x)
            if (ys.size < 2) continue
            // coppie consecutive = intervalli dentro il poligono (even-odd)
            val intervals = ArrayList<DoubleArray>()
            var k = 0
            while (k + 1 < ys.size) { intervals.add(doubleArrayOf(ys[k], ys[k + 1])); k += 2 }
            if (intervals.isEmpty()) continue
            if (li % 2 == 0) {
                // dall'alto verso il basso
                for (iv in intervals.indices.reversed()) {
                    out.add(doubleArrayOf(intervals[iv][1], x))
                    out.add(doubleArrayOf(intervals[iv][0], x))
                }
            } else {
                for (iv in intervals.indices) {
                    out.add(doubleArrayOf(intervals[iv][0], x))
                    out.add(doubleArrayOf(intervals[iv][1], x))
                }
            }
        }
        return out
    }

    // latitudini dove la verticale lng=x incrocia tutti gli anelli (bordi + buchi)
    private fun scanline(rings: List<List<DoubleArray>>, x: Double): DoubleArray {
        val ys = ArrayList<Double>()
        for (ring in rings) {
            val n = ring.size
            if (n < 2) continue
            for (i in 0 until n) {
                val a = ring[i]
                val b = ring[(i + 1) % n]
                val x1 = a[1]; val x2 = b[1]
                if ((x1 <= x && x2 > x) || (x2 <= x && x1 > x)) {
                    val t = (x - x1) / (x2 - x1)
                    ys.add(a[0] + t * (b[0] - a[0]))
                }
            }
        }
        ys.sort()
        return ys.toDoubleArray()
    }

    // costruisce un anello circolare finto se non hai un confine reale (turbo su cerchio manuale)
    private fun buildCircleRing(cLat: Double, cLng: Double, radiusM: Int): List<List<DoubleArray>> {
        val cosLat = cos(Math.toRadians(cLat))
        val dLat = radiusM / METERS_PER_DEG
        val dLng = radiusM / (METERS_PER_DEG * cosLat)
        val pts = ArrayList<DoubleArray>()
        val steps = 64
        for (i in 0 until steps) {
            val a = 2.0 * Math.PI * i / steps
            pts.add(doubleArrayOf(cLat + dLat * kotlin.math.sin(a), cLng + dLng * kotlin.math.cos(a)))
        }
        return listOf(pts)
    }

    // decodifica "lat,lng lat,lng ; lat,lng ..." -> anelli
    private fun parseRings(s: String): List<List<DoubleArray>> {
        val rings = ArrayList<List<DoubleArray>>()
        for (ringStr in s.split(';')) {
            val pts = ArrayList<DoubleArray>()
            for (pStr in ringStr.split(' ')) {
                if (pStr.isBlank()) continue
                val c = pStr.split(',')
                if (c.size < 2) continue
                val la = c[0].toDoubleOrNull() ?: continue
                val ln = c[1].toDoubleOrNull() ?: continue
                pts.add(doubleArrayOf(la, ln))
            }
            if (pts.size >= 3) rings.add(pts)
        }
        return rings
    }

    // motore di cammino condiviso: percorre i vertici a passo costante, ciclando.
    // startTraveled = metri gia percorsi in un giro precedente (ripresa da posizione salvata).
    private fun walk(verts: List<DoubleArray>, speedMs: Double, cosLat: Double, startTraveled: Double, sig: String) {
        val stepM = speedMs * (UPDATE_MS / 1000.0)
        var totalLen = 0.0
        for (i in 0 until verts.size - 1) totalLen += distM(verts[i], verts[i + 1], cosLat)
        if (totalLen <= 0) totalLen = 1.0
        val total = totalLen

        running = true
        worker = Thread {
            var seg = 0
            var cur = doubleArrayOf(verts[0][0], verts[0][1])
            var traveled = 0.0

            // ripresa: avanza fino al punto salvato prima di iniziare a spingere posizioni
            val startAt = if (startTraveled > 0) startTraveled % total else 0.0
            if (startAt > 0) {
                var acc = 0.0
                while (seg < verts.size - 1) {
                    val a = verts[seg]; val b = verts[seg + 1]
                    val segLen = distM(a, b, cosLat)
                    if (acc + segLen >= startAt) {
                        val f = if (segLen > 0) (startAt - acc) / segLen else 0.0
                        cur = doubleArrayOf(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)
                        traveled = startAt
                        break
                    }
                    acc += segLen; seg++
                }
            }
            var lastBearing = 0f

            while (running) {
                var stepRemaining = stepM
                var guard = 0
                while (stepRemaining > 0 && running && guard < verts.size + 2) {
                    val tgt = verts[seg + 1]
                    val segLen = distM(cur, tgt, cosLat)
                    lastBearing = bearingOf(cur, tgt, cosLat)
                    if (segLen <= stepRemaining) {
                        cur = doubleArrayOf(tgt[0], tgt[1])
                        stepRemaining -= segLen
                        traveled += segLen
                        seg++
                        if (seg >= verts.size - 1) {
                            // percorso completato: ricomincia
                            seg = 0
                            cur = doubleArrayOf(verts[0][0], verts[0][1])
                            traveled = 0.0
                            guard++
                        }
                    } else {
                        val f = stepRemaining / segLen
                        cur = doubleArrayOf(
                            cur[0] + (tgt[0] - cur[0]) * f,
                            cur[1] + (tgt[1] - cur[1]) * f
                        )
                        traveled += stepRemaining
                        stepRemaining = 0.0
                    }
                }

                val cov = (traveled * 100 / total).toInt().coerceIn(0, 100)
                val remainingSecs = ((total - traveled) / speedMs).toLong().coerceAtLeast(0)
                push(cur[0], cur[1], speedMs.toFloat(), lastBearing)
                saveProgress(sig, traveled, total, cur[0], cur[1])
                broadcast(cur[0], cur[1], cov, remainingSecs)
                if (traveled < stepM) updateNotification("Serpentina: nuovo giro")
                else updateNotification("Serpentina: completato $cov%")
                sleep(UPDATE_MS)
            }
        }.also { it.start() }
    }

    // salva il progresso ogni secondo: se l'app viene chiusa o fermata, si puo riprendere da qui
    private fun saveProgress(sig: String, traveled: Double, total: Double, lat: Double, lng: Double) {
        try {
            getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SIG, sig)
                .putLong(KEY_TRAVELED, java.lang.Double.doubleToRawLongBits(traveled))
                .putLong(KEY_TOTAL, java.lang.Double.doubleToRawLongBits(total))
                .putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(lat))
                .putLong(KEY_LNG, java.lang.Double.doubleToRawLongBits(lng))
                .apply()
        } catch (e: Exception) {
        }
    }

    // ---------------- a caso ----------------

    private fun startRandom(cLat: Double, cLng: Double, radius: Int, square: Boolean, speedKmh: Double) {
        val speedMs = (speedKmh / 3.6).coerceAtLeast(0.3)
        val stepM = speedMs * (UPDATE_MS / 1000.0)
        val cosLat = cos(Math.toRadians(cLat))
        val dLatMax = radius / METERS_PER_DEG
        val dLngMax = radius / (METERS_PER_DEG * cosLat)

        val minLat = cLat - dLatMax
        val minLng = cLng - dLngMax
        val cellLat = (2 * dLatMax) / GRID_N
        val cellLng = (2 * dLngMax) / GRID_N
        val inside = BooleanArray(GRID_N * GRID_N)
        val visited = BooleanArray(GRID_N * GRID_N)
        var totalInside = 0
        for (iy in 0 until GRID_N) {
            for (ix in 0 until GRID_N) {
                val rLat = (minLat + (iy + 0.5) * cellLat) - cLat
                val rLng = (minLng + (ix + 0.5) * cellLng) - cLng
                val isin = if (square) Math.abs(rLat) <= dLatMax && Math.abs(rLng) <= dLngMax
                else hypot(rLat / dLatMax, rLng / dLngMax) <= 1.0
                if (isin) { inside[iy * GRID_N + ix] = true; totalInside++ }
            }
        }
        if (totalInside == 0) totalInside = 1
        var visitedCount = 0

        fun markVisited(lat: Double, lng: Double) {
            val ix = ((lng - minLng) / cellLng).toInt().coerceIn(0, GRID_N - 1)
            val iy = ((lat - minLat) / cellLat).toInt().coerceIn(0, GRID_N - 1)
            val idx = iy * GRID_N + ix
            if (inside[idx] && !visited[idx]) { visited[idx] = true; visitedCount++ }
        }

        fun pickTarget(): DoubleArray {
            repeat(300) {
                val ix = (Math.random() * GRID_N).toInt().coerceIn(0, GRID_N - 1)
                val iy = (Math.random() * GRID_N).toInt().coerceIn(0, GRID_N - 1)
                val idx = iy * GRID_N + ix
                if (inside[idx] && !visited[idx])
                    return doubleArrayOf(minLat + (iy + Math.random()) * cellLat, minLng + (ix + Math.random()) * cellLng)
            }
            repeat(300) {
                val ix = (Math.random() * GRID_N).toInt().coerceIn(0, GRID_N - 1)
                val iy = (Math.random() * GRID_N).toInt().coerceIn(0, GRID_N - 1)
                val idx = iy * GRID_N + ix
                if (inside[idx])
                    return doubleArrayOf(minLat + (iy + Math.random()) * cellLat, minLng + (ix + Math.random()) * cellLng)
            }
            return doubleArrayOf(cLat, cLng)
        }

        running = true
        worker = Thread {
            var lat = cLat
            var lng = cLng
            var heading = Math.random() * 2 * Math.PI
            var target = pickTarget()
            var tick = 0
            markVisited(lat, lng)
            while (running) {
                val dxm = (target[1] - lng) * METERS_PER_DEG * cosLat
                val dym = (target[0] - lat) * METERS_PER_DEG
                val dist = hypot(dxm, dym)
                val angleTo = atan2(dxm, dym)
                heading += norm(angleTo - heading).coerceIn(-0.25, 0.25) + (Math.random() - 0.5) * 0.15

                var nLat = lat + (stepM * cos(heading)) / METERS_PER_DEG
                var nLng = lng + (stepM * sin(heading)) / (METERS_PER_DEG * cosLat)
                var rLat = nLat - cLat
                var rLng = nLng - cLng
                val outside = if (square) Math.abs(rLat) > dLatMax || Math.abs(rLng) > dLngMax
                else hypot(rLat / dLatMax, rLng / dLngMax) > 1.0
                if (outside) {
                    if (square) { rLat = rLat.coerceIn(-dLatMax, dLatMax); rLng = rLng.coerceIn(-dLngMax, dLngMax) }
                    else { val k = hypot(rLat / dLatMax, rLng / dLngMax); rLat /= k; rLng /= k }
                    nLat = cLat + rLat; nLng = cLng + rLng
                    target = pickTarget()
                }
                lat = nLat; lng = nLng
                markVisited(lat, lng)
                tick++
                if (dist < stepM * 1.5 || tick % 5 == 0) target = pickTarget()

                val cov = (visitedCount * 100 / totalInside).coerceIn(0, 100)
                push(lat, lng, speedMs.toFloat(), Math.toDegrees(heading).toFloat())
                broadcast(lat, lng, cov)
                if (tick % 3 == 0) updateNotification("A caso: copertura $cov%")
                sleep(UPDATE_MS)
            }
        }.also { it.start() }
        updateNotification("A caso avviato (r=${radius}m, ${speedKmh} km/h)")
    }

    // ---------------- geo helpers ----------------

    private fun distM(a: DoubleArray, b: DoubleArray, cosLat: Double): Double {
        val dxm = (b[1] - a[1]) * METERS_PER_DEG * cosLat
        val dym = (b[0] - a[0]) * METERS_PER_DEG
        return hypot(dxm, dym)
    }

    private fun bearingOf(a: DoubleArray, b: DoubleArray, cosLat: Double): Float {
        val dxm = (b[1] - a[1]) * METERS_PER_DEG * cosLat
        val dym = (b[0] - a[0]) * METERS_PER_DEG
        var deg = Math.toDegrees(atan2(dxm, dym)).toFloat()
        if (deg < 0) deg += 360f
        return deg
    }

    private fun norm(a: Double): Double {
        var x = a
        while (x > Math.PI) x -= 2 * Math.PI
        while (x < -Math.PI) x += 2 * Math.PI
        return x
    }

    private fun broadcast(lat: Double, lng: Double, cov: Int, remainingSecs: Long = -1L) {
        val up = Intent(ACTION_UPDATE).setPackage(packageName)
        up.putExtra(EXTRA_LAT, lat)
        up.putExtra(EXTRA_LNG, lng)
        up.putExtra(EXTRA_COV, cov)
        up.putExtra(EXTRA_REMAINING, remainingSecs)
        sendBroadcast(up)
    }

    // ---------------- location injection ----------------

    private fun enableProviders(): Boolean {
        var ok = false
        for (p in testProviders) {
            try {
                try {
                    lm.removeTestProvider(p)
                } catch (ignore: Exception) {
                }
                lm.addTestProvider(
                    p,
                    false, false, false, false,
                    true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE
                )
                lm.setTestProviderEnabled(p, true)
                ok = true
            } catch (e: SecurityException) {
                return false
            } catch (e: Exception) {
            }
        }
        try {
            fused?.setMockMode(true)
        } catch (e: Exception) {
        }
        return ok
    }

    private fun push(lat: Double, lng: Double, speed: Float, bearing: Float) {
        for (p in testProviders) {
            try {
                lm.setTestProviderLocation(p, makeLocation(p, lat, lng, speed, bearing))
            } catch (e: Exception) {
            }
        }
        try {
            fused?.setMockLocation(makeLocation(LocationManager.GPS_PROVIDER, lat, lng, speed, bearing))
        } catch (e: Exception) {
        }
    }

    private fun makeLocation(provider: String, lat: Double, lng: Double, speed: Float, bearing: Float): Location {
        val l = Location(provider)
        l.latitude = lat
        l.longitude = lng
        // valori realistici jitterati: un GPS vero non dice mai accuracy=1.0 fissa, urla "MOCK"
        l.altitude = 25.0 + Math.random() * 40.0
        l.accuracy = (3.0f + Math.random().toFloat() * 5.0f)
        l.time = System.currentTimeMillis()
        l.speed = if (speed > 0f) speed + (Math.random().toFloat() - 0.5f) * 0.4f else 0f
        l.bearing = if (bearing < 0) bearing + 360f else bearing % 360f
        l.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            l.bearingAccuracyDegrees = 1.0f + Math.random().toFloat() * 2.0f
            l.speedAccuracyMetersPerSecond = 0.3f + Math.random().toFloat() * 0.7f
            l.verticalAccuracyMeters = 1.5f + Math.random().toFloat() * 3.0f
        }
        // conteggio satelliti finto (alcune app lo controllano tramite extras)
        try {
            val extras = l.extras ?: android.os.Bundle()
            extras.putInt("satellites", 8 + (Math.random() * 4).toInt())
            extras.putBoolean("mockLocation", false)
            l.extras = extras
        } catch (e: Throwable) {
        }
        // anti-mock reflection: prova a resettare il flag isFromMockProvider
        // Android 12+: Location.setMock(boolean) esiste ma è @hide/deprecated
        try {
            val setMock = Location::class.java.getMethod("setMock", Boolean::class.javaPrimitiveType)
            setMock.invoke(l, false)
        } catch (e: Throwable) {
        }
        // fallback: manipola direttamente il bit mask interno (varia per versione Android)
        try {
            val f = Location::class.java.getDeclaredField("mFieldsMask")
            f.isAccessible = true
            val mask = f.getInt(l)
            // HAS_MOCK_PROVIDER_MASK = 0x40 (const interna framework)
            f.setInt(l, mask and 0x40.inv())
        } catch (e: Throwable) {
        }
        return l
    }

    // ---------------- helpers ----------------

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            running = false
        }
    }

    private fun stopWorker() {
        running = false
        worker?.let {
            it.interrupt()
            try {
                it.join(1500)
            } catch (e: InterruptedException) {
            }
        }
        worker = null
    }

    private fun toast(s: String) {
        mainHandler.post { Toast.makeText(applicationContext, s, Toast.LENGTH_LONG).show() }
    }

    // ---------------- notification ----------------

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "GPS Spoof", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
            return Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("GPS Spoof attivo")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build()
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
            .setContentTitle("GPS Spoof attivo")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopWorker()
        for (p in testProviders) {
            try {
                lm.setTestProviderEnabled(p, false)
                lm.removeTestProvider(p)
            } catch (e: Exception) {
            }
        }
        try {
            fused?.setMockMode(false)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
