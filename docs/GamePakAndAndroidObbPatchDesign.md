# 零境交错 PAK 与 Android OBB 补丁更新设计

> 状态：设计记录，尚未实装。
>
> 本文用于约束以后 PC 启动器、Android 启动器、开发发布页和更新服务器的补丁更新实现。任何接手者应先阅读本文，再修改下载、发布或安装流程。
>
> 硬性边界：启动器项目不得修改、编译、停止、清理或触发 `D:\UnrealMap\CrossingVoid` 虚幻工程。发布工具只读取用户已经打包好的文件。

## 1. 目标

当前完整 Android 游戏包约包含一个 APK 和一个接近 2 GB 的 main OBB。每次只修改少量资源却重新下载完整 OBB，会浪费用户时间、Github/OSS 流量和手机储存空间。

目标是建立两套共享规则、分别落地的更新能力：

1. Windows 游戏以后可以直接下载 Unreal 生成的 `_P.pak` 补丁。
2. Android 游戏以后使用“完整 main OBB + 一个可替换的 patch OBB”。
3. APK、PAK、main OBB 和 patch OBB 分别判断是否需要更新，不再把它们强制捆成一个完整 ZIP。
4. 每个文件都通过大小、SHA-256、适用基础版本和目标路径判断身份，不能只看文件名或界面版本号。
5. 下载中断、启动器被替换、系统杀死进程、来源切换和发布新版本后，仍能恢复到明确状态。

本文不要求 Unreal 立即生成补丁，也不实现第三方 OBB 二进制差分。

## 2. 术语与真实关系

### 2.1 PAK 补丁

Unreal 在当前 `UsePakFile=True`、`bUseIoStore=False` 的模式下，补丁的核心产物通常是：

```text
pakchunk0-Windows_P.pak
pakchunk0-Android_ASTC_P.pak
```

具体名称由 Unreal 版本、平台、Chunk 和打包配置决定，发布工具不能把上面的示例硬编码成唯一名称。

`_P.pak` 存放的是相对于指定基础 Release 新增或变化的 Cooked 内容。它不是旧 PAK 的二进制差分，也不能代替 APK、EXE 或原生动态库。

### 2.2 main OBB

Android 首次完整发布使用：

```text
main.<versionCode>.<packageName>.obb
```

当前包名为：

```text
com.TFAC.CorssingVoid
```

main OBB 是完整基础资源容器。只要基础版本仍兼容，后续小更新不应重复下载它。

### 2.3 patch OBB

Android 补丁容器使用：

```text
patch.<versionCode>.<packageName>.obb
```

patch OBB 内部承载 Unreal 的补丁 PAK。它不是对 main OBB 使用 `xdelta`、`bsdiff` 等工具产生的二进制差分。

Android 通常按当前包名与版本识别一个 main OBB 和一个 patch OBB。因此设计上只保留一个当前 patch OBB，不能无限累加多个 patch 文件。

### 2.4 累积补丁

每次发布的新 patch OBB 应相对于同一个基础 Release 生成，并包含从基础版本到当前目标版本所需的全部补丁内容。例如：

```text
基础 main OBB：0.5.12
第一次 patch OBB：0.5.12 -> 0.5.12.1
第二次 patch OBB：0.5.12 -> 0.5.12.2
```

第二次 patch OBB 替换第一次，而不是要求用户同时保存两个 patch OBB。补丁增长过大时，应制作新的完整基础 Release，重新生成 main OBB，并清空旧补丁链。

## 3. 哪些修改能进入 PAK，哪些必须更新程序

### 3.1 通常可以进入 PAK 补丁

- 蓝图及其编译后的字节码。
- 地图、关卡和 World Partition 已 Cook 的内容。
- 贴图、材质、材质实例、模型、骨骼和动画。
- 音频、粒子、Niagara、字体和 UI 资源。
- DataTable、Curve、DataAsset 和其他 Cooked UObject 资源。
- 已经存在于程序中的类所使用的新配置或新资源。

前提是补丁打包时使用正确的基础 Release，并且资源和依赖都被 Cook。资源没有被 Cook、被规则排除或依赖缺失时，放进项目目录不等于会出现在补丁里。

### 3.2 必须更新 Windows 程序或 Android APK

- Unreal C++ 源码、引擎模块和编译后的游戏逻辑。
- Android Java/Kotlin 代码。
- Android 插件、UPL、JNI 和原生 `.so`。
- `AndroidManifest.xml`、权限、Activity、Service、Provider 和 Intent Filter。
- 接入或升级原生 SDK。
- 包名、签名证书、ABI、最低系统版本和目标系统版本。
- Windows EXE、DLL、运行库和第三方原生插件。
- 任何要求重新链接程序二进制的修改。

