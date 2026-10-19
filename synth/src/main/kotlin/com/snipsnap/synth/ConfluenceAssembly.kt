package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Explicit audition roles; these do not infer a source's register, language, or singing technique. */
enum class ConfluenceRole { FOUNDATION, AIRY, ANSWER }

data class ConfluenceRoleSpec(
    val role: ConfluenceRole,
    val region: ConfluenceRegion,
    val performerIds: List<Int>,
    val gain: Float = 1f,
) {
    init {
        require(performerIds.isNotEmpty() && performerIds.all { it in 0 until Confluence.MAX_PERFORMERS }) { "assign one or more fixed performer slots from 0..7 to each role" }
        require(performerIds.distinct().size == performerIds.size) { "a role cannot assign the same performer twice" }
        require(gain.isFinite() && gain > 0f && gain <= 2f) { "role gain must be positive and at most 2" }
        require(region.mode == ConfluenceSourceMode.PITCHED && region.referenceMidi != null) { "assembly roles require a confirmed native source reference" }
        require(role != ConfluenceRole.FOUNDATION || region.sustainStartFrame != null) { "the foundation requires a marked vowel sustain" }
    }
}

/**
 * An explicit finite arrangement, separate from the original single-profile recipe/model.
 * Foundation voices breathe through marked sustains. Complete airy gestures call, and complete
 * phrases answer after their actual releases. Source pitches remain native; no octave is invented.
 */
data class ConfluenceAssemblyRecipe(
    val name: String,
    val roles: List<ConfluenceRoleSpec>,
    val seed: Int = 1,
    val seconds: Float = 24f,
    val space: Float = .25f,
    val divergence: Float = .25f,
    val callStartSeconds: Float = 1.25f,
    val answerDelaySeconds: Float = .45f,
    val exchangeRestSeconds: Float = .75f,
    val breathSeconds: Float = .18f,
    val model: Int = Confluence.MODEL,
) {
    init {
        require(name.isNotBlank()) { "assembly recipe needs a name" }
        require(model == Confluence.MODEL) { "unsupported Confluence assembly source model" }
        require(roles.size == 3 && roles.map { it.role }.toSet() == ConfluenceRole.entries.toSet()) { "assign exactly one foundation, airy and answer role" }
        val slots = roles.flatMap { it.performerIds }
        require(slots.size in 3..Confluence.MAX_PERFORMERS && slots.distinct().size == slots.size) { "assembly roles must use unique slots with at most eight total performers" }
        require(seconds.isFinite() && seconds in 2f..30f) { "assembly duration must be 2..30 seconds" }
        require(space.isFinite() && space in 0f..1f && divergence.isFinite() && divergence in 0f..1f) { "assembly space and divergence must be in 0..1" }
        require(callStartSeconds.isFinite() && callStartSeconds in .25f..10f) { "first call must start between .25 and 10 seconds" }
        require(answerDelaySeconds.isFinite() && answerDelaySeconds in .05f..2f) { "answer delay must be .05..2 seconds" }
        require(exchangeRestSeconds.isFinite() && exchangeRestSeconds in .1f..3f) { "exchange rest must be .1..3 seconds" }
        require(breathSeconds.isFinite() && breathSeconds in .1f..0.4f) { "foundation breath envelope must be .1.. .4 seconds" }
    }

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "engine" to JsonValue.Str("CONFLUENCE_ASSEMBLY_PROTOTYPE"), "schema" to number(ConfluenceAssembly.SCHEMA),
        "model" to number(model), "analysis" to number(Confluence.ANALYSIS_VERSION), "name" to JsonValue.Str(name),
        "seed" to number(seed), "seconds" to number(seconds), "space" to number(space), "divergence" to number(divergence),
        "callStartSeconds" to number(callStartSeconds), "answerDelaySeconds" to number(answerDelaySeconds),
        "exchangeRestSeconds" to number(exchangeRestSeconds), "breathSeconds" to number(breathSeconds),
        "roles" to JsonValue.Arr(roles.map { spec -> JsonValue.Obj(linkedMapOf(
            "role" to JsonValue.Str(spec.role.name), "gain" to number(spec.gain),
            "performerIds" to JsonValue.Arr(spec.performerIds.map(::number)), "region" to regionJson(spec.region),
        )) }),
    ))
    fun toJsonText(): String = Json.write(toJsonValue())

    companion object {
        private fun number(value: Number) = JsonValue.Num(value.toDouble())
        private fun regionJson(r: ConfluenceRegion): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
            "assetHash" to JsonValue.Str(r.assetHash), "startFrame" to number(r.startFrame), "endFrame" to number(r.endFrame),
            "mode" to JsonValue.Str(r.mode.name), "referenceMidi" to (r.referenceMidi?.let(::number) ?: JsonValue.Null),
            "sustainStartFrame" to (r.sustainStartFrame?.let(::number) ?: JsonValue.Null),
            "sustainEndFrame" to (r.sustainEndFrame?.let(::number) ?: JsonValue.Null),
        ))
        fun fromJsonText(text: String): ConfluenceAssemblyRecipe {
            try {
                val o = Json.parse(text).obj()
                require(o.getValue("engine").str() == "CONFLUENCE_ASSEMBLY_PROTOTYPE") { "not a Confluence assembly recipe" }
                require(o.getValue("schema").int() == ConfluenceAssembly.SCHEMA) { "unsupported Confluence assembly schema" }
                require(o.getValue("analysis").int() == Confluence.ANALYSIS_VERSION) { "unsupported Confluence assembly analysis" }
                fun optional(v: JsonValue?) = v?.takeUnless { it == JsonValue.Null }
                return ConfluenceAssemblyRecipe(name = o.getValue("name").str(), roles = o.getValue("roles").arr().map { value ->
                    val s = value.obj(); val r = s.getValue("region").obj()
                    ConfluenceRoleSpec(ConfluenceRole.valueOf(s.getValue("role").str()),
                        ConfluenceRegion(r.getValue("assetHash").str(), r.getValue("startFrame").int(), r.getValue("endFrame").int(),
                            ConfluenceSourceMode.valueOf(r.getValue("mode").str()), optional(r["referenceMidi"])?.num()?.toFloat(),
                            optional(r["sustainStartFrame"])?.int(), optional(r["sustainEndFrame"])?.int()),
                        s.getValue("performerIds").arr().map { it.int() }, s.getValue("gain").num().toFloat())
                }, seed = o.getValue("seed").int(), seconds = o.getValue("seconds").num().toFloat(),
                    space = o.getValue("space").num().toFloat(), divergence = o.getValue("divergence").num().toFloat(),
                    callStartSeconds = o.getValue("callStartSeconds").num().toFloat(), answerDelaySeconds = o.getValue("answerDelaySeconds").num().toFloat(),
                    exchangeRestSeconds = o.getValue("exchangeRestSeconds").num().toFloat(), breathSeconds = o.getValue("breathSeconds").num().toFloat(),
                    model = o.getValue("model").int())
            } catch (e: IllegalArgumentException) { throw JsonException(e.message ?: "invalid Confluence assembly recipe") }
        }
    }
}

