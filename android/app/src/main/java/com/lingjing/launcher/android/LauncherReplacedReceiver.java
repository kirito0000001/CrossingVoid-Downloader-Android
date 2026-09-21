package com.lingjing.launcher.android;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

public class LauncherReplacedReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "crossingvoid_launcher_updated";
    private static final int NOTIFICATION_ID = 2015;

    @Override
    public void onReceive(Context context, Intent intent) {
        // 连取 action 都要放在"不会炸"的地方：这个方法存在的意义就是绝不让进程崩在这一刻。
        if (intent == null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        // ⚠️ 这条广播正好在"启动器刚被覆盖安装完"那一刻派发，而 BroadcastReceiver 里漏出去的异常
        // 会直接把进程带崩 —— 用户看到的就是"更新完启动器闪退"。
        // 这个接收器只负责顺手推一条"更新完成，点我打开"的通知，任何一步失败都不值得拿进程去赔。
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (launchIntent == null) return;
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager channelManager = context.getSystemService(NotificationManager.class);
                // 系统服务理论上一定有；拿不到就跳过建频道（下面 notify 会因为没有频道被系统丢掉，不致命）。
                if (channelManager != null) {
                    channelManager.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID,
                        "启动器更新",
                        NotificationManager.IMPORTANCE_HIGH
                    ));
                }
            }

            PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            NotificationCompat.Builder notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("零境启动器更新完成")
                .setContentText("点击打开零境启动器")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification.build());
        } catch (Exception error) {
            // 见方法开头：这里绝不能往外抛。记一笔日志，让"为什么没收到提示"有据可查。
            try {
                LauncherLogStore.append(
                    context,
                    "error",
                    "launcher-replaced",
                    "启动器更新后的提示没有发出去",
                    String.valueOf(error)
                );
            } catch (Exception ignored) {
            }
        }
    }
}
