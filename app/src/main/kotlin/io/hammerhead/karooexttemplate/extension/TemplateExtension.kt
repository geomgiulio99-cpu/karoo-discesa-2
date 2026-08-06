package io.hammerhead.karooexttemplate.extension

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.MarkLap
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.ShowPolyline
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.SystemNotification
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import io.hammerhead.karooexttemplate.R
import org.json.JSONArray

data class Descent(
    val name: String,
    val lat: Double,
    val lng: Double,
    val endLat: Double,
    val endLng: Double,
    val poly: String,
    val komSec: Double,
    val lengthM: Double,
    val curve: DoubleArray
)

fun parseKom(s: String): Double {
    val parts = s.trim().split(":")
    return try {
        when (parts.size) {
            3 -> parts[0].toDouble() * 3600 + parts[1].toDouble() * 60 + parts[2].toDouble()
            2 -> parts[0].toDouble() * 60 + parts[1].toDouble()
            1 -> parts[0].toDouble()
            else -> 0.0
        }
    } catch (e: Exception) { 0.0 }
}

fun komAvgKmh(d: Descent): Double {
    if (d.komSec <= 0.0 || d.lengthM <= 0.0) return 0.0
    return d.lengthM / d.komSec * 3.6
}

fun expectedFrac(curve: DoubleArray, distFrac: Double): Double {
    if (curve.size < 2) return distFrac
    var f = distFrac
    if (f < 0.0) f = 0.0
    if (f > 1.0) f = 1.0
    val x = f * (curve.size - 1)
    var i = Math.floor(x).toInt()
    if (i > curve.size - 2) i = curve.size - 2
    return curve[i] + (curve[i + 1] - curve[i]) * (x - i)
}

fun fmtDelta(sec: Double): String {
    val r = Math.round(sec).toInt()
    return if (r > 0) "+$r" else r.toString()
}

fun fmtKm(meters: Double): String {
    var m = meters
    if (m < 0.0) m = 0.0
    return if (m < 1000.0) String.format(java.util.Locale.US, "%.2f", m / 1000.0)
    else String.format(java.util.Locale.US, "%.1f", m / 1000.0)
}

fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

fun projSeg(
    plat: Double, plng: Double,
    alat: Double, alng: Double,
    blat: Double, blng: Double
): DoubleArray {
    val kx = Math.cos(Math.toRadians(alat)) * 111320.0
    val ky = 110540.0
    val bx = (blng - alng) * kx
    val by = (blat - alat) * ky
    val px = (plng - alng) * kx
    val py = (plat - alat) * ky
    val len2 = bx * bx + by * by
    var t = if (len2 <= 0.0) 0.0 else (px * bx + py * by) / len2
    if (t < 0.0) t = 0.0
    if (t > 1.0) t = 1.0
    val ddx = px - bx * t
    val ddy = py - by * t
    return doubleArrayOf(Math.sqrt(ddx * ddx + ddy * ddy), t)
}

fun decodePolyline(encoded: String): List<DoubleArray> {
    val poly = ArrayList<DoubleArray>()
    try {
        var index = 0
        var lat = 0
        var lng = 0
        while (index < encoded.length) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)
            poly.add(doubleArrayOf(lat / 1e5, lng / 1e5))
        }
    } catch (e: Exception) { }
    return poly
}

fun readDescents(context: Context): List<Descent> {
    val prefs = context.getSharedPreferences("karoo_discesa", Context.MODE_PRIVATE)
    val raw = prefs.getString("descents", null) ?: return emptyList()
    val out = ArrayList<Descent>()
    try {
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val la = o.getDouble("lat")
            val ln = o.getDouble("lng")
            val cs = o.optString("curve", "")
            val parts = if (cs.isEmpty()) emptyList() else cs.split(",")
            val curve = if (parts.size < 2) DoubleArray(0) else {
                val c = DoubleArray(parts.size)
                var ok = true
                for (k in parts.indices) {
                    val v = parts[k].toDoubleOrNull()
                    if (v == null) { ok = false; break }
                    c[k] = v
                }
                if (ok) c else DoubleArray(0)
            }
            out.add(
                Descent(
                    o.optString("name", "?"),
                    la, ln,
                    o.optDouble("endLat", la),
                    o.optDouble("endLng", ln),
                    o.optString("poly", ""),
                    parseKom(o.optString("kom", "0")),
                    o.optDouble("len", 0.0),
                    curve
                )
            )
        }
    } catch (e: Exception) { }
    return out
}

