plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.brahmkshatriya.compose")
    `maven-publish`
}

val composeNativeVersion = providers.gradleProperty("composeNativeVersion").orElse("1.13.0-alpha10").get()
val waylandAppViewVersion = providers.gradleProperty("waylandAppViewVersion").orElse("0.1.0").get()

kotlin {
    explicitApi()
    linuxX64()
    linuxArm64()

    sourceSets {
        getByName("desktopNativeMain") {
            dependencies {
                api("dev.brahmkshatriya.compose.ui:ui:$composeNativeVersion")
                implementation("dev.brahmkshatriya.compose.desktop:desktop-native:$composeNativeVersion")
            }
        }
        linuxX64Main {
            kotlin.srcDir("src/linuxMain/kotlin")
            dependencies {
                api(project(":webview-core"))
                implementation("dev.brahmkshatriya.wayland:wayland-appview:$waylandAppViewVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }
        linuxArm64Main {
            kotlin.srcDir("src/linuxMain/kotlin")
            dependencies {
                api(project(":webview-core"))
                implementation("dev.brahmkshatriya.wayland:wayland-appview:$waylandAppViewVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }
        linuxX64Test.dependencies {
            implementation(kotlin("test"))
        }
    }
}
