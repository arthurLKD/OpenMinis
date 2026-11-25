package com.openminis.app.sandbox.offload

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-vdisplay-cli] Source-level contract of `VDisplayOffloadHandler`.
 *
 * The handler cannot be instantiated off-device (it takes a Context and talks
 * to Shizuku, PRootKernel and the virtual-display core), so — following
 * `A11yScreenshotPathTest` — the properties that must not silently regress are
 * pinned against its source text. Each assertion below corresponds to a
 * requirement from `docs/vdisplay/README.md`, and to a bug that the sibling
 * a11y handler actually shipped (private, unmounted screenshot directory;
 * globally-resolved attachments path landing in another chat).
 */
class VDisplayHandlerContractTest {

    private val handlerSrc: String by lazy {
        val f = File("src/main/java/com/openminis/app/sandbox/offload/VDisplayOffloadHandler.kt")
        assertTrue("missing ${f.absolutePath}", f.exists())
        f.readText()
    }

    /** Source with comment lines removed: prose must not satisfy a code assertion. */
    private val handlerCode: String by lazy {
        handlerSrc.lineSequence()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .joinToString("\n")
    }

    private val screenshotBody: String by lazy {
        handlerCode.substringAfter("private fun screenshotCommand(")
            .substringBefore("\n    // ── helpers")
    }

    // ── permission gating ───────────────────────────────────────────────

    @Test
    fun `the agent gate is the separate vdisplay permission`() {
        assertTrue(
            "must gate on its own OffloadPermissionManager entry, not shizuku_cli",
            handlerCode.contains("""OffloadGate.allow("vdisplay", VDisplayCli.TOOL, request)"""),
        )
        assertFalse(
            "must not ride on the shizuku_cli grant",
            handlerCode.contains("""OffloadGate.allow("shizuku_cli\""""),
        )
        assertFalse(
            "nor on a11y_cli's",
            handlerCode.contains("""OffloadGate.allow("a11y_cli\""""),
        )
    }

    @Test
    fun `status and help stay readable when the gate is closed`() {
        // `status` must be exempt so a refusal is diagnosable; the exemption is
        // expressed as "everything except Status passes the gate".
        assertTrue(
            "status must skip the permission check",
            handlerCode.contains("if (command !is VDisplayCli.Command.Status)"),
        )
        assertTrue(
            "help returns before any gate",
            handlerCode.contains("return NativeOffloadResult(0, VDisplayCli.HELP)"),
        )
    }

    @Test
    fun `an unresolvable mount fails loudly instead of writing to a private dir`() {
        assertTrue(screenshotBody.contains("NO_ATTACHMENTS_MOUNT"))
        for (forbidden in listOf("getExternalFilesDir", "cacheDir", "getExternalCacheDir", "filesDir")) {
            assertFalse(
                "$forbidden is mounted nowhere the agent can read",
                screenshotBody.contains(forbidden),
            )
        }
        // A known session whose mount cannot be resolved must NOT fall back to
        // the global mount map (another chat's directory) — fail instead.
        assertFalse(
            "no global-map fallback for the screenshot destination",
            screenshotBody.contains("resolveHostPath("),
        )
    }

    @Test
    fun `shizuku is checked after the permission and only for real commands`() {
        val gateIndex = handlerCode.indexOf("OffloadGate.allow(\"vdisplay\"")
        val shizukuIndex = handlerCode.indexOf("shizukuUnavailable(args)")
        assertTrue("gate must exist", gateIndex >= 0)
        assertTrue("shizuku check must exist", shizukuIndex >= 0)
        assertTrue(
            "the gate must be consulted before Shizuku: permission is the stronger boundary",
            gateIndex < shizukuIndex,
        )
        assertTrue(
            "both checks live inside the non-status branch",
            handlerCode.substring(gateIndex, shizukuIndex).contains("if (!allowed)"),
        )
    }

    @Test
    fun `the permission-denied envelope points at the settings page`() {
        val denied = handlerCode.substringAfter("""if (!allowed) {""").substringBefore("val shizukuFailure")
        assertTrue("PERMISSION_DENIED", denied.contains("\"PERMISSION_DENIED\""))
        assertTrue("must name the settings location", denied.contains("Settings → Permissions"))
        assertTrue("must mention that status still works", denied.contains("status"))
        assertTrue("exit 126 = permission, matching OffloadGate", denied.contains("126"))
    }

    // ── screenshot destination ──────────────────────────────────────────

    @Test
    fun `the destination is validated before the frame is captured`() {
        // A rejected --output must cost nothing and must be an argument error.
        val resolveAt = screenshotBody.indexOf("VDisplayScreenshotPath.resolve(")
        val captureAt = screenshotBody.indexOf("VirtualDisplayManager.capture()")
        assertTrue("resolve must be present", resolveAt >= 0)
        assertTrue("capture must be present", captureAt >= 0)
        assertTrue("resolve must run first", resolveAt < captureAt)
        val head = screenshotBody.substring(0, captureAt)
        assertTrue("rejection is INVALID_ARGS with exit 2", head.contains("\"INVALID_ARGS\"") && head.contains(", 2)"))
    }

    @Test
    fun `an existing file is never overwritten`() {
        assertTrue(
            "must go through the uniqueness helper",
            screenshotBody.contains("VDisplayScreenshotPath.uniqueName("),
        )
        assertTrue(
            "which needs the real filesystem as its oracle",
            screenshotBody.contains("exists = { rel -> fileOf(rel).exists() }"),
        )
    }

