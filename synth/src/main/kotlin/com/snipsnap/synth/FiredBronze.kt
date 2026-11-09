package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.math.*

/**
 * FIRED BRONZE: a finite kit study, not a registered factory engine.
 * Modal tables are authored audition shapes, NOT measured traditional instruments.
 * See docs/superpowers/specs/2026-11-07-fired-bronze-design.md for research and limits.
 */
object FiredBronze {
    const val VERSION = 1
    enum class Part(val label: String, val drumClass: DrumClass, val hz: Float, val gain: Float) {
        KICK("Clay Kick", DrumClass.KICK, 55f, .85f),
        SNARE("Palm Snare", DrumClass.SNARE, 180f, .65f),
        CLOSED_HAT("Closed Bronze", DrumClass.HAT_CLOSED, 4000f, .25f),
        OPEN_HAT("Open Bronze", DrumClass.HAT_OPEN, 4000f, .25f),
        DAYAN_OPEN("Open Skin", DrumClass.PERC, 220f, .50f),
        DAYAN_DAMPED("Stopped Skin", DrumClass.PERC, 220f, .45f),
        BAYAN("Pressure Bass", DrumClass.PERC, 95f, .65f),
        DHOLAK("Barrel Slap", DrumClass.PERC, 290f, .50f),
        KENDHANG_LOW("Low Hand", DrumClass.PERC, 145f, .55f),
        KENDHANG_HIGH("High Hand", DrumClass.PERC, 340f, .45f),
        GHATAM("Clay Pop", DrumClass.PERC, 390f, .45f),
        BAMBOO("Slit Knock", DrumClass.PERC, 650f, .40f),
        BONANG_LOW("Low Kettle", DrumClass.PERC, 330f, .40f),
        BONANG_HIGH("High Kettle", DrumClass.PERC, 440f, .36f),
        KEMPUL("Dark Gong", DrumClass.PERC, 110f, .45f),
        CHHING("Cup Choke", DrumClass.PERC, 2400f, .25f),
    }
    enum class Stage { DRY, PROCESSOR, AMP }

