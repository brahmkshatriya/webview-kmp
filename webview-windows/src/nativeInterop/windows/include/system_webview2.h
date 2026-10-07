#ifndef KTNATIVE_SYSTEM_WEBVIEW2_H
#define KTNATIVE_SYSTEM_WEBVIEW2_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct KtnWebView2 KtnWebView2;

typedef void (*KtnWebView2StateCallback)(void *user_data);
typedef void (*KtnWebView2JavaScriptCallback)(void *user_data, int success, const char *value);
typedef void (*KtnWebView2CookieCallback)(void *user_data, int success, const char *value);
typedef int (*KtnWebView2NavigationCallback)(
    void *user_data,
    const char *url,
    int is_redirect,
    int is_user_initiated
);
typedef void (*KtnWebView2MessageCallback)(
    void *user_data,
    const char *data
);

int ktn_webview2_runtime_available(void);
const char *ktn_webview2_runtime_status(void);

KtnWebView2 *ktn_webview2_create(
    int javascript_enabled,
    const char *user_agent,
    int profile_mode,
    const char *profile_name,
    KtnWebView2StateCallback state_callback,
    void *user_data
);
void ktn_webview2_destroy(KtnWebView2 *view);

int ktn_webview2_is_ready(KtnWebView2 *view);
int ktn_webview2_is_loading(KtnWebView2 *view);
float ktn_webview2_progress(KtnWebView2 *view);
int ktn_webview2_can_go_back(KtnWebView2 *view);
int ktn_webview2_can_go_forward(KtnWebView2 *view);
const char *ktn_webview2_url(KtnWebView2 *view);
const char *ktn_webview2_title(KtnWebView2 *view);
const char *ktn_webview2_error(KtnWebView2 *view);

int ktn_webview2_render_pixels(
    KtnWebView2 *view,
    void *pixels,
    int width,
    int height,
    int stride
);
void ktn_webview2_navigate(KtnWebView2 *view, const char *url);
void ktn_webview2_navigate_html(KtnWebView2 *view, const char *html);
void ktn_webview2_reload(KtnWebView2 *view);
void ktn_webview2_stop(KtnWebView2 *view);
void ktn_webview2_go_back(KtnWebView2 *view);
void ktn_webview2_go_forward(KtnWebView2 *view);
void ktn_webview2_set_focused(KtnWebView2 *view, int focused);
void ktn_webview2_pointer_motion(
    KtnWebView2 *view,
    int x,
    int y,
    unsigned int time,
    unsigned int modifiers
);
void ktn_webview2_pointer_button(
    KtnWebView2 *view,
    int x,
    int y,
    unsigned int time,
    unsigned int button,
    int pressed,
    unsigned int modifiers
);
void ktn_webview2_scroll(
    KtnWebView2 *view,
    int x,
    int y,
    unsigned int time,
    double delta_x,
    double delta_y,
    unsigned int modifiers
);
void ktn_webview2_key(
    KtnWebView2 *view,
    long long compose_key,
    unsigned int code_point,
    int pressed,
    unsigned int modifiers
);
void ktn_webview2_evaluate_javascript(
    KtnWebView2 *view,
    const char *script,
    KtnWebView2JavaScriptCallback callback,
    void *user_data
);
void ktn_webview2_set_navigation_callback(
    KtnWebView2 *view,
    KtnWebView2NavigationCallback callback,
    void *user_data
);
void ktn_webview2_add_user_script(
    KtnWebView2 *view,
    const char *script
);
void ktn_webview2_set_message_callback(
    KtnWebView2 *view,
    KtnWebView2MessageCallback callback,
    void *user_data
);
void ktn_webview2_post_message(
    KtnWebView2 *view,
    const char *data
);
void ktn_webview2_get_cookies(
    KtnWebView2 *view,
    const char *url,
    KtnWebView2CookieCallback callback,
    void *user_data
);
void ktn_webview2_set_cookie(
    KtnWebView2 *view,
    const char *name,
    const char *value,
    const char *domain,
    const char *path,
    long long expires_at_millis,
    int secure,
    int http_only,
    int same_site,
    KtnWebView2CookieCallback callback,
    void *user_data
);
void ktn_webview2_delete_cookie(
    KtnWebView2 *view,
    const char *url,
    const char *name,
    const char *domain,
    const char *path,
    KtnWebView2CookieCallback callback,
    void *user_data
);
void ktn_webview2_clear_cookies(
    KtnWebView2 *view,
    KtnWebView2CookieCallback callback,
    void *user_data
);

#ifdef __cplusplus
}
#endif

#endif
