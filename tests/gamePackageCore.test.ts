import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

import {
  GAME_PACKAGE_CORE_VERSION,
  buildGamePackagePlan,
  gamePackageManifestUrl,
  isSafeGamePackagePath,
  parseGamePackageManifest,
  parseLatestPointer,
  type GamePackageFile,
} from "../src/services/gamePackage";

/**
 * 共用内核守卫：`src/services/gamePackage.ts` 与 PC 仓库里的
 * `src/gamePackage.ts` **逐字相同**（换行按 LF 计算）。
 * 改动内核时，两边这条测试的哈希要一起更新。
 */
describe("shared game package core", () => {
  it("keeps the core file identical across repos (hash check)", () => {
    const source = readFileSync(resolve(process.cwd(), "src/services/gamePackage.ts"), "utf8")
      .split("\r\n")
      .join("\n");
    expect(GAME_PACKAGE_CORE_VERSION).toBe("1");
    expect(createHash("sha256").update(source, "utf8").digest("hex")).toBe(
      "141469941f0cebd65a954ca4e806d513aa5fef0d1bb0612b6a4a8bb614894783",
    );
  });

  it("treats path safety the same way the native side does", () => {
    expect(isSafeGamePackagePath("CrossingVoid/Content/Paks/global.ucas")).toBe(true);
    expect(isSafeGamePackagePath("../escape.bin")).toBe(false);
    expect(isSafeGamePackagePath("/absolute.bin")).toBe(false);
    expect(isSafeGamePackagePath("C:/drive.bin")).toBe(false);
  });

  it("parses the pointer and manifest the download site really serves", () => {
    const pointer = parseLatestPointer({
      schemaVersion: 1,
      productSegment: "crossingvoid-android",
      version: "0.5.14",
      manifestUrl:
        "https://dl.crossingvoid.top/games/crossingvoid-android/manifests/0.5.14.json",
      generatedAt: "2026-09-20T13:05:55Z",
    });
    expect(pointer.version).toBe("0.5.14");
    expect(gamePackageManifestUrl("crossingvoid-android")).toBe(
      "https://dl.crossingvoid.top/games/crossingvoid-android/latest.json",
    );

    const file: GamePackageFile = {
      path: "CrossingVoid-Android-Shipping-arm64.apk",
      sizeBytes: 10,
      sha256: "a".repeat(64),
    };
    const manifest = parseGamePackageManifest(
      {
        schemaVersion: 1,
        productKey: "crossingvoid-android-game",
        runtime: "Android",
        version: "0.5.14",
        channel: "stable",
        baseUrl: "https://dl.crossingvoid.top/games/crossingvoid-android/0.5.14/",
        generatedAt: "",
        files: [file],
        patches: [],
      },
      { productKey: "crossingvoid-android-game", runtime: "Android" },
    );
    expect(manifest.files).toHaveLength(1);

    const plan = buildGamePackagePlan(manifest, null, []);
    expect(plan.download).toHaveLength(1);
  });
});
