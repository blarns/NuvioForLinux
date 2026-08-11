package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopRestoreTest {

    @Test
    fun `stage then apply replaces the data dir and keeps the old one aside`() {
        val (parent, root) = newDataDir()
        root.resolve("nuvio_profiles.properties").writeText("who=live")
        root.resolve("nuvio_window.properties").writeText("width=1280")

        val zip = zipOf(parent, "nuvio_profiles.properties" to "who=backed-up")
        assertEquals(1, DesktopRestore.stage(zip.toString(), root).getOrThrow())

        // Staging must not have touched the live data — the app is still running at this point.
        assertEquals("who=live", root.resolve("nuvio_profiles.properties").readText())

        DesktopRestore.applyPendingIfNeeded(root)

        assertEquals("who=backed-up", root.resolve("nuvio_profiles.properties").readText())
        // The backup did not contain this one, so a restore is a replacement, not a merge.
        assertFalse(root.resolve("nuvio_window.properties").exists())
        assertEquals(
            "width=1280",
            parent.resolve("nuvio-restore-previous/nuvio_window.properties").readText(),
        )
        assertFalse(parent.resolve("nuvio-restore-pending").exists())
    }

    @Test
    fun `apply is a no-op when nothing is staged`() {
        val (_, root) = newDataDir()
        root.resolve("nuvio_profiles.properties").writeText("who=live")

        DesktopRestore.applyPendingIfNeeded(root)

        assertEquals("who=live", root.resolve("nuvio_profiles.properties").readText())
    }

    @Test
    fun `apply replays after a crash without losing the pre-restore copy`() {
        val (parent, root) = newDataDir()
        root.resolve("nuvio_profiles.properties").writeText("who=live")

        val zip = zipOf(parent, "nuvio_profiles.properties" to "who=backed-up")
        DesktopRestore.stage(zip.toString(), root).getOrThrow()

        // Simulate dying right after the data dir was moved aside: `previous` holds the real
        // pre-restore data, `root` is a half-written mess, `pending` is still there.
        val root2 = root.also {
            Files.walk(it).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
        parent.resolve("nuvio-restore-previous").createDirectories()
        parent.resolve("nuvio-restore-previous/nuvio_profiles.properties").writeText("who=live")
        root2.createDirectories()
        root2.resolve("half-written.properties").writeText("junk")

        DesktopRestore.applyPendingIfNeeded(root2)

        assertEquals("who=backed-up", root2.resolve("nuvio_profiles.properties").readText())
        assertFalse(root2.resolve("half-written.properties").exists())
        // Still the original, not the half-written attempt.
        assertEquals(
            "who=live",
            parent.resolve("nuvio-restore-previous/nuvio_profiles.properties").readText(),
        )
    }

    @Test
    fun `a zip with no nuvio data is rejected before anything is touched`() {
        val (parent, root) = newDataDir()
        root.resolve("nuvio_profiles.properties").writeText("who=live")

        val zip = zipOf(parent, "holiday.jpg" to "not a backup")
        assertTrue(DesktopRestore.stage(zip.toString(), root).isFailure)

        assertFalse(parent.resolve("nuvio-restore-pending").exists())
        assertEquals("who=live", root.resolve("nuvio_profiles.properties").readText())
    }

    @Test
    fun `an entry escaping the staging dir is refused`() {
        val (parent, root) = newDataDir()
        val zip = zipOf(
            parent,
            "nuvio_profiles.properties" to "who=backed-up",
            "../../escaped.properties" to "pwned",
        )

        assertTrue(DesktopRestore.stage(zip.toString(), root).isFailure)
        assertFalse(parent.resolve("escaped.properties").exists())
        assertFalse(parent.parent.resolve("escaped.properties").exists())
    }

    @Test
    fun `a missing file fails rather than throwing`() {
        val (parent, root) = newDataDir()
        assertTrue(DesktopRestore.stage(parent.resolve("nope.zip").toString(), root).isFailure)
    }

    /** A temp stand-in for `~/.config` plus the `nuvio` data dir inside it. */
    private fun newDataDir(): Pair<Path, Path> {
        val parent = Files.createTempDirectory("nuvio-restore-test")
        parent.toFile().deleteOnExit()
        val root = parent.resolve("nuvio").createDirectories()
        return parent to root
    }

    private fun zipOf(dir: Path, vararg entries: Pair<String, String>): Path {
        val zip = dir.resolve("backup.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            entries.forEach { (name, body) ->
                out.putNextEntry(ZipEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }
        return zip
    }
}
