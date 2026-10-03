package com.lingjing.launcher.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

final class ApkPackageValidator {
    private ApkPackageValidator() {
    }

    static PackageInfo validateReplacement(Context context, File apk, String expectedPackage) throws Exception {
        PackageManager packageManager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? PackageManager.GET_SIGNING_CERTIFICATES
            : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = packageManager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (candidate == null || !expectedPackage.equals(candidate.packageName)) {
            throw new IOException("目标游戏 APK 包名不正确");
        }

        PackageInfo installed = packageManager.getPackageInfo(context.getPackageName(), flags);
        if (packageVersionCode(candidate) < packageVersionCode(installed)) {
            throw new IOException("目标游戏 APK 的版本号低于手机上的零境启动器，无法覆盖安装。"
                + "请先卸载已安装的零境启动器，再重新下载安装游戏。");
        }
        if (!sameSignatures(packageSignatures(installed), packageSignatures(candidate))) {
            // 启动器和游戏同包名，装游戏 APK 就是覆盖启动器自己，所以签名必须完全一致。
            // 手机上的启动器多半是测试版或被第三方改过包，Android 不允许跨签名覆盖安装。
            throw new IOException("目标游戏 APK 签名不一致：手机上的零境启动器与本次下载的游戏包不是同一个签名。"
                + "Android 不允许跨签名覆盖安装，请先卸载已安装的零境启动器（游戏内数据会一并删除），再重新下载安装游戏。");
        }
        return candidate;
    }

    private static long packageVersionCode(PackageInfo packageInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return packageInfo.getLongVersionCode();
        return packageInfo.versionCode;
    }

    private static Signature[] packageSignatures(PackageInfo packageInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (packageInfo.signingInfo == null) return new Signature[0];
            return packageInfo.signingInfo.hasMultipleSigners()
                ? packageInfo.signingInfo.getApkContentsSigners()
                : packageInfo.signingInfo.getSigningCertificateHistory();
        }
        return packageInfo.signatures == null ? new Signature[0] : packageInfo.signatures;
    }

    private static boolean sameSignatures(Signature[] left, Signature[] right) {
        if (left.length == 0 || left.length != right.length) return false;
        String[] leftValues = new String[left.length];
        String[] rightValues = new String[right.length];
        for (int index = 0; index < left.length; index++) leftValues[index] = left[index].toCharsString();
        for (int index = 0; index < right.length; index++) rightValues[index] = right[index].toCharsString();
        Arrays.sort(leftValues);
        Arrays.sort(rightValues);
        return Arrays.equals(leftValues, rightValues);
    }
}
