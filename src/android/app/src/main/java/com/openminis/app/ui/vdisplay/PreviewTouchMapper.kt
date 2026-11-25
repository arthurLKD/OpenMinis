package com.openminis.app.ui.vdisplay

/**
 * [T-vdisplay-ui] Maps a touch point inside the preview box to a virtual-display
 * pixel.
 *
 * The preview draws the frame with `ContentScale.Fit`, so the bitmap can be
 * letterboxed inside the box: the touchable rect is not the box, and using the
 * box size as the scale would put every coordinate off by the letterbox margin.
 * Keeping this arithmetic out of the composable makes the rule testable without
 * a device — which matters, because a wrong mapping silently taps the wrong
 * place on someone else's virtual app.
 *
 * Units: [boxWidth]/[boxHeight] and [localX]/[localY] are Compose dp-independent
 * pixels inside the layout node; [frameWidth]/[frameHeight] are the captured
 * bitmap's pixel size (which can differ from the requested geometry after
 * rotation — the core returns the buffer's size).
 */
internal object PreviewTouchMapper {

    /** Returns null when the point lies in the letterbox / outside the image. */
    fun map(
        localX: Float,
        localY: Float,
        boxWidth: Int,
        boxHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
    ): Pair<Int, Int>? {
        if (boxWidth <= 0 || boxHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) return null
        val scale = minOf(
            boxWidth.toFloat() / frameWidth,
            boxHeight.toFloat() / frameHeight,
        )
        if (scale <= 0f) return null
        val drawW = frameWidth * scale
        val drawH = frameHeight * scale
        val left = (boxWidth - drawW) / 2f
        val top = (boxHeight - drawH) / 2f
        if (localX < left || localY < top || localX > left + drawW || localY > top + drawH) {
            return null
        }
        val x = ((localX - left) / scale).toInt().coerceIn(0, frameWidth - 1)
        val y = ((localY - top) / scale).toInt().coerceIn(0, frameHeight - 1)
        return x to y
    }
}
