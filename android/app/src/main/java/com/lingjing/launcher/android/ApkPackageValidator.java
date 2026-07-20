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
            throw new IOException("目标游戏 APK versionCode 低于当前安装器");
        }
        if (!sameSignatures(packageSignatures(installed), packageSignatures(candidate))) {
            throw new IOException("目标游戏 APK 签名不一致");
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
