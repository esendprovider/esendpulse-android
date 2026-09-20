package com.esendpulse.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Android SDK.
 *
 * Same surface as the browser and iOS SDKs — track, identify, push token,
 * tray, in-app messages — with the differences a phone forces:
 *
 * - **The queue survives the process.** A browser can flush on `pagehide`; an
 *   app is killed without warning, and an event that only existed in memory is
 *   an event that never happened. Pending events are written to disk and
 *   picked up on the next launch.
 * - **A session is an app session.** The per-session frequency counters reset
 *   when the app comes back to the foreground after long enough to count as a
 *   new visit, not when a tab is closed.
 * - **The moments are the app's.** There is no scroll depth here; a launch, a
 *   screen and an event are what the SDK is told about.
 *
 * Threading: one worker thread owns the queue, the identity and the in-app
 * decisions, so every public method is safe to call from anywhere — which on
 * Android it will be. HTTP runs on its own small pool ([Transport]), and the
 * only thing that touches the UI is [InAppPresenter], on the main looper.
 */
class EsendPulse private constructor(context: Context, private val config: Config) {

    // ---- Setting up ----------------------------------------------------

    class Config @JvmOverloads constructor(
        /** Publishable key (`pk_...`). A secret key must never ship in an app. */
        val key: String,
        /** API base, e.g. `https://api.yourapp.com`. */
        host: String,
        val flushIntervalMillis: Long = 5_000L,
        maxBatchSize: Int = 20,
        /**
         * Time in the background after which the next foreground counts as a
         * new session. Thirty minutes is the usual analytics convention and
         * the one the frequency caps are written against.
         */
        val sessionTimeoutMillis: Long = 30 * 60 * 1000L,
        /**
         * Pull notifications: fetch the app pushes this handset missed when
         * the app comes to the foreground, and on a system job every
         * [pullIntervalMinutes] (fifteen at the least) while it is away. For
         * the OEM builds that stop FCM from waking the app — see [PushPull].
         */
        val pullNotifications: Boolean = false,
        val pullIntervalMinutes: Int = 60
    ) {
        val host: String = host.trimEnd('/')
        val maxBatchSize: Int = maxBatchSize.coerceIn(1, 100)
    }

    companion object {
        private const val TAG = "EsendPulse"

        @Volatile
        private var instance: EsendPulse? = null

        /** Receivers ke liye: start hua ho to client, warna null (thanda process). */
        internal val instanceOrNull: EsendPulse? get() = instance

        /**
         * The client [start] made.
         *
         * Throws rather than returning null if nothing has started it: the
         * mistake is always the same one — a call before `Application.onCreate`
         * ran — and a message naming it beats a null-pointer stack trace
         * pointing at the call site.
         */
        @JvmStatic
        val shared: EsendPulse
            get() = instance
                ?: error("[esendpulse] EsendPulse.start(context, config) has not been called yet")

        /**
         * Tell the platform what somebody did to a rich notification.
         *
         * Static, and tolerant of the SDK not having been started, because a
         * broadcast receiver can run in a process that was created for the tap
         * itself — `Application.onCreate` has not necessarily happened, and a
         * rating that is thrown away because the SDK was not warmed up yet is
         * the one rating the person actually bothered to give.
         *
         * Fire-and-forget on purpose: the endpoint is idempotent per token and
         * kind, so a lost report costs one rating and a retried one costs
         * nothing (`apps/api/src/routes/tracking.ts`).
         */
        @JvmStatic
        internal fun reportPushResponse(
            token: String,
            kind: String,
            value: String?,
            eventName: String?
        ) {
            val host = instance?.config?.host ?: pushReportHost ?: return
            val body = JSONObject()
                .put("token", token)
                .put("kind", kind)
            if (value != null) body.put("value", value)
            if (eventName != null) body.put("event", eventName)

            Thread {
                try {
                    val connection = java.net.URL("$host/t/p").openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                    connection.inputStream.use { it.readBytes() }
                } catch (e: Exception) {
                    // Nothing to do about it and nothing to show the person.
                }
            }.apply { isDaemon = true }.start()
        }

        /**
         * Where to report from a cold process.
         *
         * Written by [start] and read when the SDK has not been started yet.
         * A plain field rather than something persisted: it is set on every
         * launch, and a notification arriving before the app has ever run is
         * not a case that exists — the token came from a registration the app
         * had to be running to make.
         */
        @Volatile
        private var pushReportHost: String? = null

        @JvmStatic
        fun start(context: Context, config: Config): EsendPulse {
            require(!config.key.startsWith("sk_")) {
                "[esendpulse] a secret key must never ship in an app — use the publishable (pk_) key"
            }
            pushReportHost = config.host
            return EsendPulse(context.applicationContext, config).also { instance = it }.also { client ->
                val app = context.applicationContext
                if (config.pullNotifications) {
                    Store(app).pullEndpoint = config.host to config.key
                    PushPull.schedule(app, config.pullIntervalMinutes)
                    client.pullPendingPushes()
                } else {
                    Store(app).pullEndpoint = null
                    PushPull.cancel(app)
                }
            }
        }
    }

