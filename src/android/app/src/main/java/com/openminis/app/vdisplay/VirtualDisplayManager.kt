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
 */
package com.openminis.app.vdisplay

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.Display
import android.view.KeyEvent
import com.openminis.app.BuildConfig
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Public entry point of the background virtual-display core ("后台屏"). A
 * process-wide singleton shared by the UI
 * ([com.openminis.app.ui.vdisplay.CoreVDisplayBackend]) and the CLI
 * ([com.openminis.app.sandbox.offload.VDisplayOffloadHandler]): both drive the
 * SAME display (one display at a time by design).
 *
 * ## API shape (v2)
 * Every command returns a [Result] — `Ok(data)` or
 * `Failure(code, message, exit)` with `code` from [Codes] — and captures return
 * [CaptureResult]. Diagnostics come from [status]. There is deliberately **no
 * `displayId` parameter** anywhere: the manager only ever targets the display
 * it created, so the physical screen (display 0) is unreachable by
 * construction, not by a check.
 *
 * ## Threading
 * The command methods are **blocking**: they own the binder round trip, the
 * frame copy and the coroutine plumbing, so a plain `fun` keeps them usable
 * from both the UI backend and the non-coroutine CLI handler. They must be
 * called off the main thread — every current caller already does (the UI wraps
 * them in `withContext(Dispatchers.IO)`, the CLI runs on an offload worker).
 * [stopAsync] and [shutdown] are fire-and-forget and may be called anywhere.
 *
 * ## Failure semantics
 *  - nothing ever falls back to the physical display, and no input path can
 *    address display 0;
 *  - an untrusted display is **never created implicitly**: when the trusted /
 *    OWN_FOCUS flags are refused the call fails closed with
 *    [Codes.UNSUPPORTED] unless the caller explicitly passes
 *    `allowUntrustedDowngrade = true`;
 *  - service/binder death releases all local resources and surfaces
 *    [VDisplayState.Failed] with [VFailure.ServiceDied] (or drops back to Idle
 *    when no display was up); a later [start] recovers;
 *  - the privileged side holds a death token on this process, so a Minis death
 *    destroys the virtual display instead of orphaning it.
 */
object VirtualDisplayManager {

    private const val TAG = "VirtualDisplayManager"
    private const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L

    /** Message used only to carry the Unconfirmed outcome through [Result.Failure]. */
    private const val UNCONFIRMED_MARKER = "__partial_delivery__"

    // ------------------------------------------------------------------
    // Command result model (consumed by the UI backend and the CLI handler)
    // ------------------------------------------------------------------

    /** Stable error codes. The UI maps them one-to-one; the CLI prints them. */
    object Codes {
        const val UNSUPPORTED = "UNSUPPORTED"
        const val NOT_RUNNING = "NOT_RUNNING"
        const val ALREADY_RUNNING = "ALREADY_RUNNING"
        const val INVALID_CONFIG = "INVALID_CONFIG"
        const val CREATE_FAILED = "CREATE_FAILED"
        const val CAPTURE_FAILED = "CAPTURE_FAILED"
        const val INPUT_FAILED = "INPUT_FAILED"
        const val PACKAGE_NOT_FOUND = "PACKAGE_NOT_FOUND"
        const val LAUNCH_FAILED = "LAUNCH_FAILED"
        const val IN_USE_ON_MAIN_SCREEN = "IN_USE_ON_MAIN_SCREEN"
        const val SERVICE_NOT_RUNNING = "SERVICE_NOT_RUNNING"
    }

    sealed class Result {
        /** [data] is a fresh mutable object; callers may add fields of their own. */
        data class Ok(val data: JSONObject) : Result()

        /**
         * [exit] is the exit code the CLI should use: 2 = caller arguments,
         * 77 = transport (Shizuku) unavailable, 1 = everything else.
         */
        data class Failure(
            val code: String,
            val message: String,
            val exit: Int = 1,
        ) : Result()
    }

    /** One captured frame: PNG bytes plus the geometry/time they belong to. */
    class Capture(
        val png: ByteArray,
        val width: Int,
        val height: Int,
        val capturedAtMs: Long,
    )

    sealed class CaptureResult {
        data class Ok(val capture: Capture) : CaptureResult()
        data class Failure(
            val code: String,
            val message: String,
            val exit: Int = 1,
        ) : CaptureResult()
    }

