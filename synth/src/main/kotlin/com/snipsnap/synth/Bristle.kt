package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.math.*

/** Finite bass study: the owner accepted both Python routes. The registered Patch preserves this model.
 * A shared fundamental and PolyBLEP saw give onset → rasp → darkening body → tight stop.
 * Model 1 retains the accepted hard-touch source; softer touch reduces drive and cutoff.
 * Output uses the JVM house stage, which differs from the Python audition's RMS proxy.
 */
object Bristle {
    const val MODEL_VERSION = 1
    const val MIDI_MIN = 24
    const val MIDI_MAX = 48
    // A/B are stable saved routing anchors; the named entries are new source voices.
    enum class Route { A, B, ROUND, PULSE, HOLLOW, REED, WIRE, FOLD }
    val anchors = listOf(Route.A, Route.B)
    val musicalVoices = listOf(Route.ROUND, Route.PULSE, Route.HOLLOW, Route.REED, Route.WIRE, Route.FOLD)
    val defaults: Map<String, Float> = mapOf(
        "TUNE" to .5f, "WEIGHT" to .55f, "GRIT" to .55f, "DARK" to .55f,
        "BITE" to .25f, "SLIDE" to 0f, "HOLD" to .5f,
    )
    fun settled(macros: Map<String, Float>): Map<String, Float> = defaults.mapValues { (k, d) ->
        macros[k]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: d
    }
    fun midiFor(tune: Float): Int = MIDI_MIN + (24 * (tune.takeIf { it.isFinite() } ?: .5f).coerceIn(0f, 1f)).roundToInt()
    fun macrosFor(@Suppress("UNUSED_PARAMETER") route: Route): List<MacroSpec> =
        defaults.map { (name, value) -> MacroSpec(name, value) }
    fun defaults(@Suppress("UNUSED_PARAMETER") route: Route) = defaults
    fun scramble(route: Route, random: kotlin.random.Random): Map<String, Float> =
        Dsp.scrambleNear(defaults(route), .3f, random)
    fun drumClassFor(@Suppress("UNUSED_PARAMETER") route: Route,
                     @Suppress("UNUSED_PARAMETER") macros: Map<String, Float>) = com.snipsnap.audio.DrumClass.TONAL

    /** Patch MIDI is the authored centre; TUNE offsets it by up to one octave. */
    fun rootMidi(patch: BristlePatch): Int =
        (patch.midi + midiFor(settled(patch.macros).getValue("TUNE")) - 36).coerceIn(MIDI_MIN, MIDI_MAX)

