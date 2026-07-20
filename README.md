# 零境启动器 Android

这是零境交错 Android 启动器项目。

技术栈：

```text
Vue 3 + Vite + Capacitor Android
```

项目目录：

```text
D:\UnrealMap\CrossingVoidinitiator-Android
```

## 当前功能

当前版本定位是与游戏同包名的一次性 APK + OBB 安装器：

- 启动时优先检查启动器自身更新，再检查游戏更新
- 从 Gitee 下载新版启动器 APK，校验大小、SHA-256、包名、versionCode 和签名
- 启动器与游戏统一使用 `com.TFAC.CorssingVoid`，游戏 APK 安装后直接替换启动器
- 检测游戏包是否已安装
- 显示当前游戏版本
- 从正式更新接口读取 Android 游戏版本和 100 MiB 分片清单
- 手机版启动器主界面
- 原生前台服务下载、暂停、继续、取消和系统中断恢复
- OSS 官方源与 Github 源，切换来源时复用已校验分片
- HTTP Range 断点续传、分片 SHA-256 和完整包 SHA-256 校验
- 合并、解压、APK 安装和 OBB 安装兼容检测
- 打开未知来源安装权限设置
- 拉起系统 APK 安装器和游戏入口
- 为 Android 7 时代 WebView 同时输出 modern/legacy 脚本，兼容旧平板与 x86 设备

Github 源对应 `Android-V<版本>` Release，提示“需要魔法”。系统通知只显示固定下载文案和进度条。

## 启动器热更新

手机版启动器版本使用纯数字三段式，不添加 Beta 标识。安装器与游戏共用包名和签名；相同 `versionCode` 的安装器版本使用 `versionName` 判断是否需要更新：

```text
1.0.20 -> versionCode 1
1.0.21 -> versionCode 1
```

更新清单：

```text
https://gitee.com/xiaojie578/CrossingVoid-Downloader-Android/raw/master/launcher/android-installer-latest.json
```

首次安装和每次启动器更新都需要用户在 Android 系统界面确认。旧版独立包名启动器不再提供自动迁移；需要先卸载旧版，再安装当前同包名安装器。安装游戏 APK 后，游戏会替换一次性安装器。

发布命令：

```powershell
.\Scripts\Publish-AndroidLauncher.ps1 -VersionName 1.0.25 -VersionCode 1 -Notes "更新说明"
```

Release APK 必须使用与游戏相同的签名。发布脚本从以下环境变量读取签名配置：

```text
CROSSINGVOID_GAME_KEYSTORE
CROSSINGVOID_GAME_KEYSTORE_PASSWORD
CROSSINGVOID_GAME_KEY_ALIAS
CROSSINGVOID_GAME_KEY_PASSWORD
```

签名文件和密码不得提交到公开仓库，必须离线备份。签名不一致时，安装器、启动器更新和游戏 APK 都不能互相覆盖。

## 游戏包名

当前检测的游戏包名：

```text
com.TFAC.CorssingVoid
```

如果 UE 项目后续修正包名，安卓启动器也需要同步修改。

## 常用命令

前端构建：

```powershell
npm run build
```

同步 Web 资源到 Android：

```powershell
npx cap sync android
```

构建 Debug APK：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-23'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
cd D:\UnrealMap\CrossingVoidinitiator-Android\android
.\gradlew.bat assembleDebug --console=plain --no-daemon
```

Debug APK 输出位置：

```text
D:\UnrealMap\CrossingVoidinitiator-Android\android\app\build\outputs\apk\debug\app-debug.apk
```

## Java 版本说明

当前项目使用 Capacitor 8 生成的 Android 工程，构建时需要 JDK 21 或更高。

本机已验证可用：

```text
C:\Program Files\Java\jdk-23
```

不要为了这个项目修改全局 `JAVA_HOME`，避免影响 Unreal/Android 旧工具链。构建时按上面的命令临时设置即可。

## Android 原生插件

插件文件：

```text
android\app\src\main\java\com\lingjing\launcher\android\AndroidLauncherPlugin.java
```

前端封装：

```text
src\services\androidLauncher.ts
```

插件能力：

- `checkGame`
- `openInstallPermissionSettings`
- `installDownloadedApk`
- `startDownload`
- `pauseDownload`
- `cancelDownload`
- `getDownloadState`
- `getLauncherInfo`
- `startLauncherUpdate`
- `cancelLauncherUpdate`
- `getLauncherUpdateState`
- `installLauncherUpdate`

下载完成后 APK 解压到启动器私有目录。安装 APK 时读取下载状态中的实际文件路径；默认路径为：

```text
files/downloads/CrossingVoid-latest.apk
```

OBB 会在弹出系统 APK 安装界面之前写入 `getObbDir()` 返回的同包名目录。因为启动器与游戏都使用 `com.TFAC.CorssingVoid`，游戏覆盖启动器后会直接使用该 OBB。只有 APK 与 OBB 都存在且非空时，下载状态才会进入 `ready`。

完整架构、发布流程、排障经验和 AI 接手顺序见：

```text
docs/AndroidLauncherDevelopmentGuide.md
```

## 真机验证

- Android 13+ 通知权限申请
- 官方源与 Github 源首片下载
- 暂停、继续和切换来源
- 杀死进程后恢复任务
- APK 系统安装确认
- OBB 写入同包名目录、APK 覆盖安装与游戏启动