    /**
     * Snapshot of what the core currently owns. Cheap (no binder round trip),
     * safe from any thread, and the single source of `status` for both the CLI
     * envelope and the UI's "is it still running" view.
     */
    data class Status(
        val running: Boolean,
        val displayId: Int?,
        val width: Int,
        val height: Int,
        val dpi: Int,
        /** null when not running; otherwise what the ROM actually granted. */
        val trusted: Boolean?,
        val engineInstalled: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("running", running)
            .put("displayId", displayId ?: JSONObject.NULL)
            .put("width", width)
            .put("height", height)
            .put("dpi", dpi)
            .put("trusted", trusted ?: JSONObject.NULL)
            .put("engineInstalled", engineInstalled)
    }

    // ------------------------------------------------------------------
    // State model (documented; used for diagnostics)
    // ------------------------------------------------------------------

    sealed class VDisplayState {
        data object Idle : VDisplayState()
        data object Connecting : VDisplayState()
        data object Connected : VDisplayState()
        data object Starting : VDisplayState()
        data class Running(
            val displayId: Int,
            val width: Int,
            val height: Int,
            val dpi: Int,
            val trusted: Boolean,
        ) : VDisplayState()
        data object Stopping : VDisplayState()
        data class Failed(val reason: VFailure, val message: String? = null) : VDisplayState()
    }

    enum class VFailure {
        ShizukuNotReady,
        BindFailed,
        ServiceDied,
        CreateDisplayFailed,
        InvalidArguments,
        AlreadyRunning,
        NotConnected,
        ScreenshotFailed,
        /** The ROM refused the trusted/OWN_FOCUS flags and no downgrade was allowed. */
        TrustedDisplayUnsupported,
    }

    sealed class VConnectResult {
        data object Ok : VConnectResult()
        data class Denied(val failure: VFailure, val message: String? = null) : VConnectResult()
    }

    enum class VTextResult { Dispatched, Unconfirmed, NotSent }
    enum class VLaunchResult { Ok, InUseOnMain, NotFound, Error }

    val state: StateFlow<VDisplayState> get() = _state.asStateFlow()
    private val _state = MutableStateFlow<VDisplayState>(VDisplayState.Idle)

    /** displayId of the running display, or null. */
    val displayId: Int? get() = (_state.value as? VDisplayState.Running)?.displayId
    val isRunning: Boolean get() = _state.value is VDisplayState.Running

    /** Latest cached frame (may be null). Prefer [capture] for a fresh copy. */
    val latestFrame: Bitmap? get() = frameSink?.latestFrameBitmap()

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var initialized = false
    @Volatile private var appContext: Application? = null
    @Volatile private var service: IVirtualDisplayService? = null
    @Volatile private var frameSink: FrameSink? = null
    @Volatile private var connection: ServiceConnection? = null

