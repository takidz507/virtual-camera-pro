package com.takidz.vcam;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

public class VCamApplication extends Application {
    public static final String CHANNEL_ID_OVERLAY = "vcam_overlay_channel";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID_OVERLAY,
                    "خدمة الكاميرا الوهمية والتحكم السريع",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("تُبقي الكاميرا الوهمية وأزرار التبديل السريع نشطة فوق التطبيقات");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }
}
