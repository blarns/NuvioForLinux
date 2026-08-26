# Release channels, versioning, and how to cut a release

Read this before publishing any release, and before picking the next version number.

**Current state:** latest release is **v0.3.7** (build 115, 2026-08-26). `cmp-rewrite` and the
`v0.3.7` tag both point at that commit. Development happens on **`dev`**.

---

## Where the code lives

`cmp-rewrite` is the **release channel branch**, and that is not a preference — every installed
build resolves updates through `AppUpdater.matchesRequestedChannel()`, which requires a release's
`target_commitish` to be `cmp-rewrite`. Existing installs cannot be told otherwise, so releases
have to keep coming from that name whatever else changes. A release created against `dev` is
invisible to every install in the field.

Day-to-day work lands on `dev`. `cmp-rewrite` moves **only when a release is cut**, and it moves
by fast-forward from `dev`.

> `stable/0.3.2` was deleted on origin and replaced by `dev`. A stale clone will still show it,
> and a prunable worktree for it.

---

## ⚠ The CI release workflow is broken — releases are built locally

`.github/workflows/release.yml` has failed since 2026-08-20 on `:composeApp:fetchTorrServer`:
the pinned TorrServer release was **deleted upstream**, so the whole tag 404s and a clean
checkout cannot build at all. Local builds only work because
`composeApp/desktop-resources/linux-x64/torrserver` already exists on the build machine and
matches the recorded sha256.

Re-pinning to a current TorrServer has been deliberately deferred: it ships a different
torrent-engine binary into the one subsystem nobody runtime-tests. Until that is decided,
**every release is built by hand using the procedure below**, and the workflow's checks have to
be run by hand with it.

---

## Cutting a release, step by step

### 1. Land the code and fast-forward `cmp-rewrite` FIRST

```sh
git checkout dev && git pull --ff-only
# bump iosApp/Configuration/Version.xcconfig (CURRENT_PROJECT_VERSION + MARKETING_VERSION)
# add a "## [<version>]" section to CHANGELOG.md
git commit -am "release: vX.Y.Z (build N)" && git push origin dev

git checkout cmp-rewrite
git reset --hard origin/cmp-rewrite      # ⚠ a stale local branch is how the v0.3.7 tag went wrong
git merge --ff-only dev
git push origin cmp-rewrite
```

> ⚠⚠ **`gh release create --target cmp-rewrite` creates the tag at that branch's *current* tip.**
> Publishing before the fast-forward tags the *previous* release's commit. Cutting v0.3.7 that
> way put the tag on the v0.3.6.2 commit — the uploaded binaries were correct and the updater was
> unaffected (it matches on the branch *name*), but the tag's source archives pointed at the wrong
> tree. The repair is `git push -f origin <sha>:refs/tags/vX.Y.Z`; the release, its assets and its
> "latest" status all survive a tag move. Doing it in the right order avoids the whole problem.

### 2. Check the build config before building

```sh
grep -i supabase local.properties     # BOTH lines must stay COMMENTED OUT
```

If they are live, the build bakes a raw Supabase project URL instead of the production backend
and sign-in breaks for everyone. `SupabaseConfig.URL` must end up as the production endpoint —
step 4 verifies this from the built artifact, not from intent.

### 3. Build both artifacts

```sh
rm -rf composeApp/build/compose/binaries
scripts/package-appimage.sh --version X.Y.Z --vlc-bundle appimage-build/vlc-bundle
JAVA_HOME=appimage-build/jdk/jdk-21* ./gradlew :composeApp:packageDeb -PpackageVersion=X.Y.Z
```

- `packageDeb`, **not** `packageReleaseDeb`.
- ⚠ **Always pass `--vlc-bundle`.** Omitted, the script silently takes libVLC from the *host* and
  pins the AppImage's glibc floor to the host's — the build succeeds and only an `objdump` check
  reveals it.
- ⚠ Both commands read `composeApp/build/compose/binaries`, and the deb build leaves a system-JDK
  jlink runtime behind. AppImage-then-deb is safe **as long as the deb build also gets**
  `JAVA_HOME` pointing at the staged Temurin JDK, as above.

### 4. Acceptance checks — all of them, every time

```sh
# tree must be clean, and both artifacts must be NEWER than the last commit
git status --short
```

> ⚠ **A release build owns the working tree.** Do no other work in it until the artifacts exist
> and are verified. A near-miss on v0.3.6.2: an unrelated edit landed mid-build and the `.deb`
> was written after it, so its contents were not trustworthy and it had to be rebuilt.

| Check | Command | Expected |
| --- | --- | --- |
| deb depends on VLC | `dpkg-deb --field <deb> Depends` | contains `vlc-plugin-base \| vlc` |
| backend endpoint baked in | extract the deb, then `javap -p -constants -cp <app jar> com.nuvio.app.core.network.SupabaseConfig` | production URL, non-empty anon key |
| glibc floor | `objdump -T` across `appimage-build/AppDir` | ≤ 2.35 |
| libva not bundled | look for `libva.so*`, `libva-drm.so*`, `libva-x11.so*` | none |

> ⚠ `find -name 'libva*'` gives a **false positive** — `libvaapi_plugin.so` and
> `libvaapi_drm_plugin.so` are VLC's own codec plugins and are supposed to be there. Match the
> library names, not the prefix.

### 5. Smoke-test the packaged artifact — mandatory

