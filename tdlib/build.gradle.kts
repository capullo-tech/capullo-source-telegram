plugins {
    // No kotlin.android: AGP 9.0+ ships built-in Kotlin support (see capullo-audio). This module is
    // pure Java (TDLib's generated org.drinkless.tdlib API) plus prebuilt .so, so no Kotlin anyway.
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.drinkless.tdlib"
    compileSdk = 36
    defaultConfig {
        // 24 = TDLib prebuilt (TGX-Android) floor; the source lib + apps match it.
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(libs.androidx.annotation)
}
