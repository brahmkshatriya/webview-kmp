# webview-kmp

Kotlin Multiplatform WebView abstraction for Compose that uses the WebView engine already provided
by the host platform. The library does not bundle Chromium, WebKit, Gecko, or another browser
engine.

The public API is backend-oriented so consumers can choose their preferred engine. On Linux,
Chromium-family and Firefox-family browsers are embedded as real Wayland clients through
`wayland-appview`; the browser engine itself is never bundled by this library.

## Artifacts

The project is split so platform-specific build machinery and native dependencies stay out of
unrelated targets:

| Artifact | Purpose |
| --- | --- |
| `webview-core` | Controller, state, capabilities, backend selection; no Compose or browser/native backend dependency |
| `webview-android` | Android framework WebView backend |
| `webview-ios` | iOS WKWebView backend |
| `webview-js` | Browser iframe backend |
| `webview-linux` | Linux AppView Chromium/Firefox backends |
| `webview-macos` | macOS WKWebView/AppKit backend |
| `webview-windows` | Windows WebView2 backend and loader preparation task |
| `webview-compose` | Multiplatform facade that re-exports `webview-core` and selects only the matching platform backend |

For the normal KMP Compose API, depend on `webview-compose`. Its Linux variant depends on
`webview-linux`, its Windows variant on `webview-windows`, and so on. Gradle therefore does not
resolve Wayland/AppView dependencies for a Windows target or WebView2 SDK machinery for Linux.

Consumers building a platform-specific integration can instead depend directly on `webview-core`
plus the one backend artifact they need. Backend artifacts expose `platformBackendProviders()` and
`PlatformWebView(...)`; the higher-level `webview-compose` facade preserves the common
`platformWebViewBackends()`, `createWebViewController()`, `rememberWebViewController()`, and
`WebView(...)` API.

## Current platform status

| Platform | System backend | Native engine | Status |
| --- | --- | --- | --- |
| Linux Native | AppView browser process | installed Chromium/Firefox family browser | Implemented and runtime-tested with Chrome and Firefox on Arch Linux |
| Android | `android.webkit.WebView` | installed Android WebView provider | Implemented and compile-tested |
| JavaScript browser | `<iframe>` | current browser | Implemented and compile-tested |
| iOS ARM64 | `WKWebView` | system WebKit | Implemented; requires an Apple host to compile/validate |
| iOS Simulator ARM64 | `WKWebView` | system WebKit | Implemented; requires an Apple host to compile/validate |
| Windows Native | WebView2 through Compose Native `NativeView` | installed Evergreen WebView2 Runtime | Implemented and cross-compiled on Linux; Windows runtime validation pending |
| macOS Native | `WKWebView` through Compose Native `AppKitView` | system WebKit | Implemented; Apple-host runtime validation pending |

Windows uses the installed WebView2 Runtime and renders it into Compose's framebuffer rather than
launching a separate Edge window. macOS uses Compose Native's real AppKit child-view interop and therefore keeps WKWebView's
native focus, keyboard/IME, accessibility, scrolling, gestures, context menus, and input behavior.

## Common Compose usage

```kotlin
@Composable
fun Browser() {
    val controller = rememberWebViewController(
        order = listOf(
            WebViewBackendId.Chromium,
            WebViewBackendId.Firefox,
            WebViewBackendId.System,
        ),
        config = WebViewConfig(
            initialUrl = "https://example.com",
            javaScript = JavaScriptMode.Enabled,
        ),
    )

    WebView(
        controller = controller,
        modifier = Modifier.fillMaxSize(),
    )
}
```

`rememberWebViewController` owns the controller for the composition and closes it when it leaves the
composition.

The controller is also usable outside Compose:

```kotlin
val controller = createWebViewController(
    order = listOf(WebViewBackendId.System),
)

controller.loadUrl("https://example.com")
controller.reload()

controller.evaluateJavaScript("document.title") { result ->
    println(result)
}

controller.close()
```

## Backend ordering

`WebViewBackendSelector` tries providers in exactly the requested order. A provider is skipped when
it is not registered, reports itself unavailable, lacks a required capability, or fails while being
created. If every requested provider fails, `NoWebViewBackendException` contains the attempted
backend IDs and reasons.

