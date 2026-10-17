package com.takidz.vcam.engine;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import com.takidz.vcam.utils.VCamPrefs;

import java.io.File;

/**
 * Renders the selected virtual video onto any Surface or TextureView,
 * supporting loop, audio mute, horizontal mirror, and frame extraction at target quality.
 */
public class SurfaceVideoPlayer {
    private static final String TAG = "SurfaceVideoPlayer";

    private MediaPlayer mediaPlayer;
    private Surface currentSurface;

    public synchronized void startPlayback(Context context, Surface targetSurface, VCamPrefs prefs) {
        stopPlayback();
        File videoFile = prefs.getInternalVideoFile();
        if (targetSurface == null || !targetSurface.isValid() || !videoFile.exists()) {
            return;
        }

        try {
            currentSurface = targetSurface;
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(context, Uri.fromFile(videoFile));
            mediaPlayer.setSurface(targetSurface);
            mediaPlayer.setLooping(prefs.isLoopEnabled());

            float vol = prefs.isMuteAudioEnabled() ? 0f : 1f;
            mediaPlayer.setVolume(vol, vol);

            mediaPlayer.setOnPreparedListener(MediaPlayer::start);
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "MediaPlayer error: " + what + ", " + extra);
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Failed starting SurfaceVideoPlayer", e);
        }
    }

    public synchronized void updateLiveSettings(VCamPrefs prefs, TextureView optionalTextureView) {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.setLooping(prefs.isLoopEnabled());
                float vol = prefs.isMuteAudioEnabled() ? 0f : 1f;
                mediaPlayer.setVolume(vol, vol);
            } catch (Exception ignored) {}
        }
        if (optionalTextureView != null) {
            optionalTextureView.setScaleX(prefs.isMirrorEnabled() ? -1f : 1f);
        }
    }

    public synchronized int getCurrentPositionMs() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.getCurrentPosition();
            } catch (Exception ignored) {}
        }
        return 0;
    }

    public synchronized void stopPlayback() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
            } catch (Exception ignored) {}
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        currentSurface = null;
    }

    /**
     * Extracts the exact video frame at `positionMs` scaled to the user's selected broadcast quality (480p/720p/1080p)
     * and applies horizontal mirroring if enabled.
     */
    public static Bitmap extractScaledFrame(File videoFile, long positionMs, VCamPrefs prefs) {
        if (videoFile == null || !videoFile.exists()) return null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(videoFile.getAbsolutePath());
            Bitmap rawFrame = retriever.getFrameAtTime(positionMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
            if (rawFrame == null) {
                rawFrame = retriever.getFrameAtTime(0);
            }
            if (rawFrame == null) return null;

            int targetW = prefs.getTargetWidth();
            int targetH = prefs.getTargetHeight();
            if (rawFrame.getHeight() > rawFrame.getWidth()) {
                // Portrait video: swap target dimensions
                int tmp = targetW;
                targetW = targetH;
                targetH = tmp;
            }

            Bitmap scaled = Bitmap.createScaledBitmap(rawFrame, targetW, targetH, true);
            if (prefs.isMirrorEnabled()) {
                Matrix m = new Matrix();
                m.preScale(-1f, 1f);
                scaled = Bitmap.createBitmap(scaled, 0, 0, scaled.getWidth(), scaled.getHeight(), m, true);
            }
            return scaled;
        } catch (Exception e) {
            Log.e(TAG, "Frame extraction failed", e);
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }
}
