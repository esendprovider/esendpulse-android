package com.esendpulse.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Drawing a rich notification.
 *
 * The host app hands us the FCM data map and we do the rest:
 *
 * ```kotlin
 * class MyMessagingService : FirebaseMessagingService() {
 *     override fun onMessageReceived(message: RemoteMessage) {
 *         if (EsendPulsePush.handle(this, message.data)) return
 *         // …your own notifications, unchanged
 *     }
 * }
 * ```
 *
 * It returns false for anything that is not ours, so adding this line to an
 * app that already shows notifications cannot take any of them over.
 *
 * The interesting constraint is that a notification's layout is not drawn by
 * this process at all. Android renders it in the system UI, from a [RemoteViews]
 * description and a handful of `Bitmap`s that have to be **in the payload** —
 * there is no callback later, no lazy loading, no view that can fetch its own
 * image. So every picture is downloaded first, on a background thread, and the
 * notification is posted only once they are in hand. That is also why only the
 * visible carousel slide is fetched ([PushRules.imagesToFetch]): the reader is
 * waiting, and the other five are not on screen.
 *
 * No AndroidX. The framework's own [Notification.Builder] has done everything
 * needed here since API 24, which is this SDK's minimum, and an analytics
 * library that forces `androidx.core` into somebody's app to save itself two
 * `if (Build.VERSION…)` checks is making a bad trade for them.
 */
object EsendPulsePush {

    /** Used when the payload names no channel and the app created none. */
    const val DEFAULT_CHANNEL_ID = "esendpulse_default"
    private const val DEFAULT_CHANNEL_NAME = "Notifications"

    /**
     * What this channel was called before the product was renamed.
     *
     * A channel id is not a constant that can simply be edited. It is a row in
     * the person's own Settings screen: they may have muted it, silenced it, or
     * given it a different importance, and on API 26 and up the system — not
     * the app — owns those choices. Changing the id creates a SECOND channel at
     * default importance and leaves the first one standing in their settings
     * with a name from a product that no longer exists.
     *
     * So the old one is deleted when the new one is created, once, on the next
     * notification after an upgrade. The honest cost is stated rather than
     * hidden: somebody who had muted `notify_default` will hear the new channel
     * until they mute it again. The alternative — copying their importance over
     * — is not available: `NotificationChannel.getImportance()` reports what the
     * app asked for, not what the person chose, and re-asking for a lower
     * importance is the one direction the system allows an app to move, so a
     * person who had RAISED it would be quietly turned back down.
     */
    private const val LEGACY_CHANNEL_ID = "notify_default"

    internal const val EXTRA_PUSH = "com.esendpulse.sdk.PUSH"
    internal const val EXTRA_ACTION = "com.esendpulse.sdk.ACTION"
    internal const val EXTRA_SLIDE = "com.esendpulse.sdk.SLIDE"
    internal const val EXTRA_RATING = "com.esendpulse.sdk.RATING"
    internal const val KEY_REPLY = "com.esendpulse.sdk.REPLY"

    private val work = Executors.newSingleThreadExecutor { r ->
        Thread(r, "notify-push").apply { isDaemon = true }
    }

    /** Small icon, set once by the app. Android refuses to post without one. */
    @Volatile
    var smallIconResId: Int = android.R.drawable.ic_dialog_info

    /**
     * Show a notification for this message, or say it was not ours.
     *
     * The whole thing happens off the calling thread, because a
     * `FirebaseMessagingService` callback runs on a worker with a time budget
     * and downloading three images is not something to do inside it.
     */
    @JvmStatic
    fun handle(context: Context, data: Map<String, String>): Boolean {
        val push = PushPayload.from(data) ?: return false
        val app = context.applicationContext
        work.execute { post(app, push, slideIndex = 0, firstDraw = true) }
        return true
    }

    /**
     * Draw a notification the app pulled from the server because its FCM copy
     * never arrived — see [PushPull]. The same drawing as [handle], with the
     * same id, so a late FCM copy replaces it in the tray.
     */
    internal fun showPulled(context: Context, push: RichPush) {
        work.execute { post(context.applicationContext, push, slideIndex = 0, firstDraw = true) }
    }

