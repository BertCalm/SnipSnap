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

enum class TrioVoice { TRES, BERIMBAU, CHARANGO, ENSEMBLE }
enum class TrioActor { TRES, BERIMBAU, CHARANGO, OUD, BOUZOUKI, PHIN }

/** Regenerable performance choices. These are prototype shapes, not instrument measurements. */
data class TrioConfig(
    val seed: Int = 0x5452494F,
    val tempoBpm: Float = 104f,
    val beats: Int = 2,
    val press: Float = .4f,
    val tremoloHz: Float = 10f,
    val strumMs: Float = 8f,
    val model: Int = Trio.MODEL_VERSION,
) {
    init {
        require(tempoBpm.isFinite() && tempoBpm in 60f..200f) { "TRIO tempo must be 60..200 BPM" }
        require(beats in 1..4) { "TRIO beats must be 1..4" }
        require(beats * 60f / tempoBpm <= 2f) { "TRIO beat grid must fit within two seconds" }
        require(press.isFinite() && press in 0f..1f) { "TRIO press must be 0..1" }
        require(tremoloHz.isFinite() && tremoloHz in 5f..25f) { "TRIO tremolo must be 5..25 Hz" }
        require(strumMs.isFinite() && strumMs in 1f..30f) { "TRIO strum must be 1..30 ms" }
        require(model in Trio.LEGACY_MODEL_VERSION..Trio.MODEL_VERSION) { "Unsupported TRIO model: $model" }
    }
}

data class TrioEvent(val actor: TrioActor, val timeSeconds: Float, val kind: String, val energy: Float)
data class TrioProbe(
    val samples: FloatArray,
    val actors: Map<TrioActor, FloatArray>,
    val events: List<TrioEvent>,
)

/**
 * An audition-first plucked ensemble. A render explicitly builds its players; note order never
 * determines the instrument. The three main string ports exchange returned waves through a
 * symmetric convex junction. SIDES continuously chooses the isolated player, while MOTION
 * moves that relationship during the phrase. The same relationship changes gesture timing.
 *
 * All body tables, voicing gains, gesture schedules and companion choices are prototype shapes
 * awaiting listening. There is no claim of an acoustically calibrated tres/berimbau/charango.
 * This finite renderer deliberately has no HOLD, live allocation or export-loop contract.
 */
object Trio {
    const val MIN_MIDI = 48
    const val MAX_MIDI = 84
    const val DEFAULT_MIDI = 60
    const val LEGACY_MODEL_VERSION = 1
    const val MODEL_VERSION = 7
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

    /** Solo recipes retain all six values, but only these three controls act there. */
    fun isMacroActive(voice: TrioVoice, name: String): Boolean =
        voice == TrioVoice.ENSEMBLE || name !in setOf("DRAMA", "SIDES", "MOTION")

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

