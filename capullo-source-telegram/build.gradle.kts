plugins {
    alias(libs.plugins.android.library)
    // No kotlin.android: AGP 9.0+ ships built-in Kotlin support (see capullo-audio).
    alias(libs.plugins.ksp)
    id("maven-publish")
}

android {
    namespace = "tech.capullo.source.telegram"
    compileSdk = 36

    defaultConfig {
        // 24 to match the :tdlib prebuilt floor; stays ≤ the consuming app (telecloud is 24).
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // New DSL for Kotlin 2.3 / AGP 9.x (mirrors capullo-audio, the known-good reference).
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    testOptions {
        // The contract-validation driver test is pure JVM (fakes for TelegramClient + DAO); let the
        // few stubbed Android calls it may touch return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    // The SPI - `api` because the public surface implements/returns its types
    // (MediaSourceProvider, NowPlaying via NowPlayingSource, PlaybackQueue).
    api(pins.capullo.audio.contracts)

    // TDLib Java API + prebuilt native libs, via the lib-tdlib-android jitpack AAR.
    // `api` so consumers get org.drinkless.tdlib.* + libtdjni.so transitively.
    api(pins.lib.tdlib.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Track index + on-disk cache persistence (media_messages table).
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "tech.capullo.source.telegram"
            artifactId = "capullo-source-telegram"
            version = "0.1.0-SNAPSHOT"
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/capullo-tech/capullo-source-telegram")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
