#include "system_webview2.h"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <objbase.h>
#include <shellapi.h>
#include <wincodec.h>
#include <WebView2.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <cwchar>
#include <memory>
#include <string>
#include <vector>

namespace {

using CreateEnvironmentFn = decltype(&CreateCoreWebView2EnvironmentWithOptions);
using RuntimeVersionFn = decltype(&GetAvailableCoreWebView2BrowserVersionString);

thread_local std::string runtime_status;

std::wstring utf8_to_wide(const char *value) {
    if (!value || !*value) return {};
    const int size = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value, -1, nullptr, 0);
    if (size <= 1) return {};
    std::wstring result(static_cast<size_t>(size), L'\0');
    if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value, -1, result.data(), size) <= 0) {
        return {};
    }
    result.resize(static_cast<size_t>(size - 1));
    return result;
}

std::string wide_to_utf8(const wchar_t *value) {
    if (!value || !*value) return {};
    const int size = WideCharToMultiByte(CP_UTF8, 0, value, -1, nullptr, 0, nullptr, nullptr);
    if (size <= 1) return {};
    std::string result(static_cast<size_t>(size), '\0');
    if (WideCharToMultiByte(CP_UTF8, 0, value, -1, result.data(), size, nullptr, nullptr) <= 0) {
        return {};
    }
    result.resize(static_cast<size_t>(size - 1));
    return result;
}

std::string hresult_text(const char *operation, HRESULT result) {
    char buffer[128]{};
    std::snprintf(
        buffer,
        sizeof(buffer),
        "%s failed (HRESULT 0x%08lx)",
        operation,
        static_cast<unsigned long>(result)
    );
    return buffer;
}

HMODULE load_webview2_loader() {
    return LoadLibraryW(L"WebView2Loader.dll");
}

template <typename T>
void release_com(T *&value) {
    if (!value) return;
    value->Release();
    value = nullptr;
}

struct State;

template <typename Interface>
const IID &handler_iid();

#define KTN_HANDLER_IID(type) \
    template <> const IID &handler_iid<type>() { return IID_##type; }

KTN_HANDLER_IID(ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler)
KTN_HANDLER_IID(ICoreWebView2CreateCoreWebView2ControllerCompletedHandler)
KTN_HANDLER_IID(ICoreWebView2NavigationStartingEventHandler)
KTN_HANDLER_IID(ICoreWebView2NavigationCompletedEventHandler)
KTN_HANDLER_IID(ICoreWebView2SourceChangedEventHandler)
KTN_HANDLER_IID(ICoreWebView2HistoryChangedEventHandler)
KTN_HANDLER_IID(ICoreWebView2DocumentTitleChangedEventHandler)
KTN_HANDLER_IID(ICoreWebView2ExecuteScriptCompletedHandler)
KTN_HANDLER_IID(ICoreWebView2CapturePreviewCompletedHandler)
KTN_HANDLER_IID(ICoreWebView2GetCookiesCompletedHandler)
KTN_HANDLER_IID(ICoreWebView2AddScriptToExecuteOnDocumentCreatedCompletedHandler)
KTN_HANDLER_IID(ICoreWebView2WebMessageReceivedEventHandler)

#undef KTN_HANDLER_IID

template <typename Interface>
class ComHandlerBase : public Interface {
public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void **object) override {
        if (!object) return E_POINTER;
        *object = nullptr;
        if (IsEqualIID(iid, IID_IUnknown) || IsEqualIID(iid, handler_iid<Interface>())) {
            *object = static_cast<Interface *>(this);
            AddRef();
            return S_OK;
        }
        return E_NOINTERFACE;
    }

    ULONG STDMETHODCALLTYPE AddRef() override { return ++references_; }

    ULONG STDMETHODCALLTYPE Release() override {
        const ULONG remaining = --references_;
        if (remaining == 0) delete this;
        return remaining;
    }

protected:
    virtual ~ComHandlerBase() = default;

private:
    std::atomic<ULONG> references_{1};
};

struct State : std::enable_shared_from_this<State> {
    HWND host = nullptr;
    HMODULE loader = nullptr;
    ICoreWebView2Environment *environment = nullptr;
    ICoreWebView2Controller *controller = nullptr;
    ICoreWebView2 *webview = nullptr;
    KtnWebView2StateCallback state_callback = nullptr;
    void *user_data = nullptr;
    KtnWebView2NavigationCallback navigation_callback = nullptr;
    void *navigation_user_data = nullptr;
    KtnWebView2MessageCallback message_callback = nullptr;
    void *message_user_data = nullptr;
    bool closed = false;
    bool ready = false;
    bool loading = false;
    bool com_initialized = false;
    bool javascript_enabled = true;
    int width = 1;
    int height = 1;
    std::vector<unsigned char> frame;
    int frame_width = 0;
    int frame_height = 0;
    int frame_stride = 0;
    bool frame_ready = false;
    bool capture_in_flight = false;
    ULONGLONG last_capture = 0;
    float progress = 0.0f;
    bool can_go_back = false;
    bool can_go_forward = false;
    std::wstring user_agent;
    std::wstring user_data_folder;
    bool delete_user_data_on_close = false;
    std::string url;
    std::string title;
    std::string error;

    EventRegistrationToken navigation_starting{};
    EventRegistrationToken navigation_completed{};
    EventRegistrationToken source_changed{};
    EventRegistrationToken history_changed{};
    EventRegistrationToken title_changed{};
    EventRegistrationToken web_message_received{};
    bool has_navigation_starting = false;
    bool has_navigation_completed = false;
    bool has_source_changed = false;
    bool has_history_changed = false;
    bool has_title_changed = false;
    bool has_web_message_received = false;

    ~State() {
        close();
        if (loader) FreeLibrary(loader);
        if (com_initialized) CoUninitialize();
    }

    void notify() {
        if (!closed && state_callback) state_callback(user_data);
    }

    void set_error(const std::string &message) {
        error = message;
        notify();
    }

    void refresh() {
        if (!webview) return;
        LPWSTR source = nullptr;
        if (SUCCEEDED(webview->get_Source(&source)) && source) {
            url = wide_to_utf8(source);
            CoTaskMemFree(source);
        }
        LPWSTR current_title = nullptr;
        if (SUCCEEDED(webview->get_DocumentTitle(&current_title)) && current_title) {
            title = wide_to_utf8(current_title);
            CoTaskMemFree(current_title);
        }
        BOOL back = FALSE;
        BOOL forward = FALSE;
        if (SUCCEEDED(webview->get_CanGoBack(&back))) can_go_back = back != FALSE;
        if (SUCCEEDED(webview->get_CanGoForward(&forward))) can_go_forward = forward != FALSE;
    }

