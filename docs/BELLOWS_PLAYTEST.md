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
