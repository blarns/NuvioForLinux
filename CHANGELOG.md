# Changelog

All notable changes to **NuvioForLinux** (the Linux desktop fork) are documented here.
This focuses on desktop-specific work; features synced from upstream
[NuvioMedia/NuvioMobile](https://github.com/NuvioMedia/NuvioMobile) are noted where relevant.

The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [0.1.24] — 2026-06-22

### Fixed
- **End-of-playback "blink" (desktop).** At the end of a movie or episode the video flashed black
  3–4 times. libVLC drains a few trailing/black frames as the decoder flushes at end-of-stream, and
  the renderer was painting them; it now freezes the last good frame the moment playback ends (and
  resumes cleanly for the next episode), so the finish and the return to the previous screen stay clean.

### Changed
Synced functional fixes from upstream [NuvioMedia/NuvioMobile](https://github.com/NuvioMedia/NuvioMobile)
(shared `commonMain`, so these apply to the desktop build too):
- **Collection catalog "show more" routing** now navigates to the correct target.
- **Continue Watching**: fixed backfill, and fixed local removal when Trakt progress is present.
- **Subtitles**: addon matching now applies the tv/series alias and singular resource name, so more
  subtitle addons resolve.
- **Debrid**: instant streams are resolved before offering download / copy-link.
- A clear **"torrent not supported"** message in the stream actions sheet where applicable.
- Softer detail-screen hero bottom gradient.

## [0.1.23] — 2026-06-21

### Changed
- **Playback errors now say *why* they failed (desktop).** When a stream wouldn't open, the player
  previously showed a generic "playback error" with no detail — impossible to tell a dead link from a
  real bug. The desktop player now does a quick reachability probe of the failed source and reports the
  actual reason: a rejected/expired link (HTTP 401/403), a removed file (404/410), a server error
  (5xx), an unreachable host, or — when the source *is* reachable — an unsupported format or codec.
  This is most useful for plugin/scraper sources, whose links are frequently short-lived signed URLs:
  a 403 now reads "the link has likely expired or is region-locked — refresh sources and try again"
  instead of an opaque failure. The probe only runs on failure, so it adds nothing to normal playback,
  and non-HTTP sources (torrents, local files) keep their existing messages.

## [0.1.22] — 2026-06-20

### Added
- **Collection "focus" art now animates on hover (desktop).** Home-screen collection cards that have
  an animated focus image (GIF / animated WebP) now play it when you hover the card with the mouse:
  the static cover shows at rest and the animation fades in while hovered, and only the hovered card
  animates. Coil's desktop image decoder only renders the first frame of an animated image (and can't
  decode some at all), so the focus animation is decoded frame-by-frame with Skia's `Codec`,
  mirroring how the iOS build hand-rolls animation. Reported in #2.
- **Horizontal scrolling for card rows (desktop).** Card rows can now be scrolled with **Shift + the
  mouse wheel** or a **horizontal trackpad swipe**, and each scrollable row shows **floating ◀ / ▶
  buttons** at its edges (40% opacity, brighter on hover) so you can scroll by clicking. A plain
  vertical wheel still scrolls the page as before. Previously, a windowed (non-maximized) window could
  leave overflowing cards unreachable with a mouse.

### Fixed
- **Blank collection posters (desktop).** Collection tiles whose art is an animated WebP could show up
  blank, because the desktop decoder couldn't render an animated image at rest. Cards now show the
  static cover at rest (with the animation on hover, above), so they no longer appear blank. Reported
  in #2.

## [0.1.21] — 2026-06-19

### Fixed
- **Home-screen collection posters no longer reload while scrolling (desktop).** On the Linux
  desktop build, collection-row posters were loaded outside the shared image cache, so each one
  was re-downloaded and re-decoded every time it scrolled back into view — appearing to "unload"
  and flicker. They now go through the same Coil **memory + disk cache** as the rest of the app, so
  they load instantly after the first fetch and persist across restarts (disk cache lives at
  `~/.cache/nuvio/images`). Reported in #2.

### Internal
- **Removed inherited issue/PR auto-management workflows.** Several GitHub Actions carried over
  from the upstream legacy repo were auto-closing new issues and pull requests (and reaping
  unlabeled issues on a daily schedule). They have been removed so community issues and PRs stay
  open.

## [0.1.20] — 2026-06-17

### Added
- **Universal AppImage build (x86_64).** Alongside the `.deb`, Nuvio can now be packaged as a portable
  **AppImage** that runs on any glibc ≥ 2.35 distribution — Ubuntu 22.04 LTS, Debian 12, Fedora, Arch,
  and newer — with **no VLC installation required**: libVLC 3.0.16 plus its full plugin/codec set are
  bundled inside. Built by `scripts/build-vlc-bundle.sh` (stages VLC from an Ubuntu 22.04 container so
  the glibc floor stays low) and `scripts/package-appimage.sh` (jlinks the bundled runtime with
  Temurin 21, assembles the AppDir, and packs with appimagetool). The `.deb` is unchanged.
- **Cloud audio file playback (experimental).** The TorBox / Premiumize cloud library now lists and
  plays **audio** files — audiobooks and music — not just video. It recognizes `audio/*` types and
  common audio extensions (mp3, m4a, m4b, flac, opus, ogg, aac, wav, and similar). Playback uses the
  normal player, so progress tracking and resume-to-position work exactly as they do for video.
  Surfaced only when audio files are already present in your debrid cloud; there is no in-app search
  for them yet.

## [0.1.19] — 2026-06-15

### Changed
- **Stream descriptions are easier to read.** The secondary line under each stream result (size,
  source, quality, etc.) now uses a brighter, higher-contrast text color instead of the dim gray
  that was hard to read on some themes. Applied in both the results list and the stream-options sheet.

### Internal
- **Disabled the broken CI release workflow.** The tag-triggered GitHub Actions release built with an
  empty Supabase config (the root cause of the 0.1.13–0.1.17 login breakage) and skipped the deb
  patch step. Releases are now built locally and published manually; the in-app updater (GitHub
  Releases API) is unaffected, so users keep updating normally.

## [0.1.18] — 2026-06-15

### Fixed
- **Sign-in was broken (regression).** Releases since 0.1.13 (confirmed: 0.1.15.1, 0.1.17) were
  built without the Supabase configuration present, which baked an **empty** backend URL into the
  app. At runtime an empty URL makes every auth and data request resolve to `localhost` and fail —
  so signing in failed (`POST /auth/v1/token`), profiles couldn't sync, and **profile avatars
  rendered blank**. It stayed hidden while a previously-cached session kept working, and surfaced
  once that session expired and a fresh login was required. 0.1.18 ships with the correct backend
  configuration. **If you were affected, just sign in again after updating.**
- **Profile avatars no longer silently vanish.** The avatar catalog now falls back to a
  session-independent (anon) fetch — it's a public catalog, so a stale login must not blank it —
  and each avatar falls back to your initial if the image ever fails to load, instead of an empty
  circle.

### Added
- **Dedicated "P2P Streaming" settings page** (Settings → General → P2P). Peer-to-peer streaming
  can now be enabled directly from Settings; previously it was only reachable via an in-player
  consent prompt that never appeared when debrid resolved your streams. Includes the
  IP-exposure / VPN warning and the upload / hide-stats toggles. Off by default; debrid remains
  the recommended way to stream.

### Changed
- **The build now refuses to package an empty Supabase configuration.** Release builds fail fast
  if the backend URL/key are missing (e.g. building without `local.properties`), so the
  0.1.13–0.1.17 "empty config → localhost → broken login" class of regression can never ship again.

## [0.1.17] — 2026-06-14

### Fixed
- **Catalog pagination.** Scrolling to the end of a catalog (in Home rows, the catalog grid,
  Search results, and collection folders) now loads the next page correctly instead of
  stopping short or re-requesting the same page. (upstream)

### Changed
- **Stream lists are no longer prefetched on the details screen.** Opening a movie/show page
  used to start resolving its streams in the background before you asked; that warm-up is
  removed, so streams resolve when you open the stream list. Reduces unnecessary addon/debrid
  requests and avoids prematurely waking sources. (upstream)

## [0.1.16] — 2026-06-14

### Added
- **Peer-to-peer (P2P) torrent streaming — experimental, opt-in.** Desktop can now stream
  torrents directly, peer-to-peer, without a debrid subscription. This brings the fork to
  parity with the Android app's P2P feature.
  - **Off by default and gated behind consent.** P2P is disabled out of the box. Enabling it
    in Settings first shows a consent dialog explaining that **your real IP address becomes
    visible to other peers in the swarm** and that you are responsible for what you download.
    Debrid (which resolves torrents server-side over plain HTTPS, so your IP is never exposed
    to peers) remains the recommended default. **If you enable P2P, a VPN is strongly
    advised.**
  - **How it works.** The `.deb` bundles [TorrServer](https://github.com/YouROK/TorrServer)
    (MatriX.141.5), a self-contained torrent-streaming engine. When you play a P2P stream,
    Nuvio launches it locally, hands it the torrent, and plays the resulting stream through
    the normal VLC-based player — so seeking, resume and subtitles work the same as any other
    source. The engine idles down automatically a couple of minutes after playback stops.
  - **Why "experimental":** the full data path is verified (engine launch, torrent add,
    range-served playback, libVLC decode), but in-app playback hasn't yet had a wide test
    pass across many torrents. Expect rough edges on restrictive networks (e.g. UDP-blocking
    VPNs can slow the initial connect). Please report issues.
  - **Package size:** the bundled engine adds ~25 MB to the download (the `.deb` is now
    ~140 MB). TorrServer is GPL-3.0; its license and a source offer ship inside the package.

## [0.1.15.1] — 2026-06-13

### Fixed
- **`.deb` installs now pull in libVLC automatically.** The package embeds the VLCJ Java
  bindings but relies on the system's native `libvlc.so` at runtime, and the generated
  `.deb` previously declared no VLC dependency — so installing it on a machine without VLC
  left video playback silently broken (a blank player, no obvious cause). The package now
  declares `Depends: vlc-plugin-base | vlc`, so `apt` installs the libVLC runtime (plus its
  codec/demux/output plugins) alongside Nuvio. Users who already have VLC are unaffected,
  and building from source was never affected (its setup step already installs VLC).

## [0.1.15] — 2026-06-13

An upstream parity sync (NuvioMedia/NuvioMobile). No Linux-specific changes this round —
upstream briefly merged then reverted its own "desktop port," so the fork's player path
is unchanged; these are the worthwhile cross-platform fixes from that window.

### Fixed
- **Autoplay no longer skips post-credits scenes**, and post-credit detection now
  respects your configured skip threshold. (upstream)
- **Trakt: the next episode is no longer wrongly marked as watched** when an episode
  finishes, and Trakt save failures now surface as a toast instead of failing silently.
  (upstream)
- **Missing logos fall back to the title text** on the details hero, home hero and
  stream rows, instead of leaving a blank gap when a logo image is absent or fails to
  load. (upstream)
- **Clearing the Continue Watching cache refreshes watch progress immediately** rather
  than waiting for the next sync. (upstream)

### Changed
- **The home hero carousel now loops** around past the last item. (upstream)
- **Shelf rows: the "View All" action is now icon-only**, and shelf headers stay aligned
  even on rows that have no View All. (upstream)

## [0.1.14] — 2026-06-12

### Added
- **Addon update notifications.** Nuvio now re-checks your enabled addons' manifests
  twice a week while the app is running. When an addon server starts serving a newer
  version, a toast appears and the Addons page shows an Updates section listing each
  change (old → new version) with a one-click "Refresh now" action. (Addons are still
  refreshed automatically at every app start, as before.)
- **Update an addon's URL in place.** Config-based addons (AIOStreams and friends)
  generate a new manifest URL when you rebuild their configuration, leaving the old
  install pointing at the stale config. The new swap action (⇄) on each addon card
  lets you paste the new manifest URL — the addon keeps its position, enabled state
  and custom name, and the change syncs to your account.
- **The .deb now recommends `fonts-noto-color-emoji`.** Many addons (AIOStreams
  formatters in particular) use emoji in stream names and descriptions, which render
  as empty boxes when no emoji font is installed. Installing via apt or a software
  center now pulls the font in automatically; if you installed with plain `dpkg -i`
  and see boxes, install `fonts-noto-color-emoji` manually.

### Fixed
- **Initial sync no longer races sign-in restore at boot.** The first server pull
  (addons, library, watch progress, settings, collections) could be silently skipped
  when the app started faster than the session restore — most visible as data not
  syncing until much later. The pull now waits for sign-in to settle and backfills
  itself if boot got ahead of it.

## [0.1.13] — 2026-06-11

### Changed
- **Settings and data now live in `~/.config/nuvio/`** (one `.properties` file per
  store), adopting the official NuvioDesktop storage layout. Everything migrates
  automatically and verbatim on first launch — profiles, login session, addons,
  library, watch progress, watched history, settings — and the old data is left in
  place as a backup. If an official Linux client ever ships, it will read this data
  as-is.
- Clearing local account data now also removes the legacy preference data.

## [0.1.12.1] — 2026-06-11

### Fixed
- **Player controls work again.** 0.1.12's player reorg re-introduced a controller reset
  that raced controller delivery on desktop, leaving every control button a no-op.
- **Volume slider is back.** Its change-handler was dropped from the new controls wiring,
  which hid the slider entirely.
- **Volume slider drags smoothly.** The thumb used to rubber-band toward stale
  poll values mid-drag.
- **Rewinding no longer stalls playback for ~15 seconds.** While a seek was still
  landing, the timeline snapped back to the pre-seek position (inviting repeat seeks),
  rapid ±10s presses re-sought from the stale position instead of stacking, and every
  seek was issued twice. Seeks now hold their target until the player catches up,
  accumulate correctly, and flush only once.

## [0.1.12] — 2026-06-10

### Added — second upstream sync (10 commits)
- **Remember last used profile** — skip the profile picker on launch (toggle in the new
  Advanced settings section).
- **Advanced settings section**, including a clear-continue-watching-cache action.
- **Trakt as a source for "More like this"** on details pages (configurable in Trakt settings;
  defaults to Trakt when authenticated).
- **Stream badges overhaul** — Fusion badge settings are now global (moved out of Debrid
  settings; existing rules migrate automatically), plus a size-badge toggle and a badge
  position option (top/bottom of stream cards).
- **Credentialed stream auto-recovery** — when a tokened stream URL expires mid-playback,
  the app re-resolves the stream and resumes from where it failed instead of erroring out.

### Changed
- Player screen internals reorganized to match upstream's new structure; all desktop
  behavior (resume via `:start-time`, mouse auto-hide controls, window-close progress flush,
  volume slider) re-ported on top.
- Design-token refactor of the theming layer (synced from upstream).
- Adopted upstream's P2P module scaffolding — **disabled on desktop** (no torrent streaming
  on Linux; this just keeps future syncs clean and unblocked the badges overhaul).

### Fixed — desktop bug scan
- **Rewinding then quitting now saves the earlier position.** The resume guard used to
  refuse any backward save, snapping you back to the furthest point watched.
- **Stream headers (User-Agent / Referer) are now actually sent to VLC** — header-protected
  addon streams previously played without them or failed.
- A native media-player instance leaked on every playback session (never released).
- Switching sources/episodes could kill the new stream (a stop-race on the shared player).
- Logging in a second time no longer requires an app restart (stale OAuth state).
- Settings cleanup now removes spilled overflow files correctly.

## [0.1.11] — 2026-06-09

### Added
- **In-app trailer playback.** Trailers used to open in your browser; they now play inside the app.
  Click a trailer on a title's details page and it resolves and plays in the built-in player, with
  audio. On desktop the trailer appears as a wide card that slides down from the top of the window
  (mobile keeps the bottom sheet).

### Notes
- Trailers are sourced from YouTube. Extraction can occasionally fail or break when YouTube changes
  things (the same risk the mobile builds carry) — if a specific trailer won't play, try another.
- The first trailer of a session may take a moment to start (the extractor warms up).

## [0.1.10] — 2026-06-07

### Added — desktop settings now persist
Many settings were no-op stubs on Linux (they reset on restart). They now persist via
local storage with profile-scoped keys, mirroring Android (and syncing with mobile):

- **Player settings** — resize mode, preferred audio/subtitle languages, stream auto-play
  (mode, source, selected addons, regex, timeout), skip-intro, anime-skip, next-episode
  thresholds, hold-to-speed, subtitle delay, reuse-last-link, and more.
- **Theme settings** — selected theme, AMOLED, and app language (the saved language is applied
  at startup).
- **Per-content track preferences** — the audio/subtitle track you picked for a title is
  remembered next time.
- **Trakt comments** toggle.

`hwAccelEnabled` and `audioOutput` stay machine-local and out of cloud sync on purpose (a synced
audio-output value would break playback on a different machine).

### Notes — persisted vs applied on desktop
A few settings now **persist and sync to mobile but do not change desktop playback**, because the
desktop (VLCJ) player doesn't consume them: **subtitle styling** (text/outline color, size, bold),
the **iOS video-output** block, and **decoder priority / tunneling / DV7→HEVC** (ExoPlayer concepts).
Everything else — resize mode, preferred languages, auto-play, skip-intro, thresholds, theme,
AMOLED, app language — takes effect on desktop.

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

[0.1.12]: https://github.com/blarns/NuvioForLinux/releases/tag/v0.1.12
[0.1.11]: https://github.com/blarns/NuvioForLinux/releases/tag/v0.1.11
[0.1.10]: https://github.com/blarns/NuvioForLinux/releases/tag/v0.1.10
[0.1.9]: https://github.com/blarns/NuvioForLinux/releases/tag/v0.1.9
[0.1.8.1]: https://github.com/blarns/NuvioForLinux/releases/tag/v0.1.8.1
[0.1.8]: https://github.com/blarns/NuvioForLinux/releases/tag/v0.1.8
[0.1.7]: https://github.com/blarns/NuvioForLinux/releases/tag/v0.1.7
[0.1.6]: https://github.com/blarns/NuvioForLinux/releases/tag/v0.1.6
[0.1.5]: https://github.com/blarns/NuvioForLinux/releases/tag/v0.1.5
[0.1.1]: https://github.com/blarns/NuvioForLinux/releases/tag/v0.1.1
[0.1.0]: https://github.com/blarns/NuvioForLinux/releases/tag/v0.1.0
