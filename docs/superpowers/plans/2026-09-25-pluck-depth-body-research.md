# Body resonances for PLUCK's Phase 2 — sourced, not recalled

**Date:** 2026-09-25

## 0. Preamble

The rule for everything below: every Hz value is tied to a citation. A `confirmed` mode means the verifier opened the cited source directly and the Hz value (and, where given, the Q/bandwidth) is a verbatim match. A `corrected` mode would mean the verifier found the source says a different number than the report claimed, and the Hz below has already been fixed to what the source actually says — **no mode in this batch needed that fix**; every value the researcher wrote down was already right where the source was reachable. An `unsupported` mode means the cited source could not be opened by any policy-compliant method this run — it is listed only so nobody re-derives it from memory or a search-engine snippet and treats it as sourced. **Unsupported values are NOT to be used in code.** The koto section below is entirely unsupported for exactly this reason (its single cited paper, a paywalled/bot-gated JASA article, could not be opened).

How these tables map onto the engine: PLUCK's body is a fixed resonator bank — it does not retune with the played pitch — so every Hz value below is an absolute frequency, not a ratio or an interval to transpose. Decay time is given as `t60 ≈ 2.2·Q/f` (the standard exponential-decay-to-Q relation, T60 = ln(1000)/(π·f/Q) ≈ 2.2·Q/f) **only where a source gives an explicit Q or a 3 dB/FWHM bandwidth** — anything else in the "Q/decay" column marked "not reported" or carrying an unconverted raw figure (e.g. the harp's damping-coefficient percentages, whose ζ-vs-η convention the source itself never disambiguates) is left unconverted rather than guessed. Every mode without a source-derived decay is marked **"shape, not measurement"** in the closing tables — a starting point for the audition to tune by ear, not a number to defend in a review.

---

## 1. Classical (nylon-string) guitar

Source #0 (Christensen & Vistisen 1980) and #1 (Jansson 2002 Ch. VI) both defeated automated text extraction as raw PDF streams but were read in full with a direct file read (9 pages and 31 pages respectively), recovering every figure caption verbatim. Sources #2 (Su et al. 2024) and #3 (Woodhouse/Euphonics.org) were fetched directly. Source #4 (Woodhouse 2004) was deliberately not opened — it is cited only for context on Q-measurement difficulty, never for a mode frequency, and no check below depends on it.

### 1.1 Modes

