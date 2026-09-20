package com.esendpulse.sdk

import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * When an in-app message may appear, and how often.
 *
 * This is a port of `packages/core/src/inapp-message.ts` — the same port the
 * iOS SDK carries in `InAppRules.swift` — and it is a port rather than a fetch
 * on purpose: the decision has to be made on the device, in the moment, with
 * facts (which screen, which session, how many today) that are gone by the
 * time a server could hear about them.
 *
 * Because it is a port, the rules have to keep agreeing with the original.
 * The tests here are the same cases as the TypeScript and the Swift ones, so a
 * change on one side that the others do not follow fails a test rather than
 * producing three products that behave differently on the same campaign.
 *
 * Nothing in this file touches `android.*` or `org.json`. That is what lets it
 * run as a plain JVM unit test instead of an instrumented one — `org.json` is
 * stubbed to throw in unit tests, and a rules file that reached for it would
 * force the whole suite onto an emulator. Tests that need an emulator to run
 * are tests people stop running.
 */

enum class InAppTrigger(val wire: String) {
    APP_LAUNCH("app_launch"),
    EVENT("event"),
    SCREEN("screen");

    companion object {
        fun fromWire(raw: String?): InAppTrigger? = entries.firstOrNull { it.wire == raw }
    }
}

enum class InAppLayout(val wire: String) {
    MODAL("modal"),
    HALF_INTERSTITIAL("half_interstitial"),
    COVER("cover"),
    BANNER_TOP("banner_top"),
    BANNER_BOTTOM("banner_bottom"),
    /** A small muted video in a corner, over the app, while the person carries on. */
    PIP("pip"),
    CUSTOM_HTML("custom_html");

    /** Layouts that are a line of text with somewhere to go. */
    val isBanner: Boolean get() = this == BANNER_TOP || this == BANNER_BOTTOM

    /** Layouts that sit over the app without dimming it or taking its touches. */
    val isFloating: Boolean get() = this == PIP

    companion object {
        fun fromWire(raw: String?): InAppLayout? = entries.firstOrNull { it.wire == raw }
    }
}

data class InAppTriggerConfig(
    val kind: InAppTrigger,
    /** For [InAppTrigger.EVENT] — the event name that opens the moment. */
    val eventName: String? = null,
    /** For [InAppTrigger.SCREEN] — the screen the app reported. */
    val screenName: String? = null,
    /** Seconds to wait after that moment before showing. 0 = straight away. */
    val delaySeconds: Int = 0
)

/**
 * How often a message may appear.
 *
 * [perDay] has no equivalent in the web pop-up rules, and it is here for the
 * reason it is there: an app session is not a browsing session. People open an
 * app eleven times a day for ten seconds each, and "once per session" on that
 * pattern is eleven interruptions.
 */
data class InAppFrequency(
    val perSession: Int = 1,
    val perDay: Int = 2,
    val lifetime: Int = 5
) {
    companion object {
        val DEFAULT = InAppFrequency()
    }
}

object InAppLimits {
    data class Bounds(val min: Int, val max: Int, val default: Int)

    val delaySeconds = Bounds(0, 300, 0)
    val perSession = Bounds(0, 20, 1)
    val perDay = Bounds(0, 50, 2)
    val lifetime = Bounds(0, 100, 5)
}

private fun clamp(value: Int?, bounds: InAppLimits.Bounds): Int {
    val n = value ?: bounds.default
    return minOf(bounds.max, maxOf(bounds.min, n))
}

fun normalizeInAppTrigger(raw: InAppTriggerConfig?): InAppTriggerConfig {
    if (raw == null) return InAppTriggerConfig(kind = InAppTrigger.APP_LAUNCH, delaySeconds = 0)
    val delay = clamp(raw.delaySeconds, InAppLimits.delaySeconds)
    return when (raw.kind) {
        // A blank name keeps its kind rather than falling back to app launch:
        // turning it into "show everybody" is the opposite of what an author
        // who chose "after an event" asked for.
        InAppTrigger.EVENT -> InAppTriggerConfig(
            kind = InAppTrigger.EVENT,
            eventName = (raw.eventName ?: "").trim(),
            delaySeconds = delay
        )
        InAppTrigger.SCREEN -> InAppTriggerConfig(
            kind = InAppTrigger.SCREEN,
            screenName = (raw.screenName ?: "").trim(),
            delaySeconds = delay
        )
        InAppTrigger.APP_LAUNCH -> InAppTriggerConfig(
            kind = InAppTrigger.APP_LAUNCH,
            delaySeconds = delay
        )
    }
}

