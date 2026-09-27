# SILK research — shamisen, oud, guzheng, santur, and tuning

**Date:** 2026-09-27
**For:** [`../specs/2026-09-27-silk-string-engine-design.md`](../specs/2026-09-27-silk-string-engine-design.md)

## 0. Preamble — read this before using any number below

The rule is the one [`2026-09-25-pluck-depth-body-research.md`](2026-09-25-pluck-depth-body-research.md)
set: every number is tied to a source that was **opened and read
directly**, with the supporting sentence quoted. `confirmed` means read
directly and matched verbatim. `unsupported` means seen only in a search
snippet, an abstract or a secondary mention, or the source could not be
opened — and **unsupported values are NOT to be used in code.**

**This pass ran under a network policy that allowed only GitHub and the
package registries.** Every scholarly host — pub.dega-akustik.de (reachable
on 2026-09-25), Wikipedia, CCRMA, arXiv, ResearchGate, PubMed Central,
J-STAGE, BioResources, Springer, World Scientific, AIP/JASA, DAFx archives
and more — answered `EGRESS_BLOCKED` or a proxy 403. None was retried or
routed around. So:

- **No instrument measurement for any of the four instruments is
  confirmed.** Body modes, decay times, course detunes, open-string pitches
  and inharmonicity coefficients are all `unsupported`. The spec treats
  every one as a placeholder ("shape, not measurement").
- What *is* confirmed came from files hosted on GitHub: a co-author's copy
  of the DAFx-16 tanpura paper (van Walstijn, Bridges & Mehes — the
  collision method SAWARI will use), two author-lineage dispersion
  implementations (Van Duyne's CLM piano; J. O. Smith's Faust port of
  Rauhala–Välimäki), and encyclopedia text on the shamisen (NICT's
  bilingual Wikipedia corpus, a 2009 Wikipedia snapshot).
- Values marked `computed` are this pass's own arithmetic (the `scratchpad/gz/*.py` scripts were throwaway and are not committed), with the script
  named; they are not literature.

**Re-run plan.** With network access widened, re-open in this order:
Sinin et al. 2026 (guzheng, BioResources); Inanli & Altinsoy, DAGA 2018
(oud, pub.dega-akustik.de); Taguti & Tohnai 2001 (biwa sawari, J-STAGE);
the MIT sound-hole thesis (oud); Van Duyne & Smith 1994, Rauhala &
Välimäki 2006, Abel, Välimäki & Smith 2010 (dispersion); Siddiq 2012
(Archives of Acoustics — not DAFx, as the brief said). Each section's own
source table lists the rest.

Sections 1–4 are one instrument each and keep their researchers' inner numbering (`N.`, `Z.`);
each is self-contained, with its own sources table, its own "what the
literature cannot give", and a "modelling implications" subsection that
is clearly marked as synthesis, not data.

---

## 1. Shamisen (三味線: three-string lute with a skin body, sawari buzz, bachi strike)

**Read this first: sourcing conditions this run.** The session's egress policy blocked every scholarly host tried: J-STAGE, AIP/JASA, ResearchGate, Semantic Scholar, Wikipedia (en/ja), arXiv, DAFx archives (dafx.de, dafx14.fau.de, dafx17), Archives of Acoustics (acoustics.ippt.pan.pl, psjd.icm.edu.pl), BioResources (NCSU), CiNii, HAL, CORE, Zenodo, Unpaywall, Internet Archive, CCRMA, Euphonics, KTH, and vendor pages (sonica.jp, takaramon.com). All of them returned `EGRESS_BLOCKED` or a proxy 403. Per the README these are policy denials, so they were not retried or routed around. Only GitHub-hosted content could be opened. Every source below marked "read directly" was therefore a file on GitHub: a copy of a peer-reviewed DAFx paper that its co-author posted, a published NICT corpus mirrored on GitHub, a 2009 Wikipedia snapshot, and two open-source codebases.

