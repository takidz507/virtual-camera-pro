package com.takidz.vcam.service;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.takidz.vcam.R;
import com.takidz.vcam.VCamApplication;
import com.takidz.vcam.utils.VCamPrefs;

/**
 * Floating Overlay Controller that stays on top of WhatsApp, Telegram, Zoom, or Browsers
 * so the user can switch between the Virtual Video and the Real Camera with 1 tap!
 */
public class FloatingControlService extends Service {
    public static final String ACTION_BROADCAST_STATE_CHANGED = "com.takidz.vcam.ACTION_STATE_CHANGED";

    private WindowManager windowManager;
    private View floatingView;
    private VCamPrefs prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new VCamPrefs(this);
        startForeground(1001, buildNotification());
        showFloatingWindow();
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, VCamApplication.CHANNEL_ID_OVERLAY)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("Virtual Camera Pro نشط")
                .setContentText("استخدم الأداة العائمة للتبديل الفوري بين الفيديو والكاميرا الحقيقية")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void showFloatingWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null);

        int layoutFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 30;
        params.y = 180;

        TextView tvLabel = floatingView.findViewById(R.id.tvFloatingLabel);
        TextView btnSwitch = floatingView.findViewById(R.id.btnFloatingSwitch);
        TextView btnClose = floatingView.findViewById(R.id.btnFloatingClose);

        updateFloatingLabel(tvLabel);

        btnSwitch.setOnClickListener(v -> {
            prefs.toggleSourceMode();
            updateFloatingLabel(tvLabel);
            sendBroadcast(new Intent(ACTION_BROADCAST_STATE_CHANGED));
            String modeMsg = prefs.isShowingRealCamera()
                    ? "📷 تم التبديل إلى: الكاميرا الحقيقية"
                    : "🎬 تم التبديل إلى: الفيديو الوهمي";
            Toast.makeText(this, modeMsg, Toast.LENGTH_SHORT).show();
        });

        btnClose.setOnClickListener(v -> stopSelf());

        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        if (windowManager != null && floatingView != null) {
                            windowManager.updateViewLayout(floatingView, params);
                        }
                        return true;
                }
                return false;
            }
        });

        if (windowManager != null) {
            windowManager.addView(floatingView, params);
        }
    }

    private void updateFloatingLabel(TextView tvLabel) {
        if (prefs.isShowingRealCamera()) {
            tvLabel.setText("📷 VCam: كاميرا حقيقية");
        } else {
            tvLabel.setText("🎬 VCam: فيديو (" + prefs.getStreamQuality() + ")");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (windowManager != null && floatingView != null) {
            try { windowManager.removeView(floatingView); } catch (Exception ignored) {}
            floatingView = null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
