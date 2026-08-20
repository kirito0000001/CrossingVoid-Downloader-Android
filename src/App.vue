<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import {
  ChevronLeft,
  ChevronRight,
  BatteryCharging,
  CircleAlert,
  Download,
  ExternalLink,
  Gamepad2,
  HardDriveDownload,
  Info,
  Megaphone,
  PackageOpen,
  Pause,
  RefreshCw,
  Settings,
  Trash2,
  UploadCloud,
  Users,
  Video,
  X,
} from "lucide-vue-next";
import {
  addDownloadProgressListener,
  addLauncherUpdateProgressListener,
  cancelLauncherUpdate,
  cancelGameDownload,
  checkAndroidGame,
  getAndroidLauncherInfo,
  getGithubNetworkStatus,
  getGameDownloadState,
  getLauncherLogInfo,
  getLauncherPermissionStatus,
  getLauncherUpdateState,
  installDownloadedApk,
  importGameChunks,
  installLauncherUpdate,
  openInstallPermissionSettings,
  openBatteryOptimizationSettings,
  pauseGameDownload,
  startGameDownload,
  startLauncherUpdate,
  uploadLauncherLog,
  type AndroidGameInfo,
  type AndroidLauncherInfo,
  type LauncherLogInfo,
  type LauncherPermissionStatus,
  type NativeDownloadState,
  type NativeLauncherUpdateState,
} from "./services/androidLauncher";
import {
  buildAndroidDownloadPlan,
  launcherPhaseFromNativeState,
  type AndroidDownloadSource,
} from "./services/downloadPlan";
import {
  readAndroidDownloadSource,
  saveAndroidDownloadSource,
} from "./services/downloadSource";
import { githubNetworkWarning, type GithubNetworkStatus } from "./services/githubNetwork";
import {
  checkLatestAndroidGame,
  type AndroidGameUpdateInfo,
} from "./services/gameUpdate";
import {
  checkLatestAndroidLauncher,
  shouldInstallLauncherUpdate,
  type AndroidLauncherUpdateManifest,
} from "./services/launcherUpdate";
import { writeLauncherLog } from "./services/launcherLog";
import {
  fetchTrafficQuotaStatus,
  type TrafficQuotaStatus,
} from "./services/trafficStatus";

type LauncherPhase =
  | "launcherChecking"
  | "launcherUpdateReady"
  | "launcherUpdating"
  | "launcherUpdateInstall"
  | "launcherInstalling"
  | "checking"
  | "ready"
  | "updateReady"
  | "downloading"
  | "paused"
  | "verifying"
  | "readyInstall"
  | "installing"
  | "error";

type GameManagementAction = "cancelDownload" | "deletePackage" | "clearDownload";

const launcherPages = ["设置", "首页", "公告", "账号", "角色介绍", "视频"] as const;

const gameInfo = ref<AndroidGameInfo | null>(null);
const launcherInfo = ref<AndroidLauncherInfo | null>(null);
const launcherUpdateInfo = ref<AndroidLauncherUpdateManifest | null>(null);
const launcherTargetVersionName = ref("");
const updateInfo = ref<AndroidGameUpdateInfo | null>(null);
const phase = ref<LauncherPhase>("checking");
const progress = ref(0);
const statusMessage = ref("正在检测游戏版本");
const latestVersionName = computed(() => updateInfo.value?.version || "读取中");
const downloadedBytes = ref(0);
const totalBytes = ref(0);
const downloadSpeedBytes = ref(0);
const downloadSource = ref<AndroidDownloadSource>(readAndroidDownloadSource());
const activeDownloadSource = ref<AndroidDownloadSource | null>(null);
const nativeDownloadStatus = ref<NativeDownloadState["status"]>("idle");
const currentChunk = ref(0);
const totalChunks = ref(0);
const verifiedChunks = ref(0);
const currentPageIndex = ref(1);
const launcherUpdateCheckError = ref("");
const launcherUpdateCheckCompleted = ref(false);
const launcherAccessLocked = computed(() =>
  !launcherUpdateCheckCompleted.value ||
  Boolean(launcherUpdateCheckError.value) ||
  ["launcherChecking", "launcherUpdateReady", "launcherUpdating", "launcherUpdateInstall", "launcherInstalling"].includes(phase.value),
);
const trafficQuota = ref<TrafficQuotaStatus | null>(null);
const trafficQuotaPending = ref(false);
const githubNetworkStatus = ref<GithubNetworkStatus | null>(null);
const githubNetworkPending = ref(false);
const operationErrorMessage = ref("");
const launcherLogInfo = ref<LauncherLogInfo | null>(null);
const launcherLogUploadState = ref<"idle" | "uploading" | "success" | "error">("idle");
const launcherLogUploadMessage = ref("");
const launcherPermissionStatus = ref<LauncherPermissionStatus | null>(null);
let touchStartX = 0;
let progressListener: Awaited<ReturnType<typeof addDownloadProgressListener>> = null;
let launcherProgressListener: Awaited<ReturnType<typeof addLauncherUpdateProgressListener>> = null;
let gameInstallPending = false;
let lastReportedNativeError = "";
let lastNativeLogSignature = "";
let lastLauncherUpdateLogSignature = "";
let trafficQuotaRefreshTimer: number | undefined;
let pausingOfficialDownload = false;

const isLauncherUpdatePhase = computed(() => phase.value.startsWith("launcher"));
const displayedLatestVersion = computed(() =>
  isLauncherUpdatePhase.value
    ? launcherUpdateInfo.value?.versionName || launcherTargetVersionName.value || "读取中"
    : latestVersionName.value,
);

const downloadSourceName = computed(() => downloadSource.value === "github" ? "Github 源" : "零境交错源");
const activeDownloadSourceName = computed(() =>
  (activeDownloadSource.value || downloadSource.value) === "github" ? "Github 源" : "零境交错源",
);
const downloadSourceLocked = computed(() =>
  isLauncherUpdatePhase.value || ["downloading", "verifying", "installing"].includes(phase.value),
);
const showGlobalProgress = computed(() =>
  ["launcherUpdating", "downloading", "paused", "verifying"].includes(phase.value),
);
const progressAnimating = computed(() => showGlobalProgress.value && phase.value !== "paused");
const progressDetailText = computed(() => {
  if (isLauncherUpdatePhase.value) return "更新来源：Gitee";
  if (totalChunks.value <= 0) return "准备下载";
  if (nativeDownloadStatus.value === "verifying") {
    return `已校验 ${Math.min(verifiedChunks.value, totalChunks.value)} / ${totalChunks.value} 片`;
  }
  if (["downloading", "paused"].includes(nativeDownloadStatus.value)) {
    return `第 ${Math.max(1, currentChunk.value)} / ${totalChunks.value} 片`;
  }
  return "等待下载";
});

