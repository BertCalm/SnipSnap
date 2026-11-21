package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.pow
import kotlin.random.Random

/**
 * KERF's voices (docs/superpowers/specs/2026-11-19-kerf-synthetic-chops-design.md, round 4). Each voice is a pool of
 * the bank's fitted hits ([KerfBank]) of one kind, in kit then pad order; HIT picks one. A ghost note files as a
 * SNARE, and the stacked hits (a hat over a snare, a crash over a kick) sit with the percussion.
 */
enum class KerfVoice(val drumClass: DrumClass, val bankClasses: Set<String>) {
    KICK(DrumClass.KICK, setOf("KICK")),
    SNARE(DrumClass.SNARE, setOf("SNARE")),
    GHOST(DrumClass.SNARE, setOf("GHOST")),
    HAT(DrumClass.HAT_CLOSED, setOf("HAT")),
    HAT_OPEN(DrumClass.HAT_OPEN, setOf("HAT_OPEN")),
    PERC(DrumClass.PERC, setOf("PERC", "STACK")),
}

/**
 * KERF as an engine: synthetic chops on the SYNTH picker, the house shape (a voice and its macros) over the bank's
 * fitted numbers. This is the spec's decision A (section 6): the voices point at bundled patch data, and a pad made
 * from a capture keeps its own numbers in the pad recipe ([KerfRecipe]) instead.
 *
 * The characteristic gesture is **the last hit still ringing → a new hit lands on top of it → its own ring and wash →
 * the knife comes down early or late**.
 *
 * Every render goes through [KerfRecipe], so the engine and a SYNTH TWIN pad are one sound path: velocity is
 * [KerfVelocity] at the owner's depth, and a take is [KerfRecipe.take]. The one difference is level: a twin keeps the
 * level its hit had in the loop (a hat a tenth of the kick), and the engine levels every patch with one gain that
 * every velocity and take of it shares, so a soft hit stays softer. The gain puts the loudest of the first [LEVEL_TAKES]
 * takes at full velocity at [PEAK]: a take is a fresh cut, and one can peak half again over take 0 (a ghost note whose
 * knife lands in the kick before it, measured 2026-11-20), so levelling take 0 alone clipped.
 *
 * Six controls per voice:
 * - **HIT**: which fitted hit of the voice's pool.
 * - **TUNE**: ±7 semitones, the body, the resonances and the noise bands together.
 * - **DECAY**: every decay time ×0.35 to ×2.8 (the crack only ever shortens).
 * - **FLOOR**: the wash under the hit, from none to four times the fit (in power).
 * - **BLEED**: the previous hit's tail, from none to twice the fit.
 * - **SLOP**: how far off the knife lands.
 *
 * No HOLD: every hit is a one-shot. Hits over [MAX_SECONDS] are left out of the pools (one snare, 3.3 s), so every
 * render sits under the classifier's 1.5 s loop threshold.
 */
object Kerf {
    /**
     * Engine models. A save keeps the model it was made with.
     * - **2 (round 7, 2026-11-21):** each voice's pool is model 1's, unchanged and in the same order, then the hits the
     *   owner approved in the genre kits of rounds 5 and 6 ([KerfBank.genreKits], as [KerfKits] files them): 231 hits.
     *   A model-1 hit keeps its place, so a preset's hit index lands on the same hit on either model.
     * - **1 (round 4, 2026-11-20):** the bank's v1 numbers through [KerfRender] model 1, velocity at depth 3. Its
     *   pools are the first bank's 63 hits; a model-1 save keeps them.
     */
    const val MODEL_VERSION = 2

    /** The loudest full-velocity take's peak. */
    const val PEAK = 0.89f

    /** The takes the level is set from: the four a kit pad deals. */
    const val LEVEL_TAKES = 4

    private val gains = java.util.concurrent.ConcurrentHashMap<Triple<KerfVoice, Map<String, Float>, Int>, Float>()

    /** The gain every render of these settings shares ([render]). */
    fun gainFor(voice: KerfVoice, macros: Map<String, Float> = emptyMap(), model: Int = MODEL_VERSION): Float {
        val m = settled(voice, macros, model)
        return gains.computeIfAbsent(Triple(voice, m, model)) {
            val r = recipe(voice, m, model)
            val loudest = (0 until LEVEL_TAKES).maxOf { t -> r.take(t, 1.0).samples.maxOf { kotlin.math.abs(it) } }
            PEAK / loudest.coerceAtLeast(1e-6f)
        }
    }

    /** The longest fitted hit a voice may hold. */
    const val MAX_SECONDS = 1.2

