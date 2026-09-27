# SILK research — shamisen, oud, guzheng, santur, and tuning

**Date:** 2026-09-27 (two passes the same day)
**For:** [`../specs/2026-09-27-silk-string-engine-design.md`](../specs/2026-09-27-silk-string-engine-design.md)

## 0. Preamble — read this before using any number below

The rule is the one [`2026-09-25-pluck-depth-body-research.md`](2026-09-25-pluck-depth-body-research.md)
set: every number is tied to a source that was **opened and read
directly**, with the supporting sentence quoted. `confirmed` means read
directly and matched verbatim. `corrected` means the source was read and
says something different from what an earlier pass recorded; the row
carries the fix. `unsupported` means seen only in a search snippet, an
abstract or a secondary mention, or the source could not be opened — and
**unsupported values are NOT to be used in code.** `computed` is this
note's own arithmetic on sourced inputs, with the working named; `dataset`
(section 5) means confirmed against an open research dataset read
directly, whose underlying book was not opened.

**Two passes.** The first pass ran under a network policy that reached only
GitHub, so almost nothing in it was confirmed. The environment's network
access was widened the same day and every section was re-run; **this file
holds the second pass**, which kept the first pass's confirmed rows,
re-opened what had been blocked, and marks every change of status. The
first pass is in git history (PR #353).

**Access in the second pass.** Journals, proceedings, J-STAGE, arXiv,
Wikipedia (en/ja/zh), CCRMA, the official Scala pages and Maqam World were
read directly. Still refused, and not worked around: pubs.aip.org (all of
JASA and POMA) and ResearchGate (403); a few hosts behind Cloudflare or
CAPTCHA challenges (MIT DSpace, World Scientific, BYU ScholarsArchive,
quod.lib.umich.edu); members-only pages (the MIDI Tuning Standard PDF,
*American Lutherie*); and a handful of hosts outside the widened allowlist.
Some requests were sent with a generic browser User-Agent header; no bot
challenge was bypassed. Three scanned Japanese papers (§1) were read from
page images, so their quotes are transcriptions. Each section's own access
note and source table give the detail.

**What the second pass still could not give,** across all four
instruments: no measured detuning within a course (oud or santur), no
santur t60 or body mode, no Q for any guzheng mode, no mezrab contact
time, and a shamisen skin mode that the same lab reports at two
incompatible frequencies. The spec keeps every such value as a placeholder
tuned by ear at the listening gates ("shape, not measurement").

Throwaway check scripts named in §3 (`scratchpad/gz/*.py`,
`scratchpad/gz2/*.py`) were not committed.

---

## 1. Shamisen (三味線: three-string lute with a skin body, sawari buzz, bachi strike)

**Read this first: sourcing conditions for this run (second pass, 2026-09-27).** This time the scholarly hosts answered. J-STAGE, BioResources (NCSU), Archives of Acoustics (acoustics.ippt.pan.pl), the DAFx-14 archive (dafx14.fau.de), Wikipedia (en/ja), Zenodo and the Hosei University repository (hosei.ecats-library.jp) all returned the full text. Every source the first pass had listed as blocked was fetched with `curl` and read in full, except the CiNii HTML page. That page returned an AWS-WAF bot challenge (HTTP 202, empty body) and was **not** bypassed. Only the bibliographic record was taken from CiNii's public JSON endpoint (`/crid/….json`). The paper itself came from its J-STAGE PDF. ResearchGate, academia.edu and pubs.aip.org are bot-gated and were not tried.

**How the texts were read.** Text-layer PDFs (Taguti 2001/2008, BioResources, Siddiq, Bilbao & Torin, Lasickas, JSME 2013/2016, both Hosei theses) were extracted with PyMuPDF. The three scanned Japanese papers are JSME 2012, JASJ 1995 (Horiuchi et al.) and JASJ 1983 (Ando & Yamaguchi). Their embedded OCR is garbled, so each page was rendered to an image and read visually. For those three, "verbatim" means **our transcription of the printed page**, not a copy-paste. Tables that extracted badly (Taguti 2008 Tables 1 and 5, the Kokubun thesis Tables 4-1 to 4-4 and 10-3, and Bilbao & Torin's Fig. 2 caption) were also checked against the rendered page.

**Consequences.** (a) Real **shamisen measurements now exist in this section**: body-frame, whole-instrument and skin modes with damping ratios, the cavity resonance, radiated spectrum envelopes, attack times, touch-noise levels, decay times, pitch glide, bachi/skin/bridge transients and open-string pitches for nagauta. (b) Some of them come from **student theses and branch-conference papers**, not peer-reviewed journals. The Kind column says so, and conflicts between sources are shown rather than resolved. (c) **No number was read off a plotted curve.** Values come from body text, tables, or numbers printed as figure labels. Anything visible only as a curve is described in words and carries no number. (d) Values marked `computed` are this pass's own arithmetic (throwaway script `scratchpad/gz/sham_v2_calc.py`, not committed). They are not literature.

Status legend as in §0: `confirmed` means opened directly and the quoted text matches verbatim. `corrected` means the source says something different from the first pass; the fix is given. `unsupported` means not found in any source read, or seen only in a secondary mention. **Unsupported values are NOT to be used in code.** A few rows are `confirmed (verbatim) — do NOT use`: the source says it, but the source is internally inconsistent, and the row says why.

### N.1 Mechanism and instrument facts (descriptive, no Hz)

| # | Fact | Kind | Status | Src |
|---|---|---|---|---|
| F1 | Sawari acts on the **open 1st string (ichi no ito, the thickest/lowest) only**. The string lightly touches the neck (sao) near its tuning-peg end and gives a "bīn" buzz. | descriptive | confirmed. The live ja article keeps the F2 sentence verbatim; the F1 sentence now adds a comparison to the sitar's jawari | 1, 2, 22 |
| F1a | **Sawari geometry (new).** The 1st string does *not* sit on the kamikoma (upper nut) the other two strings use. It lies directly on the neck, lower than the others, and grazes a bump (「さわり山」, sawari-yama) very close to the kamikoma. Taguti: a narrow groove "a few millimeters in width" with a pair of edges. The peg-side edge is the fixed end; the other edge is where the string touches and separates. | descriptive (measurement papers' descriptions) | confirmed | 6, 8, 12, 14 |
| F2 | Acoustic effect as stated: **raises the overtone content and prolongs the ring** (倍音成分を増やして…響きを延ばす). It is "a kind of noise" but "indispensable". | descriptive | confirmed | 1, 22 |
| F3 | Stopped notes on strings 2 and 3 at certain positions (which ones depends on the tuning) get the **same effect through resonance** with the open, buzzing 1st string. **Now confirmed by measurement:** in honchōshi, string 2's 3rd and 6th harmonics coincide with string 1's 4th and 8th. String 3's harmonics 1–4 coincide with string 1's harmonics 2, 4, 6, 8, and string 3 beats more strongly. Players use this sympathetic buzz to check tuning and finger position. | descriptive + measurement | confirmed | 1, 15, 16 |
| F4 | **Azuma-zawari**: a screw-type sawari set into the neck, adjustable in height. The fixed-bump type is called 一文字ざわり (ichimonji-zawari, alias oshi-zawari). | descriptive | confirmed | 1, 15 |
| F5 | The body (dō) has skin on **both faces**. The dō is four karin boards glued into a frame. Top-grade frames are carved inside ("ayasugi"). | descriptive | confirmed | 1, 2, 8, 22 |
| F5a | **The two skins are coupled through the enclosed air (new).** In an experimental SEA model, the front-skin → back-skin coupling had to be included for the model to predict the back skin. Coupling-loss factors rank wood→wood > wood→skin > skin→skin > skin→wood. Frequencies with large skin-to-skin power flow had long reverberation. | measurement (SEA) | confirmed | 19 |
| F6 | Skin: cat belly traditionally. Dog skin was ~70% of the total in the 2009 text, and Tsugaru uses dog "with some exceptions". Synthetic skin is "not preferred". **The live ja article (rev. 110697170) no longer gives 70%.** It says cat and dog are falling out of use and being replaced by sheep skin and synthetic leather, while Tsugaru still uses dog. The ja Tsugaru article specifies dog *back* skin glued with starch paste, "extremely sensitive to humidity". **Kangaroo skin: not mentioned in any source read.** | descriptive | confirmed (kangaroo: not found) | 1, 2, 22 |
| F7 | Strings: silk. Tsugaru and students also use **nylon or Tetoron (polyester)**. The live en article says "silk (traditionally) or nylon". | descriptive | confirmed | 1, 2, 22 |
| F8 | Bachi: "the bachi is often used to **strike both string and skin**, creating a highly percussive sound." Still verbatim in the live en article. **Now measured: see B-rows in N.4.** | descriptive | confirmed | 2, 22 |
| F9 | Sizes and genres: **hosozao** = nagauta (kabuki). **chuzao** = jiuta, tokiwazu, kiyomoto, shinnai. **futozao** = gidayū (bunraku) and Tsugaru. Gidayū uses a "big, thick bachi". Tsugaru uses a small bachi with a tortoiseshell tip. The ja Tsugaru article: "三味線本体の寸法は義太夫の三味線とほぼ同じ" (body size about the same as gidayū). The **heike shamisen** neck is "about half the length of most shamisen, giving the instrument the high range needed". **No scale length or dō dimension appears in any source except Hamdan (S11 note).** | descriptive | confirmed | 1, 2, 22 |
| F10 | **Sawari "depth" has a sweet spot (new).** A player (a Kitagawa-school iemoto) set six sawari levels by ear on one instrument; the recordings were then compared. Too little: the sound swells several times. Correctly set: no big swell, a stepwise decay, longer ring. Too much (120%): large beats and a faster decay. Far too much (150%): the beats vanish, which the author attributes to the sawari-yama acting as the kamikoma. | measurement (qualitative, thesis) | confirmed | 15 |

**Quotes (verbatim):**
- F1/F2 (src 1, 2009; still in live ja src 22): 「通常、一の糸の巻き取り部の近くに『さわり』と呼ばれるしくみがある。」「これは一の糸の開放弦をわずかに棹に接触させることによって『ビーン』という音を出させるもので、倍音成分を増やして音色に味を付け、響きを延ばす効果がある。」 Src 2: "The lowest passes over a small hump at the "nut" end so that it buzzes, creating a characteristic sound known as sawari".
- F1a (src 6, Taguti & Tohnai 2001): "It is provided for the first string only. Its structure is a narrow groove of a few millimeters in width, together with a pair of edges, settled just below and perpendicularly to the kamikoma (nut) where the second and third strings are laid. The first string is not on the kamikoma but laid across over the groove so that the edge at peg side works as the fixed end while the other edge is the point the string touches to and separate from when it vibrates." Src 12 (Taguti 2008): "the vital point of the sawari is a slightly raised edge set close to the fixed end of the lowest string across a narrow groove; it acts only on the lowest string." Src 8 (JSME 2012; src 14 has the same text): 「一の糸はこの上駒を使わず棹に直接のせる構造になっている。このため、弦の高さが他の2 本に比べて低くなり，上駒の極めて近い位置にある「さわり山」と呼ばれる突起をかすめるようになっている。」
- F3 (src 16, Oe 2011): 「二の糸は3 倍音と6 倍音が一の糸の4 倍音と8 倍音に，三の糸は基音，2，3，4 倍音が一の糸の2，4，6，8 倍音とほぼ一致している．このことから図2-5と2-6で考察した現象（一の糸の共振）が発生しているといえる．」 Src 15 (Kokubun 2012): 「二の糸、三の糸が正しく倍音に調律されていた場合それらの弦を引いた際に一の糸が共振し、さわりが発生することで判断できる。」
- F4 (src 15): 「このようにさわり山があるだけの構造のものを「一文字ざわり（別名押しざわり）」という。…「東ざわり」…これは螺子によりさわり山の高さを調整するもの」.
- F5a (src 19, Suzuki et al. 2016): 「CLF は木材から木材が大きくなり，木材から皮，皮から皮，皮から木材の順番でCLF は小さくなっていく．」 and 「皮と皮間でのエネルギーのやり取りが顕著なほど残響時間が大きくなることが言える．」
- F6 (src 22, live ja): 「現在は両方とも使用されなくなってきており、代替素材として羊皮や合成皮革に移り変わっているが、津軽三味線は例外を除き犬革を使用する。」 Src 22 (ja 津軽三味線): 「皮に用いるのは犬の皮で、背の部分を用いる。胴との貼り付けは澱粉糊を使用するため、きわめて湿度変化に弱い。」
- F8 (src 2 and live en src 22): "As in the clawhammer style of American banjo playing, the bachi is often used to strike both string and skin, creating a highly percussive sound."
- F9 (src 22, en): "The neck of the heike shamisen is about half the length of most shamisen, giving the instrument the high range needed to play Heike Ondo."
- F10 (src 15, §2.4.1): 「さわりが不十分な場合、音のふくらみが一度ではなく、複数回にわたって発生し…」「さらにさわりを付加し十分に効いた状態にすると…段階的に音が減衰していく形となった。」「さわりの効きすぎた状態では大きなうなりを発生し，音が早めに減衰してしまうことがわかった。その状態からさらにさわりをつけた場合、うなりは発生しなくなる。これはさわり山が高すぎるため、さわり山が上駒の働きをしてしまうためと考えられる。」

### N.2 Tuning, open-string pitch and strings

| # | Quantity | Value | Kind | Status | Src |
|---|---|---|---|---|---|
| S1 | Honchōshi tuning (strings 1-2-3) | 2nd = **perfect 4th** above 1st, 3rd = **octave** above 1st (e.g. C-F-C) | descriptive (interval) | confirmed (also in live ja/en, src 22) | 1, 22 |
| S2 | Niagari | 2nd = **perfect 5th**, 3rd = **octave** (C-G-C) | descriptive (interval) | confirmed | 1, 22 |
| S3 | Sansagari | 2nd = **perfect 4th**, 3rd = **minor 7th above** (C-F-B♭) | descriptive (interval) | confirmed (see verifier note) | 1, 22 |
| S4 | Share of dog skin among current shamisen | **~70%** | descriptive | confirmed **for the 2009 text only**. The live ja article no longer states it (F6), so treat as dated | 1 |
| S5 | Absolute open-string pitch | Was "none found". **Now superseded by P1–P6** | — | superseded | — |
| P1 | Nagauta hosozao in honchōshi (one instrument, one player): open string 2 **≈ 175 Hz**; open string 1 **≈ 131 Hz**; open string 3 **≈ 262 Hz** | measurement | confirmed | 17 |
| P2 | Nagauta shamisen in honchōshi (a different study): open strings **assigned to 12-TET B2, E3, B3 with A4 = 440 Hz**. That is 123.5 / 164.8 / 246.9 Hz (**computed**, our arithmetic) | measurement (note-name assignment) | confirmed (names); Hz computed | 18 |
| P3 | Honchōshi, 1st string (the Oe thesis instrument): fundamental peak "**near 150 Hz**" | measurement (approx.) | confirmed | 16 |
| P4 | **本 (hon) pitch system:** the 1st-string pitch is chosen in semitone steps, **1本 to 12本**, according to the singer's range. Thesis Table 10-3 maps 一本 = A (黄鐘) … 四本 = C … 五本 = C# … 十二本 = G#, and gives Hz for every cell (e.g. 一本: 1st A 218.5, 2nd D 292.7, 3rd A 437; 四本: C 258.7 / F 343.1 / C 517.3; 五本: C# 274.8 / F# 365.7 / C# 549.5). Also: "C-#" pitch pipe = "本調子の5本、一の糸" | descriptive + table | confirmed (system) / **confirmed (verbatim) — do NOT use the Hz column** (see verifier note iv) | 15 |
| P5 | Tsugaru absolute pitch is named after shakuhachi length (「2尺」≈ C; each semitone up −1寸; 「1尺9寸」 = C#, 「2尺1寸」 = B). In western Japan it follows the shinobue 本 (「4本」≈ C, 「5本」 = C#, 「3本」 = B). This agrees with P4's pitch-class mapping | descriptive | confirmed (tertiary) | 22 |
| P6 | Suspended instrument, 3rd (thinnest) string plucked by a reproducible wire-break: reverberation time longest at 500 Hz, "this band contains the 2nd harmonic". 1250 Hz is called the "5th harmonic". So the 3rd string f0 was **≈ 250 Hz** and string 1 ≈ 125 Hz if tuned an octave below (**computed inference**, not stated) | measurement + inference | confirmed (text); f0 computed | 19 |
| S10 | Okinawan "shamisen" paper: "resonant peaks between 1000 and 5000 Hz", sawari "increases higher frequency sound pressure" | — | **corrected.** BioResources does not measure this. It is Hamdan et al.'s one-sentence summary of Kokubun et al. 2012 (src 8), and it garbles it. The primary says peaks at **600–1000 Hz with or without sawari**, and sawari raises the **3000–5000 Hz** band (R3, R4). Use R3/R4, not S10 | 7 → 8 |
| S11 | Open strings "C5 (523 Hz), G4 (392 Hz), and C4 (261 Hz), corresponding to strings 1 through 3" | measurement | **confirmed (verbatim) — do NOT use** (see verifier note v) | 7 |
| S6 | Code constants, string tension T1/T2/T3 (Shamisen-JUCE FD model) | 138.67 / 145.53 / 140.73 N; radii 4.15e-4 / 2.83e-4 / 2.10e-4 m; ρ 1156 kg/m³; E 9.9e9 Pa; σ0 1.378 s⁻¹; σ1 3.57e-3 m²/s; **L = 1 m** | code constant, **not a measurement** | confirmed (as code). **Provenance now stated by the authors' SMC-21 paper:** string E and ρ are taken from a *spider-silk* fibre paper, "all the lengths are set to 1", and tensions and radii were tuned "empirically" to C4-G4-C5 | 5, 20 |
| S7 | Code constants, membrane (same repo) | T 4000 N/m; thickness 0.0002 m; ρ 1150 kg/m³; E 3e9 Pa; ν 0.4; σ0 2.756 s⁻¹; σ1 0.192 m²/s; **Lx = Ly = 1 m** | code constant, **not a measurement** | confirmed (as code). Paper: membrane E/ρ from the "Engineering toolbox" website, thickness from a drumhead article | 5, 20 |
| ST1 | Strings measured in the Kokubun thesis (Fuji-ito), mass / diameter / length: 17-1 silk **1.0 g / 0.90 mm / 1128 mm**; 15-2 silk 0.5 g / 0.65 mm / 1132 mm; 14-2 silk 0.4 g / 0.60 mm / 1121 mm; 13-3 silk 0.2 g / 0.40 mm / 1186 mm; 13-3 nylon 0.3 g / 0.50 mm / 1145 mm | measurement (lengths are whole strings, not scale lengths) | confirmed | 15 |
| ST1c | Linear density from ST1: 17-1 ≈ 8.9e-4 kg/m; 15-2 ≈ 4.4e-4; 14-2 ≈ 3.6e-4; 13-3 silk ≈ 1.7e-4; 13-3 nylon ≈ 2.6e-4 kg/m. Masses have only 1–2 significant figures | **computed** | computed | 15 |
| ST2 | Gauge sets per genre (strings 1/2/3): **min'yō 17-1 / 15-2 / 13-3**; **nagauta 15-1 / 14-2 / 13-3** | descriptive (table) | confirmed | 15 |
| ST3 | Biwa silk strings (Chikuzen, **not shamisen**), lateral compression fit: d0 = 1.35 / 1.45 / 1.02 / 0.82 / 0.72 mm (strings 1–5); contact stiffness K0 = 3.2 / 2.0 / 3.6 / 3.4 / 2.9 ×10⁶ N/m² (per unit length); soft initial segment K0′ = 4.5e4 / 1.1e5 / 4.1e4 / 3.8e4 / 2.0e4 N/m² | measurement (biwa silk) | confirmed | 12 |
| S12 | Old row: "1607 Sound and Vibration of SHAMISEN", JSME D&D 2012, no numbers seen | — | **corrected** (citation) and now **read**. It is JSME **Kanto Branch** 18th General Meeting (Narashino, 9–10 Mar 2012), paper 1607, by Kokubun, Oe, Iwahara & Minorikawa (Hosei), DOI 10.1299/jsmekanto.2012.18.479. It is not the D&D Conference. Its numbers are in N.3 | 8 |

**Quotes (verbatim):**
- P1 (src 17, our transcription of the scan): 「使用した三味線は，長唄等に用いられる細棹であり，本調子で調弦した。また，材質は紅木棹，花梨胴（内部は綾杉彫り），猫皮であり，象牙撥を用いて演奏した。本論文中で解析した音は2の開放弦（基本周波数は約175 Hz）である。」 and 「1の開放弦（基本周波数は約131 Hz），3の開放弦（基本周波数は約262 Hz）について三味線音を収集・解析した。」
- P2 (src 18, transcription): 「三味線は，長唄に使用されるものを用い，本調子に調弦している。このとき，各開放弦は，12平均律の音名B2, E3, B3に対応させ，これにより，各資料音を分類した。ただし，A4=440 Hzである。」
- P3 (src 16): 「さわりの有無に関係なく基音（150Hz 付近のピーク）の音圧よりも1000Hz 前後のピークの音圧のほうが高くなっている」.
- P4 (src 15, §1.3.2): 「本数は、音域の基準を示しており、歌い手の声域などによって異なった調律を行う。1 本かた12 本まであり、西洋音階でいう半音を単位としている。」 §2.3.1.1: 「ここではC-#の音を録音した。なお、この音は本調子の5 本、一の糸の調律に用いる。」 Table 10-3 rows as given above.
- P5 (src 22, ja 津軽三味線): 「「2尺」がほぼ絶対音Cに該当し、以降半音上がるごとに1寸減じ、下がるごとに1寸増す。「1尺9寸」がC#、「2尺1寸」がBにほぼ該当する。」「4本がほぼ絶対音Cに該当し…「5本」がC＃、「3本」がBにほぼ該当する。」
- P6 (src 19): 「約18000Hz まで倍音を確認することができる．次に図4 より500Hz において一番残響時間が長くなっている．この周波数帯には2 次の倍音成分が存在する．」 and 「500Hz（2 次の倍音成分），1250Hz（5 次の倍音成分）で残響時間が長くなっている．」
- S10 (src 7): "Kokubu et al. (2012) … Using FFT and Finite Element Method (FEM) analysis, they identified resonant peaks between 1000 and 5000 Hz and confirmed that sawari increases higher frequency sound pressure". Compare the primary, src 8: 「さわりの有無に関わらず600〜1000Hz 付近にピークが立った。さらにさわりのある場合は3000〜5000Hz においてさわり無しの場合に比べてピークが高くなった。」
- S11 (src 7): "The plucked notes were C5 (523 Hz), G4 (392 Hz), and C4 (261 Hz), corresponding to strings 1 through 3." Abstract: "The PicoScope displayed the fundamental frequency one octave higher than the perceived pitch".
- S6/S7 (src 20, Table 2 caption): "Parameters were taken from * [21]; ** [22]; ⋆[23]; ⋆⋆[24]; all the lengths† are set to 1 and the other parameters are tuned empirically to produce a desired sound." Ref [22] = "Engineering properties of spider silk fibers". §4.2: "The strings are tuned to 'C4', 'G4' and 'C5' as it is a common tuning for the shamisen."
- ST1/ST2 (src 15, Tables 1-1 and 1-2): as tabulated above (e.g. 「富士糸 17-1（Silk） 1.0 0.90 1128」; 「民謡 17-1 15-2 13-3」「長唄 15-1 14-2 13-3」).
- ST3 (src 12, Table 5, checked on the rendered page): "1st 1.35 0.14 4.5·10⁴ 3.2·10⁶ … 5th 0.72 0.13 2.0·10⁴ 2.9·10⁶"; "†The second string is the thickest one, and is tuned to the lowest pitch."

**Verifier notes.**
- (i) S3: the NICT English says "minor seventh **below**". That is a translation error. The Japanese says 「三の糸を短７度**高く**」; the live ja, live en and ja-Tsugaru articles agree ("短7度上"). A second typo: the Kokubun thesis §1.3.1 gives 二上がり's 2nd string as 「完全4 度」. That contradicts its own Table 10-3 (e.g. A→E) and every other source; niagari is a 5th.
- (ii) Converting S1–S3 to cents is **our arithmetic**. No source states just versus tempered intervals. P4's table implies neither (note iv).
- (iii) S6's derived open strings (235.4 / 353.6 / 468.6 Hz on L = 1 m) remain our derivation from code and are **not a pitch reference**. The paper states its target was C4-G4-C5 and that the lengths are not physical.
- (iv) **P4's Hz column should not be used.** The table cites no source. Its semitone steps run from **75 to 118 cents** (computed), so it is not 12-TET or any regular temperament. It gives G# as both 401.1 Hz (二本 三下がり) and 410.1 Hz (五本 二上がり, 十二本 1st string). Its 1st-string octave (218.5–410.1 Hz) is **an octave above every measured 1st string** (P1 ≈ 131 Hz, P2 B2, P3 ≈ 150 Hz, P6 ≈ 125 Hz inferred). Use P4 for the **pitch-class mapping of 本** only. P5 corroborates that mapping (4本 = C, 5本 = C#, 3本 = B).
- (v) **S11 should not be used.** Hamdan et al. studied a **heike shamisen** (about 65 cm overall, body ~20 cm, neck 33 cm, head ~12 cm, rosewood), which by src 22 is a short-neck, high-range instrument. They number the strings in reverse: their "string 1" is the highest. Their abstract and Conclusion 5 say the displayed fundamental was "one octave higher than the perceived pitch". Their Table 1 lists partial ratios of 1.49–1.51, 2.49–2.50 and 3.51, which fits a true f0 of half the listed value (260 / 196 / 130.5 Hz, **computed**). They also read octave jumps in their own fret sweep as "traditional Japanese tuning". The recording chain was a microphone at 20 cm, a PA amplifier and a PicoScope.

### N.3 Body, skin and cavity: modal data (all measured on real shamisen unless marked)

| # | Label | Hz | Damping / decay | Kind | Status | Src |
|---|---|---|---|---|---|---|
| B1 | Whole instrument, 1st mode (neck bending) | **67.55** | ζ = **0.923 %** (Q ≈ 54, t60 ≈ 1.76 s, **computed**) | measurement (thesis; hammer test, 12 points Z) | confirmed | 15 (from 16) |
| B2 | Whole instrument, 2nd mode (twisting) | **85.38** | ζ = **2.35 %** (Q ≈ 21, t60 ≈ 0.55 s, computed) | measurement (thesis) | confirmed | 15 |
| B3 | **Bare dō frame (no skins)**, 1st mode (bending), 49-point XYZ test | **573.2** | ζ = **0.605 %** (Q ≈ 83, t60 ≈ 0.32 s, computed) | measurement | confirmed | 8, 15 |
| B4 | Bare dō frame, 2nd mode (twisting), same test | **630.8** | ζ = **0.854 %** (Q ≈ 59, t60 ≈ 0.20 s, computed) | measurement | confirmed | 8, 15 |
| B3′/B4′ | Same frame, earlier Z-only 9-point test | 569.2 / 672.4 | ζ 0.576 % / 0.258 % | measurement (superseded by the authors) | confirmed | 15 |
| B3″/B4″ | Bare dō frame, the lab's 2013 test (24 points XYZ) | **578.1 / 636.6** | not reported | measurement | confirmed | 14 |
| B5 | **Skin, mounted, 1st mode** (laser-Doppler vibrometer, hammer, Z) | **151.7** (thesis; text also says 151.3; JSME 2012 "near 152 Hz") | ζ = **1.02 %** (Q ≈ 49, t60 ≈ 0.71 s, computed) | measurement | confirmed — **conflicts with B6** | 8, 15 |
| B6 | **Skin, mounted, 1st / 2nd mode** (same lab, 2013, LDV, 216 points Z) | **766.4 / 1253** | not reported | measurement | confirmed — **conflicts with B5** | 14 |
| C1 | Dō air cavity, FEM (rigid walls, closed, from CAD of the bare frame) | **996, 999, 1517, 1787, 1834, 2078** | — | simulation | confirmed | 15 (also 8: "1000, 1500, 2000 Hz", "999Hz") |
| C2 | Dō air cavity, experiment: frame openings sealed with **boards**, bubble-wrap pop inside | peaks near **1000, 2200, (2600), 3700**. JSME 2012: ~1000 and 2000–3000 | every pop "decayed in about **0.05 s**". Longest reverberation near 1000 Hz | measurement (not the real skin-bounded cavity) | confirmed | 8, 15 |
| C3 | Structural response under hammer at the bridge: skins have many modes and large response; wood has few modes and small response | — | — | measurement (qualitative) | confirmed | 19 |
| B7 | Aluminium-frame shamisen with substitute heads (newspaper, foil, felt) | — | "decayed within **0.5 s**". Cowhide decayed longer (JSME 2012). The thesis's cowhide instead gave a *shorter* decay than plywood/aluminium | measurement (non-standard heads) | confirmed | 8, 15 |

**Quotes (verbatim):**
- B1/B2 (src 15, Table 4-1, checked on the rendered page): "Fundamental 67.55 0.923 bend / Second 85.38 2.35 twisted". Text: 「固有振動数は67.55Hz および85.38Hz であった。これは三味線の最も低い音（一の糸開放）よりも著しく低かった。」
- B3/B4 (src 15, Table 4-3): "Fundamental 573.2 0.605 bend / Second 630.8 0.854 twisted". Src 8 figure label: "Mode Shape : Order = 4, f = 573.2 (Hz), ζ = 0.605 (%)" and text 「胴体の1 次モードは573Hz 、2 次モードは630.8Hz となった。」 Method (src 8): 「皮を張る前の状態の胴体に対し、インパルスハンマを用いた打撃試験を行い、XYZ 方向に49 点加振した。」
- B3′/B4′ (src 15, Table 4-2): "Fundamental 569.2 0.576 bend / Second 672.4 0.258 twisted".
- B3″/B4″ (src 14): 「胴体の１次モードは578.1Hz，２次モードは636.6Hz となった．」
- B5 (src 15, Table 4-4 and figure label): "Fundamental 151.7 1.02 bend"; "Mode Shape : Order = 6, f = 151.7 (Hz), ζ = 1.02 (%)"; 「皮の固有振動数はハンマリングによる加振試験で減衰比の小さな値は151.7Hz に1 次モードが見られた。」 Src 8: 「実験の結果、152Hz 付近においてモード形状を確認することができた。」 Conclusion 5: 「皮の固有振動数は150Hz 付近であり単体ではあまり音色に影響がない」.
- B6 (src 14): 「レーザードップラー振動計を用いたハンマリング加振実験により三味線に張ってある状態の皮の固有振動数を測定した．インパルスハンマを用いZ 方向に216 点を加振した．」 and 「皮の１次モードは766.4Hz，２次モードは1253Hz となった．」
- C1 (src 15, Table 6-1): "996Hz 999Hz 1517Hz 1787Hz 1834Hz 2078Hz"; 「境界条件は外部との共振がない完全な空洞共鳴とし剛体として扱った。」
- C2 (src 15): 「すべてのデータにはおいて0.05 秒ほどの時間で減衰した。」「すべての結果で1000Hz、2200Hz、3700Hz 付近にピークが見られた。」 §9.6: 「1000Hz、2200Hz、2600Hz、3700Hz 付近の音圧が高いことがわかった。また、スペクトログラム解析の結果最も残響が長かったのが1000Hz 前後となった。」
- C3 (src 19): 「皮は固有振動数も多く，振動が大きく，木材部はそれに比べ振動が小さく，固有振動数も少ない．」
- B7 (src 8): 「新聞紙やアルミホイル、フェルトを張って実験を行った結果0.5 秒以内に減衰した。一方皮革については他の素材と比べると減衰は長かった。」

**Verifier notes.** (i) **B5 and B6 are not reconcilable from the text.** B5 (151.7 Hz, 2011 thesis, 124 points; the JSME 2012 paper says 194 points for the same test) and B6 (766.4 Hz, 2013, 216 points, mounted) come from the same lab, apparently on different instruments. Neither paper mentions the other. The B5 mode plot looks noisy, and its software label is "Order = 6", which suggests lower-order fits were discarded. Treat the fundamental skin mode as **unresolved between ~150 Hz and ~770 Hz**. (ii) **B3/B4 are wood-frame modes measured without skins.** They are not dō-with-skins modes. (iii) C1/C2 are the cavity with rigid or board walls, not skin walls. Their ~1000 Hz result agrees with the radiated-spectrum peak (R1–R3), and the authors argue it is related. (iv) Q = 1/(2ζ) and t60 = ln(1000)/(2πfζ) are **computed** (§0 relation).

### N.4 Radiated sound: spectrum, attack, bachi, decay, pitch glide

| # | Quantity | Value | Kind | Status | Src |
|---|---|---|---|---|---|
| R1 | Spectral envelope, tatakibachi (standard strike), 10 notes, nagauta | **peak near 700 Hz**; below it **−25 dB/oct** roll-off; B2's fundamental at **−41 dB**; for B2 the largest partial is the **5th** | measurement | confirmed | 18 |
| R1a | Envelope peak by technique: hajiki (left-hand pluck) **≈ 1 kHz** and broader; sukuibachi (upstroke) **800 Hz** with relatively more HF | measurement | confirmed | 18 |
| R2 | Sustained part (region IV): skin vibration ≈ **700 Hz**, amplitude-modulated at the 175 Hz string fundamental. Comb of equally spaced peaks at the string f0 | measurement | confirmed | 17 |
| R3 | Plucked by finger, string 1: peaks near **600–1000 Hz with or without sawari** (2012), "near 1000 Hz" (2013). Conclusion: "SPL near 1000 Hz is highest". In all strings the 1000 Hz region exceeds the fundamental | measurement | confirmed | 8, 14, 15, 16 |
| R4 | Sawari on string 1 raises the **3000–5000 Hz** band (2012) / everything "above 3000 Hz" (2013), and lengthens reverberation there. Spectrograms: with sawari, reverberation lengthens mainly around **4000 and 6000 Hz**, and also around 1000 Hz. Laser measurement of the string alone: with sawari, "uniformly strong peaks up to 2000 Hz"; without, levels fall from the fundamental upward | measurement | confirmed | 8, 14, 15 |
| R5 | Harmonics visible up to **~18 kHz** (3rd string, wire-break pluck) | measurement | confirmed | 19 |
| A1 | **Attack time** (−20 dB → peak), all techniques and notes: **1.5–5 ms**, except sukuibachi B2/E3 ≈ **7 ms**. Piano by the same method: 8–20 ms. Conclusion: "5 ms or less" | measurement | confirmed | 18 |
| A2 | **Touch noise** (the bachi touching the string *before* the note starts): mean duration **≈ 21 ms**, level **−20 to −26 dB** re the waveform maximum. Main components: string 1: 900 Hz (0 dB), 1.3 kHz (−10.5), 3.6 kHz (−6, −15); string 2: 1.2k (0), 1.6k (−5), 1.9k (−3), 2.3k (−1); string 3: 700 (0, −2), 1.7k (0), 1.9k (0, −5), 2.3k (−5). Per technique (tatakibachi B2…A4): −15.9 dB/18 ms, −23.3/30, −20.0/25, −21.2/23, −22.4/18. Kasumebachi and uchiyubi: mostly none | measurement | confirmed | 18 |
| A3 | Bachi–string–skin sequence (high-speed video at 1000 fps plus accelerometers): the bachi rubs the string for **≈ 7 ms** (T1→T2), then hits the skin while still on the string. String–skin contact lasts **≈ 20 ms**. The string is then released (T3 = onset). After onset the sound and skin signals fall steeply for **≈ 6 ms**. The bachi's own vibration dies in **≈ 11 ms** | measurement | confirmed | 17 |
| A4 | Frequency content by phase (skin vibration, Table 1): I: ~1 kHz (via bridge). II: ~1 kHz plus a transient **up to ~8 kHz** (bachi on skin while rubbing). III: ~700 Hz plus a transient **up to ~16 kHz** (bachi alone strikes skin). IV: ~700 Hz. Playing *without* touching the skin removes most content **above ~3 kHz** just after onset. The skin-strike contribution spans **≈ 25 ms** (regions II+III) | measurement | confirmed | 17 |
| A5 | Bachi contact point ≈ **1/6 of the bridge–kamikoma distance** (for string 1: bridge to the end of the sawari groove). This gives a component at **≈ 6× f0**: ~800 Hz on string 1, ~1.6 kHz on string 3 | measurement | confirmed | 17 |
| A6 | Bachi material (oak, artificial ivory, ivory) changes the bachi's own vibration spectrum, but the **radiated sound's frequency content "hardly depends"** on it | measurement | confirmed | 17 |
| A7 | Attack-noise recipe that raised perceived shamisen-likeness in a listening test: filtered white noise, **~2 ms rise, exponential decay to −40 dB in ~20 ms**. Harmonic : noise peak ratio **1 : 2 (B2), 1 : 0.9 (A3), 1 : 1 (A4)**. The harmonic attack was reshaped to a **5 ms** log-linear rise | synthesis parameter (validated by listening test, 5 listeners) | confirmed | 18 |
| D1 | Decay to −20 dB, tatakibachi A3: **≈ 270 ms**. Higher notes decay faster. Tatakibachi and keshibachi are fastest. Piano A2/A3/A4: 2000/800/900 ms; guitar A3: 900 ms (their cited data). Shamisen ≈ "half or less" | measurement | confirmed | 18 |
| D2 | Per-harmonic decay is **two-slope**. First mean slope ≈ **−60 to beyond −300 dB/s**, several times steeper than the second. The break falls between 0.05 and 0.5 s. Higher harmonics and higher notes decay faster. Sukuibachi and kasumebachi are mostly single-slope | measurement | confirmed (ranges only; per-harmonic values are graph-only) | 18 |
| D3 | Reverberation time vs frequency, 3rd string (wire-break pluck, suspended instrument): **≈ 3 s at 400 and 500 Hz**, **≈ 2 s at 1250 Hz**, **≈ 0.5 s at 5000 Hz**. Above 1250 Hz it falls exponentially with frequency | measurement | confirmed | 19 |
| D4 | Sawari prolongs the decay and makes the note louder (2012, 2013). The string-1 note with sawari swells once **near 0.3 s**, then decays (Oe) | measurement (qualitative + one time) | confirmed | 8, 14, 16 |
| D5 | Jiuta chuzao (dog skin), A4: "time to settle" ≈ **1.2 s** vs classical guitar ≈ 2.8 s. Strongest partial is the 2nd (880 Hz). Report quality is low (it states the 440 Hz period as "about 9.1 ms") | measurement (non-peer-reviewed report) | confirmed (verbatim), low weight | 21 |
| G1 | **Pitch glide after the attack:** open strings drop **≈ 3 %** over a few hundred ms, from high to low. Tatakibachi B2: **3.5 % → 1.5 % within 100 ms**, then drifting toward **0.8 %** | measurement | confirmed | 18 |

**Quotes (verbatim; srcs 17 and 18 are our transcriptions of scanned pages):**
- R1 (src 18): 「三味線音の叩き撥におけるスペクトルは，700 Hz近辺を最大ピークとしており，低い周波数では−25 dB/octのroll offを示し，特に，最低音名B2の基音のスペクトルレベルは−41 dBにも達している。」 and 「基音のスペクトルレベルが，最大振幅を示す第5倍音に対して著しく小さい。」
- R1a (src 18): 「はじき奏法でのスペクトルエンベロープの最大ピークは1 kHz近辺にあり，そのバンド幅は他の奏法に比べて広く，また，すくい撥のスペクトルエンベロープは，800 Hzをピークとしており，高い周波数のスペクトルレベルが他の奏法のそれに比べて比較的大きい。」
- R2 (src 17): 「基本周波数700 Hzの皮の振動が175 Hzの弦の振動により振幅変調されているものと見なせる。」 Src 17 also cites Takazawa (1993, not opened): 「スペクトルは700 Hz付近に最大値を持つ」.
- R3 (src 8): 「さわりの有無に関わらず600〜1000Hz 付近にピークが立った。」 Conclusion 1: 「三味線の基本的な特性として、1000Hz 前後の音圧が最も高くなる。」 Src 14: 「さわりの有無に関わらず1000Hz 付近にピークが立った．」
- R4 (src 8): 「さらにさわりのある場合は3000〜5000Hz においてさわり無しの場合に比べてピークが高くなった。」 Src 14: 「さわりを付加することで3000Hz 以降の帯域で音圧が高くなる」 and 「弦のみの振動ではさわりありでは2000Hz まで均一に強いピークがみられ，さわり無しでは基音を最大として周波数が高くなるごとに音圧が下がる傾向がある」. Src 15: 「まずさわりをつけた状態では4000Hz、6000Hz 付近の帯域を中心に残響が長くなった。また、1000Hz 前後の帯域の残響も長くなることがわかった。」
- A1 (src 18): 「三味線音の立ち上がり時間は，すくい撥の音名B2, E3のそれが7 msec程度であることを除けば，1.5から5 msec程度の範囲内にある。」 Definition: 「波形の最大値を0 dBとしたとき，振幅が−20 dBから0 dBに達するまでの時間とした。」
- A2 (src 18): 「なお，タッチノイズの平均持続時間は，約21 msec，その振幅レベルは，全体の波形の最大値に対し−20から−26 dB程度である。」 Table 2 and Table 3 as tabulated above.
- A3 (src 17): 「その後約7 msの間，撥は弦を擦り続ける。」「弦と皮の接触状態は約20 ms持続する。」「音の立ち上がり後波形は約6 msの間に急激に減衰する。」「音の立ち上がり後約11 msの間に急激に減衰する。」 (bachi).
- A4 (src 17, Table 1): 「I 駒を通じての持続的振動 約1 kHz / II … 撥が弦を擦りながら皮に接触したことによる過渡的振動 約8 kHzまで / III 駒を通じての持続的振動 約700 Hz 撥が単独で皮に接触したことによる過渡的振動 約16 kHzまで / IV 駒を通じての持続的振動 約700 Hz」. 「撥を皮に接触させずに演奏した場合，音の立ち上がり直後に見られる約3 kHz以上の周波数成分は少ないことが分かる。」「②が関与する時間領域は，領域II及びIII…の，およそ25 msの間である。」
- A5 (src 17): 「一方撥が弦を擦っている位置は，駒と上駒（1の弦では駒とさわり溝の終端）の間の距離の約1/6の位置である。このため弦には開放弦の基本周波数の約6倍の振動周波数が発生することとなる。」 and 「1の弦では約800 Hz，3の弦では約1.6 kHzとなった。」
- A6 (src 17): 「撥の振動に含まれる周波数成分は，材質によって変化することが分かる。一方音に含まれる周波数成分は，撥の材質にほとんど依存しない。」
- A7 (src 18): 「振幅エンベロープの形状は，約2 msec程度の立ち上がり時間とし，また，その減衰は，約20 msecで−40 dBに減衰する指数関数型とした。」「両者の振幅の最大値の比率を夫々の音名で1:2, 1:0.9, 1:1とした時に，アタックノイズのない場合の合成音に比べて三味線音らしさが増大した。」「5 msecの立ち上がり時間を有し対数的に直線で立ち上がるエンベロープに修正する」.
- D1 (src 18): 「音高が高いほど減衰時間は短くなる傾向があり，また，奏法に関しては，叩き撥，消し撥の減衰時間が最も短く…ちなみに，叩き撥の音名A3では，−20 dBでの減衰時間は，270 msec程度である。」
- D2 (src 18): 「第1の平均的減衰率は，約−60 dB/secから−300 dB/sec以上の広い範囲を示し…倍音次数が大きいほど，また，音高が高いものほど平均的減衰率は大きくなる傾向がある。…第1の平均的減衰率は，第2のそれに比べて数倍程度大きい。」 Break: 「これを0.05から0.5秒の間とした」. Conclusion: 「減衰は，立ち上がり直後に早く，その後，やや遅いピアノ音に似た2段階的な減衰形状を呈する。この場合，高次倍音は低次のそれよりも早く減衰する。」
- D3 (src 19): 「1250Hz 以降は残響時間が周波数と共に指数関数的に短くなっている．」 and 「残響時間が3 秒近い400Hz や500Hz，2 秒近い1250Hz において…残響時間が0.5 秒ほどの5000Hz においては…」.
- D4 (src 8): 「図3 よりさわり有りの場合は無しの場合に比べて減衰が長くなり音を大きくする結果となった。」 Src 16: 「さわりが付いた音は0.3 sec 付近で一度大きくなり，その後減衰していく形をしている．」
- D5 (src 21): 「音が収束するまでの時間は約1.2sec であった。」 and 「基本周波数の440Hz よりも2 次高調波の880Hz 付近でスペクトルの強度が最も強い。」
- G1 (src 18): 「立ち上がり後100 msecまでの間に，約3.5%から1.5%への周波数変化があり，それ以後，0.8%に向けて緩やかな変動を示している。」「三味線音の立ち上がり後数百msecの間でのピッチ変動は，開放弦で3%前後と可成り大きな変化を示し，周波数は，時間軸上高い周波数から低いそれへと移行する傾向がみられる。」

**Verifier notes.**
- (i) D1 and D3 are **not comparable**. D1 is the waveform RMS falling to −20 dB after a real bachi stroke, on a nagauta shamisen in an anechoic room. D3 is band reverberation time after a wire-break on a suspended Kanagawa instrument.
- (ii) A7 is a *synthesis* setting that listeners judged more shamisen-like. It is not a measurement of the noise itself (A2 is).
- (iii) Srcs 17 and 18 are two independent labs (ETL/Tsukuba and Nippon Gakki). Both put the sustained radiated peak near **700 Hz** (R1, R2).
- (iv) The thesis-lab sources (8, 14, 15, 16) put it near 1000 Hz (R3). Those are finger plucks on a sponge (2012) or in a player's lap (2013); the recordings were not anechoic.

### N.5 Sawari and collision: shamisen/biwa data and transferable DSP

| # | Quantity | Value | Kind | Status | Src |
|---|---|---|---|---|---|
| S8 | Sawari on Chikuzen **biwa**: partials "6th to 20th and up" intensified *and* their durations elongated | partials 6–20+ | measurement (biwa) | **confirmed** (abstract and conclusion, read in full) | 6 |
| S9 | Fundamental/low partials said to sit **30–40 dB** below partials 6–12 under biwa sawari | 30–40 dB | — | **still unsupported.** Not stated anywhere in Taguti & Tohnai's text or tables (it could only be eyeballed from Fig. 5). NOT to be used in code. The nearest sourced facts: for biwa string 1, "A = A6" (the 6th partial is the largest, with and without sawari); for string 2, the 8th. For **shamisen**, R1 gives B2's fundamental at **−41 dB** re the 5th harmonic | 6, 11 |
| S8a | Biwa sawari grades by approximate surface width: S0 0 mm (none), S1 **0.7 mm**, S2 **1.5 mm**, S3 **3.0 mm** | measurement (biwa) | confirmed | 6 |
| S8b | Biwa string 1 (C#3, 138.6 Hz) spectral centroid (65.4–8372 Hz band): S0 **864.1**, S1 968.9, S2 1147.1, S3 **1203.6 Hz**. String 2: 946.4 / 938.3 / 1038.6 / 1024.5 Hz | measurement (biwa) | confirmed | 6 |
| S8c | Biwa string 1 RMS envelope (peak = 0 dB at t = 0), S0 vs S3: 0.2 s −8.5 vs −2.8; 0.4 s −10.6 vs −1.3; 0.6 s −15.2 vs −5.6; 1.0 s −27.2 vs −16.8; 2.0 s −47.1 vs −34.4 dB. "S3 has a slower build-up of energy as well as a slower decay" | measurement (biwa) | confirmed | 6 |
| S8d | Biwa partial durations above 1% of the maximum: S3 is "more than twice as large as those of S0 at 8 ≤ i ≤ 20 except i = 13 and 14". Partials 6 and up with sawari "grow at first slowly then decay undulatingly". Sawari effect "stronger on lower strings" | measurement (biwa) | confirmed | 6 |
| S8e | Shamisen (Ando 1996, cited by Taguti & Tohnai): mean decay rates are smaller with sawari "except in the 13th partial" | measurement (secondary) | **unsupported** (Ando 1996 book not opened) | 6 |
| T1 | Bridge contact law (tanpura) | one-sided, **exponent 1** (lossless), F_b = k_b⌊h_b − y_b⌋ | model | confirmed | 3 |
| T2 | Contact stiffness k_b (steel string on ivory) | **4.39×10⁸ N/m**, from k_b = ¼πE*w_b, w_b = 2.0 mm | model parameter | confirmed | 3 |
| T3 | String damping fit, 1 m steel tanpura string, r = 0.14 mm, C3 = 130.81 Hz | σ0 **0.6 s⁻¹**, σ1 **6.5×10⁻³ m/s**, σ3 **5.0×10⁻⁶ m³/s** | measurement-fitted (tanpura) | confirmed | 3 |
| T4 | Thread (soft termination) | k_c = w_c·10⁸ N/m, r_c = k_c·10⁻⁶ | model parameter | confirmed | 3 |
| T5 | String–bridge compression | **< 3×10⁻⁸ m** | simulation result | confirmed | 3 |
| T6 | Perceived envelope | grows over the initial **500 ms** | simulation result | confirmed | 3 |
| T7 | Sample rate | 44.1 kHz is "artificially strong" above **15 kHz**; **2× (88.2 kHz)** suffices | simulation result | confirmed | 3 |
| T8 | Stiffness needed for the buzz | with EI = 0 the precursor **disappears** | simulation result | confirmed | 3 |
| T9 | STK `Sitar` (Cook & Scavone): KS with no collision | delay jitter ×(1 + 0.05·noise); loop gain 0.995 + f·5e-7 (cap 0.9995); one-zero 0.01; ADSR 1 ms / 40 ms | code constant (hack) | confirmed | 4 |
| T10 | **Sawari model for biwa/shamisen (Taguti 2008):** a linear one-sided "sawari term" φ(ξ) = K0·ξ for ξ ≥ 0, where ξ is the lateral compression of the silk string against a rigid surface. The model is lossless | model | confirmed | 12 |
| T11 | Taguti 2008 sample problem: L = **0.8 m**, T = **38.4 N**; string L (low): ρ **1.5×10⁻³ kg/m**, r0 **0.72 mm**, K0 **2.0×10⁶ N/m²** (f0 100 Hz, c 160 m/s); string H: 0.375×10⁻³, 0.36 mm, 2.9×10⁶ (f0 200 Hz). Sawari length l = **4.17 mm** (5L/960), flat, at z = r0. Sawari "roughly 1/200 the length of an open string" | model parameters (from biwa silk data, ST3) | confirmed | 12 |
| T12 | Taguti 2008 results: HF oscillation builds up gradually; partials stay **nearly harmonic** on the sawari-free f0 with **no subharmonics**; spectral centroid rises and then undulates. A local **13–14 kHz hump** arises from the short sawari segment, with local frequencies fT = 9.6 kHz and fK = √(K0/ρ)/(2π) = 5.81 kHz (string L). Grids of 960–3840 points ran at **192–768 kHz** internal rates | simulation result | confirmed | 12 |
| T13 | Taguti 2008 preliminary coupled model: resonator of **two mass-dashpot-spring elements at 850 Hz and 1,900 Hz** (biwa, not measured) | model choice | confirmed | 12 |
| T14 | **Siddiq 2012 (sitar):** waveguide string with allpass-cascade dispersion (Rauhala–Välimäki). The colliding end segment runs as FD (Krishnaswamy–Smith coupling). Extra loss terms a = 2bd − 1, g = 1 − d with **b = 0.97, d = 0.00005**. Descending formants and partial-envelope complexity "can only be observed in dispersive models" | model + model parameters | confirmed | 9 |
| T15 | **Bilbao & Torin 2014 (fretboard):** power-law penalty F = K[η]₊^α with **K = 10¹⁵, α = 2.3** (barrier); finger Kf = 10¹⁰, αf = 2.3. Penetration **< 10⁻⁹ m**. **88.2 kHz**. Guitar string L 0.65 m, ρ 5.25×10⁻³ kg/m, T 60 N, E 2×10¹¹ Pa, r 0.43 mm, σ0 1.38, σ1 1.25×10⁻⁴ | model parameters (guitar) | confirmed | 10 |
| T16 | **Kokubun FD sawari sim (shamisen string 1):** 800 mm string, Δx 2 mm, Δt 0.01 ms, **c = 100 m/s** (so f0 ≈ 63 Hz, not a real pitch); obstacle 10 mm from the kamikoma, −0.21 mm vertical. Horizontal sweep 2–20 mm: effect strongest at **8–12 mm**. Farther obstacle → higher sawari-generated frequency and earlier onset. Lower obstacle → very slightly higher frequency. Damping d = 0.5 s⁻¹ | simulation settings/results (thesis; not calibrated) | confirmed | 15 |

**Quotes (verbatim):**
- S8 (src 6): "The sawari effect appears in two aspects: (1) to intensify the partials of 6th to 20th and up, and (2) to elongate their durations."
- S8a (src 6, Table 2): "S0 0 mm none / S1 0.7 mm slight / S2 1.5 mm moderate / S3 3.0 mm strong; †Approximate size."
- S8b (src 6, Table 4): "S0 864.1 S1 968.9 S2 1,147.1 S3 1,203.6"; "the open first string (C3♯, 138.6 Hz)".
- S8c (src 6, Table 3 rows): "0.20 −8.5 −2.8 / 0.40 −10.6 −1.3 / 0.60 −15.2 −5.6 / 1.00 −27.2 −16.8 / 2.00 −47.1 −34.4"; "The result implies that waveform S3 has a slower build-up of energy as well as a slower decay than S0."
- S8d (src 6): "specifically, more than twice as large as those of S0 at 8 ≤ i ≤ 20 except i = 13 and 14"; "the partials of 6th order and up at grade S3 do not decay monotonically, but grow at first slowly then decay undulatingly"; "though the sawari effect appeared stronger on lower strings". S9 context: "Apparently A = A6 for S0, as well as for S3"; string 2: "The 8th partial has the maximal amplitude among all the partials for both grades S0 and S3."
- S8e (src 6): "It is worth to cite the finding in the case of shamisen by Ando [5] that the mean decay rates become smaller (i.e., the durations become elongated) for its sawari tones than its sawari-less tones, except in the 13th partial."
- T10/T11 (src 12): "φ(ξ) = 0 (ξ < 0); K0ξ (ξ ≥ 0)"; "were chosen with common L = 0.8 m and T = 38.4 N"; Table 1 "L 1.5·10⁻³ 0.72·10⁻³ 2.0·10⁶ / H 0.375·10⁻³ 0.36·10⁻³ 2.9·10⁶"; "l = 4.17 × 10⁻³ m (5 × L/960, exactly)"; "roughly 1/200 the length of an open string, installed at the nut-side end".
- T12 (src 12): "every spectral component, existing from the beginning or newly appearing, is a nearly harmonic partial on the fundamental frequency of the associated sawari-free problem, and (iv) no subharmonics seem to appear"; "there is a spectral hump in the region 13–14 kHz"; "fK = √(K0/ρ)/(2π) = 5.81 kHz (string L) or 14.0 kHz (string H)".
- T13 (src 12): "the resonator consists of two mass-dashpot-spring elements whose natural frequencies are 850 Hz and 1,900 Hz".
- T14 (src 9): "Proposed values for the sitar string's model are b = 0.97 and d = 0.00005 to avoid a too fast decay."; "descending formants, as in the sound of the real instrument, can only be observed in dispersive models." Src 9 also says Taguti's earlier FD shamisen/biwa work neglected dispersion ("In all these works dispersion is neglected").
- T15 (src 10, Fig. 2 caption, checked on the rendered page): "The barrier collision parameters are K = 10¹⁵ and α = 2.3 … The sample rate is 88.2 kHz."; "it takes on values under 10⁻⁹ m".
- T16 (src 15): 「一の糸の長さを800mm の両端固定とし、距離刻みは2mm、時間刻みは0.01ms とした。波の伝搬速度は100m/s とした。」「さわり効果が最もよく出る距離は10mm 付近(8、10、12)であることもわかった。」「さわりの影響による弦振動の周波数を変化させたい場合にはさわり山の水平方向の位置を変化させる事が効果的である」.

The first pass's T-row quotes (src 3, src 4) still stand as written there. These leads were **not opened** this pass: Bilbao, Torin & Chatziioannou 2015; Chatziioannou & van Walstijn 2015; Kartofelev et al. 2013; Krishnaswamy & Smith 2003; Valette 1991/1995; Taguti 2007 (ICA Madrid, "Numerical simulation of vibrating string subject to sawari mechanism"); Taguti 2005 (ICSV Lisbon); Takazawa 1993 (ASJ MA-93-16); Kikkawa 1997; Ando 1971/1996. Issanchou et al. still not located.

### N.6 What the literature cannot give (this run)

- **The fundamental skin mode is unresolved.** One lab reports ~152 Hz (2011/2012) and ~766 Hz (2013) for a mounted skin (B5 vs B6). Only one skin damping value exists (ζ 1.02 %). Nothing distinguishes cat, dog or synthetic skins. No measurement of the front and back skins together as a coupled pair (only SEA, F5a).
- **No dō-with-skins modal data.** B3/B4 are the bare wooden frame. C1/C2 are the air cavity with rigid or board walls. The only whole-instrument modes (B1/B2) are low-frequency neck modes.
- **No scale length, bridge position, string tension or dō dimensions** for any standard shamisen from a measurement source. The only lengths are whole-string lengths (ST1), a simulation setting (T16: 800 mm) and the heike shamisen's overall size (S11 note). **No measured string tension** exists. Tension can only be back-computed from ST1c plus a scale length that no source gives.
- **No per-genre absolute pitch distribution.** Pitch is by 本 and singer (P4, P5). Only nagauta open strings are measured (P1 ≈ 131/175/262 Hz; P2 B2/E3/B3; P3 ≈ 150 Hz). Nothing is measured for gidayū or Tsugaru.
- **No bachi force, velocity or contact-stiffness data.** The strike is characterised only through timing, spectra and levels (A1–A7).
- **No shamisen sawari contact stiffness or groove geometry** beyond "a few millimeters" (F1a). Taguti's biwa silk K0 (ST3/T11) is the nearest measured value. The biwa 30–40 dB claim (S9) is still unsupported. Per-harmonic decay rates exist only as curves (D2 gives ranges).
- **The sources measure differently and conflict.** Spectral peak ~700 Hz (anechoic, bachi: srcs 17/18) vs ~1000 Hz (finger pluck, non-anechoic: srcs 8/14–16). Decay 270 ms to −20 dB (D1) vs 2–3 s band reverberation (D3). These are different quantities on different instruments, and no source reconciles them.

### N.7 Modelling implications (OUR SYNTHESIS, not sourced data)

1. **Sawari = a one-sided obstacle at the nut end of string 1, open string only.** Split the loop a few mm from the nut termination. T16's sweet spot is 8–12 mm on a sim string. Taguti uses a ~4 mm surface on 0.8 m, i.e. ~1/200 of the string. Keep dispersion in the loop (T8, T14) and oversample at least 2× (T7). For stiffness, silk against wood is orders of magnitude softer than steel on ivory. Taguti's measured biwa-silk K0 ≈ 2–3.6×10⁶ N/m² (per metre of contact) is the only silk figure, so start there rather than at the tanpura's 4.39×10⁸ N/m (point contact). Tune the rest by ear.
2. **Make "sawari depth" non-monotonic (F10, S8a–S8d).** Too little gives repeated swells. Correct gives a stepwise, prolonged decay with the energy/brightness swell near 0.3 s (D4), a centroid rise (biwa: +340 Hz, S8b) and a lift in the 3–5 kHz band (R4). Too much gives beating and a faster decay, and past a point the obstacle acts as the nut and the buzz disappears. A single "depth" knob should map to obstacle height *through* that sweet spot, not to a gain.
3. **Sympathetic sawari (F3)**, now measured. Keep an undamped open-string-1 loop with the obstacle and feed it from the bridge. Coincidences at string 1 harmonics 4/8 (string 2 in honchōshi) and 2/4/6/8 (string 3) make it buzz. String 3 couples more strongly.
4. **Bachi = three layered events, now with sourced timings (A1–A7):**
   - (i) A pre-onset **touch noise** ~20–30 ms long at −20 to −26 dB. Its band-limited components sit around 0.7–3.6 kHz and depend on the string (A2). It covers the ~7 ms bachi rub plus ~20 ms string–skin contact (A3).
   - (ii) At onset, a **skin-strike burst**: broadband up to ~16 kHz, collapsing within ~6 ms (A3/A4). The listening-test recipe is noise with a 2 ms rise, −40 dB by 20 ms, peak level ≈ the harmonic peak (A7).
   - (iii) The string, with attack ≤ 5 ms (A1). Its excitation point sits at ~1/6 of the string from the bridge (A5).
   A "no skin hit" articulation (kasumebachi/hajiki) drops (ii) and most of (i) (A2 Table 3, A4). Bachi material can be ignored for the radiated tone (A6).
5. **Body = a fixed filter that almost removes the fundamental.** The sourced shape is a broad radiated peak at 700–1000 Hz, with a −25 dB/oct slope below it, so the lowest fundamentals sit ~40 dB down (R1–R3). A bank could use a broad peak near 700 Hz, a cavity resonance near 1000 Hz (C1/C2), and a wood-frame mode near 570–630 Hz with Q ≈ 60–80 (B3/B4, computed Q). The skin mode stays "shape, not measurement" because of the B5/B6 conflict. The tanpura-style high-pass tilt in the first pass is now supported for the shamisen too, by R1.
6. **Decay and pitch.** Use two-slope, frequency-dependent loop loss: a steep first slope, several times slower afterwards, higher partials faster (D2). As a sanity target, an A3 tatakibachi note falls ~20 dB in ~270 ms (D1). Add a **pitch envelope**: open strings start ≈ 3–3.5 % sharp and settle within ~100 ms to ~1.5 %, then drift toward <1 % (G1). This comes from tension modulation and is cheap to fake.
7. **Genre presets.** Set the 1st-string pitch class by 本 (1本 = A … 4本 = C … 12本 = G#; P4 pitch classes, P5), in the octave where the measurements sit (nagauta string 1 ≈ 123–150 Hz; P1–P3). Do **not** use P4's Hz column. Apply S1–S3 for strings 2–3. Beyond that, presets can differ by bachi weight (F9), sawari depth and skin/body shape, all set by audition.

### N.8 Sources

| # | Citation | URL | Read directly |
|---|---|---|---|
| 1 | NICT, *Japanese-English Bilingual Corpus of Wikipedia's Kyoto Articles* v2.01, CLT00969 「三味線」 (jawiki-20090527), GitHub mirror | https://raw.githubusercontent.com/venali/BilingualCorpus/master/wiki_corpus_2.01/CLT/CLT00969.xml | **Yes** (first pass; curl of the GitHub mirror. Tertiary.) |
| 2 | Wikipedia, "Shamisen", rev. 283182194 (April 2009), snapshot in a GitHub dataset | https://raw.githubusercontent.com/deeppatel710/WikiDocumentClassification/master/textFiles/1c46718eee19e9b340d255619a0e282f.txt | **Yes** (first pass. Tertiary.) |
| 3 | van Walstijn, M., Bridges, J., & Mehes, S. (2016). "A real-time synthesis oriented tanpura model." *Proc. DAFx-16*. | https://github.com/sandormehes/sandormehes.github.io/blob/master/publications/dafx16/paper/dafx16.pdf | **Yes** (first pass; all 8 pages) |
| 4 | Cook & Scavone, STK `Sitar.h`/`Sitar.cpp` | https://github.com/thestk/stk/blob/master/src/Sitar.cpp | **Yes** (first pass) |
| 5 | Lasickas, T. "Shamisen-JUCE", `MainComponent.cpp` | https://github.com/titas2001/Shamisen-JUCE | **Yes** (first pass; git clone) |
| 6 | Taguti, T., & Tohnai, Y. (K.) (2001). "Acoustical analysis on the sawari tone of Chikuzen biwa." *Acoust. Sci. & Tech.* 22(3), 199–207. | https://www.jstage.jst.go.jp/article/ast/22/3/22_3_199/_pdf | **Yes** (curl from J-STAGE, all 9 pages, text layer) |
| 7 | Hamdan, S., Sinin, A. E., Said, K. A. M., Musib, A. F., & Duin, E. M. A. (2026). "Sound analyses of a Japanese traditional stringed instrument of Okinawa: The shamisen." *BioResources* 21(1), 918–938. DOI 10.15376/biores.21.1.918-938 | https://bioresources.cnr.ncsu.edu/wp-content/uploads/2025/12/BioRes_21_1_918_Hamdan_SSMD_Sound_Analysis_Japanese_Traditional_String_Instrument_24804.pdf | **Yes** (curl, full PDF). Low internal consistency; see S11 |
| 8 | Kokubun, K., Oe, K., Iwahara, M., & Minorikawa, G. (2012). "1607 三味線の音響・振動特性 (Sound and Vibrational of SHAMISEN)." *JSME Kanto Branch 18th General Meeting*, pp. 479–480. DOI 10.1299/jsmekanto.2012.18.479. **Corrected** from "JSME D&D 2012" | https://www.jstage.jst.go.jp/article/jsmekanto/2012.18/0/2012.18_479/_pdf | **Yes** (curl from J-STAGE; scanned, read from rendered images). CiNii HTML was WAF-challenged; the metadata came from the CiNii JSON API |
| 9 | Siddiq, S. (2012). "A physical model of the nonlinear sitar string." *Archives of Acoustics* 37(1), 73–79. DOI 10.2478/v10168-012-0010-y | https://acoustics.ippt.pan.pl/index.php/aa/article/download/129/124/260 | **Yes** (curl, all 7 pages) |
| 10 | Bilbao, S., & Torin, A. (2014). "Numerical simulation of string/barrier collisions: the fretboard." *Proc. DAFx-14*, Erlangen. | https://www.dafx14.fau.de/papers/dafx14_stefan_bilbao_numerical_simulation_of_s.pdf | **Yes** (curl, all 8 pages; caption checked on the rendered page) |
| 11 | Crack-Pantelimon/bespoke-mcp-data-pack, `shamisen/RESEARCH.md` (third-party, unverified) | https://github.com/Crack-Pantelimon/bespoke-mcp-data-pack | Yes (first pass), **secondary, unverified**. Used only for the S9 flag |
| 12 | Taguti, T. (2008). "Dynamics of simple string subject to unilateral constraint: A model analysis of sawari mechanism." *Acoust. Sci. & Tech.* 29(3), 203–214. DOI 10.1250/ast.29.203 | https://www.jstage.jst.go.jp/article/ast/29/3/29_3_203/_pdf | **Yes** (curl, full PDF; Tables 1 and 5 checked on the rendered page) |
| 13 | (number unused, to keep source numbers stable) | — | — |
| 14 | Oya, T., Fujitsuka, K., Iwahara, M., & Minorikawa, G. (2013). "806 三味線の振動音響特性." *JSME Hokuriku-Shinetsu Branch 50th General Meeting* (Fukui, 9 Mar 2013). | https://www.jstage.jst.go.jp/article/jsmehs/2013.50/0/2013.50_080601/_pdf | **Yes** (curl, 2 pages, text layer) |
| 15 | Kokubun, K. (2012). 『三味線の振動と音響 (Vibration and Sound of SHAMISEN)』. Master's thesis, Hosei University (supervisors Minorikawa, Iwahara). The repository metadata names "國吉, 大吾 / KUNIYOSHI, Daigo", but the title page reads 國分圭介 (Keisuke Kokubun), 10R1119 | https://hosei.ecats-library.jp/da/repository/00008389/%E5%9C%8B%E5%88%86%E5%9C%AD%E4%BB%8B.pdf | **Yes** (curl, 157 pp.; ch. 1–2, 4–10 read; Tables 4-1 to 4-4 and 10-1 to 10-3 checked on rendered pages). Student thesis |
| 16 | Oe, K. (2011). 『三味線のさわり機構による音響・振動特性』. Master's thesis, Hosei University | https://hosei.ecats-library.jp/da/repository/00007716/%E5%A4%A7%E6%B1%9F%E8%80%95%E5%8F%B8.pdf | **Yes, partially** (curl, 121 pp.; §2 recordings read; the rest skimmed by keyword). Student thesis |
| 17 | Horiuchi, R., Kikuchi, T., Sato, S., & Miura, H. (1995). "三味線の発音機構の解析：楽器音の立ち上がり部の波形と皮・駒・撥各部の振動波形の比較." *J. Acoust. Soc. Jpn.* 51(9), 690–697. DOI 10.20697/jasj.51.9_690 | https://www.jstage.jst.go.jp/article/jasj/51/9/51_KJ00001450787/_pdf/-char/ja | **Yes** (curl; scanned, all 8 pages read from rendered images) |
| 18 | Ando, S., & Yamaguchi, K. (1983). "三味線音の音響的性質について (Considerations on physical characteristics of samisen tones)." *J. Acoust. Soc. Jpn.* 39(7), 433–443. | https://www.jstage.jst.go.jp/article/jasj/39/7/39_KJ00001453403/_pdf/-char/ja | **Yes** (curl; scanned, all 11 pages read from rendered images; figure curves not digitised) |
| 19 | Suzuki, Y., Matsunaga, M., Ito, N., Yamazaki, T., Nakamura, H., Tanaka, T., & Ito, Y. (2016). "129 三味線の振動エネルギー伝搬解析 (Vibrational energy propagation analysis on Shamisen)." JSME Environmental Engineering Symposium 2016 (J-STAGE jsmeenv 2016.26). | https://www.jstage.jst.go.jp/article/jsmeenv/2016.26/0/2016.26_129/_pdf | **Yes** (curl, 4 pages, text layer) |
| 20 | Lasickas, T., Willemsen, S., & Serafin, S. (2021). "Real-time implementation of the shamisen using finite difference schemes." *Proc. SMC 2021*, pp. 100–107. | https://zenodo.org/records/5042901 | **Yes** (Zenodo API file download, all 8 pages) |
| 21 | Tokushima, T. (2010). 「音響解析による和楽器の特徴について」. 日本海学グループ研究支援事業 report (not peer-reviewed). | https://www.nihonkaigaku.org/library/group/tokushimadata.pdf | **Yes** (curl; shamisen section read). Low weight |
| 22 | Wikipedia (live): en "Shamisen" rev. 1360859905; ja 「三味線」 rev. 110697170; ja 「津軽三味線」 rev. 110706013 | https://en.wikipedia.org/wiki/Shamisen · https://ja.wikipedia.org/wiki/三味線 · https://ja.wikipedia.org/wiki/津軽三味線 | **Yes** (curl, parsed HTML. Tertiary) |


---

## 2. Oud (Arabic / Turkish short-necked fretless lute, bowl-back soundbox)

**Sourcing conditions this pass (second pass, 2026-09-27).** Scholarly hosts were reachable this time. Every source marked "read directly" in N.6 was downloaded and read in full, not taken from a search summary. The PDFs were read in one of two ways. DAGA 2018 was read with a direct file read that rendered both the text and the page images, so its figures were seen. Meccanica 2026, the Erkut 2002 thesis with its ICMC 2001 paper, Nia 2015 and Ziyagil 2021 went through `pdfminer.six` text extraction in a throwaway scratch venv. Erkut's Table 3.1 was cross-checked in two further ways: against the repository's own text rendition, and by the text-box coordinates on the page. Wikipedia was read as raw wikitext (`index.php?title=Oud&action=raw`, revision 1376603830, 2026-09-25).

**Still not opened, and not routed around:**
- **Bot-gated:** `dspace.mit.edu` (the sound-hole thesis) serves an AWS WAF CAPTCHA page ("verify that you're not a robot"). `worldscientific.com` serves a Cloudflare "Just a moment..." challenge. ResearchGate returns 403, per the brief; it was not retried. academia.edu downloads need a login, so they were not attempted.
- **Policy-blocked:** `salamuzik.com` returned `EGRESS_BLOCKED`.
- **Rate-limited:** `api.openalex.org` answered two DOI lookups, then exhausted its shared daily budget.
- **Disclosure:** three retailer pages (arabinstruments, tapadum, sultaninstrument) were fetched with a generic browser User-Agent header. None of them presented a challenge, and nothing was solved or evaded.

**Headline results of this pass:**
1. The eight peaks (31–347 Hz) are now **confirmed verbatim from DAGA 2018 (#1)**. They are **free–free modes of a bare, unassembled soundboard**, with no bowl, no air cavity, no strings and no bridge. **None of them is an air/Helmholtz mode, and none belongs in a body resonator bank.** The first pass's "candidate A0" labels are corrected.
2. A new, directly read source gives **the oud's lowest radiated body resonances with Q**: Erkut 2002 (#14), Table 3.1, a Turkish ud measured strung in an anechoic chamber. The values are **113 Hz, Q 9.91** and **182 Hz, Q 10.06**. The source does not say which one is the air mode.
3. A second new source, Meccanica 2026 (#15), gives experimental modes of an **assembled Arabic oud** at **142 / 226 / 242 / 280 Hz**, with no Q.
4. **Turkish tuning is resolved.** The two snippet forms were never one contradictory tuning. They are two distinct named tunings: Bolahenk (C#2 F#2 B2 E3 A3 D4) and a common variant (D2 A2 B2 E3 A3 D4). A peer-reviewed synthesis paper (#13) used the variant.
5. **Scale length is confirmed:** 610–620 mm for the Arabic oud and 585 mm for the Turkish oud.
6. **Still no source anywhere** for course detuning, string t60 or partial decay, or inharmonicity B on the oud.

**Status legend.** This follows §0 and the 2026-09-25 preamble:
- `confirmed`: opened directly, and the quoted text matches verbatim.
- `corrected`: opened directly, and the source says something different from what the first pass recorded. The fix is stated in the row.
- `unsupported`: seen only in a search snippet, an abstract or a secondary mention, or the source could not be opened. **Unsupported values are NOT to be used in code.**
- `computed`: this pass's own arithmetic, not literature.

Two further caveats appear in the tables:
- *wrong instrument*: the value is confirmed but belongs to a European lute, not an oud.
- *not citable*: the page was read directly, but it is not a citable measurement, for example student coursework. Such values are treated as unsupported for code.

### N.1 Body: soundbox (qas'a) and soundboard (wajh) modes

**N.1a Bare soundboard, free–free (DAGA 2018, #1).** These are NOT body resonances. Do not put them in a resonator bank.

| # | Label (as source gives it) | Hz | Relative strength | Q / decay | Status | Src |
|---|---|---|---|---|---|---|
| O1 | Plate mode (0,2) of a bare Turkish-oud soundboard, free–free | 31 | Barely visible in Fig. 5 (by eye) | not reported | **corrected**: Hz confirmed; the identity is a bare-plate mode, not a body or suspension mode | 1 |
| O2 | Plate mode (1,1), same board | 51 | Barely visible (by eye) | not reported | **corrected** (identity) | 1 |
| O3 | Plate mode (0,3), same board | 69 | Barely visible (by eye) | not reported | **corrected** (identity) | 1 |
| O4 | Plate mode (1,2), same board | 115 | About 0.35 of the maximum in the normalized FRF (by eye) | not reported | **corrected**: *not* an air/Helmholtz mode. There was no cavity. | 1 |
| O5 | Plate mode (0,4), same board | 123 | About 0.8 of the maximum (by eye) | not reported | **corrected**: *not* an air-coupled mode | 1 |
| O6 | Plate mode (1,3), same board | 198 | Highest peak in Fig. 5 (by eye) | not reported | **corrected** (identity) | 1 |
| O7 | Plate mode (0,5), same board | 292 | Not clearly visible in Fig. 5. Coherence (Fig. 6) dips sharply in narrow bands near 260–300 Hz (by eye). | not reported | **corrected** (identity; lowest confidence of the eight) | 1 |
| O8 | Plate mode (2,3), same board | 347 | About 0.15 of the maximum (by eye) | not reported | **corrected** (identity) | 1 |

Conditions of the N.1a test board, all confirmed from #1:
- Borçka spruce, uniform 2.0 mm, 490 × 360 mm, seven fan braces.
- Held in "free boundary conditions" on three soft foam supports.
- Excited with an impact hammer at 317 points, with the response taken at one accelerometer.
- Measured over 0–350 Hz at 1.22 Hz resolution. Each FRF is the average of three taps.

The "by eye" amplitudes are this pass's reading of the plotted Fig. 5, not text. They come from one response point on an unloaded plate and must not be used as gains.

**N.1b Complete instruments.**

| # | Label | Hz | Relative strength | Q / decay | Status | Src |
|---|---|---|---|---|---|---|
| O12 | Turkish ud, lowest body resonance f_B1. Identity (air vs plate) **not stated**. Measured strung, radiated sound 1 m above the top plate, anechoic. | **113** | "most prominent" mode; the synthesis extracted it and resynthesized it as a separate resonator | **Q = 9.91** → BW ≈ 11.4 Hz, t60 ≈ 0.19 s (`computed`, t60 = ln(1000)/π · Q/f) | **confirmed** | 13, 14 |
| O13 | Turkish ud, second body resonance f_B2. Identity not stated. Same measurement. | **182** | not reported | **Q = 10.06** → BW ≈ 18.1 Hz, t60 ≈ 0.12 s (`computed`) | **confirmed** | 14 |
| O14 | Arabic oud, experimental Mode 1 (roving-hammer EMA on the assembled instrument, response near the bridge). The paper's FE counterpart is called the "fundamental breathing mode". | **142** | Mode 1 is the paper's "anchor" mode. No measured amplitudes are given in text. | not reported | **confirmed** | 15 |
| O15 | Arabic oud, experimental Mode 2 | **226** | not reported | not reported | **confirmed** | 15 |
| O16 | Arabic oud, experimental Mode 3 | **242** | not reported | not reported | **confirmed** | 15 |
| O17 | Arabic oud, experimental Mode 4 | **280** | not reported | not reported | **confirmed** | 15 |
| O18 | Arabic oud, additional FRF peak | **~360** ("near 360 Hz") | not reported; missed by the softest hammer tip | not reported | **confirmed** (approximate as stated) | 15 |
| O19 | Oud (type unstated): "Primary Air Mode" and "Primary Wood Mode", found by damping the strings and striking the bridge | ⩰150 (air), ⩰220 (wood) | "Acoustic radiation is dominated by the lowest air and lowest wood modes" | not reported | **unsupported** (*not citable*: student course wiki. Its own table lists G3 = 199 Hz and A3 = 203 Hz; 12-TET values are 196.0 and 220.0 Hz, `computed`.) | 17 |

**N.1c FE predictions and other instruments.**

| # | Label | Hz | Relative strength | Q / decay | Status | Src |
|---|---|---|---|---|---|---|
| O9 | FE coupled-model SPL resonances, soundboard 2.1 mm | 340, 900 | snippet: "amplitude of acoustic pressure substantially increases" | not reported | **unsupported** (source still unreachable: ResearchGate 403) | 2 |
| O10 | FE SPL resonance, soundboard 1.7 mm | 1040 | snippet: "increases significantly" | not reported | **unsupported** | 2 |
| O11 | FE SPL resonance, soundboard 2.9 mm | ~700 | snippet: "strong pitch frequency" | not reported | **unsupported** | 2 |
| O20 | FE (ANSYS) Arabic oud, bowl and neck fully fixed, no air coupling in the modal step: modes 1–4 braced / unbraced | 140/210/239/303 braced; 117/182/204/275 unbraced | not a measurement | — | **confirmed** as FE output. Not for code: a comparative model with fixed boundaries. | 15 |
| L1 | *European lute* (Firth 1977): Helmholtz air mode | 132 | — | not reported | **unsupported**. Now a verbatim *secondary* mention in #14, not only a snippet. The primary (#6) was not opened. *Wrong instrument.* | 6 via 14 |
| L2 | *European lute* (Firth 1977): top-plate resonance(s) | 304 (secondary, #14). 395 and 602 were seen in a snippet only. | — | not reported | **unsupported** (*wrong instrument*) | 6 via 14 |
| L3 | *Renaissance tenor lute* (Erkut): lowest two body resonances, same set-up as O12/O13 | 144, 356 | — | Q = 10.63, 8.97 | **confirmed** (*wrong instrument*; context only) | 13, 14 |
| G* | Guitar in the same Erkut set-up, as a calibration reference | 100, 204 | "monopoles" | Q = 15.06, 12.90 | **confirmed** (context only) | 14 |

**Quotes (verbatim):**
- **O1–O8, eight peaks (#1, p. 924):** "Totally eight peaks were observed corresponding to different resonance frequencies; 31 Hz, 51 Hz, 69 Hz, 115 Hz, 123 Hz, 198 Hz, 292 Hz and 347 Hz."
- **O1–O8, mode labels (#1, Fig. 7 captions):** "(0,2) 31 Hz (1,1) 51 Hz (0,3) 69 Hz (1,2) 115 Hz (0,4) 123 Hz (1,3) 198 Hz (0,5) 292 Hz (2,3) 347 Hz". The (m, n) notation is explained as: "“m” is the number of transverse half-waves and “n” is the number of longitudinal half-waves on the soundboard."
- **O1–O8, bare board, not a body:** "Resonance frequencies and mode shapes were obtained for free-free boundary conditions." / "It was supported by three soft foam elements underside." / "The content of this study involves only the vibrational behaviour of the soundboard itself. Next step will be the investigation of the coupled modes of the soundboard and the air cavity of body after completing the instrument assembly."
- **Attribution:** the snippet text "The soundboard has a thickness varying between 1.5-2.0 mm and seven fan braces..." is also verbatim in #1, p. 923. The attribution question is closed: the eight numbers are DAGA 2018's. Whether #3 (World Scientific) *also* reports them remains unknown, because it is still unreadable.
- **O12/O13, method (#13, §3.1):** "We conducted a series of acoustic measurements on the ud and lute bodies, and identified the most prominent modes (f1 = 113 Hz for the ud, and f1 = 144Hz for the lute)." / "The a parameters of the notch filter are obtained by analyzing the frequency and the bandwidth of the body resonance in the excitation signals."
- **O12/O13, values (#14, Table 3.1, "The lowest body resonances of the plucked string instruments analyzed in this thesis"):** row "Ud 113 9.91 182 10.06", under the columns "fB1 (Hz) Q1 fB2 (Hz) Q2".
- **O12/O13, conditions (#14):** "The measurements were conducted in an anechoic chamber." / "In every measurement reported in this thesis, the sound recordings were made 1 m above the top plate of the corresponding instrument". Only one string was excited: "only one string is set into motion while all the others are kept damped".
- **O12/O13, identity:** no sentence in #13 or #14 labels 113 or 182 Hz as air or plate. The only labelling nearby is for the guitar: "the lowest body resonances of the guitar are monopoles around 102 and 204 Hz".
- **O14–O17 (#15, Table 3):** "Experimental ... Mode 1 f1 : 142 Hz Mode 2 f2 : 226 Hz Mode 3 f3 : 242 Hz Mode 4 f4 : 280 Hz".
- **O14–O17, set-up (#15, Fig. 4):** "Experimental modal-analysis setup, showing the Arabic oud". The response point was "the region near the bridge". The FE counterpart is described in Fig. 11: "Mesh convergence of the first modal (breathing-mode) frequency ... converged value (≈ 140 Hz)". The source does not say whether the tested oud was strung.
- **O18 (#15, §5.1):** "the ultra-flexible red tip missed one peak near 360 Hz".
- **O20 (#15, §5.3):** the modal step "neglects both the internal damping of the wood and the coupling between the soundboard and the enclosed air cavity". From §5.2: "the bowl and the neck were fully fixed (X = Y = Z = 0)".
- **O19 (#17):** "Primary Air Mode |⩰150Hz" / "Primary Wood Mode |⩰220Hz" / "The cavity and body resonances are measured by damping the strings and striking the bridge."
- **L1/L2 (#14, secondary):** "In his study of the lute, Firth identified the resonance around 132 Hz as the Helmholtz air mode and the resonance around 304 Hz as the resonance of the top plate." Firth is cited in #14 as "I. Firth, “Some measurements on the lute,” Catgut Acoust. Soc. Newsletter, vol. 27, p. 12, 1977."
- **L3 (#14):** "The measured lute in [P7] was smaller in size compared to Firth’s lute, and its resonances are found around 144 Hz and 356 Hz."
- **Soundholes (#16, violin/lute/guitar context, not an oud measurement):** "the purely circular sound holes of classical guitars and the intricate sound hole rosettes of lutes ... have negligibly different resonance frequencies (within a quarter tone) and air resonance powers (within roughly 10%) for fixed outer perimeter length." Fig. 10 of #16 includes an "oud without rosette" point, but only as a normalized theory/experiment ratio. **No oud Hz is given.** The first pass's MIT-thesis snippet ("less than a semitone") remains unsupported, because the thesis is still gated.
- **Three soundholes:**
  - #1: "There are three sound holes on the soundboard arranged in triangular position." Fig. 2 carries the dimension labels "85 mm" and "42 mm" (read from the figure).
  - #13: "The soundboard has three latticed sound holes; one large and two small ones."

### N.2 Courses: paired unison strings and detuning

| # | Item | Value | Status | Src |
|---|---|---|---|---|
| C1 | Course layout | 11 strings in 6 courses (5 unison pairs + 1 single bass). Some models have 5 or 7 courses (10 or 13 strings). | **corrected**. The substance is confirmed. The snippet wording ("eleven strings arranged as five double courses plus one single bass string...") is **not** on the current Wikipedia page, and its origin is unknown. | 1, 8, 13, 15 |
| C2 | Measured detuning or beat rate within a course | **no source found**. #13's model has "a coupling matrix that controls the sympathetic vibration of the strings", but publishes no coupling or detune values. #14 measured one string at a time with the others damped. | n/a | 13, 14 |
| C3 | "a two-cent error as a slow pulse" | 2 cents | **confirmed verbatim**, but it is tuning-app guide text, **not a measurement and not a detune value**. Do not use it as a parameter. | 9 |

**Quotes (verbatim):**
- **C1:**
  - #1: "Commonly, this instrument has 11 strings grouped in 6 courses."
  - #8: "usually with 11 strings grouped in six courses, but some models have five or seven courses, with 10 or 13 strings respectively."
  - #13: "The Turkish ud has five courses tuned in unison, and a single bass string used as chanterelle."
  - #15 garbles the layout: "11 strings, arranged in 5 or 6 courses (pairs) plus one single bass string".
- **C3 (#9):** "the dial cannot judge a unison but your ear can hear a two-cent error as a slow pulse." The same page says, qualitatively, that a pair's reading can drift "as one string decays faster than the other". That is a tuner-behaviour remark, not data.

### N.3 Tuning, strings, risha, neck, dimensions

| # | Item | Value | Status | Src |
|---|---|---|---|---|
| T1 | Arabic tuning, low → high | C2 F2 A2 D3 G3 C4 ("Many current Arab players"). Older pattern: D2 G2 A2 D3 G3 C4. "Modern Arabic": F2 A2 D3 G3 C4 F4. | **confirmed** (encyclopedic) | 8 (also 18) |
| T2a | Turkish **Bolahenk** tuning, low → high | C#2 F#2 B2 E3 A3 D4 | **corrected**: this is one of *two* distinct tunings, not a contradiction. Confirmed in #8. #18 renders it "C 2 F 2 B2 E3 A3 D4", with the sharps apparently lost in its HTML. | 8, 18 |
| T2b | Turkish **variant** (attributed to Şerif Muhiddin Targan) | D2 A2 B2 E3 A3 D4 | **confirmed**. #18 calls it "A common variation". #13, a peer-reviewed paper, used it: "D1, A1, B1, E2, A2, D3" in its own octave labels, which equals D2…D4 in scientific pitch notation (`computed`, see below). | 13, 18, (19) |
| T2c | "one whole step higher than Arabic" | Wikipedia: "usually (and partly) tuned one whole step higher" | **corrected**. The qualifier "partly" matters. Against Arabic C2 F2 A2 D3 G3 C4, Bolahenk's top four courses are +2 semitones and its bottom two are +1 (`computed`). | 8 |
| T3 | String materials | "silk, nylon, copper and/or silver bound wire" (#8). "The two treble courses are made of nylon and the others of metal wound with fine silk or nylon thread" (#13, Turkish ud). The snippet's "rectified" nylon and nickel windings remain unsupported (#11 is egress-blocked). | **confirmed** (#8, #13); snippet parts **unsupported** | 8, 13, 11 |
| T4 | String tension | "a working tension of 3.4 Kg each string" | **confirmed**, as one manufacturer's spec for its own set, not a survey. 11 × 3.4 = 37.4 kg (`computed`). A secondary review gives "yaklaşık 52 kg" total (Açın 2001, cited in #21): **unsupported** (secondary). | 12, 21 |
| T5 | Risha / mızrap | "traditionally made from an eagles feather. Although today, the risha is most commonly made from plastic, and less often from tortoise shell or filed down animal bone." The snippet's "long, flexible plectrum ... cut from a quill" was **not found** on the page read. A size of 100–180 × 6–10 mm appears in #17 only (*not citable*). | **confirmed** (the #8 sentence); the rest **unsupported** | 8, 17 |
| T6 | Neck | fretless | **confirmed** | 1, 8, 13, 15 |
| T7 | Scale length | Arabic **610–620 mm**; Turkish **585 mm**. Zenne (small) oud 55–57 cm. | **confirmed** (was "no source found") | 1, 8 |
| T8 | Soundboard | 1.5–2.0 mm thick, seven fan braces "parallel to each other and perpendicular to the string alignment". Spruce or cedar top; walnut, mahogany, maple, rosewood or wenge body. | **confirmed** | 1 |
| T9 | Turkish vs Arabic build | Turkish ouds are "more lightly constructed than Arabian with an unfinished sound board, lower string action and with string courses placed closer together", with a "brighter timbre". Arabian ouds are "larger". | **confirmed** (encyclopedic, qualitative) | 8 |

**Quotes (verbatim):**
- **T1 (#8):** "a common older pattern of tuning the strings is (low pitch to high): D2 G2 A2 D3 G3 C4" / "Many current Arab players use this tuning: C2 F2 A2 D3 G3 C4 on the standard tuning instruments, and some use a higher pitch tuning, F2 A2 D3 G3 C4 F4, known as the Modern Arabic Tuning."
- **T2a (#8):** "In the Turkish tradition, the "Bolahenk" tuning, is common, (low pitch to high): C#2 F#2 B2 E3 A3 D4 on instruments with single string courses or C#2, F#2 F#2, B2 B2, E3 E3, A3 A3, D4 D4 on instruments with courses of two strings. The C2 and F2 are actually tuned 1/4 of a tone higher than a normal c or f in the Bolahenk system."
  - **Caveat:** this last sentence is internally unclear. A quarter tone above C2 is about 67.3 Hz, while C#2 is about 69.3 Hz (`computed`). So the page does not settle whether Bolahenk's two bass courses sit a semitone or a quarter tone above C and F. Treat those two courses as uncertain between the two.
- **T2b (#18):** "Turkish tuning can be of different types and the most popular among them is Bolahenk’ tuning ... Its notation is described below: C 2 F 2 B2 E3 A3 D4 A common variation of this tuning is: D2 A2 B2 E3 A3 D4 . This tuning technique was practiced and perfected by a famed Oud player named: Serif Muhiddin Targan."
  - #13: "The tuning used in this study is D1, A1, B1, E2, A2, D3."
  - #19, a retailer, conflates the two: "D2 A2 B2 E3 A3 D4 is a popular version of this tuning".
  - #20, a retailer, lists "C♯ – F♯ – B – E – A – D" as "From 1st string – the thinnest – to 6th string – the thickest". That reverses string order against every other source, so it is **disregarded as garbled**.
- **T2b, octave convention (`computed`, from #13's own numbers).** #13 labels the Renaissance lute's top course "G3" and reports "first string, open position, f0 = 389.9 Hz". 389.9 Hz is G4 in scientific pitch notation, 9 cents flat. The same string at "third fret, f0 = 464 Hz" fits, since 389.9 × 2^(3/12) = 463.7 Hz. So #13's octave numbers are one below scientific pitch, and its ud "D1 … D3" is D2 … D4.
- **T4 (#12):** "The sets use a working tension of 3.4 Kg each string." Also: "Until the mid-20th century the top three strings of Ouds were mounted exclusively in gut".
- **T7:**
  - #1: "Arabian ouds have larger body than their Turkish counterparts where they have a scale length of 610 - 620 mm in comparison to the 585 mm scale length for Turkish ones."
  - #8: "Arabian ouds have a scale length of between 61 cm and 62 cm in comparison to the 58.5 cm scale length for Turkish." / Zenne: "It usually has a scale length of 55–57 cm".
- **T8 (#1):** "Its soundboard has a thickness varying between 1.5-2.0 mm and seven fan braces are placed on the inner side parallel to each other and perpendicular to the string alignment".

**Open-string frequencies (`computed`, 12-TET, A4 = 440 Hz; not literature):**

| Tuning | Pitches (Hz) |
|---|---|
| Arabic | C2 65.41 · F2 87.31 · A2 110.00 · D3 146.83 · G3 196.00 · C4 261.63 |
| Arabic, older | D2 73.42 · G2 98.00 · A2 110.00 · D3 146.83 · G3 196.00 · C4 261.63 |
| Bolahenk | C#2 69.30 · F#2 92.50 · B2 123.47 · E3 164.81 · A3 220.00 · D4 293.66 (bottom two uncertain, see T2a caveat) |
| Turkish variant | D2 73.42 · A2 110.00 · B2 123.47 · E3 164.81 · A3 220.00 · D4 293.66 |

The confirmed Turkish-ud body mode at 113 Hz (O12) sits 47 cents above the A2 course and 153 cents below the B2 course (`computed`).

### N.4 Sustain, decay, timbre, inharmonicity

| # | Item | Value | Status | Src |
|---|---|---|---|---|
| D1 | String t60, per-partial decay, or loop-filter gains for the ud | **not published**. #13 fitted biquad loop filters to ud and lute recordings, but its only plotted example is a *lute* tone. | n/a | 13 |
| D2 | Inharmonicity B for oud strings | **no source found** | n/a | — |
| D3 | Fretless glissando: extra damping | qualitative: the finger termination is lossy, so the loop gain is lowered during a slide. Unlike the fretted lute, there is no re-excitation. | **confirmed** (mechanism and modelling choice; no number) | 13 |
| D4 | Body-mode decay | 113 Hz: t60 ≈ 0.19 s. 182 Hz: t60 ≈ 0.12 s. | **computed** from the confirmed Q (O12/O13) | 14 |

**Quotes (verbatim, #13 §3.3):**
- "the ud is a fretless one. This structural difference manifests itself in the measured glissandi regimes."
- "In this case, the re-plucking is absent since the instrument is fretless. The increased decay of energy is due to lossy termination of the string by the flesh of the finger, and it is simulated by lowering the loop filter gain during the synthetic glissando."
- "Since the glissandi are the backbones in ud playing technique, the fundamental frequency trajectories and decay characteristics of the recorded samples provide the templates for a realistic simulation of the glissandi."

**Loop-filter method (#13 §3.2).** This is a method, not an oud value. The decay-rate function σ(ω) is "approximate[d] ... by low-order algebraic polynomials", with the order chosen by "analytical model-order selection criteria".

### N.5 What the literature cannot give (as of this pass)

- **The air/Helmholtz mode of an oud is still unidentified in any citable source.** DAGA 2018 had no cavity. Erkut reports 113 and 182 Hz with Q but does not say which one is air-dominated. Meccanica's modal FE ignores the air coupling, and its experimental table does not label mode types. The only explicit "air mode" figure (about 150 Hz) comes from a student wiki (O19).
- **Only one oud has a measured Q:** Erkut's single Turkish ud, measured strung, which gives Q ≈ 10 for both of its lowest modes. The Arabic oud in #15 has Hz but no Q. No oud has measured gains or relative levels in text, and nothing was measured above 182 Hz *with* Q.
- **Turkish (113/182 Hz, radiated, strung) and Arabic (142/226/242/280 Hz, structural EMA) are different instruments measured differently.** They are not a like-for-like comparison, and neither one is "the oud".
- **Course detuning, beat rates and the prompt/aftersound decay** of oud double courses are unmeasured in anything found. The one synthesis paper that models string coupling (#13) publishes no values.
- **String decay (t60 per partial) and inharmonicity B** remain unpublished for the oud.
- **Where the risha strikes the string** (the pluck position as a fraction of the scale) was not found.
- **Bolahenk's bass courses are ambiguous** between a semitone and a quarter tone above C/F (T2a caveat).
- **Still unread:**
  - #2 and #5 (ResearchGate).
  - #3 (World Scientific, Cloudflare challenge).
  - #7 (MIT DSpace, WAF CAPTCHA). The related open paper #16 was read instead, but it gives no oud Hz.
  - Firth 1977 primary (#6).
  - Değirmenli's MSc (2014) and PhD (2018) and his journal papers on oud body modes, which appear to contain measured natural frequencies and damping. Only academia.edu copies (login required) were found; these are the most promising next lead.
  - The reference list of #15 (refs 23–26) was checked. Note that #15 miscites DAGA 2018 as "18th Conference on Digital Audio Effects (DAGA) ... September 18–21", but the paper's own page header reads "DAGA 2018 München".

### N.6 Sources

| # | Citation | URL | Read directly |
|---|---|---|---|
| 1 | Inanli, S. & Altinsoy, M. E. (2018). "Vibroacoustics of Oud." DAGA 2018 München, pp. 923–925. TU Dresden. | https://pub.dega-akustik.de/DAGA_2018/data/articles/000581.pdf | **Yes.** curl 200 (application/pdf). Full direct file read, with text and page images; figures seen. |
| 2 | Özdeş, M., Yılmaz, Y. & Oktav, A. "Coupled Vibro-Acoustic Analysis of the Turkish Oud Guitar." III. Int. Symp. on Engineering, Natural Sciences and Architecture, Kocaeli, 2020, pp. 54–60. Authors and venue come from a search snippet only. | https://www.researchgate.net/publication/340875857 | **No.** ResearchGate is bot-gated (not retried). No open copy of the proceedings was found. |
| 3 | Mohamed, E., Sassi, A., Gharib, M. & Sassi, S. "Optimizing Acoustic Performance and Structural Integrity of the Oud." *IJSSD* (2026). DOI 10.1142/S0219455426500392. Authors from OpenAlex metadata; OpenAlex marks it `closed`. | https://www.worldscientific.com/doi/10.1142/S0219455426500392 | **No.** Cloudflare "Just a moment..." challenge (not bypassed). |
| 4 | *(merged into #15)* | — | — |
| 5 | Acar, T., Karakaş, M. & Oktav, A. "Tuning the structural eigenfrequencies of an oud guitar by using different brace patterns on the soundboard." 4th Int. New York Conf. on Evolving Trends in Interdisciplinary Research & Practices, 2021. | https://www.researchgate.net/publication/351335073 | **No.** ResearchGate only. |
| 6 | Firth, I. (1977). "Some measurements on the lute." *Catgut Acoust. Soc. Newsletter* 27, p. 12. Citation read verbatim in #14. | (none) | **No.** Primary not located. Its content is known only via #14's secondary sentence. |
| 7 | Nia, H. T. (2010). "Acoustic Function of Sound Hole Design in Musical Instruments." MIT SM thesis, supervised by N. C. Makris. | https://dspace.mit.edu/bitstream/handle/1721.1/61924/707340180-MIT.pdf | **No.** HTTP 405 with an AWS WAF CAPTCHA page (not bypassed). |
| 8 | Wikipedia, "Oud", revision 1376603830 (2026-09-25). | https://en.wikipedia.org/wiki/Oud | **Yes.** Raw wikitext via `index.php?action=raw`. |
| 9 | Tonalics, "Online Oud Tuner" (commercial tuner guide). | https://tonalics.com/tuner/oud | **Yes**, curl. Guide text, not a measurement. |
| 10 | Sala Muzik, "How to Tune an Oud: Arabic & Turkish Tunings" (retailer blog). | https://salamuzik.com/blogs/news/how-to-tune-oud-instrument-easily | **No.** `EGRESS_BLOCKED`. |
| 11 | Sala Muzik, "Importance of Choosing the Right String for Oud" (retailer blog). | https://salamuzik.com/blogs/news/importance-of-choosing-the-right-string-for-oud-all-about-oud-strings | **No.** `EGRESS_BLOCKED`. |
| 12 | Lord of the Strings, "Others: Oud" (string-maker product page). | https://lordofthestrings.com/en/others/oud | **Yes**, curl. |
| 13 | Erkut, C., Laurson, M., Kuuskankare, M. & Välimäki, V. (2001). "Model-based synthesis of the ud and the Renaissance lute." Proc. ICMC 2001, Havana, pp. 119–122. Publication P7 of #14. | https://aaltodoc.aalto.fi/server/api/core/bitstreams/0863f02d-e06f-4e87-b513-3d632e7d82f1/content | **Yes.** Aaltodoc (DSpace REST), `article7.pdf`, full text via pdfminer. |
| 14 | Erkut, C. (2002). *Aspects in analysis and model-based sound synthesis of plucked string instruments.* D.Sc. thesis, Helsinki Univ. of Technology. ISBN 951-22-6190-1. | https://aaltodoc.aalto.fi/items/1d95a760-c75b-44a9-a1b6-93f101a19e16 | **Yes.** Summary PDF, full text. Table 3.1 was cross-checked against the repository's own text file and by text-box coordinates. |
| 15 | Abdallah, M., Dreik, E., Sassi, A., Paurobally, M. & Sassi, S. (2026). "Vibroacoustic sensitivity of an oud soundboard to bracing geometry." *Meccanica* 61:89. DOI 10.1007/s11012-026-02151-1. Open access. | https://link.springer.com/content/pdf/10.1007/s11012-026-02151-1.pdf | **Yes.** Full 28-page text via pdfminer. |
| 16 | Nia, H. T., Jain, A. D., Liu, Y., Alam, M.-R., Barnas, R. & Makris, N. C. (2015). "The evolution of air resonance power efficiency in the violin and its ancestors." *Proc. R. Soc. A* 471: 20140905. The journal companion to #7. | https://europepmc.org/api/getPdf?pmcid=PMC4353046 | **Yes.** Full text. No oud Hz in it. |
| 17 | UBC Wiki, "Course:Phys341 2020/Oud" (student course project, last revision 2020-04-09). | https://wiki.ubc.ca/Course:Phys341_2020/Oud | **Yes**, raw wikitext. *Not citable* as a measurement. |
| 18 | Arab Instruments, "Tuning the Oud" (retailer guide). | https://www.arabinstruments.com/pages/tuning-the-oud | **Yes**, curl. |
| 19 | Sultan Instrument, "Turkish Oud" (retailer blog). | https://www.sultaninstrument.com/blogs/oud-instrument-information/turkish-oud | **Yes**, curl. Conflates T2a and T2b. |
| 20 | Tapadum, "How to Tune a Turkish Oud" (retailer blog). | https://tapadum.com/how-to-tune-a-turkish-oud-complete-guide-for-beginners-and-professionals/ | **Yes**, curl. Its string order is garbled, so it is disregarded. |
| 21 | Ziyagil, H. E. (2021). "Türk Mûsikîsinin Telli Çalgılarından Ud'un Ses Oluşumu ve Yapısal Özellikleri Bağlamında İncelenmesi." *Motif Akademi Halkbilimi Dergisi* 14(35), 1057–1073. DOI 10.12981/mahder.932841. A review article. | https://dergipark.org.tr/tr/download/article-file/1751498 | **Yes**, full text. Everything numeric in it is secondary (Değirmenli 2014, Açın 2001). |
| 22 | Değirmenli, E. Personal site (oud maker and researcher): workshop and acoustics pages. | https://www.emirdegirmenli.com/en/musical-acoustics/modal-analysis/ | **Yes**, curl. No numbers. Its publication list names the unread theses and papers (see N.5). |

### N.7 Modelling implications (MY SYNTHESIS, not sourced data)

*Everything in this subsection is engineering judgement for an offline KS/waveguide engine, not literature. Numbers from N.1–N.4 are cited by row ID. Every other number is an audition starting point, marked "shape, not measurement".*

1. **Body bank: Turkish voice (default).** Use the two confirmed resonances **O12: 113 Hz, Q 9.91** and **O13: 182 Hz, Q 10.06**. These are the only oud modes with sourced Q, measured on a strung instrument as radiated sound, which is the right kind of data for a fixed body bank.
   - **Gains are not in the source.** Set them by ear, with 113 Hz as the dominant one, since #13 calls it "most prominent" (shape, not measurement).
   - **Do not label either mode "A0".** The source doesn't. By analogy with the guitar row measured in the same set-up (100/204 Hz, called monopoles), 113 Hz is *plausibly* air-dominated. That is an inference only.
   - **Above 182 Hz there is no sourced oud mode with Q.** Either stop the bank there or add a generic, clearly labelled upper cluster (shape, not measurement).
2. **Body bank: Arabic voice (optional).** Use **O14–O17 (142 / 226 / 242 / 280 Hz)**, plus optionally O18 (~360 Hz). The Hz values are confirmed, but they are structural EMA modes with no Q and no amplitudes. Borrow Q ≈ 10 from O12/O13 as "shape, not measurement", and set gains by ear. Keep the Turkish and Arabic banks separate rather than averaging them, because they are different instruments and different measurement types (N.5).
3. **Do NOT put O1–O8 (DAGA) into any resonator bank.** They are free–free modes of a bare soundboard, with no cavity, no bowl coupling, no strings and no bridge, so assembly will shift all of them. Their only lesson for the model is qualitative: plate modes are dense below 350 Hz, and the (1,3) mode at 198 Hz dominates the bare board's FRF at the one response point used.
4. **Commuted structure matches the literature.** #13 kept the "most prominent" body mode as a separate parallel resonator and folded the rest of the body into the excitation signal. That is the same split as a fixed bank plus a body-coloured excitation. Follow it: resonators for O12/O13, and a short noise/impulse excitation for the rest of the body colour.
5. **Two loops per double course, one for the single bass.** Unchanged from the first pass. There is still no sourced detune (C2), so tune `d` by ear with a seeded per-course RNG. C3's "two-cent" figure is a tuner-guide claim and must not be used as the default. A small cross-feed between the two loops of a course (for prompt/aftersound) remains a knob, not a claim. #13's "coupling matrix" is precedent for modelling sympathetic coupling at all, not a value.
6. **Fretless slides: sourced behaviour.** Keep both loops of a course on one pitch envelope, and **lower the loop-filter gain during a glide**. #13 names this as the physical effect ("lossy termination of the string by the flesh of the finger") and uses it in synthesis (D3). Do **not** add energy bumps along the glide: "the re-plucking is absent since the instrument is fretless". The amount of extra damping is unsourced (shape, not measurement).
7. **Tuning presets:**
   - Arabic C2 F2 A2 D3 G3 C4 (T1).
   - Turkish Bolahenk C#2 F#2 B2 E3 A3 D4 (T2a). Flag the bottom two courses, which may be a quarter tone rather than a semitone above C/F; offer a ±50-cent trim on them.
   - Turkish variant D2 A2 B2 E3 A3 D4 (T2b).
   - Use the `computed` Hz table in N.3 as the equal-tempered reference. Maqam intonation is a separate layer (see §5).
8. **Scale length drives the pluck-position comb.** Use 585 mm for the Turkish voice and 610–620 mm for the Arabic (T7). There is no sourced risha position, so pick β from a distance-to-bridge by ear, for example a few cm on the 585 mm scale (shape, not measurement). Scale the comb with scale length so that the two voices keep the same striking *distance*.
9. **Strings.** Put the two treble courses on nylon (T3, #13): darker, faster-decaying loop filter. Put the rest on silk- or nylon-overspun metal-wound strings: longer, brighter, with mild dispersion. There is no sourced B (D2), so keep dispersion subtle and set it by ear. There are no ud loop-filter targets either (D1), so the per-course damping is shape, not measurement.
10. **Stereo.** Unchanged: pan the two loops of each course a few percent apart.


---

## 3. Guzheng (Chinese 21-string zither, movable bridges, paulownia soundboard)

**Date:** 2026-09-27 (second pass; replaces the first-pass section in full)

**Access this pass.** Most scholarly hosts now answered. The following were opened and read directly: bioresources.cnr.ncsu.edu (Sinin 2026), scirp.org (Li & Li 2024), arxiv.org (ISMIR 2022; Zhang et al. 2019), j.bjfu.edu.cn (both soundboard modal papers, full PDFs), xuebao.sjtu.edu.cn (Deng 2016, full PDF), dafx.de (Rauhala & Välimäki DAFx-06), aaltodoc.aalto.fi (the IEEE SPL 2006 letter as reprinted in Rauhala's dissertation, the dissertation itself, and the guqin paper as reprinted in Penttinen's dissertation), ccrma.stanford.edu (by curl; WebFetch was egress-blocked there) for the Abel–Välimäki–Smith supporting wiki page and J. O. Smith's *PASP* pages, pub.dega-akustik.de (Coaldrake ISMA 2019), etheses.whiterose.ac.uk (Shi 2014), en.wikipedia.org and guzhengalive.com.

Still **not** opened:
- **Van Duyne & Smith 1994.** Its only host, quod.lib.umich.edu, served a Cloudflare "Just a moment..." bot check. That was not bypassed.
- **The final Abel/Välimäki/Smith 2010 letter.** Its IEEE, ResearchGate and acoustics.hut.fi copies were not opened. ResearchGate was not tried, per instruction; acoustics.hut.fi is "not in allowlist".
- **The final JASA version of the guqin paper.** pubs.aip.org was not tried, per instruction. Only the TKK Report 78 preprint was read.
- **Leads that were unreachable:**
  - Waltham (ISMA 2014) on conforg.fr: host not in allowlist.
  - Deng et al. 2015/2022 in *J. Vibration and Shock* (jvs.sjtu.edu.cn): HTTP 502.
  - A Chinese teaching page giving vibrato rates (bjqfyx.com): host not in allowlist.
  - A Taiwanese timbre paper (toaj.stpi.niar.org.tw): connection reset.

Every quote below comes from a file actually downloaded and read, except where a row says otherwise. Chinese-language quotes are given in the original, followed by an English gloss.

**What changed from the first pass, in one paragraph.** There is still **no measured inharmonicity coefficient for any guzheng string**, and Sinin 2026 turns out not to contain one. Its "inharmonic strings" list is an artefact of note-name peak labelling (Z-I1). A **physical parameter set for string 21** (D2) now exists (Zhang et al. 2019, citing Deng's thesis), and B can be *computed* from it: **B ≈ 3.5–3.8 × 10⁻⁵**, with a factor-4 ambiguity toward 1.5 × 10⁻⁴ (Z-I7, Z-I8). The guqin paper gives a verbatim **B = 0.00009** at 392 Hz as a sister-instrument proxy (Z-I2). **Five measured body modes** of a complete guzheng now have sourced Hz, strung and unstrung (Deng 2016), plus 13 + 13 soundboard modes (BJFU 2021/2022). **No body-mode Q or t60 exists** in anything read. Bend depth is confirmed qualitatively ("within major third"; "up to three half steps"). **No vibrato rate in Hz** was found in any readable source. The dispersion **sign convention and design equations are now quoted from the papers** (Rauhala & Välimäki 2006 ×2, Rauhala 2007, *PASP*). This includes the **D < 1 → bypass rule, which DAFx-06 itself applies**, and the **Eq. 7 erratum**.

`confirmed` = read directly, matched verbatim. `corrected` = the source says something different from what the first pass recorded; the fix is given. `unsupported` = not read directly or only a search snippet, and **NOT to be used in code**. `computed` = this pass's arithmetic (scripts `scratchpad/gz2/compute.py` and `scratchpad/gz2/stiff.py`, throwaway, not committed), not literature.

### Z.1 String stiffness / inharmonicity

| # | Quantity | Value | Status | Src |
|---|---|---|---|---|
| Z-I1 | Sinin's "strings whose partials are not harmonic" | Strings 2, 6, 7, 10, 11, 12, 14, 15, 16. **This is not stiffness inharmonicity and yields no B or cents value.** Table 3 lists every partial as a 12-TET note frequency. All 101 entries lie within 0.37 ¢ of 12-TET (`computed`). The "non-harmonic" rows are fn/f0 ratios between note names (e.g. string 12: 1046.5 Hz = C6, "4.23"), i.e. skipped or mislabelled peaks. The unquantised peaks quoted in the text sit uniformly +23 to +52 ¢ above nominal, *fundamentals included* (`computed`). That points to a measurement or calibration offset, not dispersion. | quote **confirmed**; interpretation **corrected** (the first pass read it as possible stiffness data; it is not) | 1 |
| Z-I2 | Guqin (sister zither, Shangyin steel-nylon strings) B | **B = 0.00009** for string 6, mark 5, f0 = 392.25 Hz (nail-stopped). Qualitatively, the lower steel-cored strings are more inharmonic than the all-nylon upper strings, and B rises as the stopped length shortens. The Fig. 8 per-note values exist only as plotted points on a 10⁻⁶–10⁻⁴ log axis, so no further number is taken from it. | **confirmed** (single verbatim value, preprint; proxy, not guzheng) | 6 |
| Z-I3 | Koto string inharmonicity (Coaldrake, ISMA 2019) | **None in the paper.** It is an FE model of pre-tensioned strings driven at 220 Hz. It reports no B, no partial-frequency table and no material constants. | **confirmed absent** | 7 |
| Z-I4 | Piano B, *proxy only* | "0.0001 is typical" (Faust docstring). The papers' own piano examples: "B = 0.00020, f0 = 32.703 Hz (key C1), (b) B = 0.00010, f0 = 65.406 Hz (key C2), and (c) B = 0.00015, f0 = 130.82 Hz (key C3)". | **confirmed** (code docstring; DAFx-06 Fig. 5 caption) | 10, 17 |
| Z-I5 | Piano stiffness-allpass coefficients, *proxy only* (Van Duyne's CLM piano), 8 first-order sections | −0.92 (key 21/A0) → −0.90 (24) → −0.70 (36) → −0.25 (48) → −0.10 (60) → −0.04 (75–96) → **+0.2 (99), +0.5 (108)** | **confirmed** (code) | 8 |
| Z-I6 | Harpsichord / steel-string zither / guitar B | None opened this pass either. | **unsupported (no value)** | — |
| Z-I7 | **Guzheng string 21 (D2) physical parameters** | ε = 2.057·10⁻² kg/m; E = 220 GPa; d = 0.6 mm; I = 2.545·10⁻² mm⁴; l = 950 mm; Ts = 400 N. They are self-consistent for pitch: f0 = (1/2l)√(Ts/ε) = 73.39 Hz against D2 = 73.42 Hz (`computed`). | **confirmed** (Zhang et al. Table 2; they cite Deng's thesis, which was not itself located) | 19 |
| Z-I8 | **Guzheng string 21 B, derived** | Using B = π³Qd⁴/(64l²T) (formula confirmed in src 17 Eq. 2 and src 6) with d = 0.6 mm: **B = 3.83 × 10⁻⁵**. With the table's I instead (B = π²EI/(Tl²)): **B = 1.53 × 10⁻⁴**, because the table's I equals πd⁴/**16**, 4× the circular πd⁴/64. The authors' own dispersion filter (Dd = 15.5236, 4 second-order Thiran sections) inverts through the Rauhala–Välimäki Eq. 7 (M = 4 constants) to **B ≈ 3.46 × 10⁻⁵**. Their Thiran coefficients (a1 = −1.6369, a2 = 0.6783) re-derive exactly from Dd. So they effectively used the d-based value. Stretch at B = 3.5–3.8 × 10⁻⁵: k = 10 → ≈ 3.3 ¢, k = 20 → ≈ 13 ¢. At 1.53 × 10⁻⁴: 13 ¢ and 51 ¢. | **computed** (from Z-I7) | 17, 19 |
| Z-I9 | Guzheng string construction | "steel cores are typically covered in nylon for greater durability and a brighter tone". ε is ~9× that of a 0.6 mm steel wire (2.22·10⁻³ kg/m, `computed`), consistent with d being the core of a wound string. That last point is an inference. | **confirmed** (quote) | 1 |
| Z-I10 | String tension across the set | Measured with a "Tensometric-COMBI-490" meter. Fig. 3 plots tension rising from string 1 to string 21, but only as a graph, so no per-string numbers are taken. Z-I7 gives 400 N for string 21 verbatim. | **confirmed** (method); plot values **unsupported** | 3, 19 |

**Quotes (verbatim):**
- Z-I1 (src 1): "The partials harmonic increased gradually with the harmonic number except for strings 2, 6, 7, 10, 11, 12, 14, 15, and 16 where some partials are not harmonic." / "The big difference between the gradient of the equations and the fundamental frequency (in strings 2, 6, 7, 10, 11, 12, 14, 15, and 16) indicated that some partials frequency did not occur at the harmonic interval". Table 3, string 12: partial 2 "1046.5" "4.23", partial 3 "1244.5" "5.03". Text: "in string 21 the 150 Hz peak (~D3=146) was more significant than the 70 Hz peak (~D2=73)". Method: "FFT analysis was used to extract dominant frequencies".
- Z-I2 (src 6): Fig. 7 caption: "Black dots follow the inharmonicity value B = 0.00009 (solid line) and the white dots follow the B/4 trend (dashed line). Fundamental frequencies obtained with the YIN algorithm for tones in pane (a) and (b) are 392.25 Hz and 392.44 Hz, respectively." Body: "the inharmonicity for lower strings (strings 1 to 4) is larger than for higher strings (strings 5 to 7) … This results from a higher Young's modulus value for steel than nylon." and "the inharmonicity increases as the length of the string decreases." Also: "According to the threshold of audibility the inharmonicity should be synthesized for at least strings 1 to 4."
- Z-I4 (src 17, Fig. 5 caption): as in the table. (src 10): "* `B`: string inharmonicity coefficient (0.0001 is typical)".
- Z-I5 (src 8): "(define default-stiffnessCoefficient-table '(21.000 -0.920 24.000 -0.900 36.000 -0.700 48.000 -0.250 60.000 -0.100 75.179 -0.040 82.986 -0.040 92.240 -0.040 96.000 -0.040 99.000 .2 108.000 .5))".
- Z-I7 (src 19, Table 2, "The physical parameters of 21st string of guzheng [2] [3]"): "Linear density 2.057 · 10−2 kg/m", "Young's Modulus 220 Gpa", "Diameter of string 0.6 mm", "Moment of inertia 2.545 · 10−2 mm4", "Length of string 950 mm", "Tension of string 400 N". Table 1: "Ad(z) Dd a1 a2 15.5236 −1.6369 0.6783". Text: "The pitch of 21st string is D2 (73.42 Hz), therefore the total delay needed is 48000/73.42 = 653.77 samples."
- Z-I8 formula (src 17, Eq. 2): "B = π3Qd4 / 64l2T, where Q is Young's modulus, d is the diameter of the string, l is the length, and T is the string tension."
- Z-I9 (src 1): "Although traditional silk strings are occasionally used for a softer and more conventional sound, steel cores are typically covered in nylon for greater durability and a brighter tone."
- Z-I10 (src 3): "其中各琴弦的张力由Tensometric-COMBI-490型弦线张力测试仪测定,具体结果见图3." ("the tension of each string was measured with a Tensometric-COMBI-490 string tension meter; results in Fig. 3").

**Bottom line for Z.1:** the one guzheng B in reach is **computed**, for one string (21, D2), from a confirmed parameter set: **≈ 3.5–3.8 × 10⁻⁵**. The parameter table's own moment of inertia would give 1.5 × 10⁻⁴ instead, and the authors' filter sides with the lower figure. Nothing covers strings 1–20. The guqin proxy (B = 9 × 10⁻⁵ at 392 Hz, confirmed) is the same order of magnitude.

### Z.2 Body (soundboard / box)

| # | Label | Hz | Q / decay | Status | Src |
|---|---|---|---|---|---|
| Z-B1 | Band of higher radiation efficiency (FE plus experiment) | 350–550 Hz, more efficient than below 350 Hz | not reported | **confirmed** | 3 |
| Z-B6 | **Complete guzheng, measured modes 1–5, strings slack** (scanning laser vibrometer, 1–1000 Hz sine sweep) | **84.06, 144.69, 177.81, 198.13, 270.63** | not reported | **confirmed** | 3 |
| Z-B7 | **Same instrument, all strings tensioned** | **83.69, 138.13, 172.50, 197.19, 275.00** (−8, −80, −53, −8, +28 ¢ vs slack, `computed`) | not reported | **confirmed** | 3 |
| Z-B2 | Solid-board (整板) paulownia soundboard glued on its box (unstrung), experimental modal analysis, 13 modes | (0,1) 238.28 · (0,2) 347.66 · (0,3) 425.78 · (1,3) 460.94 · (0,4) 492.19 · (1,5) 601.56 · (2,4) 656.25 · (2,5) 691.41 · (2,6) 746.90 · (1,7) 890.63 · (1,8) 906.25 · (2,8) 1027.34 · (1,11) 1289.06 | not reported (damping is defined in the method text but never tabulated) | **confirmed** (first pass: "no Hz reached"). All values but one are integer multiples of 3.90625 Hz, the analyser's bin width; 746.90 is not, likely a typo for 746.09 (`computed`). | 4 |
| Z-B3 | Glued-panel (拼板) soundboard on its box blank, **all edges clamped**, 13 modes identified | captioned: (0,0) 234.38 · (0,4) 593.75 · (1,4) 691.41 · (0,7) 988.28. Spectrum peaks at point (0,6): 234.38, 316.41, 425.78, 503.91, 593.75, 691.41, 855.47, 929.69, 988.28, 1027.34, 1117.29, 1285.16, 1343.75 | not reported | **confirmed** (first pass: "no Hz reached"). Citation **corrected**: published 2022, 44(5):132–141, not 2021. | 5 |
| Z-B4 | "Resonance peaks" of a guzheng A4 note: 890, 1300, 1800, 2200, 2700, 3100, 3600 Hz | **Trap, now confirmed from the paper itself: these are the partials of the played A4** ("harmonic resonance phenomena"). They are rounded to 100 Hz and scatter −26 to +39 ¢ about k·440 (`computed`), so they are unusable for B as well. Never put them in a fixed resonator bank. | n/a | **confirmed** (quote) | 2 |
| Z-B5 | *Koto proxy*: air (0,0) 85 Hz; first top-plate mode 100 Hz | 85, 100 | not reported | confirmed **in the existing note §2** (Coaldrake ICA 2019); not re-opened | 9 |
| Z-B8 | *Guqin proxy (synthesis fit, not a measurement)*: two peak filters for "two prominent low-frequency body modes" | 65 Hz (Q∞ = 6), 310 Hz (Q∞ = 8), +10 dB each; plus a 200 Hz shelf at −20 dB | Q given **as filter settings fitted to an averaged excitation spectrum**, not modal Q | **confirmed** (preprint) | 6 |

**Quotes:**
- Z-B1 (src 3, English abstract): "The acoustic radiation efficiency between 350 and 550 Hz is higher than that before 350 Hz." Body: "古筝在350~550Hz时的声辐射效率比小于350Hz时更高" ("the guzheng's radiation efficiency at 350–550 Hz is higher than below 350 Hz").
- Z-B6/Z-B7 (src 3, Table 2, "古筝仿真与实验的模态频率对比 / Simulation and experimental modal frequency of Guzheng"). Columns 无弦张拉 (strings slack) / 全弦张拉 (all strings tensioned), "实测频率/Hz" (measured). Rows: "1 89.36 84.06 6.3 89.11 83.69 6.5 / 2 140.49 144.69 -2.9 140.41 138.13 1.6 / 3 179.00 177.81 0.7 178.94 172.50 3.7 / 4 210.24 198.13 6.1 209.75 197.19 6.3 / 5 268.52 270.63 -0.8 267.13 275.00 -2.8". Method: "利用Polytec-PSV-300型扫描激光测振仪进行古筝的振动实验 … 利用控制器给激振器提供1~1 000Hz的正弦扫频信号" ("vibration test with a Polytec PSV-300 scanning laser vibrometer … a 1–1000 Hz sine sweep to the shaker"). Also: "全弦张拉即有预压应力作用下的频率比无弦张拉即无预压应力作用下的低" ("with strings tensioned (pre-stress) the frequencies are lower than slack").
- Z-B2 (src 4): "本研究将古筝共鸣面板固定在共鸣箱上，共鸣箱内部框架材料为云杉（Picea asperata），底板材料为泡桐" ("the soundboard was fixed onto its resonance box, spruce frame, paulownia back"). "能够识别到13 阶的共振频率与振型" ("13 resonance frequencies and mode shapes could be identified"). Fig. 2 captions as tabulated, e.g. "a 238.28 Hz (0,1)" … "m 1 289.06 Hz (1,11)".
- Z-B3 (src 5): "即为面板、底板和框架胶合好后的木坯 … 采用四周固定的边界条件" ("i.e. the glued body blank of top, back and frame … with all-edges-fixed boundary conditions"). "拼板古筝共鸣面板在频率为234.38 Hz 时的振动是完全同相位的振动" ("at 234.38 Hz the glued-panel soundboard vibrates entirely in phase").
- Z-B4 (src 2): "Particularly around 440 Hz, the frequency response reaches a high amplitude, corresponding to the fundamental frequency of the guzheng's standard pitch A. Additionally, other prominent resonance peaks appear at frequencies such as 890 Hz, 1300 Hz, 1800 Hz, 2200 Hz, 2700 Hz, 3100 Hz, and 3600 Hz. These resonance peaks indicate strong harmonic resonance phenomena in these frequency ranges."
- Z-B8 (src 6): "As a last step, two prominent low-frequency body modes are modeled with parametric second-order peak filters." / "The cutoff frequency for the shelving filter is 200 Hz with a 20 dB attenuation. The peak filter parameters are fc = 65 Hz and 310 Hz, and Q∞= 6 and 8, respectively, with a 10 dB amplification for both filters."

**Reading the three guzheng body datasets together:**
- Deng's complete instrument has five modes below 280 Hz.
- The BJFU bodies start at ~234–238 Hz.
- Both BJFU specimens are body blanks under a stiffer boundary: the 2022 one is explicitly clamped on all edges. They are not playable instruments.

Deng's set is the one closest to playing conditions, and it reports the strung case.

### Z.3 Playing technique

| # | Quantity | Value | Status | Src |
|---|---|---|---|---|
| Z-T1 | Upward press-bend (上滑音) range | "within major third" → ≤ 400 ¢ (`computed` conversion) | **confirmed** (ISMIR 2022 definition) | 11 |
| Z-T2 | Maximum held press (按音 anyin) | "up to three half steps" → ≤ 300 ¢ | **confirmed** (teaching site; describes practice, not a measurement). First pass: "source unidentified". | 12 |
| Z-T3 | Vibrato (颤音 chanyin): definition | "periodic oscillation of tones caused by the periodic pressing of a string with left hand". **No rate (Hz) or depth (cents) in the paper.** | **confirmed** (definition only) | 11 |
| Z-T4 | Downward bend | "decrease the pitch of a ringing note by releasing a bended string" | **confirmed** (the first-pass wording "decreases" was a paraphrase; fixed to verbatim) | 11 |
| Z-T5 | Fingerpick material | "plastic, resin, tortoise-shell, or ivory"; "artificial nails, composed of plastic". **No acoustic measurement of pick vs attack.** | **confirmed** (descriptive) | 1 |
| Z-T6 | Attack: A4 amplitude peak after the pluck | 0.034 s | **confirmed** | 2 |
| Z-T7 | Light vibrato (吟 yín) depth | "typically less than a half step" → < 100 ¢ | **confirmed** (teaching site) | 12 |
| Z-T8 | Heavy vibrato (揉音 róuyīn) depth and rate | depth: "the string reaches the next highest note" (in D-pentatonic tuning, 200 or 300 ¢ depending on string, `computed` from Z.4). Rate: "only pressing the string 2 or 3 times in the space of a quarter note" (tempo-relative: 2–3 Hz at ♩ = 60, 4–6 Hz at ♩ = 120, `computed`) | **confirmed** (teaching site, citing Ferguson's sources E and F) | 12 |
| Z-T9 | Vibrato rates 颤弦 7–8 Hz / 擞音 5–6 Hz / 吟弦 3–4 Hz | as stated | **unsupported** (search-engine summary only; host bjqfyx.com not in allowlist) | 20b |
| Z-T10 | Pitches the open tuning lacks, supplied by pressing | "no strings are specifically assigned to play F or B, those pitches can be produced by pressing E and A instead" (C tuning) → required bends of +100 ¢ and +200 ¢ (`computed`) | **confirmed** | 22 |
| Z-T11 | *Guqin proxy*: tension-modulation initial pitch glide after the pluck | mean 0.025 ERB (mf), 0.034 ERB (ff). Example: 198.6 → 197 Hz, "0.035 ERB" (= 14 ¢, `computed`) | **confirmed** (preprint) | 6 |

**Quotes:**
- Z-T1/T3/T4 (src 11): "Vibrato (chanyin颤音): the periodic oscillation of tones caused by the periodic pressing of a string with left hand" / "Upward Portamento (shanghuayin上滑音) … press a string with left hand to increase the pitch of a ringing note to a desired pitch within major third" / "Downward Portamento (xiahuayin下滑音) … decrease the pitch of a ringing note by releasing a bended string".
- Z-T2 (src 12, bent plucks page): "This is a generic term for raising a string's pitch by up to three half steps and does not refer to any specific string or pitch."
- Z-T5 (src 1): "Guzheng musicians frequently use one or both hands to hold a fingerpick composed of plastic, resin, tortoise-shell, or ivory." / "To pluck the strings, players wear artificial nails, composed of plastic."
- Z-T6 (src 2): "At 0.034 seconds after the string is struck, the amplitude reaches its peak".
- Z-T7 (src 12, vibrato page): "It is considered "light" because it does not cause the pitch to change very much, typically less than a half step."
- Z-T8 (src 12): "A category of deep vibratos that are pressed so strongly the string reaches the next highest note. … Ferguson's Sources E and F say it is a slow rhythm, only pressing the string 2 or 3 times in the space of a quarter note."
- Z-T9 (search summary, **not verified**): "以腕关节为轴心的"颤弦"频率可达7～8Hz … 以肘关节为轴心的"擞音"频率一般为5～6Hz … "吟弦"频率仅为3～4Hz" (wrist-pivot "chanxian" up to 7–8 Hz; elbow-pivot "souyin" 5–6 Hz; "yinxian" only 3–4 Hz).
- Z-T10 (src 22): "among the 21 strings of Guzheng, although no strings are specifically assigned to play F or B, those pitches can be produced by pressing E and A instead, respectively."
- Z-T11 (src 6): "For mezzoforte tones the largest initial pitch glide value obtained was 0.075 ERB … while the mean was 0.025 ERB … for forte fortissimo tones the mean value for the initial pitch glides was 0.034 ERB" / "At 0.11 seconds the fundamental frequency is 198.6 Hz and beyond 1 s it is 197 Hz. This gives a change of 1.6 Hz, which is 0.035 ERB."

### Z.4 Standard tuning

| # | Quantity | Value | Status | Src |
|---|---|---|---|---|
| Z-U1 | Range of the 21-string instrument, string 21 → string 1 | "D2=73.41 Hz to D6=1174.7 Hz" | **confirmed** | 1 |
| Z-U2 | Tuning: D major pentatonic (D E F♯ A B) over four octaves plus D6 | Sinin calls the set "G major pentatonic" but lists "D (first degree), E (second degree), F# (third degree), A (fifth degree), and B (sixth degree)". Those degrees are counted from D, so the scale is **D** major pentatonic (G major pentatonic would be G A B D E). Wikipedia: "tuned in a major pentatonic scale"; the key is the tuner's choice (its figure caption shows C major). Strings 21–17 = D2–B2, 16–12 = D3–B3, 11–7 = D4–B4, 6–2 = D5–B5, 1 = D6. | **corrected** (the key label; the notes themselves are confirmed) | 1, 22 |
| Z-U3 | Per-string Hz on Sinin's instrument | D2=73.41, E2=82.40, F2#=92.499, A2=110.00, B2=123.47, D3=146.83, E3=164.81, F3#=185.00, A3=220.00, B3=246.94, D4=293.67, E4=329.63, F4#=369.99, A4=440.00, B4=493.88, D5=587.33, E5=659.26, F5#=739.99, A5=880.00, B5=987.77, D6=1174.7 | **confirmed**. These are 12-TET nominal values (A4 = 440), not measured deviations (Z-I1). | 1 |

The 12-TET arithmetic from the first pass (D2 73.42 … D6 1174.66, `computed`) agrees with Z-U3 to rounding. One typo: Sinin's Conclusion 2 repeats "(D5, E5, F5#, A5, B5)" twice. Its Results section has the correct octave list.

**Quotes:** (src 1) "The progressing note for string 21 to string 1 are from D2=73.41 Hz to D6=1174.7 Hz. The G major pentatonic scale consists of the first, second third, fifth, and sixth degrees: D, E, F♯, A, and B." / "Strings 21 to 17: D2, E2, F2#, A2, B2, Strings 16 to 12: D3, E3, F3#, A3, B3, Strings 11 to 7: D4, E4, F4#, A4, B4, Strings 6 to 2: D5, E5, F5#, A5, B5, String 1: D6." (src 22) "The modern guzheng commonly has 21, 25, or 26 strings, is 64 inches (1.6 m; 5 ft 4 in) long, and is tuned in a major pentatonic scale."

### Z.5 Decay / sustain / timbre

| # | Quantity | Value | Status | Src |
|---|---|---|---|---|
| Z-D1 | Band that persists over 0–1.6 s of an A4 note | 370–520 Hz, "no significant decay observed" | **confirmed** | 2 |
| Z-D2 | Components above 2 kHz of A4 decay within | 0.75 s | **confirmed** | 2 |
| Z-D3 | Decay of string 13 (A3, 220 Hz), "two-stage" | ≈ 8 s; rapid initial drop, then a slower steady decay. Not a defined T60: it was judged visually from the waveform. | **corrected**: the source is **Shi 2014**, not Sinin 2026 (Sinin reports no decay figure). The value itself is confirmed. | 20 |
| Z-D4 | A4 envelope landmarks | peak at 0.034 s; "sine wave phase interleaving" 0.13–0.24 s; "clear trend of amplitude decay" from 0.47 s | **confirmed** (qualitative) | 2 |
| Z-D5 | Fitted loss parameters, string 21 (for a modal/DWG model) | d1 = 2.9390, d3 = −9.1 × 10⁻⁴ (R² = 0.74); loop LP g = 0.9985, a = 2.31 × 10⁻⁷ at 48 kHz. **Do not use.** The paper's labels for d1/d3 contradict its own PDE, and g = 0.9985 per period at 73.42 Hz implies T60 ≈ 63 s (`computed`), implausible against Z-D3. | **confirmed** (quote), flagged unusable | 19 |
| Z-D6 | *Guqin proxy*: nail- vs fingertip-stopped decay | "the decay times of fingertip tones are smaller than those of the nail tones". Per-mark T60 appears only in Fig. 6 (plotted). | **confirmed** (qualitative) | 6 |
| Z-D7 | Per-partial t60 table for any guzheng string | none found | — | — |

**Quotes:**
- Z-D1/D2 (src 2): "within the frequency range of 370 Hz to 520 Hz, the persistence of the frequencies is relatively strong, with no significant decay observed." / "above 2 kHz, the persistence of frequency components is weaker, with these high-frequency elements rapidly decaying within 0.75 seconds after the string is struck."
- Z-D3 (src 20): "two waveforms have a similar shape with a rapid decay shortly after attack of the tone and then a steady, slower decay, and the decay time is almost the same (approximately 8s)." The recorded note is "Plucking of String No.13", and the thesis says its "fundamental frequency is 220Hz".
- Z-D4 (src 2): "between 0.13 seconds and 0.24 seconds after striking the string, a phenomenon of sine wave phase interleaving occurs." / "Starting from 0.47 seconds after the string is struck, a clear trend of amplitude decay is observed."
- Z-D5 (src 19): "we calculated the damping parameters as d1 = 2.9390 and d3 = −9.1 × 10−4 with goodness-of-fit R2 = 0.74"; Table 1 "Hlp(z) g a 0.9985 2.31 × 10−7"; and "d1 and d3 are the frequency dependent and independent decay parameter, respectively" (in the stated PDE, d1 multiplies ∂y/∂t, which is the frequency-*independent* term).

### Z.6 What the literature cannot give

- **No measured B for any guzheng string.** The only number is computed, for string 21 alone, from one parameter table (Z-I7/I8). That table carries an internal factor-4 inconsistency in I, so the answer is 3.5–3.8 × 10⁻⁵ or 1.5 × 10⁻⁴ depending on which entry is trusted. The authors' filter sides with the lower figure. No readable source has diameter, length and tension for strings 1–20. The guqin data show B varies strongly with string and stopped length (Z-I2), so a single B across 21 strings is a modelling choice, not a fact.
- **Sinin 2026 cannot supply cents deviations.** Its partials are reported at note-name resolution (12-TET values) and its raw peaks carry a uniform ~+30 ¢ offset (Z-I1).
- **No body-mode Q, bandwidth or t60 for the guzheng.** Deng 2016, BJFU 2021 and BJFU 2022 report frequencies only. The only Q values anywhere are the guqin model's *filter* settings (Z-B8), a synthesis fit for another instrument.
- **The body data disagree on the lowest mode:** 84 Hz (complete instrument) against 234–238 Hz (body blanks, stiffer boundary). No source reconciles them, and no guzheng air/Helmholtz mode is identified in anything read.
- **No measured vibrato rate or depth, and no bend rise time.** Only a definition (ISMIR), qualitative depths ("within major third", "three half steps", "less than a half step", "next highest note") and a tempo-relative press count reached a readable page. The Hz figures (Z-T9) remain a search snippet.
- **No acoustic study of pick material vs attack spectrum.**
- **String tension figures conflict across sources.** Deng measured string tensions (Fig. 3, graph only); Zhang's table gives 400 N for string 21. Guzheng Alive's hobbyist calculation puts "#21 string … at ~60 pounds force (26kg)", about 255 N. That page is a secondary estimate and is not used.
- **Van Duyne & Smith 1994 remains unread.** Its sign and design content is known only through Rauhala & Välimäki's description of it ("did not indicate how the filter cascade can be designed, other than by trial and error or by using a search algorithm") and through Van Duyne's own CLM code (src 8). **The Abel–Välimäki–Smith 2010 letter is known only through its abstract and the authors' Matlab listing** on the CCRMA wiki.

### Z.7 Modelling implications (MY SYNTHESIS: not a literature claim unless a source # is given)

1. **Section form and sign: now confirmed from the papers, not just code.**
   - *PASP* (src 21) defines the stiffness allpass as H_s(z) = z^−K Ã(z)/A(z) with A(z) = 1 + a₁z⁻¹ + …. With K = 0 and first order this is H(z) = (a + z⁻¹)/(1 + a·z⁻¹), matching both code sources (CLM `clm.c`: "y[i] = x[i] + (coeff * (y0 - y[i]));"; Faust `fi.tf1(a1,1,a1)`).
   - Rauhala & Välimäki's first-order Thiran design gives **a₁ = (1 − D)/(D + 1)** (src 17, Eq. 8). This is SPL Eq. 1 with N = 1.
   - Their Figs. 3–4 plot a₁ only between −1 and 0 across B = 10⁻⁶…10⁻³.
   - SPL (src 15): the section's "phase delay decreases monotonically with frequency from D samples at dc to N samples at the Nyquist frequency". So D > 1 ⇒ **a < 0 ⇒ delay falls with frequency ⇒ partials go sharp.** This is the first pass's sign finding, now sourced.
   - The Faust comment "By Eq. 3, have D >= 0, hence a1 >= 0 also" is contradicted by the paper's own figures. Its code is right.

   `computed` check kept from the first pass (48 kHz, f0 = 147 Hz, 4 sections, fundamental re-tuned):

   | a | k = 2 | k = 5 | k = 12 |
   |---|---|---|---|
   | −0.5 | +0.05 ¢ | +0.37 ¢ | +2.11 ¢ |
   | +0.5 | 0.00 ¢ | −0.01 ¢ | −0.03 ¢ |

2. **Placement in the loop.** Dispersion "commutes with other LTI systems in series, such as delay elements" (src 21). DAFx-06 Fig. 6 draws the dispersion filter inside the basic string loop, alongside the delay line, loss filter and tuning filter (src 17). Keep the cascade outside the swept fractional-delay element during bends, as before.

3. **Pitch compensation: the budget, sourced.**
   - SPL (src 15): "L₁ = floor(fs/f₁ − d_disp,1 M) − 1" and "d_t = fs/f₁ − d_disp,1 M − L₁". The paper then approximates d_disp,1 by D, the DC delay ("It is found that the filter parameter D, which corresponds to the phase delay at dc, is a satisfactory approximation").
   - Zhang et al. (src 19) use the same form: "L = ⌊fs/f0−4Ddisp⌋−1 … Dfrac = fs/f0 −L −4Ddisp".
   - The SILK spec's `dispersionDelay(f0) = M × (phase delay at ω0)`, in closed form, is the exact version of this. Keep it. Faust's `-Df0*M` computes it, matching the numerically measured phase delay to 4 decimals (first pass, `computed`).
   - DAFx-06 warns that "the product D · M may not be a sufficient estimate for the delay with large M values" (src 17). That is one more reason to keep the exact phase delay.

4. **Closed-form B → a design. Two literature-backed rules:**
   - **Erratum:** Eq. 7 as printed in DAFx-06 reads "Cd(B, M) = e((m1 ln M+m2) ln M+m3 ln M+m4)" and has no B in it. The dissertation's Eq. 3.8 (src 18) corrects it to "Cd(B, M) = e((m1 ln M+m2) ln B+m3 ln M+m4)". Faust implements the corrected form. **SILK must use the corrected form**, with constants k1…k3 = −0.00179, −0.0233, −2.93 and m1…m4 = 0.0126, 0.0606, −0.00825, 1.97 (src 17 Table 2, confirmed).
   - **Clamp, now sourced:** "When equation 3 resulted in a D value less than 1 (for instance, key numbers 75-88 in Figure 9), D was set to be 1, which corresponds to replacing the allpass filters with the transfer function A(z) = 1" (src 17). A first-order section with D = 1 (a = 0) is z⁻¹, not 1. The paper's "A(z) = 1" implies the sections are removed, not zeroed. So SILK should **drop the sections and charge no dispersion delay** when D ≤ 1, which is the spec's existing "bypass the cascade".
   - For guzheng-plausible B the clamp bites at the top of the range. Corrected Eq. 7, M = 4 (`computed`, `stiff.py`):

   | B | D2 | D3 | A3 | D4 | A4 | D5 | A5 | D6 |
   |---|---|---|---|---|---|---|---|---|
   | 1e-5 | 6.65 | 3.43 | 2.33 | 1.77 | 1.21 | **0.92** | **0.62** | **0.47** |
   | 3.5e-5 | 8.77 | 4.47 | 3.02 | 2.28 | 1.54 | 1.16 | **0.78** | **0.59** |
   | 9e-5 | 11.06 | 5.59 | 3.76 | 2.83 | 1.90 | 1.43 | **0.96** | **0.72** |
   | 1.5e-4 | 12.65 | 6.38 | 4.28 | 3.22 | 2.16 | 1.62 | 1.09 | **0.82** |

   (**bold** = D ≤ 1, cascade bypassed). The first pass's fit-error findings stand as `computed`: 13–20 % overshoot at k = 10 for B = 1e-4 across D2–D4, and ~2.5× at B = 1e-5. So the STIFF test should still measure the stretch it produces rather than trust the map. The paper's own accuracy target is "0.5% error limits corresponding to ±8.39 cents" (src 17), which is loose next to the few-cent stretches a guzheng needs.

5. **How much dispersion: STIFF's recommended B range, updated.**
   - **Anchor:** string 21 (D2) B ≈ 3.5–3.8 × 10⁻⁵ (Z-I8, `computed` from a confirmed parameter set). At that B the stretch is ≈ 3 ¢ at k = 10 and ≈ 13 ¢ at k = 20. It is audible mainly as the low strings' upper partials spreading.
   - **Upper bracket:** 1.5 × 10⁻⁴, the same string read with the table's I (Z-I8), and the guqin's verbatim 9 × 10⁻⁵ at 392 Hz (Z-I2) sits inside that bracket.
   - **Recommendation:** STIFF maps to **B ∈ [0, 1.5 × 10⁻⁴]**, **default ≈ 3.5 × 10⁻⁵**, audition sweep {0, 1e-5, 3.5e-5, 9e-5, 1.5e-4} on D2/D3/A3.
   - Anything above 1.5 × 10⁻⁴ has no guzheng or guqin source in hand. It should not be offered as a "guzheng" setting, though it may exist as an effect range.
   - This is a **single B for all keys**, which the guqin data say is physically wrong (Z-I2: B varies by string and length). Treat the per-key constant as shape, not measurement. Do not cite 1e-4 (piano) as a guzheng property.
   - Because the clamp removes the cascade from ~A5 up (point 4), the top octave is effectively harmonic at every sourced B. That is acceptable: the upper strings' B is unsourced anyway.

6. **Body bank.**
   - If SILK's guzheng uses a fixed resonator bank, **Deng's strung-instrument modes (83.69, 138.13, 172.50, 197.19, 275.00 Hz; Z-B7) are the only sourced Hz for a playable guzheng.**
   - Add the 350–550 Hz radiation emphasis (Z-B1) as a broad EQ, not a mode.
   - The BJFU sets (Z-B2/B3) describe body blanks under stiffer boundaries. Use them only as evidence of dense modes from ~230 Hz to 1.3 kHz, not as bank frequencies.
   - **Q is unsourced for every guzheng mode.** Every Q and gain is "shape, not measurement". The guqin model's Q∞ = 6–8 (Z-B8) is the only order-of-magnitude reference, and it is a filter fit for another instrument.
   - Never place the Li & Li peaks (Z-B4) in a fixed bank.

7. **Bends and vibrato.**
   - Press-bend depth: cap at **400 ¢** ("within major third", Z-T1). Typical held presses are **≤ 300 ¢** (Z-T2). The scale-filling presses the tuning needs are **100 and 200 ¢** (Z-T10). In D-pentatonic tuning, a róuyīn to "the next highest note" is **200 or 300 ¢** depending on string (Z-T8).
   - Light vibrato depth: **< 100 ¢** (Z-T7).
   - Vibrato **rate has no sourced Hz**. The only sourced rate is tempo-relative, "2 or 3 times in the space of a quarter note" (Z-T8), i.e. 2–6 Hz over ♩ = 60–120 (`computed`). The 3–8 Hz snippet (Z-T9) is unsupported. Keep rate an audition parameter; if the engine is tempo-aware, a presses-per-beat control is the sourced form.
   - Rise time has no source.
   - A small post-pluck downward glide of order 0.025–0.034 ERB (≈ 10–14 ¢ at ~200 Hz; guqin, Z-T11) is a sourced proxy for tension modulation, if SILK models it.
   - Recompute the point 3 compensation per control block as f0 moves. D varies smoothly with f0, so a fixed B can be held during a bend. If the bend crosses the D = 1 boundary (point 4), crossfade the cascade in or out rather than switching it, to avoid a pitch step.

### Z.8 Sources

Numbers 1–16 keep their first-pass meaning. 17 onward are new.

| # | Citation | URL | Read directly |
|---|---|---|---|
| 1 | Sinin, A. E., Hamdan, S., Said, K. A. M., Tutom, L. P. & Musib, A. F. (2026). "Acoustics of the Guzheng: Chinese Plucked Zither." *BioResources* 21(2), 5306–5328. DOI 10.15376/biores.21.2.5306-5328 | https://bioresources.cnr.ncsu.edu/wp-content/uploads/2026/04/BioRes_21_2_5306_Sinin_HSTM_Acoustics_Guzheng_Plucked_Zither_25391.pdf | **Yes** (all 24 pages; Table 1 and Fig. 6 viewed as images) |
| 2 | Li, N. & Li, D. (2024). "Acoustic Measurement and Modeling of the Traditional Chinese Instrument Guzheng in Digital Transformation: A Case Study of Spectral and Resonance Analysis of Standard Pitch A440." *Open J. Acoustics* 12, 17–30. DOI 10.4236/oja.2024.122002 | https://www.scirp.org/pdf/oja2024122_11610220.pdf | **Yes** (all 14 pages) |
| 3 | Deng Xiaowei, Yu Zhengyue, Yao Weiping & Chen Minjie (2016). "传统古筝的振动声学特性仿真分析" (Simulation analysis of vibro-acoustic characteristics of traditional Guzheng). *J. Shanghai Jiao Tong Univ.* 50(2), 300–305. | https://xuebao.sjtu.edu.cn/CN/abstract/abstract41211.shtml (PDF via `…/CN/article/downloadArticleFile.do?attachType=PDF&id=41211`) | **Yes** (full 6-page PDF) |
| 4 | Ge Ying, Zhang Yuanting, Wan Ke et al. (2021). "整板结构的古筝共鸣面板振动模态的研究" (Vibration mode of Guzheng resonance panel with whole board structure). *J. Beijing Forestry Univ.* 43(8), 107–116. DOI 10.12171/j.1000-1522.20210136 | https://j.bjfu.edu.cn/cn/article/pdf/preview/10.12171/j.1000-1522.20210136.pdf | **Yes** (full PDF) |
| 5 | Zhang Yuanting, Ge Ying, Miao Yuanyuan et al. (**2022**). "拼板结构的古筝共鸣面板振动模态研究" (Vibration mode of Guzheng soundboard with composite structure). *J. Beijing Forestry Univ.* **44(5), 132–141**. DOI 10.12171/j.1000-1522.20210495 | https://j.bjfu.edu.cn/cn/article/pdf/preview/10.12171/j.1000-1522.20210495.pdf | **Yes** (full PDF) |
| 6 | Penttinen, H., Pakarinen, J., Välimäki, V., Laurson, M., Li, H. & Leman, M. "Model-based sound synthesis of the guqin." Read as TKK Acoustics Lab Report 78 (Sept. 2006, "submitted for publication" to JASA), reprinted as article 6 of Penttinen's dissertation. Final version: *JASA* 120(6), 4052–4063 (2006), a citation confirmed via src 18's reference [79]. | https://aaltodoc.aalto.fi/items/5639c73a-0131-4710-a60e-0b5c3cd7858a (bitstream `article6.pdf`) | **Yes (preprint)**; the final JASA text was not opened |
| 7 | Coaldrake, K. (2019). "Finite element modelling of Japanese koto strings." ISMA 2019, Detmold. | https://pub.dega-akustik.de/ISMA2019/data/articles/000063.pdf | **Yes** (no B or partial data in it) |
| 8 | Van Duyne, S. — CLM `piano.ins`, Snd/Scheme `piano.scm`; `one_pole_all_pass` in `clm.c` (Snd mirror). | https://raw.githubusercontent.com/yurivict/Snd/HEAD/piano.scm and …/clm.c | Yes (first pass; not re-read) |
| 9 | Existing repo note §2 (Coaldrake, ICA 2019, koto 85/100 Hz). | docs/superpowers/plans/2026-09-25-pluck-depth-body-research.md | Yes (repo file) |
| 10 | Smith, J. O. — Faust `misceffects.lib` `piano_dispersion_filter`; `filters.lib` `tf1`. | https://raw.githubusercontent.com/grame-cncm/faustlibraries/master/misceffects.lib | **Yes** (re-read this pass) |
| 11 | Li, D., Wu, Y., Li, Q., Zhao, J., Yu, Y., Xia, F. & Li, W. (2022). "Playing Technique Detection by Fusing Note Onset Information in Guzheng Performance." Proc. ISMIR 2022, Bengaluru. | https://arxiv.org/pdf/2209.08774 | **Yes** |
| 12 | Guzheng Alive (teaching site): "Guzheng Techniques: Vibrato", "…Plucks (bent)", "String Tension". Secondary; describes practice. | https://guzhengalive.com/guzheng-techniques-vibrato ; …/guzheng-techniques-plucks-bent ; …/string-tension | **Yes** |
| 13 | Secondary: Crack-Pantelimon/bespoke-mcp-data-pack `guzheng/RESEARCH.md`. Its "~8 s at A3" is from Shi 2014 (src 20), not Sinin. Its note that Sinin says "G major" is right; the label itself is wrong (Z-U2). | https://github.com/Crack-Pantelimon/bespoke-mcp-data-pack | first pass only; lead source, nothing confirmed from it |
| 14 | Van Duyne, S. A. & Smith, J. O. III (1994). "A simplified approach to modeling dispersion caused by stiffness in strings and plates." Proc. ICMC, Århus, pp. 407–410 (pages per src 18, ref. [98]). | https://quod.lib.umich.edu/i/icmc/bbp2372.1994.105/1 | **No**. Cloudflare bot check; not bypassed. |
| 15 | Rauhala, J. & Välimäki, V. (2006). "Tunable dispersion filter design for piano synthesis." *IEEE Signal Processing Letters* 13(5), 253–256. DOI 10.1109/LSP.2006.870376 | https://aaltodoc.aalto.fi/items/de93a7a6-f450-4820-b42a-14fc49c7234a (bitstream `article1.pdf`, IEEE reprint) | **Yes** (equations read from rendered pages) |
| 16 | Abel, J. S., Välimäki, V. & Smith, J. O. III (2010). "Robust, efficient design of allpass filters for dispersive string sound synthesis." *IEEE Signal Processing Letters* 17(4). | https://ccrma.stanford.edu/wiki/Dispersion_Filter_Design | **Partly**: the abstract and the authors' `adf.m` listing on the CCRMA wiki were read; the letter itself was not |
| 17 | Rauhala, J. & Välimäki, V. (2006). "Dispersion modeling in waveguide piano synthesis using tunable allpass filters." Proc. DAFx-06, Montreal, pp. 71–76. | https://www.dafx.de/paper-archive/2006/papers/p_071.pdf | **Yes** |
| 18 | Rauhala, J. (2007). *Physics-based parametric synthesis of inharmonic piano tones.* Doctoral dissertation, Helsinki Univ. of Technology, Report 85 (Eq. 3.8 = corrected DAFx-06 Eq. 7). | https://aaltodoc.aalto.fi/items/de93a7a6-f450-4820-b42a-14fc49c7234a | **Yes** |
| 19 | Zhang, E., Gupta, G., Greif, C. & Paplinski, A. (2019). "An Efficient Modal-based Approach Towards Guzheng Sound Synthesis." arXiv:1910.05447 (string 21 data attributed to Deng's master thesis and a companion work). | https://arxiv.org/pdf/1910.05447 | **Yes** |
| 20 | Shi, J. (2014). *Extending the Sound of the Guzheng.* MA by research, University of York. | https://etheses.whiterose.ac.uk/id/eprint/8788/1/Extending%20the%20sound%20of%20the%20guzheng.pdf | **Yes** (decay, recording and technique sections) |
| 20b | Chinese teaching page on vibrato rates (颤弦/擞音/吟弦), probably "谈谈古筝的表现力" | http://www.bjqfyx.com/zx/kepu/278.html | **No**. Host not in allowlist; search summary only. |
| 21 | Smith, J. O. III, *Physical Audio Signal Processing* (W3K, 2010), online pages "Stiff String Synthesis Models" and "Dispersion Filter Design". | https://ccrma.stanford.edu/~jos/pasp/Stiff_String_Synthesis_Models.html ; …/Dispersion_Filter_Design.html | **Yes** (curl) |
| 22 | Wikipedia, "Guzheng" (fetched 2026-09-27). | https://en.wikipedia.org/wiki/Guzheng | **Yes** |
| 23 | Waltham, C. (2014). "An Acoustical Comparison of East Asian and Western String Instruments." ISMA 2014, Le Mans, 375–380 (cited by src 2). A likely source of string parameters. | http://www.conforg.fr/isma2014/cdrom/data/articles/000144.pdf | **No**. Host not in allowlist. |
| 24 | Deng Xiaowei et al. (2015). "古筝弦振动及琴码的动力学分析" (string vibration and bridge dynamics). *J. Vibration and Shock* 34(18), 166–170; and the 2022 follow-up "古筝弦-码结构的三维动力学分析与试验验证", *J. Vibration and Shock* 41(6), 70. Likely sources of per-string parameters. | https://jvs.sjtu.edu.cn/CN/Y2015/V34/I18/166 ; https://jvs.sjtu.edu.cn/EN/Y2022/V41/I6/70 | **No**. HTTP 502. |

**Additional verbatim quotes for the dispersion rows (src 14–18, 21):**
- src 15, Eq. (1): "a_k = (−1)^k (N choose k) ∏_{n=0}^{N} (D − N + n)/(D − N + k + n) for k = 1, 2, …, N". Eq. (2): "P_k = f_s / (f_0 √(1 + Bk²))". Eq. (8): "A(z) = ((a2 + a1 z^−1 + z^−2)/(1 + a1 z^−1 + a2 z^−2))^4". Table I (A_disp1, N=2, M=4): "k1 -0.00050469, k2 -0.0064264, k3 -2.8743, C1 0.069618, C2 2.0427".
- src 17: "In this paper, a cascade of first-order filters is used for designing a dispersion filter originally proposed by Van Duyne and Smith [5]." / "Van Duyne and Smith did not indicate how the filter cascade can be designed, other than by trial and error or by using a search algorithm [5]." / "In practical cases, M values below 20 are preferred for computational reasons." / Eq. (8) "a1 = (1 − D)/(D + 1)".
- src 18, Eq. (3.8): "Cd(B, M) = e((m1 ln M+m2) ln B+m3 ln M+m4)".
- src 21: "H_s(z) = z^{−K} Ã(z)/A(z) where K ≥ 0 is an integer pure-delay … A(z) ≜ 1 + a_1 z^{−1} + a_2 z^{−2} + ⋯ + a_N z^{−N}" / "For an allpass filter H_s(z) simulating stiffness, we would normally have K=0, since the filter is already in series with a delay line."
- src 16 (CCRMA wiki): "The proposed method designs an allpass filter in cascaded biquad form directly from the target group delay … Design examples show the method to outperform a previous closed-form design." `adf.m`: "eta = (1 - beta.*cos(delta * 2*pi/fs))./(1 - beta);", "alpha = sqrt(eta.^2 - 1) - eta;", "sos = [fliplr(temp) temp];" with "temp = [ones(nap,1) 2*alpha'.*cc' alpha'.^2];". The pole radius |α| is < 1 with α < 0. This is a biquad group-delay design, an alternative to SILK's first-order cascade, and is not adopted.

The verification scripts are in the scratchpad:
- `gz/ap.py` and `gz/faust_check.py` (first pass): sign, phase delay, closed-form stretch.
- `gz2/compute.py`: Sinin 12-TET quantisation and peak offsets, Li & Li scatter, string-21 B and Thiran re-derivation, BJFU bin check, Deng strung/slack shifts.
- `gz2/stiff.py`: the corrected Eq. 7 D table.


---

## 4. Persian santur (trapezoidal hammered dulcimer, courses of four, light wooden mezrab)

**Date:** 2026-09-27 (second pass; replaces the first-pass section of the same date)

### N.0 Read this first: what this pass could and could not open

**Access this pass.** Scholarly hosts were reachable this time. Sources were fetched with `curl` through the session proxy. PDFs were converted to text locally with PyMuPDF, and every quote below was checked against that text. The only normalisation in the quotes:
- line-break hyphens and ligatures are joined;
- superscript footnote markers are dropped (e.g. "9-bridge¹² santur");
- LaTeX note names are written plainly (Euphonics' `$A_4$` becomes A4);
- one PDF-extraction garble is repaired ("0f", repaired to "f0").

Nothing else is changed.

- **Opened and read in full:** `ismir2005.ismir.net` (src 1), `repository.londonmet.ac.uk` (src 2, the 161-page thesis), `arxiv.org` (src 3), `archives.ismir.net` (src 4), `digital.library.unt.edu` (src 5), `jcaa.caa-aca.ca` (src 6, an open copy of the paper the first pass could only find on ResearchGate), `raw.githubusercontent.com` (src 7), `bioresources.cnr.ncsu.edu` (src 12), `www.speech.kth.se` (src 13, 25), `patents.google.com` (src 16, as text), `songbirdhd.com` (src 17), `luth.org` (src 19, a free web extra), `acoustics.org` (src 28), `euphonics.org` (src 26, 27), `rs2007.limsi.fr` (src 29), `vi-co.org` (src 30, the HTML page), `en.wikipedia.org` (src 23, 24), and `saxonianfolkways.wordpress.com` (src 22).
- **Opened only partly:**
  - `luth.org` src 14 and src 20: only the public opening paragraphs were read. The rest is members-only and was not accessed.
  - `huskiecommons.lib.niu.edu` src 31: only the landing page and abstract were read. The PDF is behind a Cloudflare "Just a moment…" challenge.
  - `image-ppubs.uspto.gov` src 16: the PDF downloaded but is scanned images only. Its text was read on Google Patents instead.
  - Wikipedia's API returned HTTP 429 (rate limit) for three titles. The Santur article was then read from its ordinary page HTML, fetched once.
- **Not opened (403 or bot challenge):** `pubs.aip.org` (src 8, 9, 11; all AIP/JASA/POMA pages), `www.researchgate.net` (src 10, 15), `scholarsarchive.byu.edu` (src 32, a Cloudflare challenge), `www.ethnicmusical.com`, and the `vi-co.org` PDF. None was retried or routed around.
- **One disclosure:** the first batch of ten `curl` requests sent a generic browser `User-Agent` string. The AIP and ResearchGate 403s in that batch were not retried. Later requests to the same reachable hosts (luth.org, KTH) worked without it.

**What changed from the first pass.**
- The bridge-count conflict is **resolved** from directly read sources (N.1). The 72-string / 18-bridge figure is right for the standard "9-bridge" santur, which has 9 bridges per row, two rows. The "25 bridges" sentence in MusicBrainz is filed under the **Indian `santoor`** entry, not `santur`.
- Range, reference pitch, inharmonicity, pitch stretch, string dimensions and body dimensions are now **confirmed** from Heydarian's papers and thesis (src 1, 2, 18) and a luthier's construction article (src 19).
- Several first-pass "quotes" were **misattributed**:
  - The mezrab / no-rebound text comes from *New Grove Dictionary of Musical Instruments* (1984). Patent src 16 quotes it, and that quote says "Vietnamese and Indian", not "Turkish and Indian".
  - The "undamped segments between bridge and tuning pins" sentence is about the **clavichord** (src 29), not the hammered dulcimer.
  - The "(1,0) mode … brace near a nodal line" quote is from Canfield's 1996 NIU thesis (src 31), not the POMA paper (src 9).
- Still missing: **no santur t60, no measured course detuning, no mezrab contact time, and no santur body mode or Q exists in anything opened this pass.** Those rows stay `gap` or `unsupported`, and N.6 says which proxies were confirmed.

**Status legend.**
- `confirmed`: opened directly and matched verbatim.
- `confirmed (low authority)`: the same, but the source is crowd-edited, a blog repost, a maker's how-to or a practitioner's guide. Use these for structure and ranges, and do not defend them as measurement.
- `confirmed (proxy)`: a verbatim match, but about another instrument (piano, clavichord, hammered dulcimer).
- `corrected`: the first pass's value or attribution was wrong, and the fix is given.
- `computed`: this pass's own arithmetic on confirmed inputs. It is not literature.
- `unsupported`: seen only in an abstract, a snippet or a secondary mention, or the host was unreachable. **NOT to be used in code.**
- `gap`: nothing found.

---

### N.1 Structure (strings, courses, bridges, split courses)

| # | Claim | Value | Status | Src |
|---|---|---|---|---|
| S1 | Strings per course | 4 | **confirmed** | 1, 2, 18, 19 |
| S2 | "25 bridges" | Belongs to MusicBrainz's **Indian santoor** entry (`name:santoor`, description id 286). The Persian entry (`name:santur`, id 255) is just "Santur, Middle Eastern". **Do not use 25 for the Persian santur.** | **corrected** (first pass misread the entry) | 7 |
| S3 | Total strings; number of bridges (standard modern Iranian "9-bridge" santur) | 72 strings; 18 bridges in two rows of 9; one movable bridge per course | **confirmed** (luthier src 19/20; program note src 5; Grove-via-patent src 16; Wikipedia src 23) | 5, 16, 19, 20, 23 |
| S3a | Why "9-bridge" and "18 bridges" are the same instrument | Heydarian's "9-bridge" counts **one row**. Náini: "two columns of nine". 9 + 9 = 18 courses, and 18 × 4 = 72 | **computed** from confirmed S1/S3 | 2, 19 |
| S3b | Larger variants | 11- and 12-bridge santurs are "in common use" (11 per row gives 22 courses and 88 strings: **computed**, not stated in any source) | **confirmed** (the variant names); strings **computed** | 1, 2 |
| S3c | Distinct pitches on a 9-bridge santur | 27 note positions (18 courses, with the 9 treble courses sounding on both sides of their bridge). The top F repeats, "creating a total of 25 separate tones" (Wikipedia) | **confirmed** (27: Grove-via-patent, Wikipedia; 25 distinct: Wikipedia only, low authority) | 16, 23 |
| S4 | Intended course tuning | **Exact unison** is the stated norm. Deliberate detuning appears only as an experimental or electroacoustic technique (Kupper 1988). No measured spread exists (see N.2 T-D rows for proxies) | **confirmed** (qualitative); spread **gap** | 5, 19, 30 |
| S5 | Split-course interval (treble courses) | **About 1:2 (octave)**. "In practice the 1:2 ratio is departed from to inflect pitches by a small interval – mainly a quartertone or semitone." The bass-course segments right of the bridge "are not used" | **confirmed**; the first-pass `gap` is closed. Not the Western dulcimer's fifth: src 24 and 28 confirm the fifth (2:3) only for the *hammered dulcimer* | 2, 19 |
| S6 | Unequal string lengths within one course | 36.8–37.8 cm (F5–F6 course); 84.8–86.25 cm (lowest course: labelled "E3" in the thesis, "C3" in the CAA paper; same instrument) | **confirmed** (note label differs between src 2 and 18) | 2, 18 |
| S7 | String diameter | 0.35–0.36 mm measured (Heydarian, Salari santur); gauges .015″–.016″ (.38–.41 mm) recommended (Náini) | **confirmed** | 2, 18, 19 |
| S8 | String material | Bass: brass ("yellow") or phosphor-bronze-plated steel; treble: steel ("white") | **confirmed** | 2, 19 |
| S9 | Bridge | 2.3 cm high, topped by a 2.5 mm metal rod | **confirmed** | 2, 18 |
| S10 | Bass-bridge placement | Top bass bridge 50 mm and bottom bass bridge 130 mm from the right saddle | **confirmed** (low authority: maker's how-to) | 19 |
| S11 | Total static string load | "said to be under a tensile force equivalent to 900+ KG" | **confirmed** as quoted (low authority; the source itself hedges) | 19 |

**Quotes (verbatim):**
- S2 (src 7): `#. name:santoor` / `#: DB:instrument/description:286` / *"Ancient hammered dulcimer with trapezoid walnut or maple soundbox, 25 bridges each with 4 strings that are hit by special mallets called mezrab. Used in traditional, folk and mystic Sufi music."* The Persian entry reads `#. name:santur` / `#: DB:instrument/description:255` / *"Santur, Middle Eastern"*.
- S3 (src 19, Náini, GAL web extra): *"Each musical note is delivered by a course of four strings (seem) tuned exactly to the same pitch. The strings of same course share the same chessman style bridge (kharak). In this design, there are two columns of nine courses each with bass courses on the right, and treble courses on the left. Accordingly, this type of santur is named Nine-bridge (noh-kharak)."* … *"There are 72 strings, hitch pins and tuning pins in a "Nine-bridge" santur."*
- S3 (src 20, Náini, *American Lutherie* #92 public preview): *"The santur provides over three octaves of musical notes (e–f ´´´ or ≈164Hz–1396Hz), with eighteen unison courses of four strings. … There are two columns of nine bridges; bass courses are on the right, treble courses on the left."*
- S3 (src 5, Kupper 1988 program note, machine-translated on the UNT record): *"the santur is a very old instrument consists of a trapezoidal zither carrying seventy-two strings (four per note) fixed and intersecting, supported by eighteen movable easels hardwood, trimmed with a small bar of metal ."*
- S3/S3c (src 16, Bagheri patent body text): *"there are nine, and sometimes eleven quadruples of strings on either side so that with 18 groups of strings 3 octaves or 27 different notes can be played."*
- S3c (src 23): *"A total of 18 bridges divide the santur into three positions. Over each bridge cross four strings tuned in unison … comprising 27 tones altogether. The top "F" note is repeated twice, creating a total of 25 separate tones on the santur."*
- S3b (src 2, fn. 12): *"Modern Iranian santurs have 9 bridges, and 11 and 12-bridge santurs are also in common use [5]."* (src 1: *"Modern Iranian Santurs most often consist of 9 bridges, although 11 or 12-bridge Santurs can be found too."*)
- S4 (src 5): *"First, it is possible for us to detune (or freely tune) all four strings which, by tradition, is a single note of pitch. This clashing of the strings produces extraordinary sound variability. We obtain a set of sounds with stamped colorings, sounds close to those of gongs…"*
- S4 (src 30, VICO santur guide): *"small corrections are made with the tuning hammer to make the four courses sound a unison."*
- S5 (src 2): *"they are divided by a row of bridges situated to the left of centre, essentially in the ratio 1:2, such that the left part of each string sounds essentially an octave higher than its counterpart to the right. In practice the 1:2 ratio is departed from to inflect pitches by a small interval – mainly a quartertone or semitone."*
- S5/S10 (src 19): *"Bass bridges are arranged with the top bridge positioned at 50MM, and bottom bridge at 130MM from the right saddle The musical notes on the right side of bass bridges are not used."* and *"Treble bridges are located at approximately 1/3 distance (center to center) from the left saddle so that the left side of each course produces the pitch of the same note in the next higher octave."*
- S6/S7/S9 (src 2): *"The lengths of the four strings of a note are not exactly the same; they are between 36.8 and 37.8cm for F5-F6, and between 84.8cm and 86.25cm for E3."* … *"The diameters of the strings are between 0.35-0.36mm depending on the age and the tension of the strings. Each bridge is 2.3cm high, and is surmounted a horizontal metal rod of 2.5mm diameter"*. src 18 has the same sentence, ending *"…between 84.8cm and 86.25cm for C3."*
- S7 (src 19): *"Guuages are .015"-.016" (.38MM - .41MM)."* (sic)

### N.2 Sympathetic resonance, decay, and course detuning

| # | Claim | Value | Status | Src |
|---|---|---|---|---|
| R1 | "Sympathetic vibration comes from the undamped string segments between the bridge and tuning pins" | This is a **clavichord** finding (d'Alessandro & Katz), **not** a hammered dulcimer one. The directly read text: the slanted segments *"are not damped with felt"* | **corrected** (attribution); proxy only | 29 |
| R1a | Santur: striking one note excites almost all strings; there is no damping mechanism | qualitative | **confirmed** | 2; 22 (Iraqi santur, low authority) |
| R1b | Santur: long decay with audible sympathetic continuation; hand-muting leaves a "ghost note" | qualitative | **confirmed** (low authority: practitioner guide) | 30 |
| R1c | Cimbalom: steel treble strings in groups of 4 and wound bass strings in groups of 3, all in unison; the concert instrument has dampers | qualitative | **confirmed** (low authority: Wikipedia) | 24 |
| R2 | Near-coincident tuning raises sympathetic amplitude, with veering and extra damping | qualitative | **unsupported** (ResearchGate still 403) | 15 |
| R2a | Clavichord proxy: sympathetic strings act as **high-pass reverberation, mainly above 500 Hz** | 500 Hz | **confirmed (proxy)** | 29 |
| R3 | Measured t60 of santur notes or of the sympathetic strings | none. Santur: "a few seconds" (qualitative). Hammered dulcimer: "decay times of several seconds" (qualitative) | **gap** (the qualitative statements are **confirmed**) | 2, 28 |
| R4 | Higher partials decay faster than lower ones (santur A4 spectrum) | qualitative | **confirmed** | 2 |
| T-D1 | Hammered dulcimer: courses "rarely in perfect unison", so a chorus effect results | qualitative | **confirmed** (low authority: Wikipedia) | 24 |
| T-D2 | Piano proxy (Weinreich): in a midrange model two strings **lock with no beats unless mistuned by more than about 0.3 Hz**. Below that, mistuning sets the aftersound level | 0.3 Hz; 0.64 Hz example | **confirmed (proxy)** | 25 |
| T-D3 | Proxy simulation (Woodhouse, 2-string C4): mistuning of 0.1–1 cent reshapes the double decay. Pitch change becomes audible only at "2 cent or more". At 5 cents there is "clear evidence of beating" | 0.1–5 cents | **confirmed (proxy; simulation, not measurement)** | 27 |

**Quotes (verbatim):**
- R1 (src 29, LIMSI report): *"In the clavichord the slanted strings between the bridge and the tuning pins are not damped with felt, unlike in the piano."* R2a: *"These strings appear to provide high-pass reverberation: being mainly effective above 500 Hz."*
- R1a (src 2): *"Furthermore, almost all strings on the santur are excited when any one note is struck by a stick."*, and the dataset footnote *"The non-struck santur strings were not artificially dampened."* (src 22, Iraqi santur): *"There is no damping mechanism, so the sound of the struck melody notes is accompanied by the sympathetic vibrations of the other strings."*
- R1b (src 30): *"The santur has a long decay, and there are sounds which continue sympathetically after a note is struck."* and *"One strikes the note and mutes with a hand. The result is a clipped sound, but with a ghost note, which is the sympathetic resonance of the original note on other open strings."*
- R1c (src 24, Cimbalom): *"The steel treble strings are arranged in groups of 4 and are tuned in unison. The bass strings which are over-spun with copper, are arranged in groups of 3 and are also tuned in unison."*
- R3 (src 2): *"The instrument has a sustained sound which can be heard for a few seconds after a note is played."* (src 28, Peterson, American hammered dulcimer): *"The resulting tone is strong, generally undamped, but sometimes "muddy" because of decay times of several seconds."*
- R4 (src 2): *"It can be seen that the sound is rich in harmonics and that the higher harmonics decay faster through time."*
- T-D1 (src 24): *"Each set of strings is tuned in unison and is called a course. As with a piano, the purpose of using multiple strings per course is to make the instrument louder, although as the courses are rarely in perfect unison, a chorus effect usually results like a mandolin."*
- T-D2 (src 25): *"For this case, there are no beats unless the "mistuning" is more than about 0.3 Hz; more correctly, for smaller "mistunings" there is just a single "beat null," followed by a beatless aftersound whose level depends on the "mistuning.""*
- T-D3 (src 27): *"the decay profile of both sets of notes can be tailored by tiny tuning adjustments, too small to be audible as pitch changes (which requires 2 cent or more). When the mistuning reaches its highest level, 5 cent, we see clear evidence of beating in both plots."*

### N.3 Mallet (mezrab) and excitation

| # | Claim | Value | Status | Src |
|---|---|---|---|---|
| M1 | Mezrab: light hardwood; tips bare or covered with felt, cotton or leather | walnut or narenj (citrus) wood (Heydarian); rosewood-type hardwood (Náini) | **confirmed** | 1, 2, 16, 18, 19 |
| M1a | Felt compresses with use and gets thinner and harder | qualitative | **confirmed** | 2, 18 |
| M1b | Mezrab mass | **4 g** | **confirmed (low authority: maker's article)**. Single source | 19 |
| M1c | Mezrab head dimensions | 22–24 mm × 30 mm | **confirmed (low authority)** | 19 |
| M2 | Persian hammers "do not rebound"; tremolo comes from alternating wrists. Heavy hammers that bounce belong to "the Vietnamese and Indian instruments" | qualitative | **corrected**: first pass said "Turkish and Indian"; New Grove 1984, as quoted in the patent, says "Vietnamese and Indian". **Contradicted** by src 30 ("the mallets need to bounce off the strings to obtain a good tone") and by src 26's general "fast bouncing of the lightweight hammers" (said of the Kashmiri santoor) | 16, 22, 26, 30 |
| M3 | Hammer hardness and strike position shape the attack (hammered dulcimer) | qualitative | **unsupported** (src 8 is AIP, 403) | 8 |
| M3a | Strike position | "Courses are struck 30-40MM from the bridges" | **confirmed (low authority)** | 19 |
| M4 | Piano proxy: contact time falls from about 4 ms in the bass to under 1 ms in the top treble, and shortens with dynamic level (about ±20 % over p–ff for a midrange note) | 4 ms → <1 ms; ±20 % | **confirmed (proxy)**. Piano hammers are felted, heavier and key-driven; do not transfer the numbers | 13 |
| M4a | Proxy simulation parameters "in the right kind of range for a santoor or hammered dulcimer": steel string 0.5 mm × 0.4 m at A4, all-mode Q = 1000, **1 g equivalent point mass**, linear contact spring 20 kN/m. A 1 g point mass on one string stands for "an actual hammer with a mass of the order of 10 g" on a multi-string course | as listed | **confirmed (simulation inputs chosen by the author, not measurements)** | 26 |
| M4b | Woodhouse's scaling (equivalent point mass ≈ 1/3 of the rod mass, divided by the strings per course) applied to a 4 g mezrab on a 4-string course | ≈ 0.33 g equivalent point mass per string (4 g × 1/3 ÷ 4) | **computed** (it assumes Woodhouse's hinged uniform-rod model; a mezrab held between the fingers may not fit it) | 19, 26 |
| M4c | American hammered dulcimer hammer mass | 10 g | **confirmed** (another instrument; context for M1b) | 28 |
| M5 | Contact time, felt stiffness or bounce statistics of a real mezrab | none found | **gap** | — |

**Quotes (verbatim):**
- M1 (src 1): *"The Mezràb are usually coated by a piece of cotton or leather. The body of the Santur is made of walnut and the Mezràb are made of either walnut or Narenge (a citrus wood)."*
- M1a (src 2): *"The striking ends of the sticks are usually coated by felt, and the repeated impact over time compresses the felt, making it thinner and harder."*
- M1b/M1c (src 19): *"Santur is played with a couple of wooden hammers (mezrab), much thinner, lighter (4 grams) and traditionally more ornate than those of American hammered dulcimers. The short dimensions of santur hammers (22-24MM×30MM) make it possible to use just about any types of dry light, very dense but high resonance hard wood like rosewood. The striking surface tips of hammers are sometimes covered with felt to produce a milder sound."*
- M2 (src 16, patent quoting *New Grove Dictionary of Musical Instruments*, 1984, pp. 291–292): *"the hammers of the Vietnamese and Indian instruments are heavy and bounce on the string, creating a characteristic automatic tremolo. the very light Persian hammers do not rebound and the tremolo is controlled solely by a rapid alternating movement of the right and left wrists. tradition calls for a delicate and precise tone-quality which is obtained only with light hammers of hardwood. Some players wrap the ends of the hammers with felt to soften the impact."* (src 22 reposts a later version of the same entry: *"The hammers do not rebound and the tremolo is controlled solely by a rapid alternating movement of the right and left wrists."*)
- M2 contra (src 30): *"The traditional mallets are not constructed to be held in one hand, and the mallets need to bounce off the strings to obtain a good tone."*
- M3a (src 19): *"Courses are struck 30-40MM from the bridges."*
- M4 (src 13): *"the contact durations decrease from about 4 ms in the bass to less than 1 ms in the highest treble."* and *"The variation in contact duration within a comfortably accessible dynamic range (p - ff) is about +/- 20 % compared to mezzo forte."*
- M4a (src 26): *"We will choose a steel string with diameter 0.5 mm and length 0.4 m, tuned to the note A4 (440 Hz). All modes of this string are given the same Q-factor, with the value 1000. We will strike this string with a 1 g mass…"*; *"The model includes a linear contact spring with stiffness 20 kN/m."*; *"So an actual hammer with a mass of the order of 10 g would have a similar effect to my 1 g mass in the simulated cases."*; and on strike position: *"a player of the santoor or hammered dulcimer normally strikes the strings much closer to the bridge"* (the article simulates 1/10 of the length).
- M4c (src 28): *"Players hit the strings, hence the name, with light (10 grams) wooden hammers."*

### N.4 Body / soundboard (santur itself: construction only, no modes)

| # | Label | Value | Status | Src |
|---|---|---|---|---|
| B0 | Santur body dimensions (9-bridge Salari santur) | parallel sides 90.5 cm and 34.8 cm; diagonals 38.9 and 39.0 cm; top-to-back 6.4 cm; top 5.5 mm, back 8.0 mm thick; two sound holes 5 cm in diameter | **confirmed** | 2, 18 |
| B0a | Internal support | 4 rails (bars) plus sound posts (Heydarian); 4 support soundposts plus 1 "tuning" soundpost (Náini). The tuning post "regulate[s] the echo characteristics of the soundboard" to even out volume across courses | **confirmed** (the tuning-post role is **low authority**) | 2, 19 |
| B1 | Yangqin soundboard modes, 100–700 Hz | band only | **unsupported** (src 11 is AIP, 403) | 11 |
| B2 | Yangqin construction (crowned 4 cm, 7 ribs, 8 chambers) | — | **unsupported** (src 11 is AIP, 403) | 11 |
| B2a | A modern yangqin (model 410): 47 courses, 134 strings, 5 bridges | counts | **confirmed** (a different instrument; no body modes in the paper) | 12 |
| B3 | Hammered dulcimer: a (1,0) soundboard mode on 3 of 4 instruments; the brace sits near its nodal line | no Hz | **corrected** attribution: the quote is from **Canfield 1996, NIU MS thesis** (src 31), not from the POMA paper (src 9). Still **unsupported**: only the abstract was read, and the PDF is behind a Cloudflare challenge | 31 |
| B3a | Hammered dulcimer: "little modal response … at the fundamental frequencies of the lowest notes"; sound holes radiate significantly | qualitative | **unsupported** (search summary of src 9/32; both unreachable) | 9, 32 |
| B4 | Any measured santur body mode or Q | — | **gap** | — |

**Quotes (verbatim):**
- B0 (src 2): *"The parallel sides of a 9-bridge santur made by Salari, on which the measurements presented here were taken are 90.5cm and 34.8cm long. The diagonal sides (left and right) are almost equal in length: 38.9cm and 39.0cm. The top (soundboard) and the back plates (upper and lower boards) are 6.4cm apart. Their thicknesses are 5.5mm and 8.0mm respectively."* … *"Two sound holes (Fig. 4-c), in this case 5cm in diameter, influence the timbre and serve to enhance the sound quality."*
- B0a (src 2): *"Four rails (bars) of wood, along with sound posts (rigid wooden props), support the top plate a fixed distance above the back plate."* (src 19): *"Santur has 4 support soundposts and one tuning post inside the sound box"*, and *"The purpose of this soundpost is to regulate the echo characteristics of the soundboard so that a pleasant and uniform volume is produced by all santur courses."*
- B2a (src 12): *"It consists of 47 courses (sets) of 134 strings on 5 bridges."*
- B3 (src 31, abstract on the landing page, verbatim): *"A (l,0)-mode of the soundboard was found in three of the instruments, and for the professionally made instruments, the soundboard brace divided the soundboard into two similarly sized regions and accentuated the (1,0) mode by having the brace near a nodal line."* The "back plate as a second soundboard" sentence from the first pass was **not** in that abstract. It stays unsupported, with its source unknown.

### N.5 Range, reference pitch, inharmonicity, tuning

| # | Claim | Value | Status | Src |
|---|---|---|---|---|
| T1 | Range of the **9-bridge** santur | E3 (164.8 Hz) – F6 (1396.9 Hz); the thesis gives the bottom as E3q (160.1 Hz). The lowest course is usually retuned to C3 (130.8 Hz) | **corrected**: the first pass's "C3–F6" is the **11-bridge** range; the 9-bridge range reaches C3 only by retuning one course | 2, 18, 19, 20 |
| T1a | Range of the **11-bridge** santur | C3 (130.8 Hz) – F6 (1396.9 Hz); lowest string usually C#3 (138.6 Hz) or A2 (110 Hz) | **confirmed** | 1, 2 |
| T1b | Register layout (9-bridge, G-tuned) | bass courses E3–F4; treble right side E4–F5; treble left side E5–F6 | **confirmed (low authority)** | 19 |
| T1c | Hz values in the sources are **computed from A4 = 440 Hz** (12/24-TET), not measured | — | **confirmed** | 2 |
| T1d | Reference pitch | "nowadays most musicians tune their instruments to A4 = 440 Hz using electronic tuners", but the reference traditionally follows the singer | **confirmed**; closes the first pass's "reference pitch never sourced" gap | 2 |
| T2 | 13 principal notes: 7 diatonic, 3 semitones, 3 quartertones; koron = half-flat, sori = half-sharp | qualitative | **confirmed** | 1, 2 |
| T3 | Plus tone | 260–280 cents (270 average) | **confirmed** against the thesis (the values are Farhat's; his book was not opened) | 2 |
| T4 | Neutral tones: small 125–145 (135 avg), large 150–170 (160 avg); semitone about 90; Persian whole tone about 204 cents | as listed | **confirmed** against the thesis (Farhat's values; book not opened) | 2 |
| T5 | Measured vocal intervals (Karimi, dastgah shur): C–D 206, D–Ek 137, Ek–F 148 cents; SD 5–8 cents (first tetrachord) vs 11–14 cents (F–G, G–Ak, Ak–Bb) | as listed | **confirmed** (vocal, not santur) | 3 |
| T5a | The intermediate interval is "not fixed but flexible", depending on gushe or performer | qualitative | **confirmed** | 4 |
| T6 | Measured santur pitch stretch | 1st and 2nd octaves compressed by 11 and 28 cents; 3rd octave stretched by 20 cents | **confirmed** (new) | 2, 18 |
| T7 | Santur inharmonicity coefficient | **B ≈ 0.00031** (average over notes and partials; 9-bridge Salari santur). B rises toward the top of each register | **confirmed** (new) | 2, 18 |
| T8 | Per-dastgah santur retuning table in text | Figures only (src 1 fig. 3/4; src 2 fig. 1, 16); not extractable as text | **gap** (as text) | 1, 2 |

**Quotes (verbatim):**
- T1 (src 2): *"The tone range of the 9-bridge santur is E3q (160.1 Hz)-F6 (1396.9 Hz), which is usually extended downwards by tuning the first (lowest-pitched) course of strings down to C3 (130.8 Hz) rather than E3q."* (src 18): *"The tone range is: E3 (164.8 Hz)-F6 (1396.9 Hz), while the first bass note is usually tuned at C3 (130.8 Hz), instead of E3."*
- T1a (src 2): *"In comparison with the 9-bridge santur, the range of the 11-bridge santur starts a third lower, from C3 (130.8 Hz) to F6 (1396.9 Hz), although the lowest string is usually tuned to C#3 (138.6 Hz) or A2 (110 Hz)."* (src 1): *"An 11-bridge Santur has a tone Range from C3 (130.8 Hz) to F6 (1396.9 Hz)."*
- T1b (src 19): *"The nine phosphorous bronze alloy (bass) string courses provide (E3 – F4 or mi3-fa4). The middle and higher octaves are played on the steel (treble) courses in the middle and left side which are tuned to (E4 – F5 or mi4-fa5) and (E5 – F6 or mi5-fa6) respectively."*
- T1c (src 2): *"Table 4 shows the f0 of santur notes, calculated with reference to A4 = 440 Hz concert pitch."*
- T1d (src 2): *"The Persian reference pitch, which customarily depends on the comfortable range of a singer or accompanying instrument(s), is not necessarily standard concert pitch, although nowadays most musicians tune their instruments to A4 = 440 Hz using electronic tuners."*
- T2 (src 2): *"A total of thirteen principal notes are needed to play in all of the Persian modes: seven diatonic notes, three semitones, and three quartertones [5]."*
- T3/T4 (src 2): *"Small neutral tone (three-quartertone) between 125 and 145 cents (135 cents average)"*; *"Large neutral tone (three-quartertone) between 150 and 170 cents (160 cents average)"*; *"Plus-tone (five-quartertone): greater than a wholetone but smaller than three semitones; between 260 and 280 cents (270 cents average)."*
- T5 (src 3): *"The interval between C and D is 206 cents, the interval between D and E-koron is 137 cents and the interval between E-koron and F is 148 cents."*
- T5a (src 4): *"an intermediate one between the previous two, whose specific width is not fixed but flexible [1], depending on guše or even performer"*.
- T6 (src 2; src 18 is near-identical): *"The measurements show that the first and second octaves are compressed by 11 and 28 cents respectively, while the third octave is stretched by 20 cents."*
- T7 (src 2): *"The average value of the inharmonicity factor obtained for a 9-bridge Salari santur is 0.00031. This can be compared with the inharmonicity factor of a piano, which is around 0.0004 [58]."*, and *"As one moves towards higher notes of each tone area (bass, middle and treble), the inharmonicity factor increases"*.

**Note on Grove's range notation.** New Grove, as quoted in src 16, gives the three series as *"e'-f"… e"'-f""*. In Helmholtz notation (e' = E4) that would put the instrument an octave above the Hz-bearing sources. Treat this as a notation mismatch in the quoted encyclopedia text and use the Hz values of src 2/18/20.

### N.6 What the literature cannot give (after this pass)

- **Decay (t60).** No source opened gives a t60 or decay rate for any santur note, struck or sympathetic. The confirmed statements are qualitative: "heard for a few seconds" (santur), "decay times of several seconds" (hammered dulcimer), and higher partials decay faster. Heydarian lists attack and decay curves as future work. Two candidate sources could not be opened: the hammered-dulcimer comparison paper (src 10, ResearchGate) and the cimbalom measurements (src 14, members-only).
- **Course detuning.** Every santur source that addresses it says the four strings are tuned to **exact unison**. Deliberate detuning is described only as an electroacoustic experiment (Kupper). No measured spread in cents exists for santur, cimbalom, dulcimer or yangqin. The cent and Hz thresholds in T-D2/T-D3 are **piano proxies** (one model, one simulation). They bound what a spread knob can plausibly do; they are not santur data.
- **Sympathetic energy.** Confirmed: almost all strings are excited by any strike, and nothing damps them. Unmeasured: how much energy the sympathetic strings take up, and their frequency weighting. The one number, "mainly effective above 500 Hz", is for the **clavichord**, whose sympathetic segments are short bridge-to-pin lengths. The santur's sympathetic set is the other *playing* courses, so do not transfer it. The unplayed bass-course segments right of the bridge (50–130 mm, S10) exist, but no source says whether they ring audibly.
- **Mezrab contact.** One mass figure (4 g) comes from a single maker's article. Head size is known. **No contact time, felt stiffness or bounce data exist.** The sources also disagree on whether the Persian mezrab rebounds (M2). The piano numbers (M4) and Woodhouse's simulation inputs (M4a) are proxies or modelling choices, not mezrab measurements.
- **Body.** Construction dimensions are confirmed (B0). **No santur modal frequency or Q exists** in anything opened. Every dulcimer and yangqin modal source is AIP (403) or behind Cloudflare (Canfield, BYU). Search results name a study of "tar, setar and santur" radiation that reportedly obtained mode shapes for tar and setar but not santur; it was not opened and is not cited.
- **Inharmonicity per note.** Only the **average** B = 0.00031 appears in text. The per-note values are in a figure (src 2 fig. 13; src 18 fig. 4) and were not digitised.
- **Split-course accuracy.** The treble split is "essentially" 1:2, deliberately off by up to "a quartertone or semitone". No source says how far from 1:2 an uninflected course sits in practice.
- **Per-dastgah retuning tables.** They exist only as figures (src 1, 2).

### N.7 Sources and verification status

Source numbers 1–17 keep the first pass's numbering; 18 onward are new.

| # | Citation | URL | Read directly |
|---|---|---|---|
| 1 | Heydarian, P. & Reiss, J. D. (2005). "The Persian music and the santur instrument." Proc. ISMIR 2005, 524–527. | https://ismir2005.ismir.net/proceedings/2120.pdf | **Yes** (full text) |
| 2 | Heydarian, P. (2016). *Automatic recognition of Persian musical modes in audio musical signals.* PhD thesis, London Metropolitan University. (The first pass mislisted the title.) | https://repository.londonmet.ac.uk/1190/1/HeydarianPeyman%20-%20%20PhD%20Full%20Thesis.pdf | **Yes** (full text) |
| 3 | Shafiei, S. (2021). "An analysis of Iranian Music Intervals based on Pitch Histogram." arXiv:2108.01283. | https://arxiv.org/pdf/2108.01283 | **Yes** |
| 4 | Nikzat, B. & Caro Repetto, R. (2022). "KDC: an open corpus for computational research of dastgāhi music." ISMIR 2022. | https://archives.ismir.net/ismir2022/paper/000038.pdf | **Yes** |
| 5 | Kupper, L. (1988). "Santur Électroacoustique" (program note, machine-translated). UNT Digital Library, MISAME. | https://digital.library.unt.edu/ark:/67531/metadc1586056/ | **Yes** (record text; the audio is UNT-only and was not accessed). Low authority for acoustics |
| 6 | Heydarian, P. & Jones, L. "Measurement and calculation of the parameters of santur" (ResearchGate record). | https://www.researchgate.net/publication/297510696 | **No** (403). **Superseded by src 18**, an open copy of the same paper |
| 7 | MetaBrainz. MusicBrainz `po/instrument_descriptions.pot`: entries `name:santoor` (id 286) and `name:santur` (id 255). | https://raw.githubusercontent.com/metabrainz/musicbrainz-server/master/po/instrument_descriptions.pot | **Yes** (crowd-edited) |
| 8 | "Acoustics of the hammered dulcimer, its history, and recent developments." JASA 95(5) Suppl., 3002 (1994). | https://pubs.aip.org/asa/jasa/article/95/5_Supplement/3002/662645/ | **No** (403) |
| 9 | Christensen, B. Y., Gee, K. L., Anderson, B. E. & Wall, A. T. "Modal response and sound radiation from a hammered dulcimer." POMA 14, 035001. DOI 10.1121/1.4865242. OpenAlex lists it as closed access. | https://pubs.aip.org/asa/poma/article/14/1/035001/994614/ | **No** (403) |
| 10 | "A method for a acoustical comparison of the hammered dulcimer" (ResearchGate record 265353459). | https://www.researchgate.net/publication/265353459 | **No** (403) |
| 11 | "Acoustics of a yangqin." JASA 89(4B) Suppl., 1878 (1991). | https://pubs.aip.org/asa/jasa/article/89/4B_Supplement/1878/657941/Acoustics-of-a-yangqin | **No** (403) |
| 12 | Sinin, A. E. et al. (2026). "The Yangqin: Acoustical Study of a Chinese Dulcimer." BioResources 21(1), 1084–1097. | https://bioresources.cnr.ncsu.edu/wp-content/uploads/2025/12/BioRes_21_1_1084_Sinin_HJSM_Yangqin_Acoustic_Study_Chinese_Dulcimer_24951-1.pdf | **Yes**. It has no body modes, decay or detune data, and its "deviation D" metric is not a standard inharmonicity coefficient; not used for any number |
| 13 | Askenfelt, A. & Jansson, E. "String contact duration and dynamic level." KTH, *Five lectures on the acoustics of the piano* (1990). | https://www.speech.kth.se/music/5_lectures/askenflt/stricont.html | **Yes** |
| 14 | Pap, J. (2000). "The Acoustical Characteristics of the Concert Cimbalom." *American Lutherie* #61. | https://luth.org/2000_0189500-pap-cymb/ | **Partly**: the public opening only; the body is members-only and was not accessed |
| 15 | d'Alessandro, C. & Katz, B. F. G. (2004). "Tonal quality of the clavichord: the effect of sympathetic strings." ISMA'04 (ResearchGate record 236213186). | https://www.researchgate.net/publication/236213186 | **No** (403). A summary by the same group is src 29 |
| 16 | Bagheri, P. US Patent 4,706,539, "Santur". Quotes New Grove *Dictionary of Musical Instruments* (1984) pp. 291–292. | https://patents.google.com/patent/US4706539A/en | **Yes** (Google Patents text; the USPTO PDF is image-only) |
| 17 | Songbird Dulcimers. "9 Dulcimer Hammer Styles From Around the World." | https://songbirdhd.com/9-dulcimer-hammer-styles-from-around-the-world/ | **Yes**. It does **not** contain the first-pass M1/M2 sentences; those came from src 16's Grove text. Qualitative only ("extremely light, delicate hammers"); not used for any value |
| 18 | Heydarian, P. & Jones, L. (2008). "Measurement and calculation of the parameters of Santur." *Canadian Acoustics* 36(3), 86–87. | https://jcaa.caa-aca.ca/index.php/jcaa/article/download/2050/1797 | **Yes** |
| 19 | Náini, J. (2007). "Introducing Santur." Guild of American Luthiers web extra to *American Lutherie* #92. | https://luth.org/journal/american-lutherie-92-winter-2007/web-extras-american-lutherie-92-winter-2007/web-extras-american-lutherie-92-winter-2007-the-santur/ | **Yes** (free page). A maker's construction guide: low authority for acoustics |
| 20 | Náini, J. (2007). "The Santur." *American Lutherie* #92. | https://luth.org/2007_0247400-santur-al92/ | **Partly**: the public preview only |
| 21 | (reserved; not used) | — | — |
| 22 | Saxonian Folkways blog, "The Art Of The Persian Santur" (repost of an encyclopedia "Santur" entry and of src 19). | https://saxonianfolkways.wordpress.com/category/the-art-of-the-persian-santur/ | **Yes**. Low authority: an unattributed repost |
| 23 | Wikipedia, "Santur" (oldid 1372335718). | https://en.wikipedia.org/wiki/Santur | **Yes** (page HTML) |
| 24 | Wikipedia, "Hammered dulcimer" and "Cimbalom" (API plain-text extracts). | https://en.wikipedia.org/wiki/Hammered_dulcimer ; https://en.wikipedia.org/wiki/Cimbalom | **Yes** |
| 25 | Weinreich, G. "The coupled motion of piano strings: 'Mistuned' strings." KTH, *Five lectures on the acoustics of the piano* (1990). | https://www.speech.kth.se/music/5_lectures/weinreic/mistuned.html | **Yes** |
| 26 | Woodhouse, J. Euphonics §12.2, "Hitting strings: the piano and its relatives." | https://euphonics.org/11-2-hitting-strings-the-piano-and-its-relatives/ | **Yes** |
| 27 | Woodhouse, J. Euphonics §7.3, "Multiple strings and double decays." | https://euphonics.org/7-3-multiple-strings-and-double-decays/ | **Yes** |
| 28 | Peterson, D. R. (1996). "The American hammered dulcimer: Its acoustical properties…" ASA lay-language paper 4aMUb5. | https://acoustics.org/pressroom/httpdocs/132nd/4amub5.html | **Yes** |
| 29 | LIMSI-CNRS 2007 Scientific Report, "On the acoustics of the clavichord" (d'Alessandro, Katz et al.). | https://rs2007.limsi.fr/PS_Page_3.html | **Yes** |
| 30 | Vancouver Inter-Cultural Orchestra, "Santur" (Instrument Corner; informant santurist Navid Goldrick). | https://vi-co.org/resources-study/instrument-corner/santur/ | **Yes** (HTML page; the PDF version returned 403). Practitioner guide: low authority |
| 31 | Canfield, G. H. (1996). "Acoustics of the hammered dulcimer." MS thesis, Northern Illinois University. | https://huskiecommons.lib.niu.edu/allgraduate-thesesdissertations/975/ | **Abstract only**. The PDF is behind a Cloudflare challenge; not bypassed |
| 32 | Christensen, B. & Gee, K. (2013). "Sound Radiation from a Hammered Dulcimer." BYU *Journal of Undergraduate Research*. | https://scholarsarchive.byu.edu/cgi/viewcontent.cgi?article=6531&context=jur | **No** (Cloudflare challenge) |

**Read and excluded:**
- Ramsey & Pomian, ASA 167 lay paper (`acoustics.org/pressroom/httpdocs/167th/1pMU5_Ramsey.html`). Its body resonances at 80, 110, 236, 362 and 408 Hz are for a **mountain (lap) dulcimer**, a fretted plucked zither, not a hammered one. Do not use them.
- `rareinstrument.com/santur-instrument/`. A marketing page ("chorused sparkle"); nothing citable.
- The hammered-dulcimer patent US 6,483,016 (Google Patents text). Qualitative sustain discussion only.

**Verification queue for a future pass (only with legitimate access, e.g. institutional credentials; never by getting around bot checks):**
1. Src 31 (Canfield thesis PDF) and src 9/32 (Christensen & Gee): dulcimer body-mode Hz and radiation.
2. Src 14 (Pap, cimbalom; members-only) and src 10: decay and sympathetic numbers.
3. Src 11 (yangqin 1991): body modes 100–700 Hz.
4. Src 15 (d'Alessandro & Katz): frequency-band decay times for sympathetic strings.
5. Digitise src 2 fig. 13 / src 18 fig. 4 for per-note B, if per-note dispersion matters.

---

### N.8 Modelling implications (MY SYNTHESIS, not sourced; numbers are marked by where they come from)

1. **Layout is sourced, so hard-code it.** 18 courses × 4 strings. Nine bass courses are struck left of their bridge. Nine treble courses sound on **both** sides of their bridge at about 1:2, giving 27 note positions. The default range is E3 (164.8 Hz) to F6 (1396.9 Hz), with a preset option to drop the lowest course to C3 (130.8 Hz) (S3, S5, T1). The reference defaults to A4 = 440 Hz (T1d), with a global offset for singer-referenced tuning. Give each treble course a **per-side cents offset**, because the split is deliberately off-octave by up to a quartertone or semitone (S5).
2. **Four loops per course, default near-unison.** The sources say courses are tuned to exact unison (S4). So set the default spread well below audibility: the piano proxies suggest that ≲1 cent shapes the double decay without audible beating, and that beating is obvious by about 5 cents (T-D3). Keep "spread" as a preset knob seeded per note for deterministic renders. A wide-detune "gong" preset (Kupper, S4) is legitimate as an extended technique, not as the default sound. The tiny mistunings only give a double decay if the four loops are **coupled through a shared bridge admittance** (T-D2, T-D3). Four independent loops summed would give beating but no aftersound.
3. **Dispersion.** Use B = 0.00031 (T7) as the default stiffness term. This is the one confirmed santur string number, so it is the first candidate for a code constant. The confirmed pitch stretch (T6: −11 and −28 cents across the lower two octaves, +20 cents across the top) is a **tuning** fact, not a dispersion fact. Apply it as an optional per-register tuning curve, not by changing B.
4. **Mezrab.** Replace the first pass's fixed raised-cosine τ with either a mass–spring contact or a τ knob:
   - A mass–spring contact would be seeded from Woodhouse's santoor-range inputs (M4a: point mass ≈ 1 g, contact spring 20 kN/m). The computed equivalent for a 4 g mezrab on a 4-string course (M4b, ≈ 0.33 g) is a second seed, but its model assumption is shaky.
   - Either way, **contact time is a knob**, because no mezrab contact time exists (M5).
   - Strike position is sourced as a distance: 30–40 mm from the bridge (M3a). β must be computed per course from that course's vibrating length, not set as one global ratio.
   - Treat rebound as a **preset choice**, since the sources conflict (M2): default to a single contact; a "bouncy" preset can add a second, weaker contact.
   - Felt is sourced as optional and wearing harder over time (M1, M1a). Model it as a softer contact and a lowpass on the force.
5. **Sympathetic bank = the other 17 courses, undamped** (R1a). Drive it from the summed bridge signal and specify it by t60, with `Q ≈ f·t60/2.2`. **t60 is not sourced** (R3). Leave it a knob whose default gives "a few seconds" at mid-range (R3 is qualitative). Make higher partials decay faster (R4). Do not import the clavichord's ">500 Hz" figure (R2a) as a filter corner. At most use it as a reason to offer a high-pass tilt knob. Retune the bank with the dastgah preset (T2–T4), so only coincident partials bloom.
6. **Body.** Still no santur modes (B4). Use a neutral short body filter or none. The confirmed dimensions (B0: 90.5 × 34.8 cm trapezoid, 6.4 cm deep, 5.5 mm top, two 5 cm holes) could feed a **computed** Helmholtz or plate estimate later. Label any such estimate `computed`, never measured, and use no yangqin or guitar values.


---

## 5. Tuning systems for SILK's TUNE macro — sourced, not recalled

**Date:** 2026-09-27 (second pass) · Standard: `docs/superpowers/plans/2026-09-25-pluck-depth-body-research.md` §0.

**Status labels.** Each label says what was actually opened. **Unsupported values are NOT to be used in code.**

| Label | Meaning |
|---|---|
| `confirmed` | Read directly in the source that makes the claim: a spec page, a paper's own measurement, or an encyclopedia's own statement. The text is quoted verbatim. |
| `secondary` | Read directly, but in a source that reports *someone else's* numbers (for example, Farhat's fret measurements as tabled by Shafiei 2021). The original was not opened. |
| `dataset` | Confirmed against an open research dataset file (DaMuSc, ORD-CC32 via `scale-library`). The book behind the row was not opened. |
| `derived` | Arithmetic on a confirmed, secondary or dataset value. The arithmetic is shown. |
| `corrected` | The first pass (or the spec's table) got this wrong. The fix is given. |
| `unsupported` | Seen only in a snippet, or the source could not be opened. **Not for code.** |

**Access this pass.** These hosts were reached and read directly:

- `huygens-fokker.org`: the `.scl` format page and the Scala help file.
- `arxiv.org`: abstract pages, PDFs and the site search. The export API returned an error, so it was not used.
- `zenodo.org`: the ORD-CC32 record's metadata, through the API. The first request timed out and the second succeeded. The 356 MB data zip was **not** downloaded.
- `maqamworld.com`: the maqam, jins, overview and instrument pages.
- `en`, `ja` and `zh.wikipedia.org`: ordinary article pages, fetched one at a time with a pause between requests. The MediaWiki API answered `429` (rate limit), so it was dropped rather than retried.
- `midi.org`: the public MIDI Tuning page.
- `etheses.whiterose.ac.uk`: the Shi 2014 guzheng thesis PDF.
- `github.com`: `scale-library` and DaMuSc were re-cloned to re-check rows.

These were **not** reached, and nothing was tried to get around the block:

- The MIDI Tuning spec PDF on midi.org is behind "Join Us to Download" (membership).
- `dl.edi-info.ir` returned a proxy 403. It hosts the Farhat 2012 *Introduction to Persian Music* PDF.
- `web.archive.org` reset the connection.
- `digitalcommons.wku.edu` returned 403 (the WKU guzheng tuning PDF).
- ResearchGate and pubs.aip.org were not tried; they are bot-gated.

**No book was opened.** That covers Rechberger 2018, Farhat 1990/2004, Talai 1993, Vaziri 1934, Hewitt 2013, Malm, Touma, Marcus, Lambert & Cordéreix 2015 and Ellis 1885. So a "dataset" row still stands on DaMuSc's transcription of the book. What changed in this pass is that most rows now also have an independent directly-read cross-check: Maqam World, Farhat via Shafiei, Karimi's measured performance, the 1932 recordings, or Wikipedia.

---

### 1. Arabic maqam — 24-EDO convention vs. measured practice

**Theory rows (DaMuSc, from Rechberger 2018).** These were re-read this pass in `Data/theory_scales.csv` and `Data/octave_scales.csv`. Each maqam is stored twice, as 24-EDO quarter-tone units and as 53-EDO comma units, each with an ascending row and a descending row. One unit is 50.0 ¢ in 24-EDO and 22.641509 ¢ in 53-EDO. **New this pass:** DaMuSc's computed `OT` scale is built from the *ascending* row for Rast, Bayati and Saba, but from the *descending* row for Sikah (`OT0298`, `OT0485`) and Hijaz 53 (`OT0475`). The first pass and the spec took two of those computed scales at face value.

| Maqam | 53-EDO row, asc (desc) | Asc cents | Desc cents | Maqam World cross-check (page playback, see below) | Status |
|---|---|---|---|---|---|
| Rast | `9;7;6;9;9;7;6` (`9;7;6;9;9;4;9`) | 0 204 362 498 702 906 1064 | … 906 **996** | asc 0 204 **355** 498 702 906 1064; desc B♭ 996 | dataset (`OT0441` = asc). Agrees with Maqam World within 7 ¢ on every degree. |
| Bayati | `6;7;9;9;6;7;9` (`6;7;9;9;4;9;9`) | 0 136 294 498 702 838 996 | … 702 **792** 996 | 0 **151** **281** 498 702 792 996 (Nahawand on 4th); alt. 6th 860 (Rast on 4th) | dataset (`OT0468` = asc). The 6th has **two sourced forms**; see the note after this table. |
| Hijaz | `5;13;4;9;7;6;9` (`4;13;4;9;4;14;4`) | 0 113 408 498 702 860 996 | does **not** close: sums to 52 commas | 0 123 425 498 702 792 996; alt. 6th 860 | asc derived from the raw row. **corrected:** the spec says "DaMuSc's 53-comma row does not close the octave". Only the *descending* row fails (4+13+4+9+4+14+4 = 52), and it is the row `OT0475` was computed from. The ascending row closes at 53. |
| Sikah | `6;9;9;7;6;9;7` (`6;9;9;4;9;9;7`) | 0 136 340 543 **702** 838 1042 | 0 136 340 543 **634** 838 1042 | 0 133 337 540 **698** 835 1039 | **corrected.** The spec's SIKAH row (…543 **634** 838…) is the *descending* row (`OT0485` step intervals `136;204;203;91;204;204;158`). The ascending row, and Maqam World's notation (tonic E½♭, 5th B½♭), put the 5th at 702 ¢. Maqam World playback agrees with the ascending row within 4 ¢ on every degree. |
| Saba | `6;7;4;14;4;9;9` | 0 136 294 385 702 792 996 | — | not checked | dataset (`OT0476`) |

24-EDO rows are unchanged from the first pass: Rast `4;3;3;4;4;3;3` → 0 200 350 500 700 900 1050, Bayati → 0 150 300 500 700 850 1000, Hijaz `2;6;2;4;3;3;4` → 0 100 400 500 700 850 1000 (`OT0279`), Saba → 0 150 300 400 700 800 1000. Sikah's 24-EDO ascending row `3;4;4;3;3;4;3` gives 0 150 350 550 **700** 850 1050 (derived). DaMuSc's `OT0298` (…550 **650**…) is again its descending row `3;4;4;2;4;4;3`. **corrected** in the same way.

**Maqam World (Farraj), read directly.** The pages give no cents and no written tuning rule. Notes are drawn with a half-flat glyph (`icon-halfflat`). What each page *does* carry is the frequency its "click the notes" player sounds, as `data-frequency` attributes. Cents below are my arithmetic on those attributes (derived). They are **page data, not a stated tuning.** The same note varies between pages: F is `347.65` Hz on the Rast page and jins Bayati page but `345` on the maqam Bayati page (13 ¢ apart), and E½♭ is `320` on the Rast page but `322` on the Sikah page (11 ¢ apart). So treat them as the site's approximations.

- Rast (`maqam/rast.php`): `C4 260.74 · D4 293.33 · E4½♭ 320 · F4 347.65 · G4 391.11 · A4 440 · B4½♭ 482 · C5 521.48`, descending `B4♭ 463.54`. Verbatim text: "Its scale starts with the root Jins Rast on the tonic, followed on the 5th degree by either Jins Upper Rast (with its tonic up on the 8th degree) or Jins Nahawand." **confirmed** (text); cents derived.
- Bayati: "Its scale starts with Jins Bayati on the tonic followed by either Jins Nahawand or Jins Rast on the 4th degree." **confirmed.** So the 6th is B♭ (792 ¢, Nahawand) or B½♭ (860 ¢ on the page, Rast). These are the same two options as DaMuSc's descending (792) and ascending (838) rows.
- Hijaz: "followed by either Jins Nahawand or Jins Rast on the 4th degree." Jins Hijaz page, verbatim: "**The interval between the 2nd and 3rd degrees is usually played smaller than notated by raising the 2nd a little and lowering the 3rd a little.**" **confirmed.** This is the only intonation rule stated on any Maqam World page read. It gives a direction but no size. (The page's own playback, E♭ `315` and F♯ `375` over D `293.33`, i.e. 123/425 ¢, raises the 3rd rather than lowering it. That is one more reason not to treat the playback as a tuning.)
- Sikah: "Its scale starts with the root Jins Sikah on the tonic, followed by Jins Upper Rast on the 3rd degree (with its tonic on the 6th degree) then Jins Rast on the 6th degree". The notated scale is E½♭ F G A B½♭ C D E½♭. **confirmed.**
- Scope: "This website mainly covers music from the Eastern Mediterranean part of the Arab World (Egypt, Palestine, Jordan, Lebanon and Syria), with a focus on the early to mid-twentieth century period." (`index.php`). **confirmed.** No page read makes a remark about regional intonation.
- Instruments page: the oriental keyboard has "12 switches to lower each note on the keyboard by a quartertone". **confirmed.** The oud page's tuning line appears under "Incidental" at the end of this section.

**Ellis 1885 (via DaMuSc `T0356`):** Rast = 0 204 384 498 702 882 996 1200. dataset. So there are four theory-side Rast thirds: 350 (24-EDO), 355 (Maqam World playback), 362 (53-comma) and 384 (Ellis).

**Practice: the 1932 Cairo Congress (ORD-CC32).** The paper (Bozkurt 2025, arXiv:2506.14503) was opened this pass. Methodology, verbatim:
- Pitch: "Pitch series and pitch confidence extracted using pYIN (Mauch & Dixon, 2014), Crepe (Kim et al, 2018) and predominant melody makam (Atli et al, 2014) (analysis time step (for all extractors): 10 milliseconds)." Per-track tones: "Automatically extracted histogram maxima locations (from the mean histogram) and pitch scale intervals". **confirmed.**
- Tonic: "Manually labeled tonic segment time location in the recording and the frequency estimated (for a subset (64 out of 333) of recordings)". The tonic frequency comes from "i) gathering all pitch estimates from different algorithms for the segment, ii) sorting all pitch values, iii) leaving out the first and last thirds of pitch values keeping only the mid third and iv) computing the mean of these pitch values." **confirmed.**
- Aggregates: "Vocal-only recordings are excluded in such mean histogram computations due to the risk of a potential pitch drift within the recording." That is, vocal-only recordings are excluded from the paper's *aggregate* histograms, not from the per-track peaks. **confirmed.**
- Finding, verbatim: "third degree of maqam Rast and the second degree of maqam Husayni favors that quarter-tone may indeed be a regional choice (Egypt) not applied in recordings from Iraq or Tunisia. We will leave further interpretation to expert scholars of Arab music." It also says: "It is a well known nature of the maqam music culture that intervals vary due to musicians' personal choices, physical settings of the instrument (fretless/fretted)." The paper's Figure 3 averages "13 recordings (region: Egypt) and 3 recordings (Iraq)" for Rast, the same counts as the annotated `.scl` files used here. **confirmed.**
- Context the paper states: "A significant consensus among ethnomusicologists and theorists views the 24-tone scale primarily as a theoretical construct or a 'conceptual map' … rather than a precise reflection of what is performed." **confirmed** as the paper's summary. Its citations were not opened.
- Dataset description: the Zenodo record `15682346`, published 2025-06-17, lists "Pickle files", "CSV files", "Plot files" and a "Code directory". It says "The original audio files are not included due to copyright restrictions". **confirmed.** The `scale-library` README's "cent values computationally extracted from the audio recordings" is consistent with this. **Discrepancy:** the paper says 64 recordings have tonic labels, but `scale-library` marks **67** files `tonic_ref = annotated`. It is unresolved. One cross-check matches: the paper's Table 4 sample `CD 1/01 … tonic_Hz 135.22` corresponds to `CD01_01_hijaz_Egypt.scl`, whose `! tonic_hz = 135.23` matches to within rounding.

**Per-track peaks, re-extracted this pass.** The source is every `.scl` file with `tonic_ref = annotated`. The deviation in brackets is measured from the **spec's SILK table value** (derived). Each value is one track's histogram peak, not a norm.

| Degree (window) | Tracks | Range | Median / SD | Deviation from SILK table | Status |
|---|---|---|---|---|---|
| Rast 3rd (300–420), Egypt | 13 | 343.2 – 363.0 | 351.9 / 5.5 | from 362: −18.8 … +1.0 | dataset per file; stats derived |
| Rast 3rd, all regions | 18 (incl. Iraq 3, Syria 1, Algeria 1; Tunisia none in window) | 309.4 – 375.9 | 352.2 / 16.4 | from 362: **−52.6 … +13.9** (2 tracks beyond ±50: `CD11_02_rast_Iraq` 311.1, `CD15_05_rast_Algeria` 309.4) | dataset; derived |
| Rast 7th, upper cluster (1030–1120) | 11 | 1035.5 – 1099.0 | — | from 1064: −28.5 … +35.0. A separate cluster at 1001–1013 (7 tracks: 4 Egypt, 1 each Iraq, Algeria, Tunisia) is the descending B♭ (996), not a mistuned 7th. | dataset; the split is my reading |
| Bayati 2nd (100–200), Egypt | 18 of 20 | 133.0 – 183.6 | 150.2 / 11.9 | from 136: −3.0 … **+47.6** | dataset; derived |
| Bayati 2nd, Iraq | 3 | 136.6 – 142.7 | — | +0.6 … +6.7 | dataset |
| Bayati 6th (780–900), Egypt | 14 peaks in 13 tracks | 781.9 – 876.6, **bimodal**: 782–836 and 868–877 | — | matches the two Maqam World forms (792 / 860) | dataset; the split is my reading |
| Hijaz 2nd / 3rd, Egypt | 4 / 3 | 104.2 – 134.7 / 390.6 – 396.2 | — | from SILK's 100 / 400: +4 … +35 / −9 … −4 | dataset. Direction matches Maqam World's "raising the 2nd … lowering the 3rd". |
| Husayni 2nd (not a SILK row) | Egypt 2 / Iraq 3 / Tunisia 2 | 146–149 / 169–179 / 165–177 | — | — | dataset. Consistent with the paper's Egypt-vs-Iraq/Tunisia finding. |
| Saba 2nd / 4th (not a SILK row) | 11 / 9 | 112.4–167.4 / 378.4–438.2 | — | 4th from 385: up to +53.2 (Iraq `CD12_08`) | dataset |
| Sikah | 0 annotated tracks | — | — | — | Egypt has 4 Sikah tracks, but none carries a tonic label, so none is usable. |

The two rast "outliers" have whole scales that differ from Rast, not just the 3rd. `CD11_02_rast_Iraq.scl` reads ` 205.669188 311.105028 485.548525 695.657668 852.239954 1002.340137 1200.0`, and `CD15_05_rast_Algeria.scl` reads ` 206.696821 309.386406 502.927652 705.551575 906.353459 1009.15048 1200.0`. Both have a ~310 ¢ 3rd *and* a ~1005 ¢ 7th. So they read more like a different mode under the same label than like a wide rast third. That is my reading; the dataset does not say. `CD06_11_bayati_Egypt` (2nd 183.6) has no peak between 184 and 486, so its "2nd" may be a merged 2nd/3rd.

**Measured qanun (Mokhtar & Mosharrafa 1937, via DaMuSc `M0426`):** steps 40–115 ¢. dataset; unchanged from the first pass.

**Unsupported (NOT for code):** Marcus, Touma and the Congress proceedings themselves. Also any per-city or per-school "correct" size: the paper explicitly declines to give one.

---

### 2. Turkish makam — Arel–Ezgi–Uzdilek (AEU), 53 commas

- **Comma:** "Each step represents a frequency ratio of 2^(1/53), or 22.6415 cents …, an interval sometimes called the Holdrian comma." (Wikipedia, *53 equal temperament*, rev. 1371142908, reached via the *Holdrian comma* redirect.) **confirmed** (encyclopedia). The first pass only had the name from a code comment; the name is now sourced.
- **AEU uses 53:** "In Turkish music theory, the octave is divided into 53 equal intervals known as commas (koma), specifically the Holdrian comma. Each whole tone is an interval equivalent to nine commas." (Wikipedia, *Turkish makam*, rev. 1354678487.) **confirmed** (encyclopedia). The first pass's code evidence (`adaptive-tuning`, `tomato`) stands.
- **Interval letters.** The same article's table gives: `koma or fazla | 1 | F` · `eksik bakiye | 3 | E` · `bakiye | 4 | B` · `küçük (sağir) mücenneb | 5 | S` · `büyük (kebir) mücenneb | 8 | K` · `tanîni | 9 | T` · `artık ikili | 12 - 13 | A`. **confirmed** (encyclopedia). This matches DaMuSc's `TURKISH` letters (T=9, K=8, S=5, B=4, F=1, E=3, A=12). The "12 - 13" also **resolves the first pass's Segah note**: DaMuSc's letter row uses A = 12 and Rechberger's comma row uses 13, and both sizes are in the article's range.
- **Rast's third:** "Makam Rast is therefore notated in the 'same' in Arabic and Turkish music, but Turkish Rast's third is a just major third, not a neutral third." **confirmed** (encyclopedia). This is consistent with the AEU Rast row below (385 ¢).
- Rows (Rast 0 204 385 498 702 906 1087; Uşşak; Hicaz; Segah): **dataset**, unchanged. For each, DaMuSc and the `adaptive-tuning` note table agree, except Segah above the 5th.
- Measured ney (Tan 2011 via DaMuSc): dataset, unchanged. None of the measured tunings reproduces the AEU grid.

---

### 3. Persian dastgah — koron/sori, notation vs. measured practice

**Notation (Wikipedia, read directly):**
- Koron: "a symbol used in traditional Persian music in order to lower or 'flatten' a written note by an interval smaller than a semitone (broadly corresponding to a quarter tone, or specifically a half flat)". "In the early 20th century, Iranian master musician Alinaghi Vaziri established the standard usage of this symbol in written music." (*Koron (music)*, rev. 1355813652.) **confirmed** (encyclopedia). No cents are given.
- Sori: "a symbol that corresponds to a quarter step higher in tone in Persian traditional music." (*Sori (music)*, rev. 1355813630.) **confirmed** (encyclopedia). No cents are given.
- Moteghayyer: "It's a variable note – one that consistently appears as two distinct pitches, which can be used alternately in different contexts or at the performer's discretion." (*Dastgah*, rev. 1334100713.) **confirmed** (encyclopedia).
- Shur's notes, as the *Dastgah* page gives them: "Shur شور (Ca Df Ep F G A/Apm B♭ C)", where "Koron (half flats) are shown with a ׳p׳". The superscript function letters were flattened in extraction. **confirmed** as note names only; no cents.
- Mahur: "Dastgāh-e Māhur is a mode … in Persian traditional music, identical to the Western major scale" (*Mahur* disambiguation page, rev. 1278067223). **confirmed** as a note-name claim. There is no standalone *Shur* article (404).

**Measured intervals — Farhat, Talai, Vaziri and a performance measurement (Shafiei 2021, arXiv:2108.01283, read directly).**
- On sources, verbatim: "Both Farhat and Talāi have measured the intervals between the frets of tār and setār. Farhat mentions that he has measured the intervals between the frets for two tārs and one setār using a stroboconn between 1959 and 1964. The perfect fourth and perfect fifth intervals in Farhat's measurements seem to be taken from the equal temperament scale." Also: "Talāi's intervals seem to have been approximated in a way that all the measurements are multiples of 10." The paper defines k this way: "The letter 'k' stands for koron, the half-flat sign suggested by Vaziri".
- **Table 1** (cents above Sol), verbatim:

| Note | Farhat | Talāi | Marāghi |
|---|---|---|---|
| Sol | 0 | 0 | 0 |
| La b | 90 | – | 90 |
| La k | 135 | 140 | 180 |
| La | 205 | 200 | 204 |
| Si b | 295 | 280 | 294 |
| Si k | 340 | 350 | 384 |
| Si | 410 | 380 | 408 |
| Do | 500 | 500 | 498 |
| Re b | 565 | 580 | 588 |
| Re k | 630 | 640 | 678 |
| Re | 700 | 700 | 702 |
| Mi b | 790 | – | 792 |
| Mi k | 835 | 840 | 882 |
| Mi | 905 | 900 | 906 |
| Fa | 995 | 980 | 996 |
| – | 1040 | 1050 | 1086 |
| Fa# | 1110 | – | 1176 |
| Sol | 1200 | 1200 | 1200 |

  Farhat and Talāi are **secondary** (their books were not opened); Marāghi is a historical theory row.

- **Farhat's 17 steps match DaMuSc's Rechberger gamut to within 3.7 ¢ on every note** (derived: Farhat − DaMuSc = 0, 0, +1.8, +1.0, +0.9, +2.9, +2.2, +2.0, −3.7, −1.3, −2.0, −2.2, −0.2, −1.0, −1.0, +0.9, +0.2). Applying DaMuSc's gamut indices to Farhat's column (derived) gives:
  - Shur 0 **135** 295 500 700 790 995. DaMuSc has 0 133 294 498 702 792 996.
  - Mahur 0 205 410 500 700 905 1110. DaMuSc has 0 204 408 498 702 906 1110.
  - Chahargah 0 135 410 500 700 835 1110. DaMuSc has 0 133 408 498 702 835 1110.

  So SILK's SHUR, MAHUR and CHAHARGAH rows are now `dataset` **plus** agreement with a measured instrument source (`secondary`). The agreement is too close to be two independent measurements, so it looks as if Rechberger's gamut restates Farhat's. That is my inference, since Rechberger was not opened.
- **Table 2, "Scale of Shur"**, verbatim (cents above C):

| Note | Karimi (measured) | Farhat | Talāi | Vaziri |
|---|---|---|---|---|
| C | 0 | 0 | 0 | 0 |
| D | 210 | 205 | 200 | 200 |
| Ek | 347 | 340 | 350 | 350 |
| F | 498 | 500 | 500 | 500 |
| G | 696 | 700 | 700 | 700 |
| Ak | 836 | 835 | 840. | 850 |
| Bb | 985 | 995 | 980 | 1000 |
| C | 1190 | 1200 | 1200 | 1200 |

  Karimi is **confirmed**: it is the paper's own measurement, averaged over "the fifteen gushes of shur" of Karimi's vocal radif, from pYIN pitch tracks aligned to transcription by DTW. Farhat, Talāi and Vaziri are **secondary**. The **Vaziri column is exactly 24-EDO**: koron = 50 ¢ below natural (derived). The neutral second D→Ek is 137 for Karimi, 135 for Farhat, 150 for Talāi and 150 for Vaziri (derived from the table). SILK's SHUR second is 133.
- **Spread in performance.** Table 3 gives interval SDs (cents): C-D 7.65, D-Ek 7.79, Ek-F 7.07, F-G 11.47, G-Ak 13.6, Ak-Bb 11.4, Bb-C 7.33, C-D 5.5. Verbatim: "the intervals performed in this dastgāh are not fixed." **confirmed.**
- Shafiei & Hakam (SMC 2025, arXiv:2503.11956; 145 pieces by the same singer, read directly): "what is labeled as a 'neutral second' in some cases may span anywhere from approximately 120 to 180 cents"; "the interval between F and G might be 198 cents in one piece but 210 cents in another"; a hidden secondary peak appears "within 50 cents of the main peak … This type of peak often appears in variable notes within a piece." **confirmed.**
- Gushe app (koron as 24-EDO *or* 11-limit ratios): unchanged from the first pass, a design reference.
- **Still unsupported:** Farhat's and Vaziri's own texts (only Shafiei's tables of them were read), and Farhat 2012 (the Durham PDF host returned 403).

---

### 4. Japanese — shamisen tunings and koto/shamisen scales

**Shamisen tunings — now confirmed in two encyclopedias as well as the LilyPond code.**
- en Wikipedia (*Shamisen*, rev. 1360859905): honchōshi: "the first and third strings are tuned an octave apart, while the middle string is tuned to the equivalent of a fourth … The most commonly used tuning is C-F-C." Niagari: "the pitch of the second string is raised (from honchoushi), increasing the interval of the first and second strings to a fifth … C-G-C." Sansagari: "tuning the shamisen to honchoushi and lowering the 3rd string (the string with the highest pitch) down a whole step, so that the instrument is tuned in fourths, e.g. C-F-B♭." Also: "the shamisen is tuned according to the register of the singer, or simply to the liking of the player." **confirmed** (encyclopedia).
- ja Wikipedia (*三味線*, rev. 110697170, a section tagged as lacking sources), 三下り: "一の糸に対し、二の糸を完全4度高く、三の糸を短7度高く合わせる。… C-F-B♭となる。" That is, the 2nd string a perfect 4th above the 1st and the 3rd string **a minor 7th** above the 1st. **confirmed** (encyclopedia). This settles "root–4th–minor 7th, same octave".
- As before, no source gives cents; these are interval names.

**Scales — the "suspect 7-step rows" question is resolved.**
- *In scale* (en, rev. 1353971109): "In scale on D with auxiliary notes (F) & (C). 1-b2-(b3)-4-5-b6-(b7)". Also: "the miyako-bushi scale used in koto and shamisen music and whose pitches are equivalent to the in scale: … Miyako-bushi scale on D, equivalent to in scale on D … 1-b2-4-5-b6". **confirmed** (encyclopedia).
- *都節音階* (ja, rev. 109748385): "五音から成る陰音階で、洋楽階名では「ミ・ファ・ラ・シ・ド」が代表的な構成音とされる", i.e. a five-note in scale, E F A B C. The page also gives the Bunka Digital Library's example as "ド・♭レ・ファ・ソ・♭ラ" (C D♭ F G A♭). **confirmed** (encyclopedia). Both spellings are 1-b2-4-5-b6.
- **Result:** miyako-bushi / in is **pentatonic, 1-b2-4-5-b6** (plus optional auxiliaries b3 and b7 per en Wikipedia). DaMuSc's 7-step rows `T0314` "In" (`3;1;1;2;3;1;1` → 0 300 400 500 700 1000 1100) and `T0327` "Miyako-bushi" (`1;1;3;2;1;1;3` → 0 100 200 500 700 800 900) match **neither** the pentatonic nor the auxiliary-note form. **They stay unsupported (NOT for code).**
- **The SILK KUMOI row is mislabelled.** Its pitch set, 0 90 498 702 792 (1-b2-4-5-b6, Hewitt `T0080` "Kumoi joshi", dataset), is exactly the in / miyako-bushi set. Wikipedia *Japanese mode* (rev. 1244968729) gives *kumoijoshi* a different shape: "The intervals of the scale are major second, minor third, perfect fifth and minor sixth … The more correct term would be kumoijoshi, as given by William P. Malm". That is 1-2-b3-5-b6. **Kumoi naming conflicts across sources** (Hewitt vs. Malm via Wikipedia). The *in / miyako-bushi* name for 1-b2-4-5-b6 is corroborated by both Wikipedias.
- Hirajōshi (en, rev. 1352898803): "Burrows gives C-E-F♯-G-B. Sachs, as well as Slonimsky, give C-D♭-F-G♭-B♭. Speed and Kostka & Payne give C-D-E♭-G-A♭. Note that all are hemitonic pentatonic scales … different modes of the same pattern of intervals, 2-1-4-1-4 semitones." **confirmed** (encyclopedia). SILK's HIRAJOSHI (0 204 294 702 792 = 1-2-b3-5-b6) is the Speed / Kostka & Payne form, which is also Malm's kumoijoshi shape.
- Koto hira-jōshi (ja *平調子*, rev. 106012697): "壱越（D）を基準とした場合、調弦は一の弦より D4 - G3 - A3 - A3# - D4 - D4# - G4 - A4 - A4# - D5 - D5# - G5 - A5". **confirmed** (encyclopedia). Its pitch classes are D D♯ G A A♯, i.e. 1-b2-4-5-b6 on D (derived). So the koto's standard tuning *is* the miyako-bushi set. SILK's KUMOI row is that set on D, and SILK's HIRAJOSHI is the same set's mode on G (G A A♯ D D♯ → 0 2 3 7 8 semitones, derived).
- **Cents stay `dataset`.** No Japanese source gives cents. The 90/204/408 values are Hewitt's Pythagorean rows via DaMuSc. Ellis's measured koto (dataset, unchanged) has its small step at 82–107 ¢ and its large step at 346–410 ¢.
- "Yosenpō/Insenpō = yo/in" remains my reading. The *Yo scale* page (rev. 1363622667) gives yo as "two, three, two, two, and three semitones … D - E - G - A - B", and DaMuSc's Yosenpō row `2;3;2;3;2` is a different rotation. Not relied on.

---

### 5. Chinese — guzheng pentatonic and pressed notes

- **Standard tuning:**
  - zh Wikipedia *古筝* (rev. 94397269): "21弦筝通常按照D大调五声音阶定弦，过左手在琴碼左方按弦方可得到升音". That is: the 21-string zheng is usually tuned to D-major pentatonic, and raised notes can only be obtained by the left hand pressing the string on the left of the bridge. **confirmed** (encyclopedia).
  - Shi (2014, MA thesis, York, read directly) p. 21: "Strings are generally tuned as a pentatonic scale (C, D, E, G, A). … The pentatonic scale in D major is the basic and common tuning for the guzheng (Chang, 2011). In D major, String No.21 is tuned as D and String No.1 is tuned as d3". **confirmed.** The per-string list is in Figure 6, an image that was not transcribed.
  - en Wikipedia (*Guzheng*, rev. 1376277364): "tuned in a major pentatonic scale". **confirmed** (encyclopedia).
- **Pressed fa/ti — the first pass's snippet is now read in full.** en Wikipedia: "among the 21 strings of Guzheng, although no strings are specifically assigned to play F or B, those pitches can be produced by pressing E and A instead, respectively." It also says: "The guzheng is played on a pentatonic scale, with notes 'fa' and 'ti' being produced by bending the strings." **confirmed** (encyclopedia), in the C-tuning example. So fa is mi pressed up a semitone and ti is la pressed up a whole tone. These are 12-TET note *names*; **no cents are given.**
- **Press range:** Li et al. (ISMIR 2022, arXiv:2209.08774, read directly) define Upward Portamento as follows: "press a string with left hand to increase the pitch of a ringing note to a desired pitch within major third". **confirmed.** Shi 2014 notes that an app's "bending technique is really stiff … The pitch of bending is hard to control and easy to cause out of tone". This is a qualitative remark.
- **Cents deviation of a pressed note: still unsupported.** No source read measures one. Guzheng Tech99 (Li et al. 2023, arXiv:2303.13272) has per-note "onset, offset, pitch, IPT annotations", which is a candidate for measuring it in a later pass. Neither the dataset nor its pitch annotations were opened.
- Gong mode (Ho & Han via DaMuSc `T0337`) 0 204 408 702 906 (1201): **dataset**, unchanged. The pentatonic *shape* is now confirmed three times, but its *cents* rest on DaMuSc's own shí-èr-lǜ assumption. 12-TET would give 0 200 400 700 900.

---

### 6. Xenharmonic tunings and the Scala `.scl` format

| System | Step | Status |
|---|---|---|
| 19-EDO | 63.157895 ¢ (`edo-19.scl`) | dataset. Also derived: 1200/19. |
| 31-EDO | 38.709677 ¢ | dataset; derived: 1200/31. |
| Bohlen–Pierce, 13 equal steps of 3/1 | 146.304231 ¢ | dataset; derived: 1200·log2(3)/13 = 146.304. |
| Bohlen–Pierce, just | 27/25 … 3/1 | dataset (Xenharmonikon 17) |

**Scala `.scl` — official page, read directly** (`huygens-fokker.org/scala/scl_format.html`, © Manuel Op de Coul 2001–2026). **confirmed**, verbatim:
- "The file type is .scl . There is one scale per file. Lines beginning with an exclamation mark are regarded as comments and are to be ignored."
- "The first (non comment) line contains a short description of the scale, but long lines are possible and should not give a read error. The description is only one line. If there is no description, there should be an empty line."
- "The second line contains the number of notes. This number indicates the number of lines with pitch values that follow. … The lower limit is 0, which is possible since degree 0 of 1/1 is implicit. Spaces before or after the number are allowed."
- "After that come the pitch values, each on a separate line, either as a ratio or as a value in cents. **If the value contains a period, it is a cents value, otherwise a ratio. Ratios are written with a slash, and only one.** Integer values with no period or slash should be regarded as such, for example '2' should be taken as '2/1'. Numerators and denominators should be supported to at least 2^31-1 = 2147483647. Anything after a valid pitch value should be ignored. Space or horizontal tab characters are allowed and should be ignored. Negative ratios are meaningless and should give a read error."
- "The first note of 1/1 or 0.0 cents is implicit and not in the files."
- "Files for which Scala gives Error in file format are incorrectly formatted. They should give a read error and be rejected."
- The listed valid pitch lines include `408.`, `5`, `-5.0`, `10/20`, `100.0 cents` and ` 5/4 E\`. The example file `meanquar.scl` ends on ` 2/1`.
- "there is another Scala file type for mapping key/note numbers to scale degrees, called a keyboard mapping, with file extension .kbm".

**Is the last line the period?** **The format page does not say so.** The official Scala help (`huygens-fokker.org/scala/help.htm`, read directly) does, in three places. **confirmed**, verbatim:
- EXTEND: "extra tones are added by octave extension, where the formal octave (interval of equivalence) is the last tone of the original scale."
- SHOW MAPPING: "Octave degree is the degree which is considered to be the formal octave. … A value of 0 means that the last pitch of the current scale is considered to be the formal octave."
- The `.kbm` template: "! Scale degree to consider as formal octave (determines difference in pitch ! between adjacent mapping patterns):".

So in Scala's own semantics the last pitch is the period, and a `.kbm` can override it. The first pass's "convention inferred from examples" is upgraded to **confirmed (Scala help)**. This supersedes the pkg.go.dev transcription, which is kept only as a secondary cross-check.

---

### 7. MPC / MIDI practicalities

The repo evidence is unchanged from the first pass. This pass spot-checked `Tuner.kt` ("Cents for the pad's fine tune, -100..100."), `XpnImporter.kt` (`tuneFine = inst.tuneFine.coerceIn(-100, 100)`), `SurfaceKey.kt` ("the scale mask is twelve bits") and `LiveSnapEngine.cpp` (`constexpr int32_t kTuneSemitones = 24;`). Line numbers may have drifted by one or two. The first pass's fineTune = cents inference from three `.xtd`/`.xty` files is still inference, not an Akai document.

**MIDI Tuning Standard.**
- midi.org (`midi.org/midi-tuning-updated-specification`, read directly): "The MIDI Tuning specification allows the sharing of 'microtunings' (user-defined scales other than 12-tone equal temperament) among instruments, and the switching of these tunings during real-time performance." Also: "Scale/Octave Tuning is micro-tuning that is automatically repeated in every octave by calibrating a single octave of notes in small fractions of an equal-tempered half-step. … This proposal defines an easier micro tuning that sets offsets from an equal-tempered half-step by the cent." The listed messages are "Bulk Tuning Dump Request (non-real-time) · Bulk Tuning Dump (non-real-time) · Single-note Tuning Change (real-time)". **confirmed.**
- **Resolution of the frequency-data format: `secondary`.** Wikipedia (*MIDI tuning standard*, rev. 1352847362, an article tagged "relies on a single source") quotes the spec: "The next two bytes (14 bits) specify the fraction of 100 cents above the semitone at which the frequency lies. Effective resolution = 100 cents / 2^14 = .0061 cents." The spec PDF itself is membership-gated ("Join Us to Download") and was not opened.
- ODDSound MTS-ESP README: unchanged (secondary).
- The MPC corpus still holds no MPE, MTS or tuning-table field, so microtonal pitch must still be baked into `TuneCoarse`/`TuneFine`, at 1 ¢ resolution at best.

---

### What the literature cannot give (this pass)

- **A normative size for any neutral interval.** The sources give theory rows (350 / 355 / 362 / 384 for the Rast third), fret measurements (Farhat's koron second is 135) and single-performance peaks (Cairo 1932; Karimi). Bozkurt 2025 explicitly leaves interpretation "to expert scholars", and Shafiei & Hakam call their result "not intended as a prescriptive or canonical tuning". Maqam World states a *direction* for Hijaz only ("raising the 2nd a little and lowering the 3rd a little"), with no size.
- **Regional norms beyond one data point.** Only Egypt has enough tonic-labelled tracks: 13 rast and 18 bayati with a 2nd in window. Iraq has 3 rast and 3 bayati; Syria, Algeria and Tunisia have 1–2 each. The paper's regional claim rests on these small sets.
- **Sikah practice.** No tonic-labelled Sikah track exists in ORD-CC32 as packaged, so the SIKAH row has theory support only (DaMuSc plus Maqam World).
- **Cents for any Japanese scale.** Every Japanese source read gives note names or 12-TET letters. The only cents are Hewitt's Pythagorean rows (dataset) and Ellis's two measured koto tunings (dataset).
- **Pressed-note (fa/ti) cents on guzheng, and koto oshide.** Names and gesture ranges exist ("within major third"), but no measured deviation. A candidate is Guzheng Tech99's pitch annotations, which were not opened.
- **The primary books** behind every dataset row: Rechberger, Farhat, Talai, Vaziri, Hewitt, Malm and Lambert & Cordéreix.
- **The MTS spec text itself.** It is membership-gated.

---

### Design implications — **my synthesis, not sourced**

1. **Fix SIKAH's 5th: 634 → 702.** The spec's row is DaMuSc's *descending* row. The ascending row and Maqam World's notated Sikah (5th = B½♭ over E½♭) both put it at a perfect fifth. The corrected row is **0 136 340 543 702 838 1042**. If a descending variant is wanted, it is a separate row, not the default.
2. **HIJAZ: the spec's reason for using 24-EDO is wrong, though the row is still usable.** DaMuSc's *ascending* 53-comma Hijaz closes: **0 113 408 498 702 860 996**, derived from `5;13;4;9;7;6;9`. Only the descending row (52 commas) fails. For consistency with the other 53-comma Arabic rows, switch to it. Its 113 ¢ 2nd sits inside the 1932 Egyptian range (104–135). Neither row reproduces the lowered 3rd that both Maqam World's text and the recordings (391–396) show, so that is INFLECT's job. If the 24-EDO row is kept, change its stated reason.
3. **Rename KUMOI → MIYAKO-BUSHI (or IN).** The pitch set 1-b2-4-5-b6 is the in / miyako-bushi scale in both en and ja Wikipedia, and it is the koto's hira-jōshi set on D. "Kumoi" means a different shape in the Malm-derived source. Keep the cents (dataset, Hewitt). Note in the table file that HIRAJOSHI is the same set's mode on G. The DaMuSc 7-step "In"/"Miyako-bushi" rows stay out.
4. **The Persian rows are the strongest in the table.** SHUR, MAHUR and CHAHARGAH agree with Farhat's stroboconn fret measurements within 3.7 ¢, and SHUR's 133 ¢ 2nd sits between Farhat's 135 and Karimi's measured 137. Record Farhat via Shafiei 2021 as the provenance comment next to Rechberger.
5. **RAST and BAYATI: no change required, but note where they sit.** The 53-comma RAST 3rd (362) is at the *top* of the Egyptian 1932 cluster (343–363, median 352) and 7 ¢ above Maqam World's playback value. The BAYATI 2nd (136) is at the *bottom* of its cluster (133–184, median 150) and 15 ¢ below Maqam World. A "measured" variant would move them roughly −10 and +14. BAYATI's 838 ¢ 6th is one of two sourced forms: 792 (Nahawand on 4th, listed first by Maqam World) and 838/860 (Rast on 4th). The 1932 Egyptian 6ths are bimodal across exactly those two forms.
6. **±50 ¢ INFLECT is sensible, but the spec's justification sentence overclaims.** Measured from the spec's table values:
   - Every Egyptian peak fits: Rast 3rd −19…+1, Bayati 2nd −3…+48, Hijaz 2nd/3rd +4…+35 / −9…−4.
   - The upper-cluster Rast 7th fits (−29…+35).
   - The Persian spreads fit: Karimi's interval SDs are 5.5–13.6 ¢, and Shafiei & Hakam's "neutral second" span of 120–180 is −13…+47 from 133.
   - **Two tracks fall outside:** Iraq 311.1 (−50.9) and Algeria 309.4 (−52.6) Rast 3rds. The whole scales of those two tracks look like a different mode (they also have ~1005 ¢ 7ths). The Saba 4th, not a SILK row, reaches +53.2 on Iraq `CD12_08`.

   Two things to change in the spec's wording:
   - (a) Replace "past the measured spread of every neutral degree §5 found" with something like "covers every Egyptian and Persian measurement; two non-Egyptian 1932 rast thirds sit 51–53 ¢ below the table and read as a different mode".
   - (b) "the 1932 Cairo rast thirds scatter ±20 cents around one another" should read "the 13 Egyptian rast thirds span 20 ¢ (343–363, SD 5.5)".

   If full coverage of every tonic-labelled peak is wanted, ±55 does it; nothing sourced argues for more.
7. **Keep INFLECT below the size of a variable degree, deliberately.** The sourced "same degree, two pitches" pairs are all about 70 ¢ apart:
   - Rast's 7th, 1064 ascending vs 996 descending (68 ¢).
   - Bayati's 6th, 838/860 vs 792 (46–68 ¢).
   - Shur's moteghayyer 5th: Farhat 700 vs 630 (70 ¢); Karimi 702 vs 626 from D (76 ¢).

   Those are *table* choices (ascending/descending or variant rows), not inflections. ±50 correctly cannot reach them, and it should stay that way.
8. **GUZHENG PRESS needs an off-table target.** The sources say fa and ti are made by pressing mi up a semitone and la up a whole tone. But PRESS as specced "bends up to the target", and TUNE's target is always a PENTATONIC degree, which never includes fa or ti. So PRESS as written can only bend mi→sol (300 ¢, within the sourced "within major third"). Either let PRESS target the missing 4th/7th as +100/+200 ¢ above the pressed string, as 12-TET names since no cents exist, or ship a heptatonic guzheng row. Do not invent an overshoot figure.
9. **`.scl` import/export.** Follow the official rules quoted above: period → cents, slash → ratio, a bare integer n → n/1, implicit 1/1, last pitch = formal octave (Scala help). Reject negative ratios. Put provenance in `!` lines. BOHLEN–PIERCE's period is its last line, `3/1`, which needs no special case.

---

### Incidental (not tuning systems; for the oud section)

- Maqam World oud page: "The oud usually has 5 pairs of strings tuned in unison and a single bass string … The most common tuning (low to high) is C, F, A, D, G, C, which makes all intervals (except F to A) perfect fourths." **confirmed** (web reference). No octave registers are given.

---

### Sources

| # | Citation | URL | Read directly |
|---|---|---|---|
| 1 | McBride, Passmore & Tlusty (2023), DaMuSc — `Data/theory_scales.csv`, `octave_scales.csv`, `Src/tuning_system.py` (re-cloned) | https://github.com/jomimc/DaMuSc | Yes |
| 1a | Rechberger (2018); Hewitt (2013); Ellis (1885); Ho & Han (1982); Mokhtar & Mosharrafa (1937); Tan (2011) — books behind DaMuSc rows | via #1 | **No** |
| 2 | narenratan, `scale-library` @ `e9ccb06` — `scales/cairo-congress/*.scl` (67 annotated), README | https://github.com/narenratan/scale-library | Yes (cloned) |
| 3 | Bozkurt, B. (2025), "An Open Research Dataset of the 1932 Cairo Congress of Arab Music", arXiv:2506.14503v1 | https://arxiv.org/abs/2506.14503 | **Yes** (PDF, all pages) |
| 3a | ORD-CC32 Zenodo record 15682346 (metadata only; 356 MB zip not downloaded) | https://zenodo.org/records/15682346 | Yes (API metadata) |
| 3b | Lambert & Cordéreix (eds.) 2015, *Congrès de musique arabe du Caire, 1932* (BnF) | — | **No** |
| 4 | Farraj, Maqam World — `maqam/{rast,bayati,hijaz,sikah}.php`, `jins/{rast,bayati,hijaz,sikah}.php`, `maqam.php`, `jins.php`, `index.php`, `instr.php`, `instr/{oud,qanun,keyboard}.php` | https://www.maqamworld.com/en/ | Yes |
| 5 | Shafiei, S. (2021), "An analysis of Iranian Music Intervals based on Pitch Histogram", arXiv:2108.01283 | https://arxiv.org/abs/2108.01283 | **Yes** (all pages) |
| 5a | Farhat, *The Dastgah Concept in Persian Music* (1990/2004); Talāi, *A New Approach to Iranian Music* (1993); Vaziri, *Music Theory* (1934) — values via #5 | — | **No** |
| 5b | Farhat (2012), "An Introduction to Persian Music" (PDF) | http://www.dl.edi-info.ir/An%20Introduction%20to%20Persian%20Music.pdf | **No** (proxy 403) |
| 6 | Shafiei & Hakam (2025), "Computational Extraction of Intonation and Tuning Systems …", SMC 2025, arXiv:2503.11956 | https://arxiv.org/abs/2503.11956 | Yes |
| 7 | Wikipedia (en): Koron (music) r1355813652; Sori (music) r1355813630; Dastgah r1334100713; Mahur r1278067223; Persian traditional music r1369051026; In scale r1353971109; Yo scale r1363622667; Hirajōshi scale r1352898803; Japanese mode r1244968729; Shamisen r1360859905; Guzheng r1376277364; MIDI tuning standard r1352847362; 53 equal temperament (via "Holdrian comma") r1371142908; Turkish makam r1354678487 | https://en.wikipedia.org/ | Yes |
| 8 | Wikipedia (ja): 三味線 r110697170; 都節音階 r109748385; 平調子 r106012697 · (zh): 古筝 r94397269 | ja/zh.wikipedia.org | Yes |
| 9 | Shi, J. (2014), *Extending the sound of the guzheng*, MA by research, University of York | https://etheses.whiterose.ac.uk/id/eprint/8788/ | **Yes** (PDF) |
| 10 | Li, Wu, Li, Zhao, Yu, Xia & Li (2022), "Playing Technique Detection by Fusing Note Onset Information in Guzheng Performance", arXiv:2209.08774 | https://arxiv.org/abs/2209.08774 | Yes |
| 11 | Li, Che, Meng, Wu, Yu, Xia & Li (2023), "Frame-Level Multi-Label Playing Technique Detection …" (Guzheng Tech99), arXiv:2303.13272 | https://arxiv.org/abs/2303.13272 | Yes (paper); dataset **No** |
| 12 | Op de Coul, "Scala scale file format" (official) | https://www.huygens-fokker.org/scala/scl_format.html | **Yes** |
| 12a | Op de Coul, Scala help (EXTEND, SHOW MAPPING, Mappings / `.kbm` template) | https://www.huygens-fokker.org/scala/help.htm | Yes |
| 12b | `mikebharris/music/scala` Go docs (first-pass transcription; now superseded by #12) | https://pkg.go.dev/github.com/mikebharris/music/scala | Yes (first pass) |
| 13 | MIDI Association, "MIDI Tuning (Updated Specification)" page | https://midi.org/midi-tuning-updated-specification | Yes (page) |
| 13a | *MIDI Tuning Updated Specification.pdf* | same page, "Join Us to Download" | **No** (membership) |
| 14 | `threedaymonk/lilypond-shamisen`; `peimankhosravi/Gushe`; `hsercanatli/adaptive-tuning`; `sertansenturk/tomato`; ODDSound MTS-ESP README; `hmt`, `go-scala` docs | GitHub / hackage / pkg.go.dev | Yes (first pass; not re-read) |
| 15 | SnipSnap repo: `Tuner.kt`, `XpnImporter.kt`, `SurfaceKey.kt`, `LiveSnapEngine.cpp`, `docs/XPM_STRUCTURE.md`, `docs/MPC3_FORMAT.md`, `reference/golden/**` | local | Yes (spot-checked) |
| 16 | Marcus; Touma (1971); Malm; WKU "21-Stringed Guzheng Tuning Method in D Major"; guzhengalive.com; web.archive.org copies | various | **No** (not open, 403, or connection reset) |


---

