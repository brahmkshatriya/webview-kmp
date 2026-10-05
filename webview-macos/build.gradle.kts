plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.brahmkshatriya.compose")
    id("com.vanniktech.maven.publish")
}

val composeNativeVersion = providers.gradleProperty("composeNativeVersion").orElse("1.13.0-alpha10").get()

kotlin {
    explicitApi()
    macosX64()
    macosArm64()

    sourceSets {
        getByName("desktopNativeMain") {
            dependencies {
                api("dev.brahmkshatriya.compose.ui:ui:$composeNativeVersion")
                implementation("dev.brahmkshatriya.compose.desktop:desktop-native:$composeNativeVersion")
            }
        }
        macosX64Main {
            kotlin.srcDir("src/macosMain/kotlin")
            dependencies {
                api(project(":webview-core"))
                implementation("dev.brahmkshatriya.compose.desktop:desktop-native:$composeNativeVersion")
            }
        }
        macosArm64Main {
            kotlin.srcDir("src/macosMain/kotlin")
            dependencies {
                api(project(":webview-core"))
                implementation("dev.brahmkshatriya.compose.desktop:desktop-native:$composeNativeVersion")
            }
        }
    }
}
