package com.takidz.vcam.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.takidz.vcam.engine.BinderCameraInterceptor;
import com.takidz.vcam.engine.PineArtHookEngine;

/**
 * ZERO-XPOSED AUTO-BOOT ENGINE (`initOrder="999999999"`):
 *
 * In Android, ContentProviders are initialized by `ActivityThread.installContentProviders`
 * BEFORE `Application.onCreate()` and BEFORE any Activity or Camera is ever opened!
 * This provider automatically boots `BinderCameraInterceptor` (ICameraService / ICameraDeviceUser Dynamic Proxy)
 * and `PineArtHookEngine` without needing any Xposed framework or Root!
 */
public class ZeroXposedBootProvider extends ContentProvider {
    private static final String TAG = "ZeroXposedBoot";

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx != null) {
            Log.i(TAG, "Booting Zero-Xposed Virtual Camera Engine in process: " + ctx.getPackageName());
            BinderCameraInterceptor.install(ctx);
            PineArtHookEngine.install(ctx);
        }
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] p, @Nullable String s, @Nullable String[] sa, @Nullable String so) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) { return null; }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) { return null; }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String s, @Nullable String[] sa) { return 0; }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues v, @Nullable String s, @Nullable String[] sa) { return 0; }
}
