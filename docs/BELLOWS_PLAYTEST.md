# BELLOWS phone / MPC validation

Dry listening accepted the four identities and BODY/PRESSURE differences. The accepted DSP is unchanged.
Hardware playback has NOT been verified. Model 1 remains frozen; these keygroups use model 2 only.

Four melody programs cover MIDI 48..72 (C3..C5): nine minor-third roots, each with soft/mid/hard layers
at source velocities .35/.65/1, MIDI windows 1..42/43..84/85..127. Each sample has its source attack and
a measured sustain loop. The program supplies note-off release; the host supplies velocity gain once.
WHEEL melody has DRONE=0. Bellows Tonic C3 is a separate WHEEL bed mapped only to MIDI 48; its tonic
cannot follow a melody on another track. This is no shared live bellows/aftertouch model.

## Files

Keep each card/<Name>.xty beside card/<Name>_[TrackData]/. That folder contains its WAVs and the
MPC 2 <Name>.xpm twin. card/<Name>.instrument.json is the phone's sidecar. Preserve its relative paths.
recipes/ retains the original preset and every melody layer's exact model-2 source recipe.
The key adapter and host-gain convention in manifest.json are also required to reproduce a layer:
rendering a source recipe alone includes native velocity gain and is not the exported layer PCM.

## Phone

MAKE INSTRUMENT's preview holds C4 at velocity .65 for six seconds, then releases
over the selected RELEASE time. It uses the exported layer's gain convention and
the JVM sample-player reference, including the sustain loop, rather than playing
the source buffer to its end. Compare RELEASE .10 and 1.50 seconds before making
the instrument. The native phone player and MPC still need the checks below.

The existing share/import door imports kits, not this instrument ZIP. Do not import this ZIP as an .xpn.
Use SnipSnap's BELLOWS MAKE INSTRUMENT to create a package on the phone, or provision each sidecar
plus matching _[TrackData]/ folder into the app shelf's Instruments/ directory with a development
file-transfer method. The directory is app-private; ordinary Files copy is not a promised workflow.
Open MAKE > INSTRUMENTS, choose the instrument, then KEYS. An old app that cannot read velocity
sidecar version 2 must be updated; the .xty alone does not create an instrument-list entry.

- [ ] All four programs appear and open without missing samples.
- [ ] Play MIDI 48, 49, 50, 51, 60, 69, 70, 71, 72; check low/high zones and both sides of a zone boundary.
- [ ] On the grid, strike near the top/middle/bottom for soft/mid/hard; compare timbre as well as level.
- [ ] With MIDI if available, try velocities 30, 42, 43, 64, 84, 85, 110 and 127; no gap at layer boundaries.
- [ ] Hold a note for at least 12 seconds (longer than several loops); no repeated attack, click or level jump.
- [ ] Release it; the configured fade ends. Play a triad and a wide chord; no excessive bus distortion.
- [ ] Hold then change OCT/layout, leave KEYS, background the app, or disconnect MIDI; no stuck note.
- [ ] Reopen the instrument and restart the app; mapping and layers remain present.

## MPC

Copy card/ contents together to the SD card/USB drive. On MPC 3 load <Name>.xty; on MPC 2 or for
the compatibility check browse <Name>_[TrackData]/ and load <Name>.xpm. Test both formats separately.

- [ ] All samples resolve after loading, saving a project, closing it and reopening it.
- [ ] Repeat the low/high-zone, soft/mid/hard, layer-boundary, long hold, release and chord checks above.
- [ ] Stop/all-notes-off ends held notes; reopening the project does not leave notes stuck.
- [ ] Put Bellows Tonic C3 on a separate track and hold MIDI 48 while WHEEL melody plays other notes.
- [ ] The bed stays C3 and is not retriggered by melody; other notes on its track do not repitch it.

Record device/model, app/firmware version, .xty or .xpm, program, note/velocity and a short recording
for any failure. Leave these boxes unchecked until tested on the named device.

Rebuild: `./gradlew :cli:generateBellowsInstruments`. Download: `testkit/bellows-audition/Bellows-phone-mpc-m2.zip`.

## Sampled-instrument listening comparison

Run `./gradlew :shell:generateBellowsPlaybackAudition` to render native-synthesis
and sampled-instrument pairs. Override the destination with
`-PbellowsPlaybackOutput=/absolute/output/directory`.
The generator exports real PCM24 WAVs and version-2 sidecars, reloads them, and
plays the same event schedule through `InstrumentEngine`. Its manifest records
zone roots, velocity windows, pitch ratios, note-off times and levels. Each pair
shares one gain; neither side is independently loudness-matched.

Native synthesis renders every exact note and continuous source velocity.
The instrument selects one of three source timbres, repitches its nearest zone
root and applies linear host velocity once. In particular, velocity .35 rounds
to MIDI 44 and selects the middle timbre. Listen to the soft notes, both sides of
the velocity and pitch-zone boundaries, the held chords and the release tails.
The JVM reference shares the phone's zone/gain/loop map, but its bus soft-clips
where the phone's native bus hard-clamps. The comparison keeps its bus below that
threshold. It establishes software behavior, not phone or MPC validation.

The approved model-2 source remains unchanged. Low-register held BEAT has
whole-cycle steps, and full WHEEL MOTION has a larger pitch excursion on low
notes. These are separate listening decisions; the playback work does not remap
either control.
