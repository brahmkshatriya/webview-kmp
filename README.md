# webview-kmp

[![Maven Central](https://img.shields.io/maven-central/v/dev.brahmkshatriya.webview/webview-compose.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/dev.brahmkshatriya.webview/webview-compose)

A Kotlin Multiplatform WebView for Compose.

It uses the browser engine already available on each platform. It does not bundle Chromium, WebKit, Firefox, or another browser engine into your app.

## Add it to your project

For most apps, add only `webview-compose` to `commonMain`:

```kotlin
kotlin {
    sourceSets.commonMain.dependencies {
        implementation("dev.brahmkshatriya.webview:webview-compose:<version>")
    }
}
```

You do not need to add each platform implementation yourself. Gradle selects the implementations needed by your targets.

## Quick start

For a simple page:

```kotlin
WebView(
    url = "https://example.com",
    modifier = Modifier.fillMaxSize(),
)
```

Use a controller when you need navigation, state, JavaScript, cookies, profiles, or messaging:

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

`rememberWebViewController` closes the WebView automatically when it leaves the composition.

## Everyday API

Navigation:

```kotlin
controller.loadUrl("https://example.com")
controller.reload()
controller.stopLoading()
controller.goBack()
controller.goForward()
```

Load HTML:

```kotlin
controller.loadHtml(
    html = "<h1>Hello</h1>",
    baseUrl = "https://example.com",
)
```

Read state in Compose:

```kotlin
val state by controller.collectState()

Text(state.title.orEmpty())

if (state.isLoading) {
    LinearProgressIndicator(progress = { state.progress })
}
```

The state also includes the current URL and back/forward availability.

## JavaScript

From a coroutine:

```kotlin
when (val result = controller.evaluateJavaScript("document.title")) {
    is JavaScriptResult.Value -> println(result.json)
    is JavaScriptResult.Error -> println(result.message)
}
```

Useful navigation helpers:

```kotlin
controller.loadUrl("https://example.com/login")

val callbackUrl = controller.awaitUrl {
    it.startsWith("https://example.com/callback")
}

controller.awaitLoaded()
```

For event-style handling, collect `controller.events`.

## Profiles

Profiles let you choose how browser data is shared and persisted:

```kotlin
WebViewConfig(
    profile = WebViewProfile.Default,
)
```

Available modes:

```kotlin
WebViewProfile.Default
WebViewProfile.Ephemeral
WebViewProfile.Persistent("account-a")
```

- `Default` uses the backend's normal app-owned browser session.
- `Ephemeral` keeps the session isolated and disposable.
- `Persistent(name)` gives you a stable isolated profile for that name.

Named persistent profiles are supported by WPE WebKit, Linux Chromium/Firefox, Windows WebView2, Android WebView when AndroidX WebKit multi-profile support is available, and Apple WKWebView on iOS 17+ / macOS 14+.

## Cookies

Use the cookie jar attached to the controller's profile:

```kotlin
controller.cookies.set(
    url = "https://example.com",
    cookie = WebViewCookie(
        name = "session",
        value = "value",
        secure = true,
        httpOnly = true,
    ),
)

val cookies = controller.cookies.get("https://example.com")
controller.cookies.clear()
```

If your app requires cookie access, request it when creating the controller:

```kotlin
val controller = rememberWebViewController(
    requiredCapabilities = setOf(WebViewCapability.Cookies),
)
```

Android may not expose all cookie metadata when reading cookies, so some fields can be `null`. The JavaScript iframe backend does not provide native cookie access.

## Web messaging

On supported native backends, page JavaScript can send a string to the app:

```javascript
window.webviewKmp.postMessage("ready")
```

Receive it in Kotlin:

```kotlin
controller.messages.collect { message ->
    println(message.data)
}
```

Send a message back:

```kotlin
controller.postMessage("refresh")
```

The page can receive app messages through `window.webviewKmp.onmessage` or the `webview-kmp-message` window event.

## Navigation interception

Use a navigation handler for callback URLs, custom schemes, or links that should leave the WebView:

```kotlin
val controller = rememberWebViewController(
    config = WebViewConfig(
        initialUrl = loginUrl,
        navigationHandler = WebViewNavigationHandler { request ->
            when {
                request.url.startsWith(callbackUrl) ->
                    WebViewNavigationDecision.Cancel

                request.url.startsWith("mailto:") ->
                    WebViewNavigationDecision.OpenExternally

                else ->
                    WebViewNavigationDecision.Allow
            }
        },
    ),
    requiredCapabilities = setOf(
        WebViewCapability.NavigationInterception,
    ),
)
```

Navigation interception is currently provided by Android WebView, WKWebView, WPE WebKit, and WebView2.

## User scripts

Install JavaScript that should run on each page:

```kotlin
val controller = rememberWebViewController(
    config = WebViewConfig(
        initialUrl = "https://example.com",
        userScripts = listOf(
            WebViewUserScript(
                source = "window.myApp = { ready: true }",
                injectionTime = WebViewUserScriptInjectionTime.DocumentStart,
            ),
        ),
    ),
)
```

Request `WebViewCapability.UserScripts` when this is required.

## Other configuration

Custom user agent:

```kotlin
WebViewConfig(
    userAgent = UserAgent.Custom("MyApp"),
)
```

Disable JavaScript:

```kotlin
WebViewConfig(
    javaScript = JavaScriptMode.Disabled,
)
```

Custom navigation headers where supported:

```kotlin
controller.loadUrl(
    url = "https://example.com",
    headers = mapOf("X-App-Version" to "1"),
)
```

When a feature is mandatory, list it in `requiredCapabilities`. The library will avoid selecting a backend that cannot provide it.

## Platform support

| Platform | Backend |
| --- | --- |
| Android | Android WebView |
| iOS | WKWebView |
| macOS | WKWebView |
| Windows | Microsoft Edge WebView2 |
| Linux | WPE WebKit by default; Chromium/Firefox are optional |
| JavaScript | iframe |

### Linux

`WebViewBackendId.System` means WPE WebKit on Linux.

To prefer an installed browser:

```kotlin
val controller = rememberWebViewController(
    order = listOf(
        WebViewBackendId.Chromium,
        WebViewBackendId.Firefox,
    ),
)
```

Common Chromium- and Firefox-based installations are detected automatically.

For unusual locations:

```shell
WEBVIEW_KMP_CHROMIUM_EXECUTABLE=/path/to/chromium
WEBVIEW_KMP_FIREFOX_EXECUTABLE=/path/to/firefox
```

Chromium/Firefox backends use webview-kmp-owned profiles and do not reuse or modify the user's normal browser profile.

### Windows

Windows uses the installed Edge WebView2 Runtime.

Your final app also needs `WebView2Loader.dll` next to the executable. The Windows Maven artifact publishes the loader with the `webview2-loader` classifier.

When building this repository, this helper copies the loader and license into one directory:

```shell
./gradlew copyWindowsWebView2Loader
```

### Apple

iOS and macOS use the system WKWebView. Named persistent profiles require iOS 17+ or macOS 14+. Ephemeral profiles use WebKit's non-persistent data store.

### JavaScript

The JS target uses an iframe, so normal browser same-origin restrictions apply. Cross-origin page contents, native cookies, and arbitrary cross-origin script injection are not available.

## Feature overview

The common API includes:

- URL and HTML loading;
- reload, stop, back, and forward;
- state and progress;
- JavaScript evaluation;
- navigation await helpers and events;
- custom user agents;
- custom request headers where supported;
- cookies where supported;
- default, ephemeral, and named persistent profiles where supported;
- navigation interception where supported;
- document user scripts where supported;
- app/page messaging where supported;
- Linux backend selection.

Not yet exposed as public cross-platform APIs:

- downloads;
- file pickers;
- permission requests;
- new-window / popup handling.

## Advanced: core API only

Most apps should use `webview-compose`.

If you are building your own UI integration, use `webview-core` plus the platform modules you need:

```text
webview-core
webview-android
webview-ios
webview-js
webview-linux
webview-macos
webview-windows
```

## Status

Linux Chromium and Firefox have runtime integration tests. WPE WebKit is compile/link tested in this library. Android, JavaScript, Windows, iOS, and macOS compile successfully; broader real-device coverage is still ongoing on some platforms.
