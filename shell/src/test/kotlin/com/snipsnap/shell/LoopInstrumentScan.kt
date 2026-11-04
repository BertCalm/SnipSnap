package com.snipsnap.shell

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Keys
import com.snipsnap.synth.Patch
import com.snipsnap.synth.Presets
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Which HOLD-loop engines could become a keys instrument through one shared maker
 * (owner's call, 2026-11-04: qualify first, then build the qualifiers).
 *
 * For each candidate engine, each voice's first factory preset is set to HOLD's top
 * step and played at three notes through [SynthRoot] (the lowest, the root and the
 * highest note within an octave of the root). Each render is the loop a held key
 * would repeat. Three readings decide whether it can be an instrument:
 *
 *  - **wrap:** the loop is played end to start, and the energy of the second
 *    difference in a 32-sample window across the join is compared with the 95th
 *    percentile of the same window everywhere else in the clip. At or under 1 the
 *    join is an ordinary moment of the sound; a click stands out above all of them.
 *  - **pitch:** [Pitch.detect] against the note asked for, in cents; a keys
 *    instrument must play the key pressed. A reading a whole number of octaves
 *    away is reported as an octave to check by ear, not a failure: a weak
 *    fundamental fools the detector the same way.
 *  - **steady:** the spread between the 10th and 90th percentile of 50 ms RMS
 *    windows, in dB. A held tone stays within a few dB; a rhythmic loop does not.
 *
 * This is evidence for a decision, never a verdict on the sound: what passes still
 * waits on the owner's listen. Writes `loop-instruments.md` and `.csv` into the first
 * argument. Run via `./gradlew :shell:scanLoopInstruments`.
 */
object LoopInstrumentScan {

    /**
     * HOLD-loop engines without a held instrument on 2026-11-04 (MERCURY and KEEL were
     * wired the same day). OVEN has no HOLD macro, and SHOAL's factory voices are WOOD,
     * which refuses HOLD loops by design, so neither is a candidate.
     */
    val CANDIDATES = listOf(
        "AEROSTAT", "ALLOY", "APSIS", "ARCO", "BORE", "CAIRN", "CIRCUIT", "CISTERN", "COROLLA",
        "EMBOUCHURE", "FLOTILLA", "LACQUER", "MURK", "MURMUR", "NIMBUS", "PITCHWHEEL",
        "RELAY", "REVEL", "SCOUR", "SKITTER", "SPOOL", "STOOP", "SUTURE", "TANGLE",
        "TESSERA", "THAW", "UNDERTOW", "WICK",
    )

    val MAX_WRAP_RATIO = LoopPadMaker.MAX_WRAP_RATIO
    const val MAX_CENTS = 25.0
    const val MIN_CONFIDENCE = .5f
    const val MAX_SPREAD_DB = 6.0
    private const val SPAN = 12

    class Zone(val midi: Int, val seconds: Double, val ms: Double, val channels: Int, val wrap: Double,
               val hz: Float?, val cents: Double?, val confidence: Float?, val spreadDb: Double, val levelDb: Double) {
        val wrapOk get() = wrap <= MAX_WRAP_RATIO
        val pitchOk get() = cents != null && abs(cents) <= MAX_CENTS && (confidence ?: 0f) >= MIN_CONFIDENCE
        /** A whole number of octaves off, within [MAX_CENTS]: the right note class, an octave to check by ear. */
        val octaveOff get() = cents != null && !pitchOk && abs(cents) >= 600 &&
            abs(cents - 1200.0 * Math.round(cents / 1200.0)) <= MAX_CENTS && (confidence ?: 0f) >= MIN_CONFIDENCE
        val steadyOk get() = spreadDb <= MAX_SPREAD_DB
        val audible get() = levelDb > -60.0
        val ok get() = wrapOk && (pitchOk || octaveOff) && steadyOk && audible
    }

    class Row(val engine: String, val voice: String, val preset: String, val zones: List<Zone>, val failure: String? = null) {
        /** Every zone in the same octave relation to its key: a keyboard whose notes jump octaves is not one. */
        val octaveSteady get() = zones.mapNotNull { z -> z.cents?.let { Math.round(it / 1200.0) } }.distinct().size <= 1
        val ok get() = failure == null && zones.isNotEmpty() && zones.all { it.ok } && octaveSteady
        fun why(): String = failure ?: buildList {
            if (zones.any { !it.audible }) add("silent")
            if (zones.any { !it.wrapOk }) add("wrap clicks (worst ${"%.1f".format(zones.maxOf { it.wrap })}x)")
            if (zones.any { !it.pitchOk && !it.octaveOff }) add("pitch " + zones.joinToString("/") { z ->
                z.cents?.let { "%+.0fc".format(it) } ?: "none" })
            else if (zones.any { it.octaveOff }) add("octave to check by ear (" + zones.joinToString("/") { z ->
                z.cents?.let { "%+.0fc".format(it) } ?: "none" } + ")")
            if (!octaveSteady) add("octave jumps between notes")
            if (zones.any { !it.steadyOk }) add("pulses (spread up to ${"%.0f".format(zones.maxOf { it.spreadDb })} dB)")
        }.joinToString("; ").ifEmpty { "passes" }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "../testkit/loop-instruments").also { it.mkdirs() }
        val only = args.drop(1).map { it.uppercase() }.toSet()
        val presets = Presets.all().groupBy { it.engine }
        val rows = ArrayList<Row>()
        for (engine in CANDIDATES.filter { only.isEmpty() || it in only }) {
            val byVoice = presets[engine]?.groupBy { it.voiceName }
            if (byVoice == null) {
                rows += Row(engine, "-", "-", emptyList(), "no factory presets")
                println("%-11s no factory presets".format(engine)); continue
            }
            for ((voice, voicePresets) in byVoice) {
                val preset = voicePresets.first()
                val row = runCatching { scan(preset) }.getOrElse {
                    Row(engine, voice, preset.name, emptyList(), it.javaClass.simpleName + ": " + it.message)
                }
                rows += row
                println("%-11s %-12s %-5s %s".format(engine, voice, if (row.ok) "PASS" else "-", row.why()))
            }
        }
        write(out, rows)
        val passing = rows.filter { it.ok }.groupBy { it.engine }
        println("engines with at least one passing voice: ${passing.keys.joinToString()}")
        println("wrote ${rows.size} rows under ${out.absolutePath}")
    }

    fun scan(preset: Patch): Row {
        val held = preset.withMacros(preset.macros + ("HOLD" to 1f))
        val control = SynthRoot.of(held)
            ?: return Row(preset.engine, preset.voiceName, preset.name, emptyList(), "no root note")
        val window = maxOf(control.range.first, control.midi - SPAN)..minOf(control.range.last, control.midi + SPAN)
        val midis = listOf(window.first, control.midi, window.last).distinct()
        val zones = midis.map { midi ->
            val t0 = System.nanoTime()
            val snip = control.at(midi).render()
            val ms = (System.nanoTime() - t0) / 1e6
            measure(midi, snip, ms)
        }
        return Row(preset.engine, preset.voiceName, preset.name, zones)
    }

    fun measure(midi: Int, snip: Snip, ms: Double): Zone {
        val mono = (if (snip.channels == 1) snip else Cleanup.toMono(snip)).samples
        val pitch = Pitch.detect(Snip(mono, 1, snip.sampleRate), fromSec = 0f, windowSec = .5f)
        val want = Keys.midiHz(midi)
        val cents = pitch?.let { 1200.0 * ln(it.hz.toDouble() / want) / ln(2.0) }
        return Zone(midi, mono.size.toDouble() / snip.sampleRate, ms, snip.channels, LoopPadMaker.wrapRatio(mono),
            pitch?.hz, cents, pitch?.confidence, spreadDb(mono, snip.sampleRate), rmsDb(mono, 0, mono.size))
    }

    /** The 10th to 90th percentile spread of 50 ms RMS windows, in dB. */
    fun spreadDb(s: FloatArray, rate: Int): Double {
        val w = rate / 20
        val levels = (0 until s.size / w).map { rmsDb(s, it * w, (it + 1) * w) }.sorted()
        if (levels.size < 4) return 0.0
        return levels[(levels.size * .9).toInt().coerceAtMost(levels.lastIndex)] - levels[(levels.size * .1).toInt()]
    }

    private fun rmsDb(s: FloatArray, from: Int, to: Int): Double {
        var e = 0.0
        for (i in from until to) e += s[i].toDouble() * s[i]
        return 10 * log10(e / (to - from).coerceAtLeast(1) + 1e-20)
    }

    private fun write(out: File, rows: List<Row>) {
        File(out, "loop-instruments.csv").writeText(buildString {
            appendLine("engine,voice,preset,midi,seconds,render_ms,channels,wrap_ratio,hz,cents,confidence,spread_db,level_db,zone_ok,row_ok,why")
            for (r in rows) {
                if (r.zones.isEmpty()) appendLine("${r.engine},${r.voice},\"${r.preset}\",,,,,,,,,,,,false,\"${r.why()}\"")
                for (z in r.zones) appendLine(listOf(r.engine, r.voice, "\"${r.preset}\"", z.midi, "%.2f".format(z.seconds),
                    "%.0f".format(z.ms), z.channels, "%.2f".format(z.wrap), z.hz?.let { "%.1f".format(it) } ?: "",
                    z.cents?.let { "%.1f".format(it) } ?: "", z.confidence?.let { "%.2f".format(it) } ?: "",
                    "%.1f".format(z.spreadDb), "%.1f".format(z.levelDb), z.ok, r.ok, "\"${r.why()}\"").joinToString(","))
            }
        })
        File(out, "loop-instruments.md").writeText(buildString {
            appendLine("| Engine | Voice | Preset | Verdict | Notes | Clip s | Worst wrap | Cents (low/root/high) | Spread dB |")
            appendLine("|---|---|---|---|---|---|---|---|---|")
            for (r in rows) appendLine("| ${r.engine} | ${r.voice} | ${r.preset} | ${if (r.ok) "PASS" else "-"} | ${r.why()} | " +
                (if (r.zones.isEmpty()) "| | |" else "${"%.1f".format(r.zones.maxOf { it.seconds })} | ${"%.1f".format(r.zones.maxOf { it.wrap })} | " +
                    r.zones.joinToString("/") { z -> z.cents?.roundToInt()?.toString() ?: "none" } + " | " +
                    "%.0f".format(r.zones.maxOf { it.spreadDb }) + " |"))
        })
    }
}
