package com.esendpulse.sdk

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geofence ke niyam — `packages/core/src/geofences.ts` ka port.
 *
 * Wahi haversine, wahi hysteresis, wahi 7 test cases (`GeofenceRulesTest.kt`)
 * jo TypeScript aur Swift mein hain. `android.*` aur `org.json` nahi, taaki
 * tests plain JVM par chalen.
 */
data class GeofenceSpec(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    /** Metres. */
    val radius: Double,
    val tags: List<String> = emptyList()
)

data class GeofenceTransition(
    val entered: List<GeofenceSpec>,
    val exited: List<GeofenceSpec>,
    val inside: Set<String>
)

object GeofenceRules {
    /** Isse kharab accuracy (cell tower wali) par koi faisla nahi. */
    const val MAX_ACCURACY = 1_000.0

    /** Bahar maanne ke liye radius ke upar kam se kam itna — GPS ka jhoola rokne ko. */
    const val EXIT_BUFFER = 50.0

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(min(1.0, sqrt(h)))
    }

    /**
     * Nayi location par kaun andar aaya, kaun bahar gaya. Andar = doori ≤ radius;
     * bahar = doori > radius + max(accuracy, 50 m). List se hat chuka ghera
     * chupchaap bhool jaate hain, exit nahi.
     */
    fun transitions(
        inside: Set<String>,
        fences: List<GeofenceSpec>,
        latitude: Double,
        longitude: Double,
        accuracy: Double
    ): GeofenceTransition {
        val known = fences.map { it.id }.toSet()
        val next = inside.filter { it in known }.toMutableSet()
        if (accuracy.isNaN() || accuracy < 0 || accuracy > MAX_ACCURACY) {
            return GeofenceTransition(emptyList(), emptyList(), next)
        }
        val entered = ArrayList<GeofenceSpec>()
        val exited = ArrayList<GeofenceSpec>()
        val buffer = max(accuracy, EXIT_BUFFER)
        for (f in fences) {
            val d = distanceMeters(latitude, longitude, f.latitude, f.longitude)
            if (f.id !in next && d <= f.radius) {
                next.add(f.id)
                entered.add(f)
            } else if (f.id in next && d > f.radius + buffer) {
                next.remove(f.id)
                exited.add(f)
            }
        }
        return GeofenceTransition(entered, exited, next)
    }

    /** Fetch wali jagah se itna door gaye (refresh radius ka 80%) to naye paas wale ghere maango. */
    fun shouldRefresh(
        fetchedLat: Double,
        fetchedLng: Double,
        refreshRadius: Double,
        latitude: Double,
        longitude: Double
    ): Boolean = distanceMeters(fetchedLat, fetchedLng, latitude, longitude) > refreshRadius * 0.8

    /** Event ki properties — dashboard aur journey filter inhi naamon ko padhte hain. */
    fun eventProperties(f: GeofenceSpec): Map<String, Any?> = mapOf(
        "geofence_id" to f.id,
        "geofence_name" to f.name,
        "geofence_tags" to f.tags,
        "geofence_radius" to f.radius.toInt()
    )
}
