import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

function readSource(path: string) {
  const fullPath = resolve(process.cwd(), path);
  return existsSync(fullPath) ? readFileSync(fullPath, "utf8") : "";
}

const storeSource = readSource(
  "android/app/src/main/java/com/lingjing/launcher/android/LauncherLogStore.java",
);
const pluginSource = readSource(
  "android/app/src/main/java/com/lingjing/launcher/android/AndroidLauncherPlugin.java",
);
const bridgeSource = readSource("src/services/androidLauncher.ts");
const loggerSource = readSource("src/services/launcherLog.ts");
const appSource = readSource("src/App.vue");
const mainSource = readSource("src/main.ts");
const gameDownloadSource = readSource(
  "android/app/src/main/java/com/lingjing/launcher/android/GameDownloadService.java",
);
const launcherUpdateSource = readSource(
  "android/app/src/main/java/com/lingjing/launcher/android/LauncherUpdateService.java",
);

describe("native launcher log", () => {
  it("keeps one UTF-8 rolling file below 10 MiB", () => {
    expect(storeSource).toContain("MAX_LOG_BYTES = 10L * 1024L * 1024L");
    expect(storeSource).toContain("TRIM_TARGET_BYTES = 8L * 1024L * 1024L");
    expect(storeSource).toContain("StandardCharsets.UTF_8");
    expect(storeSource).toContain("RandomAccessFile");
    expect(storeSource).toContain("trimOldestLines");
    expect(storeSource).toContain("if (logFile.length() + lineBytes.length > MAX_LOG_BYTES)");
  });

  it("uses a random installation id and never a hardware identifier", () => {
    expect(storeSource).toContain("UUID.randomUUID().toString()");
    expect(storeSource).not.toMatch(/ANDROID_ID|IMEI|Build\.SERIAL|getDeviceId/);
  });

  it("exposes append, metadata, and direct upload through Capacitor", () => {
    expect(pluginSource).toContain("public void appendLauncherLog(PluginCall call)");
    expect(pluginSource).toContain("public void getLauncherLogInfo(PluginCall call)");
    expect(pluginSource).toContain("public void uploadLauncherLog(PluginCall call)");
    expect(pluginSource).toContain("getBridge().execute");
    expect(bridgeSource).toContain("appendLauncherLog(options");
    expect(bridgeSource).toContain("getLauncherLogInfo()");
    expect(bridgeSource).toContain("uploadLauncherLog(options");
  });

  it("automatically uploads native logs after an error without flooding the server", () => {
    expect(storeSource).toContain('if ("error".equalsIgnoreCase(level))');
    expect(storeSource).toContain("scheduleAutomaticUpload(context.getApplicationContext())");
    expect(storeSource).toContain("AUTO_UPLOAD_EXECUTOR");
    expect(storeSource).toContain("AUTO_UPLOAD_DEBOUNCE_MS");
    expect(storeSource).toContain("AUTO_UPLOAD_MIN_INTERVAL_MS");
    expect(storeSource).toContain("synchronized (UPLOAD_LOCK)");
  });

  it("captures global failures and user actions without logging every progress tick", () => {
    expect(loggerSource).toContain("installGlobalLauncherLogHandlers");
    expect(loggerSource).toContain('window.addEventListener("error"');
    expect(loggerSource).toContain('window.addEventListener("unhandledrejection"');
    expect(loggerSource).toContain('document.addEventListener("click"');
    expect(loggerSource).toContain("redactSensitiveText");
    expect(mainSource).toContain("installGlobalLauncherLogHandlers()");
    expect(appSource).toContain("lastNativeLogSignature");
    expect(appSource).toContain('writeLauncherLog("info", "native.download-state"');
    expect(appSource).toContain('watch(phase');
    expect(appSource).toContain('watch(currentPageIndex');
  });

  it("adds a manual log upload control to Settings", () => {
    expect(appSource).toContain("日志与诊断");
    expect(appSource).toContain("上传日志");
    expect(appSource).toContain("uploadCurrentLauncherLog");
    expect(appSource).toContain("launcherLogInfo");
    expect(appSource).toContain("launcherLogUploadState");
    expect(appSource).toContain("getLauncherLogInfo");
    expect(appSource).toContain("uploadLauncherLog");
    expect(appSource).not.toContain("reportAndroidDiagnostic");
    expect(appSource).not.toContain("flushAndroidDiagnostics");
  });

  it("records native background download, OBB preparation, and launcher update states", () => {
    expect(gameDownloadSource).toContain("lastLoggedStateSignature");
    expect(gameDownloadSource).toContain('LauncherLogStore.append(this, level, "game-download.state"');
    expect(gameDownloadSource).toContain('terminalMessage = "APK 和 OBB 已准备完成"');
    expect(gameDownloadSource).toContain("finishWorker(terminalStatus, terminalMessage");
    expect(launcherUpdateSource).toContain("lastLoggedStateSignature");
    expect(launcherUpdateSource).toContain('LauncherLogStore.append(context, level, "launcher-update.state"');
  });
});
