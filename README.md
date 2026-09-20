# EsendPulse Android SDK

Events, identity, push registration, the app inbox, and in-app messages — the
same surface the browser and iOS SDKs have, with the differences a phone
forces.

Kotlin, `minSdk 24`, and **no third-party dependencies** (the POM carries
`kotlin-stdlib` and nothing else): `HttpURLConnection` for the
network, `org.json` for the wire, `SharedPreferences` and a file for what has
to survive a launch. An analytics SDK that drags OkHttp and a serialization
compiler plugin into somebody's app is making their build slower and their
dependency graph riskier to save itself a few hundred lines.

## Adding it

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

```kotlin
// app/build.gradle.kts
implementation("com.github.esendprovider.esendpulse-android:esendpulse:0.1.0")
```

JitPack builds the tag itself — no account, no upload and no GPG key — which is
why this publication has no `repositories {}` block to point anywhere. The
coordinate is JitPack's own shape (`com.github.<owner>.<repo>:<module>`) rather
than the `com.esendpulse:esendpulse-android` in the POM; that one starts
resolving the day the same publication goes to Maven Central, which is the same
build plus Sonatype credentials and signing.

`./gradlew :esendpulse:publishToMavenLocal` puts it in `~/.m2` with a sources
jar and a full POM, which is what JitPack runs and what to use while testing a
change that is not tagged yet.

Working inside the monorepo, include the build instead:

```kotlin
// settings.gradle.kts
includeBuild("../Notification_Provider_System/sdks/android")
```

## Starting it

```kotlin
class YourApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EsendPulse.start(this, EsendPulse.Config(key = "pk_live_…", host = "https://api.yourapp.com"))
        EsendPulse.shared.appLaunched()          // the launch moment for in-app messages
    }
}
```

The key must be a **publishable** (`pk_`) key. A secret key in an app is a
secret key in everybody's hands — the SDK refuses to start with one.

Start it from `Application.onCreate` and pass the `Application`. The SDK reads
foreground and background from the activities that are started, which is what
resets a session and what gives an in-app message somewhere to draw; started
with something that is not an `Application`, everything else still works and it
says so in the log.

## Events

```kotlin
EsendPulse.shared.track("product_viewed", mapOf("sku" to "A1", "price" to 499))
EsendPulse.shared.identify("user_42", mapOf("plan" to "pro"))   // after sign-in
EsendPulse.shared.reset()                                       // after sign-out
```

Events are batched and flushed every few seconds. **The queue is written to
disk**, so an app killed between a tap and a flush still reports the tap on the
next launch — a browser can flush on `pagehide`, an app gets no such warning.

## Push

The platform sends app push through FCM, so hand over the FCM token. The SDK
does not depend on Firebase; the app that already has it does the asking:

```kotlin
FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
    EsendPulse.shared.registerPushToken(token)
}
```

## App inbox

```kotlin
EsendPulse.shared.inbox { result ->
    val page = result.getOrNull() ?: return@inbox
    println("${page.unread} unread, ${page.messages.size} cards")
}
EsendPulse.shared.markInboxRead()                       // whole tray, or pass ids
EsendPulse.shared.clickInboxMessage(message.id)
EsendPulse.shared.dismissInboxMessage(message.id)       // clears it from the tray
```

Paging is by the oldest card you already have, not by an offset — the tray
grows at the top while somebody is reading it:

```kotlin
EsendPulse.shared.inbox(limit = 20, before = oldest.createdAt) { … }
```

Callbacks arrive on a background thread. Hop to the main thread yourself before
touching views.

## In-app messages

Sync once at launch, then tell the SDK what is happening. The server decides
*what* somebody is eligible for; the device decides *when*, because the facts
that decide it — which screen, which session, how many today — only exist here.

```kotlin
EsendPulse.shared.syncInAppMessages()

EsendPulse.shared.appLaunched()
EsendPulse.shared.screenViewed("Checkout")
EsendPulse.shared.trackAndTrigger("added_to_cart")      // tracks AND offers the moment
```

The SDK draws the five fixed layouts itself and renders `custom_html` in an
isolated `WebView`. To draw them yourself:

```kotlin
EsendPulse.shared.inAppListener = object : EsendPulseInAppListener {
    override fun shouldPresent(message: InAppMessage): Boolean {
        show(message)        // your own view
        return false         // tell the SDK not to draw it
    }

    override fun onClick(message: InAppMessage) {
        openDeepLink(message.ctaUrl)
    }
}
```

Then report what happened, so the caps and the campaign's numbers stay true:

```kotlin
EsendPulse.shared.inAppShown(message.id)
EsendPulse.shared.inAppClicked(message.id)
EsendPulse.shared.inAppDismissed(message.id)
```

