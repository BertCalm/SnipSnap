# CUTTINGS device check

Start with Pocket Finds; then repeat with Night Fragments and Cut Circuit. These are test packages, not hardware-certified releases. Glue is the approved full-mix treatment; the playable pad programs contain original individual hits.

## Files

Run `python3 scripts/package-cuttings-device-check.py /path/to/generated/kits /path/to/device-check` after `:shell:generateCuttingsKits`.

- `<genre>-mpc3.zip`: one `.xtd` plus its matching `_[TrackData]` folder. Extract together and keep their names and relative locations intact. Load the track on MPC 3.
- `<genre>-native.zip`: a SnipSnap backup-style folder containing `kit.json` and the referenced WAVs. Share the ZIP into SnipSnap; it should land as a separate kit. This exercises import on an existing app; testing the new starter shelf requires the CUTTINGS APK build too.
- `<genre>/A02-take-order.wav` and `.csv`: reference backbeat, eight hits per layer, ordered 1 2 3 4 / 1 2 3 4 at soft, medium, hard. The CSV gives suggested MIDI velocities and onset times. References use decoded source windows before device velocity gain; compare character and order rather than output loudness.
- `<genre>/<genre>-glue.wav`: four-bar full-mix performance plus a decay tail, not a seamless loop or a live effect.
- `SHA256SUMS`: integrity hashes. Do not rename or edit files inside a kit before testing.

## First pass

1. Confirm 20 pads across A01–A16 and B01–B04. A01 is the lead kick; A02 is the backbeat. A03 is closed hat, A07 open hat.
2. Program eight evenly spaced A02 hits at velocity 25, then eight at 70, then eight at 110. Disable full-level, randomization and device effects for this check. Listen for four repeating performances at each layer; compare the reference WAV. Capture the device output rather than relying only on labels.
3. Check layer boundaries with repeated velocities 41/42 and 84/85. Soft, medium and hard should change source timbre as well as loudness. On the phone, use available velocity controls or MIDI; finger force on a touchscreen is not a velocity test.
4. Play A07 then A03 quickly. The closed hat should stop the open hat. Check that long phrases on A15/A16, B01/B02 and B03/B04 play through once rather than cutting off on pad release. They are authored at the kit's saved tempo: 89, 76 and 124 BPM respectively.
5. Save on the device, close and reopen the kit, then repeat the velocity/take test. Re-export and reload once to test the target's own round trip.
6. For the open MPC slice-map gate, save the track from MPC 3 and retain the `.xtd`, complete data folder and a short audio capture of step 2. The generated metadata alone is not proof of correct slice selection.

A02 is a quick sentinel, not full-kit certification. Before closing the gate, repeat the layer/cycle check on all 20 pads; leave enough spacing for phrases to finish. Record device model, OS/firmware, app version or commit, import format, kit, pad, velocity and observed sequence for any mismatch.

## Listening decisions still open

Use the compact factory audition for Night Fragments, Cut Circuit and the two-/four-bar roll/riser pads. Record Keep or Revise for each new arrangement and phrase; choosing Glue does not by itself approve those separate sounds.

## Results

| Check | Phone | MPC 3 |
| --- | --- | --- |
| Import and 20-pad layout | Not run | Not run |
| Velocity/take cycle on every pad | Not run | Not run |
| Hat choke and phrase completion | Not run | Not run |
| Save/reopen/export/reimport | Not run | Not run |
| Saved slice-map and capture evidence | N/A | Not captured |

MPC 2 remains a separate documented exception: its export retains each velocity layer's first take, not four cycling takes within each layer. Do not use it to claim the round-robin gate passed.
