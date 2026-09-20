package com.esendpulse.sdk

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import android.net.Uri
import java.lang.ref.WeakReference
import java.net.URL
import java.util.concurrent.Executors

/**
 * What the SDK draws when the host app has not taken over.
 *
 * The same bargain the browser and iOS renderers make: plain, self-contained,
 * and entirely optional. An app with its own design system implements
 * [EsendPulseInAppListener], returns false from [EsendPulseInAppListener.shouldPresent]
 * and draws the message itself.
 *
 * The message is a view added to the current activity's decor view, not a
 * system overlay window. iOS puts it in a `UIWindow` of its own because a view
 * attached to a view controller disappears when that controller is dismissed;
 * the Android equivalent of that trick is `TYPE_APPLICATION_OVERLAY`, which
 * needs the `SYSTEM_ALERT_WINDOW` permission — a trip to a Settings screen that
 * no analytics SDK has any business asking for. The decor view is the honest
 * answer, and what it costs is handled instead: the activity going away takes
 * the message with it ([dismissIfHostedBy]), and the message is offered again
 * at the next moment rather than counted as shown.
 *
 * Every view is built in code. A library that ships layout XML also ships
 * resource ids and a theme its host has to not collide with.
 */
internal object InAppPresenter {

    enum class Outcome { SHOWN, CLICKED, DISMISSED }