### 3.3 同时修改资源和底层代码

如果一次版本既修改了资源又修改了底层程序：

```text
Windows：新 EXE/DLL 等程序文件 + 新 `_P.pak`
Android：新 APK + 新 patch OBB
```

如果只改底层代码且资源完全没变，可以只更新程序文件或 APK，并继续复用已有 main/patch OBB，但必须通过清单中的 SHA-256 和兼容关系确认，而不能仅凭“看起来没改资源”判断。

## 4. 当前项目必须特别处理的同包名安装方式

Android 启动器与游戏当前都使用：

```text
com.TFAC.CorssingVoid
```

并使用同一张签名证书。它们不能同时安装：

```text
游戏 -> 安装一次性启动器，启动器覆盖游戏 APK
启动器准备资源 -> 安装游戏 APK，游戏覆盖启动器
```

这能让启动器通过 `getObbDir()` 写入自己包名对应的 OBB 目录，规避 Android 11 以后普通应用无法直接修改其他应用 OBB 目录的问题。

这个架构带来一条容易遗漏的规则：

> 即使某次更新只有 patch OBB 发生变化，启动器完成工作后仍需要一份游戏 APK 把自己覆盖回游戏。

这里要区分两个概念：

- “游戏 APK 内容是否更新”：可能没有更新。
- “更新流程是否需要游戏 APK”：同包名一次性启动器模式下一定需要。

没有修改 APK 时可以复用服务器上已经验证过的当前游戏 APK，或者以后在本机保留一份已验证 APK。不能因为本次是资源补丁就让流程停在启动器中。

当前启动器安装游戏后会清理私有 `prepared` 目录。因此在没有实现 APK 保留策略前，补丁发布清单仍必须引用一份可下载的游戏 APK。

## 5. 总体架构

```text
Unreal 已完成的打包输出
    |
    | 只读选择，不修改 Unreal
    v
PC 启动器开发发布页
    |-- 校验 APK/EXE/PAK/OBB
    |-- 生成分片与 SHA-256
    |-- 上传 OSS 与 Github
    |-- 发布版本清单
    v
更新服务器
    |-- 返回 Stable/Beta 清单
    |-- 为 OSS 对象签发短时 URL
    |-- 保留 Github Release 文件名
    v
PC 或 Android 启动器
    |-- 检查本地基础文件及 SHA-256
    |-- 选择完整更新或补丁更新
    |-- 下载、校验、原子部署
    `-- 安装程序文件或 APK
