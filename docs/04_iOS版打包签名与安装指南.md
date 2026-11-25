# 极光视界（CineCast）iOS 版打包、自动签名与安装完整指南

> **版本**：v1.1.1（与 Android 最新版 100% 功能对齐）  
> **GitHub 仓库**：`https://github.com/yxchen61/CineCast`  
> **技术栈**：原生 SwiftUI（iOS 16+）+ AVPlayer 高清硬解 + **Apple AirPlay 2 隔空播放 + 原生 DLNA 投屏** + WKWebView 网页嗅探

---

## 1. GitHub Actions 云端自动签名打包（直接产出可安装 `.ipa`，推荐！）

仓库的 `.github/workflows/build-ios-ipa.yml` 已内置 **双模式智能签名引擎**：
- **配置了证书 Secrets**：自动导入 `.p12` 与 `.mobileprovision`，自动识别描述文件里的 Team ID、Bundle ID 与导出模式（Development / Ad-Hoc / Enterprise），直接编译并产出 **`CineCast-iOS-v1.1.1-signed.ipa`**（传到 iPhone 即可直接安装，有效期 1 年免 7 天续签）！
- **未配置 Secrets**：自动降级编译 **`CineCast-iOS-v1.1.1-unsigned.ipa`**（可用于爱思助手/Sideloadly 免费 Apple ID 自签或巨魔商店 TrollStore 永久安装）。

### 3 步开启云端自动签名：

#### 第 1 步：准备您的测试证书文件
准备您绑定了自己 iPhone UDID 的苹果证书文件（来自苹果开发者账号或个人自签证书服务）：
- 证书文件：`xxx.p12`（及其密码）
- 描述文件：`xxx.mobileprovision`

#### 第 2 步：一键转为 Base64 并填入 GitHub Secrets
1. 双击运行配套脚本 **`E:\视频软件\iOS版\生成GitHub签名Secrets工具.ps1`**（右键 → 使用 PowerShell 运行），按提示拖入 `.p12` 和 `.mobileprovision` 即可自动生成 Base64 文本。
2. 打开您的 GitHub 仓库：**`https://github.com/yxchen61/CineCast/settings/secrets/actions`**
3. 点击 **「New repository secret」**，依次新建以下 3 个 Secret（全部加密私密存储，仅您可见）：

| Secret 名称 | 填写内容 | 是否必填 |
| :--- | :--- | :--- |
| `IOS_P12_BASE64` | `.p12` 文件的 Base64 字符串 | 必填 |
| `IOS_P12_PASSWORD` | `.p12` 证书的密码 | 必填 |
| `IOS_PROVISION_BASE64` | `.mobileprovision` 描述文件的 Base64 字符串 | 必填 |
| `IOS_BUNDLE_ID` | 自定义 Bundle ID（通配符描述文件默认用 `com.cinecast.ios`，显式描述文件会自动识别，一般无需填） | 可选 |

#### 第 3 步：一键触发打包并下载「已签名 `.ipa`」
1. 打开仓库 **Actions** → 左侧点击 **`Build CineCast iOS IPA`** → 右侧点 **「Run workflow」**。
2. 等待约 2 分钟编译完成后，在底部 **Artifacts** 下载 **`CineCast-iOS-v1.1.1-Signed-IPA`**，解压即得 **`CineCast-iOS-v1.1.1-signed.ipa`**！
3. 直接用爱思助手「导入安装」或 AirDrop / 第三方安装工具装到您的 iPhone 即可使用（无需再重复签名）。

---

## 2. 零证书免费自用方式（无 `.p12` 证书时）

如果您暂时没有 `.p12` 证书，直接使用未签名包 **`CineCast-iOS-v1.1.1-unsigned.ipa`** 即可免费安装：
1. 在 Windows 安装 **爱思助手**（`https://www.i4.cn/`）或 **Sideloadly**（`https://sideloadly.io/`）。
2. 数据线连上 iPhone → **爱思助手 → 工具箱 → IPA 签名 → 使用 Apple ID 签名**，用您的普通免费苹果账号签名后点击「导入安装」。
3. 装完后在 iPhone 上：
   - **设置 → 通用 → VPN 与设备管理** → 信任您的 Apple ID 证书；
   - **设置 → 隐私与安全性 → 开发者模式** → 开启并按提示重启手机。
4. 首次打开 App 时，提示「查找并连接到本地网络上的设备」请务必点 **允许**（用于 AirPlay / DLNA 投屏）。

---

## 3. Mac + Xcode 本地直接运行（如有 Mac）

```bash
cd ios
brew install xcodegen
xcodegen generate
open CineCast.xcodeproj
```
在 Xcode 中选择您的 Apple ID Team 自动签名，插上 iPhone 点击 ▶ 即可真机运行调试。
