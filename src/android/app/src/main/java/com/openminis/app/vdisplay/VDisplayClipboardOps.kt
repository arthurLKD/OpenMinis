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
 * Ctrl+modifier injection adapted from Mika's ShellService.injectCtrlKey
 * (https://github.com/wangzan101/mika, Apache-2.0). See NOTICE.
 */
package com.openminis.app.vdisplay

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import com.openminis.app.vdisplay.VDisplayShellServer.Companion.TAG

/** Modifier keys supported by [VDisplayShellServer.injectCtrlKeyChecked]. */
internal object VDisplayCtrl {
    const val A = KeyEvent.KEYCODE_A   // 29 — select all
    const val V = KeyEvent.KEYCODE_V   // 50 — paste

    fun resolve(name: String): Int? = when (name.trim().uppercase()) {
        "A", "CTRL_A", "SELECT_ALL" -> A
        "V", "CTRL_V", "PASTE" -> V
        else -> null
    }
}

/**
 * Send Ctrl+<key> on OUR display only. The modifier is pressed and released
 * with the same targeted routing as [VDisplayShellServer.injectKeyChecked] so
 * the combination never leaks onto the physical screen.
 *
 * Every press is paired with a matching release: a key-down that cannot be
 * confirmed as fully released is reported as partial so a caller never retries
 * the whole combination and double-fires the shortcut.
 *
 * Returns 1=dispatched, 0=refused, 2=partial/unconfirmed (do not retry blindly:
 * the key-down may already have been delivered).
 */
internal fun VDisplayShellServer.injectCtrlKeyChecked(displayId: Int, keyCode: Int): Int {
    if (!ownDisplay(displayId) || displayId <= Display.DEFAULT_DISPLAY) return VDisplayText.NOT_SENT
    if (keyCode != VDisplayCtrl.A && keyCode != VDisplayCtrl.V) {
        Log.w(TAG, "refused ctrl key $keyCode on display $displayId")
        return VDisplayText.NOT_SENT
    }
    val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    fun event(code: Int, action: Int, modifiers: Int): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(now, now, action, code, 0, modifiers, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
    }

    var ctrlDown = false
    var keyDown = false
    try {
        if (!inject(event(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_DOWN, 0), displayId, waitForFinish = true)) {
            return VDisplayText.NOT_SENT
        }
        ctrlDown = true
        if (!inject(event(keyCode, KeyEvent.ACTION_DOWN, meta), displayId, waitForFinish = true)) {
            return VDisplayText.NOT_SENT
        }
        keyDown = true
        // Release the letter key first (still under Ctrl), then Ctrl. Both are
        // always attempted so a modifier can never be left stuck down.
        val keyUp = event(keyCode, KeyEvent.ACTION_UP, meta)
        val keyReleased = inject(keyUp, displayId, waitForFinish = true) ||
            inject(keyUp, displayId, waitForFinish = true)
        keyDown = false
        val ctrlUp = event(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_UP, 0)
        val ctrlReleased = inject(ctrlUp, displayId, waitForFinish = true) ||
            inject(ctrlUp, displayId, waitForFinish = true)
        ctrlDown = false
        return if (keyReleased && ctrlReleased) VDisplayText.DISPATCHED else VDisplayText.UNCONFIRMED
    } finally {
        if (ctrlDown || keyDown) {
            // Defence in depth: release whatever is still believed down. Order
            // matters — the letter must come up before the modifier.
            if (keyDown && ownDisplay(displayId)) {
                val keyUp = event(keyCode, KeyEvent.ACTION_UP, meta)
                inject(keyUp, displayId) || inject(keyUp, displayId)
            }
            if (ctrlDown && ownDisplay(displayId)) {
                val ctrlUp = event(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_UP, 0)
                if (!inject(ctrlUp, displayId) && !inject(ctrlUp, displayId)) {
                    Log.w(TAG, "could not release Ctrl on display $displayId")
                }
            }
        }
    }
}

/** The service-side clipboard; created lazily so a missing manager is not fatal to display creation. */
internal val VDisplayShellServer.clipboard: VDisplayClipboard
    get() = clipboardDelegate ?: synchronized(clipboardLock) {
        clipboardDelegate ?: VDisplayClipboard(context).also { clipboardDelegate = it }
    }

/** Serializes the entire paste (clipboard write → injection → settle → restore). */
internal val VDisplayShellServer.pasteLock: Any
    get() = pasteLockDelegate ?: synchronized(clipboardLock) {
        pasteLockDelegate ?: Any().also { pasteLockDelegate = it }
    }

/** Result codes for `clipboard set` / `clipboard restore`. */
internal object VDisplayClipResult {
    const val OK = 0
    const val UNAVAILABLE = 1
    const val REFUSED = 2
    const val UNREADABLE = 3
}

/**
 * Write text into the system clipboard from the shell identity. [text] may be
 * any Unicode string (CJK, emoji, newlines). `null` asks for a read-back of
 * metadata only and is never used by the CLI; reads are exposed as `get`.
 */
internal fun VDisplayShellServer.clipboardSet(text: String): Int {
    if (text.length > VDisplayClipLimits.MAX_CLIP_CHARS) return VDisplayClipResult.REFUSED
    return when (clipboard.writeText(text)) {
        VDisplayClipboard.WriteResult.SET -> VDisplayClipResult.OK
        VDisplayClipboard.WriteResult.UNREADABLE -> VDisplayClipResult.UNREADABLE
        VDisplayClipboard.WriteResult.REFUSED -> VDisplayClipResult.REFUSED
        else -> VDisplayClipResult.UNAVAILABLE
    }
}

/** Read the clipboard (may be null when empty or unreadable). */
internal fun VDisplayShellServer.clipboardGet(): String? = clipboard.readText()

/** Restore the user's previous clip when we still own the current one. */
internal fun VDisplayShellServer.clipboardRestore(): Boolean = clipboard.restore()

/**
 * One-shot Unicode text entry for the agent CLI: write the clipboard from the
 * shell process, Ctrl+A then Ctrl+V on the target display, wait briefly for
 * the app to read the clip, and put the user's previous clipboard back.
 *
 * The WHOLE sequence runs under [pasteLock] — concurrent pastes must not
 * interleave a clipboard write with someone else's restore, or the user's
 * original clip is lost. The lock is held across the injection settle sleep so
 * a restore can never observe a half-pasted clipboard.
 *
 * This is what makes 中文/emoji input possible without any IME injection:
 * Android's `input text` only maps ASCII through KeyCharacterMap, whereas the
 * clipboard carries an arbitrary CharSequence.
 *
 * @param replaceAll select existing field content first (default true).
 * @return Bundle with `sent` (1/0/2) and `restored` (bool); never throws.
 */
internal fun VDisplayShellServer.pasteTextChecked(displayId: Int, text: String, replaceAll: Boolean): Bundle {
    val out = Bundle()
    out.putInt("sent", VDisplayText.NOT_SENT)
    out.putBoolean("restored", false)
    if (!ownDisplay(displayId) || text.isEmpty() || text.length > VDisplayClipLimits.MAX_CLIP_CHARS) return out
    return synchronized(pasteLock) {
        when (clipboardSet(text)) {
            VDisplayClipResult.OK -> Unit
            else -> {
                out.putInt("clip", VDisplayClipResult.UNAVAILABLE)
                return@synchronized out
            }
        }
        try {
            if (replaceAll) {
                when (injectCtrlKeyChecked(displayId, VDisplayCtrl.A)) {
                    VDisplayText.DISPATCHED -> Unit
                    else -> {
                        out.putInt("sent", VDisplayText.UNCONFIRMED)
                        return@synchronized out
                    }
                }
            }
            val sent = injectCtrlKeyChecked(displayId, VDisplayCtrl.V)
            out.putInt("sent", sent)
            if (sent == VDisplayText.NOT_SENT) return@synchronized out
            // Apps read the clipboard asynchronously after the paste shortcut.
            SystemClock.sleep(PASTE_SETTLE_MS)
            return@synchronized out
        } finally {
            // Never leave the user's clipboard holding our payload longer than
            // needed. A failed restore keeps the record for the next attempt
            // (and reports `restored=false` honestly).
            out.putBoolean("restored", clipboardRestore())
        }
    }
}

private const val PASTE_SETTLE_MS = 450L

internal object VDisplayClipLimits {
    const val MAX_CLIP_CHARS = 20_000
}
