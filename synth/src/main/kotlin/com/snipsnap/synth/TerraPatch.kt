package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A saved TERRA sound: voice (topology), macro settings and a hand-written
 * name, the same shape as every other engine's patch (see [Patches]). It may
 * also carry a [Striker]: another pad's captured hit, and how hard it
 * strikes this drum (HIT).
 *
 * The striker is data inside the recipe, never a pointer to a pad. A struck
 * pad therefore regenerates bit for bit from `kit.json` however its source
 * pad changes later. This is [ForkPatch.striker]'s precedent
 * (docs/superpowers/specs/2026-09-30-terra-hit-bend-talk-design.md, "Data
 * flow and compatibility").
 *
 * Not a data class: an array member would compare by identity there. A
 * plain patch writes `"version": 1` and exactly the bytes TERRA has always
 * written. A struck one writes `"version": [DRIVEN_VERSION]`. Every older
 * build refuses that by name, rather than playing plain TERRA and dropping
 * the striker on re-save.
 *
 * It may instead carry an [articulation]: a stick voice ([TerraSticks]) played
 * as an authored roll or riser ([TerraRolls]), or its hit left to ring ([Terra.renderRing]), rather than one hit. A plain hit
 * writes nothing new. A roll or riser writes `"version": [ARTICULATED_VERSION]`
 * and its articulation, so an older build refuses it by name rather than
 * playing one hit and dropping the roll on re-save. A pad is struck or
 * articulated, never both.
 */
class TerraPatch(
    override val name: String,
    val voice: TerraVoice,
    override val macros: Map<String, Float>,
    val striker: Striker? = null,
    val articulation: TerraArticulation = TerraArticulation.HIT,
    /** A roll's or riser's tempo, when it is locked to one ([TerraRolls]); null is the voice's own free rate. */
    val bpm: Int? = null,
) : Patch {

    /**
     * Another pad's hit:
     * - [head] is [Fork.STRIKER_SAMPLES] samples at [Dsp.RATE], every one
     *   finite (what `Terra.captureStriker` returns). It is copied in and
     *   out.
     * - [hit] is how much of it strikes this drum: 0 is today, 0.5 subtle,
     *   1 strong.
     * - [from] is the source pad's label as the chooser showed it. It is
     *   display only, because rendering never reads it, but it is part of
     *   the value: two patches that differ only in it are different
     *   recipes.
     */
    class Striker(head: FloatArray, val hit: Float, val from: String? = null) {
        private val samples: FloatArray = head.copyOf()

        /** A copy: the striker cannot be changed through it. */
        val head: FloatArray get() = samples.copyOf()

        init {
            require(samples.size == Fork.STRIKER_SAMPLES) { "a TERRA striker has ${Fork.STRIKER_SAMPLES} samples, got ${samples.size}" }
            for ((i, v) in samples.withIndex()) require(v.isFinite()) { "striker head[$i] is not finite: $v" }
            require(hit in 0f..1f) { "HIT is 0..1, got $hit" }
            require(from == null || TerraPatch.isLabel(from)) {
                "a striker's from is at most ${TerraPatch.MAX_FROM_CHARS} characters with no control characters: '$from'"
            }
        }

        fun copy(head: FloatArray = samples, hit: Float = this.hit, from: String? = this.from): Striker = Striker(head, hit, from)

        override fun equals(other: Any?): Boolean =
            other is Striker && hit == other.hit && from == other.from && samples.contentEquals(other.samples)

        // + 0f folds -0 into 0, which equals already treats as the same HIT.
        override fun hashCode(): Int = (samples.contentHashCode() * 31 + (hit + 0f).hashCode()) * 31 + (from?.hashCode() ?: 0)

        override fun toString(): String = "Striker(hit=$hit, from=$from, head=[${samples.size} samples])"
    }

    init {
        Patches.validateMacros(this, Terra.macrosFor(voice))
        require(articulation == TerraArticulation.HIT || voice in TerraSticks.VOICES) { "only TERRA's stick voices roll: $voice is a hit" }
        require(articulation == TerraArticulation.HIT || striker == null) { "a TERRA pad is struck by another pad or articulated, not both" }
        require(bpm == null || (articulation == TerraArticulation.ROLL || articulation == TerraArticulation.RISER)) { "only a roll or a riser follows a tempo" }
        require(bpm == null || bpm in MIN_BPM..MAX_BPM) { "a TERRA roll's tempo is $MIN_BPM to $MAX_BPM BPM, got $bpm" }
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = render(1f)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    /**
     * This pad at [velocity] as take [take], with [macros] in place of its own (a re-strike moves POS): the one
     * place a TERRA pad is rendered, so its striker or its roll is never dropped. Take 0 at full velocity is [render].
     */
    fun render(velocity: Float, take: Int = 0, macros: Map<String, Float> = this.macros): com.snipsnap.audio.Snip =
        when (articulation) {
            TerraArticulation.HIT -> Terra.render(voice, macros, striker, velocity, take)
            TerraArticulation.RING -> Terra.renderRing(voice, macros, velocity, take)
            TerraArticulation.ROLL, TerraArticulation.RISER -> TerraRolls.render(voice, macros, articulation, velocity, take, bpm)
        }

    fun copy(
        name: String = this.name,
        voice: TerraVoice = this.voice,
        macros: Map<String, Float> = this.macros,
        striker: Striker? = this.striker,
        articulation: TerraArticulation = this.articulation,
        bpm: Int? = this.bpm,
    ): TerraPatch = TerraPatch(name, voice, macros, striker, articulation, bpm)

    override fun toJsonValue(): JsonValue.Obj {
        val base = Patches.toJsonValue(this)
        if (articulation != TerraArticulation.HIT) {
            val obj = LinkedHashMap(base.entries)
            obj["version"] = JsonValue.Num(ARTICULATED_VERSION.toDouble())
            obj["articulation"] = JsonValue.Str(articulation.name)
            bpm?.let { obj["bpm"] = JsonValue.Num(it.toDouble()) }
            return JsonValue.Obj(obj)
        }
        val s = striker ?: return base
        val obj = LinkedHashMap(base.entries)
        obj["version"] = JsonValue.Num(DRIVEN_VERSION.toDouble())
        val st = LinkedHashMap<String, JsonValue>()
        s.from?.let { st["from"] = JsonValue.Str(it) }
        st["hit"] = JsonValue.Num(s.hit.toDouble())
        st["head"] = JsonValue.Arr(s.head.map { JsonValue.Num(it.toDouble()) })
        obj["striker"] = JsonValue.Obj(st)
        return JsonValue.Obj(obj)
    }

    override fun equals(other: Any?): Boolean =
        other is TerraPatch && name == other.name && voice == other.voice && macros == other.macros && striker == other.striker &&
            articulation == other.articulation && bpm == other.bpm

    override fun hashCode(): Int =
        ((((name.hashCode() * 31 + voice.hashCode()) * 31 + macros.hashCode()) * 31 + (striker?.hashCode() ?: 0)) * 31 + articulation.hashCode()) * 31 + (bpm ?: 0)

    override fun toString(): String =
        "TerraPatch(name=$name, voice=$voice, macros=$macros" + (striker?.let { ", striker=$it" } ?: "") +
            (if (articulation == TerraArticulation.HIT) "" else ", articulation=$articulation") + (bpm?.let { ", bpm=$it" } ?: "") + ")"

    companion object {
        const val ENGINE = "TERRA"

        /** The version a struck patch writes. A plain one writes [Patches.VERSION] and today's bytes. */
        const val DRIVEN_VERSION = 2

        /** The version a rolled or risen patch writes ([articulation]); an older build refuses it by name. */
        const val ARTICULATED_VERSION = 3

        /** The tempi a roll or riser may lock to. */
        const val MIN_BPM = 40
        const val MAX_BPM = 240

        /** The longest source label a striker keeps; the chooser cuts a longer one before it is stored. */
        const val MAX_FROM_CHARS = 24

        internal fun isLabel(s: String): Boolean = s.length <= MAX_FROM_CHARS && s.none { it.isISOControl() }

        /**
         * The version rule: version 1 without a striker, version
         * [DRIVEN_VERSION] with one, and nothing else; no writer produces
         * any other combination.
         *
         * Engine, version and the common fields still go through the shared
         * [Patches.decode], so a wrong engine or a later version names
         * itself first. A version-2 object reaches it relabelled version 1,
         * once its striker has been read.
         *
         * An explicit `"striker": null` reads as no striker, as SNAP's and
         * FORK's optional fields do.
         */
        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val strikerField = obj["striker"]?.takeUnless { it is JsonValue.Null }
            val articulationField = obj["articulation"]?.takeUnless { it is JsonValue.Null }
            val version = obj["version"]
            if (obj["engine"] == JsonValue.Str(ENGINE) && version is JsonValue.Num && version.value == ARTICULATED_VERSION.toDouble()) {
                if (strikerField != null) throw JsonException("a version-$ARTICULATED_VERSION TERRA patch is articulated, not struck")
                val name = (articulationField as? JsonValue.Str)?.value ?: throw JsonException("a version-$ARTICULATED_VERSION TERRA patch names no articulation")
                val articulation = TerraArticulation.entries.firstOrNull { it.name == name && it != TerraArticulation.HIT }
                    ?: throw JsonException("unknown TERRA articulation $name")
                val common = LinkedHashMap(obj)
                common["version"] = JsonValue.Num(Patches.VERSION.toDouble())
                return Patches.decode(JsonValue.Obj(common), ENGINE, { n -> voiceOf(n) }) { patchName, voice, macros ->
                    if (voice !in TerraSticks.VOICES) throw JsonException("only TERRA's stick voices roll: $voice is a hit")
                    val bpm = when (val b = obj["bpm"]) {
                        null, JsonValue.Null -> null
                        is JsonValue.Num -> b.int().also { if (it !in MIN_BPM..MAX_BPM || (articulation != TerraArticulation.ROLL && articulation != TerraArticulation.RISER)) throw JsonException("a TERRA $articulation cannot follow $it BPM") }
                        else -> throw JsonException("a TERRA roll's bpm is not a number")
                    }
                    TerraPatch(patchName, voice, macros, articulation = articulation, bpm = bpm)
                }
            }
            if (articulationField != null) throw JsonException("a TERRA articulation needs version $ARTICULATED_VERSION")
            if (obj["engine"] == JsonValue.Str(ENGINE) && version is JsonValue.Num && version.value == DRIVEN_VERSION.toDouble()) {
                if (strikerField == null) throw JsonException("a version-$DRIVEN_VERSION TERRA patch carries no driver")
                val striker = readStriker(strikerField)
                val common = LinkedHashMap(obj)
                common["version"] = JsonValue.Num(Patches.VERSION.toDouble())
                return Patches.decode(JsonValue.Obj(common), ENGINE, { n -> voiceOf(n) }) { name, voice, macros -> TerraPatch(name, voice, macros, striker) }
            }
            return Patches.decode(value, ENGINE, { n -> voiceOf(n) }) { name, voice, macros ->
                if (strikerField != null) throw JsonException("a version-${Patches.VERSION} TERRA patch carries no striker; a struck one is version $DRIVEN_VERSION")
                TerraPatch(name, voice, macros)
            }
        }

        fun fromJsonText(text: String): TerraPatch = fromJsonValue(Json.parse(text)) as TerraPatch

        private fun voiceOf(name: String): TerraVoice? = TerraVoice.entries.firstOrNull { it.name == name }

        /** The striker object, every rule of [Striker]'s `init` checked first so a bad file is a [JsonException], never a bare [IllegalArgumentException]. */
        private fun readStriker(raw: JsonValue): Striker {
            val o = (raw as? JsonValue.Obj)?.entries ?: throw JsonException("a TERRA striker is not an object")
            val points = (o["head"] ?: throw JsonException("a TERRA striker has no head")).arr()
            if (points.size != Fork.STRIKER_SAMPLES) throw JsonException("TERRA striker head has ${points.size} samples, not ${Fork.STRIKER_SAMPLES}")
            val head = FloatArray(points.size) { i ->
                val v = points[i].num().toFloat()
                if (!v.isFinite()) throw JsonException("TERRA striker head[$i] is not finite: $v")
                v
            }
            val hit = (o["hit"] ?: throw JsonException("a TERRA striker has no hit")).num().toFloat()
            if (!(hit in 0f..1f)) throw JsonException("TERRA striker hit is not 0..1: $hit")
            val from = when (val f = o["from"]) {
                null, JsonValue.Null -> null
                is JsonValue.Str -> {
                    if (!isLabel(f.value)) throw JsonException("TERRA striker from is over $MAX_FROM_CHARS characters or holds a control character")
                    f.value
                }
                else -> throw JsonException("TERRA striker from is not a string")
            }
            return Striker(head, hit, from)
        }
    }
}
