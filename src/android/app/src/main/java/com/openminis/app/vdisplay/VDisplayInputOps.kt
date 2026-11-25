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
 * Portions adapted from Mika (https://github.com/wangzan101/mika, Apache-2.0).
 * See NOTICE in the repository root.
 */
package com.openminis.app.vdisplay

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import com.openminis.app.vdisplay.VDisplayShellServer.Companion.TAG
import com.openminis.app.vdisplay.VDisplayShellServer.Companion.TAP_MS

// ---------------------------------------------------------------------------
// Targeted input injection. EVERY path here requires displayId > 0 AND a
// display created by this server; nothing ever falls back to the physical
// display or to the untargeted global focus.
// ---------------------------------------------------------------------------

/** Tap at logical coordinates; bounds-checked against the display's current logical size. */
internal fun VDisplayShellServer.injectTapChecked(displayId: Int, x: Int, y: Int): Boolean {
    if (!ownDisplay(displayId) || displayId <= 0) return false
    val size = logicalSize(displayId) ?: return false
    if (x !in 0 until size.first || y !in 0 until size.second) {
        Log.w(TAG, "tap ($x,$y) outside ${size.first}x${size.second} on display $displayId")
        return false
    }
    val now = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
    val up = MotionEvent.obtain(now, now + TAP_MS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
    return try {
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        // No shell-command fallback for taps: `input tap` without a display id
        // would hit the physical screen, and with one it is strictly not more
        // capable than the binder path. Fail closed instead.
        sendPair({ inject(down, displayId) }, { inject(up, displayId) }) { false }
    } finally {
        down.recycle()
        up.recycle()
    }
}

/** One-finger stroke; see IVirtualDisplayService.injectStroke for the contract. */
internal fun VDisplayShellServer.injectStrokeChecked(
    displayId: Int, xs: IntArray, ys: IntArray, times: LongArray,
): Boolean {
    if (!ownDisplay(displayId) || displayId <= 0) return false
    val n = times.size
    if (n < 2 || n > VDisplayShellServer.STROKE_MAX_POINTS || xs.size != n || ys.size != n ||
        times[0] != 0L || times.last() > VDisplayShellServer.STROKE_MAX_MS ||
        (1 until n).any { times[it] < times[it - 1] }
    ) return false
    val size = logicalSize(displayId) ?: return false
    if (xs.any { it !in 0 until size.first } || ys.any { it !in 0 until size.second }) return false
    val down = SystemClock.uptimeMillis()
    fun event(i: Int, action: Int): MotionEvent {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply {
            x = xs[i].toFloat()
            y = ys[i].toFloat()
            pressure = 1f
            // PointerCoords carries the contact size and the touch ellipse as
            // `size` / `toolMajor` / `toolMinor` (getSize/getToolMajor/
            // getToolMinor). `toolMajorAxis` / `toolMinorAxis` do not exist and
            // failed to compile; these values keep the pointer a plain finger
            // contact rather than a zero-area one some ROMs ignore.
            size = 1f
            toolMajor = 1f
            toolMinor = 1f
        }
        return MotionEvent.obtain(down, down + times[i], action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }
    fun send(i: Int, action: Int): Boolean {
        val wait = down + times[i] - SystemClock.uptimeMillis()
        if (wait > 0) SystemClock.sleep(wait)
        val e = event(i, action)
        return try { inject(e, displayId) } finally { e.recycle() }
    }
    if (!send(0, MotionEvent.ACTION_DOWN)) return false
    for (i in 1 until n) send(i, MotionEvent.ACTION_MOVE)
    // Always finish with an UP so a finger never stays pressed; retry once.
    return send(n - 1, MotionEvent.ACTION_UP) || send(n - 1, MotionEvent.ACTION_UP)
}

/** Whitelisted key press, targeted at the display's top task uid when resolvable. */
internal fun VDisplayShellServer.injectKeyChecked(displayId: Int, keyCode: Int): Boolean {
    if (!ownDisplay(displayId) || displayId <= 0) return false
    if (!VDisplayKeys.isAllowed(keyCode)) {
        Log.w(TAG, "refused key $keyCode on display $displayId")
        return false
    }
    val now = SystemClock.uptimeMillis()
    val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
    val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
    // Keys are routed by the system's input focus, NOT by the event's
    // displayId: untargeted they would land on the main screen's focus.
    // Resolve the top task's uid and inject to it; only when that is not
    // available use display-scoped (still non-global) injection.
    val uid = topTaskUid(displayId)
    val ok = if (uid != null) {
        injectPairTo(down, up, displayId, uid) {
            injectPair(down, up, displayId) {
                exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0
            }
        }
    } else {
        injectPair(down, up, displayId) {
            exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0
        }
    }
    if (keyCode == KeyEvent.KEYCODE_BACK) {
        Log.i(TAG, "injectKey BACK display=$displayId uid=$uid ok=$ok")
    }
    return ok
}

/** Printable ASCII text as key events aimed at the display's top task. */
@SuppressLint("DiscouragedPrivateApi")
internal fun VDisplayShellServer.injectAsciiTextChecked(displayId: Int, text: String): Int {
    if (displayId <= 0 || !ownDisplay(displayId) || !VDisplayText.supports(text)) {
        return VDisplayText.NOT_SENT
    }
    val (events, targetUid) = runCatching {
        val task = tasksOn(displayId).firstOrNull() ?: return VDisplayText.NOT_SENT
        val uid = if (android.os.Build.VERSION.SDK_INT >= 33) {
            val user = TASK_INFO.getField("userId").getInt(task)
            val top = TASK_INFO.getField("topActivity").get(task) as? android.content.ComponentName
            val pkg = top?.packageName ?: return VDisplayText.NOT_SENT
            val pm = context.packageManager
            (pm.javaClass.getMethod("getPackageUidAsUser", String::class.java, Int::class.java, Int::class.java)
                .invoke(pm, pkg, 0, user) as Int).takeIf { it >= 0 } ?: return VDisplayText.NOT_SENT
        } else null
        val keys = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
            ?.takeIf { it.isNotEmpty() } ?: return VDisplayText.NOT_SENT
        keys to uid
    }.getOrElse { return VDisplayText.NOT_SENT }

    for (event in events) {
        if (!ownDisplay(displayId)) return VDisplayText.UNCONFIRMED
        event.source = InputDevice.SOURCE_KEYBOARD
        val sent = if (targetUid != null) injectTo(event, displayId, targetUid)
        else inject(event, displayId, waitForFinish = true)
        // A failed dispatch can leave a prefix. Never replay it through another input route.
        if (!sent) return VDisplayText.UNCONFIRMED
    }
    return VDisplayText.DISPATCHED
}

// ---------------------------------------------------------------------------
// Low-level injection plumbing (reflection into hidden InputManager APIs)
// ---------------------------------------------------------------------------

internal inline fun VDisplayShellServer.injectPair(down: InputEvent, up: InputEvent, displayId: Int, fallback: () -> Boolean): Boolean =
    sendPair({ inject(down, displayId) }, { inject(up, displayId) }, fallback)

/** Aimed at the focus window owned by [targetUid]; refused by the system when focus is elsewhere, so it cannot land on another window. */
internal inline fun VDisplayShellServer.injectPairTo(down: InputEvent, up: InputEvent, displayId: Int, targetUid: Int, fallback: () -> Boolean): Boolean =
    sendPair({ injectTo(down, displayId, targetUid) }, { injectTo(up, displayId, targetUid) }, fallback)

@SuppressLint("PrivateApi")
internal fun VDisplayShellServer.inject(event: InputEvent, displayId: Int, waitForFinish: Boolean = false): Boolean {
    // Defence in depth: display 0 is the user's real screen. Even a caller
    // mistake must not be able to register an event there.
    if (displayId <= Display.DEFAULT_DISPLAY) return false
    return try {
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val manager = inputManager()
        manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
            .invoke(manager, event, injectMode(event is KeyEvent, waitForFinish)) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "injectInputEvent failed", t)
        false
    }
}

/**
 * Hidden overload `injectInputEvent(event, mode, targetUid)` (Android 13+):
 * delivered only to the focus window owned by [targetUid], waiting for it to
 * finish; the system refuses when focus is elsewhere, so the event cannot
 * leak onto another window. When the overload is absent this returns false —
 * callers then use display-scoped injection, never the physical display.
 */
@SuppressLint("PrivateApi")
internal fun VDisplayShellServer.injectTo(event: InputEvent, displayId: Int, targetUid: Int): Boolean {
    if (displayId <= Display.DEFAULT_DISPLAY) return false
    return try {
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val manager = inputManager()
        manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java, Int::class.java)
            .invoke(manager, event, INJECT_MODE_WAIT_FOR_FINISH, targetUid) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "targeted injectInputEvent failed: ${(t.cause ?: t).javaClass.simpleName}")
        false
    }
}

