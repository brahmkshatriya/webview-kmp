plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("com.vanniktech.maven.publish")
}

val androidCompileSdk = providers.gradleProperty("androidCompileSdk").get().toInt()
val androidMinSdk = providers.gradleProperty("androidMinSdk").get().toInt()
val coroutinesVersion = providers.gradleProperty("coroutinesVersion").get()

kotlin {
    explicitApi()
    android {
        namespace = "dev.brahmkshatriya.webview.core"
        compileSdk = androidCompileSdk
        minSdk = androidMinSdk
        withHostTest {}
    }
    js { browser() }
    iosArm64()
    iosSimulatorArm64()
    macosX64()
    macosArm64()
    mingwX64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
