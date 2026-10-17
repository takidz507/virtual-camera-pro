package com.takidz.vcam.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.takidz.vcam.R;
import com.takidz.vcam.engine.RealCamera2Controller;
import com.takidz.vcam.engine.SurfaceVideoPlayer;
import com.takidz.vcam.service.FloatingControlService;
import com.takidz.vcam.utils.VCamPrefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * Main Control Dashboard for Virtual Camera Pro:
 * - Pick any video from device storage and copy it into the fast local virtual camera buffer.
 * - Enable / Disable Virtual Camera engine.
 * - Instant 1-tap switching between Virtual Video and Real Hardware Camera (Camera2 API).
 * - Configure Loop, Mirror, Audio Mute, and Broadcast Quality (480p / 720p / 1080p).
 * - Launch Built-in WebRTC Virtual Camera Browser, Floating Overlay Controller, or System Camera Intent Tester.
 */
public class MainActivity extends AppCompatActivity {
    private static final int REQ_CAMERA_PERM = 101;

    private VCamPrefs prefs;
    private SurfaceVideoPlayer videoPlayer;
    private RealCamera2Controller realCameraController;
    private Surface activePreviewSurface;

    private TextView tvActiveStatusBadge;
    private TextureView texturePreview;
    private TextView tvPreviewPlaceholder;
    private TextView tvCurrentSourceTag;
    private TextView tvSelectedVideoInfo;

    private MaterialButton btnPickVideo;
    private MaterialButton btnToggleVirtualCam;
    private MaterialButton btnQuickSwitchSource;

    private SwitchMaterial swLoopVideo;
    private SwitchMaterial swMirrorVideo;
    private SwitchMaterial swMuteAudio;
    private Spinner spStreamQuality;

    private MaterialButton btnOpenZeroXposedLab;
    private MaterialButton btnOpenWebRtcBrowser;
    private MaterialButton btnToggleFloatingOverlay;
    private MaterialButton btnTestCameraIntent;

    private ActivityResultLauncher<String> videoPickerLauncher;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshUIAndPreview();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new VCamPrefs(this);
        videoPlayer = new SurfaceVideoPlayer();
        realCameraController = new RealCamera2Controller();

