# 极光视界 · CineCast

一款遵循 **Google Material Design 3（Google TV / Material You）** 设计语言打造的多平台可配置视频源高清播放与投屏应用，同时提供 **Android 原生版（Kotlin + Jetpack Compose + Media3）** 与 **iOS 原生版（SwiftUI + AVPlayer + AirPlay 2 + DLNA）**。

兼容 [cyao2q/files](https://github.com/cyao2q/files) 等 TVBox JSON 配置订阅生态，开箱即用。

---

## 核心特性

- **Google Material Design 3 视觉与交互**：
  - 统一精简 Google TV 顶栏（片源切换胶囊 + 搜索 / 网页嗅探 / 投屏快捷入口）
  - 顶部焦点轮播卡智能优先展示 **「继续观看」**（含播放进度条与一键断点续播），无历史时自动展示 **「今日精选」**
  - 首页「最新热播 | 我的收藏」双页签快捷切换
  - **两层分级分类 + 全部分类与筛选面板**：自动聚合苹果 CMS 父级大类、展开二级子分类、支持年份与高分排序筛选
  - 支持深色 / 浅色 / 跟随系统主题及 Android 12+ Material You 动态壁纸取色
- **多协议可配置视频源**：
  - 内置 9 个实测高速直连高清源（极速 / 虎牙 / 如意 / 360 / 红牛 / iKun / 新浪 / 魔都 / 猫眼），支持启动自动容灾切源与 9 源并发流式秒搜
  - 支持 **苹果 CMS JSON (Type 1)**、**XML (Type 0)**、**TVBox T4 (Type 4)**、**任意视频网站网页嗅探**
  - 一键订阅导入 `https://cyao2q.github.io/files/m.json` / `xs.json` 等 TVBox 配置（自动解析 `sites` / `lives` / `parses` / `ads`）
- **高清播放与双模投屏**：
  - Android 采用 **Media3 ExoPlayer**（HLS/DASH/MP4 硬解、手势亮度/音量/进度、0.5x~3.0x 倍速、画中画 PiP、原生 **DLNA/UPnP** 投屏与遥控器）
  - iOS 采用 **AVPlayer**（HLS/MP4 硬解、画中画 PiP、原生 **Apple AirPlay 2 隔空播放** + **DLNA/UPnP** 双模投屏）
- **高清电视直播**：内置 4 条高速 IPv4 央视/卫视直播源（M3U & TVBox TXT），支持单频道多线路自动切线与手动切线。
- **跨端配置互通**：Android 与 iOS 均支持一键复制导出 / 粘贴恢复统一格式 JSON 配置。

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
- [示例视频源配置_CineCast备份模板.json](docs/示例视频源配置_CineCast备份模板.json)
