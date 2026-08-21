# Bug-fix verification results

Results of running `docs/BUGFIX_VERIFICATION.md` against the bug-sweep branch.
Append a new `## Run:` section per run; do not overwrite an earlier one.

⚠ **Addon and indexer names, addon hosts and debrid tokens are deliberately not reproduced here.**
This repository is public and several installed addon URLs embed API keys verbatim, so individual
sources are described by what they did rather than named.

---

## Run: 2026-08-20 — bug-sweep branch

- Commit under test: `958ca1f4`
- Build: from source (`./gradlew composeApp:run`)
- Distro / DE / session: Linux Mint 22.3, Cinnamon (X-Cinnamon), X11
- Addons installed: 24 (13 offer `stream` for series); one reports a real "debrid account inactive"
  error on every request, which is genuine and unrelated to this branch
- Debrid configured: yes   P2P enabled: no   NUVIO_MPV: unset for Tier 1–2, `1` (with `NUVIO_EGL=1`)
  for the MPV rows
- Run isolation: `XDG_CONFIG_HOME` pointed at a **copy** of `~/.config/nuvio`, on the `testing`
  profile (profile 4). `~/.config/nuvio.bak-20260820-222102` taken first. The real config was not
  written to.

| ID | Result | Notes |
|----|--------|-------|
| A-01 | PASS | `BUILD SUCCESSFUL` |
| A-02 | PASS | 251 tests, 0 failures, 0 errors, 0 skipped — exactly the expected count |
| A-03 | PASS | `nuvio_0.3.5_amd64.deb` built; `dpkg-deb -f` shows `Recommends: fonts-noto-color-emoji, libmpv2` |
| SW-01 | FAIL | Sources **do** appear — 8s after pressing next-episode. But two source chips in the in-player Streams panel were still spinning after **3 minutes** and never settled. Details below. |
| SW-02 | PASS | Home → same show → stream list fetched again: `Found 13 addons for stream type=series id=…:1:3`, 13/13 returned, list populated |
| SW-03 | BLOCKED | Could not induce a dead addon: `AddonRepository.addAddon()` refuses outright on a secondary profile that uses the primary profile's addons, which is exactly the isolated profile this run used. Partial evidence below. |
| CW-01 | PASS | Show stayed in Continue Watching and advanced to the next episode. Details below. |
| NAV-01 | PASS | 12 back presses from a stream list (after having been through the player) → lands on Home and stays; no blank window (near-white pixel fraction 0.002) |
| REG-01 | PASS | Every playback operation exercised behaved correctly — itemised below. ⚠ Read as "nothing observed to be broken", not "unchanged": there is no 0.3.5 baseline run to compare against, and one item (subtitle track switching) could not be exercised at all |
| DL-01 | BLOCKED | Not run — each of these writes a full media file to real disk outside the isolated profile. Happy to run them on request |
| DL-02 | BLOCKED | as DL-01 |
| DL-03 | BLOCKED | as DL-01, and additionally needs a source URL that has since rotated, which cannot be induced on demand |
| ST-01 | PASS | Toggled a playback setting through the UI, `kill -9` ~1s later. The new value was on disk, intact, immediately after the kill. No zero-length store anywhere in the profile dir; **no stray `.tmp` files**; `.bak` siblings present as designed. One nuance below |
| ST-02 | PASS | Launch on the copied profile restored addons (13 stream addons found), 161 home-catalog items, 7 watch-progress entries, all 4 profiles, Trakt/Supabase session |
| P2P-01 | BLOCKED | P2P deliberately left disabled — maintainer's standing decision not to exercise in-app P2P playback |
| MP-01 | FAIL | After leaving the player, `playerctl -p nuvio status` reports a frozen `Paused` with a stale position, indefinitely. Intermittent — also observed correctly reporting `Stopped`. Details below. |
| PL-01 | PASS | Paused at 140s, dragged the scrubber → 648.47s, still `Paused`, unchanged 5s later; scrubber held at 10:48 on screen. No snap-back |
| HA-01 | BLOCKED | 66 hovers were run and were clean, **but on the wrong rows** — see below. The changed code was not exercised |
| UP-01 | BLOCKED | Deliberately not run: the check ends in a hand-off to the package manager, i.e. a real install on the maintainer's machine |
| MPV-01 | PASS | `NUVIO_MPV=1 NUVIO_EGL=1`: `[nuvio-egl] ACTIVE`, `hwdec-current=vaapi` (zero-copy, not `vaapi-copy`, not `no`), steady **24.0 fps against a 24 fps source**, `avsync=0.000 dropped=0 delayed=0`. Picture correct, no colour or overlay corruption |
| MPV-02 | PASS | `NuvioMpvSession: disposed` on leaving; MPRIS went to `Stopped` with position `0.000000`; no audio continued |

