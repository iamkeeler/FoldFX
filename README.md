# FoldFX

System-wide fold/unfold transition effects for Android foldables — a book-style
"book opening" effect: as the hinge moves, the screen blurs and a light sweep travels
across the folding glass, then everything settles as the device goes flat.

This is an **experimental tech demo**, not a polished product. See [Limitations](#limitations).

## How it works

```
hinge-angle sensor → FoldEffectService → target progress 0..1 → FoldOverlayManager
     (SENSOR_DELAY_GAME)                        │                 ├─ overlay on every display that is ON
                                                │                 ├─ compositor blur via LayoutParams.setBlurBehindRadius()
                                                │                 └─ FoldEffect draws accents (scrim / sweep / page)
                                                └─ Choreographer easing loop (parks at rest)
```

- **`HingeMonitor`** reads `Sensor.TYPE_HINGE_ANGLE` (0° = closed, 180° = flat) at
  `SENSOR_DELAY_GAME` (~20 ms). For this effect, latency between the physical hinge
  and the overlay matters more than the sample rate — rendering is decoupled anyway
  (see below).
- **`FoldEffectService`** (foreground service, `specialUse`) maps angle to progress:
  a 6° dead zone at each settled end (real sensors idle a few degrees off 0°/180°),
  then smoothstep easing — 0 when settled, peaking at 1 mid-fold (~90°).
- **`FoldOverlayManager`** eases the rendered progress toward the sensor target on a
  `Choreographer` loop (time-based exponential, ~90 ms time constant — smooth on
  60/90/120 Hz, and the loop parks itself at rest, costing nothing when settled).
  It adds a transparent `TYPE_APPLICATION_OVERLAY` window to every display that is
  on (inner + outer during the handoff) and drives the blur radius from progress.
  Blur pushes are quantized to 6 px (each one is a WindowManager IPC), the maximum
  radius is density-scaled (48 dp), and the overlay covers the display cutout so
  there's no unblurred notch. The overlay attaches when progress passes 0.03 and
  releases once the eased progress settles back near zero.
- **Effects** implement `FoldEffect` (`overlay/effects/`), listed in one place:
  `EffectCatalog.all`. v1 ships three:
  - **Book Fold** — a book-style foldable mimic: blur + darkening scrim + travelling light sweep + spine glow
  - **Fade** — simple fade to black mid-fold
  - **Page Turn** — a glass page lifting in 3D around the spine

Adding a new visual = one new `FoldEffect` class + one entry in
`EffectCatalog.all` (`app/src/main/java/com/keeler/foldfx/overlay/effects/EffectCatalog.kt`).
Keep `render()` allocation-free — the companion helpers
`FoldEffect.activeProgress()` (shared early-out) and `FoldEffect.scaledAlpha()`
exist for exactly that.

## Build

Requires **JDK 17** and the Android SDK (API 35). The Gradle wrapper is checked in,
so no local Gradle install is needed:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Then open **FoldFX**, grant **Display over other apps**, optionally exempt it from battery
optimization, and flip the master switch. Fold/unfold the device.

## Permissions — why each one

| Permission | Why |
|---|---|
| Display over other apps (`SYSTEM_ALERT_WINDOW`) | The only way to draw the effect above the current app |
| Foreground service (`specialUse`) | Keeps the hinge watcher alive; shows a persistent notification with Pause (foreground-service notifications need no runtime grant) |
| Receive boot completed | Restarts the watcher after reboot if you left it enabled |
| Battery-optimizations exemption (via Settings intent) | Survives aggressive OEM task killers |

FoldFX never captures the screen, reads other apps' content, or touches the network.
The blur is done by the system compositor behind our transparent window.

## Limitations (read before filing bugs)

- **FoldFX races the system transition; it can't replace it.** Android decides when the
  inner/outer displays power on/off and when apps relaunch across them. Our overlay paints
  over that, but the seam is the OS's, not ours. Only Google/Samsung could bake this into
  the compositor (SurfaceFlinger).
- **No effect over the secure lock screen.** If the phone is locked when it opens, nothing draws.
- **Needs the continuous hinge-angle sensor.** Pixel Fold / 9 Pro Fold / 10 Pro Fold report
  it (5° steps); newer Galaxy Z Folds report accurate angles; older Folds only report
  0°/90°/180° — the effect will look steppy there. Devices without the sensor show
  "unavailable" in the app.
- **Blur is capability-gated.** `addCrossWindowBlurEnabledListener` may report blur
  unavailable (GPU limits, battery saver); FoldFX then falls back to the scrim with no blur.
- Up to ~100 ms of lag between the physical hinge and the overlay is expected (sensor
  latency + the easing time constant).

## Roadmap

- Coarse fallback via Jetpack WindowManager `FoldingFeature` (FLAT/HALF_OPENED) for
  sensor-less devices
- AGSL-shader effects (needs API 33+ `RuntimeShader` path in the overlay view)
- Per-app exclusion list
- Effect marketplace / import

## License

MIT — see [LICENSE](LICENSE).
