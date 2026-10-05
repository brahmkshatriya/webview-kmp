plugins {
    kotlin("multiplatform") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("com.android.kotlin.multiplatform.library") version "9.4.0" apply false
    id("dev.brahmkshatriya.compose") version "1.13.0-alpha10" apply false
}

val libraryVersion = providers.gradleProperty("VERSION_NAME").orElse("0.1.0-SNAPSHOT")

allprojects {
    group = "dev.brahmkshatriya.webview"
    version = libraryVersion.get()
}

tasks.register("copyWindowsWebView2Loader") {
    group = "distribution"
    description = "Copies the Windows WebView2 loader from the webview-windows module."
    dependsOn(":webview-windows:copyWindowsWebView2Loader")
}