class DescentTracker(private val ext: TemplateExtension) {

    companion object {
        const val JOIN_OFF = 40.0
        const val JOIN_FRAC = 0.25
        const val APPROACH_RADIUS = 300.0
        const val MAX_JUMP = 80.0
    }

    @Volatile var descents: List<Descent> = emptyList()
    @Volatile var active = false
    @Volatile var holding = false
    @Volatile var delta = 0.0
    @Volatile var deltaText = "--"
    @Volatile var ahead = false
    @Volatile var komAvgText = "--"
    @Volatile var myAvgText = "--"
    @Volatile var remainingText = "--"
    @Volatile var nearestDist = -1.0
    @Volatile var lapAvgKmh = -1.0
    @Volatile var simulating = false
    @Volatile var armedName: String? = null

    private val cooldowns = HashMap<String, Long>()
    private val announced = HashMap<String, Long>()
    private val polyCache = HashMap<String, List<DoubleArray>>()
    private var lastSimSeen = 0L
    private var lastArmSeen = 0L
    private var cur: Descent? = null
    private var pts: List<DoubleArray> = emptyList()
    private var cum: DoubleArray = DoubleArray(0)
    private var polyLen = 0.0
    private var alongMax = 0.0
    private var joinFrac = 0.0
    private var startMs = 0L
    private var offTrack = 0
    private var lastProgressMs = 0L
    private var holdUntil = 0L
    private var smoothInit = false
    private var smoothVal = 0.0
    private var traveled = 0.0
    private var prevLat = 0.0
    private var prevLng = 0.0
    private var lastLat = 0.0
    private var lastLng = 0.0
    private var haveLast = false
    private var locConsumer: String? = null
    private var lapConsumer: String? = null
    private var navConsumer: String? = null

    fun reload(context: Context) {
        try {
            descents = readDescents(context)
            polyCache.clear()
        } catch (e: Exception) { }
    }

    private fun ptsOf(d: Descent): List<DoubleArray> {
        val c = polyCache[d.name]
        if (c != null) return c
        val p = decodePolyline(d.poly)
        polyCache[d.name] = p
        return p
    }

    /** Il rider ha scelto "Vai a" su un nostro marcatore: arma quel segmento. */
    fun onPoiChosen(poi: Symbol.POI) {
        val id = poi.id
        if (!id.startsWith("disc-")) return
        val list = descents
        if (list.isEmpty()) return
        val nm = poi.name ?: ""
        var found: Descent? = null
        val idx = id.substringAfterLast("-").toIntOrNull()
        if (idx != null && idx in list.indices && nm.contains(list[idx].name)) found = list[idx]
        if (found == null) for (d in list) if (nm.isNotEmpty() && nm.contains(d.name)) { found = d; break }
        val d = found ?: return
        armedName = d.name
        cooldowns.remove(d.name)
        announced.remove(d.name)
        ext.setArmed(d.name)
        ext.beepApproach()
        ext.notifyUser("Segmento armato", d.name)
    }

