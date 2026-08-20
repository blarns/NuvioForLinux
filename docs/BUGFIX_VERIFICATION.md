# Bug-fix verification — instructions for a session that can run the app

**Who this is for:** a Claude Code session (or a human) on a real Linux desktop, with a display,
VLC installed, and a normal Nuvio profile with addons already set up. It is written to be
self-contained — assume the reader has none of the context that produced the fixes.

**Why it exists:** the cloud agent that wrote these fixes has no X11 session, no addons, and no
way to play a video. It verified that the code compiles and that 251 unit tests pass; it verified
nothing behavioural. Every fix below is reasoned from the source, not observed. This protocol is
the missing half.

**Branch under test:** `claude/nuvioforlinux-bug-sweep-gp975p`
**Base it was cut from:** `f589c11` (v0.3.5, build 111)

---

## How to report results

Write **`docs/BUGFIX_VERIFICATION_RESULTS.md`**, commit it, and push it to
**`claude/nuvioforlinux-bug-sweep-gp975p`** — the branch these fixes are on. Do **not** push to
`cmp-rewrite`: that branch is release-only and every installed updater resolves against it.

Append a new run rather than overwriting an old one. Use exactly this shape; the `ID` values are
how results map back to checks.

```markdown
## Run: <YYYY-MM-DD> — bug-sweep branch

- Commit under test: <short sha>
- Build: <from source | deb | AppImage>
- Distro / DE / session: <e.g. Linux Mint 22, Cinnamon 6.0, X11>
- Addons installed: <count, and whether any are known-slow or known-dead>
- Debrid configured: <yes/no>   P2P enabled: <yes/no>   NUVIO_MPV: <unset | 1>

| ID | Result | Notes |
|----|--------|-------|
| A-01 | PASS | |
| SW-01 | FAIL | still spins; log shows … |
| CW-01 | BLOCKED | could not make a fetch fail on demand |

**Verdict:** <one paragraph: are the three reported bugs actually gone, and is anything worse
than before?>
```

`BLOCKED` is a perfectly good answer and more useful than a guessed PASS. Several checks below
need a failure to be induced, and if that is impractical, say so.

---

## Setup

```bash
git fetch origin claude/nuvioforlinux-bug-sweep-gp975p
git checkout claude/nuvioforlinux-bug-sweep-gp975p
```

**Run from a terminal, not the launcher.** Almost every check below is confirmed or denied by a
line on stdout, and a launcher-started app discards it.

```bash
./gradlew composeApp:run 2>&1 | tee /tmp/nuvio-verify.log
```

Keep that log. If a check fails, the log is the report.

---

## Tier 1 — automated

Run these first. If either fails, stop and report; nothing below is meaningful.

| ID | Check | Command | Pass |
|----|-------|---------|------|
| A-01 | JVM target compiles | `./gradlew composeApp:compileKotlinJvm` | `BUILD SUCCESSFUL` |
| A-02 | Unit tests | `./gradlew composeApp:jvmTest` | `BUILD SUCCESSFUL`, 251 tests, 0 failures |
| A-03 | Deb packages (optional) | `./gradlew composeApp:packageDeb` | a `.deb` under `composeApp/build/compose/binaries/main/deb/` |

A-03 is worth running once because `patchDebRecommends` shells out to `dpkg-deb`, which nothing
else exercises. Skip it if `dpkg-deb` is unavailable.

---

## Tier 2 — the three bugs that were reported

These are the reason the branch exists. Everything else is secondary.

### SW-01 — Next episode brings up sources

**Was:** pressing next-episode spun forever; no sources ever appeared.

1. Play any episode of a series and let it reach the end (or seek to the last ~30s).
2. When the next-episode prompt appears, take it.
3. Wait up to 60 seconds.

**PASS:** the stream list populates, or every source reports a real error / "Timed out". Either
is fine — the bug was a spinner that resolved to *nothing*, forever.
**FAIL:** any source still shows a spinner after ~60s. Grab the tail of the log.

