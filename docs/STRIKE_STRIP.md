# Strike strip: Android first pass

STRIKE lets one thumb choose soft-to-hard hits while the other hand plays
the pads. It uses the kit's existing SOFT HITS layers and velocity-zoned
sample chains. This pass establishes the native performance control;
new brightness, reverb and delay layer generators come later.

## Try it

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
  use that velocity or a fixed 1.00 multiplier, preserving pad level and
  pan. Fixed gain does not normalize the WAVs.
- The value is sampled on a hit. Audio continues through the existing
  cached-sample PadEngine; no synthesis or effect rendering runs on touch.
- Normal PADS and its fullscreen view share a state object. Settings are
  local performance controls, not new fields in `kit.json` or an MPC
  export. MIDI input, beat playback and other playing screens retain
  their existing velocity behavior.

This is discrete layer selection, not continuous modulation of reverb,
delay feedback or a ringing note. A later FX pass can render alternate
layers before playing and use the same hit-selection path. Live tail
modulation would need a separate audio design.

## Checks

With an Android SDK and JDK 17:

```sh
./gradlew --no-daemon test -x :app:test
./gradlew --no-daemon :app:assembleDebug :app:compileDebugAndroidTestKotlin
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
