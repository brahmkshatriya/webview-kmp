plugins {
    kotlin("multiplatform") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("com.android.kotlin.multiplatform.library") apply false
    id("dev.brahmkshatriya.compose") apply false
    id("com.vanniktech.maven.publish") apply false
}

val libraryGroup = providers.gradleProperty("GROUP").orElse("dev.brahmkshatriya.webview")
val libraryVersion = providers.gradleProperty("VERSION_NAME").orElse("unspecified")

allprojects {
    group = libraryGroup.get()
    version = libraryVersion.get()
}

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<org.gradle.api.publish.PublishingExtension> {
            repositories {
                maven {
                    name = "releaseShard"
                    url = rootProject.layout.buildDirectory.dir("maven-release-shard").get().asFile.toURI()
                }
            }
        }
    }
}

tasks.register("copyWindowsWebView2Loader") {
    group = "distribution"
    description = "Copies the Windows WebView2 loader from the webview-windows module."
    dependsOn(":webview-windows:copyWindowsWebView2Loader")
}
