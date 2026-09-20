package com.esendpulse.example

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.esendpulse.sdk.InboxMessage
import com.esendpulse.sdk.EsendPulse

/**
 * A harness for the SDK, and the shortest honest example of using it.
 *
 * Everything it does is a call an app would make: identify somebody, track
 * something, ask for their tray, sync in-app messages and tell the SDK which
 * screen just opened. Nothing here reaches into the SDK's internals — if this
 * works, the published surface works.
 *
 * The key, host and user come from intent extras so one build can be pointed
 * at a local stack from the command line:
 *
 *     adb shell am start -n com.esendpulse.example/.MainActivity \
 *       -e esendpulse_key pk_test_… -e esendpulse_host http://10.0.2.2:4000 \
 *       -e esendpulse_user android-demo
 *
 * `EsendPulse.start` lives here rather than in an `Application` because of that:
 * an Application has no launch intent to read. A real app starts the SDK in
 * `Application.onCreate` with its own build config.
 */
class MainActivity : Activity() {

    private val log = mutableListOf<String>()
    private lateinit var logView: TextView
    private lateinit var inboxList: LinearLayout
    private lateinit var inboxHeader: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        EsendPulse.start(
            this,
            EsendPulse.Config(
                key = intent.getStringExtra("esendpulse_key") ?: "pk_test_missing",
                host = intent.getStringExtra("esendpulse_host") ?: "http://10.0.2.2:4000",
                flushIntervalMillis = 2_000,
                // Pull notifications: draw the pushes FCM could not deliver.
                pullNotifications = true
            )
        )
        EsendPulse.shared.identify(intent.getStringExtra("esendpulse_user") ?: "android-demo")

        setContentView(buildUi())

        // Android 13+: a notification cannot be drawn without asking first —
        // which a pull-notifications app has to do as much as an FCM one.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
        }

        EsendPulse.shared.syncInAppMessages { count ->
            note("synced $count in-app message(s) on launch")
            EsendPulse.shared.appLaunched()
        }
        loadInbox()
    }

    private fun buildUi(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(32))
        }

        page.addView(header("EsendPulse SDK", 24f))

        page.addView(section("Events"))
        page.addView(action("Track app_launched") {
            EsendPulse.shared.track("app_launched", mapOf("source" to "android-example"))
            note("tracked app_launched")
        })
        page.addView(action("Flush now") {
            EsendPulse.shared.flush { note("flushed") }
        })

        page.addView(section("In-app messages"))
        page.addView(action("Sync") {
            EsendPulse.shared.syncInAppMessages { count -> note("synced $count in-app message(s)") }
        })
        page.addView(action("Launch moment") {
            EsendPulse.shared.appLaunched()
            note("moment: app launch")
        })
        page.addView(action("Open the Checkout screen") {
            EsendPulse.shared.screenViewed("Checkout")
            note("screen: Checkout")
        })
        page.addView(action("Track added_to_cart (and trigger)") {
            EsendPulse.shared.trackAndTrigger("added_to_cart", mapOf("sku" to "A1"))
            note("tracked and triggered added_to_cart")
        })

        page.addView(section("Places"))
        page.addView(action("Enable geofencing") {
            // App ka kaam: pehle permission, phir SDK. Background alag se maangna
            // padta hai (Android 11+ par Settings screen se).
            val fine = android.Manifest.permission.ACCESS_FINE_LOCATION
            if (checkSelfPermission(fine) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(fine), 41)
                note("asked for location — tap again once allowed")
            } else {
                note("geofencing ${if (EsendPulse.shared.enableGeofencing()) "on" else "needs permission"}")
            }
        })
        page.addView(action("Disable geofencing") {
            EsendPulse.shared.disableGeofencing()
            note("geofencing off")
        })

        inboxHeader = section("Inbox")
        page.addView(inboxHeader)
        page.addView(action("Refresh") { loadInbox() })
        page.addView(action("Mark all read") {
            EsendPulse.shared.markInboxRead { loadInbox() }
            note("marked the tray read")
        })
        inboxList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(inboxList)

        page.addView(section("Log"))
        logView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.DKGRAY)
            typeface = Typeface.MONOSPACE
        }
        page.addView(logView)

        return ScrollView(this).apply { addView(page) }
    }

    private fun loadInbox() {
        EsendPulse.shared.inbox { result ->
            runOnUiThread {
                result.onSuccess { page ->
                    inboxHeader.text = "Inbox — ${page.unread} unread"
                    inboxList.removeAllViews()
                    page.messages.forEach { inboxList.addView(card(it)) }
                    note("inbox: ${page.messages.size} card(s), ${page.unread} unread")
                }.onFailure { error ->
                    note("inbox failed: ${error.message}")
                }
            }
        }
    }

    private fun card(message: InboxMessage): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(if (message.read) 0xFFF5F5F5.toInt() else 0xFFEDE7F3.toInt())
            isClickable = true
            setOnClickListener {
                EsendPulse.shared.clickInboxMessage(message.id) { loadInbox() }
                note("clicked ${message.title}")
            }
        }
        row.addView(TextView(this).apply {
            text = message.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)
        })
        row.addView(TextView(this).apply {
            text = message.body
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.DKGRAY)
        })
        return row.also { it.layoutParams = rowParams(dp(6)) }
    }

    private fun header(title: String, size: Float) = TextView(this).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(Color.BLACK)
        setTypeface(null, Typeface.BOLD)
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(0xFF6B7280.toInt())
        letterSpacing = 0.08f
        setTypeface(null, Typeface.BOLD)
        layoutParams = rowParams(dp(20))
    }

    private fun action(title: String, onTap: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setOnClickListener { onTap() }
        layoutParams = rowParams(dp(4))
    }

    private fun rowParams(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top }

    private fun note(line: String) {
        runOnUiThread {
            log.add(line)
            logView.text = log.asReversed().joinToString("\n")
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
