package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.cos

/**
 * Opt-in four-take cells for the existing selected PLUCK sources. This has its own
 * source identity, serialization and PCM cache; the saved v1 selected bank stays
 * byte-identical. A renderer binding identifies code, not listening/runtime approval.
 *
 * Held cells finish a real preceding/current native pair together and adapt only
 * its onset prefix. Take variation comes from native excitation, never output gain.
 */
object MurmurRoundRobinInstrumentSource {
    const val KIND = "MURMUR_ROUND_ROBIN_INSTRUMENT_CELL"
    const val VERSION = 1
    const val HELD_ONSET_FRAMES = 220
    const val TOUCH_POLICY = "ABSOLUTE_035_065_100"
    const val DEFAULT_RELEASE_SECONDS = .35f
    const val MIN_RELEASE_SECONDS = .01f
    const val MAX_RELEASE_SECONDS = 1.5f
    private val touches = MurmurSelectedInstrumentSource.nativeTouches()

    enum class OutputPolicy { FACTORY_SINGLE_FINITE_V1, HOUSE_ADJACENT_CONTEXT_V1 }

    /**
     * How takes 1..3 differ from take 0. [BREATH_V1] varies the quiet breath seed; the owner
     * heard its C2 repeats as one strike. [CONTRAST_V2] moves two FM modulator starting phases
     * and the transient index/contact length, finite sources only. Both keep take 0 exact.
     * V1 stays the default, so every saved V1 cell keeps its serialization byte for byte.
     */
    enum class RoundRobinPolicy(val json: String) {
        BREATH_V1(MurmurRoundRobinRecipe.POLICY),
        CONTRAST_V2(MurmurRoundRobinContrastRecipe.POLICY);

        companion object {
            fun fromJson(value: String): RoundRobinPolicy? = entries.firstOrNull { it.json == value }
        }
    }

    fun nativeTouches(): List<Float> = touches.toList()

