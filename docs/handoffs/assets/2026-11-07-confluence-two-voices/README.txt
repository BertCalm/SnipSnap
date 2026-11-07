Two voices, no room — scratch listening study

Can the generated-from-A candidate suggest a distinct singer beyond same-take doubling?

Listen in manifest order: recorded A; recorded B; same-take A+A control; actual independent A+B; generated-from-A alone; A+generated candidate. No listening success is claimed. Both real singers perform straight /a/ near C4, natively about 30 cents apart. They were recorded separately; the real pair is a constructed reference, not a live duet recording.

All six files are 3.000 seconds, mono 44.1 kHz PCM24. The source bodies last 2 seconds. Components are matched independently to −22 LUFS by FFmpeg EBU R128 using constant gain, then the six complete clips are matched to the same integrated target. No compressor, limiter, room, stereo placement or harmony is used. This matches measured loudness; perceived loudness can still differ with vocal color.

Raw source crops remain unchanged under sources/. The finite audition removes each body's DC mean and applies explicit entrance/release envelopes. These are held-body cuts, not naturally released whole phrases or synthesized breaths.

Processing:
- A alone: recorded male3; entrance at 0.200 s with a 60 ms attack and 140 ms release.
- B alone: independently recorded female1; entrance at 0.245 s with a 90 ms attack and 180 ms release. No pitch correction or transposition.
- Same-take doubling: stock ConfluenceVoice TEXTURE primitive with the published pair's two varispeed ratios and relative entry gap. This short study is not the complete app's 30 second PAIR render.
- Generated candidate: a scratch PSOLA adapter uses A's pulse epochs and local periods for input grains. A separate clock around B's constant native median sets output F0, with a declared new drift/vibrato curve. Fixed pulse/formant-color scale 1.08, source chronology ratio 0.992; entrance at 0.245 s with a 90 ms attack and 180 ms release. No trained model or additional real human is claimed.

Numeric results and source credits are in reports/measurement.json, public-sources.json and sources/ATTRIBUTION.txt. A private numerical ablation changes only formant scale 1.08 to 1.00 under the same output clock; it is not another public listening option. Numeric pitch/envelope separation does not establish perceived identity. Words and full cultural gestures have not been tested.

Reproduction requires JDK 17, the Kotlin 2.0.21 command-line compiler, Python 3 with NumPy/SciPy, and FFmpeg with ebur128. Run ./render.sh from this extracted package. Outputs go to audio/, work/ and reports/. The copied ConfluenceVoice.kt is byte-identical to the source read from SnipSnap 5c028180f; ConfluenceShim.kt supplies its unchanged constants/enums only. IndependentSinger.kt is a scratch extension. No app/repository build is required. Host timing excludes FFmpeg measurement and is not phone latency.

Why it is built this way:
1. Real A+B establishes the recorded comparison before judging the generated candidate.
2. Dry mono and measured level matching remove width, room and level as alternative explanations.
3. Separate source and output clocks prevent A's original pitch motion from remaining the generated voice's target.
4. Raw sources and processing recipes remain inspectable; the owner decides whether it reads as two people.

VocalSet: A Singing Voice Dataset — Julia Wilkins, Prem Seetharaman, Alison Wahl, Bryan Pardo (2018), CC BY 4.0.
https://zenodo.org/records/1193957
https://creativecommons.org/licenses/by/4.0/
Anonymous dataset singer identifiers are not personal names or endorsements.
