# MPV Swap — Scoping Document (no implementation)

*Status: **feasibility proven, decision open.** The original call — stay on VLCJ until its
ceilings demonstrably hurt users — was made without knowing whether the proposed shape worked at
all. It does; see "Spike results" below. What remains is a scheduling decision, not a technical
unknown.*
*Written 2026-06-12 against v0.1.14 (build 83). Revisited 2026-08-12 against v0.3.2. Revised
2026-08-15 against v0.3.4.*

> ⚠⚠ **THE PLAN IS THE 2026-08-16b REVISION AT THE END. Everything before it is superseded.**
> libmpv restores hardware decoding, which VLCJ cannot reach at any version — that part holds
> throughout. But the two designs proposed in the body are both wrong: SW-render is worth only ~23%
> at 2160p, and the GPU-render design that replaced it assumed Compose's `DirectContext` could take
> mpv's texture — which caps at `vaapi-copy`, because Skiko is GLX and mpv's zero-copy VA-API is
> EGL-only.
>
> **The route that works: drive the shipped Skiko on EGL via `makeGLWithInterface`.** No forked
> skiko, no separate window, no product change. Whole chain measured — Skia rasterises on EGL,
> mpv reports `hwdec-current=vaapi` at 4K on the same context, Skia adopts and draws its texture,
> `RenderFactory` injection into Compose's own `SkiaLayer` fires, and **the EGL `Redrawer` itself now
> renders a real window, resize and GLX-fallback included** (revision 2026-08-16c). Nothing in the
> design is unproven; what remains is implementation — wiring it into the app, the libmpv binding,
> a sustained interleaved soak, and packaging. Earlier sections are kept for their measurements and
> reasoning trail, not as a plan.

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

## Follow-up 2026-08-16 — "can we get the fast tier without a separate window?"

### First, the door is properly closed, not just closed for VA-API

On a GLX context mpv gets **no** zero-copy interop from *any* backend. Same harness, same file:

| `hwdec` requested | `hwdec-current` on GLX |
| --- | --- |
| `auto` | `vaapi-copy` |
| `vaapi` | `no` |
| `vdpau` | `no` |
| `drm` | `no` |

`hwdec=auto` is the authoritative one: mpv knows exactly which interops it can do against the
current context, and on GLX it concludes copy is the best available. So 15.34 s is a hard floor for
*anything* rendered through Skiko's context as shipped — this is not a matter of picking a better
hwdec.

Skiko 0.144.6 also has exactly two Linux redrawers — `LinuxOpenGLRedrawer` (GLX) and
`LinuxSoftwareRedrawer`. No EGL, no Vulkan. There is no configuration that changes this.

### But a separate window is not the only escape

The constraint is "Compose renders on GLX", not "video must live in its own window". Three ways to
break it, none of them cheap:

**(a) Put Compose itself on EGL.** If Skiko used EGL, mpv shares the context, zero-copy works, and
everything composites normally — no separate window, and the `SkiaLayer` seam above gives us the
`DirectContext`. This is not hypothetical:
  - `JetBrains/skiko#918` — Jake Wharton reports having EGL working in a fork; the change is
    `skia_use_egl=true` in skia-pack plus a `makeEGL()` beside `makeGL()`. Issue is closed with no
    visible maintainer decision either way.
  - `silenium-dev/skiko` — a fork that uses EGL/GLES instead of GLX/desktop GL on Linux, feeding
    `silenium-dev/compose-gl` (render GL content into a composable, `dev.silenium.compose.gl`) and
    `silenium-dev/mpv-kt` (KMP libmpv wrapper with a Compose `VideoSurface`, GPL-3.0, v0.1.0, very
    early — 4 stars).
  - ⚠ Cost: swapping the renderer under the **whole app**, not just video. `libskiko` becomes a
    third-party native artifact (or a full Skia build we own). Touches every pixel drawn, plus .deb
    and AppImage packaging. Unverified here.

