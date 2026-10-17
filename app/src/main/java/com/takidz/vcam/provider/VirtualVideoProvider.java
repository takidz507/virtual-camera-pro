package com.takidz.vcam.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.takidz.vcam.utils.VCamPrefs;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Cross-process ContentProvider (`content://com.takidz.vcam.provider/...`)
 * Exposes the active virtual video file and real-time configuration (enabled, sourceMode, loop, quality, mirror)
 * to external apps and LSPatch/Xposed hooks without requiring root or external storage permissions.
 */
public class VirtualVideoProvider extends ContentProvider {
    public static final String AUTHORITY = "com.takidz.vcam.provider";
    public static final Uri URI_VIDEO = Uri.parse("content://" + AUTHORITY + "/video");
    public static final Uri URI_CONFIG = Uri.parse("content://" + AUTHORITY + "/config");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        if (getContext() == null) return null;
        VCamPrefs prefs = new VCamPrefs(getContext());

        MatrixCursor cursor = new MatrixCursor(new String[]{
                "enabled", "source_mode", "loop", "mirror", "mute_audio", "quality", "width", "height", "fps", "has_video"
        });
        cursor.addRow(new Object[]{
                prefs.isVirtualCamEnabled() ? 1 : 0,
                prefs.getSourceMode(),
                prefs.isLoopEnabled() ? 1 : 0,
                prefs.isMirrorEnabled() ? 1 : 0,
                prefs.isMuteAudioEnabled() ? 1 : 0,
                prefs.getStreamQuality(),
                prefs.getTargetWidth(),
                prefs.getTargetHeight(),
                prefs.getTargetFps(),
                prefs.hasSelectedVideo() ? 1 : 0
        });
        return cursor;
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        if (getContext() == null) throw new FileNotFoundException("No context");
        VCamPrefs prefs = new VCamPrefs(getContext());
        File videoFile = prefs.getInternalVideoFile();
        if (!videoFile.exists()) {
            throw new FileNotFoundException("Virtual video file not selected yet");
        }
        return ParcelFileDescriptor.open(videoFile, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        if (uri.getPath() != null && uri.getPath().contains("video")) {
            return "video/mp4";
        }
        return "vnd.android.cursor.item/vcam_config";
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) { return null; }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }
}
