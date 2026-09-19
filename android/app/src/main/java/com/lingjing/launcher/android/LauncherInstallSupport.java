package com.lingjing.launcher.android;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import com.getcapacitor.JSObject;

import java.io.File;

/**
 * 安装 APK 前后的系统查询：待安装文件位置、未知来源权限、电池优化白名单。
 *
 * 只读系统状态，不发起安装也不申请权限——申请流程需要 Activity 回调，
 * 仍留在插件里。
 */
final class LauncherInstallSupport {
    private LauncherInstallSupport() {
    }

    static File downloadedApkFile(Context context) {
        JSObject state;
        try {
            state = JSObject.fromJSONObject(GameDownloadService.readStateObject(context));
            String preparedPath = state.getString("apkPath", "");
            if (!preparedPath.isBlank()) {
                return new File(preparedPath);
            }
        } catch (Exception ignored) {
        }
        return new File(new File(new File(context.getFilesDir(), "downloads"), "prepared"), "CrossingVoid-latest.apk");
    }

    static boolean canInstallUnknownApps(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.getPackageManager().canRequestPackageInstalls();
    }

    static boolean isBatteryOptimizationIgnored(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    static Intent createInstallPermissionIntent(Context context) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        return intent;
    }

}
