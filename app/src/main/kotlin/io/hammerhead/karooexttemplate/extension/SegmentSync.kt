package io.hammerhead.karooexttemplate.extension

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object SegmentSync {

    // ====== LE TUE CHIAVI STRAVA ======
    private const val CLIENT_ID = "190895"
    private const val CLIENT_SECRET = "827974f8301df4438afac3c05be00a4dfd723817"
    private const val REFRESH_TOKEN = "31475ab564010a7acb01268571b6251b2ea05f78"
    // ==================================

    const val SKIPPED = -2
    const val FAILED = -1

    @Volatile private var running = false

    fun lastSyncMs(context: Context): Long =
        context.getSharedPreferences("karoo_discesa", Context.MODE_PRIVATE)
            .getLong("lastSync", 0L)

    /**
     * Salva SEMPRE tutte le discese preferite; KOM e profilo vengono
     * aggiunti man mano, senza mai ridurre la lista.
     */
    fun sync(context: Context, minIntervalMs: Long, progress: (String) -> Unit): Int {
        if (running) return SKIPPED
        val prefs = context.getSharedPreferences("karoo_discesa", Context.MODE_PRIVATE)
        val last = prefs.getLong("lastSync", 0L)
        if (minIntervalMs > 0 && last > 0 && System.currentTimeMillis() - last < minIntervalMs) {
            return SKIPPED
        }
        running = true
        try {
            val token = getAccessToken()

            val cache = try {
                JSONObject(prefs.getString("segcache", "{}") ?: "{}")
            } catch (e: Exception) { JSONObject() }

            // quel che già avevamo salvato, per non perdere nulla
            val prev = HashMap<String, JSONObject>()
            try {
                val a = JSONArray(prefs.getString("descents", "[]") ?: "[]")
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val k = o.optString("id", "")
                    if (k.isNotEmpty()) prev[k] = o
                }
            } catch (e: Exception) { }

            progress("Scarico l'elenco dei preferiti...")

            val starred = ArrayList<JSONObject>()
            var page = 1
            while (true) {
                val arr = JSONArray(apiGet("/segments/starred?per_page=200&page=$page", token))
                if (arr.length() == 0) break
                for (i in 0 until arr.length()) starred.add(arr.getJSONObject(i))
                progress("Preferiti trovati: ${starred.size}...")
                if (arr.length() < 200) break
                page++
                Thread.sleep(200)
            }

            val descents = ArrayList<JSONObject>()
            for (s in starred) {
                if (s.optDouble("average_grade", 0.0) < 0 && s.optJSONArray("start_latlng") != null) {
                    descents.add(s)
                }
            }

            progress("Preferiti: ${starred.size} · in discesa: ${descents.size}")

            val result = JSONArray()
            var errors = 0
            var fetched = 0
            var missing = 0
            var budget = true

            for (seg in descents) {
                val id = seg.getLong("id")
                val key = id.toString()

                var kom = ""
                var poly = ""
                var curve = ""

                // 1) dalla cache
                val c = if (cache.has(key)) cache.getJSONObject(key) else null
                if (c != null) {
                    kom = c.optString("kom", "")
                    poly = c.optString("poly", "")
                    curve = c.optString("curve", "")
                }
                // 2) da quel che avevamo già salvato
                val pv = prev[key]
                if (pv != null) {
                    if (kom.isBlank()) kom = pv.optString("kom", "")
                    if (poly.isBlank()) poly = pv.optString("poly", "")
                    if (curve.isBlank()) curve = pv.optString("curve", "")
                }

                // 3) scarico solo quel che manca, e solo se c'è budget
                if (budget && (kom.isBlank() || poly.isBlank())) {
                    try {
                        val detail = JSONObject(apiGet("/segments/$id", token))
                        val k = detail.optJSONObject("xoms")?.optString("kom") ?: ""
                        if (k.isNotBlank()) kom = k
                        val p = detail.optJSONObject("map")?.optString("polyline") ?: ""
                        if (p.isNotBlank()) poly = p
                        fetched++
                        Thread.sleep(250)
                    } catch (e: Exception) {
                        errors++
                        if (errors >= 3) budget = false
                    }
                }

                if (budget && curve.isBlank() && poly.isNotBlank()) {
                    try {
                        val st = JSONObject(
                            apiGet("/segments/$id/streams?keys=distance,altitude&key_by_type=true", token)
                        )
                        curve = buildCurve(
                            st.optJSONObject("distance")?.optJSONArray("data"),
                            st.optJSONObject("altitude")?.optJSONArray("data")
                        )
                        fetched++
                        Thread.sleep(250)
                    } catch (e: Exception) {
                        errors++
                        if (errors >= 3) budget = false
                    }
                }

                // aggiorno la cache con quel che ho
                if (kom.isNotBlank() || poly.isNotBlank() || curve.isNotBlank()) {
                    val nc = JSONObject()
                    nc.put("kom", kom)
                    nc.put("poly", poly)
                    nc.put("curve", curve)
                    cache.put(key, nc)
                }
                if (kom.isBlank() || curve.isBlank()) missing++

                // il segmento entra SEMPRE nella lista
                val start = seg.getJSONArray("start_latlng")
                val end = seg.optJSONArray("end_latlng")
                val sLat = start.getDouble(0)
                val sLng = start.getDouble(1)
                val eLat = if (end != null && end.length() >= 2) end.getDouble(0) else sLat
                val eLng = if (end != null && end.length() >= 2) end.getDouble(1) else sLng

                val o = JSONObject()
                o.put("id", key)
                o.put("name", seg.optString("name", "(senza nome)"))
                o.put("lat", sLat); o.put("lng", sLng)
                o.put("endLat", eLat); o.put("endLng", eLng)
                o.put("poly", poly)
                o.put("kom", if (kom.isBlank()) "n/d" else kom)
                o.put("len", seg.optDouble("distance", 0.0).toInt())
                o.put("curve", curve)
                result.put(o)

                if (result.length() % 5 == 0) {
                    progress("Discese: ${result.length()} / ${descents.size}" +
                            if (!budget) "\n(limite Strava: completo più tardi)" else "")
                }
            }

            val ed = prefs.edit()
            ed.putString("segcache", cache.toString())
            ed.putString("descents", result.toString())
            // considero completa la sincronizzazione solo se non manca nulla
            if (budget && missing == 0) ed.putLong("lastSync", System.currentTimeMillis())
            else ed.putLong("lastSync", 0L)
            ed.apply()

            if (!budget) {
                progress("Limite Strava raggiunto.\n${result.length()} discese salvate, " +
                        "$missing da completare: riapri tra ~15 minuti")
            }
            return result.length()
        } catch (e: Exception) {
            progress("Errore: ${e.message}")
            return FAILED
        } finally {
            running = false
        }
    }

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
            if (dd <= 0.0) { cumD[i] = cumD[i - 1]; cumT[i] = cumT[i - 1]; continue }
            val g = (sm[i] - sm[i - 1]) / dd
            var v = if (g < 0.0) Math.sqrt(900.0 + 20000.0 * (-g))
            else Math.sqrt(900.0 / (1.0 + 15.0 * g))
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
            val d0 = cumD[idx - 1]; val d1 = cumD[idx]
            val t0 = cumT[idx - 1]; val t1 = cumT[idx]
            val t = if (d1 > d0) t0 + (t1 - t0) * (target - d0) / (d1 - d0) else t0
            if (k > 0) sb.append(",")
            sb.append(String.format(java.util.Locale.US, "%.4f", t / totT))
        }
        return sb.toString()
    }

    private fun getAccessToken(): String {
        val conn = (URL("https://www.strava.com/oauth/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val body = "client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&refresh_token=$REFRESH_TOKEN&grant_type=refresh_token"
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
}
