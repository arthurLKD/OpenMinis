# Background virtual display development

Branch: `feature/vdisplay-support`  
Base: `feature/openui-support` at `408dd50`  
Reference: <https://github.com/wangzan101/Mika>

## Scope

Add a Shizuku-backed Android virtual display for running and controlling applications without using the physical screen. Preserve the existing OpenUI renderer and Release/R8 preview configuration. This is not a physical-screen mirror, an overlay, or a MediaProjection recording session.

Implementation is in progress. This document does **not** establish successful compilation or device compatibility. Concrete APIs, commands and validation evidence must be filled in after implementation and review.

## Security requirements

- Virtual display operations must be restricted to displays created and owned by the privileged service. Display 0 and arbitrary display IDs are not accepted.
- Input failures must never fall back to the physical display.
- Agent access has a separate, default-denied `vdisplay` permission. Shizuku authorization alone does not authorize agent use.
- Do not automatically unlock the device, disable keyguard, override secure content protection, or capture protected application windows.
- Do not silently migrate an application the user is currently using on the main display.
- Unsupported independent-focus configurations must produce an actionable error rather than silently steal focus.
- Screenshots remain local; no image contents or text-entry payloads go into logs.
- Permission revocation, binder death, creation failure and explicit stop must release readers, surfaces, display handles and callbacks.

## Compatibility boundary

Privileged display flags, display-specific input, independent focus and per-display wake activity depend on Android version and ROM. Some apps disallow secondary displays, use protected surfaces or open activities on the physical screen. Background execution is not an unconditional availability guarantee: force-stop, Shizuku termination and OS process eviction can stop the feature.

## Current implementation and usage

- Core: `src/android/app/src/main/java/com/openminis/app/vdisplay/`; Binder contract: `src/android/app/src/main/aidl/com/openminis/app/vdisplay/IVirtualDisplayService.aidl`.
- The Shizuku UserService is a Binder stub instantiated by Shizuku, not an Android `Service`; it does not require an ordinary Manifest service declaration.
- UI: Settings → Background display (`minis://settings/vdisplay`). Leaving the page stops the inline preview refresh, not the owned display. Preview defaults to 2 fps.
- Floating preview window (`VDisplayFloatingOverlayController`): while a virtual display is running, a draggable live floating window appears across Minis and other apps via `TYPE_APPLICATION_OVERLAY` (requires `SYSTEM_ALERT_WINDOW`; falls back to an in-app overlay inside `AppNavigation` when overlay permission is not yet granted, and automatically hides while the inline preview page is open). Supports mini/expanded toggle, `−`/`+`/`↺` and two-finger pinch zoom (`0.65×`–`2.40×`), optional Watch vs Touch direct-control mode (`PreviewTouchMapper` + `Back`), and closing (`×`) without affecting the virtual display. Toggleable and re-openable in Settings → Background display.
- Agent CLI: `android-vdisplay`. Its separate `vdisplay` permission defaults to denied, independently of Shizuku authorization.
- Default start requires Android 14/API 34+ independent-focus flags. Refused trusted creation fails closed; UI/CLI do not enable untrusted downgrade.
- `android-vdisplay text` remains printable-ASCII-only (256 characters at the CLI). The new explicit `type` / `paste` command carries Unicode through the Shizuku service's clipboard and targeted Ctrl+V; it is not an automatic fallback. `--append` avoids Ctrl+A; `--enter` can submit the field, so omit it during verification.
- `android-a11y-cli ui dump/find/info --display <id>` selects a display's accessibility windows on Android 11+. `input text ... --display <id>` uses the focused editable node and `ACTION_SET_TEXT`, which supports Unicode without touching the clipboard. Custom editors may not expose editable nodes.
- Clipboard-based entry temporarily changes the **system-wide clipboard**, backs up and restores the original when still owned, and must not overwrite a newer user clip. Target apps, keyboard services or clipboard-history tools may observe the temporary payload. Do not use this route for passwords, tokens or payment fields. Prefer accessibility node input when available.
- Successful injection is not proof the target editor accepted the text. Always inspect the target tree or screenshot before retrying, and never automatically retry partial/unconfirmed input.
- HOME, RECENTS, power and volume are not background-screen controls and are rejected.
- Screenshots resolve against the calling session's attachments mount. File creation uses `CREATE_NEW`; paths are canonical-containment checked. These checks are not a claim of resistance to a malicious process concurrently swapping parent symlinks.

```sh
android-vdisplay help
android-vdisplay status
android-vdisplay start --width 720 --height 1280 --dpi 320
android-vdisplay launch com.example.app
android-vdisplay screenshot --output shot.png
android-vdisplay tap 360 640
android-vdisplay key BACK
android-vdisplay text 'hello' --enter
android-vdisplay stop
```

`stop` destroys the virtual display and can terminate tasks on it. Only use it when that is intended. Generated screenshots are under `/var/minis/attachments/` in the calling session; no network ports are opened by the display feature.

## Build and release

`.github/workflows/build-android.yml` builds `feature/vdisplay-support` with `:app:assembleRelease` and publishes separately named `MinisApp-VDisplay-preview-arm64-v8a.apk` / `vdisplay-preview-*` artifacts with application ID `com.openminis.app.preview.vdisplay` (app name: `Minis (VDisplay)`), allowing co-existence with both official Minis and the OpenUI preview build. Verify signing certificate compatibility before installation and back up important data.