    /** Re-post the same notification in a new state — a swiped carousel, a tapped star. */
    internal fun repost(context: Context, push: RichPush, slideIndex: Int, ratedStars: Int = 0) {
        work.execute { post(context.applicationContext, push, slideIndex, ratedStars) }
    }

    private fun post(
        context: Context,
        push: RichPush,
        slideIndex: Int,
        ratedStars: Int = 0,
        firstDraw: Boolean = false
    ) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channelId = ensureChannel(manager, push)
        // Remembered before drawing, so a pull that races this never draws twice.
        if (firstDraw) Store(context).markPushShown(push.token)

        val expired = PushRules.timerExpired(push.endsAt, System.currentTimeMillis())
        val (title, body) =
            if (expired) PushRules.expiredText(push)
            else if (ratedStars > 0) Pair(push.thanksTitle ?: push.title, push.thanksBody ?: push.body)
            else Pair(push.title, push.body)

        val images = HashMap<String, Bitmap>()
        for (url in PushRules.imagesToFetch(push, slideIndex)) {
            fetchBitmap(url)?.let { images[url] = it }
        }

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, channelId)
            else @Suppress("DEPRECATION") Notification.Builder(context)

        builder
            .setSmallIcon(smallIconResId)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, push, slideIndex))

        push.summary?.let { builder.setSubText(it) }
        push.icon?.let { images[it]?.let(builder::setLargeIcon) }

        when {
            // A rating that has been given, and a countdown that has run out,
            // are both "this notification is finished": the controls come off
            // and what is left is the message it ended on.
            ratedStars > 0 || expired -> Unit

            push.kind == PushKind.BIG_PICTURE -> {
                val bitmap = push.image?.let { images[it] }
                if (bitmap != null) {
                    builder.style = Notification.BigPictureStyle().bigPicture(bitmap)
                } else {
                    // The picture is the template, so a failed download falls
                    // back to the text rather than to an empty grey box.
                    builder.style = Notification.BigTextStyle().bigText(body)
                }
            }

            push.kind == PushKind.TIMER && push.endsAt != null -> {
                // The framework's own countdown: it ticks in the tray without
                // this process being alive, which nothing we could draw would.
                builder.setWhen(push.endsAt).setUsesChronometer(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    builder.setChronometerCountDown(true)
                }
            }

            push.kind == PushKind.INPUT -> {
                builder.addAction(replyAction(context, push))
            }

            push.kind == PushKind.CAROUSEL -> {
                carouselViews(context, push, slideIndex, images, title, body)?.let {
                    builder.setStyle(Notification.DecoratedCustomViewStyle())
                    builder.setCustomBigContentView(it)
                }
            }

            push.kind == PushKind.RATING -> {
                builder.setStyle(Notification.DecoratedCustomViewStyle())
                builder.setCustomBigContentView(ratingViews(context, push, title, body))
            }

            push.kind == PushKind.PRODUCT -> {
                builder.setStyle(Notification.DecoratedCustomViewStyle())
                builder.setCustomBigContentView(productViews(context, push, images, title))
            }

            else -> builder.style = Notification.BigTextStyle().bigText(body)
        }

        // Buttons come last so they sit under whatever style was chosen, and
        // are dropped once the notification has been answered.
        if (ratedStars == 0 && !expired) {
            for (action in push.actions.take(3)) {
                builder.addAction(
                    Notification.Action.Builder(null, action.label, actionIntent(context, push, action.id))
                        .build()
                )
            }
        }

        manager.notify(PushRules.notificationId(push.token), builder.build())

        /**
         * The one signal nobody else can give us: this handset drew it.
         *
         * FCM answering 200 only means Google accepted the message. Whether it
         * reached the person is decided afterwards by the handset — by Doze, by
         * a force-stopped app, and on a great many devices by an OEM that drops
         * background notifications on its own schedule. Reported here, after
         * `notify` returned, because before that line nothing has been shown.
         *
         * Only on the first draw. A swiped carousel and a tapped star re-post
         * the same notification, and counting those would turn one delivery
         * into five.
         */
        if (firstDraw) {
            EsendPulse.reportPushResponse(push.token, "impression", null, null)
        }
    }

    // ---- layouts -------------------------------------------------------

    private fun carouselViews(
        context: Context,
        push: RichPush,
        slideIndex: Int,
        images: Map<String, Bitmap>,
        title: String,
        body: String
    ): RemoteViews? {
        if (push.slides.isEmpty()) return null
        val views = RemoteViews(context.packageName, R.layout.esendpulse_carousel)
        views.setTextViewText(R.id.esendpulse_carousel_title, title)
        views.setTextViewText(R.id.esendpulse_carousel_body, body)

        val slide = push.slides.getOrNull(slideIndex) ?: push.slides[0]
        views.setTextViewText(R.id.esendpulse_carousel_caption, slide.caption ?: "")
        images[slide.imageUrl]?.let { views.setImageViewBitmap(R.id.esendpulse_carousel_image, it) }

        // One arrow per direction, each a broadcast that comes back through
        // [EsendPulsePushReceiver] and re-posts this notification one slide along.
        views.setOnClickPendingIntent(
            R.id.esendpulse_carousel_prev,
            slideIntent(context, push, PushRules.nextSlide(slideIndex, push.slides.size, false))
        )
        views.setOnClickPendingIntent(
            R.id.esendpulse_carousel_next,
            slideIntent(context, push, PushRules.nextSlide(slideIndex, push.slides.size, true))
        )
        return views
    }

    private fun ratingViews(
        context: Context,
        push: RichPush,
        title: String,
        body: String
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.esendpulse_rating)
        views.setTextViewText(R.id.esendpulse_rating_title, title)
        views.setTextViewText(R.id.esendpulse_rating_body, body)

        val stars = PushRules.starCount(push.stars)
        val ids = intArrayOf(
            R.id.esendpulse_star_1,
            R.id.esendpulse_star_2,
            R.id.esendpulse_star_3,
            R.id.esendpulse_star_4,
            R.id.esendpulse_star_5
        )
        for (i in ids.indices) {
            if (i < stars) {
                views.setViewVisibility(ids[i], android.view.View.VISIBLE)
                views.setOnClickPendingIntent(ids[i], ratingIntent(context, push, i + 1))
            } else {
                views.setViewVisibility(ids[i], android.view.View.GONE)
            }
        }
        return views
    }

    private fun productViews(
        context: Context,
        push: RichPush,
        images: Map<String, Bitmap>,
        title: String
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.esendpulse_products)
        views.setTextViewText(R.id.esendpulse_products_title, title)

        val slots = arrayOf(
            Triple(R.id.esendpulse_product_1, R.id.esendpulse_product_image_1, Pair(R.id.esendpulse_product_title_1, R.id.esendpulse_product_price_1)),
            Triple(R.id.esendpulse_product_2, R.id.esendpulse_product_image_2, Pair(R.id.esendpulse_product_title_2, R.id.esendpulse_product_price_2)),
            Triple(R.id.esendpulse_product_3, R.id.esendpulse_product_image_3, Pair(R.id.esendpulse_product_title_3, R.id.esendpulse_product_price_3))
        )
        for (i in slots.indices) {
            val (container, image, text) = slots[i]
            val product = push.products.getOrNull(i)
            if (product == null) {
                views.setViewVisibility(container, android.view.View.GONE)
                continue
            }
            views.setViewVisibility(container, android.view.View.VISIBLE)
            views.setTextViewText(text.first, product.title ?: product.id)
            views.setTextViewText(text.second, product.price ?: "")
            product.imageUrl?.let { url -> images[url]?.let { views.setImageViewBitmap(image, it) } }
            // Each product is its own tap target, because a carousel of three
            // things that all open the same page is three times as annoying as
            // one link.
            views.setOnClickPendingIntent(
                container,
                openIntent(context, push, 0, product.url ?: push.url)
            )
        }
        return views
    }

    private fun replyAction(context: Context, push: RichPush): Notification.Action {
        val remoteInput = android.app.RemoteInput.Builder(KEY_REPLY)
            .setLabel(push.placeholder ?: "Reply")
            .build()
        return Notification.Action.Builder(
            null,
            push.submitLabel ?: "Send",
            actionIntent(context, push, actionId = null, wantsReply = true)
        )
            .addRemoteInput(remoteInput)
            // Without this the tap opens the app instead of showing the field,
            // which is the whole point of the template.
            .build()
    }

    // ---- intents -------------------------------------------------------

    /**
     * Every pending intent needs its own request code.
     *
     * Two `PendingIntent`s that differ only in their extras are the *same*
     * pending intent to Android, so without this the previous arrow, the next
     * arrow and all five stars would all do whatever the first one did.
     */
    private fun requestCode(token: String, suffix: String): Int =
        ((token + suffix).hashCode() and 0x7fffffff)

    private fun flags(mutable: Boolean): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun receiverIntent(context: Context, push: RichPush): Intent =
        Intent(context, EsendPulsePushReceiver::class.java)
            .setPackage(context.packageName)
            .putExtra(EXTRA_PUSH, PushPayload.encode(push))

    private fun openIntent(
        context: Context,
        push: RichPush,
        slideIndex: Int,
        override: String? = null
    ): PendingIntent {
        val intent = receiverIntent(context, push)
            .setAction(EsendPulsePushReceiver.ACTION_OPEN)
            .putExtra(EXTRA_SLIDE, slideIndex)
        override?.let { intent.data = Uri.parse(it) }
        return PendingIntent.getBroadcast(
            context,
            requestCode(push.token, "open$slideIndex${override ?: ""}"),
            intent,
            flags(mutable = false)
        )
    }

    private fun slideIntent(context: Context, push: RichPush, slideIndex: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(push.token, "slide$slideIndex"),
            receiverIntent(context, push)
                .setAction(EsendPulsePushReceiver.ACTION_SLIDE)
                .putExtra(EXTRA_SLIDE, slideIndex),
            flags(mutable = false)
        )

    private fun ratingIntent(context: Context, push: RichPush, stars: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(push.token, "star$stars"),
            receiverIntent(context, push)
                .setAction(EsendPulsePushReceiver.ACTION_RATE)
                .putExtra(EXTRA_RATING, stars),
            flags(mutable = false)
        )

    private fun actionIntent(
        context: Context,
        push: RichPush,
        actionId: String?,
        wantsReply: Boolean = false
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(push.token, "action${actionId ?: "reply"}"),
            receiverIntent(context, push)
                .setAction(if (wantsReply) EsendPulsePushReceiver.ACTION_REPLY else EsendPulsePushReceiver.ACTION_BUTTON)
                .putExtra(EXTRA_ACTION, actionId),
            // A reply carries the typed text back in the intent, which the
            // system can only write into a mutable one.
            flags(mutable = wantsReply)
        )

    // ---- plumbing ------------------------------------------------------

    private fun ensureChannel(manager: NotificationManager, push: RichPush): String {
        val id = push.channelId ?: DEFAULT_CHANNEL_ID
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return id
        // Only ours is created here. A channel the app named is the app's to
        // define — its name and importance are things the person sees in
        // settings, and guessing them would put "esendpulse_default" in their face.
        if (id == DEFAULT_CHANNEL_ID && manager.getNotificationChannel(id) == null) {
            manager.createNotificationChannel(
                NotificationChannel(id, DEFAULT_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            )
            // Taken away in the same breath as the replacement is made, so an
            // upgraded app never shows two of our channels in the person's
            // settings. Harmless when it was never there.
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }
        return id
    }

    private fun fetchBitmap(url: String): Bitmap? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = true
        connection.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        // A picture that will not download is not a reason to show nothing:
        // every template here reads acceptably without its image.
        null
    }
}
