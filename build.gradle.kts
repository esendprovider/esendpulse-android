// AGP 9 compiles Kotlin itself — the `org.jetbrains.kotlin.android` plugin is
// gone from these modules on purpose, not forgotten. Adding it back makes the
// build fail with "no longer required for Kotlin support since AGP 9.0".
plugins {
    id("com.android.library") version "9.3.1" apply false
    id("com.android.application") version "9.3.1" apply false
}
