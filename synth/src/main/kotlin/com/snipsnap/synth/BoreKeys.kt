package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * BORE, held: a BORE patch as a keys instrument with an attack (docs/superpowers/specs/2026-11-04-bore-r2-research.md §3;
 * docs/superpowers/specs/2026-09-28-bore-woodwind-engine-design.md, "Round 1.7" and "R2: the held keygroup, as built").
 *
 * Each zone is one held note, played from its first sample: the note's own attack (the tongue's seed, the chiff's breath
 * burst, the swell CHIFF sets) runs straight on into its held middle, and the held middle is the sustain loop. It is the
 * same held note a LOOP is cut from ([Bore.heldNote]: the one-shot's blow with its gate stretched, conditioned like the
 * one-shot), so the zone's attack is the one-shot's and its loop has what the one-shot has (vibrato, turbulence, rasp,
 * voicing), not a frozen periodic tone (Round 1.6's finding).
 *
 * Round 1.7 closes a whole-clip LOOP with its blend at the loop's *start*: the first 0.3 s fade in from the audio that
 * follows the stretch's end. A zone cannot: its loop start is reached from the attack, and a blend there would make the
 * first pass from the attack into the loop jump to the continuation. So the blend sits at the loop's *end* ([assembleTail]):
 * the loop's last [Bore.LOOP_BLEND_SECONDS] fade into the audio just before the loop start, so the file's last frame is the
 * loop start's own predecessor, and the first pass from the attack into the loop is the note's own continuous audio. The
 * length search is the LOOP's, with the same arguments ([Bore.bestLength], [Bore.LOOP_BLEND_FRAMES],
 * [Bore.LOOP_LENGTH_LOW_FRAMES], [Bore.LOOP_LENGTH_HIGH_FRAMES]), so for the same macros the zone's loop is the LOOP's
 * cycle, rotated to start where the attack arrives, with the same length and the same correlation. The guards are Round
 * 1.7's ([Bore.checkJoin]): the two blended waves must correlate by [Bore.LOOP_MIN_CORRELATION] and the level must not dip
 * more than [Bore.LOOP_MAX_DIP_DB] in the blend, or the zone is refused by name rather than shipped with a swell or a smear.
 *
 * Two BREATH layers per zone ([layerBreaths]): the patch's own BREATH as the hard layer, and [SOFT_BREATH_SHARE] of it as
 * the soft one. BREATH is mapped onto each voice's measured speaking window (`Bore.reedPressure`, `Bore.flutePressure`), so
 * BREATH 0 is the softest note that speaks and a soft layer can never be air: RESIN's held-pad decision 2, "a soft layer
 * that does not speak is a hole". The host applies the key's velocity as gain, once (the BELLOWS rule), so both layers are
 * levelled to the same loudness on their loops and differ by timbre.
 *
 * OVERBLOW plays no part: [Bore.heldNote] holds it at 0 ([Bore.held]), so every zone plays its key uncracked.
 */
object BoreKeys {

    /**
     * The soft layer's BREATH as a share of the patch's (the hard layer's). Near [Velocity]'s own law at the middle of the
     * soft window: `atVelocity` scales a brightness macro toward 0.28 of itself at velocity 0, so a key at velocity 32 of
     * 127 reads about 0.46 of it. A shape constant (the research's decision 7): whether the two layers are far enough
     * apart is the audition's question. At a patch BREATH of 0 the two layers are the same note.
     */
    const val SOFT_BREATH_SHARE = 0.45f

    /** Soft first, as every layered keys instrument in the house orders them; MIDI velocity 0 is a note-off. */
    val VELOCITY_WINDOWS: List<IntRange> = listOf(1..63, 64..127)

    /** The peak a zone is held under after it is levelled on its loop (RESIN's held-pad rule, `Keys.resinPad`). */
    const val PEAK_CEILING = 0.99f

    /** The nine zones of [voice]: every minor third across its two-octave TUNE range from [Bore.rootMidi], both ends included. */
    fun padMidis(voice: BoreVoice): List<Int> = (0..Bore.TUNE_SEMITONES step 3).map { Bore.rootMidi(voice) + it }

