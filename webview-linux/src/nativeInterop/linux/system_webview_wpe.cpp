#define GL_GLEXT_PROTOTYPES

#include "system_webview_wpe.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GL/gl.h>
#include <GL/glext.h>
#include <SDL3/SDL.h>
#include <gio/gio.h>
#include <glib-object.h>
#include <glib.h>
#include <wpe/wpe-platform.h>
#include <wpe/headless/wpe-headless.h>
#include <wpe/WPEBufferDMABuf.h>
#include <wpe/WPEBufferSHM.h>
#include <wpe/WPEEvent.h>
#include <wpe/webkit.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>

struct KtnWpeWebView {
    WPEDisplay *display = nullptr;
    WPEView *wpe_view = nullptr;
    WebKitWebView *web_view = nullptr;
    WebKitNetworkSession *owned_network_session = nullptr;
    KtnWpeNavigationCallback navigation_callback = nullptr;
    void *navigation_user_data = nullptr;
    KtnWpeMessageCallback message_callback = nullptr;
    void *message_user_data = nullptr;
    WPEBuffer *buffer = nullptr;
    GLuint texture = 0;
    GLuint framebuffer = 0;
    int texture_width = 0;
    int texture_height = 0;
    SDL_Cursor *custom_cursor = nullptr;
    bool texture_ready = false;
    bool reported_buffer = false;
    int width = 1;
    int height = 1;
    int logical_width = 1;
    int logical_height = 1;
    float scale = 1.0f;
    bool debug = false;
    int debug_width = 0;
    int debug_height = 0;
    std::string pending_uri;
    char error[512] = {};
};

struct KtnWpeJavaScriptRequest {
    KtnWpeJavaScriptCallback callback = nullptr;
    void *user_data = nullptr;
};

struct KtnWpeCookieRequest {
    KtnWpeCookieCallback callback = nullptr;
    void *user_data = nullptr;
    SoupCookie *cookie = nullptr;
};

static std::string ktn_wpe_json_string(const char *value) {
    if (!value) return "null";
    std::string output = "\"";
    for (const unsigned char ch : std::string(value)) {
        switch (ch) {
            case '\\': output += "\\\\"; break;
            case '"': output += "\\\""; break;
            case '\b': output += "\\b"; break;
            case '\f': output += "\\f"; break;
            case '\n': output += "\\n"; break;
            case '\r': output += "\\r"; break;
            case '\t': output += "\\t"; break;
            default:
                if (ch < 0x20) {
                    char escaped[7] = {};
                    std::snprintf(escaped, sizeof(escaped), "\\u%04x", ch);
                    output += escaped;
                } else {
                    output.push_back(static_cast<char>(ch));
                }
        }
    }
    output += '"';
    return output;
}

static WebKitCookieManager *ktn_wpe_webview_cookie_manager(KtnWpeWebView *view) {
    if (!view || !view->web_view) return nullptr;
    WebKitNetworkSession *session = webkit_web_view_get_network_session(view->web_view);
    return session ? webkit_network_session_get_cookie_manager(session) : nullptr;
}

static SDL_Cursor *ktn_wpe_webview_system_cursor(SDL_SystemCursor type) {
    static SDL_Cursor *cursors[SDL_SYSTEM_CURSOR_COUNT] = {};
    if (!cursors[type]) cursors[type] = SDL_CreateSystemCursor(type);
    return cursors[type];
}

static SDL_SystemCursor ktn_wpe_webview_cursor_type(const char *name) {
    if (!std::strcmp(name, "pointer")) return SDL_SYSTEM_CURSOR_POINTER;
    if (!std::strcmp(name, "text") || !std::strcmp(name, "vertical-text")) {
        return SDL_SYSTEM_CURSOR_TEXT;
    }
    if (!std::strcmp(name, "crosshair") || !std::strcmp(name, "cell")) {
        return SDL_SYSTEM_CURSOR_CROSSHAIR;
    }
    if (!std::strcmp(name, "wait")) return SDL_SYSTEM_CURSOR_WAIT;
    if (!std::strcmp(name, "progress")) return SDL_SYSTEM_CURSOR_PROGRESS;
    if (!std::strcmp(name, "not-allowed") || !std::strcmp(name, "no-drop")) {
        return SDL_SYSTEM_CURSOR_NOT_ALLOWED;
    }
    if (
        !std::strcmp(name, "e-resize") || !std::strcmp(name, "w-resize") ||
        !std::strcmp(name, "ew-resize") || !std::strcmp(name, "col-resize")
    ) return SDL_SYSTEM_CURSOR_EW_RESIZE;
    if (
        !std::strcmp(name, "n-resize") || !std::strcmp(name, "s-resize") ||
        !std::strcmp(name, "ns-resize") || !std::strcmp(name, "row-resize")
    ) return SDL_SYSTEM_CURSOR_NS_RESIZE;
    if (
        !std::strcmp(name, "ne-resize") || !std::strcmp(name, "sw-resize") ||
        !std::strcmp(name, "nesw-resize")
    ) return SDL_SYSTEM_CURSOR_NESW_RESIZE;
    if (
        !std::strcmp(name, "nw-resize") || !std::strcmp(name, "se-resize") ||
        !std::strcmp(name, "nwse-resize")
    ) return SDL_SYSTEM_CURSOR_NWSE_RESIZE;
    if (
        !std::strcmp(name, "move") || !std::strcmp(name, "all-scroll") ||
        !std::strcmp(name, "grab") || !std::strcmp(name, "grabbing")
    ) return SDL_SYSTEM_CURSOR_MOVE;
    return SDL_SYSTEM_CURSOR_DEFAULT;
}

static void ktn_wpe_webview_set_cursor_from_name(WPEView *wpe_view, const char *name) {
    auto *view = static_cast<KtnWpeWebView *>(
        g_object_get_data(G_OBJECT(wpe_view), "webview-kmp-wpe")
    );
    if (!view || !name) return;
    if (view->custom_cursor) {
        SDL_DestroyCursor(view->custom_cursor);
        view->custom_cursor = nullptr;
    }
    if (!std::strcmp(name, "none")) {
        SDL_HideCursor();
        return;
    }
    SDL_ShowCursor();
    if (SDL_Cursor *cursor = ktn_wpe_webview_system_cursor(ktn_wpe_webview_cursor_type(name))) {
        SDL_SetCursor(cursor);
    }
}

