package com.openminis.app.sandbox.offload

/**
 * `android-vdisplay` argument model, parser and help text — the whole CLI
 * surface of Minis' background virtual display ("后台屏").
 *
 * Everything here is deliberately **pure Kotlin**: no `android.*` import, no
 * Context, no Shizuku, no DisplayManager. That is what lets
 * `VDisplayCliParseTest` pin every validation rule on the JVM, which is the
 * only verification available for this feature without a device (see the
 * "验证限制" note in `docs/vdisplay/README.md`).
 *
 * Design rules encoded here, in the order they matter:
 *
 *  1. **No display id, ever.** There is no `--display` option and no
 *     positional that could carry one; unknown flags are rejected outright.
 *     The handler targets `VirtualDisplayManager`'s own display, so the
 *     physical screen is unreachable by construction rather than by check.
 *  2. **Strict validation.** Sizes, DPI, coordinates, durations, key names,
 *     package names and text all have explicit bounds; a bad value fails with
 *     `INVALID_ARGS` instead of being coerced into something plausible.
 *  3. **No silent defaults for ambiguity.** A zero-length swipe is rejected
 *     (that is a tap), text with control characters is rejected (use
 *     `--enter`), and an unparseable integer is an error rather than null.
 */
internal object VDisplayCli {

    const val TOOL = "android-vdisplay"

    // Geometry bounds. Mirrors com.openminis.app.ui.vdisplay.VDisplayConfig
    // (MIN_SIZE/MAX_SIZE/MIN_DPI/MAX_DPI) so the CLI cannot ask the core for
    // something the in-app screen would refuse. Duplicated rather than
    // imported to keep this file free of the Compose-dependent UI package;
    // VDisplayCliParseTest asserts the two stay in sync by value.
    const val MIN_SIZE = 240
    const val MAX_SIZE = 3840
    const val MIN_DPI = 120
    const val MAX_DPI = 800
    const val DEFAULT_WIDTH = 1080
    const val DEFAULT_HEIGHT = 1920
    const val DEFAULT_DPI = 420

    /** Upper bound for a single coordinate. Generous vs 3840, still bounded. */
    const val MAX_COORD = 16_384
    const val MIN_COORD = 0

    const val MIN_SWIPE_MS = 1L
    const val MAX_SWIPE_MS = 10_000L
    const val DEFAULT_SWIPE_MS = 300L

    const val MAX_TEXT_CHARS = 256
    const val MAX_PACKAGE_CHARS = 255

    /**
     * The key allowlist. Deliberately excludes POWER / SLEEP / WAKEUP / the
     * device-credential keys — and the device-scope keys HOME / RECENTS
     * (APP_SWITCH) / VOLUME_UP / VOLUME_DOWN: this CLI drives an off-screen
     * display, and every one of those would act on the physical device.
     */
    private val KEY_CODES: Map<String, Int> = linkedMapOf(
        "BACK" to 4,          // KeyEvent.KEYCODE_BACK
        "ENTER" to 66,        // KEYCODE_ENTER
        "TAB" to 61,
        "ESCAPE" to 111,
        "DELETE" to 67,       // KEYCODE_DEL
        "FORWARD_DEL" to 112,
        "MENU" to 82,
        "DPAD_UP" to 19,
        "DPAD_DOWN" to 20,
        "DPAD_LEFT" to 21,
        "DPAD_RIGHT" to 22,
        "DPAD_CENTER" to 23,
    )

    /**
     * Keys that only make sense on the physical device. They are not in
     * [KEY_CODES], and asking for one gets a hint explaining why instead of a
     * bare "unsupported key".
     */
    private val DEVICE_ONLY_KEYS = setOf("HOME", "RECENTS", "APP_SWITCH", "VOLUME_UP", "VOLUME_DOWN")

    fun keyNames(): List<String> = KEY_CODES.keys.toList()

    fun keyCodeOf(name: String): Int? = KEY_CODES[name.uppercase()]

