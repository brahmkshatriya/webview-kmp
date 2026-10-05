plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.brahmkshatriya.compose")
    id("com.vanniktech.maven.publish")
}

val composeNativeVersion = providers.gradleProperty("composeNativeVersion").get()
val waylandAppViewVersion = providers.gradleProperty("waylandAppViewVersion").get()
val serializationJsonVersion = providers.gradleProperty("serializationJsonVersion").get()
val metadataOnly = providers.gradleProperty("webviewMetadataOnly").map(String::toBoolean).orElse(false).get()
val linuxInteropDir = layout.projectDirectory.dir("src/nativeInterop/linux")
val linuxInteropIncludeDir = linuxInteropDir.dir("include")
val hostArchitecture = System.getProperty("os.arch").lowercase()
val hostIsX64 = hostArchitecture == "amd64" || hostArchitecture == "x86_64"
val hostIsArm64 = hostArchitecture == "aarch64" || hostArchitecture == "arm64"
val konanDependenciesDir = file("${System.getProperty("user.home")}/.konan/dependencies")
val linuxArm64CrossToolchain =
    konanDependenciesDir.listFiles()
        ?.filter { it.isDirectory && it.name.startsWith("aarch64-unknown-linux-gnu-gcc-") }
        ?.maxByOrNull(File::lastModified)
val linuxX64BridgeDir = layout.buildDirectory.dir("native-bridge/linuxX64")
val linuxX64BridgeObject = linuxX64BridgeDir.map { it.file("system_webview_wpe.o") }
val linuxX64BridgeArchive = linuxX64BridgeDir.map { it.file("libwebview-kmp-wpe.a") }
val linuxArm64BridgeDir = layout.buildDirectory.dir("native-bridge/linuxArm64")
val linuxArm64BridgeObject = linuxArm64BridgeDir.map { it.file("system_webview_wpe.o") }
val linuxArm64BridgeArchive = linuxArm64BridgeDir.map { it.file("libwebview-kmp-wpe.a") }
val linuxArm64HeaderOverlay = linuxArm64BridgeDir.map { it.dir("include-overlay") }

val compileLinuxX64WpeBridge = tasks.register<Exec>("compileLinuxX64WpeBridge") {
    inputs.files(
        linuxInteropDir.file("system_webview_wpe.cpp"),
        linuxInteropIncludeDir.file("system_webview_wpe.h"),
    )
    outputs.file(linuxX64BridgeObject)
    doFirst { linuxX64BridgeDir.get().asFile.mkdirs() }
    val compiler =
        providers.environmentVariable("WEBVIEW_KMP_LINUX_X64_CXX").orNull
            ?: if (hostIsX64) "c++" else ""
    val pkgConfig =
        providers.environmentVariable("WEBVIEW_KMP_LINUX_X64_PKG_CONFIG").orNull
            ?: if (hostIsX64) "pkg-config" else ""
    environment("WEBVIEW_KMP_WPE_CXX", compiler)
    environment("WEBVIEW_KMP_WPE_PKG_CONFIG", pkgConfig)
    commandLine(
        "bash",
        "-lc",
        """
            set -euo pipefail
            if [[ -z "${'$'}WEBVIEW_KMP_WPE_CXX" || -z "${'$'}WEBVIEW_KMP_WPE_PKG_CONFIG" ]]; then
              echo "linuxX64 WPE requires an x86_64 Linux host, or WEBVIEW_KMP_LINUX_X64_CXX and WEBVIEW_KMP_LINUX_X64_PKG_CONFIG for a configured cross-toolchain." >&2
              exit 2
            fi
            "${'$'}WEBVIEW_KMP_WPE_CXX" -std=c++17 -O2 -fPIC -w \
              $("${'$'}WEBVIEW_KMP_WPE_PKG_CONFIG" --cflags wpe-webkit-2.0) \
              -I${linuxInteropIncludeDir.asFile.absolutePath} \
              -c ${linuxInteropDir.file("system_webview_wpe.cpp").asFile.absolutePath} \
              -o ${linuxX64BridgeObject.get().asFile.absolutePath}
        """.trimIndent(),
    )
}

