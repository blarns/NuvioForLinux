# Tracking verification results

Runs of the protocol in `TRACKING_VERIFICATION.md`. Newest run last — **append, never overwrite**.

---

## Run: 2026-08-13 — Slice 0 baseline

- App version: 0.3.2 (build 108)            Build: deb (`/opt/nuvio`, installed from the release asset)
- Commit under test: `cb08b0f0` (tag `v0.3.2`) — confirmed: that commit's
  `iosApp/Configuration/Version.xcconfig` declares `MARKETING_VERSION=0.3.2` /
  `CURRENT_PROJECT_VERSION=108`, matching the "Version 0.3.2 (108)" the installed build shows in
  Settings
- Distro / DE / WM: Linux Mint 22.3, Cinnamon 6.6.9, X11 (kernel 6.8.0-137, libVLC 3.0.20)
- Trakt account available: no   Simkl account available: no
- Profiles used: `ben` (profile 1, real history) for WP-01…WP-04, CW-01, RG-01…RG-04; `Davy`
  (profile 2, empty) for PR-01; `testing` (profile 4, created fresh mid-run) for WP-05, WP-06,
  CW-02, CW-03, RG-02, RG-03, HS-01

**How this was run.** Everything ran against a copy of the real config under an isolated
`XDG_CONFIG_HOME` (`~/.local/share/nuvio-verify/config`), so the checks had real watch history to
work with while the live `~/.config/nuvio` stayed untouched. Isolation was verified empirically:
the isolated tree took every write, and the only change in the real tree during the run came from
the user's own concurrently running instance. A full backup was taken first.

Two caveats that colour the whole run, stated up front rather than buried:

1. **A second instance of the app was running throughout**, signed in to the same account. Some
   of the sync/realtime failures noted below may be a consequence of that rather than defects.
2. Partway through, the profile under test was switched from `ben` to a purpose-made `testing`
   profile, so the later checks ran against a **clean** profile. Where a check needed pre-existing
   history it is marked accordingly.

`HS-01` was run against a **different binary** — see its row.

