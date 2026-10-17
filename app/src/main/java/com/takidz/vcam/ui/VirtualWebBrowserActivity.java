package com.takidz.vcam.ui;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.webkit.PermissionRequest;
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
 * Built-in WebRTC Virtual Camera Browser (Works 100% Without Root on ANY Website!).
 * - Intercepts `navigator.mediaDevices.getUserMedia` and `navigator.mediaDevices.enumerateDevices`.
 * - Streams the user's selected local video (`https://vcam.local/active_video.mp4`) through an offscreen
 *   HTML5 Canvas (`captureStream(fps)`) at the selected broadcast resolution (480p / 720p / 1080p).
 * - Supports live 1-tap switching between the Virtual Video and the Real Camera without reloading the page!
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
            // Dynamically tell the injected JS hook to switch between Virtual Video and Real Camera
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
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
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

    /**
     * Injects a transparent WebRTC `navigator.mediaDevices.getUserMedia` hook into the webpage
     * so any website sees our local video as a genuine hardware camera stream!
     */
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
                + "  navigator.mediaDevices.getUserMedia = async function(constraints){"
                + "    if(!constraints || !constraints.video){ return origGetUserMedia ? origGetUserMedia(constraints) : Promise.reject('No camera'); }"
                + "    try { await vid.play(); } catch(e){}"
                + "    if(window.__vcamMode === 'REAL_CAMERA'){ await window.__vcamSetMode('REAL_CAMERA'); }"
                + "    renderLoop();"
                + "    const vStream = canvas.captureStream(" + fps + ");"
                + "    const vTrack = vStream.getVideoTracks()[0];"
                + "    if(vTrack){ Object.defineProperty(vTrack, 'label', { value: 'Camera2 0, facing front (VirtualCameraPro)', writable: false }); }"
                + "    return vStream;"
                + "  };"
                + "}"
                + "})();";

        view.evaluateJavascript(js, null);
    }
}
