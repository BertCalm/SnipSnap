package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * THAW's listening gate, written to gitignored testkit/thaw-audition/.
 * Run `./gradlew :synth:generateThawAudition`, then open index.html directly.
 * During tuning, `-PthawSections=MATERIAL,HOLD` renders a selected subset.
 * `-PthawFirstListen` renders six neutral C4 voices and the twelve factory patches.
 *
 * Every case has the unlevelled engine output and an [AuditionLevel] comparison;
 * the two files come from the same render. The roster covers every voice at
 * three notes and three gesture energies, all five timbral macros at five
 * settings with companions at MacroSpec.neutral, the five required 3x3 grids,
 * material ablations, separate network taps, stopped carriage, passive tails,
 * extremes and ordinary/difficult HOLD buffers. No rack effects are applied.
 * CSV traces and compact material curves accompany the recorded probes. The
 * manifest includes inputs, raw/matched measurements, render cost and measured
 * loop convergence; it is also inlined in the page so file URLs work.
 *
 * HOLD contains settled material rather than the initial cold engagement.
 * A bounded cycle comparison and a small seam value do not constitute a sonic
 * verdict: listen for a living material, pitched capture and repeated tails.
 */
object ThawAuditionGenerator {

    private val timbral = listOf("CONTACT", "HEAT", "FREEZE", "CHANNELS", "THICKNESS")
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private val notes = listOf(0f to "C3", .5f to "C4", 1f to "C5")
    private val energies = listOf(.25f to "QUIET", .65f to "MEDIUM", 1f to "STRONG")

    private data class Case(
        val section: String,
        val group: String,
        val id: String,
        val label: String,
        val description: String,
        val voice: ThawVoice,
        val macros: Map<String, Float>,
        val velocity: Float = 1f,
        val record: Boolean = false,
        val thermal: Boolean = true,
        val channels: Boolean = true,
        val stopSeconds: Float? = null,
        val durationSeconds: Float? = null,
        val loop: Boolean = false,
    )

    private val sectionDescriptions = linkedMapOf(
        "VOICES" to "All six voices, C3/C4/C5, and quiet/medium/strong gesture energy. Knobs remain at each voice's defaults.",
        "PRESETS" to "All twelve named factory patches with their saved settings, including the two settled held sounds. The names and material identities require a listening verdict.",
        "SWEEPS" to "CONTACT, HEAT, FREEZE, CHANNELS and THICKNESS at 0/.25/.5/.75/1 for every voice. Companion controls use their declared neutral values; HOLD is off.",
        "GRIDS" to "The five required interactions at 0/.5/1, with neutral companions. Each grid uses the voice that best exposes its interaction.",
        "MATERIAL" to "Default material evolution, frozen thermal state, disabled liquid transport, and separate plate/enclosure taps. Inspect the liquid curves and download the state traces.",
        "EDGES" to "Nearly dry contact, maximum heating with weak cooling, rapid cooling, high-register scrape, and all five timbral controls at maximum on every voice.",
        "PASSIVE" to "Runner withdrawal starts at 0.7 seconds and both powered runners stop by 0.76 seconds, followed by passive resonance and cooling. Compare with each voice's natural eight-second tail.",
        "HOLD" to "Settled loop-only buffers for every voice, then difficult wet/cold/heavy and high-register cycles. Enable Repeat and listen across several wraps. Initial cold attack is absent from this sample format.",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/thaw-audition")
        require(root.mkdirs() || root.isDirectory) { "cannot create ${root.absolutePath}" }
        val selected = args.drop(1).firstOrNull { it.startsWith("--sections=") }
            ?.substringAfter('=')?.split(',')?.map { it.trim().uppercase() }?.toSet()
        require(selected == null || selected.isNotEmpty() && selected.all { it in sectionDescriptions }) {
            "--sections must name one or more of ${sectionDescriptions.keys.joinToString() }"
        }
        val firstListen = "--first-listen" in args.drop(1)
        val descriptions = if (firstListen) sectionDescriptions + ("VOICES" to
            "All six voices at C4, strong gesture energy and the five timbral controls' declared neutral values. These are distinct from the full audition's voice-default clips.") else sectionDescriptions
        val cases = (if (firstListen) firstListenCases() else cases())
            .filter { selected == null || it.section in selected }
        val sections = descriptions.mapValues { mutableListOf<Map<String, Any?>>() }
        var rendered = 0
        var wavs = 0
        val started = System.nanoTime()

        fun writeCase(c: Case, samples: FloatArray, elapsedMs: Double, extra: Map<String, Any?> = emptyMap()) {
            require(samples.isNotEmpty() && samples.all { it.isFinite() }) { "${c.id}: invalid raw audio" }
            // Raw exports must remain raw: WavWriter would silently clip values
            // outside [-1,1], so refuse rather than disguising an unstable case.
            require(samples.all { abs(it) <= 1f }) { "${c.id}: raw output exceeds PCM range" }
            val raw = Snip(samples, channels = 1, sampleRate = Dsp.RATE)
            val matched = AuditionLevel.level(raw)
            val stem = "${c.section}/${c.id}"
            WavWriter.write(File(root, "${stem}_raw.wav"), raw, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, "${stem}_matched.wav"), matched, WavWriter.BitDepth.PCM_24)
            wavs += 2
            sections.getValue(c.section) += linkedMapOf<String, Any?>(
                "id" to c.id,
                "group" to c.group,
                "label" to c.label,
                "description" to c.description,
                "voice" to c.voice.name,
                "macros" to c.macros,
                "velocity" to c.velocity,
                "raw" to "${stem}_raw.wav",
                "matched" to "${stem}_matched.wav",
                "rawMetrics" to metrics(raw),
                "matchedMetrics" to metrics(matched),
                "renderMs" to elapsedMs,
                "loop" to c.loop,
            ) + extra
        }

