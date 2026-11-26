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

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-vdisplay-floating] Source-level contract for the global floating virtual
 * display overlay. WindowManager / TYPE_APPLICATION_OVERLAY cannot run on the
 * JVM, so safety and lifecycle invariants are pinned against comment-stripped
 * source and executed in CI (`*VDisplay*`).
 */
class VDisplayFloatingOverlayContractTest {

    private fun src(relative: String): String {
        val f = File("src/main/java/com/openminis/app/$relative")
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private fun code(text: String): String = text.lineSequence()
        .filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    private val controller by lazy { code(src("ui/vdisplay/VDisplayFloatingOverlayController.kt")) }
    private val dragHost by lazy { code(src("ui/vdisplay/VDisplayOverlayDragFrame.kt")) }
    private val owners by lazy { code(src("ui/vdisplay/VDisplayOverlayOwners.kt")) }
    private val card by lazy { code(src("ui/vdisplay/VDisplayFloatingCard.kt")) }
    private val screen by lazy { code(src("ui/vdisplay/VDisplayScreen.kt")) }
    private val app by lazy { code(src("MinisApp.kt")) }

    @Test
    fun `closing the floating window never stops or releases the virtual display`() {
        val onCloseBody = controller.substringAfter("override fun onClose()")
            .substringBefore("override fun onPinch")
        assertTrue(onCloseBody.contains("closedForDisplayIdFlow.value = runningDisplayId()"))
        assertFalse(controller.contains("VirtualDisplayManager.stop("))
        assertFalse(controller.contains("releaseDisplay"))
    }

    @Test
    fun `overlay uses TYPE_APPLICATION_OVERLAY and gates on canDrawOverlays`() {
        assertTrue(controller.contains("TYPE_APPLICATION_OVERLAY"))
        assertTrue(controller.contains("Settings.canDrawOverlays(ctx)"))
        assertTrue(controller.contains("FLAG_NOT_FOCUSABLE"))
        assertTrue(controller.contains("FLAG_NOT_TOUCH_MODAL"))
    }

    @Test
    fun `owners are installed before addView and destroyed on failure or hide`() {
        val showBody = controller.substringAfter("private fun showGlobalOverlay()")
            .substringBefore("private fun hideGlobalOverlay")
        val installIdx = showBody.indexOf("owners.install(host)")
        val addIdx = showBody.indexOf("wm.addView(host, params)")
        assertTrue("owners.install must run before wm.addView", installIdx in 0 until addIdx)
        assertTrue("addView failure must destroy owners", showBody.contains("owners.destroy()"))
        val hideBody = controller.substringAfter("private fun hideGlobalOverlay")
            .substringBefore("private fun updateTouchHostMode")
        assertTrue(hideBody.contains("owners?.destroy()"))
        assertTrue(owners.contains("Lifecycle.Event.ON_DESTROY"))
        assertTrue(owners.contains("store.clear()"))
    }

    @Test
    fun `compose-bound bitmaps are never manually recycled`() {
        assertFalse(
            "manual Bitmap.recycle() races Compose ImageBitmap rendering",
            controller.contains(".recycle()"),
        )
        assertFalse(card.contains(".recycle()"))
    }

    @Test
    fun `expanded mode clamps position rather than snapping to a corner`() {
        val snapBody = controller.substringAfter("private fun snapOrClamp(")
            .substringBefore("private fun applyScale")
        val expandedBranch = snapBody.substringAfter("if (expanded) {").substringBefore("} else {")
        assertTrue(expandedBranch.contains("VDisplayFloatingMath.clampOrigin"))
        assertFalse(expandedBranch.contains("cornerOrigin"))
    }

    @Test
    fun `overlay input never supplies a displayId and routes through PreviewTouchMapper`() {
        assertTrue(card.contains("PreviewTouchMapper.map("))
        assertTrue(controller.contains("VirtualDisplayManager.tap(x, y)"))
        assertTrue(controller.contains("VirtualDisplayManager.swipe(x1, y1, x2, y2, durationMs)"))
        assertTrue(controller.contains("VirtualDisplayManager.key(KeyEvent.KEYCODE_BACK)"))
    }

    @Test
    fun `drag host uses raw screen coordinates and recycles temporary MotionEvents`() {
        assertTrue(dragHost.contains("ev.rawX"))
        assertTrue(dragHost.contains("ev.rawY"))
        assertTrue(dragHost.contains("copy.recycle()"))
    }

    @Test
    fun `app initializes controller after backgroundSettingsRepository and suppresses duplicate overlay`() {
        val repoInit = app.indexOf("backgroundSettingsRepository = BackgroundSettingsRepository(")
        val controllerInit = app.indexOf("VDisplayFloatingOverlayController.init(this)")
        assertTrue("missing backgroundSettingsRepository init", repoInit >= 0)
        assertTrue("missing VDisplayFloatingOverlayController.init", controllerInit >= 0)
        assertTrue(
            "VDisplayFloatingOverlayController.init must run after backgroundSettingsRepository is initialized",
            controllerInit > repoInit,
        )
        assertTrue(screen.contains("VDisplayFloatingOverlayController.setOnPreviewScreen(true)"))
        assertTrue(screen.contains("VDisplayFloatingOverlayController.setOnPreviewScreen(false)"))
    }
}