    // ── Parse result model ───────────────────────────────────────────────

    sealed class ParseResult {
        /** `help`, `--help`, `-h`, or no arguments. Exit 0 with [HELP]. */
        data object Help : ParseResult()

        /** Usage-level failure: exit 2, `code` is `INVALID_ARGS` / `UNKNOWN_COMMAND`. */
        data class Failure(val code: String, val message: String) : ParseResult()

        data class Success(val command: Command) : ParseResult()
    }

    sealed class Command {
        data object Status : Command()

        data class Start(val width: Int, val height: Int, val dpi: Int) : Command()

        data object Stop : Command()

        /** [outputName] is the raw `--output` value (null = default name); resolved later by [VDisplayScreenshotPath]. */
        data class Screenshot(val outputName: String?) : Command()

        data class Tap(val x: Int, val y: Int) : Command()

        data class Swipe(
            val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Long,
        ) : Command()

        data class Key(val name: String, val keyCode: Int) : Command()

        data class Text(val text: String, val pressEnter: Boolean) : Command()

        data class Launch(val packageName: String) : Command()
    }

    /** Options that take a value; anything else starting with `--` is unknown. */
    private val VALUE_OPTIONS = setOf("width", "height", "dpi", "output", "duration")

    /** Options that are flags. [OffloadOutput] consumes compact/quiet later. */
    private val FLAG_OPTIONS = setOf("help", "h", "enter", "compact", "quiet", "q")

    // ── Parser ───────────────────────────────────────────────────────────

