import { describe, expect, it } from "vitest";
import { isVersionAtLeast, parseAndroidUpdateCheckResponse } from "../src/services/gameUpdate";

describe("Android game update manifest", () => {
  it("uses the remote game version and archive metadata", () => {
    const update = parseAndroidUpdateCheckResponse({
      success: true,
      hasUpdate: true,
      message: "发现新版本 V0.5.14。",
      manifest: {
        productKey: "crossingvoid-android-game",
        version: "V0.5.14",
        channel: "stable",
        asset: {
          runtime: "Android",
          fileName: "CrossingVoid-Android-Package.zip",
          objectKey: "Akege304/CrossingVoid/Android/package.zip",
          sha256: "abc123",
          sizeBytes: 2_018_663_318,
          chunks: [{ index: 1, count: 20, fileName: "part001", objectKey: "chunks/part001" }],
        },
      },
    });

    expect(update.version).toBe("V0.5.14");
    expect(update.hasUpdate).toBe(true);
    expect(update.asset.runtime).toBe("Android");
    expect(update.asset.chunks).toHaveLength(1);
  });

  it("surfaces backend failures instead of keeping a hard-coded version", () => {
    expect(() =>
      parseAndroidUpdateCheckResponse({ success: false, message: "没有找到 Android 更新清单" }),
    ).toThrow("没有找到 Android 更新清单");
  });

  it("compares the installed version with a V-prefixed remote version", () => {
    expect(isVersionAtLeast("0.5.14", "V0.5.14")).toBe(true);
    expect(isVersionAtLeast("0.5.13", "V0.5.14")).toBe(false);
  });
});