### SW-01 — FAIL, and not in the file the doc maps it to

Pressing next-episode at the end of an episode opened the in-player **Streams** panel and it was
populated with real sources within **8 seconds**. The headline symptom — a spinner that resolves to
nothing, forever — is gone.

But two of the source filter chips in that panel kept spinning and never resolved. Sampled at
+78s, +2m30s and +3m00s after the press; the arc was at a different rotation each time, so it is a
live spinner, not a static glyph. `SOURCE_TIMEOUT_MS` is 45s, so nothing should still be loading.

Localisation, in order of what was actually measured:

- The chip spinner is bound to `group.isLoading` (`PlayerSourcesPanel.kt:171`), so those two groups
  were never published as settled.
- No `Timed out:` and no `Failed:` line appears anywhere in the log, so the 45s bound never fired.
- A thread dump taken while both chips were spinning shows **nothing blocked**: every coroutine
  worker parked, the EDT idle in `getNextEvent`. So this is a suspended coroutine awaiting a
  response, not a wedged thread.
- **Control test.** The same episode (S1E3) and the same addon set were then loaded through the
  *other* path — the ordinary stream list, `StreamsRepository` — from the show page. All 13
  **addons** returned (`Got N streams from …` ×13) with zero timeouts. So the difference is the
  code path, not the episode, the network or a slow addon.

  ⚠ Scope of that control: it covers the addons only. The in-player panel fans out
  `pendingStreamAddons + pluginScrapers` into one chip row, whereas the stream list shows addons as
  chips and lists scrapers separately. Both stuck sources exist as an addon **and** as a plugin
  scraper, and which variant was spinning was not determined — that is the fact that would
  discriminate the two candidates below, since a `plugin:` id points at `executeScraper` and an
  addon id points at the debrid cache-check path.

⚠ **The revert table in the protocol maps SW-01 to `StreamsRepository.kt`.** The path that fails
here is `PlayerStreamsRepository.kt` — reverting the mapped file would not touch it. On the
evidence, `StreamsRepository` behaves correctly.

Not established: which of the two remaining candidates it is — a `source.fetch()` that suspends
somewhere `withTimeoutOrNull` cannot see, or the unbounded debrid cache check in
`publishStreamGroupAfterCacheCheck` → `LocalDebridAvailabilityService.annotateCachedAvailability`
→ `LocalDebridService.checkCached`, which is a network call with no timeout, and whose group is
only published once it returns. The second fits the "nothing is blocked, two groups never publish"
shape, and the account behind one configured debrid service is reporting itself inactive this
session.

### CW-01 — PASS

Clean evidence, from the one episode that reached a real end-of-file: S1E2 played through to EOF
(position reached duration, persisted as such); returning home, **the show was still in Continue
Watching**, and opening it showed the primary action had moved to **"Up Next • S1E3"**. That is the
check.

Later in the run the Continue Watching row itself rendered as **S1 E4 with its episode title and
artwork**, i.e. the row does advance and enrich. That state is not offered as primary evidence,
because it was reached after the E3 progress anomaly recorded below.

One thing that looked like a failure and is not: for the first part of the run the Continue
Watching card rendered as a bare `tt…` id with no artwork. That is a **pre-existing duplicate
progress entry** in this library — the same episode recorded twice, once under an `imdb` id and
once under a `kitsu:` id, the latter unenriched and dated before this session. It is not caused by
this branch and it did not stop the row resolving.

### MP-01 — FAIL (intermittent)

Two clean trials: play a stream, confirm `Playing`, leave the player with a single back press, then
poll.