const currentPageName = computed(() => launcherPages[currentPageIndex.value]);
const pageNumberText = computed(() =>
  `${String(currentPageIndex.value + 1).padStart(2, "0")} / ${String(launcherPages.length).padStart(2, "0")}`,
);
const launcherVersionText = computed(() => launcherInfo.value?.versionName || "读取中");
const gameVersionText = computed(() => "未安装");
const targetVersionText = computed(() =>
  isLauncherUpdatePhase.value
    ? launcherUpdateInfo.value?.versionName || launcherTargetVersionName.value || launcherVersionText.value
    : latestVersionName.value,
);
const targetVersionLabel = computed(() => isLauncherUpdatePhase.value ? "启动器最新版本" : "游戏最新版本");
const officialTrafficBlocked = computed(() =>
  Boolean(trafficQuota.value?.available && !trafficQuota.value?.downloadAllowed),
);
const trafficQuotaPercent = computed(() => {
  const quota = trafficQuota.value;
  if (!quota?.available || quota.totalBytes <= 0) return 0;
  return Math.max(0, Math.min(100, (quota.remainingBytes / quota.totalBytes) * 100));
});
const trafficQuotaText = computed(() => {
  if (trafficQuotaPending.value && !trafficQuota.value) return "正在获取服务器流量额度";
  if (!trafficQuota.value?.available) return "暂时无法获取流量额度，不影响下载";
  return `剩余流量 ${formatBytes(trafficQuota.value.remainingBytes)} / ${formatBytes(trafficQuota.value.totalBytes)}`;
});
const trafficQuotaExpiryText = computed(() => {
  const value = trafficQuota.value?.expiresAt;
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return `最近到期 ${new Intl.DateTimeFormat("zh-CN", { month: "numeric", day: "numeric" }).format(date)}`;
});
const trafficQuotaHint = computed(() =>
  officialTrafficBlocked.value
    ? "服务器当前流量不足，请更换下载源。"
    : "可以在启动器主界面顶部支持一下作者，谢谢了。",
);
const githubNetworkWarningText = computed(() =>
  githubNetworkStatus.value ? githubNetworkWarning(githubNetworkStatus.value) : "",
);
const githubProxyText = computed(() => {
  if (githubNetworkPending.value) return "正在检测";
  if (!githubNetworkStatus.value) return "等待检测";
  return githubNetworkStatus.value.proxyDetected ? "已检测到网络代理" : "未检测到网络代理";
});
const githubLatencyText = computed(() => {
  if (githubNetworkPending.value) return "正在检测";
  if (!githubNetworkStatus.value) return "等待检测";
  if (!githubNetworkStatus.value.reachable || githubNetworkStatus.value.latencyMs === null) return "无法连接";
  return `${githubNetworkStatus.value.latencyMs} ms`;
});
const launcherLogStatusText = computed(() => {
  if (launcherLogUploadState.value === "uploading") return "正在上传启动器日志";
  if (launcherLogUploadMessage.value) return launcherLogUploadMessage.value;
  const info = launcherLogInfo.value;
  if (!info?.hasLog) return "日志会自动保存在本机，最多 10 MB";
  const modified = info.lastModified > 0 ? new Date(info.lastModified).toLocaleString("zh-CN", { hour12: false }) : "";
  return `${formatBytes(info.sizeBytes)} / ${formatBytes(info.maxBytes)}${modified ? ` · ${modified}` : ""}`;
});
const launcherLogButtonText = computed(() => launcherLogUploadState.value === "uploading" ? "上传中" : "上传日志");
const installPermissionStatusText = computed(() => {
  if (!launcherPermissionStatus.value) return "正在检测权限状态";
  return launcherPermissionStatus.value.canInstallUnknownApps
    ? "已允许安装应用"
    : "未允许，更新与安装会失败";
});
const backgroundPermissionStatusText = computed(() => {
  if (!launcherPermissionStatus.value) return "正在检测权限状态";
  return launcherPermissionStatus.value.batteryOptimizationIgnored
    ? "已允许后台持续下载"
    : "受电池优化限制，后台下载可能暂停";
});

const settingsBodyRef = ref<HTMLElement | null>(null);
const activeSettingsNav = ref("preferences");

function scrollSettingsTo(section: string) {
  activeSettingsNav.value = section;
  const body = settingsBodyRef.value;
  const target = body?.querySelector<HTMLElement>(`[data-settings-section="${section}"]`);
  if (!body || !target) return;
  body.scrollTo({ top: Math.max(0, target.offsetTop - 16), behavior: "smooth" });
}

function reportFailure(stage: string, error: unknown) {
  const message = error instanceof Error ? error.message : String(error || "未知错误");
  void writeLauncherLog("error", stage, message, error);
}

function reportInstallTrace(stage: string, event: string) {
  const details = {
    event,
    gameInfo: gameInfo.value,
    phase: phase.value,
    nativeStatus: nativeDownloadStatus.value,
    nativeMessage: statusMessage.value,
  };
  return writeLauncherLog("info", stage, event, details);
}

async function refreshLauncherLogInfo() {
  try {
    launcherLogInfo.value = await getLauncherLogInfo();
  } catch (error) {
    launcherLogUploadState.value = "error";
    launcherLogUploadMessage.value = error instanceof Error ? error.message : "无法读取日志信息";
  }
}

async function refreshLauncherPermissionStatus() {
  try {
    launcherPermissionStatus.value = await getLauncherPermissionStatus();
  } catch (error) {
    reportFailure("permission-status", error);
  }
}

async function handleOpenInstallPermissionSettings() {
  try {
    await openInstallPermissionSettings();
  } catch (error) {
    reportFailure("install-permission-settings", error);
  }
}

async function handleOpenBatteryOptimizationSettings() {
  try {
    await openBatteryOptimizationSettings();
  } catch (error) {
    reportFailure("battery-optimization-settings", error);
  }
}

