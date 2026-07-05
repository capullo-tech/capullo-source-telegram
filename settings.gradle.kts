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
        // capullo-audio-contracts (published on jitpack).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "capullo-source-telegram"
include(":capullo-source-telegram")
// :tdlib holds the TDLib Java API + prebuilt .so, populated by scripts/setup_tdlib.sh (NOT a
// git submodule). Must be populated before a build - CI/jitpack run the script first.
include(":tdlib")
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
