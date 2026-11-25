# 极光视界（CineCast）iOS 版打包、签名与安装完整指南

> **版本**：v1.1.1（与 Android 最新版 100% 功能对齐）  
> **技术栈**：原生 SwiftUI（iOS 16+）+ AVPlayer 高清硬解 + **Apple AirPlay 2 隔空播放 + 原生 DLNA 投屏** + WKWebView 网页嗅探  
> **配置互通**：支持与 Android 版互相复制导入/导出完整视频源 JSON 配置！

---

## 1. 为什么 Windows 电脑不能直接本地生成 `.ipa`？
苹果官方仅在 macOS 上提供 Xcode 与 iOS SDK（`xcodebuild`），因此在 Windows 上无法本地编译 ARM64 iOS 二进制文件。  
为了帮您在 **没有 Mac 电脑** 的情况下也能顺利拿到 `.ipa` 并装进 iPhone，本项目已为您内置好 **GitHub Actions 免费云端 macOS 编译流水线**（`.github/workflows/build-ios-ipa.yml`），**全程免费、2 分钟自动产出 `.ipa` 安装包**！

---

## 2. 方案一：Windows 零 Mac 云端免费打包 `.ipa` + 装到 iPhone（推荐）

### 第 1 步：云端免费编译出 `.ipa` 安装包（约 2 分钟）
1. 在 [GitHub](https://github.com/new) 新建一个仓库（公开或私有均可，例如 `CineCast-iOS`）。
2. 将 `E:\视频软件\iOS版\CineCast-iOS` 文件夹内的全部文件（含 `.github/workflows/build-ios-ipa.yml`、`project.yml`、`CineCast/`）推送到该仓库：
   ```powershell
   cd "E:\视频软件\iOS版\CineCast-iOS"
   git init
   git add .
   git commit -m "Initial CineCast iOS v1.1.1"
   git branch -M main
   git remote add origin https://github.com/<你的用户名>/CineCast-iOS.git
   git push -u origin main
   ```
3. 打开 GitHub 仓库页面顶部的 **「Actions」** 标签页，等待 **`Build CineCast iOS IPA`** 工作流运行完成（约 2~3 分钟变成绿色对勾 ✅）。
4. 点进该运行记录，在底部 **Artifacts** 区域点击 **`CineCast-iOS-v1.1.1-IPA`**，解压即可得到 **`极光视界-CineCast-iOS-v1.1.1-unsigned.ipa`**！

### 第 2 步：在 Windows 上将该 `.ipa` 签名并安装到 iPhone
根据您的 iPhone 系统版本，任选以下 3 种安装方式之一：

#### 方式 A：使用 **爱思助手** 或 **Sideloadly**（适合所有 iOS 16/17/18/26 手机，免费 Apple ID 即可）
1. 在 Windows 安装 [Sideloadly](https://sideloadly.io/)（或 爱思助手 → 工具箱 → IPA 签名）。
2. 用数据线将 iPhone 连接电脑，点击信任此电脑。
3. 将 `极光视界-CineCast-iOS-v1.1.1-unsigned.ipa` 拖入 Sideloadly，填入您的免费 Apple ID，点击 **Start** 自动签名并安装到 iPhone。
4. 在 iPhone 上打开 **设置 → 通用 → VPN 与设备管理**，点击您的 Apple ID 证书 → **信任**。
5. （iOS 16+ 首次安装需开启）打开 **设置 → 隐私与安全性 → 开发者模式** 并重启一次手机，即可正常打开 **极光视界**！
   > *注：免费个人 Apple ID 签名有效期为 7 天，到期前连接电脑（或开启 Sideloadly Wi-Fi 自动续签）刷新一次即可，数据不丢失。*

#### 方式 B：巨魔商店（TrollStore，永久免签不掉签）
如果您的 iPhone 安装了 **TrollStore**，直接将 `极光视界-CineCast-iOS-v1.1.1-unsigned.ipa` 分享给 TrollStore 即可 **永久安装、永不掉签**！

#### 方式 C：自购个人/企业证书（如轻松签 / 全能签）
将云端导出的 `.ipa` 直接导入轻松签/全能签签名安装，有效期 1 年。

---

## 3. 方案二：如果您有 Mac 电脑（Xcode 一键真机运行）

```bash
# 1. 安装 XcodeGen（如未安装）
brew install xcodegen

# 2. 进入工程目录并生成 Xcode 工程
cd CineCast-iOS
xcodegen generate

# 3. 用 Xcode 打开
open CineCast.xcodeproj
```
4. 在 Xcode 左侧选择 `CineCast` Target → **Signing & Capabilities** → 选择您的 Apple ID（Team）并确保 `Automatically manage signing` 已勾选。
5. 数据线连接 iPhone，点击左上角 **▶ Run** 即可直接编译安装并真机调试！

---

## 4. iOS 版核心功能一览（与 Android v1.1.1 同步）

- **谷歌风格 UI（Google TV / Material 3 视觉体系）**：统一精简顶栏、片源切换胶囊、Google Blue 色彩系统、深色/浅色/跟随系统主题。
- **首页焦点轮播卡**：优先展示 **「继续观看」**（绿色角标 + 集数时间点 + 播放进度条 + 一键断点续播），无观看记录时展示 **「今日精选」**。
- **「最新热播 | 我的收藏」快捷页签**：首页直接浏览收藏海报墙。
- **两层分级分类 + 筛选总览面板**：支持父级大类并发聚合、二级子分类快速切换、右侧「全部分类与筛选」弹窗（年份/高分排序/频道总览）。
- **9 源并发流式搜索**：内置 9 个实测高速直连高清源（极速/虎牙/如意/360/红牛/iKun/新浪/魔都/猫眼），并兼容 `cyao2q/files/m.json` & `xs.json` TVBox 配置一键导入。
- **双模投屏**：同时原生支持 **Apple AirPlay 2 隔空播放** 与 **局域网 DLNA 电视投屏**，支持画中画（PiP）与多倍速播放。
- **高清电视直播 & 网页视频嗅探**：内置 4 条实测 IPv4 央视/卫视直播源（支持多线路切换）与内置广告屏蔽的 `WKWebView` 嗅探浏览器。
