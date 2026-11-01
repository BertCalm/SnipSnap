package com.snipsnap.synth

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Spectral
import com.snipsnap.kit.ArrangedPad
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * KIT FROM ANYTHING — one captured sound becomes sixteen pads in a key and a
 * style: a kick, a snare, a closed and an open hat, a clap, a ride, a crash, a
 * siren, four bass notes and four tones. Purist: every pad is the source and
 * only the source, transformed. Designed and prototyped in
 * docs/superpowers/specs/2026-11-01-kit-from-anything-design-brief.md, where
 * every number below is traced; the Python prototype beside it is the
 * reference these recipes were measured on, with the repository's own
 * classifier as the judge.
 *
 * Three tricks make every drum from any sound, and all three keep the source:
 *  - **read it faster** (the sampler's own trick: faster is higher and
 *    shorter) for the hats, the ride and the crash;
 *  - **sing its cycle at a drum pitch** for the kick's body, the snare's body
 *    and the siren: one period of a pitched source, or a 46 ms excerpt of an
 *    unpitched one forced to wrap, read as a wavetable at the note (a
 *    wavetable read at a frequency is periodic at that frequency whatever is
 *    in it, so the note is built in, not found);
 *  - **whiten its arrival into noise** for the snare and the clap: the source
 *    divided by its own long-term spectrum, its first 60 ms paulstretched
 *    with a short window, read faster until it has top end.
 *
 * The bass and tone pads are the sung cycle at the key's notes with the
 * source's own colour pressed back on, which is the CYCLE path of the QUARRY
 * engine (docs/superpowers/specs/2026-10-31-quarry-engine-design-brief.md)
 * inlined until QUARRY ships; its grain field and HOLD are not here.
 *
 * Every pad is gated as the brief's slot table says: the five slots the
 * classifier has a class for must classify as that class; the ride and crash
 * by centroid and decay; the siren by its pitch trajectory read in order; the
 * bass and tones by their root. A pad that fails its gate is kept (purist) and
 * says so in its provenance (`gate = fail`), which is what the blend rule of
 * round three will act on; a pad the probe cannot read says `unmeasured`,
 * never `fail`.
 *
 * Everything runs at the output rate, like BODY and RETUNE: this is a
 * capture-side transform, not an oversampled engine. The only oscillator is
 * the cycle reader, and its table is mip-mapped per note (every harmonic that
 * would land above 20 kHz is dropped before the read), so nothing folds back.
 * Deterministic: every seed comes from the source's own hash, the slot and
 * the genre through [Dsp.seedFor].
 */
object KitFromAnything {

    const val VERSION = 1
    const val RATE = Dsp.RATE

    /** Every pad stays under the classifier's LOOP line (1.5 s) by this margin. */
    const val MAX_PAD_SECONDS = 1.45f

    /** A source under this has nothing to quarry; over this is a recording, not a hit. */
    const val MIN_SOURCE_SECONDS = 0.05f
    const val MAX_SOURCE_SECONDS = 30f

    /** The cycle table: 2048 points, 46 ms at the output rate, the forced excerpt's length. */
    const val TABLE = 2048

    /** A pitched source is one whose period reads at this confidence or better: its note is the siren's base and the provenance says so. */
    const val PITCHED_CONFIDENCE = 0.5f

    /** A period at this confidence or better shapes the cycle (one period averaged) even when the source is not called pitched. */
    const val PERIOD_CONFIDENCE = 0.3f

    /** The classes whose material is noise: no period is looked for, the cycle is the forced excerpt. */
    val NOISE_CLASSES: Set<DrumClass> = setOf(DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.CLAP)

    /** An unpitched source's cycle is "at" C3 for the siren's base, like Grains' PITCH 0.5. */
    const val UNPITCHED_BASE_HZ = 440f

    /** Half-width, in Spectral bins (43 Hz each), of the colour envelope's smoothing: about a kilohertz across. */
    private const val COLOUR_SMOOTH = 12

    /** The frozen noise's analysis window: 1024 samples at the arrival, Spectral's own frame length. */
    private const val NOISE_WINDOW = 1024

    /** The whitening floor: what the source cannot be flattened into within −50 dB is not forced. */
    private const val WHITEN_FLOOR = 0.00316f

    /** The three styles, each a row of plain numbers. See the brief's genre table for provenance. */
    enum class Genre(val label: String, val style: Style) {
        BOOM_BAP("boom bap", Style(
            bpm = 92f, kickHz = 52f, kickDrop = 2.2f, kickDropMs = 40f, kickDecayMs = 280f, kickClick = .25f,
            kickLpHz = 110f, drive = .35f, snareHz = 190f, snareDecayMs = 230f, snareBright = .45f,
            clapDecayMs = 180f, hatClosedMs = 70f, hatOpenMs = 380f, hatSpeed = 5f, rideDecayMs = 700f,
            crashSec = 1.3f, siren = Siren.DROP, sirenSec = .45f, sirenSemis = 7f,
            bassDecayMs = 450f, bassLpHz = 900f, toneDecayMs = 800f,
        )),
        HOUSE("house", Style(
            bpm = 124f, kickHz = 48f, kickDrop = 3f, kickDropMs = 25f, kickDecayMs = 240f, kickClick = .45f,
            kickLpHz = 120f, drive = .45f, snareHz = 220f, snareDecayMs = 170f, snareBright = .6f,
            clapDecayMs = 220f, hatClosedMs = 90f, hatOpenMs = 480f, hatSpeed = 6f, rideDecayMs = 800f,
            crashSec = 1.4f, siren = Siren.RISE_FALL, sirenSec = .8f, sirenSemis = 12f,
            bassDecayMs = 280f, bassLpHz = 1200f, toneDecayMs = 900f,
        )),
        // No techno rows exist in the repo's presets: HOUSE's WAREHOUSE / PEAK TIME corner pushed
        // (the shortest decay, the most click, the most drive). The author's extrapolation, marked so.
        TECHNO("techno", Style(
            bpm = 132f, kickHz = 50f, kickDrop = 4f, kickDropMs = 18f, kickDecayMs = 170f, kickClick = .6f,
            kickLpHz = 115f, drive = .6f, snareHz = 240f, snareDecayMs = 120f, snareBright = .7f,
            clapDecayMs = 150f, hatClosedMs = 50f, hatOpenMs = 300f, hatSpeed = 7f, rideDecayMs = 600f,
            crashSec = 1.2f, siren = Siren.CLIMB, sirenSec = 1f, sirenSemis = 12f,
            bassDecayMs = 160f, bassLpHz = 700f, toneDecayMs = 500f,
        ));

