# BREAKER

BREAKER is a drum engine for surf rock by way of boom bap: drums as if sampled off a surf record,
pushed through a drum processor and a surf amp.

The characteristic gesture is **dusty crack → spring drip → crushed swell → speaker bark**.

[Design, references and listening rounds](superpowers/specs/2026-11-09-breaker-drum-engine-design.md).

## Status

Renderer model 6 (the genre kits' takes), a prototype outside the factory presets
(`PatchTypes.PROTOTYPES`). The BREAKER kit stays on model 5, which renders it exactly as the owner
accepted it on model 4.
Fourteen voices; the kick and snare are built from the owner's round-0 picks.

At round 1 the owner heard:
- the snare, its takes and a half-strength hit as theirs;
- the kick as "close, but not it".

Round 1b gives the kick back the tank and the slap time its pick was heard with. A model-1 save
keeps round 1's sound. On 2026-11-10 the owner heard the re-fit kick as the same kick, and the snare
(with the slap back at 95 ms), the takes and the half-strength hits as theirs.

Round 2 adds ten drums through the same rig: the closed and open hats, cross stick, shaker,
tambourine, three toms and ride. On 2026-11-10 the owner heard them:
- **The hats:** nearly dry.
- **The cross stick:** the short slap.
- **The tambourine and shaker:** both belong.
- **The toms:** drippy.
- **The repeat and 40% hits:** the same drum, played by a softer hand.
- **The ride:** sounds like a crash. Round 3 re-voices it, and its sound is a candidate for the crash.

Pad A15 becomes the snare riser, and the shaker moves to A16 (Bump moves to the genre kits).

Round 3 (model 3) re-voices the ride and adds the crash and the snare riser. Through the rig, the
first cymbals and riser were harsh: too loud next to the kit, fizzy and piercing. The owner asked
for the recorded cymbals to be replicated with synthesis instead. On 2026-11-11 they picked:
- **The cymbals:** the "tight" modal synth, heard dry ("these are fine"), in the kit dry by
  default, with every control able to dial the rig back in.
- **A13:** this ride. **A14:** this crash, ringing about 3 s.
- **A15:** the tamed riser.

Model-2 saves keep round 2's ride. On 2026-11-11 the owner heard the app's own renders of the
ride, crash and riser as their picks, the repeat and 40% hits as the same cymbal played softer, the
presets as bringing the rig back the way they want, and said to build the kit.

The kit is on the shelf as the BREAKER starter (below, "The kit"). On 2026-11-12 the owner heard it
in a groove as "very mid heavy, like listening through dollar tree headphones". Measured, nearly
everything sat between about 100 Hz and 4 kHz. Of four versions of the same groove, they picked the
open rig (below, "Model 4: the open rig"), and heard their snare, hat and kick through it as
"better".

 Not in the synth picker yet; that
entry waits for a session with an Android SDK, as the house rule says.

On the open tone (model 4) the owner accepted the kit (2026-11-12):
- **The balance:** "sounds right now".
- **The pushed row:** keep all three.
- **The kick hit again:** "one kick played again: fine".
- **Every pad, soft to hard:** softer in character, with a fresh hit each time.

The two genre kits, Dub Tank and Trip hop, are built to the layouts the owner approved (below, "The
genre kits") and wait for the owner's listen.

Still to come:
- the owner's listen of the genre kits;
- the surf tom fill;
- the audition page.

## The kit

The BREAKER starter (`BreakerKits`, `StarterKits` id `breaker`, under SKINS on the shelf), at
92 BPM, the tempo every BREAKER beat was heard at. A01 is bottom left.

| | | | |
|---|---|---|---|
| A13 Ride | A14 Crash (rings 3.2 s) | A15 Snare Riser | A16 Shaker |
| A09 Floor Tom | A10 Rack Tom | A11 High Tom | A12 Crushed Snare |
| A05 Crushed Boom | A06 Drip Snare | A07 Cross Stick | A08 Tambourine |
| A01 Boom | A02 Bap | A03 Closed Hat | A04 Open Hat (chokes A03) |

- **Every pad is a BREAKER patch** at its drum's defaults, so each pad keeps its recipe and stays
  editable.
- **The pushed pads** are authored and not yet heard:
  - A05 Crushed Boom: the kick at CRUSH 1, DUST .5, AMP .55;
  - A06 Drip Snare: the snare at DRIP .9, SLAP .35;
  - A12 Crushed Snare: the snare at CRUSH 1, DUST .5, AMP .55, DRIP .35.
- **Levels** come from the balance the owner heard in the round-3 beats: Boom .90, Bap .80, the
  hats and shaker .35, the toms .70, the ride .27 and the crash .32.
- **Chokes:** only the open hat chokes the closed one. The cymbals never choke.

**Velocity and round robin.** The shared robin (`Robin.apply`, as the BACKWASH kits) deals each pad
from its recipe:
- **Every synth drum: 3 zones × 3 takes.**
  - A softer zone is the drum re-rendered at velocity .3 or .6.
  - Every take after a zone's first is the drum struck again (`Restrike`).
- **The tambourine: 2 zones × 2 takes,** exactly its recordings.
  - A third zone would land on a dynamic layer already used, so it would differ only in level.
  - A third take would repeat the first.
  - With 3 × 3 it measured both: one zone was another only turned down, and two takes were the same
    recording, −119 dB apart.

Measured on the saved grid (`BreakerKitProof`), on model 4:

| Pad | Closest two takes, apart by | Least-changed soft zone |
|---|---:|---:|
| Boom | 33.6 dB | 0.6 dB |
| Crushed Boom | 32.9 dB | 0.8 dB |
| High Tom | 29.0 dB | 3.8 dB |
| Cross Stick | 28.7 dB | 2.3 dB |
| Snare Riser | 25.3 dB | 4.7 dB |
| Floor Tom | 24.3 dB | 4.7 dB |
| Rack Tom | 24.0 dB | 4.2 dB |
| Crushed Snare | 15.6 dB | 3.1 dB |
| Drip Snare | 15.2 dB | 7.0 dB |
| Bap | 14.4 dB | 6.7 dB |
| Ride, crash, hats, shaker, tambourine | about 0 dB (uncorrelated) | 1.5 to 6.6 dB |

How to read the two columns:
- **"Apart by"** is what differs between two takes against what they share. A copy, dealt with a
  level nudge and a shave off its front, is apart by more than 40 dB.
- **"Least-changed soft zone"** is the most an octave band's level moves between a soft zone and
  the hard zone, at matched loudness. A zone that is the hard one turned down moves 0.
- **The weak spot is the kick.** The crush evens out the small force differences between strikes.
  So the two kicks' takes are distinct strikes, but close: 34 dB apart, against more than 40 for a
  copy. Their soft zones move only 0.6 to 0.8 dB in shape. GATECRASH's stone kick was disclosed
  the same way. The owner's listen decides whether the kick needs more variation per strike.

**Through saving, playback and export** (`BreakerKitTest`; the full kit and listening pack:
`./gradlew :shell:generateBreakerKit`):
- **Saving:** the kit reloads equal. Rendered again from its recipes, it is the same, byte for byte.
- **Playback:** `PadHit.resolve`, the resolver the app's pads use, picks the zone by velocity, deals
  the takes in order and repeats them. The preview mixer cycles them too.
- **MPC 3:** the exported track's velocity ranges, slice anchors, cycle lengths and slice windows
  match the kit, and its samples are the saved ones.
- **MPC 2** plays each zone's first take only, so it does not pass the round-robin export gate.
- **Not yet tested on hardware:** no phone or MPC has played the kit.

**Cost:** 139 slices in 34 MB of 24-bit audio. The kit takes about 15 s to deal on a desktop JVM; a
phone has not been timed.

## The genre kits

Two more starters, each laid out for the owner and approved before it was built ("Build it",
2026-11-12). Both are on renderer model 6 (below), and neither has been heard yet. Like the BREAKER
kit, every pad is a BREAKER patch with its recipe, dealt by the shared robin: 3 zones × 3 re-struck
takes, and 2 × 2 on the recorded tambourine.

**Dub Tank** (`breaker-dub`, 74 BPM, one-drop). The drop is the kick and the rimshot together on
three. The space is echo more than spring. The toms are left open to ring, and the steppers kick and
the dry snare are damped tight.

| | | | |
|---|---|---|---|
| A13 Ride (two semitones up, echoed) | A14 Crash | A15 Bump (Kicked) | A16 Snare Riser |
| A09 Low Tom (open) | A10 Mid Tom (open) | A11 High Timbale | A12 Shaker |
| A05 Thrown Snare (375 ms echo) | A06 Steppers Kick (damped) | A07 Dry Snare (damped) | A08 Tambourine |
| A01 Drop Kick (low, round) | A02 Rimshot (300 ms echo) | A03 Closed Hat (echoed) | A04 Open Hat (chokes A03) |

**Trip hop** (`breaker-triphop`, 92 BPM, a heavy break). The kick and snare sit a semitone down and
fatter: less damped, with more dust and crush. The ghost snare and the second kick are damped short
and nearly dry, so they stay out of the way. The ride and crash are dialled into the rig.

| | | | |
|---|---|---|---|
| A13 Dusty Ride (rig in) | A14 Crash (in the spring) | A15 Crushed Riser | A16 Shaker |
| A09 Low Tom (three semitones down) | A10 Mid Tom (two down) | A11 Cross Stick (dark) | A12 Break Snare (crushed, amp) |
| A05 Ghost Snare (damped short) | A06 Second Kick (two down, short) | A07 Tambourine | A08 Open Hat (long, crushed; chokes A03) |
| A01 Heavy Kick (one down) | A02 Fat Snare (one down, wide spring) | A03 Closed Hat (dusty) | A04 Half-open Hat (chokes A03) |

**How the tunings are set.** BREAKER's TUNE moves SKIN's own tuning a quarter of its range either
way. SKIN's kick spans 45 to 75 Hz (8.8 semitones), its snare 170 to 260 Hz (7.4) and its tom 90 to
220 Hz (15.5), so one semitone is TUNE .113 on the kick, .136 on the snare and .065 on a tom. The
cymbals move three semitones either way. SLAP is a 95 ms slap up to .5 and grows to 375 ms at 1, so
300 ms is SLAP .87.

**Where the kits depart from their layouts:**
- **Dub Tank's High Timbale** reaches about three semitones over the high tom, not the five the
  layout asked for: 220 Hz is the top of SKIN's tom.
- **Dub Tank's Steppers Kick** is damped at .7 and crushed at .6, not .8 and .8. Damped and crushed
  harder, its strikes measured as copies (43 dB apart).
- **Trip hop's Second Kick** keeps a touch of spring (DRIP .1, as on the ghost snare). Fully dry,
  its soft strikes were one body: everything that differed between them was a 0.4 ms click, which
  the round robin's start trim (up to 2 ms) can cut. Two takes then measured 44 dB apart.

**Not BREAKER at other settings** (`BreakerKitTest`, pad by pad against the BREAKER kit):

| | Dub Tank | Trip hop |
|---|---:|---:|
| Pads that are another drum | 7 | 5 |
| Same drum, retuned | 4 | 6 |
| Damped or opened (DAMP off .5) | 7 | 11 |
| Same drum and tuning, only the rig moved | 3 | 3 |

**The grid**, measured on the saved kits as for BREAKER above:

| Pad | Closest two takes, apart by | Least-changed soft zone |
|---|---:|---:|
| Trip hop Second Kick | 35.6 dB | 1.0 dB |
| Dub Tank Steppers Kick | 33.9 dB | 0.5 dB |
| Dub Tank Drop Kick | 29.6 dB | 0.9 dB |
| Dub Tank Rimshot | 27.2 dB | 2.4 dB |
| Trip hop Heavy Kick | 26.8 dB | 1.8 dB |
| Trip hop Cross Stick | 26.9 dB | 2.3 dB |
| Trip hop Crushed Riser | 26.2 dB | 4.4 dB |
| Dub Tank Snare Riser | 25.1 dB | 4.7 dB |
| The toms and the timbale | 21.0 to 23.5 dB | 4.0 to 5.0 dB |
| The snares | 14.2 to 16.5 dB | 3.9 to 6.2 dB |
| Bump | 1.3 dB | 0.6 dB (it rings shorter) |
| Cymbals, hats, shaker, tambourine | about 0 dB (uncorrelated) | 1.6 to 7.0 dB |

The kicks are again the weak spot: their crush evens out how hard each strike lands, and their soft
zones move least. Bump's soft zone barely changes colour, since the tank's send is normalised. It
rings shorter instead, which the decay half of the measure hears.

## How a hit is made

1. **Source:** each drum's settings are frozen in `Breaker.kt`.
   - **Backroom's SKIN kick, snare, hats, cross stick and shaker,** as accepted (`docs/BOOM_BAP.md`).
   - **Three SKIN toms:** one shell at about 124, 154 and 188 Hz.
   - **The ride and crash:** a modal synth fitted to CC0 recordings (below, "The cymbals"). Model 2's
     ride was SKIN's long ride, with its rivets sizzling.
   - **The riser:** twenty strikes of the snare, speeding up from about 4 to 25 a second over 2.4 s
     and rising in strength.
   - **BACKWASH's CC0 tambourine recordings.**

   Each round-robin take is a fresh strike (`Restrike`):
   - the SKIN drums re-seed their noise;
   - the cymbals strike every mode afresh (below);
   - the riser re-strikes all twenty snares;
   - the tambourine alternates its two recorded takes.

   The pad's velocity works per drum:
   - the kick, snare, toms and the riser's strikes take SKIN's own contact law;
   - the cymbals take the difference measured between a soft and a hard recording;
   - the tambourine picks a recorded dynamic layer;
   - SKIN does not strike the hats, shaker and stick, so a softer hit darkens their finished
     sound after the rig (step 9).
2. **DUST:** the rack's own CRUNCH and VINYL: the record and the sampler. On model 4, CRUNCH's
   output filter sits at about 8.9 kHz at DUST .35, where model 3 put it near 4.2 kHz (5.5 kHz on
   the snare).
3. **Saturation:** an asymmetric tube curve at 4x. The cymbals and the riser skip it: on noisy
   metal and a dense roll, it was the fizz the owner heard as harsh.
4. **CRUSH, first half:** an FET-style compressor gluing the hit.
5. **DRIP:** a spring tank. Its echoes rise in pitch because a spring carries low notes faster than
   high ones, and the bend grows with every bounce. Each drum uses the tank the owner heard it
   through at round 0:
   - **the snare: tank B**, the surf spring they picked, with a deeper bend and a twang near 3 kHz;
   - **the kick: tank A**, a lighter, brighter bend. Their kick pick rang through it.
6. **SLAP:** an echo, a 95 ms slapback that grows toward 375 ms past halfway. 95 ms is the time the
   owner heard at round 0. On a kick the time matters: at 90 ms, the repeat adds to the body's
   lows instead of partly cancelling them, and the tail gets 5 dB more boom at 63 Hz.
7. **CRUSH, second half:** the same compressor after the spring, so each hit ducks the drip and it
   swells back up.
8. **AMP:** the rack's VALVE. On model 4 every drum gets its tube without the speaker. The cymbals
   and the riser keep the bright open-back combo (CAB .25) they were heard with. On the cymbals, AMP
   is how much of the combo you hear, so at 0 there is no amp at all.

9. **Soft hits on the unstruck drums** (hats, shaker, stick, and model 2's ride): the finished
   hit's top is rolled off, near 4.8 kHz at 40%. Darkening their source does not survive the rig;
   see the design spec, "As built: round 2".

10. **The riser's push:** the crush evens out a crescendo, so the riser rises after the rig, from
    -12 dB to full over the build.

A hit is capped at 1.49 s with a fade, so it stays a one-shot. Two voices are longer and are
declared PERC, never classified: the crash rings 3.2 s (the owner's call), and the riser lasts
3.4 s, the build and the spring after it.

## Model 4: the open rig

The owner heard the model-3 kit as mid heavy. Measured on the kit, almost everything sat between
about 100 Hz and 4 kHz:
- **DUST's filter:** CRUNCH low-passed every drum near 4.2 kHz at DUST .35 (5.5 kHz on the snare).
  That is Backroom's crunch, accepted in its own kit.
- **The speaker:** the combo's open-back cab rolled off the top further and added breakup peaks at
  2.6 and 3.75 kHz.
- **The cymbals:** the only full-band sounds, and quiet in the mix.

The owner heard the same groove four ways at one loudness (2026-11-12):
- as it was;
- DUST's filter opened;
- the speaker taken out;
- both.

They picked both, and heard their snare, hat and kick through it as "better".

Model 4 renders exactly that:
- **DUST's filter at DUST .35 opens to about 8.9 kHz**, CRUNCH's own default. Its bits, rate, grit
  and record stay.
- **The amp keeps its tube and drops its speaker** (CAB 0).
- **The cymbals and the riser keep the rig they were heard with.** That includes the cymbals'
  dialled presets, which the owner approved at round 3.
- **The app's model-4 renders are the clips the owner picked,** every pad at every velocity, to
  within 6 × 10⁻⁸.

The top octave against the whole hit, model 3 to model 4:

| Drum | 8 kHz octave, model 3 → 4 |
|---|---|
| Snare | −25.9 → −16.3 dB |
| Closed hat | −17.5 → −8.8 dB |
| Open hat | −19.1 → −9.1 dB |
| Kick | −40.7 → −34.4 dB |

The crush's swell past halfway is still there. Below 2 kHz, where the spring's tail is, CRUSH .5 to
.9 lifts the snare's tail 1.3 dB (1.5 on model 3) and the kick's 1.2 dB (1.9). Measured broadband,
the opened crack of the hit hides it (0.4 and 0.9 dB), so `BreakerTest` measures it below 2 kHz.

Model-3 saves keep the speaker and the darker filter.

## Model 5: DAMP and Bump

The owner approved both on 2026-11-12, so that the genre kits differ in damping as well as tuning,
roles and layout, and so that Dub Tank can have Bump. At DAMP .5, every model-4 voice renders on model
5 exactly as it did on model 4 (`BreakerTest`). Model-4 saves have no DAMP and no Bump.

**DAMP** is each drum's damping. At .5 nothing changes; above it the drum dies sooner, and below it
the drum rings longer:
- **SKIN's drums** (kick, snare, toms, hats, stick, shaker, and the riser's strikes): their own
  DECAY moves by up to .3 either way. Above .5 the drum is also damped as tape on a head damps it:
  an exponential fade on the drum before the rig, about 12 dB in the first 100 ms at DAMP 1.
- **The cymbals:** every fitted mode decays up to twice as fast, or half as fast.
- **Bump:** the kicked tank rings up to twice as short, or twice as long.
- **The tambourine:** a recording cannot ring longer than it was played. Below .5 it is unchanged;
  above .5 it is BACKWASH's own DAMP, which shortens it.

**Where DAMP is heard.** It always damps the drum itself. In a wet rig, the spring and the crush
rebuild a drum's tail. The table below is the body (.05 to .3 s) against the hit's first 50 ms. In
the full rig, DAMP is clear on the kick, the open hat, the shaker, the cymbals and Bump, and nearly
lost on the snare, the closed hat, the stick and the drippy toms. A kit that wants a damped snare or
toms also dries their spring.

| Drum | Rig's tail off, DAMP .15 / .5 / .85 | In its rig |
|---|---|---|
| Kick | −7.9 / −11.7 / −21.1 dB | −4.4 / −7.7 / −10.6 dB |
| Snare | −16.8 / −21.3 / −31.5 dB | −3.0 / −2.9 / −2.8 dB |
| Open hat | −7.4 / −9.6 / −16.2 dB | −3.7 / −5.4 / −9.6 dB |
| Floor tom | −5.4 / −7.7 / −14.4 dB | −1.6 / −1.4 / −2.1 dB |

The ride's and crash's tail (.3 to 1.2 s) moves 3 to 4 dB either way of .5.

**Bump** is the spring tank kicked. The jolt moves the transducer all at once, so the springs get a
broadband slap:
- **The jolt:** a 2 ms click and 25 ms of noise, re-seeded per take, into tank B with no dry signal.
  The tank's mechanical knock (a 60 Hz thud) is kept under the springs.
- **DRIP** is how hard it is kicked, so a harder kick clangs and rings longer.
- **TUNE** is the springs' tension, half an octave either way.
- **The rig:** it skips the tube, like the cymbals, and uses the open rig. It lasts 2.5 s and is
  declared PERC.
- **A softer pad** kicks it softer, so it rings shorter: at 40%, 1.4 to 2.2 s in, it sits 8.6 dB
  under a full kick's, each hit levelled.
- **Presets:** Kicked, Booted (DRIP 1, CRUSH .8) and Nudged (DRIP .35, DAMP .75).

## Model 6: another beater per take

On model 5, a later take of a kick or tom differed from the first only in how hard it landed (up to
15% softer, `Restrike`) and in its click, which peaks 0.4 ms in. The round robin trims up to 2 ms
off a later take's front as timing jitter, and that can cut the click. What is left of a damped,
crushed kick is then nearly the same strike every time: the crush evens out the force, and the
damping leaves little body for it to show in. Dub Tank's Steppers Kick had two takes 42.4 dB apart,
past the 40 dB that marks a copy.

A real beater meets the head a little differently each time. So on model 6 each later take of a
kick or tom moves SKIN's CLICK, PUNCH, DROP (how far the pitch bends) and TONE by up to .1 either
way, seeded per take. The drum's root and its decay stay its own, and its first take, the reference,
is model 5's exactly. At the same settings, the Steppers Kick's closest takes moved to 33.9 dB apart.
Trip hop's Second Kick needed a touch of spring as well (above, "The genre kits"): with the click
cut, its takes measure 43.9 dB apart on model 5 even with DRIP .1, 43.8 on model 6 fully dry, and
35.2 with both.

| Kick or tom, 2 ms cut off every take | Model 5 | Model 6 |
|---|---:|---:|
| Trip hop Second Kick | 43.9 dB | 35.2 dB |
| Dub Tank Steppers Kick | 42.4 dB | 33.5 dB |
| Dub Tank Drop Kick | 33.5 dB | 29.6 dB |
| Trip hop Heavy Kick | 30.7 dB | 26.7 dB |
| Dub Tank High Timbale | 28.1 dB | 21.5 dB |
| The other toms | 24.3 to 25.4 dB | 21.0 to 23.5 dB |

**The start trim cuts the click on every re-struck kit.** The round robin's trim was made for
recordings, as timing jitter. On a synth drum whose strike starts at its first sample, a trim past
the click's peak takes the attack with it. On the Second Kick (click at 0.4 ms) such a take loses its
click and plays about 2.5 to 3 dB quieter over its first 300 ms. This is the shared `Robin`, so it
applies to every kit whose takes are re-struck (`Restrike`: SKIN, TERRA, HALOBAR, BREAKER and
others), including kits already heard and accepted. It is left as it is here, since changing it
would change those kits too.

Model-5 saves keep their takes, and the BREAKER kit stays on model 5.

## The cymbals

A cymbal is several hundred resonances beating against each other. Noise in bands gets each band's
level and decay right but none of that shimmer, and the owner heard it as processed. So
`BreakerCymbal` resynthesizes the recording's own resonances (sinusoids plus a residual, Serra and
Smith 1990):
- **Modes:** 400 on the ride and 418 on the crash, picked from an 8192-point STFT of a hard hit.
  Each has a frequency, an amplitude and one decay.
- **Tight:** the fit measures two decay slopes per mode, the first 300 ms and after. Each mode keeps
  the steeper one, which leaves out the slow tail the overhead mic heard from the room. The owner
  picked it, heard dry.
- **Level:** one correction per third-octave band, matched to the recording over 80 to 320 ms.
- **The stick:** 60 ms of noise in third-octave bands at the recording's levels over its first
  40 ms, at a 0.6 mix (shape, as auditioned).
- **Velocity:** each mode moves by the level difference, per band, between the soft and hard
  recordings, and the stick moves toward the soft recording's spectrum. A softer crash carries more
  middle and less presence. A softer ride carries more low ring (250 to 315 Hz) and less at 1.3 to
  2 kHz. Neither is simply darker.
- **A take:** a fresh strike. Each mode is up to 0.3 Hz off in pitch, with its own phase and a level
  scatter of 1.6 dB rms, and the stick is fresh.
- **TUNE:** three semitones either way.

Source: Virtuosity Drums (CC0 1.0), `sfzinstruments/virtuosity_drums` at `9f04cf9a`, overhead mic,
the ride and crash at their softest and hardest layers. `scripts/fit_breaker_cymbals.py` writes
`synth/src/main/resources/breaker/cymbals.json`, and `NOTICE.txt` beside it lists the four files and
their hashes. Only the fitted numbers ship; no recording does.

## Controls

| Control | Default | What it does |
|---|---:|---|
| TUNE | .50 | the drum's own tuning, a quarter of its range either way |
| DUST | .35 | the record and sampler grit (.35 is Backroom's accepted crunch) |
| DRIP | per drum, below | how hard the hit drives the spring tank, and how much comes back |
| SLAP | .20 unless below | the echo: level and repeats, and past halfway its time |
| CRUSH | .90 unless below | the compressor: glue up to halfway, then also crushing after the spring |
| AMP | .35 unless below | the combo's drive and sag (on the cymbals, how much of the combo) |
| DAMP | .50 | model 5: the drum's damping; .5 changes nothing (below, "Model 5") |

On the shaker, which has no tuning of its own, TUNE moves its band. On Bump, DRIP is how hard the
tank is kicked and TUNE is the springs' tension.

Per-drum defaults:

| Drum | DUST | DRIP | SLAP | CRUSH | AMP | Tank |
|---|---:|---:|---:|---:|---:|---|
| KICK | .35 | .20 | .20 | .90 | .35 | A |
| SNARE | .35 | .60 | .20 | .90 | .35 | B |
| HAT_CLOSED | .35 | .10 | 0 | .60 | .35 | B |
| HAT_OPEN | .35 | .15 | 0 | .60 | .35 | B |
| STICK | .35 | .30 | .20 | .70 | .35 | B |
| SHAKER | .35 | .15 | .10 | .40 | .35 | B |
| TAMBOURINE | .30 | .35 | .20 | .50 | .35 | B |
| TOM_FLOOR | .35 | .60 | .20 | .80 | .35 | B |
| TOM_RACK | .35 | .45 | .20 | .80 | .35 | B |
| TOM_HIGH | .35 | .40 | .20 | .80 | .35 | B |
| RIDE, CRASH | 0 | 0 | 0 | 0 | 0 | B |
| RISER | .20 | .60 | .20 | .50 | .20 | B |
| BUMP | .20 | .70 (the kick) | .20 | .50 | .30 | B itself |

The cymbals are dry by default, and every control at 0 is a true bypass. Their presets are Dry,
Through the rig, and In the spring; the riser's are Tamed, Pumping, and Short spring.

Model 2's ride: DUST .30, DRIP .45, SLAP 0, CRUSH .60, AMP .35.

## Verification

`BreakerTest` checks:
- **The house basics:** every hit is finite, bounded and centred, and every control moves the
  sound. Every hit is exactly 1.49 s long, except the crash (3.2 s) and the riser (3.4 s).
- **Robustness:** an unknown key changes nothing, a non-finite control falls back to its default,
  and JSON round trips keep the model.
- **Velocity and takes:** soft and hard hits differ, takes are fresh strikes, and every take is
  deterministic.
- **The three round-0 claims:**
  - both tanks' echoes rise in pitch and never gain energy;
  - DRIP puts spring energy after the hit;
  - CRUSH past halfway lifts the tail.
- **Round 1b:**
  - the slap's first repeat lands at 95 ms (90 ms on model 1);
  - a model-1 save still renders model 1.
- **Round 2:**
  - the CRUSH swell past halfway on the kick and snare. Every drum whose tail starts more than
    6 dB under its hit gets lifted by the crush. The tambourine's jingles already sit within 6 dB.
  - DRIP rings after the hit on every drum. The ride's is the smallest, 2.6 dB, because its own wash
    already fills the band.
  - a 40% hit on the hats, shaker and stick has an attack centroid more than 5% lower.
- **Round 3:**
  - at their defaults the cymbals are the fitted synth with only the engine's finish (DC removal,
    fade, level), and AMP brings the combo in;
  - each cymbal has at least 300 modes; the crash still rings 1.6 to 2.2 s in (above -45 dB re
    its first 100 ms) and is declared PERC;
  - a 40% cymbal changes its spectrum's shape by more than 3 dB in some octave at matched loudness
    (ride +9 dB at 250 Hz; crash +6 dB at 500 Hz);
  - the riser builds by at least 8 dB;
  - model 2 refuses the crash and riser, and model 2's ride still renders model 2.

`SkinTest` checks that, asked, a later take re-seeds SKIN's hats, ride, shaker and stick, and that
unasked (every other engine), a take changes nothing.

`DeterminismTest` replays every voice and a take, byte for byte, and through a JSON round trip.

`BreakerTest` also checks model 6 on every kick and tom in the genre kits, dealt as the robin deals
them, with 2 ms cut off every take: no two takes are copies (under 40 dB apart), the Steppers Kick's
were on model 5, and each zone's first take is model 5's.

`BreakerKitTest` checks the three kits:
- each kit's sixteen pads, their grids, recipes, models, levels and chokes;
- that the genre kits are authored kits: pad by pad against BREAKER, enough of their pads are
  another drum, retuned or damped, at most four differ only in the rig, and their signature pads
  (the rimshot backbeat, Bump, the thrown snare, the ghost snare and second kick) are where the
  layouts put them;
- that no two takes are copies and no soft zone is the hard one turned down;
- saving, reloading and regenerating from the recipes, byte for byte;
- zone selection and take cycling in the pad resolver and the preview mixer;
- the MPC 3 track and its samples;
- each kit on the shelf once, at its tempo.