**(b) X Pixmap bridge.** mpv on its own EGL context renders into an `EGL_KHR_image_pixmap`-backed
pixmap; Skiko (GLX) binds the same pixmap via `GLX_EXT_texture_from_pixmap`. Both extensions
confirmed present on this machine. Zero-copy, keeps compositing, no separate window, no forked
skiko. Sync between the two contexts is the risk, and it is driver-dependent and unproven.

**(c) Custom `Redrawer` via `RenderFactory`.** `SkiaLayer`'s constructor takes a `RenderFactory`,
and Skia's `DirectContext.makeGL()` wraps *whatever GL context is current* — it does not care
whether GLX or EGL created it. So an EGL redrawer written in Kotlin/JNA would work in principle
without forking skiko's native side. Blockers: Compose builds its `SkiaLayer` internally and never
lets us pass a `RenderFactory` (`RenderFactory.Companion.Default` is a private static final set from
`makeDefaultRenderFactory()`), and we would need JAWT for the X11 display/window. Essentially (a) in
Kotlin rather than C++. This one is untested speculation, listed for completeness.

### What is definitively impossible

- mpv doing zero-copy into a GLX context. Closed, measured above.
- Sharing GL objects between an EGL context and a GLX context. No cross-API share groups.

## ✅ Revision 2026-08-16b — the EGL route works on the SHIPPED skiko. No fork, no separate window.

The revision above concluded the Skia seam tops out at `vaapi-copy` because Skiko is GLX, leaving
only "mpv owns its own window" (visible product change) or "replace skiko with an EGL fork" (own a
third-party native library). **Both were wrong.** The shipped `libskiko` can be driven on EGL.

### Why it was thought impossible, and why it isn't

Skia's `GrGLMakeNativeInterface()` on Linux is the GLX implementation, and it opens with
`if (!glXGetCurrentContext()) return nullptr;`. **That check** — not anything about Skia's GL
backend, which is API-agnostic — is what forces GLX. It is also why the known workaround is a fork
built with `skia_use_egl=true`.

But the shipped skiko also exposes:

```kotlin
GLAssembledInterface.createFromNativePointers(ctx, getProcFnPtr)
DirectContext.makeGLWithInterface(iface)
```

which assemble the GL interface from a **caller-supplied** proc-address function and never consult
GLX. Hand it an `eglGetProcAddress` adapter and Skia rasterises on an EGL context.

⚠ The adapter is required, not optional: Skia's getter is `GrGLFuncPtr(*)(void* ctx, const char*)`
but `eglGetProcAddress` takes only the name, so passing it directly reads `ctx` as the symbol name.

### The chain, all measured

| # | Claim | Evidence |
| --- | --- | --- |
| 1 | Compose's `SkiaLayer` is reachable | `WindowSkiaLayerComponent.getHierarchyRoot()` is public and returns `SkiaLayer`; `SkiaLayer extends JComponent`, so an AWT container walk also finds it |
| 2 | Its `RenderFactory` is injectable | `renderFactory` is `private final` but read **lazily** at `createRedrawer()` time → `RenderFactoryInjectSpike.kt` — `ok=true renderApi=OPENGL` |
| 3 | Shipped skiko rasterises on EGL | `EglSkiaSpike.kt` — pixel-exact readback `0xFF3FA9C8` |
| 4 | mpv gets zero-copy on that same context | `EglSkiaMpvSpike.kt` — `hwdec-current=vaapi` at 3840×2160 |
| 5 | Frames are real video, not black | mean luma 16 (≠ 0) on the real DV REMUX |
| 6 | Skia adopts and draws mpv's texture | `adoptTextureFrom` + `drawImage`, sampled non-black |
| 7 | Skia draws correctly *after* mpv has rendered | `0xFF3FA9C8` again — ⚠ **one alternation, not a loop**; see caveat below |

1.78 s CPU for 60 frames at 4K including JVM startup — the fast tier, with Skia compositing on top
and **no separate window**.

### Three traps that cost time here, and will again

