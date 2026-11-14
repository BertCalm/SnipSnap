# PATIO: percussion kit and ordered wear-chain specification

Version 1.0 · 2026-11-07

Current status (2026-11-14): the implemented three-genre, 18-pad performance kits
and cuíca replacement are listening-approved. See `docs/PATIO.md` for current
render/save/play/export verification and remaining hardware/browser limits.
The original v1 design and subsequent dated sections below preserve the study history.
Repository truth: `cc47036c43e36d3eca78bd6e70cb15994b08d469`.

## 1. Gesture and purpose

The characteristic gesture is **hand or stick contact → skin, wood or metal body → wavering
and worn decay → amp compression and speaker coloration**.

A sixteen-pad kit draws deliberately from several Central and South American traditions,
while preserving a conventional kick, snare, closed hat and open hat. The processing reference
is Generation Loss MKII into an amp. Research may name that product; saved voices and kit names
do not. The model is a behavioral approximation, not a circuit clone or a sampled pedal.

## 2. Landing and scope

Land as a kit plus reusable processing. SKIN already supplies drum-kit roles; TERRA supplies
membranes, a dedicated cajón plate/cavity model, conical bells and tuned bars. Neither has the
full scraper/shaker gesture and requested ordered processing. REVEL is an ensemble phrase
instrument and SPOOL is a tape-source instrument; neither is required for this pad kit.

The chosen STACK direction has high gesture clarity, moderate kit-level novelty and the lowest
implementation risk. The COUPLE alternative adds sympathetic neighboring drums but risks
clutter. The MUTATE alternative increases tape damage during a phrase but risks destroying
articulation. Those alternatives remain outside this build.

Round one is a standalone, replayable Kotlin audition with native WAVs and an XPM sample kit.
It does not register a new engine, alter the app picker, reorder FxChain, or promote unheard
settings to factory presets. `PatioStudy` owns the experimental recipe format. Its sidecars must
not masquerade as `PadRecipe`, whose decoder does not understand this study.

## 3. Verified implementation surfaces

Read and searched at the commit above:

- `Skin.render`, `SkinPatch`, `Skin.defaults`: core drum sources and complete saved controls.
- `Terra.render`, `TerraPatch`, `TerraVoice.BOX`, `Terra.defaults`: source topologies.
- `Modes.ring`, `Modes.Mode`: stable damped modal response for new contact textures.
- `Dsp.seedFor`, `Dsp.Noise`, `Dsp.decimate`, `Dsp.levelTo`, `Dsp.fadeTail`, `Dsp.limitPeak`.
- `Tide.bandLimit`: pre-decimation low-pass, also usable for zero-stuffed interpolation.
- `Valve.process`: existing oversampled amp, sag, tone and speaker model.
- `FxChain`: fixed VALVE-before-TAPE order. `PedalBoard` provides pre/amp/post passes for
  existing library pedals; it does not provide the proposed wear mechanism itself.
- `ArrangedPad`, `KitAssembler.assembleArranged`, `KitExporter.exportProgramFolder`:
  explicit pad placement and native XPM export.
- `WavWriter.write`, `AuditionLevel.level`, `FeatureExtractor.extract`: audition and probes.

## 4. Kit and acoustic contracts

All numbers in the initial mappings are **shape values**, not measured instrument constants.

