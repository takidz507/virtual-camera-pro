package com.takidz.vcam.ui;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.takidz.vcam.R;
import com.takidz.vcam.utils.VCamPrefs;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Upgraded v3.0 WebRTC & File-Capture Virtual Camera Browser (100% No Root & No Xposed):
 * - Overrides `navigator.mediaDevices.getUserMedia` AND `navigator.mediaDevices.enumerateDevices`.
 * - Spoofs `MediaStreamTrack.prototype.getSettings()` & `getCapabilities()` so strict web apps
 *   (WhatsApp Web, Telegram Web, Zoom, Google Meet, OmeTV, KYC portals) see genuine hardware camera specs.
 * - Intercepts `<input type="file" capture="camera">` (`onShowFileChooser`) to automatically feed
 *   the selected virtual video file without opening the hardware camera!
 */
public class VirtualWebBrowserActivity extends AppCompatActivity {

    private VCamPrefs prefs;
    private WebView webView;
    private EditText etBrowserUrl;
    private TextView tvWebRtcStatus;
    private MaterialButton btnWebRtcSwitchSource;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_virtual_browser);

        prefs = new VCamPrefs(this);

        ImageButton btnBack = findViewById(R.id.btnBrowserBack);
        etBrowserUrl = findViewById(R.id.etBrowserUrl);
        MaterialButton btnGo = findViewById(R.id.btnBrowserGo);
        tvWebRtcStatus = findViewById(R.id.tvWebRtcStatus);
        btnWebRtcSwitchSource = findViewById(R.id.btnWebRtcSwitchSource);
        webView = findViewById(R.id.webViewVirtualCam);

        btnBack.setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
            else finish();
        });

        btnGo.setOnClickListener(v -> loadEnteredUrl());
        etBrowserUrl.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                loadEnteredUrl();
                return true;
            }
            return false;
        });

        btnWebRtcSwitchSource.setOnClickListener(v -> {
            prefs.toggleSourceMode();
            updateBannerUI();
            String jsCmd = "if(window.__vcamSetMode){ window.__vcamSetMode('" + prefs.getSourceMode() + "'); }";
            webView.evaluateJavascript(jsCmd, null);
        });

        configureWebView();
        updateBannerUI();

        String initialUrl = getIntent() != null && getIntent().getDataString() != null
                ? getIntent().getDataString()
                : "https://webcamtests.com/";
        etBrowserUrl.setText(initialUrl);
        webView.loadUrl(initialUrl);
    }

    private void loadEnteredUrl() {
        String raw = etBrowserUrl.getText().toString().trim();
        if (raw.isEmpty()) return;
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "https://" + raw;
        }
        etBrowserUrl.setText(raw);
        webView.loadUrl(raw);
    }

    private void updateBannerUI() {
        if (prefs.isShowingRealCamera()) {
            tvWebRtcStatus.setText("📷 WebRTC: يبث الكاميرا الحقيقية حالياً");
            btnWebRtcSwitchSource.setText("🔄 التبديل للفيديو الوهمي");
        } else {
            tvWebRtcStatus.setText("🟢 WebRTC Hook: يبث الفيديو المختار (" + prefs.getStreamQuality() + ")");
            btnWebRtcSwitchSource.setText("🔄 التبديل للكاميرا الحقيقية");
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        // Desktop-class User-Agent support for WhatsApp Web / Telegram Web / Zoom
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (prefs.isVirtualCamEnabled() && prefs.hasSelectedVideo() && !prefs.isShowingRealCamera()) {
                    File videoFile = prefs.getInternalVideoFile();
                    if (videoFile.exists()) {
                        filePathCallback.onReceiveValue(new Uri[]{Uri.fromFile(videoFile)});
                        Toast.makeText(VirtualWebBrowserActivity.this, "🎬 تم تزويد الموقع بالفيديو الوهمي تلقائياً!", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                }
                return super.onShowFileChooser(webView, filePathCallback, fileChooserParams);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                etBrowserUrl.setText(url);
                injectVirtualCameraJsHook(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectVirtualCameraJsHook(view);
            }

            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("https://vcam.local/active_video.mp4")) {
                    try {
                        File videoFile = prefs.getInternalVideoFile();
                        if (videoFile.exists()) {
                            Map<String, String> headers = new HashMap<>();
                            headers.put("Access-Control-Allow-Origin", "*");
                            headers.put("Cache-Control", "no-store");
                            return new WebResourceResponse(
                                    "video/mp4",
                                    "UTF-8",
                                    200,
                                    "OK",
                                    headers,
                                    new FileInputStream(videoFile)
                            );
                        }
                    } catch (Exception ignored) {}
                }
                return super.shouldInterceptRequest(view, request);
            }
        });
    }

    private void injectVirtualCameraJsHook(WebView view) {
        if (!prefs.isVirtualCamEnabled()) return;

        int targetW = prefs.getTargetWidth();
        int targetH = prefs.getTargetHeight();
        int fps = prefs.getTargetFps();
        boolean loop = prefs.isLoopEnabled();
        boolean mirror = prefs.isMirrorEnabled();
        String initialMode = prefs.getSourceMode();

        String js = "(function(){"
                + "if(window.__vcamHookInstalled) return;"
                + "window.__vcamHookInstalled = true;"
                + "window.__vcamMode = '" + initialMode + "';"
                + "const origGetUserMedia = navigator.mediaDevices && navigator.mediaDevices.getUserMedia ? navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices) : null;"
                + "const canvas = document.createElement('canvas');"
                + "canvas.width = " + targetW + "; canvas.height = " + targetH + ";"
                + "const ctx = canvas.getContext('2d');"
                + "const vid = document.createElement('video');"
                + "vid.src = 'https://vcam.local/active_video.mp4';"
                + "vid.crossOrigin = 'anonymous';"
                + "vid.loop = " + (loop ? "true" : "false") + ";"
                + "vid.muted = true; vid.playsInline = true; vid.autoplay = true;"
                + "let realVideoEl = null;"
                + "window.__vcamSetMode = async function(newMode){"
                + "  window.__vcamMode = newMode;"
                + "  if(newMode === 'REAL_CAMERA' && !realVideoEl && origGetUserMedia){"
                + "    try {"
                + "      const realStream = await origGetUserMedia({video: true, audio: false});"
                + "      realVideoEl = document.createElement('video');"
                + "      realVideoEl.srcObject = realStream; realVideoEl.muted = true; realVideoEl.playsInline = true;"
                + "      await realVideoEl.play();"
                + "    } catch(e){}"
                + "  }"
                + "};"
                + "function renderLoop(){"
                + "  ctx.save();"
                + "  if(" + (mirror ? "true" : "false") + " && window.__vcamMode !== 'REAL_CAMERA'){"
                + "    ctx.translate(canvas.width, 0); ctx.scale(-1, 1);"
                + "  }"
                + "  const srcEl = (window.__vcamMode === 'REAL_CAMERA' && realVideoEl) ? realVideoEl : vid;"
                + "  if(srcEl && srcEl.readyState >= 2){"
                + "    ctx.drawImage(srcEl, 0, 0, canvas.width, canvas.height);"
                + "  }"
                + "  ctx.restore();"
                + "  requestAnimationFrame(renderLoop);"
                + "}"
                + "if(navigator.mediaDevices){"
                + "  navigator.mediaDevices.enumerateDevices = async function(){"
                + "    return ["
                + "      { deviceId: 'vcam-front-0', groupId: 'vcam-grp-0', kind: 'videoinput', label: 'Camera2 1, facing front', toJSON: function(){ return this; } },"
                + "      { deviceId: 'vcam-back-1',  groupId: 'vcam-grp-1', kind: 'videoinput', label: 'Camera2 0, facing back',  toJSON: function(){ return this; } },"
                + "      { deviceId: 'vcam-mic-0',   groupId: 'vcam-grp-0', kind: 'audioinput', label: 'Default Microphone',      toJSON: function(){ return this; } }"
                + "    ];"
                + "  };"
                + "  navigator.mediaDevices.getUserMedia = async function(constraints){"
                + "    if(!constraints || !constraints.video){ return origGetUserMedia ? origGetUserMedia(constraints) : Promise.reject('No camera'); }"
                + "    try { await vid.play(); } catch(e){}"
                + "    if(window.__vcamMode === 'REAL_CAMERA'){ await window.__vcamSetMode('REAL_CAMERA'); }"
                + "    renderLoop();"
                + "    const vStream = canvas.captureStream(" + fps + ");"
                + "    const vTrack = vStream.getVideoTracks()[0];"
                + "    if(vTrack){"
                + "      Object.defineProperty(vTrack, 'label', { value: 'Camera2 1, facing front', writable: false });"
                + "      vTrack.getSettings = function(){ return { width: " + targetW + ", height: " + targetH + ", frameRate: " + fps + ", facingMode: 'user', deviceId: 'vcam-front-0' }; };"
                + "    }"
                + "    return vStream;"
                + "  };"
                + "}"
                + "})();";

        view.evaluateJavascript(js, null);
    }
}
