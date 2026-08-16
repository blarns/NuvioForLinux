# MPV Swap — Scoping Document (no implementation)

*Status: **feasibility proven, decision open.** The original call — stay on VLCJ until its
ceilings demonstrably hurt users — was made without knowing whether the proposed shape worked at
all. It does; see "Spike results" below. What remains is a scheduling decision, not a technical
unknown.*
*Written 2026-06-12 against v0.1.14 (build 83). Revisited 2026-08-12 against v0.3.2. **Revised
2026-08-15 against v0.3.4: libmpv restores hardware decoding, which VLCJ cannot reach at any
version. But the SW-render design in this document is the wrong plan — at 2160p it is worth only
~23%, because readback replaces decode as the bottleneck. GPU rendering plus zero-copy `vaapi`
measures **1.85 s vs 41.2 s — ~22× cheaper** — on the real 4K stream, and the Skia interop
primitives are already in the shipped skiko. Build it GPU-first. See the two revision sections
below.***

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

## Revision 2026-08-15 — the CPU row above was wrong, but the fix is smaller than it first appears

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

### Confirmed through the real SW render path, not just `--vo=null`

The numbers above were taken with `--vo=null`, which is *not* the path this design uses. Re-run
against the actual one — `mpv_render_context_create(MPV_RENDER_API_TYPE_SW)` plus
`mpv_render_context_render` with `SW_SIZE`/`SW_FORMAT=bgra`/`SW_STRIDE`/`SW_POINTER` into a
caller-owned buffer, i.e. exactly the shape that feeds the Skia path today
(`scripts/spikes/mpv_sw_hwdec.c`, C against `libmpv-dev`):

| `hwdec` | `hwdec-current` reported by mpv | CPU (400 frames) | frames non-blank |
| --- | --- | --- | --- |
| `no` | `no` | 23.3 s | 400 / 400 |
| `auto-copy` | **`vaapi-copy`** | **12.6 s** | 400 / 400 |

**Hardware decoding survives SW rendering** — the two are independent in mpv, which is precisely
what VLC 3 gets wrong. `hwdec-current` is queried per run so a silent fallback to software cannot
pass unnoticed. Both legs share the same 200 µs poll in the frame pump, so it cancels out of the
comparison.

### ⚠⚠ At 2160p the win is 23%, not 46% — readback replaces decode as the bottleneck

The 1080p numbers oversell this. Re-run on the **actual problem content** — the 2160p HEVC **Dolby
Vision** HDR REMUX, streamed over its real debrid HTTP URL with a custom `User-Agent`, 120 frames
each leg, buffer sized 3840×2160:

| `hwdec` | `hwdec-current` | CPU (120 frames) | vs 1080p |
| --- | --- | --- | --- |
| `no` | `no` | 41.2 s | — |
| `auto-copy` | `vaapi-copy` | **31.7 s (−23%)** | was −46% at 1080p |

Two things this establishes and one it kills:

- ✅ **hwdec works on Dolby Vision 4K.** `hwdec-current=vaapi-copy`, all 120 frames non-blank,
  `src=3840x2160 fmt=hevc`. DV7 was a plausible fallback trigger; it is not one.
- ✅ **`http-header-fields` works** on a debrid URL — the last untested item from the original
  "What the spike does NOT establish" list.
- ❌ **The CPU case for SW rendering at 4K is much weaker than it looks.** `sys` time *rose* from
  1.56 s to 4.32 s — that is the GPU→CPU download. At 3840×2160 each frame is 33 MB, so removing
  decode just promotes readback + 10-bit→BGRA conversion to the dominant cost. Scaled against the
  ~580% the shipped app burns on this file, the projection is roughly **580% → ~450%**, not the
  near-elimination that "hardware decoding now works" suggests.

**Implication for sequencing.** If the goal is "4K without the fans", `MPV_RENDER_API_TYPE_SW` does
not get there — and neither would VLC 4, which has the same readback. The fix that does is GPU
rendering (`MPV_RENDER_API_TYPE_OPENGL` into a texture shared with Skia, no readback at all), which
this document lists under "What it does NOT fix" as a separate, much harder follow-up. That
follow-up is now the load-bearing piece, not an optional extra.

⚠ A direct VLC-3-vs-mpv measurement on the same 4K stream was attempted and **discarded**: VLC drops
frames under `--sout` when the network stalls, so the two legs did not process the same frame count.
The mpv A/B above is frame-accurate (120 = 120) and is the only comparison quoted here.

## ✅ GPU-render spike (2026-08-15) — this is the one that fixes 4K

`MPV_RENDER_API_TYPE_OPENGL` rendering into an FBO, so the frame never leaves the GPU. Offscreen
EGL, no window, headless — `scripts/spikes/mpv_gl_hwdec.c`. Same 2160p HEVC DV HDR REMUX over its
real debrid URL, same 120 frames, same machine as every number above:

| path | CPU (120 frames @ 2160p) | vs today |
| --- | --- | --- |
| SW render + software decode (≈ ships today) | 41.2 s | — |
| SW render + `hwdec=auto-copy` | 31.7 s | −23% |
| GPU render + software decode | 19.1 s | −54% |
| **GPU render + zero-copy `hwdec=vaapi`** | **1.85 s** | **−96%** |

