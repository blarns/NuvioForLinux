package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The updater picks one release out of the GitHub response, and getting that pick wrong is not a
 * cosmetic bug: it is how people end up stranded on a build with nothing newer to move to.
 *
 * The fixtures below mirror the real release history, newest-created first, because the ordering
 * is the whole point — GitHub sorts by creation date and the highest version is not necessarily
 * at the top.
 */
class AppUpdaterReleaseSelectionTest {

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
        targetCommitish: String? = "cmp-rewrite",
    ) = GitHubReleaseDto(
        tagName = tag,
        name = "NuvioForLinux $tag",
        draft = draft,
        prerelease = prerelease,
        targetCommitish = targetCommitish,
        assets = listOf(
            GitHubAssetDto(
                name = "nuvio_${tag.removePrefix("v")}_amd64.deb",
                browserDownloadUrl = "https://example.invalid/${tag}.deb",
                size = 1L,
                contentType = "application/vnd.debian.binary-package",
            ),
        ),
    )

    /** The live list as published, newest-created first. */
    private val publishedReleases = listOf(
        release("v0.3.1", prerelease = true),
        release("v0.3.0", prerelease = true),
        release("v0.2.3.1"),
        release("v0.2.3"),
        release("v0.2.2"),
        release("v0.2.1"),
        release("v0.2.0"),
        release("v0.1.31"),
        release("v0.1.30"),
        release("v0.1.29"),
        release("v0.1.28"),
    )

    // ---- version ordering -------------------------------------------------------------------

    @Test
    fun compares_numerically_rather_than_lexicographically() {
        assertTrue(VersionUtils.compareVersions("v0.2.10", "v0.2.9") > 0)
        assertTrue(VersionUtils.compareVersions("v0.1.31", "v0.1.9") > 0)
    }

    @Test
    fun orders_the_stable_successor_above_both_withdrawn_alphas() {
        assertTrue(VersionUtils.compareVersions("v0.3.2", "v0.3.1") > 0)
        assertTrue(VersionUtils.compareVersions("v0.3.2", "v0.3.0") > 0)
        assertTrue(VersionUtils.compareVersions("v0.3.2", "v0.2.3.1") > 0)
    }

    @Test
    fun treats_a_fourth_component_as_a_bump() {
        assertTrue(VersionUtils.compareVersions("v0.2.3.1", "v0.2.3") > 0)
        assertEquals(0, VersionUtils.compareVersions("v0.2.3.0", "v0.2.3"))
    }

    @Test
    fun sorts_an_unparseable_tag_below_every_real_version() {
        assertTrue(VersionUtils.compareVersions("nightly", "v0.1.0") < 0)
        assertEquals(0, VersionUtils.compareVersions("nightly", "rolling"))
    }

    // ---- selection --------------------------------------------------------------------------

    @Test
    fun stable_channel_picks_the_newest_stable() {
        val picked = selectChannelRelease(publishedReleases, allowPrerelease = false)
        assertEquals("v0.2.3.1", picked?.tagName)
    }

    @Test
    fun experimental_channel_is_no_longer_pushed_onto_the_withdrawn_alpha() {
        // Before the withdrawal this returned v0.3.1. Opting in must not reach a pulled build.
        val picked = selectChannelRelease(publishedReleases, allowPrerelease = true)
        assertEquals("v0.2.3.1", picked?.tagName)
    }

    @Test
    fun withdrawn_tags_are_refused_even_when_they_are_the_only_candidates() {
        val onlyAlphas = publishedReleases.filter { it.prerelease }
        assertNull(selectChannelRelease(onlyAlphas, allowPrerelease = true))
    }

    @Test
    fun a_higher_version_wins_even_when_it_is_not_the_newest_entry() {
        // The original bug: only the first matching entry was considered, so a later-published
        // but lower-numbered release hid everything above it.
        val withSuccessor = listOf(release("v0.2.3.2")) + listOf(release("v0.3.2")) + publishedReleases
        val picked = selectChannelRelease(withSuccessor, allowPrerelease = false)
        assertEquals("v0.3.2", picked?.tagName)
    }

    @Test
    fun publishing_0_3_2_reaches_everyone_on_both_channels() {
        val withSuccessor = listOf(release("v0.3.2")) + publishedReleases
        assertEquals("v0.3.2", selectChannelRelease(withSuccessor, allowPrerelease = false)?.tagName)
        assertEquals("v0.3.2", selectChannelRelease(withSuccessor, allowPrerelease = true)?.tagName)

        // ...and it outranks both alphas, so the updater already installed on a stranded 0.3.0 or
        // 0.3.1 machine treats it as an ordinary upgrade.
        assertTrue(VersionUtils.isRemoteNewer("v0.3.2", "0.3.0"))
        assertTrue(VersionUtils.isRemoteNewer("v0.3.2", "0.3.1"))
    }

    @Test
    fun drafts_and_other_branches_are_ignored() {
        val noise = listOf(
            release("v9.9.9", draft = true),
            release("v9.9.8", targetCommitish = "main"),
        ) + publishedReleases
        assertEquals("v0.2.3.1", selectChannelRelease(noise, allowPrerelease = false)?.tagName)
    }

    @Test
    fun an_unparseable_tag_never_beats_a_real_release() {
        val noise = listOf(release("nightly")) + publishedReleases
        assertEquals("v0.2.3.1", selectChannelRelease(noise, allowPrerelease = false)?.tagName)
    }

    @Test
    fun an_empty_channel_yields_nothing_rather_than_guessing() {
        assertNull(selectChannelRelease(emptyList(), allowPrerelease = true))
    }
}
