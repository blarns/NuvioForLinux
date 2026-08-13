# Tracking verification protocol — instructions for a session that can run the app

**Who this is for:** a Claude Code session (or a human) running on a machine with a real display,
a real Nuvio install, and ideally Trakt and Simkl accounts. It is written to be self-contained —
assume the reader has none of the context that produced it.

**Why it exists:** the cloud agent working this repo has no display, no tracking credentials, and
no sync test harness, so it cannot verify anything behavioural in watch tracking. See
`TRACKING_ADOPTION.md`. This protocol is the missing half. Results come back through the repo, so
the cloud agent can read them.

---

## How to report results

Write **`docs/TRACKING_VERIFICATION_RESULTS.md`**, commit it, and push it to **`stable/0.3.2`** —
the branch this file is on, and where development happens. Do **not** push it to `cmp-rewrite`:
that branch is release-only, it is what every installed updater resolves against via
`target_commitish`, and it should move only when a release is cut.

The cloud agent fetches `stable/0.3.2` and reads the file — that is the whole return channel, so
the file has to be complete on its own. Append a new run rather than overwriting an old one.

If the branch has been renamed or merged away by the time you read this, push to whichever branch
carries this file and say so in the run header.

Use exactly this shape. The `ID` values matter; they are how results map back to checks.

```markdown
## Run: <YYYY-MM-DD> — <slice, e.g. "Slice 0 baseline" or "Slice 2 Trakt wiring">

- App version: <e.g. 0.3.2>            Build: <deb | AppImage | from source>
- Commit under test: <short sha>
- Distro / DE / WM: <e.g. Linux Mint 22, Cinnamon 6.0, X11>
- Trakt account available: <yes | no>   Simkl account available: <yes | no>
- Profiles used: <e.g. two profiles, "A" and "B">

| ID | Result | Notes |
|----|--------|-------|
| WP-01 | PASS | |
| WP-02 | FAIL | position reset to 0 after reopen, reproduced 3/3 |
| SK-01 | BLOCKED | no Simkl account |

**Verdict:** <one paragraph: is this slice safe to ship, and if not, what is blocking>
```

`Result` is one of **PASS**, **FAIL**, **BLOCKED** (couldn't run — missing account, hardware,
etc.), or **SKIPPED** (deliberately not run; say why in Notes).

**A FAIL is a useful result, not a failure of the exercise.** Do not fix anything mid-protocol —
record it and move on, so the whole picture arrives at once. If something is ambiguous, say so in
Notes rather than rounding to PASS; a soft PASS here is worse than a BLOCKED, because it will be
trusted.

---

## Before you start

1. Note whether the profile you test with has **existing watch history**. Several checks depend on
   it; a clean profile will make them BLOCKED.
2. For anything involving resume position, know that the app writes progress on quit through a
   shutdown flush. **Close the window properly** — do not `kill -9` — unless a check says to.
3. `WP-05` deliberately tests a crash path, so do it last in its group.

---

## WP — watch progress

| ID | Check | Expected |
|----|-------|----------|
| WP-01 | Play an episode, watch ~2 min, close the window, reopen | The item is in Continue Watching at roughly the position you left |
| WP-02 | Reopen that item and press resume | Playback starts at the saved position, not from 0 |
| WP-03 | While resuming, watch the position readout for the first ~5s | It must **not** collapse to ~0 and then jump. This regressed once and is guarded against — a near-zero write is meant to be rejected |
| WP-04 | Seek backwards to ~30s, quit, reopen | Position is ~30s, not the pre-seek position |
| WP-05 | Play something, then `pkill -9` Nuvio, reopen | Some progress loss is acceptable here; the item must still appear in Continue Watching and must not be corrupted or duplicated |
| WP-06 | Finish an episode to the end credits | It leaves Continue Watching and is marked watched |

## CW — continue watching

| ID | Check | Expected |
|----|-------|----------|
| CW-01 | Open Home with several part-watched items | Most recent first, no duplicates |
| CW-02 | Remove an item from Continue Watching via its context menu | It disappears and stays gone after a restart |
| CW-03 | Finish an episode of a series | The **next** episode appears, not the one just finished |
| CW-04 | Finish the last episode of a season with another season available | Next season's first episode appears |
| CW-05 | Finish the last episode of the last season | The series leaves Continue Watching entirely |
| CW-06 | With an unaired next episode | Series does not show a next-up that cannot be played, or shows it clearly marked |

## PR — profiles (this fork has leaked across profiles before)

| ID | Check | Expected |
|----|-------|----------|
| PR-01 | Watch something on profile A, switch to profile B | B's Continue Watching does **not** contain A's item |
| PR-02 | Watch on B, switch back to A | A's list is unchanged and still correct |
| PR-03 | Mark watched on A, switch to B, check the same title | B shows it unwatched |
| PR-04 | Sign out and back in | Per-profile progress survives |

## TR — Trakt *(needs a Trakt account)*

| ID | Check | Expected |
|----|-------|----------|
| TR-01 | Sign in to Trakt in Settings | Auth completes and survives an app restart |
| TR-02 | Play an episode for ~2 min | A scrobble start appears in Trakt's site activity |
| TR-03 | Pause for a moment | Trakt shows paused, not still playing |
| TR-04 | Finish an episode | Trakt marks it watched |
| TR-05 | Mark something watched on the Trakt website, then refresh in-app | The checkmark appears in Nuvio |
| TR-06 | Mark a whole series unwatched in-app | It clears in Nuvio and on Trakt |
| TR-07 | With a large watched history (1000+ episodes), trigger a refresh | Completes without truncation, hang, or memory blow-up |
| TR-08 | Time a full watched sync | Record the seconds in Notes — a regression here is a known risk |

## SK — Simkl *(slices 3–4 only; needs a Simkl account)*

| ID | Check | Expected |
|----|-------|----------|
| SK-01 | With the Simkl flag **off**, run WP-01, CW-01, CW-03, PR-01 | Results identical to the slice-2 run. **This is the most important check in the file** — it proves the gate actually gates |
| SK-02 | Turn the flag on, sign in to Simkl | Auth completes, survives restart |
| SK-03 | Finish an episode with both Trakt and Simkl connected | Both services record it; neither double-counts |
| SK-04 | Anime with a MAL/Kitsu mapping | Resolves to the right title rather than a near-miss |
| SK-05 | Disconnect Simkl | Trakt keeps working exactly as before |

## RG — regression sweep (run for every slice)

| ID | Check | Expected |
|----|-------|----------|
| RG-01 | Play, seek, pause, resume, change audio/subtitle track | All respond; the player controls are not dead. They have been dead twice |
| RG-02 | Shift + mouse wheel on a card row; hover for scroll arrows | Rows scroll horizontally |
| RG-03 | Right-click a poster, an episode, a season chip, a Continue Watching card | Context menu opens on each |
| RG-04 | Leave the mouse still during playback for ~3s, then move it | Controls fade, then return |
| RG-05 | Startup line: `./Nuvio-*.AppImage 2>&1 \| grep DesktopWindowGeometry` | Paste the whole line into Notes |

---

## If you have time for one more thing

The cloud agent has an open question it cannot answer: **does Compose Desktop report mouse-wheel
scrolling as `NestedScrollSource.UserInput`?** A hero stretch-overscroll was ported on
2026-08-12 that hangs off exactly that, and it is unknown whether it does anything with a mouse or
is simply inert.

Scroll up hard at the top of the Home screen with a mouse wheel and record whether the hero image
stretches and springs back. Either answer is useful. Record it as `HS-01`.
