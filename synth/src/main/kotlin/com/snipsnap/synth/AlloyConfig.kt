package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** Original synthesis recipe, stored with a patch. Ratios are multiples of the played note,
 * losses/decays are seconds, and routing depths and FUSE are phase offsets in radians.
 * There are four operators; routing runs from a higher numbered operator to a lower one,
 * so the graph is finite and has no implicit sample-delay feedback. */
data class AlloyConfig(
    val operators: List<Operator>,
    val routing: List<Route>,
    val wood: Projection,
    val tine: Projection,
    val breath: Projection,
    val envelope: Envelope,
    val held: Held,
    val fuseRadians: Float = 2.4f,
    val cutoffRatio: Float = 14f,
) {
    data class Operator(val ratio: Float, val gain: Float, val decay: Float, val sustain: Float) {
        init { bounded(ratio, .5f, 16f); bounded(gain, 0f, 1f); bounded(decay, .02f, 4f); bounded(sustain, 0f, 1f) }
    }
    data class Route(val from: Int, val to: Int, val radians: Float) {
        init { require(from in 1..3 && to in 0 until from); bounded(radians, 0f, 3f) }
    }
    data class Projection(val ratios: List<Float>, val losses: List<Float>, val gains: List<Float>) {
        init {
            require(ratios.size in 1..8 && losses.size == ratios.size && gains.size == ratios.size)
            ratios.forEach { bounded(it, .5f, 20f) }; losses.forEach { bounded(it, .008f, 2f) }
            gains.forEach { bounded(it, 0f, 1f) }; require(gains.sum() in .01f..2f)
        }
    }
    data class Envelope(val attackSeconds: Float, val finiteSeconds: Float, val releaseSeconds: Float, val sustain: Float) {
        init { bounded(attackSeconds, .0005f, .3f); bounded(finiteSeconds, .3f, 5f); bounded(releaseSeconds, .04f, 1f); bounded(sustain, .05f, 1f) }
    }
    data class Held(val periodSeconds: Float, val drive: Float, val roughness: Float) {
        init { bounded(periodSeconds, .25f, 2f); bounded(drive, .01f, .25f); bounded(roughness, 0f, 1f) }
    }
    init {
        require(operators.size == 4 && operators.first().ratio == 1f)
        require(operators.sumOf { it.gain.toDouble() } in .05..2.0)
        require(routing.size <= 6 && routing.map { it.from to it.to }.distinct().size == routing.size)
        bounded(fuseRadians, 0f, 3f); bounded(cutoffRatio, 4f, 24f)
    }

    fun toJsonValue(): JsonValue.Obj {
        fun n(v: Float) = JsonValue.Num(v.toDouble())
        fun array(v: List<Float>) = JsonValue.Arr(v.map(::n))
        fun projection(p: Projection) = JsonValue.Obj(linkedMapOf("ratios" to array(p.ratios), "losses" to array(p.losses), "gains" to array(p.gains)))
        return JsonValue.Obj(linkedMapOf(
            "operators" to JsonValue.Arr(operators.map { JsonValue.Obj(linkedMapOf("ratio" to n(it.ratio), "gain" to n(it.gain), "decay" to n(it.decay), "sustain" to n(it.sustain))) }),
            "routing" to JsonValue.Arr(routing.map { JsonValue.Obj(linkedMapOf("from" to JsonValue.Num(it.from.toDouble()), "to" to JsonValue.Num(it.to.toDouble()), "radians" to n(it.radians))) }),
            "wood" to projection(wood), "tine" to projection(tine), "breath" to projection(breath),
            "envelope" to JsonValue.Obj(linkedMapOf("attackSeconds" to n(envelope.attackSeconds), "finiteSeconds" to n(envelope.finiteSeconds), "releaseSeconds" to n(envelope.releaseSeconds), "sustain" to n(envelope.sustain))),
            "held" to JsonValue.Obj(linkedMapOf("periodSeconds" to n(held.periodSeconds), "drive" to n(held.drive), "roughness" to n(held.roughness))),
            "fuseRadians" to n(fuseRadians), "cutoffRatio" to n(cutoffRatio),
        ))
    }

    companion object {
        private fun bounded(v: Float, low: Float, high: Float) = require(v.isFinite() && v in low..high) { "ALLOY recipe value $v outside $low..$high" }

        fun fromJsonValue(value: JsonValue): AlloyConfig = try {
            decode(value)
        } catch (e: IllegalArgumentException) {
            throw JsonException("invalid alloy config: ${e.message}")
        } catch (e: NoSuchElementException) {
            throw JsonException("missing alloy config field: ${e.message}")
        }

        private fun decode(value: JsonValue): AlloyConfig {
            fun number(o: Map<String, JsonValue>, name: String) = (o[name] ?: throw JsonException("missing alloy config $name")).num().toFloat()
            fun objectAt(o: Map<String, JsonValue>, name: String) = (o[name] ?: throw JsonException("missing alloy config $name")).obj()
            fun projection(v: JsonValue): Projection {
                val o = v.obj()
                fun floats(k: String) = (o[k] ?: throw JsonException("missing alloy projection $k")).arr().map { it.num().toFloat() }
                return Projection(floats("ratios"), floats("losses"), floats("gains"))
            }
            val o = value.obj()
            val e = objectAt(o, "envelope"); val h = objectAt(o, "held")
            return AlloyConfig(
                (o["operators"] ?: throw JsonException("missing alloy operators")).arr().map { it.obj().let { v -> Operator(number(v, "ratio"), number(v, "gain"), number(v, "decay"), number(v, "sustain")) } },
                (o["routing"] ?: throw JsonException("missing alloy routing")).arr().map { it.obj().let { v -> Route(v.getValue("from").int(), v.getValue("to").int(), number(v, "radians")) } },
                projection(o.getValue("wood")), projection(o.getValue("tine")), projection(o.getValue("breath")),
                Envelope(number(e, "attackSeconds"), number(e, "finiteSeconds"), number(e, "releaseSeconds"), number(e, "sustain")),
                Held(number(h, "periodSeconds"), number(h, "drive"), number(h, "roughness")),
                number(o, "fuseRadians"), number(o, "cutoffRatio"),
            )
        }
    }
}
