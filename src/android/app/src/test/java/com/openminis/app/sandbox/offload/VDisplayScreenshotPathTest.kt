package com.openminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-vdisplay-cli] Where `android-vdisplay screenshot` may write.
 *
 * The requirement is "no arbitrary path overwrite": a capture goes under the
 * session's `/var/minis/attachments` mount — the only namespace the sandbox
 * bind-mounts, and the one `read_image` and chat links already resolve — and an
 * existing file is never replaced.
 *
 * Pure-Kotlin, so every rule is checkable on the JVM. The handler's own use of
 * the resolver (`PRootKernel.resolveSessionHostPath`) is asserted separately in
 * `VDisplayScreenshotMountTest`.
 */
class VDisplayScreenshotPathTest {

    private fun ok(output: String?, ts: Long = 0L): VDisplayScreenshotPath.Resolved.Ok {
        val r = VDisplayScreenshotPath.resolve(output, ts)
        assertTrue("expected '$output' to be accepted, got $r", r is VDisplayScreenshotPath.Resolved.Ok)
        return r as VDisplayScreenshotPath.Resolved.Ok
    }

    private fun rejected(output: String?): String {
        val r = VDisplayScreenshotPath.resolve(output, 0L)
        assertTrue("expected '$output' to be rejected, got $r", r is VDisplayScreenshotPath.Resolved.Rejected)
        return (r as VDisplayScreenshotPath.Resolved.Rejected).message
    }

    private fun rejectedOrContains(output: String?, needle: String): Boolean =
        rejected(output).contains(needle)

    // ── accepted forms ──────────────────────────────────────────────────

    @Test
    fun `default output is a timestamped png under the vdisplay subdir`() {
        // Stamp is UTC-fixed in the implementation, so the expected name is a
        // pure function of the instant — no dependence on the host timezone.
        val r = ok(null, 0L)
        assertEquals("vdisplay/vdisplay_19700101-000000-000.png", r.relativePath)
        assertEquals("vdisplay_19700101-000000-000.png", r.fileName)
        assertEquals(
            "vdisplay/vdisplay_20260101-120000-500.png",
            ok(null, 1_767_268_800_500L).relativePath,
        )
    }

    @Test
    fun `a bare file name lands in the vdisplay subdir`() {
        assertEquals("vdisplay/shot.png", ok("shot.png").relativePath)
        // Uppercase extension is accepted; the name is preserved verbatim.
        assertEquals("vdisplay/SHOT.PNG", ok("SHOT.PNG").relativePath)
    }

    @Test
    fun `an absolute path is accepted only under the attachments mount`() {
        assertEquals(
            "vdisplay/a.png",
            ok("/var/minis/attachments/vdisplay/a.png").relativePath,
        )
        assertEquals(
            "a.png",
            ok("/var/minis/attachments/a.png").relativePath,
        )
    }

    @Test
    fun `a path relative to the mount root is accepted`() {
        assertEquals("vdisplay/a.png", ok("vdisplay/a.png").relativePath)
    }

    @Test
    fun `a resolved path never escapes the mount`() {
        for (out in listOf(null, "a.png", "/var/minis/attachments/vdisplay/a.png", "vdisplay/a.png")) {
            val rel = ok(out).relativePath
            assertTrue("$out -> $rel", !rel.startsWith("/"))
            assertTrue("$out -> $rel", !rel.split('/').contains(".."))
            assertTrue("$out -> $rel", !rel.split('/').contains("."))
        }
    }

    // ── rejected forms ──────────────────────────────────────────────────

    @Test
    fun `any other absolute path is refused`() {
        // The exact paths an agent would reach for if it were thinking in
        // shell terms, plus the trap that started the a11y bug: a private
        // directory the sandbox does not mount.
        for (out in listOf(
            "/sdcard/Pictures/a.png",
            "/var/minis/workspace/a.png",
            "/var/minis/shared/a.png",
            "/var/minis/attachments-evil/a.png",
            "/data/local/tmp/a.png",
            "/var/minis/attachments/../workspace/a.png",
        )) {
            val msg = rejected(out)
            assertTrue("'$out' -> $msg", msg.contains("/var/minis/attachments"))
        }
    }

    @Test
    fun `traversal segments are refused even inside the mount`() {
        for (out in listOf(
            "vdisplay/../../workspace/a.png",
            "vdisplay/./a.png",
            "/var/minis/attachments/../../data/a.png",
        )) {
            rejected(out)
        }
    }

    @Test
    fun `only png output is accepted`() {
        for (out in listOf("a.jpg", "a.png.exe", "a", "a.PNGX")) {
            assertTrue("'$out'", rejected(out).contains(".png"))
        }
    }

    @Test
    fun `a directory or empty target is refused`() {
        assertTrue(rejected("").contains("must not be empty"))
        assertTrue(rejected("/var/minis/attachments").contains("is a directory"))
        assertTrue(rejected("/var/minis/attachments/").contains("is a directory"))
        assertTrue(rejected("vdisplay/").contains("must name a file"))
    }

    @Test
    fun `hidden files and empty segments are refused`() {        assertTrue(rejected(".hidden.png").contains("hidden"))
        assertTrue(rejected("vdisplay/.hidden.png").contains("hidden"))
        rejected("vdisplay//a.png")
    }

