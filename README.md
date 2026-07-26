# Device Positioning

An Android app that estimates device attitude (heading/tilt, to sub-degree
resolution) and 2D position **using only signals the device can passively
observe** — WiFi and Bluetooth LE scan results, plus onboard motion and
pressure sensors. It never associates with a WiFi network, never pairs or
connects to a Bluetooth device, and requests no `INTERNET` permission at all.

## What it does

| Capability | Sensor / API | Notes |
|---|---|---|
| Heading, pitch, roll | `TYPE_GAME_ROTATION_VECTOR` (accel+gyro, no magnetometer) slowly corrected toward `TYPE_ROTATION_VECTOR` | Gyro heading is immune to the indoor steel that swings a compass by tens of degrees; a slow pull keeps it north-referenced (see below) |
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

### The persistent map, and finding yourself in it

Until now the geometry died with the session. Coordinates are relative to
wherever the app happened to be launched, so nothing survived except the room
fingerprints — the app labelled rooms persistently but redrew the place from
nothing every time. `MapStore` and `Relocalizer` close that.

**Recovering the frame is only a translation.** East and north are integrated
from the compass, not from an arbitrary starting orientation, so every session
already shares the stored map's axes and differs only in where its origin
landed. There is no rotation to solve for, which removes the hardest and most
ambiguous part of the problem before it starts.

**Finding the translation.** Live signals are matched against the stored
waypoints; the nearest five vote on where in the map the walker is standing,
and the gap between that and the session's own idea of its position is a
candidate offset. One candidate is never trusted — RSSI matching is good to
only a few meters, and occasionally points somewhere else entirely — so
offsets are collected as the walker moves and the lock is taken only when four
successive ones agree to within 4 m. On locking, the session's trail, its
waypoints, its room markers and its magnetic samples are all slid bodily onto
the map: the shape walked so far was right, only its place in the world was
unknown.

Measured against a clean map, over 60 runs per case:

| Home | Waypoints | Located | Median error | Worst | Walked first |
|---|---|---|---|---|---|
| studio 4×3 m | 4 | 19/60 | 0.68 m | 1.14 m | 67 m |
| small flat 6×5 m | 8 | 60/60 | 0.76 m | 1.88 m | 36 m |
| house floor 10×8 m | 20 | 60/60 | 0.55 m | 1.48 m | 7 m |
| large floor 18×14 m | 63 | 60/60 | 0.66 m | 1.83 m | 7 m |

Sub-metre from a measurement good to only ±5 m, because aggregation beats the
noise — five neighbours vote, four independent fixes must agree, and the
median is taken. **No false lock occurred in 360 runs.** A studio declines
more often than it locks, which is the right failure: too few distinguishable
places to be sure, so it says nothing rather than guessing.

**The limit is the map, not the matching.** Running the whole cycle through
the real engine — session one walks and exports, session two loads and
relocalizes — the error at the moment of locking is 7.5 m, not 0.5 m. That is
not the relocalizer failing. Session one's own map was already 7.9 m off
truth, because a long walk accumulates drift that loop closure only partly
removes, and relocalization faithfully recovers your place *in that map*,
inheriting whatever it got wrong. The two figures track each other almost
exactly.

That is the classic SLAM coupling: map quality and localization quality bound
each other, and separating them needs a global optimisation over the whole
trajectory — a pose graph — rather than the local rubber-sheeting here. It is
the same missing machinery the 2D magnetic map would need, and the natural
next thing to build.

**Merging is guarded.** A session that never located itself is never written
into the map; its coordinates are relative to an origin nothing can find
again, so merging it would smear the map rather than extend it. There is a
**Forget** button for the case where a session locates wrongly and corrupts
what was stored, since nothing else recovers from a bad merge.

### Reading the map

The position box is portrait (3:4) and sized off the width actually available
rather than to a fixed height, so it fills whatever device it lands on —
capped at 90% of the screen height so it can never grow to where the cards
below it are pushed out of sight. That shape suits the heading-up view: what
you're walking towards is ahead of you on screen, and that's the direction
worth seeing furthest in.

