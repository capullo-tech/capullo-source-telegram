pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // capullo-audio-contracts + lib-tdlib-android + build-conventions catalog (all jitpack).
        maven { url = uri("https://jitpack.io") }
    }
    versionCatalogs {
        // Shared org toolchain, pinned by commit from jitpack.
        create("libs") { from("com.github.capullo-tech:build-conventions:b07e979") }
        // Local pins: the SPI coordinate + the L0 TDLib prebuilt, pinned independently per release.
        create("pins") { from(files("gradle/pins.versions.toml")) }
    }
}

rootProject.name = "capullo-source-telegram"
include(":capullo-source-telegram")
// TDLib (Java API + prebuilt .so) now comes from the lib-tdlib-android jitpack AAR (Layer 0), so
// there is no local :tdlib module / setup_tdlib.sh / git-lfs anymore.
include(":app") // harness/demo app: exercises TelegramSource against the SPI.

// Dev/release toggle: when the SPI repo is checked out as a sibling (local co-development or the CI
// sibling-checkout), build it from source via a composite build and substitute it for the jitpack
// coordinate `com.github.capullo-tech:capullo-audio-contracts`. On jitpack (single repo, no sibling)
// this block is skipped and the coordinate resolves from jitpack.io instead.
if (file("../capullo-audio-contracts").exists()) {
    includeBuild("../capullo-audio-contracts") {
        dependencySubstitution {
            substitute(module("com.github.capullo-tech:capullo-audio-contracts"))
                .using(project(":"))
        }
    }
}
