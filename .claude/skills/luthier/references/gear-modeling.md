# Gear and instrument modeling: from a reference to a DSP block

Josh will hand you references by name: a pedal, an amp, an instrument, a plugin, a famous
machine. The job is never to clone the product. It is to find the two or three **behaviours**
that make the reference itself, write them as mechanisms, and decide where they land in
SnipSnap (a new engine, a voice, a rack section, a recipe over sections, or nothing). The house
rule is "sound yes, names never": research text may name products; engine, voice and preset
names may not (`PresetTestSupport.trademarkBlocklist`), and no trade dress is recreated.

## 1. The research procedure (do this before designing)

1. **Search for the manual and the maker's product page** (controls, signal path, modes).
   Product details change between revisions; do not quote a control set from memory. Use
   WebSearch, fetch the manual, cite it.
2. **List the controls and what each physically does.** A control that changes several things
   at once (a "character" knob) is a macro law worth stealing.
3. **Infer the signal path** from the controls and any published block diagram. For classic
   circuits (two-transistor fuzz, diode-clipping overdrive, BBD chorus, spring tank) the
   topology is public engineering knowledge and can be described as such.
4. **Watch or read two demos** and write down three adjectives per reference, then translate
   each adjective to a measurable property (brightness → spectral centroid; "gated" →
   amplitude threshold with a release; "wobble" → modulation rate and depth).
5. **Write the essence**: one sentence per reference, mechanism only, no brand words. This is
   what DISTIL (combination-grammar.md) operates on.
6. **Decide the landing**: see §4.

## 2. Pedal and processor archetypes (behaviour → block)

