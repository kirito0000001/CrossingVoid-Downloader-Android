const LAUNCHER_UPDATE_PHASES = new Set([
  "launcherChecking",
  "launcherUpdateReady",
  "launcherUpdating",
  "launcherUpdateInstall",
  "launcherInstalling",
]);

/**
 * 是否允许启动器做网络请求（传日志、查流量、探 GitHub 连通性……）。
 *
 * 只有两种明确状态才锁：更新还没查完（含正在查），以及确实查到了待安装的更新。
 *
 * "更新检查失败"**不在**其列。上一版的签名里带 `checkError`，只要清单拉不到就一直
 * 锁着，于是 2026-09-30 现场（www.crossingvoid.top 证书过期、清单取不回来）出现
 * 两个症状：主按钮永远停在「重新检测」，点一次失败一次、玩家只能杀进程；错误日志
 * 也传不出去 —— 最需要诊断的现场恰好是诊断被关掉的现场。
 *
 * 清单站点连不上，不足以判定玩家必须升级：那只是"不知道"，不是"不合格"。
 */
export function canUseAndroidLauncherNetwork(checkCompleted: boolean, phase: string) {
  return checkCompleted && !LAUNCHER_UPDATE_PHASES.has(phase);
}