> The new bound is 45s per source, so a slow addon can legitimately take that long before it is
> marked timed out. Anything still spinning past ~60s is the bug.

### SW-02 — The same title works again from the home page

**Was:** after SW-01 hung, going home and picking the show hung again, forever, until restart.

1. Do SW-01. Whatever it does, go back to the home page.
2. Pick the same show and open its stream list again.

**PASS:** it loads (or errors) again. Specifically: it must actually *try* — a second hang is
only a fail if nothing is being fetched.
**FAIL:** instant spinner with no network activity and no new log lines. That is the reload guard
still short-circuiting.

Log line worth grepping: `Skipping stream reload for unchanged request`. Seeing it once for a
title that already loaded is correct. Seeing it while the screen shows a spinner is the bug.

### SW-03 — A dead source cannot take the list down

**Was:** one throw anywhere in the fan-out silently killed the whole load.

Easiest way to induce: install an addon whose URL points at something unreachable (a manifest URL
on a port nothing listens on works), then open any stream list.

**PASS:** the dead addon's row shows an error; every other addon and scraper still populates.
**FAIL:** other rows spin forever alongside it.

### CW-01 — A show does not vanish from Continue Watching

**Was:** finishing an episode, then returning home, removed the show entirely.

1. Finish an episode of a series that has a next episode.
2. Return to the home page.

**PASS:** the show is still in Continue Watching, showing the next episode.
**FAIL:** the show is gone.

If you can induce a metadata failure (disconnect the network briefly right as you return home,
or point the metadata addon at a dead URL), that is the stronger version of this check: the row
should *stay put* rather than disappear, and recover on a later pass.

### NAV-01 — Back cannot blank the app

**Was:** pressing back enough times left a white window, restart-only.

1. Navigate deep: home → show → episode → stream list → player.
2. Press back repeatedly — more times than there are screens. Ten or so.

**PASS:** you land on the home page and stay there. Further presses do nothing.
**FAIL:** blank/white window.

Worth noting separately if back ever **skips a screen** (two screens back from one press). That is
a known remaining issue, not a regression — the fix stops it emptying the stack, it does not stop
the double-pop. Report it as a note on NAV-01 rather than a FAIL.

### REG-01 — Normal playback is unchanged

The whole point of Tier 2 is worthless if ordinary use broke.

Play a movie and an episode end-to-end: seek forward and back, pause and resume, switch audio and
subtitle tracks, change source mid-playback, let one auto-play to the next episode, and check
resume-from-position after leaving and re-entering.

**PASS:** all of it behaves as it did on 0.3.5.
**FAIL:** anything at all — describe it precisely, this is the most important row in the table.

---

## Tier 3 — the rest of the sweep

Lower priority. Run what is practical.

| ID | Check | How | Pass |
|----|-------|-----|------|
| DL-01 | Downloads are not disk-bound | Download something large; watch `iostat` or just the throughput | Progress updates ~2×/sec, throughput is network-bound, not a stutter per 8 KB |
| DL-02 | Resume still works | Start a download, quit mid-way, relaunch, resume | Completes, and the finished file plays end-to-end |
| DL-03 | A changed source restarts cleanly | Resume a download whose URL has since expired/rotated | Restarts from zero rather than producing a corrupt file |
| ST-01 | Settings survive a hard kill | Change a setting, then `kill -9` the app immediately; relaunch | Setting is either the old or the new value — never an empty/reset store |
| ST-02 | Nothing was lost on first launch | Just launch on an existing profile | Addons, profiles, watch progress, Trakt auth all present |
| P2P-01 | No orphan daemon after quit | Enable P2P, stream something, quit Nuvio, then `pgrep -af torrserver` | No output |
| MP-01 | MPRIS clears on leaving playback | Play, then leave the player; `playerctl -p nuvio status` | `Stopped` — not a frozen `Paused` with stale metadata |
| PL-01 | Paused seek does not snap back | Pause, drag the scrubber somewhere else, release, stay paused | Scrubber stays where you put it |
| HA-01 | Hover art does not grow RSS | Hover many animated collection tiles; watch RSS | Levels off; does not climb without bound |
| UP-01 | Updater still works | Trigger an update check and a download | Downloads and hands off to the package manager; progress is smooth, not per-8 KB |

