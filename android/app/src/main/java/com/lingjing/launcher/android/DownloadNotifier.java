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
 * 游戏下载的前台服务通知。
 *
 * 通知栏只显示固定文案和进度，不暴露分片名、URL 或本地路径。
 */
final class DownloadNotifier {
    private static final String CHANNEL_ID = "crossingvoid_game_download";
    private static final int NOTIFICATION_ID = 2014;

    private final Service service;

    private DownloadNotifier(Service service) {
        this.service = service;
    }

    static DownloadNotifier attach(Service service) {
        return new DownloadNotifier(service);
    }

    void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "游戏下载", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示零境交错游戏下载和校验进度");
            service.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    void startForeground(int percent) {
        Notification notification = buildProgressNotification(percent);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                service.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                service.startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException ignored) {
            // 保底：startForeground 抛出去会顺着 onStartCommand 掀掉整个服务进程，进度一起丢。
            // 常规情况走不到这里 —— Android 13+ 用户拒掉通知权限时 startForeground 照样成功，
            // 只是通知栏不显示（那是 notify 那条路径的事）。真到这儿多半是渠道/机型的边缘情况，
            // 宁可不挂前台通知也要把这一步跑完。
        }
    }

    void update(int percent) {
        try {
            NotificationManagerCompat.from(service).notify(NOTIFICATION_ID, buildProgressNotification(percent));
        } catch (SecurityException ignored) {
        }
    }

    void showPaused() {
        notify(buildSimple("游戏下载已暂停", android.R.drawable.stat_sys_download_done));
    }

    void showCompletion() {
        notify(buildSimple("游戏下载完成，可以开始安装", android.R.drawable.stat_sys_download_done));
    }

    void showError(String message) {
        Notification notification = new NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("游戏下载失败")
            .setContentText(message)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build();
        notify(notification);
    }

    void cancelActive() {
        NotificationManagerCompat.from(service).cancel(NOTIFICATION_ID);
    }

    private Notification buildProgressNotification(int percent) {
        Intent openIntent = new Intent(service, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
            service, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("零境启动器")
            .setContentText("正在（下载）链接空界幻境中...")
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, Math.max(0, Math.min(100, percent)), false)
            .build();
    }

    private Notification buildSimple(String text, int iconRes) {
        return new NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle("零境启动器")
            .setContentText(text)
            .setAutoCancel(true)
            .build();
    }

    private void notify(Notification notification) {
        try {
            NotificationManagerCompat.from(service).notify(NOTIFICATION_ID, notification);
        } catch (SecurityException ignored) {
            // 没有 POST_NOTIFICATIONS 时 notify 会抛 SecurityException。
            // 这条路径只负责"暂停/完成/失败"的提示，提示画不出来不影响下载结果。
        }
    }
}
