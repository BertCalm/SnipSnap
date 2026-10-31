# Strike strip and FX listening demo

STRIKE lets one thumb choose soft-to-hard hits while the other hand plays
the pads. It uses the kit's existing SOFT HITS layers and velocity-zoned
sample chains. The STRIKE FX starter makes the difference easier to hear:
the same short hit dry, with a short reverb, or with clearly spaced echoes.

## Try the FX demo

The optional **SnipSnap STRIKE FX** build installs alongside existing
SnipSnap apps with its own empty shelf. It uses the same application code.
CI keeps its APK in the `snipsnap-strike-fx-<commit>` artifact. Download
through the GitHub Actions artifact page while signed in; that route creates
fresh download authorization when clicked.

1. Create a **STRIKE FX** starter (near FACTORY in the starter list).
2. Turn **STRIKE ON**, select **STAY**, and leave **VOL OFF**.
3. Tap **A01 RIM** with the strip at the bottom, middle, and top. Those
   positions select **DRY**, **REVERB**, and **ECHO**. Leave about two seconds
   between comparison hits so the previous tail finishes.
4. The **LAST A01** row highlights the layer actually triggered. Sliding
   without tapping leaves that highlight unchanged. RETURN resets the
   next strike to the bottom; it does not relabel a ringing echo as dry.
5. Try A02 CLAP, A03 SNARE and A04 BLOCK, then **FULL** to play with both hands.

These are three discrete choices. No change is expected while moving
within one band. VOL OFF keeps the playback multiplier fixed; equal peaks
do not guarantee equal perceived loudness for a dry hit and its longer tail.
The remaining twelve pads are available for captures.

## Use your own sounds

1. Open a kit with SOFT HITS on at least one pad; the VELOCITY starter is
   a ready-made example. To add them, open that pad's PAD SHEET and use
   SOFT HITS. A plain single-sample pad has no
   alternate tone for the strip to select.
2. On PADS, turn STRIKE ON. Choose LEFT or RIGHT for your thumb.
3. Hold or slide the strip while tapping the layered pad with your other
   hand. Top is hard, bottom is soft. Each new hit takes the strip's value
   at finger-down; moving it does not retrigger or change an existing voice.
4. STAY holds the last setting. RETURN goes back to soft on release or
   gesture cancellation. VOL OFF holds playback gain steady; VOL ON also
   scales gain with strike strength. The recordings themselves can still
   differ in loudness with VOL OFF.
5. Tap FULL for the landscape grid. It uses the same strip settings and
   playback host. EXIT FULL returns to the windowed grid.

STRIKE starts off. When off, the original pad-position response applies:
top of a pad is soft, bottom hard. The strip is a separate vertical fader,
whose direction follows its visible HARD / SOFT labels. Accessibility
adjustments and arrow keys also change its value.

## What this pass changes

- The strip maps 0–100% to MIDI 1–127 for layer selection, giving each
  evenly spaced layer roughly equal thumb travel. Linked volume uses the
  existing 0.20–1.00 gain range so a soft hit is audible. Keeping that gain
  floor out of layer selection prevents a tiny, hard-to-reach soft zone.
- Layer and chain-zone selection use strike velocity. Playback gain can
  use the separate 0.20–1.00 curve or a fixed 1.00 multiplier, preserving pad level and
  pan. Fixed gain does not normalize the WAVs.
- The value is sampled on a hit. Audio continues through the existing
  cached-sample PadEngine; no synthesis or effect rendering runs on touch.
- Normal PADS and its fullscreen view share a state object. Settings are
  local performance controls, not new fields in `kit.json` or an MPC
  export. MIDI input, beat playback and other playing screens retain
  their existing velocity behavior.

The listening demo uses existing THUMP and TINES sources, SPRING for the
middle zone and ECHO for the top. Both effects process the same dry source
independently. Repeats are approximately 293 ms apart; the reverb's decay
setting is about 0.52 seconds. These are audition choices, not listening
claims. Each rack can add at most one second to the source. A 4 ms terminal
fade keeps the delivered tail from ending abruptly.

The starter renders its twelve WAVs once during creation and the pad
engine caches them when the kit opens. No DSP runs on thumb movement.
The target is under one second for this small starter on a phone; desktop
test timing is reported separately and does not establish phone performance.

Layer names are optional `name` fields in `kit.json`'s existing layer
objects. Older kits show LAYER 1, LAYER 2, etc.; unlayered pads show SINGLE.
Playback and the indicator share one zone selector, including its existing
fallback for a velocity outside all stored windows. Failed playback requests
do not update the indicator. Normal and fullscreen grids share the last-hit
snapshot, which clears when the kit changes. MIDI still uses its own velocity.

Names survive saving and supported standalone MPC exports with their
SnipSnap sidecars; the names are not new MPC parameters. The generated main
recipe reproduces the ECHO sample. Rebuilding that recipe alone does not
recreate all three layers; recreate the starter for the full demo.

General FX-layer authoring for arbitrary pads and continuous modulation of
a ringing note remain separate work.

## Why it is built this way

- Short attacks leave space to hear a room tail and separate repeats.
- Every version starts with the same source, so the effects supply the contrast.
- Pre-rendered WAVs use the existing performance and export paths.
- The last-hit highlight confirms the selected sound even when RETURN moves
  the thumb control back. Perceived contrast still needs a phone audition.

## Checks

With an Android SDK and JDK 17:

```sh
./gradlew --no-daemon test -x :app:test
./gradlew --no-daemon :app:assembleDebug :app:compileDebugAndroidTestKotlin
./gradlew --no-daemon :app:assembleStrikeFx
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.snipsnap.app.ui.StrikeStripTest
```

The JVM tests exercise every layer, independent gain, chain zones, the
disabled path and range handling. The Compose tests exercise simultaneous
thumb/pad pointers, later hits reading a new value, return on release and
cancel, side switching, accessible adjustment, minimum pad size and a
reused pad reading the current kit owner's callback.

Before treating the control as phone-tested, audition it on real hardware:

- Roll on two or more pads while moving either thumb; listen for dropped
  hits, extra hits and clicks. Check the softest and hardest layers.
- Try both sides in portrait and FULL, then leave fullscreen and switch
  kits. Check readability and reach on the smallest supported screen.
- With RETURN, lift the thumb, cancel the gesture and background the app.
  The next hit should use the soft setting. With STAY it should retain it.
- Compare VOL OFF and VOL ON on the same layered pad. Confirm pad level,
  pan, choke groups, gated releases and PANIC still behave as expected.
- Play MIDI and the project beat with the strip at both extremes; their
  own velocities should remain effective. Export and reopen the kit to
  confirm its existing layers still play as before.

Emulated touch tests cannot establish hardware latency or playing feel.

## Implementation evidence

Built against `e982cc63432022e92327d465dc64d75459c7d153`. The focused Kotlin
checks measured the RIM layer's reverb energy from 120–240 ms at 0.0364
times the dry attack energy. ECHO is silent in that interval; its first
and second repeats carry 0.7583 and 0.1639 times the attack energy. These
are energy ratios, not perceived loudness ratings. Starter creation,
including twelve WAV writes, took 288 ms in the first desktop test run.

Verified: deterministic finite renders, bounded tails, shared dry attack,
all three zones reachable, saved layer names, and complete tails selected
by the pad resolver. Proposed: these settings make the contrast clearer
on the phone. Heard: pending the owner's audition.