data class ConfluenceAssemblyEvent(
    val role: ConfluenceRole,
    val performerId: Int,
    val eventIndex: Int,
    val state: String,
    val startFrame: Int,
    val endFrame: Int,
    val sourceHash: String,
    val sourceStartFrame: Int,
    val sourceEndFrame: Int,
    val pitchSemitones: Float,
    val durationRatio: Float,
    val formantScale: Float,
    val gain: Float,
)

data class ConfluenceAssemblyReport(
    val mix: Snip,
    /** Role and full raw audio share the fixed roster/role gains, before the one mix normalization. */
    val raw: FloatArray,
    val dry: Snip,
    val stems: Map<ConfluenceRole, Snip>,
    val rawStems: Map<ConfluenceRole, Snip>,
    val dryStems: Map<ConfluenceRole, Snip>,
    val normalizationGain: Float,
    val performers: List<ConfluencePerformer>,
    val events: List<ConfluenceAssemblyEvent>,
    val masterGain: Float,
    val maximumSimultaneousPerformers: Int,
    val exchanges: Int,
    val recipe: ConfluenceAssemblyRecipe,
) {
    fun diagnosticsJson(): JsonValue.Obj {
        fun n(v: Number) = JsonValue.Num(v.toDouble())
        fun metric(samples: FloatArray) = JsonValue.Obj(linkedMapOf(
            "rawPeak" to n(samples.maxOf { abs(it) }), "rawRms" to n(sqrt(samples.sumOf { it.toDouble() * it } / samples.size)),
            "dc" to n(samples.average()),
        ))
        return JsonValue.Obj(linkedMapOf(
            "engine" to JsonValue.Str("CONFLUENCE_ASSEMBLY_PROTOTYPE"), "schema" to n(ConfluenceAssembly.SCHEMA),
            "model" to n(Confluence.MODEL), "analysis" to n(Confluence.ANALYSIS_VERSION),
            "processor" to JsonValue.Str("explicit three-role orchestration of existing conservative TD-PSOLA performers"),
            "nativeSourcePitch" to JsonValue.Bool(true), "holdSupported" to JsonValue.Bool(false),
            "masterGain" to n(masterGain), "normalizationGain" to n(normalizationGain),
            "roleGainPolicy" to JsonValue.Str("role gain times one fixed 1/sqrt(total roster); one full-mix normalization gain also applies to every stem"),
            "activePerformers" to n(performers.size), "rosterPerformers" to n(performers.size),
            "maximumSimultaneousPerformers" to n(maximumSimultaneousPerformers), "exchanges" to n(exchanges),
            "phraseEvents" to n(events.count { it.state == "ENTER" }), "metrics" to metric(raw),
            "roles" to JsonValue.Arr(recipe.roles.sortedBy { it.role.ordinal }.map { spec -> JsonValue.Obj(linkedMapOf(
                "role" to JsonValue.Str(spec.role.name), "gain" to n(spec.gain),
                "performerIds" to JsonValue.Arr(spec.performerIds.sorted().map(::n)), "sourceHash" to JsonValue.Str(spec.region.assetHash),
                "referenceMidi" to n(spec.region.referenceMidi!!), "metrics" to metric(rawStems.getValue(spec.role).samples),
            )) }),
            "performers" to JsonValue.Arr(performers.map { p -> JsonValue.Obj(linkedMapOf(
                "id" to n(p.id), "section" to n(p.section), "registerSemitones" to n(p.registerSemitones),
                "formantScale" to n(p.formantScale), "timingSeconds" to n(p.timingSeconds), "pitchCents" to n(p.pitchCents),
                "durationRatio" to n(p.durationRatio), "vibratoCents" to n(p.vibratoCents), "vibratoHz" to n(p.vibratoHz),
                "vibratoPhase" to n(p.vibratoPhase), "activation" to n(p.activation),
            )) }),
            "events" to JsonValue.Arr(events.map { e -> JsonValue.Obj(linkedMapOf(
                "role" to JsonValue.Str(e.role.name), "performerId" to n(e.performerId), "section" to n(e.role.ordinal),
                "eventIndex" to n(e.eventIndex), "state" to JsonValue.Str(e.state), "startFrame" to n(e.startFrame), "endFrame" to n(e.endFrame),
                "sourceHash" to JsonValue.Str(e.sourceHash), "sourceStartFrame" to n(e.sourceStartFrame), "sourceEndFrame" to n(e.sourceEndFrame),
                "pitchSemitones" to n(e.pitchSemitones), "durationRatio" to n(e.durationRatio), "formantScale" to n(e.formantScale), "gain" to n(e.gain),
            )) }),
        ))
    }
    fun diagnosticsText(): String = Json.write(diagnosticsJson())
}