**Consequences:** (a) **No shamisen acoustic measurement at all was opened this run.** There is no confirmed sawari spectrum, no dō/skin mode frequency, no t60 and no open-string Hz. (b) Every *measured* shamisen or biwa number that turned up (in search snippets or a third-party note) is **unsupported and NOT to be used in code**. (c) The confirmed numbers are of three kinds, and a "Kind" column keeps them apart: **measurement / model parameter** (from the tanpura paper: a *transferable method*, not shamisen data), **code constant** (uncited values in someone's software: not measurements), and **descriptive** (encyclopedic facts, intervals and proportions).

Status legend as in §0: `confirmed` = opened directly and the quoted text matches verbatim; `unsupported` = seen only in a search snippet, abstract or secondary mention, or the source was unreachable. **Unsupported values are NOT to be used in code.**

### N.1 Mechanism and instrument facts (descriptive, no Hz)

| # | Fact | Kind | Status | Src |
|---|---|---|---|---|
| F1 | Sawari acts on the **open 1st string (ichi no ito, the thickest/lowest) only**. The string lightly touches the neck (sao) near its tuning-peg end and gives a "bīn" buzz. | descriptive | confirmed | 1, 2 |
| F2 | Acoustic effect as stated: **raises the overtone content and prolongs the ring** (倍音成分を増やして…響きを延ばす). It is "a kind of noise" but "indispensable". | descriptive | confirmed | 1 |
| F3 | Stopped notes on strings 2 and 3 at certain positions (which ones depends on the tuning) get the **same effect through resonance**, i.e. sympathetic excitation of the open, buzzing 1st string. Which notes "light up" therefore changes with the tuning. | descriptive | confirmed | 1 |
| F4 | **Azuma-zawari**: a screw-type sawari set into the neck (adjustable). | descriptive | confirmed | 1 |
| F5 | The body (dō) has skin on **both faces** ("front and back ... in the manner of a banjo"). | descriptive | confirmed | 1, 2 |
| F6 | Skin: cat belly traditionally. Dog skin is now ~70% of the total and is used on Tsugaru shamisen "with some exceptions". Synthetic skin exists but is "not preferred" for its tone. **Kangaroo skin: not mentioned in any source read.** | descriptive | confirmed (kangaroo: not found) | 1, 2 |
| F7 | Strings: silk. Tsugaru also uses **nylon or Tetoron (polyester)**. (The 2009 Wikipedia text says "silk, or, more recently, nylon".) | descriptive | confirmed | 1, 2 |
| F8 | Bachi: "the bachi is often used to **strike both string and skin**, creating a highly percussive sound." | descriptive | confirmed | 2 |
| F9 | Sizes and genres: **hosozao** = nagauta (kabuki). **chuzao** = jiuta, tokiwazu, kiyomoto, shinnai. **futozao** = gidayū (bunraku) and Tsugaru. Gidayū uses a "big, thick bachi". Tsugaru uses a small bachi with a tortoiseshell tip. **No dimensions (scale length, dō size) were found in any source read.** | descriptive | confirmed | 1, 2 |

**Quotes (verbatim):**
- F1/F2 (src 1, ja + NICT en): 「通常、一の糸の巻き取り部の近くに『さわり』と呼ばれるしくみがある。」「これは一の糸の開放弦をわずかに棹に接触させることによって『ビーン』という音を出させるもので、倍音成分を増やして音色に味を付け、響きを延ばす効果がある。」 NICT's English: "This mechanism makes the instrument twang from the open string of ichi no ito touching the sao slightly, increasing harmonic overtones to sound more appealing and sustains longer." Src 2: "The lowest passes over a small hump at the "nut" end so that it buzzes, creating a characteristic sound known as sawari".
- F3 (src 1): "shamisen, unlike other instruments, has 'sawari' only for ichi no ito, but ni no ito and san no ito also have sounds of the same effects caused by resonance on certain points (which change according to tunings)".
- F4 (src 1): "There is a screw type sawari implanted in sao, called 'azuma sawari.'"
- F6 (src 1): "nowadays dog skin is used for practice shamisen and others, accounting for seventy-percent of the total." / "For Tsugaru-jamisen ... with some exceptions, dog skin is used." / "Synthetic skin is also used sometimes, but it is not preferred because its tone quality is inferior to natural skin."
- F7 (src 1): "Shamisen has three strings made of silk." / "For Tsugaru-jamisen, nylon or Tetoron ... strings are also used."
- F8 (src 2): "As in the clawhammer style of American banjo playing, the bachi is often used to strike both string and skin, creating a highly percussive sound."
- F9 (src 1): "Nagauta shamisen ...: Hosozao." / "Gidayu shamisen ...: Futozao. ... It is played with big, thick bachi." / "Jiuta shamisen: Chuzao." / "Tsugaru-jamisen: Futozao. ... small bachi that has a tip made of tortoiseshell."

### N.2 Numeric values

| # | Quantity | Value | Kind | Status | Src |
|---|---|---|---|---|---|
| S1 | Honchōshi tuning (strings 1-2-3) | 2nd = **perfect 4th** above 1st, 3rd = **octave** above 1st (e.g. C-F-C) | descriptive (interval) | confirmed | 1 |
| S2 | Niagari | 2nd = **perfect 5th**, 3rd = **octave** (C-G-C) | descriptive (interval) | confirmed | 1 |
| S3 | Sansagari | 2nd = **perfect 4th**, 3rd = **minor 7th above** (C-F-B♭) | descriptive (interval) | confirmed (see verifier note) | 1 |
| S4 | Share of dog skin among current shamisen | **~70%** | descriptive | confirmed | 1 |
| S5 | Absolute open-string pitch (any genre) | **none found in a source read**. Tunings are relative: "If ichi no ito is C ..." is an example key, not a standard pitch | — | — | 1 |
| S6 | Code constants, string tension T1/T2/T3 (Shamisen-JUCE FD model) | 138.67 / 145.53 / 140.73 N; radii 4.15e-4 / 2.83e-4 / 2.10e-4 m; ρ 1156.481 kg/m³; E 9.9e9 Pa; σ0 1.378 s⁻¹; σ1 3.57e-3; **L = 1 m** | code constant, **not a measurement**, no provenance in repo | confirmed (as code) | 5 |
| S7 | Code constants, membrane (same repo) | T 4000 N/m; thickness 0.0002 m; ρ 1150 kg/m³; E 3e9 Pa; ν 0.4; σ0 1.378062…×2; σ1 0.096055…×2; **Lx = Ly = 1 m** | code constant, **not a measurement** | confirmed (as code) | 5 |
| S8 | Sawari effect on Chikuzen **biwa**: partials "6th to 20th and up" intensified *and* their durations elongated | partials 6–20+ | measurement (wrong instrument) | **unsupported** (search snippet of abstract only; J-STAGE blocked). NOT to be used in code | 6 |
| S9 | Fundamental/low partials said to sit **30–40 dB** below partials 6–12 under biwa sawari | 30–40 dB | measurement (wrong instrument) | **unsupported** (seen only in a third-party AI-written research note that itself flags it unverified). NOT to be used in code | 6, 11 |
| S10 | Okinawan "shamisen" (i.e. sanshin, python skin): resonant peaks 1000–5000 Hz; sawari "increases higher frequency sound pressure" | 1–5 kHz | measurement (different instrument) | **unsupported** (search snippet only; BioResources blocked). NOT to be used in code | 7 |
| S11 | Open strings "C5 (523 Hz), G4 (392 Hz), C4 (261 Hz)" (same Okinawan paper, per snippet) | as stated | measurement? | **unsupported** (snippet; string order looks garbled). NOT to be used in code | 7 |
| S12 | JSME D&D 2012 "1607 Sound and Vibration of SHAMISEN": modal analysis of body and skin, FEM of the cavity | no numbers seen | — | **unsupported** (title/abstract snippet only; CiNii/RG blocked) | 8 |

**Verifier notes.** (i) S3: the NICT English translation says "san no ito a minor seventh **below**". That is a translation error. The Japanese says 「三の糸を短７度**高く**」 (higher), and the worked example "C-F-B♭" agrees. Use "above". (ii) Converting S1–S3 to cents (500/700/1200/1000 in 12-TET) is **our arithmetic, not sourced**. No read source says whether players tune these intervals just or tempered. (iii) S6: inserting S6's constants into the code's own wave-speed line (`cSq = T / (rho * A)`, so f0 = √cSq / 2L) gives open strings of **235.4 / 353.6 / 468.6 Hz**. That is a 1 : 1.502 : 1.991 ratio, i.e. a niagari-shaped tuning, **on a 1 m string, which is not a real shamisen scale**. This is our derivation from uncited code, so it is **NOT a pitch reference**. It is recorded only so nobody mistakes S6 for measured data.

### N.3 Transferable collision / buzz DSP (tanpura and sitar), for method, not shamisen values

| # | Quantity | Value | Kind | Status | Src |
|---|---|---|---|---|---|
| T1 | Bridge contact law | one-sided, **exponent 1** (lossless), F_b = k_b⌊h_b − y_b⌋ | model | confirmed | 3 |
| T2 | Contact stiffness k_b (steel string on ivory) | **4.39×10⁸ N/m**, from k_b = ¼πE*w_b, w_b = 2.0 mm | model parameter | confirmed | 3 |
| T3 | String damping fit, 1 m steel tanpura string, r = 0.14 mm, C3 = 130.81 Hz | σ0 **0.6 s⁻¹**, σ1 **6.5×10⁻³ m/s**, σ3 **5.0×10⁻⁶ m³/s**, with α_i = σ0 + σ1β_i + σ3β_i³ | measurement-fitted parameter (tanpura) | confirmed | 3 |
| T4 | Thread (soft termination) | k_c = w_c·10⁸ N/m, r_c = k_c·10⁻⁶ (set empirically) | model parameter | confirmed | 3 |
| T5 | String–bridge compression | **< 3×10⁻⁸ m** over the whole note, i.e. effectively a rigid one-sided wall | simulation result | confirmed | 3 |
| T6 | Perceived envelope | sound "grows in amplitude over the initial **500 ms**" (high-pass body + precursor) | simulation result | confirmed | 3 |
| T7 | Sample rate | 44.1 kHz bridge signal "artificially strong" above **15 kHz**; **2× oversampling (88.2 kHz)** makes differences "almost unnoticable" | simulation result | confirmed | 3 |
| T8 | Stiffness needed for the buzz | with EI = 0 the precursor ("the principal oscillatory manifestation of the jvari effect") **disappears** | simulation result | confirmed | 3 |
| T9 | STK `Sitar` (Cook & Scavone): KS with no collision | per note-on, delay jumps ×(1 + 0.05·noise) then glides back ×0.99999 or ×1.00001 per sample; loop gain 0.995 + f·5e-7 (cap 0.9995); one-zero loop filter at 0.01; noise burst through ADSR 1 ms / 40 ms | code constant (engineering hack, not physics) | confirmed | 4 |

**Quotes (src 3, verbatim):** T1: "The impactive contact with the bridge is modelled here using a lossless contact law with unity exponent and elasticity constant kb". T2/T3: Table 1 "kb 4.39×10⁸[N/m] ... σ0 0.6 [s−1] ... σ1 6.5 ×10−3[m/s] ... σ3 5.0 ×10−6 [m3/s]", and "one metre long male tanpura string (steel, radius = 0.14mm) when tuned to the note C3 (f1 = 130.81Hz)". T4: "empirically set here as kc = wc · 10⁸ N/m and rc = kc · 10−6 N/m". T5: "The string-bridge compression is extremely small (less than 3 × 10−8m over the whole duration)." T6: "The aural impression is therefore that the sound grows in amplitude over the initial 500ms, which is contrary to our normal experience of plucked strings." T7: "the 44.1kHz model bridge signal is artificially strong in amplitude at high frequencies (f > 15kHz) ... oversampling by a factor two (i.e. using fs = 88.2kHz) is sufficient". T8: "simulation with EI = 0 confirms that the precursor, which is the principal oscillatory manifestation of the jvari effect, disappears in the absence of string stiffness." Also: "the spectral centroid of the precursor gradually diminishes over time, which is due to the string damping being frequency-dependent", and body: "the body radiates less powerfully in this frequency range, to a first approximation acting as a high-pass filter". **Misprint flag:** the paper prints "a C4 string (f1 = 198Hz)". C4 is not 198 Hz at any common reference, so do not reuse that figure. T9 (src 4): `delay_ = targetDelay_ * (1.0 + (0.05 * noise_.tick()));` / `loopGain_ = 0.995 + (frequency * 0.0000005); if ( loopGain_ > 0.9995 ) loopGain_ = 0.9995;` / `loopFilter_.setZero( 0.01 );` / `envelope_.setAllTimes( 0.001, 0.04, 0.0, 0.5 );`.

**Collision-model literature that was named but not opened (NOT read, no numbers taken):** Siddiq 2012 (the user brief says DAFx, but it is *Archives of Acoustics* 37(1), per the search result and src 3 ref [8]); Bilbao & Torin, DAFx-14 (fretboard collisions); Bilbao, Torin & Chatziioannou 2015 (*Acta Acustica*); Chatziioannou & van Walstijn 2015 (*JSV*); Taguti 2008, "Dynamics of simple string subject to unilateral constraint: A model analysis of sawari mechanism" (*AST* 29(3)); Kartofelev, Stulov, Lehtonen & Välimäki, SMAC 2013; Krishnaswamy & Smith, WASPAA 2003; Valette et al. 1991 ("tanpura bridge as a precursive wave generator"). Issanchou et al. was not located at all. These are listed only as reading leads.

### N.4 What the literature cannot give (this run)

- **No shamisen measurement of anything was read.** The one directly relevant sawari measurement (Taguti & Tohnai 2001, biwa) and the only shamisen body/skin modal study found (JSME D&D 2012) were both unreachable. S8–S12 are recorded so nobody promotes a snippet to a parameter.
- **No dō/skin mode frequencies or decays.** No confirmed Hz, Q or t60 exists for the membrane body (two faces, cat/dog/synthetic). The Shamisen-JUCE membrane constants are uncited and use a 1 m × 1 m membrane, so they are not a body model of a real dō.
- **No bachi-strike measurements.** The skin strike is confirmed only qualitatively (F8). There is no attack time, spectrum or level relative to the string.
- **No open-string Hz, no scale lengths, no string gauges per genre.** The sources give only intervals (S1–S3) and state that gauges vary by genre ("the size to use depends on the rendition").
- **No note t60 or sustain figures.** The only sustain statement is qualitative: sawari "sustains longer" (F2).
- **Sawari geometry is not the tanpura's.** Tanpura jvari is a *curved bridge at the body end*, softened by a thread. Shamisen sawari is contact with the *neck near the nut end* (F1), and it is lost when the 1st string is stopped. The tanpura parameters (T2–T5) transfer as *method and order of magnitude*, not as values.

### N.5 Modelling implications (OUR SYNTHESIS, not sourced data)

1. **Sawari = one-sided obstacle near one end of the loop, on string 1 only, open string only.** In a KS/waveguide loop, split the delay at a point a few samples from the nut termination. Apply a unilateral clamp there: `y = max(y, -h)` with h ≈ 0, or a stiff unity-exponent penalty force as in T1. Use it only when the note is on open ichi-no-ito. The DAFx-16 results suggest three rules: (a) the obstacle needs **dispersion** (an allpass in the loop, i.e. string stiffness, T8), or the characteristic precursor/brightening will not appear; (b) run the loop **2× oversampled** (T7), since offline rendering makes that cheap; (c) the obstacle should be close to rigid (T5), and a soft clamp gives a mushy buzz.
2. **Expect the "backwards" envelope.** Brightness that *holds or grows* for a few hundred ms (T6, F2) is the tell of a working sawari. A static bright EQ cannot produce it, because energy has to move upward over time. Use frequency-dependent loop damping (T3-style σ0 + σ1β + σ3β³) so that the centroid then drifts down.
3. **Sympathetic sawari (F3).** Keep an *always-present, undamped open string-1 loop with the obstacle*, and feed it a small amount of the played string's output (bridge-coupled). Stopped notes on strings 2 and 3 that land on string-1 harmonics will then buzz, and this changes with the tuning (S1–S3), as the source describes.
4. **Bachi = two parallel excitations.** Mix (i) the string pluck (hard, wide-band, short) with (ii) a separate **strike burst into a small modal skin bank** (a handful of damped 2nd-order resonators), plus a short click. The mode frequencies, Qs and mix are **unsourced**. Mark them as "shape, not measurement" and set them by audition, per §0's convention.
5. **Body.** Model the dō as a *fixed* resonator bank (as PLUCK already does) driven by the string's bridge force. With no data, keep it broad and fairly damped, and consider a gentle high-pass tilt (the tanpura's body does this, T6; it is **not** measured for the shamisen).
6. **Genre presets** (hosozao/chuzao/futozao) can differ in bachi weight (F9), sawari depth and body size by ear. Nothing sourced sets their pitches, so let the user's note choose the pitch and apply only the S1–S3 interval rules for multi-string gestures.

