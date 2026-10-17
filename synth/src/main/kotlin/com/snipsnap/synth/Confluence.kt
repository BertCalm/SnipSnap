package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** Audition controls are explicit ablations, not hidden factory-preset switches. */
data class ConfluenceOptions(
    val spaceBypassed: Boolean = false,
    val coordination: Boolean = true,
    val fixedIdentities: Boolean = true,
    val isolatedPerformer: Int? = null,
    val normalize: Boolean = true,
)

data class ConfluencePerformer(
    val id: Int,
    val section: Int,
    val registerSemitones: Float,
    val formantScale: Float,
    val timingSeconds: Float,
    val pitchCents: Float,
    val durationRatio: Float,
    val vibratoCents: Float,
    val vibratoHz: Float,
    val vibratoPhase: Float,
    val activation: Float,
)

/** Ordered causal events use canonical output frames; REST/PAUSE carry no manufactured breath. */
data class ConfluenceEvent(
    val performerId: Int,
    val section: Int,
    val eventIndex: Int,
    val state: String,
    val startFrame: Int,
    val endFrame: Int,
    val sourceHash: String,
    val pitchSemitones: Float,
    val durationRatio: Float,
    val neighborPullSeconds: Float,
    val formantScale: Float = 1f,
)

data class ConfluenceDiagnostics(
    val rawPeak: Float,
    val rawRms: Float,
    val dc: Float,
    val activePerformers: Int,
    val effectivePerformers: Float,
    val phraseEvents: Int,
    val maximumSimultaneousPerformers: Int,
    val voicedShare: Float,
    val processor: String = "conservative voiced TD-PSOLA; unvoiced chronological playback",
    val holdSupported: Boolean = false,
    val rosterPerformers: Int = activePerformers,
)

data class ConfluenceReport(
    val snip: Snip,
    /** Final room mix before shared loudness leveling. */
    val raw: FloatArray,
    /** Room bypass at the same gain used for [snip]. */
    val dry: Snip,
    val performers: List<ConfluencePerformer>,
    val events: List<ConfluenceEvent>,
    val diagnostics: ConfluenceDiagnostics,
) {
    fun diagnosticsJson(): JsonValue.Obj {
        fun n(x: Number) = JsonValue.Num(x.toDouble())
        val d = diagnostics
        return JsonValue.Obj(linkedMapOf(
            "model" to n(Confluence.MODEL), "analysis" to n(Confluence.ANALYSIS_VERSION),
            "processor" to JsonValue.Str(d.processor), "holdSupported" to JsonValue.Bool(d.holdSupported),
            "rawPeak" to n(d.rawPeak), "rawRms" to n(d.rawRms), "dc" to n(d.dc),
            "activePerformers" to n(d.activePerformers), "effectivePerformers" to n(d.effectivePerformers),
            "rosterPerformers" to n(d.rosterPerformers),
            "phraseEvents" to n(d.phraseEvents), "maximumSimultaneousPerformers" to n(d.maximumSimultaneousPerformers),
            "voicedShare" to n(d.voicedShare),
            "performers" to JsonValue.Arr(performers.map { p -> JsonValue.Obj(linkedMapOf(
                "id" to n(p.id), "section" to n(p.section), "registerSemitones" to n(p.registerSemitones),
                "formantScale" to n(p.formantScale), "timingSeconds" to n(p.timingSeconds),
                "pitchCents" to n(p.pitchCents), "durationRatio" to n(p.durationRatio),
                "vibratoCents" to n(p.vibratoCents), "vibratoHz" to n(p.vibratoHz),
                "vibratoPhase" to n(p.vibratoPhase), "activation" to n(p.activation),
            )) }),
            "events" to JsonValue.Arr(events.map { e -> JsonValue.Obj(linkedMapOf(
                "performerId" to n(e.performerId), "section" to n(e.section), "eventIndex" to n(e.eventIndex),
                "state" to JsonValue.Str(e.state), "startFrame" to n(e.startFrame), "endFrame" to n(e.endFrame),
                "sourceHash" to JsonValue.Str(e.sourceHash), "pitchSemitones" to n(e.pitchSemitones),
                "durationRatio" to n(e.durationRatio), "neighborPullSeconds" to n(e.neighborPullSeconds),
                "formantScale" to n(e.formantScale),
            )) }),
        ))
    }
    fun diagnosticsText(): String = Json.write(diagnosticsJson())
}

