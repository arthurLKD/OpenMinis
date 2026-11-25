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
- UI: Settings → Background display (`minis://settings/vdisplay`). Leaving the page stops preview refresh, not the owned display. Preview defaults to 2 fps.
- Agent CLI: `android-vdisplay`. Its separate `vdisplay` permission defaults to denied, independently of Shizuku authorization.
- Default start requires Android 14/API 34+ independent-focus flags. Refused trusted creation fails closed; UI/CLI do not enable untrusted downgrade.
- Direct text entry accepts printable ASCII only. CLI has a conservative 256-character bound; core currently permits up to 1000. Non-ASCII input is not implemented by this command; no automatic clipboard fallback is provided.
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

`.github/workflows/build-android.yml` builds `feature/vdisplay-support` with `:app:assembleRelease` and publishes separately named `MinisApp-VDisplay-preview-arm64-v8a.apk` / `vdisplay-preview-*` artifacts. The existing preview application ID is retained; it is not an additional installation alongside the OpenUI preview. Verify signing certificate compatibility before installation and back up important data.

```sh
git diff --check
cd src/android
./gradlew :app:testReleaseUnitTest --tests '*VDisplay*' --stacktrace
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

## Reference licensing

Mika declares Apache-2.0. Any adapted source must retain its applicable copyright notices and include the corresponding license/NOTICE attribution. Audit individual files and third-party notices rather than assuming every bundled dependency shares the repository license.
