# 零境启动器 Android 制作、维护与发布指南

本文档面向第一次接手项目的开发者或 AI。目标是只读取本文档和仓库源码，就能理解启动器为什么这样设计、如何构建、如何发布，以及哪些做法会造成无法安装、丢失 OBB、旧版断更或 OSS 流量失控。

## 1. 项目边界

- Android 启动器源码：`D:\UnrealMap\CrossingVoidinitiator-Android`
- PC 启动器源码：`D:\UnrealMap\CrossingVoidinitiator-PC`
- Android 源码 GitHub：`https://github.com/kirito0000001/CrossingVoid-Downloader-Android`
- Android 发布 Gitee：`https://gitee.com/xiaojie578/CrossingVoid-Downloader-Android`
- PC 发布 Gitee：`https://gitee.com/xiaojie578/CrossingVoid-Downloader-PC`
- 更新服务器源码：`C:\Users\liuyu\Documents\租赁服务器\ToolboxUpdateServer`
- 线上更新服务器：`C:\Users\Administrator\Desktop\OSSAPI\ToolboxUpdateServer\app`
- 官网：`https://www.crossingvoid.top/`

硬性规则：不要修改、编译、停止或清理 `D:\UnrealMap\CrossingVoid` 虚幻项目。启动器开发只能读取游戏包名、版本和打包产物，不能顺手修改 Unreal 配置。

## 2. 产品定位

Android 版本不是一个永久共存的普通启动器，而是“同包名一次性安装器”。

1. 用户安装“零境启动器”。
2. 启动器检查自身更新。
3. 启动器下载游戏的 APK 与 OBB。
4. 启动器先把 OBB 放入正确目录。
5. 启动器拉起系统安装器安装游戏 APK。
6. 游戏 APK 使用相同包名和相同证书，覆盖启动器。
7. 覆盖完成后，桌面上的同一个应用入口变成游戏。

这解决了 Android 11 以后普通应用不能随意写入其他应用 `Android/obb` 目录的问题：安装器与游戏使用同一个包名，安装器写入的是“自己”的 OBB 目录，随后游戏覆盖安装器并继承这个目录。

## 3. 绝对不能随意改的身份信息

### 3.1 包名

当前包名是：

```text
com.TFAC.CorssingVoid
```

`CorssingVoid` 的拼写看起来不标准，但它是现有游戏包名。不能只在启动器内改正拼写，否则会变成两个应用，也无法覆盖安装。

相关位置：

- `capacitor.config.ts`
- `android/app/build.gradle`
- `android/app/src/main/java/com/lingjing/launcher/android/AndroidLauncherPlugin.java`
- 原生校验与测试文件

### 3.2 签名证书

启动器 APK 与游戏 APK 必须使用同一张证书。当前发布脚本要求证书 SHA-256：

```text
56f1b0b317e38985808ddd9ee03f3785a8c0190bf32ff2791ba6a3f2c7ba2d92
```

签名配置只从环境变量读取：

```text
CROSSINGVOID_GAME_KEYSTORE
CROSSINGVOID_GAME_KEYSTORE_PASSWORD
CROSSINGVOID_GAME_KEY_ALIAS
CROSSINGVOID_GAME_KEY_PASSWORD
```

禁止把 `.jks`、`.keystore`、密码、Gitee Token、OSS AccessKey 或私钥提交到 GitHub/Gitee。证书丢失后，已安装用户无法覆盖更新。

### 3.3 版本号

- 启动器显示版本使用纯数字三段式，例如 `1.0.23`。
- 启动器安装器可以保持 `versionCode = 1`，同一 `versionCode` 下由前端比较 `versionName` 判断热更新。
- 游戏 APK 最终覆盖启动器时，Android 仍会检查包名、证书和系统允许的版本升级关系。
- 发布测试版启动器时不要给手机版显示 `Beta` 后缀。

## 4. 技术架构

### 4.1 Web 层