/**
 * Small, explicit audition orchestration. It does not add ASSEMBLY to the old profile enum or
 * change old saved renders. The shared room and DC stages are linear: applying the identical
 * transfer to the role contributions permits stems whose sum is the full gathering. Every stem
 * retains the full arrangement duration and all identities/events come from this single render.
 */
object ConfluenceAssembly {
    const val SCHEMA = 1
    private const val ROOM_TAIL_SECONDS = 1.25f

    fun render(recipe: ConfluenceAssemblyRecipe, resolvedSources: Map<String, ConfluenceSource>, normalize: Boolean = true): ConfluenceAssemblyReport {
        val frozen = recipe.copy(roles = recipe.roles.map { it.copy(performerIds = it.performerIds.toList()) })
        val roles = frozen.roles.sortedBy { it.role.ordinal }
        val sources = resolvedSources.toMap()
        val frames = (frozen.seconds * Confluence.RATE).roundToInt()
        val admissionEnd = frames - (ROOM_TAIL_SECONDS * Confluence.RATE).roundToInt()
        val performers = roles.flatMap { spec -> spec.performerIds.sorted().map { id ->
            Confluence.identity(frozen.seed, id, 0f, frozen.divergence).copy(section = spec.role.ordinal)
        } }.sortedBy { it.id }
        val identityById = performers.associateBy { it.id }
        val material = roles.associate { spec ->
            val source = sources[spec.region.assetHash] ?: throw IllegalArgumentException("missing Confluence assembly source ${spec.region.assetHash}; relink the exact asset")
            require(source.hash == spec.region.assetHash) { "assembly source hash does not match its assignment" }
            require(spec.region.endFrame <= source.frameCount) { "assembly region exceeds its source asset" }
            val from = if (spec.role == ConfluenceRole.FOUNDATION) spec.region.sustainStartFrame!! else spec.region.startFrame
            val end = if (spec.role == ConfluenceRole.FOUNDATION) spec.region.sustainEndFrame!! else spec.region.endFrame
            val samples = source.pcm.copyOfRange(from, end)
            require(samples.any { abs(it) > 1e-6f }) { "assembly selected source region is silent" }
            spec.role to Material(spec, from, end, ConfluenceVoice.analyze(samples, spec.region.referenceMidi))
        }
        val plans = ArrayList<Plan>()
        val foundation = material.getValue(ConfluenceRole.FOUNDATION)
        for ((slot, id) in foundation.spec.performerIds.sorted().withIndex()) {
            val p = identityById.getValue(id)
            val duration = (foundation.analysis.samples.size * p.durationRatio).roundToInt()
            val pause = (frozen.breathSeconds * Confluence.RATE).roundToInt()
            val cycle = duration + pause
            var start = (Confluence.RATE * .04).roundToInt() + (cycle.toDouble() * slot / foundation.spec.performerIds.size).roundToInt()
            var index = 0
            while (start + duration <= admissionEnd && index < 64) {
                plans += Plan(foundation, p, index, start, duration, pause)
                start += cycle
                index++
            }
        }
        fun section(role: ConfluenceRole, start: Int, index: Int): List<Plan> {
            val m = material.getValue(role)
            return m.spec.performerIds.sorted().map { id ->
                val p = identityById.getValue(id)
                // Fixed bounded habitual entrance tendencies; the previous section's latest release is the anchor.
                val deviation = (p.timingSeconds * Confluence.RATE).roundToInt()
                Plan(m, p, index, start + deviation, (m.analysis.samples.size * p.durationRatio).roundToInt(),
                    (frozen.exchangeRestSeconds * Confluence.RATE).roundToInt())
            }
        }
        var callStart = (frozen.callStartSeconds * Confluence.RATE).roundToInt()
        var exchanges = 0
        while (exchanges < 16) {
            val call = section(ConfluenceRole.AIRY, callStart, exchanges)
            val callEnd = call.maxOf { it.end }
            // Add headroom for the earliest habitual deviation, so the configured gap is a minimum.
            val earliestAnswer = material.getValue(ConfluenceRole.ANSWER).spec.performerIds.minOf { identityById.getValue(it).timingSeconds }
            val answerStart = callEnd + (frozen.answerDelaySeconds * Confluence.RATE).roundToInt() - (earliestAnswer * Confluence.RATE).roundToInt()
            val answer = section(ConfluenceRole.ANSWER, answerStart, exchanges)
            val answerEnd = answer.maxOf { it.end }
            if (call.any { it.start < 0 } || answerEnd > admissionEnd) break
            plans += call; plans += answer
            val earliestCall = material.getValue(ConfluenceRole.AIRY).spec.performerIds.minOf { identityById.getValue(it).timingSeconds }
            callStart = answerEnd + (frozen.exchangeRestSeconds * Confluence.RATE).roundToInt() - (earliestCall * Confluence.RATE).roundToInt()
            exchanges++
        }
        require(exchanges > 0) { "assembly render is too short for one complete upper call, answer phrase and room tail" }
        require(plans.map { it.identity.id }.toSet() == identityById.keys) { "assembly duration cannot fit the assigned foundation roster" }
        require(plans.size <= Confluence.MAX_PERFORMERS * 64) { "assembly event budget exceeded" }
        val masterGain = 1f / sqrt(performers.size.toFloat())
        val dryBuffers = ConfluenceRole.entries.associateWith { FloatArray(frames) }
        val events = ArrayList<ConfluenceAssemblyEvent>()
        for (plan in plans.sortedWith(compareBy<Plan> { it.identity.id }.thenBy { it.index })) {
            val p = plan.identity; val m = plan.material
            val voice = ConfluenceVoice.render(m.analysis, m.spec.region.mode, p.pitchCents / 100, p.durationRatio,
                p.formantScale, p.vibratoCents, p.vibratoHz, p.vibratoPhase)
            check(voice.size == plan.duration && plan.end <= admissionEnd) { "assembly admitted a truncated source region" }
            val smooth = if (m.spec.role == ConfluenceRole.FOUNDATION)
                minOf((frozen.breathSeconds * Confluence.RATE).roundToInt(), voice.size / 3) else minOf(1102, voice.size / 5)
            val gain = masterGain * m.spec.gain
            val destination = dryBuffers.getValue(m.spec.role)
            for (i in voice.indices) {
                var envelope = 1f
                if (m.spec.role == ConfluenceRole.FOUNDATION) {
                    if (i < smooth) envelope *= (.5 - .5 * cos(PI * i / smooth)).toFloat()
                    if (i >= voice.size - smooth) envelope *= (.5 - .5 * cos(PI * (voice.size - 1 - i) / smooth)).toFloat()
                }
                destination[plan.start + i] += voice[i] * envelope * gain
            }
            fun emit(state: String, from: Int, end: Int) = events.add(ConfluenceAssemblyEvent(m.spec.role, p.id, plan.index,
                state, from, end, m.spec.region.assetHash, m.sourceStart, m.sourceEnd, p.pitchCents / 100, p.durationRatio, p.formantScale, gain))
            val enter = if (m.spec.role == ConfluenceRole.FOUNDATION) smooth else minOf(529, voice.size / 3)
            emit("ENTER", plan.start, plan.start + enter)
            emit("SING", plan.start + enter, plan.end - smooth)
            emit("RELEASE", plan.end - smooth, plan.end)
            val next = plans.filter { it.identity.id == p.id && it.start > plan.start }.minOfOrNull { it.start } ?: frames
            emit("PAUSE", plan.end, minOf(next, frames, plan.end + plan.pause))
        }
        for (p in performers) {
            val m = material.getValue(ConfluenceRole.entries[p.section])
            val occupied = events.filter { it.performerId == p.id }.sortedBy { it.startFrame }
            var start = 0
            for (e in occupied) {
                if (e.startFrame > start) events += ConfluenceAssemblyEvent(m.spec.role, p.id, e.eventIndex, "REST", start, e.startFrame,
                    m.spec.region.assetHash, m.sourceStart, m.sourceEnd, p.pitchCents / 100, p.durationRatio, p.formantScale, masterGain * m.spec.gain)
                start = e.endFrame
            }
            if (start < frames) events += ConfluenceAssemblyEvent(m.spec.role, p.id, 64, "REST", start, frames,
                m.spec.region.assetHash, m.sourceStart, m.sourceEnd, p.pitchCents / 100, p.durationRatio, p.formantScale, masterGain * m.spec.gain)
        }
        val rawBuffers = dryBuffers.mapValues { (_, samples) ->
            (if (frozen.space == 0f) samples.copyOf() else Confluence.room(samples, frozen.space)).also(Confluence::removeDc)
        }
        for (samples in dryBuffers.values) Confluence.removeDc(samples)
        fun sum(buffers: Map<ConfluenceRole, FloatArray>): FloatArray = FloatArray(frames) { i ->
            var value = 0f; for (role in ConfluenceRole.entries) value += buffers.getValue(role)[i]; value
        }
        val raw = sum(rawBuffers)
        val fullDry = sum(dryBuffers)
        val mix = raw.copyOf()
        val peak = raw.maxOf { abs(it) }
        if (normalize) Dsp.levelTo(mix, Confluence.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        val normalizationGain = if (normalize && peak > 1e-10f) mix.maxOf { abs(it) } / peak else 1f
        fun scaled(samples: FloatArray): Snip = Snip(FloatArray(samples.size) { samples[it] * normalizationGain }, 1, Confluence.RATE)
        check(mix.all { it.isFinite() }) { "assembly produced nonfinite audio" }
        val crossings = plans.flatMap { listOf(it.start to 1, it.end to -1) }.sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        var active = 0; var maximum = 0
        for ((_, delta) in crossings) { active += delta; maximum = maxOf(maximum, active) }
        return ConfluenceAssemblyReport(Snip(mix, 1, Confluence.RATE), raw, scaled(fullDry),
            rawBuffers.mapValues { scaled(it.value) }, rawBuffers.mapValues { Snip(it.value, 1, Confluence.RATE) },
            dryBuffers.mapValues { scaled(it.value) }, normalizationGain, performers,
            events.sortedWith(compareBy<ConfluenceAssemblyEvent> { it.startFrame }.thenBy { it.performerId }.thenBy { it.state }),
            masterGain, maximum, exchanges, frozen)
    }

    private data class Material(val spec: ConfluenceRoleSpec, val sourceStart: Int, val sourceEnd: Int, val analysis: ConfluenceVoice.Analysis)
    private data class Plan(val material: Material, val identity: ConfluencePerformer, val index: Int, val start: Int, val duration: Int, val pause: Int) {
        val end: Int get() = start + duration
    }
}
