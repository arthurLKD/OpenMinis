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
 * Adapted from Mika PipSizing / PipPhysics under the Apache-2.0 licence.
 */
package com.openminis.app.ui.vdisplay

/**
 * Pure-Kotlin geometry and visibility logic for the vdisplay floating overlay
 * window ("后台屏浮窗").
 *
 * **No `android.*` imports are allowed here.** All coordinate calculations,
 * scale clamping, corner-snap physics and show/hide decisions are expressed in
 * terms of plain Kotlin types (Int, Float, data class) so this file can be
 * unit-tested on the JVM without Robolectric.
 *
 * Pixel units throughout. The caller is responsible for converting dp values
 * to pixels before calling any function. The constants below that are expressed
 * in dp (e.g. [COLLAPSED_WIDTH_DP]) are provided as specification anchors;
 * production code should convert them with the display's logical density.
 *
 * Adapted from Mika's PipSizing / PipPhysics sizing helpers.
 */
object VDisplayFloatingMath {

    // ── Scale ─────────────────────────────────────────────────────────────

    /** Minimum allowed scale factor for the floating overlay. */
    const val MIN_SCALE = 0.65f

    /** Maximum allowed scale factor for the floating overlay. */
    const val MAX_SCALE = 2.40f

    /**
     * Multiplicative step applied on each pinch-zoom or button-zoom gesture.
     * Growing: `currentScale * ZOOM_STEP`; shrinking: `currentScale / ZOOM_STEP`.
     */
    const val ZOOM_STEP = 1.25f

    /**
     * Default aspect ratio of the vdisplay content area expressed as
     * height / width. 2.2 ≈ a tall-phone virtual display (e.g. 1080 × 2376 px).
     */
    const val DEFAULT_ASPECT = 2.2f

    // ── Window width presets in dp ─────────────────────────────────────────

    /** Collapsed overlay width in portrait (dp). */
    const val COLLAPSED_WIDTH_DP = 104f

    /** Collapsed overlay width in landscape (dp). */
    const val COLLAPSED_LANDSCAPE_WIDTH_DP = 176f

    /** Expanded overlay width in portrait (dp). */
    const val EXPANDED_WIDTH_DP = 240f

    /** Expanded overlay width in landscape (dp). */
    const val EXPANDED_LANDSCAPE_WIDTH_DP = 320f

    // ── Types ──────────────────────────────────────────────────────────────

    /** The four corners to which the overlay can snap. */
    enum class Corner { TopStart, TopEnd, BottomStart, BottomEnd }

    /**
     * The usable screen area available to the overlay after accounting for
     * system bars, insets and a mandatory margin so the overlay never touches
     * the edge of the screen.
     *
     * @param screenWidthPx  Total screen width in pixels.
     * @param screenHeightPx Total screen height in pixels.
     * @param safeLeftPx     Left inset (system bar / display cutout) in pixels.
     * @param safeTopPx      Top inset in pixels.
     * @param safeRightPx    Right inset in pixels.
     * @param safeBottomPx   Bottom inset in pixels.
     * @param marginPx       Additional mandatory margin inside the safe area.
     */
    data class SafeBounds(
        val screenWidthPx: Int,
        val screenHeightPx: Int,
        val safeLeftPx: Int,
        val safeTopPx: Int,
        val safeRightPx: Int,
        val safeBottomPx: Int,
        val marginPx: Int,
    ) {
        /** Left boundary for the overlay's origin (left edge of window). */
        val minX: Int get() = safeLeftPx + marginPx

        /** Top boundary for the overlay's origin (top edge of window). */
        val minY: Int get() = safeTopPx + marginPx

        /**
         * Right boundary: the overlay's origin (left edge) cannot exceed this
         * value, so the window width is implicitly subtracted by the caller
         * in [clampOrigin].
         */
        val maxX: Int get() = screenWidthPx - safeRightPx - marginPx

        /** Bottom boundary for the overlay's origin (top edge of window). */
        val maxY: Int get() = screenHeightPx - safeBottomPx - marginPx
    }

    /** Pixel dimensions of the floating overlay window. */
    data class WindowSize(val widthPx: Int, val heightPx: Int)

    /** Top-left origin of the floating overlay window in screen pixels. */
    data class Origin(val x: Int, val y: Int)

    // ── Scale helpers ──────────────────────────────────────────────────────