    val MACROS = listOf(MacroSpec("TUNE", .5f), MacroSpec("TOUCH", .45f),
        MacroSpec("TIGHT", .6f), MacroSpec("PRESS", .35f),
        MacroSpec("TRAIL", .25f), MacroSpec("HEAT", .35f))
    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }
    fun settled(macros: Map<String, Float>): Map<String, Float> = MACROS.associate {
        it.name to (macros[it.name]?.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: it.default)
    }

    /** A study recipe is replayed here, never masquerades as a registered PadRecipe. */
    data class Recipe(val part: Part, val macros: Map<String, Float> = defaults(),
                      val velocity: Float = 1f, val stage: Stage = Stage.AMP,
                      val bpm: Float = 96f, val take: Int = 0) {
        init {
            require(velocity.isFinite() && velocity in 0f..1f)
            require(bpm.isFinite() && bpm in 60f..180f)
            require(macros.keys.all { it in defaults() })
            require(macros.values.all { it.isFinite() && it in 0f..1f })
        }
        fun render(): Snip = FiredBronze.render(this)
        fun toJsonText(): String = Json.write(JsonValue.Obj(linkedMapOf(
            "study" to JsonValue.Str("FIRED_BRONZE"), "version" to JsonValue.Num(VERSION.toDouble()),
            "part" to JsonValue.Str(part.name), "stage" to JsonValue.Str(stage.name),
            "velocity" to JsonValue.Num(velocity.toDouble()), "bpm" to JsonValue.Num(bpm.toDouble()),
            "take" to JsonValue.Num(take.toDouble()),
            "macros" to JsonValue.Obj(settled(macros).mapValues { JsonValue.Num(it.value.toDouble()) }),
        )))
        companion object {
            fun fromJsonText(text: String): Recipe {
                val o = Json.parse(text).obj()
                require(o.getValue("study").str() == "FIRED_BRONZE")
                require(o.getValue("version").num() == VERSION.toDouble())
                return Recipe(Part.valueOf(o.getValue("part").str()),
                    o.getValue("macros").obj().mapValues { it.value.num().toFloat() },
                    o.getValue("velocity").num().toFloat(), Stage.valueOf(o.getValue("stage").str()),
                    o.getValue("bpm").num().toFloat(), o.getValue("take").int())
            }
        }
    }

    fun render(recipe: Recipe): Snip {
        val m = settled(recipe.macros)
        val dry = source(recipe.part, m, recipe.velocity, recipe.take)
        val result = when (recipe.stage) {
            Stage.DRY -> dry
            Stage.PROCESSOR -> FiredBronzeTreatment.processor(dry, recipe.part, m, recipe.bpm)
            Stage.AMP -> FiredBronzeTreatment.process(dry, recipe.part, m, recipe.bpm)
        }
        // Physical/timbral velocity precedes processing; this final gain preserves
        // dynamics despite the existing rack sections' input-relative leveling.
        return result.copy(samples = FloatArray(result.samples.size) { result.samples[it] * recipe.velocity })
    }

    private fun source(part: Part, m: Map<String, Float>, velocity: Float, take: Int): Snip {
        val touch = m.getValue("TOUCH") * (.25f + .75f * velocity)
        val tune = m.getValue("TUNE")
        val tight = m.getValue("TIGHT")
        val skin = when (part) {
            Part.KICK -> SkinVoice.KICK
            Part.SNARE -> SkinVoice.SNARE
            Part.CLOSED_HAT -> SkinVoice.HAT_CLOSED
            Part.OPEN_HAT -> SkinVoice.HAT_OPEN
            else -> null
        }
        if (skin != null) {
            val changes = mutableMapOf("TUNE" to (.35f + (tune - .5f) * .4f).coerceIn(0f, 1f),
                "DECAY" to (.12f + .4f * (1f - tight)), "PUNCH" to touch)
            when (skin) {
                SkinVoice.KICK -> { changes["CLICK"] = .25f; changes["DROP"] = .12f }
                SkinVoice.SNARE -> { changes["SNAP"] = .55f; changes["RATTLE"] = .35f; changes["SIZZLE"] = .15f }
                else -> { changes["RING"] = .48f; changes["TONE"] = .35f + .4f * touch }
            }
            val original = Skin.render(skin, Skin.defaults(skin) + changes, velocity, take)
            // The regional attack accents are explicit hybrid layers, not changes to SKIN.
            val x = original.samples.copyOf()
            if (part == Part.KICK || part == Part.SNARE) {
                val accentPart = if (part == Part.KICK) Part.GHATAM else Part.DHOLAK
                val accent = modal(accentPart, tune, touch, 1f, take)
                for (i in 0 until minOf(x.size, accent.samples.size)) x[i] += .09f * accent.samples[i]
            }
            // Mixing two individually finished sources can reintroduce a finite
            // mean. Condition the actual combined hit before its final level.
            condition(x)
            Dsp.levelTo(x, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
            Dsp.fadeTail(x)
            return original.copy(samples = x)
        }
        return modal(part, tune, touch, tight, take)
    }

    /** No modal table below is an empirical instrument fingerprint. */
    private fun modes(part: Part): List<Modes.Mode> {
        fun bank(vararg rows: Triple<Float, Float, Float>) = rows.map { Modes.Mode(it.first, it.second, it.third) }
        return when (part) {
            Part.DAYAN_OPEN -> bank(Triple(1f,1f,.70f), Triple(2f,.65f,.55f), Triple(3.01f,.45f,.38f),
                Triple(4.02f,.28f,.25f), Triple(5.04f,.17f,.18f))
            // Contact suppresses the root and damps each mode differently. It is
            // deliberately not the open stroke multiplied by a shorter envelope.
            Part.DAYAN_DAMPED -> bank(Triple(1f,.12f,.055f), Triple(2f,.7f,.085f), Triple(3.01f,1f,.045f),
                Triple(4.02f,.65f,.035f), Triple(5.04f,.4f,.025f))
            Part.BAYAN -> bank(Triple(1f,1f,.75f), Triple(1.98f,.45f,.4f), Triple(3.03f,.22f,.25f))
            Part.DHOLAK -> bank(Triple(1f,.55f,.23f), Triple(1.59f,1f,.16f), Triple(2.14f,.65f,.09f), Triple(2.65f,.3f,.055f))
            Part.KENDHANG_LOW -> bank(Triple(1f,1f,.55f), Triple(1.48f,.5f,.3f), Triple(2.07f,.35f,.17f), Triple(2.61f,.15f,.1f))
            Part.KENDHANG_HIGH -> bank(Triple(1f,.35f,.11f), Triple(1.62f,1f,.075f), Triple(2.22f,.6f,.045f), Triple(3.1f,.4f,.025f))
            Part.GHATAM -> bank(Triple(.48f,.6f,.22f), Triple(1f,1f,.16f), Triple(1.73f,.7f,.09f), Triple(2.89f,.4f,.06f), Triple(4.2f,.22f,.04f))
            Part.BAMBOO -> bank(Triple(1f,1f,.16f), Triple(1.46f,.6f,.095f), Triple(2.8f,.18f,.04f))
            Part.BONANG_LOW, Part.BONANG_HIGH -> bank(Triple(1f,1f,1.1f), Triple(2.04f,.4f,.7f),
                Triple(2.76f,.22f,.45f), Triple(4.18f,.13f,.25f), Triple(5.43f,.07f,.17f))
            Part.KEMPUL -> bank(Triple(1f,1f,1.5f), Triple(2.02f,.55f,1.1f), Triple(2.81f,.23f,.65f),
                Triple(4.09f,.15f,.4f), Triple(5.33f,.08f,.2f))
            Part.CHHING -> bank(Triple(1f,1f,.12f), Triple(1.47f,.7f,.085f), Triple(2.08f,.45f,.06f), Triple(2.79f,.3f,.04f))
            else -> error("SKIN owns $part")
        }
    }

    internal fun pressureRatio(seconds: Double, touch: Float): Double {
        // Stored press/release gesture: no pitch jump at onset and no claim of
        // solving an eccentrically loaded membrane. Raised cosine joins are smooth.
        val shape = when {
            seconds < .06 -> 0.0
            seconds < .18 -> .5 - .5 * cos(PI * (seconds - .06) / .12)
            seconds < .25 -> 1.0
            seconds < .55 -> .5 + .5 * cos(PI * (seconds - .25) / .30)
            else -> 0.0
        }
        return 2.0.pow((1.0 + 5.0 * touch) * shape / 12.0)
    }

    private fun modal(part: Part, tune: Float, touch: Float, tight: Float, take: Int): Snip {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val seconds = if (part == Part.KEMPUL) 1.35 else 1.0
        val n = (seconds * rate).toInt()
        val hz = part.hz * 2.0.pow((tune - .5) * 2).toFloat()
        val decayScale = 1.35f - .9f * tight
        val table = modes(part).mapIndexed { i, mode ->
            mode.copy(gain = mode.gain * (1f + touch * i * .45f), t60 = mode.t60 * decayScale)
        }.filter { it.ratio * hz * 1.5f < 18_000f }
        val noise = Dsp.Noise(Dsp.seedFor("FIRED_BRONZE", VERSION, part.name, take))
        val contact = ((.0018 - .0013 * touch) * rate).toInt().coerceAtLeast(8)
        val excitation = FloatArray(n)
        for (i in 0 until contact) excitation[i] =
            ((.5 - .5 * cos(2 * PI * i / (contact - 1))) * (1 + .08 * noise.next())).toFloat()
        val energy = excitation.sum().coerceAtLeast(1e-9f)
        for (i in 0 until contact) excitation[i] /= energy
        Tide.bandLimit(excitation, rate)
        val sumGain = table.sumOf { abs(it.gain).toDouble() }.toFloat()
        val raw = if (part == Part.BAYAN) {
            val out = FloatArray(n)
            for (mode in table) {
                var phase = 0.0
                var envelope = 1.0
                val loss = exp(-6.9078 / (mode.t60 * rate))
                for (i in out.indices) {
                    val t = i.toDouble() / rate
                    phase += 2 * PI * hz * mode.ratio * pressureRatio(t, touch) / rate
                    val attack = if (i < contact) .5 - .5 * cos(PI * i / contact) else 1.0
                    out[i] += (mode.gain / sumGain * envelope * attack * sin(phase)).toFloat()
                    envelope *= loss
                }
            }
            out
        } else {
            // Cancel the resonator's 1/sin(theta) impulse gain explicitly, so
            // pitch/rate do not accidentally change the source energy budget.
            Modes.ring(excitation, hz, table.map {
                it.copy(gain = it.gain / sumGain * sin(2 * PI * hz * it.ratio / rate).toFloat())
            }, rate)
        }
        Tide.bandLimit(raw, rate)
        val out = Dsp.decimate(raw, Dsp.RATE)
        condition(out)
        Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return Snip(out, 1, Dsp.RATE)
    }

    internal fun condition(out: FloatArray) {
        val mean = out.average().toFloat()
        val hp = Dsp.OnePole(Dsp.RATE)
        for (i in out.indices) {
            val x = out[i] - mean
            out[i] = x - hp.lp(x, 20f)
        }
    }
}