Linux registers three providers: `ChromiumWebViewBackend`, `FirefoxWebViewBackend`, and
`SystemWebViewBackend`. `System` preserves the common default and auto-selects Chromium first, then
Firefox. Consumers that need a specific browser family should request it explicitly:

```kotlin
val controller = createWebViewController(
    order = listOf(
        WebViewBackendId.Firefox,
        WebViewBackendId.Chromium,
    ),
)
```

## Capabilities

Backends expose `WebViewCapabilities`. Consumers can either inspect them after creation:

```kotlin
if (WebViewCapability.CustomRequestHeaders in controller.capabilities) {
    controller.loadUrl(
        "https://example.com",
        headers = mapOf("X-App" to "example"),
    )
}
```

or require features during selection:

```kotlin
val controller = createWebViewController(
    requiredCapabilities = setOf(
        WebViewCapability.CustomRequestHeaders,
        WebViewCapability.JavaScriptEvaluation,
    ),
)
```

Current capability flags cover custom request headers, custom user agents, JavaScript evaluation,
cross-origin JavaScript evaluation, navigation state, loading progress, JavaScript control, and
media-playback policy.

## Controller API

The common controller currently supports:

- `loadUrl(url, headers)`
- `loadHtml(html, baseUrl)`
- `reload()` and `stopLoading()`
- `goBack()` and `goForward()`
- `evaluateJavaScript(script, callback)`
- synchronous `state` snapshots
- reactive `states: StateFlow<WebViewState>`
- backend and capability inspection

`WebViewState` contains the current URL, page title, loading state/progress, back/forward
availability, and the latest main-page error when the platform can provide it.

Permissions, file pickers, downloads, web messaging, profiles/cookies, request interception, and
new-window handling are not part of the API yet.

## Linux AppView browser backends

Linux embeds an installed browser through `dev.brahmkshatriya.wayland:wayland-appview:0.1.0`.
AppView runs a private nested Wayland compositor inside the Compose Native view, so Chrome,
Chromium, Brave, Edge, Vivaldi, Firefox, LibreWolf, Floorp, Waterfox, Zen and compatible forks can
render directly inside the composable instead of opening a separate desktop window.

`ChromiumWebViewBackend` launches Chromium-family browsers with native Wayland/Ozone and `--app=...`
using a fresh per-controller profile. `FirefoxWebViewBackend` launches Firefox-family browsers with a
fresh profile and kiosk mode. Firefox's nested fullscreen request is intentionally ignored by
`WebView`, so kiosk mode fills only the WebView composable rather than making the host application
fullscreen.

The built-in executable search covers common fork names. Unlisted forks can be selected without a
library change:

```shell
WEBVIEW_KMP_CHROMIUM_EXECUTABLE=/path/to/chromium-fork ./your-app
WEBVIEW_KMP_FIREFOX_EXECUTABLE=/path/to/firefox-fork ./your-app
```

The Linux artifact no longer links WPE WebKit, so WPE is not required to compile, link, or run the
Chromium/Firefox AppView backends.

These backends embed a complete browser process and attach a page-control protocol to that same
process. Chromium-family browsers are controlled through the Chrome DevTools Protocol (CDP), while
Firefox-family browsers use WebDriver BiDi. The browser remains rendered through AppView; the
control connection is only used for page operations and state.

- custom user agent is supported for both browser families;
- disabling JavaScript is supported by the Firefox-family backend through its temporary profile;
- `loadUrl`, `reload`, `stopLoading`, `goBack`, and `goForward` operate on the existing embedded
  browser process instead of relaunching it;
- `loadHtml` writes an isolated temporary HTML file and opens it;
- `evaluateJavaScript` supports ordinary values, objects/arrays encoded as JSON, promises, and
  JavaScript errors on both browser families;
- Chromium navigation state comes from CDP's real navigation history; Firefox currently maintains
  the public back/forward baseline around BiDi navigation commands because Firefox BiDi does not
  expose an equivalent complete history snapshot;
- `loadUrl(url, headers)` supports custom request headers on both browser families. Headers are
  merged into that navigation's main document request only; they are not persisted to later
  navigations or automatically copied to subresources;
- loading progress is currently coarse (`0`, in-progress, complete) rather than byte-level network
  progress.

Each controller owns and removes its temporary browser profile on close. Browser sessions are
therefore isolated from the user's normal browser profile by default.