| ID | Result | Notes |
|----|--------|-------|
| WP-01 | PASS | Played Futurama S8E4 from 0 for ~4 min, closed the window properly, reopened. Item is the first card in Continue Watching. Store: `lastPositionMs=244.0s` of `1471.1s`, written by the shutdown flush at close. UI reads "18% watched" where the store is 16.6% — the UI appears to divide by the TMDB runtime (23m) rather than the actual file duration. Cosmetic, but it means the card percentage is not the stored percentage. |
| WP-02 | PASS | Reopened the same item from its Continue Watching card. Playback began at the saved position (mid-episode scene ~1s after the click; the readout was consistent with a 244s start on every subsequent sample). Never started from 0. |
| WP-03 | PASS | With a caveat worth reading. Sampled the position readout at ~7 Hz across a resume (76 frames with controls visible). The sequence is `00:00 × 4` while the stream is still opening (screen black, no video yet), then straight to `06:57` and monotonically upward. It never displays the saved position and *then* collapses — the 0 is a pre-playback placeholder, not a regression of the resume value. Separately, the store was polled every 0.3s for 45s across a resume: **no near-zero write ever reached it**. The only write in that window was the on-exit flush at the correct position. |
| WP-04 | PASS | Dragged the scrubber back to 00:31 from 11:42, played on, quit properly. Store held `51.8s`, not the pre-seek position. On reopen the card read 4%. |
| WP-05 | PASS | `kill -9` during playback of Bleach S1E2, then reopened. Single Continue Watching card, "21m left", matching the last periodic write; no duplicate and nothing corrupted. Worth recording: progress is written **every ~120s** plus on exit, so worst-case loss on a crash is about two minutes. |
| WP-06 | PASS | Seeked to 23:56 of 24:09 and let the episode run out. It froze on the final frame (no black flash), showed a "Next Episode — S1E2" card, wrote 100% to the store, created the `watched_4` key, and left Continue Watching. |
| CW-01 | PASS | Six items, order matched the store's `lastUpdatedEpochMs` order exactly, no duplicates. The three Lord of the Rings cards are three different films (Fellowship / Two Towers / Return of the King), not a duplication bug. |
| CW-02 | **FAIL** | Removal does not stick. Removing the Bleach S1E2 card cleared the whole profile's `watch_progress` (0 entries), but the card **immediately came back as an "Up next" card for the same episode**, and it was still there after a full restart. The behaviour is consistent with Remove clearing watch progress while leaving the *watched* flag on the previous episode, from which the next-up card is re-derived. Observed once, and it survives a restart. An earlier removal in the same session *did* clear the row — at that point the only state was the completed S1E1 entry, so there was nothing left to re-derive from. |
| CW-03 | PASS | After finishing S1E1, Continue Watching showed exactly one card: S1E2, badged "Up next" — the next episode, not the one just finished. The meta screen's primary button also changed to "Up Next • S1E2". **But:** the row did not update in place. Immediately after the episode ended and I navigated back, the Continue Watching row was absent entirely; the correct next-up card only appeared after restarting the app. |
| CW-04 | BLOCKED | Not run. Needs the last episode of a season finished with another season available; the time cost of a further full-episode playback was out of budget for this run. No evidence either way. |
| CW-05 | BLOCKED | Not run, same reason as CW-04. |
| CW-06 | BLOCKED | Neither test profile had a series with an unaired next episode. |
| PR-01 | PASS | Watched on `ben`, switched to `Davy`: Davy's home carried no Continue Watching row at all, and nothing of ben's. Confirmed at the storage layer too — only `watch_progress_1` / `watched_1` keys existed, no profile-2 keys. The `testing` profile likewise started with an empty Continue Watching while ben's history was intact. |
| PR-02 | BLOCKED | Not verified. The attempt to switch back to `ben` mis-landed on the Add Profile screen (see anomaly 4 below) and the run moved to the `testing` profile before this could be retried. |
| PR-03 | BLOCKED | Not run. `Davy` has no addons configured, so the same title could not be browsed to on both profiles. |
| PR-04 | BLOCKED | Sign-out/sign-in needs the account password, which this session did not have. |
| TR-01 | BLOCKED | No Trakt account signed in — `trakt_settings_1` is empty and the owner declined an OAuth sign-in for this run. Settings → Account → Trakt exists and opens. |
| TR-02 | BLOCKED | As TR-01. |
| TR-03 | BLOCKED | As TR-01. |
| TR-04 | BLOCKED | As TR-01. |
| TR-05 | BLOCKED | As TR-01. |
| TR-06 | BLOCKED | As TR-01. Additionally withheld by choice — it mutates the owner's remote Trakt account. |
| TR-07 | BLOCKED | As TR-01. |
| TR-08 | BLOCKED | As TR-01. No sync timing baseline exists yet; this remains an open risk for slice 2. |
| SK-01 | SKIPPED | Scoped to slices 3–4. No Simkl code exists in `v0.3.2`. |
| SK-02 | SKIPPED | As SK-01. |
| SK-03 | SKIPPED | As SK-01. |
| SK-04 | SKIPPED | As SK-01. |
| SK-05 | SKIPPED | As SK-01. |
| RG-01 | PASS | Controls are alive. Pause held the position (09:03, unchanged 2.5s later) and resume advanced it (09:06). The subtitle menu opens, lists five tracks, and selecting `Track 1 – [English]` rendered subtitles within a second. The audio menu opens and lists its tracks. Two things short of a clean sweep: **a single click on the progress bar does not seek — only a drag does**; and the Audio Tracks menu shows **"Disable" as the checked entry** while the episode is playing. I could not confirm whether audio was actually switching (no audio monitoring in this session), so treat the audio half as "menu functional, effect unverified". |
| RG-02 | PASS | Shift + wheel scrolled a card row horizontally (row advanced from Frieren…Haikyu!! to Solo Leveling…Dr. STONE). The floating `›` arrow is present on hover. |
| RG-03 | PASS | Context menu opened on **three of the four** surfaces: poster (Add to library / Mark as watched), episode card (Mark as watched / Mark previous / Mark season / Play manually), Continue Watching card (Go to details / Play manually / Start from beginning / Remove). **The season chip was not exercised** — treat that surface as untested rather than passing. |
| RG-04 | PASS | Measured by white-pixel presence in the control bar: controls visible at 4s idle, gone by 8s, and back within ~1.5s of a mouse move. |
| RG-05 | PASS | Run against the **deb**, not an AppImage — `/opt/nuvio/bin/nuvio 2>&1 \| grep DesktopWindowGeometry`. An AppImage smoke test of a CI-built artifact is still outstanding and this row does not stand in for one. Line: `DesktopWindowGeometry: opening 1280.0x711.0dp screen=1920.0x1160.0dp scale=1.0 fullscreenSupported=true XDG_SESSION_TYPE=x11 XDG_CURRENT_DESKTOP=X-Cinnamon WAYLAND_DISPLAY=-` |
| HS-01 | PASS (answered) | **The mouse wheel does not drive the hero stretch overscroll.** `41658dfd` is *not* in `v0.3.2` (it landed after the tag), so this was run against a from-source `createDistributable` of the branch tip, `99e29f08`. At the top of Home, 24 consecutive wheel-up events over the hero produced **no vertical displacement at all**: the "Continue Watching" heading stayed at exactly y=459 in every captured frame, and in the settled frame afterwards. The only pixels that changed were the hero's own auto-rotation. **The obvious way this measurement could lie is if the hero swallowed the wheel events, in which case nothing scrolled and nothing was tested — so that was checked directly:** from the identical pointer position, four wheel-*down* events scrolled the page (the same heading moved 459 → 386). The wheel does reach the vertical scroll chain from over the hero; the up-scroll simply produces no stretch. Untested with a trackpad, which may report a different `NestedScrollSource`. |