static void ktn_wpe_webview_set_cursor_from_bytes(
    WPEView *wpe_view,
    GBytes *bytes,
    unsigned int width,
    unsigned int height,
    unsigned int stride,
    unsigned int hotspot_x,
    unsigned int hotspot_y
) {
    auto *view = static_cast<KtnWpeWebView *>(
        g_object_get_data(G_OBJECT(wpe_view), "webview-kmp-wpe")
    );
    if (!view || !bytes || !width || !height) return;
    std::size_t byte_count = 0;
    const void *pixels = g_bytes_get_data(bytes, &byte_count);
    if (!pixels || byte_count < static_cast<std::size_t>(height) * stride) return;
    SDL_Surface *surface = SDL_CreateSurfaceFrom(
        static_cast<int>(width),
        static_cast<int>(height),
        SDL_PIXELFORMAT_ARGB8888,
        const_cast<void *>(pixels),
        static_cast<int>(stride)
    );
    if (!surface) return;
    SDL_Cursor *cursor = SDL_CreateColorCursor(
        surface,
        static_cast<int>(hotspot_x),
        static_cast<int>(hotspot_y)
    );
    SDL_DestroySurface(surface);
    if (!cursor) return;
    if (view->custom_cursor) SDL_DestroyCursor(view->custom_cursor);
    view->custom_cursor = cursor;
    SDL_ShowCursor();
    SDL_SetCursor(cursor);
}

static void ktn_wpe_webview_set_error(KtnWpeWebView *view, const char *message) {
    if (!view || view->error[0]) return;
    std::snprintf(view->error, sizeof(view->error), "%s", message);
    if (view->debug) std::fprintf(stderr, "webview-kmp WPE: %s\n", message);
}

static void ktn_wpe_webview_release_buffer(KtnWpeWebView *view) {
    if (!view) return;
    if (view->buffer) {
        g_object_unref(view->buffer);
        view->buffer = nullptr;
    }
}

static void ktn_wpe_webview_buffer_rendered(WPEView *, WPEBuffer *buffer, void *data) {
    KtnWpeWebView *view = static_cast<KtnWpeWebView *>(data);
    if (!view || !buffer) return;
    const int image_width = wpe_buffer_get_width(buffer);
    const int image_height = wpe_buffer_get_height(buffer);
    if (!view->reported_buffer) {
        if (view->debug) {
            std::fprintf(
                stderr,
                "webview-kmp WPE: received %s buffer %dx%d\n",
                WPE_IS_BUFFER_DMA_BUF(buffer) ? "DMA-BUF" :
                    (WPE_IS_BUFFER_SHM(buffer) ? "shared-memory" : "unknown"),
                image_width,
                image_height
            );
        }
        view->reported_buffer = true;
    }
    if (
        view->debug &&
        (view->debug_width != view->width || view->debug_height != view->height)
    ) {
        std::fprintf(
            stderr,
            "webview-kmp WPE: exported image %dx%d for %dx%d\n",
            image_width,
            image_height,
            view->width,
            view->height
        );
        view->debug_width = view->width;
        view->debug_height = view->height;
    }
    ktn_wpe_webview_release_buffer(view);
    view->buffer = WPE_BUFFER(g_object_ref(buffer));
    view->texture_ready = false;
}

static void ktn_wpe_webview_buffer_released(WPEView *, WPEBuffer *buffer, void *data) {
    KtnWpeWebView *view = static_cast<KtnWpeWebView *>(data);
    if (view && view->buffer == buffer) ktn_wpe_webview_release_buffer(view);
}

static bool ktn_wpe_webview_import_buffer(KtnWpeWebView *view) {
    if (!view) return false;
    if (view->texture_ready) return true;
    if (!view->buffer) return false;
    GError *error = nullptr;
    GBytes *bytes = wpe_buffer_import_to_pixels(view->buffer, &error);
    if (!bytes) {
        ktn_wpe_webview_set_error(
            view,
            error ? error->message : "Could not read pixels from the WPE buffer"
        );
        g_clear_error(&error);
        return false;
    }

    std::size_t byte_count = 0;
    const void *pixels = g_bytes_get_data(bytes, &byte_count);
    const int width = wpe_buffer_get_width(view->buffer);
    const int height = wpe_buffer_get_height(view->buffer);
    const std::size_t stride = height > 0 ? byte_count / static_cast<std::size_t>(height) : 0;
    if (!pixels || width <= 0 || height <= 0 || stride < static_cast<std::size_t>(width) * 4) {
        ktn_wpe_webview_set_error(view, "WPE returned an invalid pixel buffer");
        return false;
    }

    glBindTexture(GL_TEXTURE_2D, view->texture);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, static_cast<GLint>(stride / 4));
    glTexImage2D(
        GL_TEXTURE_2D,
        0,
        GL_RGB8,
        width,
        height,
        0,
        GL_BGRA,
        GL_UNSIGNED_BYTE,
        pixels
    );
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, view->framebuffer);
    glFramebufferTexture2D(
        GL_READ_FRAMEBUFFER,
        GL_COLOR_ATTACHMENT0,
        GL_TEXTURE_2D,
        view->texture,
        0
    );
    if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        ktn_wpe_webview_set_error(view, "Could not create the WPE source framebuffer");
        return false;
    }
    const GLenum gl_error = glGetError();
    if (gl_error != GL_NO_ERROR) {
        char message[128];
        std::snprintf(message, sizeof(message), "Could not upload WPE pixels (OpenGL error %#x)", gl_error);
        ktn_wpe_webview_set_error(view, message);
        return false;
    }
    view->texture_width = width;
    view->texture_height = height;
    view->texture_ready = true;
    return true;
}

