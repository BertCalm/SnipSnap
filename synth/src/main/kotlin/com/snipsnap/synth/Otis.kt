package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.otis.*
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The accepted organ, steam pipe, blues harmonica and Squeeze as finite, editable pad recipes.
 * One score feeds one player budget or organ cabinet, so chords retain their shared response.
 * LENGTH changes the score's finite timing. It does not promise a periodic HOLD or keyboard bank.
 * Source output preserves the audition conditioning; production output adds the house level only.
 * Inspired by the XOtis musical brief in BertCalm/XO_OX-XOmnibus; these finite sources are
 * original SnipSnap implementations. Provenance and the listening ledger: docs/OTIS_PROTOTYPE.md.
 */
object Otis {
    const val MODEL_VERSION = 1
    const val MIDI_MIN = 48
    const val MIDI_MAX = 72
    const val DEFAULT_SEED = 20261014
    private const val RATE = Dsp.RATE * Dsp.OVERSAMPLE

    fun defaultMidi(voice: OtisVoice): Int = if (voice == OtisVoice.HARP) 67 else 60
    fun macrosFor(voice: OtisVoice): List<MacroSpec> = buildList {
        add(MacroSpec("TUNE", .5f, neutral = .5f))
        when (voice) {
            OtisVoice.ORGAN -> addAll(listOf(MacroSpec("BARS", .5f, neutral = .5f),
                MacroSpec("CLICK", .45f), MacroSpec("DRIVE", .20f), MacroSpec("ROTARY", 0f)))
            OtisVoice.STEAM -> addAll(listOf(MacroSpec("PRESSURE", .60f), MacroSpec("BREATH", .35f),
                MacroSpec("WANDER", .55f), MacroSpec("SHARED", .85f)))
            OtisVoice.HARP -> addAll(listOf(MacroSpec("PRESSURE", .60f), MacroSpec("BREATH", .35f),
                MacroSpec("BEND", .65f), MacroSpec("VIBRATO", .30f)))
            OtisVoice.SQUEEZE -> addAll(listOf(MacroSpec("PRESSURE", .60f), MacroSpec("MUSETTE", .60f),
                MacroSpec("PULSE", .50f), MacroSpec("SHARED", .85f)))
        }
        add(MacroSpec("LENGTH", .5f, neutral = .5f))
    }
    fun defaults(voice: OtisVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    internal fun controlsFor(voice: OtisVoice, profile: OtisProfile): Map<String, Float> = defaults(voice) + when (voice) {
        OtisVoice.ORGAN -> mapOf("CLICK" to profile.organ.click, "DRIVE" to profile.organ.drive,
            "ROTARY" to if (profile.organ.rotary) .5f else 0f)
        OtisVoice.STEAM -> mapOf("PRESSURE" to profile.air.pressure, "BREATH" to profile.air.breath,
            "WANDER" to profile.air.steamInstability, "SHARED" to profile.sharedPressure)
        OtisVoice.HARP -> mapOf("PRESSURE" to profile.air.pressure, "BREATH" to profile.air.breath,
            "BEND" to profile.air.harpBend, "VIBRATO" to profile.air.harpVibrato)
        OtisVoice.SQUEEZE -> mapOf("PRESSURE" to profile.air.pressure, "MUSETTE" to profile.air.musette,
            "PULSE" to profile.air.bellowsPulse, "SHARED" to profile.sharedPressure)
    }

    internal fun settled(voice: OtisVoice, macros: Map<String, Float>, profile: OtisProfile): Map<String, Float> =
        controlsFor(voice, profile).mapValues { (name, value) -> macros[name]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: value }

    fun scramble(voice: OtisVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> =
        Dsp.scrambleNear((near as? OtisPatch)?.takeIf { it.voice == voice }?.macros ?: defaults(voice),
            temperature.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .35f, random)

    /** Absolute anchor after the relative tuning control, kept inside the source's safe score range. */
    fun midiFor(midi: Int, tune: Float): Int =
        (midi + (((tune.takeIf { it.isFinite() } ?: .5f).coerceIn(0f, 1f) - .5f) * 24).roundToInt()).coerceIn(36, 84)
    fun midiFor(patch: OtisPatch): Int = midiFor(patch.midi, patch.macros.getValue("TUNE")).coerceIn(
        36 - patch.profile.events.minOf { it.semitones }, 84 - patch.profile.events.maxOf { it.semitones })
    fun midiFor(voice: OtisVoice, tune: Float) = midiFor(defaultMidi(voice), tune)
    fun frequencyFor(voice: OtisVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))

    private fun organ(patch: OtisPatch): OtisOrganProfile {
        val base = patch.profile.organ
        val m = patch.macros
        val bars = m.getValue("BARS")
        var result = base.copy(click = m.getValue("CLICK"), drive = m.getValue("DRIVE"),
            drawbars = base.drawbars.mapIndexed { i, value ->
                if (bars <= .5f) value * (1 - (1 - 2 * bars) * i / 8f * .9f)
                else value + (1 - value) * (2 * bars - 1) * i / 8f
            })
        val rotary = m.getValue("ROTARY")
        val anchor = if (base.rotary) .5f else 0f
        if (rotary != anchor) {
            result = result.copy(rotary = rotary > 0f,
                rotaryDepth = base.rotaryDepth * (rotary / .35f).coerceIn(0f, 1f),
                rotaryStart = if (rotary >= .8f) OtisRotorSpeed.FAST else OtisRotorSpeed.SLOW,
                rotaryTarget = if (rotary >= .5f) OtisRotorSpeed.FAST else OtisRotorSpeed.SLOW,
                rotaryChangeSeconds = base.rotaryChangeSeconds * (1 - ((rotary - .5f) / .3f).coerceIn(0f, 1f) * .75))
        }
        return result
    }

    private fun air(patch: OtisPatch): OtisAirProfile {
        val base = patch.profile.air
        val m = patch.macros
        return when (patch.voice) {
            OtisVoice.STEAM -> base.copy(pressure = m.getValue("PRESSURE"), breath = m.getValue("BREATH"), steamInstability = m.getValue("WANDER"))
            OtisVoice.HARP -> base.copy(pressure = m.getValue("PRESSURE"), breath = m.getValue("BREATH"),
                harpBend = m.getValue("BEND"), harpVibrato = m.getValue("VIBRATO"))
            OtisVoice.SQUEEZE -> base.copy(pressure = m.getValue("PRESSURE"), musette = m.getValue("MUSETTE"), bellowsPulse = m.getValue("PULSE"))
            else -> base
        }
    }

    private fun notes(patch: OtisPatch): List<OtisNote> {
        val midi = midiFor(patch)
        val scale = 2.0.pow((patch.macros.getValue("LENGTH") - .5) * 2)
        return patch.profile.events.map { event -> OtisNote(midi + event.semitones,
            event.startSeconds * scale, (event.gateSeconds * scale).coerceIn(.04, 8.0), event.velocity * patch.velocity) }
    }

    fun gestureSeconds(patch: OtisPatch): Double = notes(patch).maxOf { it.startSeconds + it.gateSeconds }

    /** Unlevelled accepted source, useful for comparisons and headroom diagnostics. Never cached. */
    fun renderSource(patch: OtisPatch): Snip {
        checkCancelled()
        val notes = notes(patch)
        val source = if (patch.voice == OtisVoice.ORGAN) OtisOrganSource.synthesize(notes, organ(patch), patch.seed, RATE)
        else {
            val settings = air(patch)
            val frames = OtisAirSupport.frameCount(patch.voice, notes, settings, RATE)
            val strength = patch.macros["SHARED"] ?: patch.profile.sharedPressure
            val pressure = OtisAirSupport.trace(patch.voice, notes, settings, frames, strength, RATE)
            when (patch.voice) {
                OtisVoice.STEAM -> OtisSteamSource.synthesize(notes, settings, patch.seed, RATE, pressure)
                OtisVoice.HARP -> OtisHarpSource.synthesize(notes, settings, patch.seed, RATE, pressure)
                OtisVoice.SQUEEZE -> OtisSqueezeSource.synthesize(notes, settings, patch.seed, RATE, pressure)
                else -> error("Organ uses its cabinet source")
            }
        }
        checkCancelled()
        return OtisOutput.condition(source)
    }

    /** Every pad is finite; long audio is filed as LOOP by duration but still plays once. */
    fun drumClassFor(patch: OtisPatch): DrumClass {
        val events = notes(patch)
        val rawFrames = if (patch.voice == OtisVoice.ORGAN)
            ceil((events.maxOf { it.startSeconds + it.gateSeconds } + patch.profile.organ.releaseSeconds + .05) * RATE).toInt()
        else OtisAirSupport.frameCount(patch.voice, events, air(patch), RATE)
        val frames = (rawFrames + (OtisOutput.SETTLING_SECONDS * RATE).toInt()) / Dsp.OVERSAMPLE
        return if (frames.toFloat() / Dsp.RATE > 1.5f) DrumClass.LOOP else DrumClass.PERC
    }
    fun drumClassFor(voice: OtisVoice, macros: Map<String, Float> = emptyMap()) = drumClassFor(OtisPatch("Class", voice, settled(voice, macros, OtisProfile())))
    fun render(voice: OtisVoice, macros: Map<String, Float> = emptyMap()): Snip = render(OtisPatch("Otis", voice, settled(voice, macros, OtisProfile())))

    private data class RenderKey(val voice: OtisVoice, val macros: Map<String, Float>, val profile: OtisProfile,
        val midi: Int, val velocity: Float, val seed: Int, val model: Int)
    private val cache = LinkedHashMap<RenderKey, FloatArray>(16, .75f, true)
    private var cachedSamples = 0
    private const val CACHE_SAMPLES = 1_000_000
    private const val CACHE_ENTRIES = 8
    private val cacheLock = Any()

    /** Cached immutable results accelerate re-triggering; returned samples never alias the cache. */
    fun render(patch: OtisPatch): Snip {
        checkCancelled()
        val key = RenderKey(patch.voice, patch.macros, patch.profile, patch.midi, patch.velocity, patch.seed, patch.model)
        synchronized(cacheLock) { cache[key]?.let { return Snip(it.copyOf(), 1, Dsp.RATE) } }
        val out = renderSource(patch)
        checkCancelled()
        Dsp.levelTo(out.samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        if (out.samples.size <= CACHE_SAMPLES) synchronized(cacheLock) {
            cache.remove(key)?.let { cachedSamples -= it.size }
            cache[key] = out.samples
            cachedSamples += out.samples.size
            while (cache.size > CACHE_ENTRIES || cachedSamples > CACHE_SAMPLES) {
                val iterator = cache.entries.iterator()
                cachedSamples -= iterator.next().value.size
                iterator.remove()
            }
        }
        return Snip(out.samples.copyOf(), 1, Dsp.RATE)
    }
    internal fun clearCache() = synchronized(cacheLock) { cache.clear(); cachedSamples = 0 }
    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Otis render no longer wanted")
    }
}
