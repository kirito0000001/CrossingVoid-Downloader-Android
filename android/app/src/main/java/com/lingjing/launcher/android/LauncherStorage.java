package com.lingjing.launcher.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * 启动器的本地存储位置：下载根目录与 SharedPreferences。
 *
 * 下载、导入导出和状态持久化都要用它们，所以单独放一处，
 * 不必让每个模块各自拼路径或各自持有偏好键名。
 */
final class LauncherStorage {
    static final String PREFS_NAME = "crossingvoid_download";
    static final String PREF_STATE = "state";
    static final String PREF_PLAN = "plan";
    static final String PREF_MANAGED_VERSION = "managedVersion";

    private LauncherStorage() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static File downloadsRoot(Context context) {
        File root = new File(context.getFilesDir(), "downloads");
        if (!root.exists()) {
            root.mkdirs();
        }
        return root;
    }
}
