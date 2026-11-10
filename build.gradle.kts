plugins {
    alias(libs.plugins.spotless)
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ksp) apply false
    // No kotlin.android: AGP 9.0+ provides built-in Kotlin (see capullo-audio, same toolchain).
}

// Org code style, same baseline as the apps (QuantumCast / Telecloud): Spotless drives ktlint from
// the shared build-conventions versions. `./gradlew spotlessApply` formats, `spotlessCheck` (CI) verifies.
spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(
                mapOf(
                    "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                    "ktlint_standard_annotation" to "disabled",
                    "max_line_length" to 100,
                    // Same two relaxations as QuantumCast: ktlint can't auto-expand wildcard
                    // imports or wrap long lines headlessly, so they'd block CI on existing code.
                    "ktlint_standard_no-wildcard-imports" to "disabled",
                    "ktlint_standard_max-line-length" to "disabled",
                ),
            )
    }
}