## Android

Android uses the framework `android.webkit.WebView`. The library does not depend on or package a
specific Chromium build. On Android 8.0+ the provider checks `WebView.getCurrentWebViewPackage()`
when reporting availability.

Native commands are marshalled to the attached WebView's UI thread. Commands issued before the
Compose view is attached are queued and applied when the native WebView is created.

## JavaScript browser target

The JS backend uses an iframe, so browser security rules intentionally remain in force:

- arbitrary request headers cannot be added to iframe navigations;
- the browser user agent cannot be overridden;
- JavaScript/DOM access to cross-origin pages is blocked by the same-origin policy;
- reliable iframe back/forward availability is not exposed by browsers, so those state flags remain
  false.

Use `requiredCapabilities` when common code depends on behavior that the iframe backend cannot
provide.

## Windows

Windows uses the system-installed Microsoft Edge WebView2 Runtime. The library does not bundle the
Edge/Chromium engine. It uses Microsoft's small `WebView2Loader.dll` bootstrap from the WebView2 SDK
to discover and initialize the installed Evergreen Runtime.

The backend follows the working Compose Native Windows prototype: WebView2 runs in a hidden native
host window, `CapturePreview` snapshots are decoded through Windows Imaging Component (WIC), and the
resulting BGRA pixels are rendered through Compose Native's CPU `NativeView`. Pointer, wheel,
keyboard, and focus events are forwarded back to the WebView2 host. Because the WebView is rendered
as a Compose image, ordinary Compose clipping, transforms, and content drawn above it continue to
work.

For a Windows application distribution, place `WebView2Loader.dll` next to the executable. This
project provides:

```shell
./gradlew :webview-windows:copyWindowsWebView2Loader
```

which writes the x64 loader and its license to `webview-windows/build/windows-runtime/`. The root
project keeps `./gradlew copyWindowsWebView2Loader` as a convenience alias. The loader is not the
browser runtime; `SystemWebViewBackend.availability()` still probes the machine for an installed
WebView2 Runtime and reports the backend unavailable when that runtime is missing.

The Windows backend currently supports custom user agents, enabling/disabling page JavaScript,
JavaScript evaluation, URL/title/loading state, and back/forward navigation. Arbitrary navigation
headers are not currently implemented, and loading progress is coarse (`0` while navigating, `1`
when complete). The `CapturePreview` path also prioritizes correct Compose integration over
high-frame-rate video performance; it is currently throttled to roughly 30 captures per second.

## macOS

macOS uses the system WebKit framework's `WKWebView`; no WebKit binary is packaged by this library.
The native view is hosted by Compose Native's `AppKitView` in background-placement mode. That lets
Compose content draw above the WebView while Compose/AppKit hit testing decides which side receives
input in overlapping regions.

The backend supports custom navigation headers, custom user agents, JavaScript execution, native
back/forward history, loading state/progress, disabling page JavaScript, and WebKit's media playback
user-gesture policy.

This target uses Compose Native `1.13.0-alpha10`, the first published version in this project line
that contains the `AppKitView` API. The Compose Native version can still be overridden without
editing the build script with `-PcomposeNativeVersion=<version>`.

## Validation

The following checks currently pass on the development Arch Linux host:

```shell
./gradlew \
    :webview-core:linuxX64Test \
    :webview-linux:linuxX64Test \
    :webview-windows:compileKotlinMingwX64 \
    :webview-compose:compileKotlinLinuxX64 \
    :webview-compose:compileKotlinMingwX64 \
    :webview-compose:compileAndroidMain \
    :webview-compose:compileKotlinJs
```

A separate Compose Native consumer was also linked and run against this project. Google Chrome
152 rendered through AppView's DMA-BUF path and Firefox 154 rendered through the `wl_shm` fallback.
Both engines were then driven through the public controller API without relaunching the browser:
navigation, title/URL state, back/forward, reload, stop, promise-aware JavaScript evaluation, and
JavaScript exception reporting were exercised.

`webview-core/commonTest` covers backend selection. `webview-linux/linuxX64Test` contains the
reusable `WebViewController` behavioral contract and adapts its CDP and BiDi sessions to it.

The iOS and macOS implementations still require Apple-host runtime validation. macOS now resolves
against the published Compose Native `1.13.0-alpha10` AppKit interop API.