### N.6 Sources

| # | Citation | URL | Read directly |
|---|---|---|---|
| 1 | NICT, *Japanese-English Bilingual Corpus of Wikipedia's Kyoto Articles* v2.01, article CLT00969 「三味線」 (from jawiki-20090527 dump, professional translation), GitHub mirror | https://raw.githubusercontent.com/venali/BilingualCorpus/master/wiki_corpus_2.01/CLT/CLT00969.xml | **Yes** (curl of the GitHub mirror, parsed sentence pairs. Tertiary source.) |
| 2 | Wikipedia, "Shamisen", revision 283182194 (April 2009), HTML snapshot in a GitHub dataset | https://raw.githubusercontent.com/deeppatel710/WikiDocumentClassification/master/textFiles/1c46718eee19e9b340d255619a0e282f.txt | **Yes** (curl. Tertiary source, 2009 text; live Wikipedia blocked.) |
| 3 | van Walstijn, M., Bridges, J., & Mehes, S. (2016). "A real-time synthesis oriented tanpura model." *Proc. DAFx-16*, Brno. | https://github.com/sandormehes/sandormehes.github.io/blob/master/publications/dafx16/paper/dafx16.pdf | **Yes** (git clone of co-author's site repo, all 8 pages read) |
| 4 | Cook, P. R., & Scavone, G. P. *Synthesis ToolKit (STK)*, `Sitar.h` / `Sitar.cpp`. | https://github.com/thestk/stk/blob/master/src/Sitar.cpp | **Yes** (curl raw) |
| 5 | Lasickas, T. "Shamisen-JUCE: Real-Time Shamisen" (FD string–bridge–membrane model), `MainComponent.cpp`. Constants have no cited provenance. | https://github.com/titas2001/Shamisen-JUCE | **Yes** (git clone) |
| 6 | Taguti, T., & Tohnai, Y. (Kakuryo) (2001). "Acoustical analysis on the sawari tone of Chikuzen biwa." *Acoust. Sci. & Tech.* 22(3), 199–. | https://www.jstage.jst.go.jp/article/ast/22/3/22_3_199/_pdf | **No**: EGRESS_BLOCKED (search snippet only) |
| 7 | Hamdan et al. (vol. 21 ≈ 2026, per PDF filename). "Sound analyses of a Japanese traditional stringed instrument of Okinawa: The shamisen." *BioResources* 21(1), 918–. | https://bioresources.cnr.ncsu.edu/resources/sound-analyses-of-a-japanese-traditional-stringed-instrument-of-okinawa-the-shamisen/ | **No**: EGRESS_BLOCKED (search snippet only) |
| 8 | "1607 Sound and Vibration of SHAMISEN." JSME Dynamics & Design Conference 2012. | https://cir.nii.ac.jp/crid/1390282680822143104 | **No**: EGRESS_BLOCKED (snippet only) |
| 9 | Siddiq, S. (2012). "A physical model of the nonlinear sitar string." *Archives of Acoustics* 37(1), 73–79. | https://acoustics.ippt.pan.pl/index.php/aa/article/view/129 | **No**: EGRESS_BLOCKED (cited via src 3 ref [8]) |
| 10 | Bilbao, S., & Torin, A. (2014). "Numerical simulation of string/barrier collisions: the fretboard." *Proc. DAFx-14*. | https://www.dafx14.fau.de/papers/dafx14_stefan_bilbao_numerical_simulation_of_s.pdf | **No**: EGRESS_BLOCKED |
| 11 | Crack-Pantelimon/bespoke-mcp-data-pack, `shamisen/RESEARCH.md` (third-party, self-described as relayed from a research subagent and unverified) | https://github.com/Crack-Pantelimon/bespoke-mcp-data-pack | Yes, but a **secondary, unverified** source. Nothing is taken from it except the flag on S9. |


---

## 2. Oud (Arabic / Turkish short-necked fretless lute, bowl-back soundbox)

**No value in this section is confirmed.** Every value below is **unsupported** and **NOT to be used in code**. The reason is access, not plausibility. In this run the session's network egress policy allowed only GitHub and the package registries. Every scholarly host returned `EGRESS_BLOCKED` or a proxy `CONNECT 403`, whether reached by WebFetch or by curl. The blocked hosts included pub.dega-akustik.de (which the guitar/koto verifier *could* reach on 2026-09-25), link.springer.com, worldscientific.com, researchgate.net, dspace.mit.edu, ccrma.stanford.edu, en.wikipedia.org, arxiv.org, pmc.ncbi.nlm.nih.gov, dergipark.org.tr, api.openalex.org, web.archive.org and every oud-luthier or teaching site tried. GitHub code and repo search turned up no scholarly oud material, only hobby synth code, which is not a citable source. So every number below comes from a **search-engine result summary**. Under the preamble's rule, that counts the same as an abstract-only or secondary mention. The values are recorded only so that nobody re-derives them from memory and passes them off as sourced. A re-run with pub.dega-akustik.de reachable would likely turn the O-rows below into `confirmed` or `corrected`. That host is the one to try first (see N.5).

"Quote" rows below are **search-engine snippet text, not verbatim source text**. They were not checked against the source document and may be paraphrased by the search engine.

### N.1 Body: soundbox (qas'a) and soundboard (wajh) modes

| # | Label | Hz | Relative strength | Q / decay | Status | Src |
|---|---|---|---|---|---|---|
| O1 | Lowest measured peak (identity unknown; possibly a rigid-body or suspension mode) | 31 | not reported | not reported | **unsupported** | 1 (attribution uncertain) |
| O2 | Measured peak | 51 | not reported | not reported | **unsupported** | 1 (attr. uncertain) |
| O3 | Measured peak | 69 | not reported | not reported | **unsupported** | 1 (attr. uncertain) |
| O4 | Measured peak (a candidate for the air/Helmholtz-coupled mode, **not labelled as such in anything seen**) | 115 | not reported | not reported | **unsupported** | 1 (attr. uncertain) |
| O5 | Measured peak (a candidate top-plate/air coupled mode, **not labelled**) | 123 | not reported | not reported | **unsupported** | 1 (attr. uncertain) |
| O6 | Measured peak | 198 | not reported | not reported | **unsupported** | 1 (attr. uncertain) |
| O7 | Measured peak | 292 | not reported | not reported | **unsupported** | 1 (attr. uncertain) |
| O8 | Measured peak | 347 | not reported | not reported | **unsupported** | 1 (attr. uncertain) |
| O9 | FE coupled-model SPL resonances, soundboard 2.1 mm | 340, 900 | "amplitude of acoustic pressure substantially increases" | not reported | **unsupported** | 2 |
| O10 | FE coupled-model SPL resonance, soundboard 1.7 mm | 1040 | "increases significantly" | not reported | **unsupported** | 2 |
| O11 | FE coupled-model SPL resonance, soundboard 2.9 mm | ~700 | "strong pitch frequency" | not reported | **unsupported** | 2 |
| L1 | *European* bowl-back **lute** (not an oud): Helmholtz air mode | 132 | not reported | not reported | **unsupported** (and wrong instrument) | 6 |
| L2 | *European lute* top-plate resonances | 304, 395, 602 | not reported | not reported | **unsupported** (wrong instrument) | 6 |

