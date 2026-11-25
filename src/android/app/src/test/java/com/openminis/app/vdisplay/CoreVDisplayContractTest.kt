package com.openminis.app.vdisplay

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-vdisplay-core] Source-level contract of the privileged virtual-display
 * core.
 *
 * The core cannot be instantiated off-device (DisplayManager, Shizuku, a shell
 * UserService process), so — like `VDisplayHandlerContractTest` — the
 * properties that must not silently regress are pinned against its source
 * text. Every assertion maps to a requirement in
 * `docs/vdisplay/README.md` §Security requirements and to the follow-up
 * correction of the API doc's "no OWN_FOCUS ⇒ no focus stealing" claim, which
 * was wrong.
 */
class CoreVDisplayContractTest {

    private fun src(relative: String): String {
        val f = File("src/main/java/com/openminis/app/vdisplay/$relative")
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    /** Code with comment lines removed: prose must not satisfy an assertion. */
    private fun code(text: String): String = text.lineSequence()
        .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
        .joinToString("\n")

    private val manager: String by lazy { src("VirtualDisplayManager.kt") }
    private val server: String by lazy { src("VDisplayShellServer.kt") }
    private val inputOps: String by lazy { src("VDisplayInputOps.kt") }
    private val userService: String by lazy { src("VDisplayUserService.kt") }
    private val aidl: String by lazy {
        val f = File("src/main/aidl/com/openminis/app/vdisplay/IVirtualDisplayService.aidl")
        assertTrue("missing ${f.absolutePath}", f.exists())
        f.readText()
    }

    // ── fail closed on focus ────────────────────────────────────────────

    @Test
    fun `untrusted downgrade requires an explicit opt-in`() {
        assertTrue(
            "the downgrade must default to false",
            code(manager).contains("allowUntrustedDowngrade: Boolean = false"),
        )
        assertTrue(
            "the server must refuse rather than downgrade implicitly",
            code(server).contains("if (!allowUntrustedDowngrade)"),
        )
        assertTrue(
            "and report it with a dedicated code so the caller can say UNSUPPORTED",
            code(server).contains("CREATE_TRUSTED_REFUSED"),
        )
    }

    @Test
    fun `independent focus is only claimed when OWN_FOCUS is really set`() {
        // The bug in the first draft: "trusted" meant "we asked for trusted",
        // and an untrusted display was described as unable to take focus.
        assertTrue(
            "trusted bookkeeping must check the actual flag",
            code(server).contains("if (trusted && grantsOwnFocus(flags))"),
        )
        assertTrue(
            "a platform without OWN_FOCUS must fail closed",
            code(server).contains("if (!supportsIndependentFocus(sdk)) return null"),
        )
        assertTrue(
            "and displayFlags must be nullable so 'cannot' is expressible",
            code(server).contains("internal fun displayFlags(sdk: Int, trusted: Boolean): Int?"),
        )
    }

    // ── never the physical display ──────────────────────────────────────

    @Test
    fun `display zero is rejected before any lookup`() {
        assertTrue(
            "ownDisplay must refuse 0 and negative ids up front",
            code(server).contains("internal fun ownDisplay(displayId: Int): Boolean {") &&
                code(server).contains("if (displayId <= Display.DEFAULT_DISPLAY) return false"),
        )
        val releaseBody = code(server).substringAfter("fun releaseDisplay(displayId: Int) {")
            .substringBefore("awake.stop(displayId)")
        assertTrue(
            "releaseDisplay must not touch display 0 either",
            releaseBody.contains("if (displayId <= Display.DEFAULT_DISPLAY) return"),
        )
        for (fn in listOf("inject(", "injectTo(")) {
            val body = code(inputOps).substringAfter("fun VDisplayShellServer.$fn").substringBefore("\n}")
            assertTrue(
                "$fn must refuse display 0 before injecting",
                body.contains("if (displayId <= Display.DEFAULT_DISPLAY) return false"),
            )
        }
        assertTrue(
            "the manager must re-check before every binder input call",
            code(manager).contains("id <= Display.DEFAULT_DISPLAY"),
        )
    }

    @Test
    fun `device level keys are never on the whitelist`() {
        val body = code(inputOps).substringAfter("internal object VDisplayKeys").substringBefore("private val CODES")
        for (deviceKey in listOf("KEYCODE_HOME", "KEYCODE_APP_SWITCH", "KEYCODE_VOLUME_UP",
            "KEYCODE_VOLUME_DOWN", "KEYCODE_POWER", "KEYCODE_WAKEUP", "KEYCODE_SEARCH", "KEYCODE_CAMERA")) {
            assertFalse("$deviceKey acts on the whole device and must not be injectable", body.contains(deviceKey))
        }
        assertTrue("BACK stays available inside the display", body.contains("\"KEYCODE_BACK\" to 4"))
        // The client refuses too, so a caller cannot even reach the binder.
        assertTrue(code(manager).contains("if (!VDisplayKeys.isAllowed(keyCode))"))
    }

    // ── the public API the UI and CLI actually call ─────────────────────

    @Test
    fun `the command surface matches the UI backend and the CLI handler`() {
        // These names/arities are what CoreVDisplayBackend and
        // VDisplayOffloadHandler call; renaming any of them breaks the build
        // of a layer this module does not own.
        val required = listOf(
            "fun start(\n        width: Int,\n        height: Int,\n        dpi: Int = 320,",
            "fun stop(): Result",
            "fun capture(): CaptureResult",
            "fun status(): Status",
            "fun isEngineInstalled(): Boolean",
            "fun tap(x: Int, y: Int): Result",
            "fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300L): Result",
            "fun key(keyCode: Int): Result",
            "fun text(text: String, pressEnter: Boolean = false): Result",
            "fun launch(packageName: String): Result",
        )
        val managerCode = code(manager)
        for (signature in required) {
            assertTrue("missing $signature", managerCode.contains(signature))
        }
        // Result/Status/Codes must be callable exactly as the callers spell them.
        for (member in listOf("object Codes", "sealed class Result", "data class Ok(val data: JSONObject)",
            "data class Failure(", "sealed class CaptureResult", "class Capture(",
            "data class Status(", "fun toJson(): JSONObject")) {
            assertTrue("missing $member", managerCode.contains(member))
        }
        // No command may take a display id: that is what makes "own display
        // only" structural for both layers.
        for (name in listOf("fun tap(", "fun swipe(", "fun key(", "fun text(", "fun launch(")) {
            val args = managerCode.substringAfter(name).substringBefore("): Result")
            assertFalse("$name must not take a displayId", args.lowercase().contains("displayid"))
        }
    }

    @Test
    fun `start connects and initializes on its own`() {
        val body = code(manager).substringAfter("private suspend fun startLocked(")
            .substringBefore("private fun runningData")
        assertTrue("start must connect when idle", body.contains("connectLocked()"))
        assertTrue("connect must lazily init", code(manager).contains("fun ensureInitialized(): Boolean"))
        assertTrue("and connectLocked must use it", code(manager).contains("private suspend fun connectLocked") &&
            code(manager).substringAfter("private suspend fun connectLocked").substringBefore("_state.value = VDisplayState.Connecting")
                .contains("ensureInitialized()"))
    }

    @Test
    fun `the AIDL carries the downgrade decision across the binder`() {
        assertTrue(
            "createDisplay must take the allowUntrustedDowngrade flag",
            aidl.contains("int createDisplay(in Surface surface, int width, int height, int dpi, boolean allowUntrustedDowngrade) = 1;"),
        )
        assertTrue(
            "and the UserService must pass it through unchanged",
            code(userService).contains("server.createDisplay(surface, width, height, dpi, allowUntrustedDowngrade)"),
        )
    }

    @Test
    fun `text bounds agree with the CLI`() {
        // A payload that passes CLI validation must not be refused by the core.
        assertTrue(code(inputOps).contains("const val MAX_CHARS = 1_000"))
        assertTrue(code(inputOps).contains("text.length in 1..MAX_CHARS"))
    }

    @Test
    fun `no automatic physical screen or shell fallback for input`() {
        assertFalse(
            "taps must never fall back to `input tap`",
            code(inputOps).substringAfter("internal fun VDisplayShellServer.injectTapChecked")
                .substringBefore("internal fun VDisplayShellServer.injectStrokeChecked")
                .contains("\"input\","),
        )
        assertFalse(
            "the manager must not shell out at all",
            code(manager).contains("ProcessBuilder") || code(manager).contains("Runtime.getRuntime"),
        )
    }
}
