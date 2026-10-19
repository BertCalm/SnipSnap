package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Corolla's engineering/listening matrix. Every case has the same simulation written at its native
 * level and at [AuditionLevel]'s shared comparison level, so gain does not decide the macro verdict.
 * Includes the whole pitch/velocity matrix, five-step macro sweeps, five interaction grids, factory
 * presets, neighbor isolation, output-only modulation controls, and passive/powered/held edges.
 * The generated page works directly from disk, uses no remote
 * assets, and keeps listening notes in the browser until the owner exports them.
 *
 * Run `./gradlew :synth:generateCorollaAudition`; output is under testkit/corolla-audition/.
 * For a small tooling smoke check, pass `-PcorollaQuick` (fixed-duration comparisons,
 * defaults and one loop per voice).
 * This generator provides audition evidence; it does not imply human sonic acceptance.
 */
object CorollaAuditionGenerator {

    private val TIMBRE = listOf("PULL", "BLOOM", "FIELD", "CONTACT", "CHAMBER")
    private val STEPS = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    private val NOTES = listOf(0f to "C3", 0.5f to "C4", 1f to "C5")
    private val VELOCITIES = listOf(0.3f, 0.65f, 1f)
    private const val OUTPUT_MOD_DEPTH = 0.65
    private const val HELD_SECONDS = 4f
    private const val VELOCITY_SECTION = "Pitch and velocity"
    private val AUDIO_PATH_FIELDS = listOf("rawPath", "matchedPath", "releaseRawPath", "releaseMatchedPath")

    private data class Case(
        val id: String,
        val section: String,
        val voice: CorollaVoice,
        val label: String,
        val description: String,
        val macros: Map<String, Float>,
        val velocity: Float = 1f,
        val probe: Corolla.Probe = Corolla.Probe(),
        val outputMod: Boolean = false,
        val comparisonSeconds: Float? = null,
    )

    private data class Metrics(
        val peak: Double,
        val rms: Double,
        val dc: Double,
        val loudness: Float,
        val wrapStep: Double?,
        val wrapSlope: Double?,
        val seamError: Double?,
        val endpointStepRatio: Double?,
    ) {
        fun json() = "{\"peak\":$peak,\"rms\":$rms,\"dc\":$dc,\"loudness\":$loudness," +
            "\"wrapStep\":$wrapStep,\"wrapSlope\":$wrapSlope,\"seamError\":$seamError," +
            "\"endpointStepRatio\":$endpointStepRatio}"
    }

    private data class Rendered(val samples: FloatArray, val seamError: Double? = null)
    private data class Prepared(val native: Snip, val metrics: Metrics, val renderMs: Double)
    private data class GroupGain(val matched: Float, val rawExport: Float)