    fun start(context: Context) {
        if (locConsumer == null) {
            locConsumer = ext.karooSystem.addConsumer { loc: OnLocationChanged ->
                try { onLoc(loc.lat, loc.lng) } catch (e: Exception) { }
            }
        }
        if (lapConsumer == null) {
            lapConsumer = try {
                ext.karooSystem.addConsumer<OnStreamState>(
                    OnStreamState.StartStreaming(DataType.Type.AVERAGE_SPEED_LAP)
                ) { ev: OnStreamState ->
                    val st = ev.state
                    if (st is StreamState.Streaming) {
                        val v = st.dataPoint.singleValue
                        if (v != null && v >= 0.0) {
                            lapAvgKmh = if (v < 30.0) v * 3.6 else v
                        }
                    }
                }
            } catch (e: Exception) { null }
        }
        if (navConsumer == null) {
            navConsumer = try {
                ext.karooSystem.addConsumer { ev: OnNavigationState ->
                    val st = ev.state
                    if (st is OnNavigationState.NavigationState.NavigatingToDestination) {
                        try { onPoiChosen(st.destination) } catch (e: Exception) { }
                    }
                }
            } catch (e: Exception) { null }
        }
        Thread {
            while (true) {
                try {
                    if (!active && !simulating) reload(context)
                    val p = context.getSharedPreferences("karoo_discesa", Context.MODE_PRIVATE)

                    val s = p.getLong("simStart", 0L)
                    if (s > 0 && s != lastSimSeen && System.currentTimeMillis() - s < 300000) {
                        lastSimSeen = s
                        val idx = p.getInt("simIdx", 0)
                        val list = descents
                        if (idx in list.indices) {
                            val d = list[idx]
                            Thread { runSim(d) }.start()
                        }
                    }

                    val a = p.getLong("armedAt", 0L)
                    if (a > 0 && a != lastArmSeen) {
                        lastArmSeen = a
                        armedName = p.getString("armedName", null)
                        armedName?.let { cooldowns.remove(it) }
                    }

                    Thread.sleep(1000)
                } catch (e: Exception) { return@Thread }
            }
        }.start()
    }

    fun stop() {
        locConsumer?.let { try { ext.karooSystem.removeConsumer(it) } catch (e: Exception) { } }
        lapConsumer?.let { try { ext.karooSystem.removeConsumer(it) } catch (e: Exception) { } }
        navConsumer?.let { try { ext.karooSystem.removeConsumer(it) } catch (e: Exception) { } }
        locConsumer = null; lapConsumer = null; navConsumer = null
    }

    private fun runSim(d: Descent) {
        if (d.lengthM <= 0) return
        val kom = if (d.komSec > 0) d.komSec else d.lengthM / 11.0
        simulating = true
        active = true
        holding = false
        val len = d.lengthM
        komAvgText = if (d.komSec > 0) "%.1f".format(komAvgKmh(d)) else "--"
        myAvgText = "0.0"
        deltaText = "0"
        remainingText = fmtKm(len)
        ahead = false
        var sv = 0.0
        var init = false
        ext.beepStart()
        ext.markLap()

        val speed = len / kom * 0.93
        var vt = 0.0
        while (vt < kom * 1.4) {
            vt += 5.0
            val along = Math.min(len, speed * vt)
            val frac = along / len
            val raw = vt - kom * expectedFrac(d.curve, frac)
            if (!init) { sv = raw; init = true } else sv += (raw - sv) * 0.15
            delta = sv
            deltaText = fmtDelta(sv)
            ahead = sv < 0
            myAvgText = "%.1f".format(along / vt * 3.6)
            remainingText = fmtKm(len - along)
            if (frac >= 0.999) break
            try { Thread.sleep(500) } catch (e: Exception) { break }
        }

        val fin = vt - kom
        delta = fin
        deltaText = fmtDelta(fin)
        ahead = fin < 0
        remainingText = "0.00"
        active = false
        holding = true
        ext.beepEnd()
        ext.markLap()
        try { Thread.sleep(15000) } catch (e: Exception) { }
        holding = false
        simulating = false
        deltaText = "--"; komAvgText = "--"; myAvgText = "--"; remainingText = "--"
    }

    private fun idxAt(dist: Double): Int {
        if (cum.isEmpty()) return 0
        var lo = 0
        var hi = cum.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cum[mid] < dist) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun resetTexts() {
        deltaText = "--"; komAvgText = "--"; myAvgText = "--"; remainingText = "--"
        ahead = false; delta = 0.0
    }

