# Android versionCode Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Export verified Android game chunks without network use and prevent future launcher builds from leaving `versionCode=1`.

**Architecture:** Extend the existing `GameDownloadService` foreground worker with a SAF export action so the same state, notification, logging, and progress channel remains authoritative. Add one native bridge call and one settings action, then constrain the publisher to normal code `1` with a single explicit `1001003` recovery exception.

**Tech Stack:** Vue 3, TypeScript, Capacitor 8, Java Android foreground service, PowerShell 7, Vitest, Gradle.

---

### Task 1: Protect publishing versionCode

**Files:**
- Modify: `Scripts/Publish-AndroidLauncher.ps1`
- Test: `tests/launcherPublishing.test.ts`

- [ ] Add failing assertions for normal code `1` and explicit recovery code `1001003`.
- [ ] Run `npm.cmd test -- tests/launcherPublishing.test.ts` and confirm failure.
- [ ] Add `-RecoveryBuild` and reject every unsupported version code before any build or upload.
- [ ] Rerun the focused test.

### Task 2: Export verified chunks through the foreground service

**Files:**
- Modify: `android/app/src/main/java/com/lingjing/launcher/android/GameDownloadService.java`
- Modify: `android/app/src/main/java/com/lingjing/launcher/android/AndroidLauncherPlugin.java`
- Modify: `src/services/androidLauncher.ts`
- Test: `tests/nativeDownloadIntegration.test.ts`

- [ ] Add failing source-integration assertions for the native export action, SAF write permission, temporary files, SHA-256 verification, and bridge method.
- [ ] Run the focused test and confirm failure.
- [ ] Add `ACTION_EXPORT`, select a writable document tree, and run export in the existing foreground service.
- [ ] Preserve the previous ready state after success or failure and never mutate source chunks.
- [ ] Rerun the focused test.

### Task 3: Expose export progress and action

**Files:**
- Modify: `src/App.vue`
- Test: `tests/launcherUpdateIntegration.test.ts`

- [ ] Add failing assertions for the export button, enabled-state rule, `exporting` phase, and global progress dock.
- [ ] Run the focused test and confirm failure.
- [ ] Add the settings action and map native export state to the global task UI.
- [ ] Rerun the focused test.

### Task 4: Verify and publish recovery build

**Files:**
- Verify all modified source and tests.

- [ ] Run `npm.cmd test`.
- [ ] Run `npm.cmd run build`.
- [ ] Run Gradle `testDebugUnitTest` with JDK 23.
- [ ] Run `git diff --check`.
- [ ] Publish the recovery build with `-RecoveryBuild -VersionCode 1001003` after all checks pass.
- [ ] Read back the Gitee manifest and APK metadata.