    @Test
    fun `control characters in the name are refused`() {
        rejected("a\u0000.png")
        rejected("a\nb.png")
    }

    @Test
    fun `non-ascii and percent-encoded names are judged by their literal bytes`() {
        // A percent-encoded separator is never decoded by the guest shell: it
        // reaches the resolver as a literal name. It is NOT a '..' segment and
        // must not be reported as traversal — here it fails the hidden-file
        // rule ('..' prefix), which is the honest mechanism to pin.
        val msg = rejected("vdisplay/..%2Fa.png")
        assertTrue(
            "'..%2F' must fail on the hidden-file rule, not on a traversal claim: $msg",
            msg.contains("hidden"),
        )
        // %2F as a later path segment is a legal literal name: accepted as-is.
        val r = VDisplayScreenshotPath.resolve("vdisplay/%2Fetc.png", 0L)
        assertTrue(r is VDisplayScreenshotPath.Resolved.Ok)
        assertEquals("vdisplay/%2Fetc.png", (r as VDisplayScreenshotPath.Resolved.Ok).relativePath)
        // ...and never silently decoded into an escape.
        assertTrue(
            (r as VDisplayScreenshotPath.Resolved.Ok).relativePath.split('/').none { it == ".." },
        )
    }

    @Test
    fun `backslashes are normalised rather than treated as escapes`() {
        assertEquals("vdisplay/a.png", ok("vdisplay\\a.png").relativePath)
    }

    // ── no overwrite ────────────────────────────────────────────────────

    @Test
    fun `a free name is used as-is`() {
        val r = VDisplayScreenshotPath.uniqueName("vdisplay/a.png", exists = { false })
        assertTrue(r is VDisplayScreenshotPath.Resolved.Ok)
        assertEquals("vdisplay/a.png", (r as VDisplayScreenshotPath.Resolved.Ok).relativePath)
        assertEquals("a.png", r.fileName)
    }

    @Test
    fun `an existing file is never replaced, a suffix is appended`() {
        val taken = setOf("vdisplay/a.png", "vdisplay/a-1.png")
        val r = VDisplayScreenshotPath.uniqueName("vdisplay/a.png", exists = { it in taken })
        assertTrue(r is VDisplayScreenshotPath.Resolved.Ok)
        assertEquals("vdisplay/a-2.png", (r as VDisplayScreenshotPath.Resolved.Ok).relativePath)
    }

    @Test
    fun `uniqueness works at the mount root too`() {
        val r = VDisplayScreenshotPath.uniqueName("a.png", exists = { it == "a.png" })
        assertEquals("a-1.png", (r as VDisplayScreenshotPath.Resolved.Ok).relativePath)
    }

    @Test
    fun `an exhausted name space fails instead of looping`() {
        val r = VDisplayScreenshotPath.uniqueName("a.png", exists = { true }, maxAttempts = 3)
        assertTrue(r is VDisplayScreenshotPath.Resolved.Rejected)
    }

    @Test
    fun `default names differ across captures`() {
        // Millisecond stamps are what keep `screenshot` alone (no --output)
        // from colliding when an agent polls.
        val a = VDisplayScreenshotPath.defaultFileName(1_700_000_000_000L)
        val b = VDisplayScreenshotPath.defaultFileName(1_700_000_000_001L)
        assertTrue("$a vs $b", a != b)
        assertTrue(a.startsWith("vdisplay_"))
        assertTrue(a.endsWith(".png"))
    }

    // ── canonical containment (symlink escape gate) ─────────────────────

    @Test
    fun `a canonical path inside the mount root is accepted`() {
        val r = VDisplayScreenshotPath.canonicalContainment(
            "vdisplay/a.png",
            canonicalize = { rel -> "/data/data/app/files/minis/attachments/$rel" },
        )
        assertTrue(r is VDisplayScreenshotPath.Resolved.Ok)
        assertEquals("vdisplay/a.png", (r as VDisplayScreenshotPath.Resolved.Ok).relativePath)
    }

    @Test
    fun `a symlink that canonicalises outside the root is refused`() {
        // attachmentsHostDir itself resolved to a symlinked location is fine
        // (both sides canonicalise), but a link INSIDE the mount pointing out
        // is not: the child resolves elsewhere, the root stays put.
        val r = VDisplayScreenshotPath.canonicalContainment(
            "vdisplay/link.png",
            canonicalize = { rel ->
                if (rel == "vdisplay/link.png") "/sdcard/evil.png" else "/data/data/app/files/minis/attachments/$rel"
            },
        )
        assertTrue(r is VDisplayScreenshotPath.Resolved.Rejected)
        assertTrue(
            (r as VDisplayScreenshotPath.Resolved.Rejected).message.contains("/var/minis/attachments"),
        )
    }

    @Test
    fun `a prefix-sibling directory is not treated as inside the root`() {
        // /attachments-evil starts with /attachments — the trailing '/' in the
        // prefix check is what keeps it out.
        val r = VDisplayScreenshotPath.canonicalContainment(
            "vdisplay/a.png",
            canonicalize = { rel ->
                if (rel == VDisplayScreenshotPath.ATTACHMENTS_ROOT) "/data/data/app/files/minis/attachments"
                else "/data/data/app/files/minis/attachments-evil/vdisplay/a.png"
            },
        )
        assertTrue(r is VDisplayScreenshotPath.Resolved.Rejected)
    }
}
