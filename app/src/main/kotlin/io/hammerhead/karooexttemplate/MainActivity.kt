package io.hammerhead.karooexttemplate

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    // ====== LE TUE CHIAVI STRAVA ======
    private val clientId = "190895"
    private val clientSecret = "827974f8301df4438afac3c05be00a4dfd723817"
    private val refreshToken = "31475ab564010a7acb01268571b6251b2ea05f78"
    // ==================================

    private data class Descent(
        val name: String,
        val lat: Double,
        val lng: Double,
        val endLat: Double,
        val endLng: Double,
        val poly: String,
        val kom: String,
        val lengthM: Int,
        val curve: String
    )

    private lateinit var output: TextView
    private val descents = ArrayList<Descent>()
    private var status = "Connessione a Strava..."
    private var lastLoc: Location? = null
    private var permissionAsked = false
    private var gpsStarted = false

    private val locationManager by lazy {
        getSystemService(LOCATION_SERVICE) as LocationManager
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLoc = location
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        output = TextView(this).apply {
            textSize = 15f
            setPadding(24, 24, 24, 24)
        }
        scroll.addView(output)
        setContentView(scroll)
        render()

        Thread {
            try {
                loadDescents()
                saveDescents()
                var withCurve = 0
                for (d in descents) if (d.curve.isNotEmpty()) withCurve++
                setStatus("Pronto: ${descents.size} discese salvate\n($withCurve con profilo altimetrico)")
            } catch (e: Exception) {
                setStatus("ERRORE Strava:\n${e.message}")
            }
        }.start()
    }

    private fun setStatus(s: String) {
        status = s
        runOnUiThread { render() }
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            startGps()
        } else if (!permissionAsked) {
            permissionAsked = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    private fun startGps() {
        if (gpsStarted) return
        gpsStarted = true
        try {
            lastLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, listener
            )
        } catch (e: Exception) {
            status = "ERRORE GPS: ${e.message}"
        }
        render()
    }

    override fun onDestroy() {
        try { locationManager.removeUpdates(listener) } catch (e: Exception) { }
        super.onDestroy()
    }

    private fun render() {
        val sb = StringBuilder()
        sb.append(status).append("\n\n")
        val loc = lastLoc
        if (loc == null) {
            sb.append("GPS: in attesa...")
        } else {
            sb.append("GPS: ${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}\n\n")
            if (descents.isNotEmpty()) {
                var nearest: Descent? = null
                var best = -1.0
                for (d in descents) {
                    val dist = haversine(loc.latitude, loc.longitude, d.lat, d.lng)
                    if (best < 0 || dist < best) { best = dist; nearest = d }
                }
                val n = nearest
                if (n != null) {
                    sb.append("Discesa più vicina:\n• ${n.name}\n")
                    sb.append("   partenza a ${best.toInt()} m\n")
                    sb.append("   KOM ${n.kom} · lunghezza ${n.lengthM} m\n")
                    sb.append("   profilo: ${if (n.curve.isEmpty()) "no (ritmo medio)" else "sì"}")
                }
            }
        }
        output.text = sb.toString()
    }

    private fun loadDescents() {
        val token = getAccessToken()
        val prefs = getSharedPreferences("karoo_discesa", MODE_PRIVATE)
        val cache = try {
            JSONObject(prefs.getString("segcache", "{}") ?: "{}")
        } catch (e: Exception) { JSONObject() }

        setStatus("Scarico l'elenco dei preferiti...")

        val starred = ArrayList<JSONObject>()
        var page = 1
        while (true) {
            val arr = JSONArray(apiGet("/segments/starred?per_page=200&page=$page", token))
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) starred.add(arr.getJSONObject(i))
            setStatus("Preferiti trovati: ${starred.size}...")
            if (arr.length() < 200) break
            page++
            Thread.sleep(300)
        }

        val onlyDescents = ArrayList<JSONObject>()
        for (s in starred) {
            if (s.optDouble("average_grade", 0.0) < 0 && s.optJSONArray("start_latlng") != null) {
                onlyDescents.add(s)
            }
        }

        setStatus("Preferiti: ${starred.size} · in discesa: ${onlyDescents.size}\nRecupero KOM e profili...")

        var done = 0
        var errors = 0
        descents.clear()
        for (seg in onlyDescents) {
            val id = seg.getLong("id")
            val key = id.toString()
            var kom = "n/d"
            var poly = ""
            var curve = ""

            if (cache.has(key)) {
                val c = cache.getJSONObject(key)
                kom = c.optString("kom", "n/d")
                poly = c.optString("poly", "")
                curve = c.optString("curve", "")
            } else {
                try {
                    val detail = JSONObject(apiGet("/segments/$id", token))
                    kom = detail.optJSONObject("xoms")?.optString("kom")?.ifBlank { null } ?: "n/d"
                    poly = detail.optJSONObject("map")?.optString("polyline") ?: ""
                    Thread.sleep(300)

                    // profilo altimetrico (opzionale: se non arriva si usa il ritmo medio)
                    try {
                        val st = JSONObject(
                            apiGet("/segments/$id/streams?keys=distance,altitude&key_by_type=true", token)
                        )
                        curve = buildCurve(
                            st.optJSONObject("distance")?.optJSONArray("data"),
                            st.optJSONObject("altitude")?.optJSONArray("data")
                        )
                        Thread.sleep(300)
                    } catch (e: Exception) {
                        curve = ""
                    }

                    val c = JSONObject()
                    c.put("kom", kom)
                    c.put("poly", poly)
                    c.put("curve", curve)
                    cache.put(key, c)
                } catch (e: Exception) {
                    errors++
                    if (errors >= 3) {
                        prefs.edit().putString("segcache", cache.toString()).apply()
                        setStatus(
                            "Limite API Strava raggiunto.\n" +
                            "Salvate ${descents.size} discese su ${onlyDescents.size}.\n" +
                            "Riapri l'app tra ~15 minuti per completare."
                        )
                        return
                    }
                }
            }

            val start = seg.getJSONArray("start_latlng")
            val end = seg.optJSONArray("end_latlng")
            val sLat = start.getDouble(0)
            val sLng = start.getDouble(1)
            val eLat = if (end != null && end.length() >= 2) end.getDouble(0) else sLat
            val eLng = if (end != null && end.length() >= 2) end.getDouble(1) else sLng

            descents.add(
                Descent(
                    seg.optString("name", "(senza nome)"),
                    sLat, sLng, eLat, eLng, poly, kom,
                    seg.optDouble("distance", 0.0).toInt(),
                    curve
                )
            )
            done++
            if (done % 3 == 0) setStatus("Elaborate $done / ${onlyDescents.size} discese")
        }

        prefs.edit().putString("segcache", cache.toString()).apply()
    }

    /**
     * Dal profilo altimetrico ricava una curva di ritmo:
     * 51 valori = frazione di tempo KOM trascorsa a ogni 2% di distanza.
     */
    private fun buildCurve(distances: JSONArray?, altitudes: JSONArray?): String {
        if (distances == null || altitudes == null) return ""
        val n = Math.min(distances.length(), altitudes.length())
        if (n < 5) return ""

        val d = DoubleArray(n)
        val a = DoubleArray(n)
        for (i in 0 until n) {
            d[i] = distances.optDouble(i, 0.0)
            a[i] = altitudes.optDouble(i, 0.0)
        }

        // lisciatura della quota: media mobile su 5 punti (toglie il rumore GPS)
        val sm = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            var c = 0
            var j = Math.max(0, i - 2)
            val jmax = Math.min(n - 1, i + 2)
            while (j <= jmax) { s += a[j]; c++; j++ }
            sm[i] = s / c
        }

        val cumD = DoubleArray(n)
        val cumT = DoubleArray(n)
        for (i in 1 until n) {
            val dd = d[i] - d[i - 1]
            if (dd <= 0.0) {
                cumD[i] = cumD[i - 1]
                cumT[i] = cumT[i - 1]
                continue
            }
            val g = (sm[i] - sm[i - 1]) / dd
            // velocità stimata (km/h): in discesa ~ radice della pendenza, in salita penalizzata
            var v = if (g < 0.0) {
                Math.sqrt(900.0 + 20000.0 * (-g))
            } else {
                Math.sqrt(900.0 / (1.0 + 15.0 * g))
            }
            if (v < 8.0) v = 8.0
            if (v > 75.0) v = 75.0
            cumD[i] = cumD[i - 1] + dd
            cumT[i] = cumT[i - 1] + dd / (v / 3.6)
        }

        val totD = cumD[n - 1]
        val totT = cumT[n - 1]
        if (totD <= 0.0 || totT <= 0.0) return ""

        val steps = 50
        val sb = StringBuilder()
        var idx = 1
        for (k in 0..steps) {
            val target = totD * k / steps
            while (idx < n - 1 && cumD[idx] < target) idx++
            val d0 = cumD[idx - 1]
            val d1 = cumD[idx]
            val t0 = cumT[idx - 1]
            val t1 = cumT[idx]
            val t = if (d1 > d0) t0 + (t1 - t0) * (target - d0) / (d1 - d0) else t0
            if (k > 0) sb.append(",")
            sb.append(String.format(java.util.Locale.US, "%.4f", t / totT))
        }
        return sb.toString()
    }

    private fun saveDescents() {
        val arr = JSONArray()
        for (d in descents) {
            val o = JSONObject()
            o.put("name", d.name); o.put("lat", d.lat); o.put("lng", d.lng)
            o.put("endLat", d.endLat); o.put("endLng", d.endLng); o.put("poly", d.poly)
            o.put("kom", d.kom); o.put("len", d.lengthM); o.put("curve", d.curve)
            arr.put(o)
        }
        getSharedPreferences("karoo_discesa", MODE_PRIVATE)
            .edit().putString("descents", arr.toString()).apply()
    }

    private fun getAccessToken(): String {
        val conn = (URL("https://www.strava.com/oauth/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val body = "client_id=$clientId&client_secret=$clientSecret&refresh_token=$refreshToken&grant_type=refresh_token"
        conn.outputStream.use { it.write(body.toByteArray()) }
        return JSONObject(readResponse(conn)).getString("access_token")
    }

    private fun apiGet(endpoint: String, token: String): String {
        val conn = (URL("https://www.strava.com/api/v3$endpoint").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Authorization", "Bearer $token")
        }
        return readResponse(conn)
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("HTTP $code")
        return text
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