        companion object {
            /** "house", "boom bap", "boom-bap", "BOOM_BAP" all read. Null for anything else. */
            fun parse(text: String): Genre? {
                val key = text.trim().lowercase().replace('-', ' ').replace('_', ' ')
                return entries.firstOrNull { it.label == key || it.name.lowercase().replace('_', ' ') == key }
            }
        }
    }

    /** The siren's shape: a drop onto the note, a rise and fall, a climb that holds. */
    enum class Siren { DROP, RISE_FALL, CLIMB }

    /** One genre's numbers. Milliseconds and hertz, "shape" until a listen. */
    data class Style(
        val bpm: Float,
        val kickHz: Float, val kickDrop: Float, val kickDropMs: Float, val kickDecayMs: Float,
        val kickClick: Float, val kickLpHz: Float, val drive: Float,
        val snareHz: Float, val snareDecayMs: Float, val snareBright: Float,
        val clapDecayMs: Float,
        val hatClosedMs: Float, val hatOpenMs: Float, val hatSpeed: Float,
        val rideDecayMs: Float, val crashSec: Float,
        val siren: Siren, val sirenSec: Float, val sirenSemis: Float,
        val bassDecayMs: Float, val bassLpHz: Float, val toneDecayMs: Float,
    )

    /**
     * The sixteen slots, in pad order. [tag] is what the pad is filed as; the
     * classifier is the judge only where a class exists for the slot
     * ([judged]). The ride and crash wear HAT_OPEN's tag for colour and
     * balance but choke group 0: only the two hats choke each other.
     */
    enum class Slot(val pad: Int, val tag: DrumClass, val muteGroup: Int, val judged: Boolean, val label: String) {
        KICK(1, DrumClass.KICK, 0, true, "Kick"),
        SNARE(2, DrumClass.SNARE, 0, true, "Snare"),
        HAT_CLOSED(3, DrumClass.HAT_CLOSED, AutoPlace.HAT_MUTE_GROUP, true, "Closed Hat"),
        HAT_OPEN(4, DrumClass.HAT_OPEN, AutoPlace.HAT_MUTE_GROUP, true, "Open Hat"),
        CLAP(5, DrumClass.CLAP, 0, true, "Clap"),
        RIDE(6, DrumClass.HAT_OPEN, 0, false, "Ride"),
        CRASH(7, DrumClass.HAT_OPEN, 0, false, "Crash"),
        SIREN(8, DrumClass.PERC, 0, false, "Siren"),
        BASS_ROOT(9, DrumClass.TONAL, 0, false, "Bass Root"),
        BASS_THIRD(10, DrumClass.TONAL, 0, false, "Bass Third"),
        BASS_FIFTH(11, DrumClass.TONAL, 0, false, "Bass Fifth"),
        BASS_OCTAVE(12, DrumClass.TONAL, 0, false, "Bass Octave"),
        TONE_ROOT(13, DrumClass.TONAL, 0, false, "Tone Root"),
        TONE_THIRD(14, DrumClass.TONAL, 0, false, "Tone Third"),
        TONE_FIFTH(15, DrumClass.TONAL, 0, false, "Tone Fifth"),
        TONE_OCTAVE(16, DrumClass.TONAL, 0, false, "Tone Octave");

        val isBass: Boolean get() = pad in 9..12
        val isTone: Boolean get() = pad in 13..16

        /**
         * Root, third, fifth, octave by semitone interval, never by array index: a pentatonic's
         * third entry is its fourth. The third is whichever of 3 or 4 semitones the scale holds;
         * CHROMATIC holds both and takes the minor third (the kit's home register is a dark one).
         * A scale with neither (none shipped) falls back to the minor third; the fifth is 7 when the
         * scale has it and 7 anyway when it does not, because a bass without a fifth is not a bass.
         */
        fun degreeSemitones(key: KeySpec): Int {
            val iv = key.scale.intervals
            return when (pad) {
                9, 13 -> 0
                10, 14 -> if (3 in iv) 3 else if (4 in iv) 4 else 3
                11, 15 -> 7
                12, 16 -> 12
                else -> 0
            }
        }
    }

    /** The bass register starts at C1 plus the root; the tones an octave and a half up, at C3 plus the root. */
    const val BASS_BASE_MIDI = 24
    const val TONE_BASE_MIDI = 48

    /** What a gate said about a pad. */
    enum class Gate { PASS, FAIL, UNMEASURED }

    data class GateResult(val slot: Slot, val gate: Gate, val detail: String)

    /** The source, read once: where it arrives, whether it has a note, its cycle and its colour. */
    class Analysis internal constructor(
        val mono: FloatArray,
        val arrival: Int,
        val pitched: Boolean,
        val hz: Float,
        val cycle: FloatArray,
        /** [Spectral.BINS] long-term magnitudes, smoothed over about a kilohertz, max 1, floored at −40 dB. */
        val colour: FloatArray,
        val hash: Int,
        val sourceClass: DrumClass,
        /** The whitened source's 1024 samples at the arrival, computed once: every slot's noise is frozen from this. */
        val whiteArrival: FloatArray,
    ) {
        /** The siren's base: where the source sounds, or A4 when it has no note. */
        val baseHz: Float get() = if (pitched) hz else UNPITCHED_BASE_HZ

        /** True when a period, confident or weak, shaped the cycle (see [analyse]). */
        val periodic: Boolean get() = hz > 0f
    }

