# Project memory — NuvioForLinux

Shared notes for any Claude Code session working on this repo (web or CLI). Keep entries
short, dated, and sourced. This file is tracked by git — `.claude/` and `AGENTS.md` are
gitignored, so anything that needs to survive a session or reach the other client goes here.

---

## Upstream now ships an official Linux desktop build (as of 2026-08-27)

`NuvioMedia/NuvioDesktop` **0.1.21-alpha** (published 2026-08-27) added *"Linux desktop
support (native libmpv player bridge)"* and ships Linux packages for the first time.
Source: <https://github.com/NuvioMedia/NuvioDesktop/releases>

**Linux assets in 0.1.21-alpha** (all `x86_64`, alpha/prerelease):

| Asset | Size |
|---|---|
| `Nuvio-Linux-x86_64-0.1.21-alpha.AppImage` (+ `.zsync`) | 154 MB |
| `Nuvio-Linux-x86_64-0.1.21-alpha.deb` | 144 MB |
| `Nuvio-Linux-x86_64-0.1.21-alpha.rpm` | 157 MB |
| `Nuvio-Linux-x86_64-0.1.21-alpha.flatpak` | 141 MB |

Windows (`.msi`), macOS (`arm64`/`x86_64` `.dmg`) and `SHA256SUMS.txt` ship alongside.
No arm64 Linux build.

**Verified in the upstream tree** (shallow clone of `NuvioMedia/NuvioDesktop`, HEAD `785df9c`):

- `composeApp/src/desktopMain/native/linux/player_bridge.cpp` now exists next to the
  `windows/` and `macos/` bridges. It embeds libmpv into the host AWT Canvas's X11 window.
- Same stack as this fork: Kotlin Multiplatform + Compose Multiplatform (not Electron/Tauri).
- libmpv is a **system** dependency for the native packages, not bundled:
  deb → `libmpv2`, rpm → `mpv-libs` (plus `libwebkit2gtk-4.1-0`/`webkit2gtk4.1`,
  `libxcomposite1`, `libxext6`, gstreamer good + libav, `glib-networking`).
  See `scripts/linux/linux-{deb,rpm}-dependencies.sh` upstream.
- Linux app data resolves to `$XDG_CONFIG_HOME/nuvio` → `~/.config/nuvio`, cache to
  `~/.cache/nuvio` (`composeApp/src/desktopMain/kotlin/.../core/storage/DesktopStorage.kt`).

### Why this matters here

1. **The migration goal in the README is now live.** The README's "About" section says the
   plan is for users to move from this fork to the official build once it exists. It exists —
   though it is alpha, x86_64-only, and version-numbered `0.1.x` against this fork's `0.3.7`
   (build 115, 2026-08-26); the two numbering schemes are unrelated.
2. **User data should carry over as-is.** This fork moved settings/data to `~/.config/nuvio/`
   (one `.properties` file per store) in 0.1.13 specifically to adopt the official
   NuvioDesktop layout — see CHANGELOG 0.1.13. That call now pays off: the official Linux
   client reads the same directory. Worth verifying key-by-key before telling users to switch.
3. **The MPV-swap trigger has fired.** `docs/MPV_SWAP_SCOPING.md` ("Ecosystem check
   2026-08-12") recorded that upstream had *no* Linux bridge, so "migrate to official
   instead" was not an option and VLCJ stays. That premise is now stale — the bridge landed.
   Re-read that doc's "Preconditions and triggers" before doing any further VLCJ/mpv work.

### Open questions (not yet checked)

- Feature parity with this fork (supporter perks, TorrServer/P2P, tracking, subtitle styling).
- Whether the official build is usable enough on real hardware to recommend as the upgrade path.
- Whether it handles this fork's leftover data/backups cleanly on first launch.