    /** [distanza dal tracciato, frazione lungo il tracciato, lunghezza totale] o null */
    private fun onTrack(d: Descent, lat: Double, lng: Double): DoubleArray? {
        val p = ptsOf(d)
        if (p.size < 2) {
            val ds = haversine(lat, lng, d.lat, d.lng)
            return if (ds <= JOIN_OFF) doubleArrayOf(ds, 0.0, d.lengthM) else null
        }
        var total = 0.0
        val c = DoubleArray(p.size)
        for (i in 1 until p.size) {
            total += haversine(p[i - 1][0], p[i - 1][1], p[i][0], p[i][1])
            c[i] = total
        }
        if (total <= 0.0) return null
        val limit = total * JOIN_FRAC
        var bo = Double.MAX_VALUE
        var ba = 0.0
        var i = 0
        while (i < p.size - 1 && c[i] <= limit) {
            val r = projSeg(lat, lng, p[i][0], p[i][1], p[i + 1][0], p[i + 1][1])
            if (r[0] < bo) { bo = r[0]; ba = c[i] + (c[i + 1] - c[i]) * r[1] }
            i++
        }
        if (bo > JOIN_OFF) return null
        return doubleArrayOf(bo, ba / total, total)
    }

    private fun checkApproach(list: List<Descent>, lat: Double, lng: Double, now: Long) {
        if (!haveLast) return
        for (d in list) {
            if (d.lengthM <= 0.0) continue
            val dd = haversine(lat, lng, d.lat, d.lng)
            if (dd > APPROACH_RADIUS || dd < 50.0) continue
            if (dd >= haversine(lastLat, lastLng, d.lat, d.lng)) continue
            if (dd > haversine(lat, lng, d.endLat, d.endLng)) continue
            val last = announced[d.name] ?: 0L
            if (now - last < 300000L) continue
            announced[d.name] = now
            ext.beepApproach()
            val km = komAvgKmh(d)
            ext.notifyUser(
                "Discesa in arrivo",
                if (km > 0) "${d.name} · KOM ${"%.1f".format(km)} km/h" else d.name
            )
            return
        }
    }

    private fun onLoc(lat: Double, lng: Double) {
        if (simulating) return
        val list = descents
        if (list.isEmpty()) return
        val now = System.currentTimeMillis()

        var best = -1.0
        for (d in list) {
            val dd = haversine(lat, lng, d.lat, d.lng)
            if (best < 0 || dd < best) best = dd
        }
        nearestDist = best

        val c = cur
        if (c == null) {
            checkApproach(list, lat, lng, now)

            var chosen: Descent? = null
            var chosenInfo: DoubleArray? = null

            val arm = armedName
            if (arm != null) {
                for (d in list) {
                    if (d.name != arm) continue
                    val info = onTrack(d, lat, lng)
                    if (info != null) { chosen = d; chosenInfo = info }
                    break
                }
            }

            if (chosen == null) {
                var bestFrac = Double.MAX_VALUE
                var bestOff = Double.MAX_VALUE
                for (d in list) {
                    if (d.lengthM <= 0.0) continue
                    val cd = cooldowns[d.name]
                    if (cd != null && now < cd) continue
                    val ds = haversine(lat, lng, d.lat, d.lng)
                    if (ds > 600.0) continue
                    if (haversine(lat, lng, d.endLat, d.endLng) < ds) continue
                    val info = onTrack(d, lat, lng) ?: continue
                    if (info[1] < bestFrac - 0.02 ||
                        (Math.abs(info[1] - bestFrac) <= 0.02 && info[0] < bestOff)) {
                        bestFrac = info[1]; bestOff = info[0]
                        chosen = d; chosenInfo = info
                    }
                }
            }

            if (chosen != null && chosenInfo != null) {
                if (chosen.name == armedName) {
                    armedName = null
                    ext.clearArmed()
                }
                begin(chosen, chosenInfo[1], chosenInfo[2], lat, lng)
            } else if (now < holdUntil) {
                holding = true
            } else if (holding) {
                holding = false
                resetTexts()
            }
            lastLat = lat; lastLng = lng; haveLast = true
            return
        }

        var off: Double
        var along: Double
        if (pts.size < 2) {
            traveled += haversine(prevLat, prevLng, lat, lng)
            along = traveled
            off = 0.0
        } else {
            val lo = Math.max(0, idxAt(alongMax - 150.0) - 1)
            val hi = Math.min(pts.size - 2, idxAt(alongMax + 200.0))
            var bo = Double.MAX_VALUE
            var ba = alongMax
            var i = lo
            while (i <= hi) {
                val r = projSeg(lat, lng, pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1])
                if (r[0] < bo) {
                    bo = r[0]
                    ba = cum[i] + (cum[i + 1] - cum[i]) * r[1]
                }
                i++
            }
            off = bo
            along = ba
        }
        prevLat = lat; prevLng = lng
        lastLat = lat; lastLng = lng; haveLast = true