| Pad | Voice / reference | Required acoustic distinction | First-build source / limitation |
|---|---|---|---|
| A01 | Courtyard kick, acoustic bass drum / surdo influence | soft impact, low membrane body, small pitch relaxation | SKIN KICK; hybrid by design |
| A02 | Caixa-inspired snare | head ring plus vibration-driven string/wire contacts | SKIN SNARE proxy; no explicit top-string caixa geometry |
| A03 | Closed hi-hat | metal plus short contact chatter | SKIN HAT_CLOSED; resonant noise, no two-cymbal collision model |
| A04 | Loosely open hi-hat | same metal family, longer ring and intermittent contact | SKIN HAT_OPEN; same limitation; shares choke group with A03 |
| A05 | Surdo answer | padded impact and low body, damping from hand | TERRA COMPOUND_MEMBRANE proxy; no explicit second head |
| A06 | Alegre open | broad hand contact with free membrane response | TERRA membrane; same tuning as A07 |
| A07 | Alegre slap | altered contact, mode weights and damping | TERRA membrane at harder FORCE, rimward POS, shorter DECAY |
| A08 | Llamador | compact skin articulation supporting pulse | TERRA membrane; selected damped stroke, not a universal timbre claim |
| A09 | Repicador | articulate high hand-drum response | TERRA membrane proxy; modal measurements still needed |
| A10 | Peruvian cajón bass | plate impact and box-air body without wires | TERRA BOX, BUZZ=0 |
| A11 | Guacharaca short | ridge contacts through wood/bamboo body | new SCRAPE event model |
| A12 | Guacharaca long | evolving contact rate and gesture ending | same SCRAPE model, longer gesture |
| A13 | Caribbean metal guache | seed collision clusters and container ring | new SEEDS event model; not the bamboo guasá |
| A14 | Agogô low | hard impact and inharmonic metal ring | TERRA CONICAL_BELL |
| A15 | Agogô high | related but distinct ringing | same topology, different tuning/contact; independently measured spectra deferred |
| A16 | Guatemalan marimba knock | wood pitch, resonator reinforcement, charleo membrane | TERRA TUNED_BAR proxy; its BUZZ is contact noise, not yet a resonator-driven membrane |

Panamanian tamborito names a musical tradition rather than one drum, hence A09's correction.
The shaker's old working label "Gua shiver" is retired. There is no claim that all these
instruments form one traditional ensemble or share one tuning system.

## 5. Signal flow

```
saved source patch OR saved contact settings + seed
    -> SKIN / TERRA OR finite contacts -> modal body     [176.4 kHz]
    -> source output conditioning                      [44.1 kHz mono]
    -> interpolation -> bounded asymmetric saturation  [176.4 kHz]
    -> seeded moving read position
    -> flutter gain + head loss + soft dropout
    -> band-limit -> decimate -> DC/tail conditioning   [44.1 kHz mono]
    -> VALVE (its own 4x nonlinear stage and speaker)
    -> DC/tail conditioning and downward peak ceiling
    -> native PCM / separately level-matched audition copies
```

The processor never mutates the source buffer. All state is local to a render. Input is finite
mono 44.1 kHz; unsupported formats are refused. Silent input stays silent.

## 6. Scrape and seed model

Literal DSP: windowed contact pulses excite damped resonators. Analogy: the contact schedule
stands in for hand motion and collisions. There is no rigid-body particle simulation.

The finite gesture duration is `0.09 + 0.70 * HOLD` seconds. A sine-shaped activity envelope
controls contact density and energy. SCRAPE uses relatively regular ridge crossings with small
jitter; SEEDS uses much wider interval variation. TOUCH changes contact rate and pulse width.
TUNE changes the body frequencies, independently of contact scheduling.

Approximate ridge law: `contact rate = scraping speed / ridge spacing`. Round one encodes
the resulting rate, not unmeasured physical units. Contact amplitude falls with the square
root of the nominal rate so extra events share the available excitation. No event is
individually normalized. Whole-voice output leveling is separate from that excitation budget.

Each contact uses a raised-cosine pulse with a small seeded surface-noise component. The
SCRAPE bank has four short-lived modes; SEEDS has five. Tables in `PatioTexture` are explicitly
shape tables. The body's output is normalized before mixing its broadband excitation because
`Modes.ring` gain depends on frequency and rate. Both components are mixed once per gesture.
This is an inexpensive perceptual model, not energy-calibrated acoustic reconstruction.

## 7. Wear and amp model

The reference pedal's mechanisms are saturation, slow random pitch movement, faster pitch and
amplitude movement, machine-dependent frequency loss, and intermittent failures.

