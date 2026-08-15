# vlcj 5 / VLC 4 Migration — Scoping Document (no implementation)

*Status: **costed, and not recommended as the first move.** The migration is small and well-bounded
on the Kotlin side, but it is gated on two pre-release dependencies, and a route that is already
scoped in [`MPV_SWAP_SCOPING.md`](MPV_SWAP_SCOPING.md) delivers the same measured CPU win today with
none of that risk.*
*Written 2026-08-15 against v0.3.4 (build 110), vlcj 4.8.2, system VLC 3.0.20.*

## Why this came up

Playing a 2160p HEVC REMUX pegged ~580% CPU on an i7-1255U. Diagnosis: **VLC 3 silently forces
`avcodec-hw=none` whenever the video output is `vmem`** — the VLCJ callback surface the fork uses.
`PlayerEngine.desktop.kt:54` builds `--avcodec-hw=any` correctly; libVLC discards it because
callback rendering cannot accept GPU surfaces. Every frame is therefore software-decoded (~500%),
with a further ~32% in the RV32→`ImageBitmap` conversion at line 385.

Proved with a one-variable A/B against the shipped jars — same process, same file, same
`--avcodec-hw=any`, only the video surface varied:

| video surface | libVLC module search |
| --- | --- |
| normal vout | `looking for hw decoder module matching "any"` |
| `vmem` (VLCJ `CallbackVideoSurface`) | `looking for hw decoder module matching "none"` |

This is not a bug in the fork's code and cannot be configured around. It needs a different playback
engine or a newer libVLC.

## The three options, measured

All three numbers are the **same 1080p HEVC clip on the same machine**, decoding *and* delivering
frames to CPU memory as BGRA/RV32 — i.e. including the GPU→CPU readback the Compose/Skia path
actually requires, not decode-and-discard.

| Route | CPU (user+sys) | Availability | Bundling |
| --- | --- | --- | --- |
| **Stay on VLC 3 / vlcj 4** (today) | **25.5 s** | shipping | `.deb` depends on system VLC; AppImage bundles VLC 3 (18 MB plugins) |
| **VLC 4 + vlcj 5** | **14.0 s** | ⚠ VLC 4 has no stable release; vlcj 5.0.0-M4 is a Feb-2025 milestone, "in limbo" pending VLC 4 | must bundle its own libvlc — **~44 MB** (42 MB plugins + 1.5 MB core, after `strip`; the nightly ships unstripped at 243 MB) |
| **libmpv `hwdec=auto-copy`** | **14.6 s** | ✅ **libmpv 0.37.0 already installed**, stock Ubuntu 24.04 | `.deb` gains `Depends: libmpv2` — **zero bundle**; AppImage would swap its VLC bundle for libmpv |

mpv matches VLC 4 within noise (14.6 vs 14.0 s) and needs **no pre-release dependency at all**.
Verified engaged, not silently falling back: `mpv -v` logs `Using hardware decoding (vaapi-copy)`.

VLC 4 does genuinely fix the root cause — it decouples the decoder device from the vout, ships
`codec/libvaapi_plugin.so` (the hw decoder this VLC 3 install lacks entirely) plus
`vaapi/libdecdev_vaapi_{drm,x11}`, and with `--vout=dummy` still logs
`using decoder device module "decdev_vaapi_x11"` → `Using Intel iHD driver` →
`Reinit context …, pix_fmt: vaapi`. Confirmed for h264 **and** HEVC.

## API delta: what actually breaks

The fork's vlcj surface is small — **2 files, 9 imports, ~19 call sites**
(`PlayerEngine.desktop.kt` and a 10-line `VlcjCheck.kt`). Checked class-by-class against
`vlcj-5.0.0-M4.jar` from Maven Central.

**Survives unchanged:** `MediaPlayerFactory`, `MediaPlayer`, `MediaPlayerEventAdapter`,
`CallbackMediaPlayerComponent`, `EmbeddedMediaPlayer`, `BufferFormat`, `BufferFormatCallback`,
`RenderCallback`, and the sub-APIs `media()`, `controls()`, `status()`, `events()`, plus
`audio().setVolume/setMute` and `subpictures().setSubTitleFile/setDelay`.

**Mechanical (additive interface methods):**

- `RV32BufferFormat(w, h)` → `StandardBufferFormat(w, h)` — RV32 is no longer its own class.
- `BufferFormatCallback` gains `newFormatSize(int, int, int, int)`.
- `RenderCallback` gains `lock(MediaPlayer)` / `unlock(MediaPlayer)`, and `display()` gains two
  trailing `int` parameters.

**The one real rewrite — track selection.** These are gone from vlcj 5:

- `audio().trackDescriptions()`, `audio().setTrack(int)`
- `subpictures().trackDescriptions()`, `subpictures().setTrack(int)`

They are replaced by VLC 4's tracklist model on a new `tracks()` API:
`audioTracks()` / `textTracks()` returning typed `AudioTrackList` / `TextTrackList`, with
`selectTrack(Track)`, `select(TrackType, String...)`, `deselect(TrackType...)` and
`selectedAudioTrack()` / `selectedTextTrack()`. Tracks are addressed by **String id**, not `int`
index, so the controller's track handling and any persisted track preferences need reworking.

