# Adopting upstream's tracking refactor — plan and division of labour

*Status: plan only, nothing ported yet. Written 2026-08-12 against v0.3.2.*

## What this is

Upstream restructured watch tracking into a provider-neutral abstraction so Simkl could sit
alongside Trakt. Almost every watch-progress and sync fix since **2026-07-02** now sits on top of
that scaffolding, which is why sync-7 could only land 1 of 20 candidate fixes in that area: the
rest reference machinery this fork does not have.

Size, measured rather than guessed:

| | |
|---|---|
| tracking/Simkl commits since the sync-6 watermark | **75** |
| distinct `commonMain` files touched | **99** |
| …that do not exist in this fork (net-new) | **54** |
| …that exist here and would need merging into diverged versions | **45** |

## The actual constraint

Authoring this is not the expensive part. Reading 75 commits and writing 99 files is hours of
agent time, and it is the kind of mechanical, dependency-tracking work an agent is good at.

**Verification is the constraint, and it is asymmetric.** The cloud agent can establish that the
tree compiles, that 241 tests pass, that call sites and symbols line up, and that no actual is
duplicated. It cannot establish anything behavioural here, because:

- there is no display in the container, so the app cannot be run at all;
- there are no Trakt or Simkl credentials, so the integration paths cannot be exercised even in
  principle;
- the test suite has no sync harness — the watch-progress tests are pure-function rules tests,
  and `src/desktopTest` was not wired into any source set until recently.

And this is the subsystem with the worst history of compiling-but-broken in this project: the
near-zero regression guard, the profile-scoped playback leaks, and `v0.3.0` itself, which
compiled, packaged, passed everything available, and was broken in thirteen places.

**"It builds and the tests are green" is exactly the evidence that was available for 0.3.0 and was
wrong.** So the plan below is built around stopping at the boundary of what can be proven, and
handing the rest to a session that can actually run the thing.

## Slices

Each slice states what the cloud agent can prove and what it cannot. **No slice is merged to
`cmp-rewrite` until its verification row is filled in** — see `TRACKING_VERIFICATION.md`.

### Slice 0 — baseline, before anything is ported

Run the verification protocol against **stock v0.3.2**, unmodified. Without this, "did the port
break it" is unanswerable, because nobody knows which of these behaviours worked beforehand.

- *Agent proves:* nothing. This slice is entirely a human/CLI task.
- *Blocks:* everything. Do this first.

### Slice 1 — pure rules foundation

Port the provider-neutral progress/watched **decision functions** only: precedence between
sources, completion normalisation, next-up resolution. No repository wiring, no network, no UI.

- *Agent proves:* compiles; behaviour pinned by unit tests written alongside, in the style of the
  `WatchProgressRulesTest` cases that came with the Trakt precedence fix. These are pure
  functions, so they are genuinely testable without a GUI or an account — which is precisely why
  that fix was safe to take on 2026-08-12.
- *Agent cannot prove:* that the new precedence matches what users actually want to see in
  Continue Watching.
- *CLI verifies:* the `WP-*` and `CW-*` checks.

### Slice 2 — repository wiring, Trakt only

Route the existing Trakt integration through the new abstraction. **No behaviour change is
intended.** Simkl is not introduced here.

- *Agent proves:* compiles; existing tests unchanged and passing; call-site counts before/after
  match, per the audit method in `V0.3.0_PORT_VERIFICATION.md` — count call sites, never symbol
  presence.
- *Agent cannot prove:* that scrobbles still fire, that watched history still pulls, that nothing
  leaks across profiles.
- *CLI verifies:* the full protocol. This is the highest-risk slice — it touches working code
  while intending to change nothing, which is the exact shape of the four silent-drop rounds.

### Slice 3 — Simkl provider, gated off

Add Simkl behind `AppFeaturePolicy`, **defaulting to off**, so a build with the flag clear is
behaviourally identical to slice 2.

- *Agent proves:* compiles; with the flag off, no Simkl code path is reachable (audit the gates
  directly, the way the `isIos` mis-gating was caught — presence is not reachability).
- *CLI verifies:* flag off → protocol results identical to slice 2. Flag on → `SK-*` checks, which
  need a real Simkl account.

### Slice 4 — Simkl UI and settings

Only after slice 3 comes back clean.

## Rules for this work

1. **Nothing ships on green tests alone.** Every slice needs its verification row.
2. **Keep Trakt working over adding Simkl.** Trakt users exist today; Simkl users do not.
3. **One slice per release.** Do not stack unverified slices — that is how 0.3.x accumulated
   thirteen defects before anyone looked.
4. **If a slice cannot be made verifiable, do not do it.** Prefer leaving a fix unported over
   shipping an unfalsifiable change to this subsystem.
