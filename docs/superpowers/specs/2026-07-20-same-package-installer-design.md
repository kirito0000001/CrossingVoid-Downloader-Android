# Android Same-Package Installer Design

> **状态：核心安装方案仍在使用。** 发布仓库地址和当前版本以 `docs/AndroidLauncherDevelopmentGuide.md` 为准；本文出现的旧 `android-latest.json` 仅描述迁移时的历史状态。

## Goal

Turn the Android launcher into a disposable installer that temporarily replaces the game, prepares the OBB as the owner of the game package, and then lets the game APK replace the launcher.

## Fixed Constraints

- Do not modify, build, stop, or package the Unreal project.
- Launcher and game application ID: `com.TFAC.CorssingVoid`.
- Launcher and game signing certificate SHA-256: `56f1b0b317e38985808ddd9ee03f3785a8c0190bf32ff2791ba6a3f2c7ba2d92`.
- The current game certificate comes from `C:/Users/liuyu/.android/debug.keystore`; this file must never enter source control or release storage.
- The disposable launcher disappears after the game APK replaces it. A future game update starts by installing the latest disposable launcher again.
- The existing independent launcher manifest remains untouched during migration. The disposable installer uses its own Gitee release tag and manifest path.

## Package Replacement Flow

1. The user manually installs the disposable launcher over `com.TFAC.CorssingVoid`.
2. Android preserves the package data and existing OBB files because package name and signing certificate match.
3. The launcher reads the Android game manifest and downloads the APK plus OBB archive chunks.
4. During extraction, the APK stays in launcher-private storage and the OBB is atomically written to `Context.getObbDir()`.
5. The launcher verifies that the prepared APK has package name `com.TFAC.CorssingVoid`, the expected signing certificate, and a version code that is not lower than the disposable launcher.
6. The launcher opens the Android package installer for the game APK.
7. Android replaces the launcher with the game. The game starts with the already prepared OBB.

## Version Coordination

- A disposable launcher build must use the target game APK version code.
- Equal version codes are allowed for same-signature replacement; lower target game codes are rejected before opening the system installer.
- Launcher feature versions remain independent in `versionName`, for example launcher `1.0.20` with Android version code `1`.
- Launcher self-update compares semantic `versionName` when version codes are equal because the Android code is coordinated with the game rather than the launcher release count.

## Download And Storage

- Existing chunk download, pause, resume, merge, archive hash verification, and progress reporting remain unchanged.
- OBB extraction targets `getObbDir()` instead of a launcher-private prepared folder.
- OBB writes use a temporary `.extracting` file followed by an atomic rename.
- Cancelling a download removes chunks and the private APK but does not delete an already prepared OBB; deleting installed resources remains a separate explicit operation.
- The final ready state means: APK verified and OBB already installed. There is no second OBB installation button.

## UI Behavior

- While the disposable launcher is running, the game is considered unavailable even though the package name exists, because the launcher currently occupies that package.
- The primary ready action is `安装游戏`, which opens the prepared game APK.
- Text that asks the user to return and install OBB is removed.
- After Android replaces the launcher, the process may be killed. Completion must not depend on a callback to the old launcher process.

## Publishing And Migration

- Existing independent launcher manifest: `launcher/android-latest.json` remains on `1.0.19` during testing.
- Disposable installer manifest: `launcher/android-installer-latest.json`.
- Disposable release tag prefix: `android-installer-v`.
- First migration is manual because Android cannot hot-update one package name into another package name.
- The old independent launcher can be uninstalled after the new flow is validated.

## Failure Handling

- Build fails when package name, version code, signature, or signing keystore fingerprint differs from the target values.
- Installation is blocked when the prepared game APK package, signature, or version code is incompatible.
- Missing/unwritable OBB storage becomes a visible download error and is recorded in the rolling launcher log.
- No fallback uses Root, Shizuku, ADB, or cross-package `Android/obb` access.

## Verification

- Vitest source and state tests cover package identity, separate manifests, semantic launcher updates, and removal of the OBB bridge flow.
- Gradle builds the signed release APK with the target game package and version code.
- `aapt` verifies package/version; `apksigner` verifies the certificate digest.
- A dry-run package extraction test proves OBB targets the app-owned OBB directory without touching Unreal files.
