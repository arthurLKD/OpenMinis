/*
 * Copyright 2026 OpenMinis
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Portions adapted from Mika (https://github.com/wangzan101/mika, Apache-2.0):
 * virtual-display creation flags, per-display keep-awake, targeted input
 * injection and launch-on-display logic. Mika itself adapts scrcpy
 * (Apache-2.0). See NOTICE in the repository root.
 */
package com.openminis.app.vdisplay

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The privileged core running in the Shizuku UserService process under the
 * shell identity (uid 2000). Owns the virtual displays: only displays created
 * here are ever touched by the input / launch paths — never the physical
 * display, never the global input focus.
 *
 * The [allowedUid] check happens in [VDisplayUserService.onTransact];
 * this class additionally re-validates the display id on every operation.
 */
internal class VDisplayShellServer(
    /** Only this uid (Minis) may call through the binder. */
    internal val allowedUid: Int,
) {
    internal val context: Context = VDisplayShellContext.create()

    /** displayId -> live VirtualDisplay. Guarded by [displays]. */
    internal val displays = HashMap<Int, VirtualDisplay>()

    /** displayId -> whether it was created with the trusted flag set. */
    internal val trustedDisplays = HashSet<Int>()

    // Cannot use getSystemService: a DisplayManager obtained that way is bound
    // to the system context whose package/uid pair does not match shell.
    @SuppressLint("PrivateApi")
    internal val displayManager: DisplayManager =
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)

    internal val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    /** Serializes launches so two concurrent callers cannot race on the same package. */
    internal val launchLock = Any()

    /** Lazily created shell-identity clipboard; guarded by [clipboardLock]. */
    @Volatile internal var clipboardDelegate: VDisplayClipboard? = null
    /** Paste-serialization lock (guards the whole write→inject→restore span). */
    @Volatile internal var pasteLockDelegate: Any? = null
    internal val clipboardLock = Any()

    // ------------------------------------------------------------------
    // Display creation
    // ------------------------------------------------------------------

    /**
     * Create the virtual display. Trusted flags first. When the ROM refuses
     * them (SecurityException):
     *   - [allowUntrustedDowngrade]=false -> fail closed, create NOTHING and
     *     report [VDisplayShellServer.CREATE_TRUSTED_REFUSED]. An implicitly
     *     downgraded display is exactly the "silently shares/steals focus"
     *     case the contract forbids;
     *   - true -> rebuild without the trusted-only flags (no OWN_DISPLAY_GROUP,
     *     no OWN_FOCUS, no ALWAYS_UNLOCKED) and report it honestly through
     *     [isDisplayTrusted].
     * Anything else fails closed with [VDisplayShellServer.CREATE_FAILED].
     */
    fun createDisplay(
        surface: Surface,
        width: Int,
        height: Int,
        dpi: Int,
        allowUntrustedDowngrade: Boolean,
    ): Int {
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION || dpi !in MIN_DPI..MAX_DPI) {
            Log.w(TAG, "createDisplay rejected size ${width}x${height} dpi=$dpi")
            return CREATE_FAILED
        }
        if (!surface.isValid) return CREATE_FAILED

        val trustedId = try {
            createVirtualDisplay(surface, width, height, dpi, trusted = true)
        } catch (t: Throwable) {
            if (t is SecurityException) {
                Log.w(TAG, "trusted display refused: ${t.message}")
                CREATE_TRUSTED_REFUSED
            } else {
                Log.e(TAG, "createDisplay failed", t)
                CREATE_FAILED
            }
        }
        if (trustedId > 0) return trustedId
        // A non-trust refusal (bad flags/args) is a hard failure — never retried.
        if (trustedId != CREATE_TRUSTED_REFUSED) return trustedId
        if (!allowUntrustedDowngrade) {
            // FAIL CLOSED: an untrusted display has no OWN_FOCUS, so on some
            // ROMs it shares (or takes) the main screen's focus. Creating one
            // implicitly is exactly what the contract forbids.
            Log.w(TAG, "no trusted/OWN_FOCUS display available; failing closed")
            return CREATE_TRUSTED_REFUSED
        }
        Log.w(TAG, "explicit untrusted downgrade requested by the caller")
        return try {
            createVirtualDisplay(surface, width, height, dpi, trusted = false)
        } catch (t: Throwable) {
            Log.e(TAG, "createDisplay failed after downgrade", t)
            CREATE_FAILED
        }
    }

    fun isDisplayTrusted(displayId: Int): Boolean {
        if (displayId <= Display.DEFAULT_DISPLAY) return false
        return synchronized(displays) { displayId in displays && displayId in trustedDisplays }
    }

    fun releaseDisplay(displayId: Int) {
        // Never touch the physical display, whatever the caller claims.
        if (displayId <= Display.DEFAULT_DISPLAY) return
        awake.stop(displayId)
        val (vd, trusted) = synchronized(displays) {
            displays.remove(displayId) to trustedDisplays.remove(displayId)
        }
        if (vd != null) {
            Log.i(TAG, "releaseDisplay $displayId (trusted=$trusted)")
            runCatching { vd.release() }
        }
    }

    fun releaseAll() {
        awake.stopAll()
        // Never leave the agent's payload in the user's clipboard after the
        // display goes away; restore() is a no-op when nothing is held or the
        // user has since copied something else. Serialized against a
        // concurrent paste so we do not restore while a paste holds the clip.
        synchronized(pasteLock) {
            runCatching { clipboardDelegate?.restore() }
        }
        val all = synchronized(displays) {
            displays.values.toList().also { displays.clear(); trustedDisplays.clear() }
        }
        all.forEach { runCatching { it.release() } }
        Log.i(TAG, "releaseAll: ${all.size} display(s) released")
    }

    fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid())

    fun displayInfo(displayId: Int): Bundle? {
        val vd = synchronized(displays) { displays[displayId] } ?: return null
        return runCatching {
            val d = vd.display
            Bundle().apply {
                putInt("rotation", d.rotation)
                putInt("width", d.width)
                putInt("height", d.height)
            }
        }.getOrNull()
    }

    /**
     * Only displays created by THIS server qualify; foreign ids — including the
     * physical display (display 0) — are refused before the map is consulted,
     * so no bookkeeping bug can ever target the user's real screen.
     */
    internal fun ownDisplay(displayId: Int): Boolean {
        if (displayId <= Display.DEFAULT_DISPLAY) return false
        return synchronized(displays) { displayId in displays }
    }

    @SuppressLint("PrivateApi")
    private fun createVirtualDisplay(surface: Surface, width: Int, height: Int, dpi: Int, trusted: Boolean): Int {
        // null == "trusted requested but this platform cannot grant OWN_FOCUS":
        // refuse rather than create a display that only looks isolated.
        val flags = displayFlags(Build.VERSION.SDK_INT, trusted) ?: return CREATE_TRUSTED_REFUSED
        val vd = displayManager.createVirtualDisplay("openminis-agent", width, height, dpi, surface, flags)
        val id = vd.display.displayId
        synchronized(displays) {
            displays[id] = vd
            // "trusted" only when the flags really carry OWN_FOCUS: the bit is
            // what isolates focus, everything else is just a hint.
            if (trusted && grantsOwnFocus(flags)) trustedDisplays += id
        }
        Log.i(TAG, "created display $id ${width}x${height} dpi=$dpi trusted=$trusted flags=$flags")
        setFullscreenWindowingMode(id)
        setImePolicy(id)
        if (DisplayAwakeProbe.needed(flags, FLAG_OWN_DISPLAY_GROUP, displayGroup(id))) awake.start(id)
        return id
    }

    /** DisplayInfo.displayGroupId (hidden field); null when unreadable. */
    @SuppressLint("PrivateApi")
    private fun displayGroup(displayId: Int): Int? = runCatching {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)!!
        val info = global.javaClass.getMethod("getDisplayInfo", Int::class.java).invoke(global, displayId)!!
        info.javaClass.getField("displayGroupId").getInt(info)
    }.getOrNull()

    // ------------------------------------------------------------------
    // Keep-awake: an OWN_DISPLAY_GROUP display has its own sleep timer and
    // would stop compositing after the screen-off timeout; injected input
    // does not count as user activity. Poke it every 4 s while it exists.
    // ------------------------------------------------------------------

    @get:SuppressLint("PrivateApi")
    private val userActivity: Pair<Any, Method>? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "power")
            val power = Class.forName("android.os.IPowerManager\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
            power to (DisplayAwakeProbe.userActivityMethod(power.javaClass) ?: error("no per-display userActivity"))
        }.onFailure { Log.w(TAG, "display keep-awake unavailable: ${it.message}") }.getOrNull()
    }

    private fun pokeDisplay(displayId: Int): Boolean {
        val (power, method) = userActivity ?: return false
        return try {
            method.invoke(power, displayId, SystemClock.uptimeMillis(), DisplayAwake.EVENT_TOUCH, 0)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "userActivity failed; keep-awake off", t)
            false
        }
    }

    private val awake by lazy {
        val timer = Executors.newSingleThreadScheduledExecutor {
            Thread(it, "openminis-display-awake").apply { isDaemon = true }
        }
        DisplayAwake(::pokeDisplay) { r ->
            val f = timer.scheduleWithFixedDelay(r, 0, DisplayAwake.PERIOD_MS, TimeUnit.MILLISECONDS)
            return@DisplayAwake { f.cancel(false) }
        }
    }

    // ------------------------------------------------------------------
    // Windowing / IME policy for freshly created displays
    // ------------------------------------------------------------------

    /**
     * Some systems default a new external/virtual display to freeform, which
     * shrinks apps into windows and skews input coordinates. Force fullscreen
     * on OUR display only, right after creation and before any launch.
     */
    @SuppressLint("PrivateApi")
    private fun setFullscreenWindowingMode(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            // WindowConfiguration.WINDOWING_MODE_FULLSCREEN = 1 (hidden constant)
            wm.javaClass.getMethod("setWindowingMode", Int::class.java, Int::class.java).invoke(wm, displayId, 1)
        }.onFailure {
            Log.w(TAG, "setWindowingMode failed, falling back to wm", it)
            if (exec("wm", "set-display-windowing-mode", "-d", "$displayId", "1") != 0) {
                Log.w(TAG, "could not set fullscreen windowing mode on display $displayId")
            }
        }
    }

    /** IWindowManager.setDisplayImePolicy: the IME must show on the virtual display, not the user's main screen. */
    @SuppressLint("PrivateApi")
    private fun setImePolicy(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java).invoke(wm, displayId, IME_POLICY)
        }.onFailure { Log.w(TAG, "setDisplayImePolicy failed", it) }
    }

    // ------------------------------------------------------------------
    // Small shell helpers (bounded time and output)
    // ------------------------------------------------------------------

    internal fun exec(vararg cmd: String): Int = sh(*cmd).first

    internal fun sh(vararg cmd: String): Pair<Int, String> {
        val p = try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            Log.w(TAG, "exec ${cmd.joinToString(" ")} failed", t)
            return -1 to ""
        }
        val output = java.util.concurrent.atomic.AtomicReference("")
        val reader = Thread { output.set(runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")) }
            .apply { isDaemon = true; start() }
        if (!p.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            Log.w(TAG, "exec ${cmd.joinToString(" ")} timed out")
            return -1 to ""
        }
        reader.join(READER_JOIN_MS)
        return p.exitValue() to output.get()
    }

    companion object {
        internal const val TAG = "OpenMinisVDisplay"
        /** createDisplay: generic failure (bad args, invalid surface, unknown throwable). */
        internal const val CREATE_FAILED = -1
        /** createDisplay: trusted/OWN_FOCUS refused and no downgrade was allowed. Distinct from a plain failure so callers can report UNSUPPORTED. */
        internal const val CREATE_TRUSTED_REFUSED = -2
        internal const val MAX_DIMENSION = 4096
        internal const val MIN_DPI = 72
        internal const val MAX_DPI = 1000
        internal const val MAX_TASKS = 100
        internal const val TAP_MS = 50L
        internal const val STROKE_MAX_POINTS = 1_024
        internal const val STROKE_MAX_MS = 10_000L
        internal const val CMD_TIMEOUT_S = 10L
        internal const val READER_JOIN_MS = 1_000L
        internal const val USER_CURRENT = -2 // UserHandle.USER_CURRENT

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (some hidden constants)
        internal const val FLAG_PUBLIC = 1 shl 0
        internal const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        internal const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        internal const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        internal const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        internal const val FLAG_TRUSTED = 1 shl 10
        internal const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        internal const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        internal const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        internal const val FLAG_OWN_FOCUS = 1 shl 14
        internal const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

        // WindowManager.DISPLAY_IME_POLICY_LOCAL
        internal const val IME_POLICY = 0

        /**
         * Virtual display flags.
         *
         * [trusted]=true requires an own-focus display: `null` means "this
         * Android version cannot express one" (OWN_FOCUS is API 34+) and the
         * caller must fail closed.
         *
         * [trusted]=false returns a plain virtual display. It carries no
         * OWN_FOCUS *and* no OWN_DISPLAY_GROUP, which does NOT mean "cannot
         * take focus": without its own group the display shares the default
         * group, and focus arbitration there is up to the ROM. That is why the
         * server only builds one when the caller explicitly opted in, and why
         * such a display is always reported as `trusted = false`.
         */
        internal fun displayFlags(sdk: Int, trusted: Boolean): Int? {
            var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
                FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
            if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                flags = flags or FLAG_TOUCH_FEEDBACK_DISABLED
            }
            if (!trusted) return flags
            if (!supportsIndependentFocus(sdk)) return null
            return flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or
                FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
        }

        /**
         * Independent focus is only guaranteed from API 34 (Android 14), where
         * FLAG_OWN_FOCUS exists. Younger platforms can be handed TRUSTED
         * displays but cannot promise the main screen keeps focus, and a
         * TRUSTED bit alone is not a focus-isolation guarantee.
         */
        internal fun supportsIndependentFocus(sdk: Int): Boolean =
            sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

        /** Whether a display created with these flags really owns its focus. */
        internal fun grantsOwnFocus(flags: Int): Boolean = flags and FLAG_OWN_FOCUS != 0
    }
}