1. ⚠ **libmpv refuses to start under a non-C `LC_NUMERIC`, and the JVM sets a locale.** `mpv_create()`
   fails with no useful diagnostic from Java. Any JNA binding must `setlocale(LC_NUMERIC, "C")`
   first. This is not optional and not obvious.
2. ⚠ **A pixel assert on unseeked media reports false failures.** The first 4K run "failed" at mean
   luma 0 — that was the film's opening titles, not a decode failure. Hence `SPIKE_START`.
3. ⚠ **`RenderFactory` and `Redrawer` are Kotlin `internal`** — public in bytecode, unnameable from
   Kotlin source outside skiko's module. The EGL `Redrawer` needs a Java shim (see
   `RenderFactorySpy.java`) or `-Xfriend-paths`. Relatedly, Kotlin's all-defaults constructors are
   `ACC_SYNTHETIC`, so javac silently resolves `new SkiaLayer(...)` to the wrong overload.

### What is left — implementation, not unknowns

- **An EGL `Redrawer`** (Java shim): JAWT for the X11 display/window, `eglCreateWindowSurface`,
  make-current, `DirectContext` via `makeGLWithInterface`, Skia surface on the default framebuffer,
  `eglSwapBuffers`, plus frame dispatch, vsync, resize and transparency. ⚠ **This is the one piece
  with no spike behind it** — reimplementing `LinuxOpenGLRedrawer`'s lifecycle is real work.
  Everything else on this list is already proven or ordinary.

  The specific risk is that **every spike here used `eglMakeCurrent(…, EGL_NO_SURFACE, …)` against
  an FBO.** A Redrawer instead needs a *window* surface, which requires an `EGLConfig` with
  `EGL_WINDOW_BIT` whose `EGL_NATIVE_VISUAL_ID` matches the visual AWT actually gives the component.
  That is a different configuration and none of the spikes exercise it — so it was checked directly
  (`scripts/spikes/eglvisual.c`):

  ```
  X default visual id=0x21 depth=24
  window-capable OpenGL configs: 64
    cfg[6] visual=0x21 alpha=0 depth=24 stencil=8
  RESULT egl-window-config ok=true count=64 matches-x-default-visual=1 has-alpha-config=1
  ```

  A config matching the X default visual exists **with depth 24 + stencil 8** (what Skia wants), and
  alpha configs exist for transparent/undecorated windows. So the window-surface half is plumbing,
  not a wall. What remains genuinely unproven is JAWT lock/unlock discipline on the EDT and the
  frame-dispatch/vsync/resize lifecycle.

### ⚠ Caveat on assertion 7 — coexistence is proven for one alternation, not a render loop

`EglSkiaMpvSpike.kt` renders 60 mpv frames, *then* draws with Skia twice. The real pattern is
interleaved every frame, for hours, with Skia's resource cache warm and mpv reallocating on
resolution changes. `resetGLAll()` every frame is also not free. Nothing here suggests a problem —
but "state not clobbered" is stronger than one alternation supports, and a sustained interleaved
soak is worth doing before trusting it in the player.
- The JNA libmpv binding and `MpvPlayerController` — as originally scoped (~8–12 days).
- **Fallbacks stay mandatory.** If EGL or the injection fails on a given machine, the app must fall
  back to stock GLX Skiko plus the existing buffer-copy video path. Ship both.
- Packaging: `libmpv2` for the deb, bundling for the AppImage.

### The rule that still holds

**Assert `hwdec-current == "vaapi"` at runtime.** Every failure mode in this whole investigation was
silent — correct video at 4–8× the CPU. It is the only trustworthy signal.

## ✅ 2026-08-16c — the EGL Redrawer works. Nothing in the plan is unproven now.

The revision above listed one piece with no spike behind it. It now exists and renders:
`scripts/spikes/EglRedrawerSpike.kt` (+ `EglRedrawerSpikeMain.kt`) drives a real `SkiaLayer` in a
real window through a real skiko `Redrawer` backed by an EGL window surface.

