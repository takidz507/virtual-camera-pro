package com.takidz.vcam.hook;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.takidz.vcam.engine.BinderCameraInterceptor;
import com.takidz.vcam.engine.VirtualStreamCoordinator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Upgraded v3.0 Hybrid Hook Engine (Works with both BinderCameraInterceptor & LSPatch):
 *
 * Fixes all issues observed in the Open Camera / WhatsApp test (`5620.mp4`):
 * 1. `Camera.setPreviewDisplay(SurfaceHolder)`: Redirects hardware camera to `getDummyCamera1Texture()`
 *    BEFORE returning null so `Camera.startPreview()` never throws `Failed to start camera preview`!
 * 2. `Camera.takePicture(...)`: Synthesizes a high-res JPEG from the current virtual video frame and
 *    calls `shutter.onShutter()` + `jpeg.onPictureTaken(jpegBytes, camera)` so taking photos succeeds!
 * 3. `Camera.setPreviewCallback*`: Overwrites `byte[] data` with virtual NV21 YUV frames.
 * 4. `Camera2`: Hooks `BinderCameraInterceptor` + `CaptureRequest.Builder.addTarget` + `createCaptureSession`
 *    (including `List<Surface>`, `OutputConfiguration`, and Android 9-16 `SessionConfiguration`) so
 *    `cameraserver` writes to `dummyCamera2Surface` while our `MediaPlayer` owns the app's real `Surface`!
 */
public class VirtualCameraXposedHook implements IXposedHookLoadPackage {

    // Track surfaces that we redirected to dummyCamera2Surface so CaptureRequest.Builder.addTarget swaps them consistently
    private static final Set<Surface> sRedirectedCamera2Surfaces =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if ("com.takidz.vcam".equals(lpparam.packageName) || "android".equals(lpparam.packageName)) {
            return;
        }