**Snippet text (unverified, see caveat above):**
- O1–O8: "Eight peaks were observed corresponding to different resonance frequencies; 31 Hz, 51 Hz, 69 Hz, 115 Hz, 123 Hz, 198 Hz, 292 Hz and 347 Hz." Nearby snippet text on the same result page: "The soundboard has a thickness varying between 1.5-2.0 mm and seven fan braces are placed on the inner side parallel to each other and perpendicular to the string alignment." The search engine showed these beside the DAGA 2018 paper (#1), and in a second search beside the World Scientific paper (#3). **Which paper the numbers come from is therefore not established.** An exact-phrase search for the eight-number string found no page. Nothing seen says which peak is the Helmholtz mode, which are plate modes, or how they were excited and measured.
- O9–O11: "The amplitude of acoustic pressure substantially increases at resonances that appeared at 340 Hz and 900 Hz when the thickness of soundboard is 2.1 mm ... when the thickness of the soundboard is 1.7 mm, the amplitude of the pitch frequency of 1040 Hz increases significantly, and when the soundboard thickness is 2.9 mm, a strong pitch frequency of around 700 Hz can be obtained." These are **finite-element predictions for design variants**, not measurements of a playing instrument.
- #3/#4 (no Hz): "An experimental modal analysis was conducted using impact hammer testing to determine the oud's soundboard's dynamic characteristics and frequency response function for up to 400Hz." #4: "bracing strongly governs the stiffness distribution, thereby shifting the modal spectrum and the soundboard's radiation behavior" (14 bracing configurations).
- L1/L2: "Firth associates the peak at 132 Hz with the Helmholtz air mode and the peaks at 304, 395, and 602 Hz with resonances in the top plate of the lute." This is a second-hand mention of Firth (1977), surfaced through a search snippet. It is a European lute, not an oud, and is listed only as a lead to follow.
- Soundholes (#7, no Hz for the oud): "the variation in resonance frequency and bandwidth of different traditional rosettes with fixed outer diameter is less than a semitone". The source also notes "multiple sound-holes in oud families". This is relevant because many ouds have one main rosette plus two smaller ones, which likely means more than one air-coupling path. The count and effect were not quantified in anything read.

### N.2 Courses: paired unison strings and detuning

| # | Item | Value | Status | Src |
|---|---|---|---|---|
| C1 | Course layout | "eleven strings arranged as five double courses plus one single bass string, although some have six paired courses" | **unsupported** | 8 |
| C2 | Measured detuning or beat rate between the two strings of a course | **no source found**, not even a snippet | n/a | — |
| C3 | Size of detune a player can hear as beating ("a two-cent error as a slow pulse") | 2 cents | **unsupported** (tuning-app marketing text, not a measurement) | 9 |

The only relevant text found is practical tuning advice, not acoustics: "The two strings in a course should produce the same pitch, but when they are slightly apart, you hear a pulse or 'beating.'" No paper was found that measures the residual in-course mistuning on played ouds, the bridge coupling between the course strings, or the two-stage (prompt/aftersound) decay that such coupling produces.

### N.3 Tuning, strings, risha, neck

| # | Item | Value (as snippet reports it) | Status | Src |
|---|---|---|---|---|
| T1 | Arabic tuning, low → high | C2–F2–A2–D3–G3–C4 ("C, F, A, d, g, c") | **unsupported** | 8 |
| T2 | Turkish tuning, low → high | **snippets contradict each other**: "C#, F#, B, e, a, d" *and* "D2–A2–B2–E3–A3–D4", both described as "one whole step higher than Arabic" (which fits neither exactly as written) | **unsupported, internally inconsistent**. Do not pick one. | 8, 10 |
| T3 | String materials | "silk, nylon, copper and/or silver bound wire"; plain nylon (ordinary or "rectified") trebles; basses "wound with ... copper, silver, or nickel" | **unsupported** | 11 |
| T4 | String tension | "3.4 Kg each string" (one commercial set) | **unsupported** | 12 |
| T5 | Risha / mızrap | "a long, flexible plectrum held loosely between thumb and index finger, historically cut from a quill"; "traditionally made from an eagle's feather ... today ... plastic, and less often from tortoise shell or filed down animal bone" | **unsupported** | 8 |
| T6 | Neck | fretless (the basis for slides, glissandi and microtonal maqam intervals) | **unsupported** (no source read) | — |
| T7 | Scale length | **no source found** | n/a | — |

### N.4 Sustain, decay, timbre, inharmonicity

Nothing found. None of the queries turned up a measured t60, partial-decay curve, spectral centroid, or inharmonicity coefficient B for oud strings or for the whole instrument, not even at snippet level. The only nearby lead is Inanli & Altinsoy (#1), whose stated scope ("how this historical musical instrument's many structural parts affect its acoustics and sound quality") might include such data. Its content is unknown.

### N.5 What the literature cannot give (in this run)

- **No confirmed body mode.** The eight-peak list (O1–O8) is the most promising data. It is probably an impact-hammer or FRF measurement on one Turkish oud, but three things are missing: an established source attribution, a mode identification (which one is A0/Helmholtz?), and any Q or bandwidth. The FE results (O9–O11) are design-variant simulations whose SPL peaks move by hundreds of Hz with a 0.4 mm change in plate thickness. That sensitivity alone says an oud "body" is not one fixed list.
- **No Q or decay for any oud resonance** was seen, even as a snippet. As with the guitar (§1.2), Q should be expected to fall once strings load the body.
- **Course detuning is unmeasured** in anything found. There is no source for the beat rate, the prompt/aftersound split, or the cents spread on real played ouds.
- **Turkish tuning is unresolved.** The two snippet forms contradict each other, and anything written from memory would break the rule.
- **Several leads could not be followed:** a thesis on sound-hole design across the lute/oud families (#7), Acar/Karakaş/Oktav on oud brace patterns (#5), and Firth 1977 on the lute (#6).
- **Retry plan:** open #1 (pub.dega-akustik.de, reachable on 2026-09-25) first, then #7 (dspace.mit.edu, open access). #2–#5 are ResearchGate, Springer or World Scientific pages and may stay paywalled or bot-gated.

### N.6 Sources

| # | Citation | URL | Read directly |
|---|---|---|---|
| 1 | Inanli, S. & Altinsoy, M. E. (2018). "Vibroacoustics of Oud." DAGA 2018, München (TU Dresden, Chair of Acoustics and Haptics). | https://pub.dega-akustik.de/DAGA_2018/data/articles/000581.pdf | **No.** WebFetch `EGRESS_BLOCKED`, curl `CONNECT 403` (policy) |
| 2 | "Coupled Vibro-Acoustic Analysis of the Turkish Oud Guitar" (authors not seen). | https://www.researchgate.net/publication/340875857_Coupled_Vibro-Acoustic_Analysis_of_the_Turkish_Oud_Guitar | **No.** `EGRESS_BLOCKED` |
| 3 | "Optimizing Acoustic Performance and Structural Integrity of ... [oud]", *Int. J. Structural Stability and Dynamics*, DOI 10.1142/S0219455426500392 (2026). | https://www.worldscientific.com/doi/10.1142/S0219455426500392 | **No.** `EGRESS_BLOCKED` |
| 4 | "Vibroacoustic sensitivity of an oud soundboard to bracing geometry." *Meccanica* (2026), DOI 10.1007/s11012-026-02151-1. | https://link.springer.com/article/10.1007/s11012-026-02151-1 | **No.** `EGRESS_BLOCKED` |
| 5 | Acar, T., Karakaş, M. & Oktav, A. "Tuning the structural eigenfrequencies of an oud guitar by using different brace patterns on the soundboard." 4th Int. Conf. on Evolving Trends in Interdisciplinary Research & Practices. | https://www.researchgate.net/publication/351335073 | **No.** `EGRESS_BLOCKED` |
| 6 | Firth, I. (1977), on the lute (mentioned second-hand in a snippet, primary citation not seen). | (none) | **No** |
| 7 | "Acoustic Function of Sound Hole Design in Musical Instruments", MIT thesis (violin, lute and oud families). | https://dspace.mit.edu/bitstream/handle/1721.1/61924/707340180-MIT.pdf | **No.** `EGRESS_BLOCKED` |
| 8 | Wikipedia, "Oud" (tuning, courses, risha; the snippet text may come from this page or from #10). | https://en.wikipedia.org/wiki/Oud | **No.** `EGRESS_BLOCKED` |
| 9 | Tonalics, "Online Oud Tuner" (commercial tuning app page). | https://tonalics.com/tuner/oud | **No**, snippet only |
| 10 | Sala Muzik, "How to Tune an Oud: Arabic & Turkish Tunings" / "How to Tune a Turkish Oud" (retailer blog). | https://salamuzik.com/blogs/news/how-to-tune-oud-instrument-easily | **No**, snippet only |
| 11 | Sala Muzik, "Importance of Choosing the Right String for Oud" (retailer blog). | https://salamuzik.com/blogs/news/importance-of-choosing-the-right-string-for-oud-all-about-oud-strings | **No**, snippet only |
| 12 | Lord of the Strings, "Others: Oud" (string-maker product page). | https://lordofthestrings.com/en/others/oud | **No**, snippet only |

### N.7 Modelling implications (MY SYNTHESIS, not sourced data)

*Everything in this subsection is engineering judgement for an offline KS/waveguide engine. It is not literature. Any number here is an audition starting point, marked "shape, not measurement". It must not be cited as oud acoustics.*

1. **Two loops per double course, one for the single bass.** Render each course as two independent KS/waveguide loops at f0·2^(±d/2400), with `d` (cents) drawn once per rendered note from a seeded RNG. The seed should depend on the course and not on the note, so one "instrument" keeps a consistent character across renders. There is no sourced spread, so tune `d` by ear. Keep it small enough to give a slow shimmer rather than a chorus, and let the macro knob set it. Give the second loop a slightly different loop-filter gain as well as a different pitch. That produces uneven decay between the pair, which a pure detune does not. The bass course gets one loop only.
2. **Pair coupling (optional, closer to real double courses).** Real strings sharing a bridge are coupled, which produces a faster "prompt" decay followed by a slower "aftersound". A cheap offline version: feed a small fraction of each loop's output into the other loop's input at the bridge, or crossfade from a mixed-phase sum to an out-of-phase sum. Treat this as a knob, not a claim. No oud measurement of it exists (N.2, C2).
3. **Risha attack.** A long, flexible plectrum played close to the bridge gives a bright, percussive onset with a very short contact. Excite the loops with a short band-limited noise or impulse burst, comb-shaped by the pluck position (pluck-position comb at β ≈ small fraction of the string, chosen by ear). Give the burst a velocity-driven brightness low-pass. Add a separate, very short high-passed "tick" layer for the risha scrape. The ratio of tick to string could be a timbre macro.
4. **Fretless slides.** Drive each loop's delay length from a pitch envelope. Use fractional delay with Lagrange or all-pass interpolation, and apply an all-pass tuning correction so the loop stays in tune while the delay is moving. Offer a start offset (±semitones), a glide time and a curve (exp/linear). Because rendering is offline, the envelope can be arbitrary. Keep both loops of a course on the same slide envelope so the detune survives the glide. For maqam colour, allow a fixed quarter-tone (±50 cents) offset on a target degree. That is a musical-interface choice, not an acoustic claim.
5. **Damping and brightness.** Nylon trebles with wound basses: use a darker, faster-decaying loop filter on the top courses and a longer, brighter one on the wound basses. Add a mild all-pass dispersion stage (stiffness) on the wound courses only. There is no sourced inharmonicity B (N.4), so set dispersion by ear and keep it subtle.
6. **Body.** Do **not** write the O1–O11 Hz values into a resonator table until N.5's retry confirms them. Until then there are two options. (a) Reuse the confirmed guitar bank structure (§5.1) as a *generic* wooden-body shape, clearly labelled as such, perhaps shifted by ear toward a darker, lower-mid "boxy" character. (b) Ship the oud voice with a flat or neutral body and a "BODY" macro that can be swapped out, then fill it in once #1 is read directly. If O1–O8 are later confirmed, the likely mapping is the strongest peak near 100–130 Hz as A0 plus the 198/292/347 Hz cluster as plate modes, with Q from the paper or, failing that, the guitar's strung Q range marked "shape, not measurement".
7. **Stereo.** Pan the two loops of each course a few percent apart. The decorrelated detune then reads as width without extra chorus.


---

## 3. Guzheng (Chinese 21-string zither, movable bridges, paulownia soundboard)

**Date:** 2026-09-27

**This instrument has zero confirmed guzheng-specific numbers.** Every guzheng paper found was blocked by this session's network egress policy. The session could reach only `github.com`, `api.github.com` and `raw.githubusercontent.com`. WebFetch and curl both got `EGRESS_BLOCKED` or a CONNECT 403 from bioresources.cnr.ncsu.edu, ojs.bioresources.com, arxiv.org, scirp.org, j.bjfu.edu.cn, etheses.whiterose.ac.uk, researchgate, pubmed/europepmc, mdpi, ccrma.stanford.edu, dafx.de, en.wikipedia.org and every other publisher or mirror tried. The paywall was never the obstacle. Every guzheng value below is therefore **unsupported, NOT to be used in code**. Each is recorded, with the search-engine summary that surfaced it, only so that nobody re-derives it from memory later.

Two kinds of `confirmed` rows do appear below:

- **(a)** The **implementation** side of dispersion modelling. Two primary code sources were opened directly on GitHub: Scott Van Duyne's CLM piano instrument (Snd mirror) and Julius O. Smith's Faust implementation of the Rauhala–Välimäki dispersion filter. These are code by the method's authors or their close collaborators, not the papers. None of the three named papers (Van Duyne & Smith 1994, Rauhala & Välimäki 2006, Abel/Välimäki/Smith 2010) could be opened. Their text is therefore unquoted and their contents unsupported.
- **(b)** Arithmetic computed here, marked `computed`, with the script named. For example, 12-TET pitches and allpass phase delays.

### Z.1 String stiffness / inharmonicity

| # | Quantity | Value | Status | Src |
|---|---|---|---|---|
| Z-I1 | Which guzheng strings show inharmonic partials | "strings 2, 6, 7, 10, 11, 12, 14, 15, and 16" (all others: partials "increased gradually with the harmonic number", i.e. harmonic). **No B coefficient or cents value, even in the snippet.** | **unsupported** (search-engine summary only; PDF blocked) | 1 |
| Z-I2 | Guqin (sister zither, silk/nylon-steel strings) inharmonicity coefficients "estimated and analyzed from recorded guqin tones across all strings" | no number reached | **unsupported** | 6 |
| Z-I3 | Koto string inharmonicity (Coaldrake, ISMA 2019, FE model of koto strings) | not re-opened this run. The earlier note says only that it has "no body-mode values in it". Whether it gives B is unknown. | **unsupported** | 7 |
| Z-I4 | Piano B, *proxy only*: "0.0001 is typical" | B ≈ 1e-4 (piano, generic; not a measurement, not guzheng) | confirmed (code docstring by J. O. Smith) | 10 |
| Z-I5 | Piano stiffness-allpass coefficients, *proxy only* (Van Duyne's CLM piano), 8 first-order sections per string | −0.92 (key 21/A0) → −0.90 (24) → −0.70 (36) → −0.25 (48) → −0.10 (60) → −0.04 (75–96) → **+0.2 (99), +0.5 (108)** | confirmed (code) | 8 |
| Z-I6 | Harpsichord / steel-string zither / guitar B values | none opened. Every candidate (academia.edu harpsichord study, USC string-inharmonicity note, wirestrungharp.com) was blocked. | **unsupported (no value)** | — |

**Quotes (verbatim):**
- Z-I1: search summary of Sinin et al. 2026: "The partials harmonic increased gradually with the harmonic number except for strings 2, 6, 7, 10, 11, 12, 14, 15, and 16 where some partials are not harmonic." *(A search engine rephrased this. It is not verified against the PDF.)*
- Z-I4: `misceffects.lib`: "* `B`: string inharmonicity coefficient (0.0001 is typical)" and "* `M`: number of first-order allpass sections (compile-time only) Keep below 20. 8 is typical for medium-sized piano strings."
- Z-I5: `piano.scm` line 1: ";;; CLM piano.ins (Scott Van Duyne) translated to Snd/Scheme". Line 14: "(define number-of-stiffness-allpasses 8)". Line 39: "(define default-stiffnessCoefficient-table '(21.000 -0.920 24.000 -0.900 36.000 -0.700 48.000 -0.250 60.000 -0.100 75.179 -0.040 82.986 -0.040 92.240 -0.040 96.000 -0.040 99.000 .2 108.000 .5))". The table is keyed by MIDI key number.

**Honest bottom line for Z.1:** no measured B or cents deviation exists for any guzheng string in anything that could be opened. There is also no proxy B for koto, harpsichord or steel-strung zither. The only B figure that can be quoted is a *piano* ballpark, and it comes from a code docstring. For how much dispersion to use, see Z.7.

### Z.2 Body (soundboard / box)

| # | Label | Hz | Q / decay | Status | Src |
|---|---|---|---|---|---|
| Z-B1 | Band of higher radiation efficiency (FE simulation plus experiment) | 350–550 Hz, said to be "higher than that before 350 Hz" | not reported | **unsupported** (search summary of abstract only) | 3 |
| Z-B2 | Solid-board (整板) paulownia soundboard, experimental plus FE modal analysis | families (0,n), (1,n), (2,n); frequencies rise with order. **No Hz reached.** | not reported | **unsupported** | 4 |
| Z-B3 | Glued-panel (拼板) composite soundboard modal analysis | no Hz reached | not reported | **unsupported** | 5 |
| Z-B4 | "Resonance peaks" of a guzheng A4 note: 440, 890, 1300, 1800, 2200, 2700, 3100, 3600 Hz | **Trap: these are the partials of the played A4, not body modes.** Never put them in a fixed resonator bank. | n/a | **unsupported** | 2 |
| Z-B5 | *Koto proxy*: air (0,0) 85 Hz; first top-plate mode 100 Hz | 85, 100 | not reported | confirmed **in the existing note §5.2** (Coaldrake ICA 2019). Not re-opened this run. | 9 |

**Quotes:**
- Z-B1: search summary of Deng et al. 2016: "The acoustic radiation efficiency between 350 and 550 Hz is higher than that before 350 Hz."
- Z-B2: search summary of the BJFU 2021 abstract (Chinese): "随着振动阶次的升高，整板结构共鸣面板模态振型均趋于复杂，且对应的共振频率也逐渐增大", and "（0, n）、（1, n）和（2, n）等阶次的共振频率较易识别". In English: mode shapes grow more complex and frequencies rise with order; the (0,n), (1,n) and (2,n) families are the easiest to identify.
- Z-B4: search summary of Li & Li 2024: "Around 440 Hz, the frequency response reaches a high amplitude, corresponding to the fundamental frequency ... with other prominent resonance peaks appearing at frequencies such as 890 Hz, 1300 Hz, 1800 Hz, 2200 Hz, 2700 Hz, 3100 Hz, and 3600 Hz."

### Z.3 Playing technique

| # | Quantity | Value | Status | Src |
|---|---|---|---|---|
| Z-T1 | Upward press-bend (按/上滑音) range | "within major third" (≤ 400 cents) | **unsupported** (search summary; attribution uncertain, probably the ISMIR 2022 / Guzheng_Tech99 technique definitions) | 11 |
| Z-T2 | Maximum press range | "up to three half steps above its open pitch" | **unsupported** (search summary; source unidentified, probably a teaching site) | 12 |
| Z-T3 | Vibrato (颤音 chanyin / 揉弦): definition | "periodic oscillation of tones caused by the periodic pressing of a string with left hand". **No rate (Hz) or depth (cents) found anywhere.** | **unsupported** | 11 |
| Z-T4 | Downward bend | "decreases the pitch of a ringing note by releasing a bended string" (pre-pressed, then released) | **unsupported** | 11 |
| Z-T5 | Fingerpick (义甲) materials | plastic/resin/nylon, tortoiseshell (now CITES-banned). **No acoustic measurement of the pick's effect on the attack found.** | **unsupported** (retail and teaching pages via search) | 12 |
| Z-T6 | Attack: A4 pluck amplitude peak 34 ms after the strike | 34 ms | **unsupported** (appears only in a secondary GitHub note citing Li & Li 2024; the paper itself is blocked) | 2, 13 |

### Z.4 Standard tuning

| # | Quantity | Value | Status | Src |
|---|---|---|---|---|
| Z-U1 | Range of the 21-string instrument, string 21 → string 1 | "D2=73.41 Hz to D6=1174.7 Hz" | **unsupported** (search summary) | 1 |
| Z-U2 | Tuning: D major pentatonic, 21 strings | — | **unsupported** (Wikipedia blocked; the secondary note in source 13 says Sinin labels the set "G major", unverified) | 1, 13 |

**If** the D-pentatonic D2–D6 layout is adopted, these open-string pitches are pure 12-TET arithmetic (A4 = 440 Hz, `computed`, not a literature value). They run low to high, i.e. string 21 → string 1:

D2 73.42 · E2 82.41 · F#2 92.50 · A2 110.00 · B2 123.47 · D3 146.83 · E3 164.81 · F#3 185.00 · A3 220.00 · B3 246.94 · D4 293.66 · E4 329.63 · F#4 369.99 · A4 440.00 · B4 493.88 · D5 587.33 · E5 659.26 · F#5 739.99 · A5 880.00 · B5 987.77 · D6 1174.66

That is 21 pitches, which matches the string count. The end-points agree with Z-U1 to rounding.

### Z.5 Decay / sustain / timbre

| # | Quantity | Value | Status | Src |
|---|---|---|---|---|
| Z-D1 | Band that persists over the first 0–1.6 s of an A4 note | 370–520 Hz | **unsupported** (search summary) | 2 |
| Z-D2 | High-frequency components of A4 decay within | ~0.75 s | **unsupported** (secondary note only) | 2, 13 |
| Z-D3 | Total decay at A3, "two-stage envelope" | ~8 s | **unsupported**. The secondary note itself flags it "[unverified]". | 1, 13 |
| Z-D4 | Per-partial t60 / loss-filter data for any string | none found | — | — |

### Z.6 What the literature cannot give

- **No guzheng inharmonicity number was reached.** Even the one paper that reports per-string inharmonicity (Sinin 2026) surfaced only as the list of *which* strings are inharmonic. That list (2, 6, 7, 10…16) is not monotonic in pitch, so it does not look like a clean stiffness law. It could come from wound-vs-plain construction, bridge or pressing contact, or measurement artefacts. That cannot be judged without the paper.
- **No guzheng string construction data** (core diameter, winding, tension, speaking length) was reached, so B cannot even be *derived* from B = π³Ed⁴/(64TL²). That formula also surfaced only in a snippet and is not quoted here.
- **No body mode Hz, Q or t60** was reached for the guzheng. The Chinese modal-analysis papers (BJFU 2021, 2022) and the FE vibro-acoustics paper (Deng 2016) exist, but only their abstracts' prose surfaced. The one koto proxy (85/100 Hz) comes from a different, smaller instrument.
- **No measurement of bend depth distribution, bend rise time, vibrato rate or vibrato depth** was found. That holds even as snippets, beyond the qualitative "within a major third" and "three half steps".
- **No acoustic study of fingerpick material vs. attack spectrum** was found. The only such comments are from teachers and vendors.
- **The three dispersion-design papers were not opened.** Their sign conventions, design equations and error figures are unquoted. What Z.7 says about them rests on two author-lineage code implementations plus this session's own numerical checks.

### Z.7 Modelling implications (MY SYNTHESIS: not a literature claim unless a source # is given)

1. **Section form and sign.** Both confirmed implementations use the same first-order section, H(z) = (a + z⁻¹)/(1 + a·z⁻¹):
   - CLM (src 8, `clm.c`): "y[i] = x[i] + (coeff * (y0 - y[i]));"
   - Faust (src 10, `filters.lib`): "tf1(b0,b1,a1) = _ <: *(b0), (mem : *(b1)) :> + ~ *(0-a1);", called as `fi.tf1(a1,1,a1)`.

   The section's phase delay is (1−a)/(1+a) samples at DC and exactly 1 sample at Nyquist (`computed`, `scratchpad/gz/ap.py`). **For partials to go SHARP, the loop's delay must fall with frequency, so a must be negative (−1 < a < 0).** With a > 0 the delay rises and the partials go *flat*.

   Numerical check (`computed`, 48 kHz, f0 = 147 Hz, 4 sections, fundamental re-tuned):

   | a | k = 2 | k = 5 | k = 12 |
   |---|---|---|---|
   | −0.5 | +0.05 ¢ | +0.37 ¢ | +2.11 ¢ |
   | +0.5 | 0.00 ¢ | −0.01 ¢ | −0.03 ¢ |

   Van Duyne's table (Z-I5) is negative across the stiff range, which agrees. **Caveat:** the Faust source comment says "By Eq. 3, have D >= 0, hence a1 >= 0 also". That is wrong whenever D > 1. Its code, `a1 = (1-D)/(1+D)`, still yields a1 < 0 there, e.g. f0 = 146.83 Hz, B = 1e-4, M = 4 gives D = 5.745, a1 = −0.7035 (`computed`, `faust_check.py`). Trust the code, not the comment.

2. **Placement in the KS loop.** Put M identical sections in series inside the feedback loop, next to the delay line and loss filter. Both sources do exactly this; CLM feeds the string delay through `(one-pole-all-pass string1-stiffness-ap ...)`. The elements are LTI, so the order inside the loop does not change the resonances. Keep the dispersion cascade *outside* the swept fractional-delay element during bends, so that retuning the fraction never interacts with the dispersion coefficients.

3. **Pitch compensation.** An allpass has unit gain, but its phase delay at f0 steals loop length. Set the delay line to N = fs/f0 − M·τ_ap(f0) − τ_loss(f0) − τ_other(f0). Here τ_ap(f0) is the section's **phase delay at f0**, not at DC and not the group delay.
   - Faust returns this term directly (src 10): "MINUS the estimated delay at `f0` of allpass chain in samples, provided in negative form to facilitate subtraction from delay-line length." The code is `-Df0*M`, with Df0 = polydel(a1) − polydel(1/a1). It matches the numerically measured phase delay to 4 decimals, e.g. 5.7396 samples at 146.83 Hz (`computed`).
   - CLM (src 8) does the same in total-phase form: "(len (/ (+ two-pi (* numAllpasses (apPhase stiffnessCoefficient wT)) (opozPhase ...)) wT))". It then splits `len` into an integer delay and a fractional-delay allpass, borrowing a sample whenever the fraction is below "golden-mean .618" (`apfloor`).

   With this compensation the fundamental lands exactly and only partials k ≥ 2 stretch (`computed`: 0.00 ¢ at k = 1 in every case above).

4. **Tunable closed-form design: hazard at the top of the range.** Faust's port of the Rauhala–Välimäki closed form (src 10; constants k1…k3 = −0.00179, −0.0233, −2.93 and m1…m4 = 0.0126, 0.0606, −0.00825, 1.97, confirmed in code) gives D < 1, and therefore **a1 > 0 (flat partials, the wrong direction)**, for high, weakly stiff strings. `Computed`, M = 4:

   | f0 | B | D | a1 | k = 10 at 48 kHz |
   |---|---|---|---|---|
   | 1174.66 Hz | 1e-4 | 0.742 | +0.148 | −11.2 ¢ |
   | 880 Hz | 1e-5 | 0.623 | +0.233 | — |

   The flip happens for B ≲ 1e-5 at D5, B ≲ 1e-4 at A5 and B ≲ 3e-4 at D6. **Clamp: if D ≤ 1, bypass the cascade (a = 0 gives a pure one-sample delay per section; better, drop the sections).**

   Even where the sign is right, the fit to the stiff-string target √(1+Bk²)/√(1+B) is loose at k = 10 (`computed`, 48 kHz):
   - B = 1e-4 overshoots by 13–20 % across D2–D4. D3 gives +10.2 ¢ against a target of +8.5 ¢.
   - B = 1e-5 overshoots about 2.5×: +2.2 ¢ against a target of +0.9 ¢.

   The constants were fitted for piano, not this range. For a guzheng engine, a simpler route may be better: fix a, then measure the resulting stretch numerically (as `ap.py` does) and tune a against the audition, rather than trusting the B-to-a map.

5. **How much dispersion a guzheng needs: unknown.** There is no sourced B. For a low-pitched string, even a strong section (a = −0.5 ×4) moves the 12th partial only about 2 ¢ (point 1), because the phase-delay curve is flat far below Nyquist. Audible stretch needs B on the order of the piano ballpark (Z-I4), about 8.5 ¢ at k = 10 for B = 1e-4 (`computed` from the formula).
   - Recommendation: expose B as an **audition parameter**, default 0 (no cascade).
   - Offer a sweep of B ∈ {0, 1e-5, 3e-5, 1e-4} on D2/D3/A3, judged by ear against a real recording.
   - Treat any value that passes as **shape, not measurement**, in this repo's terms.
   - Do not cite 1e-4 as a guzheng property.

6. **Bends and vibrato.** The press raises pitch by raising tension on the plucked side, which the model renders as a shrinking delay length. Recompute the point 3 compensation per control block, because τ_ap(f0) changes with f0. Over ≤ 400 ¢ (Z-T1, unsupported) the dispersion coefficient can stay fixed; the D/a1 formula varies smoothly with key. Bend depth, rise time, vibrato rate and vibrato depth all have **no sourced value**, so leave them as audition parameters.

### Z.8 Sources

| # | Citation | URL | Read directly |
|---|---|---|---|
| 1 | Sinin, H. et al. (2026). "Acoustics of the Guzheng: Chinese Plucked Zither." *BioResources* 21(2), 5306–5328. | https://bioresources.cnr.ncsu.edu/wp-content/uploads/2026/04/BioRes_21_2_5306_Sinin_HSTM_Acoustics_Guzheng_Plucked_Zither_25391.pdf | **No.** Egress-blocked (WebFetch and curl); only a search-engine summary. |
| 2 | Li, N. & Li, D. (2024). "Acoustic Measurement and Modeling of the Traditional Chinese Instrument Guzheng in Digital Transformation…A440." *Open J. Acoustics* 12, 17–30. DOI 10.4236/oja.2024.122002 | https://www.scirp.org/pdf/oja2024122_11610220.pdf | **No.** Egress-blocked; search summary only. |
| 3 | Deng et al. (2016). "Simulation analysis of vibro-acoustic characteristics of traditional Guzheng." *J. Shanghai Jiao Tong Univ.* | https://www.researchgate.net/publication/306182261 | **No.** Blocked; search summary only. |
| 4 | "整板结构的古筝共鸣面板振动模态的研究" (solid-board guzheng soundboard modal study). *J. Beijing Forestry Univ.* (2021), DOI 10.12171/j.1000-1522.20210136 | https://j.bjfu.edu.cn/article/doi/10.12171/j.1000-1522.20210136 | **No.** Blocked; search summary only. |
| 5 | "拼板结构的古筝共鸣面板振动模态研究" (glued-panel guzheng soundboard modal study). *J. Beijing Forestry Univ.*, DOI 10.12171/j.1000-1522.20210495 | https://j.bjfu.edu.cn/en/article/doi/10.12171/j.1000-1522.20210495 | **No.** Blocked. |
| 6 | Penttinen, H., Pakarinen, J., Välimäki, V. et al. (2006). "Model-based sound synthesis of the guqin." *JASA* (volume not verified). | https://www.researchgate.net/publication/6577684 | **No.** Blocked. |
| 7 | Coaldrake, K. (2019). "Finite element modelling of Japanese koto strings." ISMA 2019. | https://pub.dega-akustik.de/ISMA2019/data/articles/000063.pdf | **No, not this run** (pub.dega-akustik.de blocked; the earlier note says it was read then). |
| 8 | Van Duyne, S. — CLM `piano.ins`, Snd/Scheme translation `piano.scm`; CLM `one_pole_all_pass_n` in `clm.c` (Snd mirror). | https://raw.githubusercontent.com/yurivict/Snd/HEAD/piano.scm and …/clm.c | **Yes** (curl via raw.githubusercontent.com; read lines 1–45, 130–175, 250–300, 480–516, and `clm.c` 9385–9409) |
| 9 | Existing repo note §2/§5.2 (Coaldrake, ICA 2019, koto 85/100 Hz). | docs/superpowers/plans/2026-09-25-pluck-depth-body-research.md | Yes (repo file; underlying PDF not re-opened) |
| 10 | Smith, J. O. — Faust `misceffects.lib` `piano_dispersion_filter` (port of Rauhala & Välimäki, DAFx-06, pp. 71–76, with the Eq. 7 erratum from Rauhala's dissertation); `filters.lib` `tf1`. | https://raw.githubusercontent.com/grame-cncm/faustlibraries/master/misceffects.lib and …/filters.lib | **Yes** (curl via raw.githubusercontent.com) |
| 11 | Li, D. et al. (2022). "Playing Technique Detection by Fusing Note Onset Information in Guzheng Performance." ISMIR 2022 (and the Guzheng_Tech99 definitions). | https://arxiv.org/pdf/2209.08774 | **No.** Blocked; attribution of the Z-T quotes to it is itself uncertain. |
| 12 | Guzheng Alive (teaching site): vibrato, portamento and pick-material pages; vendor pages. | https://guzhengalive.com/guzheng-techniques-vibrato | **No.** Blocked; search summary only. |
| 13 | Secondary: Crack-Pantelimon/bespoke-mcp-data-pack `guzheng/RESEARCH.md` (an AI-generated note citing sources 2 and 1). | https://github.com/Crack-Pantelimon/bespoke-mcp-data-pack | Yes (curl), but it is **secondary**. Used only to locate leads; nothing in it counts as confirmed. |
| 14 | Van Duyne, S. A. & Smith, J. O. (1994). "A simplified approach to modeling dispersion caused by stiffness in strings and plates." Proc. ICMC. | (ccrma.stanford.edu — blocked) | **No** |
| 15 | Rauhala, J. & Välimäki, V. (2006). "Tunable dispersion filter design for piano synthesis." *IEEE Signal Processing Letters* 13(5) (volume/issue as cited in danigb/synthlet README; pages not verified). | (IEEE / aalto.fi — blocked) | **No** (only its DAFx-06 companion's closed form, as ported in src 10) |
| 16 | Abel, J. S., Välimäki, V. & Smith, J. O. (2010). "Robust, efficient design of allpass filters for dispersive string sound synthesis." *IEEE Signal Processing Letters* (volume/pages not verified). (Abel & Smith 2006 conference precursor also not opened.) | (IEEE / ccrma — blocked) | **No** |

The verification scripts are in the scratchpad: `gz/ap.py` checks the sign and phase delay, and `gz/faust_check.py` checks the closed form and the partial stretch at 44.1 and 48 kHz.


---

## 4. Persian santur (trapezoidal hammered dulcimer, courses of four, light wooden mezrab)

**Date:** 2026-09-27

### N.0 Read this first: almost nothing here is confirmed

This run could open **one** source directly, and it is not a scholarly one. The session's network egress policy allowed only GitHub hosts (`github.com`, `api.github.com`, `raw.githubusercontent.com`). Every other host returned a proxy 403 / `EGRESS_BLOCKED`, through both `curl` and WebFetch. The blocked hosts include every candidate primary source: `pubs.aip.org`, `acoustics.org`, `luth.org`, `ismir2005.ismir.net`, `archives.ismir.net`, `repository.londonmet.ac.uk`, `arxiv.org`, `bioresources.cnr.ncsu.edu`, `www.speech.kth.se`, `digital.library.unt.edu`, `image-ppubs.uspto.gov`, `patents.google.com`, `www.researchgate.net`, `en.wikipedia.org`, `pmc.ncbi.nlm.nih.gov`, `api.openalex.org`, `web.archive.org` and `pub.dega-akustik.de`. GitHub code and repo search turned up no primary acoustics source for the santur, dulcimer, cimbalom or yangqin.

Under the repo rule (§0 of the pluck-depth note), everything below except one structural sentence is therefore **`unsupported` — NOT to be used in code**. The candidate values are recorded for two reasons: so nobody re-derives them from memory or a search snippet and treats them as sourced, and so that a later run with wider egress has an exact verification queue (N.7). The "quotes" for unsupported rows are **search-engine summary text**, which the search tool paraphrases. They are **not** verbatim source text, and that is weaker than the koto section's "quoted as originally reported".

Status legend: `confirmed` = opened directly, verbatim match. `unsupported` = only a search summary or abstract was seen, or the host was unreachable.

---

### N.1 Structure (strings, courses, bridges, split courses)

| # | Claim | Value | Status | Src |
|---|---|---|---|---|
| S1 | Strings per course/bridge position | 4 | **confirmed** (low-authority, crowd-edited source; see note) | 7 |
| S2 | Bridge count | "25 bridges" | **confirmed as quoted, but contradicts S3 and the brief's 72-string premise. Do NOT use.** | 7 |
| S3 | Total strings; number of movable bridges | 72 strings, "four per note"; 18 bridges ("easels") | **unsupported** | 5 |
| S4 | Course detuning (intentional spread between the 4 strings of a course; measured beating) | nothing found, not even in snippets | **gap** | — |
| S5 | Split-course interval (a course that crosses a treble bridge and sounds two pitches) | nothing found | **gap** | — |

**Quotes:**
- S1/S2: MusicBrainz instrument description (`po/instrument_descriptions.pot`, entry `name:santur`), read directly from raw.githubusercontent.com: *"Ancient hammered dulcimer with trapezoid walnut or maple soundbox, 25 bridges each with 4 strings that are hit by special mallets called mezrab. Used in traditional, folk and mystic Sufi music."*
- S3 (search summary of UNT Digital Library record "Santur Électroacoustique", not verbatim): *"a trapezoidal zither carrying seventy-two strings (four per note) fixed and intersecting, supported by eighteen movable easels of hardwood."*

**Note on S1/S2:** 72 strings ÷ 4 = 18 courses. That fits S3's 18 bridges and does not fit MusicBrainz's 25. Treat the MusicBrainz sentence as corroborating the course size only. Its bridge count is wrong for the instrument the brief describes, or it describes some other variant.

### N.2 Sympathetic resonance and decay

| # | Claim | Value | Status | Src |
|---|---|---|---|---|
| R1 | Where the sympathetic contribution comes from (hammered dulcimer, as a proxy) | undamped string segments between the bridge and the tuning pins | **unsupported** | 8/10 (search summary; the originating page was not identified) |
| R2 | Near-coincident tuning raises sympathetic amplitude and causes frequency veering and extra damping (clavichord, as a proxy) | qualitative only | **unsupported** | 15 |
| R3 | Measured t60 of santur notes, or of the sympathetic strings | none found | **gap** | — |

**Quotes (search summaries, not verbatim):**
- R1: *"Sympathetic vibration comes from the undamped string segments between the bridge and tuning pins."*
- R2: *"a significant increase in vibratory amplitude, frequency veering, and damping increase in the string segments when tuning approaches frequency coincidence."*
- A dulcimer comparison method (src 10) reportedly uses *"sound spectra and decay rates … as comparative parameters"* with a computer-controlled exciter. That makes it a likely place to find decay numbers, but none were visible.

### N.3 Mallet (mezrab) and excitation

| # | Claim | Value | Status | Src |
|---|---|---|---|---|
| M1 | Persian mezrab: light hardwood; the player may glue on felt | qualitative | **unsupported** | 16/17 (search summary) |
| M2 | Light Persian hammers do **not** rebound, so tremolo comes from alternating wrists. Heavier Turkish/Indian hammers bounce (an "automatic tremolo") | qualitative | **unsupported** | summary; the originating page was not identified |
| M3 | Hammer hardness and strike position strongly shape the attack and spectrum. During contact, a short string segment drives the bridge at frequencies well above f0 (hammered dulcimer) | qualitative | **unsupported** | 8 |
| M4 | Piano proxy: hammer–string contact of about 4 ms in the bass, falling to under 1 ms in the top treble. Contact shortens as dynamic level rises | 4 ms → <1 ms | **unsupported** (speech.kth.se was blocked this run, although the pluck-depth note read that host in another session) | 13 |
| M5 | Contact time, mass or felt stiffness of a real mezrab; double-strike statistics | none found | **gap** | — |

**Quotes (search summaries, not verbatim):**
- M1: *"Tradition calls for a delicate and precise tone-quality which is obtained only with light hammers of hardwood."* and *"Some players wrap the ends of the hammers with felt to soften the impact."*
- M2: *"The hammers of the Turkish and Indian instruments are heavy and bounce on the string, creating a characteristic automatic tremolo. In contrast, the very light Persian hammers do not rebound and the tremolo is controlled solely by a rapid alternating movement of the right and left wrists."*
- M3: *"During the brief contact time, the hammer strike creates a short string segment that drives the bridge at frequencies considerably higher than the string fundamental. Thus the attack characteristics and spectral frequencies are greatly influenced by the hardness of the hammer and the strike position."*
- M4: *"Contact durations decrease from about 4 ms in the bass to less than 1 ms in the highest treble."* and *"the duration of the hammer-string contact decreases as the dynamic level is raised."*

### N.4 Body / soundboard modes (santur itself: none. Proxies: dulcimer, yangqin)

| # | Label | Hz | Q / decay | Status | Src |
|---|---|---|---|---|---|
| B1 | Yangqin soundboard modes studied by impact modal analysis and shaker scanning | band 100–700 Hz (the studied range; no individual mode Hz visible) | not seen | **unsupported** | 11 |
| B2 | Yangqin construction: soundboard crowned 4 cm; 7 unequally spaced transverse ribs; 8 air chambers joined by holes of 2.8 cm diameter | — | — | **unsupported** | 11 |
| B3 | Hammered dulcimer: a (1,0) soundboard mode on 3 instruments; a brace near its nodal line accentuates it; the back plate acts as a "second soundboard" | no Hz visible | not seen | **unsupported** | 9 |
| B4 | Any measured santur body mode, or its Q | — | — | **gap** | — |

**Quotes (search summaries of the abstracts, not verbatim):**
- B1/B2: *"Vibrational modes of the soundboard in the frequency range 100–700 Hz have been studied by impact modal analysis as well as by scanning with an accelerometer as the soundboard is driven by a small shaker."* and *"The soundboard, which is crowned to a height of 4 cm in the center, is supported by seven unequally spaced transverse ribs. The ribs also divide the body into eight air chambers, which are connected by four or five holes (2.8 cm in diameter) through each rib."*
- B3: *"A (1,0)-mode of the soundboard was found in three of the instruments … the soundboard brace divided the soundboard into two similarly sized regions and accentuated the (1,0) mode by having the brace near a nodal line."* and *"the vibration coupling to the back plate through the internal bracing causes it to serve as a second soundboard."*

### N.5 Tuning (dastgah, koron/sori, range)

| # | Claim | Value | Status | Src |
|---|---|---|---|---|
| T1 | Santur range | C3 to F6 (note names only; no Hz in the source summary) | **unsupported** | 1 |
| T2 | Octave of 13 principal notes (7 diatonic plus 6 extra semitones or quarter-tones); koron = half-flat, sori = half-sharp | qualitative | **unsupported** | 1/2 |
| T3 | "Plus tone": an interval bigger than a whole tone and smaller than 3 semitones | ≈ 270 cents | **unsupported** | 1/2 |
| T4 | Small and large neutral tones between a semitone and a whole tone | no cents value visible | **unsupported** | 1/2 |
| T5 | Measured Iranian interval sizes from pitch histograms | not seen | **unsupported** | 3 |
| T6 | Hz range of a 9-bridge santur; per-dastgah retuning tables | — | **gap** | — |

**Quotes (search summaries, not verbatim):** T1: *"With a range from C3 to F6, its unique structure allows for playing all Dastgàh in various keys."* T3: *"an interval greater than a whole tone but smaller than 3 semitones (approximately 270 cents), which is called the 'plus tone'."*

Do **not** convert C3/F6 to Hz on the strength of T1. The note names are unsupported, and the reference pitch the santur is tuned to was never sourced either.

### N.6 What the literature cannot give (in this run)

- **No number in this section is confirmed.** The only directly read source, MusicBrainz, is crowd-edited, gives no numbers beyond counts, and gets the bridge count wrong for a 72-string santur.
- **Course detuning.** No source, not even as a snippet, gave a measured spread in cents between the four strings of a santur course, or a beat rate. The same holds for dulcimer and cimbalom. Whether players detune a course on purpose, or whether the spread is just tuning tolerance, is unanswered. Piano unison-detuning literature (Weinreich) is the obvious proxy, but it was not fetched and is not cited.
- **Split-course interval.** For the courses that pass over the treble bridges and sound on both sides, no source gave the ratio between the two segments, or said whether the unstruck side rings audibly. Do not assume the Western hammered-dulcimer fifth; that figure is unsourced here and may not transfer to the santur.
- **Decay and sympathetic data.** No t60 exists for any santur note, sympathetic or struck. No measurement quantifies how much energy the undamped courses pick up. The dulcimer comparison paper (src 10) and the cimbalom paper (src 14) are the most likely places to find numbers.
- **Mezrab physics.** No contact time, mass, felt stiffness or bounce statistics exist for a real mezrab. The only numeric proxy (M4, piano) comes from a much heavier, felted, key-driven hammer, and even that could not be opened in this run.
- **Body.** No santur modal study was found at all. The yangqin study (src 11) is structurally quite different: it is crowned, has 7 ribs and 8 chambers, and its individual mode frequencies were not visible.
- **Tuning.** Interval sizes in dastgah practice vary by performer and school. Even once sources are reachable, expect ranges, not single cents values. A per-dastgah santur retuning table (which bridges move for Shur, Mahur and so on) was not found.

### N.7 Sources and verification queue

| # | Citation | URL | Read directly |
|---|---|---|---|
| 1 | Heydarian, P. & Reiss, J. D. (2005). "The Persian music and the santur instrument." ISMIR 2005. | https://ismir2005.ismir.net/proceedings/2120.pdf | **No.** Egress blocked; search summary only |
| 2 | Heydarian, P. PhD thesis, London Metropolitan University (repository item 1190; listed as "An Analysis of Persian Music and the Santur instrument"). | https://repository.londonmet.ac.uk/1190/1/HeydarianPeyman%20-%20%20PhD%20Full%20Thesis.pdf | **No.** Egress blocked |
| 3 | "An analysis of Iranian Music Intervals based on Pitch Histogram." arXiv:2108.01283. | https://arxiv.org/pdf/2108.01283 | **No.** Egress blocked |
| 4 | Nikzat, B. et al. "KDC: an open corpus for computational research of dastgāhi music." ISMIR 2022. | https://archives.ismir.net/ismir2022/paper/000038.pdf | **No.** Egress blocked |
| 5 | "Santur Électroacoustique." UNT Digital Library record. | https://digital.library.unt.edu/ark:/67531/metadc1586056/ | **No.** Egress blocked; search summary only |
| 6 | "Measurement and calculation of the parameters of santur" (ResearchGate record 297510696; pitch deviation and inharmonicity). | https://www.researchgate.net/publication/297510696_Measurement_and_calculation_of_the_parameters_of_santur | **No.** Egress blocked |
| 7 | MetaBrainz Foundation. MusicBrainz instrument descriptions, `po/instrument_descriptions.pot`, entry `name:santur`. | https://raw.githubusercontent.com/metabrainz/musicbrainz-server/master/po/instrument_descriptions.pot | **Yes** (curl through the proxy; crowd-edited, low authority) |
| 8 | "Acoustics of the hammered dulcimer, its history, and recent developments." JASA 95(5) Suppl., 3002 (1994). | https://pubs.aip.org/asa/jasa/article/95/5_Supplement/3002/662645/ | **No.** Egress blocked; search summary only |
| 9 | "Modal response and sound radiation from a hammered dulcimer." Proc. Meet. Acoust. 14, 035001. DOI 10.1121/1.4865242. | https://pubs.aip.org/asa/poma/article/14/1/035001/994614/ | **No.** Egress blocked; search summary only |
| 10 | "A method for a acoustical comparison of the hammered dulcimer" (ResearchGate record 265353459). | https://www.researchgate.net/publication/265353459 | **No.** Egress blocked |
| 11 | "Acoustics of a yangqin." JASA 89(4B) Suppl., 1878 (1991). | https://pubs.aip.org/asa/jasa/article/89/4B_Supplement/1878/657941/Acoustics-of-a-yangqin | **No.** Egress blocked; search summary only |
| 12 | "The Yangqin: Acoustical Study of a Chinese Dulcimer." BioResources 21(1), 1084. | https://bioresources.cnr.ncsu.edu/wp-content/uploads/2025/12/BioRes_21_1_1084_Sinin_HJSM_Yangqin_Acoustic_Study_Chinese_Dulcimer_24951-1.pdf | **No.** Egress blocked |
| 13 | Askenfelt, A. & Jansson, E. "From touch to string vibration: String contact duration and dynamic level." KTH, *Five lectures on the acoustics of the piano*. | https://www.speech.kth.se/music/5_lectures/askenflt/stricont.html | **No.** Egress blocked this run; search summary only |
| 14 | "The Acoustical Characteristics of the Concert Cimbalom." Guild of American Luthiers. | https://luth.org/2000_0189500-pap-cymb/ | **No.** Egress blocked |
| 15 | "Tonal quality of the clavichord: the effect of sympathetic strings" (ResearchGate record 236213186). Also "Modelling of Sympathetic String Vibrations" (ResearchGate record 43207914). | https://www.researchgate.net/publication/236213186 | **No.** Egress blocked; search summary only |
| 16 | US Patent 4,706,539, "Santur." | https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/4706539 | **No.** Egress blocked |
| 17 | Songbird Dulcimers. "9 Dulcimer Hammer Styles From Around the World." | https://songbirdhd.com/9-dulcimer-hammer-styles-from-around-the-world/ | **No.** Not attempted after the blanket block; search summary only |

**Verification priority for the next run with wider egress:**
1. Src 1 and 2 (range, tuning, possibly course structure and split-course interval).
2. Src 9 and 11 (body-mode Hz and Q).
3. Src 10 and 14 (decay rates, sympathetic contribution).
4. Src 13 (the M4 contact-time proxy).
5. Src 6 (inharmonicity).

---

### N.8 Modelling implications (MY SYNTHESIS, not sourced; every number here is a knob to set by ear)

1. **Four detuned KS/waveguide loops per course.** Render each course as four loops. Draw per-string detune offsets from a small spread parameter (in cents) rather than hard-coding them, because the spread is gap S4. Unequal detune gives the two-stage decay and beating of a multi-string unison. Keep the spread a *preset* knob, and seed it per note so offline renders are deterministic.
2. **Mezrab as a raised-cosine force pulse.** The drive is `F(t) = A·½(1 − cos(2πt/τ))` for `0 ≤ t ≤ τ`, injected at a strike point β·L. Here τ stands in for hardness: a shorter τ extends the spectrum upward, and felt means a longer τ plus a gentle lowpass on the pulse. Strike position β places the comb nulls, which M3 names as a spectral driver. Leave τ unset until M4/M5 are verified (M4 is a piano proxy in any case). Model the "no rebound" of M2 as a **single** pulse by default. An optional second, weaker, delayed pulse can stand in for a sloppy double strike, and a tremolo is separate note events, not bounce.
3. **Sympathetic bank specified by t60, not Q.** Model the undamped courses as a bank of resonators (or cheap KS loops) driven by the summed bridge signal. Specify each by t60 and convert with `Q ≈ π·f·t60 / ln(1000) ≈ f·t60 / 2.2`, the inverse of the repo's `t60 ≈ 2.2·Q/f`. A single t60 is then musically constant across the bank, whereas a single Q would make low strings ring far longer than high ones. Per R2, coupling should rise sharply as a sympathetic string approaches coincidence with a partial of the struck note. A resonator bank driven by the full signal gets this behaviour for free.
4. **Seed and tune the sympathetic bank to the dastgah.** Build the bank from the *instrument's current tuning*, meaning all 18 courses and both sides of any split course, not from 12-TET. Changing dastgah retunes the bank: move the koron/sori courses by the preset's cents offsets (T2/T3 are unsupported, so offsets come from the user or preset, not from a table in code). Then only notes whose partials land on a tuned course bloom. That is the audible signature of the dastgah on an undamped santur.
5. **Body.** No sourced santur modes exist (B4). Until src 9/11 are verified, use a neutral short body filter, or none. Do not import yangqin or guitar values.


---

