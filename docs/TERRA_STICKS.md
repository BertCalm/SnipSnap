# TERRA's stick voices: the Japanese festival set

Nine TERRA voices added on 2026-11-20: ODAIKO, NAGADO, SHIME, KA, KANE, CHAPPA,
BINZASARA, COWBELL and WATER_DRUM. They live in `TerraSticks.kt`, play the fitted
tables in `TerraSticksTables.kt`, and share TERRA's output stage, velocity and
takes.

**Status: kits round 1, not engine-complete.** The voices render from the app's
engine, have provisional presets, play authored rolls and risers, and fill three
genre kits on the shelf whose velocity and round robin are verified through save,
export and playback. Nobody has heard the app's renders or played them on a phone
or an MPC yet. See [Gates](#gates).

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

## Rolls and risers

`TerraRolls.kt`. A stick-voice pad can be a ROLL or a RISER instead of a HIT
(`TerraArticulation`), saved in its recipe. A hit writes the bytes TERRA has
always written; a roll or riser writes `"version": 3` and its articulation, so an
older build refuses it by name instead of playing one hit and dropping the roll
on re-save (the same rule a struck pad's version 2 follows).

A roll is real strokes of the voice, never one sample looped: two hands
alternating (the second at 0.86 of the first), each stroke its own renderer take
from a cycle of eight, timing moved up to 4 ms either way, tails ringing under
the next stroke.

- **ROLL:** 2.0 s at the voice's own rate (5 strokes a second for the binzasara,
  whose every stroke is a fall of slats, up to 15 for the shime).
- **RISER:** 3.0 s, speeding up exponentially (for example 3 to 12 strokes a
  second on the odaiko) and growing from a quarter of the touch to all of it, so
  every stroke is brighter than the last, not only louder.
- **Touch and take:** the touch scales every stroke's force, so a soft roll is
  darker. A kit take is a different roll: its strokes draw renderer takes 16
  apart, so takes 0 to 3 share none.

Rolls are tempo-free, as BACKWASH's tambourine roll is: `render` receives no
tempo, so a roll is printed at the voice's rate and does not follow a kit's BPM.
Rendered they last 2.5 to 4.4 s, and the classifier files them as phrases (LOOP).

Cost: one stroke render per distinct force and stroke take, rendered in
parallel. That is over the house's tens-of-milliseconds rule for a one-shot; a
roll is a phrase pad and re-renders only when its recipe changes.

## The kits

`TerraStickKits.kt`. Three kits of sixteen pads, each a different musical job,
not one kit at three strengths. No recipe is shared between them.

| Kit | Tempo | Lead voices | Job | Rolls and risers |
|---|---|---|---|---|
| TERRA MATSURI | 90 | NAGADO | Street festival: don low, shime ten and tsu as the time, ka answering, kane and chappa on top, one odaiko accent | Ten Roll, Don Riser |
| TERRA THUNDER | 70 | ODAIKO | Big drums: both odaiko and their pair, three tuned nagado toms, rim cracks, a cymbal wash, a low gong | Odaiko Roll, Odaiko Riser, Tom Roll |
| TERRA BON | 115 | WATER_DRUM, COWBELL | Dance: water drum and shaken bell lead, shime and a soft nagado under them, slats as the shaker; no odaiko | Slat Roll, Bell Riser |

Every pad carries three touches (a third, two thirds and all of a full strike)
by four takes. A touch is rendered at its velocity through the voice's own law
and peak-matched to its take's hard hit, as every velocity zone in the house is,
so level is the hardware's velocity curve's job. A take is the voice's own next
landing (renderer takes 0 to 3). Grid pads carry no PadRecipe; each pad's recipe
rides in its source as JSON, as TERRA's round-robin kits' do.

Mute groups: MATSURI's open and choked chappa cut each other; BON's three bell
pads cut each other (the riser excepted).

Build time, all sixteen pads of twelve samples each, one machine: 11 s
(MATSURI, BON) and 24 s (THUNDER, whose odaiko pads and three rolls are the
slowest).

The listening page for this round is
<https://claude.ai/artifact/ECBiXKd9cS9E44gZBhkNmU> (private to the owner).

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
  renders. The kits round's page is the first time the app's own renders are
  played to the owner.
- **The kits are provisional.** Their arrangements, levels and roll rates were
  written from the prototype's rounds; none is pinned.
- **The classifier files a few pads outside PERC:** the binzasara as CLAP, BON's
  small cymbal and slat snap as SNARE. Each pad's class is what the classifier
  hears, as in every TERRA kit.
- **MPC 2 keeps each zone's first take.** A pad with both velocity layers and
  round robin cannot keep both in MPC 2 (the owner's 2026-11-14 format limit);
  MPC 3 keeps all four takes.

## Gates

Applied from [ENGINE_ACCEPTANCE_GATES](ENGINE_ACCEPTANCE_GATES.md).

| Gate | Status |
|---|---|
| Melodic, keygroups, chord tools, STRUM | Not applicable: percussion voices on a drum engine |
| Percussion: authored roll/riser | Implemented: ROLL and RISER on every stick voice, saved in the recipe, seven roll and riser pads across the kits. Not yet heard |
| Drums: multiple distinct genre kits | Implemented: MATSURI, THUNDER and BON on the shelf, distinct lead voices and jobs. Not yet heard |
| Velocity levels | Pass, automated: three timbral touches per pad, each softer one darker (`TerraStickKitsTest`), kept through shelf render, save and reload, MPC 3, MPC 2, SFZ and playback at all 127 velocities (`TerraStickKitsRoundTripTest`) |
| Round robin | Pass, automated: four distinct takes per touch, dealt in turn over nine hits in playback and kept through save, MPC 3 and SFZ. MPC 2 keeps each zone's first take (owner's format limit) |
| Hardware | Not attempted: no phone or MPC playback yet |