static void ktn_wpe_webview_load_changed(
    WebKitWebView *web_view,
    WebKitLoadEvent event,
    void *data
) {
    KtnWpeWebView *view = static_cast<KtnWpeWebView *>(data);
    if (!view) return;
    if (event == WEBKIT_LOAD_STARTED) view->error[0] = '\0';
    if (!view->debug) return;
    const char *state = "unknown";
    switch (event) {
        case WEBKIT_LOAD_STARTED: state = "started"; break;
        case WEBKIT_LOAD_REDIRECTED: state = "redirected"; break;
        case WEBKIT_LOAD_COMMITTED: state = "committed"; break;
        case WEBKIT_LOAD_FINISHED: state = "finished"; break;
    }
    std::fprintf(
        stderr,
        "webview-kmp WPE: load %s: %s\n",
        state,
        webkit_web_view_get_uri(web_view)
    );
}

static gboolean ktn_wpe_webview_load_failed(
    WebKitWebView *,
    WebKitLoadEvent,
    const char *uri,
    GError *error,
    void *data
) {
    KtnWpeWebView *view = static_cast<KtnWpeWebView *>(data);
    if (view) {
        std::snprintf(
            view->error,
            sizeof(view->error),
            "%s",
            error && error->message ? error->message : "WPE WebKit navigation failed"
        );
    }
    if (view && view->debug) {
        std::fprintf(
            stderr,
            "webview-kmp WPE: load failed: %s: %s\n",
            uri ? uri : "(unknown URI)",
            error ? error->message : "unknown error"
        );
    }
    return FALSE;
}

static gboolean ktn_wpe_webview_decide_policy(
    WebKitWebView *,
    WebKitPolicyDecision *decision,
    WebKitPolicyDecisionType type,
    gpointer data
) {
    auto *view = static_cast<KtnWpeWebView *>(data);
    if (
        !view ||
        !view->navigation_callback ||
        type != WEBKIT_POLICY_DECISION_TYPE_NAVIGATION_ACTION
    ) return FALSE;

    auto *navigation = WEBKIT_NAVIGATION_POLICY_DECISION(decision);
    WebKitNavigationAction *action =
        webkit_navigation_policy_decision_get_navigation_action(navigation);
    WebKitURIRequest *request = action ? webkit_navigation_action_get_request(action) : nullptr;
    const char *url = request ? webkit_uri_request_get_uri(request) : nullptr;
    if (!url || !url[0]) return FALSE;
    const char *method = request ? webkit_uri_request_get_http_method(request) : nullptr;
    const char *frame_name = action ? webkit_navigation_action_get_frame_name(action) : nullptr;
    const int result = view->navigation_callback(
        view->navigation_user_data,
        url,
        method && method[0] ? method : "GET",
        !frame_name || !frame_name[0],
        action && webkit_navigation_action_is_redirect(action),
        action && webkit_navigation_action_is_user_gesture(action)
    );
    if (result == 0) {
        webkit_policy_decision_use(decision);
        return TRUE;
    }
    if (result == 2) {
        GError *error = nullptr;
        if (!g_app_info_launch_default_for_uri(url, nullptr, &error) && view->debug) {
            std::fprintf(
                stderr,
                "webview-kmp WPE: could not open URL externally: %s\n",
                error ? error->message : "unknown error"
            );
        }
        g_clear_error(&error);
    }
    webkit_policy_decision_ignore(decision);
    return TRUE;
}

static void ktn_wpe_webview_script_message(
    WebKitUserContentManager *,
    JSCValue *value,
    gpointer data
) {
    auto *view = static_cast<KtnWpeWebView *>(data);
    if (!view || !view->message_callback || !value) return;
    char *text = jsc_value_to_string(value);
    view->message_callback(view->message_user_data, text ? text : "");
    g_free(text);
}

static void ktn_wpe_webview_javascript_finished(
    GObject *source,
    GAsyncResult *result,
    gpointer data
) {
    auto *request = static_cast<KtnWpeJavaScriptRequest *>(data);
    if (!request) return;

    GError *error = nullptr;
    JSCValue *value = webkit_web_view_evaluate_javascript_finish(
        WEBKIT_WEB_VIEW(source),
        result,
        &error
    );
    if (error) {
        if (request->callback) request->callback(request->user_data, 0, error->message);
        g_error_free(error);
        delete request;
        return;
    }

    char *serialized = nullptr;
    if (value && !jsc_value_is_undefined(value)) {
        serialized = jsc_value_to_json(value, 0);
        if (!serialized) serialized = jsc_value_to_string(value);
    }
    if (request->callback) request->callback(request->user_data, 1, serialized);
    g_free(serialized);
    if (value) g_object_unref(value);
    delete request;
}

static void ktn_wpe_webview_get_cookies_finished(
    GObject *source,
    GAsyncResult *result,
    gpointer data
) {
    auto *request = static_cast<KtnWpeCookieRequest *>(data);
    if (!request) return;
    GError *error = nullptr;
    GList *cookies = webkit_cookie_manager_get_cookies_finish(
        WEBKIT_COOKIE_MANAGER(source),
        result,
        &error
    );
    if (error) {
        if (request->callback) request->callback(request->user_data, 0, error->message);
        g_error_free(error);
        delete request;
        return;
    }

    std::string json = "[";
    bool first = true;
    for (GList *node = cookies; node; node = node->next) {
        auto *cookie = static_cast<SoupCookie *>(node->data);
        if (!cookie) continue;
        if (!first) json += ',';
        first = false;
        GDateTime *expires = soup_cookie_get_expires(cookie);
        const long long expires_millis = expires
            ? static_cast<long long>(g_date_time_to_unix(expires)) * 1000LL
            : -1LL;
        json += "{\"name\":" + ktn_wpe_json_string(soup_cookie_get_name(cookie));
        json += ",\"value\":" + ktn_wpe_json_string(soup_cookie_get_value(cookie));
        json += ",\"domain\":" + ktn_wpe_json_string(soup_cookie_get_domain(cookie));
        json += ",\"path\":" + ktn_wpe_json_string(soup_cookie_get_path(cookie));
        json += ",\"expiresAtMillis\":" + std::to_string(expires_millis);
        json += ",\"secure\":" + std::string(soup_cookie_get_secure(cookie) ? "true" : "false");
        json += ",\"httpOnly\":" + std::string(soup_cookie_get_http_only(cookie) ? "true" : "false");
        json += ",\"sameSite\":" + std::to_string(static_cast<int>(soup_cookie_get_same_site_policy(cookie)));
        json += '}';
    }
    json += ']';
    if (request->callback) request->callback(request->user_data, 1, json.c_str());
    if (cookies) g_list_free_full(cookies, reinterpret_cast<GDestroyNotify>(soup_cookie_free));
    delete request;
}

