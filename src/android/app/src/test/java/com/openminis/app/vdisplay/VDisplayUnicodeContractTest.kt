package com.openminis.app.vdisplay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for Unicode input and multi-display accessibility support.
 *
 * These are deliberately source-level checks: the privileged Shizuku service
 * and the live AccessibilityService cannot run in a JVM unit test, but the
 * security-critical routing decisions must remain visible in review and in CI.
 */
class VDisplayUnicodeContractTest {

    private fun source(relative: String): String =
        File("src/main/java/com/openminis/app/$relative").readText()

    private fun aidl(): String =
        File("src/main/aidl/com/openminis/app/vdisplay/IVirtualDisplayService.aidl").readText()

    @Test
    fun `clipboard writes happen in the privileged shell service not the app handler`() {
        val clip = source("vdisplay/VDisplayClipboard.kt")
        assertTrue(clip.contains("class VDisplayClipboard"))
        assertTrue(clip.contains("setPrimaryClip"))
        assertTrue(clip.contains("restore"))
        // Never overwrite an unreadable user clip.
        assertTrue(clip.contains("UNREADABLE"))
        // No content logging: a clipboard can hold secrets.
        assertFalse(clip.contains("Log.d(TAG, text"))
        assertFalse(clip.contains("Log.i(TAG, text"))
    }

    @Test
    fun `clipboard write verifies readback and restore clears state on all paths`() {
        val clip = source("vdisplay/VDisplayClipboard.kt")
        // setPrimaryClip can be silently declined: verify by reading back.
        assertTrue(clip.contains("nowState(text) == VDisplayClipState.Now.Ours"))
        assertTrue(clip.contains("WriteResult.REFUSED"))
        // Restore logic must never clear state on a non-local return path:
        // state is cleared in exactly one helper used by every path.
        assertTrue(clip.contains("private fun restoreLocked(): Boolean"))
        assertTrue(clip.contains("if (finished) {"))
        assertTrue(clip.contains("saved = null"))
        // A refused restore keeps the record for the next attempt.
        assertTrue(clip.contains("after != VDisplayClipState.Now.Ours && after != VDisplayClipState.Now.Unreadable"))
    }

    @Test
    fun `clipboard decision table matches mika reference semantics`() {
        // The pure decision table must mirror Mika's ShellClip exactly.
        assertTrue(
            VDisplayClipState.read(clipPresent = true, hasPrimaryClip = true, descriptionPresent = true) ==
                VDisplayClipState.Read.Has,
        )
        assertTrue(
            VDisplayClipState.read(clipPresent = false, hasPrimaryClip = true, descriptionPresent = true) ==
                VDisplayClipState.Read.Unreadable,
        )
        assertTrue(
            VDisplayClipState.read(clipPresent = false, hasPrimaryClip = false, descriptionPresent = false) ==
                VDisplayClipState.Read.Empty,
        )
        // User copied something else meanwhile: leave it alone.
        assertEquals(VDisplayClipState.Restore.Leave, VDisplayClipState.restore(false, VDisplayClipState.Now.Other))
        // Unreadable now: never clear (could hold user data).
        assertEquals(VDisplayClipState.Restore.Leave, VDisplayClipState.restore(false, VDisplayClipState.Now.Unreadable))
        // We still own it and the user had content: put it back.
        assertEquals(VDisplayClipState.Restore.PutBack, VDisplayClipState.restore(false, VDisplayClipState.Now.Ours))
        // We still own it but the user's clip was empty: clear.
        assertEquals(VDisplayClipState.Restore.Clear, VDisplayClipState.restore(true, VDisplayClipState.Now.Ours))
        // Auto-cleared by the system while we had content: restore it.
        assertEquals(VDisplayClipState.Restore.PutBack, VDisplayClipState.restore(false, VDisplayClipState.Now.Empty))
    }

