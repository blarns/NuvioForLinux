# MPV Swap — Scoping Document (no implementation)

*Status: scoping only. Decision on file: **stay on VLCJ** until its ceilings demonstrably
hurt users. This doc sizes the swap so that decision can be revisited cheaply.*
*Written 2026-06-12 against v0.1.14 (build 83).*

## Context

The fork plays video through VLCJ's buffer-callback API: libVLC decodes into a
`ByteBuffer`, each frame is copied and converted to a Skia `ImageBitmap`, and painted in
a Compose `Canvas` (`PlayerEngine.desktop.kt`, ~650 lines incl. `VlcjPlayerController`).
This deliberately avoids AWT-heavyweight embedding and its Z-order problems — all player
UI is plain Compose.

Upstream (NuvioMedia) went the other way: per-OS native bridges (~2950-line Obj-C++ for
macOS, ~1965-line C++ for Windows) embedding mpv into an AWT `Canvas`, now merged into
NuvioMobile's `cmp-rewrite` (June 2026). **No Linux bridge exists**; their loader
hard-requires macOS/Windows. If we ever swap, porting their bridge is the wrong shape —
a third hand-written JNI bridge, AWT embedding, and their player-UI stack. The right
shape is below.

## Why swap (VLCJ ceilings, from shipped experience)

| Ceiling | Effect today | With libmpv |
|---|---|---|
| Subtitle styling not applied | Style settings persist/sync but don't render | libass renders styles into the frame (`--sub-*` options, runtime-settable) |
| No track language codes | Weak preferred-language auto-select | `track-list` property has `lang`, `title`, codec per track |
| ~30 fps CPU frame copies | Smooth but capped; one full-frame copy per frame | Same copy cost with SW render — **not** improved; see Risks |
| Coarse error events | Generic "error" with no reason | `end-file` event carries a reason + `mpv_error` strings |
| Seek timing quirks | Needed the pending-seek convergence workaround (0.1.12.1) | mpv reports seeking state explicitly (`seeking`, `playback-restart`) |
| EAC3/passthrough noise | Cosmetic stderr errors at open | mpv's audio output negotiation is quieter and supports passthrough properly |

## Proposed shape: libmpv software-render into the existing pipeline

Keep the entire Compose/Skia side unchanged. Replace only what's behind the
`PlatformPlayerSurface` / `PlayerEngineController` expect-actual boundary.

1. **Binding:** JNA direct mapping of ~25 libmpv functions (`mpv_create`,
   `mpv_initialize`, `mpv_set_option_string`, `mpv_command`, `mpv_observe_property`,
   `mpv_wait_event`, `mpv_render_context_create/render/free`, …). VLCJ already pulls in
   JNA, so no new binding tech. No JNI, no C++ to maintain.
2. **Video:** `mpv_render_context` with `MPV_RENDER_API_TYPE_SW` → mpv renders BGRA into
   a caller-owned buffer → reuse the existing buffer→`ImageBitmap`→Canvas path ~1:1.
   The frame pump becomes "render on `MPV_EVENT_*` update callback" instead of VLCJ's
   `RenderCallback`.
3. **Events/state:** one daemon thread on `mpv_wait_event` + observed properties
   (`time-pos`, `duration`, `pause`, `track-list`, `seeking`, `eof-reached`) feeding the
   same controller state the 100 ms poll currently builds. The poll can stay initially.
4. **Controller mapping** (`PlayerEngineController` is 15 small methods):
   `loadMedia` → `loadfile` + per-load options; resume → `--start=<s>` (replaces the
   `:start-time` trick); headers → `--http-header-fields`, `--user-agent`, `--referrer`;
   tracks → `aid`/`sid` + `track-list`; external subs → `sub-add`/`sub-remove`;
   delay → `sub-delay`; speed → `speed`; volume → `volume` (0–130).
5. **Packaging:** deb gains `Depends: libmpv2` (the `PatchDebRecommendsTask` pattern
   already exists for control-file edits). No bundling needed on Ubuntu/Debian.

## What it does NOT fix

- The CPU frame-copy cost stays (SW render ≈ buffer callback). GPU rendering
  (`MPV_RENDER_API_TYPE_OPENGL` into a shared texture) is a separate, much harder
  follow-up with Skia/Skiko interop — explicitly out of scope.
- Nothing above the expect/actual boundary changes, so no sync benefit/cost either way.

## Effort estimate

| Work item | Size |
|---|---|
| JNA libmpv binding (functions + event/property structs) | 1–2 days |
| MpvPlayerController implementing `PlayerEngineController` | 2–3 days |
| SW render context + frame pump into existing Skia path | 1–2 days |
| Re-port the 7 fork player deltas (resume, flush callback, mouse-idle, volume wiring…) | 1 day |
| Settings parity (hwAccel toggle → `hwdec`, audio output device) | 0.5 day |
| Testing across debrid/HLS/local + subtitle styles + track switching | 2–3 days |
| **Total** | **~8–12 focused days**, high regression risk in the player for weeks after |

## Preconditions and triggers

- **Sync 3 has landed (v0.1.15); the contract is stable.** Upstream's desktop merge —
  which would have changed the `PlatformPlayerSurface` expect signature — was *reverted
  upstream*, so there is no longer a moving contract blocking a swap. (Superseded note:
  this previously said "do not start before sync 3"; sync 3 shipped 2026-06-13.)
- Triggers that would justify scheduling it: real user demand for subtitle styling or
  audio passthrough; a VLCJ defect we can't work around; upstream shipping a Linux
  bridge (at which point migrate to official instead — see strategy notes).
- Until then: VLCJ ships, works, and is user-verified across five releases.
