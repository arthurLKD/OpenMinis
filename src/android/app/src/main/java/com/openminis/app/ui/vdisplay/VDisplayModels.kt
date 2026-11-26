package com.openminis.app.ui.vdisplay

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow

/**
 * [T-vdisplay-ui] UI-side contract for Minis' background virtual display
 * ("后台屏").
 *
 * The screen deliberately does NOT talk to Android's DisplayManager /
 * VirtualDisplay / ImageReader directly: every privileged call goes through
 * [VDisplayBackend], implemented by [CoreVDisplayBackend] on top of the
 * `com.openminis.app.vdisplay.VirtualDisplayManager` singleton (API contract:
 * `vdisplay-api.md`). That keeps this package free of main-thread IPC and of
 * display-lifecycle bugs: the backend owns the display, the ViewModel owns the
 * *preview loop*, and the screen only renders state.
 *
 * The screen page itself does NOT create a SYSTEM_ALERT_WINDOW overlay; the
 * separate [VDisplayFloatingOverlayController] owns the optional floating
 * preview window across Minis and other apps without disturbing display
 * lifecycle or physical-screen focus.
 *
 * Focus is a different matter and must not be assumed away: an ImageReader as
 * the display surface only decides where pixels go, it does NOT decide focus
 * ownership. Whether the display takes the top focus away from the physical
 * screen depends entirely on the flags the display was created with
 * (TRUSTED / OWN_FOCUS / STEAL_TOP_FOCUS_DISABLED / OWN_DISPLAY_GROUP and the
 * ROM's interpretation of them). The core reports the outcome per display as
 * [VDisplaySession.trusted]; when the ROM refuses the trusted flags it
 * silently re-creates without them, and the UI must then say "焦点与主屏共享"
 * instead of claiming isolation. The UI never constructs a display itself.
 */
interface VDisplayBackend {

    /** Whether the privileged transport (Shizuku / AXManager / Sui) is up. */
    fun isPrivilegedAccessReady(): Boolean

    /**
     * The display the core currently owns, or null when stopped. Mirrors the
     * core's own state machine, so a binder/service death shows up here too
     * (it becomes null) rather than leaving the UI claiming "running".
     */
    val session: StateFlow<VDisplaySession?>

    /**
     * Re-read the core's status into [session]. Called when the page becomes
     * visible, because the display's lifetime outlives this page: it may have
     * been stopped or lost while the user was elsewhere (including by the
     * agent CLI, which shares the same single display).
     */
    suspend fun refreshSession(): VDisplaySession?

    /**
     * Connect + create the virtual display. Returns the live session.
     * Blocking / suspend: always called from Dispatchers.IO by the ViewModel.
     */
    suspend fun start(config: VDisplayConfig): VDisplaySession

    /**
     * Release the virtual display (and its reader / keep-alive).
     * Idempotent.
     */
    suspend fun stop()

    /**
     * Re-create the display with a new size/density. Surfaces are immutable in
     * size once created, so the core has no resize call: this is stop+start.
     */
    suspend fun restart(config: VDisplayConfig): VDisplaySession

    /**
     * Grab the newest frame, or null when no frame is available yet (display
     * just created, app has not drawn). Called at the preview rate off the
     * main thread; implementations must return quickly.
     */
    suspend fun capturePreview(): VDisplayFrame?

    /** Tap at display-pixel coordinates [x], [y]. */
    suspend fun tap(x: Int, y: Int)

    /** Swipe from ([x1],[y1]) to ([x2],[y2]) in display pixels. */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long)

    /**
     * Send a key event to the owned display. The core accepts a whitelist
     * (d-pad, enter, delete, tab, space, escape, page up/down, back, menu) and
     * rejects device-level keys (power, volume, HOME, APP_SWITCH) — those would
     * act on the phone, not on this display.
     */
    suspend fun key(keyCode: Int)

    /** Launch an app by package name onto the owned display. */
    suspend fun launchPackage(packageName: String)

    /** Inject [text] into the focused field of the virtual display. */
    suspend fun inputText(text: String, pressEnter: Boolean)
}

/** A display currently owned by the core. */
data class VDisplaySession(
    val displayId: Int,
    /**
     * true  = created with the trusted flags → own focus, top focus on the
     *         physical screen untouched.
     * false = the ROM refused them and the core explicitly downgraded; the
     *         display shares focus with the physical screen. Shown to the user
     *         rather than hidden.
     */
    val trusted: Boolean,
    val config: VDisplayConfig,
)

/** Thrown by [VDisplayBackend] implementations; carries a stable error code. */
class VDisplayException(
    val code: VDisplayErrorCode,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail ?: code.name, cause)