(The height is computed directly rather than by combining `aspectRatio` with
a `heightIn` cap. `fillMaxWidth` fixes the width, so `aspectRatio` can find no
size that satisfies a reduced `maxHeight`, and falls through to leaving the
height unconstrained rather than capping it — the cap would silently do
nothing.)

It is heading-up by default — the top is the direction you're facing, the
blue dot stays put in the middle, and the trail swings around it as you turn.
It takes the usual map gestures: drag to pan, pinch to zoom (0.2×–8×), twist
with two fingers to turn. **Reset view** restores all three at once.

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
  about 5% of distance travelled), so closures need at least 60 m of walking
  between the two passes. Below that the trail is left alone. Note this is
  distance *walked*, not the size of the space — repeating a small circuit
  reaches it, and a 6×5 m flat does so in three laps.

**This means loop closure does nothing in a single room, by design.** The two
gates are independent, and the drift one is the binding constraint in a small
home: a 4×3 m studio walked for 144 m accumulates only ~2.1 m of drift, well
under the 5 m floor, so no correction is attempted. That is the right answer —
correcting a 2 m error with a measurement good to ±5 m makes it worse, which
simulation confirms. Only over a house-sized floor does drift outgrow the
match resolution: a 10×8 m floor walked for 278 m drifts 10.3 m and closure
brings it to 6.9 m.

The honest limitation is that 2 m of error is small absolutely but large
*relative* to a 4 m room, so a small home's trail is meaningfully wrong and
RSSI matching cannot fix it. `MagneticSequenceMatcher` is an attempt at the
gap; it ships **off by default**, and the section below explains why.

### Magnetic sequence matching, and why it is opt-in

Steel and wiring impose structure on a building's magnetic field from tens of
centimetres upward. A single reading is nearly useless for placing yourself,
but the *sequence* of readings along a walked path is a signature. Measured
on simulated walks through a structured field, sampled once per stride:

| Offset from the original line | Match distance (median) |
|---|---|
| 0 m (same line) | 0.38 |
| 0.25 m | 2.33 |
| 1 m | 5.18 |

Same-place p90 is 0.49 against 0.25 m-away p10 of 1.70 — cleanly separated,
where RSSI's 0 m-p90 (3.81) barely undercut its 12 m-p10 (4.36). A *single*
reading overlaps (0.70 against 0.34), so it is the sequence doing the work,
not the magnetometer. Sample spacing barely matters between 0.25 m and 1 m,
because most of the field's energy sits at wavelengths a stride resolves —
which is what makes this possible at all, since the app only learns where it
is once per step.

So the resolution is there. Two things stop it being a default:

- **It needs you to retrace nearly the same line.** At ±0.15 m of wobble it
  is a large win — a 4×3 m studio walked 12 laps goes from 2.32 m to 1.40 m of
  error, which is the room-scale correction RSSI cannot do. At ±0.4 m, closer
  to how people actually walk, it barely fires, and where it does it is a mild
  loss. Loosening the threshold to tolerate that admits laterally-offset
  matches that are simply wrong: on a 10×8 m floor every loosened setting
  tried made the error worse, 7.9 m to 9.4–9.9 m.
- **A magnetically bland building cannot be told apart from a match.** Where
  there is little steel the field varies so smoothly that a stretch 15 m away
  still scores 0.86. A distinctiveness test — the winner must beat the median
  candidate by 2× — helps but does not fix it, and the apparent small-room
  wins in a bland field are an artifact of the room being small enough that
  any match is roughly right, not of the matching working.

The honest fix for the lateral-offset problem is not a better threshold but a
different formulation: accumulate a 2D magnetic *map* and search over
translations, so walking a parallel line is a shift to solve for rather than a
mismatch. That is real magnetic SLAM and a much larger piece of work.

Which regime a given home is in cannot be determined from here, so the switch
is exposed with the trade stated, and the closure count reports magnetic
matches separately so it is visible whether it is firing at all.