    /**
     * Parse `argv` **excluding** the program name.
     *
     * Unknown options are rejected rather than ignored: silently accepting
     * `--display 3` would be the one way a caller could believe it addressed
     * another display, so the flag must not parse.
     */
    fun parse(argv: List<String>): ParseResult {
        val positionals = ArrayList<String>()
        val values = HashMap<String, String>()
        val flags = HashSet<String>()

        var i = 0
        while (i < argv.size) {
            val token = argv[i]
            when {
                token == "--" -> {
                    // Everything after `--` is positional (text may start with '-').
                    positionals.addAll(argv.subList(i + 1, argv.size))
                    i = argv.size
                }
                token.startsWith("--") && token.length > 2 -> {
                    val body = token.substring(2)
                    val eq = body.indexOf('=')
                    val name = if (eq >= 0) body.substring(0, eq) else body
                    when {
                        name in FLAG_OPTIONS -> {
                            if (eq >= 0) return fail("INVALID_ARGS", "--$name does not take a value")
                            flags.add(name)
                        }
                        name in VALUE_OPTIONS -> {
                            if (eq >= 0) {
                                val v = body.substring(eq + 1)
                                if (v.isEmpty()) return fail("INVALID_ARGS", "--$name requires a value")
                                values[name] = v
                            } else {
                                val next = argv.getOrNull(i + 1)
                                if (next == null) return fail("INVALID_ARGS", "--$name requires a value")
                                values[name] = next
                                i++
                            }
                        }
                        else -> return fail(
                            "INVALID_ARGS",
                            "unknown option --$name. $TOOL has no display selector; " +
                                "see `$TOOL help`",
                        )
                    }
                }
                token.length > 1 && token.startsWith("-") -> {
                    // Only -h and -q are meaningful; everything else is a typo.
                    if (token != "-h" && token != "-q") {
                        return fail("INVALID_ARGS", "unknown option $token. See `$TOOL help`")
                    }
                    flags.add(token.substring(1))
                }
                else -> positionals.add(token)
            }
            i++
        }

        if (flags.contains("help") || flags.contains("h")) return ParseResult.Help
        if (positionals.isEmpty()) return ParseResult.Help
        if (positionals[0] == "help") return ParseResult.Help

        val sub = positionals[0].lowercase()
        val rest = positionals.drop(1)

        fun onlyAllowed(vararg allowed: String): String? {
            val unexpected = values.keys - allowed.toSet()
            return if (unexpected.isEmpty()) null
            else "option --${unexpected.first()} is not valid for `$sub`"
        }

        return when (sub) {
            "status" -> {
                onlyAllowed()?.let { return fail("INVALID_ARGS", it) }
                rest.firstOrNull()?.let { return fail("INVALID_ARGS", "status takes no arguments (got '$it')") }
                ParseResult.Success(Command.Status)
            }

            "start" -> startCommand(rest, values, ::onlyAllowed)
            "stop" -> {
                onlyAllowed()?.let { return fail("INVALID_ARGS", it) }
                rest.firstOrNull()?.let { return fail("INVALID_ARGS", "stop takes no arguments (got '$it')") }
                ParseResult.Success(Command.Stop)
            }
            "screenshot" -> {
                onlyAllowed("output")?.let { return fail("INVALID_ARGS", it) }
                rest.firstOrNull()?.let {
                    return fail("INVALID_ARGS", "screenshot takes no positional argument (got '$it'); use --output <file.png>")
                }
                ParseResult.Success(Command.Screenshot(values["output"]))
            }
            "tap" -> {
                onlyAllowed()?.let { return fail("INVALID_ARGS", it) }
                if (rest.size != 2) return fail("INVALID_ARGS", "usage: $TOOL tap <x> <y>")
                val x = coordinate(rest[0], "x") ?: return fail("INVALID_ARGS", coordinateError(rest[0], "x"))
                val y = coordinate(rest[1], "y") ?: return fail("INVALID_ARGS", coordinateError(rest[1], "y"))
                ParseResult.Success(Command.Tap(x, y))
            }
            "swipe" -> {
                onlyAllowed("duration")?.let { return fail("INVALID_ARGS", it) }
                if (rest.size != 4) return fail("INVALID_ARGS", "usage: $TOOL swipe <x1> <y1> <x2> <y2> [--duration MS]")
                val x1 = coordinate(rest[0], "x1") ?: return fail("INVALID_ARGS", coordinateError(rest[0], "x1"))
                val y1 = coordinate(rest[1], "y1") ?: return fail("INVALID_ARGS", coordinateError(rest[1], "y1"))
                val x2 = coordinate(rest[2], "x2") ?: return fail("INVALID_ARGS", coordinateError(rest[2], "x2"))
                val y2 = coordinate(rest[3], "y2") ?: return fail("INVALID_ARGS", coordinateError(rest[3], "y2"))
                if (x1 == x2 && y1 == y2) {
                    return fail("INVALID_ARGS", "swipe start and end are identical; use `tap` for a tap")
                }
                val duration = values["duration"]?.let { raw ->
                    val parsed = raw.toLongOrNull()
                        ?: return fail("INVALID_ARGS", "--duration must be an integer number of milliseconds (got '$raw')")
                    if (parsed !in MIN_SWIPE_MS..MAX_SWIPE_MS) {
                        return fail("INVALID_ARGS", "--duration must be $MIN_SWIPE_MS..$MAX_SWIPE_MS ms")
                    }
                    parsed
                } ?: DEFAULT_SWIPE_MS
                ParseResult.Success(Command.Swipe(x1, y1, x2, y2, duration))
            }
            "key" -> {
                onlyAllowed()?.let { return fail("INVALID_ARGS", it) }
                if (rest.size != 1) {
                    return fail("INVALID_ARGS", "usage: $TOOL key <${keyNames().joinToString("|")}>")
                }
                val name = rest[0].uppercase()
                val code = keyCodeOf(name)
                    ?: return fail(
                        "INVALID_ARGS",
                        if (name in DEVICE_ONLY_KEYS) {
                            "key '$name' acts on the physical device, not the off-screen display; " +
                                "it is not supported (remove it, e.g. drop HOME and use tap/BACK instead)"
                        } else {
                            "unsupported key '${rest[0]}'. Supported: ${keyNames().joinToString(", ")}"
                        },
                    )
                ParseResult.Success(Command.Key(name, code))
            }
            "text" -> {
                onlyAllowed()?.let { return fail("INVALID_ARGS", it) }
                if (rest.isEmpty()) return fail("INVALID_ARGS", "usage: $TOOL text <text> [--enter]")
                // Unquoted multi-word input is joined rather than rejected:
                // it is unambiguous, and the strictness that matters is on the
                // characters, not the token count.
                val text = rest.joinToString(" ")
                validateText(text)?.let { return fail("INVALID_ARGS", it) }
                ParseResult.Success(Command.Text(text, flags.contains("enter")))
            }
            "launch" -> {
                onlyAllowed()?.let { return fail("INVALID_ARGS", it) }
                if (rest.size != 1) return fail("INVALID_ARGS", "usage: $TOOL launch <packageName>")
                val pkg = rest[0]
                validatePackageName(pkg)?.let { return fail("INVALID_ARGS", it) }
                ParseResult.Success(Command.Launch(pkg))
            }
            else -> fail(
                "UNKNOWN_COMMAND",
                "unknown command '$sub'. Run `$TOOL help` for the command list.",
            )
        }
    }

