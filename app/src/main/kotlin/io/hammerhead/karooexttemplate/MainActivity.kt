package io.hammerhead.karooexttemplate

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import io.hammerhead.karooexttemplate.extension.SegmentSync
import io.hammerhead.karooexttemplate.extension.haversine
import io.hammerhead.karooexttemplate.extension.komAvgKmh
import io.hammerhead.karooexttemplate.extension.readDescents

class MainActivity : ComponentActivity() {

    private lateinit var output: TextView
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

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        output = TextView(this).apply {
            textSize = 15f
            setPadding(24, 24, 24, 12)
        }
        val simBtn = Button(this).apply {
            text = "SIMULA DISCESA"
            setOnClickListener { startSim() }
        }
        val scroll = ScrollView(this)
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(simBtn)
        setContentView(root)
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
                    var withCurve = 0
                    for (d in readDescents(applicationContext)) if (d.curve.size > 1) withCurve++
                    "Pronto: $n discese salvate\n($withCurve con profilo altimetrico)"
                }
            }
            runOnUiThread { render() }
        }.start()
    }

    private fun startSim() {
        val list = readDescents(applicationContext)
        if (list.isEmpty()) {
            status = "Nessuna discesa in memoria: sincronizza prima."
            render()
            return
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
        getSharedPreferences("karoo_discesa", MODE_PRIVATE).edit()
            .putInt("simIdx", idx)
            .putLong("simStart", System.currentTimeMillis())
            .apply()
        status = "Simulazione avviata su:\n${list[idx].name}\n\n" +
                "Vai alla pagina di corsa e guarda il campo.\nDura circa 30-60 secondi."
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
        val sb = StringBuilder()
        sb.append(status).append("\n\n")
        val loc = lastLoc
        if (loc == null) {
            sb.append("GPS: in attesa...")
        } else {
            sb.append("GPS: ${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}\n\n")
            val list = readDescents(applicationContext)
            if (list.isNotEmpty()) {
                var best = -1.0
                var name = ""
                var kom = 0.0
                for (d in list) {
                    val dist = haversine(loc.latitude, loc.longitude, d.lat, d.lng)
                    if (best < 0 || dist < best) {
                        best = dist; name = d.name; kom = komAvgKmh(d)
                    }
                }
                sb.append("Discesa più vicina:\n• $name\n")
                sb.append("   partenza a ${best.toInt()} m\n")
                sb.append("   KOM ${"%.1f".format(kom)} km/h")
            }
        }
        output.text = sb.toString()
    }
}
