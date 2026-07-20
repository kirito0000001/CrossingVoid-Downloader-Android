# 零境启动器 Android 发布仓库

本仓库只用于发布《零境启动器》Android 安装包与热更新清单。

## 内容

- `launcher/android-installer-latest.json`：Android 启动器最新版本清单
- `android-installer-v*` Release：Android 启动器 APK

游戏 APK、OBB 和分片资源不存放在本仓库。游戏资源由零境更新服务器统一生成清单，并通过 OSS 官方源或 GitHub 游戏 Release 下载。

## 源码

Android 启动器源码、构建脚本、测试与维护文档：

https://github.com/kirito0000001/CrossingVoid-Downloader-Android

## PC 启动器

PC 启动器更新与发布仓库：

https://gitee.com/xiaojie578/CrossingVoid-Downloader-PC

## 提醒

启动器与游戏使用同一个 Android 包名和同一张签名证书。安装器会先下载并校验 APK 与 OBB，将 OBB 放入同包名目录，再由游戏 APK 覆盖安装器。