    void apply_bounds() {
        const int safe_width = width > 0 ? width : 1;
        const int safe_height = height > 0 ? height : 1;
        if (host) {
            SetWindowPos(
                host,
                nullptr,
                -32000,
                -32000,
                safe_width,
                safe_height,
                SWP_NOACTIVATE | SWP_NOZORDER
            );
        }
        if (!controller) return;
        RECT bounds{0, 0, safe_width, safe_height};
        controller->put_Bounds(bounds);
        controller->put_IsVisible(TRUE);
    }

    void remove_handlers() {
        if (!webview) return;
        if (has_navigation_starting) webview->remove_NavigationStarting(navigation_starting);
        if (has_navigation_completed) webview->remove_NavigationCompleted(navigation_completed);
        if (has_source_changed) webview->remove_SourceChanged(source_changed);
        if (has_history_changed) webview->remove_HistoryChanged(history_changed);
        if (has_title_changed) webview->remove_DocumentTitleChanged(title_changed);
        if (has_web_message_received) webview->remove_WebMessageReceived(web_message_received);
        has_navigation_starting = false;
        has_navigation_completed = false;
        has_source_changed = false;
        has_history_changed = false;
        has_title_changed = false;
        has_web_message_received = false;
    }

    void close() {
        if (closed && !webview && !controller && !environment) return;
        closed = true;
        state_callback = nullptr;
        user_data = nullptr;
        remove_handlers();
        if (controller) controller->Close();
        release_com(webview);
        release_com(controller);
        release_com(environment);
        if (delete_user_data_on_close && !user_data_folder.empty()) {
            WIN32_FIND_DATAW find_data{};
            const std::wstring pattern = user_data_folder + L"\\*";
            HANDLE find = FindFirstFileW(pattern.c_str(), &find_data);
            if (find != INVALID_HANDLE_VALUE) {
                do {
                    const wchar_t *name = find_data.cFileName;
                    if (!std::wcscmp(name, L".") || !std::wcscmp(name, L"..")) continue;
                    const std::wstring child = user_data_folder + L"\\" + name;
                    if (find_data.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
                        // WebView2 profile contents can be nested deeply. Defer recursive cleanup
                        // to the shell so read-only attributes and nested directories are handled.
                        SHFILEOPSTRUCTW operation{};
                        std::wstring source = child;
                        source.push_back(L'\0');
                        source.push_back(L'\0');
                        operation.wFunc = FO_DELETE;
                        operation.pFrom = source.c_str();
                        operation.fFlags = FOF_NO_UI;
                        SHFileOperationW(&operation);
                    } else {
                        SetFileAttributesW(child.c_str(), FILE_ATTRIBUTE_NORMAL);
                        DeleteFileW(child.c_str());
                    }
                } while (FindNextFileW(find, &find_data));
                FindClose(find);
            }
            RemoveDirectoryW(user_data_folder.c_str());
        }
        if (host) {
            DestroyWindow(host);
            host = nullptr;
        }
        ready = false;
        loading = false;
        capture_in_flight = false;
        frame_ready = false;
    }
};

template <typename Interface, typename Args>
class StateEventHandler final : public ComHandlerBase<Interface> {
public:
    using Callback = void (*)(State &, ICoreWebView2 *, Args *);
    StateEventHandler(const std::weak_ptr<State> &state, Callback callback)
        : state_(state), callback_(callback) {}

    HRESULT STDMETHODCALLTYPE Invoke(ICoreWebView2 *sender, Args *args) override {
        if (auto state = state_.lock(); state && !state->closed) callback_(*state, sender, args);
        return S_OK;
    }

private:
    std::weak_ptr<State> state_;
    Callback callback_;
};

class ExecuteScriptHandler final : public ComHandlerBase<ICoreWebView2ExecuteScriptCompletedHandler> {
public:
    ExecuteScriptHandler(KtnWebView2JavaScriptCallback callback, void *user_data)
        : callback_(callback), user_data_(user_data) {}

    HRESULT STDMETHODCALLTYPE Invoke(HRESULT error_code, LPCWSTR result) override {
        if (!callback_) return S_OK;
        if (FAILED(error_code)) {
            const std::string error = hresult_text("ExecuteScript", error_code);
            callback_(user_data_, 0, error.c_str());
        } else {
            const std::string value = wide_to_utf8(result);
            callback_(user_data_, 1, value.c_str());
        }
        callback_ = nullptr;
        user_data_ = nullptr;
        return S_OK;
    }

private:
    KtnWebView2JavaScriptCallback callback_;
    void *user_data_;
};

class AddScriptHandler final
    : public ComHandlerBase<ICoreWebView2AddScriptToExecuteOnDocumentCreatedCompletedHandler> {
public:
    HRESULT STDMETHODCALLTYPE Invoke(HRESULT, LPCWSTR) override { return S_OK; }
};