    @Test
    fun `the open is CREATE_NEW so a lost race cannot truncate an existing capture`() {
        assertTrue(
            "must use an atomic CREATE_NEW open",
            screenshotBody.contains("StandardOpenOption.CREATE_NEW"),
        )
        assertFalse(
            "a plain FileOutputStream silently truncates",
            screenshotBody.contains("FileOutputStream"),
        )
        assertTrue(
            "the FileAlreadyExistsException race must be a typed WRITE_FAILED",
            screenshotBody.contains("FileAlreadyExistsException"),
        )
    }

    @Test
    fun `the canonical destination stays inside the attachments root`() {
        assertTrue(
            "canonical containment must be checked before writing",
            screenshotBody.contains("VDisplayScreenshotPath.canonicalContainment("),
        )
    }

    @Test
    fun `no image bytes are ever base64-encoded or logged`() {
        assertFalse("no base64 in the reply", handlerCode.contains("Base64"))
        // The comment-line filter only strips lines *starting* with //, /*, *
        // — an inline trailing comment (e.g. `… // no base64 here`) survives
        // into handlerCode, so the case-folded check must run on stripped
        // source or the test trips over its own prose.
        val codeWithoutComments = handlerCode
            .lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
        assertFalse("no base64 anywhere in the handler", codeWithoutComments.lowercase().contains("base64"))
        // The only log call in the capture path reports the exception, never
        // the byte array: an interpolated frame would put a PNG in logcat.
        assertFalse(
            "a PNG must not be interpolated into a log line",
            handlerCode.contains("frame.png}") || handlerCode.contains("\$frame"),
        )
        assertTrue(
            "the frame is written straight to the file",
            screenshotBody.contains("ByteBuffer.wrap(frame.png)") &&
                screenshotBody.contains("ch.write(buf)"),
        )
    }

    @Test
    fun `the reply carries guest path, minis url and dimensions`() {
        assertTrue(screenshotBody.contains("""put("path", guestPath)"""))
        assertTrue(screenshotBody.contains("""put("minis_url", "minis://attachments/"""))
        // minis:// URLs are percent-encoded per path segment — spaces, '+',
        // non-ASCII file names must not go out raw.
        assertTrue(
            "minis_url must be built through a segment encoder",
            screenshotBody.contains("URLEncoder.encode(seg"),
        )
        assertTrue(screenshotBody.contains("""put("width", frame.width)"""))
        assertTrue(screenshotBody.contains("""put("sizeBytes", frame.png.size)"""))
    }

    @Test
    fun `the reply never exposes the host filesystem path`() {
        // host_path is a device-local absolute path; the agent's namespace is
        // the guest path + minis_url, and a second chat must not learn where
        // this session's mount lives on the host.
        assertFalse(
            "must not publish host_path",
            screenshotBody.contains("put(\"host_path\""),
        )
        assertFalse(
            "file.absolutePath must not leak into the reply",
            screenshotBody.contains("file.absolutePath)"),
        )
    }

    @Test
    fun `mount resolution is session-only with no global fallback`() {
        assertTrue(
            "must resolve through the calling session",
            screenshotBody.contains("PRootKernel.resolveSessionHostPath("),
        )
        assertFalse(
            "the process-global map must not be a fallback",
            screenshotBody.contains("resolveHostPath("),
        )
    }

    // ── no display selector, no physical-screen fallback ────────────────

    @Test
    fun `the handler never passes a display id to the core`() {
        // Every core call takes content coordinates only. If a displayId ever
        // appears in an argument list here, the "own display only" property is
        // gone and this test is the alarm.
        for (call in listOf(
            "VirtualDisplayManager.tap(", "VirtualDisplayManager.swipe(",
            "VirtualDisplayManager.key(", "VirtualDisplayManager.text(",
            "VirtualDisplayManager.launch(", "VirtualDisplayManager.start(",
        )) {
            val args = handlerCode.substringAfter(call).substringBefore(")")
            assertFalse("$call must not take a display id (args: $args)", args.lowercase().contains("display"))
        }
        assertFalse(
            "no DisplayManager / VirtualDisplay use in the CLI layer",
            handlerCode.contains("android.hardware.display") ||
                // Bare contains("DisplayManager.") false-positives on every
                // VirtualDisplayManager dispatch; a negative lookbehind keeps
                // the alarm on the platform class only.
                Regex("(?<!Virtual)DisplayManager\\.").containsMatchIn(handlerCode) ||
                handlerCode.contains("import android.view.Display"),
        )
        assertFalse("no Display.DEFAULT_DISPLAY fallback", handlerCode.contains("DEFAULT_DISPLAY"))
    }

    @Test
    fun `input and launch are dispatched straight to the core`() {
        // The handler must not re-implement input: any local fallback that
        // "tries the main screen" would violate the requirement silently.
        assertFalse("no input command fallback via Shizuku", handlerCode.contains("\"input\""))
        assertFalse("no `am start` fallback", handlerCode.contains("\"am\""))
    }

    @Test
    fun `every command is dispatched`() {
        for (c in listOf(
            "Command.Status", "Command.Start", "Command.Stop", "Command.Screenshot",
            "Command.Tap", "Command.Swipe", "Command.Key", "Command.Text", "Command.Launch",
        )) {
            assertTrue("dispatch must handle $c", handlerCode.contains("is VDisplayCli.$c"))
        }
    }

    // ── envelope ────────────────────────────────────────────────────────

    @Test
    fun `both envelopes go through OffloadOutput`() {
        // Keeps --compact / -q working uniformly across every android-* CLI.
        assertTrue(handlerCode.contains("OffloadOutput.formatBody(body, args)"))
        assertTrue(handlerCode.contains("""put("ok", true).put("data", data)"""))
        assertTrue(handlerCode.contains("""put("ok", false).put(""") || handlerCode.contains(""""error","""))
        assertTrue("errors carry a code and a message", handlerCode.contains("""put("code", code).put("message", message)"""))
    }
}
