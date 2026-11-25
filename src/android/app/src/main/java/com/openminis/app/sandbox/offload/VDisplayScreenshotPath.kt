package com.openminis.app.sandbox.offload

/**
 * Where `android-vdisplay screenshot` is allowed to write.
 *
 * The rule is a whitelist, not a path check: a capture may only land under the
 * session's `/var/minis/attachments` mount (the same namespace the
 * AccessibilityService screenshot and the user's uploads use), and it may
 * never replace an existing file.
 *
 * Pure Kotlin on purpose — `VDisplayScreenshotPathTest` proves every rejection
 * on the JVM, without a device, a PRoot mount or a display. The handler passes
 * the resolved relative segments to the sandbox's own path resolver
 * (`PRootKernel.resolveSessionHostPath`), which is what turns them into a host
 * path the agent can read back.
 */
internal object VDisplayScreenshotPath {

    /** Guest path the agent sees, mirroring AccessibilityOffloadHandler. */
    const val ATTACHMENTS_ROOT = "/var/minis/attachments"

    /** Captures go here so they stay clear of user uploads and ImageBudget spillover. */
    const val SUBDIR = "vdisplay"

    const val FILE_PREFIX = "vdisplay_"

    sealed class Resolved {
        /**
         * [relativePath] is relative to [ATTACHMENTS_ROOT] and uses '/' — e.g.
         * `vdisplay/vdisplay_2026-01-01T10-00-00-000.png`. Never starts with
         * '/' and never contains '.', so it cannot escape the mount.
         */
        data class Ok(val relativePath: String, val fileName: String) : Resolved()

        data class Rejected(val message: String) : Resolved()
    }

    /**
     * Validate an `--output` value.
     *
     * Accepted forms:
     *   - `null` / empty → the default timestamped name under [SUBDIR]
     *   - a bare file name: `shot.png` → `<subdir>/shot.png`
     *   - an absolute path under [ATTACHMENTS_ROOT]:
     *     `/var/minis/attachments/vdisplay/shot.png`
     *   - a path relative to [ATTACHMENTS_ROOT] without a leading slash:
     *     `vdisplay/shot.png`
     *
     * Rejected: any other absolute path (so `--output /sdcard/x.png` and
     * `--output /var/minis/workspace/x.png` both fail), any `..` segment, a
     * non-`.png` extension, a name that would land directly under the mount
     * root's siblings, and control characters.
     */
    fun resolve(output: String?, timestampMs: Long = System.currentTimeMillis()): Resolved {
        // Explicitly passing an empty value is a mistake, not a request for the
        // default name — say so instead of quietly choosing a name for them.
        if (output != null && output.isBlank()) {
            return Resolved.Rejected("--output must not be empty; omit the flag for a default name")
        }
        val name = output?.trim() ?: defaultFileName(timestampMs)

        for (ch in name) {
            if (ch.code < 0x20 || ch.code == 0x7F) {
                return Resolved.Rejected("--output must not contain control characters")
            }
        }

        var rest = name.replace('\\', '/')

        // A bare file name is placed in SUBDIR below; anything the caller
        // spelled as a path (absolute, or containing '/') keeps the shape they
        // asked for — still inside the mount, still validated below.
        // Absolute: the only accepted root is the attachments mount.
        val wasAbsolute = rest.startsWith("/")
        if (wasAbsolute) {
            if (rest != ATTACHMENTS_ROOT && !rest.startsWith("$ATTACHMENTS_ROOT/")) {
                return Resolved.Rejected(
                    "screenshot output must be under $ATTACHMENTS_ROOT " +
                        "(the sandbox mount the agent can read); got '$name'",
                )
            }
            rest = rest.removePrefix(ATTACHMENTS_ROOT).trimStart('/')
        }

        if (rest.isEmpty()) {
            return Resolved.Rejected(
                "$ATTACHMENTS_ROOT is a directory; pass a file name, e.g. --output shot.png",
            )
        }
        if (rest.endsWith("/")) {
            return Resolved.Rejected("--output must name a file, not a directory")
        }
        // A bare name is placed in SUBDIR; anything the caller spelled with a
        // separator keeps the exact shape they asked for.
        val hadSeparator = rest.contains('/')

        val segments = rest.split('/')
        if (segments.any { it.isEmpty() }) {
            return Resolved.Rejected("--output must not contain empty path segments")
        }
        if (segments.any { it == "." || it == ".." }) {
            return Resolved.Rejected("--output must not contain '.' or '..' segments escaping $ATTACHMENTS_ROOT")
        }

        val fileName = segments.last()
        if (!fileName.lowercase().endsWith(".png")) {
            return Resolved.Rejected("--output must end with .png (got '$fileName')")
        }
        if (fileName.startsWith(".")) {
            return Resolved.Rejected("--output must not be a hidden file (got '$fileName')")
        }

        // A bare name always goes into SUBDIR; a caller-supplied path
        // (absolute or relative) is kept exactly as given — silently moving an
        // explicit `/var/minis/attachments/a.png` into the subdir would
        // contradict the accepted-shapes contract above.
        val relative = if (hadSeparator || wasAbsolute) segments.joinToString("/") else "$SUBDIR/$fileName"
        return Resolved.Ok(relative, fileName)
    }

