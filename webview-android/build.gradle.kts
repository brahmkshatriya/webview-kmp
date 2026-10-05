plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.kotlin.multiplatform.library")
    id("com.vanniktech.maven.publish")
}

val androidCompileSdk = providers.gradleProperty("androidCompileSdk").get().toInt()
val androidMinSdk = providers.gradleProperty("androidMinSdk").get().toInt()
val androidxComposeUiVersion = providers.gradleProperty("androidxComposeUiVersion").get()

kotlin {
    explicitApi()
    android {
        namespace = "dev.brahmkshatriya.webview.android"
        compileSdk = androidCompileSdk
        minSdk = androidMinSdk
    }
    sourceSets {
        androidMain.dependencies {
            api(project(":webview-core"))
            api("androidx.compose.ui:ui:$androidxComposeUiVersion")
        }
    }
}
