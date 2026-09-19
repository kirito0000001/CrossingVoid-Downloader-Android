package com.lingjing.launcher.android;

import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.os.Build;

import java.util.Locale;
import java.util.Arrays;

/**
 * 启动器更新包的校验：版本码、签名与版本号比较。
 *
 * 全是纯函数，不碰服务状态，因此可以脱离 Service 与模拟器测试。
 */
final class LauncherUpdateVerifier {
    private LauncherUpdateVerifier() {
    }

    static long packageVersionCode(PackageInfo packageInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return packageInfo.getLongVersionCode();
        return packageInfo.versionCode;
    }

    static Signature[] packageSignatures(PackageInfo packageInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (packageInfo.signingInfo == null) return new Signature[0];
            return packageInfo.signingInfo.hasMultipleSigners()
                ? packageInfo.signingInfo.getApkContentsSigners()
                : packageInfo.signingInfo.getSigningCertificateHistory();
        }
        return packageInfo.signatures == null ? new Signature[0] : packageInfo.signatures;
    }

    static boolean sameSignatures(Signature[] left, Signature[] right) {
        if (left.length != right.length) return false;
        String[] leftValues = new String[left.length];
        String[] rightValues = new String[right.length];
        for (int index = 0; index < left.length; index++) leftValues[index] = left[index].toCharsString();
        for (int index = 0; index < right.length; index++) rightValues[index] = right[index].toCharsString();
        Arrays.sort(leftValues);
        Arrays.sort(rightValues);
        return Arrays.equals(leftValues, rightValues);
    }

    static int compareVersionNames(String left, String right) {
        String[] leftParts = (left == null ? "" : left).split("\\.");
        String[] rightParts = (right == null ? "" : right).split("\\.");
        int count = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < count; index++) {
            int leftValue = index < leftParts.length ? parseVersionPart(leftParts[index]) : 0;
            int rightValue = index < rightParts.length ? parseVersionPart(rightParts[index]) : 0;
            if (leftValue != rightValue) return Integer.compare(leftValue, rightValue);
        }
        return 0;
    }

    static int parseVersionPart(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

}
