> [!IMPORTANT]
> # This project is discontinued — permanently.
>
> **An official Nuvio desktop build for Linux now exists, and this fork has been retired.**
>
> NuvioForLinux was always a stand-in. Its stated goal, from the beginning, was to give Linux a
> working Nuvio until the official desktop port shipped Linux support — and then to hand its users
> over. As of **2026-08-27** that has happened, so the fork has done its job and stops here.
>
> ### → Move to the official build: [NuvioMedia/NuvioDesktop releases](https://github.com/NuvioMedia/NuvioDesktop/releases/latest)
>
> It ships `.deb`, AppImage, `.flatpak` and `.rpm` for x86_64, and uses a native **libmpv** player
> bridge rather than this fork's VLCJ.
>
> **Migrating:**
> - **Sign in on the official build and your data comes back** — profiles, library, watch progress
>   and addons all restore from your Nuvio account. That is the mechanism to rely on; don't assume
>   anything is carried over locally.
> - On a test install here on 2026-08-28, the official `.deb` (`1.1.21-1`) installed over v0.3.7 as
>   an ordinary `apt` upgrade, because both packages are named `nuvio`. That is an observation, not
>   a guarantee — upstream is alpha and their version strings may change. If `apt` objects, remove
>   `nuvio` first and install fresh.
> - Don't run both builds at once — they share `~/.config/nuvio/` and will interleave writes to it.
>
> **Be aware of what you are moving to:** the official desktop is **alpha**, explicitly
> "testers only", **x86_64 only** (no arm64), and numbered `0.1.x` against this fork's `0.3.7`.
> The low version number reflects its age, not a downgrade in capability — it now enables plugins,
> P2P, downloads, in-app trailers, self-hosted servers and its own updater, all of which this fork
> either lacked or had to build separately.
>
> **What "discontinued" means here:** no further releases, no bug fixes, no security updates, and
> no upstream parity work. Issues and pull requests will not be actioned. The code stays online,
> GPL-3.0, for anyone who wants to read or fork it — but nobody is maintaining it.
>
> Thanks to everyone who ran it, filed bugs, and put up with the rough edges.

---

<div align="center">

  <img src="https://github.com/tapframe/NuvioTV/blob/main/assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />
  <br />
  <br />

  [![Contributors][contributors-shield]][contributors-url]
  [![Forks][forks-shield]][forks-url]
  [![Stargazers][stars-shield]][stars-url]
  [![Issues][issues-shield]][issues-url]
  [![License][license-shield]][license-url]

  <p>
    A modern media hub for Android, iOS, and Linux Desktop built with Kotlin Multiplatform and Compose Multiplatform.
    <br />
    Stremio addon ecosystem • Cross-platform
  </p>

</div>

## About

