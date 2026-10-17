package com.takidz.vcam.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Manages Virtual Camera configuration & state:
 * - Virtual Camera Master Enable/Disable
 * - Active Stream Source ("VIDEO" vs "REAL_CAMERA")
 * - Loop Video (true/false)
 * - Mirror Horizontally (true/false)
 * - Mute Audio (true/false)
 * - Stream Quality ("480p", "720p", "1080p")
 */
public class VCamPrefs {
    public static final String PREFS_NAME = "vcam_pro_prefs_v1";
    public static final String SOURCE_VIDEO = "VIDEO";
    public static final String SOURCE_REAL_CAMERA = "REAL_CAMERA";

    private static final String KEY_ENABLED = "vcam_enabled";
    private static final String KEY_SOURCE_MODE = "vcam_source_mode";
    private static final String KEY_VIDEO_NAME = "vcam_video_name";
    private static final String KEY_VIDEO_DURATION_MS = "vcam_video_duration_ms";
    private static final String KEY_VIDEO_RESOLUTION = "vcam_video_resolution";
    private static final String KEY_LOOP = "vcam_loop_enabled";
    private static final String KEY_MIRROR = "vcam_mirror_enabled";
    private static final String KEY_MUTE_AUDIO = "vcam_mute_audio";
    private static final String KEY_QUALITY = "vcam_stream_quality";

    private final SharedPreferences prefs;
    private final Context appContext;

    public VCamPrefs(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public File getInternalVideoFile() {
        return new File(appContext.getFilesDir(), "vcam_active_video.mp4");
    }

    public boolean hasSelectedVideo() {
        File f = getInternalVideoFile();
        return f.exists() && f.length() > 0;
    }

    public boolean isVirtualCamEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public void setVirtualCamEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public String getSourceMode() {
        return prefs.getString(KEY_SOURCE_MODE, SOURCE_VIDEO);
    }

    public void setSourceMode(String mode) {
        prefs.edit().putString(KEY_SOURCE_MODE, mode).apply();
    }

    public boolean isShowingRealCamera() {
        return SOURCE_REAL_CAMERA.equals(getSourceMode());
    }

    public void toggleSourceMode() {
        if (isShowingRealCamera()) {
            setSourceMode(SOURCE_VIDEO);
        } else {
            setSourceMode(SOURCE_REAL_CAMERA);
        }
    }

    public String getVideoDisplayName() {
        return prefs.getString(KEY_VIDEO_NAME, "لا يوجد فيديو محدد");
    }

    public void setVideoMeta(String name, String resolution, long durationMs) {
        prefs.edit()
                .putString(KEY_VIDEO_NAME, name)
                .putString(KEY_VIDEO_RESOLUTION, resolution)
                .putLong(KEY_VIDEO_DURATION_MS, durationMs)
                .apply();
    }

    public String getVideoResolution() {
        return prefs.getString(KEY_VIDEO_RESOLUTION, "1280x720");
    }

    public long getVideoDurationMs() {
        return prefs.getLong(KEY_VIDEO_DURATION_MS, 0L);
    }

    public boolean isLoopEnabled() {
        return prefs.getBoolean(KEY_LOOP, true);
    }

    public void setLoopEnabled(boolean loop) {
        prefs.edit().putBoolean(KEY_LOOP, loop).apply();
    }

    public boolean isMirrorEnabled() {
        return prefs.getBoolean(KEY_MIRROR, false);
    }

    public void setMirrorEnabled(boolean mirror) {
        prefs.edit().putBoolean(KEY_MIRROR, mirror).apply();
    }

    public boolean isMuteAudioEnabled() {
        return prefs.getBoolean(KEY_MUTE_AUDIO, false);
    }

    public void setMuteAudioEnabled(boolean mute) {
        prefs.edit().putBoolean(KEY_MUTE_AUDIO, mute).apply();
    }

    public String getStreamQuality() {
        return prefs.getString(KEY_QUALITY, "720p");
    }

    public void setStreamQuality(String quality) {
        prefs.edit().putString(KEY_QUALITY, quality).apply();
    }

    public int getTargetWidth() {
        String q = getStreamQuality();
        if ("1080p".equals(q)) return 1920;
        if ("480p".equals(q)) return 854;
        return 1280;
    }

    public int getTargetHeight() {
        String q = getStreamQuality();
        if ("1080p".equals(q)) return 1080;
        if ("480p".equals(q)) return 480;
        return 720;
    }

    public int getTargetFps() {
        String q = getStreamQuality();
        if ("1080p".equals(q)) return 60;
        return 30;
    }
}
