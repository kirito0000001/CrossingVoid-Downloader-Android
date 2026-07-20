import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const activitySource = readFileSync(
  resolve(process.cwd(), "android/app/src/main/java/com/lingjing/launcher/android/MainActivity.java"),
  "utf8",
);
const appSource = readFileSync(resolve(process.cwd(), "src/App.vue"), "utf8");
const themeSource = readFileSync(resolve(process.cwd(), "android/app/src/main/res/values/styles.xml"), "utf8");
const manifestSource = readFileSync(resolve(process.cwd(), "android/app/src/main/AndroidManifest.xml"), "utf8");
const styleSource = readFileSync(resolve(process.cwd(), "src/style.css"), "utf8");
const indexSource = readFileSync(resolve(process.cwd(), "index.html"), "utf8");
const capacitorConfigSource = readFileSync(resolve(process.cwd(), "capacitor.config.ts"), "utf8");

describe("Android immersive launcher UI", () => {
  it("hides system bars and allows transient swipe access", () => {
    expect(activitySource).toContain("WindowInsetsCompat.Type.systemBars()");
    expect(activitySource).toContain("BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE");
    expect(activitySource).toContain("LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES");
    expect(activitySource).toContain("LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS");
    expect(activitySource).toContain("requestNotificationPermission");
    expect(activitySource).toContain("Manifest.permission.POST_NOTIFICATIONS");
    expect(themeSource).toContain('<item name="android:windowFullscreen">true</item>');
    expect(themeSource).toContain('<item name="android:navigationBarColor">@android:color/transparent</item>');
    expect(themeSource).toContain('<item name="android:windowLayoutInDisplayCutoutMode">shortEdges</item>');
    expect(themeSource).toContain('<item name="postSplashScreenTheme">@style/AppTheme.NoActionBar</item>');
    expect(activitySource).not.toContain("FLAG_LAYOUT_NO_LIMITS");
    expect(activitySource).not.toContain("SYSTEM_UI_FLAG_IMMERSIVE_STICKY");
    expect(manifestSource).not.toContain('android:resizeableActivity="true"');
    expect(manifestSource).not.toContain("android:maxAspectRatio");
    expect(indexSource).toContain("viewport-fit=cover");
    expect(capacitorConfigSource).toContain("SystemBars");
    expect(capacitorConfigSource).toContain('insetsHandling: "disable"');
    expect(capacitorConfigSource).toContain("hidden: true");

    const superCreateIndex = activitySource.indexOf("super.onCreate(savedInstanceState)");
    const firstImmersiveCallIndex = activitySource.indexOf("enableImmersiveMode();");
    expect(superCreateIndex).toBeGreaterThanOrEqual(0);
    expect(firstImmersiveCallIndex).toBeGreaterThan(superCreateIndex);
  });

  it("shows the floating task dock only for progress-bearing phases", () => {
    expect(appSource).toContain('const showGlobalProgress = computed(() =>');
    expect(appSource).toContain('<Transition name="task-dock">');
    expect(appSource).toContain('v-if="showGlobalProgress" class="global-download"');
    expect(appSource).toContain("合并安装包");
    expect(appSource).toContain("解压游戏资源");
    expect(appSource).toContain("Crossing Void · illusion Dreamland");
    expect(styleSource).toContain("--cv-download-progress-start");
    expect(styleSource).toContain("border-radius: 999px");
    expect(styleSource).toContain("scale(1.296)");
    expect(styleSource).toContain("margin-left: clamp(-72px, -6vw, -48px)");
    expect(styleSource).toContain("padding-left: clamp(7px, 0.75vw, 10px)");
    expect(styleSource).toContain(".page.has-task-dock");
    expect(appSource).toContain("偏好设置");
    expect(appSource).toContain("settings-nav-button");
    expect(appSource).toContain("activeSettingsNav");
    expect(styleSource).toContain("margin: 0 0 24px");
    expect(appSource).toContain("下载游戏中：${activeDownloadSourceName.value}");
    expect(appSource).toContain("预计剩余");
    expect(appSource).toContain("服务器可用下载流量");
    expect(appSource).toContain("游戏最新版本");
  });
});