static void ktn_wpe_webview_add_cookie_finished(
    GObject *source,
    GAsyncResult *result,
    gpointer data
) {
    auto *request = static_cast<KtnWpeCookieRequest *>(data);
    if (!request) return;
    GError *error = nullptr;
    const gboolean ok = webkit_cookie_manager_add_cookie_finish(
        WEBKIT_COOKIE_MANAGER(source), result, &error
    );
    if (request->callback) {
        request->callback(
            request->user_data,
            ok && !error ? 1 : 0,
            error ? error->message : nullptr
        );
    }
    if (error) g_error_free(error);
    if (request->cookie) soup_cookie_free(request->cookie);
    delete request;
}

static void ktn_wpe_webview_delete_cookie_finished(
    GObject *source,
    GAsyncResult *result,
    gpointer data
) {
    auto *request = static_cast<KtnWpeCookieRequest *>(data);
    if (!request) return;
    GError *error = nullptr;
    const gboolean ok = webkit_cookie_manager_delete_cookie_finish(
        WEBKIT_COOKIE_MANAGER(source), result, &error
    );
    if (request->callback) {
        request->callback(
            request->user_data,
            ok && !error ? 1 : 0,
            error ? error->message : nullptr
        );
    }
    if (error) g_error_free(error);
    if (request->cookie) soup_cookie_free(request->cookie);
    delete request;
}

static void ktn_wpe_webview_clear_cookies_finished(
    GObject *source,
    GAsyncResult *result,
    gpointer data
) {
    auto *request = static_cast<KtnWpeCookieRequest *>(data);
    if (!request) return;
    GError *error = nullptr;
    const gboolean ok = webkit_cookie_manager_replace_cookies_finish(
        WEBKIT_COOKIE_MANAGER(source), result, &error
    );
    if (request->callback) {
        request->callback(
            request->user_data,
            ok && !error ? 1 : 0,
            error ? error->message : nullptr
        );
    }
    if (error) g_error_free(error);
    delete request;
}

static bool ktn_wpe_webview_is_printable_key(long long key) {
    switch (key) {
        case 0: case 11: case 8: case 2: case 14: case 3: case 5: case 4:
        case 34: case 38: case 40: case 37: case 46: case 45: case 31: case 35:
        case 12: case 15: case 1: case 17: case 32: case 9: case 13: case 7:
        case 16: case 6:
        case 29: case 18: case 19: case 20: case 21: case 23: case 22: case 26:
        case 28: case 25:
        case 49: // Space
            return true;
        default:
            return false;
    }
}

static unsigned int ktn_wpe_webview_keysym(long long key, unsigned int code_point) {
    if (code_point) {
        return code_point <= 0xff ? code_point : 0x01000000u | code_point;
    }
    switch (key) {
        case 0: return 'a'; case 11: return 'b'; case 8: return 'c'; case 2: return 'd';
        case 14: return 'e'; case 3: return 'f'; case 5: return 'g'; case 4: return 'h';
        case 34: return 'i'; case 38: return 'j'; case 40: return 'k'; case 37: return 'l';
        case 46: return 'm'; case 45: return 'n'; case 31: return 'o'; case 35: return 'p';
        case 12: return 'q'; case 15: return 'r'; case 1: return 's'; case 17: return 't';
        case 32: return 'u'; case 9: return 'v'; case 13: return 'w'; case 7: return 'x';
        case 16: return 'y'; case 6: return 'z';
        case 29: return '0'; case 18: return '1'; case 19: return '2'; case 20: return '3';
        case 21: return '4'; case 23: return '5'; case 22: return '6'; case 26: return '7';
        case 28: return '8'; case 25: return '9';
        case 36: return 0xff0d; // Return
        case 48: return 0xff09; // Tab
        case 49: return 0x20;   // Space
        case 51: return 0xff08; // BackSpace
        case 53: return 0xff1b; // Escape
        case 117: return 0xffff; // Delete
        case 123: return 0xff51; // Left
        case 124: return 0xff53; // Right
        case 125: return 0xff54; // Down
        case 126: return 0xff52; // Up
        case 115: return 0xff50; // Home
        case 119: return 0xff57; // End
        case 116: return 0xff55; // Page Up
        case 121: return 0xff56; // Page Down
        case 59: case 62: return 0xffe3; // Control
        case 56: case 60: return 0xffe1; // Shift
        case 58: case 61: return 0xffe9; // Alt
        case 54: case 55: return 0xffeb; // Meta
        default: return 0;
    }
}

static void ktn_wpe_webview_sync_system_clipboard_to_wpe(KtnWpeWebView *view) {
    if (!view || !view->display) return;

    char *text = SDL_GetClipboardText();
    if (!text) return;

    WPEClipboard *clipboard = wpe_display_get_clipboard(view->display);
    if (clipboard) {
        WPEClipboardContent *content = wpe_clipboard_content_new();
        wpe_clipboard_content_set_text(content, text);
        wpe_clipboard_set_content(clipboard, content);
        wpe_clipboard_content_unref(content);
    }
    SDL_free(text);
}

