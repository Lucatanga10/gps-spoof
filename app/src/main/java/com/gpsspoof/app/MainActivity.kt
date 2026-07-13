package com.gpsspoof.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        val latInput = findViewById<EditText>(R.id.latInput)
        val lngInput = findViewById<EditText>(R.id.lngInput)
        val waypointsInput = findViewById<EditText>(R.id.waypointsInput)
        val speedInput = findViewById<EditText>(R.id.speedInput)
        val loopCheck = findViewById<CheckBox>(R.id.loopCheck)

        requestPermissions()

        findViewById<Button>(R.id.setFixedButton).setOnClickListener {
            if (!hasLocationPermission()) {
                toast("Concedi il permesso di posizione e riprova")
                requestPermissions()
                return@setOnClickListener
            }
            val lat = latInput.text.toString().trim().toDoubleOrNull()
            val lng = lngInput.text.toString().trim().toDoubleOrNull()
            if (lat == null || lng == null) {
                toast("Latitudine/longitudine non valide")
                return@setOnClickListener
            }
            val i = Intent(this, MockLocationService::class.java).apply {
                putExtra(MockLocationService.EXTRA_MODE, MockLocationService.MODE_FIXED)
                putExtra(MockLocationService.EXTRA_LAT, lat)
                putExtra(MockLocationService.EXTRA_LNG, lng)
            }
            startForegroundService(i)
            setStatus("Posizione fissa: $lat, $lng")
        }

        findViewById<Button>(R.id.startRouteButton).setOnClickListener {
            if (!hasLocationPermission()) {
                toast("Concedi il permesso di posizione e riprova")
                requestPermissions()
                return@setOnClickListener
            }
            val raw = waypointsInput.text.toString().trim()
            val speed = speedInput.text.toString().trim().toDoubleOrNull() ?: 5.0
            if (parseWaypoints(raw).size < 2) {
                toast("Servono almeno 2 punti (lat,lng per riga)")
                return@setOnClickListener
            }
            val i = Intent(this, MockLocationService::class.java).apply {
                putExtra(MockLocationService.EXTRA_MODE, MockLocationService.MODE_ROUTE)
                putExtra(MockLocationService.EXTRA_WAYPOINTS, raw)
                putExtra(MockLocationService.EXTRA_SPEED_KMH, speed)
                putExtra(MockLocationService.EXTRA_LOOP, loopCheck.isChecked)
            }
            startForegroundService(i)
            setStatus("Percorso in corso (${speed} km/h)")
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, MockLocationService::class.java))
            setStatus("Fermo")
        }

        findViewById<Button>(R.id.devOptionsButton).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                toast("Apri manualmente: Impostazioni > Sistema > Opzioni sviluppatore")
            }
        }
    }

    private fun parseWaypoints(raw: String): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        for (line in raw.split("\n")) {
            val parts = line.trim().split(",")
            if (parts.size == 2) {
                val la = parts[0].trim().toDoubleOrNull()
                val ln = parts[1].trim().toDoubleOrNull()
                if (la != null && ln != null) out.add(Pair(la, ln))
            }
        }
        return out
    }

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
        if (perms.isNotEmpty()) {
            requestPermissions(perms.toTypedArray(), 1)
        }
    }

    private fun setStatus(s: String) {
        statusText.text = "Stato: $s"
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }
}
