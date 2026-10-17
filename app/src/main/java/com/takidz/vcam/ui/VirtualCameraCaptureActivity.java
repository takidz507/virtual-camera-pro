package com.takidz.vcam.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Surface;
import android.view.TextureView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.takidz.vcam.R;
import com.takidz.vcam.engine.RealCamera2Controller;
import com.takidz.vcam.engine.SurfaceVideoPlayer;
import com.takidz.vcam.provider.VirtualVideoProvider;
import com.takidz.vcam.utils.VCamPrefs;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

/**
 * System-Wide Camera Intent Responder (`ACTION_IMAGE_CAPTURE` & `ACTION_VIDEO_CAPTURE`).
 * Works 100% without Root when any third-party app or browser requests a camera photo/video capture intent.
 * Displays the live virtual video (or real Camera2 feed if switched) and returns the selected video or frame
 * directly to the calling app's `MediaStore.EXTRA_OUTPUT` Uri!
 */
public class VirtualCameraCaptureActivity extends AppCompatActivity {

    private VCamPrefs prefs;
    private SurfaceVideoPlayer videoPlayer;
    private RealCamera2Controller realCameraController;
    private Surface viewfinderSurface;

    private TextureView captureTextureView;
    private TextView tvCaptureModeBanner;
    private MaterialButton btnCaptureSwitchSource;
    private MaterialButton btnConfirmShutter;
    private MaterialButton btnCancelCapture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_virtual_capture);

        prefs = new VCamPrefs(this);
        videoPlayer = new SurfaceVideoPlayer();
        realCameraController = new RealCamera2Controller();

        captureTextureView = findViewById(R.id.captureTextureView);
        tvCaptureModeBanner = findViewById(R.id.tvCaptureModeBanner);
        btnCaptureSwitchSource = findViewById(R.id.btnCaptureSwitchSource);
        btnConfirmShutter = findViewById(R.id.btnConfirmShutter);
        btnCancelCapture = findViewById(R.id.btnCancelCapture);

        captureTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                viewfinderSurface = new Surface(surface);
                startViewfinderStream();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                stopViewfinderStream();
                if (viewfinderSurface != null) {
                    viewfinderSurface.release();
                    viewfinderSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
        });

        btnCaptureSwitchSource.setOnClickListener(v -> {
            prefs.toggleSourceMode();
            startViewfinderStream();
        });

        btnCancelCapture.setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        btnConfirmShutter.setOnClickListener(v -> deliverCapturedMediaToCaller());
    }

    private void startViewfinderStream() {
        stopViewfinderStream();
        if (viewfinderSurface == null || !viewfinderSurface.isValid()) return;

        if (prefs.isShowingRealCamera()) {
            tvCaptureModeBanner.setText("📷 البث الحي: الكاميرا الحقيقية (Camera2)");
            btnCaptureSwitchSource.setText("🔄 التبديل للفيديو الوهمي");
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                realCameraController.startRealCamera(this, viewfinderSurface);
            }
        } else {
            tvCaptureModeBanner.setText("🎬 البث الحي: الفيديو الوهمي (" + prefs.getStreamQuality() + ")");
            btnCaptureSwitchSource.setText("🔄 التبديل للكاميرا الحقيقية");
            if (prefs.hasSelectedVideo()) {
                captureTextureView.setScaleX(prefs.isMirrorEnabled() ? -1f : 1f);
                videoPlayer.startPlayback(this, viewfinderSurface, prefs);
            } else {
                Toast.makeText(this, "لم يتم تحديد فيديو في التطبيق الرئيسي بعد!", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void stopViewfinderStream() {
        videoPlayer.stopPlayback();
        realCameraController.stopRealCamera();
    }

    private void deliverCapturedMediaToCaller() {
        Intent incoming = getIntent();
        String action = incoming != null ? incoming.getAction() : "";
        Uri extraOutput = incoming != null ? incoming.getParcelableExtra(MediaStore.EXTRA_OUTPUT) : null;

        try {
            if (MediaStore.ACTION_VIDEO_CAPTURE.equals(action)) {
                File videoFile = prefs.getInternalVideoFile();
                if (!videoFile.exists()) {
                    Toast.makeText(this, "لا يوجد ملف فيديو مختار للإرسال!", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (extraOutput != null) {
                    try (FileInputStream in = new FileInputStream(videoFile);
                         OutputStream out = getContentResolver().openOutputStream(extraOutput)) {
                        if (out != null) {
                            byte[] buf = new byte[16384];
                            int len;
                            while ((len = in.read(buf)) != -1) {
                                out.write(buf, 0, len);
                            }
                        }
                    }
                }
                Intent result = new Intent();
                result.setData(extraOutput != null ? extraOutput : VirtualVideoProvider.URI_VIDEO);
                result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                setResult(Activity.RESULT_OK, result);
                finish();
            } else {
                // Image Capture: grab current frame from TextureView or video retriever at target quality
                Bitmap frame = captureTextureView.getBitmap(prefs.getTargetWidth(), prefs.getTargetHeight());
                if (frame == null && prefs.hasSelectedVideo()) {
                    frame = SurfaceVideoPlayer.extractScaledFrame(
                            prefs.getInternalVideoFile(),
                            videoPlayer.getCurrentPositionMs(),
                            prefs
                    );
                }
                if (frame == null) {
                    Toast.makeText(this, "تعذر التقاط الإطار الحالي!", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (extraOutput != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(extraOutput)) {
                        if (out != null) {
                            frame.compress(Bitmap.CompressFormat.JPEG, 95, out);
                        }
                    }
                }
                Intent result = new Intent();
                Bitmap thumb = Bitmap.createScaledBitmap(frame, 240, 180, true);
                result.putExtra("data", thumb);
                setResult(Activity.RESULT_OK, result);
                finish();
            }
        } catch (Exception e) {
            Toast.makeText(this, "خطأ أثناء تسليم الكاميرا: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopViewfinderStream();
    }
}
