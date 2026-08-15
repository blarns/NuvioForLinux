# MPV Swap — Scoping Document (no implementation)

*Status: **feasibility proven, decision open.** The original call — stay on VLCJ until its
ceilings demonstrably hurt users — was made without knowing whether the proposed shape worked at
all. It does; see "Spike results" below. What remains is a scheduling decision, not a technical
unknown.*
*Written 2026-06-12 against v0.1.14 (build 83). Revisited 2026-08-12 against v0.3.2. **Revised
2026-08-15 against v0.3.4 — hardware decoding is now the leading reason to swap; see the revision
section below.***

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
| ~30 fps CPU frame copies | Smooth but capped; one full-frame copy per frame | Copy cost unchanged with SW render — but **decode cost collapses**, see the 2026-08-15 revision below |
| **No hardware decoding, at all** | VLC 3 forces `avcodec-hw=none` under `vmem`; 4K HEVC is software-decoded at ~500% CPU | `hwdec=auto-copy` hardware-decodes *and* hands back system-memory frames — measured working on stock libmpv 0.37 |
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

## Spike results (2026-08-12) — the proposed shape works, headless ✅

A JNA harness against **libmpv 0.37.0** (`libmpv2` on Ubuntu 24.04) exercised the exact path
proposed above. No window, no GL, no AWT, no display — deliberately, because if it works headless
it drops into the existing pipeline unchanged.

```
libmpv loaded via JNA: true
mpv_initialize -> 0 (ok)
mpv_render_context_create(sw) -> 0 (ok)
playback started: true
mpv_render_context_render -> 0 (ok)
frame buffer: 921600 bytes, 358045 non-zero (38.9%), mean=94.9
duration property: 2.04     time-pos property: 1.0
teardown clean
```

- **Binding**: direct `Native.load("mpv", …)`, no JNI and no C to maintain, as predicted.
- **Video**: `MPV_RENDER_API_TYPE_SW` rendered a decoded 640x360 frame into a **caller-owned
  BGRA buffer** — the same shape VLCJ's `RenderCallback` already hands to the Skia path. The
  render-param enum values are `SW_SIZE=17`, `SW_FORMAT=18`, `SW_STRIDE=19`, `SW_POINTER=20`.
- **State**: `mpv_observe_property` and `mpv_get_property` returned `duration` and `time-pos`,
  which is what the controller's 100 ms poll currently synthesises.
- **Lifecycle**: `mpv_render_context_free` + `mpv_terminate_destroy` with no crash.

### What the spike does NOT establish

It removes the "does this approach work at all" risk and nothing else. Still unproven:

- Real media. The source was `av://lavfi:testsrc`, not HLS, not a debrid HTTP URL with custom
  headers, not a TorrServer `/stream` URL. Header passing (`--http-header-fields`) is untested.
- Performance at 1080p in the real pipeline. The doc already says SW render does not beat VLCJ's
  copy cost; that remains unmeasured rather than disproven.
- **Subtitle styling via libass — the single biggest reason to swap — was not exercised at all.**
- Track switching, seek convergence, external subs, audio passthrough.
- Packaging: the deb gains `Depends: libmpv2`, but the **AppImage currently bundles libVLC and
  would need to bundle libmpv instead**, which the original estimate did not cost.

The 8–12 day estimate and the "high regression risk in the player for weeks after" warning both
still stand. What has changed is that the risk is now ordinary implementation risk.

## Revision 2026-08-15 — the CPU row above was wrong, and this is now the strongest argument to swap

The "What it does NOT fix" section says *"The CPU frame-copy cost stays (SW render ≈ buffer
callback)"*. That half is still true and still unimproved. **The half it missed is decode.**

A 2160p HEVC REMUX pegs ~580% CPU on an i7-1255U, and ~500% of that is *software HEVC decode* — not
copying. Root cause: **VLC 3 silently overrides `avcodec-hw` to `none` whenever the vout is `vmem`**,
which is exactly the callback surface this fork uses. Proved with a one-variable vlcj A/B (same
process, same file, same `--avcodec-hw=any`, only the video surface varied): normal vout →
`hw decoder module matching "any"`, `vmem` → `matching "none"`. It is not configurable around.

mpv does not have this problem. Measured on the same 1080p HEVC clip, same machine, all three
decoding **and** delivering frames to CPU memory as BGRA/RV32:

| engine | CPU (user+sys) | availability |
| --- | --- | --- |
| VLC 3.0.20 (today) | 25.5 s | shipping |
| VLC 4.0.0-dev + vlcj 5 | 14.0 s | both pre-release — see [`VLCJ5_MIGRATION_SCOPING.md`](VLCJ5_MIGRATION_SCOPING.md) |
| **libmpv 0.37 `hwdec=auto-copy`** | **14.6 s** | **already installed, stock Ubuntu 24.04** |

```
mpv --hwdec=auto-copy --vf=format=bgra --vo=null --no-audio --untimed clip.ts
[vd] Trying hardware decoding via hevc-vaapi-copy.
[vd] Using hardware decoding (vaapi-copy).
```

`auto-copy` is the key: it hardware-decodes and copies the frame back to system memory, which is
precisely the shape `MPV_RENDER_API_TYPE_SW` already assumes. So the proposed design does not change
— the swap simply also buys back ~500% CPU on 4K HEVC that VLCJ cannot reach at any version.

⚠ Measured at 1080p. At 2160p the decode share is 4× larger so the win should grow, but readback
grows too — not measured. Also still unmeasured: whether `hwdec=auto-copy` survives the real
`mpv_render_context` SW path rather than `--vo=null`.

This does not change the effort estimate or the regression-risk warning. It changes the *trigger*:
the ceilings are no longer only cosmetic (subtitle styling, error reasons) — one of them is now the
largest CPU cost in the application.

## Ecosystem check (2026-08-12)

The trigger below — *"upstream shipping a Linux bridge, at which point migrate to official
instead"* — has **not** fired, and it is worth recording why, because it looks like it has.

Upstream's desktop layer has moved out of `NuvioMedia/NuvioMobile` (3 desktop files left) into
`NuvioMedia/NuvioDesktop`, which lists Linux support and ships a deb "when available". But its
native sources are only `native/windows/player_bridge.cpp` and `native/macos/player_bridge.mm`.
There is **no Linux bridge**. The Kotlin side has Linux *scaffolding* —
`DesktopHostOs.LINUX -> "linux"` and a `libplayer_bridge.so` lookup — with nothing behind it, and
the libmpv library-name table reads `"windows" -> libmpv-2.dll`, `else -> emptyList()`. So the
official desktop app cannot play video on Linux through that path, and "migrate to official
instead" is not currently an option.

What *has* changed is direction: official desktop (Windows/macOS) and the community
`UmbraProjects/NuvioDesktop` fork are both on libmpv. Anything mpv-specific in those trees —
Anime4K shaders, RTX HDR, buffer presets, >100% volume boost — is unreachable from VLCJ.

## Preconditions and triggers

- **Sync 3 has landed (v0.1.15); the contract is stable.** Upstream's desktop merge —
  which would have changed the `PlatformPlayerSurface` expect signature — was *reverted
  upstream*, so there is no longer a moving contract blocking a swap. (Superseded note:
  this previously said "do not start before sync 3"; sync 3 shipped 2026-06-13.)
- Triggers that would justify scheduling it: real user demand for subtitle styling or
  audio passthrough; a VLCJ defect we can't work around; upstream shipping a Linux
  bridge (at which point migrate to official instead — see strategy notes).
- Until then: VLCJ ships, works, and is user-verified across five releases.
