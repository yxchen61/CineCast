# 极光视界 CineCast iOS 证书转 Base64 一键辅助脚本
# 用法：右键“使用 PowerShell 运行”，按提示拖入 .p12 和 .mobileprovision 文件即可生成 GitHub Secrets 内容

$ErrorActionPreference = 'Stop'
Write-Host "=== 极光视界 iOS GitHub Actions 自动签名 Secrets 生成工具 ===" -ForegroundColor Cyan
Write-Host ""

$p12Path = (Read-Host "1. 请拖入或输入您的 .p12 证书文件路径").Trim('"').Trim()
if (-not (Test-Path $p12Path)) { throw "找不到文件：$p12Path" }

$provPath = (Read-Host "2. 请拖入或输入您的 .mobileprovision 描述文件路径").Trim('"').Trim()
if (-not (Test-Path $provPath)) { throw "找不到文件：$provPath" }

$p12B64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($p12Path))
$provB64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($provPath))

$outFile = Join-Path (Split-Path $p12Path -Parent) "GitHub_Secrets_待粘贴内容.txt"
@"
================ 请粘贴到 GitHub 仓库 Settings -> Secrets and variables -> Actions ================

1) Secret 名称: IOS_P12_BASE64
值:
$p12B64

2) Secret 名称: IOS_P12_PASSWORD
值: <填写您的 .p12 证书密码>

3) Secret 名称: IOS_PROVISION_BASE64
值:
$provB64

（填完以上 3 项后，请立即删除本临时 txt 文件以保护证书安全！）
"@ | Set-Content -Path $outFile -Encoding UTF8

Write-Host ""
Write-Host "已生成：$outFile" -ForegroundColor Green
Write-Host "请打开该文件，把对应值填入 GitHub 仓库的 Settings -> Secrets and variables -> Actions，填完后删除该 txt 文件即可！" -ForegroundColor Yellow
Pause