    @JvmStatic
    fun main(args: Array<String>) {
        if ("--help" in args) {
            println(HELP)
            return
        }
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/corolla-audition")
        val quick = "--quick" in args
        val onlyVoice = args.firstOrNull { it.startsWith("--voice=") }?.substringAfter('=')?.let(CorollaVoice::valueOf)
        val finiteOnly = "--finite-only" in args
        val heldOnly = "--held-only" in args
        val velocityOnly = "--velocity-only" in args
        val roster = if (quick) quickCases() + if (velocityOnly) velocityCases(middleOnly = true) else emptyList() else acceptanceCases()
        val cases = roster.filter {
            val held = Corolla.isLoop(Corolla.settled(it.macros, it.voice).getValue("HOLD"))
            (onlyVoice == null || it.voice == onlyVoice) &&
                (!finiteOnly || !held) &&
                ((!heldOnly && !velocityOnly) || (heldOnly && held) || (velocityOnly && it.section == VELOCITY_SECTION))
        }
        require(cases.isNotEmpty()) { "Filters selected no cases; see --help." }
        check(cases.map { it.id }.distinct().size == cases.size) { "duplicate Corolla audition IDs" }
        root.mkdirs()
        val clips = ArrayList<String>(cases.size)
        val start = System.nanoTime()
        // Keep the physical velocity difference. One gain from the hard event is shared by all
        // three velocities at a voice/register; a pair-wide peak guard cannot flatten their levels.
        val preparedVelocities = mutableMapOf<String, Prepared>()
        val velocityGains = cases.filter { it.section == VELOCITY_SECTION }.groupBy(::velocityGroup).mapValues { (_, group) ->
            val prepared = group.associateWith { prepare(it).also { p -> preparedVelocities[it.id] = p } }
            val hard = prepared.getValue(group.first { it.velocity == 1f })
            val peak = prepared.values.maxOf { it.metrics.peak }
            GroupGain(minOf(auditionGain(hard.native), matchedPeakGain(peak)), peakGain(peak))
        }
        if (preparedVelocities.isNotEmpty()) println("Prepared ${preparedVelocities.size} velocity cases in ${velocityGains.size} shared-gain groups")
        var written = 0
        for ((index, case) in cases.withIndex()) {
            val macros = Corolla.defaults(case.voice) + case.macros
            val loop = Corolla.isLoop(macros.getValue("HOLD"))
            val prepared = preparedVelocities.remove(case.id) ?: prepare(case)
            val native = prepared.native
            val metrics = prepared.metrics
            val releaseBefore = System.nanoTime()
            val release = if (loop) native(Corolla.renderHeldNote(case.voice, macros, case.velocity,
                holdSeconds = HELD_SECONDS, normalize = false), "${case.id} release") else null
            val releaseRenderMs = if (release != null) (System.nanoTime() - releaseBefore) / 1_000_000.0 else 0.0
            val releaseMetrics = release?.let { metrics(it, loop = false, seamError = null) }
            val peak = maxOf(metrics.peak, releaseMetrics?.peak ?: 0.0)
            val velocityGroup = if (case.section == VELOCITY_SECTION) velocityGroup(case) else null
            val groupGain = velocityGroup?.let(velocityGains::getValue)
            // Original native loops stay bit-identical. A new release has its own raw PCM safety
            // attenuation; matched playback shares one pair-wide gain across both source buffers.
            val exportGain = groupGain?.rawExport ?: peakGain(metrics.peak)
            val matchedGain = groupGain?.matched ?: minOf(auditionGain(native), matchedPeakGain(peak))
            val rawSnip = gain(native, exportGain)
            val matched = gain(native, matchedGain)
            val rawPath = "raw/${case.id}.wav"
            val matchedPath = "matched/${case.id}.wav"
            WavWriter.write(File(root, rawPath), rawSnip, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            written += 2
            val releaseFields = if (release != null && releaseMetrics != null) {
                val releaseRawPath = "raw/${case.id}_release.wav"
                val releaseMatchedPath = "matched/${case.id}_release.wav"
                val releaseRawExportGain = peakGain(releaseMetrics.peak)
                val releaseMatched = gain(release, matchedGain)
                WavWriter.write(File(root, releaseRawPath), gain(release, releaseRawExportGain), WavWriter.BitDepth.PCM_24)
                WavWriter.write(File(root, releaseMatchedPath), releaseMatched, WavWriter.BitDepth.PCM_24)
                written += 2
                ",\"releaseRawPath\":${q(releaseRawPath)},\"releaseMatchedPath\":${q(releaseMatchedPath)}," +
                    "\"releaseFrames\":${release.frameCount},\"releaseSeconds\":${release.frameCount.toDouble() / Dsp.RATE}," +
                    "\"releaseRawMetrics\":${releaseMetrics.json()},\"releaseMatchedMetrics\":${metrics(releaseMatched, false, null).json()}," +
                    "\"releaseRawExportGain\":$releaseRawExportGain,\"releaseMatchedGain\":$matchedGain," +
                    "\"heldSeconds\":$HELD_SECONDS,\"releaseRenderMs\":$releaseRenderMs"
            } else ""
            val levelMode = when {
                groupGain != null -> "velocity-common-gain"
                loop -> "sustain-release-common-gain"
                else -> "per-clip-loudness"
            }
            val velocityFields = if (velocityGroup != null) ",\"velocityGroup\":${q(velocityGroup)},\"velocityCommonGain\":$matchedGain" else ""
            clips += "{\"id\":${q(case.id)},\"section\":${q(case.section)},\"voice\":${q(case.voice.name)}," +
                "\"label\":${q(case.label)},\"description\":${q(case.description)}," +
                "\"macros\":${macroJson(macros)},\"velocity\":${case.velocity},\"loop\":$loop," +
                "\"requestedMidi\":${Corolla.midiFor(case.voice, macros.getValue("TUNE"))}," +
                "\"requestedHz\":${Corolla.frequencyFor(case.voice, macros.getValue("TUNE"))},\"probe\":${probeJson(case.probe)}," +
                "\"outputMod\":${case.outputMod},\"outputModDepth\":${if (case.outputMod) OUTPUT_MOD_DEPTH else 0.0}," +
                "\"outputModHz\":${if (case.outputMod) Corolla.coreHz(macros.getValue("FIELD"), case.voice) else 0.0}," +
                "\"frames\":${native.frameCount},\"seconds\":${native.frameCount.toDouble() / Dsp.RATE}," +
                "\"renderMs\":${prepared.renderMs},\"renderMsPerAudioSecond\":${prepared.renderMs / (native.frameCount.toDouble() / Dsp.RATE)}," +
                "\"rawPath\":${q(rawPath)},\"matchedPath\":${q(matchedPath)},\"rawExportGain\":$exportGain," +
                "\"matchedLevelMode\":${q(levelMode)},\"matchedGain\":$matchedGain," +
                "\"rawMetrics\":${metrics.json()},\"matchedMetrics\":${metrics(matched, loop, metrics.seamError).json()}$velocityFields$releaseFields}"
            if ((index + 1) % 12 == 0 || index == cases.lastIndex) {
                println("Corolla audition: ${index + 1}/${cases.size} cases ($written WAVs)")
            }
        }
        val partial = onlyVoice != null || finiteOnly || heldOnly || velocityOnly
        val merged = mergePreviousClips(root, clips, partial)
        val manifest = "{\"engine\":\"COROLLA\",\"sampleRate\":${Dsp.RATE},\"bitDepth\":24," +
            "\"pitchRange\":\"C3–C5\",\"sonicAcceptance\":\"pending owner listening\",\"quick\":$quick," +
            "\"metricNotes\":${q(METRIC_NOTES)},\"clips\":[\n${merged.joinToString(",\n")}\n]}\n"
        prunePreviousClips(root, merged)
        File(root, "manifest.json").writeText(manifest)
        // Embed the same manifest to avoid file:// fetch restrictions; do not maintain two clip lists.
        File(root, "index.html").writeText(PAGE.replace("__MANIFEST__", manifest.replace("</", "<\\/")))
        File(root, "README.md").writeText(readme(merged.size, merged.count { "\"releaseRawPath\"" in it }, quick))
        println("Wrote $written WAVs for ${cases.size} cases; manifest has ${merged.size} cases under ${root.absolutePath} " +
            "in %.1f s".format(Locale.ROOT, (System.nanoTime() - start) / 1e9))
    }

    private fun prepare(case: Case): Prepared {
        val macros = Corolla.defaults(case.voice) + case.macros
        val loop = Corolla.isLoop(macros.getValue("HOLD"))
        val before = System.nanoTime()
        val source = renderRaw(case.voice, macros, case.velocity, loop, case.probe)
        val rendered = if (case.outputMod) {
            check(!loop) { "output-only modulation is a finite diagnostic, not a held voice" }
            val hz = Corolla.coreHz(macros.getValue("FIELD"), case.voice)
            source.copy(samples = FloatArray(source.samples.size) { i ->
                (source.samples[i] * (1.0 + OUTPUT_MOD_DEPTH * sin(2.0 * PI * hz * i / Dsp.RATE))).toFloat()
            })
        } else source
        val raw = case.comparisonSeconds?.let { seconds ->
            check(!loop)
            rendered.samples.copyOf((seconds * Dsp.RATE).roundToInt()).also { Dsp.fadeTail(it) }
        } ?: rendered.samples
        val native = native(raw, case.id)
        val renderMs = (System.nanoTime() - before) / 1_000_000.0
        return Prepared(native, metrics(native, loop, rendered.seamError), renderMs)
    }

    private fun native(samples: FloatArray, label: String): Snip {
        require(samples.isNotEmpty() && samples.all { it.isFinite() }) { "non-finite or empty clip: $label" }
        return Snip(samples, channels = 1, sampleRate = Dsp.RATE)
    }

    private fun peakGain(peak: Double) = if (peak > .99) (.99 / peak).toFloat() else 1f

    private fun matchedPeakGain(peak: Double) = if (peak > 0.0) (.99 / peak).toFloat() else Float.POSITIVE_INFINITY

    private fun auditionGain(snip: Snip): Float {
        val index = snip.samples.indices.maxByOrNull { abs(snip.samples[it]) } ?: return 1f
        if (snip.samples[index] == 0f) return 1f
        return AuditionLevel.level(snip).samples[index] / snip.samples[index]
    }

    private fun gain(snip: Snip, gain: Float) = if (gain == 1f) snip else
        Snip(FloatArray(snip.samples.size) { snip.samples[it] * gain }, channels = 1, sampleRate = Dsp.RATE)

    private fun velocityGroup(case: Case) = case.voice.name + "_" + NOTES.first { it.first == case.macros["TUNE"] }.second

    private fun mergePreviousClips(root: File, clips: List<String>, partial: Boolean): List<String> {
        val previous = File(root, "manifest.json")
        if (!partial || !previous.isFile) return clips
        val merged = linkedMapOf<String, String>()
        for (clip in Json.parse(previous.readText()).obj().getValue("clips").arr()) {
            merged[clip.obj().getValue("id").str()] = Json.write(clip)
        }
        for (clip in clips) merged[Json.parse(clip).obj().getValue("id").str()] = clip
        return merged.values.toList()
    }

    /** Quick/full switches remove only obsolete files recorded in this generator's old manifest. */
    private fun prunePreviousClips(root: File, current: List<String>) {
        val previous = File(root, "manifest.json")
        if (!previous.isFile) return
        val clips = runCatching { Json.parse(previous.readText()).obj().getValue("clips").arr() }
            .getOrDefault(emptyList())
        val retained = current.flatMap { clip ->
            val fields = Json.parse(clip).obj()
            AUDIO_PATH_FIELDS.mapNotNull { fields[it]?.str() }
        }.toSet()
        val allowed = Regex("(raw|matched)/[a-z0-9_]+\\.wav")
        for (clip in clips) for (field in AUDIO_PATH_FIELDS) {
            val path = runCatching { clip.obj()[field]?.str() }.getOrNull() ?: continue
            if (path in retained || !allowed.matches(path)) continue
            val old = File(root, path)
            if (old.canonicalPath.startsWith(root.canonicalPath + File.separator) && old.isFile) {
                check(old.delete()) { "could not remove obsolete generated clip: $path" }
            }
        }
    }

    private fun renderRaw(voice: CorollaVoice, macros: Map<String, Float>, velocity: Float, loop: Boolean, probe: Corolla.Probe): Rendered {
        if (loop) {
            val (buffer, start) = Corolla.loopBuffer(voice, macros, velocity, normalize = false)
            return Rendered(buffer.copyOfRange(start, buffer.size), Keys.seamError(buffer, start))
        }
        return Rendered(Corolla.finish(Corolla.play(voice, macros, velocity, probe).raw, normalize = false))
    }

    private fun quickCases(): List<Case> = CorollaVoice.entries.flatMap { voice ->
        listOf(
            case("Defaults", voice, "default", "${voice.name} default", "Every macro at its voice default."),
            comparisonCase(voice),
            case("Held loops", voice, "held", "${voice.name} held", "Settled loop at the voice defaults.", mapOf("HOLD" to 1f)),
        )
    }

    private fun velocityCases(middleOnly: Boolean = false, voice: CorollaVoice? = null): List<Case> =
        CorollaVoice.entries.filter { voice == null || it == voice }.flatMap { selected ->
            (if (middleOnly) listOf(NOTES[1]) else NOTES).flatMap { (tune, note) ->
                VELOCITIES.map { velocity ->
                    case(VELOCITY_SECTION, selected, "${note.lowercase()}_v${tag(velocity)}",
                        "${selected.name} $note · velocity ${fmt(velocity)}",
                        "TUNE ${fmt(tune)}; all timbral macros at this voice's defaults. The three velocities share the hard event's comparison gain.",
                        mapOf("TUNE" to tune), velocity)
                }
            }
        }

    private fun acceptanceCases(): List<Case> = buildList {
        for (voice in CorollaVoice.entries) {
            add(case("Defaults", voice, "default", "${voice.name} default", "Every macro at its voice default."))
            add(comparisonCase(voice))
            // Both pitch and event strength vary: low/high registration can change collision thresholds.
            addAll(velocityCases(voice = voice))
            for (macro in TIMBRE + "HOLD") for (value in STEPS) {
                add(case("$macro sweep", voice, "${macro.lowercase()}_${tag(value)}",
                    "${voice.name} · $macro ${fmt(value)}", sweepDescription(macro, value), mapOf(macro to value)))
            }
            val high = TIMBRE.associateWith { 1f }
            val edges = listOf(
                Triple("passive", "Powered-off ringdown", mapOf("FIELD" to 0f)),
                Triple("passive_clean", "Powered and contact off", mapOf("FIELD" to 0f, "CONTACT" to 0f)),
                Triple("all_high", "All timbral macros high", high),
                Triple("contact_off", "Contact-off sustain", mapOf("CONTACT" to 0f, "HOLD" to 1f)),
                Triple("held_field_off", "Held FIELD 0", mapOf("FIELD" to 0f, "HOLD" to 1f)),
                Triple("held_passive_clean", "Held FIELD 0 · CONTACT 0", mapOf("FIELD" to 0f, "CONTACT" to 0f, "HOLD" to 1f)),
                Triple("held_all_high", "Held all high", high + ("HOLD" to 1f)),
                Triple("held_slow", "Held slow core", mapOf("FIELD" to 0.25f, "HOLD" to 1f)),
            )
            for ((id, label, macros) in edges) {
                add(case(if (macros["HOLD"] == 1f) "Held loops" else "Edges", voice, id,
                    "${voice.name} · $label", edgeDescription(id), macros))
            }
            for (coupled in listOf(true, false)) {
                add(case("Neighbor response", voice, "neighbors_${if (coupled) "on" else "off"}",
                    "${voice.name} · neighbor links ${if (coupled) "on" else "off"}",
                    "Same finite pull with FIELD 0, CONTACT 0 and chamber disabled in both clips; only reciprocal neighbor links differ.",
                    mapOf("FIELD" to 0f, "CONTACT" to 0f), probe = Corolla.Probe(coupling = coupled, chamber = false)))
            }
            for (preset in CorollaPresets.forVoice(voice)) {
                add(case("Factory presets", voice, "preset_${preset.name.lowercase().replace(' ', '_')}",
                    preset.name, "Factory recipe with an empty effects chain; compare the full instrument before rack effects.", preset.macros))
            }
        }
        val grids = listOf(
            Triple(CorollaVoice.BLOSSOM, "PULL" to "BLOOM", "Opening should follow event energy and alter the evolving tail."),
            Triple(CorollaVoice.CHATTER, "BLOOM" to "CONTACT", "Opening changes contact clearance and where chatter occurs."),
            Triple(CorollaVoice.ORBIT, "FIELD" to "BLOOM", "Powered circulation interacts with magnetic geometry and aperture."),
            Triple(CorollaVoice.CHATTER, "FIELD" to "CONTACT", "Sustained motion should become patterned contact buzz."),
            Triple(CorollaVoice.HUSK, "BLOOM" to "CHAMBER", "Aperture changes the cavity loading and radiation."),
        )
        for ((voice, pair, description) in grids) {
            val (a, b) = pair
            for (va in listOf(0f, 0.5f, 1f)) for (vb in listOf(0f, 0.5f, 1f)) {
                add(case("$a × $b", voice, "grid_${a.lowercase()}${tag(va)}_${b.lowercase()}${tag(vb)}",
                    "${voice.name} · $a ${fmt(va)} · $b ${fmt(vb)}", description, mapOf(a to va, b to vb)))
            }
        }
        for ((voice, tune, label) in listOf(Triple(CorollaVoice.ORBIT, 1f, "High-register ORBIT"), Triple(CorollaVoice.HUSK, 0f, "Low-register HUSK"))) {
            add(case("Register edges", voice, "register_edge", label, "Full-range registration with the voice defaults.", mapOf("TUNE" to tune)))
            add(case("Register edges", voice, "register_all_high", "$label · all high", "All timbral controls at maximum at this registration.",
                TIMBRE.associateWith { 1f } + ("TUNE" to tune)))
            add(case("Held loops", voice, "register_held_all_high", "$label · held all high", "Listen through several wraps for texture changes, pumping and repeating chatter.",
                TIMBRE.associateWith { 1f } + mapOf("TUNE" to tune, "HOLD" to 1f)))
        }
        for (voice in listOf(CorollaVoice.ORBIT, CorollaVoice.CHATTER)) {
            val hz = Corolla.coreHz(Corolla.defaults(voice).getValue("FIELD"), voice)
            add(case("Internal field vs output modulation", voice, "field_internal",
                "${voice.name} · internal powered field",
                "Full object at its defaults: powered excitation enters the resonators and changes the petal/contact/opening states."))
            add(case("Internal field vs output modulation", voice, "field_output_mod",
                "${voice.name} · output-only diagnostic",
                "Identical settings with powered input disabled, then sine gain modulation at %.2f Hz and %.2f depth after rendering. This substitute is a diagnostic comparison; it supplies no energy to the petals. Both exports use the shared audition loudness target.".format(Locale.ROOT, hz, OUTPUT_MOD_DEPTH),
                probe = Corolla.Probe(powered = false), outputMod = true))
        }
    }

    private fun case(section: String, voice: CorollaVoice, suffix: String, label: String, description: String,
                     macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
                     probe: Corolla.Probe = Corolla.Probe(), outputMod: Boolean = false, comparisonSeconds: Float? = null) =
        Case("${voice.name.lowercase()}_$suffix", section, voice, label, description, macros, velocity, probe, outputMod, comparisonSeconds)

    private fun comparisonCase(voice: CorollaVoice) = case("Same note and duration", voice, "comparison",
        "${voice.name} · C4 · 1.25 s", "Same C4, velocity 1 and 1.25-second window; level matching happens after cropping. Listen for partial balance, cavity colour, contact and evolving texture.",
        mapOf("TUNE" to .5f), comparisonSeconds = 1.25f)

    private fun sweepDescription(macro: String, value: Float): String = when (macro) {
        "PULL" -> "Soft rounded release → firm bright displacement. Same velocity; PULL changes the gesture."
        "BLOOM" -> "Compact geometry → open responsive blossom. Listen for a change after the initial pluck."
        "FIELD" -> if (value == 0f) "No powered core; finite energy must ring down." else "Powered magnetic circulation; motion and sidebands arise inside the petals."
        "CONTACT" -> if (value == 0f) "No petal collision; coupling and chamber remain." else "Reduced clearance and compliant collisions; buzz should follow petal motion."
        "CHAMBER" -> "Tight direct body → deeper hollow enclosure, with loading and aperture-dependent decay."
        else -> if (value == 1f) "Settled sustaining loop; use repeat to listen across the wrap." else "Finite note, below the held-loop setting."
    }

    private fun edgeDescription(id: String): String = when (id) {
        "passive", "passive_clean" -> "Finite pull followed by passive decay; listen for the initial petal and its responding structure."
        "held_field_off", "held_passive_clean" -> "FIELD 0 in HOLD uses the engine's documented maintenance mechanism; listen for smooth sustain without recurring attack clicks."
        "contact_off" -> "Powered sustain with collisions removed; the petal and chamber identity should remain."
        "held_slow" -> "Slow circulation must stay active in the supported loop, with no sudden phase or timbre jump."
        "held_all_high" -> "Maximum powered/contact stress; listen across wraps for bounded pitched texture."
        else -> "Extreme macro corner; compare native and matched levels, stability and retained pitched structure."
    }

    private fun metrics(snip: Snip, loop: Boolean, seamError: Double?): Metrics {
        val samples = snip.samples
        var peak = 0.0
        var energy = 0.0
        var sum = 0.0
        for (x in samples) {
            val v = x.toDouble()
            peak = maxOf(peak, abs(v)); energy += v * v; sum += v
        }
        val rms = sqrt(energy / samples.size.coerceAtLeast(1))
        val step = if (loop && samples.size >= 2) abs(samples.first().toDouble() - samples.last()) else null
        val slope = if (loop && samples.size >= 3) abs((samples[1] - samples[0]).toDouble() - (samples.last() - samples[samples.lastIndex - 1])) else null
        // An adjacent wrap step includes the waveform's normal slope; keep it separate from the
        // host metric, which is measured on loopBuffer's real preceding period before extraction.
        val stepRatio = step?.let { it * it / (rms * rms).coerceAtLeast(1e-20) }
        return Metrics(peak, rms, sum / samples.size.coerceAtLeast(1), Loudness.of(snip), step, slope, seamError, stepRatio)
    }

    private fun macroJson(macros: Map<String, Float>) = macros.entries.joinToString(",", "{", "}") { "${q(it.key)}:${it.value}" }
    private fun probeJson(probe: Corolla.Probe) = "{\"coupling\":${probe.coupling},\"chamber\":${probe.chamber}," +
        "\"pull\":${probe.pull},\"powered\":${probe.powered},\"opening\":${probe.opening}}"
    private fun tag(value: Float) = (value * 100).roundToInt().toString().padStart(3, '0')
    private fun fmt(value: Float) = "%.2f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')
    private fun q(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private const val METRIC_NOTES = "Peak, RMS and DC use the complete floating-point buffer before WAV quantization. Loudness is the loudest 200 ms RMS window. rawExportGain and releaseRawExportGain are independent PCM safety attenuations when native peak exceeds .99, preserving existing native loop audio. Velocity comparisons use one gain from velocity 1 per voice/register, with a common peak guard across the three events; velocityCommonGain records this shared gain. Other finite clips are independently loudness matched. Held loop and full four-second held/release previews share matchedGain; the peak guard covers both buffers, so the release tail is never independently boosted. renderMs measures the original clip; releaseRenderMs measures the additional physical release render. Both include conditioning and exclude encoding; wall-clock cost depends on hardware/JIT. seamError is Keys.seamError measured on the engine's conditioned loopBuffer with its real preceding period, before extracting the settled loop, and is invariant under audition/export gain. wrapStep includes the waveform's normal slope; endpointStepRatio is this step squared over whole-loop RMS squared, a diagnostic without a seam acceptance threshold. Native clips bypass engine loudness targeting. Release previews begin with the real pull, hold for heldSeconds, reduce powered work through the engine's note-off transition, then let the object decay."

    private val HELP = """
        CorollaAuditionGenerator [output-directory] [options]
          --held-only       Regenerate the 40 settled loops and full four-second hold/release previews.
          --velocity-only   Regenerate the 54 voice/register/velocity cases using shared group gain.
          --held-only --velocity-only selects their union: 94 cases and 268 WAVs.
          --quick           Smaller defaults/comparison/held roster; with --velocity-only, add C4 velocities.
          --voice=TONGUE    Restrict any selection to one voice.
          --finite-only     Exclude held cases.
          --help            Print this help without rendering.
        Filtered reruns merge changed records into an existing manifest and preserve unrelated assets.
        Example: ./gradlew :synth:generateCorollaAudition --args="/tmp/corolla-update --held-only --velocity-only"
        Smoke:   ./gradlew :synth:generateCorollaAudition --args="/tmp/corolla-smoke --quick --voice=TONGUE --held-only --velocity-only"
    """.trimIndent()

    private fun readme(count: Int, heldCount: Int, quick: Boolean) = """
        # Corolla audition

        Generated by `./gradlew :synth:generateCorollaAudition` (${if (quick) "quick tooling smoke set" else "full acceptance matrix"}).
        $count cases, ${(count + heldCount) * 2} mono 24-bit WAVs at 44.1 kHz. Sonic acceptance awaits owner listening.

        Open `index.html` directly in a browser, or serve this folder with `python3 -m http.server 8000`.
        The page embeds its manifest and has no external assets. Compare raw/native clips to hear event
        level; compare matched clips to judge timbre. Velocity comparisons share the hard event's
        gain within each voice/register, preserving event dynamics. With repeat off, held cases play
        the real attack, four seconds of holding and a physical note-off/decay. With repeat on, they
        play the original settled loop across several wraps. These two matched versions share one
        gain, including a shared peak guard; the release tail is never independently normalized.

        Start with defaults and factory presets, then pitch/velocity, each macro sweep, the five
        interaction grids, neighbor on/off pairs and the edges. The ORBIT/CHATTER field comparison
        contrasts real internal drive with a clearly marked passive-plus-output-LFO diagnostic.
        Check clear root, neighboring response, changing enclosure, buzz tied to contact, and
        continuous powered or maintained sustain. Metrics cannot make these listening decisions.

        Use Keep/Revise/Unsure and the notes field, then Export listening notes. Browser storage is
        local to the browser/origin; export before moving the folder or clearing browsing data.

        `manifest.json` contains every macro, event velocity, duration, peak/RMS/DC, loudness, wrap
        discontinuity/slope and rendering cost. $METRIC_NOTES

        Generated files are build/listening artifacts and should not be committed. A fast smoke set
        can be generated with `./gradlew :synth:generateCorollaAudition -PcorollaQuick`.
        Use `--args="/tmp/corolla-update --held-only --velocity-only"` on that Gradle task to regenerate
        just the 40 held and 54 velocity cases. Add `--quick --voice=TONGUE` for a four-case smoke set.
        Filtered reruns merge records into an existing manifest and keep unrelated audio.
    """.trimIndent() + "\n"

    private val PAGE = """
        <!doctype html>
        <html lang="en">
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Corolla · SnipSnap audition</title>
        <style>
        :root{color-scheme:dark;--bg:#0c0618;--panel:#1a1424;--line:#40306a;--ink:#c8b2f8;--quiet:#a495bf;--cyan:#40e0e8;--pink:#e040c8}
        *{box-sizing:border-box}body{margin:0;background:radial-gradient(ellipse at top,#2a1050,var(--bg) 65%);color:var(--ink);font:15px/1.5 system-ui,sans-serif}
        main{max-width:1180px;margin:auto;padding:28px 20px 120px}h1{font-size:32px;color:white;letter-spacing:.08em;margin:0}h2{font-size:20px;color:white;margin:0 0 6px}p{max-width:850px;color:var(--quiet)}
        .badge{font:12px monospace;letter-spacing:.05em;color:var(--cyan)}.controls{display:flex;flex-wrap:wrap;gap:10px;margin:20px 0}input,select,textarea,button{font:inherit;color:var(--ink);background:#161020;border:1px solid var(--line);border-radius:6px;padding:9px}
        input[type=search]{flex:1;min-width:200px}button{cursor:pointer}button:hover{border-color:var(--cyan)}button.active{background:#40306a;border-color:var(--cyan)}select{min-width:130px}textarea{width:100%;min-height:65px;resize:vertical}
        .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(285px,1fr));gap:14px}.card{background:var(--panel);border:1px solid var(--line);border-radius:9px;padding:15px}.card.playing{border-color:var(--pink)}.card h3{font-size:16px;color:white;margin:7px 0}
        .card p{font-size:13px;margin:8px 0}.row{display:flex;gap:7px;flex-wrap:wrap;margin:10px 0}.row button{font-size:13px}details{font-size:12px;color:var(--quiet);margin:8px 0}summary{cursor:pointer}pre{font:11px/1.5 monospace;white-space:pre-wrap}.section{margin-top:28px}.count{color:var(--quiet);font-size:13px}
        .player{position:fixed;bottom:0;left:0;right:0;background:#120e1af5;border-top:2px solid var(--line);padding:10px 20px;backdrop-filter:blur(12px);display:flex;align-items:center;gap:15px;flex-wrap:wrap}.player audio{height:38px;flex:1;min-width:200px}.now{font-size:12px;min-width:170px;max-width:300px}label{display:inline-flex;align-items:center;gap:7px;font-size:13px}.notice{border-left:3px solid var(--cyan);padding-left:12px}
        @media(max-width:500px){main{padding:20px 12px 180px}.grid{grid-template-columns:1fr}.player{padding:9px 12px;gap:6px}.now{width:100%;max-width:none}.player audio{width:100%}}
        </style>
        <main>
        <div class="badge">SNIPSNAP / ENGINE LISTENING</div><h1>COROLLA</h1>
        <p>One metal petal releases; its neighbors answer; the flower opens, changes its resonance, then folds. Compare native level for event energy and matched level for timbre.</p>
        <p class="notice">Held notes play the real attack, four seconds of holding and a natural release. Enable repeat to hear the original settled loop. Velocity comparisons share one gain per voice/register. Notes save locally until you export them.</p>
        <div class="controls"><select id="voice"><option value="">All voices</option></select><select id="section"><option value="">All sections</option></select><input id="search" type="search" placeholder="Find a macro, voice, note or edge" aria-label="Search clips"><button id="export">Export listening notes</button></div>
        <div id="count" class="count"></div><div id="clips"></div>
        <details><summary>How to read the measurements</summary><p id="metricNotes"></p></details>
        </main>
        <div class="player"><div class="now" id="now">Choose a clip</div><audio id="audio" controls preload="none"></audio><label><input id="repeat" type="checkbox"> Repeat held loops</label><button id="stop">Stop</button></div>
        <script id="manifest" type="application/json">__MANIFEST__</script>
        <script>
        'use strict';
        const manifest=JSON.parse(document.getElementById('manifest').textContent), clips=manifest.clips;
        const voice=document.getElementById('voice'), section=document.getElementById('section'), search=document.getElementById('search'), audio=document.getElementById('audio'), repeat=document.getElementById('repeat');
        const key='snipsnap-corolla-listening-v1';let verdicts={};try{verdicts=JSON.parse(localStorage.getItem(key)||'{}')}catch(e){}
        let current=null,currentMode='matched';
        function save(){try{localStorage.setItem(key,JSON.stringify(verdicts))}catch(e){document.getElementById('count').textContent='Browser storage unavailable; export your notes before closing.'}}
        function el(tag,txt,cls){const n=document.createElement(tag);if(txt!==undefined)n.textContent=txt;if(cls)n.className=cls;return n}
        function options(select,values){for(const val of [...new Set(values)]){const o=el('option',val);o.value=val;select.append(o)}}
        options(voice,clips.map(c=>c.voice));options(section,clips.map(c=>c.section));document.getElementById('metricNotes').textContent=manifest.metricNotes;
        function play(c,mode){current=c;currentMode=mode;const release=c.loop&&!repeat.checked&&c.releaseMatchedPath;audio.pause();audio.src=release?c[mode==='raw'?'releaseRawPath':'releaseMatchedPath']:c[mode+'Path'];audio.loop=Boolean(c.loop&&repeat.checked);document.getElementById('now').textContent=c.label+' · '+mode+(release?' · hold 4 s + release':c.loop?' · settled loop':'');for(const card of document.querySelectorAll('.card'))card.classList.toggle('playing',card.dataset.id===c.id);audio.play().catch(e=>{document.getElementById('now').textContent='Playback unavailable: '+e.message})}
        function display(){
          const query=search.value.toLowerCase(), selected=clips.filter(c=>(!voice.value||c.voice===voice.value)&&(!section.value||c.section===section.value)&&(!query||(c.label+' '+c.description+' '+c.section).toLowerCase().includes(query)));
          document.getElementById('count').textContent=selected.length+' of '+clips.length+' cases · '+(clips.length*2+clips.filter(c=>c.releaseMatchedPath).length*2)+' WAVs · '+(manifest.quick?'quick tooling set':'acceptance matrix');
          const host=document.getElementById('clips');host.replaceChildren();
          for(const name of [...new Set(selected.map(c=>c.section))]){
            const group=el('section',undefined,'section');group.append(el('h2',name));const grid=el('div',undefined,'grid');group.append(grid);host.append(group);
            for(const c of selected.filter(c=>c.section===name)){
              const card=el('article',undefined,'card');card.dataset.id=c.id;if(current&&current.id===c.id)card.classList.add('playing');grid.append(card);
              card.append(el('div',c.voice+' / '+(c.loop?'LOOP':'ONE SHOT'),'badge'),el('h3',c.label),el('p',c.description));
              const row=el('div',undefined,'row');for(const mode of ['raw','matched']){const b=el('button','Play '+mode);b.addEventListener('click',()=>play(c,mode));row.append(b)}card.append(row);
              if(c.matchedLevelMode==='velocity-common-gain')card.append(el('p','Shared gain: '+c.velocityGroup+' · '+c.velocityCommonGain.toFixed(3)));
              if(c.releaseMatchedPath)card.append(el('p','Hold '+c.heldSeconds+' s + release: '+c.releaseSeconds.toFixed(2)+' s total. Settled loop: '+c.seconds.toFixed(2)+' s.'));
              const d=el('details');d.append(el('summary',c.seconds.toFixed(2)+' s · native peak '+c.rawMetrics.peak.toFixed(3)+' · '+c.renderMs.toFixed(0)+' ms render'));
              d.append(el('pre',Object.entries(c.macros).map(([k,v])=>k+' '+v.toFixed(2)).join('  ')+'\nVelocity '+c.velocity+'\nNative RMS '+c.rawMetrics.rms.toExponential(3)+' / DC '+c.rawMetrics.dc.toExponential(3)+'\nMatched RMS '+c.matchedMetrics.rms.toExponential(3)+' / peak '+c.matchedMetrics.peak.toFixed(3)+'\nRaw WAV gain '+c.rawExportGain+(c.loop?'\nKeys seam '+c.rawMetrics.seamError.toExponential(3)+'\nWrap step '+c.rawMetrics.wrapStep.toExponential(3)+' / slope '+c.rawMetrics.wrapSlope.toExponential(3)+'\nEndpoint step ratio '+c.rawMetrics.endpointStepRatio.toExponential(3):'')));card.append(d);
              const votes=el('div',undefined,'row');for(const choice of ['Keep','Revise','Unsure']){const b=el('button',choice);b.classList.toggle('active',verdicts[c.id]?.verdict===choice);b.addEventListener('click',()=>{verdicts[c.id]={...(verdicts[c.id]||{}),verdict:choice};save();for(const v of votes.children)v.classList.toggle('active',v.textContent===choice)});votes.append(b)}card.append(votes);
              const note=el('textarea');note.placeholder='What changed? Pitch, bloom, buzz, loop seam…';note.setAttribute('aria-label','Listening notes for '+c.label);note.value=verdicts[c.id]?.note||'';note.addEventListener('input',()=>{verdicts[c.id]={...(verdicts[c.id]||{}),note:note.value};save()});card.append(note);
            }
          }
        }
        for(const control of [voice,section,search])control.addEventListener('input',display);
        repeat.addEventListener('change',()=>{if(current?.loop)play(current,currentMode)});
        document.getElementById('stop').addEventListener('click',()=>{audio.pause();audio.currentTime=0});
        document.getElementById('export').addEventListener('click',()=>{const blob=new Blob([JSON.stringify({engine:manifest.engine,exportedAt:new Date().toISOString(),quick:manifest.quick,verdicts},null,2)],{type:'application/json'});const url=URL.createObjectURL(blob), a=el('a');a.href=url;a.download='corolla-listening-notes.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)});
        display();
        </script>
        </html>
    """.trimIndent()
}