This is a fork of [NuvioMedia/NuvioMobile](https://github.com/NuvioMedia/NuvioMobile) with an added **Linux Desktop target** via Kotlin Multiplatform JVM + VLCJ.

The upstream project is an unofficial Kotlin Multiplatform rewrite of the original React Native Nuvio app. It delivers a shared Compose UI for Android and iOS while keeping the playback-focused experience, collection tools, watch progress flows, downloads, and Stremio addon ecosystem integration.

This fork adds a community-maintained Linux desktop build. Video playback, audio, authentication, and addon persistence are working. See known issues below.

### 📝 Original Project & License Compliance

This project is an independent, unofficial rewrite based on the work of the original developers of [NuvioTV](https://github.com/NuvioMedia/NuvioTV). This repository is not the original React Native application.

Because the original NuvioTV project was licensed under the **GNU General Public License v3.0 (GPL-3.0)**, this repository is also strictly licensed under the **GPL-3.0**.

If you fork, modify, or distribute this code, you **must** also open-source your modifications under the GPL-3.0.

## ⚠️ Disclaimer
> [!WARNING]
> This project was largely **vibecoded** (built rapidly with AI assistance/pair programming). While it is functional and packed with features, it may contain unconventional patterns, unoptimized code, or bugs. Pull requests, fixes, and improvements are extremely welcome!

## Environment Setup

Before building the app, you **must** configure your local environment variables. The project uses Supabase for authentication and database services.

Create a file named `local.properties` in the root of the project (this file is git-ignored) and add your keys:

```properties
SUPABASE_URL=your_supabase_project_url
SUPABASE_ANON_KEY=your_supabase_anon_key
```

If you try to build without these, authentication and networking will fail at runtime.

## Installation

### Android

Download the latest Android build from [NuvioMedia/NuvioMobile Releases](https://github.com/NuvioMedia/NuvioMobile/releases/latest).

### iOS

- [TestFlight](https://testflight.apple.com/join/u4y7MHK9)

### Linux Desktop (JVM)

**Status:** Community-maintained. Video playback and audio working. See known issues.

```bash
# Install dependencies (Ubuntu/Debian/Mint)
sudo apt install openjdk-17-jdk libvlc-dev vlc

# Optional: VA-API drivers for hardware video acceleration
# Intel:  sudo apt install intel-media-va-driver
# AMD:    sudo apt install mesa-va-drivers
# NVIDIA: sudo apt install nvidia-vaapi-driver

# Clone
git clone https://github.com/blarns/NuvioForLinux.git
cd NuvioForLinux

# Add local.properties with your Supabase keys (see Environment Setup above)

# Build and run
./gradlew composeApp:run
```

See [LINUX_QUICKSTART.md](LINUX_QUICKSTART.md) for quick setup, or [LINUX_DESKTOP.md](LINUX_DESKTOP.md) for comprehensive documentation.

**Working:**
- ✅ HLS, DASH, RTSP, HTTP streaming via VLCJ/libVLC
- ✅ Audio playback
- ✅ Subtitle support (SRT, VTT, ASS)
- ✅ OAuth authentication with persistent session
- ✅ Full addon ecosystem support with persistent storage
- ✅ Continue Watching, Library, Search
- ✅ Hardware video acceleration via VA-API (Intel/AMD) or NVDEC (NVIDIA) — toggle in Settings → Playback

**Tested on:** Linux Mint 22, kernel 6.8, X11, Intel Iris Xe, Bluetooth audio

## Development

```bash
git clone https://github.com/blarns/NuvioForLinux.git
cd NuvioForLinux
./gradlew composeApp:run        # Linux desktop
./gradlew composeApp:assembleFullDebug  # Android
```

### Project Structure

- `composeApp/src/commonMain/` — shared UI, features, repositories
- `composeApp/src/androidMain/` — Android-specific integrations
- `composeApp/src/iosMain/` — iOS-specific integrations
- `composeApp/src/desktopMain/` — Linux/JVM desktop implementations
- `iosApp/` — native Xcode project

Versioning is driven from `iosApp/Configuration/Version.xcconfig`.

## Legal & DMCA

Nuvio functions solely as a client-side interface for browsing metadata and playing media provided by user-installed extensions and/or user-provided sources. It is intended for content the user owns or is otherwise authorized to access.

Nuvio is not affiliated with any third-party extensions, catalogs, sources, or content providers. It does not host, store, or distribute any media content.

For comprehensive legal information please visit the [Legal & Disclaimer Page](https://nuvioapp.space/legal).

## Built With

- Kotlin Multiplatform
- Compose Multiplatform
- AndroidX Media3 (Android)
- VLCJ / libVLC (Linux Desktop)
- AVFoundation (iOS)

<!-- MARKDOWN LINKS & IMAGES -->
[contributors-shield]: https://img.shields.io/github/contributors/blarns/NuvioForLinux.svg?style=for-the-badge
[contributors-url]: https://github.com/blarns/NuvioForLinux/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/blarns/NuvioForLinux.svg?style=for-the-badge
[forks-url]: https://github.com/blarns/NuvioForLinux/network/members
[stars-shield]: https://img.shields.io/github/stars/blarns/NuvioForLinux.svg?style=for-the-badge
[stars-url]: https://github.com/blarns/NuvioForLinux/stargazers
[issues-shield]: https://img.shields.io/github/issues/blarns/NuvioForLinux.svg?style=for-the-badge
[issues-url]: https://github.com/blarns/NuvioForLinux/issues
[license-shield]: https://img.shields.io/github/license/blarns/NuvioForLinux.svg?style=for-the-badge
[license-url]: https://github.com/blarns/NuvioForLinux/blob/main/LICENSE
