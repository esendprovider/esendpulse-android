package com.esendpulse.sdk

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * What the SDK remembers between launches.
 *
 * Two different lifetimes, kept apart on purpose:
 *
 * - **Identity and the frequency memory** live in [SharedPreferences]. They
 *   are small, they are read on every decision, and they must survive updates.
 * - **The pending event queue** lives in a file. It can be thousands of events
 *   after a spell offline, and `SharedPreferences` is parsed whole into memory
 *   on first touch and held there for the life of the process.
 *
 * Everything here is called from the SDK's single worker thread, so nothing
 * synchronizes. `apply()` rather than `commit()` for the same reason it always
 * is: the write lands, and the caller does not wait for the disk.
 */
internal class Store(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("com.esendpulse.sdk", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "notify-events.json")

    private object Key {
        const val ANONYMOUS_ID = "notify.anonymousId"
        const val EXTERNAL_ID = "notify.externalId"
        const val SEEN = "notify.inapp.seen"
        const val SESSION = "notify.session.startedAt"
        const val BACKGROUNDED = "notify.session.backgroundedAt"
        const val SHOWN_PUSH = "notify.push.shown"
        const val PULL_HOST = "notify.pull.host"
        const val PULL_KEY = "notify.pull.key"
    }

    init {
        if (prefs.getString(Key.ANONYMOUS_ID, null) == null) {
            prefs.edit().putString(Key.ANONYMOUS_ID, UUID.randomUUID().toString()).apply()
        }
    }

    // ---- Identity ------------------------------------------------------

    val anonymousId: String
        get() = prefs.getString(Key.ANONYMOUS_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(Key.ANONYMOUS_ID, it).apply()
        }

    var externalId: String?
        get() = prefs.getString(Key.EXTERNAL_ID, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(Key.EXTERNAL_ID) else putString(Key.EXTERNAL_ID, value)
            }.apply()
        }

    /** Sign-out: a new anonymous person, and none of the old one's memory. */
    fun reset() {
        prefs.edit()
            .remove(Key.EXTERNAL_ID)
            .putString(Key.ANONYMOUS_ID, UUID.randomUUID().toString())
            // The frequency memory goes too. It describes what a person has
            // seen, and this is a different person on the same handset.
            .remove(Key.SEEN)
            .apply()
    }

    // ---- Pushes already drawn ------------------------------------------

    /**
     * Tracking tokens of the notifications this handset has drawn, over FCM or
     * from a pull — so a pull never draws a second copy of one that arrived,
     * and a late FCM copy replaces rather than duplicates. Capped: the last
     * two hundred is a fortnight of the busiest campaign imaginable.
     */
    fun hasShownPush(token: String): Boolean = shownPushes().contains(token)

    fun markPushShown(token: String) {
        val all = shownPushes().toMutableList()
        all.remove(token)
        all.add(token)
        while (all.size > 200) all.removeAt(0)
        prefs.edit().putString(Key.SHOWN_PUSH, all.joinToString("\n")).apply()
    }

    private fun shownPushes(): List<String> =
        prefs.getString(Key.SHOWN_PUSH, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()

    /** Where a cold-started pull job talks to, remembered at start. */
    var pullEndpoint: Pair<String, String>?
        get() {
            val host = prefs.getString(Key.PULL_HOST, null) ?: return null
            val key = prefs.getString(Key.PULL_KEY, null) ?: return null
            return host to key
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) { remove(Key.PULL_HOST); remove(Key.PULL_KEY) }
                else { putString(Key.PULL_HOST, value.first); putString(Key.PULL_KEY, value.second) }
            }.apply()
        }

    // ---- Sessions ------------------------------------------------------

    fun markBackgrounded() {
        prefs.edit().putLong(Key.BACKGROUNDED, System.currentTimeMillis()).apply()
    }

    /** Starts a new session if the app has been away long enough, and says so. */
    fun startSessionIfNeeded(timeoutMillis: Long): Boolean {
        val away = System.currentTimeMillis() - prefs.getLong(Key.BACKGROUNDED, 0L)
        val hasSession = prefs.getLong(Key.SESSION, 0L) > 0L
        if (hasSession && away <= timeoutMillis) return false

        prefs.edit().putLong(Key.SESSION, System.currentTimeMillis()).apply()
        // Per-session counters are per session, so they start again here. The
        // daily and lifetime ones deliberately do not.
        writeSeen(seenAll().mapValues { (_, state) -> state.copy(session = 0, dismissed = false) })
        return true
    }

    // ---- In-app frequency memory ---------------------------------------

    fun seen(id: String): InAppSeenState = seenAll()[id] ?: InAppSeenState.NEVER

    fun remember(id: String, update: (InAppSeenState) -> InAppSeenState) {
        val all = seenAll().toMutableMap()
        all[id] = update(all[id] ?: InAppSeenState.NEVER)
        writeSeen(all)
    }

    private fun seenAll(): Map<String, InAppSeenState> {
        val raw = prefs.getString(Key.SEEN, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            buildMap {
                for (id in json.keys()) {
                    val row = json.optJSONObject(id) ?: continue
                    put(
                        id,
                        InAppSeenState(
                            session = row.optInt("session", 0),
                            today = row.optInt("today", 0),
                            day = row.optString("day", ""),
                            lifetime = row.optInt("lifetime", 0),
                            converted = row.optBoolean("converted", false),
                            dismissed = row.optBoolean("dismissed", false)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Unreadable memory means the caps start over — annoying once,
            // and better than an SDK that throws on somebody's launch path.
            emptyMap()
        }
    }

    private fun writeSeen(all: Map<String, InAppSeenState>) {
        val json = JSONObject()
        for ((id, state) in all) {
            json.put(
                id,
                JSONObject()
                    .put("session", state.session)
                    .put("today", state.today)
                    .put("day", state.day)
                    .put("lifetime", state.lifetime)
                    .put("converted", state.converted)
                    .put("dismissed", state.dismissed)
            )
        }
        prefs.edit().putString(Key.SEEN, json.toString()).apply()
    }

    // ---- The event queue ------------------------------------------------

    fun loadQueue(): MutableList<QueuedEvent> {
        if (!file.exists()) return mutableListOf()
        return try {
            val array = JSONArray(file.readText())
            val events = ArrayList<QueuedEvent>(array.length())
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { events.add(QueuedEvent.from(it)) }
            }
            events
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveQueue(events: List<QueuedEvent>) {
        // A cap, because the alternative is an app that was offline for a week
        // filling the disk. The oldest go first: a week-old tap matters less
        // than this morning's, and the server would drop them from journey
        // triggers anyway under the staleness rule.
        val capped = if (events.size > MAX_QUEUED) events.takeLast(MAX_QUEUED) else events
        val array = JSONArray()
        for (event in capped) array.put(event.toJson())
        try {
            // Written beside the real file and moved over it, so a process
            // killed mid-write loses the newest events rather than all of them.
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(file)) {
                file.writeText(array.toString())
                temp.delete()
            }
        } catch (_: Exception) {
            // A full disk must not take somebody's app down over analytics.
        }
    }

    private companion object {
        const val MAX_QUEUED = 5_000
    }
}