    private class StringPart(val loop: Strings.Loop, val excitation: TrioExcitation, val ratio: Float)
    private class Player(val actor: TrioActor, val shape: PlayerShape, val strings: Array<StringPart>, val output: FloatArray) {
        val courseDivisor = sqrt(strings.size.toFloat())
    }

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
            StringPart(Strings.Loop(tuning.n, tuning.a, damping.fb, damping.loopHz, rate, stiffness = shape.stiffness, cacheLoopFilter = true),
                TrioExcitation(frames), ratio)
        }
        return Player(actor, shape, strings.toTypedArray(), FloatArray(frames))
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

    /** Immutable, bounded one-entry score cache. Audio buffers and live loops are never shared. */
    private data class ScoreKey(val voice: TrioVoice, val macros: Map<String, Float>, val midi: Int,
        val velocity: Float, val config: TrioConfig)
    @Volatile private var cachedScore: Pair<ScoreKey, TrioScore>? = null

    internal fun score(voice: TrioVoice, macros: Map<String, Float>, midi: Int, velocity: Float,
        config: TrioConfig): TrioScore {
        require(config.model >= 5 && voice == TrioVoice.ENSEMBLE && (macros["DRAMA"] ?: 0f) > 0f)
        TrioRenderSupport.checkCancelled()
        val key = ScoreKey(voice, validated(voice, macros, midi, velocity).toMap(), midi, velocity, config)
        cachedScore?.let { if (it.first == key) return it.second }
        return buildScore(voice, key.macros, midi, velocity, config).also { cachedScore = key to it }
    }

    private fun buildScore(voice: TrioVoice, macros: Map<String, Float>, midi: Int, velocity: Float,
        config: TrioConfig): TrioScore {
        val m = validated(voice, macros, midi, velocity)
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val root = Keys.midiHz(midi)
        val span = config.beats * 60f / config.tempoBpm
        val beatSeconds = 60f / config.tempoBpm
        val tension = m.getValue("DRAMA")
        // The companions now enter across the complete control travel. There is
        // no floor or threshold: zero has no companions, their excitation tends
        // continuously to zero, and full DRAMA retains model 3's exact scene.
        val drama = tension.pow(.65f)
        // Quiet drama leaves room for individual answers across the phrase;
        // higher drama brings their single strikes together. Keep the accepted
        // full entrance exactly, and leave at least 1.6 seconds for stored ring.
        val spacingScale = (span / (120f / 104f)).coerceIn(.65f, 1.25f)
        fun companionOnset(entrance: Float, zeroEntrance: Float, spreadSeconds: Float): Float {
            val budget = min(spreadSeconds * spacingScale, 1.9f - zeroEntrance)
            return entrance + (1f - tension) * budget
        }
        val selected = when (voice) {
            TrioVoice.TRES -> listOf(TrioActor.TRES)
            TrioVoice.BERIMBAU -> listOf(TrioActor.BERIMBAU)
            TrioVoice.CHARANGO -> listOf(TrioActor.CHARANGO)
            TrioVoice.ENSEMBLE -> primary.toList() + if (drama > 0f) listOf(TrioActor.OUD, TrioActor.BOUZOUKI, TrioActor.PHIN) else emptyList()
        }
        val players = selected.associateWith {
            val base = shape(it)
            if (it !in primary) base.copy(loopHz = base.loopHz * 3f) else base
        }
        val courses = mutableListOf<TrioCourse>()
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
            val damping = Strings.damping(if (companion) m.getValue("DAMP") * .32f else m.getValue("DAMP"), p.loopHz)
            val pickAmount = ((.2f + .8f * touch) * (.55f + .45f * velocity) +
                if (primary.contains(actor)) tension * (.08f + .15f * alone) else drama * .10f).coerceIn(0f, 1f)
            val basePickHz = Dsp.expMap(pickAmount, p.pickLo, p.pickHi)
            // Model 5's tension can saturate pickAmount before TOUCH reaches 1.
            // A smooth upper fifth adds contact brightness after that clamp;
            // the accepted bank (TOUCH <= .8) and every older recipe stay exact.
            val hardTouch = if (config.model >= 6) ((touch - .8f) / .2f).coerceIn(0f, 1f) else 0f
            val pickHz = basePickHz * (1f + 2f * hardTouch * hardTouch * tension)
            // Ghost answers have their own seed namespace and ordinal. An arbitrarily
            // quiet new response must never reseed a later accepted baseline gesture.
            val counts = if (response) responseEventCounts else actorEventCounts
            val eventOrdinal = counts.getOrDefault(actor, 0)
            counts[actor] = eventOrdinal + 1
            val firstOnset = actorOnsets.getOrPut(actor) { time }
            val startHz = if (actor == TrioActor.BERIMBAU && !response && eventOrdinal == 0)
                root / 2f.pow(config.press * 2f / 12f) else root
            var performedTime = time
            for ((k, ratio) in p.ratios.withIndex()) {
                val courseIndex = if (reverse) (p.ratios.size - 1 - k) / 2 else k / 2
                val offset = if (companion) 0f else if (strummed) courseIndex * config.strumMs / 1000f * (1f + .8f * alone) /
                    (1f + tension * .45f) else k * .0008f
                val hz = startHz * ratio
                // The charango's repeated plectrum reinforces each ringing course at its
                // nearest existing cycle, rather than injecting unrelated noise phases.
                // This changes an onset by at most half a string period, retaining the
                // requested flutter while keeping its root from cancelling into sidebands.
                val onset = if (actor == TrioActor.CHARANGO)
                    firstOnset + offset + Math.round((time - firstOnset) * hz) / hz else time + offset
                if (k == 0) performedTime = onset else if (reverse) performedTime = min(performedTime, onset)
                val at = (onset * rate).toInt()
                val tuning = Strings.tune(hz, damping.loopHz, rate, stiffness = p.stiffness)
                val textureOrdinal = if (actor == TrioActor.CHARANGO) 0 else eventOrdinal
                val course = if (actor == TrioActor.CHARANGO) k / 2 else k
                val seed = if ((!response && primary.contains(actor)) || actor == TrioActor.CHARANGO)
                    Dsp.seedFor("TRIO", LEGACY_MODEL_VERSION, config.seed, midi, actor.name, textureOrdinal, course)
                else Dsp.seedFor("TRIO", "DRAMA", DRAMA_SEED_MODEL, config.seed, midi, actor.name, textureOrdinal, course)
                val gain = energy / sqrt(p.ratios.size.toFloat())
                courses += TrioCourse(actor, k, at, tuning.n, hz, pickHz, p.position, seed, gain)
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
                    gesture(TrioActor.OUD, companionOnset(grid + .28f - .12f * drama, grid + .28f, .5f), drama * .92f,
                        "dark-course-single-hit", response = true)
                if (TrioActor.BOUZOUKI in players)
                    gesture(TrioActor.BOUZOUKI, companionOnset(grid + .38f - .18f * drama, grid + .38f, 1f), drama * .85f,
                        "octave-metal-single-hit", response = true)
                if (TrioActor.PHIN in players)
                    gesture(TrioActor.PHIN, companionOnset(grid + .46f - .20f * drama, grid + .46f, 1.6f), drama * .85f,
                        "scrape-single-hit", response = true)
            }
        }

        return TrioScore(span, selected.toList(), events.sortedWith(
            compareBy<TrioEvent> { it.timeSeconds }.thenBy { it.actor.ordinal }), courses.toList())
    }

    fun renderProbe(
        voice: TrioVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f, config: TrioConfig = TrioConfig(),
    ): TrioProbe = renderScene(voice, macros, midi, velocity, config)

    /** Frozen model-6 audition seam for checking the approved contact against later renderers. */
    internal fun renderContactProbe(
        macros: Map<String, Float>, midi: Int = DEFAULT_MIDI, velocity: Float = 1f,
        config: TrioConfig = TrioConfig(model = 6), transform: (TrioCourse, FloatArray) -> FloatArray,
    ): TrioProbe {
        require(config.model == 6 && (macros["DRAMA"] ?: 0f) > 0f) {
            "TRIO contact diagnostic requires model 6 and positive DRAMA"
        }
        return renderScene(TrioVoice.ENSEMBLE, macros, midi, velocity, config,
            excitationTransform = transform)
    }

    private fun renderScene(
        voice: TrioVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f, config: TrioConfig = TrioConfig(), keepActors: Boolean = true,
        excitationTransform: ((TrioCourse, FloatArray) -> FloatArray)? = null,
    ): TrioProbe {
        TrioRenderSupport.checkCancelled()
        if (config.model == 4) return TrioV4.renderProbe(voice, macros, midi, velocity, config)
        if (config.model == 3) return TrioV3.renderProbe(voice, macros, midi, velocity, config)
        if (config.model == 2) return TrioV2.renderProbe(voice, macros, midi, velocity, config)
        if (config.model == LEGACY_MODEL_VERSION || voice != TrioVoice.ENSEMBLE || macros.getOrDefault("DRAMA", 0f) == 0f)
            return TrioV1.renderProbe(voice, macros, midi, velocity, config.copy(model = LEGACY_MODEL_VERSION))
        val m = validated(voice, macros, midi, velocity)
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val frames = (SECONDS * rate).toInt()
        val root = Keys.midiHz(midi)
        val score = score(voice, m, midi, velocity, config)
        val span = score.gridSpanSeconds
        val tension = m.getValue("DRAMA")
        val contactAmount = if (config.model >= 7) TrioContact.amount(m.getValue("TOUCH"), tension) else 0f
        val players = score.actors.associateWith { player(it, root, m.getValue("DAMP"), config, rate, frames) }
        for (course in score.courses) {
            TrioRenderSupport.checkCancelled()
            val original = exciter(course.actor, course.n, course.hz, course.pickHz, course.position, course.seed, rate)
            // Shape the force before each string; timing, course gains and returned-wave coupling stay fixed.
            val contact = if (contactAmount > 0f) TrioContact.shape(original, course.hz, rate, contactAmount) else original
            val burst = excitationTransform?.invoke(course, contact) ?: contact
            players.getValue(course.actor).strings[course.string].excitation.add(course.at, burst, course.gain)
        }
        for (p in players.values) for (part in p.strings) part.excitation.prepare()
        val events = score.events
        val mainPlayers = primary.map { players[it] }
        val orderedPlayers = players.values.toTypedArray()
        val reflected = FloatArray(3)
        var weights = isolation(m.getValue("SIDES"), m.getValue("MOTION"), 0f, span)
        val wire = players[TrioActor.BERIMBAU]
        val wirePressOnset = events.firstOrNull { it.actor == TrioActor.BERIMBAU }?.timeSeconds ?: 0f
        val pressDuration = .08f + config.press * .06f
        val pressStart = root / 2f.pow(config.press * 2f / 12f)
        for (i in 0 until frames) {
            if (i % CONTROL_SAMPLES == 0) {
                TrioRenderSupport.checkCancelled()
                val time = i.toFloat() / rate
                weights = isolation(m.getValue("SIDES"), m.getValue("MOTION"), time, span)
                val pressElapsed = time - wirePressOnset
                if (wire != null && config.press > 0f && pressElapsed <= pressDuration + CONTROL_SAMPLES.toFloat() / rate) {
                    val progress = (pressElapsed / pressDuration).coerceIn(0f, 1f)
                    wire.strings[0].loop.retune(pressStart * (root / pressStart).pow(progress))
                }
            }
            for (a in 0..2) reflected[a] = mainPlayers[a]?.strings?.get(0)?.loop?.reflected() ?: 0f
            for (p in orderedPlayers) {
                val a = if (p.actor.ordinal < 3) p.actor.ordinal else -1
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
                p.output[i] = sample * p.shape.gain / p.courseDivisor
            }
        }

        val taps = linkedMapOf<TrioActor, FloatArray>()
        val samples = FloatArray((SECONDS * Dsp.RATE).toInt())
        for ((actor, p) in players) {
            val space = m.getValue("SPACE")
            TrioRenderSupport.checkCancelled()
            val body = TrioRenderSupport.body(p.output, p.shape.body, p.shape.bodyAmount * (.75f + .75f * space), rate)
            val colored = Dsp.FixedOnePole(3800f, rate)
            val delayA = ((.043f + actor.ordinal * .003f) * rate).toInt()
            val delayB = ((.087f + actor.ordinal * .005f) * rate).toInt()
            // Only two past body samples are needed. Retain their original
            // values in a short line while reusing the body buffer for arrivals.
            val history = FloatArray(delayB + 1)
            var write = 0
            var readA = history.size - delayA
            var readB = history.size - delayB
            for (i in body.indices) {
                if (i % 1024 == 0) TrioRenderSupport.checkCancelled()
                val reflection = (if (i >= delayA) history[readA] * .21f else 0f) +
                    (if (i >= delayB) history[readB] * .13f else 0f)
                val original = body[i]
                history[write] = original
                if (++write == history.size) write = 0
                if (++readA == history.size) readA = 0
                if (++readB == history.size) readB = 0
                body[i] = original + space * colored.lp(reflection)
            }
            val mono = Dsp.decimate(body, Dsp.RATE)
            // A finite recording always clears the tail, including very long body modes.
            val fade = (Dsp.RATE * .15f).toInt()
            for (j in 0 until fade) mono[mono.size - fade + j] *= (1f - j.toFloat() / fade).pow(2)
            Dsp.fadeTail(mono)
            if (keepActors) taps[actor] = mono
            for (i in samples.indices) samples[i] += mono[i]
        }
        check(samples.all { it.isFinite() }) { "TRIO produced a nonfinite sample" }
        return TrioProbe(samples, taps, events.sortedWith(compareBy<TrioEvent> { it.timeSeconds }.thenBy { it.actor.ordinal }))
    }

    fun render(
        voice: TrioVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f, config: TrioConfig = TrioConfig(),
    ): Snip {
        val output = renderScene(voice, macros, midi, velocity, config, keepActors = false).samples
        Dsp.levelTo(output, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        // Shared loudness matching must not erase the requested playing velocity.
        for (i in output.indices) output[i] *= velocity
        Dsp.fadeTail(output)
        return Snip(output, channels = 1, sampleRate = Dsp.RATE)
    }
}
