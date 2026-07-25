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
| 2D position (always-on fallback) | Pedestrian dead reckoning | Self-controlled step detection — acceleration projected onto gravity, then cadence-confirmed (see below) — × heading, integrated from a start point, with the full walked path retained and drawn as a trail. Zero radios required. |
| Floor / relative altitude | `TYPE_PRESSURE` barometer | ~3 m per floor heuristic, relative to session start |
| Room identification | WiFi/BLE RSSI + magnetic-field fingerprint matching | Weighted k-NN against a map you record once by walking each room; no coordinates needed (see below) |
| Room labels on the trail map | Room matches + dead-reckoned position | Rooms are recorded without coordinates, then pinned to the map wherever they answer as you walk past (see below) |
| Drift correction on revisit | Automatic trail fingerprinting (loop closure) | Recognises somewhere already visited and removes the drift accumulated in between; needs a circuit of 60 m+ (see below) |

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

### Room-level fingerprint matching

Trilateration needs precisely surveyed anchor coordinates; dead reckoning
drifts without correction. Room-level fingerprinting sidesteps both: walk to
a room, name it in the "Room fingerprint map" card, tap **Record** (a few
samples per room, from different spots in it, makes the match more robust) —
that snapshots every visible WiFi BSSID's RSSI, every visible BLE
identifier's RSSI, and the ambient magnetic field strength
(`MagnetometerSensor`, magnitude only — rotation-invariant, so it doesn't
matter how you're holding the phone) into a `Fingerprint`
(`FingerprintStore`, persisted to a small file in app-private storage —
no database dependency). Once a few rooms are recorded, every live signal
snapshot is matched against all stored samples with weighted k-nearest-neighbor
(`FingerprintMatcher`) — the classic RADAR-style (Bahl & Padmanabhan, 2000)
indoor fingerprinting technique — and the UI shows the best-matching room
plus a confidence score.

This is a genuinely different technique from the trilateration/dead-reckoning
position estimate above: no coordinates, no calibrated anchors, just pattern
matching against a map you walked once. It gets you "which room," not an
(x, y) position — realistically that means room-level accuracy (or
ambiguous results in small open-plan spaces with similar signal exposure
between adjacent rooms), not the centimeter-level position a robot vacuum's
LIDAR/wheel-encoder SLAM achieves. The fundamental gap versus real SLAM is
**loop closure**: a vacuum recognizes when it's revisited an exact spot and
snaps its estimate back onto a persistent map; this app has no equivalent —
every match is independent, and room matching itself builds no map.

(Drift correction *is* now attempted, separately from room matching, by
automatically fingerprinting the trail itself — see Loop closure below. It
narrows the gap versus real SLAM rather than closing it: the RF constraint is
worth a few meters, where a LIDAR's is worth centimeters.)

### Putting the labeled rooms on the map

Room fingerprints deliberately carry no coordinates — that's what lets you
record one by standing somewhere and typing a name. It also leaves them
unplaceable on the trail map. `RoomAnchorMap` fixes that from two directions,
and draws both on the position map, rubber-sheeted along with the trail
whenever a loop closes so they don't slide out of alignment with it.

**Recording a fingerprint marks the spot exactly** (solid dot). No matching
is involved — you are standing there — so there is nothing to be uncertain
about and no corroboration to wait for. This is strictly the better marker,
and it supersedes the inferred one whenever both exist.

**Rooms from earlier sessions are placed by matching** (hollow dot, `~`
prefix). Trail coordinates are relative to where the current session started,
so a fingerprint recorded last time outlives its origin and can only be
placed by recognising it again: whenever it matches, the dead-reckoned
position is an observation of where that room is, and repeat sightings settle
it onto the part of the floor that answers to it.

That second path is the one exposed to noisy matching, and three things make
it survive — all added after simulation showed the naive version failing:

- **A distance gate, not just confidence.** `confidence` is the winner's
  share of the k-NN vote, so it measures unanimity, not proximity — k-NN
  always returns *something*, and standing somewhere never recorded still
  elects a winner, often unanimously. `RoomEstimate` now also carries the raw
  distance to the nearest stored sample, which is what actually says whether
  you're near a mapped room at all. (This is why `FingerprintMatcher` now
  averages its signal terms instead of summing them: a sum is fine for
  ranking, but its scale shifts with how many signal types happen to be
  available, so no fixed number means anything absolute.)
- **Corroboration before display.** A label isn't drawn until the room has
  been seen three times. Isolated wrong matches are routine — measured at a
  spot 17 m from any recorded room, the nearest sample still scored inside
  the gate.
- **Median, and a spread limit.** Position is the per-axis median rather than
  the mean, so an outlier is ignored rather than dragging the label in
  proportion to how wrong it was. And once a room is placed, sightings
  reported more than 8 m away are disbelieved. Without that last rule a walk
  through unmapped space generated enough wrong-but-plausible matches to
  outvote the real ones: in simulation a bedroom label was pulled 9.3 m off
  the room. With it, the same walk places it 0.3 m off.

Across simulated walks that include a detour through unmapped space, all four
test rooms are placed on every run with no spurious labels, and mean error
per room runs 0.1–3.1 m. That residual is mostly not error in the usual sense:
a label marks where the room answered *along your path*, which is offset from
the room's true centre whenever you walk past a room rather than through it.

Saved rooms can be renamed or deleted individually from the fingerprint card.
Renaming onto a name already in the list merges the two rooms, which is the
natural way to reconcile one room recorded under two spellings; the markers
merge with them.

### Reading the map

The position box is heading-up by default: the top is the direction you're
facing, the blue dot stays put in the middle, and the trail swings around it
as you turn. It takes the usual map gestures — drag to pan, pinch to zoom
(0.2×–8×), twist with two fingers to turn. **Reset view** restores all three
at once.

Twisting subtracts from the heading used to project the map, which is what
lets it hold any orientation rather than only heading-up. Twisting until the
N marker reaches the top gives a conventional north-up map, which is much
easier to read against a floor plan. Because the top is then no longer
"forward", the compass ring's tick stops being decoration and starts showing
which way you're actually facing.

Zoom and twist operate about the middle of the box rather than about the
walker, so panning away to inspect a far corner of the trail and then
zooming doesn't fling it off screen. The grid coarsens as you zoom out —
0.5 m up to 500 m, whichever keeps the lines legible — and a scale bar names
the current interval, since with free zoom the grid alone no longer says how
big anything is.

The projection is easy to get subtly wrong (an early version of the map had
the walker apparently moving backwards), so the rotation signs are checked
numerically rather than by eye: that heading-up puts east on the right when
facing north and up when facing east, that a clockwise twist turns content
clockwise, that twisting by your heading yields north-up, and that the pan
offset and compass ring stay consistent with all of it.

### Loop closure: correcting drift on revisit

Dead reckoning drifts, so walking a circuit of a building and returning to
where you started leaves the trail's end somewhere other than its beginning.
Loop closure is what fixes that, and it's what makes a robot vacuum's map
come out square: recognise that you're somewhere you've already been, and
whatever gap has opened up between the two visits is pure accumulated error.

Every 2.5 m the app records a `TrailWaypoint` — the RSSI of everything
audible plus the ambient magnetic field, tagged with the dead-reckoned
position at the time. These are automatic and unlabeled, quite separate from
the rooms you name by hand. Each new waypoint is compared against earlier
ones, and a close signal match means the two are the same place. The error is
then removed by "rubber sheeting" the trail: points before the revisited
waypoint stay put, points after it shift by the whole error, and points in
between shift in proportion to how far along the loop they were walked, so
the path's shape survives locally instead of acquiring a kink where the error
happened to be noticed.

**How well it works, and when it doesn't.** Because `LoopClosureTracker` has
no Android dependencies it can be run on the JVM against simulated walks —
synthetic RF and magnetic signals generated from a known ground truth, with
heading drift accumulating as a real compass's would. That measurement is
what set the thresholds, and it says something worth being upfront about:

*RF fingerprint matching cannot resolve position finer than several meters.*
Signal distance grows only logarithmically with separation, while RSSI at a
fixed spot wanders by several dB. Simulated at a realistic ±5 dB, standing
still gives a median signal distance of ~3.0 and walking 12 m away gives only
~6.2 — heavily overlapping distributions. A match threshold of 8, which looks
sensible next to the 15 dB missing-landmark penalty, in fact accepts
waypoints 20 m apart as "the same place".

Two consequences follow, and both are enforced in code:

- The gap a closure measures is not pure drift; it is drift *plus* however
  far apart the two waypoints really are. Correcting a drift smaller than the
  match resolution therefore trades a small real error for a comparable
  invented one. Corrections under 5 m are discarded.
- Drift only exceeds that resolution once you've walked a fair way (it runs
  about 5% of distance travelled), so closures need a loop of at least 60 m.
  Below that the trail is left alone.