    fun frequencyFor(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

    /** Standalone versioned study recipe. Route is saved explicitly, never inferred from a name.
     * Explicit MIDI is the sole note input; TUNE is the convenience UI mapping used by render().
     */
    data class Recipe(
        val route: Route = Route.A,
        val midi: Int = 36,
        val velocity: Float = 1f,
        val macros: Map<String, Float> = emptyMap(),
        val model: Int = MODEL_VERSION,
        val effects: BristleEffects.Settings = BristleEffects.Settings(),
    ) {
        init {
            require(model == MODEL_VERSION) { "Unsupported Bristle model $model" }
            require(midi in MIDI_MIN..MIDI_MAX)
            require(velocity.isFinite() && velocity in 0f..1f)
            require(macros.keys.all { it in defaults } && macros.values.all { it.isFinite() && it in 0f..1f })
        }
        fun render(): Snip = renderNote(this)
        fun toJsonText(): String = Json.write(JsonValue.Obj(linkedMapOf<String, JsonValue>(
            "study" to JsonValue.Str("BRISTLE"), "model" to JsonValue.Num(model.toDouble()),
            "route" to JsonValue.Str(route.name), "midi" to JsonValue.Num(midi.toDouble()),
            "velocity" to JsonValue.Num(velocity.toDouble()),
            "macros" to JsonValue.Obj(macros.mapValues { JsonValue.Num(it.value.toDouble()) }),
        ).apply { if (!effects.isOff) put("effects", effects.toJsonValue()) }))
        companion object {
            fun fromJsonText(text: String): Recipe {
                val o = Json.parse(text).obj()
                require(o.getValue("study").str() == "BRISTLE")
                return Recipe(Route.valueOf(o.getValue("route").str()), o.getValue("midi").int(),
                    o.getValue("velocity").num().toFloat(),
                    o.getValue("macros").obj().mapValues { it.value.num().toFloat() },
                    o.getValue("model").int(),
                    o["effects"]?.let(BristleEffects.Settings::fromJsonValue) ?: BristleEffects.Settings())
            }
        }
    }

    fun render(route: Route, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip {
        val m = settled(macros)
        return Recipe(route, midiFor(m.getValue("TUNE")), velocity, m).render()
    }

    private fun blep(p: Double, dt: Double): Double = when {
        p < dt -> { val z = p / dt; 2*z-z*z-1 }
        p > 1-dt -> { val z = (p-1)/dt; z*z+2*z+1 }
        else -> 0.0
    }

    /** Raw seam to the accepted Python model; exposed internally for numerical comparisons.
     * Fixed one-second buffer, including silence. HOLD never claims to be a looping sustain.
     */
    internal fun synthesize(recipe: Recipe, rate: Int = Dsp.RATE * Dsp.OVERSAMPLE): FloatArray =
        synthesizeCore(recipe, rate, null, null, { false })

    internal fun synthesizeHeld(recipe: Recipe, heldFrames: Int, heldHz: Double,
                                cancelled: () -> Boolean): FloatArray =
        synthesizeCore(recipe, Dsp.RATE * Dsp.OVERSAMPLE, heldFrames, heldHz, cancelled)

    /** Take zero is the accepted render. Take one opens the gate at a new shared oscillator
     * phase, with a slightly slower attack and a brief filter opening. No pitch or gain jitter. */
    fun renderTake(recipe: Recipe, take: Int): Snip {
        require(take in 0..1)
        if (take == 0) return recipe.render()
        return finish(synthesizeCore(recipe, Dsp.RATE * Dsp.OVERSAMPLE, null, null, { false }, take), recipe.velocity, true, recipe.effects)
    }

    internal fun synthesizeHeldTake(recipe: Recipe, frames: Int, hz: Double, take: Int,
                                    cancelled: () -> Boolean): FloatArray {
        require(take in 0..1)
        return synthesizeCore(recipe, Dsp.RATE * Dsp.OVERSAMPLE, frames, hz, cancelled, take)
    }

    private fun synthesizeCore(recipe: Recipe, rate: Int, heldFrames: Int?, heldHz: Double?,
                               cancelled: () -> Boolean, take: Int = 0): FloatArray {
        require(rate >= Dsp.RATE)
        val m = settled(recipe.macros)
        val out = FloatArray(heldFrames ?: rate)
        if (recipe.velocity == 0f) return out
        val hz = heldHz ?: frequencyFor(recipe.midi)
        val touch = recipe.velocity.toDouble()
        // At touch=1 these maps are identity: preserve the accepted voicing.
        val cutoff = 2400.0 * (.075).pow(m.getValue("DARK").toDouble()) *
            (hz / frequencyFor(36)).pow(.6) * (.35 + .65*touch)
        val drive = 12.0.pow(m.getValue("GRIT").toDouble()) * (.65 + .35*touch)
        val norm = tanh(drive)
        val gate = .12 + .6*m.getValue("HOLD")
        val attackSeconds = (.004 + .004*(1-touch)) * if (take == 0) 1.0 else 1.12
        val svf = Dsp.TptSvf(rate)
        val fuzz = recipe.effects.fuzz.takeIf { it > 0f }?.let { BristleEffects.Fuzz(it, rate) }
        var phase = if (take == 0) 0.0 else .19
        for (i in out.indices) {
            if (i % 4096 == 0 && (cancelled() || Thread.currentThread().isInterrupted))
                throw java.util.concurrent.CancellationException("Bristle render cancelled")
            val t = i.toDouble()/rate
            if (heldFrames == null && t >= gate + .045) break
            val hzAt = hz * 2.0.pow(-m.getValue("SLIDE") * max(1-t/.12, 0.0))
            val dt = hzAt/rate
            val sine = -sin(2*PI*phase) // rising saw's fundamental has negative sine polarity
            val saw = 2*phase-1-blep(phase,dt)
            val angle = 2*PI*phase
            // New voices splice a source into the accepted clean-root/filter architecture.
            // Shapes are authored hypotheses, not physical instrument models.
            val source = when (recipe.route) {
                Route.A, Route.B -> saw
                Route.ROUND -> sine + .6*touch*sin(2*angle)
                Route.PULSE, Route.HOLLOW -> {
                    val duty = if (recipe.route == Route.PULSE) .23 else .5
                    val shifted = (phase-duty+1)%1.0
                    -(if (phase < duty) 1.0 else -1.0) - blep(phase,dt) + blep(shifted,dt)
                }
                Route.REED -> saw + .35*sin(3*angle)
                Route.WIRE -> .6*sine - .4*sin(angle + (.35 + 2.2*touch*exp(-t/.09))*sin(2*angle))
                Route.FOLD -> .55*sine - .45*sin((1.0 + 4.5*m.getValue("GRIT")*touch)*sin(angle))
            }
            phase = (phase+dt)%1.0
            val fc = (cutoff * 2.0.pow(2*m.getValue("BITE")*exp(-t/.065)) *
                if (take == 0) 1.0 else (1.0 + .12*exp(-t/.045))).toFloat()
            val input = when (recipe.route) {
                Route.A -> tanh(drive*saw)/norm
                Route.ROUND -> tanh((.5+.15*drive)*source)/tanh(.5+.15*drive)
                Route.REED -> tanh(drive*(source+.15))/norm
                else -> source
            }
            svf.process(input.toFloat(),fc,1.2f)
            val grit = when (recipe.route) {
                Route.A -> svf.low.toDouble()
                Route.B, Route.PULSE -> tanh(drive*svf.low)/norm
                Route.REED -> tanh(drive*(.35*svf.low + .65*svf.band))/norm
                Route.HOLLOW -> tanh((.6+.2*drive)*svf.low)/tanh(.6+.2*drive)
                Route.WIRE -> tanh((.5+.2*drive)*svf.low)/tanh(.5+.2*drive)
                Route.ROUND, Route.FOLD -> svf.low.toDouble()
            }
            val edge = fuzz?.process(grit) ?: grit
            val attack = .5-.5*cos(PI*min(t/attackSeconds,1.0))
            val release = if (heldFrames != null) 1.0 else .5+.5*cos(PI*((t-gate)/.045).coerceIn(0.0,1.0))
            // Soft touch leans on the clean fundamental. Cutoff alone barely changed
            // the default tone (only 4% centroid reduction in the first JVM probe).
            val edgeLevel = .6 * (.25 + .75*touch)
            out[i] = ((edgeLevel*edge + .9*m.getValue("WEIGHT")*sine)*attack*release*exp(-(if (heldFrames == null) t else min(t, gate))/.95)).toFloat()
        }
        return out
    }

    private fun renderNote(recipe: Recipe): Snip = finish(synthesize(recipe), recipe.velocity, fade = true, effects = recipe.effects)

    /** The held maker cuts after conditioning, before the tail fade used by finite notes. */
    internal fun finish(raw: FloatArray, velocity: Float, fade: Boolean,
                        effects: BristleEffects.Settings = BristleEffects.Settings()): Snip {
        Tide.bandLimit(raw, Dsp.RATE * Dsp.OVERSAMPLE)
        val out = Dsp.decimate(raw, Dsp.RATE)
        val mean = out.average().toFloat()
        val hp = Dsp.FixedOnePole(20f)
        for (i in out.indices) { val x=out[i]-mean; out[i]=x-hp.lp(x) }
        Dsp.levelTo(out,Dsp.RATE,Dsp.MELODIC_LOUDNESS_TARGET)
        // Apply performance gain after leveling, or normalization erases dynamics.
        for (i in out.indices) out[i] *= velocity
        val treated = BristleEffects.compress(Snip(out,1,Dsp.RATE), effects.compress)
        if (fade) Dsp.fadeTail(treated.samples)
        return treated
    }
}
