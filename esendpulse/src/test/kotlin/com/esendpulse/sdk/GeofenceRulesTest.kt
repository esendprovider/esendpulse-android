package com.esendpulse.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wahi cases jo `packages/core/src/geofences.test.ts` aur
 * `sdks/ios/Tests/EsendPulseSDKTests/GeofenceRulesTests.swift` mein hain.
 */
class GeofenceRulesTest {
    private val store = GeofenceSpec("A", "Store", 19.076, 72.8777, 200.0)

    /** Uttar mein itne metre (1° lat ≈ 111,195 m). */
    private fun north(m: Double) = Pair(store.latitude + m / 111_195.0, store.longitude)

    private fun run(inside: Set<String>, at: Pair<Double, Double>, accuracy: Double = 10.0): Triple<List<String>, List<String>, Set<String>> {
        val r = GeofenceRules.transitions(inside, listOf(store), at.first, at.second, accuracy)
        return Triple(r.entered.map { it.id }, r.exited.map { it.id }, r.inside)
    }

    @Test fun distanceMumbaiToPune() {
        val d = GeofenceRules.distanceMeters(19.076, 72.8777, 18.5204, 73.8567)
        assertTrue(d > 118_000 && d < 122_000)
    }

    @Test fun distance400mNorth() {
        val p = north(400.0)
        assertEquals(400L, Math.round(GeofenceRules.distanceMeters(store.latitude, store.longitude, p.first, p.second)))
    }

    @Test fun case1EntersAtCentre() = assertEquals(Triple(listOf("A"), emptyList<String>(), setOf("A")), run(emptySet(), north(0.0)))
    @Test fun case2StaysInBufferBand() = assertEquals(Triple(emptyList<String>(), emptyList<String>(), setOf("A")), run(setOf("A"), north(230.0)))
    @Test fun case3ExitsWellOutside() = assertEquals(Triple(emptyList<String>(), listOf("A"), emptySet<String>()), run(setOf("A"), north(400.0)))
    @Test fun case4VagueFixWidensBuffer() = assertEquals(Triple(emptyList<String>(), emptyList<String>(), setOf("A")), run(setOf("A"), north(400.0), 300.0))
    @Test fun case5IgnoresFixWorseThan1km() = assertEquals(Triple(emptyList<String>(), emptyList<String>(), emptySet<String>()), run(emptySet(), north(0.0), 1500.0))
    @Test fun case6ForgetsUnlistedFenceWithoutExit() = assertEquals(Triple(emptyList<String>(), emptyList<String>(), emptySet<String>()), run(setOf("GONE"), north(1000.0)))
    @Test fun case7DoesNotEnterFromBufferBand() = assertEquals(Triple(emptyList<String>(), emptyList<String>(), emptySet<String>()), run(emptySet(), north(230.0)))

    @Test fun refreshesAfterMovingMostOfTheWay() {
        val far = north(900.0)
        assertTrue(GeofenceRules.shouldRefresh(store.latitude, store.longitude, 1000.0, far.first, far.second))
        val near = north(500.0)
        assertFalse(GeofenceRules.shouldRefresh(store.latitude, store.longitude, 1000.0, near.first, near.second))
    }
}
