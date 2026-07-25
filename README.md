# Device Positioning

An Android app that estimates device attitude (heading/tilt, to sub-degree
resolution) and 2D position **using only signals the device can passively
observe** — WiFi and Bluetooth LE scan results, plus onboard motion and
pressure sensors. It never associates with a WiFi network, never pairs or
connects to a Bluetooth device, and requests no `INTERNET` permission at all.

## What it does

| Capability | Sensor / API | Notes |
|---|---|---|
| Heading, pitch, roll | `TYPE_ROTATION_VECTOR` (fused accel+gyro+magnetometer) | Sub-degree resolution, shown live on a compass widget |
| WiFi landmarks | `WifiManager.startScan()` / `getScanResults()` | Passive scan only — SSID/BSSID/RSSI, no association |
| BLE landmarks | `BluetoothLeScanner` observer scan | No `connectGatt()` — decodes iBeacon and Eddystone payloads directly from the advertisement bytes |
| Range to each landmark | Log-distance path-loss model | Converts RSSI → estimated meters; noisy but connection-free |
| 2D position (with calibrated anchors) | Least-squares multilateration | Needs ≥3 landmarks with known coordinates (see Calibration below) |
| 2D position (always-on fallback) | Pedestrian dead reckoning | Step detection (accelerometer or `TYPE_STEP_DETECTOR`) × heading, integrated from a start point. Zero radios required. |
| Floor / relative altitude | `TYPE_PRESSURE` barometer | ~3 m per floor heuristic, relative to session start |

RF-based and dead-reckoning position estimates are fused with a simple
inverse-variance weighted blend (`PositioningEngine.fuse`), so the app keeps
producing a position even when no calibrated anchors are visible, and
corrects dead-reckoning drift whenever RF landmarks are available.

### Calibration

Multilateration needs real-world coordinates for at least 3 WiFi
BSSIDs/beacon IDs (`Anchor(id, xMeters, yMeters)`). Those coordinates can't
be derived from RF alone — they come from a one-time site survey. The engine
ships with an empty anchor map (`MainViewModel.anchors`); wire in your own
site survey there to enable trilateration. Without it, the app still runs
fully useful dead reckoning + a live landmark list.

**Caveat: BSSID is not a permanent identifier.** `PositioningEngine` keys
calibrated anchors by BSSID, but a WiFi BSSID can change under a physical AP
that never moved: multi-band routers broadcast a different BSSID per radio
(2.4/5/6 GHz) under one SSID, mesh systems (eero, Orbi, Nest Wifi, etc.)
expose one BSSID per node and can steer clients between nodes, guest/virtual
SSIDs typically derive a second BSSID by incrementing the base MAC, and
firmware updates, factory resets, or hardware swaps can change it outright.
A calibrated anchor can silently go stale after any of these. Android's
`ScanResult` API doesn't expose a stronger per-device identifier to
third-party apps — security type, channel width, and vendor OUI (the BSSID's
first 3 octets) only help *group* related BSSIDs, they aren't unique IDs on
their own. If an anchor needs to survive router firmware/hardware churn,
prefer a BLE beacon's UUID/major/minor (parsed in `BleAdvertisementParser.kt`)
as the calibration target instead of a WiFi BSSID — that identifier lives in
the advertisement payload, not the radio's MAC, so it doesn't move when the
beacon reboots or a mesh node gets swapped.

## Project layout

```
app/src/main/java/com/wesrable/positioning/
  model/            Orientation, WifiSignal, BleSignal, Anchor, PositionEstimate...
  sensors/          OrientationSensor, BarometerSensor, StepDetector
  scan/             WifiScanner, BleScanner, BleAdvertisementParser
  positioning/       RssiDistance, Trilateration, DeadReckoningTracker, PositioningEngine
  ui/               Jetpack Compose screens
  MainActivity.kt, MainViewModel.kt
```

## Permissions, and why

- `ACCESS_FINE_LOCATION` — Android gates *reading* WiFi/BLE scan results
  behind location permission on most OS versions, even though this app
  never reports GPS location anywhere.
- `BLUETOOTH_SCAN` (API 31+) — required to start a BLE scan. Declared
  *without* `neverForLocation`, since we deliberately use RSSI for
  positioning.
- `NEARBY_WIFI_DEVICES` (API 33+) — lets WiFi scans work without also
  holding location permission on newer OS versions; also declared without
  `neverForLocation`.
- `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` — read scan results / request a
  scan.
- **No `INTERNET`, no `ACCESS_NETWORK_STATE`.** The app cannot phone home
  even if it wanted to.
- `BLUETOOTH_CONNECT` is intentionally **not** requested — the BLE scanner
  never calls `connectGatt()`, and gracefully falls back to a device's raw
  address when reading its advertised name would require that permission.

## Accuracy caveats

