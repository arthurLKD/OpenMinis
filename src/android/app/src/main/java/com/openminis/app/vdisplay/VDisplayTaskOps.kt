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
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.Display
import com.openminis.app.vdisplay.VDisplayShellServer.Companion.MAX_TASKS
import com.openminis.app.vdisplay.VDisplayShellServer.Companion.TAG
import com.openminis.app.vdisplay.VDisplayShellServer.Companion.USER_CURRENT

// ---------------------------------------------------------------------------
// Task inspection + launch-on-display. Launching is restricted to displays
// this server created, and refuses packages the user is actively using on
// the main screen (never move a live task out from under the user).
// ---------------------------------------------------------------------------

internal val TASK_INFO: Class<*> by lazy { Class.forName("android.app.TaskInfo") }

/** Launch result codes, mirroring IVirtualDisplayService.startApp. */
internal object VLaunch {
    const val OK = 0
    const val IN_USE_ON_MAIN = 1
    const val NOT_FOUND = 2
    const val ERROR = 3
}

/**
 * Launch [packageName]'s launcher activity onto [displayId].
 *
 * Order of checks:
 *  1. the display must be one of ours (foreign ids, including the main
 *     display, are refused — never launch onto the user's screen);
 *  2. if the user is visibly using the package on the MAIN screen, refuse
 *     (IN_USE_ON_MAIN): a NEW_TASK launch would pull the user's live task
 *     away or interrupt it;
 *  3. resolve the launcher entry; none -> NOT_FOUND;
 *  4. startActivityAsUser as the shell package with setLaunchDisplayId,
 *     falling back to `am start --display` when reflection is unavailable.
 */
internal fun VDisplayShellServer.startAppChecked(displayId: Int, packageName: String): Int = synchronized(launchLock) {
    if (!ownDisplay(displayId)) return VLaunch.ERROR
    if (packageName.isBlank() || packageName.length > 256) return VLaunch.ERROR
    try {
        if (inUseOnMainScreen(packageName)) {
            Log.i(TAG, "startApp($packageName) refused: in use on main screen")
            return VLaunch.IN_USE_ON_MAIN
        }
    } catch (t: Throwable) {
        // When the main screen cannot be inspected, refuse rather than risk disturbing the user.
        Log.e(TAG, "cannot inspect main-screen tasks", t)
        return VLaunch.ERROR
    }
    val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return VLaunch.NOT_FOUND
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
    val ok = try {
        val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
        // (caller, callingPackage, callingFeatureId, intent, resolvedType, resultTo, resultWho,
        //  requestCode, flags, profilerInfo, options, userId)
        val result = start.invoke(
            taskManager, null, VDisplayShellContext.SHELL_PACKAGE, null, intent, null, null, null,
            0, 0, null, options, USER_CURRENT,
        ) as Int
        if (result < 0) Log.w(TAG, "startActivityAsUser($packageName) -> $result")
        result >= 0
    } catch (t: Throwable) {
        Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
        val component = intent.component?.flattenToShortString() ?: return VLaunch.NOT_FOUND
        exec("am", "start", "--display", "$displayId", "-n", component) == 0
    }
    if (ok) VLaunch.OK else VLaunch.ERROR
}

/** Top activity class name on one of our displays; null otherwise. */
internal fun VDisplayShellServer.topActivityChecked(displayId: Int): String? = try {
    if (!ownDisplay(displayId)) null else tasksOn(displayId).firstOrNull()?.let {
        (TASK_INFO.getField("topActivity").get(it) as? ComponentName)?.className
    }
} catch (t: Throwable) {
    Log.e(TAG, "topActivity failed", t)
    null
}

/** The package has a VISIBLE task on the main screen, i.e. the user is actively using it. */
internal fun VDisplayShellServer.inUseOnMainScreen(packageName: String): Boolean {
    val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
    return tasksOn(Display.DEFAULT_DISPLAY).withIndex().any { (index, task) ->
        packageName in packagesOf(task) && (visibleField?.getBoolean(task) ?: (index == 0))
    }
}

/**
 * IActivityTaskManager.getTasks: ordered by recency; shell holds REAL_GET_TASKS
 * and sees every app's tasks. API 33+ has a displayId overload — prefer it;
 * earlier versions fetch all and filter by TaskInfo.displayId.
 */
internal fun VDisplayShellServer.tasksOn(displayId: Int): List<Any> {
    val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
    val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
    val tasks = if (withDisplay != null) {
        withDisplay.invoke(taskManager, MAX_TASKS, false, false, displayId)
    } else {
        methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
    } as List<*>
    val displayField = TASK_INFO.getField("displayId")
    return tasks.filterNotNull().filter { displayField.getInt(it) == displayId }
}

internal fun VDisplayShellServer.packagesOf(task: Any): List<String> = listOf("baseActivity", "topActivity").mapNotNull {
    (TASK_INFO.getField(it).get(task) as? ComponentName)?.packageName
}

/**
 * Uid of the top task on the display (the target for aimed injection); null
 * when it cannot be resolved (callers then use display-scoped injection).
 */
internal fun VDisplayShellServer.topTaskUid(displayId: Int): Int? = try {
    val task = tasksOn(displayId).firstOrNull() ?: return null
    val top = TASK_INFO.getField("topActivity").get(task) as? ComponentName
    val packageName = top?.packageName ?: return null
    val userId = TASK_INFO.getField("userId").getInt(task)
    if (Build.VERSION.SDK_INT < 33) null else {
        val pm = context.packageManager
        (pm.javaClass.getMethod("getPackageUidAsUser", String::class.java, Int::class.java, Int::class.java)
            .invoke(pm, packageName, 0, userId) as Int).takeIf { it >= 0 }
    }
} catch (t: Throwable) {
    Log.w(TAG, "resolve top task uid on display $displayId failed", t)
    null
}