Round one's saturated branch is `tanh(g * (x + bias * x²)) / g`, where `g = 1 + 2.5 * WEAR`.
WEAR crossfades from the input to that branch, so zero wear does not add saturation even when
transport motion is enabled. The branch is bounded and zero-preserving. It is **not magnetic hysteresis**, since it has no magnetic
state. A later calibrated stateful model must earn its cost in the audition.

Transport reads a fractional position with independent cosine-interpolated seeded control
knots: 2.1 knots/s for wow and 31 knots/s for flutter. Shape depths are 1.2 ms and 0.08 ms times
WOBBLE. Motion fades in over 20 ms and begins with zero displacement, protecting onset timing.
These values are not copied product controls or measured capstan behavior.

WEAR closes a one-pole head response from 17 kHz toward 3.5 kHz. At most one smoothed dropout
is scheduled per render, with its center between 160 and 520 ms and 36 ms total support.
Dropout depth grows cubically, so normal settings retain articulation. Flutter also modulates
gain slightly. No permanent hiss is added; a silent tail must not be normalized into noise.

Twenty-five milliseconds of zero padding preserve filter/transport release. No feedback path
exists. The nonlinear stages run at 4x with anti-alias filtering. An 8x tape path exists for
reference comparisons; a waveform difference is not by itself an alias-energy measurement.

VALVE is reused with SAG=.25, TONE=.58 and CAB=.30. DRIVE is independently adjustable. VALVE
is peak-relative and peak-matched by its existing contract, so it does not reproduce arbitrary
absolute-input-level cleanup. Do not claim otherwise. Final conditioning removes DC, caps
peaks downward and fades the last 4 ms. It does not loudness-match every stage. Listening copies
are separately matched by the established audition helper.

## 8. Controls and interactions

The end-state kit UI has six controls. Round one exposes native patch controls for reused
sources and TUNE/TOUCH/HOLD for textures, plus WEAR/WOBBLE/DRIVE in study JSON. A shared source
macro adapter is deferred until the voices pass listening; existing patch parameters remain
fully recorded rather than silently translated into an untested common law.

| Control | Initial value | Quantity | Interaction / proof |
|---|---:|---|---|
| TUNE | per source; textures .42/.55 | membrane/bar/bell or texture-body frequencies | isolate from event rate; compare lower and higher settings |
| TOUCH | per source; textures .58/.56 | hardness/position, or contact speed/width | TOUCH × DRIVE: brighter contact drives more harmonics |
| HOLD | short .12, long .70, seeds .38 | texture gesture duration; native DECAY on source patches | HOLD × TOUCH changes contact count; longer gesture must retain an ending |
| WEAR | .30 | saturation, head bandwidth, smooth losses | WEAR × WOBBLE; compare matched bell and scrape tails |
| WOBBLE | .20, reduced on kick/snare | transport displacement and flutter gain | sustain moves without replacing attack; expose a steady-tone probe |
| DRIVE | .35 | existing VALVE gain law | TOUCH × DRIVE, loudness-matched comparisons |

HOLD is finite at every value in this study. No seamless-loop claim or loop metadata. All
randomness uses `Dsp.Noise` with a persisted `Dsp.seedFor`-derived seed. Changing controls
does not gratuitously choose a new random take.

## 9. Serialization and export

Each `patioStudy: 1` recipe contains id, name, intended pad class, seed, mix gain, a complete
existing source patch or texture identity, every contact setting, and every treatment setting.
Unknown fields, unsupported versions and invalid finite/range/integer values are refused.
Defaults are expanded before source patches are written. A model-law change requires an
explicit study version decision, including preserving or refusing older replay.

The audition generator writes `recipes/*.json`, `manifest.json`, `metrics.csv`, native and
matched WAVs, a portable HTML page, an XPM program folder and a ZIP. Exported source metadata
points to adjacent study sidecars; the app's `recipe` field remains absent because the app
does not decode this study. This first export is a playable sample kit, not an app-editable
factory instrument. A03/A04 share a choke group; other voices do not choke.

