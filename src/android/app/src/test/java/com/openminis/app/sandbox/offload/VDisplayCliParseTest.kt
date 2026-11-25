package com.openminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-vdisplay-cli] Argv contract of `android-vdisplay`.
 *
 * [VDisplayCli] is pure Kotlin (no `android.*`, no Context) precisely so this
 * whole table runs on the JVM: the handler around it needs a device, Shizuku
 * and a live virtual display, none of which exist in `testReleaseUnitTest`.
 * What can be verified here is exactly what governs whether a malformed agent
 * command ever reaches the privileged core — and, more importantly, that no
 * invocation shape can name a display.
 */
class VDisplayCliParseTest {

    private fun ok(argv: String): VDisplayCli.Command {
        val r = VDisplayCli.parse(split(argv))
        assertTrue("expected success for `$argv`, got $r", r is VDisplayCli.ParseResult.Success)
        return (r as VDisplayCli.ParseResult.Success).command
    }

    private fun failure(argv: String): VDisplayCli.ParseResult.Failure {
        val r = VDisplayCli.parse(split(argv))
        assertTrue("expected failure for `$argv`, got $r", r is VDisplayCli.ParseResult.Failure)
        return r as VDisplayCli.ParseResult.Failure
    }

    /** Split on spaces, honouring nothing else — argv is already tokenised by the shell. */
    private fun split(s: String): List<String> =
        if (s.isBlank()) emptyList() else s.trim().split(Regex("\\s+"))

    // ── help / status ───────────────────────────────────────────────────

    @Test
    fun `no arguments and help forms print help`() {
        for (argv in listOf("", "help", "--help", "-h", "start --help")) {
            assertEquals("argv='$argv'", VDisplayCli.ParseResult.Help, VDisplayCli.parse(split(argv)))
        }
    }

    @Test
    fun `status takes no arguments`() {
        assertEquals(VDisplayCli.Command.Status, ok("status"))
        assertEquals("INVALID_ARGS", failure("status extra").code)
        assertEquals("INVALID_ARGS", failure("status --width 100").code)
    }

    // ── start ───────────────────────────────────────────────────────────

    @Test
    fun `start defaults match the documented geometry`() {
        assertEquals(
            VDisplayCli.Command.Start(1080, 1920, 420),
            ok("start"),
        )
    }

    @Test
    fun `start accepts explicit geometry, in both option forms`() {
        assertEquals(
            VDisplayCli.Command.Start(720, 1280, 320),
            ok("start --width 720 --height 1280 --dpi 320"),
        )
        assertEquals(
            VDisplayCli.Command.Start(720, 1280, 320),
            ok("start --width=720 --height=1280 --dpi=320"),
        )
    }

    @Test
    fun `start rejects out-of-range geometry instead of clamping`() {
        // Clamping would silently give the agent a display it did not ask for,
        // and every later coordinate would be wrong. Fail loudly.
        for (argv in listOf(
            "start --width 100", "start --width 5000",
            "start --height 0", "start --height 99999",
            "start --dpi 60", "start --dpi 2000",
        )) {
            assertEquals(argv, "INVALID_ARGS", failure(argv).code)
        }
    }

    @Test
    fun `start rejects non-numeric geometry`() {
        assertEquals("INVALID_ARGS", failure("start --width abc").code)
        assertEquals("INVALID_ARGS", failure("start --width 12.5").code)
        assertEquals("INVALID_ARGS", failure("start --width").code)
    }

    @Test
    fun `start rejects positional arguments and foreign options`() {
        assertEquals("INVALID_ARGS", failure("start 1080").code)
        assertEquals("INVALID_ARGS", failure("start --output x.png").code)
    }

    // ── stop ────────────────────────────────────────────────────────────

    @Test
    fun `stop is accepted bare and rejects any payload`() {
        assertEquals(VDisplayCli.Command.Stop, ok("stop"))
        assertEquals("INVALID_ARGS", failure("stop now").code)
        assertEquals("INVALID_ARGS", failure("stop --dpi 300").code)
    }

    // ── screenshot ──────────────────────────────────────────────────────

    @Test
    fun `screenshot takes an optional output name and no positional`() {
        assertEquals(VDisplayCli.Command.Screenshot(null), ok("screenshot"))
        assertEquals(VDisplayCli.Command.Screenshot("shot.png"), ok("screenshot --output shot.png"))
        assertEquals(
            VDisplayCli.Command.Screenshot("/var/minis/attachments/vdisplay/a.png"),
            ok("screenshot --output /var/minis/attachments/vdisplay/a.png"),
        )
        // Path validation is VDisplayScreenshotPath's job, not the parser's.
        assertEquals(VDisplayCli.Command.Screenshot("/etc/passwd.png"), ok("screenshot --output /etc/passwd.png"))
        assertEquals("INVALID_ARGS", failure("screenshot shot.png").code)
    }

