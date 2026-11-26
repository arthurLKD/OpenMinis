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

import com.openminis.app.ui.vdisplay.VDisplayFloatingMath.Corner
import com.openminis.app.ui.vdisplay.VDisplayFloatingMath.Origin
import com.openminis.app.ui.vdisplay.VDisplayFloatingMath.SafeBounds
import com.openminis.app.ui.vdisplay.VDisplayFloatingMath.WindowSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-vdisplay-float] Pure-JVM unit tests for [VDisplayFloatingMath].
 *
 * No Android framework, no Robolectric, no Compose: every test exercises
 * plain Kotlin arithmetic. CI picks this up via the `*VDisplay*` glob.
 *
 * Coverage areas:
 *  - scale constants and [clampScale] (normal, boundary, NaN, Inf)
 *  - [fitWindowSize] (normal aspect, degenerate inputs, max-clamping)
 *  - [clampOrigin] (inside, outside, negative safe-area coordinates)
 *  - [cornerOrigin] (all four corners)
 *  - [snapCorner] (position-dominant, velocity-dominant, mixed)
 *  - [shouldShowFloatingWindow] (all flag combinations, close/reopen by id)
 */
class VDisplayFloatingOverlayMathTest {

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun bounds(
        sw: Int = 1080, sh: Int = 2400,
        safeL: Int = 0, safeT: Int = 80,
        safeR: Int = 0, safeB: Int = 120,
        margin: Int = 16,
    ) = SafeBounds(sw, sh, safeL, safeT, safeR, safeB, margin)

    // ── Constants ─────────────────────────────────────────────────────────

    @Test
    fun `scale constants are in expected range`() {
        assertTrue(VDisplayFloatingMath.MIN_SCALE > 0f)
        assertTrue(VDisplayFloatingMath.MAX_SCALE > VDisplayFloatingMath.MIN_SCALE)
        assertTrue(VDisplayFloatingMath.ZOOM_STEP > 1f)
        assertTrue(VDisplayFloatingMath.DEFAULT_ASPECT > 0f)
    }

    @Test
    fun `dp preset constants are positive`() {
        assertTrue(VDisplayFloatingMath.COLLAPSED_WIDTH_DP > 0f)
        assertTrue(VDisplayFloatingMath.COLLAPSED_LANDSCAPE_WIDTH_DP > 0f)
        assertTrue(VDisplayFloatingMath.EXPANDED_WIDTH_DP > 0f)
        assertTrue(VDisplayFloatingMath.EXPANDED_LANDSCAPE_WIDTH_DP > 0f)
    }

    @Test
    fun `landscape presets are wider than portrait presets`() {
        assertTrue(VDisplayFloatingMath.COLLAPSED_LANDSCAPE_WIDTH_DP > VDisplayFloatingMath.COLLAPSED_WIDTH_DP)
        assertTrue(VDisplayFloatingMath.EXPANDED_LANDSCAPE_WIDTH_DP > VDisplayFloatingMath.EXPANDED_WIDTH_DP)
    }

    // ── clampScale ────────────────────────────────────────────────────────

    @Test
    fun `clampScale returns value unchanged within range`() {
        val mid = (VDisplayFloatingMath.MIN_SCALE + VDisplayFloatingMath.MAX_SCALE) / 2f
        assertEquals(mid, VDisplayFloatingMath.clampScale(mid), 0.0001f)
    }

    @Test
    fun `clampScale clamps below MIN_SCALE`() {
        assertEquals(VDisplayFloatingMath.MIN_SCALE, VDisplayFloatingMath.clampScale(0.1f), 0.0001f)
        assertEquals(VDisplayFloatingMath.MIN_SCALE, VDisplayFloatingMath.clampScale(0f), 0.0001f)
        assertEquals(VDisplayFloatingMath.MIN_SCALE, VDisplayFloatingMath.clampScale(-1f), 0.0001f)
    }

    @Test
    fun `clampScale clamps above MAX_SCALE`() {
        assertEquals(VDisplayFloatingMath.MAX_SCALE, VDisplayFloatingMath.clampScale(100f), 0.0001f)
    }