With those in place, simulated routes shorter than 60 m are untouched, and a
240 m circuit walked with heavy heading drift ends up **17.9 m → 9.6 m** from
ground truth. When drift is mild the correction is roughly a wash, and
`worst`-case error along the trail does increase even as the endpoint
improves — because only translation is corrected. A single "these two points
are the same place" constraint doesn't observe rotation, and heading drift is
usually the larger error; undoing that needs several simultaneous constraints
and a proper pose-graph optimisation, which this isn't.

### Why heading does not come from the compass

Heading error is the dominant dead-reckoning error — it rotates everything
walked after it — and indoors the compass is where that error comes from.
`TYPE_ROTATION_VECTOR` folds the magnetometer into its fusion, which is right
outdoors and wrong in a building: steel, wiring and appliances bend the field
by tens of degrees, so the reported heading swings from room to room while the
walker goes straight. It shows up as walking a circuit and not arriving back
where you started, with the return leg rotated away from the outbound one.

There is an irony worth stating plainly: this is the *same* distortion that
makes magnetic fingerprinting work. A building magnetically distinctive enough
to recognise is a building whose compass cannot be trusted.

So heading comes from `TYPE_GAME_ROTATION_VECTOR` — the identical fusion with
the magnetometer left out. Its turn rates come from the gyroscope, which does
not care what the walls are made of. On its own it would be no good either: it
creeps with gyro bias and has no idea where north is, which the persistent map
requires, since sessions can only share a frame because their axes are
north-referenced.

The two are therefore combined, but asymmetrically, and the balance took a
real-world test to get right. North only has to be established *once* — it is
needed so sessions share a frame with the stored map, not to steer the walk —
so the compass is given real authority for the first few seconds and almost
none afterwards (a time constant of about 200 s, a leash on gyro bias rather
than a steering input). The pull is suspended entirely while Android reports
the magnetometer as needing calibration, and the orientation card says which
of those is happening rather than leaving it invisible.

It was first built with a 20-second constant, on the reasoning that this is
slow enough for walking past a fridge to move the heading only a fraction of a
degree. That reasoning holds for one disturbance passed once, and fails on a
small circuit walked repeatedly. Walking a 1.5 × 3 m L-shaped path four times
produced a trail whose segment lengths were about right but whose four passes
fanned out, each rotated from the last: the whole path sat inside a distorted
field, the compass told a different story in every corner, and over a
minute-long walk the filter had four time constants in which to follow it. A
tight circuit is also the harshest possible heading test, since it is mostly
turns — sixteen or more of them — and heading error compounds per turn rather
than per metre.

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
Candidates are committed once three arrive in a row at a plausible walking
cadence, retroactively so the check costs no steps — only a short delay at the
start of a walk.

What "plausible cadence" means was got wrong first time round, and the way it
was wrong is instructive. Each interval was required to sit within 20% of the
others across the window, which is a fair description of a treadmill and a
poor one of a home. Real indoor walking is short bursts between turns, and
each burst *accelerates* out of standing and *decelerates* into stopping, so
consecutive intervals trend rather than scatter — and a window test forbids a
trend. Simulated against bursts of that shape, the detector counted 94% of
long striding steps but only 51% of pottering and **27% of slow careful
walking**, which matches the reported symptom exactly: several steps before it
starts, then dropping out.

Each interval is now compared with *the one before it* at the same 20%. The
figure did not change; the question did. A cadence that steadily quickens or
slows still reads as one walk, while scatter still does not. Two related gates
were wrong for the same reason: the slowest accepted stride was 1 s, which
rejects the first step or two out of a standstill, now 1.8 s; and a single
off-cadence stride ended the walk, costing the next three steps to
re-confirm, where a turn or a doorway looks exactly like that — two in a row
are now needed.

| Scenario | Before | After |
|---|---|---|
| long strides, 8–15 step bursts | 94% | 100% |
| normal indoor, 4–9 step bursts | 97% | 100% |
| pottering, 2–5 step bursts | 51% | 86% |
| gentle + short, 3–6 step bursts | 68% | 99% |
| slow careful, 850 ms cadence | 27% | 100% |