    private fun startCommand(
        rest: List<String>,
        values: Map<String, String>,
        onlyAllowed: (Array<out String>) -> String?,
    ): ParseResult {
        onlyAllowed(arrayOf("width", "height", "dpi"))?.let { return fail("INVALID_ARGS", it) }
        if (rest.isNotEmpty()) {
            return fail("INVALID_ARGS", "start takes no positional argument (got '${rest[0]}')")
        }
        val width = size(values["width"], "width", DEFAULT_WIDTH) ?: return fail(
            "INVALID_ARGS", sizeError(values["width"], "width"),
        )
        val height = size(values["height"], "height", DEFAULT_HEIGHT) ?: return fail(
            "INVALID_ARGS", sizeError(values["height"], "height"),
        )
        val dpi = size(values["dpi"], "dpi", DEFAULT_DPI, min = MIN_DPI, max = MAX_DPI) ?: return fail(
            "INVALID_ARGS", sizeError(values["dpi"], "dpi"),
        )
        return ParseResult.Success(Command.Start(width, height, dpi))
    }

    // ── Validators (each returns a message on failure, null on success) ──

    private fun size(raw: String?, name: String, default: Int, min: Int = MIN_SIZE, max: Int = MAX_SIZE): Int? {
        if (raw == null) return default
        val v = raw.toIntOrNull() ?: return null
        return if (v in min..max) v else null
    }

    private fun sizeError(raw: String?, name: String): String {
        val min = if (name == "dpi") MIN_DPI else MIN_SIZE
        val max = if (name == "dpi") MAX_DPI else MAX_SIZE
        return if (raw == null) "--$name out of range" else "--$name must be an integer in $min..$max (got '$raw')"
    }

    private fun coordinate(raw: String, label: String): Int? {
        val v = raw.toIntOrNull() ?: return null
        return if (v in MIN_COORD..MAX_COORD) v else null
    }

    private fun coordinateError(raw: String, label: String): String =
        "<$label> must be an integer in $MIN_COORD..$MAX_COORD (got '$raw')"

    /**
     * Text rules: non-blank, bounded, ASCII printable only. The virtual
     * display's input path is the core's ASCII text injector — any character
     * outside printable ASCII (0x20..0x7E) is either a control character
     * (NUL/ESC/newline would smuggle terminal sequences or a second shell
     * line into the target app) or a non-ASCII letter (中文 included), which
     * the injector cannot type; say so explicitly instead of typing garbage.
     */
    const val MIN_TEXT_CODE = 0x20
    const val MAX_TEXT_CODE = 0x7E