/** API 34+ moved the injection entry point to InputManagerGlobal; earlier it lives on InputManager. */
@SuppressLint("PrivateApi")
internal fun VDisplayShellServer.inputManager(): Any = runCatching {
    Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
}.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!

/** Current logical size of the display (rotation applied); null when unreadable. */
internal fun VDisplayShellServer.logicalSize(displayId: Int): Pair<Int, Int>? = runCatching {
    val vd = synchronized(displays) { displays[displayId] } ?: return null
    val d = vd.display
    d.width to d.height
}.getOrNull()

/**
 * Down/up pair: only fall back when the DOWN itself failed. Falling back
 * after a delivered DOWN would double-fire the gesture; then we only retry
 * the UP once.
 */
internal inline fun sendPair(down: () -> Boolean, up: () -> Boolean, fallback: () -> Boolean): Boolean {
    if (!down()) return fallback()
    return up() || up()
}

/** Keys wait for a routing decision (WAIT_FOR_RESULT); touches stay ASYNC so a timed stroke keeps its pacing. */
internal fun injectMode(key: Boolean, waitForFinish: Boolean): Int = when {
    waitForFinish -> INJECT_MODE_WAIT_FOR_FINISH
    key -> INJECT_MODE_WAIT_FOR_RESULT
    else -> INJECT_MODE_ASYNC
}

