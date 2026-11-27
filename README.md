<div align="center">

# TSCC

**Unofficial TeamSpeak Community Client for Android**

[![CI](https://img.shields.io/github/actions/workflow/status/Cloudwhile/teamspeak-android-community/ci.yml?branch=master&style=for-the-badge&logo=githubactions&logoColor=white&label=CI)](https://github.com/Cloudwhile/teamspeak-android-community/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Cloudwhile/teamspeak-android-community?include_prereleases&style=for-the-badge&logo=github&label=Release)](https://github.com/Cloudwhile/teamspeak-android-community/releases)
[![Unofficial](https://img.shields.io/badge/Notice-Unofficial%20Community-f59e0b?style=for-the-badge)](#unofficial-disclaimer)
[![License](https://img.shields.io/github/license/Cloudwhile/teamspeak-android-community?style=for-the-badge)](./LICENSE)

![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?style=flat-square&logo=android&logoColor=white)
![Package](https://img.shields.io/badge/Package-com.cloudwhile.tscc-6366f1?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)
![Rust](https://img.shields.io/badge/Rust-tscc__core%20JNI-DEA584?style=flat-square&logo=rust&logoColor=black)
![Audio](https://img.shields.io/badge/Audio-Opus%2048kHz%20%2B%20WebRTC-10b981?style=flat-square&logo=webrtc&logoColor=white)
![ABIs](https://img.shields.io/badge/ABIs-arm64--v8a%20%7C%20armeabi--v7a%20%7C%20x86__64%20%7C%20universal-0ea5e9?style=flat-square)

**English** · [简体中文](./locales/README.zh-CN.md)

</div>

---

## Unofficial Disclaimer

> [!IMPORTANT]
> **TSCC** is an independent, **unofficial** open-source community client maintained by community contributors. It is **NOT** affiliated with, authorized, endorsed, or sponsored by **TeamSpeak Systems GmbH**. `TeamSpeak®` and related marks are registered trademarks of their respective owners.

## Key Features

- **Native TS3 & TS6 Encrypted Protocol Support**
  - Powered by a Rust protocol and state engine (`tsclientlib` / `tsproto`), supporting `initivexpand2` handshakes and encrypted voice transport with TeamSpeak 3 (3.13+) and TeamSpeak 6 community servers.
  - Full `_ts3._udp` SRV, `_tsdns._tcp` TSDNS, and dual-stack IPv4/IPv6 resolution, plus `tscc://` invite deep-link and share-to-join handling.
- **Desktop Identity Interoperability, TSCC Config Backup & Hardware-Backed Encryption**
  - Generate P-256 ECC identities, import/export desktop `.ini` identity files, export/restore portable `.json` TSCC configurations (identities, bookmarks/labels, and preferences with optional `PBKDF2-HMAC-SHA256` + `AES-256-GCM` encryption), and run multi-core parallel SHA-1 security level upgrades (with pause/cancel progress preservation).
  - Sensitive database columns (identity private keys and saved server/channel passwords) are encrypted at rest via Android Keystore `AES-256-GCM`, and Android Auto Backup excludes private keys and saved passwords.
- **48,000 Hz Opus Real-Time Audio & Acoustic DSP**
  - Zero-copy `DirectByteBuffer` JNI pipeline (20 ms frames) with automatic `Opus Voice` and `Opus Music` channel codec adaptation.
  - Built-in 110 Hz 2nd-order Butterworth anti-plosive filter, transient peak limiter, dynamic multi-speaker mixer, and per-member `0%–200%` volume memory.
  - Voice Activity Detection (VAD), Push-To-Talk (PTT), Continuous transmission, local Opus loopback self-test, automatic Bluetooth/Wired/Speaker/Earpiece hot-plug routing, and phone-call audio focus recovery.
  - **Listen-Only Mode**: Joining without microphone permission keeps channel audio playback active while disabling microphone controls until permission is granted.
- **Channel Management, Text Chat & TS6 Live Streaming (Experimental Alpha)**
  - Sub-channel creation, differential editing, deletion, on-demand BBCode channel descriptions, privilege key redemption, channel/server/direct messaging, and experimental Alpha TS6 WebRTC screen/camera streaming.
- **Built-in Auto-Updater**
  - Supports startup update checks and manual checks against GitHub Releases (including `alpha.x` / `beta.x` / `rc.x` pre-releases and stable releases), automatically matching the device CPU architecture, verifying `SHA256SUMS.txt`, and launching the Android package installer.

## Multi-Architecture Downloads

Download the latest installable APK from [GitHub Releases](https://github.com/Cloudwhile/teamspeak-android-community/releases). Every release provides **4 signed APK variants** alongside `SHA256SUMS.txt`:

| Package Filename | Architecture | Recommended Devices |
| :--- | :--- | :--- |
| `TSCC-vX.Y.Z-arm64-v8a.apk` | **64-bit ARM (Recommended)** | Modern 64-bit Android phones and tablets — smallest size & best performance |
| `TSCC-vX.Y.Z-armeabi-v7a.apk` | **32-bit ARM** | Legacy 32-bit Android phones, head units, and TV boxes |
| `TSCC-vX.Y.Z-x86_64.apk` | **64-bit x86** | Android Studio Emulator, Waydroid, ChromeOS, and x86_64 environments |
| `TSCC-vX.Y.Z-universal.apk` | **Universal (All ABIs)** | Combined fat APK containing `arm64-v8a`, `armeabi-v7a`, and `x86_64` |

## Building from Source

### Prerequisites

- **JDK 17**
- **Android SDK** (`compileSdk 34`, `NDK 28.2.13676358`, `CMake 3.22.1`, Ninja)
- **Rust Stable Toolchain** + `cargo-ndk`:
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  cargo install cargo-ndk
  ```

### Build & Test Commands

Linux / macOS:

```bash
# 1. Run Rust protocol, resolver, and DSP unit tests
OPUS_STATIC=1 OPUS_NO_PKG=1 CMAKE_POLICY_VERSION_MINIMUM=3.5 \
  cargo test --manifest-path rust-core/Cargo.toml

# 2. Run Android unit tests, Release Lint, and assemble all multi-ABI Release APKs
./gradlew :app:testDebugUnitTest :app:lintVitalRelease :app:assembleRelease
```

Windows PowerShell:

```powershell
$env:OPUS_STATIC="1"; $env:OPUS_NO_PKG="1"; $env:CMAKE_POLICY_VERSION_MINIMUM="3.5"
cargo test --manifest-path rust-core\Cargo.toml
.\gradlew.bat :app:testDebugUnitTest :app:lintVitalRelease :app:assembleRelease
```

Release APKs are written to `app/build/outputs/apk/release/`.

## CI/CD & Release Signing

Standard GitHub Actions workflows are configured under `.github/workflows/`:

- **Continuous Integration ([`.github/workflows/ci.yml`](.github/workflows/ci.yml))**: Runs on pushes and pull requests to `master`, executing Rust unit tests, 3-ABI Android NDK cross-compilation, Kotlin unit tests, `lintVitalRelease`, and Debug/Release APK verification.
- **Release Publishing ([`.github/workflows/release.yml`](.github/workflows/release.yml))**: Triggered by SemVer tags (such as `v0.1.0-alpha.1`, `v0.1.0-beta.1`, or `v0.1.0`) or `workflow_dispatch`. Builds, verifies (`apksigner verify`), checksums (`SHA256SUMS.txt`), and publishes all 4 installable multi-ABI APKs to GitHub Releases.

To sign release builds in GitHub Actions and preserve seamless in-place APK upgrades across versions, configure the following required repository secrets under `Settings -> Secrets and variables -> Actions`:

| Secret Name | Description |
| :--- | :--- |
| `SIGNING_KEYSTORE_BASE64` | Base64-encoded `.jks` or `.keystore` file (`base64 -w 0 release.jks`) |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | Signing key alias |
| `SIGNING_KEY_PASSWORD` | Signing key password |

## Open-Source Acknowledgments

- [ReSpeak / tsclientlib](https://github.com/ReSpeak/tsclientlib) — Rust TeamSpeak protocol & state machine implementation (MIT / Apache-2.0)
- [Xiph.Org / Opus](https://opus-codec.org/) — 48 kHz interactive speech and music audio codec (BSD-3-Clause)
- [GetStream / webrtc-android](https://github.com/GetStream/webrtc-android) — WebRTC real-time video/audio library for Android (BSD-3-Clause / Apache-2.0)
- [Hickory DNS](https://github.com/hickory-dns/hickory-dns) — Async DNS and SRV resolver library (MIT / Apache-2.0)

## License

Licensed under the [MIT License](./LICENSE).
