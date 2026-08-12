# Release channels and version numbering

Read this before publishing any release, and before picking the next version number.

## Where the code lives

`cmp-rewrite` is the **release channel branch**, and that is not a preference — every installed
build resolves updates through `AppUpdater.matchesRequestedChannel()`, which requires a release's
`target_commitish` to be `cmp-rewrite`. Existing installs cannot be told otherwise, so releases
have to keep coming from that name whatever else changes.

`cmp-rewrite` currently points at the **abandoned 1:1 upstream port** (`v0.3.0` / `v0.3.1`). The
stable line is this branch, cut from `v0.2.3.1`. Before `v0.3.2` is published, one of those two
has to give: either `cmp-rewrite` is moved back onto the stable line, or `release.yml`'s hardcoded
`target_commitish` is pointed at whatever branch stable now lives on. Publishing without settling
that produces a release whose tag points at port code and whose artifacts are stable code.

## Why the next stable release is 0.3.2 and not 0.2.10

`0.2.9` was going to run out of road anyway, but that is not what decided this. Two builds are
sitting on withdrawn alphas, and **the version number is the only lever that reaches them** — they
are running `v0.3.0` / `v0.3.1` code, so no fix shipped today can change how they behave. Their
updaters ask one question: *is the offered tag higher than mine?*

| Next stable is… | `v0.2.x` user | `v0.3.1` user | `v0.3.0` user |
| --- | --- | --- | --- |
| `0.2.10` | offered | **not offered** — lower than installed | **not offered**, and 0.3.0 has no way back |
| `0.3.2`  | offered | offered | offered |

Numbering it `0.3.2` turns every stranded install into an ordinary forward upgrade. No
`--allow-downgrades`, no manual reinstall, no "turn the experimental toggle off first" — and on
`0.3.0`, which shipped before the *Return to stable release* button existed, it is the only route
that works at all.

The cost is that the `0.3.x` name now covers both the withdrawn alphas and the stable line. Say so
plainly in the release notes; it is cheaper than stranding people.

Numbering is safe either way: `VersionUtils.parseVersionParts` splits on `.`/`-`/`_` and compares
components as `Int`, so `0.2.10 > 0.2.9` numerically, with no lexicographic trap. A fourth
component works too — `0.2.3.1` already shipped that way.

## The updater cannot push a downgrade

There is no server-side lever that moves an installed build backwards. Editing a release, clearing
a flag, deleting a tag — none of it changes what an installed copy does. `VersionUtils.isRemoteNewer`
refuses anything not newer than what is installed, and `getLatestChannelUpdate` is the only fetch
path. `v0.3.1` alone has `returnToStableChannel()`, which bypasses that comparison on purpose.

So withdrawing a release is an *announcement*, not a mechanism. Plan for every affected user having
to act manually, or give them a higher version number to move to — which is what `0.3.2` is.

## How a release is chosen (fixed in 0.3.2)

`selectChannelRelease()` takes the **highest version** among releases that match the channel, are
not drafts, are allowed by the pre-release toggle, and are not withdrawn.

It used to take the *first* matching entry. GitHub orders releases by creation date, `release.yml`
puts every release on `cmp-rewrite` so they all match, and `allowPrerelease = true` means "do not
filter pre-releases out" rather than "prefer one" — so the sole candidate was simply the newest
release of any kind. Publishing a stable build after an alpha therefore made the lower-numbered
release the only candidate and told alpha users they were up to date. That is the bug that would
have stranded `v0.3.0` permanently, and it is covered by `AppUpdaterReleaseSelectionTest`.

Two related fixes went in with it:

- The release list is **paginated** (100 per page, up to 5 pages). It used to read one page of 20;
  with 30 releases published it could not see the whole history, which the highest-version pick
  needs.
- **Withdrawn releases are refused by tag.** `withdrawnReleaseTags` holds `0.3.0` and `0.3.1`. They
  stay published so existing installs can still reach them, but no update check offers them again.
  Add to that set when a release is pulled; do not delete releases to achieve the same thing.

## Rules that already exist and should not be undone

- Keep release tags **plain** (`v0.3.2`), never `v0.3.2-alpha.N`. `version` must equal
  `MARKETING_VERSION`, and a tag that sorts above the installed version shows a permanent update
  banner.
- Never put `cmp-rewrite` in a release title — `matchesRequestedChannel()` also matches the branch
  name appearing in the tag or name.
- `CHANGELOG.md` must have a `## [<version>]` section before the release job will run.
- Run `release.yml` with `dry_run=true` first.

## Graduating a pre-release is a one-click cliff

Promoting an alpha is just clearing the `prerelease` flag on the existing GitHub release — same
artifacts, no rebuild. With the highest-version pick that instantly offers it to *everyone* whose
installed version is lower. There is no staged rollout and no practical undo once people take it.
Treat clearing the flag as the deliberate cutover for the entire userbase, not as a tidy-up.
