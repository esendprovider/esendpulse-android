package com.esendpulse.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Geofencing, Android par — Google Play Services ke bina.
 *
 * `GeofencingClient` Play Services maangta (SDK "zero dependencies" hai, aur
 * Huawei/China ke phones par Play Services hota hi nahi). Isliye platform ka
 * `LocationManager` PendingIntent ke saath: system updates deta rehta hai,
 * app band ho tab bhi [EsendPulseLocationReceiver] ko jagata hai (background mein
 * Android ise ghante mein kuch baar tak seemit karta hai — dukaan par aane ke
 * liye kaafi). Andar/bahar ka faisla [GeofenceRules] karta hai — TS/Swift wala
 * hi niyam.
 *
 * Permission app maangta hai, SDK nahi: background location Play Store ki
 * policy review laati hai, aur jo app geofencing nahi chahta use library ke
 * manifest ki wajah se wo review nahi jhelna chahiye. SDK sirf jaanchta hai.
 *
 * Config (key/host) bhi yahan rakha jaata hai: receiver aise process mein chal
 * sakta hai jahan app ne abhi `EsendPulse.start` nahi kiya — tab bhi event bhejna hai.
 */
internal class Geofencing(private val context: Context) {

    companion object {
        private const val TAG = "EsendPulse"
        const val ACTION_LOCATION = "com.esendpulse.sdk.action.LOCATION"
        private const val PREFS = "esendpulse.geofencing"
        private const val MIN_TIME_MS = 60_000L
        private const val MIN_DISTANCE_M = 25f
        private val network = Executors.newSingleThreadExecutor { r -> Thread(r, "esendpulse-geo").apply { isDaemon = true } }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    val enabled: Boolean get() = prefs.getBoolean("enabled", false)

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Sab se achhi pichhli location + updates chalu. Permission na ho to false. */
    @SuppressLint("MissingPermission")
    fun enable(key: String, host: String): Boolean {
        if (!hasPermission()) {
            Log.w(TAG, "geofencing needs location permission — ask for it first, then call enableGeofencing()")
            return false
        }
        prefs.edit().putBoolean("enabled", true).putString("key", key).putString("host", host).apply()
        val intent = pendingIntent()
        for (provider in providers()) {
            try {
                locationManager.requestLocationUpdates(provider, MIN_TIME_MS, MIN_DISTANCE_M, intent)
            } catch (e: Exception) {
                Log.w(TAG, "location updates from $provider unavailable: ${e.message}")
            }
        }
        lastKnown()?.let { onLocation(it, forceRefresh = true) }
        return true
    }

    fun disable() {
        prefs.edit().clear().apply()
        try {
            locationManager.removeUpdates(pendingIntent())
        } catch (e: Exception) {
            // Kuch register tha hi nahi.
        }
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, EsendPulseLocationReceiver::class.java).setAction(ACTION_LOCATION)
        // Mutable: system is intent mein location daalta hai.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 7301, intent, flags)
    }

