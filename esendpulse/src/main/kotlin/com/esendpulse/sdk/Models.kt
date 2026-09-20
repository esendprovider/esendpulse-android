package com.esendpulse.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The wire shapes, exactly as `/v1` speaks them.
 *
 * Parsed by hand out of `org.json` rather than by a serialization library.
 * That is the same bargain [Transport] makes about networking: an analytics
 * SDK that drags kotlinx-serialization (a compiler plugin and a runtime) into
 * somebody's app is making their build slower and their dependency graph
 * riskier to save itself a hundred lines. `org.json` ships inside Android.
 *
 * The cost is that this file cannot be exercised by a plain JVM unit test —
 * `org.json` is stubbed to throw there — which is exactly why the rules that
 * decide whether a message appears live in [InAppRules] instead, with no
 * `org.json` anywhere near them.
 */

/** A card in somebody's tray. */
data class InboxMessage(
    val id: String,
    val title: String,
    val body: String,
    val url: String?,
    val createdAt: Date,
    val read: Boolean
) {
    internal companion object {
        fun from(json: JSONObject) = InboxMessage(
            id = json.optString("id", ""),
            title = json.stringOr("title", ""),
            body = json.stringOr("body", ""),
            url = json.stringOrNull("url"),
            createdAt = Iso8601.parse(json.stringOrNull("created_at")),
            read = json.optBoolean("read", false)
        )
    }
}

data class InboxPage(
    val messages: List<InboxMessage>,
    /**
     * Counted by the server over the whole tray, not this page: a badge that
     * undercounts teaches people to ignore it.
     */
    val unread: Int
)

/** A message waiting to be shown inside the app. */
data class InAppMessage(
    val id: String,
    val layout: InAppLayout,
    val headline: String,
    val body: String,
    /** Only for the `custom_html` layout; null for every other one. */
    val html: String?,
    val imageUrl: String?,
    /** Only for the `pip` layout — the https MP4/WebM that plays in the corner. */
    val videoUrl: String? = null,
    /** `pip`: bottom_right (default), bottom_left, top_right, top_left. */
    val corner: String? = null,
    val ctaLabel: String?,
    val ctaUrl: String?,
    val dismissible: Boolean,
    val trigger: InAppTriggerConfig,
    val frequency: InAppFrequency
) {
    internal companion object {
        fun from(json: JSONObject) = InAppMessage(
            id = json.optString("id", ""),
            // An unknown layout is a newer dashboard talking to an older app.
            // Fall back to a dialog rather than dropping the message: the
            // words are still worth showing, and a silent disappearance is
            // unexplainable from either end.
            layout = InAppLayout.fromWire(json.stringOrNull("layout")) ?: InAppLayout.MODAL,
            headline = json.stringOr("headline", ""),
            body = json.stringOr("body", ""),
            html = json.stringOrNull("html"),
            imageUrl = json.stringOrNull("image_url"),
            videoUrl = json.stringOrNull("video_url"),
            corner = json.stringOrNull("corner"),
            ctaLabel = json.stringOrNull("cta_label"),
            ctaUrl = json.stringOrNull("cta_url"),
            dismissible = json.optBoolean("dismissible", true),
            trigger = normalizeInAppTrigger(triggerFrom(json.optJSONObject("trigger"))),
            frequency = normalizeInAppFrequency(frequencyFrom(json.optJSONObject("frequency")))
        )

        /**
         * The `trigger` and `frequency` columns are written by the dashboard,
         * which speaks TypeScript — so their keys are camelCase while every
         * key around them is snake_case. Reading them by hand keeps that
         * asymmetry visible instead of hiding it behind a naming strategy
         * that would have to guess, and guess wrong on one of the two.
         */
        private fun triggerFrom(json: JSONObject?): InAppTriggerConfig? {
            if (json == null) return null
            val kind = InAppTrigger.fromWire(json.stringOrNull("kind")) ?: InAppTrigger.APP_LAUNCH
            return InAppTriggerConfig(
                kind = kind,
                eventName = json.stringOrNull("eventName"),
                screenName = json.stringOrNull("screenName"),
                delaySeconds = json.optInt("delaySeconds", 0)
            )
        }

        private fun frequencyFrom(json: JSONObject?): InAppFrequency? {
            if (json == null) return null
            return InAppFrequency(
                perSession = json.optInt("perSession", InAppLimits.perSession.default),
                perDay = json.optInt("perDay", InAppLimits.perDay.default),
                lifetime = json.optInt("lifetime", InAppLimits.lifetime.default)
            )
        }
    }
}