With those in place, simulated routes shorter than 60 m are untouched, and a
240 m circuit walked with heavy heading drift ends up **17.9 m → 9.6 m** from
ground truth. When drift is mild the correction is roughly a wash, and
`worst`-case error along the trail does increase even as the endpoint
improves — because only translation is corrected. A single "these two points
are the same place" constraint doesn't observe rotation, and heading drift is
usually the larger error; undoing that needs several simultaneous constraints
and a proper pose-graph optimisation, which this isn't.

### Telling walking apart from fidgeting

The dead-reckoning trail only advances on a detected footstep, so a step
detector that fires on idle hand movement doesn't just inflate a counter — it
draws phantom corridors through the map. `StepDetector` uses two properties
of walking that shaking a phone doesn't share:

**Gait is vertical.** Walking bounces your center of mass up and down against
gravity; idle hand movement is mostly lateral and rotational. Every sample is
projected onto the gravity vector (from `TYPE_GRAVITY`, or a low-pass
estimate off the raw accelerometer) and only that vertical component drives
detection. The obvious alternative — thresholding the magnitude of the 3-axis
vector — is direction-blind, and rectifies a shake along *any* axis into an
apparent step.

**Gait is rhythmic and sustained.** A peak becomes a *candidate*, not a step.
Candidates are only committed once four arrive in a row at a steady,
plausible walking cadence (400 ms–1 s apart, within 20% of each other), and
the cadence keeps being enforced for the rest of the walk, so one lucky
confirmation can't open the floodgates. Confirmation is **retroactive** — the
whole run is emitted at once — so the check costs no steps, only a brief
delay at the start of a walk. The trade is that a walk shorter than four
steps never registers.

