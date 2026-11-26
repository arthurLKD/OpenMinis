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
package com.openminis.app.ui.vdisplay

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.vdisplay.FrameBitmap
import com.openminis.app.vdisplay.VirtualDisplayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * [T-vdisplay-floating] Process-wide owner of the global background-screen
 * floating window (`TYPE_APPLICATION_OVERLAY`, plus an in-app fallback inside
 * `AppNavigation` when `SYSTEM_ALERT_WINDOW` has not yet been granted).
 *
 * Closing the overlay (`onClose` / ✕) ONLY suppresses the overlay for the
 * current display id and tears down the preview window + frame poll — it NEVER
 * calls [VirtualDisplayManager.stop] or alters the virtual display.
 */
object VDisplayFloatingOverlayController {

    private const val TAG = "VDisplayFloatingOverlay"
    private const val FRAME_POLL_EXPANDED_MS = 250L
    private const val FRAME_POLL_COLLAPSED_MS = 500L
    private const val SAFE_MARGIN_DP = 10f
    private const val TOOL_OVERLAY_EXCLUSION_DP = 120f

    private var app: MinisApp? = null
    private var repo: BackgroundSettingsRepository? = null
    private var scope: CoroutineScope? = null

    private val windowManager: WindowManager?
        get() = app?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    /** Global overlay UI state (used inside `WindowManager` ComposeView). */
    internal var uiState by mutableStateOf<VDisplayFloatingUiState?>(null)
        private set

    /** In-app fallback UI state (used by `AppNavigation` only when `!hasOverlayPermission`). */
    internal var inAppFallbackState by mutableStateOf<VDisplayFloatingUiState?>(null)
        private set

    var directTouch by mutableStateOf(false)
        private set

    private val closedForDisplayIdFlow = MutableStateFlow<Int?>(null)
    private val onPreviewScreenFlow = MutableStateFlow(false)
    private val permissionGrantedFlow = MutableStateFlow(false)

    private var expanded by mutableStateOf(false)
    private var scale by mutableStateOf(1f)

    private var hostView: VDisplayOverlayTouchHost? = null
    private var owners: VDisplayOverlayOwners? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var attached = false

    private var originX = 0
    private var originY = 0
    private var lastWindowWidthPx = 1
    private var lastWindowHeightPx = 1

    private var frameJob: Job? = null
    private var flingJob: Job? = null

    // Never call Bitmap.recycle() manually on Bitmaps handed to Compose
    // ImageBitmap: RenderThread/recomposition can still reference the old frame
    // asynchronously. Dropping the reference lets GC reclaim it safely.
    private var currentFrame: FrameBitmap? = null
    private var lastCapturedAtMs = 0L