/**
 * Size + density of the virtual display. Density is expressed in dpi (the
 * same unit `adb shell wm density` uses) because that is what the core passes
 * to DisplayManager; the UI derives scale from it.
 */
data class VDisplayConfig(
    val widthPx: Int = DEFAULT_WIDTH,
    val heightPx: Int = DEFAULT_HEIGHT,
    val densityDpi: Int = DEFAULT_DPI,
) {
    fun normalized(): VDisplayConfig = copy(
        widthPx = widthPx.coerceIn(MIN_SIZE, MAX_SIZE),
        heightPx = heightPx.coerceIn(MIN_SIZE, MAX_SIZE),
        densityDpi = densityDpi.coerceIn(MIN_DPI, MAX_DPI),
    )

    val isValid: Boolean
        get() = widthPx in MIN_SIZE..MAX_SIZE &&
            heightPx in MIN_SIZE..MAX_SIZE &&
            densityDpi in MIN_DPI..MAX_DPI

    /** 0..1 scale aligned with `wm size` / `wm density` semantics. */
    val densityScale: Float get() = densityDpi / 160f

    companion object {
        // Deliberately a STRICT SUBSET of the core's documented bounds
        // (vdisplay-api.md §1: 1..4096 px, 72..1000 dpi). The core must accept
        // anything a display can physically be; the UI refuses sizes no app
        // could lay out on. The CLI mirrors these exact values
        // (VDisplayCli.MIN_SIZE…) and a unit test asserts the two stay in sync,
        // so change both together.
        const val MIN_SIZE = 240
        const val MAX_SIZE = 3840
        const val MIN_DPI = 120
        const val MAX_DPI = 800
        const val DEFAULT_WIDTH = 1080
        const val DEFAULT_HEIGHT = 1920
        const val DEFAULT_DPI = 420
    }
}

/** A single decoded preview frame. */
class VDisplayFrame(
    val image: ImageBitmap,
    val width: Int,
    val height: Int,
    val capturedAtMs: Long,
)

enum class VDisplayStatus { STOPPED, STARTING, RUNNING, STOPPING }

enum class VDisplayErrorCode {
    SHIZUKU_UNAVAILABLE,
    PERMISSION_DENIED,
    CREATE_FAILED,
    NOT_RUNNING,
    INVALID_CONFIG,
    /** ROM refuses the requested configuration (e.g. independent focus). */
    UNSUPPORTED,
    PACKAGE_NOT_FOUND,
    /** The package has a task the user is using on the physical screen. */
    MAIN_SCREEN_IN_USE,
    LAUNCH_FAILED,
    INPUT_FAILED,
    /** Text contained characters the core cannot inject (non-ASCII). */
    TEXT_REJECTED,
    CAPTURE_FAILED,
    /** The core's service/binder died; the display is gone and must be rebuilt. */
    SERVICE_DIED,
    UNKNOWN,
}

data class VDisplayError(
    val code: VDisplayErrorCode,
    /** Raw exception/diagnostic text from the core; shown after the localized line. */
    val detail: String? = null,
)

/** Everything the screen renders. Immutable, single StateFlow in the ViewModel. */
data class VDisplayUiState(
    val status: VDisplayStatus = VDisplayStatus.STOPPED,
    /** Geometry the user is editing; applied on start or on Apply & restart. */
    val config: VDisplayConfig = VDisplayConfig(),
    /** Geometry the running display was actually created with (null when stopped). */
    val appliedConfig: VDisplayConfig? = null,
    /** Whether the editing config differs from the running one. */
    val needsRestart: Boolean = false,
    val displayId: Int? = null,
    /** null when no display is owned; see [VDisplaySession.trusted]. */
    val trusted: Boolean? = null,
    val frame: VDisplayFrame? = null,
    val previewVisible: Boolean = false,
    /** Preview refresh rate cap, 1..8 fps. Deliberately low: a preview is a
     *  monitor, not a game stream, and every frame costs a native copy. */
    val previewFps: Int = DEFAULT_PREVIEW_FPS,
    val captureFailing: Boolean = false,
    val busy: Boolean = false,
    val error: VDisplayError? = null,
    val privilegedReady: Boolean = false,
    val packageInput: String = "",
    val textInput: String = "",
    val pressEnterAfterText: Boolean = false,
    /** Transient toast-ish confirmation, e.g. "Launch requested". */
    val notice: VDisplayNotice? = null,
) {
    val isRunning: Boolean get() = status == VDisplayStatus.RUNNING

    companion object {
        const val DEFAULT_PREVIEW_FPS = 2
        const val MIN_PREVIEW_FPS = 1
        const val MAX_PREVIEW_FPS = 8
    }
}

enum class VDisplayNotice { LAUNCH_SENT, TEXT_SENT, CONFIG_APPLIED }
