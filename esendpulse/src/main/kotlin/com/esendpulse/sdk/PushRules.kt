package com.esendpulse.sdk

/**
 * The decisions a rich notification needs, with no Android in them.
 *
 * Same bargain [InAppRules] makes: everything that could be wrong in a way a
 * person would notice — which style to draw, where the carousel is after four
 * taps, how much time is left, which pictures are worth downloading — is
 * settled here, in plain Kotlin, so a JVM unit test can hold it to account.
 * What is left in [EsendPulsePush] is the part only a handset can do.
 */

/** The template kinds the server can send. Anything else is drawn as [BASIC]. */
enum class PushKind(val wire: String) {
    BASIC("basic"),
    BIG_PICTURE("big_picture"),
    CAROUSEL("carousel"),
    TIMER("timer"),
    RATING("rating"),
    INPUT("input"),
    PRODUCT("product");

    companion object {
        /**
         * Unknown kinds fall back rather than failing.
         *
         * A server that learns a new template must not make older apps show
         * nothing: the title and the body are always meaningful, so a handset
         * that does not recognise the rest still delivers the message.
         */
        fun from(wire: String?): PushKind =
            values().firstOrNull { it.wire == wire } ?: BASIC
    }
}

data class PushAction(val id: String, val label: String, val url: String?)

data class PushSlide(val imageUrl: String, val caption: String?, val url: String?)

data class PushProduct(
    val id: String,
    val title: String?,
    val price: String?,
    val imageUrl: String?,
    val url: String?
)

/** The payload as the handset holds it — see `core/push-template.ts`. */
data class RichPush(
    val version: Int,
    val kind: PushKind,
    val title: String,
    val body: String,
    val token: String,
    val url: String? = null,
    val image: String? = null,
    val icon: String? = null,
    val summary: String? = null,
    val actions: List<PushAction> = emptyList(),
    val endsAt: Long? = null,
    val afterTitle: String? = null,
    val afterBody: String? = null,
    val slides: List<PushSlide> = emptyList(),
    val autoRotate: Boolean = false,
    val rotateSeconds: Int = 4,
    val stars: Int = 5,
    val placeholder: String? = null,
    val submitLabel: String? = null,
    val eventName: String? = null,
    val thanksTitle: String? = null,
    val thanksBody: String? = null,
    val products: List<PushProduct> = emptyList(),
    val ctaLabel: String? = null,
    val channelId: String? = null
)

object PushRules {

    /**
     * A notification id that is stable for one message and different between two.
     *
     * The token is unique per delivery, so two campaigns arriving together
     * stack instead of one silently replacing the other — which is what an id
     * of 0, or of the campaign, would do. Positive because Android treats the
     * id as opaque but people read it in logs.
     */
    fun notificationId(token: String): Int = (token.hashCode() and 0x7fffffff)

    /** Where a carousel goes when the reader presses the arrow. */
    fun nextSlide(current: Int, count: Int, forward: Boolean): Int {
        if (count <= 0) return 0
        val step = if (forward) 1 else -1
        // Wraps rather than stopping: a tray notification has no scrollbar to
        // tell you that you have reached the end, so a dead arrow reads as a
        // broken notification.
        return ((current + step) % count + count) % count
    }

    /**
     * Has the countdown finished?
     *
     * Asked on the handset against the handset's own clock, because that is
     * the only clock the person can see. A phone whose time is wrong shows a
     * wrong countdown, which is better than showing one that disagrees with
     * the lock screen beside it.
     */
    fun timerExpired(endsAt: Long?, now: Long): Boolean = endsAt != null && now >= endsAt

    /** Title and body once the countdown is done, falling back to the originals. */
    fun expiredText(push: RichPush): Pair<String, String> =
        Pair(push.afterTitle ?: push.title, push.afterBody ?: push.body)

    /**
     * The pictures worth fetching before the notification is posted.
     *
     * Deliberately not "every URL in the payload". A notification has to appear
     * quickly, each download is a network round trip on a handset that may be
     * on a train, and the reader can only see one carousel slide at a time —
     * so only the visible one is fetched, and the rest when they are swiped to.
     * The cap is what stops a six-product template from making the tray wait
     * for six downloads before anything shows at all.
     */
    fun imagesToFetch(push: RichPush, slideIndex: Int, max: Int = 3): List<String> {
        val out = ArrayList<String>()
        fun add(url: String?) {
            if (url.isNullOrBlank() || out.contains(url) || out.size >= max) return
            out.add(url)
        }
        add(push.icon)
        when (push.kind) {
            PushKind.BIG_PICTURE -> add(push.image)
            PushKind.CAROUSEL -> {
                push.slides.getOrNull(slideIndex)?.let { add(it.imageUrl) }
            }
            PushKind.PRODUCT -> {
                for (product in push.products.take(max)) add(product.imageUrl)
            }
            else -> add(push.image)
        }
        return out
    }

    /**
     * Which link a tap should follow.
     *
     * A button's own link wins over the notification's, and a carousel slide's
     * over both — the reader tapped a specific picture, and sending them to the
     * campaign's generic landing page instead is the single most annoying thing
     * a rich notification can do.
     */
    fun linkFor(push: RichPush, actionId: String?, slideIndex: Int): String? {
        if (actionId != null) {
            val action = push.actions.firstOrNull { it.id == actionId }
            if (action != null) return action.url ?: push.url
        }
        if (push.kind == PushKind.CAROUSEL) {
            push.slides.getOrNull(slideIndex)?.url?.let { return it }
        }
        return push.url
    }

    /**
     * Does this template need our renderer at all?
     *
     * Mirrors `needsSdkToRender` on the server, and is checked again here
     * because the two arrive at it differently: the server decides whether to
     * send a data-only message, and the handset decides whether a message it
     * has already received needs the custom layout or the plain one.
     */
    fun needsCustomLayout(kind: PushKind): Boolean =
        kind == PushKind.CAROUSEL || kind == PushKind.RATING || kind == PushKind.PRODUCT

    /** Stars are clamped rather than trusted: a payload is not a promise. */
    fun starCount(raw: Int): Int = raw.coerceIn(3, 5)
}
