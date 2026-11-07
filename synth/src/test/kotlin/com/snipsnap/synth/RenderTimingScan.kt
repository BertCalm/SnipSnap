package com.snipsnap.synth

import java.io.File

/**
 * Measures what the playability rule cares about: how long one knob turn takes to
 * re-render, for every registered engine with factory presets
 * (docs/SYNTH_ROADMAP.md, "The instant loop": one-shots render in tens of ms).
 *
 * For each voice, the first factory preset is rendered once to warm the JIT, then
 * [TIMED] more times. Each render nudges one non-TUNE knob by a different
 * thousandth, the way a knob turn does, so an engine's render cache cannot answer
 * from a hit. The median of the timed renders is reported, with the clip's length
 * and the cost per second of audio. Timings are evidence for where to look, never a
 * pass or fail: they depend on the machine and on whatever else it is running.
 *
 * Writes `render-times.csv` and `render-times.md` into the first argument (default
 * `../testkit/render-times`). Run via `./gradlew :synth:scanRenderTimes`. An engine
 * argument after the folder limits the scan to those engines.
 */
object RenderTimingScan {

    private const val TIMED = 3

    class Row(val engine: String, val voice: String, val preset: String, val medianMs: Double,
              val seconds: Double, val msPerSecond: Double, val failure: String? = null)

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "../testkit/render-times").also { it.mkdirs() }
        val only = args.drop(1).map { it.uppercase() }.toSet()
        val rows = ArrayList<Row>()
        val byEngine = Presets.all().groupBy { it.engine }.filterKeys { only.isEmpty() || it in only }
        for ((engine, presets) in byEngine.toSortedMap()) {
            for ((voice, voicePresets) in presets.groupBy { it.voiceName }) {
                val preset = voicePresets.first()
                rows += runCatching { time(preset) }.getOrElse {
                    Row(engine, voice, preset.name, Double.NaN, Double.NaN, Double.NaN, it.javaClass.simpleName + ": " + it.message)
                }
                val r = rows.last()
                println("%-14s %-14s %9.1f ms  %6.2f s  %8.1f ms/s  %s".format(engine, voice, r.medianMs, r.seconds, r.msPerSecond, r.failure ?: ""))
            }
        }
        File(out, "render-times.csv").writeText(buildString {
            appendLine("engine,voice,preset,median_ms,seconds,ms_per_second,failure")
            for (r in rows) appendLine("${r.engine},${r.voice},\"${r.preset}\",${"%.2f".format(r.medianMs)},${"%.3f".format(r.seconds)},${"%.1f".format(r.msPerSecond)},${r.failure ?: ""}")
        })
        File(out, "render-times.md").writeText(buildString {
            appendLine("| Engine | Voice | Preset | Median ms | Clip s | ms per audio s |")
            appendLine("|---|---|---|---|---|---|")
            for (r in rows.sortedByDescending { if (it.medianMs.isNaN()) Double.MAX_VALUE else it.medianMs })
                appendLine("| ${r.engine} | ${r.voice} | ${r.preset} | ${"%.0f".format(r.medianMs)} | ${"%.2f".format(r.seconds)} | ${"%.0f".format(r.msPerSecond)} |" + (r.failure?.let { " $it" } ?: ""))
        })
        println("wrote ${rows.size} rows under ${out.absolutePath}")
    }

    private fun time(preset: Patch): Row {
        val knob = Velocity.macroSpecsFor(preset).map { it.name }.firstOrNull { it != "TUNE" && it != "HOLD" }
        fun nudged(i: Int): Patch = if (knob == null) preset else {
            val v = preset.macros[knob] ?: Velocity.macroSpecsFor(preset).first { it.name == knob }.default
            preset.withMacros(preset.macros + (knob to (v + (if (v > .5f) -1 else 1) * .001f * (i + 1)).coerceIn(0f, 1f)))
        }
        val warm = nudged(0).render()
        val times = (1..TIMED).map { i ->
            val p = nudged(i)
            val t0 = System.nanoTime()
            p.render()
            (System.nanoTime() - t0) / 1e6
        }.sorted()
        val median = times[times.size / 2]
        val seconds = warm.samples.size.toDouble() / warm.channels / warm.sampleRate
        return Row(preset.engine, preset.voiceName, preset.name, median, seconds, median / seconds.coerceAtLeast(1e-3))
    }
}