    private val main = Handler(Looper.getMainLooper())
    private val images = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "notify-image").apply { isDaemon = true }
    }

    private var host: WeakReference<Activity>? = null
    private var container: View? = null
    /** The outcome, and for a click from a custom page what it answered (a survey's score). */
    private var onOutcome: ((Outcome, String?) -> Unit)? = null

    /** The brand purple the other surfaces use for a primary button. */
    private const val ACCENT = 0xFF8C5EA6.toInt()
    private const val SCRIM = 0x73000000

    fun present(activity: Activity, message: InAppMessage, onOutcome: (Outcome, String?) -> Unit) {
        if (container != null) return // one at a time
        // The decor view, not `android.R.id.content`: the content frame stops
        // below an action bar, and a scrim that dims everything except the bar
        // at the top reads as a rendering fault rather than as a dimmed app.
        val root = activity.window?.decorView as? ViewGroup ?: return

        this.host = WeakReference(activity)
        this.onOutcome = onOutcome

        val view = build(activity, message)
        root.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        container = view

        // So the hardware/gesture back closes it rather than the screen behind.
        // Not for a floating video: it is not in the way, and back should keep
        // doing what the app expects.
        if (!message.layout.isFloating) {
            view.isFocusableInTouchMode = true
            view.requestFocus()
            view.setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    if (message.dismissible) finish(Outcome.DISMISSED)
                    true
                } else {
                    false
                }
            }
        }

        onOutcome(Outcome.SHOWN, null)
    }

    /** The activity holding the message is going away; take the message with it. */
    fun dismissIfHostedBy(activity: Activity) {
        if (host?.get() !== activity) return
        // No outcome reported: nothing was answered, and counting a rotation
        // or a back-out as a dismissal would spend a cap the person never used.
        detach()
    }

    private fun finish(outcome: Outcome, value: String? = null) {
        // SHOWN was reported at presentation; only the two endings land here.
        val callback = onOutcome
        onOutcome = null
        callback?.invoke(outcome, value)

        val view = container ?: return
        view.animate().alpha(0f).setDuration(150).withEndAction { detach() }.start()
    }

    private fun detach() {
        onOutcome = null
        container?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            (view.findViewWithTag<View>(WEB_VIEW_TAG) as? WebView)?.destroy()
        }
        container = null
        host = null
    }

    // ---- The view ------------------------------------------------------

    private const val WEB_VIEW_TAG = "notify.webview"

    private fun build(activity: Activity, message: InAppMessage): View {
        if (message.layout.isFloating) return buildPip(activity, message)
        val scrim = FrameLayout(activity)
        // A bar does not dim the app behind it — it is a strip of information,
        // not a demand for attention.
        scrim.setBackgroundColor(if (message.layout.isBanner) Color.TRANSPARENT else SCRIM)
        // Above an app bar's own elevation. Children of the decor view are
        // drawn by elevation before order, so a flat overlay slides underneath
        // a toolbar that has any — which reads as the message being clipped.
        scrim.elevation = dp(activity, 32f)
        scrim.outlineProvider = null

        val card = FrameLayout(activity)
        card.background = GradientDrawable().apply {
            setColor(themeColor(activity, android.R.attr.colorBackground, Color.WHITE))
            cornerRadius = when {
                message.layout == InAppLayout.COVER -> 0f
                message.layout == InAppLayout.CUSTOM_HTML -> 0f
                message.layout.isBanner -> dp(activity, 12f)
                else -> dp(activity, 14f)
            }
        }
        card.clipToOutline = true
        scrim.addView(card, cardLayoutParams(activity, message))

        // A device whose WebView provider is disabled or mid-update throws from
        // the constructor. The words are still worth showing, and an analytics
        // SDK must never be the reason somebody's app dies.
        val content: View = if (message.layout == InAppLayout.CUSTOM_HTML) {
            runCatching { webView(activity, message) }.getOrElse { fixedLayout(activity, message) }
        } else {
            fixedLayout(activity, message)
        }
        card.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                if (message.layout == InAppLayout.CUSTOM_HTML || message.layout == InAppLayout.COVER) {
                    FrameLayout.LayoutParams.MATCH_PARENT
                } else {
                    FrameLayout.LayoutParams.WRAP_CONTENT
                }
            )
        )

        val close = if (message.dismissible) closeButton(activity, card) else null

        if (message.dismissible && !message.layout.isBanner) {
            scrim.setOnClickListener {
                // Only the backdrop: this listener is on the scrim, and the
                // card below swallows its own taps because it is clickable.
                finish(Outcome.DISMISSED)
            }
            card.isClickable = true
        }

        applyInsets(activity, scrim, card, content, close, message)
        return scrim
    }

    /**
     * Picture-in-picture: a 240dp card in a corner with a muted, looping
     * video, a caption, and a close control. The holder behind it is
     * transparent and not clickable, so every touch outside the card falls
     * through to the app — the whole point is that the person carries on.
     * Tapping the video is the click; the app opens the button link.
     */
    private fun buildPip(activity: Activity, message: InAppMessage): View {
        val holder = FrameLayout(activity)
        holder.setBackgroundColor(Color.TRANSPARENT)
        holder.elevation = dp(activity, 32f)
        holder.isClickable = false

        val card = FrameLayout(activity)
        card.background = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = dp(activity, 12f)
        }
        card.clipToOutline = true
        card.isClickable = true

        val stack = LinearLayout(activity)
        stack.orientation = LinearLayout.VERTICAL

        val width = minOf(dp(activity, 240f).toInt(), activity.resources.displayMetrics.widthPixels - dp(activity, 32f).toInt())
        val video = VideoView(activity)
        stack.addView(video, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, width * 9 / 16))
        message.videoUrl?.let { url ->
            runCatching {
                video.setVideoURI(Uri.parse(url))
                video.setOnPreparedListener { player ->
                    player.isLooping = true
                    player.setVolume(0f, 0f)
                    video.start()
                }
                // A video that cannot play must not crash the app or leave a black box for ever.
                video.setOnErrorListener { _, _, _ -> finish(Outcome.DISMISSED); true }
            }
        }

        if (message.headline.isNotEmpty() || message.body.isNotEmpty()) {
            val caption = LinearLayout(activity)
            caption.orientation = LinearLayout.VERTICAL
            val pad = dp(activity, 10f).toInt()
            caption.setPadding(pad, dp(activity, 8f).toInt(), pad, pad)
            if (message.headline.isNotEmpty()) {
                val title = TextView(activity)
                title.text = message.headline
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                title.setTextColor(Color.WHITE)
                // Two lines, like iOS. A card this small cannot grow to fit a
                // headline somebody pasted a paragraph into.
                title.maxLines = 2
                title.ellipsize = TextUtils.TruncateAt.END
                caption.addView(title, wrap())
            }
            if (message.body.isNotEmpty()) {
                val body = TextView(activity)
                body.text = message.body
                body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                body.setTextColor(0xFFCFC7DC.toInt())
                body.maxLines = 2
                body.ellipsize = TextUtils.TruncateAt.END
                caption.addView(body, wrap())
            }
            // The label the marketer typed, not a button: the whole card is
            // already the tap target, and a second one inside 240dp would only
            // give the same link two hit areas of different sizes.
            if (!message.ctaLabel.isNullOrEmpty() && !message.ctaUrl.isNullOrEmpty()) {
                val cta = TextView(activity)
                cta.text = message.ctaLabel
                cta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                cta.setTextColor(Color.WHITE)
                cta.maxLines = 1
                cta.ellipsize = TextUtils.TruncateAt.END
                val padX = dp(activity, 10f).toInt()
                val padY = dp(activity, 5f).toInt()
                cta.setPadding(padX, padY, padX, padY)
                cta.background = GradientDrawable().apply {
                    setColor(ACCENT)
                    cornerRadius = dp(activity, 7f)
                }
                caption.addView(
                    cta,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(activity, 6f).toInt() }
                )
            }
            stack.addView(caption, wrap())
        }
        // An exact width, not WRAP_CONTENT: a vertical LinearLayout measures a
        // MATCH_PARENT child against the whole screen first and only narrows it
        // afterwards, keeping the one-line height it decided on — which is how
        // the caption ended up clipped mid-sentence.
        card.addView(stack, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT))

        if (!message.ctaUrl.isNullOrEmpty()) {
            card.setOnClickListener { finish(Outcome.CLICKED) }
        }
        if (message.dismissible) closeButton(activity, card)

        val corner = message.corner ?: "bottom_right"
        val gravity = (if (corner.startsWith("top")) Gravity.TOP else Gravity.BOTTOM) or
            (if (corner.endsWith("left")) Gravity.START else Gravity.END)
        val margin = dp(activity, 16f).toInt()
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            gravity
        ).apply { setMargins(margin, margin, margin, margin) }
        holder.addView(card, params)

        // Clear of the status and navigation bars, whichever corner it is in.
        holder.post {
            val insets = activity.window?.decorView?.rootWindowInsets ?: return@post
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            params.topMargin = margin + top
            params.bottomMargin = margin + bottom
            card.layoutParams = params
        }
        return holder
    }

    private fun cardLayoutParams(activity: Activity, message: InAppMessage): FrameLayout.LayoutParams {
        val metrics = activity.resources.displayMetrics
        val margin = dp(activity, 12f).toInt()
        return when (message.layout) {
            InAppLayout.COVER, InAppLayout.CUSTOM_HTML -> FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            InAppLayout.HALF_INTERSTITIAL -> FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                // A fixed 60%, where iOS says "at most 60%". A sheet whose
                // height follows its content is a different size for every
                // message in a campaign, and the layout is called Half screen.
                (metrics.heightPixels * 0.6f).toInt(),
                Gravity.BOTTOM
            )

            InAppLayout.BANNER_TOP -> FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply { leftMargin = margin; rightMargin = margin; topMargin = dp(activity, 8f).toInt() }

            InAppLayout.BANNER_BOTTOM -> FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply { leftMargin = margin; rightMargin = margin; bottomMargin = dp(activity, 8f).toInt() }

            InAppLayout.MODAL, InAppLayout.PIP -> FrameLayout.LayoutParams(
                minOf(dp(activity, 420f).toInt(), metrics.widthPixels - dp(activity, 40f).toInt()),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
    }

    /**
     * Keep the message clear of the status and navigation bars.
     *
     * Read from `decorView.rootWindowInsets` rather than from the insets this
     * view is dispatched. A child of the decor view covers the whole window,
     * status bar included — but in an app that is not drawing edge to edge the
     * decor has already consumed the insets by the time they reach a child, so
     * the dispatched value is zero and a top bar lands underneath the clock.
     * `rootWindowInsets` is the window's own answer and is the same either way.
     */
    private fun applyInsets(
        activity: Activity,
        scrim: View,
        card: View,
        content: View,
        close: View?,
        message: InAppMessage
    ) {
        fun apply() {
            val insets = activity.window?.decorView?.rootWindowInsets ?: return
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }

            (card.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                when (message.layout) {
                    InAppLayout.BANNER_TOP -> params.topMargin = dp(activity, 8f).toInt() + top
                    InAppLayout.BANNER_BOTTOM -> params.bottomMargin = dp(activity, 8f).toInt() + bottom
                    else -> Unit
                }
                card.layoutParams = params
            }

            when (message.layout) {
                InAppLayout.COVER, InAppLayout.CUSTOM_HTML ->
                    content.setPadding(content.paddingLeft, top, content.paddingRight, bottom)

                InAppLayout.HALF_INTERSTITIAL ->
                    content.setPadding(
                        content.paddingLeft,
                        content.paddingTop,
                        content.paddingRight,
                        maxOf(content.paddingBottom, bottom)
                    )

                else -> Unit
            }

            if (close != null &&
                (message.layout == InAppLayout.COVER || message.layout == InAppLayout.CUSTOM_HTML)
            ) {
                (close.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                    params.topMargin = dp(activity, 8f).toInt() + top
                    close.layoutParams = params
                }
            }
        }

        // Once when it lands on screen, and again whenever the bars move — a
        // rotation, or a keyboard that changes what is covered.
        scrim.post { apply() }
        scrim.setOnApplyWindowInsetsListener { _, insets ->
            apply()
            insets
        }
    }

    private fun fixedLayout(activity: Activity, message: InAppMessage): View {
        val stack = LinearLayout(activity)
        stack.orientation = LinearLayout.VERTICAL
        val inset = dp(activity, if (message.layout.isBanner) 12f else 20f).toInt()
        stack.setPadding(inset, inset, inset, inset)

        if (!message.imageUrl.isNullOrEmpty() && !message.layout.isBanner) {
            val image = ImageView(activity)
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.background = GradientDrawable().apply { cornerRadius = dp(activity, 10f) }
            image.clipToOutline = true
            stack.addView(
                image,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(activity, 160f).toInt()
                ).apply { bottomMargin = dp(activity, 8f).toInt() }
            )
            loadImage(message.imageUrl, image)
        }

        val title = TextView(activity)
        title.text = message.headline
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (message.layout.isBanner) 15f else 20f)
        title.setTextColor(themeColor(activity, android.R.attr.textColorPrimary, Color.BLACK))
        // Room for the close button, which sits over the top-right corner.
        title.setPadding(0, 0, if (message.dismissible) dp(activity, 32f).toInt() else 0, 0)
        stack.addView(title, wrap())

        if (message.body.isNotEmpty()) {
            val body = TextView(activity)
            body.text = message.body
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (message.layout.isBanner) 13f else 15f)
            body.setTextColor(themeColor(activity, android.R.attr.textColorSecondary, Color.DKGRAY))
            stack.addView(body, wrap().apply { topMargin = dp(activity, 4f).toInt() })
        }

        if (!message.ctaLabel.isNullOrEmpty() && !message.ctaUrl.isNullOrEmpty()) {
            val button = Button(activity)
            button.text = message.ctaLabel
            button.isAllCaps = false
            button.setTextColor(Color.WHITE)
            button.background = GradientDrawable().apply {
                setColor(ACCENT)
                cornerRadius = dp(activity, 8f)
            }
            button.stateListAnimator = null
            button.setOnClickListener { finish(Outcome.CLICKED) }
            stack.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(activity, 12f).toInt() }
            )
        }

        return stack
    }

    private fun webView(activity: Activity, message: InAppMessage): View {
        val web = WebView(activity)
        web.tag = WEB_VIEW_TAG
        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.javaScriptEnabled = true
        // Nothing of the device is on offer to a campaign's page.
        web.settings.domStorageEnabled = false
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)

        web.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun post(kind: String, value: String?) {
                    // Arrives on a WebView thread of its own.
                    val answer = value?.take(1100)
                    main.post {
                        if (kind == "click") finish(Outcome.CLICKED, answer) else finish(Outcome.DISMISSED)
                    }
                }
            },
            "notifyAndroid"
        )

        // The same contract the web SDK's iframe has, so one campaign's markup
        // works on every surface: the page talks back with a message, not by
        // navigating.
        //
        //   parent.postMessage({ notify: 'click' }, '*')
        //   parent.postMessage({ notify: 'click', value: '9' }, '*')   // a survey's answer
        //
        // In a top-level document `parent` is the window itself, so overriding
        // `parent.postMessage` overrides the one the page will actually call.
        val shim = """
            <script>
            window.parent = window.parent || window;
            window.parent.postMessage = function (data) {
              var kind = data && data.notify;
              var value = data && data.value != null ? String(data.value) : null;
              if (kind) window.notifyAndroid.post(kind, value);
            };
            </script>
        """.trimIndent()

        // A null base URL gives the page a unique opaque origin: it cannot
        // read anything of the app's, and nothing it stores outlives it.
        web.loadDataWithBaseURL(null, (message.html ?: "") + shim, "text/html", "utf-8", null)
        return web
    }

    private fun closeButton(activity: Activity, card: FrameLayout): View {
        val close = TextView(activity)
        // A glyph, not an emoji — the same one every other surface uses.
        close.text = "✕"
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        close.gravity = Gravity.CENTER
        close.setTextColor(themeColor(activity, android.R.attr.textColorSecondary, Color.DKGRAY))
        close.background = GradientDrawable().apply {
            setColor(themeColor(activity, android.R.attr.colorBackground, Color.WHITE))
            cornerRadius = dp(activity, 14f)
        }
        close.setOnClickListener { finish(Outcome.DISMISSED) }
        card.addView(
            close,
            FrameLayout.LayoutParams(
                dp(activity, 28f).toInt(),
                dp(activity, 28f).toInt(),
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(activity, 8f).toInt()
                rightMargin = dp(activity, 8f).toInt()
            }
        )
        return close
    }

    private fun loadImage(url: String, into: ImageView) {
        images.execute {
            try {
                val bytes = URL(url).openStream().use { it.readBytes() }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
                main.post { into.setImageBitmap(bitmap) }
            } catch (_: Exception) {
                // Best effort: a picture that does not load must not hold up
                // the words next to it.
            }
        }
    }

    // ---- Small helpers -------------------------------------------------

    private fun wrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(activity: Activity, value: Float): Float =
        value * activity.resources.displayMetrics.density

    /**
     * A colour from the host app's theme, so the card belongs to the app it
     * appears in — including in dark mode, which the SDK never has to know
     * about.
     */
    private fun themeColor(activity: Activity, attr: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!activity.theme.resolveAttribute(attr, value, true)) return fallback
        return if (value.resourceId != 0) {
            try {
                activity.resources.getColor(value.resourceId, activity.theme)
            } catch (_: Exception) {
                fallback
            }
        } else {
            value.data
        }
    }
}
