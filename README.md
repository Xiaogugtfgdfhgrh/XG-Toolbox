# 小骨工具箱

小骨工具箱是一款面向 Android 设备管理与 Root 环境的多功能工具箱，使用 Kotlin、Jetpack Compose 和模块化架构构建。

## 主要功能

- **设备概览**：查看设备硬件、系统版本、存储、网络、电池与 Root 状态，支持字段搜索和复制。
- **应用管理**：浏览和搜索应用，查看签名、安装信息、运行 ABI、内存与流量数据，并提供冻结、卸载、备份恢复及批量操作。
- **系统工具**：提供性能优化、空间清理、显示设置、分区镜像写入、进程管理和终端等系统维护功能。
- **Root 工具**：支持 Root 状态识别、Shell 命令执行、自定义脚本，以及 Magisk、KernelSU、APatch 等模块管理。
- **操作记录**：集中展示设备状态与近期操作结果，便于核对执行情况。

> 部分功能需要设备已获取 Root 权限。执行系统级操作前，请先备份重要数据。

## 下载

请从 [GitHub Releases](https://github.com/Xiaogugtfgdfhgrh/XG-Toolbox/releases/latest) 下载最新 APK。

当前版本：**1.0.0**

- [下载 XG-Toolbox 1.0.0 APK](https://github.com/Xiaogugtfgdfhgrh/XG-Toolbox/releases/download/v1.0.0/XG-Toolbox-1.0.0-release.apk)
- [下载 SHA256SUMS.txt](https://github.com/Xiaogugtfgdfhgrh/XG-Toolbox/releases/download/v1.0.0/SHA256SUMS.txt)

系统要求：Android 9.0（API 28）及以上。

## 构建

构建环境：

- JDK 17
- Android SDK 36
- Gradle Wrapper（随仓库提供）

```bash
./gradlew assembleDebug
```

调试 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。发布构建需要在本地提供签名配置后再执行 `./gradlew assembleRelease`。