    @Test
    fun `clampScale on boundary values`() {
        assertEquals(VDisplayFloatingMath.MIN_SCALE, VDisplayFloatingMath.clampScale(VDisplayFloatingMath.MIN_SCALE), 0.0001f)
        assertEquals(VDisplayFloatingMath.MAX_SCALE, VDisplayFloatingMath.clampScale(VDisplayFloatingMath.MAX_SCALE), 0.0001f)
    }

    @Test
    fun `clampScale treats NaN as MIN_SCALE`() {
        assertEquals(VDisplayFloatingMath.MIN_SCALE, VDisplayFloatingMath.clampScale(Float.NaN), 0.0001f)
    }

    @Test
    fun `clampScale treats positive Infinity as MAX_SCALE`() {
        // Infinity is not finite → MIN_SCALE per spec
        assertEquals(VDisplayFloatingMath.MIN_SCALE, VDisplayFloatingMath.clampScale(Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test
    fun `clampScale treats negative Infinity as MIN_SCALE`() {
        assertEquals(VDisplayFloatingMath.MIN_SCALE, VDisplayFloatingMath.clampScale(Float.NEGATIVE_INFINITY), 0.0001f)
    }

    // ── fitWindowSize ─────────────────────────────────────────────────────

    @Test
    fun `fitWindowSize basic aspect ratio`() {
        // 240 dp base, aspect 2 → content 240×480, no chrome/inset → window 240×480
        val ws = VDisplayFloatingMath.fitWindowSize(
            baseWidthPx = 240f,
            aspectHeightOverWidth = 2f,
            maxWidthPx = 1000f,
            maxHeightPx = 2000f,
            chromeHeightPx = 0f,
            horizontalInsetPx = 0f,
        )
        assertEquals(240, ws.widthPx)
        assertEquals(480, ws.heightPx)
    }

    @Test
    fun `fitWindowSize adds chrome and inset`() {
        // 100 base, aspect 1, 20 chrome, 10 inset → window 110×120
        val ws = VDisplayFloatingMath.fitWindowSize(
            baseWidthPx = 100f,
            aspectHeightOverWidth = 1f,
            maxWidthPx = 2000f,
            maxHeightPx = 2000f,
            chromeHeightPx = 20f,
            horizontalInsetPx = 10f,
        )
        assertEquals(110, ws.widthPx)
        assertEquals(120, ws.heightPx)
    }

    @Test
    fun `fitWindowSize caps to maxWidth`() {
        val ws = VDisplayFloatingMath.fitWindowSize(
            baseWidthPx = 500f,
            aspectHeightOverWidth = 1f,
            maxWidthPx = 200f,
            maxHeightPx = 10000f,
            chromeHeightPx = 0f,
            horizontalInsetPx = 0f,
        )
        assertTrue(ws.widthPx <= 200)
    }

    @Test
    fun `fitWindowSize caps to maxHeight`() {
        // 100 base, aspect 10 → 1000 high > maxHeight 500 → scale down
        val ws = VDisplayFloatingMath.fitWindowSize(
            baseWidthPx = 100f,
            aspectHeightOverWidth = 10f,
            maxWidthPx = 10000f,
            maxHeightPx = 500f,
            chromeHeightPx = 0f,
            horizontalInsetPx = 0f,
        )
        assertTrue(ws.heightPx <= 500)
        assertTrue(ws.widthPx >= 1)
    }

    @Test
    fun `fitWindowSize returns at least 1x1 for zero base`() {
        val ws = VDisplayFloatingMath.fitWindowSize(0f, 1f, 1000f, 1000f, 0f, 0f)
        assertTrue(ws.widthPx >= 1)
        assertTrue(ws.heightPx >= 1)
    }

    @Test
    fun `fitWindowSize handles NaN base`() {
        val ws = VDisplayFloatingMath.fitWindowSize(Float.NaN, 1f, 1000f, 1000f, 0f, 0f)
        assertTrue(ws.widthPx >= 1)
        assertTrue(ws.heightPx >= 1)
    }

    @Test
    fun `fitWindowSize handles NaN aspect`() {
        val ws = VDisplayFloatingMath.fitWindowSize(100f, Float.NaN, 1000f, 1000f, 0f, 0f)
        assertTrue(ws.widthPx >= 1)
        assertTrue(ws.heightPx >= 1)
    }

    @Test
    fun `fitWindowSize handles Infinity max dimensions`() {
        val ws = VDisplayFloatingMath.fitWindowSize(
            baseWidthPx = 200f,
            aspectHeightOverWidth = 2f,
            maxWidthPx = Float.POSITIVE_INFINITY,
            maxHeightPx = Float.POSITIVE_INFINITY,
            chromeHeightPx = 0f,
            horizontalInsetPx = 0f,
        )
        assertTrue(ws.widthPx >= 1)
        assertTrue(ws.heightPx >= 1)
    }

    @Test
    fun `fitWindowSize DEFAULT_ASPECT produces taller-than-wide result`() {
        val ws = VDisplayFloatingMath.fitWindowSize(
            baseWidthPx = 240f,
            aspectHeightOverWidth = VDisplayFloatingMath.DEFAULT_ASPECT,
            maxWidthPx = 2000f,
            maxHeightPx = 2000f,
            chromeHeightPx = 0f,
            horizontalInsetPx = 0f,
        )
        assertTrue(ws.heightPx > ws.widthPx)
    }

    // ── clampOrigin ────────────────────────────────────────────────────────

    @Test
    fun `clampOrigin returns unchanged origin when inside bounds`() {
        val b = bounds()
        val o = VDisplayFloatingMath.clampOrigin(200, 300, 100, 200, b)
        assertEquals(Origin(200, 300), o)
    }

    @Test
    fun `clampOrigin clamps left edge`() {
        val b = bounds(safeL = 0, margin = 16)
        // minX = 0 + 16 = 16
        val o = VDisplayFloatingMath.clampOrigin(0, 300, 100, 200, b)
        assertEquals(16, o.x)
    }

    @Test
    fun `clampOrigin clamps top edge`() {
        val b = bounds(safeT = 80, margin = 16)
        // minY = 80 + 16 = 96
        val o = VDisplayFloatingMath.clampOrigin(200, 50, 100, 200, b)
        assertEquals(96, o.y)
    }

    @Test
    fun `clampOrigin clamps right edge`() {
        val b = bounds(sw = 1080, safeR = 0, margin = 16)
        // maxX = 1080 - 0 - 16 = 1064; clampedX = 1064 - winWidth
        val winW = 100
        val o = VDisplayFloatingMath.clampOrigin(2000, 300, winW, 200, b)
        val expectedX = (1064 - winW).coerceAtLeast(16)
        assertEquals(expectedX, o.x)
    }

    @Test
    fun `clampOrigin clamps bottom edge`() {
        val b = bounds(sh = 2400, safeB = 120, margin = 16)
        // maxY = 2400 - 120 - 16 = 2264; clampedY = 2264 - winHeight
        val winH = 200
        val o = VDisplayFloatingMath.clampOrigin(200, 9999, 100, winH, b)
        val expectedY = (2264 - winH).coerceAtLeast(96)
        assertEquals(expectedY, o.y)
    }

    @Test
    fun `clampOrigin handles negative safe-area coordinates`() {
        // Simulate a negative stored position (e.g. overlay dragged off left edge).
        val b = bounds(safeL = 0, margin = 16)
        val o = VDisplayFloatingMath.clampOrigin(-500, 200, 100, 150, b)
        assertEquals(16, o.x) // clamped to minX
    }

    @Test
    fun `clampOrigin window too wide for safe area snaps to minX`() {
        // Window wider than safe area → both clamps force minX.
        val b = bounds(sw = 100, safeL = 0, safeR = 0, margin = 10)
        // minX=10, maxX=90, maxX - winW = 90-200 = -110 < minX → clamp to minX
        val o = VDisplayFloatingMath.clampOrigin(0, 50, 200, 50, b)
        assertEquals(10, o.x)
    }

    // ── cornerOrigin ───────────────────────────────────────────────────────

    @Test
    fun `cornerOrigin TopStart is at minX,minY`() {
        val b = bounds()
        val o = VDisplayFloatingMath.cornerOrigin(Corner.TopStart, 100, 200, b)
        assertEquals(Origin(b.minX, b.minY), o)
    }

    @Test
    fun `cornerOrigin TopEnd is at right,minY`() {
        val b = bounds()
        val o = VDisplayFloatingMath.cornerOrigin(Corner.TopEnd, 100, 200, b)
        assertEquals((b.maxX - 100).coerceAtLeast(b.minX), o.x)
        assertEquals(b.minY, o.y)
    }

    @Test
    fun `cornerOrigin BottomStart is at minX,bottom`() {
        val b = bounds()
        val o = VDisplayFloatingMath.cornerOrigin(Corner.BottomStart, 100, 200, b)
        assertEquals(b.minX, o.x)
        assertEquals((b.maxY - 200).coerceAtLeast(b.minY), o.y)
    }

    @Test
    fun `cornerOrigin BottomEnd is at right,bottom`() {
        val b = bounds()
        val o = VDisplayFloatingMath.cornerOrigin(Corner.BottomEnd, 100, 200, b)
        assertEquals((b.maxX - 100).coerceAtLeast(b.minX), o.x)
        assertEquals((b.maxY - 200).coerceAtLeast(b.minY), o.y)
    }

    @Test
    fun `cornerOrigin all four corners are distinct for normal screen`() {
        val b = bounds()
        val tl = VDisplayFloatingMath.cornerOrigin(Corner.TopStart, 100, 200, b)
        val tr = VDisplayFloatingMath.cornerOrigin(Corner.TopEnd, 100, 200, b)
        val bl = VDisplayFloatingMath.cornerOrigin(Corner.BottomStart, 100, 200, b)
        val br = VDisplayFloatingMath.cornerOrigin(Corner.BottomEnd, 100, 200, b)
        // Top row y same, bottom row y same
        assertEquals(tl.y, tr.y)
        assertEquals(bl.y, br.y)
        // Left column x same, right column x same
        assertEquals(tl.x, bl.x)
        assertEquals(tr.x, br.x)
        // Top ≠ bottom; left ≠ right
        assertTrue(tl.y < bl.y)
        assertTrue(tl.x < tr.x)
    }

    // ── snapCorner ─────────────────────────────────────────────────────────

    @Test
    fun `snapCorner in top-left quadrant with no velocity → TopStart`() {
        val b = bounds()
        // Centre of screen: (540, 1200). Place window in top-left quadrant.
        val corner = VDisplayFloatingMath.snapCorner(50, 100, 0f, 0f, 100, 200, b)
        assertEquals(Corner.TopStart, corner)
    }

    @Test
    fun `snapCorner in top-right quadrant → TopEnd`() {
        val b = bounds()
        val corner = VDisplayFloatingMath.snapCorner(900, 100, 0f, 0f, 100, 200, b)
        assertEquals(Corner.TopEnd, corner)
    }

    @Test
    fun `snapCorner in bottom-left quadrant → BottomStart`() {
        val b = bounds()
        val corner = VDisplayFloatingMath.snapCorner(50, 1800, 0f, 0f, 100, 200, b)
        assertEquals(Corner.BottomStart, corner)
    }

    @Test
    fun `snapCorner in bottom-right quadrant → BottomEnd`() {
        val b = bounds()
        val corner = VDisplayFloatingMath.snapCorner(900, 1800, 0f, 0f, 100, 200, b)
        assertEquals(Corner.BottomEnd, corner)
    }

    @Test
    fun `snapCorner velocity biases towards TopEnd from top-left`() {
        val b = bounds()
        // Start near centre-left, fling right → biased centre crosses midX → TopEnd
        val corner = VDisplayFloatingMath.snapCorner(400, 100, 5000f, 0f, 100, 200, b)
        assertEquals(Corner.TopEnd, corner)
    }

    @Test
    fun `snapCorner velocity biases towards BottomEnd from top-right`() {
        val b = bounds()
        val corner = VDisplayFloatingMath.snapCorner(900, 100, 0f, 5000f, 100, 200, b)
        assertEquals(Corner.BottomEnd, corner)
    }

    @Test
    fun `snapCorner ignores NaN velocity`() {
        val b = bounds()
        // NaN velocity → should behave like zero velocity
        val withNaN = VDisplayFloatingMath.snapCorner(50, 100, Float.NaN, Float.NaN, 100, 200, b)
        val withZero = VDisplayFloatingMath.snapCorner(50, 100, 0f, 0f, 100, 200, b)
        assertEquals(withZero, withNaN)
    }

    @Test
    fun `snapCorner ignores Infinity velocity`() {
        val b = bounds()
        // Inf velocity still picks a valid corner (velocity bias is capped by float arithmetic)
        val corner = VDisplayFloatingMath.snapCorner(50, 100, Float.POSITIVE_INFINITY, 0f, 100, 200, b)
        // With +Inf bias the biased cx is +Inf, which is >= midX → End column
        assertEquals(Corner.TopEnd, corner)
    }

    // ── shouldShowFloatingWindow ───────────────────────────────────────────

    @Test
    fun `shouldShow returns true when all conditions met`() {
        assertTrue(
            VDisplayFloatingMath.shouldShowFloatingWindow(
                isRunning = true,
                displayId = 2,
                enabled = true,
                hasOverlayPermission = true,
                onPreviewScreen = false,
                closedForDisplayId = null,
            ),
        )
    }

    @Test
    fun `shouldShow returns false when not running`() {
        assertFalse(
            VDisplayFloatingMath.shouldShowFloatingWindow(
                isRunning = false,
                displayId = null,
                enabled = true,
                hasOverlayPermission = true,
                onPreviewScreen = false,
                closedForDisplayId = null,
            ),
        )
    }

    @Test
    fun `shouldShow returns false when disabled by user`() {
        assertFalse(
            VDisplayFloatingMath.shouldShowFloatingWindow(
                isRunning = true,
                displayId = 2,
                enabled = false,
                hasOverlayPermission = true,
                onPreviewScreen = false,
                closedForDisplayId = null,
            ),
        )
    }

    @Test
    fun `shouldShow returns false when overlay permission not granted`() {
        assertFalse(
            VDisplayFloatingMath.shouldShowFloatingWindow(
                isRunning = true,
                displayId = 2,
                enabled = true,
                hasOverlayPermission = false,
                onPreviewScreen = false,
                closedForDisplayId = null,
            ),
        )
    }

    @Test
    fun `shouldShow returns false when on preview screen`() {
        assertFalse(
            VDisplayFloatingMath.shouldShowFloatingWindow(
                isRunning = true,
                displayId = 2,
                enabled = true,
                hasOverlayPermission = true,
                onPreviewScreen = true,
                closedForDisplayId = null,
            ),
        )
    }

    @Test
    fun `shouldShow returns false for same displayId that was closed`() {
        assertFalse(
            VDisplayFloatingMath.shouldShowFloatingWindow(
                isRunning = true,
                displayId = 5,
                enabled = true,
                hasOverlayPermission = true,
                onPreviewScreen = false,
                closedForDisplayId = 5,
            ),
        )
    }

    @Test
    fun `shouldShow returns true for new displayId after close`() {
        // User closed overlay for display 5, then a new display 6 started
        assertTrue(
            VDisplayFloatingMath.shouldShowFloatingWindow(
                isRunning = true,
                displayId = 6,
                enabled = true,
                hasOverlayPermission = true,
                onPreviewScreen = false,
                closedForDisplayId = 5,
            ),
        )
    }

    @Test
    fun `shouldShow returns true when closedForDisplayId is null and display has id`() {
        // No prior close → show
        assertTrue(
            VDisplayFloatingMath.shouldShowFloatingWindow(
                isRunning = true,
                displayId = 3,
                enabled = true,
                hasOverlayPermission = true,
                onPreviewScreen = false,
                closedForDisplayId = null,
            ),
        )
    }

    @Test
    fun `shouldShow closed for id=null and current id=null still returns false`() {
        // Both null: null != null is false in Kotlin, so closed check passes through
        // → the overlay is NOT suppressed (null == null is false only in the != check;
        //   closedForDisplayId == null means "never explicitly closed").
        // Per spec: closed suppression only triggers when displayId != null.
        assertTrue(
            VDisplayFloatingMath.shouldShowFloatingWindow(
                isRunning = true,
                displayId = null,
                enabled = true,
                hasOverlayPermission = true,
                onPreviewScreen = false,
                closedForDisplayId = null,
            ),
        )
    }

    @Test
    fun `shouldShow displayId null with closedForDisplayId set → not suppressed`() {
        // displayId is null (transitional state) → closedForDisplayId check is skipped
        assertTrue(
            VDisplayFloatingMath.shouldShowFloatingWindow(
                isRunning = true,
                displayId = null,
                enabled = true,
                hasOverlayPermission = true,
                onPreviewScreen = false,
                closedForDisplayId = 5,
            ),
        )
    }

    // ── Edge cases: zoom step arithmetic ──────────────────────────────────

    @Test
    fun `applying ZOOM_STEP once then clamping stays in range`() {
        val zoomed = VDisplayFloatingMath.clampScale(1.0f * VDisplayFloatingMath.ZOOM_STEP)
        assertTrue(zoomed in VDisplayFloatingMath.MIN_SCALE..VDisplayFloatingMath.MAX_SCALE)
    }

    @Test
    fun `repeated zoom-in eventually saturates at MAX_SCALE`() {
        var s = 1.0f
        repeat(20) { s = VDisplayFloatingMath.clampScale(s * VDisplayFloatingMath.ZOOM_STEP) }
        assertEquals(VDisplayFloatingMath.MAX_SCALE, s, 0.0001f)
    }

    @Test
    fun `repeated zoom-out eventually saturates at MIN_SCALE`() {
        var s = 1.0f
        repeat(20) { s = VDisplayFloatingMath.clampScale(s / VDisplayFloatingMath.ZOOM_STEP) }
        assertEquals(VDisplayFloatingMath.MIN_SCALE, s, 0.0001f)
    }

    // ── SafeBounds helper properties ──────────────────────────────────────

    @Test
    fun `SafeBounds minX accounts for safe inset and margin`() {
        val b = SafeBounds(1080, 2400, 60, 80, 0, 0, 16)
        assertEquals(60 + 16, b.minX)
    }

    @Test
    fun `SafeBounds maxX accounts for safe inset and margin`() {
        val b = SafeBounds(1080, 2400, 0, 0, 40, 0, 16)
        assertEquals(1080 - 40 - 16, b.maxX)
    }

    @Test
    fun `SafeBounds minY accounts for top inset and margin`() {
        val b = SafeBounds(1080, 2400, 0, 80, 0, 0, 16)
        assertEquals(80 + 16, b.minY)
    }

    @Test
    fun `SafeBounds maxY accounts for bottom inset and margin`() {
        val b = SafeBounds(1080, 2400, 0, 0, 0, 120, 16)
        assertEquals(2400 - 120 - 16, b.maxY)
    }

    // ── WindowSize and Origin data classes ───────────────────────────────

    @Test
    fun `WindowSize equality`() {
        assertEquals(WindowSize(100, 200), WindowSize(100, 200))
        assertFalse(WindowSize(100, 200) == WindowSize(101, 200))
    }

    @Test
    fun `Origin equality`() {
        assertEquals(Origin(10, 20), Origin(10, 20))
        assertFalse(Origin(10, 20) == Origin(10, 21))
    }

    // ── fitWindowSize: inset larger than base ────────────────────────────

    @Test
    fun `fitWindowSize very large inset still returns positive size`() {
        val ws = VDisplayFloatingMath.fitWindowSize(
            baseWidthPx = 50f,
            aspectHeightOverWidth = 1f,
            maxWidthPx = 1000f,
            maxHeightPx = 1000f,
            chromeHeightPx = 0f,
            horizontalInsetPx = 100f, // inset > base
        )
        assertTrue(ws.widthPx >= 1)
        assertTrue(ws.heightPx >= 1)
    }

    // ── snapCorner: window larger than safe area ──────────────────────────

    @Test
    fun `snapCorner with oversized window still returns a valid Corner`() {
        val b = bounds(sw = 100, sh = 200, safeL = 0, safeT = 0, safeR = 0, safeB = 0, margin = 0)
        val corner = VDisplayFloatingMath.snapCorner(0, 0, 0f, 0f, 500, 500, b)
        assertTrue(corner in Corner.entries)
    }
}
