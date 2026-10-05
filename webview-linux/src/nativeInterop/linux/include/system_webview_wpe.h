#ifndef WEBVIEW_KMP_SYSTEM_WEBVIEW_WPE_H
#define WEBVIEW_KMP_SYSTEM_WEBVIEW_WPE_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct KtnWpeWebView KtnWpeWebView;
typedef void (*KtnWpeJavaScriptCallback)(
    void *user_data,
    int success,
    const char *value
);

KtnWpeWebView *ktn_wpe_webview_create(
    const char *uri,
    int java_script_enabled,
    const char *user_agent,
    int media_playback_requires_user_gesture,
    int debug_logging
);
void ktn_wpe_webview_destroy(KtnWpeWebView *view);
const char *ktn_wpe_webview_error(KtnWpeWebView *view);
const char *ktn_wpe_webview_url(KtnWpeWebView *view);
const char *ktn_wpe_webview_title(KtnWpeWebView *view);
int ktn_wpe_webview_is_loading(KtnWpeWebView *view);
float ktn_wpe_webview_progress(KtnWpeWebView *view);
int ktn_wpe_webview_render(
    KtnWpeWebView *view,
    int framebuffer,
    int width,
    int height,
    float device_scale
);
int ktn_wpe_webview_render_pixels(
    KtnWpeWebView *view,
    void *pixels,
    int width,
    int height,
    int stride,
    float device_scale
);
void ktn_wpe_webview_load_uri(KtnWpeWebView *view, const char *uri);
void ktn_wpe_webview_load_uri_with_headers(
    KtnWpeWebView *view,
    const char *uri,
    const char *const *header_names,
    const char *const *header_values,
    int header_count
);
void ktn_wpe_webview_load_html(
    KtnWpeWebView *view,
    const char *html,
    const char *base_uri
);
void ktn_wpe_webview_stop(KtnWpeWebView *view);
void ktn_wpe_webview_go_back(KtnWpeWebView *view);
void ktn_wpe_webview_go_forward(KtnWpeWebView *view);
void ktn_wpe_webview_reload(KtnWpeWebView *view);
int ktn_wpe_webview_can_go_back(KtnWpeWebView *view);
int ktn_wpe_webview_can_go_forward(KtnWpeWebView *view);
void ktn_wpe_webview_evaluate_javascript(
    KtnWpeWebView *view,
    const char *script,
    KtnWpeJavaScriptCallback callback,
    void *user_data
);
void ktn_wpe_webview_set_focused(KtnWpeWebView *view, int focused);
void ktn_wpe_webview_pointer_motion(
    KtnWpeWebView *view,
    int x,
    int y,
    unsigned int time,
    unsigned int modifiers
);
void ktn_wpe_webview_pointer_button(
    KtnWpeWebView *view,
    int x,
    int y,
    unsigned int time,
    unsigned int button,
    int pressed,
    unsigned int modifiers
);
void ktn_wpe_webview_scroll(
    KtnWpeWebView *view,
    int x,
    int y,
    unsigned int time,
    double delta_x,
    double delta_y,
    unsigned int modifiers
);
void ktn_wpe_webview_key(
    KtnWpeWebView *view,
    long long compose_key,
    unsigned int code_point,
    int pressed,
    unsigned int modifiers
);

#ifdef __cplusplus
}
#endif

#endif