/**
 * Per-display keep-awake scheduler (adapted from Mika's DisplayAwake).
 * A display created with OWN_DISPLAY_GROUP forms its own display group with
 * its own sleep timer; injected input does not count as user activity, so
 * without poking it the group sleeps, compositing stops, screenshots freeze
 * and taps go nowhere. Report a per-display user activity every [PERIOD_MS].
 */
internal class DisplayAwake(
    private val poke: (Int) -> Boolean,
    private val repeat: (Runnable) -> () -> Unit,
) {
    private val running = HashMap<Int, () -> Unit>()
    @Volatile private var unavailable = false

    fun start(displayId: Int) {
        if (unavailable) return
        val cancel = repeat(Runnable { if (!poke(displayId)) giveUp() })
        synchronized(running) { running.put(displayId, cancel) }?.invoke()
        if (unavailable) stopAll()
    }

    fun stop(displayId: Int) {
        synchronized(running) { running.remove(displayId) }?.invoke()
    }

    fun stopAll() {
        synchronized(running) { running.values.toList().also { running.clear() } }.forEach { it() }
    }

    private fun giveUp() {
        unavailable = true
        stopAll()
    }

    companion object {
        const val PERIOD_MS = 4_000L
        // PowerManager.USER_ACTIVITY_EVENT_TOUCH
        const val EVENT_TOUCH = 2
        private const val DEFAULT_DISPLAY_GROUP = 0

        /** Only poke when the display really forms its own group — poking the default group would keep the USER's main screen awake. */
        fun needed(flags: Int, ownGroupFlag: Int, groupId: Int?): Boolean =
            flags and ownGroupFlag != 0 && groupId != DEFAULT_DISPLAY_GROUP
    }
}

/** Static helpers split out so the probe logic is testable without a server instance. */
internal object DisplayAwakeProbe {
    fun needed(flags: Int, ownGroupFlag: Int, groupId: Int?): Boolean =
        DisplayAwake.needed(flags, ownGroupFlag, groupId)

    /** The per-display userActivity overload (Android 12+); null when only the display-less one exists (it would hit the default group). */
    fun userActivityMethod(cls: Class<*>): Method? = cls.methods.firstOrNull {
        it.name == "userActivity" && it.parameterTypes.contentEquals(PER_DISPLAY)
    }

    private val PER_DISPLAY = arrayOf<Class<*>>(
        Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!,
        Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
    )
}
