package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * CISTERN's provisional listening roster, without rack effects. Every gesture
 * has an unlevelled 24-bit WAV and an [AuditionLevel] matched comparison.
 * Includes every voice at C2/C4/C6 and three velocities, five-step macro
 * sweeps with neutral companions, five interaction grids, presets, the kit,
 * and causal/loading/inventory/HOLD diagnostics. Measurements are evidence
 * for listening, not a claim that the engine or its names have passed it.
 *
 * Run `./gradlew :synth:generateCisternAudition`, then open the generated
 * `testkit/cistern-audition/index.html`. Its embedded manifest also works
 * from a local file, without a web server or external services.
 */
object CisternAuditionGenerator {
    private data class Clip(val json: String)
    private data class Group(val label: String, val clips: List<Clip>)
    private data class Section(val id: String, val display: String, val body: String, val groups: List<Group>)
    private data class Boundary(
        val step: Double,
        val slopeChange: Double,
        val derivativeRms: Double,
        val secondDerivativeRms: Double,
        val stepRatio: Double,
        val slopeRatio: Double,
    ) {
        fun json() = "{\"step\":$step,\"slopeChange\":$slopeChange,\"derivativeRms\":$derivativeRms," +
            "\"secondDerivativeRms\":$secondDerivativeRms,\"stepRatio\":$stepRatio,\"slopeRatio\":$slopeRatio}"
    }

    // Numerical click screening against the local signal's ordinary movement.
    // This is distinct from cycle convergence and does not replace listening.
    private const val MAX_BOUNDARY_RATIO = 8.0
    private const val MAX_CYCLE_DIFFERENCE = 2e-5
    private const val MAX_RELATIVE_CYCLE_DIFFERENCE = 1e-4
    private const val MAX_CARRIED_BOUNDARY_ERROR = 1e-3
    private const val PCM24_UNIT = 1.0 / 8_388_607.0

