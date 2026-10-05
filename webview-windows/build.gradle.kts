@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.gradle.api.GradleException
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import java.net.URI
import java.util.zip.ZipFile

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.brahmkshatriya.compose")
    `maven-publish`
}

val composeNativeVersion = providers.gradleProperty("composeNativeVersion").orElse("1.13.0-alpha10").get()
val webView2SdkVersion = providers.gradleProperty("webView2SdkVersion").orElse("1.0.4129.50").get()
val webView2SdkPackage = providers.gradleProperty("webView2SdkPackage")
val webView2SdkDir = layout.buildDirectory.dir("webview2-sdk/$webView2SdkVersion")
val webView2IncludeDir = webView2SdkDir.map { it.dir("include") }
val webView2LoaderDll = webView2SdkDir.map { it.file("runtime/x64/WebView2Loader.dll") }
val webView2License = webView2SdkDir.map { it.file("runtime/LICENSE.txt") }
val windowsInteropDir = layout.projectDirectory.dir("src/nativeInterop/windows")
val windowsInteropIncludeDir = windowsInteropDir.dir("include")
val windowsBridgeDir = layout.buildDirectory.dir("native-bridge/mingwX64")
val windowsBridgeObject = windowsBridgeDir.map { it.file("system_webview2.o") }
val windowsBridgeArchive = windowsBridgeDir.map { it.file("libktnative-webview2.a") }

val prepareWebView2Sdk = tasks.register("prepareWebView2Sdk") {
    group = "build setup"
    description = "Obtains WebView2 SDK headers and the x64 loader DLL, not a browser runtime."
    outputs.files(
        webView2IncludeDir.map { it.file("WebView2.h") },
        webView2IncludeDir.map { it.file("WebView2EnvironmentOptions.h") },
        webView2LoaderDll,
        webView2License,
    )
    doLast {
        val sdkRoot = webView2SdkDir.get().asFile
        val packageFile = webView2SdkPackage.orNull?.let(::file)
            ?: sdkRoot.resolve("Microsoft.Web.WebView2.$webView2SdkVersion.nupkg")
        if (!packageFile.isFile) {
            packageFile.parentFile.mkdirs()
            URI("https://www.nuget.org/api/v2/package/Microsoft.Web.WebView2/$webView2SdkVersion")
                .toURL().openStream().use { input -> packageFile.outputStream().use(input::copyTo) }
        }

        fun extract(entryName: String, destination: File) {
            destination.parentFile.mkdirs()
            ZipFile(packageFile).use { archive ->
                val entry = archive.getEntry(entryName)
                    ?: throw GradleException("$entryName is missing from ${packageFile.name}")
                archive.getInputStream(entry).use { input -> destination.outputStream().use(input::copyTo) }
            }
        }

        extract("build/native/include/WebView2.h", webView2IncludeDir.get().file("WebView2.h").asFile)
        extract("build/native/include/WebView2EnvironmentOptions.h", webView2IncludeDir.get().file("WebView2EnvironmentOptions.h").asFile)
        extract("build/native/x64/WebView2Loader.dll", webView2LoaderDll.get().asFile)
        extract("LICENSE.txt", webView2License.get().asFile)
    }
}

val compileWindowsWebView2Bridge = tasks.register<Exec>("compileWindowsWebView2Bridge") {
    dependsOn(prepareWebView2Sdk)
    inputs.files(
        windowsInteropDir.file("system_webview2.cpp"),
        windowsInteropIncludeDir.file("system_webview2.h"),
        windowsInteropIncludeDir.file("EventToken.h"),
        webView2IncludeDir.map { it.file("WebView2.h") },
    )
    outputs.file(windowsBridgeObject)
    doFirst {
        windowsBridgeDir.get().asFile.mkdirs()
        val dependencies = file("${System.getProperty("user.home")}/.konan/dependencies")
        val sysroot = dependencies.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("msys2-mingw-w64-x86_64-") }
            ?.maxByOrNull(File::lastModified)
            ?: throw GradleException("Kotlin/Native MinGW sysroot was not found under $dependencies")
        commandLine(
            "clang++", "--target=x86_64-pc-windows-gnu", "--sysroot=${sysroot.absolutePath}",
            "-std=c++17", "-O2", "-DUNICODE", "-D_UNICODE", "-D_WIN32_WINNT=0x0A00",
            "-I${windowsInteropIncludeDir.asFile.absolutePath}",
            "-I${webView2IncludeDir.get().asFile.absolutePath}",
            "-c", windowsInteropDir.file("system_webview2.cpp").asFile.absolutePath,
            "-o", windowsBridgeObject.get().asFile.absolutePath,
        )
    }
}

val archiveWindowsWebView2Bridge = tasks.register<Exec>("archiveWindowsWebView2Bridge") {
    dependsOn(compileWindowsWebView2Bridge)
    inputs.file(windowsBridgeObject)
    outputs.file(windowsBridgeArchive)
    doFirst {
        windowsBridgeDir.get().asFile.mkdirs()
        commandLine("llvm-ar", "rcs", windowsBridgeArchive.get().asFile.absolutePath, windowsBridgeObject.get().asFile.absolutePath)
    }
}

tasks.register<Copy>("copyWindowsWebView2Loader") {
    dependsOn(prepareWebView2Sdk)
    group = "distribution"
    description = "Copies WebView2Loader.dll for placement beside a Windows executable."
    from(webView2LoaderDll)
    from(webView2License) { rename { "WebView2-LICENSE.txt" } }
    into(layout.buildDirectory.dir("windows-runtime"))
}

kotlin {
    explicitApi()
    mingwX64 {
        compilations.getByName("main") {
            cinterops.create("systemWebView2") {
                defFile(windowsInteropDir.file("system-webview2.def").asFile)
                header(windowsInteropIncludeDir.file("system_webview2.h").asFile)
                includeDirs(windowsInteropIncludeDir.asFile)
                packageName("dev.brahmkshatriya.webview.internal.webview2")
                extraOpts(
                    "-libraryPath", windowsBridgeDir.get().asFile.absolutePath,
                    "-staticLibrary", "libktnative-webview2.a",
                )
            }
        }
    }
    sourceSets {
        getByName("desktopNativeMain") {
            dependencies {
                api("dev.brahmkshatriya.compose.ui:ui:$composeNativeVersion")
                implementation("dev.brahmkshatriya.compose.desktop:desktop-native:$composeNativeVersion")
            }
        }
        mingwX64Main.dependencies {
            api(project(":webview-core"))
        }
    }
}

tasks.matching { it.name == "cinteropSystemWebView2MingwX64" }.configureEach {
    dependsOn(archiveWindowsWebView2Bridge)
}
