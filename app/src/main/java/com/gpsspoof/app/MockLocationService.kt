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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class MockLocationService : Service() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_RADIUS = "radius"
        const val EXTRA_SQUARE = "square"
        const val EXTRA_SPEED_KMH = "speed_kmh"
        const val MODE_FIXED = "fixed"
        const val MODE_ROAM = "roam"

        private const val CHANNEL_ID = "gps_spoof"
        private const val NOTIF_ID = 1
        private const val UPDATE_MS = 1000L
        private const val METERS_PER_DEG = 111320.0
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
                val radius = intent.getIntExtra(EXTRA_RADIUS, 500)
                val square = intent.getBooleanExtra(EXTRA_SQUARE, false)
                val speedKmh = intent.getDoubleExtra(EXTRA_SPEED_KMH, 5.0)
                startRoam(lat, lng, radius, square, speedKmh)
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

    private fun startRoam(cLat: Double, cLng: Double, radius: Int, square: Boolean, speedKmh: Double) {
        val speedMs = (speedKmh / 3.6).coerceAtLeast(0.3)
        val intervalSec = UPDATE_MS / 1000.0
        val stepM = speedMs * intervalSec
        val latRad = Math.toRadians(cLat)
        val dLatMax = radius / METERS_PER_DEG
        val dLngMax = radius / (METERS_PER_DEG * cos(latRad))

        running = true
        worker = Thread {
            var lat = cLat
            var lng = cLng
            var heading = Math.random() * 2 * Math.PI
            while (running) {
                // piccolo cambio di direzione = movimento naturale
                heading += (Math.random() - 0.5) * 1.0
                val stepLat = (stepM * cos(heading)) / METERS_PER_DEG
                val stepLng = (stepM * sin(heading)) / (METERS_PER_DEG * cos(latRad))
                var nLat = lat + stepLat
                var nLng = lng + stepLng

                // relative rispetto al centro
                var rLat = nLat - cLat
                var rLng = nLng - cLng

                val outside = if (square) {
                    (Math.abs(rLat) > dLatMax || Math.abs(rLng) > dLngMax)
                } else {
                    hypot(rLat / dLatMax, rLng / dLngMax) > 1.0
                }

                if (outside) {
                    if (square) {
                        rLat = rLat.coerceIn(-dLatMax, dLatMax)
                        rLng = rLng.coerceIn(-dLngMax, dLngMax)
                    } else {
                        val k = hypot(rLat / dLatMax, rLng / dLngMax)
                        rLat /= k
                        rLng /= k
                    }
                    // rimbalza verso l'interno
                    heading += Math.PI + (Math.random() - 0.5) * 1.0
                    nLat = cLat + rLat
                    nLng = cLng + rLng
                }

                lat = nLat
                lng = nLng
                push(lat, lng, speedMs.toFloat(), Math.toDegrees(heading).toFloat())
                sleep(UPDATE_MS)
            }
        }.also { it.start() }
        updateNotification("Vaga area: r=${radius}m, ${speedKmh} km/h")
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