    private val actions = object : VDisplayFloatingActions {
        override fun onExpand() {
            expanded = true
            if (attached) {
                recomputeSizeKeepingCentre()
                reclampAndPersist()
            }
            updateTouchHostMode()
            publishUiStates()
        }

        override fun onCollapse() {
            expanded = false
            if (attached) {
                recomputeSizeKeepingCentre()
                reclampAndPersist()
            }
            updateTouchHostMode()
            publishUiStates()
        }

        override fun onClose() {
            // Deliberately does NOT stop the virtual display: hide overlay only.
            closedForDisplayIdFlow.value = runningDisplayId()
        }

        override fun onPinch(factor: Float) {
            applyScale(scale * factor)
        }

        override fun onZoomIn() {
            applyScale(scale * VDisplayFloatingMath.ZOOM_STEP)
        }

        override fun onZoomOut() {
            applyScale(scale / VDisplayFloatingMath.ZOOM_STEP)
        }

        override fun onZoomReset() {
            applyScale(1f)
        }

        override fun onSetDirectTouch(direct: Boolean) {
            directTouch = direct
            repo?.setVDisplayFloatingDirectTouch(direct)
            updateTouchHostMode()
        }

        override fun onBackKey() {
            launchIo { VirtualDisplayManager.key(KeyEvent.KEYCODE_BACK) }
        }

        override fun onTap(x: Int, y: Int) {
            launchIo { VirtualDisplayManager.tap(x, y) }
        }

        override fun onSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
            launchIo { VirtualDisplayManager.swipe(x1, y1, x2, y2, durationMs) }
        }
    }

    /**
     * Called from `MinisApp.onCreate` right after `VirtualDisplayManager.init`
     * (never under safe-mode). Idempotent.
     */
    fun init(app: MinisApp) {
        if (this.app != null) return
        this.app = app
        val repo = app.backgroundSettingsRepository
        this.repo = repo
        scale = VDisplayFloatingMath.clampScale(repo.getVDisplayFloatingScale())
        directTouch = repo.vdisplayFloatingDirectTouch.value
        permissionGrantedFlow.value = hasOverlayPermission()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        this.scope = scope

        // Refresh overlay permission whenever the user returns to Minis from
        // System Settings, and while a display is running in case it is granted.
        scope.launch {
            app.isAppForegroundFlow.collect {
                permissionGrantedFlow.value = hasOverlayPermission()
            }
        }

        scope.launch {
            combine(
                VirtualDisplayManager.state,
                repo.vdisplayFloatingEnabled,
                repo.vdisplayFloatingDirectTouch,
                onPreviewScreenFlow,
                closedForDisplayIdFlow,
            ) { displayState, enabled, direct, onPreview, closedId ->
                CombinedState(displayState, enabled, direct, onPreview, closedId)
            }.combine(permissionGrantedFlow) { combined, perm ->
                combined to perm
            }.collect { (combined, perm) ->
                directTouch = combined.directTouch
                updateTouchHostMode()
                val running = combined.displayState as? VirtualDisplayManager.VDisplayState.Running
                val isRunning = running != null
                val displayId = running?.displayId

                // Clear any stale closed marker once the display stops or a
                // different display id starts.
                if (!isRunning || (combined.closedId != null && combined.closedId != displayId)) {
                    if (closedForDisplayIdFlow.value != null) {
                        closedForDisplayIdFlow.value = null
                    }
                }

                val showGlobal = VDisplayFloatingMath.shouldShowFloatingWindow(
                    isRunning = isRunning,
                    displayId = displayId,
                    enabled = combined.enabled,
                    hasOverlayPermission = perm,
                    onPreviewScreen = combined.onPreview,
                    closedForDisplayId = combined.closedId,
                )
                val showInApp = !perm && VDisplayFloatingMath.shouldShowFloatingWindow(
                    isRunning = isRunning,
                    displayId = displayId,
                    enabled = combined.enabled,
                    hasOverlayPermission = true,
                    onPreviewScreen = combined.onPreview,
                    closedForDisplayId = combined.closedId,
                )

                if (showGlobal) {
                    inAppFallbackState = null
                    showGlobalOverlay()
                } else {
                    hideGlobalOverlay(keepFrames = showInApp)
                    if (showInApp) {
                        publishUiStates(forceInApp = true)
                        ensureFrameLoop()
                    } else {
                        stopFrameLoop()
                        clearFrameReference()
                        inAppFallbackState = null
                    }
                }
            }
        }
    }

    private data class CombinedState(
        val displayState: VirtualDisplayManager.VDisplayState,
        val enabled: Boolean,
        val directTouch: Boolean,
        val onPreview: Boolean,
        val closedId: Int?,
    )

    fun setOnPreviewScreen(onScreen: Boolean) {
        onPreviewScreenFlow.value = onScreen
    }

    fun refreshPermission() {
        permissionGrantedFlow.value = hasOverlayPermission()
    }

    fun requestShow() {
        closedForDisplayIdFlow.value = null
        refreshPermission()
    }

    fun isClosedForCurrentDisplay(): Boolean {
        val id = runningDisplayId() ?: return false
        return closedForDisplayIdFlow.value != null && closedForDisplayIdFlow.value == id
    }

    fun hasOverlayPermission(): Boolean {
        val ctx = app ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)
    }

    private fun runningDisplayId(): Int? =
        (VirtualDisplayManager.state.value as? VirtualDisplayManager.VDisplayState.Running)?.displayId

    private fun runningTrusted(): Boolean =
        (VirtualDisplayManager.state.value as? VirtualDisplayManager.VDisplayState.Running)?.trusted ?: false

    // ── Global WindowManager overlay ───────────────────────────────────────

    private fun showGlobalOverlay() {
        val app = this.app ?: return
        if (attached) {
            publishUiStates()
            return
        }
        val wm = windowManager ?: return
        if (runningDisplayId() == null) return

        val owners = VDisplayOverlayOwners()
        val host = VDisplayOverlayTouchHost(app)
        val compose = ComposeView(app)
        host.addView(
            compose,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        host.onDragStart = { flingJob?.cancel() }
        host.onDragBy = { dx, dy -> moveOriginBy(dx, dy) }
        host.onDragFinished = { vx, vy -> snapOrClamp(vx, vy) }

        val params = buildLayoutParams()
        val landscape = isLandscape()
        val bounds = safeBounds()
        computeWindowSize(expanded).let { (w, h) ->
            lastWindowWidthPx = w
            lastWindowHeightPx = h
        }
        val stored = repo?.getVDisplayFloatingPosition(landscape)
        val start = stored?.let { (x, y) ->
            VDisplayFloatingMath.clampOrigin(x, y, lastWindowWidthPx, lastWindowHeightPx, bounds)
        } ?: VDisplayFloatingMath.cornerOrigin(
            VDisplayFloatingMath.Corner.TopEnd,
            lastWindowWidthPx,
            lastWindowHeightPx,
            bounds,
        )
        originX = start.x
        originY = start.y
        params.x = originX
        params.y = originY

        // ComposeView inspects ViewTreeLifecycleOwner synchronously inside
        // onAttachedToWindow() during wm.addView, so owners MUST be installed
        // BEFORE addView; if addView fails, destroy() cleans up.
        owners.install(host)
        compose.setContent {
            VDisplayOverlayTheme(app) {
                val state = uiState
                if (state != null) {
                    val (contentW, contentH) = contentSizeDp(state.expanded)
                    VDisplayFloatingCard(
                        state = state,
                        directTouch = directTouch,
                        contentWidthDp = contentW,
                        contentHeightDp = contentH,
                        actions = actions,
                    )
                }
            }
        }

        this.hostView = host
        this.owners = owners
        this.layoutParams = params
        updateTouchHostMode()

        try {
            wm.addView(host, params)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "addView failed: ${t.javaClass.simpleName}: ${t.message}")
            owners.destroy()
            this.hostView = null
            this.owners = null
            this.layoutParams = null
            return
        }

        attached = true
        lastCapturedAtMs = 0L
        AppLogger.info(TAG, "global overlay attached at ($originX,$originY) displayId=${runningDisplayId()}")
        publishUiStates()
        ensureFrameLoop()
    }

    private fun hideGlobalOverlay(keepFrames: Boolean = false) {
        if (!attached) {
            uiState = null
            if (!keepFrames) {
                stopFrameLoop()
                clearFrameReference()
            }
            return
        }
        attached = false
        flingJob?.cancel()
        flingJob = null
        if (!keepFrames) {
            stopFrameLoop()
        }
        val wm = windowManager
        val view = hostView
        if (wm != null && view != null) {
            runCatching { wm.removeView(view) }
                .onFailure { AppLogger.warning(TAG, "removeView failed: ${it.javaClass.simpleName}") }
        }
        owners?.destroy()
        owners = null
        hostView = null
        layoutParams = null
        uiState = null
        if (!keepFrames) {
            clearFrameReference()
        }
    }

    private fun updateTouchHostMode() {
        val host = hostView ?: return
        host.dragAnywhere = !expanded || !directTouch
        host.dragHandleHeightPx = if (expanded && directTouch) {
            dp(VDisplayExpandedChromeDp.value).roundToInt()
        } else {
            0
        }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }

    // ── Geometry & bounds ──────────────────────────────────────────────────

    private fun isLandscape(): Boolean {
        val ctx = app ?: return false
        return ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun dp(v: Float): Float {
        val ctx = app ?: return v
        return v * ctx.resources.displayMetrics.density
    }

    private fun safeBounds(): VDisplayFloatingMath.SafeBounds {
        val ctx = app
        val dm = ctx?.resources?.displayMetrics
        val w = dm?.widthPixels ?: 1080
        val h = dm?.heightPixels ?: 1920
        val margin = dp(SAFE_MARGIN_DP).roundToInt()
        val status = systemBarDimen(ctx, "status_bar_height")
        val nav = systemBarDimen(ctx, "navigation_bar_height")
        val toolOverlayExclusion = if (isLandscape()) 0 else dp(TOOL_OVERLAY_EXCLUSION_DP).roundToInt()
        return VDisplayFloatingMath.SafeBounds(
            screenWidthPx = w,
            screenHeightPx = h,
            safeLeftPx = 0,
            safeTopPx = status,
            safeRightPx = 0,
            safeBottomPx = nav + toolOverlayExclusion,
            marginPx = margin,
        )
    }

    private fun systemBarDimen(ctx: Context?, name: String): Int {
        ctx ?: return 0
        val id = ctx.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
    }

    internal fun computeWindowSize(isExpanded: Boolean, screenMaxW: Float? = null, screenMaxH: Float? = null): Pair<Int, Int> {
        val landscape = isLandscape()
        val baseDp = when {
            isExpanded && landscape -> VDisplayFloatingMath.EXPANDED_LANDSCAPE_WIDTH_DP * scale
            isExpanded -> VDisplayFloatingMath.EXPANDED_WIDTH_DP * scale
            landscape -> VDisplayFloatingMath.COLLAPSED_LANDSCAPE_WIDTH_DP
            else -> VDisplayFloatingMath.COLLAPSED_WIDTH_DP
        }
        val frame = currentFrame
        val aspect = if (frame != null && frame.width > 0) {
            frame.height.toFloat() / frame.width.toFloat()
        } else {
            VDisplayFloatingMath.DEFAULT_ASPECT
        }
        val bounds = safeBounds()
        val maxW = screenMaxW ?: (bounds.maxX - bounds.minX).toFloat()
        val maxH = screenMaxH ?: (bounds.maxY - bounds.minY).toFloat()
        val chrome = if (isExpanded) {
            dp(VDisplayExpandedChromeDp.value + VDisplayZoomBarDp.value)
        } else {
            0f
        }
        val size = VDisplayFloatingMath.fitWindowSize(
            baseWidthPx = dp(baseDp),
            aspectHeightOverWidth = aspect,
            maxWidthPx = maxW,
            maxHeightPx = maxH,
            chromeHeightPx = chrome,
            horizontalInsetPx = 0f,
        )
        return size.widthPx to size.heightPx
    }

    internal fun contentSizeDp(isExpanded: Boolean, screenMaxW: Float? = null, screenMaxH: Float? = null): Pair<Float, Float> {
        val (wPx, hPx) = computeWindowSize(isExpanded, screenMaxW, screenMaxH)
        val density = app?.resources?.displayMetrics?.density ?: 1f
        val chromePx = if (isExpanded) {
            dp(VDisplayExpandedChromeDp.value + VDisplayZoomBarDp.value)
        } else {
            0f
        }
        return (wPx / density) to ((hPx - chromePx).coerceAtLeast(1f) / density)
    }

    private fun moveOriginBy(dx: Float, dy: Float) {
        val bounds = safeBounds()
        val clamped = VDisplayFloatingMath.clampOrigin(
            originX + dx.roundToInt(),
            originY + dy.roundToInt(),
            lastWindowWidthPx,
            lastWindowHeightPx,
            bounds,
        )
        originX = clamped.x
        originY = clamped.y
        applyOriginToWindow()
    }

    private fun recomputeSizeKeepingCentre() {
        val oldW = lastWindowWidthPx
        val oldH = lastWindowHeightPx
        val (newW, newH) = computeWindowSize(expanded)
        originX += (oldW - newW) / 2
        originY += (oldH - newH) / 2
        lastWindowWidthPx = newW
        lastWindowHeightPx = newH
        applyOriginToWindow()
    }

    private fun snapOrClamp(vx: Float, vy: Float) {
        val bounds = safeBounds()
        val (w, h) = computeWindowSize(expanded)
        lastWindowWidthPx = w
        lastWindowHeightPx = h
        if (expanded) {
            // Expanded windows stay where the user parked them; only clamp
            // inside the safe area (never fling them away into a corner).
            val clamped = VDisplayFloatingMath.clampOrigin(originX, originY, w, h, bounds)
            originX = clamped.x
            originY = clamped.y
            applyOriginToWindow()
            persistPosition()
        } else {
            val corner = VDisplayFloatingMath.snapCorner(originX, originY, vx, vy, w, h, bounds)
            val target = VDisplayFloatingMath.cornerOrigin(corner, w, h, bounds)
            val scope = scope ?: return
            flingJob?.cancel()
            flingJob = animateFling(
                scope = scope,
                start = originX to originY,
                target = target.x to target.y,
                onUpdate = { x, y ->
                    originX = x
                    originY = y
                    applyOriginToWindow()
                },
                onEnd = { persistPosition() },
            )
        }
    }

    private fun applyScale(newScale: Float) {
        val clamped = VDisplayFloatingMath.clampScale(newScale)
        if (clamped == scale) return
        scale = clamped
        repo?.setVDisplayFloatingScale(clamped)
        if (attached) {
            reclampAndPersist()
        }
        publishUiStates()
    }

    private fun reclampAndPersist() {
        val bounds = safeBounds()
        val (w, h) = computeWindowSize(expanded)
        lastWindowWidthPx = w
        lastWindowHeightPx = h
        val clamped = VDisplayFloatingMath.clampOrigin(originX, originY, w, h, bounds)
        originX = clamped.x
        originY = clamped.y
        applyOriginToWindow()
        persistPosition()
    }

    private fun applyOriginToWindow() {
        if (!attached) return
        val wm = windowManager ?: return
        val params = layoutParams ?: return
        val view = hostView ?: return
        if (params.x == originX && params.y == originY) return
        params.x = originX
        params.y = originY
        runCatching { wm.updateViewLayout(view, params) }
            .onFailure { AppLogger.warning(TAG, "updateViewLayout failed: ${it.javaClass.simpleName}") }
    }

    private fun persistPosition() {
        repo?.setVDisplayFloatingPosition(originX, originY, isLandscape())
    }

    // ── Frame polling ──────────────────────────────────────────────────────

    private fun ensureFrameLoop() {
        if (frameJob?.isActive == true) return
        val scope = scope ?: return
        frameJob = scope.launch {
            while (isActive) {
                val delayMs = if (expanded) FRAME_POLL_EXPANDED_MS else FRAME_POLL_COLLAPSED_MS
                val newFrame = withContext(Dispatchers.IO) {
                    runCatching { VirtualDisplayManager.latestFrameIfNewer(lastCapturedAtMs) }
                        .getOrNull()
                }
                if (newFrame != null) {
                    val prev = currentFrame
                    val aspectChanged = prev == null ||
                        prev.width != newFrame.width ||
                        prev.height != newFrame.height
                    currentFrame = newFrame
                    lastCapturedAtMs = newFrame.capturedAtMs
                    if (attached && aspectChanged) {
                        reclampAndPersist()
                    }
                    publishUiStates()
                }
                delay(delayMs)
            }
        }
    }

    private fun stopFrameLoop() {
        frameJob?.cancel()
        frameJob = null
    }

    private fun publishUiStates(forceInApp: Boolean = false) {
        val displayId = runningDisplayId() ?: return
        val snapshot = VDisplayFloatingUiState(
            displayId = displayId,
            trusted = runningTrusted(),
            expanded = expanded,
            scale = scale,
            frame = currentFrame,
            waitingForFrame = currentFrame == null,
        )
        if (attached) {
            uiState = snapshot
            inAppFallbackState = null
        } else if (forceInApp || inAppFallbackState != null) {
            inAppFallbackState = snapshot
        }
    }

    private fun clearFrameReference() {
        currentFrame = null
        lastCapturedAtMs = 0L
    }

    private fun launchIo(block: () -> Unit) {
        scope?.launch(Dispatchers.IO) {
            runCatching(block)
                .onFailure { AppLogger.warning(TAG, "vdisplay command failed: ${it.javaClass.simpleName}") }
        }
    }

    internal fun currentActions(): VDisplayFloatingActions = actions
}