        if (along > alongMax + MAX_JUMP) along = alongMax
        if (along > alongMax) {
            if (along > alongMax + 2.0) lastProgressMs = now
            alongMax = along
        }

        if (off > 150.0) offTrack++ else offTrack = 0

        val stalled = now - lastProgressMs
        if (offTrack >= 15 && stalled > 20000 && now - startMs > 20000) { abort(); return }

        val elapsed = (now - startMs) / 1000.0
        var frac = if (polyLen > 0) alongMax / polyLen else 0.0
        if (frac < 0.0) frac = 0.0
        if (frac > 1.0) frac = 1.0

        if (c.komSec > 0.0) {
            val target = c.komSec * (expectedFrac(c.curve, frac) - expectedFrac(c.curve, joinFrac))
            val raw = elapsed - target
            if (!smoothInit) { smoothVal = raw; smoothInit = true }
            else smoothVal += (raw - smoothVal) * 0.15
            delta = smoothVal
            deltaText = fmtDelta(smoothVal)
            ahead = smoothVal < 0
            komAvgText = "%.1f".format(komAvgKmh(c))
        } else {
            delta = elapsed
            deltaText = "%.0f".format(elapsed)
            ahead = false
            komAvgText = "--"
        }
        myAvgText = if (elapsed > 1.0)
            "%.1f".format((alongMax - joinFrac * polyLen) / elapsed * 3.6) else "0.0"
        remainingText = fmtKm(polyLen - alongMax)

        if (elapsed < 5.0) return
        val toEnd = haversine(lat, lng, c.endLat, c.endLng)
        if (frac >= 0.985 || (frac > 0.9 && toEnd < 50.0)) finish(elapsed)
    }

    private fun begin(d: Descent, jFrac: Double, totalLen: Double, lat: Double, lng: Double) {
        cur = d
        pts = ptsOf(d)
        cum = DoubleArray(if (pts.isEmpty()) 1 else pts.size)
        polyLen = 0.0
        for (i in 1 until pts.size) {
            polyLen += haversine(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1])
            cum[i] = polyLen
        }
        if (polyLen <= 0.0) polyLen = if (totalLen > 0) totalLen else d.lengthM
        joinFrac = jFrac
        alongMax = joinFrac * polyLen
        traveled = alongMax
        startMs = System.currentTimeMillis()
        lastProgressMs = startMs
        offTrack = 0
        smoothInit = false
        prevLat = lat
        prevLng = lng
        active = true
        holding = false
        delta = 0.0
        deltaText = "0"
        ahead = false
        komAvgText = if (d.komSec > 0) "%.1f".format(komAvgKmh(d)) else "--"
        myAvgText = "0.0"
        remainingText = fmtKm(polyLen - alongMax)
        ext.beepStart()
        ext.markLap()
    }

    private fun finish(elapsed: Double) {
        val c = cur ?: return
        val fin = if (c.komSec > 0)
            elapsed - c.komSec * (1.0 - expectedFrac(c.curve, joinFrac))
        else elapsed
        delta = fin
        deltaText = if (c.komSec > 0) fmtDelta(fin) else "%.0f".format(fin)
        ahead = c.komSec > 0 && fin < 0
        remainingText = "0.00"
        holdUntil = System.currentTimeMillis() + 15000L
        holding = true
        active = false
        cooldowns[c.name] = System.currentTimeMillis() + 90000L
        cur = null
        pts = emptyList()
        ext.beepEnd()
        ext.markLap()
    }

    private fun abort() {
        val c = cur
        if (c != null) cooldowns[c.name] = System.currentTimeMillis() + 15000L
        cur = null
        pts = emptyList()
        active = false
        holding = false
        offTrack = 0
        resetTexts()
    }
}

