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

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.util.Log
import android.view.Surface
import androidx.annotation.Keep
import com.openminis.app.BuildConfig
import kotlin.system.exitProcess

/**
 * Shizuku UserService entry point. Shizuku instantiates this class by
 * reflection inside the privileged UserService process (running under the
 * shell uid 2000 when started from adb/root Shizuku); the two @Keep
 * constructors cover both the Context-taking and no-arg instantiation paths.
 *
 * Authorization boundary: only the Minis uid resolved at construction time
 * may invoke interface methods ([onTransact] enforces it). The owner token
 * registered via attachOwner ties the service lifetime to the Minis process:
 * when Minis dies, all displays are released and the process exits, so no
 * orphan virtual display is left behind.
 */
@Keep
class VDisplayUserService : IVirtualDisplayService.Stub {

    private val allowedUid: Int
    private val server: VDisplayShellServer

    @Volatile private var ownerToken: IBinder? = null
    private val ownerDeathRecipient = IBinder.DeathRecipient { onOwnerDied() }

    @Keep
    constructor(context: Context) : super() {
        ensureMainLooper()
        allowedUid = resolveUid(context)
        server = VDisplayShellServer(allowedUid = allowedUid)
        Log.i(TAG, "VDisplayUserService created, allowedUid=$allowedUid")
    }

    @Keep
    constructor() : super() {
        ensureMainLooper()
        allowedUid = resolveUid(null)
        server = VDisplayShellServer(allowedUid = allowedUid)
        Log.i(TAG, "VDisplayUserService created (no-arg), allowedUid=$allowedUid")
    }

    // ------------------------------------------------------------------
    // IVirtualDisplayService
    // ------------------------------------------------------------------

    // NOTE: allowUntrustedDowngrade is part of the AIDL contract; the core never
    // sets it on its own, so an untrusted display is only ever created when the
    // Minis process explicitly asks for one.
    override fun createDisplay(
        surface: Surface,
        width: Int,
        height: Int,
        dpi: Int,
        allowUntrustedDowngrade: Boolean,
    ): Int = server.createDisplay(surface, width, height, dpi, allowUntrustedDowngrade)

    override fun isDisplayTrusted(displayId: Int): Boolean = server.isDisplayTrusted(displayId)

    override fun releaseDisplay(displayId: Int) = server.releaseDisplay(displayId)

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean =
        server.injectTapChecked(displayId, x, y)

    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean =
        server.injectStrokeChecked(displayId, xs, ys, times)

    override fun injectKey(displayId: Int, keyCode: Int): Boolean =
        server.injectKeyChecked(displayId, keyCode)

    override fun injectAsciiText(displayId: Int, text: String): Int =
        server.injectAsciiTextChecked(displayId, text)

    override fun startApp(displayId: Int, packageName: String): Int =
        server.startAppChecked(displayId, packageName)

    override fun topActivity(displayId: Int): String? = server.topActivityChecked(displayId)

    override fun identity(): IntArray = server.identity()

    override fun displayInfo(displayId: Int): Bundle? = server.displayInfo(displayId)

    override fun releaseAll() = server.releaseAll()

    override fun attachOwner(owner: IBinder) {
        val previous = ownerToken
        if (previous != null && previous !== owner) {
            runCatching { previous.unlinkToDeath(ownerDeathRecipient, 0) }
        }
        ownerToken = owner
        runCatching { owner.linkToDeath(ownerDeathRecipient, 0) }
            .onFailure {
                // The owner is already dead — clean up immediately.
                Log.w(TAG, "owner already dead at attach; releasing")
                onOwnerDied()
            }
    }

    // ------------------------------------------------------------------
    // Unicode clipboard / Ctrl+paste (interface v2). These execute in the
    // privileged (shell) process, which is exactly why they can write the
    // clipboard while Minis is in the background.
    // ------------------------------------------------------------------

    override fun clipboardSet(text: String): Int = server.clipboardSet(text)

    override fun clipboardGet(): String? = server.clipboardGet()

    override fun clipboardRestore(): Boolean = server.clipboardRestore()

    override fun injectCtrlKey(displayId: Int, keyCode: Int): Int =
        server.injectCtrlKeyChecked(displayId, keyCode)

    override fun pasteText(displayId: Int, text: String, replaceAll: Boolean): Bundle =
        server.pasteTextChecked(displayId, text, replaceAll)

    override fun serviceVersion(): Int = SERVICE_VERSION

    override fun destroy() {
        runCatching { server.releaseAll() }
        exitProcess(0)
    }

    private fun onOwnerDied() {
        Log.w(TAG, "owner process died; releasing displays and exiting")
        runCatching { server.releaseAll() }
        exitProcess(0)
    }

    /**
     * Authorization boundary: interface transactions are only served to the
     * Minis uid. The destroy transaction additionally accepts the service's
     * own uid and root (Shizuku teardown paths).
     */
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // The AIDL declares `destroy() = 16777114`, so the wire code IS
        // DESTROY_RAW_TRANSACTION (AIDL does not add FIRST_CALL_TRANSACTION to
        // an explicitly numbered method).
        if (code == DESTROY_RAW_TRANSACTION) {
            val caller = Binder.getCallingUid()
            if (caller != allowedUid && caller != Process.myUid() && caller != VDisplayShellContext.SHELL_UID && caller != 0) {
                throw SecurityException("caller $caller is not allowed")
            }
            runCatching { server.releaseAll() }
            reply?.writeNoException()
            exitProcess(0)
        }
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION && Binder.getCallingUid() != allowedUid) {
            throw SecurityException("caller ${Binder.getCallingUid()} is not allowed")
        }
        return super.onTransact(code, data, reply, flags)
    }

    private companion object {
        const val TAG = "OpenMinisVDisplay"
        const val DESTROY_RAW_TRANSACTION = 16777114
        /** Wire revision; bump on any AIDL method-surface change. */
        const val SERVICE_VERSION = 2

        /** UserService processes may lack a main looper; prepare one if needed. */
        fun ensureMainLooper() {
            if (Looper.getMainLooper() == null) {
                @Suppress("DEPRECATION")
                Looper.prepareMainLooper()
            }
        }

        /**
         * Resolve the Minis uid: prefer the Context Shizuku passes (its
         * package must match ours); otherwise ask the package manager from a
         * shell context. Never accept the shell or system uid as "owner".
         */
        fun resolveUid(context: Context?): Int {
            val fromContext = runCatching {
                context?.takeIf { it.packageName == BuildConfig.APPLICATION_ID }?.applicationInfo?.uid
            }.getOrNull()
            if (fromContext != null && fromContext > 0 &&
                fromContext != VDisplayShellContext.SHELL_UID && fromContext != Process.SYSTEM_UID
            ) {
                return fromContext
            }
            val pm = runCatching { context?.packageManager }.getOrNull()
                ?: VDisplayShellContext.create().packageManager
            return pm.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
        }
    }
}