    private val STEPS = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    private val NOTES = listOf(36 to "C2", 60 to "C4", 84 to "C6")
    private val VELOCITIES = listOf(0.25f to "quiet", 0.6f to "medium", 1f to "strong")
    private val BODIES = mapOf(
        CisternVoice.FIRST to "A clear melodic strike with sparse answers.",
        CisternVoice.DRIP to "Distinct rounded delayed contacts on the same surface.",
        CisternVoice.CASCADE to "An expanding chain of impacts from a finite reservoir.",
        CisternVoice.POOL to "A heavy wet membrane, with softened later contacts.",
        CisternVoice.RIPPLE to "Small dense articulated contacts around the tonal center.",
        CisternVoice.RECOVERY to "The wet surface settling toward a lighter response.",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/cistern-audition")
        root.mkdirs()
        // A failed rerender must not leave an old page claiming the new matrix completed.
        for (name in listOf("manifest.json", "index.html")) {
            val old = File(root, name)
            require(!old.exists() || old.delete()) { "cannot replace old CISTERN audition $old" }
        }
        val sections = mutableListOf<Section>()
        val generatedFiles = mutableSetOf<String>()
        var gestures = 0
        var renderNanos = 0L
        var maxBufferBytes = 0L

        fun writeResult(
            section: String,
            id: String,
            label: String,
            description: String,
            voice: CisternVoice,
            macros: Map<String, Float>,
            midi: Int,
            velocity: Float,
            result: Cistern.Rendered,
            fullDiagnostics: Boolean = false,
            rawSamples: FloatArray = result.raw,
            countRender: Boolean = true,
        ): Clip {
            val dir = File(root, section)
            val raw = Snip(rawSamples, channels = 1, sampleRate = Dsp.RATE)
            require(raw.samples.all { it.isFinite() } && raw.peak() <= 1f) {
                "$section/$id cannot be written as unaltered raw PCM: non-finite samples or peak ${raw.peak()} exceeds full scale"
            }
            val matched = AuditionLevel.level(raw)
            val d = result.diagnostics
            require(d.nonFiniteRecoveries == 0 && listOf(d.initialInventory, d.maxInventoryError, d.maxLoad, d.maxEnergy, d.finalEnergy).all { it.isFinite() }) {
                "$section/$id contains an invalid model-state report or ${d.nonFiniteRecoveries} non-finite state recoveries"
            }
            val expectsLoop = Cistern.isLoop(Cistern.defaults(voice).plus(macros).getValue("HOLD"))
            require(!expectsLoop || result.loop != null) { "$section/$id requests HOLD but supplies no validated cycle report" }
            val rawBoundary = result.loop?.let { boundary(raw.samples) }
            val matchedBoundary = result.loop?.let { boundary(matched.samples) }
            result.loop?.let { loop ->
                require(loop.converged && loop.stateDiff.isFinite() && loop.materialDiff.isFinite() && loop.periodDiff.isFinite() && loop.eventDiff.isFinite() &&
                    loop.stateDiff < MAX_CYCLE_DIFFERENCE && loop.materialDiff < MAX_CYCLE_DIFFERENCE &&
                    loop.periodDiff < MAX_CYCLE_DIFFERENCE && loop.eventDiff < MAX_CYCLE_DIFFERENCE &&
                    loop.iterations in 1..Cistern.MAX_HOLD_CYCLES) {
                    "$section/$id HOLD did not converge: acoustic ${loop.stateDiff}, material ${loop.materialDiff}, audio ${loop.periodDiff}, events ${loop.eventDiff}, cycles ${loop.iterations}; limit $MAX_CYCLE_DIFFERENCE"
                }
                require(loop.seam.isFinite() && loop.seam < Keys.MAX_SEAM_ERROR) {
                    "$section/$id HOLD independent-cycle seam ${loop.seam} exceeds ${Keys.MAX_SEAM_ERROR}; an output crossfade cannot establish source convergence"
                }
                require(loop.relativePeriodDiff.isFinite() && loop.relativePeriodDiff < MAX_RELATIVE_CYCLE_DIFFERENCE &&
                    loop.boundaryStepError.isFinite() && loop.boundaryStepError < MAX_CARRIED_BOUNDARY_ERROR &&
                    loop.boundarySlopeError.isFinite() && loop.boundarySlopeError < MAX_CARRIED_BOUNDARY_ERROR) {
                    "$section/$id HOLD does not match its actual carried continuation: relative audio ${loop.relativePeriodDiff} (limit $MAX_RELATIVE_CYCLE_DIFFERENCE), boundary step ${loop.boundaryStepError}, slope ${loop.boundarySlopeError} (limit $MAX_CARRIED_BOUNDARY_ERROR)"
                }
                for ((version, edge) in listOf("raw" to requireNotNull(rawBoundary), "matched" to requireNotNull(matchedBoundary))) {
                    require(edge.stepRatio.isFinite() && edge.slopeRatio.isFinite() &&
                        edge.stepRatio <= MAX_BOUNDARY_RATIO && edge.slopeRatio <= MAX_BOUNDARY_RATIO) {
                        "$section/$id $version PCM24 HOLD boundary is discontinuous: step ratio ${edge.stepRatio}, slope ratio ${edge.slopeRatio}, limit $MAX_BOUNDARY_RATIO"
                    }
                }
            }
            WavWriter.write(File(dir, "${id}_raw.wav"), raw, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(dir, "${id}_matched.wav"), matched, WavWriter.BitDepth.PCM_24)
            generatedFiles += "$section/${id}_raw.wav"
            generatedFiles += "$section/${id}_matched.wav"
            val releases = d.events.count { it.kind.name == "RELEASE" }
            val landings = d.events.count { it.kind.name == "LANDING" }
            val secondary = d.events.count { event ->
                event.kind.name == "RELEASE" && d.events.any { it.id == event.cause && it.kind.name == "LANDING" }
            }
            val loopReport = result.loop?.let { loop ->
                "{\"periodSeconds\":${loop.period.size.toDouble() / Dsp.RATE},\"independentCycleSeam\":${loop.seam}," +
                    "\"boundaryRatioLimit\":$MAX_BOUNDARY_RATIO,\"rawBoundary\":${requireNotNull(rawBoundary).json()}," +
                    "\"matchedBoundary\":${requireNotNull(matchedBoundary).json()}," +
                    "\"cycleDifferenceLimit\":$MAX_CYCLE_DIFFERENCE,\"periodDiff\":${loop.periodDiff},\"stateDiff\":${loop.stateDiff},\"materialDiff\":${loop.materialDiff},\"eventDiff\":${loop.eventDiff}," +
                    "\"relativeCycleDifferenceLimit\":$MAX_RELATIVE_CYCLE_DIFFERENCE,\"relativePeriodDiff\":${loop.relativePeriodDiff}," +
                    "\"carriedBoundaryErrorLimit\":$MAX_CARRIED_BOUNDARY_ERROR,\"boundaryStepError\":${loop.boundaryStepError},\"boundarySlopeError\":${loop.boundarySlopeError}," +
                    "\"iterations\":${loop.iterations},\"converged\":${loop.converged},\"pumpCapacity\":${loop.pumpCapacity}," +
                    "\"pumpDelay\":${loop.pumpDelay},\"eventsPerPeriod\":${loop.eventsPerPeriod},\"crossfadeSamples\":${loop.crossfadeSamples}}"
            } ?: "null"
            val metrics = "{\"durationSeconds\":${raw.durationSeconds},\"raw\":${audioStats(raw)}," +
                "\"matched\":${audioStats(matched)},\"renderMillis\":${result.renderNanos / 1e6}," +
                "\"bufferBytes\":${result.bytes},\"slots\":${d.slotCount},\"releases\":$releases," +
                "\"landings\":$landings,\"secondaryReleases\":$secondary," +
                "\"maxInventoryError\":${d.maxInventoryError},\"maxLoad\":${d.maxLoad}," +
                "\"maxEnergy\":${d.maxEnergy},\"finalEnergy\":${d.finalEnergy}," +
                "\"nonFiniteRecoveries\":${d.nonFiniteRecoveries},\"pendingLandings\":${d.pendingLandings},\"loopReport\":$loopReport}"
            val diagnosticsPath = if (fullDiagnostics) "$section/${id}_diagnostics.json" else null
            if (fullDiagnostics) {
                val events = d.events.joinToString(",") { e ->
                    "{\"id\":${e.id},\"kind\":${q(e.kind.name)},\"time\":${e.time},\"slot\":${e.slot}," +
                        "\"region\":${e.region},\"cause\":${e.cause},\"mass\":${e.mass},\"travel\":${e.travel},\"impulse\":${e.impulse}}"
                }
                val traces = d.traces.joinToString(",") { t ->
                    "{\"time\":${t.time},\"surface\":[${t.surface.joinToString(",")}]," +
                        "\"suspended\":${t.suspended},\"airborne\":${t.airborne},\"drained\":${t.drained}," +
                        "\"pump\":${t.pump},\"inventory\":${t.inventory},\"energy\":${t.energy}," +
                        "\"dissipated\":${t.dissipated},\"inputEnergy\":${t.inputEnergy},\"drainFlow\":${t.drainFlow}}"
                }
                File(root, requireNotNull(diagnosticsPath)).writeText(
                    "{\"initialInventory\":${d.initialInventory},\"metrics\":$metrics,\"events\":[$events],\"traces\":[$traces]}\n",
                )
            }
            gestures++
            if (countRender) renderNanos += result.renderNanos
            maxBufferBytes = maxOf(maxBufferBytes, result.bytes.toLong())
            val macroJson = Cistern.defaults(voice).plus(macros).entries.joinToString(",") { (key, value) -> "${q(key)}:$value" }
            return Clip(
                "{\"id\":${q(id)},\"name\":${q(label)},\"description\":${q(description)}," +
                    "\"voice\":${q(voice.name)},\"midi\":$midi,\"velocity\":$velocity,\"macros\":{$macroJson}," +
                    "\"loop\":${result.loop != null},\"raw\":${q("$section/${id}_raw.wav")}," +
                    "\"matched\":${q("$section/${id}_matched.wav")},\"metrics\":$metrics," +
                    "\"diagnostics\":${diagnosticsPath?.let(::q) ?: "null"}}",
            )
        }

        fun capture(
            section: String,
            id: String,
            label: String,
            voice: CisternVoice,
            macros: Map<String, Float> = emptyMap(),
            midi: Int = Cistern.DEFAULT_MIDI,
            velocity: Float = 1f,
            description: String = "The remaining controls use this voice's defaults.",
            seconds: Float? = null,
            noDrops: Boolean = false,
            frozenLoad: Boolean = false,
            fullDiagnostics: Boolean = false,
        ): Clip {
            val result = try {
                Cistern.renderInternal(voice, macros, midi, velocity, seconds = seconds, noDrops = noDrops, frozenLoad = frozenLoad)
            } catch (error: IllegalArgumentException) {
                throw IllegalStateException("CISTERN audition $section/$id failed at MIDI $midi, velocity $velocity, controls ${macroLine(Cistern.defaults(voice) + macros)}", error)
            }
            return writeResult(section, id, label, description, voice, macros, midi, velocity, result, fullDiagnostics)
        }

        val kit = SynthKits.cistern().mapIndexed { index, pad ->
            val arranged = requireNotNull(pad) { "CISTERN kit pad ${index + 1} is empty" }
            val recipe = PadRecipe.fromJsonValue(requireNotNull(arranged.recipe))
            val patch = requireNotNull(recipe.patch) as CisternPatch
            val tag = "A%02d".format(index + 1)
            capture("KIT", tag.lowercase() + "_" + slug(patch.name), "$tag ${patch.name}", patch.voice, patch.macros, patch.midi, patch.velocity,
                description = "The dry factory pad at MIDI ${patch.midi}; names and sounds are awaiting listening.")
        }
        sections += Section("KIT", "The sixteen-pad kit", "Factory gestures, dry, with their recipe's note and macros.", listOf(Group("Factory pads", kit)))

        for (voice in CisternVoice.entries) {
            val section = voice.name
            val groups = mutableListOf(Group("Voice default", listOf(capture(section, "default", "Default C4", voice))))
            groups += Group("Range and event energy", NOTES.flatMap { (midi, note) ->
                VELOCITIES.map { (velocity, energy) ->
                    capture(section, "midi_${midi}_velocity_${tag(velocity)}", "$note · $energy", voice, midi = midi, velocity = velocity,
                        description = "MIDI $midi, velocity $velocity. Velocity changes strike energy and the resulting release response.")
                }
            })
            val neutral = Cistern.macrosFor(voice).associate { it.name to it.neutral }
            for (macro in Cistern.macrosFor(voice)) {
                groups += Group("${macro.name} · five steps, neutral companions", STEPS.map { value ->
                    capture(section, "${macro.name.lowercase()}_${tag(value)}", "${macro.name} $value", voice,
                        neutral + (macro.name to value), description = "Other controls at their declared neutral values: ${macroLine(neutral)}.")
                })
            }
            groups += Group("Provisional presets", CisternPresets.forVoice(voice).map { preset ->
                capture(section, "preset_" + slug(preset.name), preset.name, voice, preset.macros, preset.midi, preset.velocity,
                    description = "Authored from the design; listening and release naming checks are pending.")
            })
            sections += Section(section, voice.name, BODIES.getValue(voice), groups)
            println("rendered ${voice.name}; $gestures gestures so far")
        }

        val grids = listOf(
            Triple(CisternVoice.CASCADE, "STRIKE" to "SUSPENSION", "Initial release reach"),
            Triple(CisternVoice.CASCADE, "DROP" to "SUSPENSION", "Secondary branching"),
            Triple(CisternVoice.POOL, "DROP" to "SKIN", "Contact projection and loading"),
            Triple(CisternVoice.POOL, "DROP" to "DRAIN", "Accumulated wetness"),
            Triple(CisternVoice.RECOVERY, "SKIN" to "DRAIN", "Recovering resonance"),
        )
        sections += Section("GRIDS", "The five interactions", "Each pair at 0, .5 and 1; other controls at their declared neutral values.", grids.map { (voice, pair, meaning) ->
            val (a, b) = pair
            val neutral = Cistern.macrosFor(voice).associate { it.name to it.neutral }
            Group("$a × $b · $meaning", listOf(0f, 0.5f, 1f).flatMap { x ->
                listOf(0f, 0.5f, 1f).map { y ->
                    capture("GRIDS", "${a.lowercase()}${tag(x)}_${b.lowercase()}${tag(y)}", "$a $x · $b $y", voice,
                        neutral + mapOf(a to x, b to y), description = "${voice.name}: $meaning. Other controls neutral.")
                }
            })
        })

        val diagnostics = mutableListOf<Group>()
        diagnostics += Group("The struck surface alone", listOf(
            capture("DIAGNOSTICS", "isolated_strike", "Isolated strike · no drops", CisternVoice.FIRST, noDrops = true,
                description = "Diagnostic reservoir disabled; this is separate from SUSPENSION 0.", fullDiagnostics = true),
            capture("DIAGNOSTICS", "restrained_cascade", "Restrained cascade", CisternVoice.FIRST, mapOf("SUSPENSION" to 0f),
                description = "A normal low macro setting still has available droplets.", fullDiagnostics = true),
        ))
        val wet = Cistern.defaults(CisternVoice.POOL) + mapOf("STRIKE" to 0.8f, "SUSPENSION" to 0.8f, "DROP" to 0.9f, "DRAIN" to 0.15f)
        val full = Cistern.renderInternal(CisternVoice.POOL, wet)
        val frozen = Cistern.renderInternal(CisternVoice.POOL, wet, frozenLoad = true)
        diagnostics += Group("Changing surface · identical scheduled contacts", listOf(
            writeResult("DIAGNOSTICS", "loading_full", "Accumulating load", "The landing schedule is shared with the frozen-load comparison.", CisternVoice.POOL, wet, 60, 1f, full, true),
            writeResult("DIAGNOSTICS", "loading_frozen", "Frozen load", "The same scheduled contacts; wet mass and damping changes disabled.", CisternVoice.POOL, wet, 60, 1f, frozen, true),
            writeResult("DIAGNOSTICS", "frame_only", "Frame alone", "The frame's output from the full accumulating-load gesture.", CisternVoice.POOL, wet, 60, 1f, full, rawSamples = full.frame, countRender = false),
            writeResult("DIAGNOSTICS", "drops_only", "Drops alone", "Initial audible strike omitted, with the full gesture's release and landing schedule replayed.",
                CisternVoice.POOL, wet, 60, 1f, Cistern.renderInternal(CisternVoice.POOL, wet, initialStrike = false, replayEvents = full.diagnostics.events), true),
        ))
        val allHigh = Cistern.macrosFor(CisternVoice.CASCADE).associate { it.name to if (it.name == "HOLD") 0f else 1f }
        diagnostics += Group("One-shot extremes and spent inventory", listOf(
            capture("DIAGNOSTICS", "all_high", "All timbral controls high", CisternVoice.CASCADE, allHigh, fullDiagnostics = true),
            capture("DIAGNOSTICS", "retained_heavy", "Heavy retained liquid", CisternVoice.POOL, wet + ("DRAIN" to 0f), fullDiagnostics = true),
            capture("DIAGNOSTICS", "fast_drain", "Fast drainage", CisternVoice.RECOVERY, wet + ("DRAIN" to 1f), fullDiagnostics = true),
            capture("DIAGNOSTICS", "dense_high", "Dense impacts · C6", CisternVoice.RIPPLE, mapOf("STRIKE" to 1f, "SUSPENSION" to 1f, "DROP" to 0.45f, "SKIN" to 1f), 84, fullDiagnostics = true),
            capture("DIAGNOSTICS", "spent_inventory", "Spent reservoir · twelve seconds", CisternVoice.CASCADE,
                mapOf("STRIKE" to 1f, "SUSPENSION" to 1f, "DROP" to 1f, "DRAIN" to 1f, "HOLD" to 0f),
                seconds = 12f, description = "Extended diagnostic horizon: an exhausted one-shot cannot replenish itself or restart spent slots.", fullDiagnostics = true),
        ))
        diagnostics += Group("Difficult powered HOLD cycles", listOf(
            capture("DIAGNOSTICS", "hold_retained_low", "Heavy · slow drain · C2", CisternVoice.POOL,
                wet + mapOf("DROP" to 1f, "DRAIN" to 0f, "HOLD" to 1f), 36, fullDiagnostics = true),
            capture("DIAGNOSTICS", "hold_fast_high", "Dense · fast drain · C6", CisternVoice.RIPPLE,
                allHigh + ("HOLD" to 1f), 84, fullDiagnostics = true),
            *NOTES.map { (midi, note) ->
                capture("DIAGNOSTICS", "hold_sparse_$midi", "Sparse · quiet · $note", CisternVoice.FIRST,
                    mapOf("STRIKE" to 0f, "SUSPENSION" to 0f, "DROP" to 0f, "DRAIN" to 1f, "HOLD" to 1f), midi, velocity = 0.25f,
                    description = "Quiet, sparse powered circulation at MIDI $midi; acoustic, material, event and PCM wrap continuity must validate before this clip is published.",
                    fullDiagnostics = true)
            }.toTypedArray(),
        ))
        sections += Section("DIAGNOSTICS", "Causality, loading and circulation", "These probes expose the model's state outside the six product controls. Listen to raw and matched versions; inspect event and liquid traces where provided.", diagnostics)

        // Changed kit/preset names must not leave obsolete generated clips in
        // a successful matrix. Preserve unrelated files and clean only this
        // generator's audio filename patterns within its declared sections.
        val sectionNames = sections.map { it.id }.toSet()
        for (file in root.walkTopDown().filter { it.isFile }) {
            val relative = file.relativeTo(root).invariantSeparatorsPath
            if (relative.substringBefore('/') in sectionNames &&
                (file.name.endsWith("_raw.wav") || file.name.endsWith("_matched.wav")) &&
                relative !in generatedFiles) {
                require(file.delete()) { "cannot remove obsolete CISTERN clip $file" }
            }
        }
        val sectionJson = sections.joinToString(",\n") { s ->
            val groups = s.groups.joinToString(",") { g -> "{\"label\":${q(g.label)},\"clips\":[${g.clips.joinToString(",") { it.json }}]}" }
            "{\"id\":${q(s.id)},\"display\":${q(s.display)},\"body\":${q(s.body)},\"groups\":[$groups]}"
        }
        val manifest = "{\"engine\":\"CISTERN\",\"auditionRevision\":\"voice-contrast-2\",\"provisional\":true,\"listening\":\"pending\",\"gestures\":$gestures," +
            "\"wavFiles\":${gestures * 2},\"renderMillis\":${renderNanos / 1e6},\"maxBufferBytes\":$maxBufferBytes," +
            "\"holdValidation\":{\"sourceSeamLimit\":${Keys.MAX_SEAM_ERROR},\"cycleDifferenceLimit\":$MAX_CYCLE_DIFFERENCE," +
            "\"relativeCycleDifferenceLimit\":$MAX_RELATIVE_CYCLE_DIFFERENCE,\"carriedBoundaryErrorLimit\":$MAX_CARRIED_BOUNDARY_ERROR," +
            "\"boundaryRatioLimit\":$MAX_BOUNDARY_RATIO,\"boundaryContextSamples\":256,\"pcmBits\":24},\"sections\":[$sectionJson]}"
        File(root, "manifest.json").writeText("$manifest\n")
        val template = CisternAuditionGenerator::class.java.getResourceAsStream("/audition/cistern-audition.html")
            ?.bufferedReader()?.use { it.readText() } ?: error("CISTERN listening page resource missing")
        File(root, "index.html").writeText(template.replace("__CISTERN_MANIFEST__", manifest.replace("<", "\\u003c")))
        println("wrote $gestures gestures (${gestures * 2} WAVs), manifest and listening page under ${root.absolutePath}")
    }

