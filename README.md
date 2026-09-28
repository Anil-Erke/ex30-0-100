# EX30 0-100

**English** · [Türkçe](README.tr.md)

An acceleration timer for the **Volvo EX30** that runs on the car's **Android
Automotive OS** screen. Press **Ready** while stopped; the timer starts by itself
the moment the car moves and records the times to **50 · 70 · 100 · 120 · 140
km/h**. No phone, no extra hardware: it uses the car's own speed signal.

> **Unofficial project.** Not affiliated with, endorsed by or supported by Volvo
> Cars. "Volvo" and "EX30" are trademarks of their respective owners and are used
> here only to describe compatibility. See [Disclaimer](#disclaimer).

<p>
  <img src="play-assets/screenshot-portrait-2.png" width="230" alt="Result screen">
  <img src="play-assets/screenshot-portrait-3.png" width="230" alt="Safety warning on every launch">
  <img src="play-assets/screenshot-portrait-4.png" width="230" alt="Top 20 records">
</p>
<p>
  <img src="play-assets/screenshot-landscape-1.png" width="420" alt="Measuring, landscape">
</p>

<sub>Screenshots from the AAOS emulator using the debug simulator. The UI is in Turkish.</sub>

## How it measures

| Stage | Behaviour |
|---|---|
| **Ready** | If the car is stopped it arms immediately; if moving it asks you to stop first. |
| **Launch** | Timing starts when speed exceeds 0.8 km/h. The launch moment falls between two samples, so it is back-interpolated to v = 0 and refined with the acceleration from the next sample. |
| **Thresholds** | Each threshold is found by linear interpolation at the moment speed crosses it, so the result is not rounded to the sample interval. |
| **Finish** | Ends at 140 km/h, or if the car stops for > 1.5 s, or after **30 s**. Thresholds reached so far stay on screen. A run counts as valid if it reached 100 km/h. |
| **Reset** | Cancels a running measurement; with no measurement it clears the history. |

The time base is `CarPropertyValue.getTimestamp()` (the moment the vehicle HAL
measured the value), so callback delivery latency does not affect the result.
The active source and the measured sample rate (for example `10 Hz · ±50 ms`)
are always shown.

### Raw speed or displayed speed

A toggle selects the reference channel:

| Reference | Property | Meaning |
|---|---|---|
| **Raw speed** (default) | `PERF_VEHICLE_SPEED` | The car's actual speed. Use this for a true 0-100 time. |
| Displayed speed | `PERF_VEHICLE_SPEED_DISPLAY` | What the speedometer shows. It never reads below the real speed, so times come out shorter. |

Both channels are always read; the other channel's value is shown next to the
speed so you can see the difference live. History is kept per reference.

### Speed sources (in priority order)

| # | Source | How |
|---|---|---|
| 1 | **Vehicle HAL** | `CarPropertyManager` → `PERF_VEHICLE_SPEED`, continuous listener at the highest rate the car accepts (100 → 1 Hz). Falls back to 25 ms polling if registration fails. |
| 2 | **Car App host** | `CarInfo.addSpeedListener`, if the HAL is silent for 3 s. |
| 3 | **GPS** | `LocationManager` speed, if no vehicle speed arrives within 6 s; marked as "low accuracy". |

### Records

The **Records** button opens the best 20 runs. A run is saved only if it makes
the top 20; the worst one drops off when the list is full. Each record keeps all
thresholds, the date, the speed reference and the sample resolution. Tapping a
record shows it on the main screen (in blue, to tell it apart from a live result).

### Safety warning

The app shows a safety warning **on every launch**. Nothing is measured and no
permission is requested until you accept; *Exit* closes the app.

## Before you build: things you must fill in

Personal values were removed from this repository. Every place that needs your
own value is marked with a **CAPITALISED** comment.

| What | Where | Required? |
|---|---|---|
| Package name (`applicationId`) | `automotive/build.gradle.kts` | **Yes**, for your own release builds. Google Play rejects `com.example.*` |
| Signing key | copy `keystore.properties.example` → `keystore.properties` | Only for signed release builds |

`keystore.properties`, `*.jks`, `*.aab` and `*.apk` are in `.gitignore`.
**Never commit them.**

## Build

Requirements: Android Studio (JDK 17), Android SDK 35.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :automotive:assembleDebug       # emulator testing
.\gradlew.bat :automotive:bundleRelease       # signed AAB (needs keystore.properties)
```

## Getting it onto a car

- **Emulator** (driver profile is usually **user 10** on AAOS):

  ```bash
  PKG=com.example.ex30launch   # or your own applicationId
  adb install -r -t automotive/build/outputs/apk/debug/automotive-debug.apk
  adb shell pm grant --user 10 $PKG android.car.permission.CAR_SPEED
  adb shell pm grant --user 10 $PKG android.permission.ACCESS_FINE_LOCATION
  adb shell am start --user 10 -n "$PKG/androidx.car.app.activity.CarAppActivity"
  ```

- **Real car:** as far as we know, production cars do not allow ADB sideloading.
  The practical route is your **own Google Play Console** account → *Internal
  testing* track, with your own package name and signing key.

### Debug hooks (emulator only)

The emulator (user build) does not allow speed injection, and the host ignores
`adb shell input tap` on its buttons. The **debug build** listens for a broadcast
instead (not installed in release builds):

```bash
adb shell am broadcast --user 10 -p $PKG -a $PKG.DEBUG --es cmd accept                 # accept the launch warning
adb shell am broadcast --user 10 -p $PKG -a $PKG.DEBUG --es cmd sim --ef target 5.3    # feed a synthetic 5.3 s run
adb shell am broadcast --user 10 -p $PKG -a $PKG.DEBUG --es cmd arm
adb shell am broadcast --user 10 -p $PKG -a $PKG.DEBUG --es cmd reset
adb shell am broadcast --user 10 -p $PKG -a $PKG.DEBUG --es cmd ref                    # raw <-> displayed speed
adb shell am broadcast --user 10 -p $PKG -a $PKG.DEBUG --es cmd records                # open the records list
adb shell am broadcast --user 10 -p $PKG -a $PKG.DEBUG --es cmd seed --ei count 25     # generate fake records
adb shell am broadcast --user 10 -p $PKG -a $PKG.DEBUG --es cmd clearrec
```

Verification: with a simulated target of 5.300 s the app measured **5.3004 s**.

## Files

| File | Job |
|---|---|
| `SprintTimer.kt` | State machine, launch estimation, threshold interpolation, history |
| `RecordStore.kt` | Persistent top-20 records (SharedPreferences + JSON) |
| `RecordsScreen.kt` | Records list (`ListTemplate`, clickable rows, paging) |
| `SpeedHub.kt` | Source priority, raw/displayed channel selection, sample-interval measurement |
| `CarPropertySpeedSource.kt` | `android.car` reflection probe (HAL listener + polling fallback) |
| `FallbackSpeedSources.kt` | Car App Library and GPS fallbacks |
| `SprintRenderer.kt` | Surface drawing: measurement screen and launch warning |
| `SprintScreen.kt` | Launch warning, permission flow, NavigationTemplate, action strips |
| `SprintDebugHooks.kt` | Debug only: broadcast hooks |

Buttons live in the host's action strips because the host does not forward
touches on the drawing surface to the app.

UI language: **Turkish** only for now (texts are in code). Code comments are
mostly in Turkish; some refer to internal development notes (`prompt.md`) that
are not part of this repository.

## Privacy

No internet permission: no data can leave the car. Records are stored only in the
app's private storage. No ads, analytics or accounts.

## Disclaimer

This software is provided **"as is", without warranty of any kind**. Hard
acceleration is dangerous. Measure only where it is legal and safe, preferably on
a closed track; obey traffic laws and speed limits. The developer is not
responsible for accidents, injuries, vehicle damage, fines or any other loss. The
driver bears full responsibility. Times are estimates and may be inaccurate.

## License

Copyright (C) 2026 Anıl Erke

This program is free software: you can redistribute it and/or modify it under the
terms of the **GNU General Public License** as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later version
(`GPL-3.0-or-later`).

This program is distributed in the hope that it will be useful, but **WITHOUT ANY
WARRANTY**; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the full text in [LICENSE](LICENSE).

In short: you may use, study, modify and share this code. If you distribute a
modified version (including publishing it on an app store), you must release its
source code under the same license.
