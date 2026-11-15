package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * Sixteen audition hypotheses, not factory presets. Existing patches retain every source control.
 * The three texture pads use a versioned study recipe until listening earns a permanent landing.
 * Study JSON is intentionally separate from PadRecipe, which cannot yet replay this custom chain.
 */
object PatioStudy {
    const val VERSION = 1
    data class Recipe(
        val id: String,
        val name: String,
        val drumClass: DrumClass,
        val patch: Patch? = null,
        val texture: PatioTexture.Voice? = null,
        val contact: PatioTexture.Settings = PatioTexture.Settings(),
        val treatment: PatioWear.Settings = PatioWear.Settings(),
        val seed: Int = Dsp.seedFor("PATIO", id),
        val mixGain: Float = .6f,
    ) {
        init {
            require((patch == null) != (texture == null)) { "one source per study recipe" }
            require(id.matches(Regex("[a-z][a-z0-9-]*")) && name.isNotBlank())
            require(mixGain.isFinite() && mixGain in 0f..1f)
        }
        fun dry(): Snip = patch?.render() ?: PatioTexture.render(texture!!, contact, seed)
        fun render(): Snip = PatioWear.process(dry(), treatment, seed)
        fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
            "patioStudy" to JsonValue.Num(VERSION.toDouble()),
            "id" to JsonValue.Str(id), "name" to JsonValue.Str(name),
            "drumClass" to JsonValue.Str(drumClass.name), "seed" to JsonValue.Num(seed.toDouble()),
            "mixGain" to JsonValue.Num(mixGain.toDouble()),
            "source" to (patch?.toJsonValue() ?: JsonValue.Str(texture!!.name)),
            "contact" to numbers("tune" to contact.tune, "touch" to contact.touch, "hold" to contact.hold),
            "treatment" to numbers("wear" to treatment.wear, "wobble" to treatment.wobble, "drive" to treatment.drive),
        ))
        fun toJsonText(): String = Json.write(toJsonValue())
    }

    private fun numbers(vararg pairs: Pair<String, Float>) = JsonValue.Obj(
        pairs.associate { it.first to JsonValue.Num(it.second.toDouble()) })

    fun fromJsonText(text: String): Recipe {
        val o = Json.parse(text).obj()
        require(o.keys == setOf("patioStudy", "id", "name", "drumClass", "seed", "mixGain", "source", "contact", "treatment"))
        require(o.getValue("patioStudy").num() == VERSION.toDouble()) { "unsupported PATIO study version" }
        val c = o.getValue("contact").obj()
        val t = o.getValue("treatment").obj()
        require(c.keys == setOf("tune", "touch", "hold") && t.keys == setOf("wear", "wobble", "drive"))
        fun control(map: Map<String, JsonValue>, key: String): Float {
            val value = map.getValue(key).num()
            require(value.isFinite() && value in 0.0..1.0)
            return value.toFloat()
        }
        val source = o.getValue("source")
        val seed = o.getValue("seed").num()
        require(seed.isFinite() && seed in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() && seed == seed.toInt().toDouble())
        return Recipe(o.getValue("id").str(), o.getValue("name").str(),
            DrumClass.valueOf(o.getValue("drumClass").str()),
            patch = if (source is JsonValue.Obj) Patches.fromJsonValue(source) else null,
            texture = if (source is JsonValue.Str) PatioTexture.Voice.valueOf(source.str()) else null,
            contact = PatioTexture.Settings(control(c, "tune"), control(c, "touch"), control(c, "hold")),
            treatment = PatioWear.Settings(control(t, "wear"), control(t, "wobble"), control(t, "drive")),
            seed = seed.toInt(), mixGain = control(o, "mixGain"))
    }

    fun recipes(): List<Recipe> {
        fun skin(id: String, name: String, voice: SkinVoice, cls: DrumClass, gain: Float,
                 wobble: Float = .20f, vararg values: Pair<String, Float>) =
            Recipe(id, name, cls, SkinPatch(name, voice, Skin.defaults(voice) + values.toMap()),
                treatment = PatioWear.Settings(wobble = wobble), mixGain = gain)
        fun terra(id: String, name: String, voice: TerraVoice, gain: Float, vararg values: Pair<String, Float>) =
            Recipe(id, name, DrumClass.PERC, TerraPatch(name, voice, Terra.defaults(voice) + values.toMap()), mixGain = gain)
        return listOf(
            skin("kick", "Courtyard Kick", SkinVoice.KICK, DrumClass.KICK, .85f, .05f,
                "TUNE" to .30f, "DECAY" to .35f, "CLICK" to .18f, "DROP" to .06f),
            skin("caixa", "Wire Snare Study", SkinVoice.SNARE, DrumClass.SNARE, .65f, .12f,
                "TUNE" to .58f, "DECAY" to .28f, "SNAP" to .64f, "RATTLE" to .40f, "SIZZLE" to .18f),
            skin("closed", "Closed Metal", SkinVoice.HAT_CLOSED, DrumClass.HAT_CLOSED, .23f, .15f,
                "TONE" to .48f, "DECAY" to .18f, "RING" to .55f),
            skin("open", "Loose Metal", SkinVoice.HAT_OPEN, DrumClass.HAT_OPEN, .25f, .22f,
                "TONE" to .48f, "DECAY" to .42f, "RING" to .55f),
            terra("surdo", "Surdo Answer Study", TerraVoice.COMPOUND_MEMBRANE, .70f,
                "TUNE" to .14f, "DECAY" to .46f, "FORCE" to .20f, "POS" to .08f, "DROOP" to .07f),
            terra("alegre-open", "Alegre Open Study", TerraVoice.COMPOUND_MEMBRANE, .50f,
                "TUNE" to .52f, "DECAY" to .42f, "FORCE" to .42f, "POS" to .25f, "DROOP" to .06f),
            terra("alegre-slap", "Alegre Slap Study", TerraVoice.COMPOUND_MEMBRANE, .48f,
                "TUNE" to .52f, "DECAY" to .12f, "FORCE" to .90f, "POS" to .88f, "DROOP" to .06f),
            terra("llamador", "Llamador Study", TerraVoice.COMPOUND_MEMBRANE, .37f,
                "TUNE" to .64f, "DECAY" to .13f, "FORCE" to .50f, "POS" to .38f, "DROOP" to .03f),
            terra("repicador", "Repicador Study", TerraVoice.COMPOUND_MEMBRANE, .47f,
                "TUNE" to .76f, "DECAY" to .21f, "FORCE" to .70f, "POS" to .66f, "DROOP" to .04f),
            terra("cajon", "Cajon Bass", TerraVoice.BOX, .63f,
                "TUNE" to .35f, "DECAY" to .40f, "FORCE" to .40f, "POS" to .32f, "CAVITY" to .65f, "BUZZ" to 0f),
            Recipe("scrape-short", "Guacharaca Short Study", DrumClass.PERC,
                texture = PatioTexture.Voice.SCRAPE, contact = PatioTexture.Settings(.42f, .58f, .12f), mixGain = .29f),
            Recipe("scrape-long", "Guacharaca Long Study", DrumClass.PERC,
                texture = PatioTexture.Voice.SCRAPE, contact = PatioTexture.Settings(.42f, .58f, .70f), mixGain = .29f),
            Recipe("guache", "Guache Study", DrumClass.PERC,
                texture = PatioTexture.Voice.SEEDS, contact = PatioTexture.Settings(.55f, .56f, .38f), mixGain = .24f),
            terra("agogo-low", "Agogo Low Study", TerraVoice.CONICAL_BELL, .37f,
                "TUNE" to .25f, "DECAY" to .38f, "FORCE" to .70f, "POS" to .35f),
            terra("agogo-high", "Agogo High Study", TerraVoice.CONICAL_BELL, .32f,
                "TUNE" to .78f, "DECAY" to .28f, "FORCE" to .82f, "POS" to .52f),
            terra("marimba", "Marimba Knock Study", TerraVoice.TUNED_BAR, .48f,
                "TUNE" to .38f, "DECAY" to .42f, "FORCE" to .40f, "POS" to .30f, "BUZZ" to .28f),
        )
    }
}
