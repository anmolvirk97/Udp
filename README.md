# UDP Mock GPS

Android app (Kotlin, Jetpack Compose, minSdk 26 / targetSdk 35, Gradle Kotlin DSL) that listens for GPS data
on a UDP port (broadcast or unicast) and exposes it as a **mock location provider** for GPS, Network and Fused.

## Project layout

```
UdpMockGps/
├── settings.gradle.kts, build.gradle.kts, gradle.properties
├── gradle/wrapper/gradle-wrapper.properties        (Gradle 8.9)
├── tools/udp_sender.py                             Python test sender
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/ (strings, themes, launcher + notification icons)
        │   └── java/com/example/udpmockgps/
        │       ├── MainActivity.kt
        │       ├── data/    Models.kt, ServiceStatusHolder.kt, SettingsRepository.kt (DataStore)
        │       ├── parser/  Parsers.kt, NmeaParser.kt, JsonFixParser.kt, CsvFixParser.kt
        │       ├── mock/    MockLocationInjector.kt, MockLocationChecker.kt
        │       ├── service/ MockGpsService.kt, BootReceiver.kt
        │       └── ui/      MainViewModel.kt, MainScreen.kt, Theme.kt
        └── test/java/com/example/udpmockgps/parser/
                 NmeaParserTest.kt, CsvAndJsonParserTest.kt
```

Data flow: `MockGpsService` (socket on `Dispatchers.IO`) -> `PacketParser` -> `MockLocationInjector`
(GPS + NETWORK test providers + Fused mock mode) and publishes one `ServiceStatus` into
`ServiceStatusHolder.status` (a `StateFlow`) that `MainViewModel` / the Compose UI observe.

## Build & install

Requirements: Android Studio Ladybug (2024.2.1) or newer, JDK 17, Android SDK Platform 35.

**Android Studio:** *File > Open* the `UdpMockGps` folder, let Gradle sync, press Run.

**Command line:** the repo ships `gradle-wrapper.properties` but not the wrapper jar/scripts.
Generate them once with any local Gradle >= 8.9 (or let Android Studio do it):

```bash
cd UdpMockGps
echo "sdk.dir=$ANDROID_HOME" > local.properties      # path to your Android SDK
gradle wrapper --gradle-version 8.9
./gradlew assembleDebug                               # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest                           # parser unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## One-time phone setup

1. Enable **Developer options** (Settings > About phone > tap *Build number* 7 times).
2. Developer options > **Select mock location app** > *UDP Mock GPS*.
   (The app's "Mock location app" card shows a ✓ or ✗ and has an *Open Developer Options* button.)
3. Open the app, tap **Start**, grant Location and Notification permissions, and accept the
   battery-optimization exemption prompt (recommended; otherwise Android may kill the service).
4. Phone and sender must be on the same Wi-Fi/LAN. Some routers/APs enable "client isolation" that blocks
   broadcasts - disable it or use unicast (`--host <phone-ip>`).

## Test sender

```bash
python3 tools/udp_sender.py                          # NMEA (RMC+GGA in one packet), 255.255.255.255:10110, 1 Hz
python3 tools/udp_sender.py --format json
python3 tools/udp_sender.py --format csv --lat 28.99 --lon 77.02
python3 tools/udp_sender.py --host 192.168.1.42      # unicast
python3 tools/udp_sender.py --bind 192.168.1.10      # choose the NIC on multi-homed PCs
python3 tools/udp_sender.py --drop-after 10 --drop-for 8   # go silent to test "signal lost"
```
Choose the matching **Data format** in the app before pressing Start. The sender walks a 100 m circle.

Verify with any app that reads location (Google Maps, a "GPS test" app).

Emulator: broadcasts from the host do not reach it. In the emulator console (`telnet localhost 5554`, after
`auth <token>` from `~/.emulator_console_auth_token`) run `redir add udp:10110:10110`, then send to
`--host 127.0.0.1`.

## Formats

| Format | Details |
|---|---|
| NMEA 0183 | `$xxRMC` (lat, lon, speed knots -> m/s, course, status A/V) and `$xxGGA` (lat, lon, altitude, fix quality, HDOP x 5 m -> accuracy). Any talker (GP/GN/GL/...). Checksum validated; **a missing checksum is rejected**. RMC status `V` and GGA quality `0` are ignored. Multiple sentences per packet; an RMC+GGA pair with the same UTC time becomes one fix. Speed/bearing/altitude/accuracy are carried over from earlier sentences if a packet lacks them. |
| JSON | `{"lat":..,"lon":..,"alt":..,"speed":..,"bearing":..,"acc":..}` (only lat/lon required; also accepts arrays, newline-delimited objects, and aliases `latitude`, `longitude`/`lng`, `altitude`, `course`/`heading`, `accuracy`). |
| CSV | `lat,lon,alt,speed,bearing,accuracy` per line (only lat, lon required; blank fields allowed; `#` comments skipped). |

Defaults when missing: altitude 0 m, speed 0 m/s, bearing 0 deg, accuracy 5 m.

## Behaviour notes

- **Signal lost**: if no *valid fix* arrives for N seconds (setting, default 5) injection simply stops and the UI and
  notification show "signal lost"; it recovers automatically when fixes resume. (Packets that parse to no fix, such as
  RMC `V`, do not keep the signal alive.)
- **Not the mock app**: the injector catches `SecurityException`, shows the "select mock location app" message in the UI
  and notification, and retries registration every 2 s, so selecting the app in Developer options while the service is
  running is picked up automatically.
- **Stop**: `removeTestProvider` (GPS, NETWORK) and `setMockMode(false)` run in try/catch; the socket, MulticastLock and
  WakeLock are released.
- **Fused**: needs Google Play services. Without it, a warning is shown and the GPS/Network providers keep working.
- **Settings** (port, format, timeout, start-on-boot) are stored with DataStore and are locked while the service runs.
- **Start on boot** is best effort. Android 14/15 may refuse to start a location-type foreground service from the
  background; the failure is caught and logged. The app must have been opened once and have location permission.
- On API 26-30 the legacy `addTestProvider(...)` overload is used (the `ProviderProperties` overload is API 31+); this
  is the only deprecated call and it is confined to one suppressed function.
- Network provider mock locations carry fine accuracy values; apps that distinguish providers will simply see the same fix.

## Building without a PC (GitHub Actions)

`.github/workflows/build.yml` builds the debug APK in the cloud. Push this project to a GitHub repository
(e.g. from a browser Codespace), open the repo's **Actions** tab, run **Build APK**, and download the
`udp-mock-gps-debug-apk` artifact (a zip containing `app-debug.apk`) on your phone and install it.

To test on a single phone, run Termux with Python and use `python3 udp_sender.py --host 127.0.0.1`.
