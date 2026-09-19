package com.lingjing.launcher.android;

import java.io.File;

/** 解压完成、等待安装的 APK 与 OBB。 */
final class PreparedFiles {
    final File apk;
    final File obb;
    final String installToken;

    PreparedFiles(File apk, File obb, String installToken) {
        this.apk = apk;
        this.obb = obb;
        this.installToken = installToken;
    }
}
