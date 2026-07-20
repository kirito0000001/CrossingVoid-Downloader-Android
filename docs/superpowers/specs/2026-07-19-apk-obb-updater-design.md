# 零境启动器 APK + OBB 更新系统设计

> **状态：已废弃，仅保留为历史记录。** 其中独立启动器包名和跨包 OBB 导入方案没有继续采用。当前实现以 `docs/AndroidLauncherDevelopmentGuide.md` 和 `2026-07-20-same-package-installer-design.md` 为准：启动器与游戏同为 `com.TFAC.CorssingVoid`，先写入自己的 OBB 目录，再由游戏 APK 覆盖启动器。

## 1. 目标

将现有 Android 原型升级为可长期使用的《零境启动器》。启动器独立于游戏安装，负责检测、下载、校验、安装和启动零境交错 Android 游戏。

本系统不使用 XAPK。Unreal Engine 每次发布后直接提供 APK 和 OBB，发布工具将两者生成统一分块并同步到 OSS 与 GitHub。

## 2. 应用边界

- 启动器应用名：《零境启动器》
- 启动器包名：`com.TFAC.CorssingVoidLauncher`
- 游戏包名：`com.TFAC.CorssingVoid`
- 启动器自身更新产品：`crossingvoid-android-launcher`
- 游戏更新产品：`crossingvoid-android-game`

启动器和游戏是两个独立 Android 应用。游戏更新只覆盖安装游戏 APK，不会重新下载或覆盖启动器。只有启动器自身发布新版本时，才更新启动器 APK。

## 3. 版本规则

游戏版本由基础版本和构建修订号组成：

```text
正式版：0.5.13.0
测试版：0.5.13.1-Beta
下一测试版：0.5.13.2-Beta
```

Android `versionCode` 使用单调递增整数：

```text
versionCode = A * 100000000 + B * 1000000 + C * 1000 + D
```

例如：

```text
0.5.13.0      -> 5013000
0.5.13.1-Beta -> 5013001
0.5.13.2-Beta -> 5013002
```

OBB 文件名必须使用同一个 `versionCode`：

```text
main.5013001.com.TFAC.CorssingVoid.obb
```

已发布或已安装的 `versionCode` 不得回退。Beta 测试通过后直接将同一份已验证 APK、OBB 和分块提升到 Stable，不重新打包。

## 4. 发布通道

- `stable`：普通玩家默认通道。
- `beta`：只在开发版或明确开启测试通道后可见。

通道清单只决定当前指向哪个构建，不复制文件。将 Beta 提升为 Stable 时，只更新 Stable 清单指针。

### 4.1 GitHub 平台隔离

PC 与 Android 可以共用 `kirito0000001/CrossingVoid` 仓库，但不能共用 Release 标签、清单名或无平台标识的附件名：

- PC Release 标签：`PC-V0.5.14`。
- PC 清单：`CrossingVoid-PC-update.json`；同时保留 `update.json` 兼容旧 PC 启动器。
- PC 分片：`CrossingVoid-PC-Windows-x64.zip.part001`。
- Android Release 标签：`Android-V0.5.13.1-Beta`。
- Android 清单：`CrossingVoid-Android-update.json`。
- Android APK：`CrossingVoid-Android-0.5.13.1-Beta.apk`。
- OBB 完整文件仍必须使用 Android 系统要求的 `main.<versionCode>.com.TFAC.CorssingVoid.obb`。

PC 启动器只选择 `PC-` 标签、非草稿、非 Github Prerelease 且包含 PC 清单的 Release。Android 启动器只选择 `Android-` 标签和 Android 清单。在旧 PC 启动器仍需兼容期间，Android Release 一律标记为 Github Prerelease，避免覆盖旧客户端依赖的 `/releases/latest`。

## 5. 游戏更新清单

游戏清单至少包含：

```json
{
  "schemaVersion": 1,
  "productKey": "crossingvoid-android-game",
  "channel": "beta",
  "gameVersion": "0.5.13",
  "displayVersion": "0.5.13.1-Beta",
  "versionCode": 5013001,
  "packageName": "com.TFAC.CorssingVoid",
  "publishedAt": "2026-07-19T00:00:00Z",
  "assets": {
    "apk": {
      "fileName": "CrossingVoid-Android-0.5.13.1-Beta.apk",
      "sizeBytes": 0,
      "sha256": "",
      "chunks": []
    },
    "obb": {
      "fileName": "main.5013001.com.TFAC.CorssingVoid.obb",
      "sizeBytes": 0,
      "sha256": "",
      "chunks": []
    }
  }
}
```

每个分块记录：