Per-pad processing resets the tape and amp state for each sample. A whole-phrase comparison
processes the mixed dry sequence through one continuous chain. The two cannot be advertised
as equivalent: overlapping hits change shared amp response, and the tape clock is continuous
only in the phrase pass. The audition roll call is diagnostic, not an authentic regional groove.

## 10. Render budget and numerical gates

Target: warmed source-plus-processing medians in tens of milliseconds for short pads on the
target phone; measure cloud JVM separately and never call it a phone benchmark. Work is
linear in sample count and fixed mode count, with no solver. A future interactive caller may
cache dry sources by complete source JSON, seed and model version so processing edits need not
re-render the source. Round one has no hidden persistent cache.

Required probes: deterministic replay, finite and bounded corner renders, DC below .01,
clean tail endpoints, audible-level output, separate-source ordering, no source mutation,
silent-input behavior, meaningful control changes, invalid JSON rejection, and successful
XPM export. Record centroid, length, peak, DC and warmed medians. Additional high-rate spectral
validation and measured reference-instrument fitting remain required before factory promotion.

Pad classes in this kit are arrangement roles, not claims that the automatic classifier has
recognized a regional instrument. This is a drum kit, so the pitched-engine rule excluding
KICK/SNARE/HAT does not apply. The generator must retain the four requested drum-kit roles.

## 11. Build rounds and listening gates

1. **This build:** spec; two contact models; ordered wear processor; sixteen-source study;
   deterministic replay; targeted tests; matched audition; XPM export and measurements.
2. **After first listen:** identify generic hand drums, overly pitched scrapes, metallic seeds,
   missing caja/caixa articulation, marimba buzz quality, and excessive top-end loss. Fit actual
   source recordings with permission, measuring modal frequencies and frequency-dependent decay.
3. **Acoustic refinement:** explicit damping/contact laws for hand drums and hat pairing;
   independently calibrated bell spectra; resonator-driven charleo. Add velocity layers after
   verifying spectral changes, not merely gain scaling.
4. **Promotion:** source and processing registration where justified, standard editable recipes,
   UI on an SDK-equipped session, curated presets after listening, classifier and full export gates.

## 12. Sonic risks

- TERRA's common membrane could make regional drums read as tuned copies. Audition dry first;
  tune/contact/decay alone do not establish authenticity.
- Regular scraper events could become a pitched ratchet. Compare rate changes and exposed tails.
- Seed collisions could become hiss. Inspect onset clusters and settling rather than only spectrum.
- Head and cabinet filtering could merge hats, guache and snare. Compare them at matched level.
- Marimba buzz could read as unrelated noise. The current source is a disclosed proxy.
- Heavy amp gain could lift tail defects. Check endpoint/DC at maximum DRIVE, then listen.
- Model randomness could conceal macro changes. Persist the seed and hold it fixed for comparisons.

## 13. Research references