The cost is real and was measured alongside: fidget peaks slipping through
roughly tripled. That is the right trade while missed steps are the live
problem — a missed step shortens the trail every time you walk, where a
spurious one only costs something while you are fidgeting — and the fidget
figures overstate it, since the simulation feeds a purely vertical signal
whereas real idle movement is mostly lateral and is removed by the gravity
projection before the analyzer sees it. Pottering stays at 86% because a
two-step burst cannot be confirmed by a rule that needs three.

Because `GaitAnalyzer` is deliberately free of Android dependencies, it can
be exercised on the JVM with synthetic acceleration traces — sinusoidal gait
at various cadences and amplitudes, gait with realistic stride-to-stride
jitter, and irregular shaking — which is how the thresholds above were
chosen. Note that this over-states the fidgeting problem: a synthetic trace
feeds the analyzer a purely vertical signal, whereas real fidgeting is mostly
lateral and is largely removed by the projection before the analyzer ever
sees it.

### The dense survey, and measuring what this building actually supports

Every accuracy figure quoted in a positioning paper is a figure about *that*
building. Signal environments differ enormously — a mall has thirty visible
access points, a house has eight — so a number borrowed from someone else's
measurements says very little about yours. The dense survey exists to replace
that borrowing with a measurement.

Tap **Start survey** and walk. A signal sample is captured automatically every
half metre (`SurveyTracker.SPACING_METERS`), tagged with the dead-reckoned
position it was taken at. Half a metre is not arbitrary: cabinets stand 30-60 cm
apart, so anything coarser could not even in principle produce a pair of samples
separated by the distance the exercise is about. Sampling by *distance* rather
than by time is what keeps the survey even — standing still would otherwise
bury the map under a hundred readings of one spot and bias every statistic
towards it.

Tap **Run report** and `FingerprintCrossValidation` scores what was collected.
It reports two things:

- **Where it puts you.** Leave-one-out cross-validation: hold each sample out,
  locate it from the others using the app's own k-NN matcher, and measure how
  far off the answer was. Reported as a median and a 90th percentile.
- **Telling two places apart.** Of the sample pairs that were 1 m apart, how
  often did their signals differ by more than same-place noise does? And at
  half a metre, two metres, four?

Three details separate an honest number here from a flattering one, and all
three were arrived at by watching the analysis get them wrong first:

- **Pairs recorded close together in time are never compared**
  (`MIN_SEPARATION_MILLIS`, 15 s). Two samples taken four seconds apart share
  far more than a location: the same bodies in the same doorways, the same
  interference, the same everything. Scoring against those measures how
  repeatable one walk is, which is not the question anyone is asking. This is
  why a survey walked in one sitting reports `NOT_ENOUGH_REVISITS` and asks
  for a second pass — and why the survey persists between sessions, so the
  measurement improves every time the house is walked.
- **Identical WiFi readings are discarded rather than believed.** Android
  throttles WiFi scans to roughly one per 30 s, so consecutive survey samples
  routinely carry byte-identical RSSI maps. Counting that as evidence two
  places are the same would be measuring the scan throttle. The dense survey
  is therefore carried mostly by BLE and the magnetometer, which do refresh at
  half-metre spacing.
- **The threshold for "distinguished" is derived from the data**, not chosen.
  It is the 90th percentile of the signal distance between samples taken at
  effectively the same spot on *different passes* — the size of difference
  that noise alone produces in this building. That fixes the false-alarm rate
  at about 10% by construction, which is what makes the curve readable: a
  band scoring near 10% is telling you it is indistinguishable from standing
  still.

#### What the survey had to work with

A weak resolution curve has two explanations that point in opposite directions:
the building genuinely has few distinct signals, or the app is failing to use
the ones it has. The first means stop walking and buy hardware; the second is a
bug. Nothing else in the report separates them, and guessing wrong costs either
an afternoon of laps or a purchase, so the report also counts what went in —
distinct access points and BLE devices, how many were visible at a time, and
which signal type actually carried each comparison.