### Opt-in engines

Only if you want to exercise them — both are off by default and unchanged in behaviour.

| ID | Check | How | Pass |
|----|-------|-----|------|
| MPV-01 | mpv path still renders | `NUVIO_MPV=1 NUVIO_EGL=1 ./gradlew composeApp:run`, play something | Video renders, `hwdec-current` in the log is not `no` |
| MPV-02 | mpv teardown completes | Leave the player; watch the log | `NuvioMpvSession: disposed` appears; no audio continues after leaving |

MPV-01 also covers the JNA reachability fences added on that path. They fix a crash that was
*possible*, not one that was observed — so a clean session here is reassurance, not proof.

---

## If something fails

Each fix is independent and can be reverted on its own. Map the check to the file:

| Check | File |
|-------|------|
| SW-01 / SW-02 / SW-03 | `commonMain/.../features/streams/StreamsRepository.kt` |
| SW-02 (guard only) | `shouldSkipStreamReload` in the same file, used by `PlayerStreamsRepository.kt` |
| CW-01 | `commonMain/.../features/home/HomeScreen.kt` (`NextUpResolution`) |
| NAV-01 | `commonMain/.../App.kt` (`popBackStackSafely`) |
| DL-01 / DL-02 / DL-03 | `desktopMain/.../downloads/DownloadsPlatformDownloader.desktop.kt`, `DownloadsRepository.kt` |
| ST-01 / ST-02 | `desktopMain/.../core/storage/DesktopStorage.kt` |
| P2P-01 | `desktopMain/.../p2p/P2pStreamingEngine.desktop.kt`, `Main.kt` |
| MP-01 | `desktopMain/.../player/PlayerEngine.desktop.kt`, `MpvPlayerSurface.kt` |
| PL-01 | `bestPositionMs()` in `PlayerEngine.desktop.kt` |
| HA-01 | `desktopMain/.../home/components/CollectionCardRemoteImage.desktop.kt` |
| MPV-01 / MPV-02 | `desktopMain/.../desktop/mpv/` |

Two are worth flagging as the riskiest, because they change behaviour rather than only guarding
it — look here first if something is strange:

- **`DesktopStorage.persist()`** now writes via temp-file-and-rename and keeps a `.bak`. If
  settings behave oddly, check `~/.config/nuvio/` for stray `.tmp` files.
- **`FocusAnimation.close()`** frees Skia images on cache eviction. The reasoning is that only
  one card can be hovered at a time so the evicted entry is never on screen — if that reasoning
  is wrong the symptom is a *native crash* while hovering collection tiles, not an exception.
  Any crash there, revert that one change and say so.

---

## Known limitations of these fixes

Stated so a PASS is not read as more than it is.

- **The scraper hang is fixed structurally, not diagnosed empirically.** The root cause is
  strongly evidenced — `PlayerStreamsRepository` carries a comment describing this exact failure
  and the fix it got, which `StreamsRepository` never received — but nobody has watched the
  original bug happen under a debugger. If SW-01 still fails, the diagnosis is wrong, not the
  patch incomplete.
- **NAV-01 stops the blank screen; it does not fix the double-pop** that causes it. Back may
  still occasionally skip a screen.
- **Two sweep findings were left open on purpose:** ~60 `runBlocking { getString(…) }` call sites
  (only the one on the stream sort path was fixed, because it could throw into the load), and the
  unused `DesktopOAuthHandler`, whose removal also drops two Ktor server dependencies. Both are
  judgement calls for the maintainer, not defects to verify.