    data class Result(
        val pads: List<ArrangedPad?>,
        val gates: List<GateResult>,
        val analysis: Analysis,
    ) {
        val fallbackSet: List<GateResult> get() = gates.filter { it.gate == Gate.FAIL }
        val unmeasured: List<GateResult> get() = gates.filter { it.gate == Gate.UNMEASURED }
    }

    /**
     * One source into sixteen pads. [source] is any capture at the MPC rate;
     * stereo is folded to mono. Throws [IllegalArgumentException] in words
     * for a source too short or too long to quarry.
     */
    fun assemble(source: Snip, key: KeySpec, genre: Genre): Result {
        val a = analyse(source)
        val s = genre.style
        val pads = ArrayList<ArrangedPad?>(16)
        val gates = ArrayList<GateResult>(16)
        for (slot in Slot.entries) {
            val samples = when (slot) {
                Slot.KICK -> kick(a, s, seed(a, slot, genre))
                Slot.SNARE -> snare(a, s, seed(a, slot, genre))
                Slot.HAT_CLOSED -> hat(a, s, s.hatClosedMs, seed(a, slot, genre))
                Slot.HAT_OPEN -> hat(a, s, s.hatOpenMs, seed(a, slot, genre))
                Slot.CLAP -> clap(a, s, seed(a, slot, genre))
                Slot.RIDE -> ride(a, s, seed(a, slot, genre))
                Slot.CRASH -> crash(a, s, seed(a, slot, genre))
                Slot.SIREN -> siren(a, s)
                else -> note(a, s, midiFor(slot, key), slot.isBass)
            }
            // the house tail fade before anything measures or writes the pad: a pad cut at the LOOP
            // line still carries an envelope 30 dB down, which clicks without it
            Dsp.fadeTail(samples, 4f, RATE)
            val snip = Snip(samples, 1, RATE)
            val gate = gate(slot, snip, a, s, key)
            gates += gate
            pads += ArrangedPad(
                snip = snip,
                drumClass = slot.tag,
                muteGroup = slot.muteGroup,
                displayName = slot.label,
                source = linkedMapOf(
                    "madeBy" to "KIT FROM ANYTHING v$VERSION",
                    "slot" to slot.name,
                    "genre" to genre.label,
                    "key" to key.label,
                    "sourceHash" to a.hash.toString(),
                    "sourceRead" to when {
                        a.pitched -> "pitched %.1f Hz".format(java.util.Locale.ROOT, a.hz)
                        a.periodic -> "unpitched (${AutoPlace.nameFor(a.sourceClass)}), a period at %.1f Hz shaped the cycle".format(java.util.Locale.ROOT, a.hz)
                        else -> "unpitched (${AutoPlace.nameFor(a.sourceClass)}), the cycle forced"
                    },
                    "made" to "purist",
                    "gate" to gate.gate.name.lowercase(),
                    "gateDetail" to gate.detail,
                ) + if (slot.isBass || slot.isTone) mapOf("rootMidi" to midiFor(slot, key).toString(), "rootNote" to Scales.nameOf(midiFor(slot, key))) else emptyMap(),
            )
        }
        return Result(pads, gates, a)
    }

    fun midiFor(slot: Slot, key: KeySpec): Int =
        (if (slot.isBass) BASS_BASE_MIDI else TONE_BASE_MIDI) + key.rootSemitone + slot.degreeSemitones(key)

    private fun seed(a: Analysis, slot: Slot, genre: Genre): Int = Dsp.seedFor("kitfromanything", a.hash, slot.name, genre.name)

    // ---------------------------------------------------------------- analysis

    fun analyse(source: Snip): Analysis {
        require(source.sampleRate == RATE) { "the source must be at $RATE Hz, got ${source.sampleRate}" }
        val cleaned = Cleanup.removeDcOffset(if (source.channels == 1) source else Cleanup.toMono(source))
        val mono = cleaned.samples
        val seconds = mono.size.toFloat() / RATE
        require(seconds >= MIN_SOURCE_SECONDS) { "a source under ${(MIN_SOURCE_SECONDS * 1000).toInt()} ms has nothing to quarry (got %.0f ms)".format(java.util.Locale.ROOT, seconds * 1000) }
        require(seconds <= MAX_SOURCE_SECONDS) { "a source over ${MAX_SOURCE_SECONDS.toInt()} s is a recording, not a hit; chop it first (got %.1f s)".format(java.util.Locale.ROOT, seconds) }
        require(mono.all { it.isFinite() }) { "the source holds samples that are not numbers (NaN or infinity)" }
        require(mono.any { it != 0f }) { "the source is silent" }

        // the arrival: the loudest 5 ms block, where everything that reads the source starts
        val block = RATE / 200
        var bestAt = 0
        var best = -1.0
        var at = 0
        while (at < mono.size) {
            var acc = 0.0
            for (i in at until min(at + block, mono.size)) acc += mono[i].toDouble() * mono[i]
            if (acc > best) { best = acc; bestAt = at }
            at += block
        }

        // Which cycle the source yields. The classifier vetoes the noise classes (a hat or a clap has
        // no period worth averaging) and the pitch detector decides the rest: a confident period is
        // a note (the siren and the provenance say so); a weaker one still shapes the cycle, because
        // a 46 ms excerpt of anything with a period in it (a snare's 180 Hz body, a pluck) holds
        // several periods, and sung at a note that excerpt reads as a harmonic of the note, which the
        // first Kotlin run measured as bass pads eight and ten times too high. The classifier's TONAL
        // verdict is not the test: it never files a note above the low band as TONAL (Classifier.kt,
        // classifyBright has no TONAL arm), so a nylon pluck at A3 would have been forced.
        val sourceClass = Classifier.classify(cleaned).drumClass
        val remaining = (mono.size - bestAt).toFloat() / RATE
        val estimate = if (remaining > 0.06f && sourceClass !in NOISE_CLASSES) {
            Pitch.detect(cleaned, fromSec = bestAt.toFloat() / RATE, windowSec = min(0.25f, remaining - 0.01f))
        } else null
        val periodic = estimate != null && estimate.confidence >= PERIOD_CONFIDENCE && estimate.hz in 40f..2000f
        val pitched = periodic && estimate!!.confidence >= PITCHED_CONFIDENCE
        val hz = if (periodic) estimate!!.hz else 0f

        val cycle = if (periodic) averagedPeriods(mono, bestAt, hz) else forcedCycle(mono, bestAt - TABLE / 4)

        // the colour: the long-term magnitude in Spectral's own geometry, smoothed over five bins
        val sum = FloatArray(Spectral.BINS)
        var frames = 0
        Spectral.forEachFrame(cleaned) { _, _, mags ->
            for (b in 0 until Spectral.BINS) sum[b] += mags[b]
            frames++
        }
        // the colour is the ENVELOPE, not the line spectrum: a pitched source's harmonics are lines
        // 100 to 300 Hz apart, and pressing those lines onto a note at another pitch carves the new
        // note's own harmonics out (the first Kotlin run read a tone an octave up for that reason).
        // Smoothed over a kilohertz (24 bins), which keeps formants and loses lines.
        val colour = FloatArray(Spectral.BINS)
        var peak = 0f
        for (b in 0 until Spectral.BINS) {
            var acc = 0f
            var n = 0
            for (k in max(0, b - COLOUR_SMOOTH)..min(Spectral.BINS - 1, b + COLOUR_SMOOTH)) { acc += sum[k]; n++ }
            colour[b] = acc / n
            if (colour[b] > peak) peak = colour[b]
        }
        for (b in 0 until Spectral.BINS) colour[b] = max(colour[b] / max(peak, 1e-9f), 0.01f)

        // whitening is two full-source spectral passes; it depends on the source alone, so it runs once
        // here and not once per slot (six slots freeze their noise from this window)
        val white = whiten(mono)
        val whiteArrival = FloatArray(NOISE_WINDOW) { i -> if (bestAt + i < white.size) white[bestAt + i] else 0f }

        return Analysis(mono, bestAt, pitched, hz, cycle, colour, mono.contentHashCode(), sourceClass, whiteArrival)
    }

