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

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream

/**
 * Offscreen frame sink for one virtual display. Owns an [ImageReader] whose
 * [surface] is handed to the privileged service as the display's render
 * target; continuously draining the reader keeps the Surface valid for the
 * whole lifetime of the display (long-lived keep-alive) and makes
 * screenshots independent of any accessibility screenshot API.
 *
 * Frame processing is throttled to <= 10 fps: when a frame arrives sooner
 * than [FRAME_INTERVAL_MS] after the last one, processing is deferred and
 * intermediate frames are dropped by acquireLatestImage.
 *
 * All public methods are safe to call from any thread; buffer copies happen
 * on the dedicated [thread], never on the caller's (or the main) thread.
 */
internal class FrameSink(width: Int, height: Int) {

    private val thread = HandlerThread("openminis-vdisplay-frame").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

    /** Hand this to the privileged service's createDisplay. */
    val surface: Surface get() = reader.surface

    val bufferWidth: Int = width
    val bufferHeight: Int = height

    private val frameLock = Any()
    private var fullFrame: Bitmap? = null
    private var hasFrame = false
    private var frameAtMs = 0L
    private var lastProcessedAt = 0L
    private var pending = false
    @Volatile private var released = false

    private val process = Runnable {
        pending = false
        lastProcessedAt = android.os.SystemClock.uptimeMillis()
        processLatest()
    }

    /** Start consuming frames; call after the display has been created. */
    fun start() {
        reader.setOnImageAvailableListener({ schedule() }, handler)
        // Frames may already be queued from the creation window.
        handler.post { schedule() }
    }

    /** A copy of the latest frame, or null when nothing has been drawn yet. Caller owns the Bitmap. */
    fun latestFrameBitmap(): Bitmap? = synchronized(frameLock) {
        val bmp = fullFrame
        if (!hasFrame || bmp == null || released) null
        else bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false)
    }

    /**
     * Return a [FrameBitmap] only when a newer frame is available.
     *
     * Decision made atomically under [frameLock]:
     *  - returns **null** immediately (no copy) when any of:
     *    - no frame has arrived yet (!hasFrame)
     *    - [fullFrame] is null
     *    - this sink has been released
     *    - the stored frame is not newer than [lastCapturedAtMs]
     *  - otherwise atomically copies the bitmap and returns a [FrameBitmap].
     *
     * Callers (e.g. the floating overlay refresh loop) can pass the
     * [FrameBitmap.capturedAtMs] from the previous call to suppress
     * redundant copies when the display has not redrawn.
     */
    fun latestFrameIfNewer(lastCapturedAtMs: Long): FrameBitmap? = synchronized(frameLock) {
        val bmp = fullFrame
        if (!hasFrame || bmp == null || released || frameAtMs <= lastCapturedAtMs) return@synchronized null
        val copy = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false)
        FrameBitmap(copy, copy.width, bmp.height, frameAtMs)
    }

    /** PNG encoding of the latest frame; null when there is none. */
    fun latestFramePng(): ByteArray? {
        val copy = latestFrameBitmap() ?: return null
        return try {
            ByteArrayOutputStream().also { copy.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            copy.recycle()
        }
    }

    /**
     * PNG + the geometry and capture time of the SAME frame, taken under one
     * lock so the bytes and the reported size can never disagree. Null when
     * nothing has been drawn yet.
     */
    fun latestFrameSnapshot(): FrameSnapshot? {
        val (copy, atMs) = synchronized(frameLock) {
            val bmp = fullFrame
            if (!hasFrame || bmp == null || released) return null
            bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false) to frameAtMs
        }
        return try {
            val png = ByteArrayOutputStream()
                .also { copy.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
            FrameSnapshot(png, copy.width, copy.height, atMs)
        } finally {
            copy.recycle()
        }
    }

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacks(process)
        // Finish on the frame thread to avoid racing an in-flight copy.
        handler.post {
            runCatching { reader.close() }
            synchronized(frameLock) {
                fullFrame?.recycle()
                fullFrame = null
                hasFrame = false
            }
        }
        thread.quitSafely()
    }

    private fun schedule() {
        if (pending || released) return
        pending = true
        val wait = lastProcessedAt + FRAME_INTERVAL_MS - android.os.SystemClock.uptimeMillis()
        if (wait > 0) handler.postDelayed(process, wait) else handler.post(process)
    }

    private fun processLatest() {
        if (released) return
        val image = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            Log.w(TAG, "acquireLatestImage failed: ${t.javaClass.simpleName}", t)
            null
        } ?: return
        try {
            val plane = image.planes[0]
            val strideWidth = plane.rowStride / plane.pixelStride
            synchronized(frameLock) {
                var bmp = fullFrame
                if (bmp == null || bmp.width != strideWidth || bmp.height != bufferHeight) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(strideWidth, bufferHeight, Bitmap.Config.ARGB_8888)
                    fullFrame = bmp
                }
                bmp.copyPixelsFromBuffer(plane.buffer.rewind())
                hasFrame = true
                frameAtMs = android.os.SystemClock.elapsedRealtime()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "frame copy failed: ${t.javaClass.simpleName}", t)
        } finally {
            image.close()
        }
    }

    private companion object {
        const val TAG = "OpenMinisFrameSink"
        const val FRAME_INTERVAL_MS = 100L
    }
}

/**
 * One immutable frame: encoded PNG bytes plus the geometry and time they
 * belong to, captured under a single lock so they can never disagree.
 */
internal class FrameSnapshot(
    val png: ByteArray,
    val width: Int,
    val height: Int,
    val capturedAtMs: Long,
)

/**
 * A live bitmap frame captured from the virtual display, together with its
 * geometry and the [capturedAtMs] timestamp (from [SystemClock.elapsedRealtime]).
 *
 * The [bitmap] is a fresh copy owned exclusively by the caller; it must be
 * recycled when no longer needed. Width/height are the actual pixel dimensions
 * of [bitmap] (which may differ from the configured display resolution if the
 * ImageReader stride is wider than the logical width).
 *
 * Visible in the `vdisplay` package. Used by the floating overlay renderer to
 * skip redundant copies via [FrameSink.latestFrameIfNewer].
 */
data class FrameBitmap(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val capturedAtMs: Long,
)
