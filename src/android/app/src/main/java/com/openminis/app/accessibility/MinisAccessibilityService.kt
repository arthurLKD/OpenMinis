package com.openminis.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.openminis.app.logging.AppLogger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MinisAccessibilityService — host-side AccessibilityService backing the
 * `android-a11y-cli` offload command. The user must enable it manually
 * under Settings → Accessibility; we never start it programmatically.
 */
class MinisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MinisA11y"
        const val SERVICE_ID = "com.openminis.app/.accessibility.MinisAccessibilityService"
        private const val EVENT_RING_CAP = 1024

        @Volatile
        private var _instance: MinisAccessibilityService? = null

        fun getInstance(): MinisAccessibilityService? = _instance
    }

    data class RecordedEvent(
        val type: String,
        val packageName: String?,
        val className: String?,
        val text: String?,
        val timestamp: Long,
    )

    private val eventRing = ConcurrentLinkedQueue<RecordedEvent>()
    private val eventListeners = CopyOnWriteArrayList<(RecordedEvent) -> Unit>()

    val nodeRegistry: NodeRegistry = NodeRegistry()

    override fun onCreate() {
        super.onCreate()
        _instance = this
        AppLogger.info(TAG, "service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance = this
        // [T-android-a11y-miui-service-failure] Breadcrumb on (re)bind. On OEM
        // ROMs (MIUI etc.) the system kills + rebinds this service repeatedly,
        // surfacing "此服务出现故障"; a connect log lets us correlate a degraded
        // window in stall/diag logs with a kill/rebind. No self-restart logic —
        // remediation is the user whitelisting autostart + battery (see
        // SystemPermissionsScreen OEM guidance).
        AppLogger.info(TAG, "service connected (manufacturer=${android.os.Build.MANUFACTURER})")
        // [T-android-a11y-force-stop-recovery] Latch that the grant existed, so
        // a later absence can be recognized as a revocation rather than a
        // never-configured service. This is the one callback that only fires
        // when the user has genuinely granted it.
        try {
            AccessibilityRecoveryManager.markGranted(this)
            AccessibilityRecoveryManager.refreshRevokedState(this)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "markGranted failed: ${t.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val rec = RecordedEvent(
            type = AccessibilityEvent.eventTypeToString(event.eventType),
            packageName = event.packageName?.toString(),
            className = event.className?.toString(),
            text = event.text?.joinToString(" ") { it?.toString() ?: "" }?.takeIf { it.isNotBlank() },
            timestamp = System.currentTimeMillis(),
        )
        eventRing.offer(rec)
        while (eventRing.size > EVENT_RING_CAP) eventRing.poll()
        for (listener in eventListeners) {
            try { listener(rec) } catch (_: Throwable) {}
        }
    }

    override fun onInterrupt() {
        AppLogger.info(TAG, "service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (_instance === this) _instance = null
        eventRing.clear()
        nodeRegistry.clear()
        eventListeners.clear()
        AppLogger.info(TAG, "service destroyed")
    }

    fun snapshotEvents(): List<RecordedEvent> = eventRing.toList()

    fun addEventListener(listener: (RecordedEvent) -> Unit) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: (RecordedEvent) -> Unit) {
        eventListeners.remove(listener)
    }

    /**
     * Window list for one display, or the legacy default-display set when
     * [displayId] is null. Android 11+ exposes windows on ALL displays via
     * `getWindowsOnAllDisplays()`; the `windows` getter only covers the
     * default display, which is why a background virtual display previously
     * looked empty to the a11y CLI. Requires the service's existing
     * flagRetrieveInteractiveWindows (already in minis_accessibility_service.xml).
     *
     * [T-vdisplay-a11y-display] Fail-closed: an explicitly requested display
     * on Android 10 or older resolves to NO windows. Returning the default
     * display's windows instead would answer "here is the tree of display 7"
     * with the physical screen's content — the caller would then read (and
     * act on) the wrong screen while believing it addressed the virtual one.
     */
    private fun windowsFor(displayId: Int?): List<android.view.accessibility.AccessibilityWindowInfo> {
        if (displayId == null) {
            return windows?.toList() ?: emptyList()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            AppLogger.warning(
                TAG,
                "windowsFor($displayId): per-display windows need Android 11+; " +
                    "refusing to fall back to the default display",
            )
            return emptyList()
        }
        return runCatching {
            windowsOnAllDisplays?.get(displayId)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun rootNodes(displayId: Int? = null): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windowsFor(displayId)) {
                w.root?.let { out.add(it) }
            }
        } catch (_: Throwable) {}
        // Do NOT fall back to rootInActiveWindow for a named display: it
        // belongs to the physical screen and would silently answer with the
        // wrong app. The legacy (null) path keeps its original fallback.
        if (out.isEmpty() && displayId == null) {
            try { rootInActiveWindow?.let { out.add(it) } } catch (_: Throwable) {}
        }
        return out
    }

    fun windowInfos(displayId: Int? = null): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        try {
            if (displayId != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                // Fail-closed: a named display on Android 10 has no windows to
                // report. Listing the default display's windows under the
                // requested id would hand back the physical screen.
                AppLogger.warning(TAG, "windowInfos($displayId): per-display windows need Android 11+")
                return out
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && displayId != null) {
                for (w in windowsFor(displayId)) {
                    out.add(windowInfoMap(w, displayId))
                }
            } else {
                // Legacy behaviour: no --display means the default display only.
                for (w in windowsFor(null)) out.add(windowInfoMap(w, Display.DEFAULT_DISPLAY))
            }
        } catch (_: Throwable) {}
        return out
    }

    private fun windowInfoMap(w: android.view.accessibility.AccessibilityWindowInfo, id: Int): Map<String, Any?> = mapOf(
        "windowId" to w.id,
        "displayId" to id,
        "type" to when (w.type) {
            android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
            android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
            else -> "other"
        },
        "title" to (w.title?.toString() ?: ""),
        "focused" to w.isFocused,
        "active" to w.isActive,
    )

    /**
     * Window id → display id snapshot from `getWindowsOnAllDisplays()`.
     *
     * [T-vdisplay-a11y-display] A node id alone does not say which screen it
     * lives on. When the caller names a display, the node's owning display
     * has to be proven before any action is performed on it — otherwise a
     * node picked from the physical screen could be clicked while the caller
     * believes it addressed the virtual one.
     */
    private fun windowDisplayMap(): Map<Int, Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyMap()
        return runCatching {
            val all = windowsOnAllDisplays ?: return emptyMap()
            val out = HashMap<Int, Int>(all.size())
            for (i in 0 until all.size()) {
                val displayId = all.keyAt(i)
                for (w in all.valueAt(i) ?: emptyList()) {
                    if (w.id != -1) out[w.id] = displayId
                }
            }
            out
        }.getOrDefault(emptyMap())
    }

    /**
     * The display that owns [node], or null when ownership cannot be proven.
     *
     * null is deliberately NOT "the default display": an unknown owner means
     * the caller must not assume the node is on the screen it asked for.
     */
    fun nodeDisplayId(node: AccessibilityNodeInfo?): Int? {
        if (node == null) return null
        val map = windowDisplayMap()
        if (map.isEmpty()) return null
        var cur: AccessibilityNodeInfo? = node
        var guard = 0
        while (cur != null && guard++ < 64) {
            val windowId = cur.windowId
            if (windowId != -1) {
                // A known window id decides it; an unknown one is "unknown",
                // not "display 0".
                return map[windowId]
            }
            cur = cur.parent
        }
        return null
    }

    fun foregroundPackage(): Pair<String?, String?> {
        return try {
            val r = rootInActiveWindow
            (r?.packageName?.toString()) to (r?.className?.toString())
        } catch (_: Throwable) { null to null }
    }

    /** Top app package on a specific display, without ever consulting the physical screen's active window. */
    fun foregroundPackage(displayId: Int): Pair<String?, String?> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null to null
        val roots = rootNodes(displayId)
        val appRoot = roots.firstOrNull { it.packageName != null } ?: return null to null
        return appRoot.packageName?.toString() to appRoot.className?.toString()
    }

    /**
     * Outcome of a gesture dispatch. [ok] alone is not enough for a caller
     * that named a display: the gesture may have been *refused* (wrong API
     * level, unknown display) rather than merely cancelled, and those two
     * cases must be reported differently so the CLI can fail closed.
     */
    data class GestureOutcome(
        val ok: Boolean,
        val errorCode: String? = null,
        val errorMessage: String? = null,
    )

    /**
     * Builds a gesture bound to [displayId].
     *
     * [T-vdisplay-a11y-display] A virtual display has its own coordinate
     * origin; without an explicit `setDisplayId` the framework delivers the
     * gesture to the DEFAULT display — the physical screen. The previous
     * sub-agent claim that "dispatchGesture is inherently multi-display" is
     * wrong: `GestureDescription.Builder.setDisplayId` (API 30+) is the only
     * way to target a non-default display, and omitting it silently sends the
     * gesture to display 0.
     *
     * Therefore, when a display is named:
     *  - API < 30  → refuse (return null): there is no way to target it, and
     *                running it on the default display would touch the
     *                physical screen.
     *  - API >= 30 → `setDisplayId` is called; if that throws, refuse rather
     *                than fall back to the default display.
     */
    private fun buildGesture(strokes: List<GestureDescription.StrokeDescription>, displayId: Int?): GestureDescription? {
        val builder = GestureDescription.Builder()
        for (s in strokes) builder.addStroke(s)
        if (displayId != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                AppLogger.warning(
                    TAG,
                    "gesture for display $displayId needs Android 11+; refusing to fall back to the default display",
                )
                return null
            }
            try {
                builder.setDisplayId(displayId)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "setDisplayId($displayId) failed: ${t.message}")
                return null
            }
        }
        return builder.build()
    }

    fun dispatchSimpleGesture(
        path: Path,
        startTime: Long,
        durationMs: Long,
        timeoutMs: Long = 5000,
        displayId: Int? = null,
    ): Boolean = dispatchSimpleGestureOutcome(path, startTime, durationMs, timeoutMs, displayId).ok

    fun dispatchSimpleGestureOutcome(
        path: Path,
        startTime: Long,
        durationMs: Long,
        timeoutMs: Long = 5000,
        displayId: Int? = null,
    ): GestureOutcome {
        val stroke = GestureDescription.StrokeDescription(path, startTime, durationMs)
        val gesture = buildGesture(listOf(stroke), displayId)
            ?: return GestureOutcome(false, "DISPLAY_UNSUPPORTED",
                "cannot target display $displayId on this Android version (needs Android 11+)")
        val done = java.util.concurrent.CountDownLatch(1)
        val ok = booleanArrayOf(false)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok[0] = true; done.countDown() }
            override fun onCancelled(g: GestureDescription?) { ok[0] = false; done.countDown() }
        }
        Handler(Looper.getMainLooper()).post { dispatchGesture(gesture, callback, null) }
        val completed = done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!completed) {
            return GestureOutcome(false, "GESTURE_TIMEOUT", "dispatchGesture timed out after ${timeoutMs}ms")
        }
        if (!ok[0]) {
            return GestureOutcome(false, "GESTURE_FAILED", "dispatchGesture was cancelled")
        }
        return GestureOutcome(true)
    }

    fun dispatchPinch(cx: Float, cy: Float, scale: Float, durationMs: Long, displayId: Int? = null): Boolean =
        dispatchPinchOutcome(cx, cy, scale, durationMs, displayId).ok

    fun dispatchPinchOutcome(
        cx: Float, cy: Float, scale: Float, durationMs: Long, displayId: Int? = null,
    ): GestureOutcome {
        val initialOffset = 200f
        val finalOffset = (initialOffset * scale).coerceAtLeast(20f)
        val (s0, e0) = if (scale > 1f) {
            (cx - finalOffset to cy) to (cx - initialOffset to cy)
        } else {
            (cx - initialOffset to cy) to (cx - finalOffset to cy)
        }
        val (s1, e1) = if (scale > 1f) {
            (cx + finalOffset to cy) to (cx + initialOffset to cy)
        } else {
            (cx + initialOffset to cy) to (cx + finalOffset to cy)
        }
        val p0 = Path().apply { moveTo(s0.first, s0.second); lineTo(e0.first, e0.second) }
        val p1 = Path().apply { moveTo(s1.first, s1.second); lineTo(e1.first, e1.second) }
        val gesture = buildGesture(
            listOf(
                GestureDescription.StrokeDescription(p0, 0, durationMs),
                GestureDescription.StrokeDescription(p1, 0, durationMs),
            ),
            displayId,
        ) ?: return GestureOutcome(false, "DISPLAY_UNSUPPORTED",
            "cannot target display $displayId on this Android version (needs Android 11+)")
        val done = java.util.concurrent.CountDownLatch(1)
        val ok = booleanArrayOf(false)
        Handler(Looper.getMainLooper()).post {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { ok[0] = true; done.countDown() }
                override fun onCancelled(g: GestureDescription?) { ok[0] = false; done.countDown() }
            }, null)
        }
        val completed = done.await(durationMs + 2000, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!completed) {
            return GestureOutcome(false, "GESTURE_TIMEOUT", "pinch gesture timed out")
        }
        if (!ok[0]) {
            return GestureOutcome(false, "GESTURE_FAILED", "pinch gesture was cancelled")
        }
        return GestureOutcome(true)
    }

    /**
     * Result of [captureScreenshot]. On success [bitmap] is non-null and
     * caller is responsible for `bitmap.recycle()`.
     */
    data class ShotResult(
        val bitmap: Bitmap?,
        val errorCode: String? = null,
        val errorMessage: String? = null,
    )

    /**
     * Synchronously captures a system-wide screenshot via
     * `AccessibilityService.takeScreenshot` (API 30+). Blocks the calling
     * thread up to [timeoutMs]. Caller must check `Build.VERSION.SDK_INT`
     * before invoking — annotated `@RequiresApi(R)`.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun captureScreenshot(displayId: Int = Display.DEFAULT_DISPLAY, timeoutMs: Long = 5_000): ShotResult {
        val done = CountDownLatch(1)
        val resultRef = java.util.concurrent.atomic.AtomicReference(
            ShotResult(null, "TIMEOUT", "takeScreenshot timed out")
        )
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            takeScreenshot(displayId, executor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hwBuffer: HardwareBuffer = screenshot.hardwareBuffer
                        val cs: ColorSpace? = screenshot.colorSpace
                        val bmp = Bitmap.wrapHardwareBuffer(hwBuffer, cs)
                        try { hwBuffer.close() } catch (_: Throwable) {}
                        if (bmp == null) {
                            resultRef.set(ShotResult(null, "DECODE_FAILED", "wrapHardwareBuffer returned null"))
                        } else {
                            // Copy to a software bitmap so we can encode + scale freely.
                            val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
                            bmp.recycle()
                            resultRef.set(ShotResult(sw))
                        }
                    } catch (t: Throwable) {
                        resultRef.set(ShotResult(null, "DECODE_FAILED", "${t.javaClass.simpleName}: ${t.message ?: ""}"))
                    } finally {
                        done.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    resultRef.set(ShotResult(null, "TAKE_SCREENSHOT_FAILED", "takeScreenshot error code=$errorCode"))
                    done.countDown()
                }
            })
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                AppLogger.warning(TAG, "takeScreenshot timed out after ${timeoutMs}ms")
            }
        } catch (t: Throwable) {
            resultRef.set(ShotResult(null, "INTERNAL", "${t.javaClass.simpleName}: ${t.message ?: ""}"))
        } finally {
            executor.shutdown()
        }
        return resultRef.get()
    }

    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
