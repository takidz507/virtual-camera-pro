package com.takidz.vcam.engine;

import android.content.Context;
import android.hardware.camera2.params.OutputConfiguration;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.util.Log;
import android.view.Surface;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * REVOLUTIONARY ZERO-XPOSED & ZERO-ROOT CAMERA2 INTERCEPTOR:
 *
 * Instead of requiring an Xposed framework or root access, this engine uses:
 * 1. `HiddenApiBypass.addHiddenApiExemptions("")` (Pure Java sun.misc.Unsafe reflection on Android 9 - 16).
 * 2. `java.lang.reflect.Proxy` on `android.hardware.ICameraService` inside `CameraManager$CameraManagerGlobal`
 *    and `android.os.ServiceManager.sCache.get("media.camera")`.
 * 3. When `ICameraService.connectDevice(...)` returns an `android.hardware.camera2.ICameraDeviceUser`,
 *    this interceptor wraps `ICameraDeviceUser` in a second Dynamic Proxy!
 * 4. When `CameraDeviceImpl` calls `ICameraDeviceUser.createStream(OutputConfiguration)`:
 *    - We extract the app's real preview `Surface` (`outConfig.getSurface()`).
 *    - We attach our `VirtualStreamCoordinator` (`MediaPlayer`) to the app's real `Surface`.
 *    - We pass a dummy `OutputConfiguration(dummySurface)` to the real `ICameraDeviceUser.createStream(...)`!
 *    - `CameraDeviceImpl` keeps the original `OutputConfiguration` in its `mConfiguredOutputs` map, so
 *      `CaptureRequest.Builder.addTarget(appSurface)` succeeds with 0 errors and 0 Surface conflicts!
 */
public class BinderCameraInterceptor {
    private static final String TAG = "BinderCamProxy";

    private static volatile boolean sInstalled = false;
    private static volatile String sStatusMessage = "غير مفعّل بعد";
    private static final AtomicInteger sInterceptedSessionsCount = new AtomicInteger(0);
    private static final AtomicInteger sInterceptedStreamsCount = new AtomicInteger(0);

