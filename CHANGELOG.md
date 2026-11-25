# 更新日志（CHANGELOG）

本项目遵循 [语义化版本规范（Semantic Versioning）](https://semver.org/lang/zh-CN/)。

---

## [v1.1.1] - 2026-11-25

### 新增（Added）
- **原生 iOS 版本（`ios/`）**：基于 SwiftUI + AVPlayer + Apple AirPlay 2 + 原生 DLNA/UPnP 实现，与 Android v1.1.1 功能完全对齐，支持跨端 JSON 配置互通。
- **首页焦点轮播卡智能升级**：有观看记录时优先展示「继续观看」（含绿色角标、上次观看集数/时间点、播放进度条与「继续播放」按钮），不足 5 条时自动由「今日精选」补位。
- **首页「最新热播 | 我的收藏」双页签**：在「最新热播」旁直接新增「我的收藏」快捷切换页签。
- **GitHub Actions 双端自动化 CI/CD**：新增 Android APK 与 iOS IPA 自动编译与 Release 发布流水线。

---

## [v1.1.0] - 2026-11-25

### 优化（Changed）
- **Google TV / Material Design 3 视觉改版**：
  - 精简为单行 Google TV 顶栏（左侧片源胶囊，右侧搜索 / 网页嗅探 / 投屏图标），大幅提升首屏内容占比。
  - 新增首页 `24.dp` 大圆角「今日精选」自动轮播焦点卡。
  - 海报卡片升级为 `16.dp` 圆角细边框、磨砂角标与金星评分徽章，新增流光骨架屏与悬浮「回到顶部」按钮。
- **两层分级分类 + 筛选总览抽屉**：
  - 一级大类常驻顶栏，选中父分类后动态展开二级子分类条。
  - 右侧常驻「全部分类与筛选」面板，支持分组分类总览、年份筛选与高分/最新排序。

---

## [v1.0.0] - 2026-11-24

### 新增（Added）
- **核心架构与谷歌风格 UI**：基于 Kotlin + Jetpack Compose + Material Design 3 + Material You 动态取色。
- **多协议可配置视频源**：支持 MacCMS JSON/XML、T4 API、网页嗅探浏览器及 `cyao2q/files` TVBox JSON 订阅导入。
- **高清播放与投屏**：Media3 ExoPlayer 高清播放（倍速/手势/比例/PiP/断点续播）与纯 Kotlin 原生 DLNA 局域网发现及 SOAP 遥控器。
- **真机实测与稳定性增强**：
  - IPv4 优先 DNS 解析器 + 9 个真机实测直连高速高清源 + 自动容灾切源。
  - MacCMS 父子分类并发聚合修复。
  - 9 源并发流式搜索与三重 MacCMS 搜索回退。
  - 4 条高速 IPv4 央视/卫视直播源与单频道多线路自动/手动切线。