    /**
     * The voice's pool on [model]. Model 1: every first-bank pad of its kind, short enough, in kit then pad order.
     * Model 2: model 1's pool, then the voice's hits from the genre kits of rounds 5 and 6 (each as its kit files it,
     * in kit then pad order), so model 1's indices still name the same hits.
     */
    fun pool(voice: KerfVoice, model: Int = MODEL_VERSION): List<KerfPad> {
        require(model in 1..MODEL_VERSION) { "unsupported kerf model $model" }
        return if (model == 1) firstPools.getValue(voice) else secondPools.getValue(voice)
    }

    private val firstPools: Map<KerfVoice, List<KerfPad>> by lazy {
        KerfVoice.entries.associateWith { v ->
            KerfBank.kits.keys.sorted().flatMap { k -> KerfBank.kits.getValue(k).sortedBy { it.pad } }
                .filter { it.drumClass in v.bankClasses && it.patch.seconds <= MAX_SECONDS }
        }
    }

    private val secondPools: Map<KerfVoice, List<KerfPad>> by lazy {
        val genre = KerfKits.Genre.entries.filter { it.second }
            .flatMap { g -> KerfKits.pads(g).map { it.voice to KerfKits.hitOf(g, it) } }
            .filter { it.second.patch.seconds <= MAX_SECONDS }
        KerfVoice.entries.associateWith { v -> firstPools.getValue(v) + genre.filter { it.first == v }.map { it.second } }
    }

    fun macrosFor(@Suppress("UNUSED_PARAMETER") voice: KerfVoice, model: Int = MODEL_VERSION): List<MacroSpec> {
        require(model in 1..MODEL_VERSION) { "unsupported kerf model $model" }
        return listOf(
            MacroSpec("HIT", 0f), MacroSpec("TUNE", .5f), MacroSpec("DECAY", .5f),
            MacroSpec("FLOOR", .5f), MacroSpec("BLEED", .5f), MacroSpec("SLOP", .5f),
        )
    }

    fun defaults(voice: KerfVoice, model: Int = MODEL_VERSION) = macrosFor(voice, model).associate { it.name to it.default }

    fun settled(voice: KerfVoice, macros: Map<String, Float>, model: Int = MODEL_VERSION) = defaults(voice, model).mapValues { (k, d) ->
        macros[k]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: d
    }

    fun scramble(voice: KerfVoice, random: Random, temperature: Float = .35f, near: Patch? = null) =
        Dsp.scrambleNear(settled(voice, near?.macros ?: emptyMap()), temperature, random)

    fun drumClassFor(voice: KerfVoice): DrumClass = voice.drumClass

    /** The HIT macro's place in [voice]'s pool on [model]: the pool split into equal steps. */
    fun hitIndex(voice: KerfVoice, hit: Float, model: Int = MODEL_VERSION): Int {
        val n = pool(voice, model).size
        return (hit.coerceIn(0f, 1f) * n).toInt().coerceAtMost(n - 1)
    }

    /** The HIT value that lands in the middle of pool step [index] on [model]. */
    fun hitFor(voice: KerfVoice, index: Int, model: Int = MODEL_VERSION): Float {
        val n = pool(voice, model).size
        require(index in 0 until n) { "${voice.name} has $n hits, not ${index + 1}" }
        return (index + .5f) / n
    }

    /** The pad recipe the macros describe: the chosen hit, its neighbours, and the render controls. */
    fun recipe(voice: KerfVoice, macros: Map<String, Float> = emptyMap(), model: Int = MODEL_VERSION): KerfRecipe {
        val m = settled(voice, macros, model)
        return recipeFor(pool(voice, model)[hitIndex(voice, m.getValue("HIT"), model)], m)
    }

    /** The pad recipe for any fitted [pad] at settled macros [m] (HIT is not read): [recipe]'s controls, for any bank. */
    fun recipeFor(pad: KerfPad, m: Map<String, Float>): KerfRecipe {
        val floor = 2.0 * m.getValue("FLOOR")
        return KerfRecipe(
            patch = pad.patch, prev = pad.prev, next = pad.next,
            bleed = 2.0 * m.getValue("BLEED"), slop = m.getValue("SLOP").toDouble(), draw = pad.draw,
            controls = KerfRender.Controls(
                floor = floor * floor,
                decay = 2.0.pow((m.getValue("DECAY") - .5) * 3.0),
                tuneSemitones = (m.getValue("TUNE") - .5) * 14.0,
            ),
        )
    }

    /** One hit at [velocity] (0..1), take [take] of a round robin; velocity 1, take 0 is the reference hit. */
    fun render(voice: KerfVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f, take: Int = 0, model: Int = MODEL_VERSION): Snip {
        require(model in 1..MODEL_VERSION) { "unsupported kerf model $model" }
        val v = velocity.takeIf { it.isFinite() }?.coerceIn(.02f, 1f) ?: 1f
        val hit = recipe(voice, macros, model).take(take.coerceAtLeast(0), v.toDouble())
        val gain = gainFor(voice, macros, model)
        return hit.copy(samples = FloatArray(hit.samples.size) { hit.samples[it] * gain })
    }
}
