import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const appSource = readFileSync(resolve(process.cwd(), "src/App.vue"), "utf8");
const bridgeSource = readFileSync(resolve(process.cwd(), "src/services/androidLauncher.ts"), "utf8");
const manifestSource = readFileSync(resolve(process.cwd(), "android/app/src/main/AndroidManifest.xml"), "utf8");
const fileProviderPathsSource = readFileSync(
  resolve(process.cwd(), "android/app/src/main/res/xml/file_paths.xml"),
  "utf8",
);
const updateServiceSource = readFileSync(
  resolve(process.cwd(), "android/app/src/main/java/com/lingjing/launcher/android/LauncherUpdateService.java"),
  "utf8",
);
const replacedReceiverSource = readFileSync(
  resolve(process.cwd(), "android/app/src/main/java/com/lingjing/launcher/android/LauncherReplacedReceiver.java"),
  "utf8",
);
const pluginSource = readFileSync(
  resolve(process.cwd(), "android/app/src/main/java/com/lingjing/launcher/android/AndroidLauncherPlugin.java"),
  "utf8",
);

describe("Android launcher hot update integration", () => {
  it("checks launcher updates before game updates", () => {
    expect(appSource).toContain("checkLauncherUpdate");
    expect(appSource).toContain("refreshGameStatus");
    expect(appSource.indexOf("checkLauncherUpdate")).toBeLessThan(appSource.indexOf("refreshGameStatus"));
  });

  it("keeps launcher update detection and actions visible in the UI", () => {
    expect(appSource).toContain("launcherUpdateStatusText");
    expect(appSource).toContain("handleLauncherUpdateCheck");
    expect(appSource).toContain("检查启动器更新");
    expect(appSource).toContain("launcherUpdateButtonText");
  });

  it("maps launcher actions to the same semantic icons as the PC launcher", () => {
    expect(appSource).toContain("const actionIcon");
    expect(appSource).toContain("HardDriveDownload");
    expect(appSource).toContain("PackageOpen");
    expect(appSource).toContain("Gamepad2");
    expect(appSource).toContain("return Pause");
    expect(appSource).toContain("return Download");
  });

  it("exposes native download state and system update installation", () => {
    expect(bridgeSource).toContain("startLauncherUpdate");
    expect(bridgeSource).toContain("getLauncherUpdateState");
    expect(bridgeSource).toContain("installLauncherUpdate");
    expect(bridgeSource).toContain('addListener("launcherUpdateProgress"');
  });

  it("registers an independent foreground update service", () => {
    expect(manifestSource).toContain("LauncherUpdateService");
    expect(manifestSource).toContain("foregroundServiceType=\"dataSync\"");
    expect(manifestSource).toContain("screenOrientation=\"landscape\"");
  });

  it("rejects an APK with the wrong package, versionCode or signing certificate", () => {
    expect(updateServiceSource).toContain("validateDownloadedApk");
    expect(updateServiceSource).toContain("启动器安装包包名不正确");
    expect(updateServiceSource).toContain("启动器安装包 versionCode 不正确");
    expect(updateServiceSource).toContain("启动器安装包签名不一致");
  });

  it("requests unknown-app permission and resumes launcher installation", () => {
    expect(bridgeSource).toContain("openInstallPermissionSettings");
    expect(pluginSource).toContain("startActivityForResult(call, intent, callbackName)");
    expect(pluginSource).toContain('requestInstallPermission(call, "launcherInstallPermissionResult")');
    expect(pluginSource).toContain("@ActivityCallback");
    expect(pluginSource).toContain("launcherInstallPermissionResult");
    expect(pluginSource).toContain("application/vnd.android.package-archive");
    expect(pluginSource).toContain("Intent.ACTION_INSTALL_PACKAGE");
    expect(pluginSource).toContain("SecurityException | IllegalArgumentException");
    expect(fileProviderPathsSource).toContain('path="launcher-update/"');
  });

  it("offers a notification entry point after the launcher replaces itself", () => {
    expect(manifestSource).toContain("LauncherReplacedReceiver");
    expect(manifestSource).toContain("android.intent.action.MY_PACKAGE_REPLACED");
    expect(replacedReceiverSource).toContain("点击打开零境启动器");
    expect(replacedReceiverSource).toContain("getLaunchIntentForPackage");
    expect(replacedReceiverSource).toContain("NotificationManager.IMPORTANCE_HIGH");
    expect(replacedReceiverSource).toContain("NotificationCompat.PRIORITY_HIGH");
    expect(pluginSource).toContain("Intent.EXTRA_RETURN_RESULT");
    expect(pluginSource).toContain("launcherInstallResult");
    expect(pluginSource).toContain("Activity.RESULT_OK");
    expect(pluginSource).toContain("startActivity(launchIntent)");
  });

  it("keeps equal-versionCode update state until the semantic launcher version is installed", () => {
    expect(updateServiceSource).toContain(
      "clearIfInstalled(Context context, long currentVersionCode, String currentVersionName)",
    );
    expect(updateServiceSource).toContain("compareVersionNames(currentVersionName, targetVersionName)");
    expect(pluginSource).toContain(
      "clearIfInstalled(getContext(), packageVersionCode(packageInfo), packageInfo.versionName)",
    );
  });

  it("keeps launcher updates strictly same-package", () => {
    expect(updateServiceSource).toContain("getPackageName().equals(candidate.packageName)");
    expect(updateServiceSource).toContain("sameSignatures(installedSignatures, candidateSignatures)");
    expect(updateServiceSource).not.toMatch(/Migration|MIGRATION|migration/);
    expect(updateServiceSource).not.toContain("targetPackageName");
    expect(pluginSource).not.toContain("completedMigrationVersion");
  });
});
