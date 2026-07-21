import { Capacitor } from "@capacitor/core";
import { registerPlugin, type PluginListenerHandle } from "@capacitor/core";
import type { AndroidDownloadPlan } from "./downloadPlan";
import type { AndroidLauncherUpdateManifest } from "./launcherUpdate";

export type AndroidLauncherInfo = {
  versionName: string;
  versionCode: number;
};

export type LauncherPermissionStatus = {
  canInstallUnknownApps: boolean;
  batteryOptimizationIgnored: boolean;
};

export type NativeGithubNetworkStatus = {
  proxyDetected: boolean;
  reachable: boolean;
  latencyMs: number | null;
};

export type NativeLauncherUpdateState = {
  status: "idle" | "downloading" | "ready" | "error";
  message: string;
  versionName?: string;
  versionCode?: number;
  sha256?: string;
  downloadedBytes: number;
  totalBytes: number;
  percent: number;
  apkPath?: string;
};

export type AndroidGameInfo = {
  installed: boolean;
  packageName: string;
  versionName?: string;
  managedVersion?: string;
  versionCode: number;
  lastUpdateTime?: number;
};

export type LauncherLogInfo = {
  fileName: string;
  sizeBytes: number;
  maxBytes: number;
  lastModified: number;
  hasLog: boolean;
  installationId: string;
};

export type LauncherLogUploadResult = {
  uploaded: boolean;
  sizeBytes: number;
  installationId: string;
  serverResponse?: string;
};

export type NativeDownloadState = {
  status: "idle" | "downloading" | "paused" | "verifying" | "merging" | "extracting" | "ready" | "error";
  message: string;
  version?: string;
  source?: "official" | "github";
  archiveSha256?: string;
  downloadedBytes: number;
  totalBytes: number;
  percent: number;
  currentChunk: number;
  totalChunks: number;
  verifiedChunks: number;
  bytesPerSecond?: number;
  canPause: boolean;
  apkPath?: string;
  obbPath?: string;
  obbFileName?: string;
};

type AndroidLauncherPlugin = {
  getLauncherInfo(): Promise<AndroidLauncherInfo>;
  appendLauncherLog(options: { level: string; event: string; message: string; details?: string }): Promise<LauncherLogInfo>;
  getLauncherLogInfo(): Promise<LauncherLogInfo>;
  uploadLauncherLog(options: { launcherVersion: string }): Promise<LauncherLogUploadResult>;
  checkGame(options: { packageName: string }): Promise<AndroidGameInfo>;
  getGithubNetworkStatus(): Promise<NativeGithubNetworkStatus>;
  getLauncherPermissionStatus(): Promise<LauncherPermissionStatus>;
  openInstallPermissionSettings(): Promise<{ opened: boolean }>;
  openBatteryOptimizationSettings(): Promise<{ opened: boolean; directRequest: boolean }>;
  installDownloadedApk(): Promise<{ started: boolean }>;
  startDownload(options: { plan: AndroidDownloadPlan }): Promise<{ started: boolean }>;
  pauseDownload(): Promise<{ paused: boolean }>;
  cancelDownload(): Promise<{ cancelled: boolean }>;
  getDownloadState(): Promise<NativeDownloadState>;
  startLauncherUpdate(options: { plan: AndroidLauncherUpdateManifest }): Promise<{ started: boolean }>;
  cancelLauncherUpdate(): Promise<{ cancelled: boolean }>;
  getLauncherUpdateState(): Promise<NativeLauncherUpdateState>;
  installLauncherUpdate(): Promise<{ started: boolean }>;
  addListener(
    eventName: "downloadProgress",
    listener: (state: NativeDownloadState) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "launcherUpdateProgress",
    listener: (state: NativeLauncherUpdateState) => void,
  ): Promise<PluginListenerHandle>;
};

const plugin = registerPlugin<AndroidLauncherPlugin>("AndroidLauncher");

const GAME_PACKAGE_NAME = "com.TFAC.CorssingVoid";

export async function getAndroidLauncherInfo(): Promise<AndroidLauncherInfo> {
  if (Capacitor.getPlatform() !== "android") return { versionName: "0.0.0", versionCode: 0 };
  return plugin.getLauncherInfo();
}

export async function appendLauncherLog(options: { level: string; event: string; message: string; details?: string }) {
  if (Capacitor.getPlatform() !== "android") return null;
  return plugin.appendLauncherLog(options);
}

