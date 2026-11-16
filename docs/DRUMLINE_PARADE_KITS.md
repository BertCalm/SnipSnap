# Second Line and Samba Bateria

Status: first Python listening study. Two proposed additional kits, bringing the intended drumline collection to four. Field and Street remain the approved native kits. The new kits are not registered on the factory shelf and have not passed native integration or owner listening gates.

Repository inspected: `e7ccfef58`. `DrumlineKits` currently accepts only versioned `DrumlinePatch` recipes, and `DrumlinePresets.all()` promotes every kit enum entry automatically. Do not add experimental kit enum entries there: that would silently promote unapproved recipes. Native delivery needs an explicit accepted-kit boundary and mixed-source recipes where required.

The characteristic gesture is a bass pulse → independent snare/skin and metal responses → interlocking accents → a finite ensemble release.

## Instrumentation and arrangement

**Second Line**, 104 BPM study: one bass drummer, one snare drummer and a compact cymbal part. Sixteen pads include open/muted bass, center/ghost snare, rimshot, cross-stick, drag, flam, press buzz, roll, crash/choke/touch, bell, stick click and turnaround. Ghosts, loose subdivisions and syncopated bass/snare responses distinguish it from a marching snare section. The bell is an optional kit color, not a claim that every New Orleans parade uses one. The groove is an authored sketch, not a transcription of a band or a universal second-line pattern.

**Samba Bateria**, 140 BPM Rio-style study: low/mid surdos each with open/muted strokes, a higher cutting surdo, caixa and caixa rim, repinique open/rim and a call, tamborim open/muted, forward/back chocalho and low/high agogô. The player-count proposals are two per surdo role, four caixas, six tamborins, three chocalhos, one lead repinique and one agogô player. Different samba schools use different tunings, arrangements and section sizes. This study is not samba-reggae and does not claim school-specific authenticity. The low surdo emphasizes the second pulse, the middle drum answers, and the higher drum adds syncopation. Cuíca and pandeiro can be later additions rather than displacing these first sixteen roles.

## Mechanisms and landing

The study uses a finite raised-cosine contact driving positively damped membrane or metal modes; wire noise adds caixa/snare response. Rim contact has its own resonances. Rattle contacts form a seeded burst cloud. Sections sum independently detuned bodies with small onset differences; takes re-excite fixed identities. Rolls/calls mix raw strikes before one output stage. These are procedural listening shapes, not fitted acoustic measurements or a physically coupled ensemble.

`Drumline` already supplies the marching battery and stable player identities. `Calabash` supplies agogô/bell and shaker mechanisms; `Terra` supplies membrane/conical-bell models, and PATIO already explores surdo, caixa, agogô and cuíca. None is a complete Rio bateria. In particular, timbale/bongo renamed as repinique/tamborim would not establish those instruments' identities.

Three implementation directions were considered:

1. **Selected: compose two authored kits and add only missing voice/articulation mechanisms.** Clear gestures, distinct instrumentation relative to Drumline/Calabash, moderate sound-design risk, smallest native integration scope. Prototype first, then reuse measured native mechanisms where they fit.
2. **Retune existing Drumline recipes only.** Cheap and low numerical risk, but insufficient distinction for tamborim, repinique and chocalho. Useful for the New Orleans source base, insufficient for the complete brief.
3. **Build a separate coupled-ensemble engine.** Potentially richer interaction but high solver/render cost and no evidence that coupling is needed for the requested kits.

## Listening study and validation

Run `python3 prototype/drumline_parade/study.py --output /absolute/output/directory` with NumPy and SciPy. It uses the repository's Luthier `snipkit` output approximation: 176.4 kHz internally, 44.1 kHz mono output, RMS-proxy leveling, DC removal and a finite fade. It is not the Kotlin renderer and does not reproduce its noise bytes, FIR coefficients or loudness meter.

Each kit has sixteen pads with three velocity levels and three takes. The harness checks finiteness, sample peak, DC, a zero final frame, exact deterministic replay, take correlation below 0.99995 and normalized soft/hard waveform difference above 0.01. That velocity check rejects gain-only copies but is not proof of perceptual strike realism. Every pad has a hard-hit preview; each kit has an eight-bar groove and a ZIP of WAVs/recipes. ZIPs are study files, not MPC programs. No samples of existing performers are used.

Native acceptance still requires heard instrument identity, saved recipe/version support, full velocity/take selection and persistence, playback/export read-back, appropriate shelf registration, numerical/alias checks, render-budget measurements and phone/MPC validation. The current approved kit goldens must remain unchanged.

## Why it is built this way

- A compact second-line rhythm section needs space and individual gestures; copying six marching snares would obscure that distinction.
- A bateria gets its identity from interlocking instrument roles, not merely faster marching drums.
- Separate open/muted strokes let performers shape articulation without hard-gating every sample.
- Stable player bodies with fresh contacts keep sections consistent while avoiding identical repeated hits.
- The Python study provides a concrete listening target before committing new native voices or factory presets.

Measured first round: all 288 grid renders and 32 deterministic replays completed with exit 0. Maximum hard-take correlation was 0.99948007; minimum normalized soft/hard waveform difference was 0.14460. All rendered grid files passed finite/peak/DC/final-frame checks. These are Python harness results, not native or listening acceptance. Per-pad results are in `prototype/drumline_parade/metrics.csv`.

## Cymbal, rim and wood revision, 2026-11-16

The owner rejected the prototype cymbals, rimshot and both stick sounds as unrealistic. This second Python listening round changes eight pads: the three Second Line cymbals, rimshot, cross-stick and stick-click, plus caixa rim and repinique rim. The other 24 pads and their 216 velocity/take WAVs are byte-identical to the first study. Approved native Field/Street audio is untouched.

`contacts.py` replaces the sparse cymbal bank with 144 irregular plate modes, fixed body phases and a separate broad strike wash. Lower plate modes persist after the wash. Choke contact starts at 180 ms and adds integrated smoothstep loss, increasingly strong at higher frequencies; it does not zero the plate. The crash has a 4.5-second render window. Rim and membrane share each player’s strike onset. Rimshots combine a membrane body with nine short rim/shell modes, a finite contact pulse and a brief noise transient; repinique stays wire-free. Cross-stick and free stick-click have distinct mode tables, contact widths and lifetimes, plus a short wood-impact transient. Constants remain proposed listening shapes, not instrument measurements.

The 288 grid renders and 32 deterministic replays completed with exit 0. Maximum hard-take correlation remains 0.99948007; minimum normalized soft/hard waveform difference is 0.12379531. Numerical bounds and diversity thresholds are unchanged. `compare.py` verifies all 216 unaffected WAV hashes and produces eight before/after pairs matched by first-150-ms RMS with a shared pair peak ceiling. That is an attack-level match, not a perceptual loudness measurement. The crash's 300–700 ms RMS energy relative to its initial 150 ms rises from -14.10 to -7.86 dB; the choke retains progressive decay in the same interval. The report uses PCM16 input, with -200 dB as a numerical floor for quantized silence; those floor values do not establish physical decay levels.

Reproduce the revision with `study.py --output <new-directory>`, then `compare.py --previous <first-study-directory> --current <new-directory>`. First-study source is commit `e7ad23880`; both scripts need NumPy/SciPy and the repository Luthier helpers. Per-pad numerical results and the comparison report are committed alongside the scripts. Both kit grooves and WAV ZIPs are regenerated. Asset links and ZIP integrity pass; browser playback and owner acceptance remain unverified. No native-kit completion or all-gates pass is claimed.