RSSI-to-distance is a rough, noisy estimate (multipath fading, body
shadowing, and antenna orientation can each swing it several dB — see
`RssiDistance` for the model and its limits). Treat WiFi/BLE ranges as
"nearby / mid-range / far", not precise measurements. Dead reckoning drifts
with distance travelled (typically ~5% of path length) absent periodic RF
corrections. For applications that need real precision, see the higher-fidelity
methods below.

## Building

Requires Android Studio (or the Android SDK + `compileSdk 34`) — this repo's
Gradle wrapper is included, but this development sandbox's network policy
blocks `dl.google.com`, so the Android Gradle Plugin and `androidx`/`google()`
artifacts could not be fetched or compiled here. The plain-Kotlin `model` and
`positioning` packages (no Android dependencies) were compiled standalone as
a sanity check and build cleanly; the Android-specific sensor/scan/UI code
was reviewed by hand against the platform APIs but not compiled in this
environment. Open the project in Android Studio and let it sync to build.

```
./gradlew assembleDebug
```

---

## Proposing further methods for connection-free positioning

The brief above (WiFi/BLE passive scanning, motion sensors) covers the
lowest-friction options. Below are additional techniques that determine
position **without opening a network connection** — i.e., no data session,
no server round-trip, no live lookup against an online geolocation/beacon
database. Several still use a radio, but only to passively receive or
locally range against something already broadcasting — never to associate,
authenticate, or exchange application data over a network.

| Method | Accuracy | How it stays connection-free | Trade-off |
|---|---|---|---|
| **WiFi RTT (IEEE 802.11mc)** | ~1-2 m | `WifiRttManager` ranges APs via time-of-flight without ever associating with them (API 28+, needs RTT-capable APs) | Requires supporting hardware on both ends |
| **UWB ranging** | ~10 cm | AndroidX Core `UwbManager` performs two-way time-of-flight ranging against another UWB radio directly, no network path | Needs UWB chips (newer flagship phones/tags) on both sides |
| **BLE Channel Sounding / AoA-AoD direction finding** | ~cm-dm | Bluetooth 5.1+ Constant Tone Extension lets a receiver compute angle-of-arrival from a beacon's *advertisement*, still zero connection | Needs BT 5.1+ radios and antenna arrays on the anchor side |
| **Raw GNSS measurements** | ~1-5 m outdoors | `GnssMeasurement`/`LocationManager` with `GPS_PROVIDER` receives satellite signals directly — no cellular data or A-GPS network fetch needed | Outdoor/line-of-sight only; slow cold-start without assistance data |
| **Cellular signal fingerprint (no data session)** | ~50-500 m | `TelephonyManager.getAllCellInfo()` reads serving + neighbor cell IDs and signal strength from the radio's existing registration — no APN/data connection opened | Coarse; needs a self-collected cell-ID→location map, since no live carrier database lookup is allowed |
| **Geomagnetic fingerprinting** | ~1-3 m indoors | Steel/rebar in buildings creates a stable, location-specific magnetic-field pattern; match live magnetometer readings against a fingerprint map you walked and recorded once, entirely offline | Needs an initial survey walk; can drift if the building's fixtures change |
| **Pedestrian dead reckoning** (already implemented) | Drifts ~5%/m travelled | Pure IMU integration, zero radios | Needs periodic correction from any of the above |
| **Visual-inertial odometry (ARCore / VIO)** | cm-level, drifts over time | Camera + IMU fused locally on-device (ARCore's `Session` works fully offline) | Needs decent lighting/texture; drifts without loop closure or markers |
| **Acoustic time-of-flight ranging** | ~cm-dm | Speaker emits an inaudible (18-22 kHz) chirp, microphone(s) on nearby devices/anchors measure arrival time — pure local audio hardware, no radio at all | Needs synchronized clocks or a round-trip protocol; limited range |
| **Barometric floor detection** (already implemented) | ±1 floor | Local pressure sensor only | Only gives *relative* floor, not (x, y) |
| **Wi-Fi Aware (NAN) peer ranging** | ~m-level | Device-to-device discovery and ranging entirely off-infrastructure — no AP association, no internet | Needs another Wi-Fi Aware-capable peer nearby |
| **NFC waypoint tags** | Exact (tap-to-localize) | Passive NFC tags glued at known physical points; tapping one gives an exact fix with zero ambiguity | Requires physically instrumenting the space with tags |

### A practical combination

For an indoor space you control, the strongest offline stack is usually:
**WiFi RTT or UWB anchors** (when hardware allows) for periodic sub-meter
fixes, **BLE/WiFi RSSI multilateration** as a lower-precision fallback where
RTT/UWB anchors aren't installed, **pedestrian dead reckoning** to keep
producing a position between fixes, and **barometric floor detection** to
disambiguate multi-story buildings — which is exactly the fusion structure
`PositioningEngine` in this app already implements for RSSI + PDR, and is
built to extend with an RTT/UWB ranging source alongside the RSSI-derived
one.