internal const val INJECT_MODE_ASYNC = 0 // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
internal const val INJECT_MODE_WAIT_FOR_RESULT = 1 // InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
internal const val INJECT_MODE_WAIT_FOR_FINISH = 2 // InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
internal const val VIRTUAL_KEYBOARD = -1 // KeyCharacterMap.VIRTUAL_KEYBOARD

/**
 * Key whitelist shared by pressKey: navigation and editing keys only. Volume,
 * power/sleep, the real HOME, recents, search/assistant, camera and media
 * keys act on the WHOLE DEVICE rather than the virtual screen — refused.
 */
internal object VDisplayKeys {
    val BY_NAME: Map<String, Int> = linkedMapOf(
        "KEYCODE_DPAD_UP" to 19, "KEYCODE_DPAD_DOWN" to 20, "KEYCODE_DPAD_LEFT" to 21, "KEYCODE_DPAD_RIGHT" to 22,
        "KEYCODE_DPAD_CENTER" to 23, "KEYCODE_ENTER" to 66, "KEYCODE_NUMPAD_ENTER" to 160, "KEYCODE_DEL" to 67,
        "KEYCODE_FORWARD_DEL" to 112, "KEYCODE_TAB" to 61, "KEYCODE_SPACE" to 62, "KEYCODE_ESCAPE" to 111,
        "KEYCODE_PAGE_UP" to 92, "KEYCODE_PAGE_DOWN" to 93, "KEYCODE_MOVE_HOME" to 122, "KEYCODE_MOVE_END" to 123,
        "KEYCODE_BACK" to 4, "KEYCODE_MENU" to 82,
    )
    private val CODES: Set<Int> = BY_NAME.values.toSet()

    fun isAllowed(keyCode: Int): Boolean = keyCode in CODES

    /** Name or number; null when not whitelisted. */
    fun resolve(key: String): Int? {
        val k = key.trim()
        val code = k.toIntOrNull() ?: BY_NAME[k] ?: return null
        return code.takeIf(::isAllowed)
    }
}

/**
 * ASCII text input constraints and result codes.
 *
 * [MAX_CHARS] matches the CLI's `VDisplayCli.MAX_TEXT_CHARS` so a payload that
 * passes CLI validation is never rejected by the core; the bound still exists
 * because every character becomes at least one injected key event.
 */
internal object VDisplayText {
    const val NOT_SENT = 0
    const val DISPATCHED = 1
    const val UNCONFIRMED = 2
    const val MAX_CHARS = 1_000

    /** Control characters can trigger editor actions; they are never typed as text. */
    fun supports(text: String): Boolean = text.length in 1..MAX_CHARS && text.all { it in ' '..'~' }
}
