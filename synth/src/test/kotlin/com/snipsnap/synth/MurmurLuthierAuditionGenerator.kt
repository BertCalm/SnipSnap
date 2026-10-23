package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Bounded, experimental listening comparisons. Neither candidate replaces a factory patch.
 * KEY uses ordinary serialized model-2 targets. CHOIR's longer loop is a test-only render
 * policy, explicitly carried beside its base recipe, because model 2 has a fixed short plan.
 */
object MurmurLuthierAuditionGenerator {
    private const val LISTENING_TARGET = .03f
    private val STEPS = listOf(0f, .5f, .75f, 1f)

    private data class Case(
        val id: String, val comparisonId: String, val group: String, val variant: String,
        val title: String, val description: String, val patch: MurmurPatch,
        val longLoop: Boolean = false,
    )
    private data class Plan(val frames: Int, val root: Double, val cycles: Int, val ratios: DoubleArray)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply one output directory" }
        val output = File(args.firstOrNull() ?: "../testkit/murmur-luthier-audition")
        output.mkdirs()
        val rows = mutableListOf<Map<String, Any?>>()
        // Compile-time access deliberately remains unchanged. Reflection is confined to this
        // experimental tool, so no test policy becomes an app API or a new saved render model.
        for (midi in listOf(36, 48)) verifyReflectedFinish(midi)
        for (case in cases()) {
            val patch = case.patch
            val macros = Murmur.defaults(patch.voice) + patch.macros
            val plan = if (Murmur.isLoop(macros.getValue("HOLD"))) {
                if (case.longLoop) longPlan(patch) else defaultPlan(patch)
            } else null
            val start = System.nanoTime()
            val probe = if (case.longLoop) renderWithPlan(patch, plan!!) else
                Murmur.probe(patch.voice, macros, patch.midi, patch.velocity, patch.config)
            val renderMs = (System.nanoTime() - start) / 1e6
            val again = if (case.longLoop) renderWithPlan(patch, plan!!) else
                Murmur.probe(patch.voice, macros, patch.midi, patch.velocity, patch.config)
            check(probe.samples.contentEquals(again.samples)) { "Nondeterministic ${case.id}" }
            check(probe.previousCycle.contentEquals(again.previousCycle)) { "Nondeterministic continuation ${case.id}" }
            val raw = Snip(probe.samples, 1, Dsp.RATE)
            check(raw.samples.isNotEmpty() && raw.samples.all { it.isFinite() } && raw.peak() <= 1f)
            val matched = AuditionLevel.level(raw)
            val rawPath = "raw/${case.id}.wav"
            val matchedPath = "matched/${case.id}.wav"
            val recipePath = "recipes/${case.id}.json"
            WavWriter.write(File(output, rawPath), raw, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(output, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            val policy = if (case.longLoop) linkedMapOf(
                "name" to "choir-beat-period-v1", "productionRecipe" to false,
                "rootCycles" to plan!!.cycles, "frames" to plan.frames,
                "rule" to "Nearest even root-cycle count to one cycle of requested operator-4 beating; nearest 16 output frames. Other operators use nearest whole cycles.",
                "reproduction" to "MurmurLuthierAuditionGenerator.renderWithPlan using this basePatch and recorded loop plan; basePatch.render alone retains the approved short loop.",
            ) else null
            val recipe = if (case.longLoop) json(linkedMapOf(
                "schemaVersion" to 1, "experimental" to true,
                "basePatch" to patch.toJsonValue(), "loopPolicy" to policy,
            )) else patch.toJsonValue()
            File(output, recipePath).apply { parentFile.mkdirs(); writeText(Json.write(recipe) + "\n") }
            rows += linkedMapOf(
                "id" to case.id, "comparisonId" to case.comparisonId, "group" to case.group,
                "variant" to case.variant, "title" to case.title, "description" to case.description,
                "voice" to patch.voice.name, "midi" to patch.midi, "velocity" to patch.velocity,
                "model" to patch.config.model, "macros" to macros,
                "config" to patch.toJsonValue().obj().getValue("config"),
                "loop" to (plan != null), "frames" to raw.frameCount, "durationSeconds" to raw.durationSeconds,
                "rawPath" to rawPath, "matchedPath" to matchedPath, "recipePath" to recipePath,
                "rawSha256" to sha256(File(output, rawPath)), "matchedSha256" to sha256(File(output, matchedPath)),
                "rawMetrics" to metrics(raw), "matchedMetrics" to metrics(matched),
                "matchedGain" to Loudness.of(matched) / Loudness.of(raw).coerceAtLeast(1e-9f),
                "renderMilliseconds" to renderMs, "requestedHz" to Keys.midiHz(patch.midi),
                "renderedRootHz" to probe.rootHz, "seamError" to probe.seamError.takeIf { plan != null },
                "loopPlan" to plan?.let { planMetrics(it, patch, macros) },
                "experimentalLoopPolicy" to policy, "deterministicFloatPcm" to true,
            )
            println("MURMUR Luthier ${rows.size}/12: ${case.id}")
        }
        val manifest = linkedMapOf(
            "schemaVersion" to 1, "title" to "Murmur · Luthier comparisons",
            "sourceRevision" to git("rev-parse", "HEAD"),
            "sourceTreeDirty" to (git("status", "--porcelain")?.isNotBlank()),
            "sourceSha256" to sha256(File("src/main/kotlin/com/snipsnap/synth/Murmur.kt")),
            "generatorSha256" to sha256(File("src/test/kotlin/com/snipsnap/synth/MurmurLuthierAuditionGenerator.kt")),
            "clipCount" to rows.size, "wavCount" to rows.size * 2,
            "sampleRate" to Dsp.RATE, "channels" to 1, "bitDepth" to 24, "internalOversampling" to Dsp.OVERSAMPLE,
            "listeningTarget" to LISTENING_TARGET,
            "acceptance" to "Experimental comparisons awaiting owner listening. Approved model-1/model-2 voices and the factory bank remain unchanged.",
            "keyCandidate" to "Ordinary model-2 custom targets: each existing target gain multiplied by 3, capped at the existing limit of 3. Centers, widths, sources, envelopes, macros and ratios are unchanged. This is a conservative contour candidate, not a complete fix for weak KEY motion.",
            "choirCandidate" to "Longer test-only loop plan retains requested near-unison beating more closely. It also changes repeated-noise period and whole-cycle formant motion quantization; this comparison does not isolate beating from all duration-dependent effects.",
            "seamMetric" to "Keys.seamError between two independently synthesized adjacent settled cycles; reflected finish verified bit-identical against current probe for both comparison notes.",
            "timingNote" to "Single JVM render excluding encoding and metrics. Cloud CPU/JIT timing, not phone latency. Long-loop render cost scales with duration.",
            "hearing" to "No listening claim. Measurements do not approve the candidate sound.",
            "clips" to rows,
        )
        File(output, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        File(output, "README.md").writeText("""
            # Murmur Luthier comparisons

            Experimental listening evidence, with no factory or default voice replacement.
            The manifest identifies source revision, source/generator hashes, complete settings,
            WAV hashes, level matching, loop plans, and cloud JVM render cost.

            Reproduce from the SnipSnap checkout containing
            `synth/src/test/kotlin/com/snipsnap/synth/MurmurLuthierAuditionGenerator.kt`:

            ```sh
            ./gradlew --no-daemon :synth:generateMurmurLuthierAudition -PmurmurLuthierOutput=/absolute/output/directory
            ```

            KEY compares MOTION 0, .5, .75 and 1 with the approved targets and an ordinary
            model-2 custom patch whose target gains are multiplied by 3, capped at 3.
            Its recipe JSON is a normal MurmurPatch recipe. Source balance, widths, centers,
            operator ratios and envelope are unchanged. MOTION 0 exposes the static tone trade.

            CHOIR compares C2 and C3 held loops with unchanged voice and macros. The candidate
            uses a test-only longer loop plan, near one complete cycle of the requested beating.
            Its JSON is an experimental wrapper with a basePatch and loopPolicy, not an ordinary
            loadable patch. Rendering the basePatch alone gives the approved short loop.
            The generator uses the actual production synthesizer, verifies its reflected finish
            against the approved short-loop render byte for byte, and records all candidate
            cycle counts. Longer loops also change the repeated-noise period and the number of
            whole formant-motion cycles, and take longer to render. They are not a pure detune A/B.

            Raw WAVs preserve the final-rate engine buffer before loudness targeting. Matched
            WAVs use the shared .03 audition target with the .99 peak guard. Both are mono
            44.1 kHz PCM24. Every clip is checked for finite samples and repeated float-PCM
            determinism. Held seam measurements compare independently simulated adjacent cycles.
            No measurement is a claim of listening acceptance.
        """.trimIndent() + "\n")
    }

    private fun cases(): List<Case> = buildList {
        val key = Murmur.configurationFor(MurmurVoice.KEY)
        val stronger = key.copy(targets = key.targets.map { target ->
            target.copy(gains = target.gains.map { (it * 3.0).coerceAtMost(3.0) })
        })
        for (motion in STEPS) for (candidate in listOf(false, true)) {
            val variant = if (candidate) "candidate" else "current"
            val pair = "key_motion_${(motion * 100).roundToInt().toString().padStart(3, '0')}"
            add(Case("${pair}_$variant", pair, "key-motion", variant,
                "KEY · MOTION $motion · ${if (candidate) "stronger formants" else "approved"}",
                if (candidate) "Target gains ×3, capped at 3. Compare development after the strike at the same matched level."
                else "Approved source and targets. MOTION 0 is the static control.",
                MurmurPatch("KEY MOTION STUDY", MurmurVoice.KEY,
                    Murmur.defaults(MurmurVoice.KEY) + ("MOTION" to motion), 60, 1f,
                    if (candidate) stronger else key)))
        }
        for (midi in listOf(36, 48)) for (candidate in listOf(false, true)) {
            val variant = if (candidate) "candidate" else "current"
            val pair = "choir_hold_c${midi / 12 - 1}"
            add(Case("${pair}_$variant", pair, "choir-hold", variant,
                "CHOIR · C${midi / 12 - 1} · ${if (candidate) "longer loop" else "approved loop"}",
                if (candidate) "Experimental loop plan preserves the requested mild detune more closely. Repeat several wraps and compare the slow beating and moving mouth."
                else "Approved short-loop policy rounds every operator to a whole cycle. At C2, operator 4 becomes unison.",
                MurmurPatch("CHOIR HOLD STUDY", MurmurVoice.CHOIR,
                    Murmur.defaults(MurmurVoice.CHOIR) + ("HOLD" to 1f), midi), candidate))
        }
    }

    private fun longPlan(patch: MurmurPatch): Plan {
        val delta = abs(patch.config.ratios[3] - 1.0)
        require(delta > 0.0)
        val cycles = ((1.0 / delta / 2.0).roundToInt() * 2).coerceAtLeast(2)
        val hz = Keys.midiHz(patch.midi).toDouble()
        val frames = (cycles * Dsp.RATE / hz / 16.0).roundToInt() * 16
        require(frames / Dsp.RATE.toDouble() <= Murmur.MAX_SECONDS)
        return Plan(frames, cycles * Dsp.RATE / frames.toDouble(), cycles,
            DoubleArray(4) { (patch.config.ratios[it] * cycles).roundToInt() / cycles.toDouble() })
    }

    private fun defaultPlan(patch: MurmurPatch): Plan {
        val planner = Murmur::class.java.getDeclaredMethod("planLoop", Double::class.javaPrimitiveType, List::class.java)
            .apply { isAccessible = true }
        val plan = planner.invoke(Murmur, Keys.midiHz(patch.midi).toDouble(), patch.config.ratios)
        fun value(name: String): Any = plan.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(plan)
        return Plan(value("getFrames") as Int, value("getRoot") as Double, value("getCycles") as Int,
            value("getRatios") as DoubleArray)
    }

    private fun verifyReflectedFinish(midi: Int) {
        val patch = MurmurPatch("REFERENCE", MurmurVoice.CHOIR, Murmur.defaults(MurmurVoice.CHOIR) + ("HOLD" to 1f), midi)
        val standard = Murmur.probe(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config)
        val reflected = renderWithPlan(patch, defaultPlan(patch))
        check(standard.samples.contentEquals(reflected.samples) && standard.previousCycle.contentEquals(reflected.previousCycle) &&
            standard.seamError == reflected.seamError) { "Experimental finish differs from the production probe at MIDI $midi" }
    }

    private fun renderWithPlan(patch: MurmurPatch, plan: Plan): MurmurProbe {
        val loopClass = Class.forName("com.snipsnap.synth.Murmur\$LoopPlan")
        val constructor = loopClass.declaredConstructors.single().apply { isAccessible = true }
        val privatePlan = constructor.newInstance(plan.frames, plan.root, plan.cycles, plan.ratios)
        val synthesize = Murmur::class.java.declaredMethods.single { it.name == "synthesize" }.apply { isAccessible = true }
        val macros = Murmur.defaults(patch.voice) + patch.macros
        val attack = patch.config.attack * (.75 + macros.getValue("BREATH") * .9 + (1.0 - patch.velocity) * .4)
        // Gate has no effect in held synthesis; the original value is still supplied so the
        // reference-policy verification checks the exact production argument list.
        val gate = max(3.75 + macros.getValue("HOLD") * 1.4, attack + patch.config.decay + .05)
            .coerceAtMost(Murmur.MAX_SECONDS - patch.config.release)
        val period = plan.frames * Dsp.OVERSAMPLE
        val raw = synthesize.invoke(Murmur, patch.voice, macros, patch.midi, patch.velocity.toDouble(), patch.config,
            privatePlan, gate, attack, period * 2, period * 3 + 512 * Dsp.OVERSAMPLE) as FloatArray
        val down = Dsp.decimate(raw, Dsp.RATE)
        val previous = down.copyOfRange(plan.frames, plan.frames * 2)
        val samples = down.copyOfRange(plan.frames * 2, plan.frames * 3)
        val dc = (samples.sumOf { it.toDouble() } / samples.size).toFloat()
        for (i in samples.indices) { samples[i] -= dc; previous[i] -= dc }
        return MurmurProbe(samples, previous, plan.root, Keys.seamError(previous + samples, previous.size))
    }

    private fun planMetrics(plan: Plan, patch: MurmurPatch, macros: Map<String, Float>): Map<String, Any?> {
        val trajectory = (patch.config.referenceSeconds / (1.0 + .55 * sqrt(macros.getValue("MOTION").toDouble()))).coerceIn(.1, 3.0)
        return linkedMapOf(
            "rootCycles" to plan.cycles, "ratios" to plan.ratios.toList(),
            "requestedDetuneCents" to 1200 * ln(patch.config.ratios[3]) / ln(2.0),
            "detuneCents" to 1200 * ln(plan.ratios[3]) / ln(2.0),
            "requestedBeatHz" to abs(patch.config.ratios[3] - 1.0) * Keys.midiHz(patch.midi),
            "beatHz" to abs(plan.ratios[3] - 1.0) * plan.root,
            "contourCycles" to ((plan.frames / Dsp.RATE.toDouble()) / trajectory).roundToInt().coerceIn(1, 24),
            "rootErrorCents" to 1200 * ln(plan.root / Keys.midiHz(patch.midi)) / ln(2.0),
        )
    }

    private fun metrics(snip: Snip): Map<String, Any> = linkedMapOf(
        "peak" to snip.peak(), "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.frameCount),
        "dc" to snip.samples.sumOf { it.toDouble() } / snip.frameCount, "loudness" to Loudness.of(snip),
    )
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun git(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args).redirectError(File("/dev/null")).start()
        val text = process.inputStream.bufferedReader().use { it.readText().trim() }
        text.takeIf { process.waitFor() == 0 }
    }.getOrNull()
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported manifest type ${value::class}")
    }
}