async function uploadCurrentLauncherLog() {
  if (launcherLogUploadState.value === "uploading") return;
  launcherLogUploadState.value = "uploading";
  launcherLogUploadMessage.value = "正在上传启动器日志";
  await writeLauncherLog("info", "log.upload-start", "User requested launcher log upload", {
    launcherVersion: launcherVersionText.value,
    gameVersion: gameVersionText.value,
  });
  try {
    const result = await uploadLauncherLog(launcherVersionText.value);
    launcherLogUploadState.value = "success";
    launcherLogUploadMessage.value = `上传完成 · ${formatBytes(result.sizeBytes)}`;
    await writeLauncherLog("info", "log.upload-success", launcherLogUploadMessage.value);
  } catch (error) {
    launcherLogUploadState.value = "error";
    launcherLogUploadMessage.value = error instanceof Error ? error.message : "日志上传失败";
    await writeLauncherLog("error", "log.upload-failed", launcherLogUploadMessage.value, error);
  }
  await refreshLauncherLogInfo();
}

const statusTitle = computed(() => {
  switch (phase.value) {
    case "launcherChecking":
      return "检查启动器更新";
    case "launcherUpdateReady":
      return "发现启动器新版本";
    case "launcherUpdating":
      return "更新启动器中";
    case "launcherUpdateInstall":
      return "启动器更新已就绪";
    case "launcherInstalling":
      return "等待系统安装";
    case "checking":
      return "检测版本中";
    case "ready":
      return "资源完整";
    case "updateReady":
      return `发现新版本 ${latestVersionName.value}`;
    case "downloading":
      return `下载游戏中：${activeDownloadSourceName.value}`;
    case "paused":
      return `下载已暂停：${activeDownloadSourceName.value}`;
    case "verifying":
      if (nativeDownloadStatus.value === "pausing") return "正在暂停下载";
      if (nativeDownloadStatus.value === "cancelling") return "正在取消下载";
      if (nativeDownloadStatus.value === "importing") return "导入游戏碎片";
      if (nativeDownloadStatus.value === "merging") return "合并安装包";
      if (nativeDownloadStatus.value === "extracting") return "解压游戏资源";
      return "校验游戏包";
    case "readyInstall":
      return "安装包已就绪";
    case "installing":
      return "等待系统安装";
    case "error":
      return "处理失败";
    default:
      return "等待操作";
  }
});

const actionText = computed(() => {
  switch (phase.value) {
    case "launcherChecking":
      return "检测中";
    case "launcherUpdateReady":
      return "更新启动器";
    case "launcherUpdating":
      return "下载更新中";
    case "launcherUpdateInstall":
      return "安装启动器更新";
    case "launcherInstalling":
      return "安装中";
    case "checking":
      return "检测中";
    case "ready":
      return "启动游戏";
    case "updateReady":
      return "下载游戏";
    case "downloading":
      return "暂停下载";
    case "paused":
      return "继续下载";
    case "verifying":
      return "校验中";
    case "readyInstall":
      return "安装游戏";
    case "installing":
      return "安装中";
    case "error":
      return "重新检测";
    default:
      return "开始";
  }
});

const actionIcon = computed(() => {
  switch (phase.value) {
    case "launcherUpdateReady":
      return HardDriveDownload;
    case "launcherUpdating":
    case "launcherInstalling":
    case "launcherChecking":
    case "checking":
    case "verifying":
    case "installing":
      return RefreshCw;
    case "launcherUpdateInstall":
    case "readyInstall":
      return PackageOpen;
    case "ready":
      return Gamepad2;
    case "updateReady":
      return Download;
    case "downloading":
      return Pause;
    case "paused":
      return Download;
    case "error":
      return CircleAlert;
    default:
      return Download;
  }
});

const primaryActionSpinning = computed(() =>
  ["launcherChecking", "launcherUpdating", "launcherInstalling", "checking", "verifying", "installing"].includes(phase.value),
);

const launcherUpdateStatusText = computed(() => {
  if (launcherUpdateCheckError.value) return launcherUpdateCheckError.value;
  if (phase.value === "launcherChecking") return "正在检查启动器更新";
  if (phase.value === "launcherUpdateReady") return `发现新版本 ${launcherUpdateInfo.value?.versionName || ""}`.trim();
  if (phase.value === "launcherUpdating") return `正在下载 ${launcherTargetVersionName.value || "启动器更新"}`;
  if (phase.value === "launcherUpdateInstall") return "更新已下载，可以安装";
  if (phase.value === "launcherInstalling") return "等待系统确认覆盖安装";
  return launcherUpdateCheckCompleted.value ? `当前 ${launcherVersionText.value}，已是最新版本` : "尚未检查";
});

const launcherUpdateButtonText = computed(() => {
  if (phase.value === "launcherChecking") return "检查中";
  if (phase.value === "launcherUpdateReady") return "立即更新";
  if (phase.value === "launcherUpdating") return "取消更新";
  if (["launcherUpdateInstall", "launcherInstalling"].includes(phase.value)) return "查看进度";
  return "检查更新";
});

const actionDisabled = computed(() =>
  ["launcherChecking", "launcherUpdating", "launcherInstalling", "checking", "verifying", "installing"].includes(phase.value),
);

async function waitForNativeDownloadStatus(
  accepted: NativeDownloadState["status"][],
  timeoutMs = 15_000,
) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const state = await getGameDownloadState();
    applyNativeState(state);
    if (accepted.includes(state.status)) return state;
    await new Promise((resolve) => window.setTimeout(resolve, 150));
  }
  throw new Error("游戏下载任务仍在结束，请稍后再试。");
}

const gameManagementAction = computed<GameManagementAction | null>(() => {
  if (isLauncherUpdatePhase.value || ["checking", "installing"].includes(phase.value)) return null;
  if (["downloading", "paused", "verifying"].includes(phase.value)) return "cancelDownload";
  if (phase.value === "readyInstall") return "deletePackage";
  if (nativeDownloadStatus.value === "error" && downloadedBytes.value > 0) return "clearDownload";
  return null;
});

const gameManagementButtonText = computed(() => {
  if (gameManagementAction.value === "cancelDownload") return "取消下载";
  if (gameManagementAction.value === "deletePackage") return "删除安装包";
  if (gameManagementAction.value === "clearDownload") return "清除下载文件";
  return "";
});

const gameManagementHint = computed(() => {
  if (gameManagementAction.value === "cancelDownload") return statusMessage.value;
  if (gameManagementAction.value === "deletePackage") return "安装包已下载，可以安装或删除";
  if (gameManagementAction.value === "clearDownload") return "下载未完成，可以清除已下载文件";
  return "尚未安装游戏";
});