The pair tallies and the inventory come apart in one specific way worth
watching. Android throttles WiFi scans to about one per 30 s, so a survey can
see a dozen access points while barely using them: the readings are frozen
between scans, and identical readings are discarded rather than believed.
`pairsWifiStale` measures that directly.

In practice it fires rarely, and the JVM runs showed why: the 15-second time
gate already removes same-pass pairs, and pairs from *different* passes carry
different shadowing, so byte-identical WiFi across two passes essentially never
happens. The stale-scan guard is belt-and-braces rather than load-bearing —
which is itself useful to know, since it means a weak curve with WiFi
participating in most comparisons is a statement about the building rather than
about the scan throttle.

Physical separations come from dead reckoning, which drifts. That matters far
less than it sounds: drift accumulates over a walk, while every comparison here
is between two points a few metres apart, over which the relative error is
small. The bands the whole exercise turns on — half a metre, one metre — are
exactly the ones dead reckoning gets right.

#### What it does on synthetic homes

The analysis was checked against simulated buildings whose true resolution is
known, because a metric that reports a confident figure for random noise is
worse than no metric. Six controls, 380-760 samples each:

| Case | Median error | Resolved at | 0.5-1 m band |
|---|---|---|---|
| Signals carry **no** location information | 4.06 m | nothing | 12% |
| Clean signals, almost no noise | 0.13 m | 1.0 m | 64% |
| Realistic: 3 dB noise, 3 dB pass shadowing, 1 m DR drift | 1.42 m | nothing | 24% |
| Sparse: 3 access points, no BLE | 2.54 m | nothing | 6% |
| Small flat, clean signals, nothing ever 8 m apart | 0.14 m | 1.0 m | 50% |
| Single pass, no revisits | — | refuses | — |

The first row is the one that matters most. Signals with no spatial structure
produce a flat curve pinned at 9-12% across every separation band, and the
report declines to claim any resolution — the metric is not fooled by noise.
The last row is the second: one pass is refused outright rather than scored
against itself.

Two bugs surfaced from these runs. `resolvedAt` originally stopped at any band
with too few pairs, which meant a small flat — where nothing is ever 8 m apart —
reported that it resolved nothing, purely for being small; an empty widest band
is absent evidence, not contrary evidence. And the shortfall message originally
collapsed "there is real structure here, just not reliable enough" into "this
building has too few distinct signals to fingerprint at all", which would have
told a user with a perfectly fixable survey to give up.

Cost at the survey cap of 1200 samples (over 700,000 pairs) is a few hundred
milliseconds on a desktop JVM, which is why the report runs on
`Dispatchers.Default` rather than the main thread.

### Printed markers: naming the exact cabinet

Everything else in this app answers "where am I?". This answers "what am I
looking at?", and it exists because the first question cannot be pushed hard
enough to answer the second.

Passive signals place a walker in the right room and, measured by the dense
survey above, no closer than a metre or two. Cabinets stand 30-60 cm apart and
look identical. That gap does not close with better filtering, more survey
passes or a smarter matcher — it closes when identity stops being *inferred*
from a position and starts being *read*. WiFi RTT would have helped and no
access point here answers ranging; UWB would solve it outright and needs
provisioned anchors at both ends. A printed marker costs a sheet of paper.

**The marker.** A 6x6 grid of cells: a one-cell black border, and 4x4 = 16
payload bits inside. The border is what makes a marker findable at all — it
produces a solid dark quadrilateral that survives thresholding under nearly any
lighting. Sixteen bits could name 65,536 markers and that would be a mistake:
at a few pixels per cell, bits get read wrong, and if every pattern were valid
a misread would silently return *a different cabinet*. So `MarkerDictionary`
keeps only codes at least 6 bits from each other **and from every 90-degree
rotation of each other**, generated greedily rather than tabulated so the
printed pattern and the decoder cannot drift apart. That buys three things: two
wrong bits are corrected outright, the marker's orientation falls out of which
rotation matched, and a bad read returns nothing instead of a wrong answer.
Twenty-three markers survive the constraint, which is 23 distinguishable
objects.

