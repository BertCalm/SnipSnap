# Tap rhythm reset

The owner rejects the microcontact brushes as worse and the groove as incoherent,
asking for rhythmic, nimble tap. This is a rhythm-only study with the earlier
preferred contact model: 120 BPM, a four-beat count-in, and a two-bar motif twice.
Three previews introduce main accents, then pickups with a click, then remove the
click after the count-in. The event plan is saved in plan.json.

## Why it is built this way

- Repetition and a stable pulse make the intended rhythm identifiable.
- Light pickups and rests articulate the phrase without a repeated heavy stomp.
- The same contact renderer avoids conflating another timbre change with rhythm.
- This is authored music, not verified tap choreography. Brush realism and all
  native/source/export acceptance gates remain open. The previous brush experiment
  and native model 4 remain rejected for naturalness.

Three renders pass deterministic replay, finite/headroom and PCM16 save/read
checks. No native changes or new end-to-end gate pass is claimed.

Run `python3 prototype/footwork/rhythm_reset/build.py /tmp/rhythm-reset`.
