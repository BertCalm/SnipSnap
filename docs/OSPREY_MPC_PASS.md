# OSPREY R3 · MPC listening pass

The owner confirmed “Sounds good in app, let's proceed” on 2026-10-18.
Keep R3's sound fixed while checking the Live III handoff. Phone save/reopen,
recipe replay and MPC playback have not yet been reported.

## Load the kit

Download **R3 kit ZIP** from the [audition page](https://snipsnap-osprey-audition.bertcalm.chatgpt.site/#kit)
and unzip it. The existing download is 22,961,165 bytes; its SHA-256 is
`5c63a7966721ed7897c84422f80553289e5fe42fe3eba5f666c2576e6096bd91`.
The samples, recipes and exports are unchanged from the published handoff.

1. Copy the contents of `SnipSnap Osprey R3/mpc/` to your MPC drive.
2. On MPC 3, open `SnipSnap Osprey R3.xtd`. Keep it beside
   `SnipSnap Osprey R3_[TrackData]/` with all sixteen WAVs inside.
3. To check the compatibility format separately, open
   `SnipSnap Osprey R3_[TrackData]/SnipSnap Osprey R3.xpm`.
   Use a separate track or project so the two versions can be compared.

The two formats share the same WAVs. Keep their names and folder structure.
These are raw, dry, stereo PCM24 samples at 44.1 kHz, C4, eight seconds each;
the web audition's matching gains are not applied. Start at a comfortable volume.

## Listen on the Live III

- [ ] All sixteen pads load. A01 is bottom left; the first fifteen are
  Atlantic, Nordic, Mediterranean, Pacific and Southern, each in calm/rough/storm
  order. A16 is BETWEEN SHORES.
- [ ] Play A02, A05, A08, A11 and A14 one at a time at the same strike velocity
  (use Full Level if available). Each is the rough version of one coast.
  Compare their warmth, water movement and creature calls with the app.
- [ ] Let each hit finish before the next. The complete eight-second ending
  should play without a cut tail, click or unwanted loop. Check A16 too:
  HOLD 1 extends its gesture but still ends.
- [ ] Check stereo image and pitch. The sea gestures should remain at their
  original C4 sample pitch; octave naming can differ across devices.
- [ ] Overlap A02, A11 and A16, then lift your fingers. Listen for unexpected
  choking, harshness, clicks or missing tails. Compare single hits again if
  their combined level is too loud.
- [ ] Repeat the loading and listening checks for the XPM on a separate track.

## Finish the phone workflow check

Use **NEW KIT → STARTERS → OSPREY** for editable recipes. Save/reopen the kit,
reload a pad's recipe and regenerate it. Confirm model 3, macros, note, velocity,
seed and duration survive, with the same gesture. Android's XPM ZIP import loads
samples without these saved recipes; bundled `kit.json` is for KitStore/desktop
tooling.

## Record the result

```text
Phone / app build:
Save/reopen and recipe replay: PASS / FAIL / NOT CHECKED
MPC model / firmware:
Native XTD: PASS / FAIL / NOT CHECKED
Compatibility XPM: PASS / FAIL / NOT CHECKED
Pad(s), if different:
Difference heard or exact loading error:
```

Return this short result or record it in [BENCH B8](BENCH.md#b8--osprey-r3-handoff).
The artifact checks prove file integrity, recipe reproducibility and export
read-back; hardware playback still needs this listening pass. A musical demo
follows once the handoff is confirmed.
