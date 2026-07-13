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

class MockLocationService : Service() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_WAYPOINTS = "waypoints"
        const val EXTRA_SPEED_KMH = "speed_kmh"
        const val EXTRA_LOOP = "loop"
        const val MODE_FIXED = "fixed"
        const val MODE_ROUTE = "route"

        private const val CHANNEL_ID = "gps_spoof"
        private const val NOTIF_ID = 1
        private const val UPDATE_MS = 1000L
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

        val mode = intent.getStringExtra(EXTRA_MODE)
        when (mode) {
            MODE_FIXED -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                val lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
                startFixed(lat, lng)
            }
            MODE_ROUTE -> {
                val raw = intent.getStringExtra(EXTRA_WAYPOINTS) ?: ""
                val speedKmh = intent.getDoubleExtra(EXTRA_SPEED_KMH, 5.0)
                val loop = intent.getBooleanExtra(EXTRA_LOOP, false)
                startRoute(parseWaypoints(raw), speedKmh, loop)
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------------- modes ----------------

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

    private fun startRoute(points: List<DoubleArray>, speedKmh: Double, loop: Boolean) {
        if (points.size < 2) {
            stopSelf()
            return
        }
        val speedMs = (speedKmh / 3.6).coerceAtLeast(0.1)
        running = true
        worker = Thread {
            do {
                for (i in 0 until points.size - 1) {
                    if (!running) break
                    walkSegment(points[i], points[i + 1], speedMs.toFloat())
                }
            } while (running && loop)
            if (running) {
                // arrivato: resta fermo sull'ultimo punto
                val last = points.last()
                while (running) {
                    push(last[0], last[1], 0f, 0f)
                    sleep(UPDATE_MS)
                }
            }
        }.also { it.start() }
        updateNotification("Percorso in corso (${speedKmh} km/h)")
    }

    private fun walkSegment(a: DoubleArray, b: DoubleArray, speedMs: Float) {
        val res = FloatArray(2)
        Location.distanceBetween(a[0], a[1], b[0], b[1], res)
        val dist = res[0]
        val bearing = res[1]
        val intervalSec = UPDATE_MS / 1000.0
        val steps = Math.max(1, Math.ceil(dist / (speedMs * intervalSec)).toInt())
        for (s in 0..steps) {
            if (!running) return
            val f = s.toDouble() / steps
            val lat = a[0] + (b[0] - a[0]) * f
            val lng = a[1] + (b[1] - a[1]) * f
            push(lat, lng, speedMs, bearing)
            sleep(UPDATE_MS)
        }
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
                // provider non disponibile su questo device, ignora
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
        l.bearing = bearing
        l.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            l.bearingAccuracyDegrees = 0.1f
            l.speedAccuracyMetersPerSecond = 0.1f
            l.verticalAccuracyMeters = 0.1f
        }
        return l
    }

    // ---------------- helpers ----------------

    private fun parseWaypoints(raw: String): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for (line in raw.split("\n")) {
            val parts = line.trim().split(",")
            if (parts.size == 2) {
                val la = parts[0].trim().toDoubleOrNull()
                val ln = parts[1].trim().toDoubleOrNull()
                if (la != null && ln != null) out.add(doubleArrayOf(la, ln))
            }
        }
        return out
    }

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