```
PASS  egl-redrawer-used         redrawer=EglRedrawer
PASS  renders-frames            frames=5
PASS  render-info               EGL/OpenGL visual=0x21 stencil=8
PASS  top-left-colour           0xFF3FA9C8
PASS  bottom-right-colour       0xFFC83F7A
PASS  renders-after-resize      5 -> 10
PASS  resize-size-propagated    900x592
PASS  top-left/bottom-right-after-resize
RESULT egl-redrawer ok=true
```

And with `NUVIO_EGL_FORCE_FAIL=1`, stock skiko takes over and still renders
(`redrawer=LinuxOpenGLRedrawer`). That path is tested deliberately because it will otherwise only
ever run on machines we cannot reproduce.

### ⚠⚠ Correction: skiko renders on the EDT

This document previously assumed a dedicated render thread ("never the EDT"). **That is wrong.**
`AWTRedrawer.inDrawScope` hard-asserts `SwingUtilities.isEventDispatchThread()`, and skiko's frame
dispatcher runs on `MainUIDispatcher`. A render thread fails with *"Method should be called from AWT
event dispatch thread"*.

Consequence for the mpv work: an EGL context is current on exactly one thread, so **the EDT is the
context thread, and `mpv_render_context_render` has to be called there too.** Budget for that — it
means the video render shares a thread with UI event handling. The measured GPU render cost
(1.78 s CPU per 60 frames at 4K) is what makes that acceptable.

### Five more traps, none of them guessable

1. **`Redrawer.update(nanoTime)` must be called before drawing.** `SkiaLayer` records the frame into
   a `Picture`, and *that* is what invokes the `SkikoRenderDelegate`. Skip it and the context and
   surface build perfectly and nothing is ever drawn — no error, just a black window.
2. `onDeviceChosen()` must precede any frame; `inDrawScope` asserts on it.
3. **`eglGetPlatformDisplayEXT` is an extension, not an exported symbol** — JNA cannot bind it
   directly (`UnsatisfiedLinkError`); fetch it via `eglGetProcAddress` and call the raw pointer.
4. **`XWindowAttributes.visual` is at offset 24, not 32.** Guessing the offset segfaults the JVM
   inside `XVisualIDFromVisual` instead of failing cleanly. Use JNA-platform's real struct.
5. ⚠ **`@Suppress("INVISIBLE_REFERENCE")` does not cover *receiver* resolution** for Kotlin-internal
   types. It compiles and silently emits `checkcast error/NonExistentClass`, failing only at
   runtime. The JAWT access therefore goes through reflection rather than naming `HardwareLayer`.

Also confirmed on the way: Kotlin can reach skiko's internals with
`@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")` plus marking our own classes `internal`
— **no Java source set, no `-Xfriend-paths`, no `java.lang.reflect.Proxy`.** And the app already
ships `--add-opens=java.base/sun.misc=ALL-UNNAMED` and the `jdk.unsupported` module (VLCJ needs
Unsafe for its native video buffers), so the global `RenderFactory` hook needs no packaging change.

### Where the plan stands

| Piece | State |
| --- | --- |
| Skia on EGL, shipped skiko | ✅ proven |
| mpv zero-copy `vaapi` on that context | ✅ proven at 4K |
| Skia adopts + draws mpv's texture | ✅ proven |
| `RenderFactory` global hook | ✅ proven |
| EGL `Redrawer` incl. resize + fallback | ✅ proven |
| Wiring into the app behind a flag | not started |
| JNA libmpv binding + `MpvPlayerController` | not started (~8–12 days, as scoped) |
| Sustained interleaved Skia/mpv soak | ⚠ still owed |
| Packaging (`libmpv2` dep, AppImage bundling) | not started |

**No native artifact is needed anywhere** — the EGL path is pure JNA, which VLCJ already pulls in.

---

## ✅ Revision 2026-08-17 — the binding, the controller, and the soak are done

