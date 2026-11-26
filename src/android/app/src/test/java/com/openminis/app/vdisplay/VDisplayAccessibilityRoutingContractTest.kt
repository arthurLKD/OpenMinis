package com.openminis.app.vdisplay

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level contract for fail-closed accessibility display routing.
 *
 * The live AccessibilityService and a virtual display cannot exist in a JVM
 * unit test, so the routing decisions that keep the physical screen out of
 * reach are pinned in source and executed by CI.
 */
class VDisplayAccessibilityRoutingContractTest {

    private fun source(relative: String): String {
        val file = File("src/main/java/com/openminis/app/$relative")
        assertTrue("missing ${file.absolutePath}", file.exists())
        return file.readText()
    }

    /** Comments must not satisfy a behavioural assertion. */
    private fun code(text: String): String = text.lineSequence()
        .filterNot { line ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    private val service: String by lazy { code(source("accessibility/MinisAccessibilityService.kt")) }
    private val handler: String by lazy { code(source("sandbox/offload/AccessibilityOffloadHandler.kt")) }

    private fun body(text: String, start: String, end: String): String =
        text.substringAfter(start).substringBefore(end)

    @Test
    fun `explicit display values fail closed`() {
        assertTrue(handler.contains("private fun resolveDisplay(args: OffloadArgs): Int?"))
        assertTrue(handler.contains("\"--display requires a display id (got no value)\""))
        assertTrue(handler.contains("--display must be an integer display id"))
        assertTrue(handler.contains("if (id < 0)"))
        assertTrue(handler.contains("Refusing to fall back to the default display."))
        assertTrue(handler.contains("throw ArgFailure(\"NOT_SUPPORTED\""))
        // The failure must reach the caller as a typed envelope, not INTERNAL.
        assertTrue(handler.contains("} catch (e: ArgFailure) {"))
    }

    @Test
    fun `api below 30 never answers a named display from the default display`() {
        val windowsFor = body(service, "private fun windowsFor(", "fun rootNodes(")
        assertTrue(windowsFor.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.R"))
        assertTrue(windowsFor.contains("return emptyList()"))
        assertTrue(windowsFor.contains("refusing to fall back to the default display"))
    }

    @Test
    fun `no display keeps the legacy default-display window behaviour`() {
        val windowInfos = body(service, "fun windowInfos(", "private fun windowInfoMap(")
        assertFalse(
            "windowInfos(null) must not enumerate all displays",
            windowInfos.contains("windowsOnAllDisplays"),
        )
        assertTrue(windowInfos.contains("windowsFor(null)"))
        assertTrue(windowInfos.contains("Display.DEFAULT_DISPLAY"))
    }

    @Test
    fun `simple gesture and pinch explicitly set the display id`() {
        assertTrue(service.contains("private fun buildGesture("))
        assertTrue(service.contains("builder.setDisplayId(displayId)"))
        assertTrue(service.contains("fun dispatchSimpleGestureOutcome("))
        assertTrue(service.contains("fun dispatchPinchOutcome("))
        assertTrue(
            handler.contains("svc.dispatchPinchOutcome(cx, cy, scale, duration, displayId = resolveDisplay(args))"),
        )
    }

    @Test
    fun `a node id and a display must agree`() {
        assertTrue(service.contains("fun nodeDisplayId(node: AccessibilityNodeInfo?): Int?"))
        assertTrue(handler.contains("private fun nodeForDisplay("))
        assertTrue(handler.contains("NODE_DISPLAY_MISMATCH"))
        assertTrue(handler.contains("nodeForDisplay(svc, nodeId, resolveDisplay(args))"))
    }

    @Test
    fun `tree signature and foreground app use the same display`() {
        assertTrue(
            handler.contains("private fun treeSignature(svc: MinisAccessibilityService, displayId: Int?): Int"),
        )
        assertTrue(handler.contains("treeSignature(svc, displayId)"))
        assertTrue(handler.contains("svc.foregroundPackage(displayId)"))
        assertTrue(handler.contains("svc.foregroundPackage()"))
    }

    @Test
    fun `global input keys are refused with an explicit display`() {
        val inputKey = body(handler, "private fun inputKey(", "private fun resolveTargetEditable(")
        assertTrue(inputKey.contains("args.get(\"display\") != null || args.hasFlag(\"display\")"))
        assertTrue(inputKey.contains("DISPLAY_UNSUPPORTED"))
        assertTrue(inputKey.contains("device-scoped"))
    }
}