    /** Eight consecutive periods from the arrival, each resampled to [TABLE] points, averaged: one clean cycle that wraps by construction. */
    private fun averagedPeriods(src: FloatArray, from: Int, hz: Float): FloatArray {
        val period = RATE / hz
        val out = FloatArray(TABLE)
        var count = 0
        for (m in 0 until 8) {
            val start = from + m * period
            if (start + period >= src.size - 1) break
            for (i in 0 until TABLE) out[i] += readAt(src, (start + i * period / TABLE).toDouble())
            count++
        }
        if (count == 0) return forcedCycle(src, from)
        for (i in 0 until TABLE) out[i] /= count
        return centred(out)
    }

    /** [TABLE] samples from [start], forced to wrap with a short raised cosine at each end. Played as one period it is periodic by construction. */
    private fun forcedCycle(src: FloatArray, start: Int): FloatArray {
        val s = start.coerceIn(0, max(0, src.size - TABLE))
        val out = FloatArray(TABLE) { i -> if (s + i < src.size) src[s + i] else 0f }
        val edge = 128
        for (i in 0 until edge) {
            val ramp = (0.5 - 0.5 * cos(PI * i / edge)).toFloat()
            out[i] *= ramp
            out[TABLE - 1 - i] *= ramp
        }
        return centred(out)
    }

    private fun centred(cycle: FloatArray): FloatArray {
        var mean = 0.0
        for (v in cycle) mean += v
        val m = (mean / cycle.size).toFloat()
        var peak = 1e-9f
        for (i in cycle.indices) { cycle[i] -= m; peak = max(peak, abs(cycle[i])) }
        for (i in cycle.indices) cycle[i] /= peak
        return cycle
    }

    // ---------------------------------------------------------------- the cycle reader (QUARRY's CYCLE)

    /** Mip-map: every harmonic that would land above 20 kHz at [f0] is dropped, so the read has nothing to fold back. */
    internal fun tableFor(cycle: FloatArray, f0: Float): FloatArray {
        val re = cycle.copyOf()
        val im = FloatArray(TABLE)
        Fft.forward(re, im)
        val kmax = (20_000f / max(f0, 1f)).toInt()
        re[0] = 0f; im[0] = 0f
        for (k in 1 until TABLE / 2) {
            if (k > kmax) { re[k] = 0f; im[k] = 0f; re[TABLE - k] = 0f; im[TABLE - k] = 0f }
        }
        if (TABLE / 2 > kmax) { re[TABLE / 2] = 0f; im[TABLE / 2] = 0f }
        Fft.inverse(re, im)
        var peak = 1e-9f
        for (v in re) peak = max(peak, abs(v))
        for (i in re.indices) re[i] /= peak
        return re
    }

    /** The cycle read along a pitch trajectory, mip-mapped for the trajectory's highest note; linear read. */
    internal fun cycleAt(cycle: FloatArray, hzTrack: FloatArray): FloatArray {
        var top = 0f
        for (h in hzTrack) top = max(top, h)
        val table = tableFor(cycle, top)
        val out = FloatArray(hzTrack.size)
        var phase = 0.0
        for (i in hzTrack.indices) {
            val p = phase * TABLE
            val idx = p.toInt()
            val frac = (p - idx).toFloat()
            out[i] = table[idx % TABLE] * (1 - frac) + table[(idx + 1) % TABLE] * frac
            phase += hzTrack[i].toDouble() / RATE
            if (phase >= 1.0) phase -= 1.0
        }
        return out
    }

    // ---------------------------------------------------------------- the source's own noise

    /** The source divided by its own exact long-term magnitude, twice, floored at [WHITEN_FLOOR]: its noise and time structure kept, its resonances flattened. */
    internal fun whiten(mono: FloatArray): FloatArray {
        var snip = Snip(mono, 1, RATE)
        repeat(2) {
            val sum = FloatArray(Spectral.BINS)
            Spectral.forEachFrame(snip) { _, _, mags -> for (b in 0 until Spectral.BINS) sum[b] += mags[b] }
            var peak = 1e-9f
            for (v in sum) peak = max(peak, v)
            val gain = FloatArray(Spectral.BINS) { b -> 1f / max(sum[b] / peak, WHITEN_FLOOR) }
            snip = Spectral.process(snip) { _, _, _ -> gain }
        }
        return normalized(snip.samples)
    }

