package com.takidz.vcam.hook;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.database.Cursor;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.takidz.vcam.provider.VirtualVideoProvider;
import com.takidz.vcam.utils.VCamPrefs;

import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed / LSPatch Non-Root Hook Engine:
 * When the user patches a third-party app (like WhatsApp, Telegram, or Zoom) using LSPatch (No Root required!),
 * this hook intercepts both Camera1 (`setPreviewTexture` / `setPreviewDisplay`) and Camera2 (`createCaptureSession`)
 * and streams the selected video from `VirtualVideoProvider` directly into the app's camera Surface!
 */
public class VirtualCameraXposedHook implements IXposedHookLoadPackage {

    private static MediaPlayer hookedPlayer;

    private static class RemoteConfig {
        boolean enabled = false;
        String sourceMode = VCamPrefs.SOURCE_VIDEO;
        boolean loop = true;
        boolean muteAudio = false;
        boolean hasVideo = false;
    }

    private static RemoteConfig queryRemoteConfig(Context context) {
        RemoteConfig cfg = new RemoteConfig();
        if (context == null) return cfg;
        try (Cursor c = context.getContentResolver().query(VirtualVideoProvider.URI_CONFIG, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                cfg.enabled = c.getInt(0) == 1;
                cfg.sourceMode = c.getString(1);
                cfg.loop = c.getInt(2) == 1;
                cfg.muteAudio = c.getInt(4) == 1;
                cfg.hasVideo = c.getInt(9) == 1;
            }
        } catch (Exception e) {
            XposedBridge.log("VCamPro: Failed querying provider config: " + e.getMessage());
        }
        return cfg;
    }

    private static synchronized void attachVirtualVideoToSurface(Context context, Surface targetSurface, RemoteConfig cfg) {
        if (targetSurface == null || !targetSurface.isValid()) return;
        try {
            if (hookedPlayer != null) {
                try { hookedPlayer.stop(); } catch (Exception ignored) {}
                try { hookedPlayer.release(); } catch (Exception ignored) {}
                hookedPlayer = null;
            }
            hookedPlayer = new MediaPlayer();
            hookedPlayer.setDataSource(context, VirtualVideoProvider.URI_VIDEO);
            hookedPlayer.setSurface(targetSurface);
            hookedPlayer.setLooping(cfg.loop);
            float vol = cfg.muteAudio ? 0f : 1f;
            hookedPlayer.setVolume(vol, vol);
            hookedPlayer.setOnPreparedListener(MediaPlayer::start);
            hookedPlayer.prepareAsync();
        } catch (Exception e) {
            XposedBridge.log("VCamPro: Surface injection error: " + e.getMessage());
        }
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if ("com.takidz.vcam".equals(lpparam.packageName) || "android".equals(lpparam.packageName)) {
            return;
        }

        // 1. Hook Legacy Camera1 API: android.hardware.Camera.setPreviewTexture(SurfaceTexture)
        try {
            XposedHelpers.findAndHookMethod(
                    "android.hardware.Camera",
                    lpparam.classLoader,
                    "setPreviewTexture",
                    SurfaceTexture.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Context ctx = AndroidAppHelper.currentApplication();
                            RemoteConfig cfg = queryRemoteConfig(ctx);
                            if (cfg.enabled && cfg.hasVideo && VCamPrefs.SOURCE_VIDEO.equals(cfg.sourceMode)) {
                                SurfaceTexture st = (SurfaceTexture) param.args[0];
                                if (st != null) {
                                    attachVirtualVideoToSurface(ctx, new Surface(st), cfg);
                                    // Redirect real camera to a dummy SurfaceTexture so it doesn't overwrite our video
                                    param.args[0] = new SurfaceTexture(10);
                                }
                            }
                        }
                    }
            );

            XposedHelpers.findAndHookMethod(
                    "android.hardware.Camera",
                    lpparam.classLoader,
                    "setPreviewDisplay",
                    SurfaceHolder.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Context ctx = AndroidAppHelper.currentApplication();
                            RemoteConfig cfg = queryRemoteConfig(ctx);
                            if (cfg.enabled && cfg.hasVideo && VCamPrefs.SOURCE_VIDEO.equals(cfg.sourceMode)) {
                                SurfaceHolder holder = (SurfaceHolder) param.args[0];
                                if (holder != null && holder.getSurface() != null) {
                                    attachVirtualVideoToSurface(ctx, holder.getSurface(), cfg);
                                    param.setResult(null);
                                }
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("VCamPro: Camera1 hook error: " + t.getMessage());
        }

        // 2. Hook Modern Camera2 API: CameraDeviceImpl.createCaptureSession(List<Surface>, StateCallback, Handler)
        try {
            Class<?> camDeviceImplClass = XposedHelpers.findClassIfExists(
                    "android.hardware.camera2.impl.CameraDeviceImpl",
                    lpparam.classLoader
            );
            if (camDeviceImplClass != null) {
                XposedBridge.hookAllMethods(camDeviceImplClass, "createCaptureSession", new XC_MethodHook() {
                    @Override
                    @SuppressWarnings("unchecked")
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof List) {
                            Context ctx = AndroidAppHelper.currentApplication();
                            RemoteConfig cfg = queryRemoteConfig(ctx);
                            if (cfg.enabled && cfg.hasVideo && VCamPrefs.SOURCE_VIDEO.equals(cfg.sourceMode)) {
                                List<Surface> surfaces = (List<Surface>) param.args[0];
                                if (!surfaces.isEmpty()) {
                                    Surface targetPreviewSurface = surfaces.get(0);
                                    attachVirtualVideoToSurface(ctx, targetPreviewSurface, cfg);
                                }
                            }
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log("VCamPro: Camera2 hook error: " + t.getMessage());
        }
    }
}