```json
{
  "index": 0,
  "fileName": "CrossingVoid-Android-main.5013001.com.TFAC.CorssingVoid.obb.part000",
  "sizeBytes": 104857600,
  "sha256": "",
  "sources": {
    "official": "OSS 对象键或签名地址定位信息",
    "github": "GitHub Release 资产名"
  }
}
```

APK、OBB、分块文件名、大小和哈希必须在发布前验证。清单不能只依赖文件名判断内容。

## 6. 分块协议

- 默认分块大小约为 `100 MiB`。
- APK 较小时可以自然只生成一个分块。
- OSS 和 GitHub Release 保存完全相同的分块内容、名称和 SHA-256。
- 本地完成状态以分块 SHA-256 为身份，不绑定下载来源。
- 用户切换来源后保留所有已校验完成的分块，只下载缺少或损坏的块。
- 所有分块完成后按索引合并，随后校验完整 APK 或 OBB 的 SHA-256。
- 合并成功前不删除分块，完整文件校验成功后再清理分块。
- 新清单到达后，只有哈希仍被新清单引用的分块可以复用。

## 7. 下载来源

第一版同时支持：

- `official`：OSS 官方源，通过服务器签发短时下载地址。
- `github`：GitHub Release 源，需要用户具备可访问 GitHub 的网络环境。

官方源连续失败后暂停当前块并提示：

```text
官方源连接失败，是否切换 Github 源？
Github 源需要魔法。
```

不允许静默切换来源。用户也可以在设置中主动切换，切换后继续复用已完成分块。

## 8. 后台下载

下载由 Android 原生前台服务执行，不由 WebView 直接承担大文件传输。

前台服务负责：

- HTTP Range 断点续传。
- 分块下载、重试和限速状态。
- 分块 SHA-256 校验。
- 下载状态持久化。
- 向 Vue 界面发送进度事件。
- 在锁屏、切换应用和省电环境下尽量维持任务。

系统通知只显示进度条和固定提示语：

```text
正在（下载）链接空界幻境中...
```

通知不提供暂停或继续按钮。下载完成、取消或不可恢复失败后关闭通知。

## 9. 本地状态

制作中的下载不是内存缓存。下载任务使用独立 JSON 保存：

- 当前产品、通道、显示版本和 `versionCode`。
- 当前选择的下载来源。
- APK、OBB 各分块状态。
- 已下载字节数和校验结果。
- 完整文件合并与校验状态。
- APK 安装状态。
- OBB 部署与回退状态。

启动器被关闭、进程被杀或手机重启后，可以读取该 JSON 恢复任务。写入使用临时文件加原子替换，避免状态文件损坏。

## 10. 储存空间预估

开始下载前必须读取当前可用空间并计算更新期间的峰值额外占用。

OBB 有变化时：

```text
新 OBB + 下载中的 APK + Android 安装 APK 的临时空间 + 安全余量
```

OBB 未变化时：

```text
APK 临时空间 * 2 + 安全余量
```

安全余量取 `256 MiB` 与更新文件总量 `10%` 中较大值。界面显示当前可用空间和最低额外需求，空间不足时禁止开始下载。

下载过程中若空间变为不足，安全暂停并保留已完成分块。

## 11. OBB 复用与部署

Android 11 及以上写入 `Android/obb` 时，引导用户授予“所有文件访问权限”。未授权时不能进入最终安装阶段。

目标目录：

```text
Android/obb/com.TFAC.CorssingVoid/
```

当新旧 OBB 的完整 SHA-256 相同时：

- 不重新下载 OBB。
- 将旧 OBB 安全重命名为新 `versionCode` 对应名称。
- 重命名失败时保留原文件并提示，不删除唯一可用副本。

当 OBB 内容变化时：

- 先完整下载并校验新 OBB。
- 保留旧 OBB，直到新 APK 安装成功且新 OBB 部署完成。
- 成功后删除旧 OBB。
- 失败时保留旧 OBB和已校验的新文件，允许重试。

## 12. 安装与启动流程

首次安装和后续更新使用同一状态机：

1. 启动器自身更新检查。
2. 游戏通道清单检查。
3. 检测本机游戏 `versionCode`、APK 和 OBB 状态。
4. 检查权限与储存空间。
5. 下载或复用 APK、OBB 分块。
6. 合并并校验完整文件。
7. 显示“更新文件已就绪”，等待用户点击“安装游戏”。
8. 打开 Android 系统安装界面，不自动打断用户。
9. 用户返回启动器后重新检测游戏包和 `versionCode`。
10. 确认 APK 安装成功后部署新 OBB。
11. 校验目标 OBB，清理旧文件和临时 APK。
12. 启用“启动游戏”按钮并拉起游戏主 Activity。

Android 不允许普通侧载应用静默安装 APK。每次 APK 变化时都需要用户在系统安装界面确认。

## 13. 启动器自身更新