    fun defaultFileName(timestampMs: Long): String {
        // Fixed UTC: the stamp must be a pure function of the instant, not of
        // the device's timezone, or tests and cross-device name comparisons
        // drift by hours.
        val fmt = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return "$FILE_PREFIX${fmt.format(java.util.Date(timestampMs))}.png"
    }

    /**
     * Re-check the *canonicalised* destination against the canonicalised
     * attachments root. [resolve] already rejects `..` segments and absolute
     * escapes by shape, but shape cannot see a symlink planted inside the
     * mount (e.g. `attachments/vdisplay/link.png -> /sdcard/…`), and the
     * canonical resolution is where that attack dies. [canonicalize] is
     * injected so this is testable without a filesystem; the handler passes
     * `File::canonicalPath`.
     *
     * Called by the handler AFTER the host directory is resolved and BEFORE
     * anything is written.
     */
    fun canonicalContainment(
        relativePath: String,
        canonicalize: (String) -> String,
    ): Resolved {
        val root = canonicalize(ATTACHMENTS_ROOT)
        val full = canonicalize(relativePath)
        val prefix = if (root.endsWith("/")) root else "$root/"
        if (root.isEmpty() || !full.startsWith(prefix)) {
            return Resolved.Rejected(
                "--output resolves outside $ATTACHMENTS_ROOT (symlink or traversal); got '$relativePath'",
            )
        }
        return Resolved.Ok(relativePath, relativePath.substringAfterLast('/'))
    }

    /**
     * Pick a name that does not exist yet. Overwriting is never allowed: a
     * capture is evidence, and a second capture must not silently destroy the
     * first one the agent is still referring to.
     *
     * [exists] is injected so the rule is testable without a filesystem.
     */
    fun uniqueName(
        requestedRelativePath: String,
        exists: (String) -> Boolean,
        maxAttempts: Int = 1000,
    ): Resolved {
        if (!exists(requestedRelativePath)) return Resolved.Ok(requestedRelativePath, requestedRelativePath.substringAfterLast('/'))
        val slash = requestedRelativePath.lastIndexOf('/')
        val dir = if (slash >= 0) requestedRelativePath.substring(0, slash) else ""
        val name = requestedRelativePath.substring(slash + 1)
        val base = name.removeSuffix(".png")
        for (i in 1..maxAttempts) {
            val candidate = "$base-$i.png"
            val full = if (dir.isEmpty()) candidate else "$dir/$candidate"
            if (!exists(full)) return Resolved.Ok(full, candidate)
        }
        return Resolved.Rejected(
            "could not find an unused file name for '$requestedRelativePath' after $maxAttempts attempts",
        )
    }
}
