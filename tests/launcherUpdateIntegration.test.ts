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
// 更新服务的下载与校验已拆到独立类，两个文件的源码一起断言，
// 这样职责再搬家也不会误报。
const updateServiceSource = [
  readFileSync(
    resolve(process.cwd(), "android/app/src/main/java/com/lingjing/launcher/android/LauncherUpdateService.java"),
    "utf8",
  ),
  readFileSync(
    resolve(process.cwd(), "android/app/src/main/java/com/lingjing/launcher/android/LauncherUpdateDownloader.java"),
    "utf8",
  ),
].join("\n");
const replacedReceiverSource = readFileSync(
  resolve(process.cwd(), "android/app/src/main/java/com/lingjing/launcher/android/LauncherReplacedReceiver.java"),
  "utf8",
);
const pluginSource = readFileSync(
  resolve(process.cwd(), "android/app/src/main/java/com/lingjing/launcher/android/AndroidLauncherPlugin.java"),
  "utf8",
);

/**
 * 断言"这段代码里没有旧写法"时必须先剥掉注释：说明性注释常常会把旧代码原样抄一遍
 * 作对照，直接 not.toContain 会被自己的注释绊倒。
 */
function stripComments(source: string) {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
}

describe("Android launcher hot update integration", () => {
  it("checks launcher updates before game updates", () => {
    expect(appSource).toContain("checkLauncherUpdate");
    expect(appSource).toContain("refreshGameStatus");
    expect(appSource.indexOf("checkLauncherUpdate")).toBeLessThan(appSource.indexOf("refreshGameStatus"));
  });

  it("keeps mandatory launcher updates on the main screen without a blocking overlay", () => {
    const launcherCheckSource = appSource.slice(
      appSource.indexOf("async function checkLauncherUpdate"),
      appSource.indexOf("async function refreshGameStatus"),
    );

    expect(appSource).toContain("const launcherNetworkLocked = computed");
    expect(appSource).toContain("if (launcherNetworkLocked.value &&");
    expect(appSource).not.toContain('class="launcher-update-mask"');
    expect(appSource).not.toContain('class="launcher-update-panel"');
    expect(appSource).toContain('case "launcherUpdateReady"');
    expect(appSource).toContain('case "launcherUpdateInstall"');
    expect(appSource).toContain('if (phase.value === "launcherUpdateReady")');
    expect(appSource).toContain('if (phase.value === "launcherUpdateInstall")');
    expect(launcherCheckSource).toContain('phase.value = "error"');
  });

  it("keeps a local game chunk import entry for offline distribution", () => {
    expect(appSource).toContain("导入游戏碎片");
    expect(appSource).toContain("importGameChunksFromDevice");
    // 导入用的清单和下载是同一个构造（原生侧靠它对到一样的落点），
    // 不再是那句"适配中"的挡板。细节在 gameFragmentImport.test.ts 里守。
    expect(appSource).toContain("await importGameChunks(plan)");
    expect(bridgeSource).toContain("importGameChunks");
  });

  it("exports complete verified chunks and shows export progress in the global dock", () => {
    expect(bridgeSource).toContain("exportGameChunks");
    expect(appSource).toContain("导出已下载碎片");
    expect(appSource).toContain("exportGameChunksFromDevice");
    expect(appSource).toContain("canExportGameChunks");
    expect(appSource).toContain("verifiedChunks.value === totalChunks.value");
    expect(appSource).toContain('case "exporting"');
    const progressSource = appSource.slice(
      appSource.indexOf("const showGlobalProgress"),
      appSource.indexOf("const progressAnimating"),
    );
    expect(progressSource).toContain('"exporting"');
  });

  it("rechecks the latest launcher before every network game download", () => {
    const downloadSource = appSource.slice(
      appSource.indexOf("async function beginRealDownload"),
      appSource.indexOf("async function importGameChunksFromDevice"),
    );

    expect(appSource).toContain("async function ensureLatestLauncherForNetworkDownload");
    expect(downloadSource).toContain("await ensureLatestLauncherForNetworkDownload()");
  });

  it("does not apply the launcher update lock to local chunk import", () => {
    const importSource = appSource.slice(
      appSource.indexOf("async function importGameChunksFromDevice"),
      appSource.indexOf("watch(downloadSource"),
    );
    const importButton = appSource.match(/<button[^>]+@click="importGameChunksFromDevice"[^>]*>/)?.[0] ?? "";

    expect(importSource).not.toContain("launcherAccessLocked");
    expect(importButton).not.toContain("launcherAccessLocked");
  });

  it("uses the global progress dock while downloading a launcher update", () => {
    const progressSource = appSource.slice(
      appSource.indexOf("const showGlobalProgress"),
      appSource.indexOf("const progressAnimating"),
    );

    expect(progressSource).toContain('"launcherUpdating"');
    expect(appSource).toContain('v-if="showGlobalProgress"');
    expect(appSource).toContain("{{ Math.round(progress) }}%");
  });

  it("does not let game download broadcasts dismiss launcher update phases", () => {
    const nativeStateHandler = appSource.slice(
      appSource.indexOf("function applyNativeState"),
      appSource.indexOf("function applyLauncherUpdateState"),
    );

    expect(nativeStateHandler).toContain("const preserveLauncherUpdatePhase = isLauncherUpdatePhase.value");
    expect(nativeStateHandler).toContain("if (!preserveLauncherUpdatePhase)");
  });

  it("never restores an error phase after an inconclusive launcher check", () => {
    const checkSource = appSource.slice(
      appSource.indexOf("async function handleLauncherUpdateCheck"),
      appSource.indexOf("async function handlePrimaryAction"),
    );

    // 2026-09-27 现场：checkLauncherUpdate 返回 false 时把快照原样还原，
    // 而快照里的 phase 就是 `error` —— 于是「检查更新」一按就 launcherChecking -> error，
    // 界面卡死在 error 里点什么都出不来，只有主按钮的「重新检测」能逃出去。
    expect(checkSource).toContain('if (previous.phase === "error")');
    expect(checkSource).toContain("await refreshGameStatus();");
    expect(checkSource).toContain("phase.value = previous.phase;");
  });

  it("never pins the launcher behind a failed update check", () => {
    const checkSource = appSource.slice(
      appSource.indexOf("async function checkLauncherUpdate"),
      appSource.indexOf("async function refreshGameStatus"),
    );
    const refreshAllSource = appSource.slice(
      appSource.indexOf("async function refreshAllStatus"),
      appSource.indexOf("async function refreshTrafficQuota"),
    );
    const lockedSource = appSource.slice(
      appSource.indexOf("const launcherNetworkLocked = computed"),
      appSource.indexOf("const trafficQuota = ref<TrafficQuotaStatus | null>(null)"),
    );

    // 2026-09-30 现场：www.crossingvoid.top 的证书过期，启动器更新清单取不回来。
    // 旧版 refreshAllStatus 写着 `|| launcherUpdateCheckError.value`，检查一失败就
    // 直接 return，refreshGameStatus() 永不执行 -> phase 钉死在 "error" -> 主按钮
    // 永远是「重新检测」，点一次失败一次，玩家只能杀进程。失败是"不知道"，不是
    // "必须升级"，不该拦住游戏状态刷新。
    expect(stripComments(refreshAllSource)).not.toContain("launcherUpdateCheckError");
    expect(refreshAllSource).toContain("if (await checkLauncherUpdate()) return;");
    expect(refreshAllSource).toContain("await refreshGameStatus();");
    // 同一个现场的另一半：闸门本身不能再由"检查失败"驱动，否则主按钮即使回到
    // 「下载游戏」也点不动，日志上传按钮也一直灰着。
    expect(stripComments(lockedSource)).not.toContain("launcherUpdateCheckError.value");
    // 还有原生那一半：checkLauncherUpdate 开头把上传闸门关掉，失败路径必须还原，
    // 否则 LauncherLogStore.upload 直接抛「当前启动器版本尚未通过验证，不能上传日志。」
    expect(checkSource).toContain("await setLauncherNetworkAccess(true);");
  });

  it("keeps the local download state when the game manifest cannot be fetched", () => {
    const source = stripComments(
      appSource.slice(
        appSource.indexOf("async function refreshGameStatus"),
        appSource.indexOf("async function refreshAllStatus"),
      ),
    );

    // 2026-09-30 同一现场的另一半：本机状态和远端清单原本塞在同一个 Promise.all 里，
    // 清单一失败整个 Promise.all reject，「原生非 idle 就用本地状态」的分支永远到不了 ——
    // 设备上明明有已下完待安装、或暂停中的任务，玩家却只能看到「重新检测」。
    expect(source).toContain("const [installedGame, nativeDownload] = await Promise.all([");
    expect(source.indexOf("getGameDownloadState()")).toBeLessThan(source.indexOf("fetchAndroidGamePackage("));
    // 只有本机确实没有任何下载任务时，清单取不回来才该中断
    expect(source).toContain('if (nativeDownload.status === "idle") throw manifestError;');
    expect(source).toContain('console.warn("Game manifest unavailable; keeping the local download state", manifestError);');
    // 本地状态照旧应用；只有拿到清单时才做版本对比
    expect(source).toContain("applyNativeState(nativeDownload);");
    expect(source).toContain("if (latestUpdate && nativeDownload.version && nativeDownload.version !== latestUpdate.version)");
    // 原来的失败上报路径保持不变
    expect(source).toContain('reportFailure("refresh-game-status", error)');
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
    // 这条广播正好在"启动器刚被覆盖安装完"那一刻派发，而 BroadcastReceiver 里漏出去的异常
    // 会直接崩掉进程 —— 现场就是"更新完启动器闪退"。所以整段要包在 try/catch 里，
    // NotificationManager 也要判空（拿不到时宁可不发提示）。
    expect(replacedReceiverSource).toContain("} catch (Exception error) {");
    expect(replacedReceiverSource).toContain("if (channelManager != null)");
  });

  it("keeps equal-versionCode update state until the semantic launcher version is installed", () => {
    expect(updateServiceSource).toContain(
      "clearIfInstalled(Context context, long currentVersionCode, String currentVersionName)",
    );
    expect(updateServiceSource).toContain("compareVersionNames(currentVersionName, targetVersionName)");
    expect(pluginSource).toContain(
      "clearIfInstalled(getContext(), LauncherUpdateVerifier.packageVersionCode(packageInfo), packageInfo.versionName)",
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