/** What the host app is told when a message is on screen. */
interface EsendPulseInAppListener {
    /**
     * Return false to draw the message yourself; the SDK then reports nothing
     * until you call [EsendPulse.inAppShown] / [EsendPulse.inAppClicked] /
     * [EsendPulse.inAppDismissed].
     */
    fun shouldPresent(message: InAppMessage): Boolean = true

    /**
     * The person pressed the button. Open [InAppMessage.ctaUrl] — it is
     * usually a deep link into a router this SDK has never heard of.
     */
    fun onClick(message: InAppMessage) {}
}

/** One queued event, as `/v1/events` wants it. */
internal data class QueuedEvent(
    val eventId: String,
    val name: String,
    val externalId: String?,
    val anonymousId: String?,
    val timestamp: String,
    val properties: JSONObject
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("event_id", eventId)
        put("name", name)
        if (externalId != null) put("external_id", externalId)
        if (anonymousId != null) put("anonymous_id", anonymousId)
        put("timestamp", timestamp)
        put("properties", properties)
    }

    companion object {
        fun from(json: JSONObject) = QueuedEvent(
            eventId = json.optString("event_id", ""),
            name = json.optString("name", ""),
            externalId = json.stringOrNull("external_id"),
            anonymousId = json.stringOrNull("anonymous_id"),
            timestamp = json.optString("timestamp", ""),
            properties = json.optJSONObject("properties") ?: JSONObject()
        )
    }
}

/**
 * Timestamps, in the one format the API speaks.
 *
 * `java.time` would be tidier and is off the table: it needs API 26 or a
 * desugaring dependency, and this SDK runs from 24 with no dependencies at
 * all. A new formatter per call rather than a shared one because
 * [SimpleDateFormat] is not thread-safe and this is called from both the
 * worker thread and the network threads.
 */
internal object Iso8601 {
    fun format(date: Date): String {
        val out = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        out.timeZone = TimeZone.getTimeZone("UTC")
        return out.format(date)
    }

    /**
     * The API sends fractional seconds; some fields do not. Both are tried,
     * and an unreadable timestamp becomes "now" rather than an exception —
     * a card with a slightly wrong age still belongs in the tray, and a
     * throw here would lose the whole page.
     */
    fun parse(raw: String?): Date {
        if (raw.isNullOrBlank()) return Date()
        for (pattern in arrayOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX")) {
            try {
                return SimpleDateFormat(pattern, Locale.ROOT).parse(raw) ?: continue
            } catch (_: Exception) {
                // try the next shape
            }
        }
        return Date()
    }
}

// ---- org.json conveniences ---------------------------------------------

/** `optString` answers `""` for both "absent" and "empty", and `"null"` for a JSON null. */
internal fun JSONObject.stringOrNull(key: String): String? {
    if (isNull(key)) return null
    val value = optString(key, "")
    return value.ifEmpty { null }
}

internal fun JSONObject.stringOr(key: String, fallback: String): String =
    stringOrNull(key) ?: fallback

/**
 * Whatever the host app passed in, as something `org.json` can write.
 *
 * The equivalent of the iOS SDK's `JSONValue.from`, and needed for the same
 * reason: event properties have to survive a round trip through disk, so
 * "some object the app handed us" has to become a value with a known shape at
 * the moment it is tracked, not at the moment it is sent.
 */
internal fun jsonSafe(any: Any?): Any = when (any) {
    null -> JSONObject.NULL
    is String, is Boolean, is Int, is Long, is Short, is Byte -> any
    is Double -> if (any.isFinite()) any else any.toString()
    is Float -> if (any.isFinite()) any.toDouble() else any.toString()
    is Date -> Iso8601.format(any)
    is Map<*, *> -> JSONObject().apply {
        for ((key, value) in any) put(key.toString(), jsonSafe(value))
    }
    is Iterable<*> -> JSONArray().apply { for (value in any) put(jsonSafe(value)) }
    is Array<*> -> JSONArray().apply { for (value in any) put(jsonSafe(value)) }
    else -> any.toString()
}

internal fun Map<String, Any?>.toJson(): JSONObject =
    JSONObject().apply { for ((key, value) in this@toJson) put(key, jsonSafe(value)) }