    private fun audioStats(snip: Snip): String {
        var sum = 0.0
        var square = 0.0
        for (sample in snip.samples) {
            sum += sample
            square += sample.toDouble() * sample
        }
        val n = snip.samples.size.coerceAtLeast(1)
        return "{\"peak\":${snip.peak()},\"loudness\":${Loudness.of(snip)},\"rms\":${sqrt(square / n)},\"dc\":${sum / n}}"
    }

    /** Measure the actual last-to-first PCM24 wrap, without comparing repeated copies. */
    private fun boundary(samples: FloatArray): Boundary {
        require(samples.size >= 512) { "HOLD buffer has insufficient boundary context" }
        fun pcm(value: Float) = (value.toDouble() * 8_388_607.0).roundToLong() * PCM24_UNIT
        val edge = DoubleArray(512) { i -> pcm(samples[if (i < 256) samples.size - 256 + i else i - 256]) }
        var firstSum = 0.0
        var secondSum = 0.0
        var firstCount = 0
        var secondCount = 0
        for (i in 1 until edge.size) {
            if (i == 256) continue
            val derivative = edge[i] - edge[i - 1]
            firstSum += derivative * derivative
            firstCount++
        }
        for (i in 2 until edge.size) {
            if (i == 256 || i == 257) continue
            val change = edge[i] - 2.0 * edge[i - 1] + edge[i - 2]
            secondSum += change * change
            secondCount++
        }
        val wrap = edge[256] - edge[255]
        val step = abs(wrap)
        val slopeChange = maxOf(abs(wrap - (edge[255] - edge[254])), abs((edge[257] - edge[256]) - wrap))
        val firstRms = sqrt(firstSum / firstCount)
        val secondRms = sqrt(secondSum / secondCount)
        // A few PCM units are a quantization floor, not a free signal-scale fade.
        val stepRatio = step / maxOf(firstRms, 2.0 * PCM24_UNIT)
        val slopeRatio = slopeChange / maxOf(secondRms, 4.0 * PCM24_UNIT)
        return Boundary(step, slopeChange, firstRms, secondRms, stepRatio, slopeRatio)
    }

    private fun macroLine(macros: Map<String, Float>) = macros.entries.joinToString(" · ") { (name, value) -> "$name $value" }
    private fun tag(value: Float) = (value * 100).toInt().toString().padStart(3, '0')
    private fun slug(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    private fun q(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
}
