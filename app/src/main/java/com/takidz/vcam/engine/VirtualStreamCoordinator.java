package com.takidz.vcam.engine;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.takidz.vcam.provider.VirtualVideoProvider;
import com.takidz.vcam.utils.VCamPrefs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Central Virtual Stream Engine shared by:
 * 1. BinderCameraInterceptor (Zero-Xposed ICameraService / ICameraDeviceUser Dynamic Proxy)
 * 2. PineArtHookEngine (Zero-Xposed Standalone ART Hook)
 * 3. VirtualCameraXposedHook (LSPatch Compatibility Bridge)
 *
 * Solves all 4 issues observed in Open Camera & Camera2 apps:
 * - Persistent Dummy SurfaceTexture & Surface pool (never GC'd while previewing).
 * - Exclusive Surface producer isolation (real camera writes to dummy surface; MediaPlayer writes to app surface).
 * - Still Photo JPEG Synthesis (`extractCurrentJpegBytes`) so `Camera.takePicture()` and `ImageReader` never fail!
 * - Real-time NV21 (`YUV420sp`) byte buffer synthesis for `PreviewCallback.onPreviewFrame`.
 * - Live 350ms hot-reload watcher so tapping the Floating Widget switches modes without restarting the app.
 */
public class VirtualStreamCoordinator {
    private static final String TAG = "VCamCoordinator";

    // Strong static references so GC never destroys the dummy sinks used by the hardware camera
    private static SurfaceTexture sDummyCamera1Texture;
    private static SurfaceTexture sDummyCamera2Texture;
    private static Surface sDummyCamera2Surface;

    // Map of active target Surfaces -> MediaPlayers
    private static final Map<Surface, MediaPlayer> sActivePlayers = new ConcurrentHashMap<>();
    private static volatile Surface sPrimaryTargetSurface;
    private static volatile MediaPlayer sPrimaryPlayer;
    private static volatile File sCachedLocalVideoFile;
    private static volatile Bitmap sLastExtractedBitmap;
    private static volatile long sLastFrameExtractTimeMs = 0L;

    private static ScheduledExecutorService sConfigPoller;
    private static volatile String sLastSourceMode = "";
    private static volatile boolean sLastMuteState = false;
    private static volatile boolean sLastLoopState = true;

    public static class RemoteConfig {
        public boolean enabled = false;
        public String sourceMode = VCamPrefs.SOURCE_VIDEO;
        public boolean loop = true;
        public boolean mirror = false;
        public boolean muteAudio = false;
        public String quality = "720p";
        public int width = 1280;
        public int height = 720;
        public int fps = 30;
        public boolean hasVideo = false;

        public boolean isVirtualVideoActive() {
            return enabled && hasVideo && VCamPrefs.SOURCE_VIDEO.equals(sourceMode);
        }
    }

    public static Context resolveContext() {
        try {
            return AndroidAppHelper.currentApplication();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static RemoteConfig queryRemoteConfig(Context context) {
        RemoteConfig cfg = new RemoteConfig();
        if (context == null) context = resolveContext();
        if (context == null) return cfg;

        // If running inside our own app process, read directly from VCamPrefs first
        if ("com.takidz.vcam".equals(context.getPackageName())) {
            VCamPrefs prefs = new VCamPrefs(context);
            cfg.enabled = prefs.isVirtualCamEnabled();
            cfg.sourceMode = prefs.getSourceMode();
            cfg.loop = prefs.isLoopEnabled();
            cfg.mirror = prefs.isMirrorEnabled();
            cfg.muteAudio = prefs.isMuteAudioEnabled();
            cfg.quality = prefs.getStreamQuality();
            cfg.width = prefs.getTargetWidth();
            cfg.height = prefs.getTargetHeight();
            cfg.fps = prefs.getTargetFps();
            cfg.hasVideo = prefs.hasSelectedVideo();
            return cfg;
        }

        try (Cursor c = context.getContentResolver().query(VirtualVideoProvider.URI_CONFIG, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                cfg.enabled = c.getInt(0) == 1;
                cfg.sourceMode = c.getString(1);
                cfg.loop = c.getInt(2) == 1;
                cfg.mirror = c.getInt(3) == 1;
                cfg.muteAudio = c.getInt(4) == 1;
                cfg.quality = c.getString(5);
                cfg.width = c.getInt(6);
                cfg.height = c.getInt(7);
                cfg.fps = c.getInt(8);
                cfg.hasVideo = c.getInt(9) == 1;
            }
        } catch (Throwable e) {
            Log.w(TAG, "queryRemoteConfig fallback: " + e.getMessage());
        }
        return cfg;
    }

    /**
     * Returns a persistent dummy SurfaceTexture for Camera1 (`setPreviewTexture` / `setPreviewDisplay`)
     * so `Camera.startPreview()` ALWAYS succeeds in Open Camera, HD Camera, WhatsApp, etc.
     */
    public static synchronized SurfaceTexture getDummyCamera1Texture() {
        if (sDummyCamera1Texture == null) {
            sDummyCamera1Texture = new SurfaceTexture(10);
            sDummyCamera1Texture.setDefaultBufferSize(1280, 720);
        }
        return sDummyCamera1Texture;
    }

    /**
     * Returns a persistent dummy Surface for Camera2 (`ICameraDeviceUser.createStream` / `createCaptureSession`)
     * so `cameraserver` writes hardware frames to the dummy sink while our MediaPlayer owns the app's real Surface.
     */
    public static synchronized Surface getDummyCamera2Surface(int width, int height) {
        if (sDummyCamera2Texture == null) {
            sDummyCamera2Texture = new SurfaceTexture(11);
        }
        int w = width > 0 ? width : 1280;
        int h = height > 0 ? height : 720;
        sDummyCamera2Texture.setDefaultBufferSize(w, h);
        if (sDummyCamera2Surface == null || !sDummyCamera2Surface.isValid()) {
            sDummyCamera2Surface = new Surface(sDummyCamera2Texture);
        }
        return sDummyCamera2Surface;
    }

    /**
     * Attaches the virtual video stream to `targetSurface` and starts the 350ms live config watcher.
     */
    public static synchronized void attachVirtualVideoToSurface(Context context, Surface targetSurface, RemoteConfig cfg) {
        if (targetSurface == null || !targetSurface.isValid()) return;
        if (context == null) context = resolveContext();
        if (context == null) return;

        sPrimaryTargetSurface = targetSurface;
        sLastSourceMode = cfg.sourceMode;
        sLastMuteState = cfg.muteAudio;
        sLastLoopState = cfg.loop;

        // Release any previous player bound to this surface
        MediaPlayer old = sActivePlayers.remove(targetSurface);
        if (old != null) {
            safeReleasePlayer(old);
        }
        if (sPrimaryPlayer != null) {
            safeReleasePlayer(sPrimaryPlayer);
            sPrimaryPlayer = null;
        }

        try {
            MediaPlayer mp = new MediaPlayer();
            if ("com.takidz.vcam".equals(context.getPackageName())) {
                File localFile = new VCamPrefs(context).getInternalVideoFile();
                mp.setDataSource(localFile.getAbsolutePath());
                sCachedLocalVideoFile = localFile;
            } else {
                mp.setDataSource(context, VirtualVideoProvider.URI_VIDEO);
                ensureLocalCachedCopyAsync(context);
            }
            mp.setSurface(targetSurface);
            mp.setLooping(cfg.loop);
            float vol = cfg.muteAudio ? 0f : 1f;
            mp.setVolume(vol, vol);
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnErrorListener((player, what, extra) -> {
                Log.w(TAG, "Virtual MediaPlayer error: " + what + "/" + extra);
                return true;
            });
            mp.prepareAsync();

            sPrimaryPlayer = mp;
            sActivePlayers.put(targetSurface, mp);
            startLiveConfigWatcher(context.getApplicationContext());
        } catch (Throwable e) {
            Log.e(TAG, "attachVirtualVideoToSurface error", e);
        }
    }

    private static void ensureLocalCachedCopyAsync(final Context context) {
        new Thread(() -> {
            try {
                File cacheOut = new File(context.getCacheDir(), "vcam_cached_stream.mp4");
                try (InputStream in = context.getContentResolver().openInputStream(VirtualVideoProvider.URI_VIDEO);
                     FileOutputStream out = new FileOutputStream(cacheOut)) {
                    if (in == null) return;
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        out.write(buf, 0, r);
                    }
                }
                if (cacheOut.exists() && cacheOut.length() > 0) {
                    sCachedLocalVideoFile = cacheOut;
                }
            } catch (Throwable ignored) {}
        }).start();
    }

    /**
     * Polls VirtualVideoProvider every 350ms so when the user taps the Floating Overlay button,
     * the video stream inside Open Camera / HD Camera / WhatsApp reacts immediately!
     */
    private static synchronized void startLiveConfigWatcher(final Context appCtx) {
        if (sConfigPoller != null) return;
        sConfigPoller = Executors.newSingleThreadScheduledExecutor();
        sConfigPoller.scheduleAtFixedRate(() -> {
            try {
                RemoteConfig latest = queryRemoteConfig(appCtx);
                MediaPlayer mp = sPrimaryPlayer;
                if (mp != null) {
                    if (latest.muteAudio != sLastMuteState) {
                        sLastMuteState = latest.muteAudio;
                        float v = latest.muteAudio ? 0f : 1f;
                        mp.setVolume(v, v);
                    }
                    if (latest.loop != sLastLoopState) {
                        sLastLoopState = latest.loop;
                        mp.setLooping(latest.loop);
                    }
                    if (!latest.sourceMode.equals(sLastSourceMode)) {
                        sLastSourceMode = latest.sourceMode;
                        if (VCamPrefs.SOURCE_REAL_CAMERA.equals(latest.sourceMode)) {
                            if (mp.isPlaying()) mp.pause();
                        } else if (VCamPrefs.SOURCE_VIDEO.equals(latest.sourceMode)) {
                            if (!mp.isPlaying()) mp.start();
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }, 350, 350, TimeUnit.MILLISECONDS);
    }

    /**
     * Synthesizes a high-quality JPEG byte[] from the current playing position of the virtual video.
     * Fixes `Failed to take picture` in Open Camera, HD Camera, WhatsApp, and Telegram!
     */
    public static byte[] extractCurrentJpegBytes(Context context, RemoteConfig cfg) {
        Bitmap frame = extractCurrentVideoBitmap(context, cfg);
        if (frame == null) {
            // Fallback: generate a clean 1280x720 dark green frame so takePicture never crashes
            frame = Bitmap.createBitmap(cfg.width > 0 ? cfg.width : 1280, cfg.height > 0 ? cfg.height : 720, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(frame);
            c.drawColor(0xFF102818);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        frame.compress(Bitmap.CompressFormat.JPEG, 92, baos);
        return baos.toByteArray();
    }

    public static Bitmap extractCurrentVideoBitmap(Context context, RemoteConfig cfg) {
        long now = System.currentTimeMillis();
        if (sLastExtractedBitmap != null && (now - sLastFrameExtractTimeMs) < 60) {
            return sLastExtractedBitmap;
        }

        if (context == null) context = resolveContext();
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            if (sCachedLocalVideoFile != null && sCachedLocalVideoFile.exists()) {
                mmr.setDataSource(sCachedLocalVideoFile.getAbsolutePath());
            } else if (context != null && "com.takidz.vcam".equals(context.getPackageName())) {
                File f = new VCamPrefs(context).getInternalVideoFile();
                mmr.setDataSource(f.getAbsolutePath());
            } else if (context != null) {
                mmr.setDataSource(context, VirtualVideoProvider.URI_VIDEO);
            } else {
                return sLastExtractedBitmap;
            }

            long posUs = 0L;
            if (sPrimaryPlayer != null) {
                try {
                    posUs = sPrimaryPlayer.getCurrentPosition() * 1000L;
                } catch (Throwable ignored) {}
            }

            Bitmap raw = mmr.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST);
            if (raw == null) raw = mmr.getFrameAtTime(0);
            if (raw != null) {
                if (cfg != null && cfg.mirror) {
                    Matrix m = new Matrix();
                    m.preScale(-1f, 1f);
                    raw = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
                }
                sLastExtractedBitmap = raw;
                sLastFrameExtractTimeMs = now;
                return raw;
            }
        } catch (Throwable e) {
            Log.w(TAG, "extractCurrentVideoBitmap fallback: " + e.getMessage());
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
        return sLastExtractedBitmap;
    }

    /**
     * Converts the current virtual video frame into raw NV21 (YUV420sp) bytes and overwrites `outNv21Buffer` in-place.
     * Used when apps register `Camera.PreviewCallback.onPreviewFrame(byte[] data, Camera camera)`.
     */
    public static void fillNv21PreviewBuffer(Context context, byte[] outNv21Buffer, int width, int height, RemoteConfig cfg) {
        if (outNv21Buffer == null || width <= 0 || height <= 0) return;
        int expectedSize = width * height * 3 / 2;
        if (outNv21Buffer.length < expectedSize) return;

        Bitmap src = extractCurrentVideoBitmap(context, cfg);
        if (src == null) return;

        try {
            Bitmap scaled = (src.getWidth() == width && src.getHeight() == height)
                    ? src
                    : Bitmap.createScaledBitmap(src, width, height, false);
            int[] argb = new int[width * height];
            scaled.getPixels(argb, 0, width, 0, 0, width, height);
            encodeArgbToNv21(outNv21Buffer, argb, width, height);
        } catch (Throwable ignored) {}
    }

    private static void encodeArgbToNv21(byte[] yuv420sp, int[] argb, int width, int height) {
        final int frameSize = width * height;
        int yIndex = 0;
        int uvIndex = frameSize;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int c = argb[j * width + i];
                int R = (c & 0xff0000) >> 16;
                int G = (c & 0xff00) >> 8;
                int B = (c & 0xff);

                int Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                yuv420sp[yIndex++] = (byte) Math.max(0, Math.min(255, Y));

                if (j % 2 == 0 && i % 2 == 0 && uvIndex + 1 < yuv420sp.length) {
                    int U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                    int V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;
                    yuv420sp[uvIndex++] = (byte) Math.max(0, Math.min(255, V));
                    yuv420sp[uvIndex++] = (byte) Math.max(0, Math.min(255, U));
                }
            }
        }
    }

    private static void safeReleasePlayer(MediaPlayer mp) {
        if (mp == null) return;
        try { if (mp.isPlaying()) mp.stop(); } catch (Throwable ignored) {}
        try { mp.release(); } catch (Throwable ignored) {}
    }
}
