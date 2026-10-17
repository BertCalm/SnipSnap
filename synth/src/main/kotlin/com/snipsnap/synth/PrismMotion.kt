package com.snipsnap.synth

import com.snipsnap.json.JsonValue
import java.util.Collections

/** Finite-note spectral motion. B replaces template amplitudes, never its carrier ratios. */
class PrismMotion(
    amplitudesB: List<Float>,
    val morph: Float = 0f,
    val evolve: Float = 0f,
    val seconds: Float = 1.5f,
    val groupLag: Float = 0f,
) {
    val amplitudesB: List<Float> = Collections.unmodifiableList(amplitudesB.toList())

    init {
        require(amplitudesB.size == Prism.PARTIALS && amplitudesB.all { it.isFinite() && it in 0f..2f }) {
            "PRISM motion B requires 32 finite amplitudes in 0..2"
        }
        require(amplitudesB.first() >= .05f) { "PRISM motion B must retain a root amplitude of at least .05" }
        require(morph.isFinite() && morph in 0f..1f) { "PRISM motion morph must be 0..1" }
        require(evolve.isFinite() && evolve in 0f..1f) { "PRISM motion evolve must be 0..1" }
        require(seconds.isFinite() && seconds in .1f..6f) { "PRISM motion seconds must be .1..6" }
        require(groupLag.isFinite() && groupLag in 0f..1f) { "PRISM motion groupLag must be 0..1" }
    }

    /** Upper groups start later, by at most groupLag * seconds; all groups share the same carriers. */
    internal fun positionAt(timeSeconds: Double, group: Int): Double {
        val delay = groupLag.toDouble() * seconds * group / (Prism.GROUPS - 1)
        val x = ((timeSeconds - delay) / seconds).coerceIn(0.0, 1.0)
        val eased = x * x * (3.0 - 2.0 * x)
        return morph + (1.0 - morph) * evolve * eased
    }

    fun copy(
        amplitudesB: List<Float> = this.amplitudesB,
        morph: Float = this.morph,
        evolve: Float = this.evolve,
        seconds: Float = this.seconds,
        groupLag: Float = this.groupLag,
    ) = PrismMotion(amplitudesB, morph, evolve, seconds, groupLag)

    override fun equals(other: Any?): Boolean = other is PrismMotion && amplitudesB == other.amplitudesB &&
        morph == other.morph && evolve == other.evolve && seconds == other.seconds && groupLag == other.groupLag

    override fun hashCode(): Int = listOf(amplitudesB, morph, evolve, seconds, groupLag).hashCode()
    override fun toString(): String = "PrismMotion($amplitudesB,$morph,$evolve,$seconds,$groupLag)"

    internal fun toJsonValue() = JsonValue.Obj(linkedMapOf(
        "amplitudesB" to JsonValue.Arr(amplitudesB.map { JsonValue.Num(it.toDouble()) }),
        "morph" to JsonValue.Num(morph.toDouble()),
        "evolve" to JsonValue.Num(evolve.toDouble()),
        "seconds" to JsonValue.Num(seconds.toDouble()),
        "groupLag" to JsonValue.Num(groupLag.toDouble()),
    ))

    companion object {
        internal fun fromJsonValue(value: JsonValue): PrismMotion {
            val obj = value.obj()
            fun number(name: String, default: Float) = obj[name]?.num()?.toFloat() ?: default
            return PrismMotion(
                amplitudesB = obj.getValue("amplitudesB").arr().map { it.num().toFloat() },
                morph = number("morph", 0f),
                evolve = number("evolve", 0f),
                seconds = number("seconds", 1.5f),
                groupLag = number("groupLag", 0f),
            )
        }
    }
}

/** Original amplitude colours; GLASS changes spectral balance, not the source's tuning. */
enum class PrismMotionColor { VOWEL, REED, GLASS }
