package com.snipsnap.synth

import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Listening candidates of round caustic-voices-1: BOWL, SCATTER and TONGUE. Unheard.
 *
 * Each one reads a part of the reflected-path geometry that the kept voices discard.
 * - BOWL reads WHERE ON THE RIM each reflected ray landed ([CausticGeometry.Path.boundaryTheta]).
 *   A round vessel's bending modes come in pairs, cos(n*theta) and sin(n*theta), which a real
 *   bowl's small imperfections split by a hertz or two. A landing at angle theta drives the
 *   pair in the proportion cos : sin of n*(theta - imperfection angle), so SOURCE and FOCUS,
 *   which decide the lit arc, decide how strongly each partial is struck and how deeply it
 *   beats. The lit arc is mirror-symmetric, which alone would fix every beat depth, so the
 *   strike leans to one end of the pickup band ([bowlLean]).
 * - SCATTER reads WHEN each ray arrives. The kept voices compress the arrival times into a
 *   fraction of one period (a colour); here a coarse 64-ray fan is stretched to audible time,
 *   so the arrivals are separate small hard contacts on the body: a spill that bunches where
 *   the reflections focus. FOCUS gathers the spill into one strike.
 * - TONGUE is the spare: only a new mode table (a clamped tongue: 1, 6.27, 17.55) and a short
 *   pluck on the kept reception. It reads nothing new.
 *
 * All three are linear: finite contact pulses drive damped two-pole modes (the same zero-state
 * transfer as [CausticCharacters]); no feedback, limiter or per-voice levelling. The contact is
 * an analytic raised cosine evaluated at each path's exact fractional delay, so no interpolation
 * filter is needed. Mode tables, imperfection angles and contact widths are authored choices,
 * not measurements. These candidates are finite only: no MOTION and no sustained HOLD yet.
 */
internal object CausticCandidates {
    private const val TAU = 2.0 * PI
    private const val DELAY_CYCLES = .65
    private const val SCATTER_RAYS = 64
    private const val SCATTER_APERTURE = .05
    /** Seconds of audible time per vessel radius of path length at LENGTH 1. */
    private const val SCATTER_SECONDS_PER_RADIUS = 3.4
    /** A contact of one millisecond's area pushes this hard; shorter contacts keep the same area. */
    private const val FORCE = 4_000.0

    /** A bowl partial: both members share a ratio and are split by [beatHz] at middle C. */
    private class BowlMode(val ratio: Double, val diameters: Int, val gain: Double, val t60: Double,
        val beatHz: Double, val imperfection: Double)

    // Ratios of a hand-hammered bowl's (n,0) family, n = 2..6. Authored, in the range bowls show.
    private val bowlModes = arrayOf(
        BowlMode(1.0, 2, 1.0, 9.0, 1.3, .14),
        BowlMode(2.71, 3, .67, 6.5, 2.1, .20),
        BowlMode(5.15, 4, .70, 4.0, 3.4, .12),
        BowlMode(8.10, 5, 10.0, 2.6, 4.6, .16),
        BowlMode(11.6, 6, 25.0, 1.6, 6.1, .10),
    )

    /** The mallet meets the far end of the pickup band harder than the near end. */
    private fun bowlLean(pickup: Int) = .25 + .75 * pickup / 8.0
    private val scatterModes = arrayOf(
        CausticCharacters.Mode(1.0, 1.0, 7.3), CausticCharacters.Mode(2.0, .5, 5.0),
        CausticCharacters.Mode(3.0, .22, 3.4), CausticCharacters.Mode(5.4, .5, 1.6),
        CausticCharacters.Mode(8.93, .35, 1.0), CausticCharacters.Mode(13.3, .2, .64),
    )
    private val tongueModes = arrayOf(
        CausticCharacters.Mode(1.0, 1.0, 5.5), CausticCharacters.Mode(2.0, .25, 2.5),
        CausticCharacters.Mode(6.27, 3.0, .90), CausticCharacters.Mode(17.55, 4.0, .32),
    )