Three of the six remaining items are finished and verified. **Nothing in the design is unproven
any more**, including the one item that was still owed.

### The sustained interleaved soak — GREEN, and it was the last real unknown

The previous coexistence proof was 60 mpv frames *then* two Skia draws: **one** alternation. The
player interleaves every frame for hours with `resetGLAll()` in between, so that proved much less
than it appeared to. `scripts/spikes/EglSkiaMpvSoak.kt` now runs the real pattern — mpv renders a
frame, Skia adopts the texture, draws it, draws a rotating probe rectangle, and the probe is read
back and verified every iteration.

**30,000 alternations at 3840×2160 with `hwdec-current=vaapi` asserted before the soak began:
0 probe failures, 0 GL errors, 0 black frames, ~65 alternations/sec.** Skia's cached GL state
survives sustained interleaving with mpv.

Two findings that only a sustained run could produce:

- ⚠ **`Image` and `BackendTexture` must be `close()`d every frame.** They are native handles behind
  a Cleaner. Letting GC reclaim them leaked ~1 MB per 1000 iterations at 4K — about 330 MB over a
  two-hour film. This is mandatory in the render path, not a nicety.
- ⚠ **RSS is not evidence of a leak on its own.** The run looks like it grows ~55 MB until a GC,
  after which only **18 MB** is retained across 30,000 4K frames — consistent with Skia's bounded
  resource cache plus JIT. Always measure after a collection; heap is reclaimed under pressure,
  native texture memory is not.

### libmpv JNA binding — done

`composeApp/src/desktopMain/kotlin/com/nuvio/app/desktop/mpv/LibMpv.kt` + `MpvHandle.kt`. No native
artifact anywhere; JNA is already pulled in by VLCJ. 19/19 green in `MpvBindingSpike.kt` against
libmpv 2.2.0 — event thread, property observation, typed get/set, seeking, teardown.

Pinned in code so they cannot be rediscovered the hard way: `setlocale(LC_NUMERIC, "C")` before the
library loads (the JVM sets a locale and `mpv_create()` then fails with no diagnostic reaching
Java), and `dispose()` stopping the event thread *before* `mpv_terminate_destroy` (destroying a
handle another thread is blocked in `mpv_wait_event` on is a use-after-free).

### `MpvPlayerController` — done

All 15 `PlayerEngineController` methods, plus `MpvEngineOptions` as the mpv counterpart of
`getVlcjFactory()`. 24/24 green in `MpvControllerSpike.kt` against real H.264.

- ⚠ **`loadfile` takes per-file settings as PROPERTIES, not as its trailing options argument.**
  mpv 0.38 inserted an `<index>` parameter before `<options>`, so a positional options string is
  rejected on 0.37 ("invalid parameter") and misread elsewhere. The file silently never loads.
- ⚠ **Request headers are percent-escaped** with mpv's `%<byte-length>%<item>` form. Header values
  legitimately contain commas, which is the list separator, so one `Cookie` header would corrupt
  every header after it. Unlike libVLC — which could only ever send user-agent and referer — mpv
  sends arbitrary headers, so header-protected sources that failed on VLCJ can work here.
- `start` is reset to `"none"` on every load, or the next video resumes at the previous one's
  position. `sub-delay` is in **seconds**. Tracks are read via indexed `track-list/N/…`
  sub-properties, so there is no JSON parsing and no exposure to schema drift.
- libVLC's seek-settle tolerance heuristic is **gone**: mpv reports `PLAYBACK_RESTART` when a seek
  lands, so the optimistic target is held until then and nothing has to be guessed.
- ⚠ **No `onSnapshot` callback, deliberately.** Snapshot handling spawns the screensaver-inhibitor
  process, updates Discord and writes Compose state; driving that from mpv's event thread would run
  it off the main thread at mpv's cadence. Callers poll `currentSnapshot()`, which is a field read.

### ⚠ Two traps for whoever writes the render path