**~22× cheaper than the current path.** Projected against the ~580% the shipped app burns on this
file, that is order-of-magnitude tens of percent, not hundreds. The two effects compound: hardware
decode removes the decode cost, GPU rendering removes the 33 MB/frame readback that otherwise
replaces it. Neither alone is enough — that is why the SW-render numbers disappointed.

### ⚠ Zero-copy needs the right EGL display — this is the trap

`hwdec=vaapi` reports `hwdec-current=**no**` under `EGL_PLATFORM_SURFACELESS_MESA`, and `hwdec=auto`
silently degrades to `vaapi-**copy**` — which still downloads every frame and throws the win away
while looking like it works. It binds properly only with an **X11 platform EGL display**
(`eglGetPlatformDisplayEXT(EGL_PLATFORM_X11_KHR, XOpenDisplay(NULL), …)`) plus
`MPV_RENDER_PARAM_X11_DISPLAY`. Then `hwdec-current=vaapi`, no `-copy` suffix. **Always assert on
`hwdec-current` — the difference between `vaapi` and `vaapi-copy` is the entire benefit.**

### Skia interop: the primitives are already shipped

The remaining question was whether Compose Desktop can draw an mpv-owned GL texture. Every piece
exists in the **skiko 0.144.6 already in the app's jars** — no new dependency:

- `DirectContext.Companion.makeGL()` — wrap the live GL context
- `BackendTexture.Companion.makeGL(w, h, isMipmapped, textureId, target, format)` — wrap mpv's texture
- `Image.Companion.adoptTextureFrom(DirectContext, BackendTexture, SurfaceOrigin, ColorType)` —
  produce a Skia `Image` that the existing `drawBehind` can draw, replacing the `makeRaster` call at
  `PlayerEngine.desktop.kt:385` with zero copies

### ✅ Interop spike (2026-08-15) — Skiko draws a foreign GL texture, pixels verified

`scripts/spikes/SkikoInterop.kt` — a `SkiaLayer` (the same component Compose Desktop renders
through) with a `SkikoRenderDelegate`. Inside `onRender` it creates a GL texture with **raw GL via
JNA**, fills it with solid magenta, adopts it into Skia and draws it. The texture is created by
foreign GL rather than by mpv deliberately: mpv rendering into an FBO is already proven by
`mpv_gl_hwdec.c`; what was unproven was the Skia half.

```
SPIKE: renderApi=OPENGL
SPIKE: created foreign GL texture id=1 glError=0
SPIKE: frame 1 DirectContext=null (via LinuxOpenGLRedrawer.contextHandler.getContext())
SPIKE: frame 2 DirectContext=FOUND
SPIKE: adoptTextureFrom + drawImage OK (image 256x256)
RESULT skiko-interop ok=true apiOk=true pixelsVerified=true (1024/1024 magenta)
```

Verified by capturing the live window with `java.awt.Robot` and sampling it — "drawImage did not
throw" is not the same claim as "the texture is on screen".

Three things this establishes:

- **Raw GL calls work on Skiko's render thread**, with its context current, `glError=0`. That is
  where `mpv_render_context_render` would be called.
- **`Image.adoptTextureFrom` + `canvas.drawImage` works** on a texture Skia does not own. This is
  the drop-in replacement for the `makeRaster` copy at `PlayerEngine.desktop.kt:385`.
- `DirectContext.resetGLAll()` before drawing keeps Skia consistent after foreign GL calls.

### ⚠ Three caveats that shape the implementation

1. **The `DirectContext` is only reachable by reflection.** There is no public accessor. The working
   path is `SkiaLayer.getRedrawer$skiko()` → `LinuxOpenGLRedrawer.contextHandler` (private field) →
   `getContext()` (protected). That is skiko **0.144.6** internals and can break on any Compose
   Desktop bump — it needs a version check and a graceful fallback, not a bare `!!`.
2. **It is null on the first frame.** The handler creates it lazily during the first draw, so the
   video surface must tolerate "no context yet" and retry rather than fail.
3. **`renderApi` must be `OPENGL`.** Skiko falls back to `SOFTWARE_*` on some drivers/VMs, and this
   whole path is unavailable there. The existing buffer-copy path has to stay as a fallback, which
   means shipping **both** video paths, not replacing one with the other.

**Still not proven:** this used `SkiaLayer` directly. Compose Desktop owns its own `SkiaLayer`
inside `ComposeWindow`, so reaching it from a `@Composable` player surface needs one more seam. That
is a smaller question than the one just answered — the rendering and adoption both work — but it is
the next thing to spike.

### What this does to the plan

The effort table above is for the **SW** design and is now the wrong plan — it costs 8–12 days for
the −23% row. GPU rendering was filed under "What it does NOT fix" as an optional follow-up; it is
in fact the whole point. A re-estimate should assume: same JNA binding and controller work, but the
video path targets `MPV_RENDER_API_TYPE_OPENGL` from the start, plus the Skiko context seam above.
Build it GPU-first, not SW-first-then-maybe-GPU.

