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
 * Clipboard backup / write / restore adapted from Mika's ShellClipboard,
 * ShellClip, ShellClipOps and ShellService (https://github.com/wangzan101/mika,
 * Apache-2.0). See NOTICE in the repository root.
 */
package com.openminis.app.vdisplay

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PersistableBundle
import android.os.Process
import android.util.Log
import com.openminis.app.vdisplay.VDisplayShellServer.Companion.TAG
import java.lang.reflect.Method

/**
 * Pure decision table for swapping the user's clip out and back. It never
 * touches the system clipboard, so the exact same logic runs in a JVM unit
 * test. Mirror of Mika's `ShellClip`.
 */
internal object VDisplayClipState {
    enum class Read {
        /** Definitely empty: content, presence and description all agree. */
        Empty,

        /** Content was actually read. */
        Has,

        /** Present but unreadable: must never be treated as empty. */
        Unreadable,
    }

    fun read(clipPresent: Boolean, hasPrimaryClip: Boolean, descriptionPresent: Boolean): Read = when {
        clipPresent -> Read.Has
        hasPrimaryClip || descriptionPresent -> Read.Unreadable
        else -> Read.Empty
    }

    /** What the clipboard holds right now, from our point of view. */
    enum class Now { Ours, Empty, Other, Unreadable }

    enum class Restore {
        /** Put the user's previous clip back. */
        PutBack,

        /** The user's clip was empty; clear ours. */
        Clear,

        /** Leave it alone: the user copied something else, or it is unreadable now. */
        Leave,
    }

    /**
     * [priorEmpty] is true only when the pre-swap read said "definitely empty".
     * A clipboard that has since been emptied (e.g. system auto-clear) while we
     * had something is not user content, so put the original back.
     */
    fun restore(priorEmpty: Boolean, now: Now): Restore = when (now) {
        Now.Ours -> if (priorEmpty) Restore.Clear else Restore.PutBack
        Now.Empty -> if (priorEmpty) Restore.Leave else Restore.PutBack
        Now.Other, Now.Unreadable -> Restore.Leave
    }
}

/**
 * System clipboard access from the Shizuku UserService process (shell uid).
 *
 * Why this cannot live in the Minis process: since Android 10 an app that is
 * not the foreground app (nor the IME) is refused both reading AND writing the
 * clipboard — `ClipboardOffloadHandler` therefore always answers
 * `clipboard_requires_foreground`, and the moment an agent drives an app on a
 * virtual display Minis is necessarily in the background. A shell-identity
 * process is not subject to that foreground restriction, so the clipboard has
 * to be written HERE, next to the input injection, and never through
 * `android-clipboard`.
 *
 * Reads go through the hidden `IClipboard` binder directly: a shell process has
 * no registered app and the framework `ClipboardManager` read path first runs a
 * Settings query that never resolves in this process, silently returning null
 * even when a clip is present. Writes keep the framework `setPrimaryClip` /
 * `clearPrimaryClip` flow, exactly like Mika.
 *
 * Safety rules (mirroring Mika's ShellService):
 *   - never write over a clipboard we could not read first: an unreadable
 *     clip may hold user data, and guessing "empty" would destroy it;
 *   - every write is verified by reading the clip back; a silently declined
 *     `setPrimaryClip` is reported as refused, never as success;
 *   - the previous clip is kept and put back after the paste, but ONLY while
 *     the current content is still the one we wrote — a clip the user has
 *     since replaced is left alone;
 *   - a failed restore keeps the saved record so the next call retries, and
 *     never clears state on the non-local `return` paths;
 *   - writes are marked sensitive and carry the "suppress overlay" extra so
 *     a shell write does not pop the system "copied" toast;
 *   - every operation is bounded and returns a result instead of throwing;
 *     no clip text is ever logged.
 */
internal class VDisplayClipboard(private val context: Context) {

