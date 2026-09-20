package com.esendpulse.sdk

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * What happens when somebody touches a rich notification.
 *
 * Every control in one — the arrows, the stars, the reply field, the buttons,
 * the notification itself — points a `PendingIntent` here rather than straight
 * at an activity. Three reasons, and the third is the one that matters:
 *
 *  1. A carousel arrow must not open the app. It re-posts the notification.
 *  2. A star must be recorded before anything else happens, and opening an
 *     activity first would race the report against the app's own start-up.
 *  3. A reply's text is delivered *in the intent*, and only a receiver gets to
 *     read it before the notification is dismissed.
 *
 * Reporting goes to `POST /t/p`, which takes the delivery's own token — so a
 * rating arrives attached to the campaign that asked for it rather than
 * floating free (`apps/api/src/routes/tracking.ts`).
 */
class EsendPulsePushReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_OPEN = "com.esendpulse.sdk.action.OPEN"
        const val ACTION_SLIDE = "com.esendpulse.sdk.action.SLIDE"
        const val ACTION_RATE = "com.esendpulse.sdk.action.RATE"
        const val ACTION_REPLY = "com.esendpulse.sdk.action.REPLY"
        const val ACTION_BUTTON = "com.esendpulse.sdk.action.BUTTON"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val push = PushPayload.decode(intent.getStringExtra(EsendPulsePush.EXTRA_PUSH)) ?: return
        val slide = intent.getIntExtra(EsendPulsePush.EXTRA_SLIDE, 0)

        when (intent.action) {
            ACTION_SLIDE -> {
                // Nothing is reported for a swipe. It is navigation inside a
                // notification, not engagement with the message, and counting
                // it would make a carousel look four times as effective as a
                // picture for no reason anybody could act on.
                EsendPulsePush.repost(context, push, slide)
            }

            ACTION_RATE -> {
                val stars = intent.getIntExtra(EsendPulsePush.EXTRA_RATING, 0)
                EsendPulse.reportPushResponse(push.token, "rating", stars.toString(), push.eventName)
                // The notification stays, showing the thank-you rather than
                // vanishing: a tap that makes something disappear leaves the
                // person unsure whether it registered.
                EsendPulsePush.repost(context, push, slide, ratedStars = stars)
            }

            ACTION_REPLY -> {
                val text = replyText(intent)
                EsendPulse.reportPushResponse(push.token, "reply", text, push.eventName)
                dismiss(context, push)
                showThanks(context, push)
            }

            ACTION_BUTTON -> {
                val actionId = intent.getStringExtra(EsendPulsePush.EXTRA_ACTION)
                EsendPulse.reportPushResponse(push.token, "action", actionId ?: "", null)
                open(context, PushRules.linkFor(push, actionId, slide))
                dismiss(context, push)
            }

            else -> {
                EsendPulse.reportPushResponse(push.token, "open", null, null)
                val override = intent.data?.toString()
                open(context, override ?: PushRules.linkFor(push, null, slide))
                dismiss(context, push)
            }
        }
    }

    private fun replyText(intent: Intent): String {
        val results = android.app.RemoteInput.getResultsFromIntent(intent) ?: return ""
        return results.getCharSequence(EsendPulsePush.KEY_REPLY)?.toString() ?: ""
    }

    /**
     * A one-line notification saying the reply was received.
     *
     * Posted under the same id, so it replaces rather than stacks. Without it
     * the notification simply disappears when the person presses send, which
     * every reader reads as "it did not work".
     */
    private fun showThanks(context: Context, push: RichPush) {
        val title = push.thanksTitle ?: return
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                android.app.Notification.Builder(context, push.channelId ?: EsendPulsePush.DEFAULT_CHANNEL_ID)
            else @Suppress("DEPRECATION") android.app.Notification.Builder(context)
        manager.notify(
            PushRules.notificationId(push.token),
            builder
                .setSmallIcon(EsendPulsePush.smallIconResId)
                .setContentTitle(title)
                .setContentText(push.thanksBody ?: "")
                .setAutoCancel(true)
                .build()
        )
    }

    private fun dismiss(context: Context, push: RichPush) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(PushRules.notificationId(push.token))
    }

    private fun open(context: Context, url: String?) {
        if (url.isNullOrBlank()) {
            // No link means "open the app", which is what a person expects
            // from tapping a notification that has nowhere else to go.
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(it) }
            }
            return
        }
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