const gameManagementIcon = computed(() =>
  gameManagementAction.value === "cancelDownload" ? X : Trash2,
);

const sizeText = computed(() => {
  if (!totalBytes.value) return "";
  return `${formatBytes(downloadedBytes.value)} / ${formatBytes(totalBytes.value)}`;
});

const downloadSpeedText = computed(() =>
  downloadSpeedBytes.value > 0 ? `${formatBytes(downloadSpeedBytes.value)}/s` : "-- KB/s",
);
const remainingTimeText = computed(() => {
  if (phase.value !== "downloading") return "";
  if (downloadSpeedBytes.value <= 0 || totalBytes.value <= downloadedBytes.value) return "预计剩余 --:--";
  const seconds = (totalBytes.value - downloadedBytes.value) / downloadSpeedBytes.value;
  return `预计剩余 ${formatEtaClock(seconds)}`;
});
const progressValueText = computed(() =>
  [sizeText.value, downloadSpeedText.value, remainingTimeText.value].filter(Boolean).join(" · "),
);

function formatBytes(value: number) {
  if (value >= 1024 * 1024 * 1024) return `${(value / 1024 / 1024 / 1024).toFixed(2)} GB`;
  if (value >= 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`;
  if (value >= 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${value} B`;
}

function formatEtaClock(value: number) {
  if (!Number.isFinite(value) || value < 0 || value >= 24 * 60 * 60) return "网络不佳";
  const seconds = Math.max(0, Math.ceil(value));
  const minutes = Math.floor(seconds / 60);
  return `${String(minutes).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;
}

function applyNativeState(state: NativeDownloadState) {
  const preserveLauncherUpdatePhase = isLauncherUpdatePhase.value;
  nativeDownloadStatus.value = state.status;
  if (state.status === "idle") {
    activeDownloadSource.value = null;
    currentChunk.value = 0;
    totalChunks.value = 0;
    verifiedChunks.value = 0;
  }
  if (state.source) activeDownloadSource.value = state.source;
  currentChunk.value = Math.max(0, state.currentChunk || 0);
  totalChunks.value = Math.max(0, state.totalChunks || 0);
  verifiedChunks.value = Math.max(0, state.verifiedChunks || 0);
  downloadedBytes.value = Math.max(0, state.downloadedBytes || 0);
  totalBytes.value = Math.max(state.totalBytes || 0, totalBytes.value);
  downloadSpeedBytes.value = Math.max(0, state.bytesPerSecond || 0);
  if (!preserveLauncherUpdatePhase) {
    progress.value = Math.max(0, Math.min(100, state.percent || 0));
    statusMessage.value = state.message || "正在处理游戏下载";
    phase.value = launcherPhaseFromNativeState(state.status);
  }
  const logSignature = [state.status, state.currentChunk, state.verifiedChunks, state.message].join("|");
  if (logSignature !== lastNativeLogSignature) {
    lastNativeLogSignature = logSignature;
    void writeLauncherLog("info", "native.download-state", state.message || state.status, {
      status: state.status,
      version: state.version,
      source: state.source,
      currentChunk: state.currentChunk,
      totalChunks: state.totalChunks,
      verifiedChunks: state.verifiedChunks,
      downloadedBytes: state.downloadedBytes,
      totalBytes: state.totalBytes,
    });
  }
  if (state.status !== "error") {
    lastReportedNativeError = "";
    return;
  }

  const errorSignature = [state.message, state.version, state.source, state.currentChunk, state.downloadedBytes].join("|");
  if (errorSignature === lastReportedNativeError) return;
  lastReportedNativeError = errorSignature;
  reportFailure("native-download-state", new Error(state.message || "原生下载服务失败"));
}

function applyLauncherUpdateState(state: NativeLauncherUpdateState) {
  if (state.versionName) launcherTargetVersionName.value = state.versionName;
  downloadedBytes.value = Math.max(0, state.downloadedBytes || 0);
  totalBytes.value = Math.max(0, state.totalBytes || 0);
  downloadSpeedBytes.value = 0;
  progress.value = Math.max(0, Math.min(100, state.percent || 0));
  statusMessage.value = state.message || "正在处理启动器更新";
  const logSignature = [state.status, state.versionName, state.message].join("|");
  if (logSignature !== lastLauncherUpdateLogSignature) {
    lastLauncherUpdateLogSignature = logSignature;
    void writeLauncherLog("info", "native.launcher-update-state", state.message || state.status, state);
  }
  if (state.status === "downloading") phase.value = "launcherUpdating";
  else if (state.status === "ready") phase.value = "launcherUpdateInstall";
  else if (state.status === "error") phase.value = "error";
}

async function checkLauncherUpdate(): Promise<boolean> {
  phase.value = "launcherChecking";
  launcherUpdateCheckError.value = "";
  launcherUpdateCheckCompleted.value = false;
  launcherUpdateInfo.value = null;
  progress.value = 0;
  downloadedBytes.value = 0;
  totalBytes.value = 0;
  statusMessage.value = "正在检查零境启动器更新";
  try {
    const [installedLauncher, nativeState] = await Promise.all([
      getAndroidLauncherInfo(),
      getLauncherUpdateState(),
    ]);
    launcherInfo.value = installedLauncher;
    if (nativeState.status === "downloading" || nativeState.status === "ready") {
      applyLauncherUpdateState(nativeState);
      launcherUpdateCheckCompleted.value = true;
      return true;
    }

    const latest = await checkLatestAndroidLauncher();
    launcherTargetVersionName.value = latest?.versionName || "";
    launcherUpdateCheckCompleted.value = true;
    if (!latest || !shouldInstallLauncherUpdate(installedLauncher.versionCode, installedLauncher.versionName, latest)) return false;

    launcherUpdateInfo.value = latest;
    phase.value = "launcherUpdateReady";
    currentPageIndex.value = 1;
    progress.value = 0;
    totalBytes.value = latest.asset.sizeBytes;
    statusMessage.value = `当前 ${installedLauncher.versionName}，目标 ${latest.versionName}`;
    return true;
  } catch (error) {
    console.warn("Launcher update check failed", error);
    launcherUpdateInfo.value = null;
    launcherUpdateCheckCompleted.value = true;
    launcherUpdateCheckError.value = `启动器更新检查失败：${error instanceof Error ? error.message : "未知错误"}`;
    phase.value = "error";
    statusMessage.value = launcherUpdateCheckError.value;
    return false;
  }
}

async function refreshGameStatus() {
  phase.value = "checking";
  operationErrorMessage.value = "";
  progress.value = 0;
  statusMessage.value = "正在读取本机已安装游戏";
  try {
    const [installedGame, latestUpdate, nativeDownload] = await Promise.all([
      checkAndroidGame(),
      checkLatestAndroidGame(),
      getGameDownloadState(),
    ]);
    gameInfo.value = installedGame;
    updateInfo.value = latestUpdate;
    totalBytes.value = latestUpdate.asset.sizeBytes;
    if (nativeDownload.status !== "idle") {
      applyNativeState(nativeDownload);
      if (nativeDownload.version && nativeDownload.version !== latestUpdate.version) {
        statusMessage.value = `下载任务版本 ${nativeDownload.version} 已过期，继续后将切换到 ${latestUpdate.version}`;
      }
      return;
    }
    activeDownloadSource.value = null;
    nativeDownloadStatus.value = "idle";
    currentChunk.value = 0;
    totalChunks.value = 0;
    verifiedChunks.value = 0;
    phase.value = "updateReady";
    progress.value = 0;
    downloadedBytes.value = 0;
    statusMessage.value = `等待安装游戏，目标 ${latestUpdate.version}`;
  } catch (error) {
    phase.value = "error";
    const reason = error instanceof Error ? error.message : "检测失败";
    statusMessage.value = `无法读取游戏版本信息：${reason}`;
    operationErrorMessage.value = statusMessage.value;
    reportFailure("refresh-game-status", error);
  }
}

async function refreshAllStatus() {
  if (await checkLauncherUpdate() || launcherUpdateCheckError.value) return;
  await refreshGameStatus();
}

async function refreshTrafficQuota() {
  if (trafficQuotaPending.value) return;
  trafficQuotaPending.value = true;
  try {
    trafficQuota.value = await fetchTrafficQuotaStatus();
    await pauseOfficialSourceForLowTraffic();
  } catch (error) {
    console.warn("Unable to query server traffic quota", error);
    trafficQuota.value = null;
  } finally {
    trafficQuotaPending.value = false;
  }
}

async function refreshGithubNetworkStatus() {
  if (githubNetworkPending.value) return;
  githubNetworkPending.value = true;
  try {
    githubNetworkStatus.value = await getGithubNetworkStatus();
    const warning = githubNetworkWarning(githubNetworkStatus.value);
    void writeLauncherLog(
      warning ? "warning" : "info",
      "github.network-check",
      warning || "Github 网络连接正常",
      githubNetworkStatus.value,
    );
  } catch (error) {
    githubNetworkStatus.value = { proxyDetected: false, reachable: false, latencyMs: null };
    void writeLauncherLog("warning", "github.network-check", "Github 网络检测失败", error);
  } finally {
    githubNetworkPending.value = false;
  }
}

function ensureOfficialTrafficAvailable() {
  if (downloadSource.value !== "official" || !officialTrafficBlocked.value) return true;
  operationErrorMessage.value = "服务器当前流量不足，请更换下载源。";
  statusMessage.value = operationErrorMessage.value;
  currentPageIndex.value = 0;
  return false;
}

async function pauseOfficialSourceForLowTraffic() {
  if (
    pausingOfficialDownload ||
    activeDownloadSource.value !== "official" ||
    phase.value !== "downloading" ||
    !officialTrafficBlocked.value
  ) return;

  pausingOfficialDownload = true;
  operationErrorMessage.value = "服务器当前流量不足，请更换下载源。";
  statusMessage.value = "服务器流量不足，正在暂停 OSS 下载";
  try {
    await pauseGameDownload();
  } catch (error) {
    reportFailure("pause-low-traffic-download", error);
  } finally {
    pausingOfficialDownload = false;
  }
}

async function handleLauncherUpdateCheck() {
  if (phase.value === "launcherUpdateReady") {
    currentPageIndex.value = 1;
    await beginLauncherUpdate();
    return;
  }
  if (phase.value === "launcherUpdating") {
    if (!window.confirm("是否取消启动器更新下载？")) return;
    phase.value = "launcherChecking";
    statusMessage.value = "正在取消启动器更新";
    await cancelLauncherUpdate();
    await refreshAllStatus();
    return;
  }
  if (["launcherUpdateInstall", "launcherInstalling"].includes(phase.value)) {
    currentPageIndex.value = 1;
    return;
  }

  const previous = {
    phase: phase.value,
    statusMessage: statusMessage.value,
    progress: progress.value,
    downloadedBytes: downloadedBytes.value,
    totalBytes: totalBytes.value,
  };
  const hasUpdate = await checkLauncherUpdate();
  if (hasUpdate) return;
  phase.value = previous.phase;
  statusMessage.value = previous.statusMessage;
  progress.value = previous.progress;
  downloadedBytes.value = previous.downloadedBytes;
  totalBytes.value = previous.totalBytes;
}

function showPage(index: number) {
  currentPageIndex.value = Math.max(0, Math.min(launcherPages.length - 1, index));
}

function movePage(delta: number) {
  showPage(currentPageIndex.value + delta);
}

function handleTouchStart(event: TouchEvent) {
  touchStartX = event.changedTouches[0]?.clientX || 0;
}

function handleTouchEnd(event: TouchEvent) {
  const endX = event.changedTouches[0]?.clientX || touchStartX;
  const distance = endX - touchStartX;
  if (Math.abs(distance) < 64) return;
  movePage(distance > 0 ? -1 : 1);
}

async function handlePrimaryAction() {
  if (launcherAccessLocked.value && !["launcherUpdateReady", "launcherUpdateInstall"].includes(phase.value)) {
    if (launcherUpdateCheckError.value) await refreshAllStatus();
    return;
  }
  if (phase.value === "launcherUpdateReady") {
    await beginLauncherUpdate();
    return;
  }
  if (phase.value === "launcherUpdateInstall") {
    phase.value = "launcherInstalling";
    statusMessage.value = "请在系统界面确认覆盖安装零境启动器";
    try {
      await installLauncherUpdate();
    } catch (error) {
      phase.value = "launcherUpdateInstall";
      statusMessage.value = error instanceof Error ? error.message : "无法打开启动器更新安装界面";
    }
    return;
  }
  if (phase.value === "updateReady" || phase.value === "paused") {
    await beginRealDownload();
    return;
  }
  if (phase.value === "downloading") {
    statusMessage.value = "正在暂停下载";
    await pauseGameDownload();
    await waitForNativeDownloadStatus(["paused", "idle", "error"]);
    return;
  }
  if (phase.value === "readyInstall") {
    operationErrorMessage.value = "";
    gameInstallPending = true;
    await reportInstallTrace("install-action-start", "apk");
    phase.value = "installing";
    statusMessage.value = "正在打开系统安装器";
    try {
      await installDownloadedApk();
      void reportInstallTrace("install-native-started", "apk");
      statusMessage.value = "请在系统安装界面确认安装游戏";
    } catch (error) {
      gameInstallPending = false;
      phase.value = "readyInstall";
      operationErrorMessage.value = error instanceof Error ? error.message : "无法打开系统安装器";
      statusMessage.value = operationErrorMessage.value;
      reportFailure("install-game", error);
    }
    return;
  }
  if (phase.value === "error") {
    await refreshAllStatus();
  }
}

async function beginLauncherUpdate() {
  if (!launcherUpdateInfo.value) {
    await refreshAllStatus();
    return;
  }
  try {
    phase.value = "launcherUpdating";
    progress.value = 0;
    downloadedBytes.value = 0;
    totalBytes.value = launcherUpdateInfo.value.asset.sizeBytes;
    statusMessage.value = `正在下载启动器 ${launcherUpdateInfo.value.versionName}`;
    await startLauncherUpdate(launcherUpdateInfo.value);
  } catch (error) {
    phase.value = "error";
    statusMessage.value = error instanceof Error ? error.message : "无法开始启动器更新";
  }
}

async function ensureLatestLauncherForNetworkDownload() {
  const previousPhase = phase.value;
  const updateRequired = await checkLauncherUpdate();
  if (!updateRequired && !launcherUpdateCheckError.value && launcherUpdateCheckCompleted.value) {
    phase.value = previousPhase;
    return true;
  }
  statusMessage.value = launcherUpdateCheckError.value
    ? "无法确认启动器是否为最新版本，已停止游戏下载"
    : "必须先更新到最新启动器才能下载游戏";
  return false;
}

async function beginRealDownload() {
  if (!(await ensureLatestLauncherForNetworkDownload())) return;
  if (!updateInfo.value) {
    await refreshGameStatus();
    return;
  }
  if (downloadSource.value === "official") {
    await refreshTrafficQuota();
    if (!ensureOfficialTrafficAvailable()) return;
  }
  try {
    const plan = buildAndroidDownloadPlan(updateInfo.value, downloadSource.value);
    activeDownloadSource.value = downloadSource.value;
    nativeDownloadStatus.value = "downloading";
    currentChunk.value = 1;
    totalChunks.value = plan.chunks.length;
    verifiedChunks.value = 0;
    totalBytes.value = plan.totalBytes;
    phase.value = "downloading";
    statusMessage.value = "正在启动后台下载服务";
    await startGameDownload(plan);
  } catch (error) {
    phase.value = "error";
    statusMessage.value = error instanceof Error ? error.message : "无法开始下载";
    reportFailure("start-download", error);
  }
}

async function importGameChunksFromDevice() {
  if (["installing", "readyInstall"].includes(phase.value)) return;
  try {
    if (["downloading", "paused", "verifying"].includes(phase.value)) {
      if (!window.confirm("导入碎片会取消当前下载，并清理已经下载一半的缓存。是否继续？")) return;
      statusMessage.value = "正在停止当前下载并清理缓存";
      await cancelGameDownload();
      await waitForNativeDownloadStatus(["idle"]);
    }
    const currentUpdate = updateInfo.value ?? await checkLatestAndroidGame();
    updateInfo.value = currentUpdate;
    const plan = buildAndroidDownloadPlan(currentUpdate, downloadSource.value);
    activeDownloadSource.value = downloadSource.value;
    totalBytes.value = plan.totalBytes;
    totalChunks.value = plan.chunks.length;
    phase.value = "checking";
    statusMessage.value = "请选择包含全部游戏碎片的文件夹";
    await importGameChunks(plan);
  } catch (error) {
    phase.value = "error";
    statusMessage.value = error instanceof Error ? error.message : "无法导入游戏碎片";
    reportFailure("import-game-chunks", error);
  }
}

watch(downloadSource, (source) => {
  saveAndroidDownloadSource(source);
  void writeLauncherLog("info", "settings.download-source", source);
  if (source === "official") void refreshTrafficQuota();
  if (source === "github") void refreshGithubNetworkStatus();
});
watch(phase, (value, previous) => {
  void writeLauncherLog(value === "error" ? "error" : "info", "launcher.phase", `${previous} -> ${value}`, {
    statusMessage: statusMessage.value,
    nativeStatus: nativeDownloadStatus.value,
  });
});
watch(currentPageIndex, (pageIndex) => {
  void writeLauncherLog("info", "ui.page", launcherPages[pageIndex], { pageIndex });
  if (pageIndex === 0) {
    void refreshTrafficQuota();
    void refreshLauncherPermissionStatus();
  }
});

async function handleGameManagementAction() {
  const action = gameManagementAction.value;
  if (!action) return;

  const confirmText = action === "cancelDownload"
    ? "是否取消当前下载？已经下载的分片也会被删除。"
    : action === "deletePackage"
      ? "是否删除已经下载完成的游戏安装包？"
      : "是否清除当前未完成的下载文件？";
  if (!window.confirm(confirmText)) return;

  phase.value = "checking";
  statusMessage.value = action === "cancelDownload" ? "正在取消并清理下载文件" : "正在删除下载文件";
  try {
    await cancelGameDownload();
    await waitForNativeDownloadStatus(["idle"]);
    activeDownloadSource.value = null;
  } catch (error) {
    phase.value = "error";
    statusMessage.value = error instanceof Error ? error.message : "无法清理下载文件";
  }
}

function handleVisibilityChange() {
  if (document.visibilityState === "visible") {
    void refreshLauncherPermissionStatus();
    void refreshTrafficQuota();
  }
  if (document.visibilityState === "visible" && gameInstallPending) {
    gameInstallPending = false;
    void (async () => {
      await reportInstallTrace("install-return", "apk");
      await refreshAllStatus();
    })();
    return;
  }
  if (document.visibilityState === "visible" && ["installing", "launcherInstalling"].includes(phase.value)) {
    void refreshAllStatus();
  }
}

onMounted(async () => {
  await writeLauncherLog("info", "app.mounted", "Launcher view mounted");
  await refreshLauncherLogInfo();
  progressListener = await addDownloadProgressListener(applyNativeState);
  launcherProgressListener = await addLauncherUpdateProgressListener(applyLauncherUpdateState);
  document.addEventListener("visibilitychange", handleVisibilityChange);
  await Promise.all([
    refreshAllStatus(),
    refreshTrafficQuota(),
    refreshLauncherPermissionStatus(),
    ...(downloadSource.value === "github" ? [refreshGithubNetworkStatus()] : []),
  ]);
  trafficQuotaRefreshTimer = window.setInterval(() => void refreshTrafficQuota(), 5 * 60 * 1000);
  await writeLauncherLog("info", "app.ready", "Launcher status initialized", {
    launcherVersion: launcherVersionText.value,
    gameVersion: gameVersionText.value,
    targetVersion: targetVersionText.value,
    phase: phase.value,
    source: downloadSource.value,
  });
  await refreshLauncherLogInfo();
});

onBeforeUnmount(() => {
  document.removeEventListener("visibilitychange", handleVisibilityChange);
  void progressListener?.remove();
  void launcherProgressListener?.remove();
  if (trafficQuotaRefreshTimer !== undefined) window.clearInterval(trafficQuotaRefreshTimer);
});
</script>

<template>
  <main class="launcher-shell">
    <section class="launcher-frame">
      <header class="topbar">
        <img class="brand-logo" src="/launcher/logo_white.png" alt="零境交错:空界幻境" />
        <div class="top-versions">
          <span>启动器版本</span><strong>{{ launcherVersionText }}</strong>
          <span>游戏版本</span><strong>{{ gameVersionText }}</strong>
        </div>
        <div class="page-title">
          <small>{{ pageNumberText }}</small>
          <strong>{{ currentPageName }}</strong>
        </div>
      </header>

      <button class="page-arrow left" type="button" aria-label="上一页" :disabled="currentPageIndex === 0" @click="movePage(-1)">
        <ChevronLeft :size="42" />
      </button>
      <button class="page-arrow right" type="button" aria-label="下一页" :disabled="currentPageIndex === launcherPages.length - 1" @click="movePage(1)">
        <ChevronRight :size="42" />
      </button>

      <div class="pages" @touchstart.passive="handleTouchStart" @touchend.passive="handleTouchEnd">
        <section class="page" :class="{ active: currentPageIndex === 0, 'exit-left': currentPageIndex > 0, 'has-task-dock': showGlobalProgress }" aria-label="设置">
          <div class="panel-page settings-panel">
            <aside class="panel-index settings-index">
              <button class="settings-nav-button" :class="{ active: activeSettingsNav === 'preferences' }" type="button" @click="scrollSettingsTo('preferences')"><Settings :size="24" /><span>偏好设置</span></button>
              <button class="settings-nav-button" :class="{ active: activeSettingsNav === 'download' }" type="button" @click="scrollSettingsTo('download')"><Download :size="24" /><span>下载</span></button>
              <button class="settings-nav-button" :class="{ active: activeSettingsNav === 'game' }" type="button" @click="scrollSettingsTo('game')"><Gamepad2 :size="24" /><span>游戏</span></button>
              <button class="settings-nav-button" :class="{ active: activeSettingsNav === 'about' }" type="button" @click="scrollSettingsTo('about')"><Info :size="24" /><span>关于</span></button>
            </aside>
            <div ref="settingsBodyRef" class="panel-body settings-body">
              <section class="setting-group" data-settings-section="preferences">
                <div class="setting-copy">
                  <strong>下载来源</strong>
                  <span>{{ downloadSourceName }}</span>
                </div>
                <div class="source-options" data-settings-section="download" :class="{ locked: downloadSourceLocked }">
                  <button type="button" :class="{ selected: downloadSource === 'official' }" :disabled="downloadSourceLocked" @click="downloadSource = 'official'">
                    <strong>零境交错源</strong><span>{{ officialTrafficBlocked ? '流量不足' : '高速下载' }}</span>
                  </button>
                  <button type="button" :class="{ selected: downloadSource === 'github' }" :disabled="downloadSourceLocked" @click="downloadSource = 'github'">
                    <strong>Github 源</strong><span>需要魔法</span>
                  </button>
                </div>
                <p v-if="downloadSourceLocked" class="settings-hint">请先暂停当前任务，再切换下载源。</p>
              </section>

              <section v-if="downloadSource === 'official'" class="traffic-quota" :class="{ low: officialTrafficBlocked, unavailable: !trafficQuota?.available }">
                <div class="traffic-quota-header">
                  <strong>服务器可用下载流量</strong>
                  <span>{{ trafficQuotaText }}</span>
                </div>
                <div v-if="trafficQuota?.available" class="traffic-quota-track" aria-hidden="true"><span :style="{ width: `${trafficQuotaPercent}%` }"></span></div>
                <small>{{ officialTrafficBlocked ? trafficQuotaHint : trafficQuotaExpiryText || trafficQuotaHint }}</small>
              </section>

              <section v-else class="github-network-status" :class="{ warning: Boolean(githubNetworkWarningText) }">
                <div class="github-network-header">
                  <strong>Github 网络检测</strong>
                  <span>延迟 {{ githubLatencyText }}</span>
                </div>
                <small>代理：{{ githubProxyText }}</small>
                <small v-if="githubNetworkWarningText" class="github-network-warning">{{ githubNetworkWarningText }}</small>
                <small v-else>仅用于提示，不影响游戏下载。</small>
              </section>

              <section class="setting-row" data-settings-section="game">
                <div class="setting-copy"><strong>游戏管理</strong><span>{{ gameManagementHint }}</span></div>
                <div class="setting-actions">
                  <button type="button" :disabled="['installing', 'readyInstall'].includes(phase)" @click="importGameChunksFromDevice"><HardDriveDownload :size="22" />导入游戏碎片</button>
                  <button v-if="gameManagementAction" class="danger-action" type="button" @click="handleGameManagementAction">
                    <component :is="gameManagementIcon" :size="22" />{{ gameManagementButtonText }}
                  </button>
                </div>
              </section>

              <section class="setting-row">
                <div class="setting-copy"><strong>安装应用权限</strong><span :class="{ warning: launcherPermissionStatus && !launcherPermissionStatus.canInstallUnknownApps }">{{ installPermissionStatusText }}</span></div>
                <button type="button" @click="handleOpenInstallPermissionSettings"><Settings :size="22" />查看设置</button>
              </section>

              <section class="setting-row">
                <div class="setting-copy"><strong>后台下载权限</strong><span :class="{ warning: launcherPermissionStatus && !launcherPermissionStatus.batteryOptimizationIgnored }">{{ backgroundPermissionStatusText }}</span></div>
                <button type="button" @click="handleOpenBatteryOptimizationSettings"><BatteryCharging :size="22" />查看设置</button>
              </section>

              <section class="setting-row" data-settings-section="about">
                <div class="setting-copy">
                  <strong>日志与诊断</strong>
                  <span :class="{ warning: launcherLogUploadState === 'error' }">{{ launcherLogStatusText }}</span>
                </div>
                <button type="button" :disabled="launcherLogUploadState === 'uploading' || !launcherLogInfo?.hasLog" @click="uploadCurrentLauncherLog">
                  <UploadCloud :size="22" />{{ launcherLogButtonText }}
                </button>
              </section>

              <section class="setting-row">
                <div class="setting-copy">
                  <strong>启动器更新</strong>
                  <span :class="{ warning: launcherUpdateCheckError }">{{ launcherUpdateStatusText }}</span>
                </div>
                <button type="button" :disabled="phase === 'launcherChecking'" @click="handleLauncherUpdateCheck">
                  <RefreshCw :size="22" :class="{ spinning: phase === 'launcherChecking' }" />
                  {{ launcherUpdateButtonText }}
                </button>
              </section>
            </div>
          </div>
        </section>

        <section class="page" :class="{ active: currentPageIndex === 1, 'exit-left': currentPageIndex > 1 }" aria-label="首页">
          <div class="home-center">
            <p class="world-label">Crossing Void · illusion Dreamland</p>
            <h1>零境交错：空界幻境</h1>
            <p v-if="launcherUpdateCheckError || operationErrorMessage || phase === 'error'" class="update-warning"><CircleAlert :size="18" />{{ launcherUpdateCheckError || operationErrorMessage || statusMessage }}</p>
            <button class="primary-action" type="button" :disabled="actionDisabled" @click="handlePrimaryAction">
              <component :is="actionIcon" :size="48" :class="{ spinning: primaryActionSpinning }" />
              <span>{{ actionText }}</span>
            </button>
            <p class="target-version">{{ targetVersionLabel }}：<strong>{{ targetVersionText }}</strong></p>
          </div>
        </section>

        <section class="page" :class="{ active: currentPageIndex === 2, 'exit-left': currentPageIndex > 2 }" aria-label="公告">
          <article class="article"><Megaphone :size="30" /><time>2026 / 07 / 20</time><h2>Android 启动器测试</h2><p>启动器更新会优先于游戏更新检查。下载中断后将保留已经校验完成的资源分块。</p><h3>当前测试内容</h3><ul><li>启动器独立热更新</li><li>APK 与 OBB 分片下载</li><li>OSS 与 Github 下载源切换</li></ul></article>
        </section>

        <section class="page" :class="{ active: currentPageIndex === 3, 'exit-left': currentPageIndex > 3 }" aria-label="账号">
          <div class="panel-page">
            <aside class="panel-index"><Users :size="34" /><h2>账号</h2><div class="vertical-mark">官网<br />社区<br />开发动态</div></aside>
            <div class="panel-body account-grid">
              <a class="account-card" href="https://www.crossingvoid.top/" target="_blank" rel="noopener"><span class="account-icon">官</span><span><strong>零境交错官网</strong><small>下载与版本公告</small></span><ExternalLink :size="22" /></a>
              <a class="account-card" href="https://space.bilibili.com/534548" target="_blank" rel="noopener"><span class="account-icon">B</span><span><strong>哔哩哔哩</strong><small>开发记录与角色演示</small></span><ExternalLink :size="22" /></a>
              <a class="account-card" href="https://gitee.com/xiaojie578/CrossingVoid-Downloader-Android" target="_blank" rel="noopener"><span class="account-icon">G</span><span><strong>Gitee</strong><small>Android 启动器更新记录</small></span><ExternalLink :size="22" /></a>
            </div>
          </div>
        </section>

        <section class="page" :class="{ active: currentPageIndex === 4, 'exit-left': currentPageIndex > 4 }" aria-label="角色介绍">
          <div class="character-layout">
            <div class="character-image"></div>
            <article class="character-copy"><small>本期角色 · UNDERWORLD</small><h2>爱丽丝【UW】</h2><p>整合电脑版启动器的角色展示内容，横屏下保留完整立绘与必要的角色信息。</p><div class="tags"><span>双属性</span><span>物理防御</span><span>异能爆发</span><span>低耗费</span></div></article>
          </div>
        </section>

        <section class="page" :class="{ active: currentPageIndex === 5, 'exit-left': currentPageIndex > 5 }" aria-label="视频">
          <div class="video-page"><div class="featured-video"><Video :size="58" /></div><div class="video-list"><div><strong>版本预告</strong><span>最新内容</span></div><div><strong>角色演示</strong><span>战斗与技能展示</span></div><div><strong>开发记录</strong><span>启动器制作进度</span></div></div></div>
        </section>
      </div>

      <div class="pager" :class="{ 'task-active': showGlobalProgress }" aria-label="页面选择">
        <button v-for="(_, index) in launcherPages" :key="index" class="dot" :class="{ active: currentPageIndex === index }" type="button" :aria-label="`打开${launcherPages[index]}`" @click="showPage(index)"></button>
      </div>

      <Transition name="task-dock">
        <button v-if="showGlobalProgress" class="global-download" type="button" aria-label="返回首页查看当前任务" aria-live="polite" @click="showPage(1)">
          <span class="global-copy"><strong>{{ statusTitle }}</strong><small>{{ statusMessage }}</small></span>
          <span class="global-progress">
            <span class="progress-track"><span class="progress-fill" :class="{ active: progressAnimating }" :style="{ width: `${progress}%` }"></span></span>
            <span class="progress-meta"><small>{{ progressDetailText }}</small><small>{{ progressValueText }}</small></span>
          </span>
          <span class="global-percent"><strong>{{ Math.round(progress) }}%</strong><small>{{ displayedLatestVersion }}</small></span>
        </button>
      </Transition>
    </section>
  </main>
</template>
