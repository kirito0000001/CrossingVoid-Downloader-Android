import { describe, expect, it } from "vitest";

import { canUseAndroidLauncherNetwork } from "../src/services/launcherNetworkPolicy";

describe("Android launcher network policy", () => {
  it("allows game network operations only after the current launcher is verified", () => {
    expect(canUseAndroidLauncherNetwork(true, "updateReady")).toBe(true);
    expect(canUseAndroidLauncherNetwork(false, "launcherChecking")).toBe(false);
    expect(canUseAndroidLauncherNetwork(true, "launcherUpdateReady")).toBe(false);
    expect(canUseAndroidLauncherNetwork(true, "launcherUpdating")).toBe(false);
    expect(canUseAndroidLauncherNetwork(true, "launcherUpdateInstall")).toBe(false);
    expect(canUseAndroidLauncherNetwork(true, "launcherInstalling")).toBe(false);
  });

  it("stays open when the launcher update check itself failed", () => {
    // 2026-09-30 现场：www.crossingvoid.top 的证书过期，启动器更新清单取不回来。
    // 旧版把"检查失败"也当成锁网条件，于是主按钮永远停在「重新检测」（点一次失败
    // 一次，玩家只能杀进程），错误日志也传不出去 —— 最需要诊断的现场恰好是诊断
    // 被关掉的现场。失败只是"不知道"，不是"必须升级"。
    expect(canUseAndroidLauncherNetwork(true, "error")).toBe(true);
  });
});
