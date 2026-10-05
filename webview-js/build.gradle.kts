plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    `maven-publish`
}

kotlin {
    explicitApi()
    js { browser() }
    sourceSets {
        jsMain.dependencies {
            api(project(":webview-core"))
            api("org.jetbrains.compose.ui:ui:1.13.0-alpha01")
        }
    }
}
