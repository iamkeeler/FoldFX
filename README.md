# FoldFX

Duo-inspired fold diffusion for Android book-style foldables. The **Duo Diffusion**
effect holds a temporary screen image in place: icons retain their position and
size while diffusion and local optical spread change with the hinge angle. The
crease stays sharp, with softer, darker detail toward the moving edge. The live
app returns when flat. There is no perspective scaling, sliding, or edge reveal.

This is an experimental approximation. It is not
an OS-integrated transition and cannot control when Android powers each panel on
or off.

## Set up stationary diffusion

1. Open FoldFX and grant **Display over other apps**.
2. Leave **Duo Diffusion** and **Stationary content** selected, and enable **Fold effects**.
3. Tap **Enable screen capture**. In Android Accessibility, enable **FoldFX stationary diffusion**.
4. Open the inner screen flat, unlock it, and pause briefly on the page you want
   to fold. The app shows **Inner image ready** after a successful capture.
5. Fold and unfold. Choose **Moving half → Left half / Right half** to match the
   side you swing. These refer to the phone's natural portrait orientation.

Stationary diffusion requires Android 13+. It uses the last flat page, so content
is briefly frozen during a fold, and the projected overlay receives touches until
it clears so taps cannot reach hidden controls. Both halves retain their original
geometry; only the moving pane receives graded diffusion and symmetric optical spread. Without a
usable image or capture access, FoldFX falls back to compositor blur. Fade and
Page Turn remain available as separate effects.

### Capture and privacy

The accessibility service requests screenshot capability only. It does **not**
request window-content retrieval, inspect text/nodes, perform gestures, or touch
the network. Screen images stay in RAM and are never written to disk or shared.
Turning off Stationary content or Fold effects clears the cache and stops capture
requests; the accessibility service can also be disabled in Android Settings.

While enabled and unlocked, capture refreshes at most every 800 ms at a settled
endpoint. At most one recent frame per physical-panel shape is cached, for up to
five minutes, so the inner image can survive a quick cover/inner handoff. Switching
apps invalidates cached and displayed frames. In-flight captures are rejected if
the app, pose, display, rotation, or capture generation changed, so the effect
never recursively captures its own overlay. Protected/secure windows are not
captured. An unavailable or rate-limited capture uses live blur instead.

On a first opening after changing apps, there may be no flat inner frame yet.
FoldFX briefly attempts a fresh capture before attaching on the inner display;
otherwise that transition uses live blur. Pause flat to prepare the next fold.

## How it works

- `HingeMonitor` reads `Sensor.TYPE_HINGE_ANGLE` at `SENSOR_DELAY_GAME`.
- `FoldProgress` catches the first 5° motion sample instead of waiting for 15°–20°.
  The first crack bypasses onset easing. A 5° reading that remains still for
  240 ms releases the effect to avoid leaving a closed, noisy sensor frosted.
- `FoldOverlayManager` retains the actual hinge angle, eases sensor steps on
  `Choreographer`, and parks the frame loop at stable postures. It uses cached
  overlay window contexts that follow display changes, bounded attachment
  retries, and an unlock receiver to restore a held-angle effect after unlock.
- `ScreenCaptureService` captures clean endpoint frames through Android's
  accessibility screenshot API. Physical-panel identity is inferred from aspect
  ratio, rather than assuming logical display IDs remain fixed during handoff.
- `StationaryRenderer` is an original AGSL shader. It samples the page at its
  original coordinates on both halves, uses cubic distance-from-crease diffusion
  and shading, and supports display rotation and either moving half. A bounded
  symmetric 5×5 Gaussian kernel changes optical spread on the moving region;
  the stationary region is a single image sample. It never translates, scales,
  rotates, or introduces perspective motion into the captured page.
- Without projection, `FLAG_BLUR_BEHIND` enables the live compositor blur, in 2 px
  steps at 36 dp normal intensity, capped at 120 px. With projection, global blur
  is zero so it cannot wash out the sharp hinge and stationary half.

