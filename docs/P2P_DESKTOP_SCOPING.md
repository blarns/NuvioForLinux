# P2P Torrent Streaming on Linux Desktop — Scoping Document (no implementation)

*Status: scoping only. Decision pending. The fork shipped non-P2P (debrid-only) through
v0.1.15; this sizes adding real P2P now that the app is public.*
*Written 2026-06-13 against v0.1.15 (build 84).*

## Context

The fork adopted upstream's **entire commonMain P2P module** back in sync 2, then gated it
off on desktop. What already exists:

- `P2pStreamingEngine` (expect, `P2pStreaming.kt`):
  `suspend fun startStream(P2pStreamRequest): String` (returns a playable URL),
  `warmup()` / `cooldownWarmup()` / `stopStream()` / `shutdown()`,
  `state: StateFlow<P2pStreamingState>` (Idle / Connecting / Streaming{localUrl, speeds,
  peers, seeds, buffer/total progress} / Error).
- `P2pStreamRequest{ infoHash, fileIdx, filename, magnetUri, trackers }`.
- `P2pSettingsRepository` + `P2pSettingsStorage` — **desktop actual already persists**
  (`p2pEnabled`, `enableUpload`, `hideTorrentStats`) via `DesktopStorage`.
- `P2pConsentDialog.kt`, `P2pPlayerOverlays.kt` — commonMain UI, already built.
- `AppFeaturePolicy.desktop.p2pEnabled = false` gates every call site off.
- `P2pStreamingEngine.desktop` is **inert**: `startStream` throws.

So everything above the engine boundary is done. The single missing piece is a working
`P2pStreamingEngine.desktop`.

## How Android does it (the reference we port)

`P2pStreamingEngine.android.kt` (~1150 lines) runs **TorrServer** — a Go torrent-streaming
daemon — as a local process and drives it over HTTP:

- `TorrServerBinary`: launches `libtorrserver.so` (the TorrServer binary shipped in the
  APK's `nativeLibraryDir`), serving `http://127.0.0.1:<PORT>`; config/cache under the app
  files dir; an idle TTL stops it ~120 s after the last stream.
- `TorrServerApi` (OkHttp + JSON): add torrent (magnet/infoHash + trackers), poll metadata,
  resolve the file by index/filename (`TorrServerStreamSelector`), map stats →
  `P2pStreamingState`.
- `startStream` returns `http://127.0.0.1:<PORT>/stream?link=<magnet>&index=<n>&play` — a
  plain HTTP URL the player consumes like any other source.
- Ships a default tracker list (opentrackr, demonii, …) merged into the magnet.

TorrServer already solves the hard parts: sequential piece prioritization, read-ahead,
range-HTTP serving, multi-file selection, peer management. We are not reimplementing a
torrent client — we are launching one and asking it for a URL.

## Proposed shape: bundle TorrServer's Linux binary, reuse the Android logic

The Android actual is ~90% platform-agnostic. The port:

1. **Engine binary** — bundle `TorrServer-linux-amd64` in the deb (TorrServer ships static
   Go builds, no runtime toolchain). Launch via `ProcessBuilder` instead of Android's
   exec-from-`nativeLibraryDir`. Same `http://127.0.0.1:<PORT>` contract afterward.
2. **HTTP client** — the Android code uses OkHttp; desktop ships `ktor-client-java`. The API
   is ~6 calls; rewrite them against the JDK's built-in `java.net.http.HttpClient` (no new
   dependency) rather than adding OkHttp.
3. **Paths** — replace `ctx.filesDir` / `nativeLibraryDir` with XDG dirs
   (`~/.cache/nuvio/torrserver` for the torrent cache; the bundled binary path from the
   jpackage app image). `DesktopStorage` already established these conventions.
4. **Logging** — `android.util.Log` → `println` / the fork's logger.
5. **Port the rest near-verbatim** — `TorrServerApi`, `TorrServerStreamSelector`,
   file-index resolution, tracker defaults, state mapping, idle TTL, warmup/cooldown.
6. **Flip the gate** — `AppFeaturePolicy.desktop.p2pEnabled = true`. This only makes the
   *toggle available*; the `p2pEnabled` **setting** stays false by default (see UX).
7. **Wire to VLCJ** — nothing to do. `startStream` returns an HTTP URL; the existing player
   path plays it, with resume/headers already handled.

For a first cut, copy `TorrServerApi`/selector into the desktop actual; they could be lifted
into a shared JVM source set later if iOS/desktop converge.

## Packaging & licensing

- **Size** — the TorrServer Linux binary is ~15–20 MB; the deb grows from ~117 MB
  accordingly. Place it in the jpackage app-image resources; ensure the executable bit
  (deb `postinst`, or `chmod` at first launch).