技术栈：Vue 3、TypeScript、Vite、Lucide 图标。

主要职责：

- 页面、状态、提示与交互。
- 启动器更新优先级。
- 游戏版本检查。
- 下载来源选择与持久化。
- 流量额度显示。
- 原生状态映射到 UI 阶段。

关键文件：

```text
src/App.vue
src/services/androidLauncher.ts
src/services/launcherUpdate.ts
src/services/gameUpdate.ts
src/services/downloadPlan.ts
src/services/downloadSource.ts
src/services/trafficStatus.ts
src/services/launcherLog.ts
```

### 4.2 Capacitor 桥接层

`src/services/androidLauncher.ts` 定义 TypeScript 接口，`AndroidLauncherPlugin.java` 实现 Android 能力。

Web 层不能自己直接操作 APK、OBB、系统安装器、前台服务或电池优化设置。所有这些操作都通过原生插件完成。

### 4.3 Android 原生层

关键文件：

```text
android/app/src/main/java/com/lingjing/launcher/android/AndroidLauncherPlugin.java
android/app/src/main/java/com/lingjing/launcher/android/GameDownloadService.java
android/app/src/main/java/com/lingjing/launcher/android/LauncherUpdateService.java
android/app/src/main/java/com/lingjing/launcher/android/LauncherLogStore.java
android/app/src/main/java/com/lingjing/launcher/android/DownloadFileUtils.java
android/app/src/main/java/com/lingjing/launcher/android/ApkPackageValidator.java
```

- `AndroidLauncherPlugin`：权限、系统安装器、游戏检测、状态查询和 Web 事件桥接。
- `GameDownloadService`：游戏分片下载、恢复、校验、合并、解压和 OBB 写入。
- `LauncherUpdateService`：启动器 APK 下载、校验和恢复。
- `LauncherLogStore`：最多 10 MB 的本地滚动日志与上传。
- `DownloadFileUtils`：安全路径、下载 URL 和文件工具。
- `ApkPackageValidator`：安装前检查包名、versionCode 和签名。

## 5. 启动顺序

每次启动必须按这个顺序：

1. 读取本机启动器版本和未完成任务。
2. 检查 Gitee 启动器更新清单。
3. 如果发现启动器更新，停止后续游戏检查，先完成启动器更新。
4. 启动器无更新后，调用服务器检查游戏版本。
5. 读取本机游戏/安装器状态和未完成游戏下载。
6. 显示“下载游戏”“继续下载”“安装游戏”或“启动游戏”等状态。

不能同时开始启动器更新和游戏下载。启动器更新必须优先，否则旧启动器可能不理解新的游戏清单格式。

## 6. 启动器热更新

Android 启动器清单：

```text
https://gitee.com/xiaojie578/CrossingVoid-Downloader-Android/raw/master/launcher/android-installer-latest.json
```

清单核心字段：

```json
{
  "schemaVersion": 1,
  "productKey": "crossingvoid-launcher-android-installer",
  "versionName": "1.0.24",
  "versionCode": 1,
  "notes": "更新说明",
  "publishedAt": "UTC ISO-8601",
  "asset": {
    "fileName": "CrossingVoidInstaller-1.0.24-Android.apk",
    "url": "Gitee Release APK URL",
    "sizeBytes": 1,
    "sha256": "64位小写十六进制"
  }
}
```

更新流程：下载 APK -> 校验大小 -> 校验 SHA-256 -> 校验包名/版本/签名 -> 检查未知来源安装权限 -> 打开系统安装器 -> 用户确认覆盖。

Gitee 仓库只保存启动器 APK 和清单，不保存游戏本体。

## 7. 游戏更新来源

### 7.1 版本检查

游戏更新通过：

```text
POST https://www.crossingvoid.top/api/toolbox-updates/check
```

Android 产品键：

```text
crossingvoid-android-game
```

服务器返回版本、完整包 SHA-256、总大小和 100 MiB 分片列表。

### 7.2 OSS 官方源