启动器自身更新与游戏更新完全分离，并优先检查：

- 产品：`crossingvoid-android-launcher`
- 包名：`com.TFAC.CorssingVoidLauncher`
- 文件：签名后的启动器 APK

仅当启动器功能有新版本时下载并覆盖安装启动器。使用同一 Android 签名后，覆盖更新不会清除下载记录和设置。

## 14. UI 状态

主界面至少展示：

- 当前游戏版本、目标版本和 Stable/Beta 标识。
- 当前步骤：检查、下载、校验、等待安装、安装确认、部署 OBB、完成、失败。
- APK 与 OBB 总进度和当前文件。
- 实时下载速度和预计剩余时间。
- 当前来源和来源切换入口。
- 当前可用空间和本次最低额外空间。
- 暂停、继续、安装游戏、启动游戏等与状态相符的主操作。

错误提示必须指出具体对象，例如“OBB 第 12 块 SHA-256 校验失败”，不能只显示“更新失败”。

下载时间预估沿用 PC 启动器规则：

- 下载开始后的前 `3` 秒显示“正在计算剩余时间”。
- 使用最近约 `10` 秒的字节增量平滑计算速度，显示为“下载速度 5.2 MB/s · 预计剩余 12分钟”。
- 连续 `5` 秒没有新增字节时显示“下载暂时无进度”。
- 暂停时只显示“已暂停”，隐藏旧速度和旧剩余时间。
- 切换版本、总大小变化、下载进度回退或中断超过 `15` 秒后，必须清空旧样本并重新计算。
- 仅在实际下载 APK、OBB 或分块时显示；合并、SHA-256 校验、安装确认、APK 安装和 OBB 部署阶段不显示预计时间。
- 底部全宽下载进度条在所有分页保持可见；系统通知仍只显示固定提示语和通知进度条，不增加速度或剩余时间文本。

## 15. 错误与恢复

- 网络失败：当前块重试，达到上限后暂停并提供来源切换。
- 分块校验失败：删除该损坏块并重新下载，不清空其他块。
- 完整文件校验失败：保留已校验分块，重新合并；仍失败时定位异常块。
- 空间不足：暂停任务，保留状态和分块。
- APK 安装取消：保留新 APK、OBB，允许再次点击安装。
- APK 安装失败：保留旧 OBB，不切换游戏资源。
- OBB 部署失败：保留旧 OBB和新 OBB，允许重试部署。
- 权限被拒绝：解释用途并提供重新打开系统设置的入口。
- 清单不可用：不修改现有游戏，允许启动已安装版本。

## 16. 发布工具

发布工具输入：

- Unreal 输出 APK。
- Unreal 输出 OBB。
- 基础游戏版本。
- 构建修订号。
- 目标通道。

发布工具执行：

1. 验证 APK 包名、`versionName` 和 `versionCode`。
2. 验证 OBB 名称包含相同 `versionCode` 和包名。
3. 计算完整文件大小与 SHA-256。
4. 生成统一分块与分块 SHA-256。
5. 上传相同分块到 OSS 和 GitHub Release。
6. 验证两侧远程文件可访问且大小匹配。
7. 最后原子更新 Beta 或 Stable 清单。

任一上传或验证失败时不得更新通道清单，避免玩家读到不完整版本。

## 17. 测试策略

单元测试覆盖：

- 四段版本到 `versionCode` 的转换与边界。
- 清单解析、通道选择和版本比较。
- 分块命名、排序、复用和来源切换。
- 分块及完整文件 SHA-256 校验。
- 储存空间峰值计算。
- OBB 未变化时的复用与重命名决策。
- 更新状态机的成功、暂停、取消和失败路径。

Android 集成测试覆盖：

- 前台服务创建、恢复和通知关闭。
- APK 安装权限及安装 Intent。
- 所有文件访问权限检查。
- OBB 部署、保留旧文件和失败回退。
- 应用返回前台后的版本重新检测。

手工真机测试至少覆盖 Android 11、13 和 15，以及首次安装、覆盖更新、锁屏下载、杀进程恢复、空间不足、官方源切换 GitHub、安装取消和 OBB 复用。

## 18. 第一阶段实现范围

第一阶段交付完整客户端骨架和可本地验证的更新流程：

- Stable/Beta 清单模型。
- 分块下载状态与来源无关的复用规则。
- 前台服务与进度通知。
- APK/OBB 合并、SHA-256 校验。
- 空间预估。
- APK 安装与返回检测。
- OBB 权限、部署和回退。
- 游戏启动。
- 本地测试清单和自动化测试。

OSS/GitHub 的生产上传与服务器清单发布脚本在客户端状态机稳定后接入，但清单和分块格式从第一阶段起固定，避免后续返工。