- **`MPV_RENDER_PARAM_SW_STRIDE` is a `size_t*`** — 64-bit on x86_64. An `IntByReference` yields
  garbage strides that look like corrupted video, not an error.
- **`arrayOf(MpvRenderParamStruct(...), …)` is not a contiguous C array.** Use `Structure.toArray(n)`
  on one instance and fill the slots, or mpv reads the terminator immediately and sees no params.

### Status

| Piece | State |
| --- | --- |
| EGL `Redrawer` on shipped skiko, wired behind `NUVIO_EGL=1` | ✅ done, verified live |
| JNA libmpv binding | ✅ done, 19/19 |
| `MpvPlayerController` + `MpvEngineOptions` | ✅ done, 24/24 |
| Sustained interleaved Skia/mpv soak | ✅ done, 30k alternations at 4K clean |
| SOFTWARE render path (`MPV_RENDER_API_TYPE_SW`) into the existing buffer pipeline | not started |
| GPU render path (`MPV_RENDER_API_TYPE_OPENGL`) on the EGL context | not started |
| Shared `handleSnapshot` extraction so both engines keep the side effects | not started |
| Packaging (`libmpv2` dep, AppImage bundling) | not started |

**Build the SOFTWARE path first.** It is the mandatory fallback either way, it drops into the
existing `ByteBuffer → Image.makeRaster → Canvas` path nearly 1:1, and `mpv_render_context_render`
with SW params needs no GL context — so **none of the EDT constraint applies** and the controller
wiring, snapshot side effects and packaging can all be validated without touching the Skia seam.
Then the GPU path is a render-backend swap on a player already proven end to end.

⚠ Before writing either surface, **extract `handleSnapshot` (`PlayerEngine.desktop.kt:298`) into
something both engines call.** All the non-playback behaviour lives there — screensaver inhibit,
Discord presence, `PlayerControlBridge` position/duration for MPRIS, the `nearEnd` flush-frame
arming — plus `LastFrameStore.update` at line 394 feeds the screenshot hotkey. A parallel mpv
surface would silently lose every one of them, and no spike can see that.

---

## Revision 2026-08-19 — the surface is wired, and measured against VLCJ

The libmpv engine now runs **inside the app**, opt-in via `NUVIO_MPV=1`. `PlatformPlayerSurface`
picks an engine once per surface: mpv when the flag is set and it actually starts, VLCJ otherwise
and whenever mpv cannot start (missing library, `mpv_initialize` refusing, no render context). The
VLCJ body is unchanged — only moved behind the dispatcher into `VlcjPlayerSurface`.

### ⚠ RETRACTED: the first CPU A/B was not like-for-like

The first measurement of this path (VLCJ 383% CPU / 6.37 GB RSS vs mpv 183% / 1.60 GB on the same
4K HEVC remux at 1920x1111) is **not a fair comparison and must not be quoted.** Instrumenting the
frame pump showed the mpv path was rendering **7.2 fps against a 24 fps source**, with mpv dropping
~14 frames/s and `avsync` growing ~0.5s every 5s — audio ended up seconds ahead of picture, which
ben heard before the instrumentation caught it. It used less CPU because it was doing less work.

The RSS figures stand on their own (1.6 GB vs 6.37 GB), and the 4K decode really is on the GPU
(`hwdec-current=vaapi-copy`). The CPU claim has to be re-made on the GPU path.

### The SOFTWARE path does not keep up with 4K, and the scaler is not why

Measured with `run-mpv-swrender-spike.sh` (`RENDER_W`/`RENDER_H` select the output size):

| Source | Output | Render fps | Per render call |
| --- | --- | --- | --- |
| 4K, 30fps | 1920x1111 | 19.8 | 31.3 ms |
| 4K, 30fps | 960x540 | 18.8 | 31.3 ms |
| 1080p, 30fps | 1920x1111 | **28.7** | 31.6 ms |