    fun handles(voice: CausticVoice) =
        voice == CausticVoice.BOWL || voice == CausticVoice.SCATTER || voice == CausticVoice.TONGUE

    /** One contact: a time in seconds and one signed amplitude per mode. */
    private class Contact(val seconds: Double, val amplitudes: DoubleArray)
    private class Body(val frequencies: DoubleArray, val t60: DoubleArray, val gains: DoubleArray)

    private fun loss(ratio: Double, pickup: Int) = 1.0 / (1.0 + (ratio * (.035 + .085 * pickup / 8.0)).pow(1.6))

    private fun participation(mode: Int, pickup: Int): Double {
        val y = (pickup - 4) / 4.0
        return if (mode == 0) .92 + .08 * cos(PI * y)
        else .25 + .75 * cos(PI * (.30 + .20 * (mode % 4)) * (y + .15 * sin(mode.toDouble())))
    }

    fun render(voice: CausticVoice, m: Map<String, Float>, midi: Int, velocity: Double,
        frames: Int, oversample: Int, geometry: CausticGeometry.Prepared): CausticCharacters.Audio {
        stopIfUnwanted()
        require(handles(voice))
        require(oversample == 4 || oversample == 16)
        require(frames >= 0 && velocity.isFinite() && velocity in 0.0..1.0)
        val rate = Dsp.RATE * oversample
        val outputFrames = frames * oversample
        val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val deviation = 2.0 * m.getValue("BODY") - 1.0
        val lengthScale = (.5 + 7.5 * m.getValue("LENGTH")) / 8.0
        val body: Body
        val contacts: List<Contact>
        val width: Double
        when (voice) {
            CausticVoice.BOWL -> {
                val damping = lengthScale * 2.0.pow(.25 * deviation)
                // The beat rates are a property of the bowl, nearly independent of the note played.
                val register = (hz / 261.6255653005986).pow(.25)
                // A felt mallet: about a third of a period in contact, shorter when struck harder.
                width = (.45 - .25 * velocity) / hz * 2.0.pow(-.8 * deviation)
                contacts = geometry.paths.map { path ->
                    Contact(path.delayR * DELAY_CYCLES / hz, DoubleArray(bowlModes.size * 2) {
                        val b = bowlModes[it / 2]
                        val angle = b.diameters * (path.boundaryTheta - b.imperfection)
                        // The listener stands at the entrance, theta = 0.
                        val listen = -b.diameters * b.imperfection
                        path.weight * bowlLean(path.pickup) * loss(b.ratio, path.pickup) *
                            if (it % 2 == 0) cos(angle) * cos(listen) else sin(angle) * sin(listen)
                    })
                }
                // Each pair straddles its partial so that the louder member sits nearer to it:
                // the pair's loudness-weighted centre is the partial itself, and the root stays in tune.
                // A member's level is the coherent sum of its arrivals, each at its own delay.
                fun level(member: Int): Double {
                    val omega = TAU * bowlModes[member / 2].ratio * hz
                    return hypot(contacts.sumOf { it.amplitudes[member] * cos(omega * it.seconds) },
                        contacts.sumOf { it.amplitudes[member] * sin(omega * it.seconds) })
                }
                val share = DoubleArray(bowlModes.size) { k ->
                    val a = level(2 * k)
                    val b = level(2 * k + 1)
                    if (a + b > 0.0) b / (a + b) else .5
                }
                body = Body(
                    DoubleArray(bowlModes.size * 2) { val b = bowlModes[it / 2]
                        b.ratio * hz + (if (it % 2 == 0) -share[it / 2] else 1.0 - share[it / 2]) * b.beatHz * register },
                    DoubleArray(bowlModes.size * 2) { bowlModes[it / 2].t60 * damping * if (it % 2 == 0) 1.0 else .93 },
                    DoubleArray(bowlModes.size * 2) { bowlModes[it / 2].gain },
                )
            }
            CausticVoice.SCATTER -> {
                body = body(scatterModes, hz, lengthScale * 2.0.pow(.25 * deviation))
                // Small hard contacts, a tenth of a period or less.
                width = (.10 - .04 * velocity) / hz * 2.0.pow(-.6 * deviation)
                val fan = CausticGeometry.prepare(m.getValue("SOURCE").toDouble(), m.getValue("FOCUS").toDouble(),
                    SCATTER_RAYS, SCATTER_APERTURE).paths.ifEmpty { geometry.paths }
                val first = fan.minOfOrNull { it.delayR } ?: 0.0
                contacts = fan.map { path ->
                    Contact((path.delayR - first) * SCATTER_SECONDS_PER_RADIUS * lengthScale,
                        DoubleArray(scatterModes.size) {
                            path.weight * loss(scatterModes[it].ratio, path.pickup) * participation(it, path.pickup)
                        })
                }
            }
            else -> {
                body = body(tongueModes, hz, lengthScale * 2.0.pow(.25 * deviation))
                // A thumb releasing a tongue: sharper than a mallet, softer than a bead.
                width = (.20 - .12 * velocity) / hz * 2.0.pow(-.6 * deviation)
                contacts = geometry.paths.map { path ->
                    Contact(path.delayR * DELAY_CYCLES / hz, DoubleArray(tongueModes.size) {
                        path.weight * loss(tongueModes[it].ratio, path.pickup) * participation(it, path.pickup)
                    })
                }
            }
        }
        if (velocity == 0.0 || outputFrames == 0) return CausticCharacters.Audio(FloatArray(outputFrames), body.frequencies)
        val effort = velocity.pow(.9)
        val out = DoubleArray(outputFrames)
        val last = contacts.maxOfOrNull { it.seconds } ?: 0.0
        val drive = DoubleArray(min(outputFrames, ceil((last + width) * rate).toInt() + 2))
        for (mode in body.frequencies.indices) {
            stopIfUnwanted()
            if (body.frequencies[mode] >= 20_000.0) continue
            drive.fill(0.0)
            for (contact in contacts) {
                val amplitude = contact.amplitudes[mode] * effort
                if (amplitude == 0.0) continue
                val from = max(0, floor(contact.seconds * rate).toInt())
                val to = min(drive.size - 1, ceil((contact.seconds + width) * rate).toInt())
                for (i in from..to) {
                    val t = i.toDouble() / rate - contact.seconds
                    if (t > 0.0 && t < width) drive[i] += amplitude * (.5 - .5 * cos(TAU * t / width))
                }
            }
            // Every contact has the same area, so a shorter one is brighter, not weaker.
            CausticCharacters.ringAdd(out, drive, body.frequencies[mode], body.t60[mode], FORCE * .001 / width,
                body.gains[mode], rate)
        }
        val raw = FloatArray(outputFrames)
        for (i in raw.indices) {
            if ((i and 8191) == 0) stopIfUnwanted()
            check(out[i].isFinite() && abs(out[i]) < 8) { "CAUSTIC candidate raw state exceeded its bound at $i: ${out[i]}" }
            raw[i] = out[i].toFloat()
        }
        return CausticCharacters.Audio(raw, body.frequencies)
    }

    private fun body(modes: Array<CausticCharacters.Mode>, hz: Double, damping: Double) = Body(
        DoubleArray(modes.size) { modes[it].ratio * hz },
        // As HOLLOW_MALLET: a very short low note keeps enough cycles to stay pitched.
        DoubleArray(modes.size) { max(modes[it].t60 * damping, 12.0 / hz * (modes[it].t60 / modes[0].t60)) },
        DoubleArray(modes.size) { modes[it].gain },
    )

    private fun stopIfUnwanted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("CAUSTIC candidate render no longer wanted")
    }
}
