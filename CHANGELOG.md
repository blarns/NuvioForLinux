# Changelog

All notable changes to **NuvioForLinux** (the Linux desktop fork) are documented here.
This focuses on desktop-specific work; features synced from upstream
[NuvioMedia/NuvioMobile](https://github.com/NuvioMedia/NuvioMobile) are noted where relevant.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [0.1.9] — 2026-06-06

### Added
- **TMDB enrichment, MDBList ratings, and Debrid settings now persist on Linux.** These desktop
  settings stores were previously no-op stubs, so API keys and toggles never survived a restart.
  They are now backed by local profile-scoped storage with cloud-sync parity.
  - *Note:* TMDB enrichment activates once you enter an API key **and** turn on the separate
    "Enable enrichment" toggle; the enriched data (cast, trailers, artwork, "more like this")
    appears on a title's **details** page.
- Improved external-player plumbing and player controls (synced from upstream).

### Changed
- Synced 30 commits from upstream NuvioMobile for feature parity:
  - **Performance:** Trakt sync reduced from ~74s to <3s; watch-progress push throttling; delta progress sync.
  - **Debrid / Cloud:** stream cache check before publishing, fusion filter tags, "Original order"
    sort option, up-to-3 quality badges, library pagination + Trakt mapping improvements.
  - **UI:** TV-style continue-watching card, metascreen action-menu refresh, catalog scroll-position
    memory, updated bookmark icon, continue-watching hydration retry.
  - **Networking:** User-Agent header on requests, sync reliability (force pull on foreground).
  - **i18n:** scrollable language-selection sheet, additional Indonesian translations.

### Notes
- The global stream-badge refactor and the Calf-based bottom-sheet refactor were intentionally
  **skipped** for desktop compatibility (P2P dependency / uncertain Linux support).

## [0.1.8.1] — 2026-06-05

### Added
- **In-app update notifications** — the app polls GitHub releases on startup and offers to download
  newer versions, with release notes and a one-click `.deb` download.
- **Right-click as a context-menu trigger** on desktop (poster cards, episode rows, season chips,
  continue-watching cards), mirroring long-press on mobile — mark watched, mark previous/season
  watched, play manually, remove from continue watching, etc.

### Fixed
- The `.deb` package version now stays in sync with the app version automatically.

## [0.1.8] — 2026-06-05

### Fixed
- **Reliable video resume** — playback now resumes at the saved position using VLC's `:start-time=`
  media option. Seeking immediately after `play()` silently no-op'd on most streams because libVLC
  had not parsed the media length yet.
- Saved position no longer regresses to near-zero after the streaming backend rotates a stream URL
  token mid-session.

## [0.1.7] — 2026-06-04

### Fixed
- Resume from the saved position regardless of how playback was started.

## [0.1.6] — 2026-06-04

### Fixed
- Watch-progress flush uses the last-known-good position on pause / stop / error (VLCJ can report 0
  at event-fire time on some streams).

## [0.1.5] — 2026-06-04

### Fixed
- Player / VLCJ stabilization pass:
  - Unblock the render thread and EDT on player teardown and app close.
  - Include the `jdk.unsupported` module so VLCJ can allocate native video buffers via `sun.misc.Unsafe`.
  - Move keyboard shortcuts to window level; remove focus grab from the player.
  - Throttle frame display to ~30fps (fixes a `ZipFile` "invalid LOC header" crash on pause).
  - Poll live VLC position on every tick instead of relying on stale event state.
  - Persist playback position across app restarts.

## [0.1.1] — 2026-06-04

### Fixed
- Dynamic `Exec` path in the desktop launcher; clarified Supabase setup.
- Applied upstream NuvioMobile v0.2.3 hotfix patches.

## [0.1.0] — 2026-06-03

### Added
- Initial **Linux desktop port** via Kotlin Multiplatform JVM + VLCJ:
  - HLS / DASH / RTSP / HTTP playback, audio, and subtitles (SRT / VTT / ASS).
  - OAuth authentication with persistent session.
  - Full Stremio addon ecosystem support with persistent storage.
  - Continue Watching, Library, and Search.
  - Hardware video acceleration via VA-API (Intel / AMD) and NVDEC (NVIDIA).
  - MPRIS2 media-key integration, episode release notifications, and app-launcher integration.

[0.1.9]: https://github.com/blarns/NuvioForLinux/releases/tag/v0.1.9
[0.1.8.1]: https://github.com/blarns/NuvioForLinux/releases/tag/v0.1.8.1
[0.1.8]: https://github.com/blarns/NuvioForLinux/releases/tag/v0.1.8
[0.1.7]: https://github.com/blarns/NuvioForLinux/releases/tag/v0.1.7
[0.1.6]: https://github.com/blarns/NuvioForLinux/releases/tag/v0.1.6
[0.1.5]: https://github.com/blarns/NuvioForLinux/releases/tag/v0.1.5
[0.1.1]: https://github.com/blarns/NuvioForLinux/releases/tag/v0.1.1
[0.1.0]: https://github.com/blarns/NuvioForLinux/releases/tag/v0.1.0
