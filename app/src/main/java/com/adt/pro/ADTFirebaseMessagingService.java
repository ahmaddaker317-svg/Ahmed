package com.adt.pro;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
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

        // V172: اسم التطبيق هو العنوان الثابت، والنص تحته يشرح التغيير مباشرة.
        String title = "ADT Stock";
        String body = "تم تحديث بيانات منتج";

        if (msg.getNotification() != null && msg.getNotification().getBody() != null) {
            body = msg.getNotification().getBody();
        }
        if (msg.getData().containsKey("body") && msg.getData().get("body") != null) {
            body = msg.getData().get("body");
        }

        String actorName = msg.getData().get("actor_name");
        String productName = msg.getData().get("product_name");
        String oldSellPrice = msg.getData().get("old_sell_price");
        String newSellPrice = msg.getData().get("new_sell_price");
        String changeType = msg.getData().get("change_type");
        String currencySymbol = msg.getData().get("currency_symbol");
        if (currencySymbol == null || currencySymbol.trim().isEmpty()) currencySymbol = "د.ل";
        if ("sell_price".equals(changeType)
                && actorName != null && !actorName.trim().isEmpty()
                && productName != null && !productName.trim().isEmpty()
                && oldSellPrice != null && !oldSellPrice.trim().isEmpty()
                && newSellPrice != null && !newSellPrice.trim().isEmpty()) {
            body = "قام " + actorName.trim() + " بتغيير سعر بيع «" + productName.trim()
                    + "» من " + oldSellPrice.trim() + " " + currencySymbol.trim()
                    + " إلى " + newSellPrice.trim() + " " + currencySymbol.trim();
        }

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "إشعارات ADT Stock", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("إشعارات تعديل الأسعار والتنبيهات المهمة");
            ch.enableLights(true);
            ch.setLightColor(Color.CYAN);
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 280, 140, 280});
            ch.setShowBadge(true);
            ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            ch.setSound(soundUri, audioAttributes);
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
         .setPriority(android.app.Notification.PRIORITY_HIGH)
         .setCategory(android.app.Notification.CATEGORY_MESSAGE)
         .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
         .setColor(Color.rgb(0, 210, 180))
         .setDefaults(android.app.Notification.DEFAULT_ALL)
         .setWhen(System.currentTimeMillis())
         .setShowWhen(true);

        nm.notify((int)(System.currentTimeMillis() & 0x7fffffff), b.build());
    }
}