### Anomalies observed outside the checklist

These are baseline observations, not regressions — nothing here is attributable to the tracking
work, since none of it exists yet. Recording them so a later run can tell "was already like this"
from "we broke it".

1. **Progress is written every ~120s plus on exit.** Measured directly: a periodic write landed at
   exactly `120.0s` of playback, and no other write occurred during a 45s window mid-playback.
   This bounds what any crash can lose, and it means a store poll is a poor instrument for
   catching short-lived write bugs.
2. **The app auto-resumes on launch if it was killed or closed from inside the player.**
   `nuvio_resume_prompt.properties` carries `was_in_player=true` and `last_player_video_id`; on the
   next launch the app resolves streams and starts playing that item unprompted. Expected
   behaviour as far as I can tell, but it interferes with any protocol run — every launch in this
   run had to clear the flag first.
3. **Profile push to the server fails.** Every launch logs
   `PGRST202 — Could not find the function public.sync_push_profiles(p_origin_client_id, p_profiles)`,
   with the server hinting the real signature takes `p_client_max_profiles` as well. This is a
   client/server signature drift, server-side, not something in this tree.
4. **Add Profile hangs on "Saving…".** With (3) failing, the Add Profile screen sat on "Saving…"
   indefinitely, showed no error, and the back control did not respond. No profile was written
   locally. Worth a look independently of the tracking work.
5. **Realtime sync invalidation never subscribes.** Repeated
   `Timed out waiting for 15000 ms` / `realtimeStatus=DISCONNECTED`, retried with backoff, on both
   profiles. May be an artefact of two instances on one account — flagged, not diagnosed.
6. **An episode at 23% progress carried a watched ✓ badge** (Futurama S8E3 on the `ben` profile).
   This is exactly the progress-vs-watched precedence area that `b4fa7538` addresses, and it is
   present in the shipped `v0.3.2`. Anyone verifying slice 1 should re-check this specific case.
7. **The isolation was filesystem-only, and that is worth knowing for the next run.** The isolated
   instance was still signed in to the same account, so a `SyncManager` push could in principle
   have written test positions to the cloud, where a local backup would not have covered them.
   Checked afterwards from the logs: profile 1's `lastPush` reads `2026-08-11 05:24:51` in every
   log of every launch in this run — **it never pushed**, so nothing of the real profile's progress
   left the machine. The `testing` profile did push (`2026-08-13 04:41:43`), which is what it is
   for. A future run should either use a throwaway account or verify the same field again.
8. **Some watch-progress entries have the IMDb id as their title** and no poster (e.g. an entry
   titled `tt0120912`), so they render as bare ids. Metadata enrichment gap in existing history.

**Verdict:** Slice 0 has a usable baseline for `WP-*`, `CW-01/02/03`, `PR-01`, `RG-*` and the
`HS-01` open question, and one real defect to carry forward: **CW-02 fails on stock `v0.3.2`** —
removing an item from Continue Watching does not keep it gone, because the next-up card is
re-derived from the watched flag that Remove leaves behind. That is a pre-existing failure, so a
later run seeing it must not read it as slice damage.

The baseline is **not** complete, and the gaps are where the risk is. All eight `TR-*` checks are
BLOCKED for want of a Trakt account, which means **slice 2 — the "route Trakt through the new
abstraction, change nothing" slice the plan already calls the highest-risk one — currently has no
before-picture at all.** `TR-08`'s sync timing has no recorded number to regress against, and
`PR-02`/`PR-03` are unverified, so three of the four profile-leak checks are untested. Getting a
Trakt sign-in onto a test profile and re-running `TR-*` and `PR-*` is worth more than any other
single thing before slice 2 is written.