        for (c in cases) {
            val began = System.nanoTime()
            if (c.loop) {
                val loop = Thaw.renderLoopMeasured(c.voice, c.macros, velocity = c.velocity, normalize = false)
                val elapsed = (System.nanoTime() - began) / 1e6
                writeCase(c, loop.samples, elapsed, mapOf(
                    "loopEvidence" to mapOf(
                        "seam" to finiteEvidence(loop.seam),
                        "stateError" to finiteEvidence(loop.stateError),
                        "cycles" to loop.cycles,
                        "converged" to loop.converged,
                        "groupErrors" to loop.groupErrors.mapValues { finiteEvidence(it.value) },
                        "rawRms" to finiteEvidence(loop.rawRms),
                        "metricsDefined" to (loop.seam.isFinite() && loop.stateError.isFinite()),
                        "audibleRaw" to (loop.rawRms.isFinite() && loop.rawRms > 1e-5),
                    ),
                ))
            } else {
                val played = Thaw.play(c.voice, c.macros, Thaw.Probe(
                    record = c.record,
                    thermal = c.thermal,
                    channelTransfer = c.channels,
                    velocity = c.velocity,
                    stopSeconds = c.stopSeconds,
                    durationSeconds = c.durationSeconds,
                ))
                val raw = Thaw.finish(played.raw, normalize = false)
                val elapsed = (System.nanoTime() - began) / 1e6
                val extra = mutableMapOf<String, Any?>(
                    "probe" to mapOf(
                        "thermal" to c.thermal,
                        "channelTransfer" to c.channels,
                        "stopSeconds" to c.stopSeconds,
                        "durationSeconds" to c.durationSeconds,
                    ),
                    "maxAcousticEnergy" to played.maxEnergy,
                    "recoveredStates" to played.recoveredStates,
                )
                played.trace?.let { trace ->
                    val path = "${c.section}/${c.id}_trace.csv"
                    writeTrace(File(root, path), trace)
                    extra["traceCsv"] = path
                    extra["material"] = materialSummary(trace)
                }
                writeCase(c, raw, elapsed, extra)

                // These are actual network outputs from the same recorded run,
                // not three independent sounds or another source underneath it.
                if (c.id == "channel_material") {
                    listOf(
                        Triple("direct", "Dominant plate", played.direct),
                        Triple("neighbors", "Responding plates", played.neighbors),
                        Triple("enclosure", "Wooden enclosure", played.enclosure),
                    ).forEach { (id, label, tap) ->
                        requireNotNull(tap) { "recorded THAW tap $id is missing" }
                        val tapCase = c.copy(id = "channel_$id", group = "CHANNEL network taps", label = label,
                            description = "Isolated $label from the full CHANNEL material run; raw gain is preserved.")
                        writeCase(tapCase, Thaw.finish(tap, normalize = false), 0.0, mapOf("tapOf" to c.id))
                    }
                }
            }
            rendered++
            if (rendered % 20 == 0 || rendered == cases.size) {
                println("THAW audition: $rendered/${cases.size} renders, $wavs WAVs")
            }
        }

