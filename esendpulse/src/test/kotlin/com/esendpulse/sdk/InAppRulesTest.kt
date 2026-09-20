package com.esendpulse.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The same cases as `packages/core/src/inapp-message.test.ts` and
 * `sdks/ios/Tests/EsendPulseSDKTests/InAppRulesTests.swift`.
 *
 * Deliberately the same, and worth keeping the same: these rules exist three
 * times — once in TypeScript for the dashboard and the web SDK, once in Swift,
 * once here — and the failure they guard against is the copies drifting apart,
 * which shows up as one campaign behaving differently on Android than on the
 * web with nothing in either product to explain why.
 */
class InAppRulesTest {

    private fun seen(
        session: Int = 0,
        today: Int = 0,
        day: String = "",
        lifetime: Int = 0,
        converted: Boolean = false,
        dismissed: Boolean = false
    ) = InAppSeenState(session, today, day, lifetime, converted, dismissed)

    // ---- normalize ----------------------------------------------------

    @Test
    fun unknownDelayIsClamped() {
        assertEquals(
            0,
            normalizeInAppTrigger(InAppTriggerConfig(InAppTrigger.APP_LAUNCH, delaySeconds = -5)).delaySeconds
        )
        assertEquals(
            InAppLimits.delaySeconds.max,
            normalizeInAppTrigger(InAppTriggerConfig(InAppTrigger.APP_LAUNCH, delaySeconds = 99_999)).delaySeconds
        )
    }

    @Test
    fun blankEventNameKeepsItsKind() {
        // Turning it into app_launch would show the message to everybody —
        // the opposite of what the author asked for.
        val trigger = normalizeInAppTrigger(InAppTriggerConfig(InAppTrigger.EVENT, eventName = "   "))
        assertEquals(InAppTrigger.EVENT, trigger.kind)
        assertEquals("", trigger.eventName)
    }

    @Test
    fun frequencyDefaultsAndClamps() {
        assertEquals(InAppFrequency.DEFAULT.perSession, normalizeInAppFrequency(null).perSession)
        val clamped = normalizeInAppFrequency(InAppFrequency(perSession = 999, perDay = -3, lifetime = 4))
        assertEquals(InAppLimits.perSession.max, clamped.perSession)
        assertEquals(0, clamped.perDay)
        assertEquals(4, clamped.lifetime)
    }

    // ---- triggers -----------------------------------------------------

    @Test
    fun appLaunchFiresOnceTheDelayHasPassed() {
        val trigger = normalizeInAppTrigger(InAppTriggerConfig(InAppTrigger.APP_LAUNCH, delaySeconds = 10))
        assertFalse(inAppTriggerFired(trigger, InAppMoment(InAppTrigger.APP_LAUNCH, secondsSinceMoment = 9)))
        assertTrue(inAppTriggerFired(trigger, InAppMoment(InAppTrigger.APP_LAUNCH, secondsSinceMoment = 10)))
    }

    @Test
    fun differentKindNeverFires() {
        val trigger = normalizeInAppTrigger(InAppTriggerConfig(InAppTrigger.SCREEN, screenName = "cart"))
        assertFalse(inAppTriggerFired(trigger, InAppMoment(InAppTrigger.APP_LAUNCH)))
    }

    @Test
    fun namesMatchIgnoringCaseAndPadding() {
        val event = normalizeInAppTrigger(InAppTriggerConfig(InAppTrigger.EVENT, eventName = "Product_Viewed"))
        assertTrue(inAppTriggerFired(event, InAppMoment(InAppTrigger.EVENT, eventName = " product_viewed ")))
        assertFalse(inAppTriggerFired(event, InAppMoment(InAppTrigger.EVENT, eventName = "cart_viewed")))

        val screen = normalizeInAppTrigger(InAppTriggerConfig(InAppTrigger.SCREEN, screenName = "checkout"))
        assertTrue(inAppTriggerFired(screen, InAppMoment(InAppTrigger.SCREEN, screenName = "Checkout")))
    }

    @Test
    fun namedTriggerWithNoNameNeverFires() {
        val event = normalizeInAppTrigger(InAppTriggerConfig(InAppTrigger.EVENT, eventName = ""))
        assertFalse(inAppTriggerFired(event, InAppMoment(InAppTrigger.EVENT, eventName = "anything")))
    }

    // ---- eligibility --------------------------------------------------

    private val frequency = InAppFrequency(perSession = 1, perDay = 2, lifetime = 5)

    @Test
    fun unseenMessageIsEligible() {
        assertTrue(inAppIsEligible(frequency, seen(), today = "2026-08-22"))
    }

    @Test
    fun pressedButtonEndsItForever() {
        assertFalse(inAppIsEligible(frequency, seen(converted = true), today = "2026-08-22"))
    }

    @Test
    fun dismissalStopsThisSessionOnly() {
        assertFalse(inAppIsEligible(frequency, seen(session = 1, dismissed = true), today = "2026-08-22"))
        // New session: the counters reset, and the memory alone does not block.
        assertTrue(inAppIsEligible(frequency, seen(session = 0, dismissed = true), today = "2026-08-22"))
    }

    @Test
    fun sessionCap() {
        assertFalse(inAppIsEligible(frequency, seen(session = 1), today = "2026-08-22"))
        assertTrue(
            inAppIsEligible(
                InAppFrequency(perSession = 0, perDay = 2, lifetime = 5),
                seen(session = 9),
                today = "2026-08-22"
            )
        )
    }

    @Test
    fun dailyCapAppliesOnlyToTheDayItWasCounted() {
        // The eleven-launches-a-day pattern is why this cap exists at all.
        assertFalse(inAppIsEligible(frequency, seen(today = 2, day = "2026-08-22"), today = "2026-08-22"))
        // Yesterday's tally must not spend today's budget.
        assertTrue(inAppIsEligible(frequency, seen(today = 2, day = "2026-08-21"), today = "2026-08-22"))
    }

    @Test
    fun lifetimeCap() {
        assertFalse(inAppIsEligible(frequency, seen(lifetime = 5), today = "2026-08-22"))
    }

    // ---- links and days -----------------------------------------------

    @Test
    fun deepLinksAreValidDestinations() {
        assertTrue(isInAppLink("myapp://cart"))
        assertTrue(isInAppLink("https://example.com/offer"))
        assertFalse(isInAppLink("/cart"))
        assertFalse(isInAppLink("cart"))
    }

    @Test
    fun dayKeyNamesTheLocalCalendarDay() {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        calendar.clear()
        calendar.set(2026, Calendar.AUGUST, 22, 23, 30, 0)
        val date = calendar.time
        assertEquals("2026-08-22", inAppDayKey(date, calendar))
    }
}