The visual study that informed the tracing-paper diffusion model is
[Why the iPhone Duo fold animation works so well](https://www.empirical.health/blog/why-iphone-duo-fold-animation-works/).
This project does not contain Apple implementation code or assets.

## Build

Requires JDK 17 and Android SDK API 35. The Gradle wrapper is included:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Verify

```bash
./gradlew testDebugUnitTest lintDebug
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w com.keeler.foldfx.test/androidx.test.runner.AndroidJUnitRunner
```

Use an unlocked, awake device with overlay permission granted. Open the inner
screen flat in portrait for the projection test. Tests use a generated calibration
page, not a private user image, to check that the stationary half remains unchanged
between fold angles while the moving half changes diffusion. Landmark-centroid
checks verify that moving-side icons retain their original coordinates. They
also cover blur fallback, early cover onset without easing delay, release after
sensor noise, service resumption, and cleanup. Screenshots from the generated
page are saved to the app's external-files `diffusion-qa/` directory by the tests.
The offscreen GPU test checks both icon positions at four angles for either moving
half without requiring the phone to be unlocked:

```bash
adb shell am instrument -w -e class 'com.keeler.foldfx.overlay.StationaryProjectionTest#offscreenGpuKeepsLandmarksPinnedAtEveryAngle' com.keeler.foldfx.test/androidx.test.runner.AndroidJUnitRunner
```

Reopen FoldFX after instrumentation to resume tracking if left enabled.
Instrumentation stops the app process. Android may leave its accessibility service
enabled but disconnected afterward. If capture does not resume, toggle **FoldFX
stationary diffusion** off and on in Accessibility, then return to FoldFX. Run
instrumentation before enabling capture when preparing a personal device.

Use direct instrumentation on personal devices: `connectedDebugAndroidTest` can
uninstall the app and erase its settings during cleanup.

To trace a physical transition in a debug build:

```bash
adb logcat -v threadtime FoldFXTrace:D FoldOverlayManager:W '*:S'
```

The trace records angles, display states, capture readiness, and attachment
outcomes without image contents. Controlled tests do not prove a seamless physical
screen handoff; also fold the actual phone to judge timing and viewing angle.

## Limitations

- **Android controls the display handoff.** On a Pixel 10 Pro Fold / Android 17,
  three recorded openings switched the display off only 54–88 ms after the first
  5° reading. Version 0.2.1 attached at 15°–20°. Version 0.3.0 attempts the effect
  on the first motion sample, but cannot postpone shutoff or promise a visible
  cover effect on every fold.
- **The page is fixed to screen coordinates.** Optical spread changes with angle;
  there is no eye/head tracking or physical perspective compensation.
- **Snapshots briefly freeze content.** They cannot force the launcher or another
  app to keep its actual layout fixed across screen resizing. A near-flat blend
  returns to the live app; its own layout changes may still be visible.
- **No effect over the secure lock screen.** Unlock first. Protected windows and
  missing/stale/rotation-mismatched captures fall back to live blur.
- **Continuous hinge sensing is required.** Missing sensors cannot drive the
  effect; coarse sensors produce less precise motion.
- **Blur is capability-gated.** Battery saver or GPU/system restrictions can
  disable cross-window blur. The fallback is then the effect's subtle veil.
- **Panel shape detection targets book-style foldables.** Other form factors,
  external displays and unusual aspect ratios need separate calibration.

## Permissions

| Permission / access | Purpose |
|---|---|
| Display over other apps | Draw the effect above the current app |
| Foreground service (`specialUse`) | Keep the hinge watcher alive, with a persistent Pause notification |
| Optional screenshot-only accessibility service | Hold temporary images for stationary diffusion |
| Receive boot completed | Restart the watcher after reboot if enabled |
| Optional battery-optimization exemption | Help the watcher survive OEM power management |

## Roadmap

- Per-app exclusions and finer panel/viewpoint calibration
- Coarse posture fallback for devices without a continuous hinge sensor
- Effect import

## License

MIT — see [LICENSE](LICENSE).