    public static synchronized boolean install(Context context) {
        if (sInstalled) return true;
        try {
            // Step 1: Unlock all Android 9 - 16 Hidden APIs in pure Java without root or Xposed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("");
            }

            // Step 2: Obtain CameraManagerGlobal singleton
            Class<?> cmGlobalClass = Class.forName("android.hardware.camera2.CameraManager$CameraManagerGlobal");
            Method getMethod = cmGlobalClass.getDeclaredMethod("get");
            getMethod.setAccessible(true);
            Object cmGlobalInstance = getMethod.invoke(null);

            // Ensure mCameraService is connected first
            Method getCameraServiceMethod = cmGlobalClass.getDeclaredMethod("getCameraService");
            getCameraServiceMethod.setAccessible(true);
            Object realCameraService = getCameraServiceMethod.invoke(cmGlobalInstance);

            Field cameraServiceField = cmGlobalClass.getDeclaredField("mCameraService");
            cameraServiceField.setAccessible(true);
            if (realCameraService == null) {
                realCameraService = cameraServiceField.get(cmGlobalInstance);
            }

            Class<?> iCameraServiceClass = Class.forName("android.hardware.ICameraService");

            if (realCameraService != null && !Proxy.isProxyClass(realCameraService.getClass())) {
                Object cameraServiceProxy = createCameraServiceProxy(context, realCameraService, iCameraServiceClass);
                cameraServiceField.set(cmGlobalInstance, cameraServiceProxy);
            }

            // Step 3: Also wrap ServiceManager.sCache["media.camera"] IBinder so future lookups stay hooked
            hookServiceManagerCameraBinder(context, iCameraServiceClass);

            sInstalled = true;
            sStatusMessage = "✅ نشط (ICameraService + ICameraDeviceUser Binder Proxy — بدون Xposed وبدون روت)";
            Log.i(TAG, sStatusMessage);
            return true;
        } catch (Throwable t) {
            sStatusMessage = "⚠️ تعذر تفعيل Binder Proxy بالكامل: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.e(TAG, sStatusMessage, t);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static void hookServiceManagerCameraBinder(final Context context, final Class<?> iCameraServiceClass) {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getServiceMethod = smClass.getDeclaredMethod("getService", String.class);
            getServiceMethod.setAccessible(true);
            final IBinder realBinder = (IBinder) getServiceMethod.invoke(null, "media.camera");
            if (realBinder == null) return;

            Field sCacheField = smClass.getDeclaredField("sCache");
            sCacheField.setAccessible(true);
            Map<String, IBinder> sCache = (Map<String, IBinder>) sCacheField.get(null);
            if (sCache == null) return;

            IBinder proxyBinder = (IBinder) Proxy.newProxyInstance(
                    IBinder.class.getClassLoader(),
                    new Class<?>[]{IBinder.class},
                    (proxy, method, args) -> {
                        if ("queryLocalInterface".equals(method.getName()) && args != null && args.length > 0) {
                            String descriptor = String.valueOf(args[0]);
                            if (descriptor.contains("ICameraService")) {
                                Class<?> stubClass = Class.forName("android.hardware.ICameraService$Stub");
                                Method asInterface = stubClass.getDeclaredMethod("asInterface", IBinder.class);
                                asInterface.setAccessible(true);
                                Object realService = asInterface.invoke(null, realBinder);
                                return createCameraServiceProxy(context, realService, iCameraServiceClass);
                            }
                        }
                        return method.invoke(realBinder, args);
                    }
            );
            sCache.put("media.camera", proxyBinder);
        } catch (Throwable t) {
            Log.w(TAG, "ServiceManager sCache hook note: " + t.getMessage());
        }
    }

    private static Object createCameraServiceProxy(final Context context, final Object realCameraService, final Class<?> iCameraServiceClass) {
        return Proxy.newProxyInstance(
                iCameraServiceClass.getClassLoader(),
                new Class<?>[]{iCameraServiceClass, IInterface.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        Object result = method.invoke(realCameraService, args);
                        String name = method.getName();
                        if ("connectDevice".equals(name) && result != null) {
                            sInterceptedSessionsCount.incrementAndGet();
                            return wrapCameraDeviceUser(context, result);
                        }
                        return result;
                    }
                }
        );
    }

    private static Object wrapCameraDeviceUser(final Context context, final Object realDeviceUser) {
        try {
            final Class<?> iCameraDeviceUserClass = Class.forName("android.hardware.camera2.ICameraDeviceUser");
            if (Proxy.isProxyClass(realDeviceUser.getClass())) {
                return realDeviceUser;
            }
            return Proxy.newProxyInstance(
                    iCameraDeviceUserClass.getClassLoader(),
                    new Class<?>[]{iCameraDeviceUserClass, IInterface.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String methodName = method.getName();
                            if ("createStream".equals(methodName) && args != null && args.length > 0 && args[0] instanceof OutputConfiguration) {
                                VirtualStreamCoordinator.RemoteConfig cfg = VirtualStreamCoordinator.queryRemoteConfig(context);
                                if (cfg.isVirtualVideoActive()) {
                                    OutputConfiguration origConfig = (OutputConfiguration) args[0];
                                    Surface appSurface = origConfig.getSurface();
                                    if (appSurface != null && appSurface.isValid()) {
                                        sInterceptedStreamsCount.incrementAndGet();
                                        // 1. Stream our virtual video onto the app's real Surface
                                        VirtualStreamCoordinator.attachVirtualVideoToSurface(context, appSurface, cfg);
                                        // 2. Give cameraserver a dummy OutputConfiguration so there is 0 Surface conflict!
                                        Surface dummySurface = VirtualStreamCoordinator.getDummyCamera2Surface(cfg.width, cfg.height);
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                            OutputConfiguration dummyConfig = new OutputConfiguration(dummySurface);
                                            args[0] = dummyConfig;
                                        }
                                    }
                                }
                            }
                            return method.invoke(realDeviceUser, args);
                        }
                    }
            );
        } catch (Throwable t) {
            Log.e(TAG, "wrapCameraDeviceUser error", t);
            return realDeviceUser;
        }
    }

    public static boolean isInstalled() {
        return sInstalled;
    }

    public static String getStatusMessage() {
        return sStatusMessage;
    }

    public static int getInterceptedSessionsCount() {
        return sInterceptedSessionsCount.get();
    }

    public static int getInterceptedStreamsCount() {
        return sInterceptedStreamsCount.get();
    }
}
