plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ksp) apply false
    // No kotlin.android: AGP 9.0+ provides built-in Kotlin (see capullo-audio, same toolchain).
}
