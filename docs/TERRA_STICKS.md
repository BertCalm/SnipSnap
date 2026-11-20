# TERRA's stick voices: the Japanese festival set

Nine TERRA voices added on 2026-11-20: ODAIKO, NAGADO, SHIME, KA, KANE, CHAPPA,
BINZASARA, COWBELL and WATER_DRUM. They live in `TerraSticks.kt`, play the fitted
tables in `TerraSticksTables.kt`, and share TERRA's output stage, velocity and
takes.

**Status: prototype round, not engine-complete.** The voices render from the
app's engine and have provisional presets. No kit, no roll or riser pad, and no
velocity/round-robin evidence through save, playback and export exists yet. See
[Gates](#gates).

## The gesture

The stick, the clapper or the slats land, once or as a train → the object's own
notes ring, each from the level and phase that landing gave it → band noise
fills what the notes leave out of each landing's first tens of milliseconds →
silence.

## Where the sound comes from

The owner supplied 42 reference recordings on 2026-11-19 and -20: forty loops
and builds from a commercial sample pack, and two single big-drum hits. They
were measured only. No audio from them is in the repository or the app.

Each voice is fitted to one clean hit cut from a reference, the way
`TerraFitted`'s hand drums were ([TERRA_HANDS](TERRA_HANDS.md), "measured
partials plus a fitted residual"):

1. **Notes.** The strongest spectral peaks, each with a ring time read from how
   fast its band falls, and a sine and cosine level solved by least squares
   against the waveform.
2. **Residual.** Per third-octave band, noise with its own level and decay,
   solved so the band's energy matches the reference in an attack window (0 to
   30 ms) and a body window (30 to 300 ms), never above the reference.

The fitting scripts and the fitted models are in `prototype/taiko/`.
`gen_kotlin.py` writes `TerraSticksTables.kt` from them.

## The voices

| Voice | What it is | Fitted to | Own macros |
|---|---|---|---|
| ODAIKO | two big drums | the owner's two single hits: a chu-daiko duo hit (notes at 66, 91, 113 Hz) and a big taiko hit (61, 85 Hz) | DRUM: 0 the first, 1 the second, between both together |
| NAGADO | mid barrel drum, 119 Hz | a hit from an ensemble loop | none |
| SHIME | small rope-tensioned drum, 307 Hz | two hits from two loops | STROKE: 0 and 1 the two strokes |
| KA | stick on a drum's rim | a hit from a clicks loop | none |
| KANE | atarigane hand gong | a centre stroke (708 Hz) and an edge stroke (from 3.4 kHz) | EDGE: 0 centre, 1 edge |
| CHAPPA | hand cymbals | a hit with one second clear | none |
| BINZASARA | wooden slats on a cord | the single-throw reference | SPREAD: the fall's length, half to twice the measured 185 ms |
| COWBELL | a clapper bell, shaken | the ringing reference, whole gesture | SHAKE: 1 the measured train, 0 one strike. DAMP: a hand on the bell |
| WATER_DRUM | water drum, 183 Hz | a hit from the short reference | none |

All share TUNE (+-5 semitones), DECAY (0.5x to 2x), FORCE (the contact, softer
to harder) and ATTACK (the landing's noise, +-9 dB).

The cowbell has no contact pulse (metal on metal: its notes start at once), so
there FORCE is the clapper's hardness instead: its upper notes and strike noise
down or up around the fitted stroke. `TerraSticksTest` caught FORCE as a dead
knob on the cowbell before this was added.

## What the listening rounds changed

The owner listened to Python renders on a published page over six rounds.

- **Round 1.** Eleven voices fitted as single strikes. Verdict: clapper and
  cowbell need work, big drum muddy.
- **Round 2.** The big drum had been fitted to a hit cut from a dense loop: four
  notes between 78 and 167 Hz ringing 3 to 4 s with no stick attack. It was
  rebuilt from the nagado, scaled down (later dropped). The clapper became what
  the reference is: 21 slat clicks over 185 ms. The cowbell lost a 3 s noise
  tail. Verdict on the cowbell: "sounds like a regular bell".
- **Round 3.** The cowbell reference is three clapper strikes (0, 139, 345 ms,
  rising in level) and a rebound at 708 ms before the ring; rounds 1 and 2 had
  fitted the loudest strike alone. The strike train was added.
- **Round 4.** The owner sent two single big-drum hits. Both were fitted (2.3 and
  2.6 dB, 4.9 and 1.6 dB over third octaves, attack and body). The owner kept
  both.
- **Round 5.** The owner sent the cowbell reference again. Each note now has its
  own level at each strike (the first two strikes mostly ring 722 Hz, the third
  brings in 1.9 kHz and a cluster near 6.2 kHz), and strong notes are close
  pairs that beat. Error across the gesture fell from about 6 dB to 2.5 to
  4.3 dB. Verdict: "better".
- **Round 6.** Both big drums on one voice, and together. The owner asked for
  all the voices.

## The Kotlin port, measured

Against the Python render of the same tables (full velocity, take 0), over third
octaves in the attack and body windows: 2.2 to 6.5 dB attack, 1.8 to 5.6 dB
body. Two Python takes of one voice differ by 2 to 8 dB in the attack, because
the residual is noise, so the port is within one take of the prototype. The
output stages differ (`TerraBox.finish` here).

Render time, warm, one voice: 18 to 150 ms (BINZASARA and ODAIKO the slowest:
441 noise bands, and a 1.6 ms contact convolved at four times the rate).

Velocity, soft (0.3) to full, centroid of the first 30 ms: +38% (KA) to +163%
(ODAIKO) on every voice at its defaults.

## Disclosed, not fixed

- **KANE's edge stroke is volume-only with velocity** (+4%). Its energy sits in
  one narrow region, so the velocity law has little to tilt.
- **The cowbell's waveform match is 51%.** Its spectrum over time is close; its
  fine detail is not. Its strikes are a quarter to a half louder against its
  ring than measured.
- **Ring times are lower bounds** wherever the reference was a loop: the next hit
  cut the tail (0.25 to 0.45 s clear on most drum voices).
- **Every pad is cut to 1.45 s or less**, under the classifier's 1.5 s. ODAIKO's
  first drum rings 2 to 3 s in the reference.
- **The fan in the references is not built.** It is a noise gesture, not a
  struck object.
- **The presets are provisional.** They were written from the prototype's
  rounds. Nobody has heard them from the app, so none is pinned.
- **Nobody has heard the Kotlin render.** The owner's verdicts are on the Python
  renders.

## Gates

Applied from [ENGINE_ACCEPTANCE_GATES](ENGINE_ACCEPTANCE_GATES.md).

| Gate | Status |
|---|---|
| Melodic, keygroups, chord tools, STRUM | Not applicable: percussion voices on a drum engine |
| Percussion: authored roll/riser | Open. The prototype has five roll builds; none is in the app |
| Drums: multiple distinct genre kits | Open. No kit uses these voices yet |
| Velocity levels | Rendering only: measured above. Not yet verified through saving, playback or export |
| Round robin | Rendering only: takes are distinct landings. Not yet verified through saving, playback or export |
| Hardware | Not attempted |