fun normalizeInAppFrequency(raw: InAppFrequency?): InAppFrequency = InAppFrequency(
    perSession = clamp(raw?.perSession, InAppLimits.perSession),
    perDay = clamp(raw?.perDay, InAppLimits.perDay),
    lifetime = clamp(raw?.lifetime, InAppLimits.lifetime)
)

/** What just happened in the app, as the SDK sees it. */
data class InAppMoment(
    val kind: InAppTrigger,
    val eventName: String? = null,
    val screenName: String? = null,
    /** Seconds since that moment. */
    val secondsSinceMoment: Int = 0
)

/**
 * Has this trigger's moment arrived?
 *
 * Names match case-insensitively and trimmed. An app reporting `Checkout`
 * against a dashboard that says `checkout` is one screen to every human who
 * looks at it, and a message that silently never appears is the hardest bug
 * this channel produces: nothing errors, nothing logs, the campaign just reads
 * 0 shown a week later.
 */
fun inAppTriggerFired(trigger: InAppTriggerConfig, moment: InAppMoment): Boolean {
    if (trigger.kind != moment.kind) return false
    if (moment.secondsSinceMoment < trigger.delaySeconds) return false

    fun same(a: String?, b: String?): Boolean {
        val want = (a ?: "").trim().lowercase(Locale.ROOT)
        if (want.isEmpty()) return false
        return want == (b ?: "").trim().lowercase(Locale.ROOT)
    }

    return when (trigger.kind) {
        InAppTrigger.EVENT -> same(trigger.eventName, moment.eventName)
        InAppTrigger.SCREEN -> same(trigger.screenName, moment.screenName)
        InAppTrigger.APP_LAUNCH -> true
    }
}

/** What this device remembers about a message it has met before. */
data class InAppSeenState(
    /** Times shown in this app session. */
    val session: Int = 0,
    /** Times shown on the day named by [day]. */
    val today: Int = 0,
    /** The calendar day [today] counts, as yyyy-MM-dd in the device's zone. */
    val day: String = "",
    /** Times shown on this device, ever. */
    val lifetime: Int = 0,
    /** True once somebody pressed its button. */
    val converted: Boolean = false,
    /** True once somebody closed it by hand. */
    val dismissed: Boolean = false
) {
    companion object {
        val NEVER = InAppSeenState()
    }
}

/**
 * May this message be shown again?
 *
 * [InAppSeenState.today] is believed only when it belongs to the day being
 * asked about — a stale counter from last Tuesday must not spend today's
 * budget. The caller passes the day in rather than this function reading a
 * clock, which is what keeps the rule testable.
 */
fun inAppIsEligible(frequency: InAppFrequency, seen: InAppSeenState, today: String): Boolean {
    if (seen.converted) return false
    // Closed by hand means "not now". Tomorrow's budget may still allow it,
    // but repeating it inside the session it was just closed in re-asks a
    // question answered seconds ago.
    if (seen.dismissed && seen.session > 0) return false
    if (frequency.perSession > 0 && seen.session >= frequency.perSession) return false
    if (frequency.lifetime > 0 && seen.lifetime >= frequency.lifetime) return false
    if (frequency.perDay > 0 && seen.day == today && seen.today >= frequency.perDay) return false
    return true
}

/** The calendar day, as the frequency counters name it. */
@JvmOverloads
fun inAppDayKey(date: Date = Date(), calendar: Calendar = Calendar.getInstance()): String {
    // Cloned, because a Calendar carries a time of its own and callers pass
    // theirs in only for its time zone.
    val c = calendar.clone() as Calendar
    c.time = date
    return String.format(
        Locale.ROOT,
        "%04d-%02d-%02d",
        c.get(Calendar.YEAR),
        c.get(Calendar.MONTH) + 1,
        c.get(Calendar.DAY_OF_MONTH)
    )
}

private val LINK = Regex("^[a-z][a-z0-9+.-]*://\\S+$", RegexOption.IGNORE_CASE)

/**
 * A destination the app can open.
 *
 * Unlike a web pop-up's button this is usually NOT a web address: the point of
 * an in-app message is to move somebody deeper into the app, which means a
 * deep link. Demanding http(s) would refuse the most common correct answer.
 */
fun isInAppLink(value: String): Boolean = LINK.matches(value.trim())