    /**
     * The whitened arrival, FROZEN into noise: one 1024-point window at the arrival, every frame of
     * the output that same magnitude spectrum with new seeded phases (Stretch.freeze's idea), so the
     * noise is stationary by construction. A stretch that walked along the arrival followed its decay
     * instead, and every tile of that noise started loud and died, which read as a closed hat and a
     * 110 ms ride in the first Kotlin run. Then read faster until at least half its power sits above
     * 2 kHz (the classifier's high band): a kick's click whitens to nothing up there, and the sampler's
     * trick gives it a top end that is still the source. Broadband by construction, so the flatness
     * gates a snare (0.25) and a clap (0.35) must clear are met.
     */
    internal fun noiseOf(a: Analysis, n: Int, seed: Int): FloatArray {
        var noise = freeze(a.whiteArrival, 2 * n, seed)
        repeat(4) {
            if (highShare(noise) >= 0.5f) return@repeat
            noise = speed(noise, 2f)
        }
        return normalized(tile(noise, n))
    }

    /** [window]-point frames of [seg] (its whole length), each with new seeded phases, overlap-added: stationary noise with seg's spectrum. */
    private fun freeze(seg: FloatArray, outFrames: Int, seed: Int): FloatArray {
        val window = seg.size
        val hop = window / 4
        val rng = java.util.Random(seed.toLong())
        val hann = FloatArray(window) { k -> (0.5 - 0.5 * cos(2.0 * PI * k / window)).toFloat() }
        val acc = FloatArray(outFrames + window)
        val re = FloatArray(window)
        val im = FloatArray(window)
        var pos = 0
        while (pos < outFrames) {
            for (k in 0 until window) { re[k] = seg[k] * hann[k]; im[k] = 0f }
            Fft.forward(re, im)
            re[0] = abs(re[0]); im[0] = 0f
            re[window / 2] = abs(re[window / 2]); im[window / 2] = 0f
            for (b in 1 until window / 2) {
                val mag = Math.hypot(re[b].toDouble(), im[b].toDouble())
                val phase = rng.nextDouble() * 2.0 * PI
                val pr = (mag * cos(phase)).toFloat()
                val pi = (mag * sin(phase)).toFloat()
                re[b] = pr; im[b] = pi; re[window - b] = pr; im[window - b] = -pi
            }
            Fft.inverse(re, im)
            for (k in 0 until window) acc[pos + k] += re[k] * hann[k]
            pos += hop
        }
        return normalized(acc.copyOf(outFrames))
    }

    /** Share of power at 2 kHz and above, the classifier's high band, on a 4096-point look. */
    private fun highShare(x: FloatArray): Float {
        val mags = Fft.magnitudeSpectrum(x, 4096)
        var high = 0.0
        var total = 0.0
        for (b in mags.indices) {
            val p = mags[b].toDouble() * mags[b]
            total += p
            if (Fft.binToHz(b, 4096, RATE) >= 2000f) high += p
        }
        return if (total > 0) (high / total).toFloat() else 0f
    }

    /** Stretch.kt's idea with a short window: Hann frames read at 1/factor pace, every phase replaced by a seeded one. */
    internal fun paulstretch(src: FloatArray, outFrames: Int, seed: Int, window: Int): FloatArray {
        val hop = window / 4
        val factor = outFrames.toDouble() / max(1, src.size)
        val rng = java.util.Random(seed.toLong())
        val hann = FloatArray(window) { n -> (0.5 - 0.5 * cos(2.0 * PI * n / window)).toFloat() }
        val acc = FloatArray(outFrames + window)
        val re = FloatArray(window)
        val im = FloatArray(window)
        var pos = 0
        while (pos < outFrames) {
            val inStart = (pos / factor).toInt().coerceIn(0, max(0, src.size - 1))
            for (k in 0 until window) {
                val s = inStart + k
                re[k] = (if (s < src.size) src[s] else 0f) * hann[k]
                im[k] = 0f
            }
            Fft.forward(re, im)
            re[0] = abs(re[0]); im[0] = 0f
            re[window / 2] = abs(re[window / 2]); im[window / 2] = 0f
            for (b in 1 until window / 2) {
                val mag = Math.hypot(re[b].toDouble(), im[b].toDouble())
                val phase = rng.nextDouble() * 2.0 * PI
                val pr = (mag * cos(phase)).toFloat()
                val pi = (mag * sin(phase)).toFloat()
                re[b] = pr; im[b] = pi; re[window - b] = pr; im[window - b] = -pi
            }
            Fft.inverse(re, im)
            for (k in 0 until window) acc[pos + k] += re[k] * hann[k]
            pos += hop
        }
        return normalized(acc.copyOf(outFrames))
    }

    // ---------------------------------------------------------------- small tools

    private fun readAt(src: FloatArray, pos: Double): Float {
        if (pos <= 0.0) return src[0]
        val i = pos.toInt()
        if (i >= src.size - 1) return src[src.size - 1]
        val f = (pos - i).toFloat()
        return src[i] * (1 - f) + src[i + 1] * f
    }

    /**
     * The sampler's trick: read faster, it gets higher and shorter. Band-limited through the
     * repository's windowed-sinc [Resampler] (the samples are converted to RATE / ratio and then
     * played back at RATE), so a 10 kHz component read 4× faster leaves instead of folding to
     * 4 kHz inside the ride's band, which a plain interpolated read did. A ratio under 1 reads
     * slower and lower, the same way.
     */
    internal fun speed(src: FloatArray, ratio: Float): FloatArray {
        if (ratio == 1f || src.size < 2) return src.copyOf()
        val targetRate = (RATE / ratio).roundToInt().coerceAtLeast(1000)
        return Resampler.resample(Snip(src, 1, RATE), targetRate).samples
    }

    private fun tile(src: FloatArray, n: Int): FloatArray = FloatArray(n) { i -> src[i % src.size] }

