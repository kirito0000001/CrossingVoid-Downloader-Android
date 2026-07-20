# Android Same-Package Installer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a disposable Android launcher that owns the game package, prepares OBB directly, and is replaced by the game APK.

**Architecture:** Keep the existing Vue and native download service. Change Android identity/signing at build time, move OBB extraction into the package-owned OBB directory, simplify the install state to one APK replacement, and publish through a separate installer manifest.

**Tech Stack:** Vue 3, TypeScript, Capacitor 8, Java/Android, Gradle, PowerShell, Vitest

---

### Task 1: Package Identity And Publishing Contract

**Files:**
- Modify: `android/app/build.gradle`
- Modify: `Scripts/Publish-AndroidLauncher.ps1`
- Modify: `src/services/launcherUpdate.ts`
- Test: `tests/launcherPublishing.test.ts`
- Test: `tests/launcherUpdate.test.ts`

- [ ] Add failing assertions for `com.TFAC.CorssingVoid`, the game certificate digest, installer release tags, a separate manifest, and equal-code semantic updates.
- [ ] Configure release signing with the existing game keystore without storing the private key in the repository.
- [ ] Validate package, version, and signer before producing or uploading an installer.
- [ ] Keep the independent launcher manifest unchanged.
- [ ] Run focused publishing and update tests.

### Task 2: App-Owned OBB Preparation

**Files:**
- Modify: `android/app/src/main/java/com/lingjing/launcher/android/GameDownloadService.java`
- Test: `tests/obbBridge.test.ts`

- [ ] Add failing assertions that OBB extraction uses `getObbDir()` and no longer depends on `ObbImportActivity`.
- [ ] Extract OBB through a temporary file into the app-owned OBB directory.
- [ ] Preserve existing progress, cancellation, archive verification, and ready-state metadata.
- [ ] Run the focused OBB test and compile Java.

### Task 3: One-Step Game Installation

**Files:**
- Modify: `android/app/src/main/java/com/lingjing/launcher/android/AndroidLauncherPlugin.java`
- Modify: `src/App.vue`
- Modify: `src/services/androidLauncher.ts`
- Remove: `src/services/gameInstallState.ts`
- Remove: `tests/gameInstallState.test.ts`
- Test: `tests/nativeDownloadIntegration.test.ts`

- [ ] Add failing assertions for one APK install action and removal of the second OBB action.
- [ ] Treat the running package as the disposable launcher, not an installed game.
- [ ] Validate the prepared APK before opening the system installer.
- [ ] Remove pending APK/OBB return state and OBB importer calls from the Vue flow.
- [ ] Run focused native integration tests.

### Task 4: Release Build And Isolated Publication

**Files:**
- Produce: `D:/启动器新包/AndroidInstaller/CrossingVoidInstaller-1.0.20-Android.apk`
- Produce: `D:/启动器新包/AndroidInstaller/android-installer-latest.json`

- [ ] Run all Vitest tests.
- [ ] Run the production frontend build and Capacitor sync.
- [ ] Build launcher `1.0.20` with Android version code `1`.
- [ ] Verify package name, signer digest, size, and SHA-256.
- [ ] Publish to the independent Gitee installer release and manifest.
- [ ] Re-download the online APK and verify it byte-for-byte by size and SHA-256.