        val manifest = Json.write(value(linkedMapOf(
            "engine" to "THAW",
            "firstListen" to firstListen,
            "renderCount" to rendered,
            "caseCount" to sections.values.sumOf { it.size },
            "wavCount" to wavs,
            "sampleRate" to Dsp.RATE,
            "bitDepth" to 24,
            "elapsedSeconds" to (System.nanoTime() - started) / 1e9,
            "rawMeaning" to "Band-limited, DC-cleaned engine audio before melodic loudness targeting. No per-clip raw gain is applied.",
            "matchedMeaning" to "The same raw render matched to AuditionLevel's shared loudest-200ms RMS target, with its peak guard.",
            "sections" to descriptions.filterKeys { sections.getValue(it).isNotEmpty() }.map { (id, desc) ->
                mapOf("id" to id, "description" to desc, "cases" to sections.getValue(id))
            },
        ))) + "\n"
        File(root, "manifest.json").writeText(manifest)
        val template = ThawAuditionGenerator::class.java.getResourceAsStream("/audition/thaw-audition.html")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("missing /audition/thaw-audition.html")
        require("/* THAW_MANIFEST */" in template) { "THAW page has no manifest insertion point" }
        File(root, "index.html").writeText(template.replace("/* THAW_MANIFEST */", "window.THAW_MANIFEST = $manifest;"))
        File(root, "README.txt").writeText(
            "THAW audition\n" +
                "Regenerate: ./gradlew :synth:generateThawAudition" +
                (if (firstListen) " -PthawFirstListen" else "") +
                (selected?.let { " -PthawSections=${it.joinToString(",")}" } ?: "") + "\n" +
                "$rendered renders, ${sections.values.sumOf { it.size }} listening cases, $wavs PCM-24 WAV files.\n" +
                "Open index.html directly. Every case provides RAW and MATCHED from the same render.\n" +
                "Raw files retain engine amplitude before melodic loudness normalization. Matched files share AuditionLevel.\n" +
                "Material and passive cases include 100 Hz CSV diagnostic traces and inline curves.\n" +
                "HOLD samples contain settled sustain; listen across wraps. Check measured convergence in the manifest.\n" +
                "These clips require the owner's sonic verdict; metrics alone do not establish the material identity.\n",
        )
        println("wrote $wavs WAVs ($rendered renders) + manifest.json + index.html under ${root.absolutePath}")
    }

    private fun cases(): List<Case> = buildList {
        for (preset in ThawPresets.all()) {
            val held = Thaw.isLoop(preset.macros["HOLD"] ?: 0f)
            add(Case("PRESETS", preset.voice.name, "preset_${preset.name.lowercase().replace(' ', '_')}",
                preset.name, "${preset.voice.name}; ${line(preset.macros)}." +
                    if (held) " Settled loop-only material; initial cold engagement is absent." else "",
                preset.voice, preset.macros, loop = held))
        }
        for (voice in ThawVoice.entries) {
            for ((tune, note) in notes) for ((energy, name) in energies) {
                add(Case("VOICES", voice.name, "${tag(voice)}_${note.lowercase()}_${name.lowercase()}",
                    "$note $name", "${voice.name} defaults; gesture energy ${fmt(energy)}.", voice,
                    Thaw.defaults(voice) + ("TUNE" to tune), velocity = energy))
            }
            val neutral = Thaw.macrosFor(voice).associate { it.name to it.neutral } + ("TUNE" to .5f) + ("HOLD" to 0f)
            for (macro in timbral) for (step in steps) {
                add(Case("SWEEPS", "${voice.name} / $macro", "${tag(voice)}_${macro.lowercase()}_${code(step)}",
                    "$macro ${fmt(step)}", "All other timbral controls at neutral; C4, strong gesture.", voice,
                    neutral + (macro to step)))
            }
            add(Case("MATERIAL", "Default material trajectories", "${tag(voice)}_material", voice.name,
                "Default cold engagement, warming and withdrawal. Curves show evolving liquid and channels.",
                voice, Thaw.defaults(voice), record = true))
            add(Case("EDGES", "All controls high", "${tag(voice)}_all_high", "${voice.name}: all high",
                "All five timbral controls at 1; HOLD off, C4.", voice,
                Thaw.defaults(voice) + timbral.associateWith { 1f } + ("HOLD" to 0f)))
            add(Case("PASSIVE", "Stopped carriage", "${tag(voice)}_stopped", "${voice.name}: stopped at .7 s",
                "Withdrawal begins at .7 seconds; both runners stop by .76 seconds. Passive resonance, cooling and finite stress releases continue in the eight-second clip.",
                voice, Thaw.defaults(voice), record = true, stopSeconds = .7f, durationSeconds = 8f))
            add(Case("PASSIVE", "Long natural tails", "${tag(voice)}_tail", "${voice.name}: eight-second tail",
                "The normal finite gesture followed by passive cooling; no indefinitely powered contact.",
                voice, Thaw.defaults(voice), record = true, durationSeconds = 8f))
            add(Case("HOLD", "Every voice held", "${tag(voice)}_held", "${voice.name}: HOLD 1",
                "Settled sustain material. Repeat for seam, pumping and event repetition checks.",
                voice, Thaw.defaults(voice) + ("HOLD" to 1f), loop = true))
        }

        val interactions = listOf(
            Triple("CONTACT", "HEAT", ThawVoice.RUNNER),
            Triple("HEAT", "FREEZE", ThawVoice.MELT),
            Triple("CHANNELS", "FREEZE", ThawVoice.FROST),
            Triple("THICKNESS", "HEAT", ThawVoice.SHEET),
            Triple("CONTACT", "THICKNESS", ThawVoice.BRITTLE),
        )
        for ((a, b, voice) in interactions) {
            val neutral = Thaw.macrosFor(voice).associate { it.name to it.neutral } + ("TUNE" to .5f) + ("HOLD" to 0f)
            for (x in listOf(0f, .5f, 1f)) for (y in listOf(0f, .5f, 1f)) {
                add(Case("GRIDS", "$a × $b / ${voice.name}", "${a.lowercase()}${code(x)}_${b.lowercase()}${code(y)}",
                    "$a ${fmt(x)} · $b ${fmt(y)}", "${voice.name}, C4. Companion controls at neutral.",
                    voice, neutral + (a to x) + (b to y)))
            }
        }
        add(Case("MATERIAL", "Causal comparisons", "runner_frozen_state", "RUNNER: thermal evolution off",
            "Same RUNNER gesture as RUNNER material; temperature, liquid and channel phase state are held cold.",
            ThawVoice.RUNNER, Thaw.defaults(ThawVoice.RUNNER), record = true, thermal = false))
        add(Case("MATERIAL", "Causal comparisons", "channel_transfer_off", "CHANNEL: liquid transport off",
            "Same CHANNEL gesture as CHANNEL material; local heating continues while delayed channel transport is disabled.",
            ThawVoice.CHANNEL, Thaw.defaults(ThawVoice.CHANNEL), record = true, channels = false))

        fun edge(id: String, label: String, voice: ThawVoice, settings: Map<String, Float>, duration: Float? = null) {
            add(Case("EDGES", "Material extremes", id, label, "${voice.name}; ${line(settings)}. Other controls at voice defaults.",
                voice, Thaw.defaults(voice) + settings, record = true, durationSeconds = duration))
        }
        edge("nearly_dry", "Nearly dry, light contact", ThawVoice.BRITTLE,
            mapOf("CONTACT" to .05f, "HEAT" to 0f, "FREEZE" to 1f))
        edge("maximum_wetness", "Maximum heating, weak cooling", ThawVoice.MELT,
            mapOf("CONTACT" to .8f, "HEAT" to 1f, "FREEZE" to 0f, "CHANNELS" to 1f), 6f)
        edge("rapid_cooling", "Rapid cooling and constrained channels", ThawVoice.FROST,
            mapOf("HEAT" to .85f, "FREEZE" to 1f, "CHANNELS" to 1f), 6f)
        edge("high_scrape", "High-register, heavy runner scrape", ThawVoice.RUNNER,
            mapOf("TUNE" to 1f, "CONTACT" to 1f, "HEAT" to .4f, "THICKNESS" to .1f))

        for ((id, voice, settings) in listOf(
            Triple("wet", ThawVoice.MELT, mapOf("HEAT" to 1f, "FREEZE" to 0f, "CHANNELS" to 1f)),
            Triple("cold", ThawVoice.FROST, mapOf("CONTACT" to 1f, "HEAT" to 1f, "FREEZE" to 1f, "CHANNELS" to 1f)),
            Triple("heavy", ThawVoice.SHEET, timbral.associateWith { 1f }),
        )) {
            add(Case("HOLD", "Difficult held material", "held_$id", "${voice.name}: $id HOLD",
                "${line(settings)}. Settled loop-only output; consult the convergence evidence before judging the seam.",
                voice, Thaw.defaults(voice) + settings + ("HOLD" to 1f), loop = true))
        }
        add(Case("HOLD", "Difficult held material", "held_high_melt", "MELT: high-register HOLD",
            "C5, voice defaults and HOLD 1. The high-register endpoint tests complete material and acoustic convergence.",
            ThawVoice.MELT, Thaw.defaults(ThawVoice.MELT) + mapOf("TUNE" to 1f, "HOLD" to 1f), loop = true))
    }

    private fun firstListenCases(): List<Case> = cases().filter { it.section == "PRESETS" } +
        ThawVoice.entries.map { voice ->
            Case("VOICES", voice.name, "${tag(voice)}_c4_strong_neutral", "C4 STRONG / NEUTRAL",
                "${voice.name} at C4; strong gesture and timbral companions at neutral.", voice,
                Thaw.macrosFor(voice).associate { it.name to it.neutral } + mapOf("TUNE" to .5f, "HOLD" to 0f))
        }

    private fun metrics(snip: Snip): Map<String, Double> {
        var peak = 0.0
        var sum = 0.0
        var energy = 0.0
        for (sample in snip.samples) {
            val x = sample.toDouble()
            peak = max(peak, abs(x)); sum += x; energy += x * x
        }
        return mapOf(
            "seconds" to snip.samples.size.toDouble() / snip.sampleRate,
            "peak" to peak,
            "rms" to sqrt(energy / snip.samples.size),
            "dc" to sum / snip.samples.size,
            "loudness" to Loudness.of(snip).toDouble(),
        )
    }

    private fun materialSummary(trace: Thaw.Trace): Map<String, Any> {
        val count = trace.runnerVelocity.size
        val indices = List(minOf(80, count)) { i -> if (count <= 1) 0 else i * (count - 1) / max(1, minOf(80, count) - 1) }
        fun maximum(arrays: Array<FloatArray>) = arrays.maxOfOrNull { it.maxOrNull()?.toDouble() ?: 0.0 } ?: 0.0
        fun meanAt(arrays: Array<FloatArray>, i: Int) = if (arrays.isEmpty()) 0.0 else arrays.sumOf { it[i].toDouble() } / arrays.size
        return mapOf(
            "maxTemperature" to maximum(trace.temperature),
            "maxLiquid" to maximum(trace.liquid),
            "maxChannel" to maximum(trace.channel),
            "stressEventSamples" to trace.events.count { it > 0f },
            "totalContactWork" to trace.contactWork.sumOf { it.toDouble() } / trace.rate,
            "liquidLedgerError" to trace.liquidTotal.indices.maxOfOrNull {
                abs(trace.liquidTotal[it] - trace.liquidBalance[it]).toDouble()
            }.orZero(),
            "curves" to mapOf(
                "seconds" to indices.map { it.toDouble() / trace.rate },
                "liquid" to indices.map { trace.liquid.firstOrNull()?.get(it)?.toDouble() ?: 0.0 },
                "channel" to indices.map { meanAt(trace.channel, it) },
            ),
        )
    }

    private fun writeTrace(file: File, trace: Thaw.Trace) {
        file.parentFile.mkdirs()
        val stride = max(1, trace.rate / 100)
        file.bufferedWriter().use { out ->
            val headings = listOf("seconds", "runner_velocity", "contact_force", "contact_work") +
                trace.temperature.indices.map { "temperature_$it" } + trace.liquid.indices.map { "liquid_$it" } +
                trace.channel.indices.map { "channel_$it" } + trace.stress.indices.map { "stress_$it" } +
                listOf("stress_events", "acoustic_energy", "liquid_total", "liquid_balance")
            out.appendLine(headings.joinToString(","))
            for (i in trace.runnerVelocity.indices step stride) {
                val row = listOf(i.toDouble() / trace.rate, trace.runnerVelocity[i], trace.contactForce[i], trace.contactWork[i]) +
                    trace.temperature.map { it[i] } + trace.liquid.map { it[i] } + trace.channel.map { it[i] } +
                    trace.stress.map { it[i] } + listOf(trace.events[i], trace.energy[i], trace.liquidTotal[i], trace.liquidBalance[i])
                out.appendLine(row.joinToString(","))
            }
        }
    }

    private fun tag(voice: ThawVoice) = voice.name.lowercase()
    private fun finiteEvidence(v: Double): Double? = if (v.isFinite()) v else null
    private fun Double?.orZero() = this ?: 0.0
    private fun code(v: Float) = (v * 100).roundToInt().toString().padStart(3, '0')
    private fun fmt(v: Float) = "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
    private fun line(macros: Map<String, Float>) = macros.entries.joinToString(" · ") { "${it.key} ${fmt(it.value)}" }

    private fun value(v: Any?): JsonValue = when (v) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(v)
        is Boolean -> JsonValue.Bool(v)
        is Number -> v.toDouble().let { require(it.isFinite()) { "non-finite diagnostic value" }; JsonValue.Num(it) }
        is Map<*, *> -> JsonValue.Obj(v.entries.associate { it.key.toString() to value(it.value) })
        is Iterable<*> -> JsonValue.Arr(v.map { value(it) })
        else -> error("cannot write ${v::class.simpleName} to the THAW manifest")
    }
}