        setupVideoPicker();
        initViews();
        refreshUIAndPreview();
    }

    private void setupVideoPicker() {
        videoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        importSelectedVideo(uri);
                    }
                }
        );
    }

    private void initViews() {
        tvActiveStatusBadge = findViewById(R.id.tvActiveStatusBadge);
        texturePreview = findViewById(R.id.texturePreview);
        tvPreviewPlaceholder = findViewById(R.id.tvPreviewPlaceholder);
        tvCurrentSourceTag = findViewById(R.id.tvCurrentSourceTag);
        tvSelectedVideoInfo = findViewById(R.id.tvSelectedVideoInfo);

        btnPickVideo = findViewById(R.id.btnPickVideo);
        btnToggleVirtualCam = findViewById(R.id.btnToggleVirtualCam);
        btnQuickSwitchSource = findViewById(R.id.btnQuickSwitchSource);

        swLoopVideo = findViewById(R.id.swLoopVideo);
        swMirrorVideo = findViewById(R.id.swMirrorVideo);
        swMuteAudio = findViewById(R.id.swMuteAudio);
        spStreamQuality = findViewById(R.id.spStreamQuality);

        btnOpenZeroXposedLab = findViewById(R.id.btnOpenZeroXposedLab);
        btnOpenWebRtcBrowser = findViewById(R.id.btnOpenWebRtcBrowser);
        btnToggleFloatingOverlay = findViewById(R.id.btnToggleFloatingOverlay);
        btnTestCameraIntent = findViewById(R.id.btnTestCameraIntent);

        swLoopVideo.setChecked(prefs.isLoopEnabled());
        swMirrorVideo.setChecked(prefs.isMirrorEnabled());
        swMuteAudio.setChecked(prefs.isMuteAudioEnabled());

        String[] qualityLabels = {
                "480p SD — 854×480 (30 FPS - خفيف وسريع)",
                "720p HD — 1280×720 (30 FPS - مستحسن للاتصالات)",
                "1080p Full HD — 1920×1080 (60 FPS - دقة فائقة)"
        };
        final String[] qualityValues = {"480p", "720p", "1080p"};
        ArrayAdapter<String> qAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, qualityLabels);
        spStreamQuality.setAdapter(qAdapter);
        String savedQ = prefs.getStreamQuality();
        for (int i = 0; i < qualityValues.length; i++) {
            if (qualityValues[i].equals(savedQ)) {
                spStreamQuality.setSelection(i);
            }
        }

        spStreamQuality.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.setStreamQuality(qualityValues[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        swLoopVideo.setOnCheckedChangeListener((b, checked) -> {
            prefs.setLoopEnabled(checked);
            videoPlayer.updateLiveSettings(prefs, texturePreview);
        });

        swMirrorVideo.setOnCheckedChangeListener((b, checked) -> {
            prefs.setMirrorEnabled(checked);
            videoPlayer.updateLiveSettings(prefs, texturePreview);
        });

        swMuteAudio.setOnCheckedChangeListener((b, checked) -> {
            prefs.setMuteAudioEnabled(checked);
            videoPlayer.updateLiveSettings(prefs, texturePreview);
        });

        texturePreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                activePreviewSurface = new Surface(surface);
                startActiveStreamOnPreview();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                stopAllPreviewStreams();
                if (activePreviewSurface != null) {
                    activePreviewSurface.release();
                    activePreviewSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
        });

        btnPickVideo.setOnClickListener(v -> videoPickerLauncher.launch("video/*"));

        btnToggleVirtualCam.setOnClickListener(v -> {
            if (!prefs.isVirtualCamEnabled() && !prefs.hasSelectedVideo() && !prefs.isShowingRealCamera()) {
                Toast.makeText(this, "الرجاء اختيار فيديو من هاتفك أولاً!", Toast.LENGTH_LONG).show();
                videoPickerLauncher.launch("video/*");
                return;
            }
            boolean nextState = !prefs.isVirtualCamEnabled();
            prefs.setVirtualCamEnabled(nextState);
            refreshUIAndPreview();
            Toast.makeText(this, nextState ? "✅ تم تفعيل الكاميرا الوهمية!" : "⏹ تم إيقاف الكاميرا الوهمية", Toast.LENGTH_SHORT).show();
        });

        btnQuickSwitchSource.setOnClickListener(v -> {
            if (!prefs.isShowingRealCamera()) {
                // Switching to Real Camera2: check CAMERA permission
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERM);
                    return;
                }
            }
            prefs.toggleSourceMode();
            refreshUIAndPreview();
        });

        btnOpenZeroXposedLab.setOnClickListener(v -> {
            startActivity(new Intent(this, ZeroXposedLabActivity.class));
        });

        btnOpenWebRtcBrowser.setOnClickListener(v -> {
            if (!prefs.hasSelectedVideo() && !prefs.isShowingRealCamera()) {
                Toast.makeText(this, "اختر فيديو أولاً ليتم بثه داخل مواقع الويب!", Toast.LENGTH_SHORT).show();
                videoPickerLauncher.launch("video/*");
                return;
            }
            prefs.setVirtualCamEnabled(true);
            startActivity(new Intent(this, VirtualWebBrowserActivity.class));
        });

        btnToggleFloatingOverlay.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "يرجى منح إذن الظهور فوق التطبيقات الأخرى (Overlay)", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return;
            }
            Intent serviceIntent = new Intent(this, FloatingControlService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Toast.makeText(this, "🪟 تم إظهار أداة التبديل السريع العائمة!", Toast.LENGTH_SHORT).show();
        });

        btnTestCameraIntent.setOnClickListener(v -> {
            Intent testIntent = new Intent(this, VirtualCameraCaptureActivity.class);
            testIntent.setAction(MediaStore.ACTION_VIDEO_CAPTURE);
            startActivity(testIntent);
        });
    }

    private void importSelectedVideo(Uri uri) {
        try {
            ContentResolver resolver = getContentResolver();
            String fileName = queryDisplayName(resolver, uri);
            File destFile = prefs.getInternalVideoFile();

            try (InputStream in = resolver.openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(destFile)) {
                if (in == null) throw new Exception("تعذر فتح ملف الفيديو");
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }

            String resolution = "1280x720";
            long durationMs = 0L;
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(destFile.getAbsolutePath());
                String w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (w != null && h != null) resolution = w + "×" + h;
                if (d != null) durationMs = Long.parseLong(d);
            } catch (Exception ignored) {
            } finally {
                try { mmr.release(); } catch (Exception ignored) {}
            }

            prefs.setVideoMeta(fileName, resolution, durationMs);
            prefs.setSourceMode(VCamPrefs.SOURCE_VIDEO);
            prefs.setVirtualCamEnabled(true);
            refreshUIAndPreview();
            Toast.makeText(this, "✅ تم تجهيز الفيديو وتفعيل الكاميرا الوهمية!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "فشل استيراد الفيديو: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String queryDisplayName(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {}
        return "video_" + System.currentTimeMillis() + ".mp4";
    }

    private void refreshUIAndPreview() {
        boolean enabled = prefs.isVirtualCamEnabled();
        boolean isRealCam = prefs.isShowingRealCamera();

        if (enabled) {
            tvActiveStatusBadge.setText("● نشط (" + prefs.getStreamQuality() + ")");
            tvActiveStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.vcam_accent));
            btnToggleVirtualCam.setText("⏹ إيقاف الكاميرا الوهمية");
            btnToggleVirtualCam.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.vcam_danger)));
            btnToggleVirtualCam.setTextColor(0xFFFFFFFF);
        } else {
            tvActiveStatusBadge.setText("● متوقف");
            tvActiveStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.vcam_muted));
            btnToggleVirtualCam.setText("▶ تفعيل الكاميرا الوهمية");
            btnToggleVirtualCam.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.vcam_accent)));
            btnToggleVirtualCam.setTextColor(ContextCompat.getColor(this, R.color.vcam_accent_ink));
        }

        if (isRealCam) {
            tvCurrentSourceTag.setText("📷 المصدر الحي: الكاميرا الحقيقية (Camera2)");
            btnQuickSwitchSource.setText("🔄 التبديل الفوري إلى: الفيديو المختار 🎬");
        } else {
            tvCurrentSourceTag.setText("🎬 المصدر الحي: الفيديو المختار");
            btnQuickSwitchSource.setText("🔄 التبديل الفوري إلى: الكاميرا الحقيقية (Camera2) 📷");
        }

        if (prefs.hasSelectedVideo()) {
            long sec = Math.max(1, prefs.getVideoDurationMs() / 1000L);
            tvSelectedVideoInfo.setText(String.format(Locale.US,
                    "📹 الفيديو: %s (%s • %d ثانية)",
                    prefs.getVideoDisplayName(), prefs.getVideoResolution(), sec));
        } else {
            tvSelectedVideoInfo.setText("الملف الحالي: لم يتم اختيار فيديو بعد");
        }

        startActiveStreamOnPreview();
    }

    private void startActiveStreamOnPreview() {
        stopAllPreviewStreams();
        if (activePreviewSurface == null || !activePreviewSurface.isValid()) return;

        if (prefs.isShowingRealCamera()) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                tvPreviewPlaceholder.setVisibility(View.GONE);
                texturePreview.setScaleX(1f);
                realCameraController.startRealCamera(this, activePreviewSurface);
            } else {
                tvPreviewPlaceholder.setVisibility(View.VISIBLE);
                tvPreviewPlaceholder.setText("يرجى منح إذن الكاميرا لعرض الكاميرا الحقيقية");
            }
        } else {
            if (prefs.hasSelectedVideo()) {
                tvPreviewPlaceholder.setVisibility(View.GONE);
                texturePreview.setScaleX(prefs.isMirrorEnabled() ? -1f : 1f);
                videoPlayer.startPlayback(this, activePreviewSurface, prefs);
            } else {
                tvPreviewPlaceholder.setVisibility(View.VISIBLE);
                tvPreviewPlaceholder.setText("لم يتم اختيار فيديو بعد
اضغط على (اختيار فيديو) بالأسفل");
            }
        }
    }

    private void stopAllPreviewStreams() {
        videoPlayer.stopPlayback();
        realCameraController.stopRealCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(FloatingControlService.ACTION_BROADCAST_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        refreshUIAndPreview();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
        stopAllPreviewStreams();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_PERM && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            prefs.setSourceMode(VCamPrefs.SOURCE_REAL_CAMERA);
            refreshUIAndPreview();
        }
    }
}
