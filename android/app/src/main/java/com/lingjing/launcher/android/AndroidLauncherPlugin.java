package com.lingjing.launcher.android;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.activity.result.ActivityResult;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;

@CapacitorPlugin(
    name = "AndroidLauncher",
    permissions = {
        @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
    }
)
public class AndroidLauncherPlugin extends Plugin {
    private static final String DEFAULT_GAME_PACKAGE = "com.TFAC.CorssingVoid";
    private boolean receiverRegistered;
    private boolean launcherUpdateReceiverRegistered;
    private final BroadcastReceiver downloadStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String json = intent.getStringExtra(GameDownloadService.EXTRA_STATE);
            if (json == null || json.isBlank()) {
                return;
            }
            try {
                notifyListeners("downloadProgress", new JSObject(json), true);
            } catch (Exception ignored) {
            }
        }
    };
    private final BroadcastReceiver launcherUpdateStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String json = intent.getStringExtra(LauncherUpdateService.EXTRA_STATE);
            if (json == null || json.isBlank()) return;
            try {
                notifyListeners("launcherUpdateProgress", new JSObject(json), true);
            } catch (Exception ignored) {
            }
        }
    };

    @Override
    public void load() {
        ContextCompat.registerReceiver(
            getContext(),
            downloadStateReceiver,
            new IntentFilter(GameDownloadService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        );
        receiverRegistered = true;
        ContextCompat.registerReceiver(
            getContext(),
            launcherUpdateStateReceiver,
            new IntentFilter(LauncherUpdateService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        );
        launcherUpdateReceiverRegistered = true;
    }

    @Override
    protected void handleOnDestroy() {
        if (receiverRegistered) {
            try {
                getContext().unregisterReceiver(downloadStateReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiverRegistered = false;
        }
        if (launcherUpdateReceiverRegistered) {
            try {
                getContext().unregisterReceiver(launcherUpdateStateReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            launcherUpdateReceiverRegistered = false;
        }
        super.handleOnDestroy();
    }

    @PluginMethod
    public void getLauncherInfo(PluginCall call) {
        try {
            PackageInfo packageInfo = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            JSObject result = new JSObject();
            result.put("versionName", packageInfo.versionName == null ? "" : packageInfo.versionName);
            result.put("versionCode", packageVersionCode(packageInfo));
            call.resolve(result);
        } catch (PackageManager.NameNotFoundException error) {
            call.reject("无法读取启动器版本。", error);
        }
    }

    @PluginMethod
    public void appendLauncherLog(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(LauncherLogStore.append(
                getContext(),
                call.getString("level", "info"),
                call.getString("event", "launcher"),
                call.getString("message", ""),
                call.getString("details", "")
            )));
        } catch (Exception error) {
            call.reject("无法写入启动器日志。", error);
        }
    }

    @PluginMethod
    public void getLauncherLogInfo(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(LauncherLogStore.getInfo(getContext())));
        } catch (Exception error) {
            call.reject("无法读取启动器日志信息。", error);
        }
    }

    @PluginMethod
    public void uploadLauncherLog(PluginCall call) {
        String launcherVersion = call.getString("launcherVersion", "");
        getBridge().execute(() -> {
            try {
                call.resolve(JSObject.fromJSONObject(LauncherLogStore.upload(getContext(), launcherVersion)));
            } catch (Exception error) {
                call.reject(error.getMessage() == null ? "启动器日志上传失败。" : error.getMessage(), error);
            }
        });
    }

    @PluginMethod
    public void checkGame(PluginCall call) {
        String packageName = call.getString("packageName", DEFAULT_GAME_PACKAGE);
        JSObject result = new JSObject();
        result.put("packageName", packageName);
        result.put("installed", false);
        result.put("versionCode", 0);
        result.put("installerOwnsGamePackage", getContext().getPackageName().equals(packageName));
        call.resolve(result);
    }

    @PluginMethod
    public void openInstallPermissionSettings(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            JSObject result = new JSObject();
            result.put("opened", false);
            call.resolve(result);
            return;
        }

        try {
            Intent intent = createInstallPermissionIntent();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            JSObject result = new JSObject();
            result.put("opened", true);
            call.resolve(result);
        } catch (ActivityNotFoundException | SecurityException error) {
            call.reject("无法打开安装未知应用权限设置。", error);
        }
    }

    @PluginMethod
    public void getLauncherPermissionStatus(PluginCall call) {
        JSObject result = new JSObject();
        result.put("canInstallUnknownApps", canInstallUnknownApps());
        result.put("batteryOptimizationIgnored", isBatteryOptimizationIgnored());
        call.resolve(result);
    }

    @PluginMethod
    public void openBatteryOptimizationSettings(PluginCall call) {
        boolean openedDirectRequest = false;
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isBatteryOptimizationIgnored()) {
                intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getContext().getPackageName()));
                openedDirectRequest = true;
            } else {
                intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            try {
                Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(fallback);
                openedDirectRequest = false;
            } catch (ActivityNotFoundException | SecurityException fallbackError) {
                call.reject("无法打开后台下载权限设置。", fallbackError);
                return;
            }
        }
        JSObject result = new JSObject();
        result.put("opened", true);
        result.put("directRequest", openedDirectRequest);
        call.resolve(result);
    }

    @PluginMethod
    public void getDownloadState(PluginCall call) {
        try {
            JSObject state = JSObject.fromJSONObject(GameDownloadService.readStateObject(getContext()));
            String status = state.getString("status", "idle");
            if (!GameDownloadService.isRunning() && (
                status.equals("downloading") ||
                status.equals("verifying") ||
                status.equals("merging") ||
                status.equals("extracting")
            )) {
                state.put("status", "paused");
                state.put("message", "下载任务曾被系统中断，可以从现有进度继续。");
                state.put("canPause", false);
            }
            call.resolve(state);
        } catch (Exception error) {
            call.reject("无法读取下载状态。", error);
        }
    }

    @PluginMethod
    public void startDownload(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "downloadNotificationPermissionResult");
            return;
        }
        startDownloadService(call);
    }

    @PermissionCallback
    private void downloadNotificationPermissionResult(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && getPermissionState("notifications") != PermissionState.GRANTED) {
            call.reject("需要通知权限才能在后台显示游戏下载进度。");
            return;
        }
        startDownloadService(call);
    }

    private void startDownloadService(PluginCall call) {
        JSObject plan = call.getObject("plan");
        if (plan == null) {
            call.reject("缺少游戏下载清单。");
            return;
        }
        Intent intent = new Intent(getContext(), GameDownloadService.class);
        intent.setAction(GameDownloadService.ACTION_START);
        intent.putExtra(GameDownloadService.EXTRA_PLAN, plan.toString());
        ContextCompat.startForegroundService(getContext(), intent);
        JSObject result = new JSObject();
        result.put("started", true);
        call.resolve(result);
    }

    @PluginMethod
    public void getLauncherUpdateState(PluginCall call) {
        try {
            PackageInfo packageInfo = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            JSObject state = JSObject.fromJSONObject(
                LauncherUpdateService.clearIfInstalled(getContext(), packageVersionCode(packageInfo), packageInfo.versionName)
            );
            if (!LauncherUpdateService.isRunning() && state.getString("status", "idle").equals("downloading")) {
                state.put("status", "error");
                state.put("message", "启动器更新下载曾被系统中断，请重新开始。");
            }
            call.resolve(state);
        } catch (Exception error) {
            call.reject("无法读取启动器更新状态。", error);
        }
    }

    @PluginMethod
    public void startLauncherUpdate(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "launcherUpdateNotificationPermissionResult");
            return;
        }
        startLauncherUpdateService(call);
    }

    @PermissionCallback
    private void launcherUpdateNotificationPermissionResult(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && getPermissionState("notifications") != PermissionState.GRANTED) {
            call.reject("需要通知权限才能显示启动器更新进度。");
            return;
        }
        startLauncherUpdateService(call);
    }

    private void startLauncherUpdateService(PluginCall call) {
        JSObject plan = call.getObject("plan");
        if (plan == null) {
            call.reject("缺少启动器更新清单。");
            return;
        }
        Intent intent = new Intent(getContext(), LauncherUpdateService.class);
        intent.setAction(LauncherUpdateService.ACTION_START);
        intent.putExtra(LauncherUpdateService.EXTRA_PLAN, plan.toString());
        ContextCompat.startForegroundService(getContext(), intent);
        JSObject result = new JSObject();
        result.put("started", true);
        call.resolve(result);
    }

    @PluginMethod
    public void cancelLauncherUpdate(PluginCall call) {
        Intent intent = new Intent(getContext(), LauncherUpdateService.class);
        intent.setAction(LauncherUpdateService.ACTION_CANCEL);
        getContext().startService(intent);
        JSObject result = new JSObject();
        result.put("cancelled", true);
        call.resolve(result);
    }

    @PluginMethod
    public void installLauncherUpdate(PluginCall call) {
        JSObject state;
        try {
            state = JSObject.fromJSONObject(LauncherUpdateService.readState(getContext()));
        } catch (Exception error) {
            call.reject("无法读取启动器更新文件。", error);
            return;
        }
        File apkFile = new File(state.getString("apkPath", ""));
        if (!state.getString("status", "idle").equals("ready") || !apkFile.isFile() || apkFile.length() <= 0) {
            call.reject("启动器更新尚未下载完成。");
            return;
        }
        if (requestInstallPermission(call, "launcherInstallPermissionResult")) return;
        launchLauncherUpdateInstaller(call, apkFile);
    }

    @ActivityCallback
    private void launcherInstallPermissionResult(PluginCall call, ActivityResult result) {
        if (!canInstallUnknownApps()) {
            call.reject("未允许零境启动器安装未知应用，无法继续更新。");
            return;
        }
        installLauncherUpdate(call);
    }

    @ActivityCallback
    private void launcherInstallResult(PluginCall call, ActivityResult result) {
        boolean installed = result.getResultCode() == Activity.RESULT_OK;
        if (installed) {
            Intent launchIntent = getContext().getPackageManager().getLaunchIntentForPackage(getContext().getPackageName());
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                getContext().startActivity(launchIntent);
            }
        }
        JSObject response = new JSObject();
        response.put("started", true);
        response.put("installerResult", result.getResultCode());
        response.put("installed", installed);
        call.resolve(response);
    }

    @PluginMethod
    public void pauseDownload(PluginCall call) {
        Intent intent = new Intent(getContext(), GameDownloadService.class);
        intent.setAction(GameDownloadService.ACTION_PAUSE);
        getContext().startService(intent);
        JSObject result = new JSObject();
        result.put("paused", true);
        call.resolve(result);
    }

    @PluginMethod
    public void cancelDownload(PluginCall call) {
        Intent intent = new Intent(getContext(), GameDownloadService.class);
        intent.setAction(GameDownloadService.ACTION_CANCEL);
        getContext().startService(intent);
        JSObject result = new JSObject();
        result.put("cancelled", true);
        call.resolve(result);
    }

    @PluginMethod
    public void installDownloadedApk(PluginCall call) {
        File apkFile = getDownloadedApkFile();
        if (!apkFile.exists() || apkFile.length() <= 0) {
            call.reject("安装包不存在，请先完成下载。路径：" + apkFile.getAbsolutePath());
            return;
        }

        try {
            ApkPackageValidator.validateReplacement(getContext(), apkFile, DEFAULT_GAME_PACKAGE);
        } catch (Exception error) {
            call.reject(error.getMessage() == null ? "目标游戏 APK 无法覆盖当前安装器。" : error.getMessage(), error);
            return;
        }

        if (requestInstallPermission(call, "gameInstallPermissionResult")) return;
        launchApkInstaller(call, apkFile, "无法打开系统安装器。");
    }

    @ActivityCallback
    private void gameInstallPermissionResult(PluginCall call, ActivityResult result) {
        if (!canInstallUnknownApps()) {
            call.reject("未允许零境启动器安装未知应用，无法安装游戏。");
            return;
        }
        installDownloadedApk(call);
    }

    private File getDownloadedApkFile() {
        JSObject state;
        try {
            state = JSObject.fromJSONObject(GameDownloadService.readStateObject(getContext()));
            String preparedPath = state.getString("apkPath", "");
            if (!preparedPath.isBlank()) {
                return new File(preparedPath);
            }
        } catch (Exception ignored) {
        }
        return new File(new File(new File(getContext().getFilesDir(), "downloads"), "prepared"), "CrossingVoid-latest.apk");
    }

    private boolean canInstallUnknownApps() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getContext().getPackageManager().canRequestPackageInstalls();
    }

    private boolean isBatteryOptimizationIgnored() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager powerManager = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(getContext().getPackageName());
    }

    private Intent createInstallPermissionIntent() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        intent.setData(Uri.parse("package:" + getContext().getPackageName()));
        return intent;
    }

    private boolean requestInstallPermission(PluginCall call, String callbackName) {
        if (canInstallUnknownApps()) return false;
        try {
            Intent intent = createInstallPermissionIntent();
            startActivityForResult(call, intent, callbackName);
        } catch (ActivityNotFoundException | SecurityException error) {
            call.reject("无法打开安装未知应用权限设置。", error);
        }
        return true;
    }

    private void launchApkInstaller(PluginCall call, File apkFile, String failureMessage) {
        try {
            Uri apkUri = FileProvider.getUriForFile(
                getContext(),
                getContext().getPackageName() + ".fileprovider",
                apkFile
            );
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.setClipData(ClipData.newRawUri("APK", apkUri));
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (getActivity() != null) {
                getActivity().startActivity(intent);
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
            }
            JSObject result = new JSObject();
            result.put("started", true);
            call.resolve(result);
        } catch (ActivityNotFoundException error) {
            call.reject(failureMessage + " 系统中没有可用的 APK 安装器。", error);
        } catch (SecurityException | IllegalArgumentException error) {
            call.reject(failureMessage + " 安装包权限或路径无效。", error);
        }
    }

    private void launchLauncherUpdateInstaller(PluginCall call, File apkFile) {
        try {
            Uri apkUri = FileProvider.getUriForFile(
                getContext(),
                getContext().getPackageName() + ".fileprovider",
                apkFile
            );
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.setClipData(ClipData.newRawUri("APK", apkUri));
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            startActivityForResult(call, intent, "launcherInstallResult");
        } catch (ActivityNotFoundException error) {
            call.reject("无法打开系统启动器更新界面。系统中没有可用的 APK 安装器。", error);
        } catch (SecurityException | IllegalArgumentException error) {
            call.reject("无法打开系统启动器更新界面。安装包权限或路径无效。", error);
        }
    }

    private static long packageVersionCode(PackageInfo packageInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return packageInfo.getLongVersionCode();
        }
        return packageInfo.versionCode;
    }

}