⚠ This is a **feature win, but not a differentiator**: `MPV_SWAP_SCOPING.md` lists "no track
language codes" as a current VLCJ ceiling, and VLC 4's tracklist carries `lang`. mpv's `track-list`
property carries `lang` too. Both candidate routes fix it — it is a wash between them.

Also note `vlcj 5` raises the minimum to **Java 11** (already satisfied — the build targets 21).

## Effort estimate

| Work item | Size |
| --- | --- |
| Bump dependency, adapt the two callback objects + `StandardBufferFormat` | 0.5 day |
| Rewrite audio/subtitle track selection onto `tracks()` (String ids, persisted prefs) | 1–2 days |
| Bundle libvlc 4 in the `.deb` (drop `Depends:` system VLC, ship ~44 MB, set `VLC_PLUGIN_PATH`) | 1–2 days |
| Re-point `scripts/build-vlc-bundle.sh` at VLC 4 for the AppImage | 0.5–1 day |
| Testing across debrid/HLS/local, subtitle + audio track switching, seek, resume | 2–3 days |
| **Total** | **~5–8 focused days**, plus carrying two pre-release dependencies in a shipped product |

Smaller than the mpv swap's 8–12 days — but the mpv estimate buys subtitle styling, error reasons
and seek-state reporting as well, and does not require shipping unreleased software.

## Blockers, in order of severity

1. **VLC 4 has no stable release.** Linux nightlies are **snap-only**
   (`artifacts.videolan.org/vlc/nightly-snap/`); there is no deb/rpm nightly target. Ubuntu noble
   carries 3.0.20; Flathub carries 3.0.23. Shipping this means shipping a nightly build of a media
   engine to users.
2. **vlcj 5 is a milestone, not a release.** 5.0.0-M4 (Feb 2025) is the newest on Maven Central and
   the project describes itself as awaiting VLC 4's final release.
3. **Packaging inverts.** Today the `.deb` `Depends:` system VLC and ships no engine. A VLC 4 build
   must carry its own ~44 MB libvlc + plugins and set `VLC_PLUGIN_PATH`, on both artifacts. mpv's
   equivalent is a one-line `Depends: libmpv2`.

## The cheap adjacent move

The fork is on **vlcj 4.8.2**; **4.12.1** is current. Four minor versions of fixes, no VLC 4, no
track rewrite, no packaging change. It is **not** a CPU fix — the `vmem` → `avcodec-hw=none`
override is libVLC 3 behaviour and no vlcj version changes it — but it is close to free and should
not be conflated with this migration.

## Recommendation

1. **Now:** nothing engine-side. The CPU cost only appears on 4K HEVC; a 1080p stream is a fraction
   of it, and `stream_auto_play_timeout_seconds` currently auto-grabs the top-ranked 2160p source.
2. **When it is scheduled:** prefer the **mpv route** — same measured win, shipping dependency, zero
   bundle on Debian/Ubuntu, and it clears the subtitle-styling ceiling too. See
   [`MPV_SWAP_SCOPING.md`](MPV_SWAP_SCOPING.md).
3. **Revisit this document** when VLC 4.0.0 ships stable *and* lands in a distro the fork targets,
   *and* vlcj publishes a non-milestone 5.0.0. Until all three hold, this route ships pre-release
   software to users for a win mpv already delivers.

## Reproducing the measurements

```sh
# VLC 4 nightly without snapd — a .snap is squashfs
curl -O https://artifacts.videolan.org/vlc/nightly-snap/<date>/vlc_4.0.0-dev-*_amd64.snap
unsquashfs -d sq vlc_*.snap
export LD_LIBRARY_PATH=$PWD/sq/usr/lib:$PWD/sq/usr/lib/vlc   # 2nd path is required
export VLC_PLUGIN_PATH=$PWD/sq/usr/lib/vlc/plugins

# decode + readback to CPU memory, the comparison that matters
SOUT='#transcode{vcodec=RV32,acodec=none}:std{access=file,mux=raw,dst=/dev/null}'
/usr/bin/time -f "%Us %Ss" cvlc -I dummy --aout=dummy --avcodec-hw=any --play-and-exit clip.ts --sout "$SOUT" vlc://quit
/usr/bin/time -f "%Us %Ss" ./sq/usr/bin/vlc -I dummy --aout=dummy --avcodec-hw=any --play-and-exit clip.ts --sout "$SOUT" vlc://quit
/usr/bin/time -f "%Us %Ss" mpv --hwdec=auto-copy --vf=format=bgra --vo=null --no-audio --untimed clip.ts
```

⚠ `cvlc --vout=vmem` is **not** a valid control — vmem's callbacks are set through the libvlc API,
not the CLI, so `Open()` fails and VLC silently falls through to a normal vout. The `vmem` A/B at the
top of this document was done with a vlcj harness against the shipped jars, varying only the video
surface.