The workflow runs the focused tests and the release assembly as two steps with their own time limits (20 and 35 minutes inside a 60-minute job). Test reports are uploaded on every outcome, so a failure leaves XML/HTML behind. Before publishing, the workflow runs `apksigner verify`, checks the exact application ID, rejects debuggable APKs, checks arm64-v8a-only native code, and records SHA-256.

```sh
git diff --check
cd src/android
# The project has a `distribution` flavor dimension, so the flavor-free
# `testReleaseUnitTest` is ambiguous; name the variant explicitly.
# `--tests` is a per-task option: it binds only to the task name immediately
# before it, so never place another task name between the task and its filter.
./gradlew :app:testGithubReleaseUnitTest --tests '*VDisplay*' --stacktrace
python3 ../../scripts/verify_vdisplay_test_results.py app/build/test-results/testGithubReleaseUnitTest
./gradlew :app:assembleRelease --stacktrace
```

Local sandbox doctor currently reports a missing JDK/SDK toolchain. Windows SSH probe closed the connection; Deepin has Java but the probed `/opt/android-sdk` is absent. CI is the existing full native dependency build route; a successful APK build is still separate from device-level validation.

## Required acceptance checks

1. Release build, focused unit tests and APK signature/package checks.
2. Shizuku unavailable, unauthorized, disconnected and reconnected paths.
3. Create/stop/recreate, invalid dimensions and repeated concurrent requests.
4. Live preview, screenshot freshness, row-stride correctness and bitmap ownership.
5. Tap/swipe/key/text act exclusively on the owned display; no physical-screen fallback.
6. Launch respects a package already active on the physical screen.
7. Leave the preview page while agent operations continue; no preview-loop or image-reader leak.
8. Physical-screen on/off and focus isolation, reporting ROM-specific failures honestly.
9. Existing OpenUI rendering remains functional.

## Unicode and multi-display acceptance (current change)

CI evidence (run 22, commit `d5f78af`): 103 focused VDisplay unit tests passed (7 suites, 0 failed/skipped), Release/R8 assembly succeeded, and the workflow verified the APK signature (`apksigner verify`, CN=OpenMinis Preview), package `com.openminis.app.preview.vdisplay`, non-debuggable, `arm64-v8a`-only native code, and recorded its SHA-256. Published as `vdisplay-preview-22`.

Device acceptance (Xiaomi 15, Android 17/API 37, Shizuku READY, install of `vdisplay-preview-22`) — all verified with screenshots:

- Multi-display routing: `ui info --display 115` returned only the Settings window on the virtual display; without `--display` only display 0 was reported. `ui dump --display 115` enumerated 35 nodes of the settings tree.
- Unicode input channel 1 (a11y `ACTION_SET_TEXT`): `input text '中文测试😀多屏' --display 115` landed verbatim in the Settings search field (screenshot-verified, emoji intact).
- Unicode input channel 2 (shell clipboard + Ctrl+V): `type '粘贴通道 🎉'` reported `sent=1, restored=true, replaceAll=true` and replaced the previous content verbatim (screenshot-verified). `type ' 追加OK' --append` preserved existing content and appended (screenshot-verified).
- Gesture routing: `tap xy 812 60 --display 115` pressed the search-clear button on the virtual display only; the physical screen stayed on the user's foreground app throughout.
- Fail-closed behaviour: `--display abc` → `INVALID_ARGS`; `--display=-1` → `INVALID_ARGS (must not be negative)`; bare `--display -1` → `INVALID_ARGS (requires a value)`; `input key BACK --display 115` → `DISPLAY_UNSUPPORTED`; cross-display node ops (`tap node`/`input text --node` on a display-0 node with `--display 115`) → `NODE_DISPLAY_MISMATCH` with a re-dump hint.
- Clipboard restore: both paste calls reported `restored=true` (read-back verified in the shell service). The user's clip contents were never read into this session.

Known limits observed: `--display -1` (space form) is rejected as "missing value" rather than "negative" because `-1` is tokenized as a flag; both forms fail closed. `--display 999` on a nonexistent display yields an empty tree rather than a dedicated error code.

```sh
android-vdisplay start --width 1080 --height 2400 --dpi 440
android-vdisplay status
# Replace DISPLAY_ID with the actual owned id reported above.
android-a11y-cli ui dump --display DISPLAY_ID --compact
android-a11y-cli input text '中文输入测试' --display DISPLAY_ID
# For a custom editor without an editable accessibility node:
android-vdisplay type '中文输入测试 😀'
android-vdisplay screenshot --output unicode-verification.png
# Verify the text, physical-screen focus, and clipboard restoration before retrying.
android-vdisplay stop
```

Use a disposable local text field, not a message composer, for initial tests. Verify replacement and append, multiline/emoji, nonexistent display rejection, wrong-display node rejection, failed/partial paste reporting, concurrent calls, and clipboard restoration without exposing clipboard contents. Accessibility operations remain under the separate existing accessibility permission; `--display` does not grant virtual-display creation privileges. The virtual-display implementation opens no network port and retains no text payload files. Re-enable the updated app's accessibility service if Android retains a stale binding after an update; Shizuku must also bind the updated UserService.

## Reference licensing

Mika declares Apache-2.0. Any adapted source must retain its applicable copyright notices and include the corresponding license/NOTICE attribution. Audit individual files and third-party notices rather than assuming every bundled dependency shares the repository license.