A custom HTML message talks back the same way it does on the web:

```javascript
parent.postMessage({ notify: 'click' }, '*')     // or 'dismiss'
parent.postMessage({ notify: 'click', value: '9' }, '*')   // with what was answered
```

A `value` (up to 64 characters) is kept on the message and recorded as an
`inapp_response` event on the person. The dashboard's NPS and star-rating
messages use exactly this, so they need nothing from the app beyond this SDK.

When several messages wait for the same moment, the one with the highest
priority (set on the campaign) is shown — the api hands them over in that order.

## Places (geofencing)

Places are set up on the dashboard (**Audience → Places**). When the phone
arrives at or leaves one, the SDK tracks `$geofence_entered` /
`$geofence_exited` with `geofence_id`, `geofence_name`, `geofence_tags` and
`geofence_radius` — start a journey on it, with a condition on the name.

The app asks for location; the SDK never does. Declare the permissions in your
own manifest (the SDK's manifest deliberately does not, because background
location triggers a Play Store policy review):

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

Then, once the person has allowed it:

```kotlin
EsendPulse.shared.enableGeofencing()   // false if location is not allowed
EsendPulse.shared.disableGeofencing()
```

It stays on across launches. No Google Play Services: the SDK uses the
platform `LocationManager`, asks the API for the 19 places nearest the phone,
and asks again once the phone moves most of the way out of that area. While
the app is in the background Android hands out locations only a few times an
hour, so an arrival can be noticed some minutes late.

## Frequency

Each message carries its own caps — per session, per day, ever — and the SDK
enforces them on the device. `perDay` matters more than it sounds: people open
an app eleven times a day for ten seconds each, and "once per session" on that
pattern is eleven interruptions.

A session ends after 30 minutes in the background (configurable). Pressing a
message's button retires it for good; closing it by hand stops it for the rest
of that session.

## Building it

The build needs a JDK and the Android SDK. On a machine with no system Java,
Android Studio's own runtime works:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"

./gradlew :esendpulse:assembleRelease      # the AAR
./gradlew :esendpulse:testDebugUnitTest    # the rules tests
```

## Running the example

`example/` is a small app used to verify the SDK against a local stack. Its key
and host come from intent extras so one build can be pointed anywhere:

```bash
./gradlew :example:installDebug

adb shell am start -n com.esendpulse.example/.MainActivity \
  -e esendpulse_key pk_test_… \
  -e esendpulse_host http://10.0.2.2:4000 \
  -e esendpulse_user android-demo
```

`10.0.2.2` is the emulator's name for the host machine's `localhost`.

## Rich push notifications

A campaign can send more than a title and a line of text — a carousel, a
countdown, stars to tap, a reply to type, products from your catalog. Those are
drawn by this SDK, because Android's own notification is a fixed layout.

Two lines in your messaging service:

```kotlin
class MyMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (EsendPulsePush.handle(this, message.data)) return
        // …your own notifications, unchanged
    }

    override fun onNewToken(token: String) {
        EsendPulse.shared.registerPushToken(token)
    }
}
```

`handle` returns false for anything that is not ours, so adding it to an app
that already shows notifications cannot take any of them over.

One thing to set, once, wherever you start the SDK:

```kotlin
EsendPulsePush.smallIconResId = R.drawable.ic_notification
```

Android refuses to post a notification without a small icon, and the SDK has no
way to guess which of your drawables is the right one.

Nothing else is needed. The receiver that handles the carousel arrows, the
stars, the reply field and the buttons is declared by the library's own
manifest, and what somebody does with a notification is reported back to the
platform automatically — a tapped star becomes a `$push_rating` event attached
to the campaign that asked for it.

**What arrives without the SDK.** A standard or big-picture notification is
drawn by Android itself and reaches every handset. Everything else is sent as a
data-only message so this code can draw it, which means it is not shown at all
on a device without the SDK, or with the app force-stopped. The dashboard says
so next to each template.

## Tests

```bash
./gradlew :esendpulse:testDebugUnitTest
```

The tests cover the rules that decide whether a message may appear, and the
ones that decide how a rich notification is drawn (`PushRulesTest`). They are
deliberately the same cases as `packages/core/src/inapp-message.test.ts`,
`packages/core/src/push-template.test.ts` and their Swift counterparts, because
those rules exist three times — once in TypeScript, once in Swift, once here — and the
failure worth guarding against is the copies drifting apart.

They are plain JVM tests, not instrumented ones, which is why `InAppRules.kt`
touches neither `android.*` nor `org.json`: `org.json` is stubbed to throw in
unit tests, and a rules file that reached for it would put the whole suite on
an emulator. Tests that need an emulator to run are tests people stop running.