    /**
     * Strings freeze complete deep source/cell snapshots. This recipe executes
     * its own source/output contract; it is deliberately not a PadRecipe.
     */
    data class CellRecipe internal constructor(
        val selectionId: String,
        val sourcePatchJson: String,
        val cellPatchJson: String,
        val touchIndex: Int,
        val take: Int,
        val releaseSeconds: Float,
        val outputPolicy: OutputPolicy,
        val onsetFrames: Int,
        val policy: RoundRobinPolicy = RoundRobinPolicy.BREATH_V1,
    ) {
        init { validateCell(this) }

        val midi: Int get() = cellPatch().midi
        fun sourcePatch(): MurmurPatch = canonicalPatch(Json.parse(sourcePatchJson))
        fun cellPatch(): MurmurPatch = canonicalPatch(Json.parse(cellPatchJson))

        fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
            "kind" to JsonValue.Str(KIND),
            "version" to JsonValue.Num(VERSION.toDouble()),
            "selectionId" to JsonValue.Str(selectionId),
            "sourcePatch" to Json.parse(sourcePatchJson),
            "cellPatch" to Json.parse(cellPatchJson),
            "touchPolicy" to JsonValue.Str(TOUCH_POLICY),
            "touchIndex" to JsonValue.Num(touchIndex.toDouble()),
            "take" to JsonValue.Num(take.toDouble()),
            "roundRobinPolicy" to JsonValue.Str(policy.json),
            "releaseSeconds" to JsonValue.Num(releaseSeconds.toDouble()),
            "outputPolicy" to JsonValue.Str(outputPolicy.name),
            "onsetFrames" to JsonValue.Num(onsetFrames.toDouble()),
            "rendererBinding" to rendererBinding(policy),
        ))

        fun toJsonText(): String = Json.write(toJsonValue())

        companion object {
            fun fromJsonValue(value: JsonValue): CellRecipe {
                val obj = value.obj()
                val expected = setOf("kind", "version", "selectionId", "sourcePatch", "cellPatch", "touchPolicy",
                    "touchIndex", "take", "roundRobinPolicy", "releaseSeconds", "outputPolicy", "onsetFrames", "rendererBinding")
                if (obj.keys != expected) throw JsonException("MURMUR instrument cell has missing or unknown fields")
                if (obj.getValue("kind").str() != KIND || obj.getValue("version").int() != VERSION)
                    throw JsonException("unsupported MURMUR instrument cell kind/version")
                if (obj.getValue("touchPolicy").str() != TOUCH_POLICY)
                    throw JsonException("unsupported MURMUR native touch policy")
                val policy = RoundRobinPolicy.fromJson(obj.getValue("roundRobinPolicy").str())
                    ?: throw JsonException("unsupported MURMUR round-robin policy")
                if (obj.getValue("rendererBinding") != rendererBinding(policy))
                    throw JsonException("MURMUR instrument renderer binding differs")
                val output = OutputPolicy.entries.firstOrNull { it.name == obj.getValue("outputPolicy").str() }
                    ?: throw JsonException("unknown MURMUR instrument output policy")
                try {
                    val recipe = CellRecipe(obj.getValue("selectionId").str(),
                        canonicalPatch(obj.getValue("sourcePatch")).toJsonText(),
                        canonicalPatch(obj.getValue("cellPatch")).toJsonText(),
                        obj.getValue("touchIndex").int(), obj.getValue("take").int(), obj.getValue("releaseSeconds").num().toFloat(),
                        output, obj.getValue("onsetFrames").int(), policy)
                    require(recipe.toJsonValue() == value) { "MURMUR RR cell contains lossy or noncanonical values" }
                    return recipe
                } catch (failure: IllegalArgumentException) {
                    throw JsonException(failure.message ?: "invalid MURMUR instrument cell")
                }
            }

            fun fromJsonText(text: String): CellRecipe = fromJsonValue(Json.parse(text))
        }
    }

    fun cellRecipe(patch: MurmurPatch, midi: Int, touchIndex: Int, take: Int, releaseSeconds: Float = DEFAULT_RELEASE_SECONDS,
                   policy: RoundRobinPolicy = RoundRobinPolicy.BREATH_V1): CellRecipe {
        val source = canonicalPatch(patch.toJsonValue())
        val eligible = requireNotNull(MurmurSelectedInstrumentSource.eligibility(source)) { "Choose a qualified kept MURMUR source setting" }
        require(midi in eligible.supportedMidi) { "MURMUR requested cell note is outside the selected range" }
        require(touchIndex in touches.indices) { "MURMUR native touch index must be 0..2" }
        val cell = source.copy(midi = midi, velocity = touches[touchIndex])
        return CellRecipe(eligible.selectionId, source.toJsonText(), cell.toJsonText(), touchIndex, take, releaseSeconds,
            if (eligible.held) OutputPolicy.HOUSE_ADJACENT_CONTEXT_V1 else OutputPolicy.FACTORY_SINGLE_FINITE_V1,
            if (eligible.held) HELD_ONSET_FRAMES else 0, policy)
    }

    /** A capture value, not a signature of trusted native authorship. Production builders render it here. */
    data class Capture(
        val recipe: CellRecipe,
        val snip: Snip,
        val loopStartFrame: Int,
        val loopEndFrameExclusive: Int,
        val rawSampleSha256: String,
        val rawPreviousSha256: String,
        val finishedFloatSha256: String,
        val seamError: Double,
        val rootHz: Double,
    ) {
        init {
            require(snip.channels == 1 && snip.sampleRate == Dsp.RATE && snip.frameCount > 0)
            require(loopEndFrameExclusive == snip.frameCount && loopStartFrame in 0 until loopEndFrameExclusive)
            require(rootHz.isFinite() && rootHz > 0.0 && seamError.isFinite() && seamError >= 0.0)
            val hash = Regex("[0-9a-f]{64}")
            require(listOf(rawSampleSha256, rawPreviousSha256, finishedFloatSha256).all { hash.matches(it) })
        }
    }

    /** A cache hit returns independent PCM; no caller can mutate a later cell or reference. */
    fun renderCell(recipe: CellRecipe): Capture {
        validateCell(recipe)
        stopIfUnwanted()
        cachedCapture(recipe)?.let { stopIfUnwanted(); return it }
        val capture = renderUncached(recipe)
        stopIfUnwanted()
        retainCapture(capture)
        stopIfUnwanted()
        return capture
    }

    /** One storage policy for native captures and synthetic ownership/budget checks. */
    internal fun retainCapture(capture: Capture) {
        stopIfUnwanted()
        validateCell(capture.recipe)
        val key = capture.recipe.toJsonText()
        synchronized(cache) {
            val bytes = capture.snip.samples.size.toLong() * Float.SIZE_BYTES
            if (bytes <= MAX_CACHE_PCM_BYTES) {
                cache.remove(key)?.let { cacheBytes -= it.snip.samples.size.toLong() * Float.SIZE_BYTES }
                cache[key] = ownedCopy(capture)
                cacheBytes += bytes
                while (cache.size > MAX_CACHE_ENTRIES || cacheBytes > MAX_CACHE_PCM_BYTES) {
                    val oldest = cache.entries.iterator().next()
                    cacheBytes -= oldest.value.snip.samples.size.toLong() * Float.SIZE_BYTES
                    cache.remove(oldest.key)
                }
            }
        }
    }

    fun replay(value: JsonValue): Capture = renderCell(CellRecipe.fromJsonValue(value))

    private fun renderUncached(recipe: CellRecipe): Capture {
        val patch = recipe.cellPatch()
        val proof = when (recipe.policy) {
            RoundRobinPolicy.BREATH_V1 -> Murmur.probeRoundRobin(patch, recipe.take)
            RoundRobinPolicy.CONTRAST_V2 -> Murmur.probeRoundRobinContrast(patch, recipe.take)
        }
        require(proof.samples.isNotEmpty() && proof.samples.all { it.isFinite() } &&
            proof.previousCycle.all { it.isFinite() }) { "MURMUR native context is empty or nonfinite" }
        val sampleHash = floatSha256(proof.samples)
        val previousHash = floatSha256(proof.previousCycle)
        val held = recipe.outputPolicy == OutputPolicy.HOUSE_ADJACENT_CONTEXT_V1
        val start: Int
        val finished: Snip
        if (held) {
            require(proof.previousCycle.size == proof.samples.size && proof.samples.size > 512) {
                "MURMUR held instrument needs a real complete preceding native cycle"
            }
            require(proof.seamError.isFinite() && proof.seamError < Keys.MAX_SEAM_ERROR) {
                "MURMUR native adjacent periods do not close"
            }
            start = proof.previousCycle.size
            // The shared finisher sees one real adjacent pair. Neither period is
            // independently normalized or fabricated from a repeated current cycle.
            finished = Murmur.finishHouseSnapshot(proof.previousCycle + proof.samples, patch.velocity)
            val samples = finished.samples
            require(recipe.onsetFrames < start - 256)
            for (i in 0 until recipe.onsetFrames) {
                val gain = ((1.0 - cos(PI * i / (recipe.onsetFrames - 1))) * .5).toFloat()
                samples[i] *= gain
            }
            samples[0] = 0f
        } else {
            require(proof.previousCycle.isEmpty()) { "MURMUR finite capture unexpectedly contains held context" }
            start = 0
            finished = Murmur.finishHouseSnapshot(proof.samples, patch.velocity)
        }
        stopIfUnwanted()
        require(finished.samples.all { it.isFinite() }) { "MURMUR finished context is nonfinite" }
        val seam = if (held) Keys.seamError(finished.samples, start) else 0.0
        require(seam.isFinite() && seam < Keys.MAX_SEAM_ERROR) { "MURMUR finished context seam failed" }
        return Capture(recipe, finished, start, finished.frameCount, sampleHash, previousHash,
            floatSha256(finished.samples), seam, proof.rootHz)
    }

    private fun validateCell(recipe: CellRecipe) {
        val source = canonicalPatch(Json.parse(recipe.sourcePatchJson))
        val eligible = requireNotNull(MurmurSelectedInstrumentSource.eligibility(source)) { "MURMUR cell source is not a qualified kept shape" }
        require(recipe.selectionId == eligible.selectionId) { "MURMUR selected source identity differs" }
        require(recipe.touchIndex in touches.indices) { "MURMUR native touch index must be 0..2" }
        require(recipe.releaseSeconds.isFinite() && recipe.releaseSeconds in MIN_RELEASE_SECONDS..MAX_RELEASE_SECONDS) { "invalid MURMUR sampler release" }
        val cell = canonicalPatch(Json.parse(recipe.cellPatchJson))
        // Exact native policy and complete selected source eligibility; V2 also refuses held sources.
        when (recipe.policy) {
            RoundRobinPolicy.BREATH_V1 -> MurmurRoundRobinRecipe(cell, recipe.take)
            RoundRobinPolicy.CONTRAST_V2 -> MurmurRoundRobinContrastRecipe(cell, recipe.take)
        }
        require(cell.midi in eligible.supportedMidi) { "MURMUR cell note is outside the selected range" }
        require(cell == source.copy(midi = cell.midi, velocity = touches[recipe.touchIndex])) {
            "MURMUR adapted cell changed its source shape or absolute native touch"
        }
        require(recipe.outputPolicy == if (eligible.held) OutputPolicy.HOUSE_ADJACENT_CONTEXT_V1 else OutputPolicy.FACTORY_SINGLE_FINITE_V1) {
            "MURMUR output policy changes the selected articulation"
        }
        require(recipe.onsetFrames == if (eligible.held) HELD_ONSET_FRAMES else 0) { "MURMUR onset policy differs" }
    }

    private fun canonicalPatch(value: JsonValue): MurmurPatch {
        val patch = MurmurPatch.fromJsonValue(value) as MurmurPatch
        require(patch.toJsonValue() == value) { "MURMUR instrument requires a complete canonical patch without unknown fields" }
        return patch
    }

    /**
     * Hashes the new adapter and native dependencies; external receipts qualify actual execution.
     * A V2 cell names its own policy in the binding, so a V1 cell can never satisfy a V2 request.
     */
    fun rendererBinding(policy: RoundRobinPolicy = RoundRobinPolicy.BREATH_V1): JsonValue {
        val binding = Json.parse(MurmurRoundRobinRendererIdentity.JSON)
        require(binding.obj()["sealed"]?.bool() == true) { "MURMUR selected renderer identity has not been sealed" }
        if (policy == RoundRobinPolicy.BREATH_V1) return binding
        return JsonValue.Obj(LinkedHashMap(binding.obj()).apply { put("roundRobinPolicy", JsonValue.Str(policy.json)) })
    }

    fun floatSha256(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putFloat(sample)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }

    fun clearCache() = synchronized(cache) { cache.clear(); cacheBytes = 0L }
    internal fun cachedCapture(recipe: CellRecipe): Capture? {
        stopIfUnwanted()
        val key = recipe.toJsonText()
        val result = synchronized(cache) { cache[key]?.let(::ownedCopy) }
        stopIfUnwanted()
        return result
    }
    internal fun cachedPcmBytes(): Long = synchronized(cache) { cacheBytes }
    internal fun cachedEntries(): Int = synchronized(cache) { cache.size }
    private fun ownedCopy(capture: Capture): Capture = capture.copy(snip = capture.snip.copy(samples = capture.snip.samples.copyOf()))
    private fun stopIfUnwanted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("MURMUR RR instrument capture cancelled")
    }

    private const val MAX_CACHE_ENTRIES = 16
    private const val MAX_CACHE_PCM_BYTES = 16L * 1024 * 1024
    private val cache = LinkedHashMap<String, Capture>(16, .75f, true)
    private var cacheBytes = 0L
}
