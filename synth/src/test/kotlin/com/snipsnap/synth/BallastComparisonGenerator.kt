package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/** Focused listening gate: bass preservation, a glass ladder, structural controls and true touch. */
object BallastComparisonGenerator {
    private data class Case(val id: String, val title: String, val group: String, val role: String,
                            val voice: BallastVoice, val changes: Map<String, Float> = emptyMap(),
                            val bassOnly: Boolean = false, val velocitySequence: Boolean = false)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args[0]).apply { mkdirs() }
        val cases = buildList {
            for (voice in listOf(BallastVoice.ROOT, BallastVoice.DEEP)) add(Case("bass_${voice.name.lowercase()}",
                "${voice.name} · bass alone", "Bass preservation", "The original bass oscillator and filter, with the structure removed.", voice, bassOnly = true))
            for (glass in listOf(.15f, .4f, .75f)) add(Case("root_glass_${(glass * 100).toInt()}",
                "ROOT · GLASS ${(glass * 100).toInt()}%", "Glass ladder", "Same C2 bass and shared gain: quiet contact through a clear rattle.", BallastVoice.ROOT,
                mapOf("GLASS" to glass, "FRAME" to .6f)))
            for (glass in listOf(.25f, .5f, .85f)) add(Case("prism_glass_${(glass * 100).toInt()}",
                "PRISM · GLASS ${(glass * 100).toInt()}%", "Glass ladder", "Formerly GLINT; listen for graduated glass intensity.", BallastVoice.GLINT,
                mapOf("GLASS" to glass, "FRAME" to .6f)))
            for (span in listOf(0f, 1f)) add(Case("wire_span_${span.toInt()}", "WIRE · SPAN ${span.toInt()}",
                "Structural controls", "GLASS is off; narrow root wires versus the full octave spread.", BallastVoice.WIRE,
                mapOf("SPAN" to span, "GLASS" to 0f, "SYMPATHY" to .7f)))
            for (frame in listOf(0f, 1f)) add(Case("root_frame_${frame.toInt()}", "ROOT · FRAME ${frame.toInt()}",
                "Structural controls", "GLASS is off; a tight body versus a soft, ringing frame.", BallastVoice.ROOT,
                mapOf("FRAME" to frame, "GLASS" to 0f)))
            for (voice in listOf(BallastVoice.ROOT, BallastVoice.GLINT, BallastVoice.BLOOM)) add(Case("velocity_${voice.name.lowercase()}",
                "${if (voice == BallastVoice.GLINT) "PRISM" else voice.name} · soft / medium / hard", "Velocity",
                "Velocities .3 / .65 / 1 share one fixed reference gain; no clip or touch is normalized independently.", voice,
                velocitySequence = true))
            add(Case("swarm_factory", "SWARM · dense rattle", "Factory character", "Dense glass at the factory macro recipe, without replacing the underlying bass.", BallastVoice.SWARM))
        }
        val gainFile = File(root.parentFile, "reference-gain.txt")
        val referenceMacros = Ballast.defaults(BallastVoice.ROOT) + mapOf("TUNE" to 12 / 36f, "HOLD" to 0f, "GLASS" to 0f)
        val referenceRun = run(BallastVoice.ROOT, referenceMacros, 1.0)
        val reference = snip(Ballast.condition(referenceRun.raw))
        val gain = if (gainFile.exists()) gainFile.readText().trim().toFloat() else {
            // Leave headroom for the added structural and glass energy at the other settings.
            val g = minOf(AuditionLevel.level(reference).peak() / reference.peak(), .45f / reference.peak())
            gainFile.writeText("$g\n")
            g
        }
        val recipes = mutableListOf<String>()
        val measurements = mutableListOf<String>()
        for (case in cases) {
            val macros = Ballast.defaults(case.voice) + mapOf("TUNE" to 12 / 36f, "HOLD" to 0f) + case.changes
            val runs = (if (case.velocitySequence) listOf(.3, .65, 1.0) else listOf(1.0)).map { run(case.voice, macros, it) }
            val tail = Ballast.totalSeconds(case.voice, macros) - Ballast.gateSeconds(macros.getValue("HOLD")) - Ballast.RELEASE_SECONDS
            val pieces = runs.map { run -> Ballast.fadeOneShot(Ballast.condition(if (case.bassOnly) run.parts!!.direct else run.raw), tail) }
            val gap = (Dsp.RATE * .12).toInt()
            val output = FloatArray(pieces.sumOf { it.size } + gap * (pieces.size - 1))
            var at = 0
            for (piece in pieces) {
                for (i in piece.indices) output[at + i] = piece[i] * gain
                at += piece.size + gap
            }
            require(output.all { it.isFinite() && abs(it) <= .99f }) { "${case.id}: shared gain clipped" }
            WavWriter.write(File(root, "${case.id}.wav"), snip(output), WavWriter.BitDepth.PCM_16)
            recipes += """{"id":"${case.id}","title":"${case.title}","group":"${case.group}","role":"${case.role}","voice":"${case.voice.name}","macros":{${macros.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}},"referenceGain":$gain,"velocities":[${if (case.velocitySequence) "0.3,0.65,1" else "1"}]}"""
            measurements += """{"id":"${case.id}","runs":[${runs.map { r -> """{"directRms":${rms(r.parts!!.direct)},"frameRms":${rms(r.parts.frame)},"stringsRms":${rms(r.parts.strings)},"glassRms":${rms(r.parts.glass)},"knocks":${r.stats.knocks},"peak":${r.raw.maxOf { abs(it) }}}""" }.joinToString(",") }]}"""
        }
        File(root, "recipes.json").writeText(recipes.joinToString(",\n", "[", "]\n"))
        File(root, "measurements.json").writeText(measurements.joinToString(",\n", "[", "]\n"))
        println("Rendered ${cases.size} Ballast comparison clips to $root; shared reference gain $gain")
    }

    private fun run(voice: BallastVoice, macros: Map<String, Float>, velocity: Double): Ballast.Run =
        Ballast.simulate(voice, Ballast.oneShotPlan(voice, Ballast.frequencyFor(voice, macros.getValue("TUNE")).toDouble(), macros), macros,
            velocity, Ballast.Probe(record = true))

    private fun snip(samples: FloatArray) = Snip(samples, channels = 1, sampleRate = Dsp.RATE)
    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
}