class TemplateExtension : KarooExtension("template-id", "1.0") {

    lateinit var karooSystem: KarooSystemService
    val tracker: DescentTracker by lazy { DescentTracker(this) }

    override val types by lazy {
        listOf(
            DescentDistanceType(this, extension),
            DescentDeltaType(this, extension)
        )
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        tracker.reload(applicationContext)
        karooSystem.connect {
            tracker.start(applicationContext)
            syncAtBoot()
        }
    }

    fun setArmed(name: String) {
        try {
            applicationContext
                .getSharedPreferences("karoo_discesa", Context.MODE_PRIVATE)
                .edit().putString("armedName", name).apply()
        } catch (e: Exception) { }
    }

    fun clearArmed() {
        try {
            applicationContext
                .getSharedPreferences("karoo_discesa", Context.MODE_PRIVATE)
                .edit().remove("armedName").apply()
        } catch (e: Exception) { }
    }

    private fun syncAtBoot() {
        Thread {
            var attempt = 0
            while (attempt < 5) {
                attempt++
                val n = SegmentSync.sync(applicationContext, 30 * 60 * 1000L) { }
                if (n >= 0) {
                    tracker.reload(applicationContext)
                    notifyUser("Discese KOM", "$n segmenti in discesa pronti")
                    return@Thread
                }
                if (n == SegmentSync.SKIPPED) return@Thread
                try { Thread.sleep(180000) } catch (e: Exception) { return@Thread }
            }
            notifyUser("Discese KOM", "Sincronizzazione non riuscita: apri l'app con la rete attiva")
        }.start()
    }

    fun notifyUser(header: String, msg: String) {
        try {
            karooSystem.dispatch(
                SystemNotification(
                    id = "discese-${System.currentTimeMillis()}",
                    message = msg,
                    header = header,
                    style = SystemNotification.Style.EVENT
                )
            )
        } catch (e: Exception) { }
    }

    fun markLap() {
        try { karooSystem.dispatch(MarkLap) } catch (e: Exception) { }
    }

    fun beepApproach() {
        try {
            karooSystem.dispatch(
                PlayBeepPattern(
                    listOf(
                        PlayBeepPattern.Tone(1200, 90),
                        PlayBeepPattern.Tone(1200, 90)
                    )
                )
            )
        } catch (e: Exception) { }
    }

    fun beepStart() {
        try {
            karooSystem.dispatch(
                PlayBeepPattern(
                    listOf(
                        PlayBeepPattern.Tone(900, 120),
                        PlayBeepPattern.Tone(1400, 220)
                    )
                )
            )
        } catch (e: Exception) { }
    }

    fun beepEnd() {
        try {
            karooSystem.dispatch(
                PlayBeepPattern(
                    listOf(
                        PlayBeepPattern.Tone(1400, 130),
                        PlayBeepPattern.Tone(1100, 130),
                        PlayBeepPattern.Tone(800, 320)
                    )
                )
            )
        } catch (e: Exception) { }
    }

