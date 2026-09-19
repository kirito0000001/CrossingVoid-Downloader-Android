package com.lingjing.launcher.android;

import android.content.Context;
import android.content.Intent;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * 下载状态的持久化与广播。
 *
 * 状态 JSON 是 App 与原生层之间的唯一约定：写状态时整份落盘并广播，
 * 读状态缺失时返回空闲态，绝不把异常抛给调用方。
 */
final class DownloadStateStore {
    private static final Object STATE_LOCK = new Object();

    private DownloadStateStore() {
    }

    static void writeStateAndBroadcast(Context context, JSONObject state) {
        String json = state.toString();
        synchronized (STATE_LOCK) {
            LauncherStorage.prefs(context).edit().putString(LauncherStorage.PREF_STATE, json).apply();
        }
        Intent update = new Intent(GameDownloadService.ACTION_STATE);
        update.setPackage(context.getPackageName());
        update.putExtra(GameDownloadService.EXTRA_STATE, json);
        context.sendBroadcast(update);
    }

    public static JSONObject readStateObject(Context context) {
        synchronized (STATE_LOCK) {
            String json = LauncherStorage.prefs(context).getString(LauncherStorage.PREF_STATE, "");
            if (json == null || json.isBlank()) {
                return idleState();
            }
            try {
                return new JSONObject(json);
            } catch (JSONException ignored) {
                return errorState("下载状态文件已损坏，请重新开始下载。");
            }
        }
    }

    public static String getManagedVersion(Context context) {
        return LauncherStorage.prefs(context).getString(LauncherStorage.PREF_MANAGED_VERSION, "");
    }

    public static void clearManagedVersion(Context context) {
        LauncherStorage.prefs(context).edit().remove(LauncherStorage.PREF_MANAGED_VERSION).apply();
    }

    public static boolean completeInstallation(Context context, String installToken) {
        JSONObject state = readStateObject(context);
        if (installToken == null || !installToken.equals(state.optString("installToken"))) {
            return false;
        }
        String version = state.optString("version", "");
        DownloadFileUtils.deleteRecursively(new File(LauncherStorage.downloadsRoot(context), "prepared"));
        LauncherStorage.prefs(context).edit()
            .remove(LauncherStorage.PREF_STATE)
            .remove(LauncherStorage.PREF_PLAN)
            .putString(LauncherStorage.PREF_MANAGED_VERSION, version)
            .apply();
        JSONObject completed = idleState();
        try {
            completed.put("message", "游戏资源安装完成");
        } catch (JSONException ignored) {
        }
        writeStateAndBroadcast(context, completed);
        return true;
    }

    public static boolean failInstallation(Context context, String installToken, String failureMessage) {
        JSONObject state = readStateObject(context);
        if (installToken == null || !installToken.equals(state.optString("installToken"))) {
            return false;
        }
        try {
            state.put("status", "error");
            state.put("message", failureMessage == null || failureMessage.isBlank()
                ? "游戏资源安装失败。"
                : failureMessage);
            state.put("canPause", false);
            state.put("updatedAt", System.currentTimeMillis());
        } catch (JSONException error) {
            return false;
        }
        writeStateAndBroadcast(context, state);
        return true;
    }

    public static void clearAllDownloads(Context context) {
        DownloadFileUtils.deleteRecursively(LauncherStorage.downloadsRoot(context));
        LauncherStorage.prefs(context).edit()
            .remove(LauncherStorage.PREF_STATE)
            .remove(LauncherStorage.PREF_PLAN)
            .apply();
    }

    static JSONObject idleState() {
        JSONObject state = new JSONObject();
        try {
            state.put("status", "idle");
            state.put("message", "等待下载");
            state.put("downloadedBytes", 0L);
            state.put("totalBytes", 0L);
            state.put("percent", 0.0);
            state.put("currentChunk", 0);
            state.put("totalChunks", 0);
            state.put("verifiedChunks", 0);
            state.put("canPause", false);
        } catch (JSONException ignored) {
        }
        return state;
    }

    static JSONObject errorState(String message) {
        JSONObject state = idleState();
        try {
            state.put("status", "error");
            state.put("message", message);
        } catch (JSONException ignored) {
        }
        return state;
    }

    static void copyPreparedStateFields(JSONObject target, JSONObject source) throws JSONException {
        for (String key : new String[] { "apkPath", "obbPath", "obbFileName", "installToken" }) {
            if (source.has(key)) target.put(key, source.optString(key));
        }
    }

}
