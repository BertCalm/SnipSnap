package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** Opt-in, pre-render body response. [AlloyPatch.config] remains the saved anchor.
 * Endpoint decimals stay Double until the final configuration is made: rounding
 * them to Float before interpolation changes the approved intermediate audio. */
data class AlloyMaterialResponse(
    val version: Int = 1,
    val originalWeightPower: Int,
    val operators: List<Operator>,
    val routing: List<Route>,
    val woodLosses: List<Double>,
    val woodGains: List<Double>,
    val cutoffRatio: Double,
) {
    data class Operator(val gain: Double, val decay: Double, val sustain: Double) {
        init { bounded(gain, 0.0, 1.0); bounded(decay, .02, 4.0); bounded(sustain, 0.0, 1.0) }
    }
    data class Route(val from: Int, val to: Int, val radians: Double) {
        init { require(from in 1..3 && to in 0 until from); bounded(radians, 0.0, 3.0) }
    }
    init {
        require(version == 1) { "unsupported alloy material response $version" }
        require(originalWeightPower in 1..2) { "unsupported alloy material response power" }
        require(operators.size == 4 && operators.sumOf { it.gain } in .05..2.0)
        require(routing.size <= 6 && routing.map { it.from to it.to }.distinct().size == routing.size)
        require(woodLosses.size in 1..8 && woodLosses.size == woodGains.size)
        woodLosses.forEach { bounded(it, .008, 2.0) }
        woodGains.forEach { bounded(it, 0.0, 1.0) }
        require(woodGains.sum() in .01..2.0)
        bounded(cutoffRatio, 4.0, 24.0)
    }

    fun validateAnchor(anchor: AlloyConfig) {
        require(woodLosses.size == anchor.wood.ratios.size) { "alloy material response wood size differs from anchor" }
    }

    /** The exact upper branch also preserves route order and absent edges. */
    fun resolve(anchor: AlloyConfig, material: Float): AlloyConfig {
        validateAnchor(anchor)
        require(material.isFinite() && material in 0f..1f)
        if (material >= .5f) return anchor
        val linear = material.toDouble() * 2.0
        val weight = if (originalWeightPower == 2) linear * linear else linear
        fun blend(left: Double, right: Float): Float = (left + (right.toDouble() - left) * weight).toFloat()
        val before = routing.associate { (it.from to it.to) to it.radians }
        val after = anchor.routing.associate { (it.from to it.to) to it.radians }
        val edges = (before.keys + after.keys).sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        return anchor.copy(
            operators = anchor.operators.zip(operators) { old, left ->
                old.copy(gain = blend(left.gain, old.gain), decay = blend(left.decay, old.decay), sustain = blend(left.sustain, old.sustain))
            },
            routing = edges.map { (from, to) -> AlloyConfig.Route(from, to, blend(before[from to to] ?: 0.0, after[from to to] ?: 0f)) },
            wood = anchor.wood.copy(
                losses = woodLosses.zip(anchor.wood.losses, ::blend),
                gains = woodGains.zip(anchor.wood.gains, ::blend),
            ),
            cutoffRatio = blend(cutoffRatio, anchor.cutoffRatio),
        )
    }

    fun toJsonValue(): JsonValue.Obj {
        fun n(v: Double) = JsonValue.Num(v)
        return JsonValue.Obj(linkedMapOf(
            "version" to n(version.toDouble()), "originalWeightPower" to n(originalWeightPower.toDouble()),
            "operators" to JsonValue.Arr(operators.map { JsonValue.Obj(linkedMapOf("gain" to n(it.gain), "decay" to n(it.decay), "sustain" to n(it.sustain))) }),
            "routing" to JsonValue.Arr(routing.map { JsonValue.Obj(linkedMapOf("from" to n(it.from.toDouble()), "to" to n(it.to.toDouble()), "radians" to n(it.radians))) }),
            "woodLosses" to JsonValue.Arr(woodLosses.map(::n)), "woodGains" to JsonValue.Arr(woodGains.map(::n)),
            "cutoffRatio" to n(cutoffRatio),
        ))
    }

    companion object {
        private fun bounded(v: Double, low: Double, high: Double) = require(v.isFinite() && v in low..high) { "alloy material response value $v outside $low..$high" }
        fun fromJsonValue(value: JsonValue): AlloyMaterialResponse = try {
            val o = value.obj()
            fun field(name: String) = o[name] ?: throw JsonException("missing alloy material response $name")
            AlloyMaterialResponse(
                field("version").int(), field("originalWeightPower").int(),
                field("operators").arr().map { item -> item.obj().let { Operator(it.getValue("gain").num(), it.getValue("decay").num(), it.getValue("sustain").num()) } },
                field("routing").arr().map { item -> item.obj().let { Route(it.getValue("from").int(), it.getValue("to").int(), it.getValue("radians").num()) } },
                field("woodLosses").arr().map { it.num() }, field("woodGains").arr().map { it.num() }, field("cutoffRatio").num(),
            )
        } catch (e: IllegalArgumentException) {
            throw JsonException("invalid alloy material response: ${e.message}")
        } catch (e: NoSuchElementException) {
            throw JsonException("missing alloy material response field: ${e.message}")
        }
    }
}
