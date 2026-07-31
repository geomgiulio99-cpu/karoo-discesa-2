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
     * Scarica i preferiti in discesa con KOM e profilo altimetrico.
     * @return numero di discese salvate, oppure FAILED / SKIPPED
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
                Thread.sleep(300)
            }

            val onlyDescents = ArrayList<JSONObject>()
            for (s in starred) {
                if (s.optDouble("average_grade", 0.0) < 0 && s.optJSONArray("start_latlng") != null) {
                    onlyDescents.add(s)
                }
            }

            progress("Preferiti: ${starred.size} · in discesa: ${onlyDescents.size}")

            val result = JSONArray()
            var done = 0
            var errors = 0
            var partial = false

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
                        try {
                            val st = JSONObject(
                                apiGet("/segments/$id/streams?keys=distance,altitude&key_by_type=true", token)
                            )
                            curve = buildCurve(
                                st.optJSONObject("distance")?.optJSONArray("data"),
                                st.optJSONObject("altitude")?.optJSONArray("data")
                            )
                            Thread.sleep(300)
                        } catch (e: Exception) { curve = "" }

                        val c = JSONObject()
                        c.put("kom", kom); c.put("poly", poly); c.put("curve", curve)
                        cache.put(key, c)
                    } catch (e: Exception) {
                        errors++
                        if (errors >= 3) { partial = true; break }
                        continue
                    }
                }

                val start = seg.getJSONArray("start_latlng")
                val end = seg.optJSONArray("end_latlng")
                val sLat = start.getDouble(0)
                val sLng = start.getDouble(1)
                val eLat = if (end != null && end.length() >= 2) end.getDouble(0) else sLat
                val eLng = if (end != null && end.length() >= 2) end.getDouble(1) else sLng

                val o = JSONObject()
                o.put("name", seg.optString("name", "(senza nome)"))
                o.put("lat", sLat); o.put("lng", sLng)
                o.put("endLat", eLat); o.put("endLng", eLng)
                o.put("poly", poly); o.put("kom", kom)
                o.put("len", seg.optDouble("distance", 0.0).toInt())
                o.put("curve", curve)
                result.put(o)

                done++
                if (done % 3 == 0) progress("Elaborate $done / ${onlyDescents.size} discese")
            }

            val ed = prefs.edit()
            ed.putString("segcache", cache.toString())
            if (result.length() > 0) ed.putString("descents", result.toString())
            if (!partial) ed.putLong("lastSync", System.currentTimeMillis())
            ed.apply()

            if (partial) progress("Limite API Strava: salvate $done discese, riprova tra ~15 min")
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
