plugins {
    id("com.android.application")
}

android {
    namespace = "com.esendpulse.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.esendpulse.example"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        // Debug only, and signed with the debug key: this app exists to be
        // installed on an emulator by hand, never to be shipped.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


dependencies {
    implementation(project(":esendpulse"))
}
