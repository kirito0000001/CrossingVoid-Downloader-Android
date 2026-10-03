import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

import { nativeSource } from "./helpers/androidNativeSources";

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
    // 清单 v1：前端用共用内核算出差异计划，再交给同一个前台原生服务下载。
    expect(appSource).toContain("fetchAndroidGamePackage");
    expect(appSource).toContain("buildAndroidGameDownloadPlan");
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
    expect(nativeSource).toContain("CrossingVoid手机端.碎片");
    expect(downloadServiceSource).not.toContain("EXTRA_IMPORT_URIS");
  });

  it("exports verified chunks through the foreground service without mutating source files", () => {
    expect(nativeBridgeSource).toContain("exportGameChunks()");
    expect(pluginSource).toContain("exportGameChunks(PluginCall call)");
    expect(pluginSource).toContain("Intent.FLAG_GRANT_WRITE_URI_PERMISSION");
    expect(pluginSource).toContain("Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION");
    expect(pluginSource).toContain("takePersistableUriPermission");
    expect(downloadServiceSource).toContain("ACTION_EXPORT");
    expect(downloadServiceSource).toContain("EXTRA_EXPORT_TREE_URI");
    expect(downloadServiceSource).toContain("getString(LauncherStorage.PREF_PLAN");
    expect(nativeSource).toContain('publishState("exporting"');
    expect(nativeSource).toContain(".exporting");
    expect(nativeSource).toContain("sha256(tempFile)");
    expect(nativeSource).toContain("source.length() != chunk.sizeBytes");
    expect(nativeSource).toContain("RECOVERY_MANIFEST_FILE");
    expect(nativeSource).toContain("exportPreparedRecoveryFiles");
    expect(nativeSource).toContain("importPreparedRecoveryFiles");
    expect(nativeSource).toContain('previousState.optString("apkPath"');
    expect(nativeSource).toContain('previousState.optString("obbPath"');
    expect(nativeSource).toContain("copyPreparedStateFields");
    expect(nativeSource).toContain('status.equals("exporting")');
    expect(nativeSource).toContain('"error".equals(previousState.optString("status"))');
    expect(downloadServiceSource).not.toContain("source.delete()");
    expect(downloadServiceSource).not.toContain("source.renameTo(");
  });

  it("redelivers active foreground tasks after Android reclaims the process", () => {
    expect(downloadServiceSource).toContain("return START_REDELIVER_INTENT;");
    expect(launcherUpdateServiceSource).toContain("return START_REDELIVER_INTENT;");
    expect(downloadServiceSource).toContain("getString(LauncherStorage.PREF_PLAN");
    expect(launcherUpdateServiceSource).toContain("getString(PREF_PLAN");
  });

  it("shows install and background-download permission states in settings", () => {
    expect(manifestSource).toContain("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");
    expect(nativeBridgeSource).toContain("getLauncherPermissionStatus()");
    expect(nativeBridgeSource).toContain("openBatteryOptimizationSettings()");
    expect(nativeSource).toContain("isIgnoringBatteryOptimizations");
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
    expect(nativeSource).toContain('request.put("launcherVersion"');
    expect(downloadServiceSource).toContain("currentLauncherVersion");
    expect(downloadServiceSource).not.toContain('CrossingVoidAndroidLauncher/1.0"');
  });

  it("keeps the foreground notification to fixed text and a progress bar", () => {
    expect(nativeSource).toContain('.setContentText("正在（下载）链接空界幻境中...")');
    expect(nativeSource).toContain(".setProgress(100");
    expect(nativeSource).not.toContain("builder.addAction");
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
    expect(apkValidatorSource).toContain("目标游戏 APK 的版本号低于手机上的零境启动器");
    // 光说"签名不一致"玩家没法行动：启动器和游戏同包名，唯一的出路是先卸载再重装。
    expect(apkValidatorSource).toContain("请先卸载已安装的零境启动器");
  });

  it("keeps a half-downloaded file so the next run resumes instead of restarting", () => {
    // 2026-10-03 设备 769993cb 现场：pakchunk0-Android.ucas（652 MB）下到 1.296 GB 时
    // 连接中断，用户点「重新检测 → 下载游戏」后进度回落到 876 MB 重新开始 ——
    // 因为这里在重试循环之前把整份半成品删了，PackageFileDownloader 的 Range 续传永远用不上。
    const loopSource = downloadServiceSource.slice(
      downloadServiceSource.indexOf("private void downloadPackageFiles"),
      downloadServiceSource.indexOf("private PreparedFiles preparePackageArtifacts"),
    );

    // 删文件只允许发生在「长度已经够了却没通过校验」这一种情况，而且必须在续传判断之后。
    const guardAt = loopSource.indexOf("target.length() >= entry.sizeBytes");
    const deleteAt = loopSource.indexOf("DownloadFileUtils.deleteFile(target);");
    expect(guardAt).toBeGreaterThan(-1);
    expect(deleteAt).toBeGreaterThan(guardAt);

    // 单次连接被掐断是常态，重试次数要多给几次，否则一次 run 撑不到文件传完。
    expect(downloadServiceSource).toContain("private static final int PACKAGE_MAX_ATTEMPTS = 6;");
    expect(loopSource).toContain("attempt <= PACKAGE_MAX_ATTEMPTS");
    expect(loopSource).toContain('"连接中断，正在重试 " + (attempt + 1) + " / " + PACKAGE_MAX_ATTEMPTS');
  });

  it("ignores a second tap while a long native action is still running", () => {
    // 2026-10-01 设备 e033416a 连报 4 次「上一项游戏下载任务正在结束，请稍后再试。」：
    // 两个原生前台任务撞在一起，所以从 UI 侧先拦一道。
    expect(appSource).toContain("async function runExclusiveAction(label: string, action: () => Promise<void>)");
    expect(appSource).toContain("if (pendingAction.value) return;");
    expect(appSource).toContain("Boolean(pendingAction.value)");
    expect(appSource).toContain(`@click="runExclusiveAction('primary', handlePrimaryAction)"`);
    expect(appSource).toContain(`@click="runExclusiveAction('import', importGameChunksFromDevice)"`);
    expect(appSource).toContain(`@click="runExclusiveAction('export', exportGameChunksFromDevice)"`);
  });

  it("keeps the game fragment export switched off", () => {
    // 2026-10-03 设备 67974c7e：原生 runExport 用老分片清单解析逐文件清单，一点就抛
    // org.json.JSONException: No value for archiveFileName。短期不走这条路，先把入口关掉。
    expect(appSource).toContain("const exportGameChunksEnabled: boolean = false;");
    expect(appSource).toContain("exportGameChunksEnabled &&");
    // 按钮还在，只是被 canExportGameChunks 关掉；恢复只需把这一个开关改成 true。
    expect(appSource).toContain(`@click="runExclusiveAction('export', exportGameChunksFromDevice)"`);
  });

  it("never gates a download on the notification permission", () => {
    // 2026-09-27 现场：通知权限被拒时 startDownload 直接 reject
    // 「需要通知权限才能在后台显示游戏下载进度。」—— 一条纯提示性的权限把下载整个挡住。
    // 权限在 MainActivity.onCreate 已经问过一次，这里只负责闭嘴照跑。
    expect(pluginSource).not.toContain("requestPermissionForAlias");
    expect(pluginSource).not.toContain("NotificationPermissionResult");
    expect(pluginSource).not.toContain('call.reject("需要通知权限');

    // 提醒画不出来也不该掀掉服务：notify/startForeground 一律吞掉异常。
    expect(nativeSource).toContain("catch (SecurityException ignored)");
    expect(nativeSource).toContain("catch (RuntimeException ignored)");
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