std::string json_string(const std::string &value) {
    std::string output = "\"";
    for (const unsigned char ch : value) {
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
                    char escaped[7]{};
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

class GetCookiesHandler final : public ComHandlerBase<ICoreWebView2GetCookiesCompletedHandler> {
public:
    GetCookiesHandler(KtnWebView2CookieCallback callback, void *user_data)
        : callback_(callback), user_data_(user_data) {}

    HRESULT STDMETHODCALLTYPE Invoke(HRESULT error_code, ICoreWebView2CookieList *result) override {
        if (!callback_) return S_OK;
        if (FAILED(error_code) || !result) {
            const std::string error = hresult_text("GetCookies", error_code);
            callback_(user_data_, 0, error.c_str());
            finish();
            return S_OK;
        }

        UINT count = 0;
        const HRESULT count_result = result->get_Count(&count);
        if (FAILED(count_result)) {
            const std::string error = hresult_text("CookieList.get_Count", count_result);
            callback_(user_data_, 0, error.c_str());
            finish();
            return S_OK;
        }

        std::string json = "[";
        bool first = true;
        for (UINT index = 0; index < count; ++index) {
            ICoreWebView2Cookie *cookie = nullptr;
            if (FAILED(result->GetValueAtIndex(index, &cookie)) || !cookie) continue;

            LPWSTR name = nullptr;
            LPWSTR value = nullptr;
            LPWSTR domain = nullptr;
            LPWSTR path = nullptr;
            double expires = 0.0;
            BOOL is_http_only = FALSE;
            BOOL is_secure = FALSE;
            BOOL is_session = FALSE;
            COREWEBVIEW2_COOKIE_SAME_SITE_KIND same_site = COREWEBVIEW2_COOKIE_SAME_SITE_KIND_NONE;

            cookie->get_Name(&name);
            cookie->get_Value(&value);
            cookie->get_Domain(&domain);
            cookie->get_Path(&path);
            cookie->get_Expires(&expires);
            cookie->get_IsHttpOnly(&is_http_only);
            cookie->get_IsSecure(&is_secure);
            cookie->get_IsSession(&is_session);
            cookie->get_SameSite(&same_site);

            if (!first) json += ',';
            first = false;
            json += "{\"name\":" + json_string(wide_to_utf8(name));
            json += ",\"value\":" + json_string(wide_to_utf8(value));
            json += ",\"domain\":" + json_string(wide_to_utf8(domain));
            json += ",\"path\":" + json_string(wide_to_utf8(path));
            const long long expires_millis = is_session
                ? -1LL
                : static_cast<long long>(expires * 1000.0);
            json += ",\"expiresAtMillis\":" + std::to_string(expires_millis);
            json += ",\"secure\":" + std::string(is_secure ? "true" : "false");
            json += ",\"httpOnly\":" + std::string(is_http_only ? "true" : "false");
            json += ",\"sameSite\":" + std::to_string(static_cast<int>(same_site));
            json += '}';

            if (name) CoTaskMemFree(name);
            if (value) CoTaskMemFree(value);
            if (domain) CoTaskMemFree(domain);
            if (path) CoTaskMemFree(path);
            cookie->Release();
        }
        json += ']';
        callback_(user_data_, 1, json.c_str());
        finish();
        return S_OK;
    }

private:
    void finish() {
        callback_ = nullptr;
        user_data_ = nullptr;
    }

    KtnWebView2CookieCallback callback_;
    void *user_data_;
};

static bool decode_png(State &state, IStream *stream) {
    LARGE_INTEGER start{};
    if (FAILED(stream->Seek(start, STREAM_SEEK_SET, nullptr))) return false;

    IWICImagingFactory *factory = nullptr;
    IWICBitmapDecoder *decoder = nullptr;
    IWICBitmapFrameDecode *source = nullptr;
    IWICFormatConverter *converter = nullptr;
    HRESULT result = CoCreateInstance(
        CLSID_WICImagingFactory,
        nullptr,
        CLSCTX_INPROC_SERVER,
        IID_PPV_ARGS(&factory)
    );
    if (SUCCEEDED(result)) {
        result = factory->CreateDecoderFromStream(
            stream,
            nullptr,
            WICDecodeMetadataCacheOnLoad,
            &decoder
        );
    }
    if (SUCCEEDED(result)) result = decoder->GetFrame(0, &source);
    if (SUCCEEDED(result)) result = factory->CreateFormatConverter(&converter);
    if (SUCCEEDED(result)) {
        result = converter->Initialize(
            source,
            GUID_WICPixelFormat32bppPBGRA,
            WICBitmapDitherTypeNone,
            nullptr,
            0.0,
            WICBitmapPaletteTypeCustom
        );
    }

    UINT width = 0;
    UINT height = 0;
    if (SUCCEEDED(result)) result = converter->GetSize(&width, &height);
    const UINT stride = width * 4;
    std::vector<unsigned char> pixels;
    if (SUCCEEDED(result) && width && height) {
        pixels.resize(static_cast<size_t>(stride) * height);
        result = converter->CopyPixels(
            nullptr,
            stride,
            static_cast<UINT>(pixels.size()),
            pixels.data()
        );
    }
    if (SUCCEEDED(result) && !state.closed) {
        state.frame = std::move(pixels);
        state.frame_width = static_cast<int>(width);
        state.frame_height = static_cast<int>(height);
        state.frame_stride = static_cast<int>(stride);
        state.frame_ready = true;
    }

    if (converter) converter->Release();
    if (source) source->Release();
    if (decoder) decoder->Release();
    if (factory) factory->Release();
    return SUCCEEDED(result);
}

class CapturePreviewHandler final : public ComHandlerBase<ICoreWebView2CapturePreviewCompletedHandler> {
public:
    CapturePreviewHandler(std::shared_ptr<State> state, IStream *stream)
        : state_(std::move(state)), stream_(stream) {
        stream_->AddRef();
    }

    ~CapturePreviewHandler() override {
        stream_->Release();
    }

    HRESULT STDMETHODCALLTYPE Invoke(HRESULT error_code) override {
        auto state = state_;
        if (!state || state->closed) return S_OK;
        if (SUCCEEDED(error_code)) {
            if (!decode_png(*state, stream_)) {
                state->set_error("Could not decode the WebView2 preview");
            }
        } else {
            state->set_error(hresult_text("CapturePreview", error_code));
        }
        state->capture_in_flight = false;
        state->notify();
        return S_OK;
    }

private:
    std::shared_ptr<State> state_;
    IStream *stream_;
};

static void request_capture(const std::shared_ptr<State> &state) {
    if (!state || !state->webview || state->capture_in_flight || state->closed) return;
    const ULONGLONG now = GetTickCount64();
    if (now - state->last_capture < 32) return;
    state->last_capture = now;

    IStream *stream = nullptr;
    HRESULT result = CreateStreamOnHGlobal(nullptr, TRUE, &stream);
    if (FAILED(result) || !stream) {
        state->set_error(hresult_text("CreateStreamOnHGlobal", result));
        return;
    }
    state->capture_in_flight = true;
    auto *handler = new CapturePreviewHandler(state, stream);
    result = state->webview->CapturePreview(
        COREWEBVIEW2_CAPTURE_PREVIEW_IMAGE_FORMAT_PNG,
        stream,
        handler
    );
    handler->Release();
    stream->Release();
    if (FAILED(result)) {
        state->capture_in_flight = false;
        state->set_error(hresult_text("CapturePreview", result));
    }
}

static HWND find_input_window(HWND parent) {
    if (!parent) return nullptr;
    HWND child = GetWindow(parent, GW_CHILD);
    if (!child) return parent;
    while (HWND nested = GetWindow(child, GW_CHILD)) child = nested;
    return child;
}

static WPARAM mouse_modifiers(unsigned int modifiers) {
    WPARAM result = 0;
    if (modifiers & 1u) result |= MK_CONTROL;
    if (modifiers & 2u) result |= MK_SHIFT;
    return result;
}

static UINT virtual_key(long long key) {
    switch (key) {
        case 0: return 'A'; case 11: return 'B'; case 8: return 'C'; case 2: return 'D';
        case 14: return 'E'; case 3: return 'F'; case 5: return 'G'; case 4: return 'H';
        case 34: return 'I'; case 38: return 'J'; case 40: return 'K'; case 37: return 'L';
        case 46: return 'M'; case 45: return 'N'; case 31: return 'O'; case 35: return 'P';
        case 12: return 'Q'; case 15: return 'R'; case 1: return 'S'; case 17: return 'T';
        case 32: return 'U'; case 9: return 'V'; case 13: return 'W'; case 7: return 'X';
        case 16: return 'Y'; case 6: return 'Z';
        case 29: return '0'; case 18: return '1'; case 19: return '2'; case 20: return '3';
        case 21: return '4'; case 23: return '5'; case 22: return '6'; case 26: return '7';
        case 28: return '8'; case 25: return '9';
        case 36: return VK_RETURN;
        case 48: return VK_TAB;
        case 49: return VK_SPACE;
        case 51: return VK_BACK;
        case 53: return VK_ESCAPE;
        case 117: return VK_DELETE;
        case 123: return VK_LEFT;
        case 124: return VK_RIGHT;
        case 125: return VK_DOWN;
        case 126: return VK_UP;
        case 115: return VK_HOME;
        case 119: return VK_END;
        case 116: return VK_PRIOR;
        case 121: return VK_NEXT;
        case 56: return VK_LSHIFT;
        case 60: return VK_RSHIFT;
        case 59: return VK_LCONTROL;
        case 62: return VK_RCONTROL;
        case 58: return VK_LMENU;
        case 61: return VK_RMENU;
        case 55: return VK_LWIN;
        case 54: return VK_RWIN;
        case 27: return VK_OEM_MINUS;
        case 24: return VK_OEM_PLUS;
        case 43: return VK_OEM_COMMA;
        case 47: return VK_OEM_PERIOD;
        case 44: return VK_OEM_2;
        case 41: return VK_OEM_1;
        case 39: return VK_OEM_7;
        case 42: return VK_OEM_5;
        case 33: return VK_OEM_4;
        case 30: return VK_OEM_6;
        case 50: return VK_OEM_3;
        default: return 0;
    }
}

class ControllerCompletedHandler final
    : public ComHandlerBase<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler> {
public:
    explicit ControllerCompletedHandler(std::shared_ptr<State> state) : state_(std::move(state)) {}

    HRESULT STDMETHODCALLTYPE Invoke(HRESULT error_code, ICoreWebView2Controller *controller) override {
        auto state = std::move(state_);
        if (!state || state->closed) return S_OK;
        if (FAILED(error_code) || !controller) {
            state->set_error(hresult_text("CreateCoreWebView2Controller", error_code));
            return S_OK;
        }

        controller->AddRef();
        state->controller = controller;
        HRESULT result = controller->get_CoreWebView2(&state->webview);
        if (FAILED(result) || !state->webview) {
            state->set_error(hresult_text("get_CoreWebView2", result));
            return S_OK;
        }

        ICoreWebView2Settings *settings = nullptr;
        if (SUCCEEDED(state->webview->get_Settings(&settings)) && settings) {
            settings->put_IsScriptEnabled(state->javascript_enabled ? TRUE : FALSE);
            if (!state->user_agent.empty()) {
                ICoreWebView2Settings2 *settings2 = nullptr;
                if (SUCCEEDED(settings->QueryInterface(
                        IID_ICoreWebView2Settings2,
                        reinterpret_cast<void **>(&settings2)
                    )) && settings2) {
                    settings2->put_UserAgent(state->user_agent.c_str());
                    settings2->Release();
                }
            }
            settings->Release();
        }

        auto weak = std::weak_ptr<State>(state);
        {
            using Handler = StateEventHandler<
                ICoreWebView2NavigationStartingEventHandler,
                ICoreWebView2NavigationStartingEventArgs
            >;
            auto *handler = new Handler(
                weak,
                [](State &value, ICoreWebView2 *, ICoreWebView2NavigationStartingEventArgs *args) {
                    if (args && value.navigation_callback) {
                        LPWSTR uri = nullptr;
                        BOOL redirected = FALSE;
                        BOOL user_initiated = FALSE;
                        args->get_Uri(&uri);
                        args->get_IsRedirected(&redirected);
                        args->get_IsUserInitiated(&user_initiated);
                        const std::string utf8_uri = wide_to_utf8(uri);
                        if (uri) CoTaskMemFree(uri);
                        const int decision = value.navigation_callback(
                            value.navigation_user_data,
                            utf8_uri.c_str(),
                            redirected != FALSE,
                            user_initiated != FALSE
                        );
                        if (decision != 0) {
                            args->put_Cancel(TRUE);
                            if (decision == 2) {
                                const std::wstring external_uri = utf8_to_wide(utf8_uri.c_str());
                                if (!external_uri.empty()) {
                                    ShellExecuteW(
                                        nullptr,
                                        L"open",
                                        external_uri.c_str(),
                                        nullptr,
                                        nullptr,
                                        SW_SHOWNORMAL
                                    );
                                }
                            }
                            return;
                        }
                    }
                    value.loading = true;
                    value.progress = 0.0f;
                    value.error.clear();
                    value.refresh();
                    value.notify();
                }
            );
            if (SUCCEEDED(state->webview->add_NavigationStarting(handler, &state->navigation_starting))) {
                state->has_navigation_starting = true;
            }
            handler->Release();
        }
        {
            using Handler = StateEventHandler<
                ICoreWebView2NavigationCompletedEventHandler,
                ICoreWebView2NavigationCompletedEventArgs
            >;
            auto *handler = new Handler(
                weak,
                [](State &value, ICoreWebView2 *, ICoreWebView2NavigationCompletedEventArgs *args) {
                    value.loading = false;
                    value.progress = 1.0f;
                    BOOL success = FALSE;
                    if (args && SUCCEEDED(args->get_IsSuccess(&success)) && !success) {
                        COREWEBVIEW2_WEB_ERROR_STATUS status = COREWEBVIEW2_WEB_ERROR_STATUS_UNKNOWN;
                        args->get_WebErrorStatus(&status);
                        value.error = "WebView2 navigation failed (status " +
                            std::to_string(static_cast<int>(status)) + ")";
                    } else {
                        value.error.clear();
                    }
                    value.refresh();
                    value.notify();
                }
            );
            if (SUCCEEDED(state->webview->add_NavigationCompleted(handler, &state->navigation_completed))) {
                state->has_navigation_completed = true;
            }
            handler->Release();
        }
        {
            using Handler = StateEventHandler<ICoreWebView2SourceChangedEventHandler, ICoreWebView2SourceChangedEventArgs>;
            auto *handler = new Handler(
                weak,
                [](State &value, ICoreWebView2 *, ICoreWebView2SourceChangedEventArgs *) {
                    value.refresh();
                    value.notify();
                }
            );
            if (SUCCEEDED(state->webview->add_SourceChanged(handler, &state->source_changed))) {
                state->has_source_changed = true;
            }
            handler->Release();
        }
        {
            using Handler = StateEventHandler<ICoreWebView2HistoryChangedEventHandler, IUnknown>;
            auto *handler = new Handler(
                weak,
                [](State &value, ICoreWebView2 *, IUnknown *) {
                    value.refresh();
                    value.notify();
                }
            );
            if (SUCCEEDED(state->webview->add_HistoryChanged(handler, &state->history_changed))) {
                state->has_history_changed = true;
            }
            handler->Release();
        }
        {
            using Handler = StateEventHandler<ICoreWebView2DocumentTitleChangedEventHandler, IUnknown>;
            auto *handler = new Handler(
                weak,
                [](State &value, ICoreWebView2 *, IUnknown *) {
                    value.refresh();
                    value.notify();
                }
            );
            if (SUCCEEDED(state->webview->add_DocumentTitleChanged(handler, &state->title_changed))) {
                state->has_title_changed = true;
            }
            handler->Release();
        }
        {
            using Handler = StateEventHandler<
                ICoreWebView2WebMessageReceivedEventHandler,
                ICoreWebView2WebMessageReceivedEventArgs
            >;
            auto *handler = new Handler(
                weak,
                [](State &value, ICoreWebView2 *, ICoreWebView2WebMessageReceivedEventArgs *args) {
                    if (!args || !value.message_callback) return;
                    LPWSTR message = nullptr;
                    if (FAILED(args->TryGetWebMessageAsString(&message)) || !message) return;
                    const std::string utf8 = wide_to_utf8(message);
                    CoTaskMemFree(message);
                    value.message_callback(value.message_user_data, utf8.c_str());
                }
            );
            if (SUCCEEDED(state->webview->add_WebMessageReceived(handler, &state->web_message_received))) {
                state->has_web_message_received = true;
            }
            handler->Release();
        }

        state->apply_bounds();
        state->ready = true;
        state->loading = false;
        state->progress = 1.0f;
        state->refresh();
        state->notify();
        return S_OK;
    }

private:
    std::shared_ptr<State> state_;
};

class EnvironmentCompletedHandler final
    : public ComHandlerBase<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler> {
public:
    explicit EnvironmentCompletedHandler(std::shared_ptr<State> state) : state_(std::move(state)) {}

    HRESULT STDMETHODCALLTYPE Invoke(HRESULT error_code, ICoreWebView2Environment *environment) override {
        auto state = std::move(state_);
        if (!state || state->closed) return S_OK;
        if (FAILED(error_code) || !environment) {
            state->set_error(hresult_text("CreateCoreWebView2EnvironmentWithOptions", error_code));
            return S_OK;
        }

        environment->AddRef();
        state->environment = environment;
        auto *handler = new ControllerCompletedHandler(state);
        const HRESULT result = environment->CreateCoreWebView2Controller(state->host, handler);
        handler->Release();
        if (FAILED(result)) state->set_error(hresult_text("CreateCoreWebView2Controller", result));
        return S_OK;
    }

private:
    std::shared_ptr<State> state_;
};

void fail_operation(const std::shared_ptr<State> &state, const char *name, HRESULT result) {
    if (state && FAILED(result)) state->set_error(hresult_text(name, result));
}

std::wstring user_data_folder() {
    wchar_t local_app_data[MAX_PATH]{};
    const DWORD size = GetEnvironmentVariableW(L"LOCALAPPDATA", local_app_data, MAX_PATH);
    if (size == 0 || size >= MAX_PATH) return L"WebViewKmpWebView2";
    std::wstring parent(local_app_data);
    parent += L"\\WebViewKmp";
    CreateDirectoryW(parent.c_str(), nullptr);
    std::wstring folder = parent + L"\\WebView2";
    CreateDirectoryW(folder.c_str(), nullptr);
    return folder;
}

std::wstring profile_user_data_folder(
    int profile_mode,
    const char *profile_name,
    bool *delete_on_close
) {
    if (delete_on_close) *delete_on_close = false;
    if (profile_mode == 0) return user_data_folder();

    wchar_t base[MAX_PATH]{};
    if (profile_mode == 1) {
        const DWORD size = GetTempPathW(MAX_PATH, base);
        std::wstring parent = size > 0 && size < MAX_PATH ? std::wstring(base) : L".";
        if (!parent.empty() && parent.back() != L'\\') parent += L'\\';
        parent += L"WebViewKmp";
        CreateDirectoryW(parent.c_str(), nullptr);
        static std::atomic<unsigned long> serial{0};
        const std::wstring folder =
            parent + L"\\Ephemeral-" + std::to_wstring(GetCurrentProcessId()) + L"-" +
            std::to_wstring(++serial);
        CreateDirectoryW(folder.c_str(), nullptr);
        if (delete_on_close) *delete_on_close = true;
        return folder;
    }

    const DWORD size = GetEnvironmentVariableW(L"LOCALAPPDATA", base, MAX_PATH);
    std::wstring parent = size > 0 && size < MAX_PATH ? std::wstring(base) : L".";
    parent += L"\\WebViewKmp";
    CreateDirectoryW(parent.c_str(), nullptr);
    parent += L"\\Profiles";
    CreateDirectoryW(parent.c_str(), nullptr);

    std::wstring name = utf8_to_wide(profile_name);
    if (name.empty()) name = L"default";
    for (wchar_t &character : name) {
        const bool valid =
            (character >= L'a' && character <= L'z') ||
            (character >= L'A' && character <= L'Z') ||
            (character >= L'0' && character <= L'9') ||
            character == L'.' || character == L'_' || character == L'-';
        if (!valid) character = L'_';
    }
    const std::wstring folder = parent + L"\\" + name;
    CreateDirectoryW(folder.c_str(), nullptr);
    return folder;
}

} // namespace

struct KtnWebView2 {
    std::shared_ptr<State> state;
};

namespace {

std::shared_ptr<State> state_of(KtnWebView2 *view) {
    return view ? view->state : nullptr;
}

HRESULT cookie_manager_of(
    const std::shared_ptr<State> &state,
    ICoreWebView2CookieManager **manager
) {
    if (!manager) return E_POINTER;
    *manager = nullptr;
    if (!state || !state->webview || state->closed) return E_FAIL;
    ICoreWebView2_2 *webview2 = nullptr;
    HRESULT result = state->webview->QueryInterface(IID_PPV_ARGS(&webview2));
    if (SUCCEEDED(result) && webview2) {
        result = webview2->get_CookieManager(manager);
        webview2->Release();
    }
    return result;
}

} // namespace

extern "C" int ktn_webview2_runtime_available(void) {
    runtime_status.clear();
    HMODULE loader = load_webview2_loader();
    if (!loader) {
        runtime_status = "WebView2Loader.dll was not found";
        return 0;
    }
    auto version_fn = reinterpret_cast<RuntimeVersionFn>(
        GetProcAddress(loader, "GetAvailableCoreWebView2BrowserVersionString")
    );
    if (!version_fn) {
        runtime_status = "WebView2Loader.dll does not export the runtime version API";
        FreeLibrary(loader);
        return 0;
    }
    LPWSTR version = nullptr;
    const HRESULT result = version_fn(nullptr, &version);
    if (FAILED(result) || !version || !*version) {
        runtime_status = hresult_text("WebView2 Runtime detection", result);
        if (version) CoTaskMemFree(version);
        FreeLibrary(loader);
        return 0;
    }
    runtime_status = "WebView2 Runtime " + wide_to_utf8(version);
    CoTaskMemFree(version);
    FreeLibrary(loader);
    return 1;
}

extern "C" const char *ktn_webview2_runtime_status(void) {
    if (runtime_status.empty()) ktn_webview2_runtime_available();
    return runtime_status.c_str();
}

extern "C" KtnWebView2 *ktn_webview2_create(
    int javascript_enabled,
    const char *user_agent,
    int profile_mode,
    const char *profile_name,
    KtnWebView2StateCallback state_callback,
    void *user_data
) {
    auto result = std::make_unique<KtnWebView2>();
    auto state = std::make_shared<State>();
    result->state = state;
    state->javascript_enabled = javascript_enabled != 0;
    state->user_agent = utf8_to_wide(user_agent);
    state->user_data_folder = profile_user_data_folder(
        profile_mode,
        profile_name,
        &state->delete_user_data_on_close
    );
    state->state_callback = state_callback;
    state->user_data = user_data;

    const HRESULT com_result = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    if (SUCCEEDED(com_result)) {
        state->com_initialized = true;
    } else if (com_result == RPC_E_CHANGED_MODE) {
        state->error = "WebView2 requires the UI thread to use a single-threaded COM apartment";
        return result.release();
    } else {
        state->error = hresult_text("CoInitializeEx", com_result);
        return result.release();
    }

    state->host = CreateWindowExW(
        WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
        L"STATIC",
        L"WebView KMP WebView2",
        WS_POPUP,
        -32000,
        -32000,
        1,
        1,
        nullptr,
        nullptr,
        GetModuleHandleW(nullptr),
        nullptr
    );
    if (!state->host) {
        state->error = hresult_text(
            "CreateWindowExW",
            HRESULT_FROM_WIN32(GetLastError())
        );
        return result.release();
    }
    ShowWindow(state->host, SW_SHOWNOACTIVATE);

    state->loader = load_webview2_loader();
    if (!state->loader) {
        state->error = "WebView2Loader.dll was not found";
        return result.release();
    }
    auto create_environment = reinterpret_cast<CreateEnvironmentFn>(
        GetProcAddress(state->loader, "CreateCoreWebView2EnvironmentWithOptions")
    );
    if (!create_environment) {
        state->error = "WebView2Loader.dll does not export CreateCoreWebView2EnvironmentWithOptions";
        return result.release();
    }

    auto *handler = new EnvironmentCompletedHandler(state);
    const HRESULT create_result = create_environment(
        nullptr,
        state->user_data_folder.c_str(),
        nullptr,
        handler
    );
    handler->Release();
    if (FAILED(create_result)) {
        state->error = hresult_text("CreateCoreWebView2EnvironmentWithOptions", create_result);
    }
    return result.release();
}

extern "C" void ktn_webview2_destroy(KtnWebView2 *view) {
    if (!view) return;
    if (view->state) view->state->close();
    view->state.reset();
    delete view;
}

extern "C" int ktn_webview2_is_ready(KtnWebView2 *view) {
    const auto state = state_of(view);
    return state && state->ready && !state->closed ? 1 : 0;
}

extern "C" int ktn_webview2_is_loading(KtnWebView2 *view) {
    const auto state = state_of(view);
    return state && state->loading ? 1 : 0;
}

extern "C" float ktn_webview2_progress(KtnWebView2 *view) {
    const auto state = state_of(view);
    return state ? state->progress : 0.0f;
}

extern "C" int ktn_webview2_can_go_back(KtnWebView2 *view) {
    const auto state = state_of(view);
    return state && state->can_go_back ? 1 : 0;
}

extern "C" int ktn_webview2_can_go_forward(KtnWebView2 *view) {
    const auto state = state_of(view);
    return state && state->can_go_forward ? 1 : 0;
}

extern "C" const char *ktn_webview2_url(KtnWebView2 *view) {
    const auto state = state_of(view);
    return state ? state->url.c_str() : "";
}

extern "C" const char *ktn_webview2_title(KtnWebView2 *view) {
    const auto state = state_of(view);
    return state ? state->title.c_str() : "";
}

extern "C" const char *ktn_webview2_error(KtnWebView2 *view) {
    const auto state = state_of(view);
    return state ? state->error.c_str() : "";
}

extern "C" int ktn_webview2_render_pixels(
    KtnWebView2 *view,
    void *pixels,
    int width,
    int height,
    int stride
) {
    const auto state = state_of(view);
    if (!state || state->closed || !pixels || width <= 0 || height <= 0 || stride < width * 4) {
        return 0;
    }
    state->width = width > 0 ? width : 1;
    state->height = height > 0 ? height : 1;
    state->apply_bounds();
    request_capture(state);
    if (!state->frame_ready || state->frame_width <= 0 || state->frame_height <= 0) return 0;

    auto *destination = static_cast<unsigned char *>(pixels);
    if (state->frame_width == width && state->frame_height == height) {
        for (int row = 0; row < height; ++row) {
            std::memcpy(
                destination + static_cast<size_t>(row) * stride,
                state->frame.data() + static_cast<size_t>(row) * state->frame_stride,
                static_cast<size_t>(width) * 4
            );
        }
    } else {
        for (int row = 0; row < height; ++row) {
            const int source_y = std::min(
                state->frame_height - 1,
                row * state->frame_height / height
            );
            const auto *source =
                state->frame.data() + static_cast<size_t>(source_y) * state->frame_stride;
            auto *target = destination + static_cast<size_t>(row) * stride;
            for (int column = 0; column < width; ++column) {
                const int source_x = std::min(
                    state->frame_width - 1,
                    column * state->frame_width / width
                );
                std::memcpy(
                    target + static_cast<size_t>(column) * 4,
                    source + static_cast<size_t>(source_x) * 4,
                    4
                );
            }
        }
    }
    state->frame_ready = false;
    return 1;
}

extern "C" void ktn_webview2_navigate(KtnWebView2 *view, const char *url) {
    const auto state = state_of(view);
    if (!state || !state->webview || state->closed) return;
    const std::wstring wide = utf8_to_wide(url);
    if (wide.empty()) return;
    fail_operation(state, "Navigate", state->webview->Navigate(wide.c_str()));
}

extern "C" void ktn_webview2_navigate_html(KtnWebView2 *view, const char *html) {
    const auto state = state_of(view);
    if (!state || !state->webview || state->closed) return;
    const std::wstring wide = utf8_to_wide(html);
    fail_operation(state, "NavigateToString", state->webview->NavigateToString(wide.c_str()));
}

extern "C" void ktn_webview2_reload(KtnWebView2 *view) {
    const auto state = state_of(view);
    if (!state || !state->webview || state->closed) return;
    fail_operation(state, "Reload", state->webview->Reload());
}

extern "C" void ktn_webview2_stop(KtnWebView2 *view) {
    const auto state = state_of(view);
    if (!state || !state->webview || state->closed) return;
    fail_operation(state, "Stop", state->webview->Stop());
}

extern "C" void ktn_webview2_go_back(KtnWebView2 *view) {
    const auto state = state_of(view);
    if (!state || !state->webview || state->closed || !state->can_go_back) return;
    fail_operation(state, "GoBack", state->webview->GoBack());
}

extern "C" void ktn_webview2_go_forward(KtnWebView2 *view) {
    const auto state = state_of(view);
    if (!state || !state->webview || state->closed || !state->can_go_forward) return;
    fail_operation(state, "GoForward", state->webview->GoForward());
}

extern "C" void ktn_webview2_set_focused(KtnWebView2 *view, int focused) {
    const auto state = state_of(view);
    if (!state || !state->controller || state->closed || !focused) return;
    state->controller->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
}

extern "C" void ktn_webview2_pointer_motion(
    KtnWebView2 *view,
    int x,
    int y,
    unsigned int,
    unsigned int modifiers
) {
    const auto state = state_of(view);
    if (!state || !state->host || state->closed) return;
    HWND input = find_input_window(state->host);
    if (!input) return;
    PostMessageW(input, WM_MOUSEMOVE, mouse_modifiers(modifiers), MAKELPARAM(x, y));
}

extern "C" void ktn_webview2_pointer_button(
    KtnWebView2 *view,
    int x,
    int y,
    unsigned int,
    unsigned int button,
    int pressed,
    unsigned int modifiers
) {
    const auto state = state_of(view);
    if (!state || !state->host || state->closed) return;
    HWND input = find_input_window(state->host);
    if (!input) return;
    const UINT down =
        button == 3 ? WM_RBUTTONDOWN : button == 2 ? WM_MBUTTONDOWN : WM_LBUTTONDOWN;
    const UINT up =
        button == 3 ? WM_RBUTTONUP : button == 2 ? WM_MBUTTONUP : WM_LBUTTONUP;
    PostMessageW(
        input,
        pressed ? down : up,
        mouse_modifiers(modifiers),
        MAKELPARAM(x, y)
    );
}

extern "C" void ktn_webview2_scroll(
    KtnWebView2 *view,
    int x,
    int y,
    unsigned int,
    double delta_x,
    double delta_y,
    unsigned int modifiers
) {
    const auto state = state_of(view);
    if (!state || !state->host || state->closed) return;
    HWND input = find_input_window(state->host);
    if (!input) return;
    POINT point{x, y};
    ClientToScreen(input, &point);
    if (delta_y != 0.0) {
        const int wheel = static_cast<int>(std::lround(-delta_y * WHEEL_DELTA));
        PostMessageW(
            input,
            WM_MOUSEWHEEL,
            MAKEWPARAM(mouse_modifiers(modifiers), static_cast<WORD>(wheel)),
            MAKELPARAM(point.x, point.y)
        );
    }
    if (delta_x != 0.0) {
        const int wheel = static_cast<int>(std::lround(delta_x * WHEEL_DELTA));
        PostMessageW(
            input,
            WM_MOUSEHWHEEL,
            MAKEWPARAM(mouse_modifiers(modifiers), static_cast<WORD>(wheel)),
            MAKELPARAM(point.x, point.y)
        );
    }
}

extern "C" void ktn_webview2_key(
    KtnWebView2 *view,
    long long compose_key,
    unsigned int code_point,
    int pressed,
    unsigned int modifiers
) {
    const auto state = state_of(view);
    if (!state || !state->host || state->closed) return;
    HWND input = find_input_window(state->host);
    if (!input) return;

    const bool has_command_modifier = (modifiers & (1u | 4u | 8u)) != 0;
    if (pressed && code_point && !has_command_modifier) {
        PostMessageW(input, WM_CHAR, static_cast<WPARAM>(code_point), 1);
        return;
    }

    const UINT key = virtual_key(compose_key);
    if (!key) return;
    PostMessageW(input, pressed ? WM_KEYDOWN : WM_KEYUP, key, 1);
}

extern "C" void ktn_webview2_evaluate_javascript(
    KtnWebView2 *view,
    const char *script,
    KtnWebView2JavaScriptCallback callback,
    void *user_data
) {
    const auto state = state_of(view);
    if (!callback) return;
    if (!state || !state->webview || state->closed) {
        callback(user_data, 0, "WebView2 is not ready");
        return;
    }
    const std::wstring wide = utf8_to_wide(script);
    auto *handler = new ExecuteScriptHandler(callback, user_data);
    const HRESULT result = state->webview->ExecuteScript(wide.c_str(), handler);
    if (FAILED(result)) {
        const std::string error = hresult_text("ExecuteScript", result);
        callback(user_data, 0, error.c_str());
    }
    handler->Release();
}

extern "C" void ktn_webview2_set_navigation_callback(
    KtnWebView2 *view,
    KtnWebView2NavigationCallback callback,
    void *user_data
) {
    const auto state = state_of(view);
    if (!state) return;
    state->navigation_callback = callback;
    state->navigation_user_data = user_data;
}

extern "C" void ktn_webview2_add_user_script(
    KtnWebView2 *view,
    const char *script
) {
    const auto state = state_of(view);
    if (!state || !state->webview || state->closed || !script || !*script) return;
    const std::wstring wide = utf8_to_wide(script);
    auto *handler = new AddScriptHandler();
    state->webview->AddScriptToExecuteOnDocumentCreated(wide.c_str(), handler);
    handler->Release();
}

extern "C" void ktn_webview2_set_message_callback(
    KtnWebView2 *view,
    KtnWebView2MessageCallback callback,
    void *user_data
) {
    const auto state = state_of(view);
    if (!state) return;
    state->message_callback = callback;
    state->message_user_data = user_data;
}

extern "C" void ktn_webview2_post_message(
    KtnWebView2 *view,
    const char *data
) {
    const auto state = state_of(view);
    if (!state || !state->webview || state->closed || !data) return;
    const std::wstring wide = utf8_to_wide(data);
    state->webview->PostWebMessageAsString(wide.c_str());
}

extern "C" void ktn_webview2_get_cookies(
    KtnWebView2 *view,
    const char *url,
    KtnWebView2CookieCallback callback,
    void *user_data
) {
    if (!callback) return;
    const auto state = state_of(view);
    ICoreWebView2CookieManager *manager = nullptr;
    const HRESULT manager_result = cookie_manager_of(state, &manager);
    if (FAILED(manager_result) || !manager) {
        const std::string error = hresult_text("WebView2 CookieManager", manager_result);
        callback(user_data, 0, error.c_str());
        return;
    }
    const std::wstring wide_url = utf8_to_wide(url);
    auto *handler = new GetCookiesHandler(callback, user_data);
    const HRESULT result = manager->GetCookies(wide_url.c_str(), handler);
    if (FAILED(result)) {
        const std::string error = hresult_text("GetCookies", result);
        callback(user_data, 0, error.c_str());
    }
    handler->Release();
    manager->Release();
}

extern "C" void ktn_webview2_set_cookie(
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
) {
    if (!callback) return;
    const auto state = state_of(view);
    ICoreWebView2CookieManager *manager = nullptr;
    HRESULT result = cookie_manager_of(state, &manager);
    if (FAILED(result) || !manager) {
        const std::string error = hresult_text("WebView2 CookieManager", result);
        callback(user_data, 0, error.c_str());
        return;
    }

    const std::wstring wide_name = utf8_to_wide(name);
    const std::wstring wide_value = utf8_to_wide(value);
    const std::wstring wide_domain = utf8_to_wide(domain);
    const std::wstring wide_path = utf8_to_wide(path);
    ICoreWebView2Cookie *cookie = nullptr;
    result = manager->CreateCookie(
        wide_name.c_str(),
        wide_value.c_str(),
        wide_domain.c_str(),
        wide_path.empty() ? L"/" : wide_path.c_str(),
        &cookie
    );
    if (SUCCEEDED(result) && cookie) {
        if (expires_at_millis >= 0) {
            result = cookie->put_Expires(static_cast<double>(expires_at_millis) / 1000.0);
        }
        if (SUCCEEDED(result) && secure >= 0) {
            result = cookie->put_IsSecure(secure ? TRUE : FALSE);
        }
        if (SUCCEEDED(result) && http_only >= 0) {
            result = cookie->put_IsHttpOnly(http_only ? TRUE : FALSE);
        }
        if (SUCCEEDED(result) && same_site >= 0 && same_site <= 2) {
            result = cookie->put_SameSite(
                static_cast<COREWEBVIEW2_COOKIE_SAME_SITE_KIND>(same_site)
            );
        }
        if (SUCCEEDED(result)) result = manager->AddOrUpdateCookie(cookie);
    }
    if (cookie) cookie->Release();
    manager->Release();
    if (FAILED(result)) {
        const std::string error = hresult_text("SetCookie", result);
        callback(user_data, 0, error.c_str());
    } else {
        callback(user_data, 1, nullptr);
    }
}

extern "C" void ktn_webview2_delete_cookie(
    KtnWebView2 *view,
    const char *url,
    const char *name,
    const char *domain,
    const char *path,
    KtnWebView2CookieCallback callback,
    void *user_data
) {
    if (!callback) return;
    const auto state = state_of(view);
    ICoreWebView2CookieManager *manager = nullptr;
    HRESULT result = cookie_manager_of(state, &manager);
    if (FAILED(result) || !manager) {
        const std::string error = hresult_text("WebView2 CookieManager", result);
        callback(user_data, 0, error.c_str());
        return;
    }
    const std::wstring wide_name = utf8_to_wide(name);
    if (domain && *domain && path && *path) {
        const std::wstring wide_domain = utf8_to_wide(domain);
        const std::wstring wide_path = utf8_to_wide(path);
        result = manager->DeleteCookiesWithDomainAndPath(
            wide_name.c_str(), wide_domain.c_str(), wide_path.c_str()
        );
    } else {
        const std::wstring wide_url = utf8_to_wide(url);
        result = manager->DeleteCookies(wide_name.c_str(), wide_url.c_str());
    }
    manager->Release();
    if (FAILED(result)) {
        const std::string error = hresult_text("DeleteCookie", result);
        callback(user_data, 0, error.c_str());
    } else {
        callback(user_data, 1, nullptr);
    }
}

extern "C" void ktn_webview2_clear_cookies(
    KtnWebView2 *view,
    KtnWebView2CookieCallback callback,
    void *user_data
) {
    if (!callback) return;
    const auto state = state_of(view);
    ICoreWebView2CookieManager *manager = nullptr;
    HRESULT result = cookie_manager_of(state, &manager);
    if (SUCCEEDED(result) && manager) result = manager->DeleteAllCookies();
    if (manager) manager->Release();
    if (FAILED(result)) {
        const std::string error = hresult_text("ClearCookies", result);
        callback(user_data, 0, error.c_str());
    } else {
        callback(user_data, 1, nullptr);
    }
}
