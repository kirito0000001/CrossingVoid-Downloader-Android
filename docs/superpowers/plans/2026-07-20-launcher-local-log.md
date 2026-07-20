# Android Launcher Local Log Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a 10 MiB rolling launcher log with manual upload and temporarily force Github game downloads.

**Architecture:** Java owns the private log file, size trimming, installation UUID, and direct HTTP upload. Vue records user-visible state transitions through a thin Capacitor bridge and exposes upload controls in Settings. The existing PowerShell diagnostics listener receives and stores explicitly uploaded logs.

**Tech Stack:** Vue 3, TypeScript, Capacitor 8, Java/Android, PowerShell HttpListener, Vitest

---

### Task 1: Lock Game Downloads To Github

**Files:**
- Modify: `src/services/downloadSource.ts`
- Modify: `src/App.vue`
- Test: `tests/downloadSource.test.ts`

- [x] Write tests asserting unknown, saved OSS, and missing values normalize to `github`.
- [x] Run `npm.cmd test -- --run tests/downloadSource.test.ts` and verify the new assertions fail.
- [x] Change source normalization/defaults to Github and disable the OSS settings button with `暂时关闭` text.
- [x] Run the focused test and verify it passes.

### Task 2: Native Rolling Log Store

**Files:**
- Create: `android/app/src/main/java/com/lingjing/launcher/android/LauncherLogStore.java`
- Test: `tests/launcherLogIntegration.test.ts`

- [x] Write source-level integration assertions for a 10 MiB cap, UTF-8 append, oldest-line trimming, random installation UUID, metadata, and upload methods.
- [x] Run the focused test and verify the missing implementation fails.
- [x] Implement synchronized append and trimming, metadata lookup, sanitization, and direct HTTPS log upload.
- [x] Run the focused test and verify it passes.

### Task 3: Capacitor Log Bridge And App Instrumentation

**Files:**
- Modify: `android/app/src/main/java/com/lingjing/launcher/android/AndroidLauncherPlugin.java`
- Modify: `src/services/androidLauncher.ts`
- Create: `src/services/launcherLog.ts`
- Modify: `src/App.vue`
- Modify: `src/main.ts`
- Test: `tests/launcherLogIntegration.test.ts`

- [x] Add failing assertions for `appendLauncherLog`, `getLauncherLogInfo`, `uploadLauncherLog`, global error hooks, action events, phase events, and deduplicated native progress logs.
- [x] Run the focused test and verify it fails for missing bridge and instrumentation.
- [x] Add native plugin methods and TypeScript wrappers.
- [x] Add application lifecycle, error, action, phase, update, download, and install logging without recording secrets or every progress tick.
- [x] Run the focused test and verify it passes.

### Task 4: Settings Upload UI

**Files:**
- Modify: `src/App.vue`
- Modify: `src/style.css`
- Test: `tests/launcherLogIntegration.test.ts`

- [x] Add failing assertions for the `日志与诊断` row, metadata text, upload button, pending state, completion state, and retryable failure state.
- [x] Run the focused test and verify it fails.
- [x] Implement the row using existing settings styling and refresh metadata after app start and upload.
- [x] Run the focused test and verify it passes.

### Task 5: Server Log Upload Endpoint

**Files:**
- Modify: `Server/LauncherDiagnosticsServer.ps1`
- Create: `tests/launcherDiagnosticsServer.test.ts`

- [x] Add failing assertions for the upload route, 10 MiB raw log ceiling, product validation, installation-ID sanitization, and upload directory.
- [x] Run the focused test and verify it fails.
- [x] Implement validated raw log upload while preserving the existing report route and health endpoint.
- [x] Run the focused test and verify it passes.
- [x] Deploy the script to `crossing-server`, restart only the diagnostics listener, and POST a small marked smoke-test log.

### Task 6: Build, Publish, And Verify

**Files:**
- Modify generated Android web assets through `npx cap sync android`
- Produce: `D:/启动器新包/AndroidLauncher/CrossingVoidLauncher-1.0.18-Android.apk`

- [x] Run `npm.cmd test` and require all tests to pass.
- [x] Run `npm.cmd run build` and require a successful Vite production build.
- [x] Publish version `1.0.18`, versionCode `19`, through `Scripts/Publish-AndroidLauncher.ps1`.
- [x] Re-read the online Gitee manifest and verify version, size, SHA-256, and HTTP 200 download.
- [ ] Upload a log from the installed launcher and confirm the server receives a phone-generated file.