    private fun normalized(x: FloatArray): FloatArray {
        var peak = 1e-9f
        for (v in x) peak = max(peak, abs(v))
        for (i in x.indices) x[i] /= peak
        return x
    }

    /** −20 dB at [decayMs], the classifier's own decay definition (Features.decayMs). */
    private fun decayEnv(n: Int, decayMs: Float, attackMs: Float): FloatArray {
        val attack = max(1f, attackMs * RATE / 1000f)
        val decay = decayMs * RATE / 1000f
        return FloatArray(n) { i -> min(1f, i / attack) * 10f.pow(-i / decay) }
    }

    private fun drive(x: FloatArray, amount: Float): FloatArray {
        val g = 1f + 3f * amount
        for (i in x.indices) x[i] = tanh(x[i] * g)
        return normalized(x)
    }

    /**
     * A second-order Butterworth section (RBJ cookbook), float direct form I like the
     * house's [Dsp.Biquad], which has shelves and a bandpass but no plain low- or
     * high-pass. Private here until a second caller wants it in Dsp.
     */
    private class Section(hz: Float, highpass: Boolean, q: Float = 0.7071f) {
        private val b0: Float; private val b1: Float; private val b2: Float; private val a1: Float; private val a2: Float
        private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f
        init {
            val w = 2.0 * PI * min(hz, RATE * 0.45f) / RATE
            val cw = cos(w); val sw = sin(w)
            val alpha = sw / (2 * q)
            val a0 = 1 + alpha
            if (highpass) {
                b0 = ((1 + cw) / 2 / a0).toFloat(); b1 = (-(1 + cw) / a0).toFloat(); b2 = b0
            } else {
                b0 = ((1 - cw) / 2 / a0).toFloat(); b1 = ((1 - cw) / a0).toFloat(); b2 = b0
            }
            a1 = (-2 * cw / a0).toFloat(); a2 = ((1 - alpha) / a0).toFloat()
        }
        fun run(x: FloatArray): FloatArray {
            for (i in x.indices) {
                val v = x[i]
                val y = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = v; y2 = y1; y1 = y
                x[i] = y
            }
            return x
        }
    }

    private fun lp(x: FloatArray, hz: Float, poles: Int = 2): FloatArray {
        var y = x
        repeat(poles / 2) { y = Section(hz, highpass = false).run(y) }
        return y
    }

    private fun hp(x: FloatArray, hz: Float, poles: Int = 2): FloatArray {
        var y = x
        repeat(poles / 2) { y = Section(hz, highpass = true).run(y) }
        return y
    }

    /** First order, gentle on purpose: a steep filter empties bins and sinks the flatness a snare and a clap need. */
    private fun lp1(x: FloatArray, hz: Float): FloatArray {
        val f = Dsp.OnePole(RATE)
        for (i in x.indices) x[i] = f.lp(x[i], hz)
        return x
    }

    private fun hp1(x: FloatArray, hz: Float): FloatArray {
        val f = Dsp.OnePole(RATE)
        for (i in x.indices) x[i] = x[i] - f.lp(x[i], hz)
        return x
    }

    private fun bp(x: FloatArray, lo: Float, hi: Float): FloatArray = lp(hp(x, lo), hi)

    /** The source's own first [ms] from the arrival, faded out over its second half. */
    private fun head(a: Analysis, ms: Float): FloatArray {
        val n = max(0, min((ms * RATE / 1000f).toInt(), a.mono.size - a.arrival))
        val out = FloatArray(n) { i -> a.mono[a.arrival + i] }
        for (i in n / 2 until n) out[i] *= (0.5 + 0.5 * cos(PI * (i - n / 2) / max(1, n - n / 2))).toFloat()
        return out
    }

    private fun frames(seconds: Float): Int = (min(seconds, MAX_PAD_SECONDS) * RATE).toInt()

    // ---------------------------------------------------------------- the slots

    /**
     * The cycle sung at the kick pitch with a drop, driven THEN low-passed steeply (the drive's
     * harmonics are what the filter takes away; the classifier wants the centroid under 100 Hz with
     * the low band over 0.55), the source's own first 8 ms high-passed as the click.
     */
    private fun kick(a: Analysis, s: Style, seed: Int): FloatArray {
        val n = frames(min(0.9f, s.kickDecayMs * 3e-3f))
        val track = FloatArray(n) { i -> s.kickHz * (1 + (s.kickDrop - 1) * exp(-i / (s.kickDropMs * RATE / 1000f))) }
        val body = lp(drive(cycleAt(a.cycle, track), s.drive), s.kickLpHz, poles = 4)
        val env = decayEnv(n, s.kickDecayMs, 0.5f)
        for (i in body.indices) body[i] *= env[i]
        normalized(body)
        val click = normalized(hp(head(a, 8f), 1000f))
        for (i in click.indices) body[i] += 0.35f * s.kickClick * click[i]
        return normalized(body)
    }

    /** The source's noise decaying, brightened per genre, over the cycle at the snare's tune. */
    private fun snare(a: Analysis, s: Style, seed: Int): FloatArray {
        val n = frames(min(0.8f, s.snareDecayMs * 3e-3f))
        val noise = hp1(noiseOf(a, n, seed), 250f)
        val env = decayEnv(n, s.snareDecayMs, 0.5f)
        for (i in noise.indices) noise[i] *= env[i]
        val bright = hp1(noise.copyOf(), 2500f)
        for (i in noise.indices) noise[i] = (1 - s.snareBright) * noise[i] + s.snareBright * bright[i]
        // a snare is not a cymbal: the frozen noise reads faster until it is bright, and the techno
        // tilt on top of that put the whole thing above 12 kHz (the classifier called it an open hat),
        // so the top is shelved gently and the body carries more of the hit
        lp1(noise, 7000f)
        normalized(noise)
        val body = cycleAt(a.cycle, FloatArray(n) { s.snareHz })
        val bodyEnv = decayEnv(n, s.snareDecayMs * 0.5f, 0.5f)
        for (i in body.indices) body[i] *= bodyEnv[i]
        normalized(body)
        for (i in noise.indices) noise[i] = 0.8f * noise[i] + 0.5f * body[i]
        return normalized(noise)
    }