val archiveLinuxX64WpeBridge = tasks.register<Exec>("archiveLinuxX64WpeBridge") {
    dependsOn(compileLinuxX64WpeBridge)
    inputs.file(linuxX64BridgeObject)
    outputs.file(linuxX64BridgeArchive)
    doFirst { linuxX64BridgeDir.get().asFile.mkdirs() }
    val archiver =
        providers.environmentVariable("WEBVIEW_KMP_LINUX_X64_AR").orNull
            ?: if (hostIsX64) "ar" else ""
    environment("WEBVIEW_KMP_WPE_AR", archiver)
    commandLine(
        "bash",
        "-lc",
        """
            set -euo pipefail
            if [[ -z "${'$'}WEBVIEW_KMP_WPE_AR" ]]; then
              echo "linuxX64 WPE requires WEBVIEW_KMP_LINUX_X64_AR when cross-compiling." >&2
              exit 2
            fi
            "${'$'}WEBVIEW_KMP_WPE_AR" rcs \
              ${linuxX64BridgeArchive.get().asFile.absolutePath} \
              ${linuxX64BridgeObject.get().asFile.absolutePath}
        """.trimIndent(),
    )
}

val compileLinuxArm64WpeBridge = tasks.register<Exec>("compileLinuxArm64WpeBridge") {
    inputs.files(
        linuxInteropDir.file("system_webview_wpe.cpp"),
        linuxInteropIncludeDir.file("system_webview_wpe.h"),
    )
    outputs.file(linuxArm64BridgeObject)
    doFirst { linuxArm64BridgeDir.get().asFile.mkdirs() }
    val compilerOverride = providers.environmentVariable("WEBVIEW_KMP_LINUX_ARM64_CXX").orNull
    val pkgConfigOverride = providers.environmentVariable("WEBVIEW_KMP_LINUX_ARM64_PKG_CONFIG").orNull
    val useKonanCrossCompiler = !hostIsArm64 && compilerOverride == null && pkgConfigOverride == null
    val compiler =
        compilerOverride
            ?: if (hostIsArm64) {
                "c++"
            } else {
                linuxArm64CrossToolchain
                    ?.resolve("bin/aarch64-unknown-linux-gnu-g++")
                    ?.absolutePath
                    .orEmpty()
            }
    val pkgConfig = pkgConfigOverride ?: if (hostIsArm64 || useKonanCrossCompiler) "pkg-config" else ""
    environment("WEBVIEW_KMP_WPE_CXX", compiler)
    environment("WEBVIEW_KMP_WPE_PKG_CONFIG", pkgConfig)
    environment(
        "WEBVIEW_KMP_WPE_HEADER_OVERLAY",
        if (useKonanCrossCompiler) linuxArm64HeaderOverlay.get().asFile.absolutePath else "",
    )
    commandLine(
        "bash",
        "-lc",
        """
            set -euo pipefail
            if [[ -z "${'$'}WEBVIEW_KMP_WPE_CXX" || -z "${'$'}WEBVIEW_KMP_WPE_PKG_CONFIG" ]]; then
              echo "linuxArm64 WPE requires an ARM64 Linux host, or WEBVIEW_KMP_LINUX_ARM64_CXX and WEBVIEW_KMP_LINUX_ARM64_PKG_CONFIG for a configured cross-toolchain." >&2
              exit 2
            fi
            extra_headers=()
            if [[ -n "${'$'}WEBVIEW_KMP_WPE_HEADER_OVERLAY" ]]; then
              rm -rf "${'$'}WEBVIEW_KMP_WPE_HEADER_OVERLAY"
              mkdir -p "${'$'}WEBVIEW_KMP_WPE_HEADER_OVERLAY"
              for directory in EGL GL KHR SDL3 xkbcommon; do
                ln -s "/usr/include/${'$'}directory" "${'$'}WEBVIEW_KMP_WPE_HEADER_OVERLAY/${'$'}directory"
              done
              extra_headers+=("-I${'$'}WEBVIEW_KMP_WPE_HEADER_OVERLAY")
            fi
            "${'$'}WEBVIEW_KMP_WPE_CXX" -std=c++17 -O2 -fPIC -w \
              $("${'$'}WEBVIEW_KMP_WPE_PKG_CONFIG" --cflags wpe-webkit-2.0) \
              "${'$'}{extra_headers[@]}" \
              -I${linuxInteropIncludeDir.asFile.absolutePath} \
              -c ${linuxInteropDir.file("system_webview_wpe.cpp").asFile.absolutePath} \
              -o ${linuxArm64BridgeObject.get().asFile.absolutePath}
        """.trimIndent(),
    )
}