- [Fletcher and Bassett, bass-drum experiments](https://physics.byu.edu/docs/publication/1492):
  modes, strike position, amplitude-dependent frequency changes.
- [Meinl traditional caixa](https://meinlpercussion.com/en/products/ca12t-m8197.html): guitar-string snares.
- [Sekiguchi and Samejima, hi-hat modeling](https://www.jstage.jst.go.jp/article/ast/44/5/44_E2293/_pdf):
  paired cymbals and collision-controlled decay.
- [Grinnell surdo](https://omeka-s.grinnell.edu/s/MusicalInstruments/item/1240): construction and roles.
- [Fundación BAT](https://dev.fundacionbat.com.co/instrumentos.php?IDTipoInstrumento=2): alegre/llamador
  construction and performance accounts; not a measured modal table.
- [Universidad de Antioquia, alegre teaching study](https://hdl.handle.net/10495/42278): stroke vocabulary.
- [University of Panama](https://up-rid.up.ac.pa/10935/1/antonio_diaz.pdf): repicador and ensemble roles.
- [A Tempo cajón](https://atempopercusion.com/producto/cajon-clasico-jaspeado/): unwired Peruvian construction.
- [Cajón hit-classification research](https://www.frontiersin.org/journals/ict/articles/10.3389/fict.2018.00016/full).
- [Artesanías de Colombia guacharaca](https://artesaniasdecolombia.com.co/PortalAC/Productos/guacharaca-o-carrasca_225).
- [Músicas del Caribe Colombiano](https://www.unireformada.edu.co/wp-content/uploads/2020/05/LIBRO-M%C3%9ASICAS-DEL-CARIBE-COLOMBIANO.pdf): metal-cylinder guache.
- [Grinnell agogô](https://omeka-s.grinnell.edu/s/MusicalInstruments/item/2681): double-bell construction.
- [Guatemalan Ministry, marimba de tecomates](https://noticias.mcd.gob.gt/2026/03/05/conversatorio-sobre-la-marimba-de-tecomates-acerco-al-publico-a-las-raices-guatemaltecas/): resonators and charleo.
- [Generation Loss MKII manual](https://www.chasebliss.com/s/Generation-Loss-MKII_Manual_Pedal_Chase-Bliss.pdf):
  mechanism reference only; no trade dress, product-named presets or copied sound samples.

## 14. Evidence ledger

| Claim | Status | Evidence |
|---|---|---|
| Existing source, processing and export APIs | verified | searched source files at `cc47036` |
| Instrument construction and playing roles | researched | sources above; preceding research discussion |
| Exact regional spectra, modal ratios, decay values | proposed | no calibrated instrument recordings measured |
| First-study recipes and processing laws | implemented | PatioStudy, PatioTexture, PatioWear |
| Targeted tests, timings and exported audio | measured | 8 passing checks; native Kotlin audition and 16-pad XPM; see as-built notes |
| Musical quality, source authenticity and hardware load | not heard / not verified | requires listening and MPC load |

## Why it is built this way

- Reusing the kick/snare/hats makes the idea playable before specialized source modeling is complete.
- Contact events make scraping and shaking evolve in time; an envelope over generic noise misses that.
- Tape precedes the amp so saturation reacts to the damaged signal, preserving the requested order.
- Dry and intermediate renders expose problems that a pleasing effect could conceal.
- The risky decision is how far existing membrane and bar models can carry regional identities;
  the study labels those approximations instead of treating them as finished instruments.

## As built

Implemented `PatioStudy`, `PatioTexture`, `PatioWear`, a replayable audition/export generator,
seven targeted tests and one determinism canary. Eight checks passed. The first native audition
contains 51 dry/intermediate/processed clips, matched listening copies and a 16-pad XPM sample kit.
Warmed cloud render medians span 6.56–78.73 ms. Full evidence and limitations are recorded in
[`docs/PATIO.md`](../../PATIO.md). The broad regression gate is tracked there separately.

Deliberate first-build reductions: source knobs remain native rather than passing through an
unheard common adapter; marimba charleo and several regional drum mechanisms remain source
proxies; study sidecars replay through the generator rather than app registration. This keeps
the first build concrete and auditable while preserving the acoustic refinement work above.

### Listening revision: alegre focus

Josh found revision-two scraper/shaker/marimba closer but the alegre still bland.
`PatioHand` now isolates head, air/shell response and finite skin contact in two
new studies. The detailed design, measured checks, replay command and limitations
are recorded in `docs/PATIO.md` under Revision three. Original recipe version 1
and revision-two audio remain intact. The audition page compares previous dry,
new dry and the same tape/amp chain, with four-touch sequences. No MPC program
replacement or acoustic-authenticity claim is made by this audition revision.


Performance delivery update (2026-11-09): `PatioPerformance` supplies Cumbia,
Samba and Latin Dub shelf kits, each with 18 pads (16 finite voices plus authored
roll/riser), three velocity zones and four source-varied takes per zone.
`ArrangedPad.velocityTakes` preserves the grid through saving and playback;
`PatioPrograms` supplies native MPC 3 exports with cycling zones, genre clips
and a source-recipe sidecar. See `docs/PATIO.md` for verification and format limits.