    /**
     * The real transient read at the hat's speed for the first 30 ms (identity), over the source's
     * noise read at the same speed (length and a top end), high-passed at 9 kHz, short or long.
     */
    private fun hat(a: Analysis, s: Style, decayMs: Float, seed: Int): FloatArray {
        val n = frames(min(0.9f, decayMs * 3e-3f))
        val fast = speed(a.mono, s.hatSpeed)
        val from = (a.arrival / s.hatSpeed).toInt()
        val tick = FloatArray(n)
        val tickLen = min(RATE * 30 / 1000, max(0, fast.size - from))
        for (i in 0 until tickLen) tick[i] = fast[from + i] * (0.5 + 0.5 * cos(PI * i / max(1, tickLen))).toFloat()
        val body = tile(speed(noiseOf(a, n, seed), s.hatSpeed), n)
        val x = normalized(hp(body, 9000f, poles = 4))
        val t = normalized(hp(tick, 9000f, poles = 4))
        val env = decayEnv(n, decayMs, 0.3f)
        for (i in x.indices) x[i] = (x[i] + 0.25f * t[i]) * env[i]
        return normalized(x)
    }

    /** Four flams of the source's noise 12 ms apart, rising, with 3 ms of near-silence between so the burst counter re-arms, then its tail. Gentle filters: flatness over 0.35 is the gate. */
    private fun clap(a: Analysis, s: Style, seed: Int): FloatArray {
        val n = frames(min(0.8f, s.clapDecayMs * 3e-3f))
        val noise = noiseOf(a, n, seed)
        val out = FloatArray(n)
        val burst = RATE * 9 / 1000
        val levels = floatArrayOf(0.55f, 0.7f, 0.85f, 1f)
        for (k in levels.indices) {
            val o = k * RATE * 12 / 1000
            for (i in 0 until burst) {
                val w = (0.5 - 0.5 * cos(2.0 * PI * i / burst)).toFloat()
                if (o + i < n) out[o + i] += levels[k] * noise[o + i] * w
            }
        }
        val tailStart = 4 * RATE * 12 / 1000
        val env = decayEnv(n - tailStart, s.clapDecayMs, 0.5f)
        for (i in tailStart until n) out[i] += 0.45f * noise[i] * env[i - tailStart]
        return normalized(lp1(hp1(out, 400f), 9000f))
    }

    /** The source's noise read 4× faster as the wash, band-passed 4 to 11 kHz, long; the real transient read 4× faster as the stick; a short cycle ping as the bell. */
    private fun ride(a: Analysis, s: Style, seed: Int): FloatArray {
        val n = frames(min(1.4f, s.rideDecayMs * 2.5e-3f))
        val fast = speed(a.mono, 4f)
        val from = a.arrival / 4
        val stick = FloatArray(n)
        val stickLen = min(RATE * 30 / 1000, max(0, fast.size - from))
        for (i in 0 until stickLen) stick[i] = fast[from + i] * (0.5 + 0.5 * cos(PI * i / max(1, stickLen))).toFloat()
        val wash = bp(tile(speed(noiseOf(a, n, seed), 4f), n), 4000f, 11000f)
        val env = decayEnv(n, s.rideDecayMs, 0.3f)
        for (i in wash.indices) wash[i] *= env[i]
        normalized(wash)
        val ping = cycleAt(a.cycle, FloatArray(n) { 1800f })
        val pingEnv = decayEnv(n, 60f, 0.2f)
        val st = normalized(bp(stick, 4000f, 11000f))
        for (i in wash.indices) wash[i] = wash[i] + 0.4f * st[i] + 0.35f * ping[i] * pingEnv[i]
        return normalized(wash)
    }

    /** The source's noise and the source itself, both read 3× faster, paulstretched into one wash, high-passed at 3 kHz, capped under the LOOP line. */
    private fun crash(a: Analysis, s: Style, seed: Int): FloatArray {
        val n = frames(s.crashSec)
        val fast = speed(a.mono.copyOfRange(a.arrival, a.mono.size), 3f)
        val len = max(fast.size, 4096)
        val bright = tile(speed(noiseOf(a, len, seed), 3f), len)
        val material = FloatArray(len) { i -> bright[i] + 0.6f * (if (i < fast.size) fast[i] else 0f) }
        val wash = hp(paulstretch(normalized(material), n, Dsp.seedFor(seed, "wash"), 4096), 3000f)
        val env = decayEnv(n, s.crashSec * 650f, 8f)
        for (i in wash.indices) wash[i] *= env[i]
        return normalized(wash)
    }

    /** The cycle along the genre's trajectory: a drop onto the base, a rise and fall, a climb that holds; log-domain so the shape reads as even (Siren.kt's rule). */
    private fun siren(a: Analysis, s: Style): FloatArray {
        val n = frames(s.sirenSec)
        val track = FloatArray(n) { i -> a.baseHz * 2f.pow(sirenSemitones(s, i.toFloat() / n) / 12f) }
        val x = lp(cycleAt(a.cycle, track), 6000f)
        val fadeIn = RATE / 100
        val fadeOut = RATE / 20
        for (i in x.indices) x[i] *= min(1f, i / fadeIn.toFloat()) * min(1f, (n - i) / fadeOut.toFloat())
        return normalized(x)
    }

    internal fun sirenSemitones(s: Style, t: Float): Float = when (s.siren) {
        Siren.DROP -> s.sirenSemis * (1 - t)
        Siren.RISE_FALL -> s.sirenSemis * (1 - abs(2 * t - 1))
        Siren.CLIMB -> s.sirenSemis * min(1f, t / 0.8f)
    }