Per-call cost is identical at 1920x1111 and 960x540, so **the CPU scale is not the bottleneck** —
`sws-fast` and `fast-bilinear` were measured and changed nothing, which is why they are deliberately
not set. What tracks the failure is the *source* size: it is the `vaapi-copy` readback of 4K frames.
1080p is comfortable; 4K is not. The GPU path never reads the frame back, which is the fix.

⚠ **Every signalled frame must be RENDERED, even when it will not be delivered to Compose.** mpv
advances one frame per `mpv_render_context_render` call, so deferring a render does not skip a
frame — it delays the entire video stream, permanently. The original 30fps throttle deferred
renders and so guaranteed drift; it now throttles delivery only. (This alone did not rescue 4K,
because the per-frame cost is the readback, but on 1080p it is the difference between correct and
slowly drifting.)

### Verified in the real app

Timeline and duration, seek (±10s), pause holding the frame, resume-from-position, the source
badge, Continue Watching, the controls overlay, and the "S" screenshot hotkey — driven through the
UI, not a spike. The screenshot needed an engine-side fix: mpv scales to the window while decoding,
so `LastFrameStore` holds a window-sized frame and a 4K film was being saved at 1080p. libmpv now
writes it itself via `screenshot-to-file` (`DesktopScreenshot.engineCapture`) — verified 3840x2160.

### ⚠ Subtitle defaults differ, and it is not an mpv bug

On the mpv path, embedded subtitles that VLCJ plays by default are **off**. The cause is in shared
code, not in either engine:

`PlayerScreenRuntimeTrackActions.kt` turns subtitles off when the preferred-subtitle-language
targets are empty (the default, `SubtitleLanguageOption.NONE`) **and** the engine reports a track as
selected. `VlcjPlayerController.getSubtitleTracks()` hardcodes `isSelected = false` for every track,
so that branch has never fired on desktop and libVLC's default subtitle survives by accident.
`MpvPlayerController` reports selection honestly, so the branch fires.

Confirmed causally, not inferred — the app log shows mpv's own choice and then the override:
```
NuvioMpvController: file loaded, hwdec-current=vaapi-copy paused=false sid=1 aid=1
NuvioMpvController: selectSubtitleTrack(-1)
```
⚠ **Scope is NOT established.** Code reading says an install with a non-default preferred subtitle
language never reaches that call, so it would only affect installs left on the default — but the
attempt to test that failed (the settings file is rewritten at startup, so hand-editing the profile
key does not take; it has to be set through the UI). Treat the scope as unverified.

So mpv's behaviour is what the setting actually asks for, and matches what ExoPlayer would do; the
current desktop behaviour is a side effect of a stub. **This is a product decision, not a fix** —
either honour the setting (subtitles off unless a preferred language is set) or reproduce VLCJ's
accidental default. It must be decided before the mpv path becomes the default.

### Status

| Piece | State |
| --- | --- |
| EGL `Redrawer` on shipped skiko, wired behind `NUVIO_EGL=1` | ✅ done, verified live |
| JNA libmpv binding | ✅ done, 19/19 |
| `MpvPlayerController` + `MpvEngineOptions` | ✅ done, 24/24 |
| Sustained interleaved Skia/mpv soak | ✅ done, 30k alternations at 4K clean |
| Shared `handleSnapshot` extraction (`DesktopPlaybackSideEffects`) | ✅ done, verified in the app |
| SOFTWARE render path (`MPV_RENDER_API_TYPE_SW`) | ✅ done, 11/11 |
| `MpvSession` + `PlatformPlayerSurface` wiring behind `NUVIO_MPV=1` | ✅ done, 17/17 + real-app run |
| Source-resolution screenshots on the mpv path | ✅ done, verified 3840x2160 |
| SOFTWARE path at 4K | ❌ **does not keep up** (19.8 fps vs 24) — 1080p is fine |
| Subtitle-default decision (above) | ⏳ needs a product call |
| **GPU render path (`MPV_RENDER_API_TYPE_OPENGL`) on the EGL context** | **not started — now the critical path** |
| Packaging (`libmpv2` dep, AppImage bundling) | not started |

