package io.hammerhead.karooexttemplate

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import io.hammerhead.karooexttemplate.extension.Descent
import io.hammerhead.karooexttemplate.extension.SegmentSync
import io.hammerhead.karooexttemplate.extension.haversine
import io.hammerhead.karooexttemplate.extension.komAvgKmh
import io.hammerhead.karooexttemplate.extension.readDescents

class MainActivity : ComponentActivity() {

    private lateinit var output: TextView
    private lateinit var listBox: LinearLayout
    private var status = "Sincronizzazione..."
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

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        output = TextView(this).apply { textSize = 15f }
        val simBtn = Button(this).apply {
            text = "SIMULA DISCESA"
            setOnClickListener { startSim() }
        }
        val disarmBtn = Button(this).apply {
            text = "ANNULLA SEGMENTO ARMATO"
            textSize = 12f
            setOnClickListener { disarm() }
        }
        val hint = TextView(this).apply {
            textSize = 13f
            setPadding(0, 20, 0, 6)
            text = "Sulla mappa: tocca una bandierina e premi \"Vai a\" per armarla.\n" +
                    "Oppure tocca qui sotto:"
        }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(output)
        col.addView(simBtn)
        col.addView(disarmBtn)
        col.addView(hint)
        col.addView(listBox)

        val scroll = ScrollView(this)
        scroll.addView(col)
        setContentView(scroll)
        render()

        Thread {
            val n = SegmentSync.sync(applicationContext, 0L) { msg ->
                status = msg
                runOnUiThread { render() }
            }
            status = when (n) {
                SegmentSync.FAILED -> "Sincronizzazione non riuscita.\nControlla la connessione."
                SegmentSync.SKIPPED -> "Sincronizzazione già in corso."
                else -> {
                    val all = readDescents(applicationContext)
                    var noKom = 0
                    var withCurve = 0
                    for (d in all) {
                        if (d.komSec <= 0.0) noKom++
                        if (d.curve.size > 1) withCurve++
                    }
                    "Pronto: $n discese salvate\n$withCurve con profilo · $noKom senza KOM"
                }
            }
            runOnUiThread { render() }
        }.start()
    }

    private fun prefs() = getSharedPreferences("karoo_discesa", MODE_PRIVATE)

    private fun startSim() {
        val list = readDescents(applicationContext)
        if (list.isEmpty()) {
            status = "Nessuna discesa in memoria: sincronizza prima."
            render(); return
        }
        var idx = 0
        val loc = lastLoc
        if (loc != null) {
            var best = -1.0
            for (i in list.indices) {
                val d = haversine(loc.latitude, loc.longitude, list[i].lat, list[i].lng)
                if (best < 0 || d < best) { best = d; idx = i }
            }
        }
        prefs().edit()
            .putInt("simIdx", idx)
            .putLong("simStart", System.currentTimeMillis())
            .apply()
        status = "Simulazione avviata su:\n${list[idx].name}"
        render()
    }

    private fun arm(d: Descent) {
        prefs().edit()
            .putString("armedName", d.name)
            .putLong("armedAt", System.currentTimeMillis())
            .apply()
        status = "ARMATO: ${d.name}"
        render()
    }

    private fun disarm() {
        prefs().edit()
            .remove("armedName")
            .putLong("armedAt", System.currentTimeMillis())
            .apply()
        status = "Nessun segmento armato."
        render()
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
        render()
    }

    private fun startGps() {
        if (gpsStarted) return
        gpsStarted = true
        try {
            lastLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, listener
            )
        } catch (e: Exception) { }
        render()
    }

    override fun onDestroy() {
        try { locationManager.removeUpdates(listener) } catch (e: Exception) { }
        super.onDestroy()
    }

    private fun render() {
        val armed = prefs().getString("armedName", null)
        val sb = StringBuilder()
        sb.append(status)
        if (armed != null) sb.append("\n\n▶ ARMATO: $armed")
        sb.append(if (lastLoc == null) "\n\nGPS: in attesa..." else "\n\nGPS ok")
        output.text = sb.toString()

        listBox.removeAllViews()
        val loc = lastLoc ?: return
        val list = readDescents(applicationContext)
        if (list.isEmpty()) return

        val sorted = list.sortedBy { haversine(loc.latitude, loc.longitude, it.lat, it.lng) }
        for (d in sorted.take(12)) {
            val dist = haversine(loc.latitude, loc.longitude, d.lat, d.lng)
            val km = komAvgKmh(d)
            val b = Button(this)
            b.text = "${d.name}\n${String.format("%.1f", dist / 1000)} km · " +
                    (if (km > 0) "KOM ${String.format("%.1f", km)} km/h" else "senza KOM") +
                    (if (d.name == armed) "  ← ARMATO" else "")
            b.textSize = 12f
            b.isAllCaps = false
            if (d.name == armed) b.setTextColor(Color.parseColor("#33CC33"))
            b.setOnClickListener { arm(d) }
            listBox.addView(b)
        }
    }
}