    private fun providers(): List<String> {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        return wanted.filter { p ->
            try {
                locationManager.allProviders.contains(p) && locationManager.isProviderEnabled(p)
            } catch (e: Exception) {
                false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): Location? = providers()
        .mapNotNull { p -> try { locationManager.getLastKnownLocation(p) } catch (e: Exception) { null } }
        .maxByOrNull { it.time }

    // ---- Har nayi location ------------------------------------------------

    /**
     * Nayi location aayi. Ghere puraane/khaali hon ya banda door nikal gaya to
     * pehle paas wale ghere maango, phir andar/bahar tay karo.
     */
    fun onLocation(location: Location, forceRefresh: Boolean = false, done: (() -> Unit)? = null) {
        if (!enabled) { done?.invoke(); return }
        val fences = loadFences()
        val fetched = prefs.getString("fetched", null)?.split(",")?.mapNotNull { it.toDoubleOrNull() }
        val stale = forceRefresh || fences == null || fetched == null || fetched.size != 3 ||
            GeofenceRules.shouldRefresh(fetched[0], fetched[1], fetched[2], location.latitude, location.longitude)
        if (!stale) {
            evaluate(fences!!, location)
            done?.invoke()
            return
        }
        fetch(location) { fresh ->
            evaluate(fresh ?: fences ?: emptyList(), location)
            done?.invoke()
        }
    }

    private fun fetch(location: Location, completion: (List<GeofenceSpec>?) -> Unit) {
        val key = prefs.getString("key", null)
        val host = prefs.getString("host", null)
        if (key == null || host == null) { completion(null); return }
        Transport(key, host, network).getJson(
            "/v1/geofences",
            mapOf("lat" to location.latitude.toString(), "lng" to location.longitude.toString())
        ) { result ->
            val json = result.getOrNull()
            if (json == null) {
                Log.w(TAG, "could not fetch geofences: ${result.exceptionOrNull()?.message}")
                completion(null)
                return@getJson
            }
            val list = parseFences(json.optJSONArray("geofences") ?: JSONArray())
            val refresh = json.optDouble("refresh_radius", 5_000.0)
            prefs.edit()
                .putString("fences", json.optJSONArray("geofences")?.toString() ?: "[]")
                .putString("fetched", "${location.latitude},${location.longitude},$refresh")
                .apply()
            completion(list)
        }
    }

    @Synchronized
    private fun evaluate(fences: List<GeofenceSpec>, location: Location) {
        val inside = prefs.getStringSet("inside", emptySet()) ?: emptySet()
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 100.0
        val r = GeofenceRules.transitions(inside, fences, location.latitude, location.longitude, accuracy)
        prefs.edit().putStringSet("inside", HashSet(r.inside)).apply()
        for (f in r.entered) send("\$geofence_entered", f)
        for (f in r.exited) send("\$geofence_exited", f)
    }

    private fun send(name: String, fence: GeofenceSpec) {
        Log.i(TAG, "$name ${fence.name}")
        val properties = GeofenceRules.eventProperties(fence)
        val client = EsendPulse.instanceOrNull
        if (client != null) {
            client.track(name, properties)
            client.flush()
            return
        }
        // Thanda process — SDK start nahi hua. Seedha bhejo; na jaaye to queue
        // mein, agli launch par jaayega.
        val store = Store(context)
        val event = QueuedEvent(
            eventId = UUID.randomUUID().toString(),
            name = name,
            externalId = store.externalId,
            anonymousId = store.anonymousId,
            timestamp = Iso8601.format(Date()),
            properties = properties.toJson()
        )
        val key = prefs.getString("key", null) ?: return
        val host = prefs.getString("host", null) ?: return
        Transport(key, host, network).post("/v1/events", JSONObject().put("events", JSONArray().put(event.toJson()))) { result ->
            if (result.isFailure) {
                val queue = store.loadQueue()
                queue.add(event)
                store.saveQueue(queue)
            }
        }
    }

    private fun loadFences(): List<GeofenceSpec>? =
        prefs.getString("fences", null)?.let { raw ->
            try { parseFences(JSONArray(raw)) } catch (e: Exception) { null }
        }

    private fun parseFences(array: JSONArray): List<GeofenceSpec> = (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        val tags = o.optJSONArray("tags")
        GeofenceSpec(
            id = o.optString("id"),
            name = o.optString("name"),
            latitude = o.optDouble("latitude"),
            longitude = o.optDouble("longitude"),
            radius = o.optDouble("radius", 200.0),
            tags = if (tags == null) emptyList() else (0 until tags.length()).map { tags.optString(it) }
        )
    }
}

/**
 * System ki location updates yahan aati hain (app band ho tab bhi). Manifest
 * library ka hai, exported nahi.
 */
class EsendPulseLocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Geofencing.ACTION_LOCATION) return
        @Suppress("DEPRECATION")
        val location = intent.getParcelableExtra<Location>(LocationManager.KEY_LOCATION_CHANGED) ?: return
        // Network call ke liye thoda waqt — onReceive ke baad bhi.
        val pending = goAsync()
        Geofencing(context.applicationContext).onLocation(location) { pending.finish() }
    }
}