    private val manager: ClipboardManager? by lazy {
        runCatching {
            ClipboardManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java)
                .apply { isAccessible = true }
                .newInstance(context, Handler(Looper.getMainLooper() ?: Looper.myLooper()!!))
        }.onFailure { Log.w(TAG, "ClipboardManager unavailable: ${it.message}") }.getOrNull()
    }

    /** Hidden IClipboard binder for the read path (see class doc). */
    private val service: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
            Class.forName("android.content.IClipboard\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.getOrNull()
    }

    private val getClip by lazy { method("getPrimaryClip") }
    private val getDescription by lazy { method("getPrimaryClipDescription") }
    private val hasClip by lazy { method("hasPrimaryClip") }

    private val callerLabel by lazy {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(VDisplayShellContext.SHELL_PACKAGE, 0)).toString()
        }.getOrDefault(VDisplayShellContext.SHELL_PACKAGE)
    }

    private val lock = Any()
    private var saved: ClipData? = null
    private var held = false
    private var mine: String? = null

    // ------------------------------------------------------------------
    // Read helpers (reflection first, framework fallback — mirror of Mika)
    // ------------------------------------------------------------------

    private val primaryClip: ClipData?
        get() = getClip?.let { call(it) as ClipData? } ?: manager?.primaryClip

    private val primaryClipDescription: ClipDescription?
        get() = getDescription?.let { call(it) as ClipDescription? } ?: manager?.primaryClipDescription

    private fun hasPrimaryClip(): Boolean =
        hasClip?.let { call(it) as Boolean } ?: manager?.hasPrimaryClip() ?: false

    /** Clipboard state right now relative to [ours], never treating "unreadable" as empty. */
    private fun nowState(ours: String?): VDisplayClipState.Now {
        val now = primaryClip
        return when (VDisplayClipState.read(now != null, hasPrimaryClip(), primaryClipDescription != null)) {
            VDisplayClipState.Read.Empty -> VDisplayClipState.Now.Empty
            VDisplayClipState.Read.Unreadable -> VDisplayClipState.Now.Unreadable
            VDisplayClipState.Read.Has -> {
                val ourClip = now!!.description.label == CLIP_LABEL && now.itemCount == 1 && ours != null &&
                    now.getItemAt(0).text?.toString() == ours
                if (ourClip) VDisplayClipState.Now.Ours else VDisplayClipState.Now.Other
            }
        }
    }

    /** Find a readable IClipboard method by capability; null falls back to ClipboardManager. */
    private fun method(name: String): Method? {
        if (service == null) return null
        val type = runCatching { Class.forName("android.content.IClipboard") }.getOrNull() ?: return null
        val s = String::class.java
        val i = Int::class.javaPrimitiveType!!
        val candidates = listOf(
            // Android 14+: packageName, attributionTag, userId, deviceId
            arrayOf(s, s, i, i),
            // Android 12–13: packageName, attributionTag, userId
            arrayOf(s, s, i),
            // Some ROMs: packageName, appLabel, privacyShelf, attributionTag, userId, deviceId, isUserOp
            arrayOf(s, s, s, s, i, i, Boolean::class.javaPrimitiveType!!),
        )
        return candidates.firstNotNullOfOrNull { runCatching { type.getMethod(name, *it) }.getOrNull() }
    }

    private fun call(method: Method): Any? {
        val userId = Process.myUid() / 100_000
        val args: Array<Any?> = when (method.parameterTypes.size) {
            // Background save/restore is not a user "paste"; never fabricate
            // the user-op flag, and leave the privacy stack empty like the
            // framework default.
            7 -> arrayOf(VDisplayShellContext.SHELL_PACKAGE, callerLabel, "", null, userId, 0, false)
            4 -> arrayOf(VDisplayShellContext.SHELL_PACKAGE, null, userId, 0)
            else -> arrayOf(VDisplayShellContext.SHELL_PACKAGE, null, userId)
        }
        return method.invoke(service, *args)
    }

    // ------------------------------------------------------------------
    // Public surface
    // ------------------------------------------------------------------

    /** Read the current clip as plain text; null when absent, unreadable or not text. */
    fun readText(): String? = synchronized(lock) {
        runCatching {
            val clip = primaryClip ?: return null
            if (clip.itemCount <= 0) return null
            val item = clip.getItemAt(0)
            item.text?.toString()
                ?: item.htmlText
                ?: item.coerceToText(context)?.toString()
        }.onFailure { Log.w(TAG, "clipboard read refused: ${it.javaClass.simpleName}") }.getOrNull()
    }

    /**
     * Overwrite the clipboard with [text], remembering the previous content.
     * Returns [WriteResult]. Never replaces a clip whose presence we cannot
     * establish, and never reports success without a successful read-back.
     */
    fun writeText(text: String): Int = synchronized(lock) {
        val mgr = manager ?: return WriteResult.UNAVAILABLE
        return runCatching {
            // A previous paste whose restore was refused must be resolved
            // first: building on top of an unresolved hostage clip is unsafe.
            if (held && !restoreLocked()) return WriteResult.UNREADABLE
            if (!held) {
                val prior = primaryClip
                when (VDisplayClipState.read(prior != null, hasPrimaryClip(), primaryClipDescription != null)) {
                    VDisplayClipState.Read.Unreadable -> return WriteResult.UNREADABLE
                    VDisplayClipState.Read.Empty -> saved = null
                    VDisplayClipState.Read.Has -> saved = prior
                }
                held = true
            }
            val clip = ClipData.newPlainText(CLIP_LABEL, text)
            suppressOverlay(clip)
            markSensitive(clip)
            mgr.setPrimaryClip(clip)
            mine = text
            // setPrimaryClip can be silently declined by the system: never
            // paste (or later restore over) a clipboard we did not actually set.
            if (nowState(text) == VDisplayClipState.Now.Ours) {
                WriteResult.SET
            } else {
                restoreLocked()
                WriteResult.REFUSED
            }
        }.onFailure {
            Log.w(TAG, "clipboard write refused: ${it.javaClass.simpleName}")
            // A write that threw must not leave the user's clip held hostage.
            runCatching { restoreLocked() }
        }.getOrDefault(WriteResult.REFUSED)
    }

    /**
     * Put the user's previous clip back, but only when nobody has replaced
     * our content in the meantime. Safe to call repeatedly; a failed restore
     * keeps the record so a later call can retry.
     */
    fun restore(): Boolean = synchronized(lock) { restoreLocked() }

    /** Must be called under [lock]. Clears state exactly once, on all paths. */
    private fun restoreLocked(): Boolean {
        if (!held) return true
        val mgr = manager ?: return false
        val finished = runCatching {
            val prior = saved
            val now = nowState(mine)
            when (VDisplayClipState.restore(prior == null, now)) {
                VDisplayClipState.Restore.PutBack -> mgr.setPrimaryClip(suppressOverlay(prior!!))
                VDisplayClipState.Restore.Clear -> mgr.clearPrimaryClip()
                VDisplayClipState.Restore.Leave -> Unit
            }
            // The write back may also be silently declined: keep the record
            // when the clip is still ours or has become unreadable, so the
            // next call can retry. Clear it only when it is truly gone or the
            // user replaced it.
            val after = nowState(mine)
            after != VDisplayClipState.Now.Ours && after != VDisplayClipState.Now.Unreadable
        }.onFailure {
            Log.w(TAG, "clipboard restore failed: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
        if (finished) {
            saved = null
            held = false
            mine = null
        }
        return finished
    }

    /** Drop the saved reference without writing (used on teardown). */
    fun forget() = synchronized(lock) {
        saved = null
        held = false
        mine = null
    }

    /** Human-readable current clip metadata for `status`; never includes text. */
    fun describe(): String = runCatching {
        "present=${hasPrimaryClip()} mime=${primaryClipDescription?.getMimeType(0)} heldByUs=$held"
    }.getOrDefault("unreadable")

    private fun suppressOverlay(clip: ClipData): ClipData {
        val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
        extras.putBoolean(EXTRA_SUPPRESS_OVERLAY, true)
        clip.description.extras = extras
        return clip
    }

    private fun markSensitive(clip: ClipData): ClipData {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return clip
        val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
        extras.putBoolean(EXTRA_IS_SENSITIVE, true)
        clip.description.extras = extras
        return clip
    }

    internal object WriteResult {
        const val UNAVAILABLE = 0
        const val SET = 1
        const val REFUSED = 2
        const val UNREADABLE = 3
    }

    companion object {
        const val CLIP_LABEL = "openminis-vdisplay"
        /** Same key AOSP uses: ClipDescription.EXTRA_IS_SENSITIVE (String). */
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
        /** OEM/system "suppress copied overlay" extra used by shell writers. */
        const val EXTRA_SUPPRESS_OVERLAY = "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"
    }
}
