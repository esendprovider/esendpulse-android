package com.esendpulse.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reading the `notify` data key off an FCM message.
 *
 * Hand-parsed out of `org.json`, like every other wire shape in this SDK — see
 * the note at the top of [Models]. The one rule worth stating: nothing in here
 * throws. A push arrives from a server this app does not control, on a version
 * of the SDK that may be older than the campaign that sent it, and a parser
 * that fails on an unfamiliar field would turn a new template into silence on
 * every handset that has not updated.
 */
object PushPayload {

    /** The data key the server puts the whole template under. */
    const val DATA_KEY = "notify"

    /**
     * Parse an FCM data map, or return null when there is nothing rich in it.
     *
     * Null is the ordinary case, not an error: a plain notification carries no
     * `notify` key at all, and the app's own messaging service should show it
     * the way it always did.
     */
    fun from(data: Map<String, String>): RichPush? {
        val raw = data[DATA_KEY] ?: return null
        return try {
            parse(JSONObject(raw))
        } catch (e: Exception) {
            null
        }
    }

    internal fun parse(json: JSONObject): RichPush? {
        val title = json.stringOrNull("title") ?: return null
        val token = json.stringOrNull("token") ?: return null
        return RichPush(
            version = json.optInt("v", 1),
            kind = PushKind.from(json.stringOrNull("kind")),
            title = title,
            body = json.stringOr("body", ""),
            token = token,
            url = json.stringOrNull("url"),
            image = json.stringOrNull("image"),
            icon = json.stringOrNull("icon"),
            summary = json.stringOrNull("summary"),
            actions = json.array("actions") { item ->
                val id = item.stringOrNull("id") ?: return@array null
                PushAction(id, item.stringOr("label", id), item.stringOrNull("url"))
            },
            endsAt = if (json.has("endsAt")) json.optLong("endsAt") else null,
            afterTitle = json.stringOrNull("afterTitle"),
            afterBody = json.stringOrNull("afterBody"),
            slides = json.array("slides") { item ->
                val image = item.stringOrNull("imageUrl") ?: return@array null
                PushSlide(image, item.stringOrNull("caption"), item.stringOrNull("url"))
            },
            autoRotate = json.optBoolean("autoRotate", false),
            rotateSeconds = json.optInt("rotateSeconds", 4),
            stars = json.optInt("stars", 5),
            placeholder = json.stringOrNull("placeholder"),
            submitLabel = json.stringOrNull("submitLabel"),
            eventName = json.stringOrNull("eventName"),
            thanksTitle = json.stringOrNull("thanksTitle"),
            thanksBody = json.stringOrNull("thanksBody"),
            products = json.array("products") { item ->
                val id = item.stringOrNull("id") ?: return@array null
                PushProduct(
                    id,
                    item.stringOrNull("title"),
                    item.stringOrNull("price"),
                    item.stringOrNull("imageUrl"),
                    item.stringOrNull("url")
                )
            }
        )
    }

    /** Back to a string, for handing the payload to the receiver in an Intent. */
    fun encode(push: RichPush): String {
        val json = JSONObject()
        json.put("v", push.version)
        json.put("kind", push.kind.wire)
        json.put("title", push.title)
        json.put("body", push.body)
        json.put("token", push.token)
        push.url?.let { json.put("url", it) }
        push.image?.let { json.put("image", it) }
        push.icon?.let { json.put("icon", it) }
        push.summary?.let { json.put("summary", it) }
        push.endsAt?.let { json.put("endsAt", it) }
        push.afterTitle?.let { json.put("afterTitle", it) }
        push.afterBody?.let { json.put("afterBody", it) }
        push.eventName?.let { json.put("eventName", it) }
        push.thanksTitle?.let { json.put("thanksTitle", it) }
        push.thanksBody?.let { json.put("thanksBody", it) }
        push.channelId?.let { json.put("channelId", it) }
        json.put("stars", push.stars)
        json.put("autoRotate", push.autoRotate)
        json.put("rotateSeconds", push.rotateSeconds)
        if (push.actions.isNotEmpty()) {
            val arr = JSONArray()
            for (a in push.actions) {
                val o = JSONObject().put("id", a.id).put("label", a.label)
                a.url?.let { o.put("url", it) }
                arr.put(o)
            }
            json.put("actions", arr)
        }
        if (push.slides.isNotEmpty()) {
            val arr = JSONArray()
            for (s in push.slides) {
                val o = JSONObject().put("imageUrl", s.imageUrl)
                s.caption?.let { o.put("caption", it) }
                s.url?.let { o.put("url", it) }
                arr.put(o)
            }
            json.put("slides", arr)
        }
        if (push.products.isNotEmpty()) {
            val arr = JSONArray()
            for (p in push.products) {
                val o = JSONObject().put("id", p.id)
                p.title?.let { o.put("title", it) }
                p.price?.let { o.put("price", it) }
                p.imageUrl?.let { o.put("imageUrl", it) }
                p.url?.let { o.put("url", it) }
                arr.put(o)
            }
            json.put("products", arr)
        }
        return json.toString()
    }

    fun decode(raw: String?): RichPush? {
        if (raw.isNullOrBlank()) return null
        return try {
            parse(JSONObject(raw))
        } catch (e: Exception) {
            null
        }
    }

    private fun <T> JSONObject.array(name: String, map: (JSONObject) -> T?): List<T> {
        val arr = optJSONArray(name) ?: return emptyList()
        val out = ArrayList<T>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            map(item)?.let { out.add(it) }
        }
        return out
    }
}
