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

        btnWhatsApp.setOnClickListener(v -> openWorkspaceUrl("https://web.whatsapp.com/"));\nbtnTelegram.setOnClickListener(v -> openWorkspaceUrl("https://web.telegram.org/k/"));\nbtnMeet.setOnClickListener(v -> openWorkspaceUrl("https://meet.google.com/"));\nbtnWebcamTest.setOnClickListener(v -> openWorkspaceUrl("https://webcamtests.com/"));\n\nBinderCameraInterceptor.install(this);\nPineArtHookEngine.install(this);\nrefreshTelemetry();\n}\n\nprivate void openWorkspaceUrl(String url) {\nprefs.setVirtualCamEnabled(true);\nIntent i = new Intent(this, VirtualWebBrowserActivity.class);\ni.setData(Uri.parse(url));\nstartActivity(i);\n}\n\nprivate boolean ensureReadyForTest() {\nif (!prefs.hasSelectedVideo()) {\nToast.makeText(this, "الرجاء اختيار فيديو من الشاشة الرئيسية أولاً!", Toast.LENGTH_LONG).show();\nreturn false;\n}\nif (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {\nActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAM);\nreturn false;\n}\nprefs.setVirtualCamEnabled(true);\nprefs.setSourceMode(VCamPrefs.SOURCE_VIDEO);\nBinderCameraInterceptor.install(this);\nPineArtHookEngine.install(this);\nreturn true;\n}\n\nprivate void refreshTelemetry() {\nStringBuilder sb = new StringBuilder();\nsb.append("• محرك Camera2 Binder Proxy:\n")\n.append(BinderCameraInterceptor.getStatusMessage()).append("\n");\nsb.append("• محرك Camera1/ART Hook (Pine):\n")\n.append(PineArtHookEngine.getHookStatus()).append("\n");\nsb.append(String.format(Locale.US,\n"• إحصائيات الاعتراض الحي: %d جلسات كاميرا | %d أسطح فيديو (Surfaces)\n",\nBinderCameraInterceptor.getInterceptedSessionsCount(),\nBinderCameraInterceptor.getInterceptedStreamsCount()));\nsb.append("• حالة الفيديو المختار: ")\n.append(prefs.hasSelectedVideo() ? ("جاهز (" + prefs.getVideoDisplayName() + ")") : "لم يتم اختيار فيديو بعد");\ntvLabTelemetry.setText(sb.toString());\n}\n\n/**\n* Calls the standard Android Camera2 API (`CameraManager.openCamera` -> `createCaptureSession`).\n* Our `BinderCameraInterceptor` automatically intercepts `ICameraDeviceUser.createStream` at the Binder\n* interface level (WITHOUT Xposed!) and replaces the hardware camera stream with the virtual video!\n*/\n@SuppressLint("MissingPermission")\nprivate void startCamera2BinderProxyTest() {\nstopActiveCameras();\nSurface targetSurface = svZeroXposedPreview.getHolder().getSurface();\nif (targetSurface == null || !targetSurface.isValid()) {\nToast.makeText(this, "السطح غير جاهز بعد، حاول مرة أخرى", Toast.LENGTH_SHORT).show();\nreturn;\n}\n\ntry {\nCameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);\nString[] ids = cm.getCameraIdList();\nif (ids.length == 0) return;\nString camId = ids[0];\n\ntvLabPreviewStatus.setText("⚡ جاري فتح Camera2 عبر Binder Proxy...");\ncm.openCamera(camId, new CameraDevice.StateCallback() {\n@Override\npublic void onOpened(@NonNull CameraDevice camera) {\nactiveCamera2Device = camera;\ntry {\nfinal CaptureRequest.Builder reqBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);\nreqBuilder.addTarget(targetSurface);\n\ncamera.createCaptureSession(\nCollections.singletonList(targetSurface),\nnew CameraCaptureSession.StateCallback() {\n@Override\npublic void onConfigured(@NonNull CameraCaptureSession session) {\nactiveCamera2Session = session;\ntry {\nsession.setRepeatingRequest(reqBuilder.build(), null, null);\n} catch (Exception ignored) {}\nrunOnUiThread(() -> {\ntvLabPreviewStatus.setText("✅ Camera2 Binder Proxy: يبث الفيديو الوهمي بنجاح!");\nrefreshTelemetry();\n});\n}\n\n@Override\npublic void onConfigureFailed(@NonNull CameraCaptureSession session) {\nrunOnUiThread(() -> tvLabPreviewStatus.setText("⚠️ فشل تهيئة جلسة Camera2"));\n}\n},\nnull\n);\n} catch (Exception e) {\nrunOnUiThread(() -> tvLabPreviewStatus.setText("خطأ Camera2: " + e.getMessage()));\n}\n}\n\n@Override\npublic void onDisconnected(@NonNull CameraDevice camera) {\ncamera.close();\n}\n\n@Override\npublic void onError(@NonNull CameraDevice camera, int error) {\ncamera.close();\n}\n}, null);\n} catch (Exception e) {\nToast.makeText(this, "خطأ Camera2: " + e.getMessage(), Toast.LENGTH_LONG).show();\n}\n}\n\n/**\n* Calls the standard Android Camera1 API (`Camera.open()` -> `setPreviewDisplay(SurfaceHolder)` -> `startPreview()`).\n* This is the EXACT sequence used by Open Camera!\n*/\n@SuppressWarnings("deprecation")\nprivate void startCamera1SurfaceHolderTest() {\nstopActiveCameras();\nSurfaceHolder holder = svZeroXposedPreview.getHolder();\nif (holder == null || holder.getSurface() == null || !holder.getSurface().isValid()) {\nToast.makeText(this, "السطح غير جاهز بعد", Toast.LENGTH_SHORT).show();\nreturn;\n}\n\ntry {\ntvLabPreviewStatus.setText("📷 جاري فتح Camera1 (وضع Open Camera SurfaceHolder)...");\nactiveCamera1 = Camera.open(0);\nactiveCamera1.setPreviewDisplay(holder);\nactiveCamera1.startPreview();\ntvLabPreviewStatus.setText("✅ Camera1 (SurfaceHolder): يبث الفيديو الوهمي بدون أخطاء!");\nrefreshTelemetry();\n} catch (Exception e) {\ntvLabPreviewStatus.setText("⚠️ خطأ Camera1: " + e.getMessage());\n}\n}\n\n/**\n* Tests `Camera.takePicture(...)` JPEG synthesis (the exact call that previously failed in Open Camera).\n*/\n@SuppressWarnings("deprecation")\nprivate void testCamera1TakePicture() {\nif (activeCamera1 == null) {\nstartCamera1SurfaceHolderTest();\n}\nif (activeCamera1 == null) return;\n\ntry {\nactiveCamera1.takePicture(\n() -> runOnUiThread(() -> Toast.makeText(this, "📸 صوت الغالق (Shutter)!", Toast.LENGTH_SHORT).show()),\nnull,\nnull,\n(data, camera) -> runOnUiThread(() -> {\nint kb = data != null ? (data.length / 1024) : 0;\ntvLabPreviewStatus.setText("✅ تم التقاط صورة JPEG من الفيديو الوهمي (" + kb + " KB)!");\nToast.makeText(this, "✅ نجح التقاط صورة JPEG من الفيديو (" + kb + " KB)!", Toast.LENGTH_LONG).show();\n})\n);\n} catch (Exception e) {\nToast.makeText(this, "خطأ التقاط الصورة: " + e.getMessage(), Toast.LENGTH_LONG).show();\n}\n}\n\n@SuppressWarnings("deprecation")\nprivate void stopActiveCameras() {\nif (activeCamera2Session != null) {\ntry { activeCamera2Session.close(); } catch (Exception ignored) {}\nactiveCamera2Session = null;\n}\nif (activeCamera2Device != null) {\ntry { activeCamera2Device.close(); } catch (Exception ignored) {}\nactiveCamera2Device = null;\n}\nif (activeCamera1 != null) {\ntry { activeCamera1.stopPreview(); } catch (Exception ignored) {}\ntry { activeCamera1.release(); } catch (Exception ignored) {}\nactiveCamera1 = null;\n}\n}\n\n@Override\nprotected void onPause() {\nsuper.onPause();\nstopActiveCameras();\n}\n}\n