    // ── tap / swipe ─────────────────────────────────────────────────────

    @Test
    fun `tap needs exactly two in-range integers`() {
        assertEquals(VDisplayCli.Command.Tap(540, 960), ok("tap 540 960"))
        assertEquals(VDisplayCli.Command.Tap(0, 0), ok("tap 0 0"))
        for (argv in listOf("tap", "tap 1", "tap 1 2 3", "tap -1 5", "tap 1 abc", "tap 999999 1")) {
            assertEquals(argv, "INVALID_ARGS", failure(argv).code)
        }
    }

    @Test
    fun `swipe needs four points, a movable path and a bounded duration`() {
        assertEquals(
            VDisplayCli.Command.Swipe(100, 200, 300, 400, 300L),
            ok("swipe 100 200 300 400"),
        )
        assertEquals(
            VDisplayCli.Command.Swipe(100, 200, 300, 400, 120L),
            ok("swipe 100 200 300 400 --duration 120"),
        )
        // Zero-length swipe is a tap in disguise; reject rather than guess.
        assertEquals("INVALID_ARGS", failure("swipe 100 200 100 200").code)
        for (argv in listOf(
            "swipe 1 2 3", "swipe 1 2 3 4 5",
            "swipe 1 2 3 4 --duration 0",
            "swipe 1 2 3 4 --duration 60000",
            "swipe 1 2 3 4 --duration fast",
        )) {
            assertEquals(argv, "INVALID_ARGS", failure(argv).code)
        }
    }

    // ── key ─────────────────────────────────────────────────────────────

    @Test
    fun `key resolves the documented names case-insensitively`() {
        assertEquals(VDisplayCli.Command.Key("BACK", 4), ok("key BACK"))
        assertEquals(VDisplayCli.Command.Key("ENTER", 66), ok("key Enter"))
        assertEquals(VDisplayCli.Command.Key("TAB", 61), ok("key tab"))
    }

    @Test
    fun `key refuses codes outside the allowlist`() {
        // POWER / device-credential keys act on the device, not the off-screen
        // display, so they are deliberately absent.
        for (key in listOf("POWER", "WAKEUP", "SLEEP", "0", "", "BACKSPACEISH")) {
            assertEquals(key, "INVALID_ARGS", failure("key $key").code)
        }
        assertEquals("INVALID_ARGS", failure("key").code)
        assertEquals("INVALID_ARGS", failure("key BACK HOME").code)
    }

    @Test
    fun `device-scope keys are rejected with a remove-HOME hint`() {
        // These act on the physical device (launcher / recents / volume), so
        // they must never reach the privileged core from this CLI.
        for (key in listOf("HOME", "RECENTS", "APP_SWITCH", "VOLUME_UP", "VOLUME_DOWN", "home", "recents")) {
            val f = failure("key $key")
            assertEquals(key, "INVALID_ARGS", f.code)
            assertTrue(
                "'$key' rejection must explain removal (mention HOME), got: ${f.message}",
                f.message.contains("HOME"),
            )
        }
        // ...and none of them survive into any parsed command.
        for (key in listOf("HOME", "RECENTS", "APP_SWITCH", "VOLUME_UP", "VOLUME_DOWN")) {
            assertTrue(
                "$key must not resolve to a key code",
                VDisplayCli.keyCodeOf(key) == null,
            )
        }
    }

    // ── text ────────────────────────────────────────────────────────────

    @Test
    fun `text joins unquoted words and honours --enter`() {
        assertEquals(VDisplayCli.Command.Text("hello world", false), ok("text hello world"))
        assertEquals(VDisplayCli.Command.Text("hello", true), ok("text hello --enter"))
        assertEquals(VDisplayCli.Command.Text("--literal", false), ok("text -- --literal"))
    }

    @Test
    fun `text rejects blank, oversized and control-character payloads`() {
        assertEquals("INVALID_ARGS", failure("text").code)
        assertEquals("INVALID_ARGS", failure("text   ").code)
        val tooLong = "a".repeat(VDisplayCli.MAX_TEXT_CHARS + 1)
        assertEquals("INVALID_ARGS", failure("text $tooLong").code)
        // A raw newline would be a second shell line; --enter exists for that.
        val withNewline = "line1\nline2"
        val r = VDisplayCli.parse(listOf("text", withNewline))
        assertTrue(r is VDisplayCli.ParseResult.Failure)
        assertEquals("INVALID_ARGS", (r as VDisplayCli.ParseResult.Failure).code)
    }