| # | Label | Hz (range) | Relative strength | Q / decay | Status | Src |
|---|---|---|---|---|---|---|
| G1 | A0 — main air/Helmholtz resonance (coupled with the top plate; the "first resonance" f₋) | **104 Hz** (90–120 Hz across guitars generally; 92–102 Hz for "most highly appreciated instruments"; 104.7 Hz and 111.1 Hz on two specific instruments) | Dominant low-frequency radiator: the soundhole/air-piston path "gives the major contribution [to radiated sound pressure] at low frequencies," while the top plate dominates higher up. | Q₋ ≈ 29.0 (Fig. 4, same 104 Hz dataset). Separately, unstrung Q = 19.4 (104.7 Hz) and 21.8 (111.1 Hz) on two other instruments (Su et al.); strung, those fall to Q ≈ 11.0–13.6 (hand-made) and 12.9–14.8 (Ramirez) — see verifier note below, these two ranges were swapped in the original draft. | confirmed | 1 |
| G2 | Uncoupled Helmholtz cavity resonance f_h — an **antiresonance/radiation notch** between A0 and T1, not a radiating peak | **127 Hz** (122–135 Hz across guitars; 126.5 Hz on one specific instrument) | Explicitly NOT a radiator — shows up as a dip, not a peak, in top-plate mobility and (by the paper's model) in sound pressure. Do not place a positive-gain resonator here. | not reported | confirmed | 1 |
| G3 | T1 — first/fundamental top-plate mode (coupled with the Helmholtz cavity; the "second resonance" f₊) | **219 Hz** (170–250 Hz across guitars; a separate instrument's "signature mode 2" measured at 205 Hz) | Top plate "dominates in the high frequency range" relative to the air path; unusually, this is the one top-plate mode where "the vibrations are NOT small at the bridge" — it couples strongly to the string excitation point. | Q₊ ≈ 25.8 (Fig. 4, same 219 Hz dataset). | confirmed | 1 |
| G4 | T2 — second top-plate mode (antisymmetric "cross-dipole" vibration of the two halves of the lower bout) | **286 Hz** (240–300 Hz typical; 285 Hz and 286 Hz on two other specific instruments) | Explicit and load-bearing: "acts as a dipole" and its "contribution to the sound pressure is rather small" compared with the monopole-like A0/T1 pair — should sit quieter than A0/T1 despite similar mechanical amplitude. | not reported | confirmed | 1 |
| G5 | Third top-plate mode (higher free-plate resonance, above T2) | **436 Hz** (460 Hz on a different top plate, same rough position in the series) | "Motion is largely confined to the top plate" — no finer radiation ranking given. | not reported | confirmed | 4 |
| G6 | Fourth top-plate resonance (hologram-interferometry series) | **510 Hz** | General series statement only: modes above the first show large motion "outside the bridge," small motion "at the bridge" — progressively weaker coupling to the string-driven excitation point, not quantified per mode. | not reported | confirmed | 2 |
| G7 | Fifth top-plate resonance (same hologram series) | **645 Hz** | Same general statement as G6. | not reported | confirmed | 2 |
| G8 | Higher air-cavity resonance (above A0) | **370 Hz** (part of a series at 370, 540, 760, 980, 1000 Hz — only the lowest used here) | "These air resonances can play an important role and co-operate with plate modes" — no quantitative strength given. | not reported | confirmed | 2 |

**Quotes (verbatim):**
- G1 — "The two lowest resonance frequencies of the guitar occur at approximately 100 and 200 Hz. These resonances are found in all guitars and may vary in frequency in the typical ranges 90-120 Hz for the first resonance and 170-250 Hz for the second resonance. ... The first resonance is found only in complete instruments, i.e., when a cavity enclosure is added to the top plate. It is therefore related to the Helmholtz resonance of the guitar cavity." Fig. 4 caption: "f₋=104 Hz, f_h=127 Hz, f₊=219 Hz, m_p=112 g, Q₋=29.0, and Q₊=25.8." Su et al.: "Hand-made guitar: 104.7 Hz with FWHM of 5.4 Hz; quality factor Q = 19.4. José Ramirez guitar: 111.1 Hz with FWHM of 5.1 Hz; quality factor Q = 21.8," with strung Q "lower (13.6-14.8 for hand-made; 11.0-12.9 for Ramirez)" [this pairing is the transposition the verifier flagged — see note below].
- G2 — "The Helmholtz resonance is found as the antiresonance in the spectrum of top plate mobility. ... an antiresonance between the first and second resonance at 126.5 Hz. ... The Helmholtz frequency for the present guitar is 126.5 Hz. For other guitars we found values in the range 122-135 Hz."
- G3 — "...170-250 Hz for the second resonance. Hologram interferometric studies of the guitar top plate have shown that the second resonance corresponds in frequency to the lowest (fundamental) mode of the top plate." Fig. 4: "f₊=219 Hz ... Q₊=25.8." Jansson: "except for the first top plate resonance, the vibrations tend to be small at the bridge and large outside the bridge."
- G4 — "The second top plate mode occurs usually at 240-300 Hz and consists of an antisymmetrical vibration of the two halves of the top plate in the lower bout of the guitar. ... a contribution to the sound pressure from the second top plate mode is rather small since this resonance acts as a dipole." Corroborating: Jansson hologram series mode "b) 285 Hz"; Richardson/Euphonics "signature mode 3: 286 Hz."
- G5 — "Mode 4: 436 Hz ... In all four modes shown here, motion is largely confined to the top plate." Corroborating (different instrument): Jansson Fig. 6.9, mode "c) 460 Hz."
- G6 — Jansson Fig. 6.9 caption: "Resonant frequencies at a) 185 Hz, b) 285 Hz, c) 460 Hz, and d) 510 Hz, and e) 645 Hz. (guitar top made by G. Bolin, interferograms by Molin and Stetson.)"
- G7 — same Fig. 6.9 caption as G6: "...and d) 510 Hz, and e) 645 Hz."
- G8 — Jansson Fig. 6.12 caption: "Air resonances at 370, 540, 760, 980 and 1000 Hz - positions of pressure antinodes (thick lines), pressure nodes (dashed lines)..." Body text: "An air volume like the one of the guitar has many resonances too... These air resonances can play an important role and co-operate with plate modes."

**Verifier note on this table (narrative defects, not Hz errors):** all 8 Hz figures were confirmed as exact, verbatim matches to the cited sources — nothing here is "corrected." But the verifier found three splices in the surrounding narrative that a reviewer should not repeat: (1) Su et al.'s strung-Q ranges above were transposed between the two guitars in the original draft — the correct pairing is hand-made guitar strung Q = 11.0–13.6, José Ramirez strung Q = 12.9–14.8 (both are corrected in the table above); (2) 126.5 Hz and 127 Hz are not one Helmholtz frequency "refined" by one process — they come from two different measurement sessions/guitars in the paper (Fig. 1's guitar vs. Fig. 4's guitar), which differ by 21 Hz in f₊ (198 vs 219 Hz), so they should not be read as a before/after on the same instrument; (3) the 173 Hz mass-loaded f₊ value belongs to the Fig. 1 guitar (198→173 Hz under load), not to the 219 Hz Fig. 4 guitar — no sentence in the source connects 173 Hz to 219 Hz.

### 1.2 What the literature cannot give

No sourced Q, bandwidth, or decay figure exists above T1 in what was read this run — G4 through G8 have a measured Hz but no source-reported decay. The task's own framing named a "T2 back-coupled" mode arising from back-plate coupling; this is a genuine, named gap, since Christensen & Vistisen explicitly assume a rigid back ("The bottom of the guitar is in this analysis assumed to be rigid") and Jansson states only that the back plate "can supply prominent resonances" without giving a Hz value — a later 3-DOF extension by Christensen that adds the back plate was found but its numbers were never fetched, so nothing from it is reported. Fletcher & Rossing's "Physics of Musical Instruments" could not be verified directly (two PDF fetch attempts both failed) and is therefore cited nowhere above. Instrument-to-instrument variation is large and only partly quantified — Christensen & Vistisen's own multi-guitar ranges already span roughly ±20%, and Su et al.'s two instruments differ in A0 frequency and Q even before strings are added. Measured Q is not a fixed instrument property: Su et al. show A0's Q collapsing from ~20–22 unstrung to ~11–15 once strings load the resonance, so a "bare body" Q overstates what a played instrument shows. Above roughly 700 Hz the response is described as densely packed and idiosyncratic per instrument, not a short generalizable list — a small fixed resonator bank realistically only models the lower part of the spectrum this catalog covers.

### 1.3 Sources

| # | Citation | URL | Read directly |
|---|---|---|---|
| 1 | Christensen, O., & Vistisen, B. B. (1980). "Simple model for low-frequency guitar function." JASA 68(3), 758–766. | https://backend.orbit.dtu.dk/ws/files/3580072/Vitsten.pdf | Yes |
| 2 | Jansson, E. (2002). *Acoustics for Violin and Guitar Makers*, 4th ed., Ch. VI. KTH. | https://www.speech.kth.se/music/acviguit4/part6.pdf | Yes |
| 3 | Su, K.-C. et al. (2024). "A Three-Dimensional Method for Analysis of the Body Mode of Classical Guitars..." *Sensors* 24(16), 5147. | https://pmc.ncbi.nlm.nih.gov/articles/PMC11360423/ | Yes |
| 4 | Woodhouse, J. — Euphonics.org, §5.3 "Signature modes and formants" (guitar figure/measurements by Bernard Richardson). | https://euphonics.org/5-3-signature-modes-and-formants/ | Yes |
| 5 | Woodhouse, J. (2004). "Plucked Guitar Transients..." *Acta Acustica*, 90, 945–965. (context only — string-overtone Q, not body-mode Q; not used for any Hz above.) | https://euphonics.org/wp-content/uploads/2022/03/Guitar_II.pdf | Yes |

---

## 2. Koto (Japanese 13-string zither, paulownia soundbox)

**This instrument has zero confirmed or corrected modes.** All seven catalogued modes are **unsupported**: the single source cited for every one of them — Coaldrake (2020), *JASA* 148(5), 3153–3170 — could not be opened by any policy-compliant method this run (WebFetch and curl both returned 403; a real Playwright browser session hit AIP's Cloudflare "Just a moment..." bot-check; the CC-BY open-access mirror at asa.scitation.org returned 429 twice; no repository mirror exists per Unpaywall/OpenAlex metadata). Per the rule in the preamble, an unopenable cited source means unsupported regardless of plausibility, so **none of the Hz values below may be used in code.** They are recorded here only so nobody re-derives them from a search snippet and mistakes them for sourced numbers.

### 2.1 Modes

| # | Label | Hz (range) | Relative strength | Q / decay | Status | Src |
|---|---|---|---|---|---|---|
| K1 | Unexplained low-frequency response (~50 Hz cluster) — not a confirmed body/air mode even in the original report | 50 Hz (33–56 Hz depending on technique) | Observed across every physical technique used, but the model could not reproduce it as an eigenmode or an air mode; physical origin unresolved. | not reported | **unsupported** | 1 |
| K2 | Air cavity resonance, Helmholtz-type (0,0) | 85 Hz (83–88 Hz across techniques) | One of two peaks said to dominate the whole spectrum; confirmed air-mediated by vanishing in helium. | not reported | **unsupported** | 1 |
| K3 | Top-plate/body eigenmode, wood-mediated (0,2), dominant x-direction | 100 Hz (99–102 Hz) | The single dominant transducer-sweep feature; unchanged in helium (wood-mediated). | not reported | **unsupported** | 1 |
| K4 | Second air mode | 157 Hz | Identified only in simulation, paired with the 85 Hz air mode via a speed-of-sound sweep. | not reported | **unsupported** | 1 |
| K5 | Second torsional mode (1,2) | 184 Hz (183–195 Hz) | Torsional (twisting) node pattern, distinct from the longitudinal modes. | not reported | **unsupported** | 1 |
| K6 | Second longitudinal/top-plate eigenfrequency (0,3) | 202 Hz (169–216 Hz) | "Standard radiation pattern" for the mode type; harder to resolve with the laser vibrometer due to the curved top plate. | not reported | **unsupported** | 1 |
| K7 | Third torsional mode (1,3) | 352 Hz (352–366 Hz) | No further strength description given. | not reported | **unsupported** | 1 |

**Quotes as originally reported (unverified against the source itself — see 2.2):**
- K1 — "a frequency response around 50 Hz was clearly observed in all physical experiments... it did not appear in the results of the study with helium or change of the speed of sound either as an eigenmode or an air mode. One possible answer is that 50 Hz is a subharmonic beat, but the origin of the response remains ambiguous."
- K2 — "the 85 Hz peak disappeared while the 100 Hz remained unchanged [in helium] ... The conclusion is that 100 and 183 Hz are eigenmodes, while 85 and 157 Hz are air modes."
- K3 — "It was concluded that 100 Hz was the first eigenmode in the dominant x-direction and 85 Hz was confirmed as an air mode."
- K4 — "the frequency response was curved for 85 and 157 Hz with unusual mode shapes, confirming them to be air-mediated modes, while the 100 Hz again remained unchanged."
- K5 — "A second torsion mode (1,2) was predicted at 184 Hz in the CT model and was observed between 190 and 195 Hz in experimental results."
- K6 — "A second eigenfrequency (0,3) was predicted at 202 Hz in the CT model with 200 Hz observed in the Chladni patterns and 216 Hz in the acoustic camera."
- K7 — "A third torsion mode (1,3) is predicted at 352 Hz in the CT model. It was observed at 359 Hz in the LSV with 366 Hz in the acoustic camera."

**Why unsupported despite partial corroboration:** two independent, reachable sources partially corroborate the *broad* findings without confirming any specific number above. The PubMed/EuropePMC abstract for this paper (PMID 33261375) says "...identifying 100 Hz as the first eigenmode and 85 Hz as a major air mode." Coaldrake's 2019 ICA conference paper (fetched and read in full) says "Chladni patterns showing clearly that the (0,1) mode was at 100Hz and the (0,0) mode at 85Hz which the acoustic camera confirmed" — but labels 100 Hz as mode (0,1), not the (0,2) claimed above, an unresolved discrepancy. No reachable source mentions K4, K5, K6, or K7's specific Hz values at all (the closest, unrelated coincidences in the 2019 ICA paper's superseded axis-diagnostic chart, are noted but not treated as confirmation).

### 2.2 What the literature cannot give

No Q, bandwidth, or decay/T60 value is reported for any individual koto resonance in either paper read. The 2020 paper (per its own PubMed abstract and the 2019 companion paper) says damping data for paulownia wood is unreliable, so the modeler hand-picked a single whole-body Rayleigh damping coefficient (β = 0.1/s) to loosely match a recording — that is a global fudge factor, not a per-mode measurement, and must not be reused as a modal Q for any mode above. Every number above comes from one hand-crafted, ~30-year-old koto belonging to the paper's author — no cross-instrument spread is reported, and the author's own future work explicitly says a second instrument for comparison doesn't exist yet. A persistent very-low-frequency peak cluster (roughly 12–56 Hz) recurs across measurements but is explicitly unexplained by the authors (possibly internal struts, possibly string artifacts, possibly below the equipment's calibrated range) — K1 is the clearest instance, reported with that ambiguity intact. Ando's original 1986/1996 Chladni-pattern work — the only other koto acoustic dataset in existence — could not be retrieved this run (a 2-page conference note and an untranslated Japanese book); no Hz figure is attributed to Ando anywhere above even though he is the founding source for koto body-resonance study. Above roughly 400–410 Hz, top-plate mode shapes are described as "not simple" because the wood is not straight-grained, so a simple fixed-resonator idealization fits progressively worse there — part of why the catalog stops at 352 Hz.

### 2.3 Sources

| # | Citation | URL | Read directly |
|---|---|---|---|
| 1 | Coaldrake, A. K. (2020). "A finite element model of the Japanese koto constructed from computed tomography scans." *JASA* 148(5), 3153–3170. DOI 10.1121/10.0002427. | https://pubs.aip.org/asa/jasa/article/148/5/3153/631851/A-finite-element-model-of-the-Japanese-koto | **No — unreachable (403 / Cloudflare / 429 across every mirror)** |
| 2 | Coaldrake, K. "Measurement and modelling of the Japanese koto: Problems and solutions." ICA 2019, Aachen, pp. 7549–7556. | https://pub.dega-akustik.de/ICA2019/data/articles/001444.pdf | Yes |
| 3 | Coaldrake, K. "Finite element modelling of Japanese koto strings." ISMA 2019, Detmold, pp. 385–392. | https://pub.dega-akustik.de/ISMA2019/data/articles/000063.pdf | Yes (no body-mode values in it) |
| 4 | Ando, Y. (1986). "Acoustics of sohs ('koto's)." ICA 1986, Toronto, Vol. 3, pp. K1 5-6. | (none) | No |
| 5 | Ando, Y. (1996). *Acoustics of Musical Instruments*, 2nd ed. (Ongaku no Tomo Sha) [Japanese]. | (none) | No |
| 6 | Fletcher, N. & Rossing, T. *The Physics of Musical Instruments*, 2nd ed. (Springer, 2010). | (none) | No |
| 7 | Obata, J. & Sugita, E. (1931). "Acoustical investigation of some Japanese musical instruments." *Proc. Physico-Math. Soc. Japan* 13(5), 133–150. | (none) | No |
| 8 | PubMed record for Coaldrake (2020), PMID 33261375 (fetched via NCBI E-utilities). | https://pubmed.ncbi.nlm.nih.gov/33261375/ | Yes (via API, not the HTML page) |

---

## 3. Concert (pedal) harp

All six modes come from one instrument: a Camac "Atlantide Prestige" concert harp (a 6-mm semi-conical shell soundbox, 0.029 m³ enclosed air volume, five elliptical sound holes), measured strung and tuned in Le Carrou, Gautier & Foltête (2007). The paper states directly: "This result is valid for the studied harp." Source #0 was fetched and its PDF text extracted directly (Fig. 4 and Table II both recovered); source #1 (the 2010 companion paper) was fetched and cross-checked; source #2 (a 34-harp mobility survey) was read but reports no per-mode Hz values, so it neither confirms nor contradicts anything here.

### 3.1 Modes

| # | Label | Hz (alt. method) | Relative strength | Q / decay | Status | Src |
|---|---|---|---|---|---|---|
| H1 | Mode 1 — global soundbox motion (rigid-body-like, no internal nodes) | **54.8 Hz** (61.5 Hz, single-point FRF peak) | Global body motion tied to the arm/pillar connection, not a strong radiator. | Damping coefficient 5.5% (convention — damping ratio ζ vs. loss factor η — not stated by the source; **not converted** to Q/t60). | confirmed | 1 |
| H2 | Mode 2 — soundbox first bending mode (beam-like, axial) | **80.9 Hz** (84.5 Hz alt.) | "Modes 2 and 3... do not induce a change in the volume of the cavity: a weak coupling... with the fluid inside the cavity can be expected." | Damping coefficient 4.8% (convention unstated; not converted). | confirmed | 1 |
| H3 | Mode 3 — soundbox second bending mode (beam-like, axial) | **123.4 Hz** (124.5 Hz alt.) | Same weak-coupling family as Mode 2. | Damping coefficient 2.5% (convention unstated; not converted). | confirmed | 1 |
| H4 | Mode 4 — **T1**, soundboard first bending/plate mode, coupled to soundbox air (lower partner of the yielding-wall Helmholtz pair) | **152.2 Hz** (153.5 Hz alt.; 148 Hz in the 2010 companion paper, same harp) | One of the two dominant radiators: "the first two peaks of the pressure amplitude correspond to the resonance frequencies of T1 and A0." | Damping coefficient 2.3% (convention unstated; not converted). | confirmed | 1 |
| H5 | Mode 5 — pitch mode (soundboard motion on its own nodal line) | **161.9 Hz** | **Explicitly negligible in play** — the source itself excludes it: "the role of this mode is not important when the instrument is played. For this reason, it will not be considered afterwards." | Damping coefficient 0.9% (convention unstated; not converted). | confirmed (but see strength note) | 1 |
| H6 | Mode 6 — **A0**, soundbox air/Helmholtz-like mode coupled to soundboard (upper partner) | **168.5 Hz** (172 Hz alt.; 166 Hz in the 2010 companion paper, same harp) | The other dominant radiator; vanishes when the sound holes are stoppered, confirming its air-piston character. Unusually, "the A0 mode is above rather than below the frequency of the lowest acoustically significant structural mode T1" — the reverse of guitar/violin. | Damping coefficient 1.4% (convention unstated; not converted). | confirmed | 1 |

**Quotes (verbatim):**
- H1 — "Mode 1 has no nodes on its mode shape: the modal displacement is close to a global motion of the body depending on its connections to the arm and to the bottom of the pillar." Fig. 4: "Mode 1 (54.8 Hz - 5.5%)."
- H2 — "Modes 2 and 3 have common characteristics: The axial profiles of soundbox's displacements are similar to the first two mode shapes of a simply supported free beam. ... the shapes of modes 2 and 3 do not induce a change in the volume of the cavity: a weak coupling of these modes with the fluid inside the cavity can be expected." Fig. 4: "Mode 2 (80.9 Hz - 4.8%)."
- H3 — same paragraph as H2. Fig. 4: "Mode 3 (123.4 Hz - 2.5%)."
- H4 — "Modes 4 and 6 have very similar mode shapes... modes 4 and 6 involve a coupling between the bending motion of the soundboard mode and the oscillation of the air piston. These two modes can respectively be labeled, with the common notation, T1 and A0." Fig. 4: "Mode 4 (152.2 Hz - 2.3%)."
- H5 — "Mode 5 is a pitch mode. In the measured response functions, this mode is not clearly present. It is actually not well excited since the shaker is connected close to the central line of the soundboard, which exactly corresponds to its nodal line. Since the strings are also attached on this nodal line, the role of this mode is not important when the instrument is played. For this reason, it will not be considered afterwards." Fig. 4: "Mode 5 (161.9 Hz - 0.9%)."
- H6 — "on one hand, when sound holes are closed, mode 6 disappears... Contrary to the violin and to the guitar, the A0 mode is above rather than below the frequency of the lowest acoustically significant structural mode T1." Fig. 4: "Mode 6 (168.5 Hz - 1.4%)."

### 3.2 What the literature cannot give

No source gives a true Q factor or a decay time in seconds for any of these six modes. The only damping figure available — Fig. 4's "damping coefficient" (%) — is never defined as a damping ratio (ζ) versus a loss factor (η), a distinction that would change the implied Q by roughly 2×; that ambiguity is left unresolved here rather than silently converted, which is why every row above is marked "not converted" rather than given a computed t60. All six modes come from a single instrument; the authors state plainly that A0/T1 eigenfrequencies depend on "cavity volume, sound holes sizes, and soundboard material" and report no cross-instrument spread. Even within this one paper, method matters: the full modal fit (Fig. 4) and a simpler single-point FRF peak-read (Table II) disagree by several Hz for the same six physical modes on the same harp. Nothing is reported below 54.8 Hz, even though the identification window opened at 24 Hz and the instrument's strings run well below that — this looks like evidence of absence, not absence of evidence. Radiated strength is only rank-ordered in prose, never given in comparable absolute units, so H1/H2/H3/H5's relative gains against T1/A0 cannot be set with real rigor. Waltham & Kotlicki (2008) was paywalled and contributed no numeric mode data. Bell (1987, 1997) and Firth (1989) are known only second-hand via citation inside the 2007 paper — not independently retrieved — so nothing from them appears in the numeric table above. Nothing above ~180 Hz is characterized as an individual mode anywhere found — the authors state "mode identification at higher frequencies was not possible" due to rising modal density.

### 3.3 Sources

| # | Citation | URL | Read directly |
|---|---|---|---|
| 1 | Le Carrou, J.-L., Gautier, F., & Foltête, E. (2007). "Experimental study of A0 and T1 modes of the concert harp." *JASA* 121(1), 559–567. | https://www.lam.jussieu.fr/Membres/LeCarrou/Articles/A2_LeCarrou_IdentificationA0T1modes.pdf | Yes |
| 2 | Le Carrou, J.-L., Leclère, Q., & Gautier, F. (2010). "Some characteristics of the concert harp's acoustic radiation." *JASA* 127(5), 3203–3211. | https://www.lam.jussieu.fr/Membres/LeCarrou/Articles/A4_LeCarrou_HarpAcousticRadiation.pdf | Yes |
| 3 | Le Carrou, J.-L. et al. (2010). "Vibratory study of harp's soundboxes." ISMA 2010, Sydney/Katoomba. | https://www.acoustics.asn.au/conference_proceedings/ICA2010/cdrom-ISMA2010/papers/p25.pdf | Yes (no per-mode Hz values in it) |
| 4 | Firth, I. M. (1989). "Harps of the baroque period." *J. Catgut Acoust. Soc.* 1(3), 52–61. [known only via citation inside #1] | (none) | No |
| 5 | Bell, A. J. (1987). "An acoustical investigation of the Concert Harp." PhD dissertation, Univ. of St Andrews. [known only via citation inside #1] | (none) | No |
| 6 | Bell, A. J. (1997). "The Helmholtz resonance and higher air modes of the harp soundbox." *J. Catgut Acoust. Soc.* 3(2), 2–8. [known only via citation inside #1] | (none) | No |
| 7 | Waltham, C., & Kotlicki, A. (2008). "Vibrational characteristics of harp soundboards." *JASA* 124(3), 1774–1780. | https://pubs.aip.org/asa/jasa/article-abstract/124/3/1774/676197 | No (403, paywalled) |
| 8 | Waltham, C. "What Makes a Good Harp?" Lay-language paper, 157th ASA. | https://acoustics.org/pressroom/httpdocs/157th/waltham.html | Yes (no Hz values of its own) |
| 9 | Waltham, C. "Harp Design and Construction." Lay-language paper, 149th ASA. | https://acoustics.org/pressroom/httpdocs/149th/waltham.html | Yes (no soundboard mode values) |

---

## 4. 5-string banjo (Mylar head over a wooden pot, with or without resonator)

Sources #0 (Rae, in Rossing's *The Science of String Instruments*), #1 (Politzer, "Banjo Rim Height and Sound in the Pot"), and #2 (Politzer/Woodhouse/Mansour, "Pickers' Guide to Acoustics of the Banjo") were all downloaded as raw PDF bytes and run through direct text extraction after WebFetch's own summarizer failed on all three (a 10 MB cap on the 466-page Rossing book; "binary/encoded" on both arXiv PDFs despite saving the bytes). Every quoted passage and figure caption below was located verbatim this way. The nine modes cite only these three sources; #3–#9 were not attempted since nothing above depends on them.

### 4.1 Modes

| # | Label | Hz | Relative strength | Q / decay | Status | Src |
|---|---|---|---|---|---|---|
| B1 | Pot-air (Helmholtz-like) cavity resonance, coupled with the head fundamental | **220 Hz** (190–230 Hz bare/uncoupled; splits into a coupled doublet at 209/254 Hz) | Isolable as one peak only when everything else is damped. Coupled, it splits into two normal modes trading amplitude with resonator height; fitting a resonator lengthens nearby-harmonic decay "by as much as 20%." | not reported (the 20% figure is a transient-duration change, not a t60) | confirmed | 1 |
| B2 | Head (0,1) fundamental membrane mode | **234 Hz** (≈208–254 Hz depending on tension/resonator condition) | Sits above the open 3rd/4th-string fundamentals (196/147 Hz), which the source says are therefore "poorly represented" in the output. | **Bandwidth 20–30 Hz** at "bluegrass tightness," narrowing to "as little as a few Hz" at very high tension. | confirmed | 1 |
| B3 | Head (1,1) membrane mode | **509 Hz** | Falls inside the 400–1200 Hz band said to carry 85–90% of radiated power. | not reported | confirmed | 1 |
| B4 | Head (2,1) membrane mode | **803 Hz** | Near the top edge of that same high-radiation-efficiency band. | not reported | confirmed | 1 |
| B5 | Lowest closed-cylinder pot-air mode (rim-height independent) | **850 Hz** (calculated 808 Hz for a 9.85 in pot; measured "close to that value"; elsewhere called "the 900 Hz resonance") | Side-to-side (r–θ) motion, not a net air-piston pump; common to all three rim heights tested because it depends on pot diameter, not depth. | not reported | confirmed | 2 |
| B6 | Head (5,1) membrane mode | **1,593 Hz** | Past the upper edge of the main formant; radiation efficiency falling. | not reported | confirmed | 1 |
| B7 | Head (7,1) membrane mode — last visually "strong" head mode | **2,055 Hz** | "The last strong mode occurred at about 2,000 Hz... Very weak modes can be seen above 2,000 Hz, but usually cannot be seen well enough to be given a drum head mode name." | not reported | confirmed | 1 |
| B8 | Bridge-flexing formant near 3.5 kHz ("bridge hill") | **3,500 Hz** (specific to one Deering Eagle II bridge) | Enhanced by tapping sideways on the bridge edge; requires bridge flex coupled to head motion, not the bridge resonating alone. | not reported | confirmed | 3 |
| B9 | Bridge-flexing formant near 5 kHz | **5,000 Hz** (same bridge) | Enhanced by tapping downward at the 3rd-string slot; a different flex pattern from B8. | not reported | confirmed | 3 |

**Quotes (verbatim):**
- B1 — "These measurements show that the cavity resonance can be tuned to be in the range 190–230 Hz while maintaining a reasonable-quality banjo sound. ... the (0,1) head mode of a banjo that has its head tuned to G# and a resonator attached occurs at about 209 Hz. If you remove the resonator, the apparent (0,1) mode is at about 254 Hz. ... the duration of sound transients is increased by as much as 20% when the resonator is present."
- B2 — Fig. 5.6: "Selected banjo head modes, (0,1) = 234 Hz..." Body text: "The first mode (0,1) occurs at around 208 Hz or so ... It is generally a wide mode encompassing a frequency range of 20–30 Hz."
- B3/B4/B6/B7 — Fig. 5.6: "(0,1) = 234 Hz, (1,1) = 509 Hz (2,1) = 803 Hz, (5,1) = 1,593 Hz, (7,1) = 2,055 Hz." B7 also: "The last 'strong' mode occurred at about 2,000 Hz."
- B5 — "The lowest calculated resonance is at 808 Hz, and, indeed, all three pots show a fine peak very close to that value. ... a strong resonance which is nearly the same for all pots. That's the one between 800 and 900 Hz. ... alternating back and forth at ~900 Hz."
- B8 — "Careful observation of the Deering Eagle II revealed two additional formants at higher frequencies, i.e., centered around 3.5 kHz and 5 kHz. ... Tapping sideways on the edge of the bridge enhanced the 3.5 kHz [formant]." Fig. 3: "FE/BE calculation of resonant bridge/head motion near 3500 Hz."
- B9 — "Tapping downward at the 3rd string slot enhanced the 5 kHz formant." Fig. 4: "FE/BE calculation of resonant bridge/head motion near 5000 Hz."

### 4.2 What the literature cannot give

No source gives a measured Q or decay time for any individual named body mode. What exists is qualitative: the (0,1) mode's steady-state bandwidth (B2, 20–30 Hz at bluegrass tightness), a general remark that "higher frequency motions lose a larger fraction of their energy to heat than lower ones," and Woodhouse's Euphonics page stating body-mode "Q-factors around 100 or lower" as a general characterization not tied to any specific mode above. The two peer-reviewed Acta Acustica papers (Woodhouse/Politzer/Mansour 2021) plausibly hold the real per-mode admittance/damping tables, but the journal site's bot-protection blocked every fetch attempt, so nothing numeric from them appears here; Dickey (2003) and Stephey & Moore (2008) were likewise paywalled. Instrument-to-instrument variation is large and explicit: the pot/Helmholtz-like resonance alone runs from ~150 Hz to over 900 Hz depending on rim depth and resonator presence; bridge wood alone shifts the bridge's own resonance from 700–800 Hz (soft wood) to 3500 Hz (dense wood), and B8/B9 are explicitly tied to one specific bridge, with the source stating "analogous effects occur at different frequencies with different bridge designs." A fixed resonator bank tuned from this one paper's numbers will not generalize across real instruments without re-measurement.

### 4.3 Sources

| # | Citation | URL | Read directly |
|---|---|---|---|
| 1 | Rae, J. "Banjo" (Ch. 5), in T. D. Rossing (ed.), *The Science of String Instruments*, Springer, 2010, pp. 59–75. | https://logosfoundation.org/kursus/The%20Science%20of%20String%20Instruments.pdf | Yes |
| 2 | Politzer, D. "Banjo Rim Height and Sound in the Pot." HDP:16-03 (2016); arXiv:1608.04620. | http://www.its.caltech.edu/~politzer/rim-height.pdf | Yes |
| 3 | Politzer, D., Woodhouse, J., & Mansour, H. "Pickers' Guide to Acoustics of the Banjo, parts I and II." arXiv:2104.02480 (2021). | https://arxiv.org/pdf/2104.02480 | Yes |
| 4 | Politzer, D. "Banjo Drum Physics." HDP:18-02 (2018); arXiv:1806.08857. | https://arxiv.org/pdf/1806.08857 | Yes (not cited by any mode above) |
| 5 | Woodhouse, J. "An extreme case: the banjo." Euphonics.org. | https://euphonics.org/5-4-an-extreme-case-the-banjo/ | Yes (not cited by any mode above) |
| 6 | Woodhouse, J., Politzer, D., & Mansour, H. "Acoustics of the banjo: measurements and sound synthesis." *Acta Acustica* 5, 15 (2021). | https://acta-acustica.edpsciences.org/articles/aacus/full_html/2021/01/aacus200055/aacus200055.html | No (bot-gated) |
| 7 | Woodhouse, J., Politzer, D., & Mansour, H. "Acoustics of the banjo: theoretical and numerical modelling." *Acta Acustica* 5, 16 (2021). | https://acta-acustica.edpsciences.org/articles/aacus/full_html/2021/01/aacus200052/aacus200052.html | No (bot-gated) |
| 8 | Rae, J. & Rossing, T. D. "The Acoustics of the Banjo." ISMA 2004, Nara. | (none) | No |
| 9 | Dickey, J. "The structural dynamics of the American five-string banjo." *JASA* 114(5), 2958–2966 (2003). | https://pubs.aip.org/asa/jasa/article-abstract/114/5/2958/547753 | No (paywalled) |
| 10 | Stephey, L. A. & Moore, T. R. "Experimental investigation of an American five-string banjo." *JASA* 124(5), 3276–3283 (2008). | https://pubs.aip.org/asa/jasa/article-abstract/124/5/3276/910926 | No (paywalled) |

---

## 5. Working tables for the plan

Only confirmed/corrected modes appear below. Every gain and t60 is a **proposal for the audition to tune**, not a re-derivation from a source beyond what's noted. A row is marked **measured** only where the t60 comes from a source-given Q or bandwidth via `t60 = 2.2·Q/f`; every other row is marked **shape** — chosen to be in a plausible ballpark and expected to move at the gate.

### 5.1 Classical guitar (8/8 modes confirmed)

| Label | Hz | Gain (0–1) | t60 (s) | Basis |
|---|---|---|---|---|
| A0 | 104 | 1.00 | **0.61** | measured — Q₋=29.0 → 2.2×29.0/104 |
| Helmholtz f_h *(antiresonance — do NOT implement as a positive-gain resonator; row kept only for completeness)* | 127 | 0.00 | n/a | excluded per source (antiresonance, not a radiating peak) |
| T1 | 219 | 0.90 | **0.26** | measured — Q₊=25.8 → 2.2×25.8/219 |
| T2 | 286 | 0.35 | 0.15 | shape |
| Top-plate mode 3 | 436 | 0.25 | 0.12 | shape |
| Top-plate mode 4 | 510 | 0.15 | 0.10 | shape |
| Top-plate mode 5 | 645 | 0.10 | 0.08 | shape |
| Higher air-cavity mode | 370 | 0.30 | 0.15 | shape |

### 5.2 Koto — no table, except two modes confirmed via source #2

**Zero confirmed or corrected modes from source #1** (the 2020 JASA paper). All seven Hz values catalogued against it in §2.1 are unsupported and must not be copied into any resonator table. Treat koto as blocked on a Phase 2 re-verification pass (an authenticated or institutional route to that paper, or a direct request to the author) before any further number in §2 is usable in code.

Two of those seven do, however, also appear directly in source #2 (Coaldrake, ICA 2019, read in full — see §2's "Why unsupported despite partial corroboration"), which is reachable and was opened: "the (0,1) mode was at 100Hz and the (0,0) mode at 85Hz which the acoustic camera confirmed." That gives a two-row working table:

**Confirmed via source #2 (ICA 2019), decays shape — the rest of section 2 stays unsupported**

| Label | Hz | Gain (0–1) | t60 (s) | Basis |
|---|---|---|---|---|
| Air mode (0,0) | 85 | 0.80 | 0.40 | shape — no Q or bandwidth reported |
| First top-plate eigenmode | 100 | 1.00 | 0.50 | shape — no Q or bandwidth reported |

The mode-numbering discrepancy noted in §2 (source #1's abstract calls the plate mode (0,2); source #2's own text calls it (0,1)) does not affect either Hz value — both sources agree on 85 and 100 Hz — so it does not block using them here.

### 5.3 Concert harp (6/6 modes confirmed)

| Label | Hz | Gain (0–1) | t60 (s) | Basis |
|---|---|---|---|---|
| Mode 1 (global soundbox) | 54.8 | 0.20 | 0.30 | shape — the source's damping % read as ζ; the η reading doubles it |
| Mode 2 (first bending) | 80.9 | 0.15 | 0.35 | shape — the source's damping % read as ζ; the η reading doubles it |
| Mode 3 (second bending) | 123.4 | 0.15 | 0.36 | shape — the source's damping % read as ζ; the η reading doubles it |
| T1 (Mode 4) | 152.2 | 0.95 | 0.31 | shape — the source's damping % read as ζ; the η reading doubles it |
| Pitch mode (Mode 5) *(source excludes it from playing relevance — row kept only for completeness; do not give it meaningful gain)* | 161.9 | 0.00 | n/a | excluded per source (not well excited in play) |
| A0 (Mode 6) | 168.5 | 1.00 | 0.47 | shape — the source's damping % read as ζ; the η reading doubles it |

Note: the source's damping-coefficient percentages (5.5/4.8/2.5/2.3/0.9/1.4%, in the same mode order) are read above as a damping ratio ζ, via t60 = 2.2·Q/f with Q = 1/(2ζ) — the source never states whether they are ζ or a loss factor η, a convention that would double every t60 above (η = 2ζ). That reading is this document's own choice, not the source's, which is why the basis column still says "shape" rather than "measured": the % figures themselves come from the source's own modal fit, but the ζ-vs-η convention does not, and the audition may need to retune by ear if the real convention turns out to be η.

### 5.4 Banjo (9/9 modes confirmed)

| Label | Hz | Gain (0–1) | t60 (s) | Basis |
|---|---|---|---|---|
| Pot-air (Helmholtz doublet, coupled) | 220 | 0.50 | 0.35 | shape |
| Head (0,1) | 234 | 0.60 | **0.09** | measured — bandwidth 20–30 Hz → Q≈7.8–11.7 → 2.2×Q/234 (range 0.07–0.11 s, midpoint used) |
| Head (1,1) | 509 | 0.90 | 0.15 | shape |
| Head (2,1) | 803 | 0.90 | 0.12 | shape |
| Pot-air cylinder mode | 850 | 0.70 | 0.10 | shape |
| Head (5,1) | 1,593 | 0.40 | 0.08 | shape |
| Head (7,1) | 2,055 | 0.30 | 0.06 | shape |
| Bridge hill ~3.5 kHz | 3,500 | 0.30 | 0.05 | shape |
| Bridge hill ~5 kHz | 5,000 | 0.25 | 0.04 | shape |