This does not change the effort estimate or the regression-risk warning. It changes the *trigger*:
the ceilings are no longer only cosmetic (subtitle styling, error reasons) — one of them is now the
largest CPU cost in the application.

## ⚠⚠ Revision 2026-08-16 — Skiko is GLX, so the Skia seam cannot reach zero-copy

The section above ends by calling the ComposeWindow seam "a smaller question than the one just
answered". That was wrong, and not because the seam is hard to reach — it turns out to be *easier*
than expected (see below). It is wrong because **the context on the other side of it is the wrong
kind**.

**Skiko's Linux OpenGL redrawer is GLX, not EGL.** `libskiko-linux-x64.so` links `libGLX.so.0` /
`libGL.so.1` / `libX11.so.6` and exports **zero** EGL symbols (`nm -D | grep -i egl` matches only
JNI names like `..._1nMakeGL`). mpv's zero-copy VA-API interop goes through
`EGL_EXT_image_dma_buf_import` and is EGL-only.

Proved by one-variable A/B — `scripts/spikes/mpv_glx_hwdec.c` is `mpv_gl_hwdec.c` with GLX
substituted for EGL and nothing else changed. Same file, same args, same frame count:

| GL context | `hwdec` requested | `hwdec-current` |
| --- | --- | --- |
| EGL (`EGL_PLATFORM_X11_KHR`) | `vaapi` | **`vaapi`** ✅ |
| GLX (what Skiko gives) | `vaapi` | **`no`** ❌ |
| GLX | `auto-copy` | `vaapi-copy` |

So the plan's load-bearing assumption — adopt mpv's GL texture into Skiko's `DirectContext` — tops
out at `vaapi-copy`. It never reaches the tier that motivated the migration.

### What each tier actually costs

Re-measured 2026-08-16 on the real 2160p HEVC Dolby Vision REMUX over its debrid URL, 120
frames/leg, all three legs back-to-back in one session.

⚠ **Methodology differs from the table earlier in this document.** These are `/usr/bin/time`
user+sys (so they include network `sys` time); the earlier rows were `/proc` per-thread deltas.
Compare *within* this table only — the earlier "1.85s" and today's "3.91s" are the same
configuration measured two different ways.

| path | `hwdec-current` | CPU (120 frames) |
| --- | --- | --- |
| GLX + software decode | `no` | 29.08 s |
| **GLX + `auto-copy` — the ceiling of the Skia-interop route** | `vaapi-copy` | **15.34 s** |
| **EGL + `vaapi` — needs mpv to own its own context** | `vaapi` | **3.91 s** |

There is **no today-methodology number for the shipping VLC 3 path**, so no honest multiplier can be
quoted against it. GLX + software decode (29.08 s) is the fair proxy floor: the shipping path does
strictly more work than that (same software decode, plus the readback and the JVM-side
`makeRaster` copy).

### The three options this leaves

1. **Skia interop over GLX** — 15.3 s. Roughly halves CPU vs software decode, keeps Compose
   compositing exactly as it is today, no visible product change. Still needs the ComposeWindow
   seam spike. Does **not** fix 4K the way the 3.9 s tier does.
2. **mpv owns its own EGL context** (`--wid` embedding or a dedicated X window) — the 3.9 s tier.
   Skia is not involved at all, so no `DirectContext`, no reflection, no version fragility. The
   cost is entirely UI: a heavyweight AWT/X11 child window means Compose cannot draw over the
   video, so player controls need re-hosting. This document's "Context" section lists that Z-order
   problem as a thing the fork deliberately avoided — but it is now the only route to the number.
3. **Pixmap bridge** — mpv renders via EGL into an `EGL_KHR_image_pixmap`-backed image, Skiko binds
   the same X Pixmap with `GLX_EXT_texture_from_pixmap`. Both extensions are present on this
   machine (checked via `glxinfo`/`eglinfo`). Zero-copy *and* keeps Compose compositing — but it is
   by far the most exotic idea in this investigation, driver-dependent, and unproven anywhere.

### Correction to the "still not proven" note above

The seam itself is **not** the five-hop reflection walk that section assumed.
`androidx.compose.ui.scene.skia.WindowSkiaLayerComponent.getHierarchyRoot()` returns
`org.jetbrains.skiko.SkiaLayer` **publicly**, and `SkiaLayer extends JComponent` — so it is an
ordinary recursive `Container.getComponents()` walk from `FrameWindowScope.window`, looking for
`instanceof SkiaLayer` (assert exactly one, so a popup/dialog scene layer cannot be silently
picked). Only the `getRedrawer$skiko()` → `contextHandler` → `getContext()` tail is reflection.

That makes option 1 cheap to build. It does not make it fast.

### The one rule that survives all three options

**`hwdec-current` is the only trustworthy signal.** It is what caught this, and it is what will
catch the next silent degradation. Whichever path gets built must assert it at runtime — not only
in a spike — because every failure mode here is silent: you still get correct video, just at 4–8×
the CPU.

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
