plugins {
    id("com.android.library")
    `maven-publish`
}

/*
 * The coordinate an app writes in its own build file. Kept here rather than in
 * gradle.properties so that the one file somebody opens to answer "what version
 * is this SDK" is the one that defines it.
 */
group = "com.esendpulse"
version = "0.1.0"

android {
    namespace = "com.esendpulse.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    /*
     * One variant, and it has to be named explicitly.
     *
     * Without this the library has no `release` software component for
     * `maven-publish` to read, and the publication fails at configuration time
     * with a message about a missing component rather than about a missing
     * setting. Nobody wants a debug build of an SDK from a repository anyway.
     */
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            artifactId = "esendpulse-android"

            /*
             * A POM this complete is not ceremony: Maven Central refuses a
             * publication without a name, description, URL, licence and
             * developer, and JitPack shows them on the package page. Filling
             * them in now means the first publish is not the moment somebody
             * discovers the requirement.
             */
            pom {
                name.set("eSendPulse Android SDK")
                description.set(
                    "Events, identity, push registration, the app inbox and in-app messages for eSendPulse. Kotlin, no dependencies."
                )
                url.set("https://github.com/esendprovider/esendpulse-android")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        name.set("eSendPulse")
                    }
                }
                scm {
                    url.set("https://github.com/esendprovider/esendpulse-android")
                    connection.set("scm:git:https://github.com/esendprovider/esendpulse-android.git")
                }
            }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