    /**
     * Clamp [value] to [[MIN_SCALE]..[MAX_SCALE]].
     *
     * NaN and ±Infinity are replaced with [MIN_SCALE] so callers never
     * propagate a poisoned scale into geometry calculations.
     */
    fun clampScale(value: Float): Float {
        if (!value.isFinite()) return MIN_SCALE
        return value.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    // ── Window sizing ──────────────────────────────────────────────────────

    /**
     * Compute the [WindowSize] for a floating overlay that shows the vdisplay
     * content area inside a containing chrome (title bar, border, etc.).
     *
     * The content area is sized to [baseWidthPx] × ([baseWidthPx] × [aspectHeightOverWidth])
     * and then constrained so the full window (content + chrome + insets) fits
     * within [maxWidthPx] × [maxHeightPx]. The resulting window is never
     * smaller than 1 × 1.
     *
     * @param baseWidthPx           Desired content width before aspect correction (px).
     * @param aspectHeightOverWidth Height-over-width ratio of the content area.
     * @param maxWidthPx            Maximum total window width (px).
     * @param maxHeightPx           Maximum total window height (px).
     * @param chromeHeightPx        Height consumed by window chrome (title bar etc.) (px).
     * @param horizontalInsetPx     Width consumed by horizontal padding/border on each side (px × 2).
     */
    fun fitWindowSize(
        baseWidthPx: Float,
        aspectHeightOverWidth: Float,
        maxWidthPx: Float,
        maxHeightPx: Float,
        chromeHeightPx: Float,
        horizontalInsetPx: Float,
    ): WindowSize {
        // Guard against degenerate inputs; always return at least 1×1.
        val safeBase = baseWidthPx.takeIf { it.isFinite() && it > 0f } ?: 1f
        val safeAspect = aspectHeightOverWidth.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_ASPECT
        val safeMaxW = maxWidthPx.takeIf { it.isFinite() && it > 0f } ?: safeBase
        val safeMaxH = maxHeightPx.takeIf { it.isFinite() && it > 0f } ?: Float.MAX_VALUE
        val safeChrome = chromeHeightPx.takeIf { it.isFinite() && it >= 0f } ?: 0f
        val safeInset = horizontalInsetPx.takeIf { it.isFinite() && it >= 0f } ?: 0f

        // Content width: requested base, capped so total window fits maxWidth.
        val maxContentW = (safeMaxW - safeInset).coerceAtLeast(1f)
        val contentW = safeBase.coerceIn(1f, maxContentW)

        // Content height derived from aspect ratio.
        val contentH = contentW * safeAspect

        // Total window dimensions.
        var winW = (contentW + safeInset).coerceAtLeast(1f)
        var winH = (contentH + safeChrome).coerceAtLeast(1f)

        // Fit into the available bounding box by scaling down uniformly.
        if (winW > safeMaxW || winH > safeMaxH) {
            val scaleW = if (winW > safeMaxW) safeMaxW / winW else 1f
            val scaleH = if (winH > safeMaxH) safeMaxH / winH else 1f
            val scale = minOf(scaleW, scaleH)
            winW *= scale
            winH *= scale
        }

        return WindowSize(
            widthPx = winW.toInt().coerceAtLeast(1),
            heightPx = winH.toInt().coerceAtLeast(1),
        )
    }

    // ── Position helpers ───────────────────────────────────────────────────

    /**
     * Clamp a proposed window origin ([x], [y]) so the window of size
     * ([winWidthPx] × [winHeightPx]) stays within [bounds].
     *
     * The window is clamped against `bounds.min*` on the near edges and
     * against `bounds.max* - winSize` on the far edges, so the whole
     * window is always inside the safe area.
     */
    fun clampOrigin(
        x: Int,
        y: Int,
        winWidthPx: Int,
        winHeightPx: Int,
        bounds: SafeBounds,
    ): Origin {
        val clampedX = x.coerceIn(bounds.minX, (bounds.maxX - winWidthPx).coerceAtLeast(bounds.minX))
        val clampedY = y.coerceIn(bounds.minY, (bounds.maxY - winHeightPx).coerceAtLeast(bounds.minY))
        return Origin(clampedX, clampedY)
    }

    /**
     * Compute the clamped origin for snapping to a specific [corner].
     *
     * - [Corner.TopStart]    → top-left of the safe area.
     * - [Corner.TopEnd]      → top-right.
     * - [Corner.BottomStart] → bottom-left.
     * - [Corner.BottomEnd]   → bottom-right.
     */
    fun cornerOrigin(
        corner: Corner,
        winWidthPx: Int,
        winHeightPx: Int,
        bounds: SafeBounds,
    ): Origin {
        val x = when (corner) {
            Corner.TopStart, Corner.BottomStart -> bounds.minX
            Corner.TopEnd, Corner.BottomEnd -> (bounds.maxX - winWidthPx).coerceAtLeast(bounds.minX)
        }
        val y = when (corner) {
            Corner.TopStart, Corner.TopEnd -> bounds.minY
            Corner.BottomStart, Corner.BottomEnd -> (bounds.maxY - winHeightPx).coerceAtLeast(bounds.minY)
        }
        return Origin(x, y)
    }

    /**
     * Determine the best [Corner] for the overlay to snap to after a drag.
     *
     * The snap target is chosen by the overlay's current position + a fling
     * velocity bias ([vx], [vy] in px/s). A non-zero velocity pushes the snap
     * corner in the direction of travel (Mika PipPhysics "velocity bias"
     * heuristic).
     *
     * @param x          Current left edge of the window (px).
     * @param y          Current top edge of the window (px).
     * @param vx         Horizontal velocity after the drag (px/s, positive = right).
     * @param vy         Vertical velocity after the drag (px/s, positive = down).
     * @param winWidthPx Window width (px).
     * @param winHeightPx Window height (px).
     * @param bounds     Safe area.
     */
    fun snapCorner(
        x: Int,
        y: Int,
        vx: Float,
        vy: Float,
        winWidthPx: Int,
        winHeightPx: Int,
        bounds: SafeBounds,
    ): Corner {
        // Centre of the window.
        val cx = x + winWidthPx / 2f
        val cy = y + winHeightPx / 2f

        // Centre of the safe area.
        val midX = (bounds.minX + bounds.maxX) / 2f
        val midY = (bounds.minY + bounds.maxY) / 2f

        // Velocity bias: shift the apparent centre in the direction of travel
        // by a fraction of the velocity (capped to avoid unrealistic snapping).
        val vBias = 0.15f   // seconds of travel added as a bias
        val biasedCx = cx + (vx.takeIf { it.isFinite() } ?: 0f) * vBias
        val biasedCy = cy + (vy.takeIf { it.isFinite() } ?: 0f) * vBias

        val end = biasedCx >= midX
        val bottom = biasedCy >= midY

        return when {
            !end && !bottom -> Corner.TopStart
            end && !bottom -> Corner.TopEnd
            !end && bottom -> Corner.BottomStart
            else -> Corner.BottomEnd
        }
    }

    // ── Visibility decision ────────────────────────────────────────────────

    /**
     * Pure-logic gate: should the vdisplay floating overlay window be shown?
     *
     * All inputs are plain scalar/Boolean/Int so this function can be unit-
     * tested without Android or Compose dependencies.
     *
     * @param isRunning           Whether the vdisplay core is currently running.
     * @param displayId           Current display id (null when not running).
     * @param enabled             Whether the user has enabled the floating overlay.
     * @param hasOverlayPermission Whether `SYSTEM_ALERT_WINDOW` has been granted.
     * @param onPreviewScreen     Whether the in-app vdisplay preview screen is
     *                            currently in the foreground; when true the overlay
     *                            is redundant and should hide.
     * @param closedForDisplayId  The display id the user most recently closed the
     *                            overlay for (tapped the ✕ button). The overlay
     *                            is suppressed for the same display but re-shown
     *                            when a new display starts (different id or null).
     */
    fun shouldShowFloatingWindow(
        isRunning: Boolean,
        displayId: Int?,
        enabled: Boolean,
        hasOverlayPermission: Boolean,
        onPreviewScreen: Boolean,
        closedForDisplayId: Int?,
    ): Boolean {
        if (!isRunning) return false
        if (!enabled) return false
        if (!hasOverlayPermission) return false
        if (onPreviewScreen) return false
        // If the user explicitly closed the overlay for this exact display id,
        // respect that choice — but only for the same id. A new display (even
        // with a recycled id == null) starts with the overlay visible again.
        if (displayId != null && displayId == closedForDisplayId) return false
        return true
    }
}
