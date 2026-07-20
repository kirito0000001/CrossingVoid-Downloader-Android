import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const launcherPlugin = readFileSync(
  resolve(process.cwd(), "android/app/src/main/java/com/lingjing/launcher/android/AndroidLauncherPlugin.java"),
  "utf8",
);
const nativeBridge = readFileSync(resolve(process.cwd(), "src/services/androidLauncher.ts"), "utf8");
const downloadService = readFileSync(
  resolve(process.cwd(), "android/app/src/main/java/com/lingjing/launcher/android/GameDownloadService.java"),
  "utf8",
);
const launcherManifest = readFileSync(
  resolve(process.cwd(), "android/app/src/main/AndroidManifest.xml"),
  "utf8",
);
describe("same-package OBB preparation", () => {
  it("prepares OBB directly in the disposable installer's package-owned OBB directory", () => {
    expect(downloadService).toContain("getObbDir()");
    expect(downloadService).toContain('target.getName() + ".extracting"');
    expect(downloadService).not.toContain('File obbDir = new File(preparedDir, "obb")');
    expect(downloadService).toContain('"APK 和 OBB 已准备完成"');
  });

  it("does not call a cross-package OBB importer", () => {
    expect(launcherPlugin).not.toContain("installPreparedObb");
    expect(launcherPlugin).not.toContain("ObbImportActivity");
    expect(nativeBridge).not.toContain("installPreparedObb");
  });

  it("removes the obsolete game-to-launcher result receiver", () => {
    expect(launcherManifest).not.toContain("com.lingjing.launcher.android.action.OBB_INSTALL_FAILED");
    expect(existsSync(resolve(process.cwd(), "android/app/src/main/java/com/lingjing/launcher/android/ObbInstalledReceiver.java"))).toBe(false);
  });
});
