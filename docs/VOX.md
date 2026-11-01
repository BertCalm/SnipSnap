# Vox

VOX is the voice engine: formant synthesis, from a shopping-mall choir to a
mouth doing drums. The characteristic gesture is the vowel morph, "aah" to "ooh"
on one knob, sung by a throat that opens on a consonant and breathes. The singing
voices run one path; the other five each have their own file:

```
CHOIR, ROBOT, GHOST (Vox.kt):
  glottal pulse (ROBOT: square) + breath puffs (BREATH)
  ──> 5 formants: F1-F3 morphed A→E→I→O→U (VOWEL, GLIDE), F4-F5 fixed,
      scaled by SIZE ──> ONSET consonant ──> envelope (DECAY, hold in its top half)
  CHOIR: seven singers, detuned, octave sections, panned, in stereo
BEATBOX (VoxBeatbox.kt)  mouth percussion: noise through a moving tract + hiss + burst + voice
THROAT  (VoxThroat.kt)   a drone with one harmonic picked out by a narrow resonance
WRAITH  (VoxWraith.kt)   three sine tones tracing a word's formant paths
SWARM   (VoxSwarm.kt)    two to sixteen mouths saying a word, in stereo
SPEAK   (VoxSpeak.kt)    a cascade formant speech synthesizer counting one to eight
```

Every voice but WRAITH renders at 4x and decimates; WRAITH's pure tones render
at the output rate. BEATBOX is levelled by peak, like a drum; the rest by loudness. No note outlasts 4 s (`Vox.MAX_SECONDS`). Every draw is seeded from
the recipe, so a pad regenerates to the byte. The roadmap rows are S3.7 (the
first three formants and three voices, shipped) and S11 (rounds 1 to 4, built);
the S11 section of `SYNTH_ROADMAP.md` has the design and what each audition
changed.

## Voices

| Voice | What it is | Knobs past TUNE | Bottom of TUNE |
|---|---|---|---|
| CHOIR | seven singers in sections, stereo | VOWEL BREATH DECAY SIZE GLIDE ONSET | A2 (110 Hz) |
| ROBOT | a square wave through the formants, no vibrato | the same six | E2 (82.4 Hz) |
| GHOST | a breathy singer, slower and wider vibrato | the same six | D3 (147 Hz) |
| BEATBOX | vocal percussion, filed as drums by HIT | HIT DECAY SIZE | 30 Hz; TUNE's middle puts the kick's hum on 60 Hz |
| THROAT | overtone singing | WHISTLE MELODY DRONE DECAY GROWL YODEL | A1 (55 Hz) |
| WRAITH | sine-wave speech | WORD DECAY TUNED ALIEN STUTTER BREATH | A1 (55 Hz) |
| SWARM | a crowd, stereo | WORD CROWD LOOSE EFFORT DECAY STUTTER | A1 (55 Hz) |
| SPEAK | a talking voice | WORD HUMAN DECAY SIZE EFFORT STUTTER | C2 (65.4 Hz) |

`Vox.drumClassFor` files BEATBOX by its hit: the kick as KICK, PF, PSH and K as
SNARE, TS and T as HAT_CLOSED, TSS as HAT_OPEN, RIM as PERC. Every other voice
is TONAL.

## Knobs

- **TUNE** snaps to whole semitones over two octaves above the voice's bottom note.
- **VOWEL** morphs F1-F3 through A, E, I, O, U. **GLIDE** moves it during the
  note: below the middle toward A, above it toward U; 0.5 holds still.
- **BREATH** crossfades the singers toward breath noise; on WRAITH it turns the
  tones into whispered bands of noise.
- **DECAY** is the note's length, 0.25 s to 3 s on the singers, held for up to 60%
  of the note in its top half. On BEATBOX it is each hit's tail; on WRAITH it is
  how long the word takes; on SPEAK it stretches the vowels, never the consonants.
- **SIZE** scales the throat from about 2.1 times the formants (a child) to 0.47
  (a giant). SPEAK's giant stops at 0.7.
- **ONSET** snaps the consonant a note opens on: none, m, b, d, h, t, s.
- **HIT** snaps BEATBOX's drum: kick, pf, psh, k, ts, t, tss, rim.
- **WHISTLE** snaps THROAT's whistled harmonic, 5th to 13th. **MELODY** walks it
  up to six harmonics and back during the note. **DRONE** trades whistle level for
  the body under it. **GROWL** is a rasp an octave down. **YODEL** flips chest to
  head and back.
- **WORD** snaps the word: why, you, no, hello, wow, yeah on WRAITH; hey, ho, yeah,
  huh, ooh, aah on SWARM; one to eight on SPEAK.
- **TUNED** crossfades WRAITH's gliding tones with the same tones stepped onto the
  note's harmonics. **ALIEN** moves them further than a mouth can and gives each a
  beating twin.
- **STUTTER** repeats the word's opening up to four times on WRAITH and SWARM,
  and puts up to three false starts before SPEAK's word.
- **CROWD** is SWARM's number of mouths, 2 to 16. **LOOSE** runs from one word
  said together to a murmuring room. **EFFORT** runs hushed, talk, shout on SWARM
  and SPEAK.
- **HUMAN** runs SPEAK from an 80s speech chip to a person.

## Kit, audition, presets

- **Presets:** twelve per voice in `VoxPresets.kt`, ninety-six in all. Authored
  from the DSP, not by ear, per that file's KDoc.
- **Starter kit:** `SynthKits.cloud()`, the atmosphere kit. A01-A06 are VOX pads
  (CHOIR root, fourth and fifth, ROBOT, two GHOSTs), with recipes; A07-A16 are
  GRAINS clouds made from TINES, PLUCK, VELVET and a CHOIR render, without recipes.
  Render it with `./gradlew :synth:generateCloudKit`.
- **Audition page:** `./gradlew :synth:generateVoxAudition` runs
  `VoxRosterAuditionGenerator`, the shared `RosterAudition` page. It writes
  `testkit/vox-audition/` with the cloud kit, then each voice's default, every
  knob at both ends, TUNE at three notes, and every preset, at one loudness. The
  snapped knobs' ends are their first and last choices, not the whole set.

## Ledger

- **Verified:** `VoxGrainsTest` covers every voice (BEATBOX's filing, THROAT's
  whistle, WRAITH's render rate, SPEAK's byte-exact regeneration among them) and
  GRAINS; `VoxPresetsTest` covers the roster. `FirstGenAuditionTest` checks the audition spec has a note
  for every voice and every knob but TUNE.
- **Heard:** each S11 round was prototyped and auditioned before it was built;
  the S11 section records the verdicts (BEATBOX's BIG take, SPEAK's HUMAN reach
  and its giant's 0.7, the hushed EFFORT that replaced SWARM's whisper).
- **Not heard:** the ninety-six presets as a set, the cloud kit and this audition
  page.
- **Open:** the per-voice loudness offsets are zero awaiting a listen.
