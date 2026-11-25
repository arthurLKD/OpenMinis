package com.openminis.app.ui.vdisplay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.ui.graphics.asImageBitmap
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.vdisplay.VirtualDisplayManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * [T-vdisplay-ui] The only place in the UI layer that touches the privileged
 * core. It maps `com.openminis.app.vdisplay.VirtualDisplayManager` (contract:
 * `vdisplay-api.md`) onto the UI's own vocabulary.
 *
 * Two rules this class exists to enforce:
 *
 *  1. **No display id crosses this boundary.** The core's API has no
 *     `displayId` parameter anywhere, and this backend never invents one: every
 *     call targets whatever display the engine says it owns. That is what makes
 *     "never fall back to the physical screen" structural rather than a check.
 *  2. **No fabricated success.** The core answers with `Result.Ok` /
 *     `Result.Failure(code, message)`; anything that is not Ok becomes a
 *     [VDisplayException] carrying a stable UI code, so the page can show a
 *     real reason instead of a generic failure.
 *
 * The core also reports status through [VirtualDisplayManager.Status], which is
 * polled by [pollStatus] — the core has no StateFlow of its own, so the page's
 * "is it still running" view is derived from that status, never assumed.
 *
 * All calls re-dispatch to [Dispatchers.IO]: PNG decoding and binder round
 * trips must not run on the main thread even though the engine is expected to
 * do its own dispatching.
 */
class CoreVDisplayBackend : VDisplayBackend {

    private val _session = MutableStateFlow<VDisplaySession?>(null)
    override val session: StateFlow<VDisplaySession?> = _session.asStateFlow()

    override fun isPrivilegedAccessReady(): Boolean =
        runCatching { ShizukuManager.isReady() }.getOrDefault(false) &&
            runCatching { VirtualDisplayManager.isEngineInstalled() }.getOrDefault(false)

    /**
     * Re-read the core's status into [session]. Cheap enough to call on every
     * page resume; returns the value it just published so a caller that wants a
     * one-shot answer does not have to re-read a StateFlow.
     */
    override suspend fun refreshSession(): VDisplaySession? = withContext(Dispatchers.IO) {
        val status = runCatching { VirtualDisplayManager.status() }.getOrNull()
        val next = status?.takeIf { it.running && it.displayId != null }?.let { s ->
            VDisplaySession(
                displayId = s.displayId!!,
                // The core reports `trusted = null` only when nothing is
                // running; here the display IS running, so the flag is known.
                // A missing value is treated as "not trusted" (the honest
                // reading) rather than crashing the page.
                trusted = s.trusted ?: false,
                config = VDisplayConfig(s.width, s.height, s.dpi),
            )
        }
        _session.value = next
        next
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override suspend fun start(config: VDisplayConfig): VDisplaySession = withContext(Dispatchers.IO) {
        val cfg = requireValid(config)
        val result = VirtualDisplayManager.start(cfg.widthPx, cfg.heightPx, cfg.densityDpi)
        result.requireOk("start")
        val status = VirtualDisplayManager.status()
        val session = VDisplaySession(
            displayId = status.displayId
                ?: throw VDisplayException(VDisplayErrorCode.CREATE_FAILED, "core reported no display id"),
            // A display was just created, so the core knows whether the ROM
            // granted the trusted flags; `null` here would mean the core lost
            // track of the display, which is reported as "not trusted".
            trusted = status.trusted ?: false,
            config = cfg,
        )
        _session.value = session
        session
    }

    override suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        // stop() is documented as safe when not running; a failure to stop is
        // still surfaced, because a half-released display is what leaks.
        VirtualDisplayManager.stop().requireOk("stop")
        _session.value = null
    }

    override suspend fun restart(config: VDisplayConfig): VDisplaySession = withContext(Dispatchers.IO) {
        // The engine has no resize: a surface's size is fixed at creation. A
        // stop that fails leaves the old display alive, so the start below would
        // be rejected as ALREADY_RUNNING rather than silently doubling up.
        VirtualDisplayManager.stop().requireOk("restart/stop")
        _session.value = null
        start(config)
    }

    // ── Preview ────────────────────────────────────────────────────────────

