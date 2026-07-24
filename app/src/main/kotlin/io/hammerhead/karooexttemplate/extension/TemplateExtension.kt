package io.hammerhead.karooexttemplate.extension

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.ShowPolyline
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import org.json.JSONArray

data class Descent(
    val name: String,
    val lat: Double,
    val lng: Double,
    val endLat: Double,
    val endLng: Double,
    val poly: String,
    val komSec: Double,
    val lengthM: Double
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

fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
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
            out.add(
                Descent(
                    o.optString("name", "?"),
                    la, ln,
                    o.optDouble("endLat", la),
                    o.optDouble("endLng", ln),
                    o.optString("poly", ""),
                    parseKom(o.optString("kom", "0")),
                    o.optDouble("len", 0.0)
                )
            )
        }
    } catch (e: Exception) { }
    return out
}

class TemplateExtension : KarooExtension("template-id", "1.0") {

    lateinit var karooSystem: KarooSystemService

    override val types by lazy {
        listOf(
            DescentDistanceType(this, extension),
            DescentDeltaType(this, extension)
        )
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { }
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
            symbols.add(Symbol.POI("disc-start-$i", d.lat, d.lng, Symbol.POI.Types.SUMMIT, "INIZIO ${d.name}"))
            symbols.add(Symbol.POI("disc-end-$i", d.endLat, d.endLng, Symbol.POI.Types.CONTROL, "FINE ${d.name}"))
        }
        if (symbols.isNotEmpty()) emitter.onNext(ShowSymbols(symbols))
    }

    override fun onDestroy() {
        try { karooSystem.disconnect() } catch (e: Exception) { }
        super.onDestroy()
    }
}

class DescentDistanceType(
    private val ext: TemplateExtension,
    extension: String
) : DataTypeImpl(extension, "descent-distance") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val descents = readDescents(ext.applicationContext)
        if (descents.isEmpty()) {
            emitter.onNext(StreamState.NotAvailable)
            return
        }
        emitter.onNext(StreamState.Searching)

        val consumerId = ext.karooSystem.addConsumer { loc: OnLocationChanged ->
            var best = -1.0
            for (d in descents) {
                val dist = haversine(loc.lat, loc.lng, d.lat, d.lng)
                if (best < 0 || dist < best) best = dist
            }
            if (best >= 0) {
                emitter.onNext(
                    StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to best)))
                )
            }
        }
        emitter.setCancellable {
            try { ext.karooSystem.removeConsumer(consumerId) } catch (e: Exception) { }
        }
    }
}

class DescentDeltaType(
    private val ext: TemplateExtension,
    extension: String
) : DataTypeImpl(extension, "descent-delta") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val descents = readDescents(ext.applicationContext)
        if (descents.isEmpty()) {
            emitter.onNext(StreamState.NotAvailable)
            return
        }
        emitter.onNext(StreamState.Searching)

        var active: Descent? = null
        var activePoints: List<DoubleArray> = emptyList()
        var startMs = 0L
        var traveled = 0.0
        var lastLat = 0.0
        var lastLng = 0.0
        var holdUntilMs = 0L
        var holdValue = 0.0

        val consumerId = ext.karooSystem.addConsumer { loc: OnLocationChanged ->
            val now = System.currentTimeMillis()
            val a = active

            if (a == null) {
                if (now < holdUntilMs) {
                    emitter.onNext(
                        StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to holdValue)))
                    )
                } else {
                    var near: Descent? = null
                    var best = -1.0
                    for (d in descents) {
                        val dist = haversine(loc.lat, loc.lng, d.lat, d.lng)
                        if (best < 0 || dist < best) { best = dist; near = d }
                    }
                    val n = near
                    if (n != null && best in 0.0..30.0 && n.komSec > 0 && n.lengthM > 0) {
                        active = n
                        activePoints = decodePolyline(n.poly)
                        startMs = now
                        traveled = 0.0
                        lastLat = loc.lat
                        lastLng = loc.lng
                        ext.beepStart()
                        emitter.onNext(
                            StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to 0.0)))
                        )
                    } else {
                        emitter.onNext(StreamState.Searching)
                    }
                }
            } else {
                traveled += haversine(lastLat, lastLng, loc.lat, loc.lng)
                lastLat = loc.lat
                lastLng = loc.lng

                if (activePoints.isNotEmpty()) {
                    var minDist = -1.0
                    for (p in activePoints) {
                        val dist = haversine(loc.lat, loc.lng, p[0], p[1])
                        if (minDist < 0 || dist < minDist) minDist = dist
                        if (minDist <= 100.0) break
                    }
                    if (minDist > 100.0) {
                        active = null
                        activePoints = emptyList()
                        emitter.onNext(StreamState.Searching)
                        return@addConsumer
                    }
                }

                val elapsed = (now - startMs) / 1000.0
                var frac = traveled / a.lengthM
                if (frac < 0.0) frac = 0.0
                if (frac > 1.0) frac = 1.0
                val delta = elapsed - a.komSec * frac

                val toEnd = haversine(loc.lat, loc.lng, a.endLat, a.endLng)
                if (frac > 0.7 && toEnd < 30.0) {
                    holdValue = elapsed - a.komSec
                    holdUntilMs = now + 12000L
                    active = null
                    activePoints = emptyList()
                    ext.beepEnd()
                    emitter.onNext(
                        StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to holdValue)))
                    )
                } else {
                    emitter.onNext(
                        StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to delta)))
                    )
                }
            }
        }
        emitter.setCancellable {
            try { ext.karooSystem.removeConsumer(consumerId) } catch (e: Exception) { }
        }
    }
}
