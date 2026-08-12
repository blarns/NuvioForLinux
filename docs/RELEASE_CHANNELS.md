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

**That route also closes the next time anything is published** — see hazard 3 below. Manual
installation of `v0.2.3.1` is the only route that keeps working, which is why
[#5](https://github.com/blarns/NuvioForLinux/issues/5) leads with it.

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

## Hazards to decide about

Hazards 1 and 2 bite when stable reaches `0.2.9`. **Hazard 3 bites on the very next release of any
kind**, so read that one first if something is about to be published.

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

### 3. The experimental channel resolves by publish date, not by version

`getLatestChannelUpdate` considers exactly one candidate:

```kotlin
val release = releases.firstOrNull {
    it.matchesRequestedChannel() && !it.draft && (allowPrerelease || !it.prerelease)
} ?: throw NoChannelReleaseException()
```

Three things combine badly here. GitHub's `/releases` returns newest-**created** first, not
highest-version. `release.yml` hardcodes `target_commitish: cmp-rewrite`, so *every* release
matches the channel. And `allowPrerelease = true` means "do not filter pre-releases out" — not
"prefer a pre-release" — so with the experimental toggle on the predicate is satisfied by the
newest release of any kind. `checkForUpdates` then compares that single candidate against the
installed version and gives up if it is not newer; it never looks at the second entry.

So publishing a stable `v0.2.3.2` today would tell every `v0.3.0` user with the toggle on that
they are already up to date, and `v0.3.1` — the build carrying their only in-app way back — would
become unreachable from inside the app. It costs them nothing on stable and everything on the
alpha, which is exactly the population least able to notice.

If an experimental channel is ever reopened, make this pick the highest *version* among matching
releases rather than the first, and paginate past `per_page=20` (the list is 30 releases and
counting, so the current call already cannot see the whole history).

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
