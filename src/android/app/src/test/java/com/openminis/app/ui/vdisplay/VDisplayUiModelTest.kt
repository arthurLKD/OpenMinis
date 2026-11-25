package com.openminis.app.ui.vdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-vdisplay-ui] Pure tests for the background-screen page's arithmetic.
 *
 * These are the parts that can be verified without a device:
 *
 *  - geometry validation (the page must refuse what the core would refuse,
 *    and must NOT silently clamp what the user typed);
 *  - preview coordinate mapping (a tap in the letterbox must not be sent, and
 *    a tap inside the image must land on the right virtual-display pixel);
 *  - the touch mapper's rounding, which is where off-by-one taps come from.
 *
 * Everything that needs Shizuku, DisplayManager or an ImageReader is out of
 * scope here and is listed as an unverified boundary in the report.
 */
class VDisplayUiModelTest {

    // ── Geometry ─────────────────────────────────────────────────────────

    @Test
    fun `default config is valid`() {
        val cfg = VDisplayConfig()
        assertTrue(cfg.isValid)
        assertEquals(1080, cfg.widthPx)
        assertEquals(1920, cfg.heightPx)
        assertEquals(420, cfg.densityDpi)
    }

    @Test
    fun `bounds are inclusive at both ends`() {
        assertTrue(
            VDisplayConfig(VDisplayConfig.MIN_SIZE, VDisplayConfig.MIN_SIZE, VDisplayConfig.MIN_DPI)
                .isValid,
        )
        assertTrue(
            VDisplayConfig(VDisplayConfig.MAX_SIZE, VDisplayConfig.MAX_SIZE, VDisplayConfig.MAX_DPI)
                .isValid,
        )
    }

    @Test
    fun `out of range values are invalid rather than clamped`() {
        // The page shows an error for these; it must not quietly create a
        // display at a size the user did not ask for.
        assertFalse(VDisplayConfig(widthPx = 0).isValid)
        assertFalse(VDisplayConfig(widthPx = VDisplayConfig.MAX_SIZE + 1).isValid)
        assertFalse(VDisplayConfig(heightPx = 0).isValid)
        assertFalse(VDisplayConfig(densityDpi = VDisplayConfig.MIN_DPI - 1).isValid)
        assertFalse(VDisplayConfig(densityDpi = VDisplayConfig.MAX_DPI + 1).isValid)
        assertEquals(0, VDisplayConfig(widthPx = 0).widthPx)
    }

    @Test
    fun `normalized clamps into the UI range`() {
        val n = VDisplayConfig(widthPx = 9_999, heightPx = 1, densityDpi = 5).normalized()
        assertEquals(VDisplayConfig.MAX_SIZE, n.widthPx)
        assertEquals(VDisplayConfig.MIN_SIZE, n.heightPx)
        assertEquals(VDisplayConfig.MIN_DPI, n.densityDpi)
    }

    @Test
    fun `density scale follows wm density semantics`() {
        assertEquals(2.625f, VDisplayConfig(densityDpi = 420).densityScale, 0.0001f)
    }

    @Test
    fun `bounds match the CLI contract values`() {
        // The CLI duplicates these numbers to stay Compose-free; if one side
        // moves, the other must move with it (VDisplayCliParseTest pins the
        // same numbers on its side).
        assertEquals(240, VDisplayConfig.MIN_SIZE)
        assertEquals(3840, VDisplayConfig.MAX_SIZE)
        assertEquals(120, VDisplayConfig.MIN_DPI)
        assertEquals(800, VDisplayConfig.MAX_DPI)
    }

    // ── Preview touch mapping ────────────────────────────────────────────

    @Test
    fun `centre of the box maps to the centre of the frame`() {
        // 1000x500 box, 1080x1920 frame → scale 0.26 wide is the limiter?
        // 1000/1080 = 0.926, 500/1920 = 0.260 → height limits, letterbox left/right.
        val p = PreviewTouchMapper.map(
            localX = 500f, localY = 250f,
            boxWidth = 1000, boxHeight = 500,
            frameWidth = 1080, frameHeight = 1920,
        )
        assertEquals(540, p!!.first)
        assertEquals(960, p.second)
    }

    @Test
    fun `tap in the letterbox is rejected`() {
        // Same layout: drawn width = 1080*0.2604 = 281px, centred → left ≈ 359.
        val p = PreviewTouchMapper.map(
            localX = 10f, localY = 250f,
            boxWidth = 1000, boxHeight = 500,
            frameWidth = 1080, frameHeight = 1920,
        )
        assertNull(p)
    }

    @Test
    fun `frame taller than wide is mapped by its height`() {
        // 200x200 box, 100x400 frame → scale 0.5, drawn 50x200, left = 75.
        val topLeft = PreviewTouchMapper.map(75f, 0f, 200, 200, 100, 400)
        assertEquals(0 to 0, topLeft)
        val bottomRight = PreviewTouchMapper.map(125f, 200f, 200, 200, 100, 400)
        assertEquals(99 to 399, bottomRight)
    }

    @Test
    fun `points exactly on the image edge stay inside the frame`() {
        val p = PreviewTouchMapper.map(200f, 200f, 200, 200, 200, 200)
        assertEquals(199 to 199, p)
    }

    @Test
    fun `degenerate box or frame yields null instead of dividing by zero`() {
        assertNull(PreviewTouchMapper.map(0f, 0f, 0, 0, 100, 100))
        assertNull(PreviewTouchMapper.map(0f, 0f, 100, 100, 0, 100))
        assertNull(PreviewTouchMapper.map(0f, 0f, 100, 100, 100, 0))
    }

    @Test
    fun `rotated buffer uses the frame size not the configured size`() {
        // The core returns the buffer's size, which after rotation is the
        // configured size transposed. Mapping must follow the frame, otherwise
        // every tap lands mirrored.
        val portrait = PreviewTouchMapper.map(100f, 100f, 200, 200, 100, 200)
        val landscape = PreviewTouchMapper.map(100f, 100f, 200, 200, 200, 100)
        assertEquals(50 to 100, portrait)
        assertEquals(100 to 50, landscape)
    }
}