| Archetype | Defining behaviours | DSP block | SnipSnap home | Watch out |
|---|---|---|---|---|
| Overdrive (op-amp + soft clipping) | mid-forward pre-EQ, soft symmetric clip, tone after | pre-emphasis → tanh/soft knee → post lowpass; 4× and band-limit | VALVE, DRIVE recipe | aliasing; the "mid hump" is the identity, not the clip |
| Fuzz (two-transistor, Fuzz-Face style) | hard asymmetric clip, input-impedance interaction (cleans up with guitar volume), gated decay, square-ish sustain | asymmetric hard clip with bias; a bias-dependent gate on the input envelope; cab filter | FUZZ is deferred in the pedal-board spec (DRIVE's top is gain 1000) | the "cleanup" needs an input-level-dependent bias, or it is just distortion |
| Octave/gated fuzz | full-wave rectification (octave up), subharmonic (octave down by flip-flop), gating | `abs()` or squared rectifier into clip; a frequency divider; threshold gate with fast release | none; CRUNCH is bit/rate | rectifiers make DC: block it before the clip |
| Slicer / pattern tremolo | tempo-locked on/off patterns, soft edges, sometimes a filter or pan per step | step sequencer of gains with 1 to 5 ms raised-cosine edges; optional per-step filter | none in `FxChain`; a SLICE section or an engine that re-excites at the slice rate | hard gates click; a rate that is not a musical division sounds broken; bakes into the render |
| Tremolo | sine/square AM at a rate, sometimes harmonic (bias) tremolo | AM with waveform and depth; harmonic version splits bands and modulates in antiphase | staged in the pedal-board spec | depth 1 at square = slicer |
| Chorus / flanger / phaser | short modulated delay (chorus 5 to 30 ms, flanger 0.1 to 5 ms with feedback), allpass chain (phaser) | modulated delay with cubic read; allpass cascade with LFO | ENSEMBLE, PHASE; FLANGER staged | the LFO must be baked; stereo width is lost in mono |
| Delay (tape, digital, "collector"/looper-ish) | repeats with loss per pass, wow/flutter, tape saturation; a buffer that remembers and brings back earlier moments | delay with feedback and loop filter; a long buffer with scheduled recall and modifiers (reverse, octave, stutter) | ECHO, DUB; a "memory" engine idea lives in SPOOL/ORPHICA territory | feedback > 1 needs a limiter which the house forbids as a stability story; bound the loop gain by construction |
| Reverse / stutter / glitch | buffer read backward; repeat a window at a division | reversed read with crossfade; windowed repeat | REVERSE flag (whole pad), GHOST, SMEAR | window edges |
| Spring / plate reverb | dispersive "boing" (spring), dense fast build (plate) | allpass + comb (Schroeder) with dispersive allpass for spring; FDN for plate | SPRING | FDN gain bound |
| Compressor / squash | attack/release gain law, pumping | envelope follower + gain computer | SQUASH, COMPRESSOR staged | a tail lifted over its body is the SQUASH ceiling finding |
| Wah / envelope filter | resonant bandpass swept by foot, LFO or envelope | time-varying TPT SVF; sweep laws (TOUCH, AUTO, ROCK) | WAH section (built, gate pending) | linear, nothing to oversample |
| Ring mod / frequency shifter | multiply by a carrier; single-sideband shift | product; Hilbert pair for SSB | RING | carrier not tracking pitch is the sound |
| Pitch shifter / shimmer | octave-up in a reverb's feedback; grain-based shift | PGHI/phase-vocoder or overlapping grains at 2× read | OPENSKY's octave feedback; `Pghi` in `:audio` | mono shimmer gets metallic fast |
| Bitcrusher / sample-rate reducer | quantize, zero-order hold | quantizer, hold | CRUNCH | ZOH aliases on purpose |
| Freeze / infinite sustain | spectral or granular hold of the current instant | PGHI hold (ETERNAL) or granular loop | ETERNAL, ORPHICA | one-shot hygiene: it must end |
| Amp + speaker | pre-EQ, stage gain with sag, power-amp compression, cabinet bandpass with resonances | staged soft clips, a sag envelope that drops gain with level, cab IR or 2 to 4 resonances | VALVE (DRIVE, SAG, CAB) | the VALVE lifts quiet tails: the end-fade finding in S30 |

## 3. Instrument references (behaviour → block)

| Instrument | Essence (what to keep) | Block | SnipSnap home |
|---|---|---|---|
| Sitar | jawari (bridge buzz: a one-sided fold-back that brightens as the string decays), sympathetic strings, long sustain, slides | `Strings.Loop.jawari`, sympathetic bank, retune glide | PLUCK SITAR, SILK |
| Guzheng, oud, santur, shamisen | courses (paired strings beating), dispersion, sawari, bachi strike | SILK's toolkit | SILK |
| Electric guitar | pickup comb (position), coil resonance, left dry for the amp | KS + comb + resonance | MAGNET |
| Cello / erhu | stick-slip bow, body resonances, bow pressure vs speed | `Strings.Bow` | ARCO |
| Flute / sax | air jet vs reed table on a bore; onset in periods; voicing | `Strings.Loop` + valve | BORE |
| Accordion / hurdy-gurdy | reed ranks with beating, wheel-bowed drone | oscillator ranks | BELLOWS |
| Kalimba / bell / tine | 2-op FM with snapped ratio, or modal with sourced ratios | FM or `Modes.ring` | TINES, FORK |
| Membrane drums | modal with inharmonic ratios (1, 1.59, 2.14, 2.30, 2.65 for an ideal membrane), skin damping, strike position weights | `Modes.ring`, `Modes.atPosition` | SKIN, TERRA, TREMOR |
| Bowl, glass, gong | long-decay modes with slight beating pairs; rubbing excitation | coupled modal bank | MERCURY, THAW, NIMBUS |
| Voice | formants over a glottal pulse; vowels move slowly; breath noise | formant bank + pulse + noise | VOX, MURMUR |

## 4. Where a reference lands in SnipSnap (the decision)

| If the essence is... | Then it is... | Example |
|---|---|---|
| a new gesture (a beginning, evolution and ending the roster lacks) | an **engine** | a sitar string re-plucked at a slice rate while it rings |
| the same gesture with a different mechanism inside an existing engine | a **voice** of that engine | a gated-fuzz voice of MAGNET |
| a processing stage any pad could use, in a slot a real board gives it | a **rack section** (`FxChain`, fixed order) | SLICE between CONTOUR and SQUASH |
| named settings over sections that already exist | a **recipe** (a PEDALS chip) | DRIVE is VALVE at the top of its law |
| a preset of an existing voice | a **preset** (after listening) | |
| trade dress, a name, a sample of the original | **nothing** | |

Prefer the lightest landing that keeps the essence. An engine is the most expensive thing in
the repo (26 integration surfaces, an audition gate, 16 pads); a recipe is a dozen lines.

## 5. Multi-reference synthesis ("better than the sum")

When several tools in a family are given (three fuzzes, four delays, five reverbs):
1. Make the behaviour table for each (§1 step 5).
2. Find the behaviours that are **shared** (that is the family's core: keep one implementation)
   and the behaviours that are **unique** to one tool (that is where the character lives).
3. Pick the unique behaviours that **compose**: two that act on different stages (one on the
   input envelope, one on the feedback path) compose; two that act on the same stage compete,
   and you pick one.
4. Drop anything a SnipSnap constraint makes meaningless (stereo field, live expression,
   true-bypass, presets memory).
5. Write the composed design as one signal flow with one macro story. Then check: does any
   single reference do all of this? If yes, you built a clone; go back to step 3.
6. Name the trade: what each reference does that the composite does not.

## 6. Example essences for a prompt like "sitar, amp, slicer, gated fuzz, a delay that remembers"

Written at behaviour level so the design can start without the brand words. Verify the
specific products' current controls with a search before citing them in the research section.

- Sitar: a plucked string whose bridge buzz brightens as it decays, over sympathetic strings
  that hold the key.
- Guitar amp: asymmetric stage gain with sag and a speaker bandpass that darkens the top.
- Slicer: tempo-locked gain patterns with soft edges, sometimes with a filter per step.
- Gated/octave fuzz: full-wave rectification into a hard clip with a fast gate that chops the
  decay into bursts.
- "Collector" delay: a long buffer that records what was played and brings back earlier
  moments at scheduled times, through modifiers (reverse, octave, stutter), rather than a
  regular repeat.

The composite's new gesture: pluck → chopped, rectified buzz bursts → earlier bursts return
backward and an octave up, under the sympathetic drone. That is an engine candidate because the
ending (what returns, and when) is a designed trajectory the rack cannot make.
