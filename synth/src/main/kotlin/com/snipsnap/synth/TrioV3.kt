package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** Frozen single-hit model 3. Earlier saved sounds retain exact audio. */
internal object TrioV3 {
    const val MIN_MIDI = 48
    const val MAX_MIDI = 84
    const val DEFAULT_MIDI = 60
    const val LEGACY_MODEL_VERSION = 1
    const val MODEL_VERSION = 3
    // Model 3 changes only the companions' articulation and decay. Main-player
    // answers keep the model 2 seed namespace, independent of the latest model.
    private const val DRAMA_SEED_MODEL = 2
    private const val SECONDS = 3.5f
    private const val CONTROL_SAMPLES = 64
    private val primary = arrayOf(TrioActor.TRES, TrioActor.BERIMBAU, TrioActor.CHARANGO)

    fun macrosFor(voice: TrioVoice): List<MacroSpec> = listOf(
        MacroSpec("TOUCH", .55f), MacroSpec("DRAMA", 0f), MacroSpec("SIDES", 0f),
        MacroSpec("MOTION", 0f), MacroSpec("SPACE", .35f), MacroSpec("DAMP", .48f),
    )

    fun defaults(voice: TrioVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    private fun validated(voice: TrioVoice, macros: Map<String, Float>, midi: Int, velocity: Float): Map<String, Float> {
        require(midi in MIN_MIDI..MAX_MIDI) { "TRIO midi must be $MIN_MIDI..$MAX_MIDI" }
        require(velocity.isFinite() && velocity in 0f..1f) { "TRIO velocity must be 0..1" }
        val settled = defaults(voice).toMutableMap()
        for ((name, value) in macros) {
            require(name in settled) { "Unknown TRIO macro: $name" }
            require(value.isFinite() && value in 0f..1f) { "TRIO $name must be 0..1" }
            settled[name] = value
        }
        return settled
    }

    /** Cyclic convex weights: at 0 Tres is isolated, at 1/3 wire, at 2/3 Charango. */
    private fun isolation(sides: Float, motion: Float, seconds: Float, span: Float): FloatArray {
        val phase = sides + motion * seconds.coerceAtMost(span) / span
        val position = ((phase * 3f) % 3f + 3f) % 3f
        val base = floor(position).toInt()
        val fraction = position - base
        val weights = FloatArray(3)
        weights[base] = 1f - fraction
        weights[(base + 1) % 3] = fraction
        return weights
    }

    private data class PlayerShape(
        val ratios: FloatArray, val loopHz: Float, val pickLo: Float, val pickHi: Float,
        val position: Float, val gain: Float, val stiffness: Float, val body: List<Modes.Mode>,
        val bodyAmount: Float,
    )

    private fun shape(actor: TrioActor): PlayerShape = when (actor) {
        TrioActor.TRES -> PlayerShape(floatArrayOf(1f, 1.002f, 1f, 1.003f, 2f, 2.004f),
            5200f, 1500f, 8500f, .20f, .24f, 0f,
            listOf(Modes.fixed(130f, .4f, .20f), Modes.fixed(350f, 1f, .14f), Modes.fixed(850f, .4f, .10f)), .10f)
        TrioActor.BERIMBAU -> PlayerShape(floatArrayOf(1f),
            6500f, 500f, 1800f, .31f, .42f, -.025f,
            listOf(Modes.fixed(185f, 1f, .32f), Modes.fixed(430f, .45f, .20f), Modes.fixed(920f, .15f, .10f)), .22f)
        TrioActor.CHARANGO -> PlayerShape(floatArrayOf(1f, 1.003f, 2f, 2.006f),
            8200f, 2800f, 12000f, .12f, .28f, -.008f,
            listOf(Modes.fixed(420f, .45f, .12f), Modes.fixed(1050f, 1f, .08f), Modes.fixed(2300f, .4f, .05f)), .10f)
        TrioActor.OUD -> PlayerShape(floatArrayOf(1f, 1.0015f),
            2800f, 700f, 3300f, .38f, .60f, 0f,
            listOf(Modes.fixed(110f, .55f, .32f), Modes.fixed(280f, 1f, .22f), Modes.fixed(620f, .3f, .13f)), .32f)
        TrioActor.BOUZOUKI -> PlayerShape(floatArrayOf(2f, 2.008f),
            10500f, 4500f, 15000f, .055f, .52f, -.065f,
            listOf(Modes.fixed(330f, .4f, .14f), Modes.fixed(780f, 1f, .11f), Modes.fixed(1900f, .65f, .08f)), .21f)
        TrioActor.PHIN -> PlayerShape(floatArrayOf(1f),
            7500f, 2000f, 10000f, .075f, .46f, -.015f,
            listOf(Modes.fixed(245f, .4f, .15f), Modes.fixed(640f, 1f, .13f), Modes.fixed(2200f, .45f, .07f)), .24f)
    }

    private class StringPart(val loop: Strings.Loop, val excitation: FloatArray, val ratio: Float)
    private class Player(val actor: TrioActor, val shape: PlayerShape, val strings: List<StringPart>, val output: FloatArray)

    private fun player(actor: TrioActor, root: Float, damp: Float, config: TrioConfig, rate: Int, frames: Int): Player {
        val baseShape = shape(actor)
        val companion = !primary.contains(actor)
        // A single strike needs the string's own stored energy to ring. Open its
        // loop filter and retain more feedback; the unchanged pick and body still
        // supply each companion's color. DAMP controls both through the shared
        // contractive mapping, and the same corner is charged to Strings.tune.
        val shape = if (companion) baseShape.copy(loopHz = baseShape.loopHz * 3f) else baseShape
        val damping = Strings.damping(if (companion) damp * .32f else damp, shape.loopHz)
        val start = if (actor == TrioActor.BERIMBAU) root / 2f.pow(config.press * 2f / 12f) else root
        val strings = shape.ratios.map { ratio ->
            val tuning = Strings.tune(start * ratio, damping.loopHz, rate, stiffness = shape.stiffness)
            StringPart(Strings.Loop(tuning.n, tuning.a, damping.fb, damping.loopHz, rate, stiffness = shape.stiffness),
                FloatArray(frames), ratio)
        }
        return Player(actor, shape, strings, FloatArray(frames))
    }

    /** Each family has a source distinction before the resonator/body, not only a gain change. */
    private fun exciter(actor: TrioActor, n: Int, hz: Float, pickHz: Float, position: Float, seed: Int, rate: Int): FloatArray {
        val maxLen = n * 3 + 8
        return when (actor) {
            TrioActor.BERIMBAU -> Strings.mallet(n, hz, pickHz, position, seed, rate, maxLen)
            TrioActor.PHIN -> {
                // A short, asymmetric plectrum scrape: distinct from both noise pluck and mallet.
                val random = Random(seed)
                val length = (rate * .0035f).toInt().coerceAtMost(n).coerceAtLeast(2)
                val pulse = FloatArray(length) { i ->
                    val envelope = (1f - i.toFloat() / length).pow(2)
                    envelope * (.55f * cos(2 * PI * 3300 * i / rate).toFloat() + .45f * (random.nextFloat() * 2f - 1f))
                }
                val mean = pulse.average().toFloat()
                for (i in pulse.indices) pulse[i] -= mean
                pulse
            }
            TrioActor.BOUZOUKI -> {
                val pick = Strings.pluckExciter(n, hz, pickHz, position, seed, rate, maxLen)
                var previous = 0f
                for (i in pick.indices) {
                    val current = pick[i]
                    pick[i] = current - .65f * previous
                    previous = current
                }
                pick
            }
            else -> Strings.pluckExciter(n, hz, pickHz, position, seed, rate, maxLen)
        }
    }

    fun renderProbe(
        voice: TrioVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f, config: TrioConfig = TrioConfig(model = MODEL_VERSION),
    ): TrioProbe {
        if (config.model == 2) return TrioV2.renderProbe(voice, macros, midi, velocity, config)
        if (config.model == LEGACY_MODEL_VERSION || voice != TrioVoice.ENSEMBLE || macros.getOrDefault("DRAMA", 0f) == 0f)
            return TrioV1.renderProbe(voice, macros, midi, velocity, config.copy(model = LEGACY_MODEL_VERSION))
        val m = validated(voice, macros, midi, velocity)
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val frames = (SECONDS * rate).toInt()
        val root = Keys.midiHz(midi)
        val span = config.beats * 60f / config.tempoBpm
        val beatSeconds = 60f / config.tempoBpm
        val tension = m.getValue("DRAMA")
        val drama = ((tension - .7f) / .3f).coerceIn(0f, 1f).pow(.7f)
        val selected = when (voice) {
            TrioVoice.TRES -> listOf(TrioActor.TRES)
            TrioVoice.BERIMBAU -> listOf(TrioActor.BERIMBAU)
            TrioVoice.CHARANGO -> listOf(TrioActor.CHARANGO)
            TrioVoice.ENSEMBLE -> primary.toList() + if (drama > 0f) listOf(TrioActor.OUD, TrioActor.BOUZOUKI, TrioActor.PHIN) else emptyList()
        }
        val players = selected.associateWith { player(it, root, m.getValue("DAMP"), config, rate, frames) }
        val events = mutableListOf<TrioEvent>()
        val actorEventCounts = mutableMapOf<TrioActor, Int>()
        val responseEventCounts = mutableMapOf<TrioActor, Int>()
        val actorOnsets = mutableMapOf<TrioActor, Float>()
        val touch = m.getValue("TOUCH")
        val effort = (.28f + .72f * touch) * sqrt(velocity)

        fun isolationAt(actor: TrioActor, time: Float): Float {
            val index = primary.indexOf(actor)
            return if (voice == TrioVoice.ENSEMBLE && index >= 0)
                isolation(m.getValue("SIDES"), m.getValue("MOTION"), time, span)[index] else 0f
        }

        fun gesture(actor: TrioActor, time: Float, strength: Float, kind: String, strummed: Boolean = false,
            response: Boolean = false, reverse: Boolean = false) {
            if (velocity == 0f || strength <= 0f) return
            val p = players.getValue(actor)
            val alone = isolationAt(actor, time)
            // Endpoint gains interpolate directly; paired voices are never clipped back to unity.
            val alliance = if (primary.contains(actor) && voice == TrioVoice.ENSEMBLE) 1.12f - .52f * alone else 1f
            val energy = effort * strength * alliance
            val companion = !primary.contains(actor)
            val damping = Strings.damping(if (companion) m.getValue("DAMP") * .32f else m.getValue("DAMP"), p.shape.loopHz)
            val pickAmount = ((.2f + .8f * touch) * (.55f + .45f * velocity) +
                if (primary.contains(actor)) tension * (.08f + .15f * alone) else drama * .10f).coerceIn(0f, 1f)
            val pickHz = Dsp.expMap(pickAmount, p.shape.pickLo, p.shape.pickHi)
            // Ghost answers have their own seed namespace and ordinal. An arbitrarily
            // quiet new response must never reseed a later accepted baseline gesture.
            val counts = if (response) responseEventCounts else actorEventCounts
            val eventOrdinal = counts.getOrDefault(actor, 0)
            counts[actor] = eventOrdinal + 1
            val firstOnset = actorOnsets.getOrPut(actor) { time }
            val startHz = if (actor == TrioActor.BERIMBAU && !response && eventOrdinal == 0)
                root / 2f.pow(config.press * 2f / 12f) else root
            var performedTime = time
            for ((k, part) in p.strings.withIndex()) {
                val courseIndex = if (reverse) (p.strings.size - 1 - k) / 2 else k / 2
                val offset = if (companion) 0f else if (strummed) courseIndex * config.strumMs / 1000f * (1f + .8f * alone) /
                    (1f + tension * .45f) else k * .0008f
                val hz = startHz * part.ratio
                // The charango's repeated plectrum reinforces each ringing course at its
                // nearest existing cycle, rather than injecting unrelated noise phases.
                // This changes an onset by at most half a string period, retaining the
                // requested flutter while keeping its root from cancelling into sidebands.
                val onset = if (actor == TrioActor.CHARANGO)
                    firstOnset + offset + Math.round((time - firstOnset) * hz) / hz else time + offset
                if (k == 0) performedTime = onset else if (reverse) performedTime = min(performedTime, onset)
                val at = (onset * rate).toInt()
                val tuning = Strings.tune(hz, damping.loopHz, rate, stiffness = p.shape.stiffness)
                val textureOrdinal = if (actor == TrioActor.CHARANGO) 0 else eventOrdinal
                val course = if (actor == TrioActor.CHARANGO) k / 2 else k
                val seed = if ((!response && primary.contains(actor)) || actor == TrioActor.CHARANGO)
                    Dsp.seedFor("TRIO", LEGACY_MODEL_VERSION, config.seed, midi, actor.name, textureOrdinal, course)
                else Dsp.seedFor("TRIO", "DRAMA", DRAMA_SEED_MODEL, config.seed, midi, actor.name, textureOrdinal, course)
                val burst = exciter(actor, tuning.n, hz, pickHz, p.shape.position, seed, rate)
                val gain = energy / sqrt(p.strings.size.toFloat())
                for (j in burst.indices) if (at + j in part.excitation.indices) part.excitation[at + j] += burst[j] * gain
            }
            events += TrioEvent(actor, performedTime, kind, energy)
        }

        // Only explicit gestures are scheduled. There are no silently allocated husband voices.
        for (beat in 0 until config.beats) {
            val grid = beat * beatSeconds + .012f
            val falloff = if (beat == 0) 1f else .68f
            if (TrioActor.TRES in players) {
                val alone = isolationAt(TrioActor.TRES, grid)
                gesture(TrioActor.TRES, grid + alone * .065f * (1f + .75f * tension), falloff, "course-strum", strummed = true)
                if (beat > 0) gesture(TrioActor.TRES, grid + .15f + alone * .07f, .28f, "strum-answer", strummed = true)
                gesture(TrioActor.TRES, grid + .24f + alone * .05f, .85f * tension * falloff,
                    "reverse-counterstrum", strummed = true, response = true, reverse = true)
            }
            if (TrioActor.BERIMBAU in players) {
                val alone = isolationAt(TrioActor.BERIMBAU, grid)
                gesture(TrioActor.BERIMBAU, grid + .023f * (1f - .5f * tension) + alone * .10f * (1f + .75f * tension), .92f * falloff,
                    if (beat == 0) "wire-strike-press" else "wire-strike")
                if (beat > 0) gesture(TrioActor.BERIMBAU, grid + .19f + alone * .065f, .26f, "wire-answer")
                gesture(TrioActor.BERIMBAU, grid + .32f + alone * .065f, .62f * tension * falloff,
                    "wire-counterstrike", response = true)
            }
            if (TrioActor.CHARANGO in players) {
                val alone = isolationAt(TrioActor.CHARANGO, grid)
                val onset = grid + .036f * (1f - .5f * tension) + alone * .115f * (1f + .75f * tension)
                val length = min(.24f, beatSeconds * .42f)
                val baselineStrokes = ceil(length * config.tremoloHz).toInt().coerceIn(2, 6)
                val rateHz = config.tremoloHz * (1f + .55f * tension)
                for (stroke in 0 until baselineStrokes + 4) {
                    val time = onset + stroke / rateHz
                    if (stroke < baselineStrokes)
                        gesture(TrioActor.CHARANGO, time, falloff * (if (stroke == 0) .76f else .40f), "repluck-$stroke")
                    else {
                        val answer = (tension * 4f - (stroke - baselineStrokes)).coerceIn(0f, 1f)
                        gesture(TrioActor.CHARANGO, time, falloff * .40f * answer,
                            "repluck-answer-${stroke - baselineStrokes}", response = true)
                    }
                }
            }
            // Each companion strikes once for the entire pad performance, then
            // its waveguide and body decay without another answer or excitation.
            if (beat == 0) {
                if (TrioActor.OUD in players)
                    gesture(TrioActor.OUD, grid + .28f - .12f * drama, drama * .92f,
                        "dark-course-single-hit", response = true)
                if (TrioActor.BOUZOUKI in players)
                    gesture(TrioActor.BOUZOUKI, grid + .38f - .18f * drama, drama * .85f,
                        "octave-metal-single-hit", response = true)
                if (TrioActor.PHIN in players)
                    gesture(TrioActor.PHIN, grid + .46f - .20f * drama, drama * .85f,
                        "scrape-single-hit", response = true)
            }
        }

        val mainPlayers = primary.map { players[it] }
        val reflected = FloatArray(3)
        var weights = isolation(m.getValue("SIDES"), m.getValue("MOTION"), 0f, span)
        val wire = players[TrioActor.BERIMBAU]
        val wirePressOnset = events.firstOrNull { it.actor == TrioActor.BERIMBAU }?.timeSeconds ?: 0f
        val pressDuration = .08f + config.press * .06f
        val pressStart = root / 2f.pow(config.press * 2f / 12f)
        for (i in 0 until frames) {
            if (i % CONTROL_SAMPLES == 0) {
                val time = i.toFloat() / rate
                weights = isolation(m.getValue("SIDES"), m.getValue("MOTION"), time, span)
                val pressElapsed = time - wirePressOnset
                if (wire != null && config.press > 0f && pressElapsed <= pressDuration + CONTROL_SAMPLES.toFloat() / rate) {
                    val progress = (pressElapsed / pressDuration).coerceIn(0f, 1f)
                    wire.strings[0].loop.retune(pressStart * (root / pressStart).pow(progress))
                }
            }
            for (a in 0..2) reflected[a] = mainPlayers[a]?.strings?.get(0)?.loop?.reflected() ?: 0f
            for ((actor, p) in players) {
                val a = primary.indexOf(actor)
                var mainWave = if (a >= 0) reflected[a] else p.strings[0].loop.reflected()
                if (voice == TrioVoice.ENSEMBLE && a >= 0) {
                    // e01=w2, e12=w0, e20=w1. Each row remains a convex mixture,
                    // and symmetry makes this a contractive returned-wave junction.
                    for (b in 0..2) if (a != b) {
                        val outsider = 3 - a - b
                        mainWave += (.10f + .08f * tension) * weights[outsider] * (reflected[b] - reflected[a])
                    }
                }
                var sample = p.strings[0].loop.inject(mainWave + p.strings[0].excitation[i])
                for (k in 1 until p.strings.size) sample += p.strings[k].loop.next(p.strings[k].excitation[i])
                p.output[i] = sample * p.shape.gain / sqrt(p.strings.size.toFloat())
            }
        }

        val taps = linkedMapOf<TrioActor, FloatArray>()
        for ((actor, p) in players) {
            val space = m.getValue("SPACE")
            val body = Strings.bodyRing(p.output, p.shape.body, p.shape.bodyAmount * (.75f + .75f * space), rate, SECONDS)
            val colored = Dsp.OnePole(rate)
            val early = FloatArray(body.size)
            val delayA = ((.043f + actor.ordinal * .003f) * rate).toInt()
            val delayB = ((.087f + actor.ordinal * .005f) * rate).toInt()
            for (i in body.indices) {
                val reflection = (if (i >= delayA) body[i - delayA] * .21f else 0f) +
                    (if (i >= delayB) body[i - delayB] * .13f else 0f)
                early[i] = body[i] + space * colored.lp(reflection, 3800f)
            }
            val mono = Dsp.decimate(early, Dsp.RATE)
            // A finite recording always clears the tail, including very long body modes.
            val fade = (Dsp.RATE * .15f).toInt()
            for (j in 0 until fade) mono[mono.size - fade + j] *= (1f - j.toFloat() / fade).pow(2)
            Dsp.fadeTail(mono)
            taps[actor] = mono
        }
        val samples = FloatArray((SECONDS * Dsp.RATE).toInt())
        for (tap in taps.values) for (i in samples.indices) samples[i] += tap[i]
        check(samples.all { it.isFinite() }) { "TRIO produced a nonfinite sample" }
        return TrioProbe(samples, taps, events.sortedWith(compareBy<TrioEvent> { it.timeSeconds }.thenBy { it.actor.ordinal }))
    }

    fun render(
        voice: TrioVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f, config: TrioConfig = TrioConfig(model = MODEL_VERSION),
    ): Snip {
        val output = renderProbe(voice, macros, midi, velocity, config).samples.copyOf()
        Dsp.levelTo(output, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        // Shared loudness matching must not erase the requested playing velocity.
        for (i in output.indices) output[i] *= velocity
        Dsp.fadeTail(output)
        return Snip(output, channels = 1, sampleRate = Dsp.RATE)
    }
}