val archiveLinuxArm64WpeBridge = tasks.register<Exec>("archiveLinuxArm64WpeBridge") {
    dependsOn(compileLinuxArm64WpeBridge)
    inputs.file(linuxArm64BridgeObject)
    outputs.file(linuxArm64BridgeArchive)
    doFirst { linuxArm64BridgeDir.get().asFile.mkdirs() }
    val archiver =
        providers.environmentVariable("WEBVIEW_KMP_LINUX_ARM64_AR").orNull
            ?: if (hostIsArm64) {
                "ar"
            } else {
                linuxArm64CrossToolchain
                    ?.resolve("bin/aarch64-unknown-linux-gnu-ar")
                    ?.absolutePath
                    .orEmpty()
            }
    environment("WEBVIEW_KMP_WPE_AR", archiver)
    commandLine(
        "bash",
        "-lc",
        """
            set -euo pipefail
            if [[ -z "${'$'}WEBVIEW_KMP_WPE_AR" ]]; then
              echo "linuxArm64 WPE requires WEBVIEW_KMP_LINUX_ARM64_AR when cross-compiling." >&2
              exit 2
            fi
            "${'$'}WEBVIEW_KMP_WPE_AR" rcs \
              ${linuxArm64BridgeArchive.get().asFile.absolutePath} \
              ${linuxArm64BridgeObject.get().asFile.absolutePath}
        """.trimIndent(),
    )
}

kotlin {
    explicitApi()
    linuxX64 {
        compilations.getByName("main") {
            cinterops.create("systemWpe") {
                defFile(linuxInteropDir.file("system-webview-wpe.def").asFile)
                header(linuxInteropIncludeDir.file("system_webview_wpe.h").asFile)
                includeDirs(linuxInteropIncludeDir.asFile)
                packageName("dev.brahmkshatriya.webview.internal.wpe")
                if (!metadataOnly) {
                    extraOpts(
                        "-libraryPath", linuxX64BridgeDir.get().asFile.absolutePath,
                        "-staticLibrary", "libwebview-kmp-wpe.a",
                    )
                }
            }
        }
    }
    linuxArm64 {
        compilations.getByName("main") {
            cinterops.create("systemWpe") {
                defFile(linuxInteropDir.file("system-webview-wpe.def").asFile)
                header(linuxInteropIncludeDir.file("system_webview_wpe.h").asFile)
                includeDirs(linuxInteropIncludeDir.asFile)
                packageName("dev.brahmkshatriya.webview.internal.wpe")
                if (!metadataOnly) {
                    extraOpts(
                        "-libraryPath", linuxArm64BridgeDir.get().asFile.absolutePath,
                        "-staticLibrary", "libwebview-kmp-wpe.a",
                    )
                }
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
        linuxX64Main {
            kotlin.srcDir("src/linuxMain/kotlin")
            dependencies {
                api(project(":webview-core"))
                implementation("dev.brahmkshatriya.wayland:wayland-appview:$waylandAppViewVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationJsonVersion")
            }
        }
        linuxArm64Main {
            kotlin.srcDir("src/linuxMain/kotlin")
            dependencies {
                api(project(":webview-core"))
                implementation("dev.brahmkshatriya.wayland:wayland-appview:$waylandAppViewVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationJsonVersion")
            }
        }
        linuxX64Test.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.matching { it.name == "cinteropSystemWpeLinuxX64" }.configureEach {
    if (!metadataOnly) dependsOn(archiveLinuxX64WpeBridge)
}

tasks.matching { it.name == "cinteropSystemWpeLinuxArm64" }.configureEach {
    if (!metadataOnly) dependsOn(archiveLinuxArm64WpeBridge)
}
