import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const gradleSource = readFileSync(resolve(process.cwd(), "android/app/build.gradle"), "utf8");
const publisherSource = readFileSync(resolve(process.cwd(), "Scripts/Publish-AndroidLauncher.ps1"), "utf8");
const launcherUpdateSource = readFileSync(resolve(process.cwd(), "src/services/launcherUpdate.ts"), "utf8");
const appSource = readFileSync(resolve(process.cwd(), "src/App.vue"), "utf8");

describe("Android launcher release publishing", () => {
  it("uses the game package and signing identity for disposable replacement", () => {
    expect(gradleSource).toContain('applicationId "com.TFAC.CorssingVoid"');
    expect(gradleSource).toContain("launcherVersionCode");
    expect(gradleSource).toContain("launcherVersionName");
    expect(gradleSource).toContain(".android/debug.keystore");
    expect(gradleSource).toContain("signingConfigs.release");
    expect(publisherSource).toContain("56f1b0b317e38985808ddd9ee03f3785a8c0190bf32ff2791ba6a3f2c7ba2d92");
  });

  it("publishes an isolated disposable-installer release and manifest", () => {
    expect(publisherSource).toContain("crossingvoid-launcher-android-installer");
    expect(publisherSource).toContain("launcher/android-installer-latest.json");
    expect(publisherSource).toContain("android-installer-v");
    expect(publisherSource).toContain("CrossingVoidInstaller-");
    expect(publisherSource).toContain("SHA256");
    expect(publisherSource).toContain("assembleRelease");
    expect(publisherSource).not.toMatch(/MigrationBridge|AndroidMigrationBridge/);
    expect(publisherSource).not.toContain("launcher/android-latest.json");
    expect(publisherSource).not.toContain("com.TFAC.CorssingVoidLauncher");
  });

  it("uses the supplied square artwork without an adaptive-icon crop layer", () => {
    expect(existsSync(resolve(process.cwd(), "android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"))).toBe(false);
    expect(existsSync(resolve(process.cwd(), "android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml"))).toBe(false);
  });

  it("keeps Android launcher updates in the Android-only Gitee repository", () => {
    const expectedRepository = "xiaojie578/CrossingVoid-Downloader-Android";
    const oldMixedRepository = "xiaojie578/CrossingVoid-Downloader/raw";

    expect(publisherSource).toContain(expectedRepository);
    expect(launcherUpdateSource).toContain(`${expectedRepository}/raw/master/launcher/android-installer-latest.json`);
    expect(appSource).toContain(`https://gitee.com/${expectedRepository}`);
    expect(publisherSource).not.toContain(oldMixedRepository);
    expect(launcherUpdateSource).not.toContain(oldMixedRepository);
  });
});
