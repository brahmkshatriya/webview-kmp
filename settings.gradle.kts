pluginManagement {
    val kotlinVersion = providers.gradleProperty("kotlinVersion").get()
    val androidPluginVersion = providers.gradleProperty("androidPluginVersion").get()
    val composeNativeVersion = providers.gradleProperty("composeNativeVersion").get()
    val mavenPublishPluginVersion = providers.gradleProperty("mavenPublishPluginVersion").get()

    plugins {
        id("org.jetbrains.kotlin.multiplatform") version kotlinVersion
        id("org.jetbrains.kotlin.plugin.compose") version kotlinVersion
        id("com.android.kotlin.multiplatform.library") version androidPluginVersion
        id("dev.brahmkshatriya.compose") version composeNativeVersion
        id("com.vanniktech.maven.publish") version mavenPublishPluginVersion
    }

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://redirector.kotlinlang.org/maven/compose-dev")
    }
}

rootProject.name = "webview-kmp"

include(
    ":webview-core",
    ":webview-compose",
    ":webview-android",
    ":webview-ios",
    ":webview-js",
    ":webview-linux",
    ":webview-macos",
    ":webview-windows",
)