客户端不能持有 OSS AccessKey。每个分片下载前向服务器请求短时签名 URL：

```text
POST https://www.crossingvoid.top/api/toolbox-updates/sign-download
```

请求必须带当前 `launcherVersion`。服务端会：

- 校验产品、版本、运行平台和 objectKey。
- 拒绝过旧 Android 启动器。
- 查询阿里云可用流量。
- 低于 3 GB 时返回 `503`，不签发下载 URL。
- 正常时返回约 10 分钟有效的 GET 签名 URL。

客户端 UI 的 3 GB 限制只是体验层，服务端拒签才是真正的保护。

### 7.3 GitHub 源

GitHub 游戏资源位于游戏仓库的 `Android-V<游戏版本>` Release。它与 Gitee 启动器更新不是同一套仓库。

提示必须保留：

```text
需要魔法
```

切换 OSS/GitHub 来源后，可以复用 SHA-256 已通过的相同分片，不应重新下载所有内容。

## 8. 游戏下载状态机

原生状态：

```text
idle
downloading
paused
verifying
merging
extracting
ready
error
```

标准顺序：

```text
读取任务
-> 检查已有分片
-> 下载缺失分片
-> 逐片 SHA-256 校验
-> 合并 ZIP
-> 完整 ZIP SHA-256 校验
-> 解压 APK 与 OBB
-> ready
-> 打开系统 APK 安装器
```

必须保存：目标版本、来源、分片列表、完整包哈希、已验证分片数和下载状态。手机重启、进程被杀、切换来源或返回应用后，都应从已验证分片恢复。

前台服务使用通知栏显示固定文案“正在（下载）链接空界幻境中...”和进度条。下载任务使用 `START_REDELIVER_INTENT`，系统回收服务后可重新投递任务。

## 9. APK 与 OBB 的实际处理

下载到的是包含 APK 和 OBB 的 ZIP 分片集合，不是直接安装单个 APK。

完成下载后：

1. 合并分片为完整 ZIP。
2. 校验完整 ZIP SHA-256。
3. APK 解压到启动器私有 `files/downloads/prepared/CrossingVoid-latest.apk`。
4. OBB 解压到 `getObbDir()` 返回的目录，即当前同包名应用的 `Android/obb/com.TFAC.CorssingVoid/`。
5. 写入时先使用 `.extracting` 临时文件，完成后原子重命名，避免半个 OBB 被误认为成功。
6. 确认 APK 和 OBB 均存在且大小大于 0。
7. 删除同目录下旧的 `main.*.obb` 或 `patch.*.obb`。
8. 状态变为 `ready`，消息为“APK 和 OBB 已准备完成”。
9. 用户点击安装后，只需要覆盖 APK，因为 OBB 已经提前放好。

如果系统安装界面已经弹出，就代表 OBB 检查已经通过。不能把 `ready` 提前到 OBB 写入之前。

## 10. 暂停、取消与异常恢复

- 下载阶段允许暂停，从上一个已校验完整分片继续。
- 合并、完整校验、解压和系统安装不提供暂停，可以取消或等待完成。
- 取消下载会删除分片、合并包和 prepared 安装包。
- 下载中版本变化时重新读取清单；哈希相同的分片保留，变化的分片作废。
- 空间估算必须包含未下载分片、合并 ZIP、解压 APK 与 OBB 所需空间，不能只算网络包大小。
- `.extracting` 文件不算有效结果，崩溃后应重新写入。
- 安装权限、通知权限或电池优化状态变化后，应用回到前台时重新检测。

## 11. 权限与后台下载

AndroidManifest 需要：

```text
INTERNET
REQUEST_INSTALL_PACKAGES
POST_NOTIFICATIONS
FOREGROUND_SERVICE
FOREGROUND_SERVICE_DATA_SYNC
WAKE_LOCK
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
```

设置页显示：

- “安装应用权限”当前状态和系统设置入口。
- “后台下载权限”当前电池优化状态和系统设置入口。

