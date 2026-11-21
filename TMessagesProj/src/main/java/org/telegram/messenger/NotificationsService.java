/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.ui.LaunchActivity;

import uz.unnarsx.cherrygram.core.configs.CherrygramCoreConfig;

public class NotificationsService extends Service {

    @Override
    public void onCreate() {
        super.onCreate();
        ApplicationLoader.postInitApplication();

        if (allowResidentNotification()) {
            NotificationChannelCompat channel = new NotificationChannelCompat.Builder("cgPush", NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(LocaleController.getString(R.string.CG_PushService))
                    .setLightsEnabled(false)
                    .setVibrationEnabled(false)
                    .setSound(null, null)
                    .build();
            if (CherrygramCoreConfig.isDevBuild()) FileLog.d("cgPush: " + "Starting resident notification...");
            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (allowResidentNotification()) {
            try {
                startInForeground();
            } catch (Exception e) {
                FileLog.e("Failed to start foreground", e);
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }

    public void onDestroy() {
        super.onDestroy();
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.getBoolean("pushService", true)) {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
    }

    private void startInForeground() {
        Intent intent = new Intent(this, LaunchActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        android.app.Notification notification = new NotificationCompat.Builder(this, "cgPush")
                        .setSmallIcon(R.drawable.ic_stat_calamus)
                        .setShowWhen(false)
                        .setOngoing(true)
                        .setContentText(LocaleController.getString(R.string.CG_PushService))
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setContentIntent(pendingIntent)
                        .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1390, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1390, notification);
        }
        if (CherrygramCoreConfig.isDevBuild()) FileLog.d("cgPush: " + "Started foreground");
    }

    private boolean allowResidentNotification() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && CherrygramCoreConfig.INSTANCE.getResidentNotification();
    }

}