    /** Set this to draw messages yourself. See [EsendPulseInAppListener]. */
    @Volatile
    var inAppListener: EsendPulseInAppListener? = null

    private val appContext: Context = context.applicationContext
    private val store = Store(context)
    private val worker = Executors.newSingleThreadScheduledExecutor(daemonThreads("notify-worker"))
    private val network = Executors.newFixedThreadPool(2, daemonThreads("notify-net"))
    private val transport = Transport(config.key, config.host, network)
    private val main = Handler(Looper.getMainLooper())

    /** Worker thread only. */
    private val pending = ArrayList<QueuedEvent>()
    private var inAppCache: List<InAppMessage>? = null
    private val scheduledInApp = HashSet<String>()

    /** Main thread only. */
    private var currentActivity: WeakReference<Activity>? = null
    private var startedActivities = 0

    /** Sent once per process — see `track`. */
    @Volatile
    private var contextSent = false

    /**
     * What this device is, in the property names the platform stores.
     *
     * `$timezone` is what a country is derived from when no CDN stamped one on
     * the request, which on a native app is always: these calls do not go
     * through anybody's edge.
     */
    private val deviceContextValues: Map<String, Any?> = buildMap {
        put("\$platform", "android")
        put("\$os", "Android")
        put("\$os_version", Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())
        // "Pixel 8" rather than "Google Pixel 8" when the manufacturer is
        // already in the model, which is how most of them are written.
        val model = Build.MODEL ?: ""
        val make = Build.MANUFACTURER ?: ""
        put("\$device", if (model.startsWith(make, ignoreCase = true)) model else "$make $model".trim())
        put("\$timezone", TimeZone.getDefault().id)
        put("\$locale", Locale.getDefault().toLanguageTag())
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName?.let { put("\$app_version", it) }
        } catch (e: Exception) {
            // An app that cannot read its own package still tracks events.
            Log.w(TAG, "app version unavailable: ${e.message}")
        }
    }

    private fun deviceContext(): Map<String, Any?> = deviceContextValues

    init {
        worker.execute {
            pending.addAll(store.loadQueue())
            store.startSessionIfNeeded(config.sessionTimeoutMillis)
        }
        worker.scheduleWithFixedDelay(
            { flushOnWorker() },
            config.flushIntervalMillis,
            config.flushIntervalMillis,
            TimeUnit.MILLISECONDS
        )

        val application = context as? Application
        if (application != null) {
            application.registerActivityLifecycleCallbacks(lifecycleCallbacks())
        } else {
            // Everything except sessions and drawing still works, so this is a
            // warning rather than a throw — but it is worth saying out loud,
            // because the symptom otherwise is in-app messages that never
            // appear and per-session caps that never reset.
            Log.w(TAG, "started with a context that is not an Application: " +
                "sessions and in-app presentation are disabled")
        }
    }

    // ---- Identity ------------------------------------------------------

    val anonymousId: String get() = store.anonymousId
    val externalId: String? get() = store.externalId

    /** After sign-in. The anonymous history merges into this person server-side. */
    @JvmOverloads
    fun identify(externalId: String, traits: Map<String, Any?> = emptyMap()) {
        store.externalId = externalId
        worker.execute {
            // The cached in-app messages belonged to whoever was here before.
            inAppCache = null
            scheduledInApp.clear()
        }
        track("\$identify", traits)
    }

    /** After sign-out. A new anonymous person starts here. */
    fun reset() {
        worker.execute {
            store.reset()
            inAppCache = null
            scheduledInApp.clear()
        }
    }

    // ---- Events --------------------------------------------------------

    @JvmOverloads
    fun track(name: String, properties: Map<String, Any?> = emptyMap()) {
        // The identity is read here, on the caller's thread, not on the worker:
        // `identify(...)` followed by `track(...)` must produce an event that
        // carries the new identity, and hopping threads first would leave that
        // to a race.
        //
        // Device context rides on the first event of the process. There is no
        // User-Agent on these requests for the server to read, so if the app
        // does not say what it is running on, nobody knows — and once per
        // launch is enough for facts that cannot change while it runs.
        val merged = if (contextSent) properties else deviceContext() + properties
        contextSent = true
        val event = QueuedEvent(
            eventId = UUID.randomUUID().toString(),
            name = name,
            externalId = store.externalId,
            anonymousId = store.anonymousId,
            timestamp = Iso8601.format(Date()),
            properties = merged.toJson()
        )
        worker.execute {
            pending.add(event)
            store.saveQueue(pending)
            if (pending.size >= config.maxBatchSize) flushOnWorker()
        }
    }

    /** Send what is queued. Safe to call at any time; a no-op when empty. */
    @JvmOverloads
    fun flush(completion: (() -> Unit)? = null) {
        worker.execute { flushOnWorker(completion) }
    }

    private fun flushOnWorker(completion: (() -> Unit)? = null) {
        if (pending.isEmpty()) {
            completion?.invoke()
            return
        }
        val batch = ArrayList(pending.take(100))
        repeat(batch.size) { pending.removeAt(0) }
        store.saveQueue(pending)

        val body = JSONObject().put(
            "events",
            JSONArray().apply { batch.forEach { put(it.toJson()) } }
        )
        transport.post("/v1/events", body) { result ->
            worker.execute {
                val error = result.exceptionOrNull()
                if (error != null && error.isWorthRetrying) {
                    // Back on the front of the queue, and back on disk: a
                    // transient failure must not cost the events. A 4xx is our
                    // own bug and is dropped, because retrying it forever
                    // would block everything behind it.
                    pending.addAll(0, batch)
                    store.saveQueue(pending)
                }
                completion?.invoke()
            }
        }
    }

    // ---- The app's own lifecycle ---------------------------------------

    /**
     * Foreground and background, counted from the activities that are started.
     *
     * `ProcessLifecycleOwner` would say this in one line and cost a dependency
     * on androidx.lifecycle; this SDK has none, and the counter is the thing
     * that library does anyway.
     *
     * A configuration change (a rotation) takes the count to zero between the
     * old activity stopping and the new one starting, so it reads as a moment
     * in the background. That costs a flush, which is harmless, and cannot
     * start a new session — a session needs [Config.sessionTimeoutMillis] away,
     * not a millisecond.
     */
    private fun lifecycleCallbacks() = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {}

        override fun onActivityStarted(activity: Activity) {
            startedActivities += 1
            if (startedActivities == 1) {
                if (config.pullNotifications) pullPendingPushes()
                worker.execute {
                    if (store.startSessionIfNeeded(config.sessionTimeoutMillis)) {
                        // A new session means the per-session counters start
                        // again, and the messages may well have changed while
                        // the app was away.
                        inAppCache = null
                        scheduledInApp.clear()
                    }
                }
            }
        }

        override fun onActivityResumed(activity: Activity) {
            currentActivity = WeakReference(activity)
        }

        override fun onActivityPaused(activity: Activity) {}

        override fun onActivityStopped(activity: Activity) {
            startedActivities = maxOf(0, startedActivities - 1)
            if (startedActivities == 0) {
                // Last chance before the process may be killed.
                flush()
                worker.execute { store.markBackgrounded() }
            }
        }

        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}

        override fun onActivityDestroyed(activity: Activity) {
            InAppPresenter.dismissIfHostedBy(activity)
            if (currentActivity?.get() === activity) currentActivity = null
        }
    }

    // ---- Push ----------------------------------------------------------

    /**
     * Register the device's FCM token.
     *
     * The SDK depends on no push library of its own — the app that already has
     * Firebase Messaging hands the token over.
     */
    @JvmOverloads
    fun registerPushToken(token: String, completion: ((Boolean) -> Unit)? = null) {
        registerToken(token, "fcm", completion)
    }

    /**
     * Register the device's MiPush registration id.
     *
     * Call it alongside `registerPushToken` rather than instead of it. A
     * handset that has both is sent to over Firebase as usual, and MiPush is
     * used only if nothing ever confirms that the notification was drawn —
     * which on a Chinese ROM is a real and frequent outcome. A handset that has
     * only this one is sent to over MiPush directly.
     *
     * ```kotlin
     * MiPushClient.registerPush(context, appId, appKey)
     * // in your PushMessageReceiver:
     * override fun onReceiveRegisterResult(context: Context, message: MiPushCommandMessage) {
     *     EsendPulse.shared.registerMiPushToken(message.commandArguments.first())
     * }
     * ```
     *
     * Messages arrive through your `PushMessageReceiver`; hand a pass-through
     * message's payload to `EsendPulsePush.handle(context, data)` exactly as you
     * do for Firebase. A copy that arrives twice replaces itself in the tray
     * rather than stacking, because the notification id comes from the
     * message's own token.
     */
    @JvmOverloads
    fun registerMiPushToken(registrationId: String, completion: ((Boolean) -> Unit)? = null) {
        registerToken(registrationId, "xiaomi", completion)
    }

    /**
     * Register the device's HMS push token.
     *
     * On a Huawei handset with no Google Play Services this is the only way in,
     * and there is nothing to register alongside it. On one that has both, it
     * behaves exactly as MiPush does above.
     *
     * ```kotlin
     * val token = HmsInstanceId.getInstance(context).getToken(appId, "HCM")
     * EsendPulse.shared.registerHuaweiToken(token)
     * ```
     */
    @JvmOverloads
    fun registerHuaweiToken(token: String, completion: ((Boolean) -> Unit)? = null) {
        registerToken(token, "huawei", completion)
    }

    private fun registerToken(token: String, kind: String, completion: ((Boolean) -> Unit)?) {
        val body = identityBody()
            .put("kind", kind)
            .put("subscription", JSONObject().put("endpoint", token).put("keys", JSONObject()))
        transport.post("/v1/push/subscriptions", body) { completion?.invoke(it.isSuccess) }
    }

    // ---- App inbox -----------------------------------------------------

    @JvmOverloads
    fun inbox(
        limit: Int = 50,
        before: Date? = null,
        completion: (Result<InboxPage>) -> Unit
    ) {
        val query = identityQuery().toMutableMap()
        query["limit"] = limit.toString()
        // Paging is by the oldest card already held, not by an offset: the
        // tray grows at the top while somebody is reading it.
        if (before != null) query["before"] = Iso8601.format(before)

        transport.getJson("/v1/inbox", query) { result ->
            completion(
                result.map { json ->
                    val array = json.optJSONArray("messages") ?: JSONArray()
                    InboxPage(
                        messages = (0 until array.length()).mapNotNull { index ->
                            array.optJSONObject(index)?.let(InboxMessage::from)
                        },
                        unread = json.optInt("unread", 0)
                    )
                }
            )
        }
    }

    /** Mark cards read — or the whole tray when [ids] is null. */
    @JvmOverloads
    fun markInboxRead(ids: List<String>? = null, completion: ((Boolean) -> Unit)? = null) {
        val body = identityBody()
        if (ids != null && ids.isNotEmpty()) {
            body.put("ids", JSONArray().apply { ids.forEach { put(it) } })
        } else {
            body.put("all", true)
        }
        transport.post("/v1/inbox/read", body) { completion?.invoke(it.isSuccess) }
    }

    @JvmOverloads
    fun clickInboxMessage(id: String, completion: ((Boolean) -> Unit)? = null) {
        transport.post("/v1/inbox/click", identityBody().put("id", id)) {
            completion?.invoke(it.isSuccess)
        }
    }

    /**
     * Clear a card out of the tray. The delivery it came from is untouched, so
     * campaign statistics do not shrink because somebody tidied up.
     */
    @JvmOverloads
    fun dismissInboxMessage(id: String, completion: ((Boolean) -> Unit)? = null) {
        transport.post("/v1/inbox/dismiss", identityBody().put("id", id)) {
            completion?.invoke(it.isSuccess)
        }
    }

    // ---- In-app messages -----------------------------------------------

    /**
     * Fetch everything this person is eligible for and hold it.
     *
     * Called once at launch. Everything after that is answered from memory,
     * because asking the server as each screen opens puts a network round trip
     * between a tap and the message — which people read as the app hesitating.
     */
    @JvmOverloads
    fun syncInAppMessages(completion: ((Int) -> Unit)? = null) {
        transport.getJson("/v1/inapp", identityQuery()) { result ->
            worker.execute {
                val array = result.getOrNull()?.optJSONArray("messages") ?: JSONArray()
                val messages = (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let(InAppMessage::from)
                }
                inAppCache = messages
                completion?.invoke(messages.size)
            }
        }
    }

    /** The app came to the front. */
    fun appLaunched() = inAppMoment(InAppMoment(InAppTrigger.APP_LAUNCH))

    /** A screen opened. Call it with the same name the campaign uses. */
    fun screenViewed(name: String) =
        inAppMoment(InAppMoment(InAppTrigger.SCREEN, screenName = name))

    /** Track an event AND offer it as an in-app moment — the common case. */
    @JvmOverloads
    fun trackAndTrigger(name: String, properties: Map<String, Any?> = emptyMap()) {
        track(name, properties)
        inAppMoment(InAppMoment(InAppTrigger.EVENT, eventName = name))
    }

    /** Tell the SDK a moment happened, and show whatever belongs to it. */
    fun inAppMoment(moment: InAppMoment) {
        worker.execute {
            val cache = inAppCache
            if (cache.isNullOrEmpty()) return@execute
            val today = inAppDayKey()

            // One at a time. Two overlays on a phone is not two messages, it
            // is a screen nobody can leave.
            val message = cache.firstOrNull { candidate ->
                !scheduledInApp.contains(candidate.id) &&
                    inAppIsEligible(candidate.frequency, store.seen(candidate.id), today) &&
                    inAppTriggerFired(
                        candidate.trigger,
                        moment.copy(secondsSinceMoment = candidate.trigger.delaySeconds)
                    )
            } ?: return@execute

            scheduledInApp.add(message.id)
            worker.schedule({
                // Re-checked at showing time, not only at scheduling time:
                // during a thirty-second wait the person may have moved on, or
                // another message may have taken the screen.
                if (!inAppIsEligible(message.frequency, store.seen(message.id), inAppDayKey())) {
                    scheduledInApp.remove(message.id)
                    return@schedule
                }
                present(message)
            }, message.trigger.delaySeconds.toLong(), TimeUnit.SECONDS)
        }
    }

    private fun present(message: InAppMessage) {
        main.post {
            inAppListener?.let { listener ->
                if (!listener.shouldPresent(message)) return@post
            }
            val activity = currentActivity?.get()
            if (activity == null || activity.isFinishing) {
                // Nothing on screen to draw on — the app went away between the
                // moment and the delay expiring. Forget that it was scheduled
                // so the next moment can offer it again.
                worker.execute { scheduledInApp.remove(message.id) }
                return@post
            }
            InAppPresenter.present(activity, message) { outcome, value ->
                when (outcome) {
                    InAppPresenter.Outcome.SHOWN -> inAppShown(message.id)
                    InAppPresenter.Outcome.CLICKED -> {
                        inAppClicked(message.id, value)
                        inAppListener?.onClick(message)
                    }
                    InAppPresenter.Outcome.DISMISSED -> inAppDismissed(message.id)
                }
            }
        }
    }

    // ---- In-app reporting (public, for apps that draw their own) --------

    fun inAppShown(id: String) {
        worker.execute {
            store.remember(id) { seen ->
                val today = inAppDayKey()
                seen.copy(
                    session = seen.session + 1,
                    lifetime = seen.lifetime + 1,
                    // A tally from another day is not this day's tally.
                    // Resetting on read avoids needing a timer that only fires
                    // while the app happens to be open.
                    today = if (seen.day == today) seen.today + 1 else 1,
                    day = today
                )
            }
        }
        report("shown", id)
    }

    /**
     * Pass `value` for what the person answered — a survey's score — and it
     * is kept on the message and recorded as an `inapp_response` event.
     */
    @JvmOverloads
    fun inAppClicked(id: String, value: String? = null) {
        worker.execute { store.remember(id) { it.copy(converted = true) } }
        report("click", id, value)
    }

    fun inAppDismissed(id: String) {
        worker.execute { store.remember(id) { it.copy(dismissed = true) } }
        report("dismiss", id)
    }

    private fun report(kind: String, id: String, value: String? = null) {
        val body = identityBody().put("id", id)
        value?.let { body.put("value", it.take(1100)) }
        transport.post("/v1/inapp/$kind", body) { }
    }

    // ---- Geofencing ----------------------------------------------------

    private val geofencing = Geofencing(context)

    /**
     * Dashboard ke Places par aana/jaana `\$geofence_entered` / `\$geofence_exited`
     * event banta hai.
     *
     * Pehle app location permission maange — `ACCESS_FINE_LOCATION`, aur app band
     * hone par bhi chahiye to `ACCESS_BACKGROUND_LOCATION` (Android 10+). SDK
     * khud nahi maangta. Permission na ho to `false` aur kuch chalu nahi hota.
     * Ek baar chalu kiya to app ke restart ke baad bhi chalta rehta hai.
     */
    /**
     * Fetch and draw any app push this handset missed. Called by the SDK on
     * every return to the foreground when [Config.pullNotifications] is on;
     * public so an app can ask at a moment of its own, such as after sign-in.
     */
    fun pullPendingPushes() = PushPull.pull(appContext, config.host, config.key)

    fun enableGeofencing(): Boolean = geofencing.enable(config.key, config.host)

    fun disableGeofencing() = geofencing.disable()

    val isGeofencingEnabled: Boolean get() = geofencing.enabled

        // ---- Plumbing ------------------------------------------------------

    private fun identityQuery(): Map<String, String> = buildMap {
        put("anonymous_id", store.anonymousId)
        store.externalId?.let { put("external_id", it) }
    }

    private fun identityBody(): JSONObject = JSONObject().apply {
        put("anonymous_id", store.anonymousId)
        store.externalId?.let { put("external_id", it) }
    }
}

/**
 * Daemon threads, so an SDK that is merely loaded never keeps a process alive.
 */
private fun daemonThreads(prefix: String): ThreadFactory {
    val counter = AtomicInteger(1)
    return ThreadFactory { runnable ->
        Thread(runnable, "$prefix-${counter.getAndIncrement()}").apply { isDaemon = true }
    }
}
