plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.kotlin.multiplatform.library")
    id("dev.brahmkshatriya.compose")
    id("com.vanniktech.maven.publish")
}

val composeNativeVersion = providers.gradleProperty("composeNativeVersion").orElse("1.13.0-alpha10").get()

kotlin {
    explicitApi()
    android {
        namespace = "dev.brahmkshatriya.webview.compose"
        compileSdk = 37
        minSdk = 24
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
        val commonMain = getByName("commonMain")
        commonMain.dependencies {
            api(project(":webview-core"))
            api("org.jetbrains.compose.ui:ui:1.13.0-alpha01")
        }
        getByName("desktopNativeMain") {
            dependencies {
                api("dev.brahmkshatriya.compose.ui:ui:$composeNativeVersion")
                implementation("dev.brahmkshatriya.compose.desktop:desktop-native:$composeNativeVersion")
            }
        }
        androidMain.dependencies { api(project(":webview-android")) }
        jsMain.dependencies { api(project(":webview-js")) }
        mingwX64Main.dependencies { api(project(":webview-windows")) }

        val iosMain = create("iosMain") {
            dependsOn(commonMain)
            dependencies { api(project(":webview-ios")) }
        }
        iosArm64Main.get().dependsOn(iosMain)
        iosSimulatorArm64Main.get().dependsOn(iosMain)

        macosX64Main {
            kotlin.srcDir("src/macosMain/kotlin")
            dependencies { api(project(":webview-macos")) }
        }
        macosArm64Main {
            kotlin.srcDir("src/macosMain/kotlin")
            dependencies { api(project(":webview-macos")) }
        }

        linuxX64Main {
            kotlin.srcDir("src/linuxMain/kotlin")
            dependencies { api(project(":webview-linux")) }
        }
        linuxArm64Main {
            kotlin.srcDir("src/linuxMain/kotlin")
            dependencies { api(project(":webview-linux")) }
        }
    }
}