---

## Revision 2026-08-19b — the GPU path works, and it is the whole point

`hwdec-current=vaapi` — zero-copy — **inside the app**, drawing into Compose's own Skia context.
Same 4K HEVC remux, same window (1920×1111), same machine, 30s of steady playback:

| Engine / path | CPU | RSS |
| --- | --- | --- |
| VLCJ (shipping) | 383% | 6.37 GB |
| libmpv, SOFTWARE render (`vaapi-copy`) | 183%, **but only rendering 7 of every 24 frames** | 1.60 GB |
| **libmpv, GPU render (`vaapi`)** | **26.6%** | **1.11 GB** |

Unlike the software figure, this one is like-for-like — **verified by counting presented frames,
not by watching the clock**: 24.0 presented fps against a 24 fps source, `dropped=0`, `delayed=0`,
`avsync=0.000`, sustained. ⚠ Real-time timeline advance proves nothing on its own: it is driven by
the audio clock, and the software path advanced it perfectly while presenting 7 frames in 24.

Window resize during playback is also verified (1920x1111 → 1280x699 → 1600x879): the
texture/FBO/Image set is rebuilt with no artefacts and no change in pacing.

### How it fits together

- `EglRedrawer` already builds Compose's EGL context; `EglSeam` publishes the Skia `DirectContext`
  and the X11 display so mpv can share them. No second context — that would mean a copy.
- `Gl` resolves the few GL entry points through `eglGetProcAddress`, never by `dlopen`ing libGL,
  which is how a process ends up back on GLX's dispatch table.
- The mpv render context is created **on the first draw**, not at session creation: it probes the
  live GL context, so it needs the EDT *and* a current context, and only the draw scope has both.
- The tier is chosen from `EglRenderer.isActive`. On GLX `hwdec=vaapi` degrades to software with no
  error, so when EGL is not live the session stays on the software renderer.

### ⚠ Two GL-lifetime traps, both of which look like something else

- **`Image.adoptTextureFrom` means ADOPT.** Skia takes ownership and deletes the GL texture when
  the image is closed. Wrapping mpv's texture fresh every frame and closing the previous image
  destroys the texture mpv is still rendering into; Skia then hands the recycled id to the UI. It
  presents as **wrong colours and a corrupted overlay**, not as a use-after-free, and it cost two
  builds chasing a non-existent channel-order bug. Adopt once per texture, close on resize/teardown.
- **mpv leaves its own framebuffer bound after rendering.** Everything Compose draws next lands in
  the video texture instead of the window. Rebind the default framebuffer after every render.

Also: nothing may be closed inside the draw scope. Compose draws into a **recording canvas** that
skiko replays afterwards, so a handle freed in `drawBehind` is freed before it is used.

### Subtitles: matched to VLCJ, on purpose

`MpvPlayerController.getSubtitleTracks()` now reports `isSelected = false` for every track,
mirroring `VlcjPlayerController`'s stub deliberately. Reporting honestly made the app's "no
preferred subtitle language" branch disable the track mpv had auto-selected, which silently turned
embedded subtitles off on default installs. Nothing in the UI reads the flag — `SubtitleModal`
marks the active row from the screen's own `selectedIndex`.

### Status

| Piece | State |
| --- | --- |
| **GPU render path on the EGL context** | ✅ **done — `vaapi`, 26.6% CPU on 4K in the app** |
| SOFTWARE render path | ✅ done — fine at 1080p, cannot keep up with 4K (the fallback) |
| Subtitle default | ✅ matched to VLCJ |
| Source-resolution screenshots on the mpv path | ✅ done |
| Packaging (`libmpv2` dep, AppImage bundling) | not started |
| Window resize on the GPU path | ✅ verified |
| End of episode on the GPU path (`frozen`/`nearEnd` are software-pump only) | ⚠ not tested |
| Long soak of the in-app GPU path (leaks over a film, engine switching) | not started |
| Making either flag the default | not started — both still opt-in |
