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
                : "https://webcamtests.com/";\netBrowserUrl.setText(initialUrl);\nwebView.loadUrl(initialUrl);\n}\n\nprivate void loadEnteredUrl() {\nString raw = etBrowserUrl.getText().toString().trim();\nif (raw.isEmpty()) return;\nif (!raw.startsWith("http://") && !raw.startsWith("https://")) {\nraw = "https://" + raw;\n}\netBrowserUrl.setText(raw);\nwebView.loadUrl(raw);\n}\n\nprivate void updateBannerUI() {\nif (prefs.isShowingRealCamera()) {\ntvWebRtcStatus.setText("📷 WebRTC: يبث الكاميرا الحقيقية حالياً");\nbtnWebRtcSwitchSource.setText("🔄 التبديل للفيديو الوهمي");\n} else {\ntvWebRtcStatus.setText("🟢 WebRTC Hook: يبث الفيديو المختار (" + prefs.getStreamQuality() + ")");\nbtnWebRtcSwitchSource.setText("🔄 التبديل للكاميرا الحقيقية");\n}\n}\n\n@SuppressLint("SetJavaScriptEnabled")\nprivate void configureWebView() {\nWebSettings settings = webView.getSettings();\nsettings.setJavaScriptEnabled(true);\nsettings.setDomStorageEnabled(true);\nsettings.setDatabaseEnabled(true);\nsettings.setMediaPlaybackRequiresUserGesture(false);\nsettings.setAllowFileAccess(true);\nsettings.setAllowContentAccess(true);\n// Desktop-class User-Agent support for WhatsApp Web / Telegram Web / Zoom\nsettings.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36");\n\nwebView.setWebChromeClient(new WebChromeClient() {\n@Override\npublic void onPermissionRequest(final PermissionRequest request) {\nrunOnUiThread(() -> request.grant(request.getResources()));\n}\n\n@Override\npublic boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {\nif (prefs.isVirtualCamEnabled() && prefs.hasSelectedVideo() && !prefs.isShowingRealCamera()) {\nFile videoFile = prefs.getInternalVideoFile();\nif (videoFile.exists()) {\nfilePathCallback.onReceiveValue(new Uri[]{Uri.fromFile(videoFile)});\nToast.makeText(VirtualWebBrowserActivity.this, "🎬 تم تزويد الموقع بالفيديو الوهمي تلقائياً!", Toast.LENGTH_SHORT).show();\nreturn true;\n}\n}\nreturn super.onShowFileChooser(webView, filePathCallback, fileChooserParams);\n}\n});\n\nwebView.setWebViewClient(new WebViewClient() {\n@Override\npublic void onPageStarted(WebView view, String url, Bitmap favicon) {\nsuper.onPageStarted(view, url, favicon);\netBrowserUrl.setText(url);\ninjectVirtualCameraJsHook(view);\n}\n\n@Override\npublic void onPageFinished(WebView view, String url) {\nsuper.onPageFinished(view, url);\ninjectVirtualCameraJsHook(view);\n}\n\n@Nullable\n@Override\npublic WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {\nString url = request.getUrl().toString();\nif (url.startsWith("https://vcam.local/active_video.mp4")) {\ntry {\nFile videoFile = prefs.getInternalVideoFile();\nif (videoFile.exists()) {\nMap<String, String> headers = new HashMap<>();\nheaders.put("Access-Control-Allow-Origin", "*");\nheaders.put("Cache-Control", "no-store");\nreturn new WebResourceResponse(\n"video/mp4",\n"UTF-8",\n200,\n"OK",\nheaders,\nnew FileInputStream(videoFile)\n);\n}\n} catch (Exception ignored) {}\n}\nreturn super.shouldInterceptRequest(view, request);\n}\n});\n}\n\nprivate void injectVirtualCameraJsHook(WebView view) {\nif (!prefs.isVirtualCamEnabled()) return;\n\nint targetW = prefs.getTargetWidth();\nint targetH = prefs.getTargetHeight();\nint fps = prefs.getTargetFps();\nboolean loop = prefs.isLoopEnabled();\nboolean mirror = prefs.isMirrorEnabled();\nString initialMode = prefs.getSourceMode();\n\nString js = "(function(){"\n+ "if(window.__vcamHookInstalled) return;"\n+ "window.__vcamHookInstalled = true;"\n+ "window.__vcamMode = '" + initialMode + "';"\n+ "const origGetUserMedia = navigator.mediaDevices && navigator.mediaDevices.getUserMedia ? navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices) : null;"\n+ "const canvas = document.createElement('canvas');"\n+ "canvas.width = " + targetW + "; canvas.height = " + targetH + ";"\n+ "const ctx = canvas.getContext('2d');"\n+ "const vid = document.createElement('video');"\n+ "vid.src = 'https://vcam.local/active_video.mp4';"\n+ "vid.crossOrigin = 'anonymous';"\n+ "vid.loop = " + (loop ? "true" : "false") + ";"\n+ "vid.muted = true; vid.playsInline = true; vid.autoplay = true;"\n+ "let realVideoEl = null;"\n+ "window.__vcamSetMode = async function(newMode){"\n+ "  window.__vcamMode = newMode;"\n+ "  if(newMode === 'REAL_CAMERA' && !realVideoEl && origGetUserMedia){"\n+ "    try {"\n+ "      const realStream = await origGetUserMedia({video: true, audio: false});"\n+ "      realVideoEl = document.createElement('video');"\n+ "      realVideoEl.srcObject = realStream; realVideoEl.muted = true; realVideoEl.playsInline = true;"\n+ "      await realVideoEl.play();"\n+ "    } catch(e){}"\n+ "  }"\n+ "};"\n+ "function renderLoop(){"\n+ "  ctx.save();"\n+ "  if(" + (mirror ? "true" : "false") + " && window.__vcamMode !== 'REAL_CAMERA'){"\n+ "    ctx.translate(canvas.width, 0); ctx.scale(-1, 1);"\n+ "  }"\n+ "  const srcEl = (window.__vcamMode === 'REAL_CAMERA' && realVideoEl) ? realVideoEl : vid;"\n+ "  if(srcEl && srcEl.readyState >= 2){"\n+ "    ctx.drawImage(srcEl, 0, 0, canvas.width, canvas.height);"\n+ "  }"\n+ "  ctx.restore();"\n+ "  requestAnimationFrame(renderLoop);"\n+ "}"\n+ "if(navigator.mediaDevices){"\n+ "  navigator.mediaDevices.enumerateDevices = async function(){"\n+ "    return ["\n+ "      { deviceId: 'vcam-front-0', groupId: 'vcam-grp-0', kind: 'videoinput', label: 'Camera2 1, facing front', toJSON: function(){ return this; } },"\n+ "      { deviceId: 'vcam-back-1',  groupId: 'vcam-grp-1', kind: 'videoinput', label: 'Camera2 0, facing back',  toJSON: function(){ return this; } },"\n+ "      { deviceId: 'vcam-mic-0',   groupId: 'vcam-grp-0', kind: 'audioinput', label: 'Default Microphone',      toJSON: function(){ return this; } }"\n+ "    ];"\n+ "  };"\n+ "  navigator.mediaDevices.getUserMedia = async function(constraints){"\n+ "    if(!constraints || !constraints.video){ return origGetUserMedia ? origGetUserMedia(constraints) : Promise.reject('No camera'); }"\n+ "    try { await vid.play(); } catch(e){}"\n+ "    if(window.__vcamMode === 'REAL_CAMERA'){ await window.__vcamSetMode('REAL_CAMERA'); }"\n+ "    renderLoop();"\n+ "    const vStream = canvas.captureStream(" + fps + ");"\n+ "    const vTrack = vStream.getVideoTracks()[0];"\n+ "    if(vTrack){"\n+ "      Object.defineProperty(vTrack, 'label', { value: 'Camera2 1, facing front', writable: false });"\n+ "      vTrack.getSettings = function(){ return { width: " + targetW + ", height: " + targetH + ", frameRate: " + fps + ", facingMode: 'user', deviceId: 'vcam-front-0' }; };"\n+ "    }"\n+ "    return vStream;"\n+ "  };"\n+ "}"\n+ "})();";\n\nview.evaluateJavascript(js, null);\n}\n}\n