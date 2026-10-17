package com.takidz.vcam.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.net.Uri;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.takidz.vcam.R;
import com.takidz.vcam.engine.BinderCameraInterceptor;
import com.takidz.vcam.engine.PineArtHookEngine;
import com.takidz.vcam.utils.VCamPrefs;

import java.util.Collections;
import java.util.Locale;

/**
 * Interactive Zero-Xposed & Zero-Root Virtual Camera Lab:
 * - Demonstrates and verifies `BinderCameraInterceptor` (ICameraService / ICameraDeviceUser Dynamic Proxy)
 *   and `PineArtHookEngine` (In-Process ART Method Hook) running WITHOUT any Xposed module or Root!
 * - Tests both `Camera2 API` (`CameraManager.openCamera`) and `Camera1 API` (`Camera.setPreviewDisplay` + `takePicture`)
 *   live on a real `SurfaceView`.
 */
public class ZeroXposedLabActivity extends AppCompatActivity {
    private static final int REQ_CAM = 202;

    private VCamPrefs prefs;
    private TextView tvLabTelemetry;
    private TextView tvLabPreviewStatus;
    private SurfaceView svZeroXposedPreview;

    private Camera activeCamera1;
    private CameraDevice activeCamera2Device;
    private CameraCaptureSession activeCamera2Session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_zero_xposed_lab);

        prefs = new VCamPrefs(this);

        ImageButton btnBack = findViewById(R.id.btnLabBack);
        tvLabTelemetry = findViewById(R.id.tvLabTelemetry);
        tvLabPreviewStatus = findViewById(R.id.tvLabPreviewStatus);
        svZeroXposedPreview = findViewById(R.id.svZeroXposedPreview);

        MaterialButton btnActivate = findViewById(R.id.btnActivateZeroXposedEngine);
        MaterialButton btnTestCam2 = findViewById(R.id.btnTestCamera2Binder);
        MaterialButton btnTestCam1 = findViewById(R.id.btnTestCamera1SurfaceHolder);
        MaterialButton btnTestPhoto = findViewById(R.id.btnTestTakePictureJpeg);

        MaterialButton btnWhatsApp = findViewById(R.id.btnLaunchWhatsAppWeb);
        MaterialButton btnTelegram = findViewById(R.id.btnLaunchTelegramWeb);
        MaterialButton btnMeet = findViewById(R.id.btnLaunchZoomMeet);
        MaterialButton btnWebcamTest = findViewById(R.id.btnLaunchWebcamTest);

        btnBack.setOnClickListener(v -> finish());

        btnActivate.setOnClickListener(v -> {
            BinderCameraInterceptor.install(this);
            PineArtHookEngine.install(this);
            refreshTelemetry();
            Toast.makeText(this, "⚡ تم تفعيل محرك Binder Proxy + Pine ART بدون Xposed!", Toast.LENGTH_SHORT).show();
        });

        btnTestCam2.setOnClickListener(v -> {
            if (!ensureReadyForTest()) return;
            startCamera2BinderProxyTest();
        });

        btnTestCam1.setOnClickListener(v -> {
            if (!ensureReadyForTest()) return;
            startCamera1SurfaceHolderTest();
        });

        btnTestPhoto.setOnClickListener(v -> {
            if (!ensureReadyForTest()) return;
            testCamera1TakePicture();
        });

        btnWhatsApp.setOnClickListener(v -> openWorkspaceUrl("https://web.whatsapp.com/"));
        btnTelegram.setOnClickListener(v -> openWorkspaceUrl("https://web.telegram.org/k/"));
        btnMeet.setOnClickListener(v -> openWorkspaceUrl("https://meet.google.com/"));
        btnWebcamTest.setOnClickListener(v -> openWorkspaceUrl("https://webcamtests.com/"));

        BinderCameraInterceptor.install(this);
        PineArtHookEngine.install(this);
        refreshTelemetry();
    }

    private void openWorkspaceUrl(String url) {
        prefs.setVirtualCamEnabled(true);
        Intent i = new Intent(this, VirtualWebBrowserActivity.class);
        i.setData(Uri.parse(url));
        startActivity(i);
    }

    private boolean ensureReadyForTest() {
        if (!prefs.hasSelectedVideo()) {
            Toast.makeText(this, "الرجاء اختيار فيديو من الشاشة الرئيسية أولاً!", Toast.LENGTH_LONG).show();
            return false;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAM);
            return false;
        }
        prefs.setVirtualCamEnabled(true);
        prefs.setSourceMode(VCamPrefs.SOURCE_VIDEO);
        BinderCameraInterceptor.install(this);
        PineArtHookEngine.install(this);
        return true;
    }

    private void refreshTelemetry() {
        StringBuilder sb = new StringBuilder();
        sb.append("• محرك Camera2 Binder Proxy:
   ")
                .append(BinderCameraInterceptor.getStatusMessage()).append("
");
        sb.append("• محرك Camera1/ART Hook (Pine):
   ")
                .append(PineArtHookEngine.getHookStatus()).append("
");
        sb.append(String.format(Locale.US,
                "• إحصائيات الاعتراض الحي: %d جلسات كاميرا | %d أسطح فيديو (Surfaces)
",
                BinderCameraInterceptor.getInterceptedSessionsCount(),
                BinderCameraInterceptor.getInterceptedStreamsCount()));
        sb.append("• حالة الفيديو المختار: ")
                .append(prefs.hasSelectedVideo() ? ("جاهز (" + prefs.getVideoDisplayName() + ")") : "لم يتم اختيار فيديو بعد");
        tvLabTelemetry.setText(sb.toString());
    }

    /**
     * Calls the standard Android Camera2 API (`CameraManager.openCamera` -> `createCaptureSession`).
     * Our `BinderCameraInterceptor` automatically intercepts `ICameraDeviceUser.createStream` at the Binder
     * interface level (WITHOUT Xposed!) and replaces the hardware camera stream with the virtual video!
     */
    @SuppressLint("MissingPermission")
    private void startCamera2BinderProxyTest() {
        stopActiveCameras();
        Surface targetSurface = svZeroXposedPreview.getHolder().getSurface();
        if (targetSurface == null || !targetSurface.isValid()) {
            Toast.makeText(this, "السطح غير جاهز بعد، حاول مرة أخرى", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            String[] ids = cm.getCameraIdList();
            if (ids.length == 0) return;
            String camId = ids[0];

            tvLabPreviewStatus.setText("⚡ جاري فتح Camera2 عبر Binder Proxy...");
            cm.openCamera(camId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    activeCamera2Device = camera;
                    try {
                        final CaptureRequest.Builder reqBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        reqBuilder.addTarget(targetSurface);

                        camera.createCaptureSession(
                                Collections.singletonList(targetSurface),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(@NonNull CameraCaptureSession session) {
                                        activeCamera2Session = session;
                                        try {
                                            session.setRepeatingRequest(reqBuilder.build(), null, null);
                                        } catch (Exception ignored) {}
                                        runOnUiThread(() -> {
                                            tvLabPreviewStatus.setText("✅ Camera2 Binder Proxy: يبث الفيديو الوهمي بنجاح!");
                                            refreshTelemetry();
                                        });
                                    }

                                    @Override
                                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                        runOnUiThread(() -> tvLabPreviewStatus.setText("⚠️ فشل تهيئة جلسة Camera2"));
                                    }
                                },
                                null
                        );
                    } catch (Exception e) {
                        runOnUiThread(() -> tvLabPreviewStatus.setText("خطأ Camera2: " + e.getMessage()));
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                }
            }, null);
        } catch (Exception e) {
            Toast.makeText(this, "خطأ Camera2: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Calls the standard Android Camera1 API (`Camera.open()` -> `setPreviewDisplay(SurfaceHolder)` -> `startPreview()`).
     * This is the EXACT sequence used by Open Camera!
     */
    @SuppressWarnings("deprecation")
    private void startCamera1SurfaceHolderTest() {
        stopActiveCameras();
        SurfaceHolder holder = svZeroXposedPreview.getHolder();
        if (holder == null || holder.getSurface() == null || !holder.getSurface().isValid()) {
            Toast.makeText(this, "السطح غير جاهز بعد", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            tvLabPreviewStatus.setText("📷 جاري فتح Camera1 (وضع Open Camera SurfaceHolder)...");
            activeCamera1 = Camera.open(0);
            activeCamera1.setPreviewDisplay(holder);
            activeCamera1.startPreview();
            tvLabPreviewStatus.setText("✅ Camera1 (SurfaceHolder): يبث الفيديو الوهمي بدون أخطاء!");
            refreshTelemetry();
        } catch (Exception e) {
            tvLabPreviewStatus.setText("⚠️ خطأ Camera1: " + e.getMessage());
        }
    }

    /**
     * Tests `Camera.takePicture(...)` JPEG synthesis (the exact call that previously failed in Open Camera).
     */
    @SuppressWarnings("deprecation")
    private void testCamera1TakePicture() {
        if (activeCamera1 == null) {
            startCamera1SurfaceHolderTest();
        }
        if (activeCamera1 == null) return;

        try {
            activeCamera1.takePicture(
                    () -> runOnUiThread(() -> Toast.makeText(this, "📸 صوت الغالق (Shutter)!", Toast.LENGTH_SHORT).show()),
                    null,
                    null,
                    (data, camera) -> runOnUiThread(() -> {
                        int kb = data != null ? (data.length / 1024) : 0;
                        tvLabPreviewStatus.setText("✅ تم التقاط صورة JPEG من الفيديو الوهمي (" + kb + " KB)!");
                        Toast.makeText(this, "✅ نجح التقاط صورة JPEG من الفيديو (" + kb + " KB)!", Toast.LENGTH_LONG).show();
                    })
            );
        } catch (Exception e) {
            Toast.makeText(this, "خطأ التقاط الصورة: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void stopActiveCameras() {
        if (activeCamera2Session != null) {
            try { activeCamera2Session.close(); } catch (Exception ignored) {}
            activeCamera2Session = null;
        }
        if (activeCamera2Device != null) {
            try { activeCamera2Device.close(); } catch (Exception ignored) {}
            activeCamera2Device = null;
        }
        if (activeCamera1 != null) {
            try { activeCamera1.stopPreview(); } catch (Exception ignored) {}
            try { activeCamera1.release(); } catch (Exception ignored) {}
            activeCamera1 = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopActiveCameras();
    }
}