- Trial: `Playing` at 69.08s → back → `Paused` / pos `70.020000` at t+4s, t+8s, t+12s, and still
  `Paused` minutes later with the app sitting on the home page and no player anywhere.
- Earlier in the same session the same action reported `Stopped` correctly, twice.

So the fix works but races. `VlcjPlayerSurface`'s `onDispose` sets `hasMedia = false`, but
`DesktopPlaybackSideEffects.kt:159` sets `hasMedia = !snap.isEnded` on **every** snapshot tick. A
tick that lands after disposal flips it back to `true`, and `Mpris2Handler.kt:67` needs
`!hasMedia && controller == null` to report `Stopped` — so it falls through to `Paused` forever.
That is consistent with both the failures and the passes. Not proven by instrumentation.

### REG-01 — what was actually exercised

Each of these was observed this session, on VLCJ:

- Seek forward (300s → 303.8) and **backward** (120s → 124.0) ✅
- Pause → position frozen, no drift; resume → advances again ✅
- Audio track switch: menu listed 3 entries, selected another track, reopened the menu and the new
  track was ticked ✅
- Source change mid-playback, twice: once through the Sources panel (playback continued from the
  same position on the new release, 3:17 → 3:52 without interruption), and once across a
  leave-and-re-enter where the replacement release had a different duration (23:04 → 23:15) and
  playback still continued from 10:48 ✅
- Resume-from-position after leaving and re-entering: left at 0:46, stream list offered
  "Resume from 0:46", and re-entry resumed there ✅
- End of episode: reached EOF cleanly, Next Episode card appeared, progress persisted as complete ✅
- Progress persistence mid-episode: left at 45.2s, store recorded 46s of 1385s ✅

Not exercised: **subtitle track switching**. The releases played exposed no embedded subtitle track
— the Subtitles panel opened and rendered its Built-in / Addons / Style tabs with `None` as the
only built-in entry, so there was nothing to switch to.

⚠ One anomaly, **not reproduced over two re-tests**, recorded because of what it would mean if it
is real. S1E3 was persisted at **100%** (`pos=1396 dur=1396`) when the highest position ever
observed on that episode was 648.6s — 46% of it. The **Next Episode card appeared at 10:48 of
23:15**, as though at EOF, which is the visible tell that something had set an at-end condition.
An episode watched under half way being marked complete removes it from Continue Watching and
advances the show, which is the same class of harm CW-01 exists to catch.

Two re-tests, neither reproducing it:

1. Play → leave mid-episode. Persisted 46s of 1385s. Correct.
2. Play to 3:17 → **change source mid-playback through the Sources panel** → confirm playback
   continues on the new release → leave. No Next Episode card at the switch, playback continued
   from the same position, persisted 266s of 1385s (19%). Correct.

What the original sequence had that neither re-test did: the source change happened across a
**leave-and-re-enter**, where the episode was resumed at 648s and the replacement release was
auto-selected rather than picked from the Sources panel, and the two releases differed in length
by 11s (23:04 vs 23:15). If this is chased, that is the shape to try.

### SW-03 — BLOCKED, and why

The plan was to install an addon that serves a valid manifest and then fails every `/stream`
request, so the fan-out sees a real throw. It cannot be installed: `addAddon()` returns
`profile_primary_addons_required` for any secondary profile that uses the primary profile's addons
(`AddonRepository.kt:228`), and the isolated `testing` profile is exactly that. Doing it properly
means either the primary profile — which is PIN-locked and whose addon list pushes to Supabase —
or giving the testing profile its own addon list, which is also a synced change. Neither was worth
doing unattended.

Partial evidence, worth what it is: one installed addon returned a genuine error on **every**
request this session (its debrid account reports itself inactive). Its error was rendered in the
list and **every other source populated normally**, on both the stream-list and the in-player
paths. That is the SW-03 property holding for an error *response*. It does not exercise the case
the fix is actually about — an exception thrown inside the fan-out.

### ST-01 — the nuance

The atomic-write change did its job. What it cannot protect against: on the next launch the app
**rewrites `nuvio_player_settings.properties` and dropped the key**. The new value was in the file
straight after the kill, it is still in the `.bak` sibling now, it is absent from the live file,
and the toggle reads OFF in the UI after restart.

