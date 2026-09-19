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
    expect(publisherSource).not.toContain('manifestRepositoryPath = "launcher/android-latest.json"');
    expect(publisherSource).not.toContain("com.TFAC.CorssingVoidLauncher");
  });

  it("uses the supplied square artwork without an adaptive-icon crop layer", () => {
    expect(existsSync(resolve(process.cwd(), "android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"))).toBe(false);
    expect(existsSync(resolve(process.cwd(), "android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml"))).toBe(false);
  });

  it("keeps Android launcher assets in Gitee while clients read the website manifest", () => {
    const expectedRepository = "xiaojie578/CrossingVoid-Downloader-Android";
    const oldMixedRepository = "xiaojie578/CrossingVoid-Downloader/raw";

    expect(publisherSource).toContain(expectedRepository);
    expect(launcherUpdateSource).toContain("https://www.crossingvoid.top/manifests/launcher/android-latest.json");
    expect(appSource).toContain(`https://gitee.com/${expectedRepository}`);
    expect(publisherSource).not.toContain(oldMixedRepository);
    expect(launcherUpdateSource).not.toContain(oldMixedRepository);
  });

  it("locks normal builds to versionCode 1 and requires an explicit recovery build for 1001003", () => {
    expect(publisherSource).toContain("[switch]$RecoveryBuild");
    expect(publisherSource).toContain("$NormalVersionCode = 1");
    expect(publisherSource).toContain("$RecoveryVersionCode = 1001003");
    expect(publisherSource).not.toContain("[int]$VersionCode,");
    expect(publisherSource).toContain("$VersionCode = if ($RecoveryBuild) { $RecoveryVersionCode } else { $NormalVersionCode }");
  });

  it("publishes the Android launcher manifest to the stable website path", () => {
    expect(publisherSource).toContain("Publish-WebsiteManifest");
    expect(publisherSource).toContain("C:\\inetpub\\wwwroot\\manifests\\launcher\\android-latest.json");
    expect(publisherSource).toContain("scp");
    expect(publisherSource).toContain("icacls.exe `$target /reset");
  });
});