```sh
scripts/smoke-appimage.sh
```

This is not optional and not a formality. Cutting v0.3.7, every static check above passed — unit
tests, `javap` on the shipped jar, glibc floor, deb depends, clean tree — and the artifact was
still wrong twice:

- two backend RPCs fired before the auth session restored, so every cold start sent a pair of
  guaranteed-401 requests;
- the entire app-icon feature did nothing, because `src/desktopMain/resources` had never been
  registered as a resources dir and so no icon asset was ever packaged. It failed silently — the
  window-icon loader falls back to a default that resolves only because a duplicate copy happens
  to live in `src/jvmMain/resources`.

Unit tests, jar inspection and `gradlew run` are all structurally blind to both: the dev build
reads resources straight off the build tree and rides an already-warm session.

> ⚠⚠ **Never launch the artifact you are about to upload.** If AppImageLauncher is installed it
> intercepts execution via binfmt and *moves* the file to `~/Applications/<name>_<hash>.AppImage`
> — it vanishes from `appimage-build/dist/`, and if you do not notice, there is nothing left to
> upload. `scripts/smoke-appimage.sh` handles this (it disables AppImageLauncher and runs a
> temp-dir copy), which is the reason to use it rather than running the AppImage yourself.

### 6. Publish

```sh
gh release create vX.Y.Z -R blarns/NuvioForLinux --target cmp-rewrite \
  --title "NuvioForLinux vX.Y.Z" --notes-file <body> <appimage> <deb>
```

`gh release create` does **not** run the workflow's changelog extraction or add the install-table
footer — regenerate both from the `## [<version>]` section of `CHANGELOG.md`, or the release page
looks unlike every previous one.

Then confirm: `target_commitish` is `cmp-rewrite`, `prerelease` is false, both assets attached,
GitHub reports it as `latest`, and the tag resolves to the commit the binaries were built from.

### 7. Keep the tree clean

Prefer explicit `git add <paths>` over `git add -A` while releasing. A JVM crash log was swept
into a commit that way; `hs_err_pid*.log`, `replay_pid*.log` and `smoke-*.log` are now ignored,
but the habit is the real fix.

---

## Version numbering

`VersionUtils.parseVersionParts` splits on `.`/`-`/`_` and compares components as `Int`, so
`0.2.10 > 0.2.9` numerically with no lexicographic trap. **A fourth component works** — `0.2.3.1`
and `0.3.6.1` both shipped, and jpackage, `packageDeb` and `VersionUtils` all handle it.

Rules that should not be undone:

- Keep release tags **plain** (`v0.3.7`), never `v0.3.7-alpha.N`. `version` must equal
  `MARKETING_VERSION`; a tag sorting above the installed version shows a permanent update banner.
- **Never put `cmp-rewrite` in a release title** — `matchesRequestedChannel()` also matches the
  branch name appearing in a tag or name.
- `CHANGELOG.md` must have a `## [<version>]` section.

### Historical: why stable resumed at 0.3.2 rather than 0.2.10

The `v0.3.0` / `v0.3.1` alphas — a 1:1 upstream port — were withdrawn and are not being
continued. `v0.3.2` was stable code (0.2.3.1 plus updater fixes) deliberately given the higher
number, because the version number was the only lever that could reach installs stranded on those
alphas: their updaters ask only whether the offered tag is higher than the installed one.
Numbering it `0.3.2` turned every stranded install into an ordinary forward upgrade. That cliff is
resolved, not pending — do not re-derive the old plan.

---

## The updater cannot push a downgrade

There is no server-side lever that moves an installed build backwards. Editing a release, clearing
a flag, deleting a tag — none of it changes what an installed copy does. `VersionUtils.isRemoteNewer`
refuses anything not newer than what is installed, and `getLatestChannelUpdate` is the only fetch
path. `v0.3.1` alone has `returnToStableChannel()`, which bypasses that comparison on purpose.

So withdrawing a release is an **announcement, not a mechanism**. Plan for every affected user
having to act manually, or give them a higher version number to move to.

## How a release is chosen

`selectChannelRelease()` takes the **highest version** among releases that match the channel, are
not drafts, are allowed by the pre-release toggle, and are not withdrawn.

It used to take the *first* matching entry, which — because GitHub orders by creation date and
every release matches the channel — meant simply the newest release of any kind. Publishing a
stable build after an alpha therefore made the lower-numbered release the only candidate and told
alpha users they were up to date. Covered by `AppUpdaterReleaseSelectionTest`. Two related fixes
went in with it and must not be regressed:

- The release list is **paginated** (100 per page, up to 5 pages). It used to read one page of 20,
  which cannot support a highest-version pick once there are more releases than that.
- **Withdrawn releases are refused by tag.** `withdrawnReleaseTags` holds `0.3.0` and `0.3.1`.
  They stay published so existing installs can still reach them, but no update check offers them
  again. Add to that set when a release is pulled; do not delete releases to achieve the same
  thing.

## Graduating a pre-release is a one-click cliff

Promoting an alpha is just clearing the `prerelease` flag on the existing GitHub release — same
artifacts, no rebuild. With the highest-version pick that instantly offers it to *everyone* whose
installed version is lower. There is no staged rollout and no practical undo once people take it.
Treat clearing the flag as the deliberate cutover for the entire userbase, not as a tidy-up.