**The pipeline**, all in `vision/` and all dependency-free: local-mean adaptive
threshold over an integral image → Moore-neighbour contour tracing →
Douglas-Peucker polygon fitting, keeping convex quads → Heckbert square-to-quad
homography → Otsu over the 36 sampled cells → dictionary lookup. Every stage
exists to discard candidates; a room yields hundreds of dark outlines, a handful
of quads, and zero or a few real markers. The border-is-black test alone removes
essentially every door frame, picture frame and shadow edge.

OpenCV was deliberately not used. It would add well over a hundred megabytes of
native libraries for six algorithms that fit in a few hundred lines, and — the
real cost — it would put the part of this app that most needs measuring behind a
wall the JVM harness cannot see through.

#### What it actually detects

Measured against synthetic photographs rendered through a pinhole camera, with
perspective, roll, motion blur, sensor noise and a lighting gradient across the
frame. 40 trials per row, at 640x480 with a 65-degree field of view:

| Condition | Correct |
|---|---|
| 15 cm marker, head-on at 1 m / 2 m / 3 m / 4 m | 100% / 98% / 100% / 98% |
| 15 cm at 2 m, 30 / 45 / 60 degrees off-square | 98% / 93% / 95% |
| 2 m, motion blur | 95% |
| 2 m, heavy sensor noise | 98% |
| 2 m, harsh lighting gradient | 98% |
| 2 m, blur + noise + lighting together | 95% |
| 6 cm sticker, head-on at 1 m / 1.5 m | 100% / 95% |
| 6 cm sticker at 1 m, 40 degrees off-square | 93% |
| **Wrong marker id reported, anywhere above** | **0** |
| **False markers across 120 cluttered, marker-free frames** | **0** |

The app runs analysis at 1280x720 rather than 640x480, so real range is roughly
double the table.

Those figures are about 1.5x better in range than the first run, and the reason
is worth recording. The first pass showed hard cliffs — 100% at 3 m and 0% at
4 m — which is not how physics degrades. Sweeping the quad filter's minimum side
against apparent marker size showed why: the filter, not the decoder, was
binding. The payload still reads 100% correctly at 16 px a side (under 3 pixels
per cell), while `MIN_SIDE_PIXELS` was set to 20 and therefore discarding
readable markers unread. Detection only genuinely collapses below 14 px.

It is now 14 rather than 12, and that is a deliberate trade. False positives on
cluttered frames first appear at 10, so 12 would have taken 17% more range with
one step of margin. Naming the wrong cabinet is a far worse failure than failing
to name one: the user can always step closer, but has no way to notice a
confident wrong answer.

Detection costs about 6 ms per 640x480 frame on a desktop JVM. Frames are
analysed on a background executor with `KEEP_ONLY_LATEST`, so a slow frame is
dropped rather than queued and what is analysed is always what the camera is
pointed at now.

**Privacy.** Frames are read and discarded; nothing is stored. Camera permission
is requested at the feature rather than at launch, so declining it costs only
this card. The app still holds no `INTERNET` permission, so a frame has nowhere
to go even in principle.

## Project layout

```
app/src/main/java/com/wesrable/positioning/
  model/            Orientation, WifiSignal, BleSignal, Anchor, PositionEstimate,
                     Fingerprint, RoomEstimate...
  sensors/          OrientationSensor, BarometerSensor, MagnetometerSensor, StepDetector
  scan/             WifiScanner, BleScanner, BleAdvertisementParser, RttRanger,
                     MarkerAnalyzer
  vision/           MarkerDictionary, AdaptiveThreshold, ContourTracer, QuadFitter,
                     Homography, MarkerDecoder, MarkerDetector
  positioning/       RssiDistance, Trilateration, DeadReckoningTracker, RoomAnchorMap,
                     MagneticSequenceMatcher, Relocalizer, SurveyTracker,
                     LoopClosureTracker, PositioningEngine
  fingerprint/      FingerprintStore, FingerprintMatcher, FingerprintCrossValidation,
                     MapStore, SurveyStore, MarkerStore
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
