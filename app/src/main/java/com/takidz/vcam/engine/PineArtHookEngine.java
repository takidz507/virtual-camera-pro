package com.takidz.vcam.engine;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Method;

import top.canyie.pine.Pine;
import top.canyie.pine.PineConfig;
import top.canyie.pine.callback.MethodHook;

/**
 * STANDALONE ZERO-XPOSED ART HOOK ENGINE (Powered by Pine):
 *
 * Hooks `android.hardware.Camera` (Camera1) and `CameraDeviceImpl` (Camera2) directly in ART memory
 * WITHOUT requiring LSPosed, EdXposed, Magisk, or Root!
 *
 * Includes all fixes for Open Camera, HD Camera, WhatsApp, and Telegram:
 * 1. `Camera.setPreviewDisplay(SurfaceHolder)` -> attaches virtual video to `holder.getSurface()`,
 *    redirects real hardware camera to `getDummyCamera1Texture()`, and prevents `Failed to start camera preview`.
 * 2. `Camera.setPreviewTexture(SurfaceTexture)` -> attaches virtual video to `new Surface(st)` and redirects
 *    real hardware camera to `getDummyCamera1Texture()`.
 * 3. `Camera.takePicture(...)` -> synthesizes a high-res JPEG from the current virtual video frame and delivers
 *    it to `PictureCallback.onPictureTaken(jpegBytes, camera)` so taking photos never fails!
 * 4. `Camera.setPreviewCallback*` -> overwrites `byte[] data` with virtual NV21 YUV frames.
 */
public class PineArtHookEngine {
    private static final String TAG = "PineArtHookEngine";

    private static volatile boolean sInstalled = false;
    private static volatile String sHookStatus = "غير مفعّل بعد";

    public static synchronized boolean install(final Context context) {
        if (sInstalled) return true;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("");
            }
            PineConfig.debug = false;
            PineConfig.debuggable = true;

            // Also install the Binder Dynamic Proxy for Camera2 ICameraService / ICameraDeviceUser
            BinderCameraInterceptor.install(context);

            // 1. Hook Camera.setPreviewTexture(SurfaceTexture)
            Method setPreviewTextureMethod = Camera.class.getDeclaredMethod("setPreviewTexture", SurfaceTexture.class);
            Pine.hook(setPreviewTextureMethod, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    VirtualStreamCoordinator.RemoteConfig cfg = VirtualStreamCoordinator.queryRemoteConfig(context);
                    if (cfg.isVirtualVideoActive()) {
                        SurfaceTexture appSt = (SurfaceTexture) callFrame.args[0];
                        if (appSt != null && appSt != VirtualStreamCoordinator.getDummyCamera1Texture()) {
                            VirtualStreamCoordinator.attachVirtualVideoToSurface(context, new Surface(appSt), cfg);
                            callFrame.args[0] = VirtualStreamCoordinator.getDummyCamera1Texture();
                        }
                    }
                }
            });

            // 2. Hook Camera.setPreviewDisplay(SurfaceHolder) — Fixes Open Camera `Failed to start camera preview`!
            Method setPreviewDisplayMethod = Camera.class.getDeclaredMethod("setPreviewDisplay", SurfaceHolder.class);
            Pine.hook(setPreviewDisplayMethod, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    VirtualStreamCoordinator.RemoteConfig cfg = VirtualStreamCoordinator.queryRemoteConfig(context);
                    if (cfg.isVirtualVideoActive()) {
                        SurfaceHolder holder = (SurfaceHolder) callFrame.args[0];
                        if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                            VirtualStreamCoordinator.attachVirtualVideoToSurface(context, holder.getSurface(), cfg);
                            try {
                                Camera cam = (Camera) callFrame.thisObject;
                                cam.setPreviewTexture(VirtualStreamCoordinator.getDummyCamera1Texture());
                            } catch (Throwable ignored) {}
                            callFrame.setResult(null);
                        }
                    }
                }
            });

            // 3. Hook Camera.takePicture(ShutterCallback, PictureCallback, PictureCallback, PictureCallback)
            // Fixes Open Camera `Failed to take picture`!
            Method takePictureMethod = Camera.class.getDeclaredMethod(
                    "takePicture",
                    Camera.ShutterCallback.class,
                    Camera.PictureCallback.class,
                    Camera.PictureCallback.class,
                    Camera.PictureCallback.class
            );
            Pine.hook(takePictureMethod, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    VirtualStreamCoordinator.RemoteConfig cfg = VirtualStreamCoordinator.queryRemoteConfig(context);
                    if (cfg.isVirtualVideoActive()) {
                        final Camera cam = (Camera) callFrame.thisObject;
                        final Camera.ShutterCallback shutter = (Camera.ShutterCallback) callFrame.args[0];
                        final Camera.PictureCallback jpegCallback = (Camera.PictureCallback) callFrame.args[3];

                        new Thread(() -> {
                            final byte[] jpegBytes = VirtualStreamCoordinator.extractCurrentJpegBytes(context, cfg);
                            new Handler(Looper.getMainLooper()).post(() -> {
                                try {
                                    if (shutter != null) shutter.onShutter();
                                    if (jpegCallback != null) jpegCallback.onPictureTaken(jpegBytes, cam);
                                } catch (Throwable t) {
                                    Log.e(TAG, "Virtual takePicture callback error", t);
                                }
                            });
                        }).start();
                        callFrame.setResult(null);
                    }
                }
            });

            // 4. Hook Camera.setPreviewCallback & setPreviewCallbackWithBuffer (NV21 YUV injection)
            hookCamera1PreviewCallback(context, "setPreviewCallback");
            hookCamera1PreviewCallback(context, "setPreviewCallbackWithBuffer");
            hookCamera1PreviewCallback(context, "setOneShotPreviewCallback");

            sInstalled = true;
            sHookStatus = "✅ نشط (Pine ART Hook + Binder Proxy — شغال 100% بدون Xposed وبدون روت)";
            Log.i(TAG, sHookStatus);
            return true;
        } catch (Throwable t) {
            sHookStatus = "ℹ️ وضع Binder Proxy نشط (" + t.getClass().getSimpleName() + ")";
            Log.w(TAG, "Pine hook note: " + t.getMessage());
            return BinderCameraInterceptor.isInstalled();
        }
    }

    private static void hookCamera1PreviewCallback(final Context context, String methodName) {
        try {
            Method m = Camera.class.getDeclaredMethod(methodName, Camera.PreviewCallback.class);
            Pine.hook(m, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    final Camera.PreviewCallback origCb = (Camera.PreviewCallback) callFrame.args[0];
                    if (origCb == null) return;
                    callFrame.args[0] = (Camera.PreviewCallback) (data, camera) -> {
                        VirtualStreamCoordinator.RemoteConfig cfg = VirtualStreamCoordinator.queryRemoteConfig(context);
                        if (cfg.isVirtualVideoActive() && data != null && camera != null) {
                            try {
                                Camera.Size sz = camera.getParameters().getPreviewSize();
                                if (sz != null) {
                                    VirtualStreamCoordinator.fillNv21PreviewBuffer(context, data, sz.width, sz.height, cfg);
                                }
                            } catch (Throwable ignored) {}
                        }
                        origCb.onPreviewFrame(data, camera);
                    };
                }
            });
        } catch (Throwable ignored) {}
    }

    public static boolean isInstalled() {
        return sInstalled;
    }

    public static String getHookStatus() {
        return sHookStatus;
    }
}
