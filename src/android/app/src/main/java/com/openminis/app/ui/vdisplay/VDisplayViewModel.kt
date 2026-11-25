package com.openminis.app.ui.vdisplay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-vdisplay-ui] State holder for the background-screen page.
 *
 * Responsibilities are deliberately narrow:
 *  - every [VDisplayBackend] call runs on [Dispatchers.IO] (binder IPC and
 *    ImageReader copies are never main-thread work);
 *  - the preview loop lives here and is *started/stopped by the screen's
 *    lifecycle*, capped at [VDisplayUiState.previewFps] (2 fps by default).
 *    Stopping the loop NEVER stops the display — the whole point of the
 *    feature is that the screen keeps running after the page is left;
 *  - config edits are only validated/committed here, the display itself is
 *    only ever created by the core.
 */
class VDisplayViewModel(
    private val backend: VDisplayBackend,
) : ViewModel() {

    private val _state = MutableStateFlow(VDisplayUiState())
    val state: StateFlow<VDisplayUiState> = _state.asStateFlow()

    private var previewJob: Job? = null

    init {
        // Cheap local probe; no IPC.
        _state.update { it.copy(privilegedReady = safeReady()) }
        // Mirror the core's own state machine: a binder/service death, or a
        // stop initiated elsewhere (CLI), must not leave this page claiming a
        // display it no longer owns.
        viewModelScope.launch {
            backend.session.collect { session ->
                _state.update { s ->
                    when {
                        session == null && s.isRunning -> s.copy(
                            status = VDisplayStatus.STOPPED,
                            displayId = null,
                            trusted = null,
                            appliedConfig = null,
                            needsRestart = false,
                            frame = null,
                            previewVisible = false,
                        )
                        session != null -> s.copy(
                            status = VDisplayStatus.RUNNING,
                            displayId = session.displayId,
                            trusted = session.trusted,
                            appliedConfig = s.appliedConfig ?: session.config,
                            needsRestart = s.appliedConfig != null && s.appliedConfig != s.config,
                        )
                        else -> s
                    }
                }
            }
        }
    }

    // ── Capability ─────────────────────────────────────────────────────────

    /** Called on resume: re-probe the transport AND re-read what the core owns. */
    fun refreshFromCore() {
        _state.update { it.copy(privilegedReady = safeReady()) }
        viewModelScope.launch {
            val session = runCatching { backend.refreshSession() }.getOrNull()
            if (session == null) {
                _state.update { s ->
                    if (!s.isRunning) s
                    else s.copy(
                        status = VDisplayStatus.STOPPED,
                        displayId = null,
                        trusted = null,
                        appliedConfig = null,
                        needsRestart = false,
                        frame = null,
                        previewVisible = false,
                    )
                }
            } else {
                _state.update { s ->
                    s.copy(
                        status = VDisplayStatus.RUNNING,
                        displayId = session.displayId,
                        trusted = session.trusted ?: s.trusted,
                        appliedConfig = session.config,
                        needsRestart = session.config != s.config,
                    )
                }
            }
        }
    }

    fun refreshPrivilegedState() {
        _state.update { it.copy(privilegedReady = safeReady()) }
    }

    private fun safeReady(): Boolean = runCatching { backend.isPrivilegedAccessReady() }
        .getOrDefault(false)

    // ── Config editing ─────────────────────────────────────────────────────

    // Values are stored exactly as typed. Nothing is silently clamped: an
    // out-of-range geometry fails `isValid` and the screen says so, instead of
    // creating a display at a size the user did not ask for.
    fun setWidth(raw: String) = _state.update { s ->
        val v = raw.filter { it.isDigit() }.take(4).toIntOrNull() ?: return@update s.copy(config = s.config)
        s.copy(config = s.config.copy(widthPx = v))
    }

    fun setHeight(raw: String) = _state.update { s ->
        val v = raw.filter { it.isDigit() }.take(4).toIntOrNull() ?: return@update s.copy(config = s.config)
        s.copy(config = s.config.copy(heightPx = v))
    }

    fun setDensity(raw: String) = _state.update { s ->
        val v = raw.filter { it.isDigit() }.take(3).toIntOrNull() ?: return@update s.copy(config = s.config)
        s.copy(config = s.config.copy(densityDpi = v))
    }

    fun applyPreset(widthPx: Int, heightPx: Int, densityDpi: Int = _state.value.config.densityDpi) =
        _state.update { s ->
            s.copy(config = s.config.copy(widthPx = widthPx, heightPx = heightPx, densityDpi = densityDpi))
        }

    fun setPreviewFps(fps: Int) = _state.update { s ->
        s.copy(previewFps = fps.coerceIn(VDisplayUiState.MIN_PREVIEW_FPS, VDisplayUiState.MAX_PREVIEW_FPS))
    }

    fun setPackageInput(value: String) = _state.update { it.copy(packageInput = value) }

    fun setTextInput(value: String) = _state.update { it.copy(textInput = value) }

    fun setPressEnterAfterText(value: Boolean) =
        _state.update { it.copy(pressEnterAfterText = value) }

    fun clearError() = _state.update { it.copy(error = null) }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    private fun recomputeNeedsRestart() = _state.update { s ->
        s.copy(needsRestart = s.appliedConfig != null && s.appliedConfig != s.config)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /** Called when the screen becomes visible. Only refreshes the preview. */
    fun startPreview() {
        if (previewJob?.isActive == true) return
        val fps = _state.value.previewFps
        previewJob = viewModelScope.launch {
            val periodMs = 1000L / fps.coerceAtLeast(1)
            while (true) {
                // A preview frame is a monitor frame: no frame is fine.
                val frame = runCatching { backend.capturePreview() }.getOrNull()
                _state.update { s ->
                    when {
                        !s.isRunning -> s.copy(previewVisible = false)
                        frame != null -> s.copy(
                            frame = frame,
                            previewVisible = true,
                            captureFailing = false,
                        )
                        else -> s.copy(captureFailing = true)
                    }
                }
                if (_state.value.previewFps != fps) return@launch
                delay(periodMs)
            }
        }
    }

    /**
     * Called when the screen stops. The preview loop ends; the display and
     * every app on it stay alive. The last frame is dropped so navigating
     * away does not keep a full-size ImageBitmap alive.
     */
    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        _state.update { it.copy(frame = null, previewVisible = false, captureFailing = false) }
    }

    fun onPreviewFpsChanged(fps: Int) {
        setPreviewFps(fps)
        // Restart the loop only while the page is actually watching.
        val wasRunning = previewJob != null
        stopPreview()
        if (wasRunning) startPreview()
    }

    // ── Display control ────────────────────────────────────────────────────

    fun start() = runCommand(starting = true) {
        val cfg = _state.value.config
        if (!cfg.isValid) throw VDisplayException(VDisplayErrorCode.INVALID_CONFIG)
        val session = backend.start(cfg)
        _state.update {
            it.copy(
                status = VDisplayStatus.RUNNING,
                displayId = session.displayId,
                trusted = session.trusted,
                appliedConfig = cfg,
                needsRestart = false,
                error = null,
            )
        }
    }

    fun stop() = runCommand(starting = false) {
        backend.stop()
        _state.update {
            it.copy(
                status = VDisplayStatus.STOPPED,
                displayId = null,
                trusted = null,
                appliedConfig = null,
                needsRestart = false,
                frame = null,
                previewVisible = false,
                captureFailing = false,
            )
        }
    }

    /** "Apply & restart": geometry is immutable on a live surface. */
    fun applyAndRestart() = runCommand(starting = true) {
        val cfg = _state.value.config
        if (!cfg.isValid) throw VDisplayException(VDisplayErrorCode.INVALID_CONFIG)
        val session = if (_state.value.isRunning) backend.restart(cfg) else backend.start(cfg)
        _state.update {
            it.copy(
                status = VDisplayStatus.RUNNING,
                displayId = session.displayId,
                trusted = session.trusted,
                appliedConfig = cfg,
                needsRestart = false,
                error = null,
                notice = VDisplayNotice.CONFIG_APPLIED,
            )
        }
    }

    // ── Input, always on the owned display ─────────────────────────────────

    fun tap(x: Int, y: Int) = runInput {
        if (!_state.value.isRunning) throw VDisplayException(VDisplayErrorCode.NOT_RUNNING)
        backend.tap(x, y)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 180L) = runInput {
        if (!_state.value.isRunning) throw VDisplayException(VDisplayErrorCode.NOT_RUNNING)
        backend.swipe(x1, y1, x2, y2, durationMs)
    }

    fun back() = sendKey(KeyEvent.KEYCODE_BACK)

    /**
     * The core's key path is a navigation/edit whitelist; HOME and APP_SWITCH
     * are device-level keys it deliberately rejects, so the page offers ENTER
     * instead of controls that could only ever fail.
     */
    fun enter() = sendKey(KeyEvent.KEYCODE_ENTER)

    private fun sendKey(keyCode: Int) = runInput {
        if (!_state.value.isRunning) throw VDisplayException(VDisplayErrorCode.NOT_RUNNING)
        backend.key(keyCode)
    }

    fun launchPackage() = runInput {
        val pkg = _state.value.packageInput.trim()
        if (pkg.isEmpty()) throw VDisplayException(VDisplayErrorCode.PACKAGE_NOT_FOUND)
        if (!_state.value.isRunning) throw VDisplayException(VDisplayErrorCode.NOT_RUNNING)
        backend.launchPackage(pkg)
        _state.update { it.copy(notice = VDisplayNotice.LAUNCH_SENT) }
    }

    fun sendText() = runInput {
        val text = _state.value.textInput
        if (text.isEmpty()) return@runInput
        if (!_state.value.isRunning) throw VDisplayException(VDisplayErrorCode.NOT_RUNNING)
        backend.inputText(text, _state.value.pressEnterAfterText)
        _state.update {
            it.copy(
                textInput = "",
                notice = VDisplayNotice.TEXT_SENT,
            )
        }
    }

    // ── Plumbing ───────────────────────────────────────────────────────────

    /** Wraps a display command: busy flag, IO dispatcher, error mapping. */
    private fun runCommand(starting: Boolean, block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    error = null,
                    status = if (starting) VDisplayStatus.STARTING else VDisplayStatus.STOPPING,
                )
            }
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
            _state.update { s ->
                result.fold(
                    onSuccess = { s.copy(busy = false) },
                    onFailure = { t ->
                        // Roll the transient status back to something truthful:
                        // a failed start leaves nothing running, a failed stop
                        // leaves whatever the core still owns.
                        s.copy(
                            busy = false,
                            status = when {
                                t is VDisplayException && t.code == VDisplayErrorCode.NOT_RUNNING ->
                                    VDisplayStatus.STOPPED
                                s.displayId != null -> VDisplayStatus.RUNNING
                                else -> VDisplayStatus.STOPPED
                            },
                            error = toError(t),
                        )
                    },
                )
            }
            recomputeNeedsRestart()
            _state.update {
                it.copy(privilegedReady = safeReady())
            }
        }
    }

    /** Input commands are fire-and-forget: no busy flag, they must not queue. */
    private fun runInput(block: suspend () -> Unit) {
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
            result.onFailure { t -> _state.update { it.copy(error = toError(t)) } }
        }
    }

    private fun toError(t: Throwable): VDisplayError = when (t) {
        is VDisplayException -> VDisplayError(t.code, t.detail)
        else -> VDisplayError(VDisplayErrorCode.UNKNOWN, t.message)
    }

    override fun onCleared() {
        stopPreview()
        super.onCleared()
    }

    companion object {
        fun factory(backend: VDisplayBackend): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    VDisplayViewModel(backend) as T
            }
    }
}