    fun validateText(text: String): String? {
        if (text.isBlank()) return "text must not be blank"
        if (text.length > MAX_TEXT_CHARS) return "text must be at most $MAX_TEXT_CHARS characters (got ${text.length})"
        for (ch in text) {
            if (ch.code < MIN_TEXT_CODE || ch.code == 0x7F) {
                return "text must not contain control characters (found U+${ch.code.toString(16).padStart(4, '0')}); " +
                    "use --enter for a trailing ENTER"
            }
            if (ch.code > MAX_TEXT_CODE) {
                return "text supports printable ASCII (U+0020..U+007E) only; " +
                    "found non-ASCII U+${ch.code.toString(16).padStart(4, '0')} " +
                    "(Chinese/emoji input is not supported by the virtual display)"
            }
        }
        return null
    }

    /** `com.example.app` shape; no `-`, no path separators, no whitespace. */
    fun validatePackageName(pkg: String): String? {
        if (pkg.isEmpty()) return "package name must not be empty"
        if (pkg.length > MAX_PACKAGE_CHARS) return "package name is too long"
        val segments = pkg.split('.')
        if (segments.size < 2) return "package name must contain at least one '.' (got '$pkg')"
        for (seg in segments) {
            if (seg.isEmpty()) return "package name has an empty segment (got '$pkg')"
            if (!seg[0].isLetter()) return "each package segment must start with a letter (got '$pkg')"
            if (!seg.all { it.isLetterOrDigit() || it == '_' }) {
                return "package name may only contain letters, digits and '_' (got '$pkg')"
            }
        }
        return null
    }

    private fun fail(code: String, message: String): ParseResult =
        ParseResult.Failure(code, message)

    // ── Help ─────────────────────────────────────────────────────────────

    val HELP: String = """$TOOL — Minis' own background virtual display ("后台屏").

Usage:
  $TOOL help
  $TOOL status
  $TOOL start [--width N] [--height N] [--dpi N]
  $TOOL stop
  $TOOL screenshot [--output <file.png>]
  $TOOL tap <x> <y>
  $TOOL swipe <x1> <y1> <x2> <y2> [--duration MS]
  $TOOL key <KEY>
  $TOOL text <text> [--enter]
  $TOOL launch <packageName>

Commands:
  status      Report core / Shizuku / permission state. Readable without the
              agent permission and without Shizuku, so it is the way to find
              out why a command is refused.
  start       Create the virtual display (default ${DEFAULT_WIDTH}x${DEFAULT_HEIGHT} @ ${DEFAULT_DPI}dpi).
              --width/--height $MIN_SIZE..$MAX_SIZE, --dpi $MIN_DPI..$MAX_DPI.
              Idempotent: when a display already exists its geometry is
              reported instead of creating a second one.
  stop        Release the display, its surfaces and everything running on it.
  screenshot  Capture the virtual display into
              /var/minis/attachments/vdisplay/. --output takes a bare file
              name or a path under /var/minis/attachments only; nothing is
              ever overwritten and no base64 is emitted.
  tap         Tap at display-pixel coordinates ($MIN_COORD..$MAX_COORD).
  swipe       Swipe in display pixels; --duration $MIN_SWIPE_MS..$MAX_SWIPE_MS ms
              (default $DEFAULT_SWIPE_MS). Start and end must differ.
  key         ${keyNames().joinToString(" | ")}
  text        Type into the focused field of the virtual display (max
              $MAX_TEXT_CHARS characters, printable ASCII (U+0020..U+007E)
              only — Chinese/emoji are rejected; --enter presses ENTER
              afterwards).
  launch      Start <packageName> on the virtual display. Refused (not
              silently moved) when the user is already using that app on the
              physical screen.

Rules:
  - There is no display id and no --display option: the command only ever
    targets the display it created itself, and never falls back to the
    physical screen.
  - Every command except help/status needs BOTH the separate `vdisplay` agent
    permission (Settings → Permissions → Integrations, default: not allowed)
    and Shizuku, authorized.
  - Output is a JSON envelope: { ok: true, data } or
    { ok: false, error: { code, message } }.
"""
}
