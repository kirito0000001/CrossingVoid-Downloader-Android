import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const appSource = readFileSync(resolve(process.cwd(), "src/App.vue"), "utf8");
const nativeBridgeSource = readFileSync(resolve(process.cwd(), "src/services/androidLauncher.ts"), "utf8");
const downloadServiceSource = readFileSync(
  resolve(process.cwd(), "android/app/src/main/java/com/lingjing/launcher/android/GameDownloadService.java"),
  "utf8",
);
const launcherUpdateServiceSource = readFileSync(
  resolve(process.cwd(), "android/app/src/main/java/com/lingjing/launcher/android/LauncherUpdateService.java"),
  "utf8",
);
const manifestSource = readFileSync(resolve(process.cwd(), "android/app/src/main/AndroidManifest.xml"), "utf8");
const pluginSource = readFileSync(
  resolve(process.cwd(), "android/app/src/main/java/com/lingjing/launcher/android/AndroidLauncherPlugin.java"),
  "utf8",
);
const apkValidatorSource = readFileSync(
  resolve(process.cwd(), "android/app/src/main/java/com/lingjing/launcher/android/ApkPackageValidator.java"),
  "utf8",
);

describe("native Android game download integration", () => {
  it("uses the foreground native downloader instead of the simulated timer", () => {
    expect(appSource).toContain("buildAndroidDownloadPlan");
    expect(appSource).toContain("startGameDownload");
    expect(appSource).toContain("addDownloadProgressListener");
    expect(appSource).not.toContain("simulateDownload");
  });

  it("exposes start, pause, cancel and persisted state through the Capacitor bridge", () => {
    expect(nativeBridgeSource).toContain("startDownload(options");
    expect(nativeBridgeSource).toContain("pauseDownload()");
    expect(nativeBridgeSource).toContain("cancelDownload()");
    expect(nativeBridgeSource).toContain("getDownloadState()");
    expect(nativeBridgeSource).toContain('addListener("downloadProgress"');
  });

  it("uses explicit transition states so resume and cancel cannot race the old worker", () => {
    expect(downloadServiceSource).toContain('"pausing"');
    expect(downloadServiceSource).toContain('"cancelling"');
    expect(downloadServiceSource).toContain("compareAndSet(false, true)");
    expect(downloadServiceSource).toContain("finishWorker");
    expect(appSource).toContain("waitForNativeDownloadStatus");
  });

  it("imports one selected folder recursively and accepts only canonical chunk names", () => {
    expect(pluginSource).toContain("Intent.ACTION_OPEN_DOCUMENT_TREE");
    expect(downloadServiceSource).toContain("DocumentFile.fromTreeUri");
    expect(downloadServiceSource).toContain("CrossingVoid手机端.碎片");
    expect(downloadServiceSource).not.toContain("EXTRA_IMPORT_URIS");
  });

  it("redelivers active foreground tasks after Android reclaims the process", () => {
    expect(downloadServiceSource).toContain("return START_REDELIVER_INTENT;");
    expect(launcherUpdateServiceSource).toContain("return START_REDELIVER_INTENT;");
    expect(downloadServiceSource).toContain("getString(PREF_PLAN");
    expect(launcherUpdateServiceSource).toContain("getString(PREF_PLAN");
  });

  it("shows install and background-download permission states in settings", () => {
    expect(manifestSource).toContain("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");
    expect(nativeBridgeSource).toContain("getLauncherPermissionStatus()");
    expect(nativeBridgeSource).toContain("openBatteryOptimizationSettings()");
    expect(pluginSource).toContain("isIgnoringBatteryOptimizations");
    expect(pluginSource).toContain("Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");
    expect(appSource).toContain("安装应用权限");
    expect(appSource).toContain("后台下载权限");
    expect(appSource).toContain("refreshLauncherPermissionStatus");
  });

  it("checks proxy and Github latency when Github is selected without blocking downloads", () => {
    expect(nativeBridgeSource).toContain("getGithubNetworkStatus()");
    expect(pluginSource).toContain("getGithubNetworkStatus(PluginCall call)");
    expect(pluginSource).toContain("getDefaultProxy");
    expect(pluginSource).toContain("https://github.com/");
    expect(appSource).toContain("refreshGithubNetworkStatus");
    expect(appSource).toContain('if (source === "github") void refreshGithubNetworkStatus()');
    expect(appSource).toContain("githubNetworkWarning");
    expect(appSource).toContain("github-network-warning");
    expect(appSource).not.toContain("ensureGithubNetworkAvailable");
  });

  it("reports transfer speed so the launcher can estimate remaining time", () => {
    expect(downloadServiceSource).toContain('state.put("bytesPerSecond"');
    expect(nativeBridgeSource).toContain("bytesPerSecond?: number");
    expect(appSource).toContain("downloadSpeedBytes");
    expect(appSource).toContain("formatEtaClock");
  });

  it("identifies the exact launcher version when requesting an OSS signed URL", () => {
    expect(downloadServiceSource).toContain('request.put("launcherVersion"');
    expect(downloadServiceSource).toContain("currentLauncherVersion");
    expect(downloadServiceSource).not.toContain('CrossingVoidAndroidLauncher/1.0"');
  });

  it("keeps the foreground notification to fixed text and a progress bar", () => {
    expect(downloadServiceSource).toContain('.setContentText("正在（下载）链接空界幻境中...")');
    expect(downloadServiceSource).toContain(".setProgress(100");
    expect(downloadServiceSource).not.toContain("builder.addAction");
  });

  it("keeps download cleanup in settings and installs the game in one replacement step", () => {
    expect(appSource).toContain('type GameManagementAction = "cancelDownload" | "deletePackage" | "clearDownload"');
    expect(appSource).toContain("gameManagementAction");
    expect(appSource).toContain("删除安装包");
    expect(appSource).toContain("清除下载文件");
    expect(appSource).toContain('reportInstallTrace("install-action-start", "apk")');
    expect(appSource).toContain("await installDownloadedApk()");
    expect(appSource).toContain("请在系统安装界面确认安装游戏");
    expect(appSource).not.toContain("needsPreparedApkInstall");
    expect(appSource).not.toContain("pendingGameApkInstall");
    expect(appSource).not.toContain("installPreparedObb");
    expect(nativeBridgeSource).not.toContain("installPreparedObb");
    expect(pluginSource).not.toContain("installPreparedObb");
    expect(appSource).not.toContain('@click="cancelCurrentDownload"');
    expect(nativeBridgeSource).not.toContain("uninstallAndroidGame");
    expect(pluginSource).not.toContain("Intent.ACTION_DELETE");
  });

  it("validates the prepared game APK before replacing the disposable installer", () => {
    expect(pluginSource).toContain("ApkPackageValidator.validateReplacement");
    expect(apkValidatorSource).toContain("目标游戏 APK 包名不正确");
    expect(apkValidatorSource).toContain("目标游戏 APK 签名不一致");
    expect(apkValidatorSource).toContain("目标游戏 APK versionCode 低于当前安装器");
  });

  it("records native status errors and refresh failures", () => {
    expect(appSource).toContain('reportFailure("native-download-state", new Error(state.message || "原生下载服务失败"))');
    expect(appSource).toContain('reportFailure("refresh-game-status", error)');
  });

  it("records the game install lifecycle even when native calls do not throw", () => {
    expect(appSource).toContain('reportInstallTrace("install-action-start"');
    expect(appSource).toContain('reportInstallTrace("install-native-started"');
    expect(appSource).toContain('reportInstallTrace("install-return"');
    expect(appSource).not.toContain("flushAndroidDiagnostics");
  });

  it("keeps each settings sidebar entry aligned with one ordered content group", () => {
    const preferences = appSource.indexOf('data-settings-section="preferences"');
    const download = appSource.indexOf('data-settings-section="download"');
    const game = appSource.indexOf('data-settings-section="game"');
    const about = appSource.indexOf('data-settings-section="about"');

    expect(preferences).toBeGreaterThan(-1);
    expect(preferences).toBeLessThan(download);
    expect(download).toBeLessThan(game);
    expect(game).toBeLessThan(about);
    expect(appSource).not.toContain('if (section === "preferences")');
  });
});
