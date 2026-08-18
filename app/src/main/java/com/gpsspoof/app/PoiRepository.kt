package com.gpsspoof.app

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * PoiRepository — carica POI (ristoranti, bar, negozi, ecc) da OpenStreetMap Overpass.
 * gratis, senza API key. bbox = [south, west, north, east].
 */
object PoiRepository {

    data class Poi(
        val id: Long,
        val name: String,
        val lat: Double,
        val lng: Double,
        val type: String,      // "restaurant", "bar", "cafe", "shop", ...
        val category: String   // human-readable, tipo "Ristorante" / "Bar"
    )

    // categorie che ci interessano per farming visite bump-style
    // (POI = luoghi in cui utenti reali passano tempo → registrano visite)
    private val QUERIES = listOf(
        // ristorazione
        """node["amenity"~"restaurant|cafe|bar|pub|fast_food|ice_cream|food_court"]""" to "Ristorazione",
        // negozi
        """node["shop"]"""                                                            to "Negozio",
        // svago
        """node["leisure"~"fitness_centre|sports_centre|park"]"""                     to "Svago",
        """node["tourism"~"hotel|hostel|guest_house|attraction|museum"]"""            to "Turismo",
        """node["amenity"~"cinema|theatre|nightclub|pharmacy|bank|post_office|library"]""" to "Servizi"
    )

    /**
     * fetch POIs in bbox. cap totale limitato per non intasare la UI.
     * bbox: south, west, north, east (lat, lng, lat, lng)
     */
    fun fetchInBbox(south: Double, west: Double, north: Double, east: Double, cap: Int = 400): List<Poi> {
        val bbox = "$south,$west,$north,$east"
        val queryParts = QUERIES.joinToString("") { (q, _) -> "$q($bbox);" }
        val overpassQL = "[out:json][timeout:25];($queryParts);out center $cap;"
        val body = "data=${URLEncoder.encode(overpassQL, "UTF-8")}"

        // mirrors: Overpass ha 3 endpoint principali, fallback in cascata
        val endpoints = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://z.overpass-api.de/api/interpreter"
        )
        for (url in endpoints) {
            try {
                val json = post(url, body) ?: continue
                return parse(json).take(cap)
            } catch (e: Exception) {
                Log.w("PoiRepository", "overpass $url fallito: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun post(url: String, body: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", "gps-spoof-app/1.0")
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String): List<Poi> {
        val root = JSONObject(json)
        val elements = root.optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Poi>(elements.length())
        for (i in 0 until elements.length()) {
            val e = elements.optJSONObject(i) ?: continue
            val id = e.optLong("id")
            var lat = e.optDouble("lat", Double.NaN)
            var lng = e.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) {
                val c = e.optJSONObject("center") ?: continue
                lat = c.optDouble("lat", Double.NaN)
                lng = c.optDouble("lon", Double.NaN)
            }
            if (lat.isNaN() || lng.isNaN()) continue

            val tags = e.optJSONObject("tags") ?: continue
            val name = tags.optString("name", "").ifBlank { tags.optString("brand", "").ifBlank { "(senza nome)" } }
            val amenity = tags.optString("amenity", "")
            val shop = tags.optString("shop", "")
            val leisure = tags.optString("leisure", "")
            val tourism = tags.optString("tourism", "")
            val type = amenity.ifBlank { shop }.ifBlank { leisure }.ifBlank { tourism }.ifBlank { "poi" }
            val category = when {
                amenity.isNotBlank() && amenity in setOf("restaurant","cafe","bar","pub","fast_food","ice_cream","food_court") -> "Ristorazione"
                shop.isNotBlank() -> "Negozio ($shop)"
                leisure.isNotBlank() -> "Svago"
                tourism.isNotBlank() -> "Turismo"
                amenity.isNotBlank() -> "Servizi"
                else -> "POI"
            }
            out.add(Poi(id, name, lat, lng, type, category))
        }
        return out
    }
}
