package com.adt.pro;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class ADTFirebaseMessagingService extends FirebaseMessagingService {
    private static final String CHANNEL_ID = "adt_price_updates";

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        getSharedPreferences("adt_push", MODE_PRIVATE)
                .edit().putString("fcm_token", token == null ? "" : token).apply();
    }

    @Override
    public void onMessageReceived(RemoteMessage msg) {
        super.onMessageReceived(msg);

        String title = "ADT Pro";
        String body = "تم تحديث بيانات منتج";

        if (msg.getNotification() != null) {
            if (msg.getNotification().getTitle() != null) title = msg.getNotification().getTitle();
            if (msg.getNotification().getBody() != null) body = msg.getNotification().getBody();
        }
        if (msg.getData().containsKey("title")) title = msg.getData().get("title");
        if (msg.getData().containsKey("body")) body = msg.getData().get("body");

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "تحديثات الأسعار", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("إشعارات تعديل أسعار المنتجات");
            ch.enableLights(true);
            ch.setLightColor(Color.CYAN);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(this, CHANNEL_ID)
                : new android.app.Notification.Builder(this);

        b.setSmallIcon(R.drawable.ic_stat_adt)
         .setContentTitle(title)
         .setContentText(body)
         .setStyle(new android.app.Notification.BigTextStyle().bigText(body))
         .setAutoCancel(true)
         .setContentIntent(pi)
         .setPriority(android.app.Notification.PRIORITY_HIGH);

        nm.notify((int)(System.currentTimeMillis() & 0x7fffffff), b.build());
    }
}