Two details matter more than they look. The stride floor is 400 ms rather
than 300 ms because at 300 ms a 3 Hz shake sits *inside* the plausible band
and reads as perfectly steady fast walking. And a peak rejected for arriving
too soon still updates the "last candidate" timestamp — without that, a fast
rhythmic shake has every other peak rejected and the survivors land a
plausible stride apart, frequency-dividing a 4 Hz fidget into convincing 2 Hz
"gait". Both were found by simulating synthetic traces against the analyzer.

Because `GaitAnalyzer` is deliberately free of Android dependencies, it can
be exercised on the JVM with synthetic acceleration traces — sinusoidal gait
at various cadences and amplitudes, gait with realistic stride-to-stride
jitter, and irregular shaking — which is how the thresholds above were
chosen. Note that this over-states the fidgeting problem: a synthetic trace
feeds the analyzer a purely vertical signal, whereas real fidgeting is mostly
lateral and is largely removed by the projection before the analyzer ever
sees it.

## Project layout

```
app/src/main/java/com/wesrable/positioning/
  model/            Orientation, WifiSignal, BleSignal, Anchor, PositionEstimate,
                     Fingerprint, RoomEstimate...
  sensors/          OrientationSensor, BarometerSensor, MagnetometerSensor, StepDetector
  scan/             WifiScanner, BleScanner, BleAdvertisementParser
  positioning/       RssiDistance, Trilateration, DeadReckoningTracker, RoomAnchorMap,
                     LoopClosureTracker, PositioningEngine
  fingerprint/      FingerprintStore, FingerprintMatcher
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
- **No `ACTIVITY_RECOGNITION` needed.** Step detection is done with our own
  algorithm over `TYPE_LINEAR_ACCELERATION` + `TYPE_GRAVITY` (falling back to
  raw `TYPE_ACCELEROMETER`) rather than the OS's hardware
  `TYPE_STEP_DETECTOR`, which requires that permission. This also sidesteps
  `TYPE_STEP_DETECTOR`'s OEM-variable firmware behavior — some devices need
  several warm-up steps before they start reporting, and some drop out
  unpredictably — which made the step counter feel unresponsive.
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

**The position map is heading-up, not north-up.** `DeadReckoningTracker`
accumulates true (east, north) displacement from the compass heading at each
step, and now also keeps every point visited (`trail`) — that part is a
straightforward, correct compass-to-Cartesian conversion. But `PositionCard`
renders it *heading-up*: the top of the map
always means "the direction you're currently facing," not north (the same
convention a phone nav app's walking mode uses), by projecting the
accumulated displacement onto (forward, right) relative to the live compass
reading at render time. Without this, a fixed north-up map only shows
forward motion as moving up the screen if you happen to be walking due
north — walking any other direction (say, south) would correctly move the
dot down, which reads as "backward" even though the underlying math was
right. A small ring (`NorthCompassRing`) next to the map shows where true
north currently is relative to that heading-up frame — its top tick is
always "forward" (same convention as the map), and the "N" marker orbits it
as you turn, using the identity that a unit vector at absolute bearing θ
projects to screen angle (θ − heading) from "up" in a heading-up frame.

**The camera follows the current position, not the start point.** Every
point drawn — grid, trail, start marker — is projected relative to *where
you are now*, so the live position dot stays fixed at the view's center as
you walk, with everything else (including the start point) sliding around
it. A drag gesture on the map adds a manual `panOffset` on top of that
camera-follow behavior, letting you look at other parts of the trail; a
"Recenter" button appears whenever `panOffset != Offset.Zero` to snap back
to auto-follow.

If the dot's motion still doesn't track your own steps after all this, suspect
device-attitude/carry-angle mismatch instead (phone held tilted or flat
rather than upright with its top aimed the way you're walking) — that
genuinely biases the raw heading itself, which no display transform can fix.

## Building

Requires Android Studio (or the Android SDK + `compileSdk 34`) locally:

```
./gradlew assembleDebug
```

CI (`.github/workflows/build.yml`) builds the debug APK on every push and
commits it to `dist/device-positioning-debug.apk` on the same branch
(`[skip ci]`-tagged, so that commit doesn't re-trigger the workflow) —
`dl.google.com` is reachable there even though it's blocked in some
sandboxed dev environments, where the Android Gradle Plugin and
`androidx`/`google()` artifacts can't be fetched.

**Debug signing is pinned to a committed keystore** (`debug.keystore` at the
repo root, referenced from `app/build.gradle.kts`'s `signingConfigs.debug`).
Without this, Android's default debug signing config auto-generates a fresh
*random* key at `~/.android/debug.keystore` whenever that file doesn't
already exist — true on every CI run, since each starts on a clean machine.
Android refuses to install an APK as an update over one signed by a
different key, so every CI-built APK required uninstalling the previous one
first. The pinned keystore's password/alias are the well-known Android
debug-key defaults (`android` / `androiddebugkey`) — not a secret, since
debug signing was never meant to be tamper-proof, only convenient. With it,
every future CI build shares one signature and installs as a normal update.

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
| **Geomagnetic + RF fingerprinting** *(implemented — see Room-level fingerprint matching above)* | Room-level | Steel/rebar creates a stable, location-specific magnetic-field pattern; combined with WiFi/BLE RSSI and matched via weighted k-NN against a map you walk and record once, entirely offline | Needs an initial survey walk; can drift if the building's fixtures/APs change; gives a room label, not (x, y) coordinates |
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