    /** Death token handed to the privileged side; our death => it releases everything. */
    private val ownerToken = Binder()
    private var serviceDeathRecipient: IBinder.DeathRecipient? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, VDisplayUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("vdisplay")
            .version(BuildConfig.VERSION_CODE)
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        AppLogger.warning(TAG, "Shizuku binder died")
        handleServiceDeath("shizuku binder dead")
    }

    // ------------------------------------------------------------------
    // Initialization
    // ------------------------------------------------------------------

    /**
     * Idempotent; called once from app startup ([com.openminis.app.MinisApp],
     * right after `ShizukuManager.init`). Optional: [ensureInitialized] lazily
     * resolves the Application on first use, so a missed call cannot leave the
     * core unusable.
     */
    fun init(app: Application) {
        if (initialized) return
        initialized = true
        appContext = app
        runCatching { Shizuku.addBinderDeadListener(binderDeadListener) }
            .onFailure { AppLogger.warning(TAG, "addBinderDeadListener failed: ${it.message}") }
    }

    /** Same as [init] for callers that only hold a [Context] (UI/CLI hand-off). */
    fun init(context: Context) {
        val app = context.applicationContext as? Application
        if (app != null) {
            init(app)
        } else {
            AppLogger.warning(TAG, "init(context) without an Application; falling back to lazy init")
            ensureInitialized()
        }
    }

    /**
     * Best-effort initialization for callers that never called [init].
     * Resolves the process' Application via ActivityThread, which always exists
     * inside a normal app process.
     */
    fun ensureInitialized(): Boolean {
        if (initialized) return true
        val app = appContext ?: runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Application
        }.getOrNull()
        if (app == null) {
            AppLogger.warning(TAG, "no Application available; continuing without init()")
            return false
        }
        init(app)
        return true
    }

    // ------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------

    /**
     * Bind the privileged UserService. Idempotent: returns Ok when already
     * connected. Requires [ShizukuManager.isReady]; otherwise returns
     * Denied(ShizukuNotReady) without touching Shizuku permission UI.
     *
     * [start] calls this itself, so callers never have to.
     */
    fun connect(timeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS): VConnectResult = runBlocking {
        mutex.withLock { connectLocked(timeoutMs) }
    }

    /** Must be called under [mutex]. */
    private suspend fun connectLocked(timeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS): VConnectResult {
        ensureInitialized()
        service?.let {
            if (runCatching { it.asBinder().pingBinder() }.getOrDefault(false)) {
                return VConnectResult.Ok
            }
            // Stale reference — fall through and rebind.
            cleanupLocalResources()
        }
        if (!ShizukuManager.isReady()) {
            AppLogger.warning(TAG, "connect refused: Shizuku not ready (state=${ShizukuManager.snapshot.value.state})")
            _state.value = VDisplayState.Failed(VFailure.ShizukuNotReady)
            return VConnectResult.Denied(VFailure.ShizukuNotReady, "Shizuku is not ready")
        }
        _state.value = VDisplayState.Connecting
        val bound = bindWithTimeout(timeoutMs)
        if (bound == null) {
            val failure = if (!ShizukuManager.isReady()) VFailure.ShizukuNotReady else VFailure.BindFailed
            _state.value = VDisplayState.Failed(failure, "UserService bind timed out")
            return VConnectResult.Denied(failure)
        }
        service = bound
        // Tie the privileged side's lifetime to this process.
        runCatching { bound.attachOwner(ownerToken) }
            .onFailure { AppLogger.warning(TAG, "attachOwner failed: ${it.message}") }
        _state.value = VDisplayState.Connected
        AppLogger.info(TAG, "connected (uid/pid=${runCatching { bound.identity().joinToString() }.getOrDefault("?")})")
        return VConnectResult.Ok
    }

    private suspend fun bindWithTimeout(timeoutMs: Long): IVirtualDisplayService? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (binder == null) {
                            if (cont.isActive) cont.resume(null)
                            return
                        }
                        val iface = IVirtualDisplayService.Stub.asInterface(binder)
                        val recipient = IBinder.DeathRecipient { handleServiceDeath("service binder died") }
                        runCatching { binder.linkToDeath(recipient, 0) }
                        serviceDeathRecipient = recipient
                        connection = this
                        if (cont.isActive) cont.resume(iface)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        handleServiceDeath("service disconnected")
                    }
                }
                try {
                    Shizuku.bindUserService(userServiceArgs, conn)
                } catch (t: Throwable) {
                    AppLogger.warning(TAG, "bindUserService threw: ${t.message}")
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation {
                    runCatching { Shizuku.unbindUserService(userServiceArgs, conn, true) }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Display lifecycle
    // ------------------------------------------------------------------

    /**
     * Create the virtual display (1..4096 px, 72..1000 dpi). Connects first if
     * needed, so a caller that never called [connect] still works.
     *
     * Idempotent for identical geometry: an already-running display with the
     * same size/dpi is reported as Ok instead of creating a second one.
     * Different geometry while running is refused as [Codes.ALREADY_RUNNING]
     * (the caller must [stop] first — a Surface cannot be resized).
     *
     * [allowUntrustedDowngrade] defaults to **false**: if the ROM (or this
     * Android version) cannot grant a trusted, OWN_FOCUS display the call fails
     * closed with [Codes.UNSUPPORTED] and creates nothing. Opting in explicitly
     * creates an untrusted display, reported as `trusted=false`; such a display
     * has no own focus, which on some ROMs means focus is shared with — or
     * taken from — the main screen.
     */
    fun start(
        width: Int,
        height: Int,
        dpi: Int = 320,
        allowUntrustedDowngrade: Boolean = false,
    ): Result = runBlocking { startLocked(width, height, dpi, allowUntrustedDowngrade) }

    /** Must be called under [mutex]. */
    private suspend fun startLocked(
        width: Int,
        height: Int,
        dpi: Int,
        allowUntrustedDowngrade: Boolean,
    ): Result = mutex.withLock {
        if (width !in 1..VDisplayShellServer.MAX_DIMENSION ||
            height !in 1..VDisplayShellServer.MAX_DIMENSION ||
            dpi !in VDisplayShellServer.MIN_DPI..VDisplayShellServer.MAX_DPI
        ) {
            _state.value = VDisplayState.Failed(VFailure.InvalidArguments, "size/dpi out of range")
            return@withLock Result.Failure(
                Codes.INVALID_CONFIG,
                "width/height must be 1..${VDisplayShellServer.MAX_DIMENSION} and dpi " +
                    "${VDisplayShellServer.MIN_DPI}..${VDisplayShellServer.MAX_DPI}; got ${width}x$height@$dpi",
                2,
            )
        }
        val current = _state.value as? VDisplayState.Running
        if (current != null) {
            if (current.width == width && current.height == height && current.dpi == dpi) {
                return@withLock Result.Ok(runningData(current))
            }
            return@withLock Result.Failure(
                Codes.ALREADY_RUNNING,
                "display ${current.displayId} is already running at " +
                    "${current.width}x${current.height}@${current.dpi}; stop it first",
            )
        }
        if (_state.value is VDisplayState.Starting || _state.value is VDisplayState.Stopping) {
            return@withLock Result.Failure(Codes.ALREADY_RUNNING, "another lifecycle operation is in progress")
        }
        when (val conn = connectLocked()) {
            is VConnectResult.Ok -> Unit
            is VConnectResult.Denied -> {
                val message = conn.message ?: conn.failure.name
                return@withLock Result.Failure(
                    Codes.SERVICE_NOT_RUNNING,
                    "privileged service unavailable: $message",
                    if (conn.failure == VFailure.ShizukuNotReady) 77 else 1,
                )
            }
        }
        val svc = service
            ?: return@withLock Result.Failure(Codes.SERVICE_NOT_RUNNING, "privileged service unavailable", 77)

        _state.value = VDisplayState.Starting
        val sink = FrameSink(width, height)
        val id = withContext(Dispatchers.IO) {
            runCatching { svc.createDisplay(sink.surface, width, height, dpi, allowUntrustedDowngrade) }
                .onFailure { AppLogger.warning(TAG, "createDisplay threw: ${it.message}") }
                .getOrDefault(VDisplayShellServer.CREATE_FAILED)
        }
        if (id <= 0) {
            sink.release()
            return@withLock when (id) {
                // Fail closed: the ROM/API level refused trusted/OWN_FOCUS and the
                // caller did not ask for a possibly focus-sharing display.
                VDisplayShellServer.CREATE_TRUSTED_REFUSED -> {
                    _state.value = VDisplayState.Failed(
                        VFailure.TrustedDisplayUnsupported,
                        "trusted/OWN_FOCUS display refused",
                    )
                    Result.Failure(
                        Codes.UNSUPPORTED,
                        "this device (API ${Build.VERSION.SDK_INT}) cannot provide an " +
                            "independent-focus (trusted) virtual display; refusing to create one that " +
                            "may share or steal the main screen's focus. No display was created. " +
                            "Independent focus requires Android 14 (API 34) or newer.",
                    )
                }
                else -> {
                    _state.value = VDisplayState.Failed(VFailure.CreateDisplayFailed, "createDisplay returned $id")
                    Result.Failure(Codes.CREATE_FAILED, "createDisplay failed (code $id)")
                }
            }
        }
        frameSink = sink
        sink.start()
        // Ask the server what it actually granted instead of trusting the request.
        val trusted = runCatching { svc.isDisplayTrusted(id) }.getOrDefault(false)
        val running = VDisplayState.Running(id, width, height, dpi, trusted)
        _state.value = running
        AppLogger.info(TAG, "display $id running ${width}x${height} dpi=$dpi trusted=$trusted")
        Result.Ok(runningData(running))
    }

    private fun runningData(r: VDisplayState.Running): JSONObject = JSONObject()
        .put("displayId", r.displayId)
        .put("trusted", r.trusted)
        .put("width", r.width)
        .put("height", r.height)
        .put("dpi", r.dpi)

    /** Release the display and all local resources. Idempotent. */
    fun stop(): Result = runBlocking { mutex.withLock { stopLocked() } }

    /** Non-suspending stop for teardown paths (onDestroy, CLI exit). */
    fun stopAsync() {
        scope.launch { mutex.withLock { stopLocked() } }
    }

    /** Must be called under [mutex]. */
    private fun stopLocked(): Result {
        val svc = service
        val id = displayId
        val sink = frameSink
        if (svc == null && id == null && sink == null) {
            if (_state.value !is VDisplayState.Failed) _state.value = VDisplayState.Idle
            return Result.Ok(JSONObject().put("running", false))
        }
        _state.value = VDisplayState.Stopping
        if (id != null && id > Display.DEFAULT_DISPLAY) {
            // Best effort: a dead binder must not keep the local resources alive.
            runCatching { svc?.releaseDisplay(id) }
                .onFailure { AppLogger.warning(TAG, "releaseDisplay failed: ${it.message}") }
        }
        sink?.release()
        frameSink = null
        val alive = svc != null && runCatching { svc.asBinder().pingBinder() }.getOrDefault(false)
        _state.value = if (alive) VDisplayState.Connected else VDisplayState.Idle
        AppLogger.info(TAG, "display ${id ?: "?"} stopped")
        return Result.Ok(JSONObject().put("running", false))
    }

    /** Stop and unbind the UserService. The process may exit afterwards. */
    fun shutdown() {
        scope.launch {
            mutex.withLock {
                stopLocked()
                unbindLocked()
                _state.value = VDisplayState.Idle
            }
        }
    }

    private fun unbindLocked() {
        val conn = connection
        connection = null
        service = null
        serviceDeathRecipient = null
        if (conn != null) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, conn, true) }
                .onFailure { AppLogger.warning(TAG, "unbindUserService failed: ${it.message}") }
        }
    }

    /**
     * Service/binder death: release everything locally. A display that was
     * running becomes Failed(ServiceDied); otherwise drop back to Idle.
     * No automatic restart — the next [start] reconnects.
     *
     * Lock-free on purpose: this fires from binder death callbacks which may
     * race any operation; every cleanup step here is idempotent ([FrameSink]
     * guards against double release, volatile refs tolerate concurrent nulls).
     */
    private fun handleServiceDeath(reason: String) {
        val wasRunning = isRunning
        frameSink?.release()
        frameSink = null
        service = null
        connection = null
        serviceDeathRecipient = null
        _state.value = if (wasRunning) {
            VDisplayState.Failed(VFailure.ServiceDied, reason)
        } else {
            VDisplayState.Idle
        }
        AppLogger.warning(TAG, "service death ($reason); wasRunning=$wasRunning")
    }

    /** Must be called under [mutex]. */
    private fun cleanupLocalResources() {
        frameSink?.release()
        frameSink = null
        service = null
        connection = null
        serviceDeathRecipient = null
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    /** Non-blocking, binder-free snapshot used by the CLI `status` command. */
    fun status(): Status {
        val running = _state.value as? VDisplayState.Running
        return Status(
            running = running != null,
            displayId = running?.displayId,
            width = running?.width ?: 0,
            height = running?.height ?: 0,
            dpi = running?.dpi ?: 0,
            trusted = running?.trusted,
            engineInstalled = isEngineInstalled(),
        )
    }

    /**
     * The privileged engine ships inside this APK ([VDisplayUserService]), so it
     * is installed whenever this code runs. Kept as an explicit probe so the
     * UI's "privileged access ready" gate has an engine check of its own.
     */
    fun isEngineInstalled(): Boolean = true

    // ------------------------------------------------------------------
    // Captures
    // ------------------------------------------------------------------

    /**
     * Copy of the newest frame as PNG. Fails with [Codes.NOT_RUNNING] when no
     * display is up and [Codes.CAPTURE_FAILED] when nothing has been drawn yet.
     */
    fun capture(): CaptureResult = runBlocking { captureInternal() }

    private suspend fun captureInternal(): CaptureResult = withContext(Dispatchers.IO) {
        if (!isRunning) {
            return@withContext CaptureResult.Failure(Codes.NOT_RUNNING, "no virtual display is running")
        }
        val sink = frameSink
            ?: return@withContext CaptureResult.Failure(Codes.CAPTURE_FAILED, "frame sink is gone")
        val snap = sink.latestFrameSnapshot()
            ?: return@withContext CaptureResult.Failure(
                Codes.CAPTURE_FAILED,
                "no frame yet: nothing has been drawn on the display",
            )
        CaptureResult.Ok(Capture(snap.png, snap.width, snap.height, snap.capturedAtMs))
    }

    /** Copy of the latest frame; null when nothing has been drawn yet. */
    fun screenshot(): Bitmap? = runBlocking {
        withContext(Dispatchers.IO) { if (isRunning) frameSink?.latestFrameBitmap() else null }
    }

    /** PNG bytes of the latest frame; null when there is none. */
    fun screenshotPng(): ByteArray? = runBlocking {
        withContext(Dispatchers.IO) { if (isRunning) frameSink?.latestFramePng() else null }
    }

    // ------------------------------------------------------------------
    // Targeted input (never takes a display id: only OUR display is reachable)
    // ------------------------------------------------------------------

    private fun notRunning(): Result =
        Result.Failure(Codes.NOT_RUNNING, "no virtual display is running")

    private suspend fun <T> withService(default: T, block: suspend (IVirtualDisplayService, Int) -> T): T {
        val svc = service
        val id = displayId
        // display 0 is the physical screen: no code path may ever target it.
        if (svc == null || id == null || id <= Display.DEFAULT_DISPLAY) return default
        return withContext(Dispatchers.IO) {
            runCatching { block(svc, id) }
                .onFailure {
                    AppLogger.warning(TAG, "service call failed: ${it.message}")
                    if (it is android.os.DeadObjectException) handleServiceDeath("dead object")
                }
                .getOrDefault(default)
        }
    }

    /** Tap at logical coordinates of the virtual display. */
    fun tap(x: Int, y: Int): Result = runBlocking {
        withService(notRunning()) { svc, id ->
            if (svc.injectTap(id, x, y)) {
                Result.Ok(JSONObject().put("x", x).put("y", y))
            } else {
                Result.Failure(
                    Codes.INPUT_FAILED,
                    "tap ($x,$y) was not delivered on display $id (outside its bounds or refused)",
                )
            }
        }
    }

    /** One-finger swipe; durationMs must be 1..10_000. */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300L): Result {
        if (durationMs < 1L || durationMs > VDisplayShellServer.STROKE_MAX_MS) {
            return Result.Failure(
                Codes.INVALID_CONFIG,
                "durationMs must be 1..${VDisplayShellServer.STROKE_MAX_MS}",
                2,
            )
        }
        // Interpolate ~60 fps worth of points (at least 2, at most 256).
        val steps = ((durationMs / 16L) + 1).toInt().coerceIn(2, 256)
        val xs = IntArray(steps) { i -> x1 + (x2 - x1) * i / (steps - 1) }
        val ys = IntArray(steps) { i -> y1 + (y2 - y1) * i / (steps - 1) }
        val times = LongArray(steps) { i -> durationMs * i / (steps - 1) }
        return runBlocking {
            withService(notRunning()) { svc, id ->
                if (svc.injectStroke(id, xs, ys, times)) {
                    Result.Ok(JSONObject().put("durationMs", durationMs))
                } else {
                    Result.Failure(Codes.INPUT_FAILED, "swipe was not delivered on display $id")
                }
            }
        }
    }

    /**
     * Press a whitelisted key (navigation/editing only). Device-level keys —
     * HOME, APP_SWITCH/recents, volume, power, media — are refused here and
     * again on the privileged side: they act on the whole phone.
     */
    fun key(keyCode: Int): Result {
        if (!VDisplayKeys.isAllowed(keyCode)) {
            return Result.Failure(
                Codes.INPUT_FAILED,
                "key $keyCode is not on the virtual-display whitelist " +
                    "(device-level keys such as HOME/recents/volume/power are refused)",
                2,
            )
        }
        return runBlocking { keyInternal(keyCode) }
    }

    private suspend fun keyInternal(keyCode: Int): Result = withService(notRunning()) { svc, id ->
        if (svc.injectKey(id, keyCode)) {
            Result.Ok(JSONObject().put("keyCode", keyCode))
        } else {
            Result.Failure(Codes.INPUT_FAILED, "key $keyCode was not delivered on display $id")
        }
    }

    /**
     * Type printable ASCII (1..1000 chars) into the display's focused field and
     * optionally press ENTER afterwards — a single ENTER key event, never a
     * newline character (the text path rejects control characters).
     */
    fun text(text: String, pressEnter: Boolean = false): Result {
        if (!VDisplayText.supports(text)) {
            return Result.Failure(
                Codes.INPUT_FAILED,
                "text must be 1..${VDisplayText.MAX_CHARS} printable ASCII characters (' '..'~')",
                2,
            )
        }
        return runBlocking {
            val sent = withService(notRunning()) { svc, id ->
                when (svc.injectAsciiText(id, text)) {
                    VDisplayText.DISPATCHED -> Result.Ok(JSONObject().put("chars", text.length))
                    VDisplayText.UNCONFIRMED -> Result.Failure(
                        Codes.INPUT_FAILED,
                        "text delivery is unconfirmed: a prefix may have landed on display $id; " +
                            "capture a screenshot before retrying",
                    )
                    else -> Result.Failure(Codes.INPUT_FAILED, "text was not sent on display $id")
                }
            }
            if (sent !is Result.Ok || !pressEnter) return@runBlocking sent
            // ENTER is whitelisted; send it through the key path so it is routed
            // exactly like a user key press.
            when (val enter = keyInternal(KeyEvent.KEYCODE_ENTER)) {
                is Result.Ok -> Result.Ok(sent.data.put("enter", true))
                is Result.Failure -> enter
            }
        }
    }

    /**
     * Launch a package onto the virtual display. Refuses
     * ([Codes.IN_USE_ON_MAIN_SCREEN]) when the user is actively using the
     * package on the main screen.
     */
    fun launch(packageName: String): Result {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) {
            return Result.Failure(Codes.PACKAGE_NOT_FOUND, "package name must not be empty", 2)
        }
        return runBlocking {
            withService(notRunning()) { svc, id ->
                when (svc.startApp(id, pkg)) {
                    VLaunch.OK -> Result.Ok(JSONObject().put("package", pkg))
                    VLaunch.IN_USE_ON_MAIN -> Result.Failure(
                        Codes.IN_USE_ON_MAIN_SCREEN,
                        "$pkg is in use on the main screen; refusing to move the user's live task",
                    )
                    VLaunch.NOT_FOUND -> Result.Failure(Codes.PACKAGE_NOT_FOUND, "$pkg has no launcher activity")
                    else -> Result.Failure(Codes.LAUNCH_FAILED, "could not launch $pkg on display $id")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Documented convenience wrappers (kept for API compatibility)
    // ------------------------------------------------------------------

    /** [key] as a boolean, for callers written against the v1 API. */
    fun pressKey(keyCode: Int): Boolean = key(keyCode) is Result.Ok

    /**
     * [text] as a tri-state result, for callers written against the v1 API.
     * Preserves Unconfirmed: a partially delivered payload must not be
     * reported as "not sent", or the caller may replay it and double-type.
     */
    fun inputText(text: String): VTextResult {
        if (!VDisplayText.supports(text)) return VTextResult.NotSent
        return runBlocking {
            val r = withService(notRunning()) { svc, id ->
                when (svc.injectAsciiText(id, text)) {
                    VDisplayText.DISPATCHED -> Result.Ok(JSONObject().put("chars", text.length))
                    VDisplayText.UNCONFIRMED -> Result.Failure(Codes.INPUT_FAILED, UNCONFIRMED_MARKER)
                    else -> Result.Failure(Codes.INPUT_FAILED, "text was not sent")
                }
            }
            when {
                r is Result.Ok -> VTextResult.Dispatched
                r is Result.Failure && r.message == UNCONFIRMED_MARKER -> VTextResult.Unconfirmed
                else -> VTextResult.NotSent
            }
        }
    }

    /** Top activity class name on the display (diagnostics); null when none. */
    fun topActivity(): String? = runBlocking {
        withService<String?>(null) { svc, id -> svc.topActivity(id) }
    }
}