    override suspend fun capturePreview(): VDisplayFrame? = withContext(Dispatchers.IO) {
        val capture = when (val r = VirtualDisplayManager.capture()) {
            is VirtualDisplayManager.CaptureResult.Ok -> r.capture
            is VirtualDisplayManager.CaptureResult.Failure ->
                throw VDisplayException(VDisplayErrorCode.CAPTURE_FAILED, r.message)
        }
        // The core hands over PNG bytes (never a Bitmap), so decoding is this
        // layer's job and also the natural place to give the copy a lifetime.
        val bitmap: Bitmap = BitmapFactory.decodeByteArray(capture.png, 0, capture.png.size)
            ?: throw VDisplayException(VDisplayErrorCode.CAPTURE_FAILED, "frame was not decodable")
        VDisplayFrame(
            image = bitmap.asImageBitmap(),
            width = capture.width.takeIf { it > 0 } ?: bitmap.width,
            height = capture.height.takeIf { it > 0 } ?: bitmap.height,
            capturedAtMs = capture.capturedAtMs.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
        )
    }

    // ── Input ──────────────────────────────────────────────────────────────

    override suspend fun tap(x: Int, y: Int): Unit = withContext(Dispatchers.IO) {
        VirtualDisplayManager.tap(x, y).requireOk("tap($x,$y)")
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) =
        withContext(Dispatchers.IO) {
            VirtualDisplayManager.swipe(x1, y1, x2, y2, durationMs)
                .requireOk("swipe($x1,$y1 -> $x2,$y2)")
        }

    override suspend fun key(keyCode: Int): Unit = withContext(Dispatchers.IO) {
        VirtualDisplayManager.key(keyCode).requireOk("key($keyCode)")
    }

    override suspend fun launchPackage(packageName: String): Unit = withContext(Dispatchers.IO) {
        VirtualDisplayManager.launch(packageName.trim()).requireOk("launch($packageName)")
    }

    override suspend fun inputText(text: String, pressEnter: Boolean): Unit = withContext(Dispatchers.IO) {
        // The core's text() takes `pressEnter` itself — no second synthetic
        // ENTER event from here, which would double-submit on some IMEs.
        VirtualDisplayManager.text(text, pressEnter).requireOk("text")
    }

    // ── Mapping ────────────────────────────────────────────────────────────

    private fun requireValid(config: VDisplayConfig): VDisplayConfig {
        if (!config.isValid) {
            throw VDisplayException(
                VDisplayErrorCode.INVALID_CONFIG,
                "${config.widthPx}x${config.heightPx}@${config.densityDpi}",
            )
        }
        return config
    }

    /** Every core code maps to a stable UI code; nothing falls through silently. */
    private fun VirtualDisplayManager.Result.requireOk(what: String) {
        if (this is VirtualDisplayManager.Result.Ok) return
        val failure = this as VirtualDisplayManager.Result.Failure
        throw VDisplayException(failure.code.toErrorCode(), "$what: ${failure.message}")
    }

    private fun String.toErrorCode(): VDisplayErrorCode = when (this) {
        VirtualDisplayManager.Codes.UNSUPPORTED -> VDisplayErrorCode.UNSUPPORTED
        VirtualDisplayManager.Codes.NOT_RUNNING -> VDisplayErrorCode.NOT_RUNNING
        VirtualDisplayManager.Codes.ALREADY_RUNNING -> VDisplayErrorCode.CREATE_FAILED
        VirtualDisplayManager.Codes.INVALID_CONFIG -> VDisplayErrorCode.INVALID_CONFIG
        VirtualDisplayManager.Codes.CREATE_FAILED -> VDisplayErrorCode.CREATE_FAILED
        VirtualDisplayManager.Codes.CAPTURE_FAILED -> VDisplayErrorCode.CAPTURE_FAILED
        VirtualDisplayManager.Codes.INPUT_FAILED -> VDisplayErrorCode.INPUT_FAILED
        VirtualDisplayManager.Codes.PACKAGE_NOT_FOUND -> VDisplayErrorCode.PACKAGE_NOT_FOUND
        VirtualDisplayManager.Codes.LAUNCH_FAILED -> VDisplayErrorCode.LAUNCH_FAILED
        VirtualDisplayManager.Codes.IN_USE_ON_MAIN_SCREEN -> VDisplayErrorCode.MAIN_SCREEN_IN_USE
        // The engine is bound per Shizuku user service: losing it is the
        // service death the page must report as "start it again".
        VirtualDisplayManager.Codes.SERVICE_NOT_RUNNING -> VDisplayErrorCode.SERVICE_DIED
        else -> VDisplayErrorCode.UNKNOWN
    }

}