    override fun startMap(emitter: Emitter<MapEffect>) {
        val descents = readDescents(applicationContext)
        val symbols = ArrayList<Symbol>()
        for (i in descents.indices) {
            val d = descents[i]
            if (d.poly.isNotEmpty()) {
                emitter.onNext(ShowPolyline("discesa-$i", d.poly, 0xFFFF6600.toInt(), 8))
            }
            val kmh = komAvgKmh(d)
            val tag = if (kmh > 0) "KOM ${"%.1f".format(kmh)} km/h · " else ""
            symbols.add(
                Symbol.POI("disc-start-$i", d.lat, d.lng, Symbol.POI.Types.SUMMIT,
                    "${tag}INIZIO ${d.name}")
            )
            symbols.add(
                Symbol.POI("disc-end-$i", d.endLat, d.endLng, Symbol.POI.Types.CONTROL,
                    "${tag}FINE ${d.name}")
            )
        }
        if (symbols.isNotEmpty()) emitter.onNext(ShowSymbols(symbols))
    }

    override fun onDestroy() {
        try { tracker.stop() } catch (e: Exception) { }
        try { karooSystem.disconnect() } catch (e: Exception) { }
        super.onDestroy()
    }
}

class DescentDistanceType(
    private val ext: TemplateExtension,
    extension: String
) : DataTypeImpl(extension, "descent-distance") {

    override fun startStream(emitter: Emitter<StreamState>) {
        var run = true
        Thread {
            while (run) {
                try {
                    val t = ext.tracker
                    if (t.descents.isEmpty()) {
                        t.reload(ext.applicationContext)
                        emitter.onNext(StreamState.NotAvailable)
                    } else if (t.nearestDist >= 0) {
                        emitter.onNext(
                            StreamState.Streaming(
                                DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to t.nearestDist))
                            )
                        )
                    } else {
                        emitter.onNext(StreamState.Searching)
                    }
                } catch (e: Exception) { }
                try { Thread.sleep(1000) } catch (e: Exception) { }
            }
        }.start()
        emitter.setCancellable { run = false }
    }
}

class DescentDeltaType(
    private val ext: TemplateExtension,
    extension: String
) : DataTypeImpl(extension, "descent-delta") {

    override fun startStream(emitter: Emitter<StreamState>) {
        var run = true
        Thread {
            while (run) {
                try {
                    val t = ext.tracker
                    val v = if (t.active || t.holding) t.delta
                    else if (t.lapAvgKmh >= 0) t.lapAvgKmh else 0.0
                    emitter.onNext(
                        StreamState.Streaming(
                            DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to v))
                        )
                    )
                } catch (e: Exception) { }
                try { Thread.sleep(1000) } catch (e: Exception) { }
            }
        }.start()
        emitter.setCancellable { run = false }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        var run = true
        Thread {
            while (run) {
                try {
                    val t = ext.tracker
                    val rv = RemoteViews(context.packageName, R.layout.field_delta)
                    if (t.active || t.holding) {
                        rv.setViewVisibility(R.id.field_left, View.VISIBLE)
                        rv.setTextViewText(R.id.field_kom_avg, "KOM ${t.komAvgText}")
                        rv.setTextViewText(R.id.field_my_avg, "Io ${t.myAvgText}")
                        rv.setTextViewText(R.id.field_delta_value, t.deltaText)
                        rv.setTextViewText(R.id.field_remaining, "${t.remainingText} km")
                        val color = when {
                            t.deltaText == "--" || t.komAvgText == "--" -> 0xFFFFFFFF.toInt()
                            t.ahead -> 0xFF33CC33.toInt()
                            else -> 0xFFFF4444.toInt()
                        }
                        rv.setTextColor(R.id.field_delta_value, color)
                    } else {
                        rv.setViewVisibility(R.id.field_left, View.GONE)
                        val lap = t.lapAvgKmh
                        rv.setTextViewText(
                            R.id.field_delta_value,
                            if (lap >= 0) "%.1f".format(lap) else "--"
                        )
                        rv.setTextColor(R.id.field_delta_value, 0xFFFFFFFF.toInt())
                        rv.setTextViewText(R.id.field_remaining, "km/h giro")
                    }
                    emitter.updateView(rv)
                } catch (e: Exception) { }
                try { Thread.sleep(500) } catch (e: Exception) { }
            }
        }.start()
        emitter.setCancellable { run = false }
    }
}
