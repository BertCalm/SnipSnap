package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import kotlin.math.*

/** PATIO's deliverable percussion family: authored velocity/contact takes, not post-render detuning. */
object PatioPerformance {
    const val VERSION = 3
    const val TAKES = 4
    val VELOCITIES = listOf(.35f, .65f, 1f)
    enum class Genre(val id: String, val title: String) {
        CUMBIA("patio-cumbia", "Patio Cumbia"), SAMBA("patio-samba", "Patio Samba"), DUB("patio-dub", "Patio Latin Dub")
    }
    val IDS get() = PatioStudy.recipes().map { it.id } + listOf("hand-roll", "cuica")
    data class Recipe(val id: String, val genre: Genre, val hold: Float = .5f, val version: Int = VERSION) {
        init { require(version in 1..VERSION && (id in IDS || id == "rise") && (id != "cuica" || version >= 3) && hold.isFinite() && hold in 0f..1f) }
        fun json(): String = Json.write(JsonValue.Obj(mapOf("patioPerformance" to JsonValue.Num(version.toDouble()),
            "id" to JsonValue.Str(id), "genre" to JsonValue.Str(genre.name), "hold" to JsonValue.Num(hold.toDouble()))))
    }
    fun fromJson(text: String): Recipe {
        val o = Json.parse(text).obj()
        require(o.keys == setOf("patioPerformance", "id", "genre", "hold"))
        val version = o.getValue("patioPerformance").num()
        require(version in 1.0..VERSION.toDouble() && version == version.toInt().toDouble())
        val hold = o.getValue("hold").num()
        require(hold.isFinite() && hold in 0.0..1.0)
        return Recipe(o.getValue("id").str(), Genre.valueOf(o.getValue("genre").str()), hold.toFloat(), version.toInt())
    }
    fun treatment(genre: Genre) = when (genre) {
        Genre.CUMBIA -> PatioCleanup.treatment
        Genre.SAMBA -> PatioCleanup.treatment.copy(id = "patio-samba", title = "Patio Samba", wear = .42f, wobble = .25f, drive = .59f, sag = .12f, tone = .62f, cab = .30f, dropout = .10f)
        Genre.DUB -> PatioCleanup.treatment.copy(id = "patio-dub", title = "Patio Latin Dub", wear = .86f, wobble = .85f, drive = .66f, sag = .35f, tone = .40f, cab = .55f, dropout = .65f)
    }
    fun level(id: String, genre: Genre): Float {
        val r = PatioStudy.recipes().firstOrNull { it.id == id } ?: return .48f
        val factor = when (genre) {
            Genre.CUMBIA -> 1f
            Genre.SAMBA -> when (id) { "caixa", "surdo" -> 1.2f; "agogo-low", "agogo-high", "guache" -> 1.35f; "scrape-short", "scrape-long" -> .65f; else -> .9f }
            Genre.DUB -> when (id) { "kick", "cajon", "surdo" -> 1.12f; "closed", "open", "agogo-high", "guache" -> .68f; else -> .85f }
        }
        return (PatioWorkingKit.level(r) * factor).coerceAtMost(1f)
    }