通知仍显示但下载停止，通常是厂商系统冻结了进程。前台服务、WakeLock、任务持久化和电池优化白名单需要共同工作，任何一个都不能替代其他部分。

## 12. 流量额度与 3 GB 限制

流量状态接口：

```text
GET https://www.crossingvoid.top/api/toolbox-updates/traffic-status
```

客户端行为：

- 启动时获取。
- 返回前台时刷新。
- 每 5 分钟刷新。
- 只在选择 OSS 时显示“服务器可用下载流量”。
- 剩余低于 3 GB 时提示“服务器当前流量不足，请更换下载源。”
- 正在进行的 OSS 下载自动暂停。
- GitHub 源不显示额度与低流量提示。

服务端行为：低于 3 GB 时拒绝签发 OSS URL。即使客户端被修改，也不能继续消耗 OSS 流量。

## 13. 日志与诊断

- 原生日志文件最多 10 MB，超过后滚动截断。
- 记录应用生命周期、用户操作、页面、状态切换、下载、校验、安装和异常。
- 不记录访问令牌、签名 URL 参数、密码或授权头。
- 设置页“上传日志”由用户主动触发。
- 高频字节进度不能每一帧写日志，只记录有意义的阶段与状态变化。

远程排障优先根据日志确认：清单 -> 计划 -> 分片 -> 合并 -> 校验 -> 解压 -> OBB 路径 -> APK 安装器，每一层分别判断。

## 14. UI 约束

- 横屏布局。
- 首页保留 Logo、英文标题、中文标题、主按钮和目标版本。
- 设置在首页左侧；公告、账号、角色介绍和视频位于右侧分页。
- 下载、校验、解压、安装和上传等长任务使用跨页面悬浮进度栏。
- 没有任务时隐藏悬浮栏。
- 页面切换不能重新创建大图或重复发网络请求。
- 主按钮图标必须对应状态：下载、暂停、安装、启动、刷新不能混用。
- Android 点击区域要比 PC 大，上下栏与分页按钮也要适配触控。

## 15. 构建环境

已验证组合：

```text
Node.js + npm
Capacitor 8.4.1
Android Gradle Plugin 8.13.0
Gradle 9.1.0
JDK 23
compileSdk 36
```

不要修改全局 `JAVA_HOME`，使用命令局部设置：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-23'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

标准验证：

```powershell
cd D:\UnrealMap\CrossingVoidinitiator-Android
npm.cmd test
npm.cmd run build
npx.cmd cap sync android
cd android
.\gradlew.bat assembleRelease -PlauncherVersionName=1.0.24 -PlauncherVersionCode=1 --console=plain --no-daemon
```

发布优先使用统一脚本：

```powershell
.\Scripts\Publish-AndroidLauncher.ps1 -VersionName 1.0.24 -VersionCode 1 -Notes "更新说明"
```

脚本必须完成：测试、Web 构建、Capacitor 同步、Gradle Release、包名检查、版本检查、签名检查、SHA-256、Gitee Release 上传和 latest 清单提交。

## 16. 仓库职责

### GitHub Android 源码仓库

```text
kirito0000001/CrossingVoid-Downloader-Android
```

只保存可复现构建的源码、测试、脚本和文档。不保存 `node_modules`、`dist`、Gradle 缓存、APK、AAB、OBB、签名文件、密码或本机 SDK 路径。

### Gitee Android 发布仓库

```text
xiaojie578/CrossingVoid-Downloader-Android
```

保存：

- `launcher/android-installer-latest.json`
- Android 启动器 Release
- APK Release 附件

### Gitee PC 发布仓库

```text
xiaojie578/CrossingVoid-Downloader-PC
```

保存 PC 的 `launcher/latest.json`、Tauri 签名和 Windows 安装包。不要再向 PC 仓库发布 Android 资产。