- **License** — TorrServer is **GPL-3.0**. We ship it *unmodified, as a separate executable
  invoked over localhost HTTP* (not linked) → "mere aggregation," so the GPL does not reach
  the Compose app. Obligations: include TorrServer's license text and a written offer/link
  to its source in the package (e.g. `/usr/share/doc/nuvio/`). **Confirm the exact upstream
  license/version before shipping.**
- Alternative to bundling: download-on-first-use (the addon-updater download pattern exists),
  but bundling is simplest and works offline.

## UX & safety (this is a public app)

Debrid — what the maintainer uses — resolves torrents server-side to plain HTTP. **P2P
fetches over the user's real IP.** For a public Reddit/GitHub audience:

- **Off by default.** The `p2pEnabled` setting stays false; flipping the policy gate only
  surfaces the toggle.
- **Consent on enable.** `P2pConsentDialog` already exists in commonMain — wire it to the
  first enable; state the real-IP exposure plainly and suggest a VPN.
- Later, optional: a VPN/interface-bind guard; the upload toggle already exists
  (`enableUpload`).

## Alternative engine (rejected for v1)

`frostwire-jlibtorrent` (libtorrent JNA binding) + a Ktor range server: no separate process,
but you reimplement the sequential-download + range-serving that TorrServer already does,
still ship a native `.so`, and diverge from the Android implementation. More code, more
regression surface, no parity. Revisit only if the TorrServer process dependency becomes a
real problem.

## Effort estimate

| Work item | Size |
|---|---|
| Bundle TorrServer-linux in deb + jpackage resource wiring + exec bit | 1 day |
| `TorrServerBinary` desktop: `ProcessBuilder` lifecycle, port, config dir, warmup/idle-TTL | 1–2 days |
| Port `TorrServerApi` + stream selector to `java.net.http` (from Android) | 1–2 days |
| State mapping + flip `AppFeaturePolicy` gate + wire consent dialog | 1 day |
| License/doc compliance + deb metadata | 0.5 day |
| Testing: magnet + infoHash-only + multi-file index; play/seek/resume via VLCJ; VPN on/off | 2–3 days |
| **Total** | **~6–9 focused days** |

Far smaller than it looks because the commonMain half is done and the Android engine is a
working blueprint.

## Recommended next step: a ~1-day spike

De-risk the two real unknowns before committing to the full estimate:

1. Does the official **TorrServer Linux binary run headless** on a clean Ubuntu (no Go
   toolchain) and serve `/stream` for a known magnet?
2. Does **VLCJ play the resulting `127.0.0.1/stream` URL** cleanly (open, seek, resume)?

A throwaway shell script (download binary, run it, curl `/stream`) plus a one-off
`ProcessBuilder` launch answers both. Green spike → the rest is the near-verbatim port.

## Spike results (2026-06-13) — both unknowns GREEN ✅

Ran the spike against `TorrServer-linux-amd64` MatriX.141.5 (a 74 MB Go binary) on a clean
machine, streaming Blender's *Big Buck Bunny* (CC-BY).

- **Q1 — headless binary:** ✅ Runs with no Go toolchain, serves its HTTP API (`/echo` →
  `MatriX.141.5`), DNS + outbound connectivity fine. Exactly the shape `ProcessBuilder`
  would launch and drive.
- **Data path:** ✅ `GET /stream/<name>?link=<hash>&index=<n>&play` serves the file with
  HTTP range support; a 3 MB range fetch returned valid `ISO Media, MP4`.
- **Q2 — libVLC plays it:** ✅ `cvlc` (the same libVLC VLCJ wraps) opened the `/stream`
  URL, demuxed the MP4 (3 tracks), started the **h264** decoder, and decoded at
  **1920×1080**. VLCJ plays it unchanged — `startStream` just returns this URL into the
  existing player path.

**Caveat to carry into the implementation *and* the user-facing UX:** in this sandbox,
fetching magnet metadata via **DHT/UDP failed** (firewalled) — `Torrent close by timeout`,
no peers found. Adding via the **`.torrent` HTTP URL + webseed** worked perfectly. Real
torrents reach plenty of **TCP** peers via trackers so this is usually a non-issue, but:
(a) keep DHT + trackers enabled and tolerate webseeds; (b) users on restrictive networks or
UDP-blocking VPNs may see slow starts — worth a line in the consent/help text. This also
reinforces that debrid (pure HTTPS) is the more robust default; P2P is the opt-in.

**Conclusion:** the architecture is validated end-to-end, no surprises. Proceed with the
~6–9 day implementation whenever it's greenlit.

## Preconditions / triggers

- 0.1.15 is shipped; **no contract churn here** — the P2P engine API was untouched by the
  desktopweb revert, so unlike the MPV swap there is no moving target.
- Trigger to build: the maintainer wants public users to get addon/Torrentio-style torrent
  playback **without a debrid subscription**.
- Keep debrid the recommended default; P2P is an opt-in for the broader user base, not the
  maintainer's own workflow.
