plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.vanniktech.maven.publish")
}

val composeUiVersion = providers.gradleProperty("composeUiVersion").get()

kotlin {
    explicitApi()
    js { browser() }
    sourceSets {
        jsMain.dependencies {
            api(project(":webview-core"))
            api("org.jetbrains.compose.ui:ui:$composeUiVersion")
        }
    }
}