/**
 * CONFLUENCE's first source-based proof: immutable resolved audio enters explicitly, a stable
 * eight-slot roster schedules whole regions, and independent pitch-synchronous performers leave
 * as a finite mono sample. No Patch registry, factory presets, source discovery, or HOLD promise.
 *
 * REGISTER is deliberately conservative here: a continuous distribution within +/-2 semitones,
 * not validated octave roles. Sparse section coordination changes future entrance deviations;
 * an external phrase clock prevents accumulated group drift. It is audible only when identities
 * differ and more than one performer participates. No ongoing pitch-consensus model is claimed.
 */
object Confluence {
    const val MODEL = 1
    const val ANALYSIS_VERSION = 1
    const val RATE = 44_100
    const val MAX_PERFORMERS = 8
    val MACRO_NAMES = setOf("CROWD", "REGISTER", "DIVERGENCE", "RESPONSE", "SPACE")
    val DEFAULTS = linkedMapOf("CROWD" to .4f, "REGISTER" to .25f, "DIVERGENCE" to .3f, "RESPONSE" to .25f, "SPACE" to .4f)

    fun crowdForCount(count: Int): Float {
        require(count in 1..MAX_PERFORMERS)
        return (count - 1) / (MAX_PERFORMERS - 1f)
    }

    /** Arrangement samples use the existing phrase/LOOP shelf; this does not assert periodic audio. */
    fun drumClassFor(@Suppress("UNUSED_PARAMETER") recipe: ConfluenceRecipe): DrumClass = DrumClass.LOOP

