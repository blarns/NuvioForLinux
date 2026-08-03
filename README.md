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

## Screenshots

<div align="center">

<img src="docs/screenshots/player.png" width="85%" alt="Nuvio in-app player on Linux" />

<sub><em>In-app player — libVLC playback with subtitles, transport controls, track/source switching, and resume-from-position.</em></sub>

</div>

| Browse & discover | Title details & episodes |
|:---:|:---:|
| <img src="docs/screenshots/browse.png" alt="Browse catalogs" /> | <img src="docs/screenshots/detail.png" alt="Show details, seasons and episodes" /> |
| **Multi-source stream selection** | **Playback with subtitles** |
| <img src="docs/screenshots/sources.png" alt="Stream source selection across addons" /> | <img src="docs/screenshots/player-subtitles.png" alt="Player rendering subtitles" /> |

<div align="right"><sub>Running on Linux Mint 22 (Ubuntu 24.04 base) · X11 · built from the universal AppImage · v0.1.20</sub></div>

## About

[EDIT] :Update as of 7/18/2026:
I am considering taking this out of maintenence and back into active developement AND I will be working towards keeping it working untill the official version is released. The goal right now is to make it so that once the official version is released users can simply update from my fork to the offcial repo's version.

This is a fork of [NuvioMedia/NuvioMobile](https://github.com/NuvioMedia/NuvioMobile) with an added **Linux Desktop target** via Kotlin Multiplatform JVM + VLCJ.

The upstream project is an unofficial Kotlin Multiplatform rewrite of the original React Native Nuvio app. It delivers a shared Compose UI for Android and iOS while keeping the playback-focused experience, collection tools, watch progress flows, downloads, and Stremio addon ecosystem integration.

This fork adds a community-maintained Linux desktop build. Video playback, audio, authentication, and addon persistence are working. See known issues below.

### 📝 Original Project & License Compliance

This project is an independent, unofficial rewrite based on the work of the original developers of [NuvioTV](https://github.com/NuvioMedia/NuvioTV). This repository is not the original React Native application.

Because the original NuvioTV project was licensed under the **GNU General Public License v3.0 (GPL-3.0)**, this repository is also strictly licensed under the **GPL-3.0**.

If you fork, modify, or distribute this code, you **must** also open-source your modifications under the GPL-3.0.

## Project status
> [!NOTE]
> This community fork exists to give Linux desktop a working Nuvio **today** — the official desktop port targets macOS and Windows first. It's built on the upstream [NuvioMobile](https://github.com/NuvioMedia/NuvioMobile) Kotlin Multiplatform / Compose codebase — the same shared `commonMain` that powers the Android and iOS apps — with a Linux/JVM desktop layer added on top: VLCJ playback, MPRIS media keys, hardware-accelerated video, and `.deb` + AppImage packaging. It's actively used and regularly released (see [Releases](https://github.com/blarns/NuvioForLinux/releases)). Development is fast-moving and AI-assisted, so expect the occasional rough edge — issues and pull requests are very welcome.

## Environment Setup

> [!IMPORTANT]
> **Nuvio backend switch (July 1, 2026):** the official Nuvio backend moved to
> `https://api.nuvio.tv`. The old hosted Supabase project was decommissioned, so **builds made
> before the switch can no longer log in or sync** — they were logged out when the switch
> completed. To recover: rebuild with the new backend URL/key below (or install a release built
> after the switch), then sign in again. Server-side account data was not wiped.

**No configuration is required.** When `local.properties` is absent (or has no Supabase keys),
builds default to the official Nuvio backend (`https://api.nuvio.tv`, the post-switch backend) —
accounts, profiles, and cross-device sync work out of the box, same as official builds.

**To use your own backend instead:** set up a free Supabase project at
[supabase.com](https://supabase.com), then copy the **Project URL** and **anon/public key** from
Project Settings → API into a `local.properties` file in the root of the project (git-ignored,
see [`local.properties.example`](local.properties.example)):

```properties
SUPABASE_URL=https://your-project-id.supabase.co
SUPABASE_ANON_KEY=your-anon-public-key
```

(You will need to provision the Nuvio schema yourself; accounts on your own backend are separate
from official Nuvio accounts. Set both keys or neither — a partial override fails the build.)

## Installation

### Android

Download the latest Android build from [NuvioMedia/NuvioMobile Releases](https://github.com/NuvioMedia/NuvioMobile/releases/latest).

### iOS

- [TestFlight](https://testflight.apple.com/join/u4y7MHK9)

### Linux Desktop (JVM)

**Status:** Community-maintained and actively developed. Video, audio, OAuth, addon
persistence, debrid, resume-across-restart, and experimental P2P torrent streaming all
working. See known issues.

#### Option A — Universal AppImage (easiest, no VLC install needed)

Download `Nuvio-<version>-x86_64.AppImage` from the
[Releases page](https://github.com/blarns/NuvioForLinux/releases/latest). It bundles libVLC and its
full codec set, so there is **nothing else to install** — it runs on any glibc ≥ 2.35 distribution
(Ubuntu 22.04 LTS+, Debian 12+, Fedora, Arch, Mint, …):

```bash
chmod +x Nuvio-*-x86_64.AppImage
./Nuvio-*-x86_64.AppImage
```

> Using **AppImageLauncher**? Older versions mishandle modern AppImages — update it to the latest
> release, or launch from a terminal with `APPIMAGELAUNCHER_DISABLE=1 ./Nuvio-*-x86_64.AppImage`.

#### Option B — Debian / Ubuntu `.deb`

Download the latest `nuvio_<version>_amd64.deb` from the
[Releases page](https://github.com/blarns/NuvioForLinux/releases/latest), then:

```bash
# VLC provides the libVLC runtime the player needs — it is NOT bundled in the .deb
sudo apt install vlc

# Install Nuvio (apt resolves the remaining runtime dependencies and the emoji font)
sudo apt install ./nuvio_*_amd64.deb
```

Nuvio then appears in your application launcher. To update, download the newer `.deb` and
run the same command, or use the in-app update prompt.

> **Note:** libVLC is a hard requirement at runtime. If playback fails right after a `.deb`
> install, install VLC with `sudo apt install vlc` and relaunch.

#### Build from source

```bash
# Build + runtime dependencies (Ubuntu/Debian/Mint)
sudo apt install openjdk-17-jdk libvlc-dev vlc

# Optional: VA-API drivers for hardware video acceleration
# Intel:  sudo apt install intel-media-va-driver
# AMD:    sudo apt install mesa-va-drivers
# NVIDIA: sudo apt install nvidia-vaapi-driver

# Optional: desktop notifications
sudo apt install libnotify-bin

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
- ✅ Addon update notifications — twice-weekly manifest re-checks with an Updates section, plus one-click URL swap (⇄) for reconfigured addons (AIOStreams and friends)
- ✅ JavaScript plugin/scraper support (QuickJS runtime) — Settings → Plugins
- ✅ Continue Watching, Library, Search
- ✅ Resume playback from where you left off (persists across restarts)
- ✅ Right-click context menus (mark watched, mark previous/season watched, play manually, remove from Continue Watching)
- ✅ In-app update notifications — checks for new releases on startup and downloads the `.deb`
- ✅ TMDB enrichment, MDBList ratings, and Debrid (Torbox/Real-Debrid) settings — configurable and persistent (Settings → Integrations)
- 🧪 **Peer-to-peer (P2P) torrent streaming — experimental, off by default.** Stream torrents without a debrid subscription, via a bundled [TorrServer](https://github.com/YouROK/TorrServer) engine. Enabling it (Settings → P2P) requires accepting a consent dialog. **⚠️ P2P exposes your real IP address to other peers in the swarm — use a VPN.** Debrid (which resolves torrents server-side over HTTPS, never exposing your IP) remains the recommended default.
- ✅ Hardware video acceleration via VA-API (Intel/AMD) or NVDEC (NVIDIA) — toggle in Settings → Playback
- ✅ Audio output selection (Auto / PulseAudio / ALSA / JACK) — Settings → Playback
- ✅ Keyboard shortcuts (Space, arrows, M, F for fullscreen, scroll wheel for volume)
- ✅ Media key integration via MPRIS2 (works with system panel and `playerctl`)
- ✅ Episode release notifications via `notify-send`
- ✅ App menu integration — run `scripts/install-desktop.sh` to add Nuvio to your application launcher
- ✅ Window size persists between sessions

See [CHANGELOG.md](CHANGELOG.md) for the full release history.

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

### Keyboard shortcuts

| Key | Action |
|-----|--------|
| Space | Play / Pause |
| ← / → | Seek ±10 seconds |
| ↑ / ↓ | Volume ±5% |
| M | Toggle mute |
| F | Toggle fullscreen |
| Scroll wheel | Volume |

**Mouse:** right-click a poster, episode, season, or Continue Watching card to open its context menu (mark watched, play manually, remove, etc.) — the desktop equivalent of long-press on mobile.

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
