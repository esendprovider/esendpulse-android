package com.esendpulse.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rich-push decisions, held to the same account as the in-app rules.
 *
 * These exist twice — here and in `packages/core/src/push-template.ts`, which
 * the server and the dashboard preview both read — and the failure they guard
 * against is the two drifting: a carousel that wraps on the server's preview
 * and dead-ends on the handset, or a countdown the phone thinks has expired
 * while the campaign report says it is still running.
 */
class PushRulesTest {

    private fun push(
        kind: PushKind = PushKind.BASIC,
        slides: List<PushSlide> = emptyList(),
        products: List<PushProduct> = emptyList(),
        actions: List<PushAction> = emptyList(),
        url: String? = null,
        image: String? = null,
        icon: String? = null,
        endsAt: Long? = null,
        afterTitle: String? = null,
        afterBody: String? = null
    ) = RichPush(
        version = 1,
        kind = kind,
        title = "Title",
        body = "Body",
        token = "abc",
        url = url,
        image = image,
        icon = icon,
        actions = actions,
        endsAt = endsAt,
        afterTitle = afterTitle,
        afterBody = afterBody,
        slides = slides,
        products = products
    )

    // ---- kinds --------------------------------------------------------

    @Test
    fun unknownKindFallsBackRatherThanFailing() {
        // An older app must still deliver the message when the server learns a
        // template it has never heard of.
        assertEquals(PushKind.BASIC, PushKind.from("hologram"))
        assertEquals(PushKind.BASIC, PushKind.from(null))
        assertEquals(PushKind.CAROUSEL, PushKind.from("carousel"))
    }

    @Test
    fun onlyThreeKindsNeedTheCustomLayout() {
        assertTrue(PushRules.needsCustomLayout(PushKind.CAROUSEL))
        assertTrue(PushRules.needsCustomLayout(PushKind.RATING))
        assertTrue(PushRules.needsCustomLayout(PushKind.PRODUCT))
        // A countdown is a framework chronometer and a reply is a RemoteInput;
        // drawing either by hand would be worse than what Android already does.
        assertFalse(PushRules.needsCustomLayout(PushKind.TIMER))
        assertFalse(PushRules.needsCustomLayout(PushKind.INPUT))
    }

    // ---- notification id ----------------------------------------------

    @Test
    fun twoMessagesGetTwoNotifications() {
        assertTrue(PushRules.notificationId("one") != PushRules.notificationId("two"))
        assertEquals(PushRules.notificationId("one"), PushRules.notificationId("one"))
        assertTrue(PushRules.notificationId("anything") >= 0)
    }

    // ---- carousel ------------------------------------------------------

    @Test
    fun carouselWrapsInBothDirections() {
        assertEquals(1, PushRules.nextSlide(0, 3, forward = true))
        assertEquals(0, PushRules.nextSlide(2, 3, forward = true))
        assertEquals(2, PushRules.nextSlide(0, 3, forward = false))
    }

    @Test
    fun carouselSurvivesAnEmptyPayload() {
        assertEquals(0, PushRules.nextSlide(0, 0, forward = true))
    }

    // ---- timer ---------------------------------------------------------

    @Test
    fun countdownEndsOnTheHandsetsOwnClock() {
        assertFalse(PushRules.timerExpired(2_000L, 1_999L))
        assertTrue(PushRules.timerExpired(2_000L, 2_000L))
        assertFalse(PushRules.timerExpired(null, Long.MAX_VALUE))
    }

    @Test
    fun expiredTextFallsBackToTheOriginal() {
        assertEquals(Pair("Title", "Body"), PushRules.expiredText(push()))
        assertEquals(
            Pair("Gone", "Body"),
            PushRules.expiredText(push(afterTitle = "Gone"))
        )
    }

    // ---- images --------------------------------------------------------

    @Test
    fun onlyTheVisibleSlideIsFetched() {
        val p = push(
            kind = PushKind.CAROUSEL,
            slides = listOf(
                PushSlide("https://a/1.png", null, null),
                PushSlide("https://a/2.png", null, null),
                PushSlide("https://a/3.png", null, null)
            )
        )
        assertEquals(listOf("https://a/2.png"), PushRules.imagesToFetch(p, slideIndex = 1))
    }

    @Test
    fun theIconIsAlwaysWorthFetching() {
        val p = push(kind = PushKind.BIG_PICTURE, image = "https://a/big.png", icon = "https://a/i.png")
        assertEquals(listOf("https://a/i.png", "https://a/big.png"), PushRules.imagesToFetch(p, 0))
    }

    @Test
    fun productImagesAreCappedSoTheTrayDoesNotWait() {
        val p = push(
            kind = PushKind.PRODUCT,
            products = (1..6).map { PushProduct("s$it", null, null, "https://a/$it.png", null) }
        )
        assertEquals(3, PushRules.imagesToFetch(p, 0).size)
    }

    @Test
    fun theSameUrlIsNotFetchedTwice() {
        val p = push(kind = PushKind.BIG_PICTURE, image = "https://a/x.png", icon = "https://a/x.png")
        assertEquals(1, PushRules.imagesToFetch(p, 0).size)
    }

    // ---- links ---------------------------------------------------------

    @Test
    fun theMostSpecificLinkWins() {
        val p = push(
            kind = PushKind.CAROUSEL,
            url = "https://shop/campaign",
            slides = listOf(
                PushSlide("https://a/1.png", null, "https://shop/slide-one"),
                PushSlide("https://a/2.png", null, null)
            ),
            actions = listOf(PushAction("buy", "Buy", "https://shop/buy"))
        )
        assertEquals("https://shop/buy", PushRules.linkFor(p, "buy", 0))
        assertEquals("https://shop/slide-one", PushRules.linkFor(p, null, 0))
        // Second slide has no link of its own, so the campaign's is right.
        assertEquals("https://shop/campaign", PushRules.linkFor(p, null, 1))
    }

    @Test
    fun aButtonWithoutALinkStillOpensTheCampaign() {
        val p = push(url = "https://shop/campaign", actions = listOf(PushAction("later", "Later", null)))
        assertEquals("https://shop/campaign", PushRules.linkFor(p, "later", 0))
    }

    @Test
    fun nothingToOpenIsNotAnError() {
        assertNull(PushRules.linkFor(push(), null, 0))
    }

    // ---- stars ---------------------------------------------------------

    @Test
    fun starsAreClampedBecauseAPayloadIsNotAPromise() {
        assertEquals(3, PushRules.starCount(0))
        assertEquals(5, PushRules.starCount(99))
        assertEquals(4, PushRules.starCount(4))
    }
}