    /** The two layers' BREATH, soft first, for a patch with [macros] (an absent or non-finite BREATH is the default). */
    fun layerBreaths(voice: BoreVoice, macros: Map<String, Float>): List<Float> {
        val hard = Bore.settled(macros, voice).getValue("BREATH")
        return listOf(hard * SOFT_BREATH_SHARE, hard)
    }

    /** One zone's two layers at [midi], soft first, each its attack then its loop ([padLayer]). */
    fun pad(voice: BoreVoice, macros: Map<String, Float>, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> =
        layerBreaths(voice, macros).map { padLayer(voice, macros, midi, it, cancelled) }

    /**
     * One layer of one zone: the held note at [midi] blown at [breath], from its first sample to the end of its loop, with
     * the loop marker at the loop's start. TUNE is set by [midi]; HOLD plays no part except as the LOOP's own vibrato
     * ([Bore.LOOP_HOLD]); OVERBLOW plays as 0; every other macro plays as it does in the one-shot. Throws, naming the zone,
     * if the held note's join does not hold. [cancelled] is asked before and after the render; the render itself stops on
     * its thread's interrupt, as the SYNTH preview's does.
     */
    fun padLayer(voice: BoreVoice, macros: Map<String, Float>, midi: Int, breath: Float, cancelled: () -> Boolean = { false }): KeyNote =
        padMeasured(voice, macros, midi, breath, cancelled).note

    /**
     * A held zone and how its join was made. [join] is [Bore.LoopJoin] read on the zone: `startFrames` and `junctionFrames`
     * are both the loop's start in the file (the blend closes the loop at the wrap), `lengthFrames` is the loop's length,
     * and the steps are in the zone's levelled units. [gain] is the one scale the levelling applied to [Bore.heldNote]'s
     * samples, so a test can read the zone against the note it was cut from.
     */
    internal class Held(val note: KeyNote, val join: Bore.LoopJoin, val name: String, val gain: Float)

    /** [padLayer] with its join, for the tests and the audition. */
    internal fun padMeasured(voice: BoreVoice, macros: Map<String, Float>, midi: Int, breath: Float, cancelled: () -> Boolean = { false }): Held {
        val m = zoneMacros(voice, macros, midi, breath)
        val name = "BORE $voice ${Scales.nameOf(midi)} at BREATH %.2f".format(java.util.Locale.ROOT, breath)
        checkCancelled(cancelled, name)
        val x = Bore.heldNote(voice, m)
        checkCancelled(cancelled, name)
        return cutHeld(x, Bore.heldUsableEnd(x, m), name)
    }

    /** The settled macros of one zone: TUNE from [midi], BREATH the layer's, HOLD the LOOP's vibrato. */
    internal fun zoneMacros(voice: BoreVoice, macros: Map<String, Float>, midi: Int, breath: Float): Map<String, Float> {
        val root = Bore.rootMidi(voice)
        require(midi - root in 0..Bore.TUNE_SEMITONES) { "BORE $voice pads are MIDI $root..${root + Bore.TUNE_SEMITONES}, got $midi" }
        require(breath.isFinite() && breath in 0f..1f) { "BORE layer BREATH wants 0..1, got $breath" }
        val tune = (midi - root) / Bore.TUNE_SEMITONES.toFloat()
        return Bore.settled(macros, voice) + mapOf("TUNE" to tune, "BREATH" to breath, "HOLD" to Bore.LOOP_HOLD)
    }

    /**
     * The zone cut from [x], a held note conditioned like the one-shot, using it up to [usableEnd], with the join checked
     * ([Bore.checkJoin]) and refused by [name] if it does not hold.
     *
     * Where: the blend's reference (the audio just before the loop) starts where Round 1.7's stretch would
     * ([Bore.loopStart]: once the level has settled and no sooner than [Bore.LOOP_START_SECONDS], the floor that waits for
     * the waveform to settle too), and the loop starts [Bore.LOOP_BLEND_SECONDS] after it. So the two waves blended are cut
     * from where Round 1.7's are, the ones its 1,071 corners measured.
     * How long: [Bore.bestLength] from the reference, which correlates the reference with the [Bore.LOOP_BLEND_SECONDS]
     * that follow the length, the loop's last blend's worth.
     * Then [assembleTail], the dip read on the loop turned so its blend comes first (the shape [Bore.blendDipDb] reads), the
     * level (the loop at the melodic target, the attack wherever it falls against it, under [PEAK_CEILING]: loud where it is
     * held), and the join's steps read off the result.
     */
    internal fun cutHeld(x: FloatArray, usableEnd: Int, name: String): Held {
        val blend = Bore.LOOP_BLEND_FRAMES
        val (from, settledAt) = Bore.loopStart(x, usableEnd, Bore.LOOP_LENGTH_HIGH_FRAMES, blend, name)
        val (length, correlation) = Bore.bestLength(x, from, blend, Bore.LOOP_LENGTH_LOW_FRAMES, Bore.LOOP_LENGTH_HIGH_FRAMES)
        val start = from + blend
        val end = start + length
        val out = assembleTail(x, start, length, blend, correlation)

        val turned = FloatArray(length) { out[if (it < blend) end - blend + it else start + it - blend] }
        val dip = Bore.blendDipDb(turned, blend)
        val gain = levelOn(out, start, end)

        val steps = FloatArray(length - 1) { abs(out[start + it + 1] - out[start + it]) }
        steps.sort()
        val join = Bore.LoopJoin(
            startFrames = start,
            lengthFrames = length,
            settledFrames = settledAt,
            correlation = correlation,
            blendDipDb = dip,
            junctionFrames = start,
            junctionStep = abs(out[start] - out[end - 1]).toDouble(),
            steepestStep = steps[((steps.size - 1) * 0.999).toInt()].toDouble(),
        )
        Bore.checkJoin(name, join)
        return Held(KeyNote(Snip(out, channels = 1, sampleRate = RATE), loopStartFrame = start.toLong()), join, name, gain)
    }

    /**
     * [x] from its first frame to the end of the loop [start] .. [start] + [length], with the loop's last [blend] frames
     * faded into the [blend] frames of [x] just before [start]: so the file's last frame is [x]'s frame before [start] and
     * the wrap into [start] is two consecutive frames of the note, while everything before the blend, the attack and the
     * first pass into the loop included, is [x] untouched.
     *
     * The fade is [Bore.assembleLoop]'s, mirrored: weights cos t / k on the loop's own wave and sin t / k on the audio
     * before [start], t from a [blend]th of a quarter turn to a quarter turn, k = sqrt(1 + r sin 2t), so the power holds for
     * two waves that correlate by [correlation]. The same two waves under the same weights as the LOOP's blend, one index on.
     */
    internal fun assembleTail(x: FloatArray, start: Int, length: Int, blend: Int, correlation: Double): FloatArray {
        require(start >= blend && blend in 1..length && start + length <= x.size) {
            "no room for a tail blend: start $start, length $length, blend $blend, ${x.size} frames"
        }
        val r = correlation.coerceIn(0.0, 1.0)
        val out = x.copyOf(start + length)
        val tail = start + length - blend
        for (i in 0 until blend) {
            val t = 0.5 * Math.PI * (i + 1) / blend
            val k = sqrt(1.0 + r * sin(2.0 * t))
            out[tail + i] = ((cos(t) / k) * x[tail + i] + (sin(t) / k) * x[start - blend + i]).toFloat()
        }
        return out
    }

    /**
     * [buf] scaled, in place and by one gain, so its frames [from] until [to] read [Dsp.MELODIC_LOUDNESS_TARGET], unless
     * that would take its peak over [PEAK_CEILING], in which case the peak sets the gain. Returns the gain.
     */
    private fun levelOn(buf: FloatArray, from: Int, to: Int): Float {
        val loud = Loudness.of(buf.copyOfRange(from, to), 1, RATE)
        var g = if (loud > 1e-6f) Dsp.MELODIC_LOUDNESS_TARGET / loud else 1f
        val peak = Dsp.finitePeak(buf)
        if (peak * g > PEAK_CEILING) g = PEAK_CEILING / peak
        for (i in buf.indices) {
            val v = buf[i] * g
            buf[i] = if (v.isFinite()) v else 0f
        }
        return g
    }

    private fun checkCancelled(cancelled: () -> Boolean, name: String) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("$name no longer wanted")
    }
}
