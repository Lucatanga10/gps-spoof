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
        const val MODE_FIXED = "fixed"
        const val MODE_ROAM = "roam"
        const val ACTION_UPDATE = "com.gpsspoof.app.UPDATE"

        // progresso salvato: sopravvive a stop/chiusura app -> permette la ripresa
        const val PROGRESS_PREFS = "spoof_progress"
        const val KEY_SIG = "sig"
        const val KEY_TRAVELED = "traveled"
        const val KEY_TOTAL = "total"
        const val KEY_LAT = "lat"
        const val KEY_LNG = "lng"

        private const val CHANNEL_ID = "gps_spoof"
        private const val NOTIF_ID = 1
        private const val UPDATE_MS = 1000L
        private const val METERS_PER_DEG = 111320.0
        private const val GRID_N = 40

        // corsie serpentina: devono combaciare con MainActivity (attaccate, con tetto)
        private const val LANE_WIDTH_M = 8.0
        private const val MAX_LANES = 4000
    }

    private lateinit var lm: LocationManager
    private var fused: FusedLocationProviderClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    private var worker: Thread? = null

    private val testProviders = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER
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
        l.altitude = 30.0
        l.accuracy = 1.0f
        l.time = System.currentTimeMillis()
        l.speed = speed
        l.bearing = if (bearing < 0) bearing + 360f else bearing % 360f
        l.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            l.bearingAccuracyDegrees = 0.1f
            l.speedAccuracyMetersPerSecond = 0.1f
            l.verticalAccuracyMeters = 0.1f
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
