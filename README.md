# 极光视界 · CineCast

一款遵循 **Google Material Design 3（Google TV / Google Play Books / Material You）** 设计语言打造的多平台 **可配置影视播放投屏 + 独立多源小说阅读** 双栖应用，同时提供 **Android 原生版（Kotlin + Jetpack Compose + Media3 + Jsoup）** 与 **iOS 原生版（SwiftUI + AVPlayer + AirPlay 2 + DLNA）**。

影视模块兼容 [cyao2q/files](https://github.com/cyao2q/files) 等 TVBox JSON 配置生态；**独立小说阅读模块**兼容 [XIU2/Yuedu](https://github.com/XIU2/Yuedu)、[liufuyou/read](https://github.com/liufuyou/read) 等 GitHub 开源 **Legado 3.0 书源 JSON** 生态，**视频源与小说书源、功能与存储完全独立隔离**，开箱即用。

---

## 核心特性

- **Google Material Design 3 视觉与交互**：
  - 统一精简 Google TV 顶栏（片源切换胶囊 + 搜索 / 网页嗅探 / 投屏快捷入口）
  - 顶部焦点轮播卡智能优先展示 **「继续观看」**（含播放进度条与一键断点续播），无历史时自动展示 **「今日精选」**
  - 首页「最新热播 | 我的收藏」双页签快捷切换
  - **两层分级分类 + 全部分类与筛选面板**：自动聚合苹果 CMS 父级大类、展开二级子分类、支持年份与高分排序筛选
  - 支持深色 / 浅色 / 跟随系统主题及 Android 12+ Material You 动态壁纸取色
- **独立小说阅读模块（v1.2.0 新增，Google Play Books 风格）**：
  - **彻底分离架构**：独立底部导航「阅读」（内含 `书架 | 书城 | 书源` 分段导航）、独立 `NovelRepository` 本地存储与 `NovelBackupBundle` 备份，与影视源零耦合
  - **GitHub 开源书源生态**：内置 8 个从 GitHub 实测筛选的高可用小说源（含 `XIU2/Yuedu` 酷我 API / 速读谷 / 手机小说、`liufuyou/read` 全本同人 / ESJZone / 爱尚 / 英文原著，以及永久直连的 **Gutendex 开源中文与世界名著库**），并支持一键订阅拉取或粘贴任意 Legado 3.0 JSON 书源
  - **全书源并发搜书 & 在线一键换源**：支持多书源并发流式搜书、测速筛选、阅读中一键换源续读与下一章静默预加载
  - **Google 纸感沉浸阅读器**：4 套护眼阅读主题（纯净纸白 / 暖黄羊皮 / 护眼豆沙 / 深邃暗夜）、字号/行距/衬线字体自定义、正倒序目录抽屉与滑块跳章
- **多协议可配置视频源**：
  - 内置 9 个实测高速直连高清源（极速 / 虎牙 / 如意 / 360 / 红牛 / iKun / 新浪 / 魔都 / 猫眼），支持启动自动容灾切源与 9 源并发流式秒搜
  - 支持 **苹果 CMS JSON (Type 1)**、**XML (Type 0)**、**TVBox T4 (Type 4)**、**任意视频网站网页嗅探**
  - 一键订阅导入 `https://cyao2q.github.io/files/m.json` / `xs.json` 等 TVBox 配置（自动解析 `sites` / `lives` / `parses` / `ads`）
- **高清播放与双模投屏**：
  - Android 采用 **Media3 ExoPlayer**（HLS/DASH/MP4 硬解、手势亮度/音量/进度、0.5x~3.0x 倍速、画中画 PiP、原生 **DLNA/UPnP** 投屏与遥控器）
  - iOS 采用 **AVPlayer**（HLS/MP4 硬解、画中画 PiP、原生 **Apple AirPlay 2 隔空播放** + **DLNA/UPnP** 双模投屏）
- **高清电视直播**：内置 4 条高速 IPv4 央视/卫视直播源（M3U & TVBox TXT），支持单频道多线路自动切线与手动切线。
- **跨端配置互通**：Android 与 iOS 均支持一键复制导出 / 粘贴恢复统一格式 JSON 配置（影视配置与小说书源配置独立备份）。

---

## 仓库目录结构

```text
CineCast/
├── android/                  # Android 原生工程 (Kotlin + Jetpack Compose + Material 3 + Media3)
├── ios/                      # iOS 原生工程 (SwiftUI + AVPlayer + AirPlay 2 + DLNA + XcodeGen)
├── docs/                     # 完整产品架构文档、用户指南、开发调试手册、iOS打包指南与配置模板
├── .github/workflows/        # GitHub Actions 自动化编译 Android APK 与 iOS IPA 流水线
├── CHANGELOG.md              # 语义化版本变更日志
└── README.md
```

---

## 获取安装包（CI 自动构建）

每次推送代码或发布 `v*` 版本标签后，GitHub Actions 会自动编译双端安装包：
- **Android APK**：进入仓库 **Actions → Build CineCast Android APK**（或 **Releases**）下载 `极光视界-CineCast-Android-*.apk`（兼容 Android 7.0+ 及鸿蒙 HarmonyOS 2~4.x）。
- **iOS IPA**：进入仓库 **Actions → Build CineCast iOS IPA**（或 **Releases**）下载 `极光视界-CineCast-iOS-*-unsigned.ipa`，使用 Sideloadly / 爱思助手 / TrollStore 安装至 iPhone（详阅 [docs/04_iOS版打包签名与安装指南.md](docs/04_iOS版打包签名与安装指南.md)）。

---

## 本地构建

### Android
```bash
cd android
./gradlew testDebugUnitTest assembleRelease
```

### iOS (macOS + Xcode 15+)
```bash
cd ios
brew install xcodegen
xcodegen generate
open CineCast.xcodeproj
```

---

## 配套文档

- [01_产品需求与架构设计文档.md](docs/01_产品需求与架构设计文档.md)
- [02_用户使用与视频源配置指南.md](docs/02_用户使用与视频源配置指南.md)
- [03_开发编译与真机调试部署手册.md](docs/03_开发编译与真机调试部署手册.md)
- [04_iOS版打包签名与安装指南.md](docs/04_iOS版打包签名与安装指南.md)
- [05_小说阅读模块详细设计文档.md](docs/05_小说阅读模块详细设计文档.md)
- [06_小说阅读模块部署与书源导入手册.md](docs/06_小说阅读模块部署与书源导入手册.md)
- [示例视频源配置_CineCast备份模板.json](docs/示例视频源配置_CineCast备份模板.json)
- [示例小说书源配置_Legado模板.json](docs/示例小说书源配置_Legado模板.json)