    @Test
    fun `paste is serialized across write inject settle and restore`() {
        val ops = source("vdisplay/VDisplayClipboardOps.kt")
        assertTrue(ops.contains("synchronized(pasteLock) {"))
        assertTrue(ops.contains("clipboardSet(text)"))
        assertTrue(ops.contains("SystemClock.sleep(PASTE_SETTLE_MS)"))
        // Restore runs in the finally block, inside the lock.
        assertTrue(ops.contains("finally"))
        assertTrue(ops.contains("clipboardRestore()"))
    }

    @Test
    fun `ctrl injection pairs every key down with a release`() {
        val ops = source("vdisplay/VDisplayClipboardOps.kt")
        assertTrue(ops.contains("keyCode != VDisplayCtrl.A && keyCode != VDisplayCtrl.V"))
        assertTrue(ops.contains("displayId <= Display.DEFAULT_DISPLAY"))
        assertTrue(ops.contains("META_CTRL_ON"))
        assertTrue(ops.contains("KEYCODE_CTRL_LEFT"))
        // Modifier and letter are both always released, even on failure.
        assertTrue(ops.contains("var ctrlDown = false"))
        assertTrue(ops.contains("var keyDown = false"))
        assertTrue(ops.contains("keyReleased && ctrlReleased"))
        assertTrue(ops.contains("clipboardRestore()"))
    }

    @Test
    fun `aidl keeps existing transaction numbers and appends unicode methods`() {
        val a = aidl()
        assertTrue(a.contains("void attachOwner(IBinder owner) = 13;"))
        assertTrue(a.contains("int clipboardSet(String text) = 14;"))
        assertTrue(a.contains("String clipboardGet() = 15;"))
        assertTrue(a.contains("boolean clipboardRestore() = 16;"))
        assertTrue(a.contains("int injectCtrlKey(int displayId, int keyCode) = 17;"))
        assertTrue(a.contains("Bundle pasteText(int displayId, String text, boolean replaceAll) = 18;"))
        // Stable protocol revision must exist for stale-service rebinding.
        assertTrue(a.contains("int serviceVersion() = 19;"))
    }

    @Test
    fun `manager rejects stale user service version and handles enter on same display`() {
        val mgr = source("vdisplay/VirtualDisplayManager.kt")
        assertTrue(mgr.contains("EXPECTED_SERVICE_VERSION"))
        assertTrue(mgr.contains("it.serviceVersion()"))
        assertTrue(mgr.contains("stale UserService"))
        // paste(..., pressEnter) sends ENTER inside the same withService id.
        assertTrue(mgr.contains("fun paste(text: String, replaceAll: Boolean = true, pressEnter: Boolean = false): Result"))
        assertTrue(mgr.contains("svc.injectKey(id, KeyEvent.KEYCODE_ENTER)"))
        // The transport log must not embed a binder payload into the message.
        assertTrue(mgr.contains("\"service call failed: \${it.javaClass.simpleName}\""))
    }

    @Test
    fun `accessibility uses windowsOnAllDisplays only for named displays`() {
        val svc = source("accessibility/MinisAccessibilityService.kt")
        assertTrue(svc.contains("windowsOnAllDisplays"))
        assertTrue(svc.contains("fun rootNodes(displayId: Int? = null)"))
        assertTrue(svc.contains("fun windowInfos(displayId: Int? = null)"))
        // The active-window fallback must not answer for another display.
        assertTrue(svc.contains("out.isEmpty() && displayId == null"))
        assertTrue(svc.contains("builder.setDisplayId(displayId)"))
        assertTrue(svc.contains("ACTION_SET_TEXT"))
    }

    @Test
    fun `a11y CLI forwards display through the strict resolver`() {
        val handler = source("sandbox/offload/AccessibilityOffloadHandler.kt")
        assertTrue(handler.contains("private fun resolveDisplay(args: OffloadArgs): Int?"))
        assertTrue(handler.contains("svc.rootNodes(resolveDisplay(args))"))
        assertTrue(handler.contains("input text <s> [--node id] [--display <id>]"))
        assertTrue(handler.contains("ACTION_SET_TEXT"))
        assertTrue(handler.contains("resolveDisplay(args) ?: Display.DEFAULT_DISPLAY"))
        assertTrue(handler.contains("nodeForDisplay(svc, nodeId, resolveDisplay(args))"))
    }
}