    private fun raw(recipe: Recipe, velocity: Float, take: Int): Snip {
        val r = PatioStudy.recipes().single { it.id == recipe.id }
        if (velocity == 1f && take == 0 && recipe.genre == Genre.CUMBIA) return PatioHollow.dry(r)
        val seed = Dsp.seedFor("PATIO_PERFORMANCE", 1, recipe.id, recipe.genre, take)
        val offset = if (take == 0) 0f else floatArrayOf(0f, -.7f, .45f, .9f)[take]
        val touch = (.25f + .35f * velocity + offset * .035f).coerceIn(0f, 1f)
        var source = when (r.id) {
            in PatioArticulation.IDS -> PatioArticulation.source(r.id, seed,
                pitchHz = when (r.id) { "llamador" -> 205f; "repicador" -> 270f; "agogo-low" -> 490f; else -> 820f },
                hollowRevision = if (r.id == "agogo-low") 2 else 1,
                touch = velocity, contactOffset = offset)
            "alegre-open", "alegre-slap" -> PatioHand.render(if (r.id.endsWith("open")) PatioHand.Stroke.OPEN else PatioHand.Stroke.SLAP,
                PatioHand.Settings(touch = touch, hold = (.45f + offset * .025f).coerceIn(0f, 1f)), seed, reference = true)
            "scrape-short", "scrape-long" -> PatioScrape.render(if (r.id == "scrape-short") PatioScrape.Direction.RETURN else PatioScrape.Direction.FORWARD,
                touch = touch, hold = .45f + offset * .025f, seed = seed, modelVersion = 3)
            "guache", "marimba" -> PatioCharacter.render(if (r.id == "guache") PatioCharacter.Voice.SEEDS else PatioCharacter.Voice.BUZZ_BAR,
                touch = touch, hold = .45f + offset * .025f, seed = seed)
            else -> {
                var patch = if (r.id in PatioCore.IDS) PatioCore.patch(r) else requireNotNull(r.patch)
                val m = patch.macros.toMutableMap()
                if (r.id in setOf("closed", "open")) {
                    m["TONE"] = (.22f + .16f * velocity + offset * .025f).coerceIn(0f, 1f)
                    m["RING"] = (.82f + offset * .05f).coerceIn(0f, 1f)
                    m["DECAY"] = (m.getValue("DECAY") + offset * .018f).coerceIn(0f, 1f)
                }
                if (recipe.genre == Genre.SAMBA) m["DECAY"] = (m.getValue("DECAY") * .75f).coerceIn(0f, 1f)
                if (recipe.genre == Genre.DUB && r.id in setOf("kick", "surdo", "cajon")) m["TUNE"] = (m.getValue("TUNE") - .045f).coerceAtLeast(0f)
                patch = patch.withMacros(m)
                Restrike.take(patch, velocity, seed, take) ?: Velocity.atVelocity(patch, velocity)
            }
        }
        if (r.id in setOf("surdo", "cajon")) source = source.copy(samples = FloatArray(source.samples.size) { i ->
            source.samples[i] * exp(-max(0.0, i.toDouble() / source.sampleRate - .1) / .2).toFloat() })
        return source
    }
    private fun wet(recipe: Recipe, velocity: Float, take: Int): Snip {
        if (recipe.id == "cuica") {
            val fx = treatment(recipe.genre).let { it.copy(drive = minOf(it.drive, .52f),
                wobble = minOf(it.wobble, .30f), dropout = 0f) }
            return PatioCleanup.process(PatioCuica.render(velocity, recipe.hold, take),
                Dsp.seedFor("PATIO_CUICA_FX", 1, recipe.genre, take), fx)
        }
        if (recipe.id in setOf("hand-roll", "rise")) return phrase(recipe, velocity, take)
        if (velocity == 1f && take == 0 && recipe.genre == Genre.CUMBIA)
            return PatioHollow.render(PatioStudy.recipes().single { it.id == recipe.id })
        return PatioCleanup.process(raw(recipe, velocity, take), Dsp.seedFor("PATIO_PERFORMANCE_FX", recipe.id, take), treatment(recipe.genre))
    }
    /** Each zone has its own loudness target; take variation preserves that zone's level. */
    fun grid(recipe: Recipe): List<List<Snip>> {
        val reference = wet(recipe, 1f, 0)
        val target = Loudness.of(reference)
        return VELOCITIES.map { velocity -> (0 until TAKES).map { take ->
            if (velocity == 1f && take == 0) reference else {
                val s = wet(recipe, velocity, take)
                Dsp.levelTo(s.samples, Dsp.RATE, target * velocity.pow(.8f), ceiling = .95f)
                s
            }
        } }
    }
    fun render(recipe: Recipe, zone: Int, take: Int): Snip {
        require(zone in VELOCITIES.indices && take in 0 until TAKES)
        val velocity = VELOCITIES[zone]
        val reference = wet(recipe, 1f, 0)
        if (zone == VELOCITIES.lastIndex && take == 0) return reference
        val s = wet(recipe, velocity, take)
        Dsp.levelTo(s.samples, Dsp.RATE, Loudness.of(reference) * velocity.pow(.8f), ceiling = .95f)
        return s
    }
    /** HOLD chooses finite phrase length; the voices themselves stay one-shots. */
    private fun phrase(recipe: Recipe, velocity: Float, take: Int): Snip {
        if (recipe.id == "rise" && recipe.version >= 2) return clearRise(recipe, velocity, take)
        val rising = recipe.id == "rise"
        val seconds = if (rising) 1.2 + 2.4 * recipe.hold else .6 + 1.2 * recipe.hold
        val out = FloatArray(((seconds + .8) * Dsp.RATE).toInt())
        var time = 0.0; var hit = 0
        while (time < seconds) {
            val phase = (time / seconds).toFloat()
            val id = if (rising) (if (hit % 3 == 0) "guache" else "repicador") else (if (hit % 3 == 0) "alegre-open" else "alegre-slap")
            val v = velocity * (if (rising) .25f + .75f * phase else if (hit % 4 == 0) 1f else .65f)
            val s = wet(Recipe(id, recipe.genre), v, (take + hit) % TAKES)
            val start = (time * Dsp.RATE).toInt()
            s.samples.forEachIndexed { i, x -> if (start + i < out.size) out[start + i] += x * v * .42f }
            time += if (rising) .18 - .14 * phase else .075
            hit++
        }
        return PatioWear.finish(Snip(out, 1, Dsp.RATE))
    }
    /** One drum, four measured subdivisions, then a landing. Short gates prevent
     * ringing bodies accumulating as the strokes get closer. Process the phrase
     * once so the amp responds to the build, rather than stacking processed tails. */
    private fun clearRise(recipe: Recipe, velocity: Float, take: Int): Snip {
        val seconds = 1.2 + 2.4 * recipe.hold
        val out = FloatArray(((seconds + .35) * Dsp.RATE).toInt())
        var hit = 0
        fun strike(time: Double, phase: Float, gate: Double, landing: Boolean = false) {
            val strength = velocity * (if (landing) .95f else .30f + .55f * phase)
            val source = raw(Recipe("repicador", recipe.genre), strength, (take + hit) % TAKES)
            val start = (time * Dsp.RATE).toInt()
            val count = minOf((gate * Dsp.RATE).toInt(), source.frameCount, out.size - start)
            val fade = (Dsp.RATE * .008).toInt()
            for (i in 0 until count) {
                val tail = minOf(1f, (count - 1 - i).toFloat() / fade)
                val damping = exp(-i.toDouble() / Dsp.RATE / (if (landing) .065 else .035)).toFloat()
                out[start + i] += source.samples[i] * damping * tail * strength * .65f
            }
            hit++
        }
        val section = seconds / 4
        for ((stage, pulses) in listOf(2, 3, 4, 6).withIndex()) {
            val step = section / pulses
            for (i in 0 until pulses) {
                val time = stage * section + i * step
                strike(time, (time / seconds).toFloat(), minOf(.12, step * .78))
            }
        }
        strike(seconds, 1f, .22, landing = true)
        val fx = treatment(recipe.genre).let { it.copy(
            drive = minOf(it.drive, .48f), sag = minOf(it.sag, .12f),
            wobble = minOf(it.wobble, .25f), dropout = 0f) }
        return PatioCleanup.process(Snip(out, 1, Dsp.RATE),
            Dsp.seedFor("PATIO_CLEAR_RISE", 2, recipe.genre, take), fx)
    }
    fun build(genre: Genre): List<ArrangedPad> = IDS.map { id ->
        val recipe = Recipe(id, genre)
        val grid = grid(recipe)
        val base = PatioStudy.recipes().firstOrNull { it.id == id }
        ArrangedPad(grid.last().first(), base?.drumClass ?: if (id == "cuica") DrumClass.PERC else DrumClass.LOOP,
            level = level(id, genre), displayName = base?.let { PatioWorkingKit.name(it) } ?: if (id == "cuica") "Cuica Friction Drum" else "Hand Drum Roll",
            muteGroup = if (id in setOf("closed", "open")) 1 else 0,
            source = mapOf("patioPerformanceRecipe" to recipe.json()), velocityTakes = grid)
    }
}
