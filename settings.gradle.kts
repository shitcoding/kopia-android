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

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

// Flutter SDK path detection
val flutterSdkPath: String = run {
    val localProperties = file("flutter_ui/.android/local.properties")
    if (localProperties.exists()) {
        val properties = java.util.Properties()
        localProperties.inputStream().use { properties.load(it) }
        properties.getProperty("flutter.sdk") ?: "/opt/homebrew/share/flutter"
    } else {
        System.getenv("FLUTTER_ROOT") ?: "/opt/homebrew/share/flutter"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Flutter module AAR repository (built via `flutter build aar`)
        maven {
            url = uri("flutter_ui/build/host/outputs/repo")
        }
        // Flutter engine artifacts (download.flutter.io)
        maven {
            val storageUrl = System.getenv("FLUTTER_STORAGE_BASE_URL") ?: "https://storage.googleapis.com"
            url = uri("$storageUrl/download.flutter.io")
        }
    }
}

rootProject.name = "KopiaKt"

include(":core")
include(":snapshot")
include(":storage")
include(":android")
include(":app")
include(":app-android")
include(":e2e")
