pluginManagement {
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