So the setting reverted to its old value. That is still a PASS by this check's own wording ("either
the old or the new value — never an empty/reset store"). Flagged because "change a setting,
hard-kill, lose the setting" is a real user-visible outcome that the atomic write does not by
itself fix.

⚠ The cause was **not isolated**, and no comparison run was made against `f589c11` — so this write-up
does not claim the drop is or is not new in this branch. What is established is only the sequence
above.

### HA-01 — BLOCKED, because the hovers missed the changed code

66 tile hovers over three passes on the Home page: RSS 1794.4 MB → 1796.3 MB (+1.9 MB), and
1771.8 MB after a forced `GC.run` — below where it started. No crash, no unbounded climb.

That result does **not** cover this change. The rows hovered were catalog rows, which render through
`HomeCatalogRowSection`. `FocusAnimation.close()` lives in `CollectionCardRemoteImage.desktop.kt`,
whose only caller is `HomeCollectionRowSection` (`HomeScreen.kt:859`) — the **collection** rows,
which are a separate `isCollection` branch of the home item list. Scrolling the whole home page on
this profile turned up no collection row, so the animated-art path never ran and the flagged
native-crash risk is untested.

To close this row: enable a collection row on Home (this profile has several defined — watchlists,
"Up Next", "Unwatched") with collection GIF animation on, then hover across its tiles repeatedly.
The failure mode to watch for is a **native crash**, not an exception.

### MPRIS reports `Paused` during active mpv playback

Separate from MP-01 and on the other engine. With mpv playing normally — `pause=false`,
`core-idle=false`, 24.0 fps, position advancing 93.7 → 115.5 across samples — `playerctl -p nuvio
status` returned **`Paused`** throughout. The position property is live and correct; only the
playback status is wrong, so a desktop media widget shows Nuvio as paused while it plays.

Teardown on this path is clean (MPV-02), which is the mirror image of the VLCJ path: VLCJ gets the
playing status right and the stopped status wrong, mpv gets the stopped status right and the
playing status wrong. Both flags are `PlayerControlBridge` state, so they are probably one bug.

### Not a check, but found while running one: credentials in the log

`StreamsRepository.kt:513` logs `"Fetching streams from: $url"` at debug for every addon. Several
installed addon URLs carry the debrid API key in the base64 config segment, so **a plain run of the
app prints live Real-Debrid and TorBox keys in cleartext**, and `nuvio_debug_logs.sh` collects
exactly that file.

