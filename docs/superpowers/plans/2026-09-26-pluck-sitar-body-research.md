# Body resonances for PLUCK's sitar — sourced, not recalled

**Date:** 2026-09-26

## 0. Preamble

The rule for everything below, in my own words: every Hz value in a mode table has to be tied to a source I actually opened — either WebFetch rendering real text, or a PDF I fetched (or pulled down over plain HTTP when WebFetch's own summarizer choked on the binary stream) and then read directly with the file-reading tool, page by page, until I could see the sentence or caption with the number in it. A source I could only see as an abstract, a search-engine snippet, a paywalled landing page, or a training-data recollection does not count, no matter how plausible the number sounds, and does not go in the mode table or in a quote block presented as sourced. Where I hit exactly that wall, the candidate is listed in the "Not opened" table instead, with the specific access attempts and their outcomes, so nobody mistakes a number I remember or infer for one I verified.

Status of the three priority items, plainly:

1. **Measured body resonances of the sitar (tumba air resonance, tabli plate modes, bridge/body admittance peaks) — not sourced at all. Zero confirmed modes.** After an extensive search (impact-hammer/laser-vibrometry/holography terms, "modal analysis of the sitar," "vibro-acoustic," "tumba," "tabli," JASA/Acta Acustica/JASI/SMAC/ISMA searches, IIT/IISc thesis repositories including Shodhganga), the only paper that appears to have actually measured a sitar's natural frequencies — Limkar & Chandekar's operational-modal-analysis study — is paywalled with no reachable mirror (see §1.4/§5). Every other candidate that turned up is either about the string (Siddiq, Raman, Vyasarayani et al.), about a different instrument (tanpura, koto-adjacent, guitar), or a conference abstract too short to contain a number. One physical-modeling thesis (Ronan) states outright that no sitar body measurement was available to it and substitutes a guitar's resonances instead — that absence-of-data statement is itself sourced and quoted below, but it obviously licenses no Hz value for the sitar.
2. **The jawari mechanism — sourced and quoted.** Raman's 1921 paper is the foundational source but never names the sitar (it covers the tanpura and veena only); two openable, sitar-specific sources close that gap explicitly: Pisharody & Gupta (IIT Kanpur) state the curved-bridge/wrapping mechanism applies to "sit¯ar and rudra v¯ın. ¯a," and Siddiq's physical-modelling paper opens by crediting "Raman identified the flat bridges of the sitar as the main reason for its distinctive sound." A sitar-specific dissertation (Ronan) and a HCI paper (Kapur et al.) add corroborating, independently-opened description of the same mechanism.
3. **The tarab (sympathetic strings) — sourced and quoted.** Weisser & Demoucron's conference paper, read in full via an institutional-repository mirror, gives a sitar-specific table: string count, bridge type, tuning convention. Two more independently-opened sources (Ronan, Kapur et al.) corroborate the count and tuning-by-raga claim with their own numbers, which differ somewhat from each other — that spread is reported rather than resolved.

How this maps onto the engine: PLUCK's body is a fixed resonator bank of absolute Hz values, so a mode only earns a row in the working table (written later, by the verifier/controller, not here) if a real source gives a real Hz number for the sitar's body. None does, in what was reachable this run. That is the headline finding, not a footnote.

---

## 1. Sitar body (tumba gourd resonator + tabli wooden soundboard)

### 1.1 Modes

| # | Label | Hz (range) | Relative strength | Q / decay | Status | Src |
|---|---|---|---|---|---|---|
| — | *(no rows)* | — | — | — | — | — |

**Controller's note (2026-09-26):** no verifier ran on this note, because
no Hz from it reaches code — the mode table is empty. The jawari and tarab
quotes below support design claims in the spec, not constants; they are
recorded as the researcher read them and remain unverified. The four
unopened candidates in section 4 are the list to try when someone with
access can open them; a verified modal table from any of them is what adds
`bodyFor(SITAR)`.

**Zero rows, deliberately.** No source opened this run gives a measured or even a plausibly-sourced Hz value for a sitar body/air/plate resonance. The table is kept (per the task's required structure) rather than deleted so the gap is visible rather than silently absorbed. Section 1.2 below quotes what the reachable literature gives instead: sitar anatomy/coupling description (no Hz), and two sources' explicit statements that no sitar body measurement was available to them either.

### 1.2 What was found instead (quotes, no Hz)

**Anatomy and the coupling path (tumba → tabli → dand), independently opened:**

- Kapur, Lazier, Davidson, Wilson & Cook (NIME 2004), on the physical parts and how they connect: "The gourd section of the sitar is known as the tumba and plays the role of a resonating chamber. The flat piece of wood which lays on the front side of the tumba is known as the tabli. The long column which extends from the tumba is known as the dand (similar to the neck of a guitar), and is made out of the same material as the tabli. This part of the instrument acts as a column resonator. Sometimes, a second tumba is put at the dand to increase resonance."
- Same source, on the string load: "The sitar contains seven strings on the upper bridge, and twelve sympathetic stings below, all tuned by tuning pegs." And on overall build: "Its bulbous gourd (shown in Figure 1), cut flat on the top, is joined to a long necked hollowed concave stem that stretches three feet long and three inches wide."
- Ronan (University of Limerick MSc dissertation), on the resonator itself: "The resonator of the sitar is called the Kadu. These are very delicate and are normally just made of a gourd. On some sitars there are two resonators, the other one being at the top of the neck. They gourds may also sometimes have strings inside of them that are there to resonate sympathetically."

**Explicit statements that no sitar body measurement was available — this is the strongest evidence for the "zero modes" finding, so it is quoted at length:**

- Ronan, describing his own resonator implementation choice: "Unfortunately an actual sitar was not obtainable at the time that this implementation was being developed so an analysis of the actual body resonances of a sitar was not performed. The resonances used were similar to those of a Martin D-28 guitar (Fletcher and Rossing, 2005). The exact resonances in Fletcher and Rossings book weren't used, they were used mainly as a guideline, and a lot of trial and error was involved in getting it to sound correct." **No Martin D-28 (or any other guitar's) Hz value is reported anywhere in this note — that substitution is quoted here only as evidence that even a dedicated sitar physical-modeling project could not find real sitar body data, not as a source of numbers to reuse.**
- Siddiq, scoping his own model away from the body entirely: "Sympathetic strings and the resonating body, although beyond doubt important for its sound because of their filtering effect and because of interactions between them and the vibrating string, are neglected."

**A caution on two numbers that appear in the reachable literature but are NOT body modes, and must not be mistaken for them:** two opened sources report a Hz figure for a *plucked string's fundamental*, not a body/air resonance — Pisharody & Gupta's tanpura string "was consistently tuned to F-sharp (using an electronic tānpurā drone), having a fundamental frequency of around 92.8 Hz" (tanpura, not sitar), and Siddiq's analyzed recording used "the plucked string (c with f0 ≈ 131Hz)" (sitar, but the string, not the body). Neither number describes the tumba, the tabli, or any coupled body/air mode, and neither appears in the mode table above.

### 1.3 What the literature cannot give

Every source that plausibly contains a real sitar body measurement was either unreachable (§1.4/§5) or turned out, on full reading, to be about the string or explicitly silent on the body. No JASA/Acta Acustica/JASI paper on sitar modal testing (impact hammer, laser vibrometry, holography, or acoustic-camera Chladni work) could be found open. No Indian-university thesis repository (Shodhganga was searched directly) surfaced a sitar acoustics/vibration thesis with retrievable body-mode data — the one Shodhganga hit found by title ("Sitar in Indian classical music: a study of the changing Vadan Shailis...") is a musicological/performance-practice thesis, not an acoustics one, and was not pursued further since its title gives no indication of modal content. The physical-modeling literature that does exist (Ronan, Siddiq, and the ESitar/NIME line) either substitutes a different instrument's resonances as a guideline (Ronan) or excludes the body from the model outright (Siddiq) — both are symptomatic of the same underlying gap, not independent confirmations of anything. The one paper that appears to have actually performed modal testing on a real sitar — Limkar & Chandekar (2022), using both operational modal analysis via Stochastic Subspace Identification and a soft-tip-hammer experimental modal analysis, explicitly because "hammer or shaker excitation required for conventional experimental modal analysis (EMA) has huge limitations of using harder hammer tips and high magnitude force as the instrument is delicate" (per its search-indexed abstract; this sentence itself was never independently opened and is not treated as quoted/sourced) — could not be opened by any policy-compliant route tried; see §1.4 and §5 for the specific attempts and their outcomes. Until that paper (or an equivalent) is reachable, a fixed resonator bank for PLUCK's sitar body has no sitar-specific Hz to draw on at all.

### 1.4 Sources (opened)

| # | Citation | URL | Opened? |
|---|---|---|---|
| 1 | Kapur, A., Lazier, A. J., Davidson, P., Wilson, R. S., & Cook, P. R. (2004). "The Electronic Sitar Controller." *Proc. NIME 2004*, Hamamatsu, pp. NIME04-7–8. | https://www.nime.org/proceedings/2004/nime2004_007.pdf | Yes |
| 2 | Ronan, D. "The Physical Modelling of a Sitar." MSc dissertation, University of Limerick (Music Technology), supervised by G. Torre. | http://issta.ie/wp-content/uploads/The-Physical-Modelling-of-a-Sitar.pdf (HTTPS 403/TLS-refused from this run's network; the identical path over plain HTTP returned 200) | Yes |
| 3 | Siddiq, S. (2010). "Physical Modelling of the Sitar String." *Proceedings of the Second Vienna Talk*, pp. 137–140. | https://viennatalk.mdw.ac.at/papers/Pap_01_90_Siddiq.pdf | Yes (no sitar body Hz in it — see §1.2/§1.3) |
| 4 | Pisharody, R., & Gupta, A. "Experimental investigations of tānpurā acoustics." IIT Kanpur (short paper/note). | https://home.iitk.ac.in/~ag/papers/tanpura.pdf | Yes (tanpura, not sitar — cited here only for its explicit sitar/rudra vīṇā generalization, §2) |

No sitar-specific body-mode Hz value came from any of these four — they are listed here (rather than only under §2/§3) because they are the sources for the anatomy and absence-of-measurement quotes in §1.2.

---

## 2. The jawari (curved bridge mechanism)

Priority: quotes only, no numbers needed — this backs a design claim (why PLUCK's pluck engine should model energy moving into upper partials over the course of a note), not a resonator frequency.

**Foundational description — Raman (1921), read in full. Note precisely: this paper's title is "On some Indian stringed instruments" and its entire text covers only the Tanpura and the Veena; the word "sitar" does not appear in it anywhere. It is quoted here as background, and the sitar-specific extension of the same claim comes from the two sources quoted after it.**

- On the bridge form itself (Tanpura): "The strings do not come clear off the edge of a sharp bridge as in European stringed instruments, but pass over a curved wooden surface fixed to the body which forms the bridge. The exact length of the string which actually touches the upper surface of the bridge is adjusted by slipping in a woollen or silken thread of suitable thickness between each string and the bridge below it and adjusting its position by trial."
- On the mechanism of energy transfer into overtones: "It seems probable that by far the greater portion of the communication of energy to the bridge occurs at or near the point of grazing contact. The forces exerted by the string on the bridge near this point are probably in the nature of impulses occurring once in each vibration of the string. This would explain the powerful retinue of overtones including even those absent initially in the vibration of the string. At a slightly later stage, the reaction of the bridge on the string would result in a modification of the vibration form of the latter and bring into existence partials absent initially in it. There would in fact be a continual transformation of the energy of vibration of the fundamental vibration into the overtones."
- On the resulting breakdown of normal plucked-string acoustics: "We are thus forced to the conclusion that the effect of the special form of bridge is completely to set aside the validity of the Young–Helmholtz law and actually to manufacture a powerful sequence of overtones including those which ought not to have been elicited according to that law."
- Summary line: "The tones of these instruments show a remarkable, powerful series of overtones which gives them a bright and pleasing quality."

**Extension to the sitar specifically — Pisharody & Gupta (IIT Kanpur tanpura paper), which explicitly generalizes the mechanism:**

- "The strings pass over a doubly-curved bridge of finite width before reaching the board, see Figure 1, as is the case with other Indian string instruments such as sit¯ar and rudra v¯ın. ¯a [4]." (reference [4] in that paper is Raman 1921, cited above)
- "The curved bridge provides a unilateral constraint to the vibrating string and, in doing so, becomes the source for an overtone rich sound of the musical instrument."
- "The coupling of various modes, and therefore of the overtones, is also present in this case due to the wrapping/unwrapping motion of the string on the bridge."

**Extension to the sitar specifically — Siddiq, whose paper is about the sitar string by name and opens by crediting Raman:**

- "Raman identified the flat bridges of the sitar as the main reason for its distinctive sound [2]. The bridge disturbs the free vibration of the string which is resting on it, varying the length of the vibrating part periodically. Laws which assume that the string has constant length are thus not applicable."
- On the Young–Helmholtz breakdown, stated for the sitar directly: "This rule, known as the Young-Helmholtz law, is not valid for the sitar [2], as figure 3 shows."
- Footnote on the bridge's actual shape (Siddiq's own term for it is "flat," in scare quotes): "It should be noted that the bridges of the sitar are not really flat. Their surface is rather a curved plane."

**Corroborating description of the physical mechanism — Ronan, on the sitar's two named bridges and how plucking generates overtones through them:**

- "The most important parts of the sitar are the two bridges. There is the large bridge called the badaa goraa for holding the drone and melody strings in place and then there is the smaller bridge for the sympathetic strings called the chota goraa. These bridges are collectively know as jawari and are normally made of camel bone. The shape of the jawari are like slopes and it is the way the string interacts with these slopes when plucked that give the sitar its particular timbre."
- "Initially, when the sitar string is plucked is, there is a shortening and lengthening of the string relative that is relative to the slope which leads to the string generating overtones."
- "This interaction between the string and jawari reduces gain substantially, as the energy is transferred to the louder, higher partials."

**Corroborating description — Kapur et al., on the bridge's physical construction and naming:**

- "The seven main upper strings run along the dand, above moveable, curved metal frets, over a bridge (jawari) made of ivory or deer horn and tie together at the langot at the very bottom of the sitar. The sympathetic strings, or tarab strings, run below the frets and have their own separate bridge (ara), but still tie together at the langot."

### Sources

| # | Citation | URL | Opened? |
|---|---|---|---|
| 1 | Raman, C. V. (1921). "On some Indian stringed instruments." *Proc. Indian Assoc. Cultiv. Sci.* 7, 29–33. | http://repository.ias.ac.in/69870/1/69870.pdf | Yes |
| 2 | Pisharody, R., & Gupta, A. "Experimental investigations of tānpurā acoustics." IIT Kanpur. | https://home.iitk.ac.in/~ag/papers/tanpura.pdf | Yes |
| 3 | Siddiq, S. (2010). "Physical Modelling of the Sitar String." *Proceedings of the Second Vienna Talk*, pp. 137–140. | https://viennatalk.mdw.ac.at/papers/Pap_01_90_Siddiq.pdf | Yes |
| 4 | Ronan, D. "The Physical Modelling of a Sitar." MSc dissertation, University of Limerick. | http://issta.ie/wp-content/uploads/The-Physical-Modelling-of-a-Sitar.pdf | Yes |
| 5 | Kapur, A. et al. (2004). "The Electronic Sitar Controller." *Proc. NIME 2004*. | https://www.nime.org/proceedings/2004/nime2004_007.pdf | Yes |

---

## 3. The tarab (sympathetic strings)

Priority: quotes only — how many there are, and how they are tuned.

**Primary source — Weisser & Demoucron (2012/2014), *Proceedings of Meetings on Acoustics* 15, 035006, read in full via an institutional-repository mirror after the AIP/ASA publisher page returned 403:**

- Abstract: "Most chordophones of the contemporary classical Hindustani tradition are characterized by the presence of numerous sympathetic strings (taraf), sometimes up to over 30. Generally tuned according to the rag, they are inserted within the handle of the plucked lutes sitar and sarod, and next to and below the main strings of the bowed fiddle sarangi."
- Table 1 ("Taraf settings and characteristics according to instrument type"), Sitar column, verbatim cell contents: Material — "Metal"; Bridge — "Wide and curved, independent from the playing strings"; Tuning — "According to the rag"; Number — "From 11 to 13, grouped in a single set"; Location — "In the closed unfretted handle, next to the playing strings."
- On the taraf's historical adoption (cited within the paper from Junius 1974, not independently opened by me): "the sitar became equipped with taraf by the end of the 19th century [Junius 1974, p. 20]."
- On sympathetic strings' measured effect on tuning, cited within the paper from Jairazbhoy & Stone (1963) — **this citation was read inside Weisser & Demoucron's text, not independently opened**: "N. A. Jairazbhoy and A. W. Stone [1963] observed that on the sitar the presence of sympathetic strings may have an important effect on intonation: the sympathetic strings may cause the pitch of the main string to drop rapidly and beats to become audible. They measured 'a difference of as much as 20 cents in extreme cases between the pitch at the moment of impact and the levelling of the tone' [Jairazbhoy and Stone 1963]."

**Corroborating count and tuning-by-raga claim — Ronan, independently opened, giving a different total:**

- "Normally the sitar has about 21 strings, most of these being sympathetic. These sympathetic strings are also known as tarb. These strings are never really ever touched as they are just meant to vibrate sympathetically."

**Corroborating count — Kapur et al., independently opened, giving a third figure:**

- "The sitar contains seven strings on the upper bridge, and twelve sympathetic stings below, all tuned by tuning pegs."
- On the historical range: "In the 19th century, the tarabdar style of sitar emerged, which had 9 to 12 sympathetic strings (known as tarab) positioned under the frets, as depicted in Figure 1."

**The spread is reported, not resolved:** three independently-opened sources give three different counts for the sitar's sympathetic strings — 11–13 (Weisser & Demoucron, describing a studied instrument at ITC-Sangeet Research Academy, Kolkata), "about 21" total strings with "most" sympathetic (Ronan, i.e. roughly 14 sympathetic once ~7 main/drone strings are subtracted — Ronan does not give that subtraction explicitly, so this arithmetic is mine, not the source's, and is flagged as such), and 12 (Kapur et al.'s specific instrument), with a historical range of 9–12 noted by the same source. All three agree on the qualitative claims that matter for design — tuned to match the notes of the raga being played, mounted in the neck/handle separate from the main playing strings, and passing over their own curved bridge — but none of them claims a universal fixed count, and this note does not manufacture one.

### Sources

| # | Citation | URL | Opened? |
|---|---|---|---|
| 1 | Weisser, S., & Demoucron, M. (2012/2014). "Shaping the resonance. Sympathetic strings in Hindustani classical instruments." *Proc. Mtgs. Acoust.* 15, 035006. | https://dipot.ulb.ac.be/dspace/bitstream/2013/336895/3/Weisser-Demoucron_POMA.pdf (ULB institutional repository mirror; the AIP/ASA publisher page at pubs.aip.org/asa/poma returned 403) | Yes |
| 2 | Ronan, D. "The Physical Modelling of a Sitar." MSc dissertation, University of Limerick. | http://issta.ie/wp-content/uploads/The-Physical-Modelling-of-a-Sitar.pdf | Yes |
| 3 | Kapur, A. et al. (2004). "The Electronic Sitar Controller." *Proc. NIME 2004*. | https://www.nime.org/proceedings/2004/nime2004_007.pdf | Yes |
| 4 | Jairazbhoy, N. A., & Stone, A. W. (1963). "Intonation in Present-Day North Indian Classical Music." *Bulletin of SOAS* 26(1), 119–132. [known only via quotation inside source #1] | (none) | No — cited within #1, not independently opened |
| 5 | Junius, M. (1974). *The Sitar. The Instrument and its Technique.* Wilhelmshaven: Heinrichshofen. [known only via citation inside source #1] | (none) | No — cited within #1, not independently opened |

---

## 4. Not opened (candidates that could plausibly contain sitar body-mode Hz values, but could not be verified this run)

**These are listed so nobody re-derives a sitar body frequency from a search-engine snippet or a training-data guess and mistakes it for sourced. No number from this table appears anywhere above, and none may be used in code.**

| # | Citation | URL | Attempts and outcomes |
|---|---|---|---|
| 1 | Limkar, B., & Chandekar, G. S. (2022). "Dynamic analysis of Sitar: A comparative study of operational and experimental modal analysis." *Journal of Vibration and Control*. DOI: 10.1177/10775463211042196. | https://journals.sagepub.com/doi/full/10.1177/10775463211042196 | SAGE publisher page: HTTP 403. ResearchGate publication page: HTTP 403. ResearchGate figure page (a stabilization chart of "OMA modes," which per its caption alone almost certainly is the source's own body-mode figure): HTTP 403. Author's Academia.edu profile page (checked for a self-hosted copy): HTTP 403. Unpaywall API lookup on the DOI: `"is_oa":false`, `"best_oa_location":null`, `"has_repository_copy":false` — no open-access copy indexed anywhere. This is the single most likely real source for sitar body Hz values found this run, and it is completely unreachable by every route tried. |
| 2 | Limkar, B., & Chandekar, G. S. (2022). "Dynamic Analysis of Psychoacoustic Parameters to Evaluate Sound Quality of an Indian String Instrument Sitar." In *Technology Innovation in Mechanical Engineering*, Lecture Notes in Mechanical Engineering, Springer, pp. 83–90. DOI: 10.1007/978-981-16-7909-4_8. | https://link.springer.com/chapter/10.1007/978-981-16-7909-4_8 | Springer chapter page: redirected (HTTP 303) to a SpringerLink authentication wall (idp.springer.com), not fetched further since that is explicitly an authenticated URL. Academia.edu copy: HTTP 403 via WebFetch and HTTP 403 via a direct curl attempt with a browser user-agent. Unpaywall API lookup on the DOI: `"is_oa":false`, `"best_oa_location":null` — closed, no repository copy. This is the same author pair's companion paper (three sitars categorized by jawari type — "Khuli Jawari," "Gol Jawari," "Band Jawari" — with loudness/sharpness measurements per the search-indexed abstract), and it too is fully unreachable. |
| 3 | Gunasekera, S. N. "Acoustical Analysis of Sitar Timbre" (also indexed as "Analysis and Estimation of Acoustical Properties of Sitar"). | https://www.scribd.com/document/483392345/Analysis-and-Estimation-of-Acoustical-Properties-of-Sitar | Scribd document page: only a one-paragraph preview/description rendered ("aims to quantify the relationship between physical parameters of a sitar and attributes of its unique timbre" — brightness, buzzing effect, richness of harmonicity), no body text, no table, no figure, and no Hz value anywhere in what loaded. Full text is gated behind a Scribd account/subscription. Author, institution, and publication date could not be confirmed from the document itself (only from an unrelated search-result snippet, which is not a source). |
| 4 | "Sitar spectrum properties." *The Journal of the Acoustical Society of America* 71, S83 (1982). DOI: 10.1121/1.2019587. | https://pubs.aip.org/asa/jasa/article-pdf/71/S1/S83/10768501/s83_1_online.pdf | Direct PDF link: HTTP 403. This is a one-paragraph 1982 ASA-meeting abstract (title only, from search results); even if opened, abstracts of this vintage and length are unlikely to carry a specific Hz table, but it was a genuine candidate and is recorded as unreached rather than assumed empty. |

**Named leads that were explicitly checked against and ruled out as sitar body-mode sources (not listed in the table above because they are not body-mode candidates at all, not because they were unreachable):** Taguti's sawari-mechanism papers concern the biwa/shamisen string against an obstacle, not the sitar body. Vyasarayani, Birkett & McPhee (2009, JASA 125(6), 3673–3682) and Burridge, Kappraff & Morshedi (1982, SIAM J. Appl. Math. 42) model the sitar *string's* dynamics against the curved bridge, not the body — both are cited by name inside Siddiq's opened paper but were not independently fetched, since their subject (per that citation context) is the string, not a resonator Hz. Valette & Cuesta's *Mécanique de la corde vibrante* (1993) is a French monograph on the tanpura string, cited inside Siddiq and Pisharody & Gupta, and no accessible PDF or repository copy was found. A Shodhganga thesis titled "Sitar in Indian classical music: a study of the changing Vadan Shailis of the instrument vis-à-vis its structure" was found by search but not opened — its title indicates a musicological/performance-practice study of playing style, not an acoustics/vibration thesis, so it was not a plausible body-mode candidate and pursuing it further was not a good use of the remaining search budget.