    /**
     * A note: the cycle sung at [midi] with the source's colour pressed back on (QUARRY's CYCLE and
     * STAIN), the source's own first 15 ms spliced in front, decaying per genre, low-passed for the bass.
     * Transposing a sound moves its formants; the colour press puts the original envelope back over
     * whatever pitch did, which is what keeps a bark's bass sounding like that bark.
     */
    private fun note(a: Analysis, s: Style, midi: Int, bass: Boolean): FloatArray {
        val decayMs = if (bass) s.bassDecayMs else s.toneDecayMs
        val n = frames(max(0.5f, decayMs * 3e-3f))
        val hz = Scales.midiToHz(midi)
        var x = cycleAt(a.cycle, FloatArray(n) { hz })
        x = stain(x, a.colour, 0.75f)
        val env = decayEnv(n, decayMs, 2f)
        for (i in x.indices) x[i] *= env[i]
        normalized(x)
        if (bass) x = lp(x, s.bassLpHz)
        val h = normalized(head(a, 15f))
        val xf = min(h.size, RATE / 100)
        for (i in h.indices) {
            val fadeIn = if (i < h.size - xf) 0f else (0.5 - 0.5 * cos(PI * (i - (h.size - xf)) / max(1, xf))).toFloat()
            x[i] = x[i] * fadeIn + 0.8f * h[i]
        }
        return normalized(x)
    }

    /** The source's colour envelope raised to [power] as a fixed per-bin gain through Spectral's door. */
    private fun stain(x: FloatArray, colour: FloatArray, power: Float): FloatArray {
        val gain = FloatArray(Spectral.BINS) { b -> colour[b].pow(power) }
        return normalized(Spectral.process(Snip(x, 1, RATE)) { _, _, _ -> gain }.samples)
    }

    // ---------------------------------------------------------------- the gates

    private fun gate(slot: Slot, pad: Snip, a: Analysis, s: Style, key: KeySpec): GateResult {
        if (slot.judged) {
            val c = Classifier.classify(pad)
            val ok = c.drumClass == slot.tag
            return GateResult(slot, if (ok) Gate.PASS else Gate.FAIL,
                "classifier says ${c.drumClass} (%.2f), wanted ${slot.tag}".format(java.util.Locale.ROOT, c.confidence))
        }
        val f = Classifier.classify(pad).features
        return when (slot) {
            Slot.RIDE -> GateResult(slot, if (f.centroidHz > 4000f && f.decayMs > 300f) Gate.PASS else Gate.FAIL,
                "centroid %.0f Hz (over 4000), decay %.0f ms (over 300)".format(java.util.Locale.ROOT, f.centroidHz, f.decayMs))
            Slot.CRASH -> GateResult(slot, if (f.durationSeconds < 1.5f && f.decayMs > 500f) Gate.PASS else Gate.FAIL,
                "%.2f s (under 1.5), decay %.0f ms (over 500)".format(java.util.Locale.ROOT, f.durationSeconds, f.decayMs))
            Slot.SIREN -> sirenGate(pad, a, s)
            else -> {
                val target = Scales.midiToHz(midiFor(slot, key))
                val est = readPitch(pad, target, fromSec = 0.03f, windowSec = 0.3f)
                if (est == null) GateResult(slot, Gate.UNMEASURED, "no pitch read")
                else {
                    val cents = 1200f * log2(est.first / target)
                    val ok = abs(cents) <= 20f && est.second >= 0.3f
                    GateResult(slot, if (ok) Gate.PASS else Gate.FAIL,
                        "%.1f Hz, %+.0f cents of ${Scales.nameOf(midiFor(slot, key))}, confidence %.2f".format(java.util.Locale.ROOT, est.first, cents, est.second))
                }
            }
        }
    }

    /** Eight 50 ms windows in order against the trajectory, 150 cents each; fewer than six reads is unmeasured, apart from a fail. */
    private fun sirenGate(pad: Snip, a: Analysis, s: Style): GateResult {
        val win = 0.05f
        var reads = 0
        var worst = 0f
        for (k in 0 until 8) {
            val t0 = 0.02f + k * (s.sirenSec - 0.1f - win) / 7
            val tc = (t0 + win / 2) / s.sirenSec
            val expected = a.baseHz * 2f.pow(sirenSemitones(s, tc) / 12f)
            val est = readPitch(pad, expected, fromSec = t0, windowSec = win) ?: continue
            if (est.second < 0.3f) continue
            reads++
            worst = max(worst, abs(1200f * log2(est.first / expected)))
        }
        return when {
            reads < 6 -> GateResult(Slot.SIREN, Gate.UNMEASURED, "$reads of 8 windows read; a sweep needs a short-window tracker")
            worst <= 150f -> GateResult(Slot.SIREN, Gate.PASS, "$reads of 8 windows on the trajectory, worst %.0f cents".format(java.util.Locale.ROOT, worst))
            else -> GateResult(Slot.SIREN, Gate.FAIL, "$reads of 8 windows read, worst %.0f cents off the trajectory".format(java.util.Locale.ROOT, worst))
        }
    }

    /**
     * `Pitch.detect` reads 40 to 1200 Hz, and the kit's notes run from C1 (32.7 Hz) to a siren's
     * 1760 Hz. The pad is read faster or slower by a power of two (band-limited, [speed]) until
     * the expected pitch sits in 80 to 600 Hz, detected there, and the reading scaled back; the
     * window and offset scale with it. Returns (hz, confidence) in the pad's own time, or null.
     */
    internal fun readPitch(pad: Snip, expectedHz: Float, fromSec: Float, windowSec: Float): Pair<Float, Float>? {
        var ratio = 1f
        var expect = expectedHz
        while (expect < 80f && ratio < 8f) { ratio *= 2f; expect *= 2f }
        while (expect > 600f && ratio > 0.125f) { ratio /= 2f; expect /= 2f }
        val read = if (ratio == 1f) pad else Snip(speed(pad.samples, ratio), 1, RATE)
        val est = Pitch.detect(read, fromSec = fromSec / ratio, windowSec = windowSec / ratio) ?: return null
        return est.hz / ratio to est.confidence
    }

    @Suppress("unused")
    private fun ln2(x: Float) = ln(x) / ln(2f)

    @Suppress("unused")
    private fun rms(x: FloatArray): Float {
        var acc = 0.0
        for (v in x) acc += v.toDouble() * v
        return sqrt(acc / max(1, x.size)).toFloat()
    }
}