static const char *ktn_wpe_webview_current_drm_device(EGLDisplay display) {
    auto query_display = reinterpret_cast<PFNEGLQUERYDISPLAYATTRIBEXTPROC>(
        eglGetProcAddress("eglQueryDisplayAttribEXT")
    );
    auto query_device = reinterpret_cast<PFNEGLQUERYDEVICESTRINGEXTPROC>(
        eglGetProcAddress("eglQueryDeviceStringEXT")
    );
    if (!query_display || !query_device) return nullptr;

    EGLAttrib device_attribute = 0;
    if (!query_display(display, EGL_DEVICE_EXT, &device_attribute) || !device_attribute) {
        return nullptr;
    }
    auto device = reinterpret_cast<EGLDeviceEXT>(device_attribute);
    const char *render_node = query_device(device, EGL_DRM_RENDER_NODE_FILE_EXT);
    if (render_node && render_node[0]) return render_node;
    const char *primary_node = query_device(device, EGL_DRM_DEVICE_FILE_EXT);
    return primary_node && primary_node[0] ? primary_node : nullptr;
}

extern "C" {

KtnWpeWebView *ktn_wpe_webview_create(
    const char *uri,
    int java_script_enabled,
    const char *user_agent,
    int media_playback_requires_user_gesture,
    int debug_logging,
    int ephemeral_profile,
    const char *data_directory,
    const char *cache_directory
) {
    KtnWpeWebView *view = new KtnWpeWebView();
    view->debug = debug_logging != 0;
    const EGLDisplay egl_display = eglGetCurrentDisplay();
    if (egl_display == EGL_NO_DISPLAY) {
        ktn_wpe_webview_set_error(view, "WPE WebKit requires an EGL-backed SDL OpenGL context");
        return view;
    }
    GError *display_error = nullptr;
    const char *drm_device = ktn_wpe_webview_current_drm_device(egl_display);
    view->display = drm_device
        ? wpe_display_headless_new_for_device(drm_device, &display_error)
        : wpe_display_headless_new();
    if (drm_device && view->debug) {
        std::fprintf(stderr, "webview-kmp WPE: using EGL DRM device %s\n", drm_device);
    }
    if (!view->display || !wpe_display_connect(view->display, &display_error)) {
        ktn_wpe_webview_set_error(
            view,
            display_error ? display_error->message : "Could not create the headless WPE display"
        );
        g_clear_error(&display_error);
        return view;
    }
    if (ephemeral_profile) {
        view->owned_network_session = webkit_network_session_new_ephemeral();
    } else if (data_directory && data_directory[0]) {
        view->owned_network_session = webkit_network_session_new(
            data_directory,
            cache_directory && cache_directory[0] ? cache_directory : nullptr
        );
    }
    view->web_view = view->owned_network_session
        ? WEBKIT_WEB_VIEW(g_object_new(
            WEBKIT_TYPE_WEB_VIEW,
            "display", view->display,
            "network-session", view->owned_network_session,
            nullptr
        ))
        : WEBKIT_WEB_VIEW(g_object_new(
            WEBKIT_TYPE_WEB_VIEW,
            "display", view->display,
            nullptr
        ));
    if (!view->web_view) {
        ktn_wpe_webview_set_error(view, "Could not create the WPE WebKit web view");
        return view;
    }
    view->wpe_view = webkit_web_view_get_wpe_view(view->web_view);
    if (!view->wpe_view) {
        ktn_wpe_webview_set_error(view, "WPE WebKit did not create a platform view");
        return view;
    }
    g_object_set_data(G_OBJECT(view->wpe_view), "webview-kmp-wpe", view);
    WPEViewClass *view_class = WPE_VIEW_GET_CLASS(view->wpe_view);
    view_class->set_cursor_from_name = ktn_wpe_webview_set_cursor_from_name;
    view_class->set_cursor_from_bytes = ktn_wpe_webview_set_cursor_from_bytes;
    g_signal_connect(
        view->wpe_view,
        "buffer-rendered",
        G_CALLBACK(ktn_wpe_webview_buffer_rendered),
        view
    );
    g_signal_connect(
        view->wpe_view,
        "buffer-released",
        G_CALLBACK(ktn_wpe_webview_buffer_released),
        view
    );

    WebKitSettings *settings = webkit_web_view_get_settings(view->web_view);
    webkit_settings_set_enable_javascript(settings, java_script_enabled != 0);
    webkit_settings_set_enable_media(settings, TRUE);
    webkit_settings_set_enable_mediasource(settings, TRUE);
    webkit_settings_set_enable_media_capabilities(settings, TRUE);
    webkit_settings_set_enable_webgl(settings, TRUE);
    webkit_settings_set_media_playback_allows_inline(settings, TRUE);
    webkit_settings_set_media_playback_requires_user_gesture(
        settings,
        media_playback_requires_user_gesture != 0
    );
    webkit_settings_set_enable_site_specific_quirks(settings, TRUE);
    if (user_agent && user_agent[0]) webkit_settings_set_user_agent(settings, user_agent);
    webkit_settings_set_enable_write_console_messages_to_stdout(settings, view->debug);
    g_signal_connect(view->web_view, "load-changed", G_CALLBACK(ktn_wpe_webview_load_changed), view);
    g_signal_connect(view->web_view, "load-failed", G_CALLBACK(ktn_wpe_webview_load_failed), view);
    g_signal_connect(view->web_view, "decide-policy", G_CALLBACK(ktn_wpe_webview_decide_policy), view);
    WebKitUserContentManager *content_manager =
        webkit_web_view_get_user_content_manager(view->web_view);
    if (content_manager) {
        g_signal_connect(
            content_manager,
            "script-message-received::webviewKmp",
            G_CALLBACK(ktn_wpe_webview_script_message),
            view
        );
        webkit_user_content_manager_register_script_message_handler(
            content_manager,
            "webviewKmp",
            nullptr
        );
    }
    if (view->debug) {
        std::fprintf(stderr, "webview-kmp WPE: user agent: %s\n", webkit_settings_get_user_agent(settings));
    }

    wpe_view_set_visible(view->wpe_view, TRUE);
    wpe_view_map(view->wpe_view);
    glGenTextures(1, &view->texture);
    glGenFramebuffers(1, &view->framebuffer);
    if (uri && uri[0]) view->pending_uri = uri;
    return view;
}

void ktn_wpe_webview_destroy(KtnWpeWebView *view) {
    if (!view) return;
    if (view->wpe_view) {
        g_signal_handlers_disconnect_by_data(view->wpe_view, view);
        g_object_set_data(G_OBJECT(view->wpe_view), "webview-kmp-wpe", nullptr);
    }
    if (view->custom_cursor) {
        SDL_SetCursor(ktn_wpe_webview_system_cursor(SDL_SYSTEM_CURSOR_DEFAULT));
        SDL_DestroyCursor(view->custom_cursor);
    }
    ktn_wpe_webview_release_buffer(view);
    if (view->framebuffer) glDeleteFramebuffers(1, &view->framebuffer);
    if (view->texture) glDeleteTextures(1, &view->texture);
    if (view->web_view) {
        WebKitUserContentManager *content_manager =
            webkit_web_view_get_user_content_manager(view->web_view);
        if (content_manager) {
            webkit_user_content_manager_unregister_script_message_handler(
                content_manager,
                "webviewKmp",
                nullptr
            );
            g_signal_handlers_disconnect_by_data(content_manager, view);
        }
        g_object_unref(view->web_view);
    }
    if (view->owned_network_session) g_object_unref(view->owned_network_session);
    if (view->display) g_object_unref(view->display);
    delete view;
}

const char *ktn_wpe_webview_error(KtnWpeWebView *view) {
    return view && view->error[0] ? view->error : nullptr;
}

const char *ktn_wpe_webview_url(KtnWpeWebView *view) {
    return view && view->web_view ? webkit_web_view_get_uri(view->web_view) : nullptr;
}

const char *ktn_wpe_webview_title(KtnWpeWebView *view) {
    return view && view->web_view ? webkit_web_view_get_title(view->web_view) : nullptr;
}

int ktn_wpe_webview_is_loading(KtnWpeWebView *view) {
    return view && view->web_view && webkit_web_view_is_loading(view->web_view);
}

float ktn_wpe_webview_progress(KtnWpeWebView *view) {
    return view && view->web_view
        ? static_cast<float>(webkit_web_view_get_estimated_load_progress(view->web_view))
        : 0.0f;
}

int ktn_wpe_webview_render(
    KtnWpeWebView *view,
    int framebuffer,
    int width,
    int height,
    float device_scale
) {
    if (!view || !view->web_view || width <= 0 || height <= 0) return 0;
    const float safe_scale = std::clamp(device_scale, 0.05f, 5.0f);
    const int logical_width = std::max(1, static_cast<int>(std::lround(width / safe_scale)));
    const int logical_height = std::max(1, static_cast<int>(std::lround(height / safe_scale)));
    if (
        view->width != width ||
        view->height != height ||
        view->logical_width != logical_width ||
        view->logical_height != logical_height ||
        std::fabs(view->scale - safe_scale) > 0.001f
    ) {
        view->width = width;
        view->height = height;
        view->logical_width = logical_width;
        view->logical_height = logical_height;
        view->scale = safe_scale;
        WPEToplevel *toplevel = wpe_view_get_toplevel(view->wpe_view);
        if (toplevel) {
            wpe_toplevel_scale_changed(toplevel, safe_scale);
            wpe_toplevel_resize(toplevel, logical_width, logical_height);
        } else {
            wpe_view_resized(view->wpe_view, logical_width, logical_height);
        }
    }
    if (!view->pending_uri.empty()) {
        webkit_web_view_load_uri(view->web_view, view->pending_uri.c_str());
        view->pending_uri.clear();
    }

    for (int iteration = 0; iteration < 64; ++iteration) {
        if (!g_main_context_iteration(nullptr, FALSE)) break;
    }
    if (!ktn_wpe_webview_import_buffer(view)) {
        glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(framebuffer));
        glViewport(0, 0, width, height);
        glDisable(GL_SCISSOR_TEST);
        glClearColor(1, 1, 1, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        return 1;
    }

    // The SDL host uses an OpenGL 3.3 core-profile context, so legacy fixed-function drawing
    // (glBegin/glEnd) is invalid. Copy the uploaded WPE texture through framebuffer blitting,
    // which is core-profile safe. The source texture uses RGB8 so XRGB buffers acquire alpha 1.
    glBindFramebuffer(GL_READ_FRAMEBUFFER, view->framebuffer);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(framebuffer));
    glDisable(GL_SCISSOR_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glBlitFramebuffer(
        0,
        0,
        view->texture_width,
        view->texture_height,
        0,
        height,
        width,
        0,
        GL_COLOR_BUFFER_BIT,
        GL_LINEAR
    );
    const GLenum draw_error = glGetError();
    if (draw_error != GL_NO_ERROR) {
        char message[128];
        std::snprintf(message, sizeof(message), "Could not draw WPE pixels (OpenGL error %#x)", draw_error);
        ktn_wpe_webview_set_error(view, message);
        return 0;
    }
    return 1;
}

int ktn_wpe_webview_render_pixels(KtnWpeWebView *, void *, int, int, int, float) {
    return 0;
}

void ktn_wpe_webview_load_uri(KtnWpeWebView *view, const char *uri) {
    if (!view || !view->web_view || !uri || !uri[0]) return;
    view->error[0] = '\0';
    if (view->width <= 1 || view->height <= 1) {
        view->pending_uri = uri;
    } else {
        webkit_web_view_load_uri(view->web_view, uri);
    }
}

void ktn_wpe_webview_load_uri_with_headers(
    KtnWpeWebView *view,
    const char *uri,
    const char *const *header_names,
    const char *const *header_values,
    int header_count
) {
    if (!view || !view->web_view || !uri || !uri[0]) return;
    if (header_count <= 0) {
        ktn_wpe_webview_load_uri(view, uri);
        return;
    }
    view->error[0] = '\0';
    WebKitURIRequest *request = webkit_uri_request_new(uri);
    SoupMessageHeaders *headers = webkit_uri_request_get_http_headers(request);
    for (int index = 0; index < header_count; ++index) {
        const char *name = header_names ? header_names[index] : nullptr;
        const char *value = header_values ? header_values[index] : nullptr;
        if (name && name[0] && value) soup_message_headers_replace(headers, name, value);
    }
    webkit_web_view_load_request(view->web_view, request);
    g_object_unref(request);
}

void ktn_wpe_webview_load_html(
    KtnWpeWebView *view,
    const char *html,
    const char *base_uri
) {
    if (!view || !view->web_view || !html) return;
    view->error[0] = '\0';
    webkit_web_view_load_html(view->web_view, html, base_uri && base_uri[0] ? base_uri : nullptr);
}

void ktn_wpe_webview_stop(KtnWpeWebView *view) {
    if (view && view->web_view) webkit_web_view_stop_loading(view->web_view);
}

void ktn_wpe_webview_go_back(KtnWpeWebView *view) {
    if (view && view->web_view && webkit_web_view_can_go_back(view->web_view)) {
        webkit_web_view_go_back(view->web_view);
    }
}

void ktn_wpe_webview_go_forward(KtnWpeWebView *view) {
    if (view && view->web_view && webkit_web_view_can_go_forward(view->web_view)) {
        webkit_web_view_go_forward(view->web_view);
    }
}

void ktn_wpe_webview_reload(KtnWpeWebView *view) {
    if (view && view->web_view) webkit_web_view_reload(view->web_view);
}

int ktn_wpe_webview_can_go_back(KtnWpeWebView *view) {
    return view && view->web_view && webkit_web_view_can_go_back(view->web_view);
}

int ktn_wpe_webview_can_go_forward(KtnWpeWebView *view) {
    return view && view->web_view && webkit_web_view_can_go_forward(view->web_view);
}

void ktn_wpe_webview_evaluate_javascript(
    KtnWpeWebView *view,
    const char *script,
    KtnWpeJavaScriptCallback callback,
    void *user_data
) {
    if (!callback) return;
    if (!view || !view->web_view || !script) {
        callback(user_data, 0, "WPE WebKit is not ready");
        return;
    }
    auto *request = new KtnWpeJavaScriptRequest();
    request->callback = callback;
    request->user_data = user_data;
    webkit_web_view_evaluate_javascript(
        view->web_view,
        script,
        -1,
        nullptr,
        nullptr,
        nullptr,
        ktn_wpe_webview_javascript_finished,
        request
    );
}

void ktn_wpe_webview_set_navigation_callback(
    KtnWpeWebView *view,
    KtnWpeNavigationCallback callback,
    void *user_data
) {
    if (!view) return;
    view->navigation_callback = callback;
    view->navigation_user_data = user_data;
}

void ktn_wpe_webview_add_user_script(
    KtnWpeWebView *view,
    const char *source,
    int injection_time,
    int main_frame_only
) {
    if (!view || !view->web_view || !source || !source[0]) return;
    WebKitUserContentManager *manager = webkit_web_view_get_user_content_manager(view->web_view);
    if (!manager) return;
    WebKitUserScript *script = webkit_user_script_new(
        source,
        main_frame_only
            ? WEBKIT_USER_CONTENT_INJECT_TOP_FRAME
            : WEBKIT_USER_CONTENT_INJECT_ALL_FRAMES,
        injection_time == 0
            ? WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START
            : WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_END,
        nullptr,
        nullptr
    );
    if (!script) return;
    webkit_user_content_manager_add_script(manager, script);
    webkit_user_script_unref(script);
}

void ktn_wpe_webview_set_message_callback(
    KtnWpeWebView *view,
    KtnWpeMessageCallback callback,
    void *user_data
) {
    if (!view) return;
    view->message_callback = callback;
    view->message_user_data = user_data;
}

void ktn_wpe_webview_get_cookies(
    KtnWpeWebView *view,
    const char *uri,
    KtnWpeCookieCallback callback,
    void *user_data
) {
    if (!callback) return;
    WebKitCookieManager *manager = ktn_wpe_webview_cookie_manager(view);
    if (!manager || !uri) {
        callback(user_data, 0, "WPE WebKit cookie manager is not ready");
        return;
    }
    auto *request = new KtnWpeCookieRequest();
    request->callback = callback;
    request->user_data = user_data;
    webkit_cookie_manager_get_cookies(
        manager,
        uri,
        nullptr,
        ktn_wpe_webview_get_cookies_finished,
        request
    );
}

void ktn_wpe_webview_set_cookie(
    KtnWpeWebView *view,
    const char *name,
    const char *value,
    const char *domain,
    const char *path,
    long long expires_at_millis,
    int secure,
    int http_only,
    int same_site,
    KtnWpeCookieCallback callback,
    void *user_data
) {
    if (!callback) return;
    WebKitCookieManager *manager = ktn_wpe_webview_cookie_manager(view);
    if (!manager || !name || !value || !domain) {
        callback(user_data, 0, "Invalid WPE WebKit cookie");
        return;
    }
    SoupCookie *cookie = soup_cookie_new(name, value, domain, path && path[0] ? path : "/", -1);
    if (!cookie) {
        callback(user_data, 0, "Could not create WPE WebKit cookie");
        return;
    }
    if (expires_at_millis >= 0) {
        GDateTime *expires = g_date_time_new_from_unix_utc(expires_at_millis / 1000LL);
        if (expires) {
            soup_cookie_set_expires(cookie, expires);
            g_date_time_unref(expires);
        }
    }
    if (secure >= 0) soup_cookie_set_secure(cookie, secure != 0);
    if (http_only >= 0) soup_cookie_set_http_only(cookie, http_only != 0);
    if (same_site >= 0 && same_site <= 2) {
        soup_cookie_set_same_site_policy(cookie, static_cast<SoupSameSitePolicy>(same_site));
    }
    auto *request = new KtnWpeCookieRequest();
    request->callback = callback;
    request->user_data = user_data;
    request->cookie = cookie;
    webkit_cookie_manager_add_cookie(
        manager,
        cookie,
        nullptr,
        ktn_wpe_webview_add_cookie_finished,
        request
    );
}

void ktn_wpe_webview_delete_cookie(
    KtnWpeWebView *view,
    const char *name,
    const char *value,
    const char *domain,
    const char *path,
    KtnWpeCookieCallback callback,
    void *user_data
) {
    if (!callback) return;
    WebKitCookieManager *manager = ktn_wpe_webview_cookie_manager(view);
    if (!manager || !name || !domain) {
        callback(user_data, 0, "Invalid WPE WebKit cookie deletion");
        return;
    }
    SoupCookie *cookie = soup_cookie_new(
        name,
        value ? value : "",
        domain,
        path && path[0] ? path : "/",
        -1
    );
    if (!cookie) {
        callback(user_data, 0, "Could not create WPE WebKit cookie deletion");
        return;
    }
    auto *request = new KtnWpeCookieRequest();
    request->callback = callback;
    request->user_data = user_data;
    request->cookie = cookie;
    webkit_cookie_manager_delete_cookie(
        manager,
        cookie,
        nullptr,
        ktn_wpe_webview_delete_cookie_finished,
        request
    );
}

void ktn_wpe_webview_clear_cookies(
    KtnWpeWebView *view,
    KtnWpeCookieCallback callback,
    void *user_data
) {
    if (!callback) return;
    WebKitCookieManager *manager = ktn_wpe_webview_cookie_manager(view);
    if (!manager) {
        callback(user_data, 0, "WPE WebKit cookie manager is not ready");
        return;
    }
    auto *request = new KtnWpeCookieRequest();
    request->callback = callback;
    request->user_data = user_data;
    webkit_cookie_manager_replace_cookies(
        manager,
        nullptr,
        nullptr,
        ktn_wpe_webview_clear_cookies_finished,
        request
    );
}

void ktn_wpe_webview_set_focused(KtnWpeWebView *view, int focused) {
    if (!view || !view->wpe_view) return;
    if (focused) wpe_view_focus_in(view->wpe_view);
    else wpe_view_focus_out(view->wpe_view);
}

void ktn_wpe_webview_pointer_motion(
    KtnWpeWebView *view,
    int x,
    int y,
    unsigned int time,
    unsigned int modifiers
) {
    if (!view || !view->wpe_view) return;
    WPEEvent *event = wpe_event_pointer_move_new(
        WPE_EVENT_POINTER_MOVE,
        view->wpe_view,
        WPE_INPUT_SOURCE_MOUSE,
        time,
        static_cast<WPEModifiers>(modifiers),
        x / view->scale,
        y / view->scale,
        0,
        0
    );
    if (!event) return;
    wpe_view_event(view->wpe_view, event);
    wpe_event_unref(event);
}

void ktn_wpe_webview_pointer_button(
    KtnWpeWebView *view,
    int x,
    int y,
    unsigned int time,
    unsigned int button,
    int pressed,
    unsigned int modifiers
) {
    if (!view || !view->wpe_view) return;
    const unsigned int button_modifier = button >= 1 && button <= 5 ? 1u << (7 + button) : 0;
    WPEEvent *event = wpe_event_pointer_button_new(
        pressed ? WPE_EVENT_POINTER_DOWN : WPE_EVENT_POINTER_UP,
        view->wpe_view,
        WPE_INPUT_SOURCE_MOUSE,
        time,
        static_cast<WPEModifiers>(modifiers | button_modifier),
        button,
        x / view->scale,
        y / view->scale,
        pressed ? 1 : 0
    );
    if (!event) return;
    wpe_view_event(view->wpe_view, event);
    wpe_event_unref(event);
}

void ktn_wpe_webview_scroll(
    KtnWpeWebView *view,
    int x,
    int y,
    unsigned int time,
    double delta_x,
    double delta_y,
    unsigned int modifiers
) {
    if (!view || !view->wpe_view) return;
    WPEEvent *event = wpe_event_scroll_new(
        view->wpe_view,
        WPE_INPUT_SOURCE_TOUCHPAD,
        time,
        static_cast<WPEModifiers>(modifiers),
        -delta_x,
        -delta_y,
        TRUE,
        FALSE,
        x / view->scale,
        y / view->scale
    );
    if (!event) return;
    if (view->debug) {
        std::fprintf(stderr, "webview-kmp WPE: scroll %.1f, %.1f at %d, %d\n", -delta_x, -delta_y, x, y);
    }
    wpe_view_event(view->wpe_view, event);
    wpe_event_unref(event);
}

void ktn_wpe_webview_key(
    KtnWpeWebView *view,
    long long compose_key,
    unsigned int code_point,
    int pressed,
    unsigned int modifiers
) {
    if (!view || !view->wpe_view) return;

    // SDL emits a physical key event followed by SDL_TEXTINPUT for printable input. The latter
    // is already keyboard-layout and IME translated, so forwarding both inserts each character
    // twice. Keep physical printable events only for Ctrl/Meta shortcuts; committed text arrives
    // separately with a non-zero code point. Ctrl+Alt is treated as AltGr text input.
    const bool control_shortcut =
        (modifiers & WPE_MODIFIER_KEYBOARD_CONTROL) &&
        !(modifiers & WPE_MODIFIER_KEYBOARD_ALT);
    const bool meta_shortcut = modifiers & WPE_MODIFIER_KEYBOARD_META;

    // The offscreen backend uses a headless WPE display, so WebKit's clipboard is not connected
    // to the SDL window's desktop clipboard. Populate the WPE clipboard immediately before a
    // paste shortcut and then let WebKit process Ctrl+V normally.
    if (pressed && control_shortcut && compose_key == 9) {
        ktn_wpe_webview_sync_system_clipboard_to_wpe(view);
    }

    if (
        !code_point &&
        ktn_wpe_webview_is_printable_key(compose_key) &&
        !control_shortcut &&
        !meta_shortcut
    ) {
        return;
    }

    const unsigned int keysym = ktn_wpe_webview_keysym(compose_key, code_point);
    if (!keysym) return;
    WPEEvent *event = wpe_event_keyboard_new(
        pressed ? WPE_EVENT_KEYBOARD_KEY_DOWN : WPE_EVENT_KEYBOARD_KEY_UP,
        view->wpe_view,
        WPE_INPUT_SOURCE_KEYBOARD,
        0,
        static_cast<WPEModifiers>(modifiers),
        0,
        keysym
    );
    if (!event) return;
    wpe_view_event(view->wpe_view, event);
    wpe_event_unref(event);
}

} // extern "C"
