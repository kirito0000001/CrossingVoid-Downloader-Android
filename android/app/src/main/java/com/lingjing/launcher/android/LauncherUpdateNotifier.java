package com.lingjing.launcher.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * 启动器更新的前台服务通知。
 *
 * 与游戏下载的通知分开：版本号不同、渠道不同，互不干扰。
 */
final class LauncherUpdateNotifier {
    private static final String CHANNEL_ID = "crossingvoid_launcher_update";
    private static final int NOTIFICATION_ID = 2015;

    private final Service service;

    private LauncherUpdateNotifier(Service service) {
        this.service = service;
    }

    static LauncherUpdateNotifier attach(Service service) {
        return new LauncherUpdateNotifier(service);
    }

    void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "启动器更新", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示零境启动器更新进度");
            service.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    Notification build(int percent) {
        Intent openIntent = new Intent(service, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(service, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("零境启动器")
            .setContentText("正在更新零境启动器...")
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, Math.max(0, Math.min(100, percent)), false)
            .build();
    }

    void startForeground(int percent) {
        Notification notification = build(percent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            service.startForeground(NOTIFICATION_ID, notification);
        }
    }

    void update(int percent) {
        try {
            NotificationManagerCompat.from(service).notify(NOTIFICATION_ID, build(percent));
        } catch (SecurityException ignored) {
        }
    }

    void showCompletion(String versionName) {
        Notification notification = new NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("启动器更新已就绪")
            .setContentText("版本 " + versionName + " 可以安装")
            .setAutoCancel(true)
            .build();
        NotificationManagerCompat.from(service).notify(NOTIFICATION_ID, notification);
    }

    void showError(String message) {
        Notification notification = new NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("启动器更新失败")
            .setContentText(message)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build();
        NotificationManagerCompat.from(service).notify(NOTIFICATION_ID, notification);
    }

    void cancelActive() {
        NotificationManagerCompat.from(service).cancel(NOTIFICATION_ID);
    }
}
