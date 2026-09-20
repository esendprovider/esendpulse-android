package com.esendpulse.sdk

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Pull notifications — fetching the pushes this handset missed.
 *
 * Some Android builds (Xiaomi, Oppo, Vivo, Huawei among them) stop a
 * backgrounded app so thoroughly that an FCM data message never wakes it,
 * and the notification a campaign sent is simply never drawn. Nothing on the
 * server can tell: the vendor accepted the message.
 *
 * So the SDK asks. On every return to the foreground, and — when the app
 * opts in — on a periodic job the system runs even while the app sleeps, it
 * fetches the app pushes sent to this person in the last two days and draws
 * the ones it has not drawn yet, then tells the server which, so the push
 * health page can show how many arrived this way. "Drawn" is remembered by
 * tracking token ([Store.markPushShown]), the same id a late FCM copy would
 * replace, so nothing is ever shown twice.
 *
 * Zero dependencies, like the rest of this SDK: `JobScheduler` is in the
 * framework, and its fifteen-minute floor is the right cadence anyway.
 */
internal object PushPull {

    private const val JOB_ID = 0x6E5F5001
    private val work = Executors.newSingleThreadExecutor { r ->
        Thread(r, "notify-pull").apply { isDaemon = true }
    }

    /** Ask the system to run [EsendPulsePullJobService] on a schedule. */
    fun schedule(context: Context, intervalMinutes: Int) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, EsendPulsePullJobService::class.java))
            .setPeriodic(maxOf(15, intervalMinutes) * 60_000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(false)
            .build()
        runCatching { scheduler.schedule(job) }
    }

    fun cancel(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        runCatching { scheduler.cancel(JOB_ID) }
    }

    /**
     * Fetch, draw what is new, acknowledge. Off the calling thread; the
     * completion runs on the pull thread with how many were drawn.
     */
    fun pull(context: Context, host: String, key: String, done: ((Int) -> Unit)? = null) {
        val app = context.applicationContext
        work.execute {
            val store = Store(app)
            val transport = Transport(key, host, work)
            val query = HashMap<String, String>()
            query["anonymous_id"] = store.anonymousId
            store.externalId?.let { query["external_id"] = it }

            val latch = java.util.concurrent.CountDownLatch(1)
            var drawn = 0
            transport.getJson("/v1/push/pending", query) { result ->
                try {
                    val pushes = result.getOrNull()?.optJSONArray("pushes") ?: JSONArray()
                    val shown = ArrayList<String>()
                    for (i in 0 until pushes.length()) {
                        val item = pushes.optJSONObject(i) ?: continue
                        val notify = item.optJSONObject("notify") ?: continue
                        val push = PushPayload.parse(notify) ?: continue
                        if (store.hasShownPush(push.token)) continue
                        EsendPulsePush.showPulled(app, push)
                        item.stringOrNull("id")?.let { shown.add(it) }
                    }
                    drawn = shown.size
                    if (shown.isNotEmpty()) {
                        val body = JSONObject()
                        body.put("anonymous_id", store.anonymousId)
                        store.externalId?.let { body.put("external_id", it) }
                        body.put("ids", JSONArray(shown))
                        transport.post("/v1/push/pulled", body) { latch.countDown() }
                    } else {
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    latch.countDown()
                }
            }
            // Bounded: a job has a time budget, and a hung request must not spend it.
            latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
            done?.invoke(drawn)
        }
    }
}

/**
 * The periodic job. Runs in a process the system may have created just for
 * it — `Application.onCreate` has run, but the app may never have called
 * `EsendPulse.start` on this path — so it reads the host and key the last
 * start remembered rather than requiring a live client.
 */
class EsendPulsePullJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val endpoint = Store(applicationContext).pullEndpoint ?: return false
        PushPull.pull(applicationContext, endpoint.first, endpoint.second) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
