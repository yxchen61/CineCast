# 极光视界 · CineCast

一款遵循 **Google Material Design 3（Google TV / Google Play Books / YouTube Music / Material You）** 设计语言打造的多平台 **影视播放投屏 + 独立多源小说阅读 + 独立多源音乐播放** 三栖个人媒体应用，同时提供 **Android 原生版（Kotlin + Jetpack Compose + Media3 + Jsoup）** 与 **iOS 原生版（SwiftUI + AVPlayer + AirPlay 2 + DLNA）**。

影视模块兼容 [cyao2q/files](https://github.com/cyao2q/files) 等 TVBox JSON 配置生态；**独立小说阅读模块**兼容 [XIU2/Yuedu](https://github.com/XIU2/Yuedu)、[liufuyou/read](https://github.com/liufuyou/read) 等 GitHub 开源 **Legado 3.0 书源 JSON** 生态；**独立音乐模块**兼容 **Meting API**、**GDStudio API**、**MusicFree `plugins.json`**、**Audius** 与 **Radio-Browser** 开源音乐生态。**三大模块的源配置、本地存储与交互功能 100% 物理隔离**，开箱即用。

---

## 核心特性

- **Google Material Design 3 视觉与交互**：
  - 统一精简 Google TV 顶栏（片源切换胶囊 + 搜索 / 网页嗅探 / 投屏快捷入口）
  - 顶部焦点轮播卡智能优先展示 **「继续观看」**（含播放进度条与一键断点续播），无历史时自动展示 **「今日精选」**
  - 首页「最新热播 | 我的收藏」双页签快捷切换；**「推荐」旁常驻纯英文动画「阳阳专区」**（基于 `vod_lang` 严格过滤，100% 英语原版动画，含少儿启蒙/动画电影/欧美英语动漫子筛选）
  - **两层分级分类 + 全部分类与筛选面板**：自动聚合苹果 CMS 父级大类、展开二级子分类、支持年份与高分排序筛选
  - 支持深色 / 浅色 / 跟随系统主题及 Android 12+ Material You 动态壁纸取色
- **独立音乐播放模块（v1.3.0 ~ v1.6.0，YouTube Music / Material 3 Expressive 风格）**：
  - **三域彻底隔离**：独立底部导航「音乐」（内含 `音乐馆 | 歌手 | 歌单 | 音乐源` 胶囊导航）、独立 `MusicRepository` 存储与 `MusicAudioController` 音频播放内核（播放视频/评书时自动暂停音乐防串音）
  - **4 大国内实测 320K 完整全曲音源 + 自动去伴奏（v1.7.1 升级）**：默认置顶 **奇杰雅云音乐 320K（7~12MB 全曲）**、**酷我官方高速直连（4MB 官方 CDN 搜歌秒播）**、**i-Meto 官方 Meting 320K（1.1s 极速全曲）** 与 **GD音乐台 · JOOX 320K（周杰伦/港台 10MB 全曲）**，辅以 Injahow / Audius / Radio-Browser；全链路自动过滤 **伴奏 / KTV / 消音 / 试听片段**，播放中若检测到 `<= 65s` 试听片段立即自动跨源切换完整版
  - **歌手分类查询 + 自动连播与同曲跨源容灾**：内置 7 大分类 40 位精选歌手；支持锁屏/后台稳定自动播放下一首，遇到音频失效或播放报错时**自动跨其余音源精准匹配并续播同一首完整版歌曲**
- **独立小说阅读与听评书模块（v1.2.0 ~ v1.7.0，Google Play Books 风格）**：
  - **书籍源与评书源彻底分离（v1.7.0 升级）**：独立底部导航「阅读」（内含 `书架 | 书城 | 听评书 | 书源` 胶囊导航），文字书籍书源（`cinecast_novel_sources.json`）与评书音频源（`cinecast_pingshu_sources.json`）100% 物理与界面隔离，各具独立管理面板与导出/恢复备份
  - **5 大国内实测评书源 + 全屏听评书**：内置 **B站全本分P评书馆（200~419回无损 DASH M4A 直连）**、**喜马拉雅免费评书馆**、**酷我评书名家馆**、**恋听网评书相声馆** 与 **曲艺相声 24h 有声电台**（覆盖单田芳 / 袁阔成 / 田连元 / 刘兰芳 / 连丽如 / 郭德纲 / 王玥波 / 张少佐），支持全屏听评书、倍速（0.75x~2.0x）、助眠定时关闭、快退15s/快进30s、选回目录、断点续听与后台自动连播下一回
  - **国内 36ms 直连古典名著馆 + GitHub 开源书源生态（v1.6.0 升级）**：全新内置国内免代理秒开的 **五千言 · 中华古典名著馆（`CLASSICS_5000YAN`，含四大名著/封神/聊斋/儒林/论语/唐诗宋词等 19 部全本简体名著）** 与 **酷我小说书城/历史文学馆（纯 JSON API 秒开）**，自动关闭国内受限的维基文库海外源（保留在「书源」页按需手动开启），并支持订阅导入任意 GitHub Legado 3.0 书源
  - **全书源并发搜书 & 在线一键换源**：支持多书源并发流式搜书、测速筛选、阅读中一键换源续读与下一章静默预加载
  - **Google 纸感沉浸阅读器**：4 套护眼阅读主题（纯净纸白 / 暖黄羊皮 / 护眼豆沙 / 深邃暗夜）、字号/行距/衬线字体自定义、正倒序目录抽屉与滑块跳章
- **多协议可配置视频源（含英文动画片精选）**：
  - 内置 14 个实测高速直连高清源（极速 / 虎牙 / 如意 / 360 / 红牛 / iKun / 新浪 / 魔都 / 猫眼 + **v1.4.0 新增 5 个英文动画/欧美动漫直连源：速播动漫、光速动漫、火狐动漫、电影天堂动漫、优质1080动画**，以及 **B站英文动画专区 / PBS Kids 英语原版动画** 网页嗅探），支持启动自动容灾切源与并发流式秒搜
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
- [07_音乐模块详细设计文档.md](docs/07_音乐模块详细设计文档.md)
- [08_音乐模块部署与音源导入手册.md](docs/08_音乐模块部署与音源导入手册.md)
- [示例视频源配置_CineCast备份模板.json](docs/示例视频源配置_CineCast备份模板.json)
- [示例小说书源配置_Legado模板.json](docs/示例小说书源配置_Legado模板.json)
- [示例音乐源配置_CineCast模板.json](docs/示例音乐源配置_CineCast模板.json)
