package com.lingjing.launcher.android;

import android.content.Context;
import android.content.Intent;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 启动器更新状态的持久化与广播。
 *
 * 与游戏下载的状态分开存放：更新走自己的偏好键和广播动作，
 * 版本号、包名与签名都写在状态 JSON 里，供安装与断点恢复校验。
 */
final class UpdateStateStore {
    private static final Object STATE_LOCK = new Object();
    private static String lastLoggedStateSignature = "";

    private UpdateStateStore() {
    }

    static JSONObject readState(Context context) {
        synchronized (STATE_LOCK) {
            String json = context.getSharedPreferences(LauncherUpdateService.PREFS_NAME, Context.MODE_PRIVATE).getString(LauncherUpdateService.PREF_STATE, "");
            if (json == null || json.isBlank()) return idleState();
            try {
                return new JSONObject(json);
            } catch (JSONException ignored) {
                return errorState("启动器更新状态已损坏，请重新下载。");
            }
        }
    }

    static void publishState(Context context, JSONObject state) {
        String json = state.toString();
        String status = state.optString("status", "idle");
        String message = state.optString("message", status);
        String logSignature = status + "|" + message;
        boolean shouldLog;
        synchronized (STATE_LOCK) {
            shouldLog = !logSignature.equals(lastLoggedStateSignature);
            if (shouldLog) lastLoggedStateSignature = logSignature;
            context.getSharedPreferences(LauncherUpdateService.PREFS_NAME, Context.MODE_PRIVATE).edit().putString(LauncherUpdateService.PREF_STATE, json).apply();
        }
        if (shouldLog) {
            String level = status.equals("error") ? "error" : "info";
            try {
                LauncherLogStore.append(context, level, "launcher-update.state", message, json);
            } catch (Exception ignored) {
            }
        }
        Intent update = new Intent(LauncherUpdateService.ACTION_STATE).setPackage(context.getPackageName());
        update.putExtra(LauncherUpdateService.EXTRA_STATE, json);
        context.sendBroadcast(update);
    }

    static JSONObject idleState() {
        JSONObject state = new JSONObject();
        try {
            state.put("status", "idle");
            state.put("message", "启动器已是最新版本");
            state.put("downloadedBytes", 0L);
            state.put("totalBytes", 0L);
            state.put("percent", 0.0);
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

}
