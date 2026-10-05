# webview-kmp

A Kotlin Multiplatform WebView for Compose.

`webview-kmp` uses the browser engine that is already available on each platform. It does not bundle
Chromium, WebKit, Firefox, or another browser engine into your app.

Supported targets:

| Platform | What it uses |
| --- | --- |
| Android | Android WebView |
| iOS | WKWebView |
| macOS | WKWebView |
| Windows | Microsoft Edge WebView2 Runtime |
| Linux | WPE WebKit by default; installed Chromium/Firefox are optional alternatives |
| JavaScript | iframe |

## Add it to your project

For most projects, add only `webview-compose` to `commonMain`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.brahmkshatriya.webview:webview-compose:<version>")
        }
    }
}
```

That is enough even if your project targets several platforms.

You do **not** need to add Android, Linux, macOS, Windows, and other implementations separately.
Gradle selects only the implementation needed by each target in your project.

For example, an Android + iOS project will not pull in the Linux or Windows implementation. A
Windows-only project will not pull in Linux browser dependencies.

## Basic usage

```kotlin
@Composable
fun Browser() {
    val controller = rememberWebViewController(
        config = WebViewConfig(
            initialUrl = "https://example.com",
        ),
    )

    WebView(
        controller = controller,
        modifier = Modifier.fillMaxSize(),
    )
}
```

`rememberWebViewController` automatically closes the WebView when it leaves the composition.

## Navigation

The controller can be used from your UI code:

```kotlin
controller.loadUrl("https://example.com")
controller.reload()
controller.stopLoading()
controller.goBack()
controller.goForward()
```

You can also load HTML directly:

```kotlin
controller.loadHtml(
    html = "<h1>Hello</h1>",
    baseUrl = "https://example.com",
)
```

## Reading WebView state

The current state is available as both a snapshot and a `StateFlow`:

```kotlin
val state = controller.state

println(state.url)
println(state.title)
println(state.isLoading)
println(state.progress)
println(state.canGoBack)
println(state.canGoForward)
```

In Compose:

```kotlin
val state by controller.states.collectAsState()

if (state.isLoading) {
    LinearProgressIndicator(progress = { state.progress })
}
```

## JavaScript

```kotlin
controller.evaluateJavaScript("document.title") { result ->
    when (result) {
        is JavaScriptResult.Value -> println(result.value)
        is JavaScriptResult.Error -> println(result.message)
    }
}
```

JavaScript can also be disabled when creating the controller:

```kotlin
val controller = rememberWebViewController(
    config = WebViewConfig(
        javaScript = JavaScriptMode.Disabled,
    ),
)
```

## Custom user agent

```kotlin
val controller = rememberWebViewController(
    config = WebViewConfig(
        userAgent = UserAgent.Custom("MyApp/1.0"),
    ),
)
```

## Request headers

Platforms that support custom navigation headers can use:

```kotlin
controller.loadUrl(
    url = "https://example.com",
    headers = mapOf(
        "Authorization" to "Bearer token",
    ),
)
```

If your app requires a feature that is not available everywhere, you can ask for it when creating
the controller:

```kotlin
val controller = rememberWebViewController(
    requiredCapabilities = setOf(
        WebViewCapability.CustomRequestHeaders,
        WebViewCapability.JavaScriptEvaluation,
    ),
)
```

This prevents the library from selecting an implementation that cannot provide those features.

## Choosing a browser on Linux

By default, Linux uses the system WebView backend: WPE WebKit.

You can also choose an installed Chromium-based or Firefox-based browser explicitly.

To prefer Firefox:

```kotlin
val controller = rememberWebViewController(
    order = listOf(
        WebViewBackendId.Firefox,
        WebViewBackendId.Chromium,
    ),
)
```

To prefer Chromium:

```kotlin
val controller = rememberWebViewController(
    order = listOf(
        WebViewBackendId.Chromium,
        WebViewBackendId.Firefox,
    ),
)
```

`WebViewBackendId.System` always means WPE WebKit on Linux. It does not fall back to Chrome or
Firefox.

Common Chrome, Chromium, Edge, Brave, Vivaldi, Firefox, LibreWolf, Floorp, Waterfox, and Zen
installations are detected automatically.

For an unusual browser location, set one of these environment variables before starting the app:

```shell
WEBVIEW_KMP_CHROMIUM_EXECUTABLE=/path/to/chromium
WEBVIEW_KMP_FIREFOX_EXECUTABLE=/path/to/firefox
```

## Windows setup

Windows uses the installed Microsoft Edge WebView2 Runtime.

The browser runtime itself is **not** bundled with this library. Most current Windows installations
already have it.

Your application also needs Microsoft's small `WebView2Loader.dll` next to the final `.exe`.

The Windows Maven artifact publishes that DLL and Microsoft's license alongside the Kotlin/Native
library. They use the classifiers `webview2-loader` and `webview2-license`, so packaging tools can
resolve the loader directly from Maven instead of cloning this repository.

If you are building this repository itself, the convenience task below copies both files into one
directory:

```shell
./gradlew copyWindowsWebView2Loader
```

The files are written to:

```text
webview-windows/build/windows-runtime/
├── WebView2Loader.dll
└── WebView2-LICENSE.txt
```

Copy `WebView2Loader.dll` next to your application executable when packaging your Windows app.

## Platform notes

### Android

Uses the WebView provider installed on the device. No browser engine is packaged by this library.

### iOS and macOS

Use Apple's system WKWebView. No WebKit binary is packaged by this library.

macOS requires Compose Native `1.13.0-alpha10` or newer because that release added native AppKit
view support.

### Linux

The default `System` backend uses WPE WebKit. WPE WebKit must be installed on the build and target
system.

Chromium and Firefox are optional alternative backends. When one of those is selected, the browser
runs with an isolated temporary profile, so it does not reuse or modify the user's normal browser
profile.

### JavaScript

Uses an iframe, so normal browser security rules apply. In particular, scripts cannot access the
contents of unrelated cross-origin pages.

### Windows

Requires the Microsoft Edge WebView2 Runtime to be installed.

Rendering is currently optimized for normal application pages rather than high-frame-rate video or
animation.

## Available features

The common API currently includes:

- loading URLs;
- loading HTML;
- reload and stop;
- back and forward navigation;
- JavaScript evaluation;
- URL and page title state;
- loading state and progress;
- custom user agents;
- optional JavaScript disabling;
- custom request headers where supported;
- selecting a preferred browser implementation.

Features such as downloads, file pickers, permissions, cookies/profiles, web messaging, request
interception, and new-window handling are not part of the public API yet.

## Advanced: use only the core API

Most applications should depend on `webview-compose` and stop there.

If you are building your own integration and do not want the Compose WebView UI, you can instead use
`webview-core` together with a specific platform implementation:

```text
webview-core
webview-android
webview-ios
webview-js
webview-linux
webview-macos
webview-windows
```

This is an advanced use case. Normal multiplatform applications do not need to declare these
modules individually.

## Current status

Linux has been runtime-tested with Chrome and Firefox. The WPE WebKit renderer is based on the
runtime-tested Compose Native WPE integration and is compile/link-tested in this library.

Android, JavaScript, Windows, iOS, and macOS implementations compile successfully. Windows, iOS,
and macOS still need broader real-device/runtime testing before the library should be considered
stable.