This is **pre-existing** — the same line is in `f589c11` and in v0.3.4 — so it is not a regression
in this branch, and no check fails because of it. It is raised here because the branch rewrote that
file and because `redactSourceUrl` already exists and is already applied on the player paths
(`PlayerEngine.desktop.kt`, `MpvPlayerController.kt`, verified in this run's log). It lives in
`desktopMain` while this call site is `commonMain`.

### Isolation post-check

`diff -rq ~/.config/nuvio ~/.config/nuvio.bak-20260820-222102` after the whole run: **no
differences**. Nothing was written to the real profile, and no `.tmp`/`.bak` files leaked into it.
The isolated copy's own writes stayed on the `testing` profile. Not covered by that check: whatever
the sync layer pushed to Supabase for profile 4 — the realtime channel was failing with HTTP 503
all session, but REST pushes may have gone through. That profile exists for this purpose.

**Verdict:** Two of the three reported bugs are genuinely fixed and ordinary playback is not worse
than it was. **CW-01** is fixed and behaves exactly as designed, including the interesting case —
the row survived a period where the show's metadata would not resolve, and then advanced on its
own. **SW-02** is fixed: reopening the same title after the next-episode flow refetches and
populates. **NAV-01** holds; twelve back presses land on Home with no blank window. **SW-01 is not
fixed.** The headline symptom is gone — sources appear in eight seconds instead of never — but two
source chips in the in-player Streams panel spin forever, past the 45s bound, with no timeout ever
logged. The control test pins it to `PlayerStreamsRepository`, not the `StreamsRepository` the
revert table points at, so the mapped revert would not address it. **MP-01 also still fails**,
intermittently: leaving the player can leave MPRIS advertising a frozen `Paused` player, which is
precisely the symptom that change set out to remove, so that fix is incomplete rather than wrong.
The mpv path has the complementary bug of reporting `Paused` while playing. Nothing in the regression pass got
worse. Of the two changes flagged as riskiest, only one was actually exercised: the atomic settings
write behaved (no stray `.tmp` files, no truncated store, value intact across a `kill -9`), while
**freeing evicted hover-art frames was never reached** — the hovering hit catalog rows, not
collection rows, so HA-01 is BLOCKED rather than passed. Seven rows are BLOCKED rather than
guessed: SW-03, HA-01, the three DL rows, P2P-01 and UP-01.

---

## Run: 2026-08-20 (second pass) — the blocked rows, revisited

Same machine, same branch, same isolation as the first run. This pass exists only to close the
rows the first run left BLOCKED. Rows not listed here are unchanged from run 1.

- Commit under test: `958ca1f4`
- Build: from source
- Profile: `testing` (profile 4) except where noted; one HA-01 sweep ran on profile 1, read-only
- Debrid configured: yes   P2P enabled: no   NUVIO_MPV: unset

| ID | Result | Notes |
|----|--------|-------|
| HA-01 | PASS | Now run on the **collection** rows, which are the ones that use the changed file. 68 hovers over 4 passes: RSS 1797.1 MB → 1800.1 MB, then **989.4 MB** after a forced GC. App alive throughout, no native crash. One caveat below |
| SW-03 | BLOCKED | Three further attempts, all defeated by addon filtering rather than by the fix. Much sharper account of what it would take, below |
| UP-01 | BLOCKED (half covered) | The update **check** works end to end — reaches the release feed, resolves the latest release, reports "No updates found" against 0.3.5 (111). The **download** half, which is where the changed code is, could not run: there is no newer release to fetch |

### HA-01 — now on the right rows

Run 1's sweep was on catalog rows and therefore proved nothing about this change. This pass hovered
the two collection rows on the home page — the tiles rendered by `HomeCollectionRowSection` →
`CollectionCardRemoteImage`, which is where `FocusAnimation.close()` lives. 17 distinct tiles, four
passes, 68 hovers, well past the 8-entry animation cache, so evictions must have occurred if the
cache was being populated at all.

No crash. RSS rose 3 MB across the sweep and dropped to 989 MB after `GC.run` — 808 MB below where
it started, so nothing is being retained.

⚠ What is still not proven: that the **animated decode** actually ran. There is no log line for it,
and sampling a hovered tile five times over three seconds showed a pixel-identical image each time
(mean absolute difference 0.000), so no frame advance was observed. The hover art is configured —
the collection data carries `focusGifUrl` values and `mobileFocusGifEnabled` defaults to true — and
the tile does change appearance on hover, but the GIF may simply not have decoded. Read this row as
"68 hovers of the changed composable produced no crash and no growth", not as "the eviction path
was exercised".

### SW-03 — three more attempts, still blocked, and now for a precise reason

The plan was sound and the harness worked: a local HTTP server that serves a valid Stremio manifest
and returns 500 on every `/stream` request, added to the addon list. The app fetched its manifest at
startup on every attempt, so it *was* installed. It was never given a stream request:

1. Manifest with `idPrefixes: ["tt"]`, opened a `kitsu:`-keyed title → filtered out, 12 addons in
   the fan-out.
2. Manifest with **no** `idPrefixes` at all, opened a `tt:`-keyed title → still filtered out.
   14 addons found, 14 `Got N streams` lines, none of them this addon, and no request reached the
   server. So an absent `idPrefixes` is not treated as "matches everything".
3. Manifest with `idPrefixes: ["tt", "kitsu"]`, `tt:`-keyed title → identical to (2). 14 addons,
   14 results, no request.

So a hand-edited entry in `installed_addon_urls_*` is loaded far enough to have its manifest
fetched, but not far enough to join the stream fan-out. Whatever admits an addon to that fan-out is
established somewhere other than the URL list plus a live manifest.

To actually close this row, the addon has to be installed the ordinary way, through the UI — which
needs a profile that owns its addons, because `addAddon()` refuses on a secondary profile using the
primary's list (`AddonRepository.kt:228`). That means either the primary profile, whose addon list
pushes to Supabase, or giving the testing profile its own addon list, which is also a synced change.
Both are the maintainer's call, not something to do unattended.

### UP-01 — the half that could run, ran

`Check for updates` reached the release feed and returned **"Update status — No updates found."**
against the running 0.3.5 (111). That exercises the check path and confirms it is not broken.

The changed code is in the **download** (`AppUpdaterPlatform.desktop.kt`, the per-8KB progress
issue), and it cannot be reached while the newest published release is the version already running.
Enabling "Experimental updates" would not help — there is no newer prerelease either. This needs a
newer release to exist, or a build deliberately versioned behind one.

**Verdict for this pass:** one of the three intended rows closed. HA-01 now covers the changed
composable and shows no crash and no retention, with the honest caveat that the animated decode
itself was never observed running. SW-03 and UP-01 remain BLOCKED, but for reasons that are now
specific and actionable rather than "not attempted": SW-03 needs a real UI install on a profile that
owns its addons, and UP-01 needs a newer release to exist. Nothing found in this pass changes any
run-1 result.

---

## Run: 2026-08-21 (third pass) — SW-03, closed

Run 2 could not install a failing addon. This pass installs one properly, on a profile that owns
its addon list, runs the check, and removes it again.

- Commit under test: `958ca1f4`
- Build: from source
- Profile: the **primary** profile, with the maintainer present to enter its PIN
- Isolation: as before, `XDG_CONFIG_HOME` on a fresh copy of `~/.config/nuvio`

| ID | Result | Notes |
|----|--------|-------|
| SW-03 | PASS | A source that throws on every request does **not** take the stream list down. Detail below |

### SW-03 — PASS

Method: a local HTTP server serving a valid Stremio manifest (`stream` resource, movie + series,
`idPrefixes` for the id schemes in use) that returns **HTTP 500 and closes the connection** on every
`/stream` request. Installed through the ordinary Addons UI, exercised, then removed.

What happened, in order:

1. Install succeeded: `addAddon()`, then `pushToServer() — pushing 38 addons … success`.
   The addon list went 37 → 38, active 31 → 32, and the addon appeared in the list as Active.
2. Opening a series stream list produced `Found 15 addons for stream type=series id=tt…:8:3` — the
   failing addon included.
3. The server logged the request and refused it: `FAILING /stream/series/tt…%3A8%3A3.json` → 500.
4. **Every other source populated anyway.** Fourteen `Got N streams from …` lines for that fan-out,
   the list rendered normally, and no filter chip was left spinning.
5. Removed: `removeAddon()`, `pushToServer() — pushing 37 addons … success`. Back to 37 / 31, and
   no trace of it in the addon store.

So the property SW-03 exists to check holds: one source throwing inside the fan-out no longer kills
the load. This is the case run 1 could only evidence with an *error response* from a real addon —
this one is a genuine transport failure inside the fetch.

⚠ Half-confirmed: the failing addon's **own** row was not visually inspected. Its filter chip sits
off the right-hand end of a horizontally scrollable strip that would not scroll under synthetic
input, and its group is far down a list of ~900 streams. So "every other source still populates" is
directly observed; "the dead addon's row shows an error" is not.

### Why run 2's attempts failed, for the record

Run 2 hand-edited the URL into `installed_addon_urls_*`. That gets the manifest fetched at startup
but the addon never joins the fan-out. Installing the same URL through the UI, on the same machine
against the same server, worked first time. So the addon list alone is not what admits an addon to
stream discovery — worth knowing before anyone else tries to script addon setup by editing the
store.

### A separate finding: "use primary addons" does not turn off

Before this pass, the intent was to run on the testing profile with its own addon list. Turning
**off** "use primary addons" for that profile does not take:

- `uses_primary_addons` is still `true` for that profile in `nuvio_profiles.properties`, in a copy
  of the store written *after* the toggle.
- Attempting an install on that profile fails with **"Install Failed — This profile uses primary
  addons."**

Not investigated further: whether the write never happens or a sync pull puts the old value back.
It is unrelated to this branch — `ProfileEditScreen` / `ProfileRepository` are untouched by it — but
it is a feature that visibly does nothing.