/**
 * In-app fallback rendered at the top level of `AppNavigation` when
 * `SYSTEM_ALERT_WINDOW` has not yet been granted, so the user still gets a
 * live floating preview on Minis' main interface and can grant cross-app
 * permission in one tap. Automatically disappears as soon as the global
 * `TYPE_APPLICATION_OVERLAY` window attaches.
 */
@Composable
internal fun VDisplayInAppFloatingOverlay() {
    val state = VDisplayFloatingOverlayController.inAppFallbackState ?: return
    val directTouch = VDisplayFloatingOverlayController.directTouch
    val actions = remember { VDisplayFloatingOverlayController.currentActions() }
    val context = LocalContext.current
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val safeInsets = WindowInsets.safeDrawing
        val safeLeft = safeInsets.getLeft(density, androidx.compose.ui.unit.LayoutDirection.Ltr)
        val safeTop = safeInsets.getTop(density)
        val safeRight = safeInsets.getRight(density, androidx.compose.ui.unit.LayoutDirection.Ltr)
        val safeBottom = safeInsets.getBottom(density)
        val marginPx = with(density) { 10.dp.roundToPx() }
        val areaW = with(density) { maxWidth.roundToPx() }
        val areaH = with(density) { maxHeight.roundToPx() }
        val bounds = VDisplayFloatingMath.SafeBounds(
            screenWidthPx = areaW,
            screenHeightPx = areaH,
            safeLeftPx = safeLeft,
            safeTopPx = safeTop,
            safeRightPx = safeRight,
            safeBottomPx = safeBottom + with(density) { 76.dp.roundToPx() },
            marginPx = marginPx,
        )

        val (contentW, contentH) = VDisplayFloatingOverlayController.contentSizeDp(
            isExpanded = state.expanded,
            screenMaxW = (bounds.maxX - bounds.minX).toFloat(),
            screenMaxH = (bounds.maxY - bounds.minY).toFloat(),
        )
        val winWPx = with(density) { contentW.dp.roundToPx() }
        val winHPx = with(density) {
            (contentH + if (state.expanded) VDisplayExpandedChromeDp.value + VDisplayZoomBarDp.value else 0f).dp.roundToPx()
        }

        val defaultPos = VDisplayFloatingMath.cornerOrigin(
            VDisplayFloatingMath.Corner.TopEnd,
            winWPx,
            winHPx,
            bounds,
        )
        var posX by remember(state.displayId) { mutableIntStateOf(defaultPos.x) }
        var posY by remember(state.displayId) { mutableIntStateOf(defaultPos.y) }
        val clamped = VDisplayFloatingMath.clampOrigin(posX, posY, winWPx, winHPx, bounds)
        posX = clamped.x
        posY = clamped.y

        val onDragBy: (Float, Float) -> Unit = { dx, dy ->
            val next = VDisplayFloatingMath.clampOrigin(
                posX + dx.roundToInt(),
                posY + dy.roundToInt(),
                winWPx,
                winHPx,
                bounds,
            )
            posX = next.x
            posY = next.y
        }
        val onDragFinished: (Float, Float) -> Unit = { vx, vy ->
            if (!state.expanded) {
                val c = VDisplayFloatingMath.snapCorner(posX, posY, vx, vy, winWPx, winHPx, bounds)
                val target = VDisplayFloatingMath.cornerOrigin(c, winWPx, winHPx, bounds)
                posX = target.x
                posY = target.y
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.offset { IntOffset(posX, posY) },
        ) {
            VDisplayFloatingCard(
                state = state,
                directTouch = directTouch,
                contentWidthDp = contentW,
                contentHeightDp = contentH,
                actions = actions,
                collapsedModifier = with(VDisplayOverlayDragFrame) {
                    Modifier.inAppWindowDragOrTap(
                        key = state.displayId,
                        currentOrigin = { Offset(posX.toFloat(), posY.toFloat()) },
                        onDragBy = onDragBy,
                        onDragFinished = onDragFinished,
                        onTap = actions::onExpand,
                    )
                },
                expandedHandleModifier = with(VDisplayOverlayDragFrame) {
                    Modifier.inAppWindowDrag(
                        key = state.displayId,
                        currentOrigin = { Offset(posX.toFloat(), posY.toFloat()) },
                        onDragBy = onDragBy,
                        onDragFinished = onDragFinished,
                    )
                },
                expandedWatchModifier = with(VDisplayOverlayDragFrame) {
                    Modifier.inAppWindowDrag(
                        key = state.displayId,
                        currentOrigin = { Offset(posX.toFloat(), posY.toFloat()) },
                        onDragBy = onDragBy,
                        onDragFinished = onDragFinished,
                    )
                },
            )
            if (state.expanded) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .background(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f),
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable {
                            openOverlayPermissionSettings(context)
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.vdisplay_floating_grant_global),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

internal fun openOverlayPermissionSettings(context: Context) {
    val specific = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:" + context.packageName),
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    try {
        context.startActivity(specific)
    } catch (_: Throwable) {
        val generic = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(generic) }
    }
}