```

发布工具不负责调用 Unreal，也不负责替用户生成 `_P.pak`。它只消费用户明确选择的已打包产物。

## 6. Windows PAK 补丁方案，后续制作

### 6.1 发布输入

PC 开发页以后增加“上传 PC 补丁”入口。输入应是 Unreal 已经生成的 Windows 补丁目录，而不是整个 Unreal 工程。

发布前至少检查：

- 存在一个或多个 `_P.pak`。
- 文件不为 0 字节。
- 文件名和目标相对路径安全，不含 `..`、盘符或绝对路径。
- 如果当前项目启用 PAK 签名，配套 `.sig` 必须完整。
- 如果未来启用 IoStore，则需要重新设计为 `.utoc + .ucas + .pak` 文件组，不能继续套用本文当前的单 PAK 假设。
- 基础 Release 的版本、文件清单和 SHA-256 已知。

### 6.2 Windows 部署位置

补丁文件应部署到游戏实际 PAK 目录，例如：

```text
CrossingVoid\Content\Paks\pakchunk0-Windows_P.pak
```

清单保存相对路径，启动器将其拼接到已确认的游戏根目录。禁止服务器下发任意绝对路径。

### 6.3 Windows 部署流程

```text
检查基础版本与基础 PAK 哈希
-> 下载缺失分片
-> 逐片 SHA-256 校验
-> 合并补丁文件
-> 校验完整 `_P.pak` SHA-256
-> 写入同目录 `.incoming`
-> 再次校验
-> 原子替换目标 `_P.pak`
-> 启动前快速检查
```

旧补丁不能在新补丁验证完成前删除。新补丁是累积补丁时，原子替换成功后才能删除已经被清单明确废弃的旧补丁。不能使用“删除所有 `_P.pak`”这种宽泛规则。

### 6.4 Windows 程序更新

如果清单同时包含 EXE、DLL 或插件更新，不能在游戏运行时直接覆盖。启动器应先阻止游戏启动，必要时使用现有安装/更新阶段完成程序文件替换，然后再部署 PAK。

PAK 更新成功不代表程序更新成功，两个结果必须分别记录。任一必需文件失败时，不得把整体状态写为“已是最新版本”。

## 7. Android patch OBB 方案，优先制作

### 7.1 发布输入

Android 发布页以后支持两种输入：

1. 完整发布：APK + main OBB。
2. 补丁发布：游戏 APK 引用或新 APK + patch OBB。

第一版优先接收 Unreal 已经生成的 patch OBB。发布工具不要默认把任意 `_P.pak` 手工 ZIP 成 OBB，因为不同 Unreal 版本的 OBB 结构、压缩方式、挂载规则和元数据可能不同。

只有经过当前项目真机验证后，才可以增加“选择 `_P.pak` 并封装 patch OBB”的辅助能力。即使增加，该能力也必须输出与 Unreal 原生结果一致的结构，并通过游戏实际挂载验证。

### 7.2 Android 目标目录

```text
Android/obb/com.TFAC.CorssingVoid/
```

同一基础版本正常保留：

```text
main.<versionCode>.com.TFAC.CorssingVoid.obb
patch.<versionCode>.com.TFAC.CorssingVoid.obb
```

当前 `GameDownloadService.removeStaleObbFiles` 会删除除本次解压文件外的其他 `main.*.obb` 和 `patch.*.obb`。实现补丁更新前必须重写为按类型和目标版本清理，否则写入 patch OBB 时可能误删仍然需要的 main OBB。

正确清理规则：

- 安装 main OBB 时只替换清单指定的 main OBB。
- 安装 patch OBB 时保留匹配的 main OBB，只替换旧 patch OBB。
- `.extracting`、`.incoming` 和清单明确废弃的旧版本文件可以在恢复检查后清理。
- 未通过哈希验证的文件不能替换当前有效文件。
- 不允许使用“保留刚写入文件，其他 OBB 全删”的实现。

### 7.3 versionCode 与 OBB 文件名

OBB 文件名中的 `versionCode` 必须与目标游戏 APK 的 `versionCode` 对应。

只更新资源且继续使用同一个游戏 APK 时：

```text
APK versionCode 不变
main OBB 名称不变
patch OBB 使用相同 versionCode
```

底层代码更新导致游戏 APK 的 `versionCode` 增加时：

- 新 patch OBB 使用新 APK 的 `versionCode`。
- 如果 main OBB 内容未变，启动器只有在旧 main OBB SHA-256 与清单声明完全一致时，才允许将它复制或原子重命名为新 versionCode 对应文件名。
- 如果哈希不匹配或清单未声明可复用，必须下载新的 main OBB。
- 不能只改文件名而不验证内容。

### 7.4 Android 下载与部署顺序

```text
读取目标清单
-> 检查现有 main OBB 和 patch OBB
-> 校验基础 main OBB SHA-256
-> 决定完整更新或补丁更新
-> 检查峰值储存空间
-> 下载游戏 APK（新文件或复用当前已验证文件）
-> 下载 patch OBB 分片
-> 合并并校验 patch OBB
-> 写入 patch OBB.extracting
-> fsync/关闭文件并再次校验
-> 原子替换正式 patch OBB
-> 确认 main + patch + 游戏 APK 均就绪
-> 状态变为 ready
-> 用户确认安装游戏 APK
-> 游戏覆盖一次性启动器
```

如果本次不需要 patch OBB，则省略 patch 阶段。如果本次需要完整 main OBB，则复用当前完整安装流程，但 main 与 patch 必须分别记录状态。

### 7.5 不允许错误地进入 ready

以下条件同时成立才能进入 `ready`：

- 游戏 APK 已存在、可读、大小和 SHA-256 正确。
- APK 包名正确。
- APK 签名与启动器一致。
- APK versionCode 不低于当前一次性启动器。
- 目标 main OBB 存在且 SHA-256 正确。
- 清单要求 patch OBB 时，目标 patch OBB 存在且 SHA-256 正确。
- 没有残留的 `.extracting` 文件被当成有效文件。

界面不能根据按钮文字或上一次内存状态推断 ready。

## 8. 更新清单设计

现有 Android 游戏接口只返回一个包含 APK + OBB 的完整 ZIP `asset`。补丁更新需要升级清单，但服务器应在迁移期保留旧字段，直到支持新清单的启动器已经普及。

建议使用 `schemaVersion: 2`：

```json
{
  "schemaVersion": 2,
  "productKey": "crossingvoid-android-game",
  "channel": "stable",
  "releaseType": "patch",
  "gameVersion": "0.5.12",
  "displayVersion": "0.5.12.1",
  "versionCode": 1,
  "packageName": "com.TFAC.CorssingVoid",
  "publishedAt": "2026-07-21T00:00:00Z",
  "requirements": {
    "baseReleaseId": "android-0.5.12-base",
    "baseMainObbSha256": "64位小写SHA256",
    "acceptedGameVersions": ["0.5.12"]
  },
  "assets": {
    "gameApk": {
      "changed": false,
      "fileName": "CrossingVoid-Android-0.5.12.apk",
      "versionCode": 1,
      "sizeBytes": 0,
      "sha256": "64位小写SHA256",
      "chunks": []
    },
    "mainObb": {
      "required": true,
      "changed": false,
      "fileName": "main.1.com.TFAC.CorssingVoid.obb",
      "sizeBytes": 0,
      "sha256": "64位小写SHA256",
      "chunks": []
    },
    "patchObb": {
      "required": true,
      "fileName": "patch.1.com.TFAC.CorssingVoid.obb",
      "sizeBytes": 0,
      "sha256": "64位小写SHA256",
      "chunks": []
    }
  }
}
```

说明：

- `releaseType` 为 `full` 或 `patch`。
- `gameApk.changed=false` 表示 APK 内容没有变化，但同包名一次性启动器仍需要下载或复用它来恢复游戏。
- `mainObb.changed=false` 只有在本地哈希匹配时才能复用。
- `patchObb.required=false` 表示该版本不使用 patch OBB。
- `baseReleaseId` 防止把补丁应用到错误的基础包。
- `acceptedGameVersions` 只是辅助提示；真正兼容判断仍以文件 SHA-256 和 versionCode 为准。
- 每个 `chunks` 项继续保存索引、总数、文件名、对象键、大小和 SHA-256。
- OSS 与 Github 的同一分片内容和 SHA-256 必须完全相同。

Windows 可以复用同一个基础思想，但 `assets` 改为程序文件组、基础 PAK 和 patch PAK 文件组，不能把 Android OBB 字段直接套到 Windows。

## 9. 本地状态设计

下载状态必须持久化以下信息：

- 清单 `schemaVersion`、产品、通道、显示版本和发布 ID。
- `releaseType`、目标 `versionCode` 和包名。
- 基础 main OBB 要求的 SHA-256 和本地校验结果。
- APK、main OBB、patch OBB 各自的下载、合并、校验和部署状态。
- 每个分片的文件名、大小、SHA-256、已下载字节和验证状态。
- 当前来源、暂停原因、失败阶段和可重试信息。
- 已部署文件的路径、SHA-256 和原子替换结果。
- 游戏 APK 是否只是复用文件，还是本次真正更新。

状态文件必须使用临时文件加原子替换。内存状态只用于展示，不能成为恢复依据。

建议阶段：

```text
idle
checking-base
planning
downloading-apk
downloading-main-obb
downloading-patch-obb
merging
verifying
deploying-main-obb
deploying-patch-obb
ready
installing-apk
paused
error
```

界面仍可把多个内部阶段合并为简短中文，但日志必须记录真实阶段和当前文件。

## 10. 中断、重试与版本变化

### 10.1 下载中断

- 只复用 SHA-256 已验证的完整分片。
- 未完成分片可以使用 HTTP Range 继续。
- 切换 OSS/Github 后复用相同哈希的分片。
- 通知栏前台服务和 `START_REDELIVER_INTENT` 继续沿用。

### 10.2 部署中断

- 正式文件写入前使用 `.extracting` 或 `.incoming`。
- 原子替换前不得删除当前有效 main/patch OBB。
- 启动时发现临时文件，先按状态与哈希判断能否继续；不能直接当成成功。

### 10.3 下载期间发布新版本

- 重新读取清单。
- 哈希仍被新清单引用的分片可以保留。
- 基础 main OBB 要求变化时，旧 patch 计划整体失效。
- 已经部署但尚未安装 APK 时发布新版本，不自动删除已验证文件；先重新规划，再明确告诉用户需要补充下载什么。

### 10.4 补丁不兼容

以下任一情况发生时回退完整更新：

- 本地 main OBB 缺失或 SHA-256 不匹配。
- 本地基础 Release 无法确认。
- patch OBB 清单损坏或基础版本不一致。
- APK versionCode 与 OBB 命名无法建立合法对应。
- 补丁体积接近完整 main OBB，继续使用补丁已没有收益。

回退应显示“基础资源不匹配，需要下载完整游戏资源”，不能伪装成普通下载失败。

## 11. 储存空间估算

补丁更新不能只看网络下载大小。峰值空间至少包含：

```text
尚未下载的分片
+ 合并后的 patch OBB
+ patch OBB 临时写入文件
+ 游戏 APK
+ Android 系统安装 APK 的临时空间
+ 256 MiB 或总更新量 10% 中较大的安全余量
```

如果实现流式合并并能证明分片在合并后安全释放，可以降低峰值估算；在此之前按最保守情况计算。

main OBB 已验证可复用时，不应把完整 main OBB 大小计入网络下载量，但重命名跨文件系统可能退化为复制，因此空间估算仍要根据实际目标卷判断。

## 12. 安全与完整性

- 所有完整文件和分片必须使用 SHA-256。
- APK 继续校验包名、versionCode 和签名证书。
- OBB 文件名必须匹配清单中的包名和目标 versionCode。
- ZIP 解压继续阻止绝对路径、盘符和 `..` 路径穿越。
- OSS 签名 URL 不写入长期日志。
- Github Release 文件名必须经过 URL 编码。
- 服务器不得根据客户端提交的任意对象键签发下载地址，只允许已发布清单中的对象。
- 日志记录用户操作、清单版本、基础哈希、阶段和错误，但不记录 Token、授权头和完整签名查询参数。

## 13. PC 开发发布页未来设计

开发页增加两个独立入口：

```text
上传 PC 补丁
上传 Android 补丁
```

不要把补丁入口塞回“上传完整游戏本体”并靠猜测目录内容决定行为。用户应明确选择完整发布还是补丁发布。

### 13.1 上传 PC 补丁

流程：

```text
选择 Windows 补丁输出目录
-> 列出发现的 `_P.pak` 和可选 `.sig`
-> 显示基础版本与目标版本
-> 校验文件和目标相对路径
-> 生成分片
-> 上传 OSS
-> 上传 Github Release
-> 生成并上传清单
-> 线上回读清单与抽查分片
```

### 13.2 上传 Android 补丁

流程：

```text
选择 patch OBB
-> 选择或复用游戏 APK
-> 选择对应基础 Release
-> 读取 APK 包名/versionCode/签名
-> 校验 patch OBB 文件名
-> 记录基础 main OBB SHA-256
-> 生成分片
-> 上传 OSS
-> 上传 Github Release
-> 生成并上传 schemaVersion 2 清单
-> 线上回读清单与抽查分片
```

发布阶段需要显示用户能看懂的短文本，例如：

```text
正在检查补丁文件
正在计算文件校验值
正在生成第 3/8 个分片
正在上传到 OSS：第 3/8 片
正在上传到 Github：第 3/8 片
正在更新版本清单
正在验证线上文件
```

脚本失败必须返回真实阶段、文件名、HTTP 状态码和响应正文摘要，不能只显示“上传失败”。

## 14. Android 启动器实现顺序

1. 为 `schemaVersion: 2` 清单解析增加测试，继续兼容当前完整 ZIP 清单。
2. 将一个 `asset` 下载计划扩展为 APK、main OBB、patch OBB 三个可独立复用的文件计划。
3. 为基础 main OBB、目标 patch OBB 和游戏 APK 增加独立 SHA-256 状态。
4. 先写测试证明安装 patch OBB 不会删除 main OBB。
5. 重构 `removeStaleObbFiles`，按 main/patch 类型和目标版本精确清理。
6. 使用临时文件和原子替换部署 patch OBB。
7. 修改 ready 判定和详细进度文本。
8. 增加同包名架构下“APK 未变化但仍需恢复游戏”的处理。
9. 增加储存空间估算、版本变化恢复和完整包回退。
10. 扩展日志字段和设置页清理范围。
11. 在 Android 单元测试、前端测试和 Release 构建通过后，再进行真机挂载验证。

不能先改 UI 按钮再补底层状态。清单、持久化状态和原子部署应先完成。

## 15. 验证清单

### 15.1 自动化测试

- schemaVersion 1 完整包清单仍可解析。
- schemaVersion 2 完整和补丁清单可解析。
- 错误包名、versionCode、哈希和基础 Release 被拒绝。
- patch OBB 部署不会删除 main OBB。
- 新 patch OBB 完整验证前保留旧 patch OBB。
- `.extracting` 文件不会被识别为有效资源。
- 下载来源切换复用相同哈希分片。
- 进程重启恢复 APK/main/patch 各自阶段。
- APK 未变化时仍能完成“启动器 -> 游戏”覆盖。
- 基础 main OBB 不匹配时切换为完整更新。
- 空间不足时在下载前阻止，在下载中安全暂停。

### 15.2 真机测试

- 当前完整 main OBB 的游戏能正常启动。
- 安装一次性启动器后 main OBB 仍存在。
- 只下载 patch OBB 后，游戏 APK 能覆盖启动器并启动。
- 游戏能实际挂载 patch OBB，并表现出补丁资源变化。
- 第二个累积 patch OBB 能替换第一个，不残留错误资源。
- 底层代码更新时，新 APK + patch OBB 能同时生效。
- Android 7、Android 11、Android 13+ 至少各验证一台真实设备或等价环境。
- ARM64、平板和 x86 环境不因补丁逻辑出现白屏或路径差异。
- 下载时锁屏、切后台、杀进程、断网、切源和磁盘不足都能恢复或给出明确错误。

### 15.3 发布验证

- OSS 与 Github 每个分片大小和 SHA-256 一致。
- 更新服务器回读的是刚发布的清单。
- OSS 低于 3 GB 时服务端仍拒绝签发，Github 源可继续使用。
- Stable 与测试服清单、标签和对象键互不覆盖。
- 发布失败时不更新“最新版指针”。

## 16. 明确不采用的方案

### 16.1 不默认对完整 OBB 做二进制差分

不把 `xdelta3`、`bsdiff`、`zstd --patch-from` 作为正式方案。原因：

- OBB/ZIP 内部文件偏移变化可能让少量资源修改产生很大差分。
- 应用差分时通常需要旧 OBB、补丁文件和新 OBB 三份空间。
- 必须严格匹配旧 OBB SHA-256，版本组合会迅速增多。
- 中断恢复、损坏恢复和原子替换明显复杂于 patch OBB。

只有 Unreal patch OBB 在目标设备上无法可靠挂载时，才重新评估二进制差分，并另写设计文档。

### 16.2 不无限保留历史补丁

正式源只服务当前版本。新累积 patch 验证并发布成功后，旧 patch 和旧分片按现有发布清理策略删除。测试服可以保留一份独立测试版本，但不能和正式版共用最新版指针。

### 16.3 不让启动器猜测 Unreal 输出

发布工具不得扫描整个 Unreal 工程、调用 Unreal 打包、修改配置或自动选择“看起来像补丁”的文件。用户必须选择已经完成的输出目录或文件，工具再做严格校验。

## 17. 接手者最先检查的文件

Android 启动器：

```text
D:\UnrealMap\CrossingVoidinitiator-Android\src\services\gameUpdate.ts
D:\UnrealMap\CrossingVoidinitiator-Android\src\services\downloadPlan.ts
D:\UnrealMap\CrossingVoidinitiator-Android\src\services\androidLauncher.ts
D:\UnrealMap\CrossingVoidinitiator-Android\android\app\src\main\java\com\lingjing\launcher\android\GameDownloadService.java
D:\UnrealMap\CrossingVoidinitiator-Android\android\app\src\main\java\com\lingjing\launcher\android\AndroidLauncherPlugin.java
D:\UnrealMap\CrossingVoidinitiator-Android\docs\AndroidLauncherDevelopmentGuide.md
```

PC 发布工具：

```text
D:\UnrealMap\CrossingVoidinitiator-PC\src\App.vue
D:\UnrealMap\CrossingVoidinitiator-PC\src-tauri\src\lib.rs
D:\UnrealMap\CrossingVoidinitiator-PC\Scripts\
D:\UnrealMap\CrossingVoidinitiator-PC\Docs\PCLauncherDevelopmentGuide.md
```

当前实现的重要风险点：

- Android 当前清单只有一个完整 ZIP `asset`，没有独立 APK/main/patch 文件计划。
- Android 当前 `PreparedFiles` 只能记录一个 OBB。
- Android 当前 OBB 清理会在写入一个 OBB 后删除其他 main/patch OBB。
- Android 当前 ready 文案和判断固定为“APK 和 OBB”，没有区分 main 与 patch。
- 同包名一次性启动器完成补丁后仍需要游戏 APK 覆盖回来。

以上五点没有完成前，不能宣称 Android patch OBB 更新已经实装。
