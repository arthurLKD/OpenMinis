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
 * Portions of the implementation behind this interface are adapted from
 * Mika (https://github.com/wangzan101/mika, Apache-2.0), which itself
 * adapts scrcpy (Apache-2.0). See NOTICE in the repository root.
 */
package com.openminis.app.vdisplay;

import android.view.Surface;
import android.os.Bundle;
import android.os.IBinder;

/**
 * Privileged virtual-display service. Runs in the Shizuku UserService process
 * under the shell identity (uid 2000), because only a shell-context process
 * can create a VirtualDisplay that hosts OTHER apps' activities in the
 * background and inject input targeted at it.
 *
 * Security contract (enforced in onTransact and re-validated per display):
 *   - only the Minis uid (resolved at construction) may call these methods;
 *   - every input/launch method requires displayId > 0 AND a display created
 *     by THIS service; display 0 (the physical screen) and foreign ids are
 *     refused before any lookup, and nothing ever falls back to the physical
 *     display or to the global input focus;
 *   - device-level keys (HOME, APP_SWITCH/recents, volume, power, media) are
 *     never injected, so no call can navigate the user's real device;
 *   - createDisplay never downgrades on its own: an untrusted display is only
 *     created when the caller explicitly passes allowUntrustedDowngrade.
 */
interface IVirtualDisplayService {
    /**
     * Create a virtual display rendering into [surface] (an ImageReader-owned
     * offscreen Surface held by the Minis process, which keeps the display
     * alive). width/height must be 1..4096, dpi 72..1000 (validated here).
     *
     * Focus policy (fail closed):
     *   - trusted flags (TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED |
     *     OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED) are tried first;
     *   - if this platform cannot express an own-focus display, or the ROM
     *     refuses the trusted flags, the call returns CREATE_TRUSTED_REFUSED
     *     and creates NOTHING when [allowUntrustedDowngrade] is false. A
     *     display without OWN_FOCUS may share or take the main screen's focus,
     *     so it is never created implicitly;
     *   - when [allowUntrustedDowngrade] is true the display is rebuilt
     *     without the trusted-only flags and isDisplayTrusted() reports false,
     *     which the UI must surface as "focus shared with the main screen".
     *
     * @return the displayId (> 0), CREATE_FAILED (-1), or
     *         CREATE_TRUSTED_REFUSED (-2) when no independent-focus display
     *         could be created and no downgrade was allowed.
     */
    int createDisplay(in Surface surface, int width, int height, int dpi, boolean allowUntrustedDowngrade) = 1;

    /** Whether displayId was created with trusted flags (own focus group). False for display 0 / foreign ids. */
    boolean isDisplayTrusted(int displayId) = 2;

    /** Release one display created by this service. No-op for foreign ids and display 0. */
    void releaseDisplay(int displayId) = 3;

    /**
     * Tap at logical coordinates of the given display. Bounds-checked against
     * the display's current logical size. Returns false for foreign displays,
     * out-of-bounds coordinates or injection failure.
     */
    boolean injectTap(int displayId, int x, int y) = 4;

    /**
     * One-finger stroke: down at (xs[0],ys[0]), move through the points at
     * relative times[i] ms, up at the last point. 2..1024 points, times[0]==0,
     * monotonic, total <= 10000 ms. Blocks until injected (bounded by the
     * total duration). Returns false when the down event could not be injected.
     */
    boolean injectStroke(int displayId, in int[] xs, in int[] ys, in long[] times) = 5;

    /**
     * Press a key on the display. Whitelist-only (navigation/editing keys);
     * whole-device keys (volume, power, real HOME, recents, ...) are refused.
     * Injection is targeted at the display's top task uid when possible so it
     * never lands on the main screen's focus.
     */
    boolean injectKey(int displayId, int keyCode) = 6;

    /**
     * Type printable ASCII text (' '..'~', 1..1000 chars) as key events aimed
     * at the display's current top task. Returns 0=not sent, 1=dispatched,
     * 2=unconfirmed (a prefix may have landed; do not blindly retry).
     */
    int injectAsciiText(int displayId, String text) = 7;

    /**
     * Launch a package's launcher activity onto the display.
     * Returns 0=ok, 1=refused because the user is actively using the package
     * on the MAIN screen (never move the user's live task), 2=no launcher
     * entry, 3=error.
     */
    int startApp(int displayId, String packageName) = 8;

    /** Top activity class name on the display, or null when it has no task. */
    String topActivity(int displayId) = 9;

    /** [uid, pid] of this service process (diagnostics). */
    int[] identity() = 10;

    /**
     * Live geometry of the display: keys "rotation" (Surface.ROTATION_*),
     * "width"/"height" (current logical size). Null for foreign displays.
     */
    Bundle displayInfo(int displayId) = 11;

    /** Release every display and stop keep-awake; the process keeps running. */
    void releaseAll() = 12;

    /**
     * Register the client's death token: when the owning Minis process dies,
     * the service releases all displays and exits, so no orphan virtual
     * display survives its owner. Replaces any previous token.
     */
    void attachOwner(IBinder owner) = 13;

    // ------------------------------------------------------------------
    // Unicode text entry (added in v2 of this interface; the numbered methods
    // above are wire-compatible, so an older client simply never calls these).
    // ------------------------------------------------------------------

    /**
     * Write [text] into the SYSTEM clipboard from the shell identity. This is
     * deliberately NOT exposed through `android-clipboard`: an app that is not
     * in the foreground is refused clipboard writes since Android 10, whereas
     * this process is the shell. [text] may contain any Unicode, up to 20000
     * chars. Returns 0=set, 1=unavailable, 2=refused, 3=previous clip unreadable
     * (nothing was overwritten).
     */
    int clipboardSet(String text) = 14;

    /** Current clipboard text, or null when empty/unreadable/non-text. */
    String clipboardGet() = 15;

    /** Put the previous user clip back if we still own the current one. */
    boolean clipboardRestore() = 16;

    /**
     * Inject Ctrl+A (keyCode 29) or Ctrl+V (keyCode 50) on our display, with
     * the modifier released afterwards. No other key is accepted, and the
     * display must be one this service created.
     * Returns 0=refused, 1=dispatched, 2=unconfirmed.
     */
    int injectCtrlKey(int displayId, int keyCode) = 17;

    /**
     * One-shot Unicode text entry: shell-side clipboard write, optional
     * Ctrl+A, Ctrl+V, short settle, then restore the user's clip. Result keys:
     * "sent" (0/1/2), "restored" (boolean), "clip" (optional failure code).
     * The caller must capture the display afterwards to verify the field.
     */
    Bundle pasteText(int displayId, String text, boolean replaceAll) = 18;

    /**
     * Stable wire-protocol revision of this interface. The Shizuku binder
     * is reused across Minis upgrades, so a client must never talk to a
     * stale UserService built from an older AIDL. Bump this when the method
     * surface changes; clients refuse a service whose revision differs.
     */
    int serviceVersion() = 19;

    /** Release everything and exit the UserService process. */
    void destroy() = 16777114;
}
