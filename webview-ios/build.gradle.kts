plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.vanniktech.maven.publish")
}

val composeUiVersion = providers.gradleProperty("composeUiVersion").get()

kotlin {
    explicitApi()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain = getByName("commonMain")
        val iosMain = create("iosMain") {
            dependsOn(commonMain)
            dependencies {
                api(project(":webview-core"))
                api("org.jetbrains.compose.ui:ui:$composeUiVersion")
            }
        }
        iosArm64Main.get().dependsOn(iosMain)
        iosSimulatorArm64Main.get().dependsOn(iosMain)
    }
}
