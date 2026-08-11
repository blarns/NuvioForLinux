# Release channels and version numbering

Read this before publishing any release, and before picking the next version number.

## Status: the 0.3.x alpha is WITHDRAWN (2026-08-11)

The experimental line is not being continued. **The supported build is `v0.2.3.1`, and it is the
only build anyone should be directed to.** See
[#5](https://github.com/blarns/NuvioForLinux/issues/5).

`v0.3.0` and `v0.3.1` remain published as pre-releases, deliberately. Do not delete them:
**`v0.3.0` has no "Return to stable release" button** — that shipped in `v0.3.1` — so a `v0.3.0`
user's only in-app route back is to take `v0.3.1` first and use the button there. Deleting
`v0.3.1` strands them on a build they cannot leave from inside the app.

The rest of this document still describes how the channels work, and applies again if an
experimental channel is ever reopened.

## The short version

**Every `v0.3.x` release is an ALPHA.** The stable channel is still `v0.2.x`.

| Channel      | Resolves to | How a user gets it |
| ------------ | ----------- | ------------------ |
| stable       | `v0.2.3.1`  | default — nothing to turn on |
| experimental | `v0.3.1` (withdrawn) | Settings → About → **Experimental updates** |

Published `v0.3.0` and `v0.3.1` are both GitHub pre-releases pointing at the CMP port on
`cmp-rewrite`. `AppUpdater` skips pre-releases unless the user opted in, so nobody on a stable
build is offered them.

## The updater cannot push a downgrade

There is no server-side lever that moves an installed build backwards. Editing a release, clearing
a flag, deleting a tag — none of it changes what an installed copy does.
`VersionUtils.isRemoteNewer` refuses anything not newer than what is installed
(`AppUpdater.kt:104`), and `getLatestChannelUpdate` is the only fetch path
(`AppUpdater.kt:128`). The single exception is `returnToStableChannel()`
(`AppUpdater.kt:~300`), which bypasses that comparison on purpose — and it only exists in `v0.3.1`
and later.

So withdrawing a release is an *announcement*, not a mechanism. Plan for every affected user having
to act manually, and check which builds actually contain the button before assuming there is an
in-app path.

## Two hazards to decide about before stable reaches 0.2.9

### 1. Running out of 0.2.x numbers

The natural bump after `0.2.9` is `0.3.0`, and that tag is already spent on the alpha. It is not
free to reuse: `.github/workflows/release.yml` hardcodes `target_commitish: cmp-rewrite`, so a tag
lands wherever that branch points — and that branch *is* the port.

**This is the easy half.** Stable can simply keep counting: `0.2.10`, `0.2.11`, … That is safe
because `VersionUtils.parseVersionParts` (in `AppUpdater.kt`) splits on `.`/`-`/`_` and compares
each component as an `Int`, so `0.2.10 > 0.2.9` numerically — there is no lexicographic trap.
A fourth component works too; `0.2.3.1` already shipped that way.

### 2. Graduating an alpha to stable is a one-click cliff

Promoting an alpha is just clearing the pre-release flag on the existing GitHub release — same
artifacts, no rebuild. But `AppUpdaterRepository.getLatestChannelUpdate` takes the **first**
matching release, so the instant that flag is cleared, *every* `0.2.x` user is offered `0.3.x`.
There is no staged rollout, and no practical undo once people have taken it.

**Treat clearing `prerelease` as the deliberate cutover for the entire stable userbase**, not as
a tidy-up. This is the thing that would actually break stable users — more than any tag collision.

## Rules that already exist and should not be undone

- Keep release tags **plain** (`v0.3.0`), never `v0.3.0-alpha.N`. `version` must equal
  `MARKETING_VERSION`, and a tag that sorts above the installed version shows a permanent update
  banner to alpha users.
- `target_commitish` must stay `cmp-rewrite` — the in-app updater only shows releases whose
  `target_commitish` matches its channel branch.
- Run `release.yml` with `dry_run=true` first.

## Safety valves for people who took an alpha (added in 0.3.1)

- **Settings → About → Return to stable release** — offers the newest stable build even though it
  is *older* than what is installed, and switches the experimental channel off.
- **Restore from backup** — staged at pick time, applied at the next launch.
- **`nuvio-backup-<stamp>-restore.sh`**, written next to each backup zip, for the case that
  matters most: a downgrade that went wrong leaves you on an older build with no restore UI in it.
