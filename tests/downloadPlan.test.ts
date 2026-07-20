import { describe, expect, it } from "vitest";

import {
  buildAndroidDownloadPlan,
  launcherPhaseFromNativeState,
} from "../src/services/downloadPlan";
import type { AndroidGameUpdateInfo } from "../src/services/gameUpdate";

function createUpdate(): AndroidGameUpdateInfo {
  return {
    version: "V0.5.14",
    channel: "stable",
    hasUpdate: true,
    message: "",
    asset: {
      runtime: "Android",
      fileName: "CrossingVoid-Android-Package.zip",
      objectKey: "releases/V0.5.14/package.zip",
      sha256: "a".repeat(64),
      sizeBytes: 12,
      chunks: [
        {
          index: 2,
          count: 2,
          fileName: "package.zip.part002",
          objectKey: "releases/V0.5.14/chunks/package.zip.part002",
          sha256: "b".repeat(64),
          sizeBytes: 5,
        },
        {
          index: 1,
          count: 2,
          fileName: "package.zip.part001",
          objectKey: "releases/V0.5.14/chunks/package.zip.part001",
          sha256: "c".repeat(64),
          sizeBytes: 7,
        },
      ],
    },
  };
}

describe("Android download plan", () => {
  it("sorts and validates a complete chunk manifest", () => {
    const plan = buildAndroidDownloadPlan(createUpdate());

    expect(plan.productKey).toBe("crossingvoid-android-game");
    expect(plan.source).toBe("official");
    expect(plan.version).toBe("V0.5.14");
    expect(plan.chunks.map((chunk) => chunk.index)).toEqual([1, 2]);
    expect(plan.chunks.every((chunk) => chunk.downloadUrl === undefined)).toBe(true);
    expect(plan.totalBytes).toBe(12);
  });

  it("builds GitHub release URLs for every chunk", () => {
    const plan = buildAndroidDownloadPlan(createUpdate(), "github");

    expect(plan.source).toBe("github");
    expect(plan.chunks.map((chunk) => chunk.downloadUrl)).toEqual([
      "https://github.com/kirito0000001/CrossingVoid/releases/download/Android-V0.5.14/package.zip.part001",
      "https://github.com/kirito0000001/CrossingVoid/releases/download/Android-V0.5.14/package.zip.part002",
    ]);
  });

  it("rejects a manifest with a missing chunk", () => {
    const update = createUpdate();
    update.asset.chunks[1].index = 3;

    expect(() => buildAndroidDownloadPlan(update)).toThrow("分片序号不连续");
  });

  it("rejects a manifest whose chunk sizes do not match the archive", () => {
    const update = createUpdate();
    update.asset.chunks[1].sizeBytes = 6;

    expect(() => buildAndroidDownloadPlan(update)).toThrow("分片总大小");
  });
});

describe("native download state mapping", () => {
  it("restores active, paused, processing, ready and failed states", () => {
    expect(launcherPhaseFromNativeState("downloading")).toBe("downloading");
    expect(launcherPhaseFromNativeState("paused")).toBe("paused");
    expect(launcherPhaseFromNativeState("merging")).toBe("verifying");
    expect(launcherPhaseFromNativeState("extracting")).toBe("verifying");
    expect(launcherPhaseFromNativeState("ready")).toBe("readyInstall");
    expect(launcherPhaseFromNativeState("error")).toBe("error");
  });
});
