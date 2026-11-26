<div align="center">

# TSCC

**非官方开源社区语音安卓客户端 · Unofficial TeamSpeak Community Client for Android**

[![CI](https://img.shields.io/github/actions/workflow/status/Cloudwhile/teamspeak-android-community/ci.yml?branch=master&style=for-the-badge&logo=githubactions&logoColor=white&label=CI)](https://github.com/Cloudwhile/teamspeak-android-community/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Cloudwhile/teamspeak-android-community?include_prereleases&style=for-the-badge&logo=github&label=Release)](https://github.com/Cloudwhile/teamspeak-android-community/releases)
[![Unofficial](https://img.shields.io/badge/Notice-Unofficial%20Community-f59e0b?style=for-the-badge)](https://github.com/Cloudwhile/teamspeak-android-community#非官方声明--unofficial-disclaimer)
[![License](https://img.shields.io/github/license/Cloudwhile/teamspeak-android-community?style=for-the-badge)](./LICENSE)

![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?style=flat-square&logo=android&logoColor=white)
![Package](https://img.shields.io/badge/Package-com.cloudwhile.tscc-6366f1?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)
![Rust](https://img.shields.io/badge/Rust-tscc__core%20JNI-DEA584?style=flat-square&logo=rust&logoColor=black)
![Audio](https://img.shields.io/badge/Audio-Opus%2048kHz%20%2B%20WebRTC-10b981?style=flat-square&logo=webrtc&logoColor=white)
![ABIs](https://img.shields.io/badge/ABIs-arm64--v8a%20%7C%20armeabi--v7a%20%7C%20x86__64%20%7C%20universal-0ea5e9?style=flat-square)

</div>

---

> [!IMPORTANT]
> **非官方声明 / Unofficial Disclaimer**
>
> **TSCC** 是由社区独立开发与维护的**非官方开源项目**，与 **TeamSpeak Systems GmbH** 无任何隶属、合作、授权或官方背书关系。`TeamSpeak®` 等相关名称与注册商标归其各自权利人所有。
>
> **TSCC** is an independent, **unofficial** open-source community client and is **not** affiliated with, authorized, endorsed, or sponsored by **TeamSpeak Systems GmbH**. `TeamSpeak®` is a registered trademark of its respective owner.

## 功能特性

- **TS3 / TS6 标准服务器协议连接**
  - 基于 Rust `tsclientlib` / `tsproto` 实现 `initivexpand2` 握手与加密传输，兼容 TeamSpeak 3（3.13+）与 TeamSpeak 6 社区服务器。
  - 支持 `_ts3._udp` SRV、`_tsdns._tcp` TSDNS 与 A/AAAA 双栈域名解析，支持 `ts3server://` 与 `teamspeak://` 邀请链接唤起。
- **桌面端身份互通与本地硬件加密存储**
  - 支持生成椭圆曲线 P-256 身份密钥、导入或导出桌面端 `.ini` 身份文件，内置多线程并行 SHA-1 安全等级计算（支持中途取消并保留已算进度）。
  - 本地数据库中的私钥与服务器/频道口令采用 Android Keystore `AES-256-GCM` 加密存储，系统自动备份仅放行界面偏好配置。
- **48,000 Hz Opus 实时语音与声学处理**
  - 20 ms 帧长 `DirectByteBuffer` JNI 音频链路，自动适配频道 `Opus Voice` 与 `Opus Music` 编码模式。
  - 内置 110 Hz 二阶巴特沃斯高通防喷麦滤波、瞬态限幅器、软膝盖混音器与单成员 `0%–200%` 独立音量调节。
  - 支持语音感应（VAD）、按住说话（PTT）、常开麦、本地 Opus 语音回路自检，以及蓝牙/有线耳机/扬声器/听筒热插拔自动切换与系统来电焦点恢复。
  - **无麦克风权限可用性降级**：拒绝麦克风权限时自动切换为纯收听前台模式，所有麦克风相关功能保持禁用并在点击时引导授权。
- **频道管理、文字聊天与 TS6 实时音视频流**
  - 支持子频道创建、差量编辑、删除、BBCode 简介查看、管理员权限密钥（Privilege Key）激活、频道/服务器/私聊消息以及 TS6 WebRTC 屏幕与摄像头直播收看/推流。

## 多架构安装包下载指南

前往 [GitHub Releases](https://github.com/Cloudwhile/teamspeak-android-community/releases) 下载最新版本的 APK 安装包。每次正式发布均提供以下 **4 个可直接安装的架构包** 与 `SHA256SUMS.txt` 校验文件：

| 安装包文件名 | 适用设备架构 | 说明 |
| :--- | :--- | :--- |
| `TSCC-vX.Y.Z-arm64-v8a.apk` | **64 位 ARM（推荐）** | 适用于绝大多数现代 Android 手机与平板，体积最小、运行效率最优 |
| `TSCC-vX.Y.Z-armeabi-v7a.apk` | **32 位 ARM** | 适用于较早期 32 位 Android 设备、车机或电视盒子 |
| `TSCC-vX.Y.Z-x86_64.apk` | **64 位 x86** | 适用于 PC 端 Android 模拟器、Waydroid、Chromebook 等 x86_64 环境 |
| `TSCC-vX.Y.Z-universal.apk` | **全架构通用包** | 同时包含 `arm64-v8a`、`armeabi-v7a`、`x86_64` 原生库，不确定设备架构时可直接安装 |

## 本地构建与测试

### 环境要求

- **JDK 17**
- **Android SDK**（`compileSdk 34`、`NDK 28.2.13676358`、`CMake 3.22.1`、Ninja）
- **Rust Stable 工具链** + `cargo-ndk`：
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  cargo install cargo-ndk
  ```

### 编译命令

```bash
# 1. 运行 Rust 协议与音频引擎单元测试
OPUS_STATIC=1 OPUS_NO_PKG=1 CMAKE_POLICY_VERSION_MINIMUM=3.5 \
  cargo test --manifest-path rust-core/Cargo.toml

# 2. 运行 Android 单元测试、Release Lint 校验并构建全部架构 Release APK
./gradlew :app:testDebugUnitTest :app:lintVitalRelease :app:assembleRelease
```

Windows PowerShell 下可执行：

```powershell
$env:OPUS_STATIC="1"; $env:OPUS_NO_PKG="1"; $env:CMAKE_POLICY_VERSION_MINIMUM="3.5"
cargo test --manifest-path rust-core\Cargo.toml
.\gradlew.bat :app:testDebugUnitTest :app:lintVitalRelease :app:assembleRelease
```

构建完成后，多架构安装包输出于 `app/build/outputs/apk/release/` 目录。

## CI/CD 与发布签名配置

仓库内置严格的标准 GitHub Actions 工作流：

- **持续集成（[`.github/workflows/ci.yml`](.github/workflows/ci.yml)）**：在推送或提交 PR 至 `master` 分支时自动执行 Rust 单元测试、三架构 JNI 交叉编译、Kotlin 单元测试、`lintVitalRelease` 静态检查以及 Debug/Release 多架构构建。
- **版本发布（[`.github/workflows/release.yml`](.github/workflows/release.yml)）**：推送语义化版本标签（如 `v1.0.0`）或手动触发时，自动执行全套质量门禁、构建并校验 `arm64-v8a` / `armeabi-v7a` / `x86_64` / `universal` 四个签名 APK、生成 `SHA256SUMS.txt` 并创建 GitHub Release。

如需在 GitHub Actions 中使用自定义正式证书签名，可在仓库 `Settings -> Secrets and variables -> Actions` 中配置以下 Secrets（未配置时构建脚本将回退至可安装的默认调试签名以保证产物可安装验证）：

| Secret 名称 | 说明 |
| :--- | :--- |
| `SIGNING_KEYSTORE_BASE64` | `.jks` 或 `.keystore` 证书文件的 Base64 编码字符串（`base64 -w 0 release.jks`） |
| `SIGNING_STORE_PASSWORD` | Keystore 密码 |
| `SIGNING_KEY_ALIAS` | 密钥别名（Key Alias） |
| `SIGNING_KEY_PASSWORD` | 密钥密码（Key Password） |

## 致谢与开源组件

- [ReSpeak / tsclientlib](https://github.com/ReSpeak/tsclientlib) — Rust TeamSpeak 协议栈与状态机实现（MIT / Apache-2.0）
- [Xiph.Org / Opus](https://opus-codec.org/) — 48 kHz 交互式语音与音乐编解码器（BSD-3-Clause）
- [GetStream / webrtc-android](https://github.com/GetStream/webrtc-android) — Android WebRTC 音视频传输库（BSD-3-Clause / Apache-2.0）
- [Hickory DNS](https://github.com/hickory-dns/hickory-dns) — 异步 DNS / SRV 解析器（MIT / Apache-2.0）

## 许可证

本项目基于 [MIT License](./LICENSE) 开源。