    fun render(
        recipe: ConfluenceRecipe,
        resolvedSources: Map<String, ConfluenceSource>,
        options: ConfluenceOptions = ConfluenceOptions(),
    ): ConfluenceReport {
        // Freeze caller-owned collection state once, before either analysis or scheduling.
        val regions = recipe.regions.toList()
        val overrides = recipe.macros.toMap()
        recipe.copy(regions = regions, macros = overrides) // Revalidate maps/lists possibly mutated after construction.
        val macros = DEFAULTS + overrides
        val sourceMap = resolvedSources.toMap()
        val crowd = 1 + 7 * macros.getValue("CROWD")
        val whole = floor(crowd + .00001f).toInt()
        val fraction = (crowd - whole).coerceIn(0f, 1f)
        val count = (whole + if (fraction > .00001f) 1 else 0).coerceIn(1, MAX_PERFORMERS)
        require(options.isolatedPerformer == null || options.isolatedPerformer in 0 until count) { "isolated performer is not in the roster" }
        val divergence = macros.getValue("DIVERGENCE")
        val register = macros.getValue("REGISTER")
        val response = macros.getValue("RESPONSE")
        val space = if (options.spaceBypassed) 0f else macros.getValue("SPACE")
        val performers = List(count) { id ->
            identity(recipe.seed, id, register, divergence).copy(activation = if (id < whole) 1f else fraction)
        }
        val analyses = regions.map { region ->
            val source = sourceMap[region.assetHash]
                ?: throw IllegalArgumentException("missing Confluence source ${region.assetHash}; relink the exact asset")
            require(source.hash == region.assetHash) { "resolved source hash does not match the assigned asset" }
            require(region.endFrame <= source.frameCount) { "Confluence source region exceeds the asset" }
            val start = if (recipe.profile == ConfluenceProfile.DRONE) region.sustainStartFrame!! else region.startFrame
            val end = if (recipe.profile == ConfluenceProfile.DRONE) region.sustainEndFrame!! else region.endFrame
            val samples = source.pcm.copyOfRange(start, end)
            require(samples.any { abs(it) > 1e-6f }) { "selected Confluence source region is silent" }
            if (recipe.midi != null && region.mode == ConfluenceSourceMode.PITCHED) {
                require(abs(recipe.midi - region.referenceMidi!!) <= 3f) { "prototype requested transposition must be within three semitones of the confirmed source reference" }
            }
            ConfluenceVoice.analyze(samples, region.referenceMidi)
        }
        val frames = (recipe.seconds * RATE).roundToInt()
        val dry = FloatArray(frames)
        val events = ArrayList<ConfluenceEvent>()
        val phrases = ArrayList<Planned>()
        // SPACE/bypass must compare the same performers and phrase events. Reserve the maximum
        // room release for every render instead of admitting additional phrases only when dry.
        val tail = 1.25
        val maxDuration = analyses.maxOf { it.samples.size.toDouble() / RATE } * 1.035
        val available = recipe.seconds - tail
        require(available > maxDuration + .1) { "render is too short for one complete selected phrase and release; choose a longer duration or an explicit shorter region" }
        val previousDeviation = DoubleArray(count)

        if (recipe.profile == ConfluenceProfile.DRONE) {
            for (p in performers) {
                val sourceIndex = p.id % regions.size
                val duration = analyses[sourceIndex].samples.size.toDouble() / RATE * p.durationRatio
                val offset = p.id.toDouble() / count * duration * (.2 + .65 * response)
                var at = .04 + offset
                var index = 0
                while (at + duration <= available && index < 64) {
                    val current = eventIdentity(recipe, p, index, register, divergence, options)
                    val variation = eventVariation(recipe.seed, p.id, index, divergence)
                    val actualDuration = analyses[sourceIndex].samples.size.toDouble() / RATE * current.durationRatio
                    if (at + actualDuration > available) break
                    phrases += Planned(p.id, index, sourceIndex, (at * RATE).roundToInt(), current, variation, 0f)
                    at += actualDuration + .11 + .24 * response + .08 * abs(variation)
                    index++
                }
            }
        } else {
            val sectionDelay = if (recipe.profile == ConfluenceProfile.RESPONSE && count > 1)
                maxDuration + .50 + .35 * response else maxDuration * .32 * response
            // Two habitual onset deviations can oppose each other by at most .454 seconds.
            // RESPONSE's .50s floor protects completed releases even without coordination.
            val recovery = if (recipe.profile == ConfluenceProfile.RESPONSE && count > 1) .50 else .22
            val cycle = maxDuration + sectionDelay + recovery + .6 * response
            var round = 0
            while (.04 + round * cycle + maxDuration <= available && round < 64) {
                // Every target comes from the previous complete round, never from the in-flight mix order.
                val old = previousDeviation.copyOf()
                for (p in performers) {
                    val sourceIndex = p.id % regions.size
                    val current = eventIdentity(recipe, p, round, register, divergence, options)
                    val variation = eventVariation(recipe.seed, p.id, round, divergence)
                    val habitual = current.timingSeconds + variation * .022
                    val follow = if (options.coordination && count > 1) .7 * (1 - divergence * .65) else 0.0
                    val neighbor = if (round == 0) 0.0 else (old[(p.id + count - 1) % count] + old[0]) * .5
                    val deviation = habitual * (1 - follow) + neighbor * follow
                    previousDeviation[p.id] = deviation
                    val start = .25 + round * cycle + p.section * sectionDelay + deviation
                    val duration = analyses[sourceIndex].samples.size.toDouble() / RATE * current.durationRatio
                    if (start >= 0 && start + duration <= available) {
                        phrases += Planned(p.id, round, sourceIndex, (start * RATE).roundToInt(), current, variation, (deviation - habitual).toFloat())
                    }
                }
                round++
            }
        }
        require(phrases.size <= MAX_PERFORMERS * 64) { "Confluence event budget exceeded" }
        require(phrases.map { it.performerId }.toSet().size == count) { "render is too short for every assigned section to complete its first phrase" }
        val overlap = IntArray(frames / 256 + 2)
        val masterGain = 1 / sqrt(crowd.toDouble()).toFloat()
        // Stable performer/event order is also the accumulation order; no parallel floating-point mix.
        for (plan in phrases.sortedWith(compareBy<Planned> { it.performerId }.thenBy { it.eventIndex })) {
            val p = plan.identity
            val region = regions[plan.sourceIndex]
            val referenceShift = if (region.mode == ConfluenceSourceMode.PITCHED && recipe.midi != null) recipe.midi - region.referenceMidi!! else 0f
            val pitch = if (region.mode == ConfluenceSourceMode.TEXTURE) 0f else
                (referenceShift + p.registerSemitones + p.pitchCents / 100 + plan.variation * .035f).coerceIn(-5f, 5f)
            val transformed = ConfluenceVoice.render(analyses[plan.sourceIndex], region.mode, pitch, p.durationRatio,
                p.formantScale, p.vibratoCents, p.vibratoHz, p.vibratoPhase)
            val end = plan.startFrame + transformed.size
            check(end <= frames) { "scheduler admitted a truncated phrase" }
            val release = minOf(1102, transformed.size / 5)
            if (options.isolatedPerformer == null || options.isolatedPerformer == p.id) {
                for (i in transformed.indices) {
                    var envelope = 1f
                    if (recipe.profile == ConfluenceProfile.DRONE) {
                        if (i < release) envelope *= (.5 - .5 * cos(PI * i / release)).toFloat()
                        if (i > transformed.size - release) envelope *= (.5 - .5 * cos(PI * (transformed.size - i) / release)).toFloat()
                    }
                    dry[plan.startFrame + i] += transformed[i] * envelope * p.activation * masterGain
                }
                for (i in plan.startFrame / 256 until minOf(overlap.size, (end + 255) / 256)) overlap[i]++
            }
            fun event(state: String, from: Int, to: Int) {
                if (to >= from) events += ConfluenceEvent(p.id, p.section, plan.eventIndex, state, from, to, region.assetHash, pitch, p.durationRatio, plan.pull, p.formantScale)
            }
            event("ENTER", plan.startFrame, minOf(end, plan.startFrame + 529))
            event("SING", minOf(end, plan.startFrame + 529), max(plan.startFrame + 529, end - release))
            event("RELEASE", max(plan.startFrame, end - release), end)
            event("PAUSE", end, minOf(frames, end + (RATE * (.11 + .24 * response)).roundToInt()))
        }
        // REST intervals explicitly expose the same identity returning after silence.
        for (p in performers) {
            var end = 0
            for (plan in phrases.filter { it.performerId == p.id }.sortedBy { it.startFrame }) {
                if (plan.startFrame > end) events += ConfluenceEvent(p.id, p.section, plan.eventIndex, "REST", end, plan.startFrame,
                    regions[plan.sourceIndex].assetHash, 0f, p.durationRatio, 0f)
                val phraseEnd = plan.startFrame + (analyses[plan.sourceIndex].samples.size * plan.identity.durationRatio).roundToInt()
                end = minOf(frames, phraseEnd + (RATE * (.11 + .24 * response)).roundToInt())
            }
            if (end < frames) events += ConfluenceEvent(p.id, p.section, 64, "REST", end, frames, regions[p.id % regions.size].assetHash, 0f, p.durationRatio, 0f)
        }
        val raw = if (space == 0f) dry.copyOf() else room(dry, space)
        removeDc(raw)
        removeDc(dry)
        val rawPeak = raw.maxOf { abs(it) }
        val rawRms = sqrt(raw.sumOf { it.toDouble() * it } / raw.size).toFloat()
        val mean = raw.average().toFloat()
        val output = raw.copyOf()
        if (options.normalize) {
            Dsp.levelTo(output, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
            val gain = if (rawPeak > 1e-10f) output.maxOf { abs(it) } / rawPeak else 1f
            for (i in dry.indices) dry[i] *= gain
        }
        check(output.all { it.isFinite() }) { "Confluence produced nonfinite audio" }
        val audibleCount = if (options.isolatedPerformer == null) count else 1
        val audibleShare = if (options.isolatedPerformer == null) crowd else performers[options.isolatedPerformer].activation
        val diag = ConfluenceDiagnostics(rawPeak, rawRms, mean, audibleCount, audibleShare, phrases.size,
            overlap.maxOrNull() ?: 0, analyses.map { it.voicedShare }.average().toFloat(), rosterPerformers = count)
        return ConfluenceReport(Snip(output, 1, RATE), raw, Snip(dry, 1, RATE), performers,
            events.sortedWith(compareBy<ConfluenceEvent> { it.startFrame }.thenBy { it.performerId }.thenBy { it.state }), diag)
    }

    private data class Planned(val performerId: Int, val eventIndex: Int, val sourceIndex: Int, val startFrame: Int,
        val identity: ConfluencePerformer, val variation: Float, val pull: Float)

    private fun identity(seed: Int, id: Int, register: Float, divergence: Float): ConfluencePerformer {
        val r = Random(stream(seed, id, 0))
        fun bipolar() = 2 * r.nextFloat() - 1
        val registerSlots = floatArrayOf(.35f, -.85f, .9f, -.4f, .45f, -1f, 1f, .2f)
        return ConfluencePerformer(id, id % 2, registerSlots[id] * 2 * register * register,
            1 + bipolar() * (.012f + .075f * divergence), bipolar() * (.025f + .18f * divergence),
            bipolar() * (3 + 30 * divergence), 1 + bipolar() * (.008f + .027f * divergence),
            2 + r.nextFloat() * (3 + 10 * divergence), 4.5f + r.nextFloat() * 1.7f,
            r.nextFloat(), 1f)
    }

    private fun eventIdentity(recipe: ConfluenceRecipe, p: ConfluencePerformer, event: Int, register: Float,
        divergence: Float, options: ConfluenceOptions): ConfluencePerformer =
        if (options.fixedIdentities || event == 0) p else identity(stream(recipe.seed, p.id, event), p.id, register, divergence).copy(activation = p.activation)

    private fun eventVariation(seed: Int, id: Int, event: Int, divergence: Float): Float =
        (2 * Random(stream(seed, id + 97, event)).nextFloat() - 1) * divergence

    private fun stream(seed: Int, id: Int, event: Int): Int {
        var x = seed xor (id * -1640531527) xor (event * 1013904223)
        x = (x xor (x ushr 16)) * -2048144789
        x = (x xor (x ushr 13)) * -1028477387
        return x xor (x ushr 16)
    }

    /** One shared bounded mono room. Direct performers remain the dominant cue at small SPACE. */
    private fun room(dry: FloatArray, space: Float): FloatArray {
        val out = FloatArray(dry.size)
        val lengths = intArrayOf((RATE * (.031 + .012 * space)).roundToInt(),
            (RATE * (.047 + .017 * space)).roundToInt(), (RATE * (.073 + .021 * space)).roundToInt())
        val lines = lengths.map { FloatArray(it) }
        val low = FloatArray(3)
        val feedback = .25f + .38f * space
        val absorption = .42f - .28f * space
        for (i in dry.indices) {
            var diffuse = 0f
            for (j in lines.indices) {
                val index = i % lengths[j]
                val delayed = lines[j][index]
                low[j] += absorption * (delayed - low[j])
                lines[j][index] = dry[i] + feedback * low[j]
                diffuse += low[j]
            }
            out[i] = dry[i] * (1 - .42f * space) + diffuse * (.13f * space)
        }
        return out
    }

    private fun removeDc(samples: FloatArray) {
        var previous = 0f; var state = 0f
        for (i in samples.indices) { val x = samples[i]; state = x - previous + .9985f * state; previous = x; samples[i] = state }
        // Finite export only: a 4 ms edge after the last allowed release and room tail.
        Dsp.fadeTail(samples)
    }
}