本次拆分按用户决定不保留旧 `CrossingVoid-Downloader` 兼容入口。以后修改仓库地址时，必须先决定是否需要迁移清单；不能默认重命名后 Raw URL 一定继续工作。

## 17. 官网 Android 下载

官网按钮应读取 Android Gitee 仓库的最新 `android-installer-v*` Release 或 `android-installer-latest.json`，弹出系统确认：

```text
安卓启动器正在测试中，是否下载？
```

官网不能固定写死某个 APK 版本。验证时检查：仓库最新 Release、清单版本、APK URL、HTTP 200、大小和 SHA-256。

## 18. 常见故障与结论

### 无法覆盖安装

依次检查：包名、证书 SHA-256、versionCode、未知来源安装权限、FileProvider URI。不要只看文件扩展名。

### 下载完成后 OBB 不存在

检查 ZIP 内是否真的有 `.obb`、`getObbDir()` 是否可用、临时文件重命名是否成功，以及状态是否错误地提前进入 `ready`。

### 安装界面只有“完成”没有“打开”

检查 APK 是否包含可导出的 MAIN/LAUNCHER Activity。系统安装器行为也可能随 ROM 变化，不能把“打开”按钮作为安装成功的唯一判据。

### 后台通知存在但下载停止

检查电池优化、厂商后台限制、前台服务状态、WakeLock、任务持久化和 `START_REDELIVER_INTENT`。

### Gitee 更新很慢或静默失败

检查 Raw 清单 HTTP 状态、JSON 日期格式、Release 附件 URL、Gitee 100 MiB 限制和中文编码。上传使用 PowerShell 7/UTF-8 或 `curl.exe`，并输出 HTTP 响应正文。

### `aapt` 无法读取中文路径 APK

部分 Android build-tools 在中文路径下报 `Illegal byte sequence`。在项目英文路径中的原始 APK 上执行 `aapt dump badging`，再用 SHA-256 证明发布副本与原文件一致。

### 更新服务器部署后提示缺少 .NET

线上 `ToolboxUpdateServer` 是 `win-x64 self-contained`。部署时也必须：

```powershell
dotnet publish --configuration Release --runtime win-x64 --self-contained true
```

不能用 framework-dependent 包覆盖其 `runtimeconfig.json`。部署前备份，保留服务器 `Data` 与 `appsettings`，部署后验证 Windows 服务、`/health`、流量接口和签名接口。

## 19. 发布前检查表

- [ ] 没有触碰 Unreal 项目。
- [ ] 包名仍为 `com.TFAC.CorssingVoid`。
- [ ] 签名 SHA-256 与游戏一致。
- [ ] `npm.cmd test` 全部通过。
- [ ] `npm.cmd run build` 成功。
- [ ] `npx.cmd cap sync android` 成功。
- [ ] Gradle Release 成功。
- [ ] APK versionName/versionCode 正确。
- [ ] Gitee 清单版本、大小、SHA-256 与 APK 一致。
- [ ] Gitee APK 下载返回 HTTP 200。
- [ ] 官网按钮解析到最新 Android Release。
- [ ] OSS 签名支持当前启动器版本。
- [ ] 3 GB 限制在客户端和服务端均存在。
- [ ] 真机完成启动器更新、游戏下载、OBB 写入和 APK 覆盖。
- [ ] GitHub 没有密钥、Token、APK、OBB、构建目录或本机路径配置。

## 20. 接手时的推荐读取顺序

1. 本文档。
2. `README.md`。
3. `src/services/downloadPlan.ts` 和相关测试。
4. `GameDownloadService.java`。
5. `AndroidLauncherPlugin.java`。
6. `LauncherUpdateService.java`。
7. `src/App.vue` 的状态映射和操作入口。
8. `Scripts/Publish-AndroidLauncher.ps1`。
9. Gitee latest 清单和线上服务器接口。

先验证现状，再修改。出现错误时沿数据流定位，不要通过反复打包和猜测来试错，更不要为了修启动器去改虚幻项目。