    @Test
    fun `text validation is ASCII printable only, with explicit non-ASCII errors`() {
        assertNull(VDisplayCli.validateText("plain ASCII ok_!~"))
        assertTrue(VDisplayCli.validateText("a\u0007b") != null)
        // Non-ASCII (中文/emoji) is explicitly out of scope for the core's
        // ASCII injector: rejected, with an error that says so.
        val zh = VDisplayCli.validateText("中文输入")
        assertTrue("中文 must be rejected", zh != null)
        assertTrue("error must name ASCII support: $zh", zh!!.contains("ASCII"))
        assertTrue(
            "error must say non-ASCII is unsupported: $zh",
            zh.contains("not supported"),
        )
        assertTrue(VDisplayCli.validateText("café") != null)
        assertTrue(VDisplayCli.validateText("emoji \uD83D\uDE00") != null)
        // The parser mirrors validateText.
        assertEquals("INVALID_ARGS", failure("text 中文").code)
    }

    // ── launch ──────────────────────────────────────────────────────────

    @Test
    fun `launch accepts a dotted package and rejects anything else`() {
        assertEquals(
            VDisplayCli.Command.Launch("com.android.settings"),
            ok("launch com.android.settings"),
        )
        assertEquals(
            VDisplayCli.Command.Launch("com.example.my_app"),
            ok("launch com.example.my_app"),
        )
        for (pkg in listOf("settings", "com..example", "com.example.", ".com.example", "com.example-app", "com/example")) {
            assertEquals(pkg, "INVALID_ARGS", failure("launch $pkg").code)
        }
        assertEquals("INVALID_ARGS", failure("launch").code)
    }

    // ── the central invariant: no display selector exists ───────────────

    @Test
    fun `no option or positional can name a display`() {
        // This is the security property, not a nicety: the CLI must be unable
        // to address the physical screen or anyone else's display.
        for (argv in listOf(
            "--display 0", "--display 3", "start --display 0",
            "start --display-id 0", "tap 1 2 --display 0",
            "screenshot --display 0", "status --display-id 0",
            "start --displayId=0", "--d 0",
            // A bare numeric positional is not a display either.
            "0 start", "start 1080 1920",
        )) {
            val r = VDisplayCli.parse(split(argv))
            assertTrue(
                "`$argv` must not parse (a display selector must not exist)",
                r is VDisplayCli.ParseResult.Failure,
            )
        }
    }

    @Test
    fun `unknown command is distinguished from bad arguments`() {
        assertEquals("UNKNOWN_COMMAND", failure("restart").code)
        assertEquals("UNKNOWN_COMMAND", failure("mirror on").code)
        assertEquals("INVALID_ARGS", failure("tap a b").code)
    }

    @Test
    fun `unknown long option is rejected rather than ignored`() {
        val f = failure("start --verbose")
        assertEquals("INVALID_ARGS", f.code)
        assertTrue("message should point at help", f.message.contains("help"))
        // The message must explain why --display in particular does not exist.
        assertTrue(failure("--display 1").message.contains("no display selector"))
    }

    @Test
    fun `output flags are accepted and do not disturb the command`() {
        assertEquals(VDisplayCli.Command.Status, ok("status --compact"))
        assertEquals(VDisplayCli.Command.Status, ok("status -q"))
        assertEquals(VDisplayCli.Command.Stop, ok("stop --quiet"))
    }

    // ── bounds stay aligned with the in-app screen ──────────────────────

    @Test
    fun `geometry bounds match the UI config values`() {
        // Kept in sync by value so the CLI can never request a display the
        // in-app screen considers invalid. Duplicated because this file must
        // not import the Compose-dependent ui.vdisplay package.
        assertEquals(240, VDisplayCli.MIN_SIZE)
        assertEquals(3840, VDisplayCli.MAX_SIZE)
        assertEquals(120, VDisplayCli.MIN_DPI)
        assertEquals(800, VDisplayCli.MAX_DPI)
        assertEquals(1080, VDisplayCli.DEFAULT_WIDTH)
        assertEquals(1920, VDisplayCli.DEFAULT_HEIGHT)
        assertEquals(420, VDisplayCli.DEFAULT_DPI)
    }

    @Test
    fun `help documents every command and the no-display rule`() {
        for (needle in listOf(
            "status", "start", "stop", "screenshot", "tap", "swipe", "key", "text", "launch",
            "no display id", "physical screen", "JSON envelope",
        )) {
            assertTrue("help must mention '$needle'", VDisplayCli.HELP.contains(needle))
        }
        // The key list in help must not advertise removed device-scope keys.
        for (gone in listOf("HOME", "RECENTS", "APP_SWITCH", "VOLUME_UP", "VOLUME_DOWN")) {
            assertTrue(
                "help key list must not offer $gone",
                !VDisplayCli.keyNames().contains(gone),
            )
        }
        // ...and the ASCII-only text contract is documented, not implied.
        assertTrue("help must state the ASCII text restriction", VDisplayCli.HELP.contains("printable ASCII"))
    }
}
