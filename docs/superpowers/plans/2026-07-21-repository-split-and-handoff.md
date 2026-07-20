# Android Launcher Repository Split And Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Separate Android and PC launcher release storage, publish the Android source to GitHub, and leave a complete implementation guide for future AI sessions.

**Architecture:** GitHub stores the Android launcher source only. Gitee uses `CrossingVoid-Downloader-Android` for Android launcher manifests and APK release assets, while the user-renamed `CrossingVoid-Downloader-PC` remains PC-only. The old mixed repository name is not retained as a compatibility endpoint, per the user's decision.

**Tech Stack:** Vue 3, TypeScript, Vite, Capacitor 8, Java, Android Gradle Plugin 8.13, Gradle 9.1, PowerShell, GitHub CLI, Gitee OpenAPI.

---

### Task 1: Document The Working System

**Files:**
- Create: `docs/AndroidLauncherDevelopmentGuide.md`
- Modify: `README.md`

- [x] Describe the Vue/Capacitor/native Java boundaries and the same-package installer model.
- [x] Record the APK plus OBB download, verification, extraction, installation, and recovery state machine.
- [x] Record OSS signing, GitHub chunks, Gitee launcher updates, the 3 GB quota gate, logs, permissions, build tools, and release checks.
- [x] Add exact commands, file map, troubleshooting notes, and release checklists.
- [x] Correct the obsolete README statement that claimed OBB required a cross-package importer.

### Task 2: Lock Repository URLs With Tests

**Files:**
- Modify: `tests/launcherPublishing.test.ts`
- Modify: `src/services/launcherUpdate.ts`
- Modify: `src/App.vue`
- Modify: `Scripts/Publish-AndroidLauncher.ps1`
- Modify: `README.md`

- [x] Add assertions requiring `CrossingVoid-Downloader-Android` and rejecting the old mixed repository name.
- [x] Run `npm.cmd test -- tests/launcherPublishing.test.ts` and verify it fails on the old URLs.
- [x] Update Android manifest, release publishing, account, and documentation URLs.
- [x] Re-run the focused test and verify it passes.

### Task 3: Point PC Source At Its Renamed Repository

**Files:**
- Modify: `D:/UnrealMap/CrossingVoidinitiator-PC/src-tauri/tauri.conf.json`
- Modify: `D:/UnrealMap/CrossingVoidinitiator-PC/Scripts/Publish-LauncherGiteePackage.ps1`

- [x] Replace the mixed Gitee repository with `CrossingVoid-Downloader-PC`.
- [x] Run the PC launcher update-related tests or source validation commands.

### Task 4: Create And Seed The Android Gitee Release Repository

- [x] Create `xiaojie578/CrossingVoid-Downloader-Android` with `master` as its default branch.
- [x] Publish `launcher/android-installer-latest.json` and an explanatory README.
- [x] Create an Android launcher release using the next version and upload its APK.
- [x] Confirm the manifest URL, release URL, APK size, and SHA-256.
- [x] Remove Android manifests and Android releases from `CrossingVoid-Downloader-PC` so it is PC-only.

### Task 5: Publish Android Source To GitHub

**Files:**
- Modify: `.gitignore`
- Create: local Git metadata

- [x] Ignore build output, Capacitor-generated web output, local Android SDK paths, signing material, APK/AAB files, logs, and local scratch folders.
- [x] Verify no token, keystore, password, APK, OBB, node_modules, Gradle cache, or build output is tracked.
- [x] Initialize the repository and commit the reviewed source tree.
- [x] Create `kirito0000001/CrossingVoid-Downloader-Android` on GitHub and push the initial branch.

### Task 6: Verify End To End

- [x] Run `npm.cmd test` and require all tests to pass.
- [x] Run `npm.cmd run build` and `npx.cmd cap sync android`.
- [x] Build the release APK with JDK 23 and verify package, version, certificate, size, and SHA-256.
- [x] Verify Gitee raw manifest and release download return success.
- [x] Verify the website Android download resolves through the new repository.
- [x] Verify GitHub contains source only and no private signing material.
