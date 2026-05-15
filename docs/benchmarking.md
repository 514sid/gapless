# Benchmarking on a Real Device

## What this measures

`PlaylistManagerBenchmarkTest` measures the worst-case cost of a single `tick()` call on ART.
Each test puts X expired/scheduled-inactive assets before a single active one, forcing
`findNextActive()` to walk the entire list on every tick.

## Prerequisites

- Android device or TV connected via ADB (USB or TCP/IP)
- `adb` available in your PATH

## Steps

### 1. Connect the device

USB:
```bash
adb devices
```

TCP/IP (replace with your device IP):
```bash
adb connect 192.168.0.244
adb devices
```

### 2. Build the test APK

```bash
./gradlew :library:assembleDebugAndroidTest
```

The APK is output to:
```
library/build/outputs/apk/androidTest/debug/library-debug-androidTest.apk
```

> **Note:** AGP 9 does not apply `testNamespace` for library modules, so a Gradle hook in
> `library/build.gradle.kts` patches the generated manifest to use the valid package
> `io.github.gapless514sid.test`. The source namespace is unchanged.

### 3. Install on the device

```bash
adb -s <device-serial> install -r library/build/outputs/apk/androidTest/debug/library-debug-androidTest.apk
```

Replace `<device-serial>` with the value from `adb devices` (e.g. `192.168.0.244:5555`).

### 4. Run the benchmarks

```bash
adb -s <device-serial> logcat -c

adb -s <device-serial> shell am instrument -w \
  -e class io.github._514sid.gapless.internal.PlaylistManagerBenchmarkTest \
  io.github.gapless514sid.test/androidx.test.runner.AndroidJUnitRunner
```

### 5. Read results

```bash
adb -s <device-serial> logcat -d -s GaplessBenchmark
```

Example output:
```
I GaplessBenchmark: 100 inactive + 1 active — avg tick: 124µs
I GaplessBenchmark: 500 inactive + 1 active — avg tick: 562µs
I GaplessBenchmark: 1000 inactive + 1 active — avg tick: 1327µs
I GaplessBenchmark: 5000 inactive + 1 active — avg tick: 9473µs
```

## Reference results

Measured on 2026-05-15 with AGP 9.0.0, default tick interval (1000ms).

| Playlist (worst case)  | Smart TV  | Amazon Fire TV (AFTKM) |
|------------------------|-----------|------------------------|
| 100 inactive + 1       | 124µs     | 501µs                  |
| 500 inactive + 1       | 562µs     | 1,479µs                |
| 1,000 inactive + 1     | 1,327µs   | 4,939µs                |
| 5,000 inactive + 1     | 9,473µs   | 13,071µs               |

Even on the slower device, a 1,000-item worst-case playlist consumes ~0.5% of the 1,000ms tick interval.