export async function getLauncherLogInfo(): Promise<LauncherLogInfo> {
  if (Capacitor.getPlatform() !== "android") {
    return { fileName: "launcher.log", sizeBytes: 0, maxBytes: 10 * 1024 * 1024, lastModified: 0, hasLog: false, installationId: "preview" };
  }
  return plugin.getLauncherLogInfo();
}

export async function uploadLauncherLog(launcherVersion: string): Promise<LauncherLogUploadResult> {
  if (Capacitor.getPlatform() !== "android") throw new Error("日志上传只能在 Android 启动器中使用。");
  return plugin.uploadLauncherLog({ launcherVersion });
}

export async function checkAndroidGame(): Promise<AndroidGameInfo> {
  if (Capacitor.getPlatform() !== "android") {
    return {
      installed: false,
      packageName: GAME_PACKAGE_NAME,
      versionCode: 0,
    };
  }
  return plugin.checkGame({ packageName: GAME_PACKAGE_NAME });
}

export async function getGithubNetworkStatus(): Promise<NativeGithubNetworkStatus> {
  if (Capacitor.getPlatform() !== "android") {
    return { proxyDetected: true, reachable: true, latencyMs: 120 };
  }
  return plugin.getGithubNetworkStatus();
}

export async function openInstallPermissionSettings() {
  if (Capacitor.getPlatform() !== "android") return { opened: false };
  return plugin.openInstallPermissionSettings();
}

export async function getLauncherPermissionStatus(): Promise<LauncherPermissionStatus> {
  if (Capacitor.getPlatform() !== "android") {
    return { canInstallUnknownApps: true, batteryOptimizationIgnored: true };
  }
  return plugin.getLauncherPermissionStatus();
}

export async function openBatteryOptimizationSettings() {
  if (Capacitor.getPlatform() !== "android") return { opened: false, directRequest: false };
  return plugin.openBatteryOptimizationSettings();
}

export async function installDownloadedApk() {
  if (Capacitor.getPlatform() !== "android") {
    throw new Error("当前平台不能安装 Android APK");
  }
  return plugin.installDownloadedApk();
}

export async function startGameDownload(plan: AndroidDownloadPlan) {
  if (Capacitor.getPlatform() !== "android") throw new Error("真实下载需要在 Android 启动器中运行");
  return plugin.startDownload({ plan });
}

export async function pauseGameDownload() {
  if (Capacitor.getPlatform() !== "android") return { paused: false };
  return plugin.pauseDownload();
}

export async function cancelGameDownload() {
  if (Capacitor.getPlatform() !== "android") return { cancelled: false };
  return plugin.cancelDownload();
}

export async function getGameDownloadState(): Promise<NativeDownloadState> {
  if (Capacitor.getPlatform() !== "android") {
    return {
      status: "idle",
      message: "等待下载",
      downloadedBytes: 0,
      totalBytes: 0,
      percent: 0,
      currentChunk: 0,
      totalChunks: 0,
      verifiedChunks: 0,
      canPause: false,
    };
  }
  return plugin.getDownloadState();
}

export async function addDownloadProgressListener(
  listener: (state: NativeDownloadState) => void,
) {
  if (Capacitor.getPlatform() !== "android") return null;
  return plugin.addListener("downloadProgress", listener);
}

export async function startLauncherUpdate(plan: AndroidLauncherUpdateManifest) {
  if (Capacitor.getPlatform() !== "android") throw new Error("启动器更新只能在 Android 中运行");
  return plugin.startLauncherUpdate({ plan });
}

export async function cancelLauncherUpdate() {
  if (Capacitor.getPlatform() !== "android") return { cancelled: false };
  return plugin.cancelLauncherUpdate();
}

export async function getLauncherUpdateState(): Promise<NativeLauncherUpdateState> {
  if (Capacitor.getPlatform() !== "android") {
    return {
      status: "idle",
      message: "启动器已是最新版本",
      downloadedBytes: 0,
      totalBytes: 0,
      percent: 0,
    };
  }
  return plugin.getLauncherUpdateState();
}

export async function installLauncherUpdate() {
  if (Capacitor.getPlatform() !== "android") throw new Error("启动器更新只能在 Android 中安装");
  return plugin.installLauncherUpdate();
}

export async function addLauncherUpdateProgressListener(
  listener: (state: NativeLauncherUpdateState) => void,
) {
  if (Capacitor.getPlatform() !== "android") return null;
  return plugin.addListener("launcherUpdateProgress", listener);
}
