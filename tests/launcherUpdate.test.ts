import { describe, expect, it } from "vitest";

import {
  parseAndroidLauncherManifest,
  shouldInstallLauncherUpdate,
} from "../src/services/launcherUpdate";

function createManifest() {
  return {
    schemaVersion: 1,
    productKey: "crossingvoid-launcher-android-installer",
    versionName: "1.0.1",
    versionCode: 2,
    notes: "测试热更新",
    publishedAt: "2026-07-19T22:30:00+08:00",
    asset: {
      fileName: "CrossingVoidLauncher-1.0.1-Beta.apk",
      url: "https://gitee.com/example/releases/download/android-launcher-v1.0.1/CrossingVoidLauncher.apk",
      sizeBytes: 9_000_000,
      sha256: "a".repeat(64),
    },
  };
}

describe("Android launcher hot update manifest", () => {
  it("parses a signed APK release", () => {
    const manifest = parseAndroidLauncherManifest(createManifest());

    expect(manifest.productKey).toBe("crossingvoid-launcher-android-installer");
    expect(manifest.versionName).toBe("1.0.1");
    expect(manifest.versionCode).toBe(2);
    expect(manifest.asset.sizeBytes).toBe(9_000_000);
  });

  it("parses a Gitee text/plain JSON response", () => {
    const manifest = parseAndroidLauncherManifest(JSON.stringify(createManifest()));

    expect(manifest.versionName).toBe("1.0.1");
    expect(manifest.versionCode).toBe(2);
  });

  it("uses semantic launcher versions when the game-coordinated versionCode is equal", () => {
    const manifest = parseAndroidLauncherManifest(createManifest());

    expect(shouldInstallLauncherUpdate(1, "1.0.0", manifest)).toBe(true);
    expect(shouldInstallLauncherUpdate(2, "1.0.0", manifest)).toBe(true);
    expect(shouldInstallLauncherUpdate(2, "1.0.1", manifest)).toBe(false);
    expect(shouldInstallLauncherUpdate(2, "1.0.2", manifest)).toBe(false);
    expect(shouldInstallLauncherUpdate(3, "1.0.0", manifest)).toBe(false);
  });

  it("rejects an invalid APK hash", () => {
    const payload = createManifest();
    payload.asset.sha256 = "not-a-sha256";

    expect(() => parseAndroidLauncherManifest(payload)).toThrow("SHA256");
  });

  it("rejects Beta suffixes for mobile launcher versions", () => {
    const payload = createManifest();
    payload.versionName = "1.0.2-Beta";

    expect(() => parseAndroidLauncherManifest(payload)).toThrow("手机版启动器版本号");
  });
});