        // 0. Install Binder Dynamic Proxy on Application.attach so ICameraService & ICameraDeviceUser are proxied automatically
        try {
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "attach",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context ctx = (Context) param.args[0];
                            BinderCameraInterceptor.install(ctx);
                        }
                    }
            );
        } catch (Throwable ignored) {}

        // 1. Hook Camera1 API: setPreviewTexture, setPreviewDisplay, takePicture, and PreviewCallbacks
        hookCamera1Api(lpparam);

        // 2. Hook Camera2 API: CaptureRequest.Builder.addTarget + all createCaptureSession overloads
        hookCamera2Api(lpparam);
    }

    private void hookCamera1Api(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 1A. Camera.setPreviewTexture(SurfaceTexture) — Used by HD Camera & TextureView apps
            XposedHelpers.findAndHookMethod(
                    "android.hardware.Camera",
                    lpparam.classLoader,
                    "setPreviewTexture",
                    SurfaceTexture.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Context ctx = AndroidAppHelper.currentApplication();
                            VirtualStreamCoordinator.RemoteConfig cfg = VirtualStreamCoordinator.queryRemoteConfig(ctx);
                            if (cfg.isVirtualVideoActive()) {
                                SurfaceTexture st = (SurfaceTexture) param.args[0];
                                SurfaceTexture dummy = VirtualStreamCoordinator.getDummyCamera1Texture();
                                if (st != null && st != dummy) {
                                    VirtualStreamCoordinator.attachVirtualVideoToSurface(ctx, new Surface(st), cfg);
                                    param.args[0] = dummy;
                                }
                            }
                        }
                    }
            );

            // 1B. Camera.setPreviewDisplay(SurfaceHolder) — Used by Open Camera!
            // FIXED: Give the real hardware Camera a dummy SurfaceTexture BEFORE skipping setPreviewDisplay
            // so `Camera.startPreview()` succeeds without throwing `Failed to start camera preview`!
            XposedHelpers.findAndHookMethod(
                    "android.hardware.Camera",
                    lpparam.classLoader,
                    "setPreviewDisplay",
                    SurfaceHolder.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Context ctx = AndroidAppHelper.currentApplication();
                            VirtualStreamCoordinator.RemoteConfig cfg = VirtualStreamCoordinator.queryRemoteConfig(ctx);
                            if (cfg.isVirtualVideoActive()) {
                                SurfaceHolder holder = (SurfaceHolder) param.args[0];
                                if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                                    VirtualStreamCoordinator.attachVirtualVideoToSurface(ctx, holder.getSurface(), cfg);
                                    try {
                                        Camera cam = (Camera) param.thisObject;
                                        cam.setPreviewTexture(VirtualStreamCoordinator.getDummyCamera1Texture());
                                    } catch (Throwable t) {
                                        XposedBridge.log("VCamPro: Dummy texture bind note: " + t.getMessage());
                                    }
                                    param.setResult(null);
                                }
                            }
                        }
                    }
            );

            // 1C. Camera.takePicture(ShutterCallback, PictureCallback, PictureCallback, PictureCallback)
            // FIXED: Synthesizes JPEG bytes from the virtual video so Open Camera & WhatsApp photo capture works!
            XposedHelpers.findAndHookMethod(
                    "android.hardware.Camera",
                    lpparam.classLoader,
                    "takePicture",
                    Camera.ShutterCallback.class,
                    Camera.PictureCallback.class,
                    Camera.PictureCallback.class,
                    Camera.PictureCallback.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(final MethodHookParam param) {
                            final Context ctx = AndroidAppHelper.currentApplication();
                            final VirtualStreamCoordinator.RemoteConfig cfg = VirtualStreamCoordinator.queryRemoteConfig(ctx);
                            if (cfg.isVirtualVideoActive()) {
                                final Camera cam = (Camera) param.thisObject;
                                final Camera.ShutterCallback shutter = (Camera.ShutterCallback) param.args[0];
                                final Camera.PictureCallback jpeg = (Camera.PictureCallback) param.args[3];

                                new Thread(() -> {
                                    final byte[] jpegBytes = VirtualStreamCoordinator.extractCurrentJpegBytes(ctx, cfg);
                                    new Handler(Looper.getMainLooper()).post(() -> {
                                        try {
                                            if (shutter != null) shutter.onShutter();
                                            if (jpeg != null) jpeg.onPictureTaken(jpegBytes, cam);
                                        } catch (Throwable t) {
                                            XposedBridge.log("VCamPro: takePicture callback error: " + t.getMessage());
                                        }
                                    });
                                }).start();
                                param.setResult(null);
                            }
                        }
                    }
            );

            // 1D. Camera.setPreviewCallback / setPreviewCallbackWithBuffer / setOneShotPreviewCallback (NV21 YUV frames)
            String[] cbMethods = {"setPreviewCallback", "setPreviewCallbackWithBuffer", "setOneShotPreviewCallback"};
            for (String mName : cbMethods) {
                XposedHelpers.findAndHookMethod(
                        "android.hardware.Camera",
                        lpparam.classLoader,
                        mName,
                        Camera.PreviewCallback.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                final Camera.PreviewCallback orig = (Camera.PreviewCallback) param.args[0];
                                if (orig == null) return;
                                param.args[0] = (Camera.PreviewCallback) (data, camera) -> {
                                    Context ctx = AndroidAppHelper.currentApplication();
                                    VirtualStreamCoordinator.RemoteConfig cfg = VirtualStreamCoordinator.queryRemoteConfig(ctx);
                                    if (cfg.isVirtualVideoActive() && data != null && camera != null) {
                                        try {
                                            Camera.Size sz = camera.getParameters().getPreviewSize();
                                            if (sz != null) {
                                                VirtualStreamCoordinator.fillNv21PreviewBuffer(ctx, data, sz.width, sz.height, cfg);
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                    orig.onPreviewFrame(data, camera);
                                };
                            }
                        }
                );
            }
        } catch (Throwable t) {
            XposedBridge.log("VCamPro: Camera1 hook error: " + t.getMessage());
        }
    }

    private void hookCamera2Api(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 2A. Hook CaptureRequest.Builder.addTarget(Surface)
            // When an app adds its preview Surface to a CaptureRequest, we stream virtual video to that Surface
            // and redirect the CaptureRequest to our dummyCamera2Surface so CameraDeviceImpl never complains!
            XposedHelpers.findAndHookMethod(
                    CaptureRequest.Builder.class,
                    "addTarget",
                    Surface.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Surface target = (Surface) param.args[0];
                            if (target == null) return;
                            Context ctx = AndroidAppHelper.currentApplication();
                            VirtualStreamCoordinator.RemoteConfig cfg = VirtualStreamCoordinator.queryRemoteConfig(ctx);
                            if (cfg.isVirtualVideoActive()) {
                                Surface dummy = VirtualStreamCoordinator.getDummyCamera2Surface(cfg.width, cfg.height);
                                if (target != dummy && sRedirectedCamera2Surfaces.contains(target)) {
                                    param.args[0] = dummy;
                                }
                            }
                        }
                    }
            );

            // 2B. Hook all CameraDeviceImpl.createCaptureSession* methods (List<Surface>, OutputConfigurations, SessionConfiguration)
            Class<?> camDeviceImplClass = XposedHelpers.findClassIfExists(
                    "android.hardware.camera2.impl.CameraDeviceImpl",
                    lpparam.classLoader
            );
            if (camDeviceImplClass != null) {
                XC_MethodHook sessionHook = new XC_MethodHook() {
                    @Override
                    @SuppressWarnings("unchecked")
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0) return;
                        Context ctx = AndroidAppHelper.currentApplication();
                        VirtualStreamCoordinator.RemoteConfig cfg = VirtualStreamCoordinator.queryRemoteConfig(ctx);
                        if (!cfg.isVirtualVideoActive()) return;

                        Surface dummySurface = VirtualStreamCoordinator.getDummyCamera2Surface(cfg.width, cfg.height);

                        // Case 1: SessionConfiguration (Android 9 - 16 modern Camera2 / CameraX API)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && param.args[0] instanceof SessionConfiguration) {
                            SessionConfiguration sc = (SessionConfiguration) param.args[0];
                            List<OutputConfiguration> outputs = sc.getOutputConfigurations();
                            if (outputs != null && !outputs.isEmpty()) {
                                OutputConfiguration first = outputs.get(0);
                                Surface appSurface = first.getSurface();
                                if (appSurface != null && appSurface != dummySurface) {
                                    sRedirectedCamera2Surfaces.add(appSurface);
                                    VirtualStreamCoordinator.attachVirtualVideoToSurface(ctx, appSurface, cfg);
                                    List<OutputConfiguration> newOutputs = new ArrayList<>();
                                    newOutputs.add(new OutputConfiguration(dummySurface));
                                    for (int i = 1; i < outputs.size(); i++) {
                                        newOutputs.add(outputs.get(i));
                                    }
                                    param.args[0] = new SessionConfiguration(
                                            sc.getSessionType(),
                                            newOutputs,
                                            sc.getExecutor(),
                                            sc.getStateCallback()
                                    );
                                }
                            }
                            return;
                        }

                        // Case 2: List<Surface> or List<OutputConfiguration>
                        if (param.args[0] instanceof List) {
                            List<?> list = (List<?>) param.args[0];
                            if (list.isEmpty()) return;
                            Object first = list.get(0);
                            if (first instanceof Surface) {
                                Surface appSurface = (Surface) first;
                                if (appSurface != dummySurface) {
                                    sRedirectedCamera2Surfaces.add(appSurface);
                                    VirtualStreamCoordinator.attachVirtualVideoToSurface(ctx, appSurface, cfg);
                                    List<Surface> replaced = new ArrayList<>((List<Surface>) list);
                                    replaced.set(0, dummySurface);
                                    param.args[0] = replaced;
                                }
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && first instanceof OutputConfiguration) {
                                OutputConfiguration outCfg = (OutputConfiguration) first;
                                Surface appSurface = outCfg.getSurface();
                                if (appSurface != null && appSurface != dummySurface) {
                                    sRedirectedCamera2Surfaces.add(appSurface);
                                    VirtualStreamCoordinator.attachVirtualVideoToSurface(ctx, appSurface, cfg);
                                    List<OutputConfiguration> replaced = new ArrayList<>((List<OutputConfiguration>) list);
                                    replaced.set(0, new OutputConfiguration(dummySurface));
                                    param.args[0] = replaced;
                                }
                            }
                        }
                    }
                };

                XposedBridge.hookAllMethods(camDeviceImplClass, "createCaptureSession", sessionHook);
                XposedBridge.hookAllMethods(camDeviceImplClass, "createCaptureSessionByOutputConfigurations", sessionHook);
            }
        } catch (Throwable t) {
            XposedBridge.log("VCamPro: Camera2 hook error: " + t.getMessage());
        }
    }
}
