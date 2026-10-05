plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.kotlin.multiplatform.library")
    id("com.vanniktech.maven.publish")
}

kotlin {
    explicitApi()
    android {
        namespace = "dev.brahmkshatriya.webview.android"
        compileSdk = 37
        minSdk = 24
    }
    sourceSets {
        androidMain.dependencies {
            api(project(":webview-core"))
            api("androidx.compose.ui:ui:1.10.5")
        }
    }
}
