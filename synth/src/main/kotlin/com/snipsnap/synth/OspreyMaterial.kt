package com.snipsnap.synth

import kotlin.math.exp
import kotlin.math.ln

/** Authored imaginary acoustic families. These are not measured folk-instrument models. */
internal object OspreyMaterial {
    data class Coast(val name: String, val ratios: DoubleArray, val decay: DoubleArray,
        val radiation: DoubleArray, val drive: DoubleArray, val period: Double, val chop: Double,
        val breaker: Double, val widthMs: Double)
    data class Morph(val ratios: DoubleArray, val decay: DoubleArray, val radiation: DoubleArray,
        val weights: DoubleArray)
    val coasts = listOf(
        Coast("Atlantic wire", doubleArrayOf(1.0,2.0,3.0,4.0,5.0,6.0,7.0,8.0,9.0,10.0,11.0,12.0),
            doubleArrayOf(1.4,1.2,1.1,.95,.82,.70,.60,.52,.45,.39,.34,.30),
            doubleArrayOf(.82,1.0,.95,.86,.78,.70,.62,.55,.49,.43,.37,.31),
            doubleArrayOf(1.0,1.0,.94,.88,.83,.77,.71,.66,.61,.56,.51,.46), 2.8,.65,.38,.20),
        Coast("Nordic bow", doubleArrayOf(1.0,2.0,3.0,4.0,5.0,6.0,7.0,8.0,9.0,10.0,11.0,12.0),
            doubleArrayOf(1.8,1.9,2.1,2.0,1.9,1.8,1.7,1.6,1.5,1.4,1.3,1.2),
            doubleArrayOf(.85,.42,1.05,.36,.95,.30,.80,.26,.65,.23,.50,.20),
            doubleArrayOf(1.0,.62,1.15,.50,1.02,.42,.89,.34,.72,.28,.58,.22), 3.8,.78,.44,.14),
        Coast("Mediterranean wood", doubleArrayOf(1.0,2.32,3.21,4.47,5.86,7.29,8.83,10.48,2.0,3.0,5.0,7.0),
            doubleArrayOf(.52,.29,.23,.19,.16,.14,.12,.10,.40,.34,.28,.22),
            doubleArrayOf(.90,1.10,.96,.84,.73,.62,.52,.43,.38,.34,.29,.24),
            doubleArrayOf(1.0,1.15,1.05,.98,.91,.83,.76,.68,.44,.40,.35,.30), 1.9,.40,.25,.36),
        Coast("Pacific bowl", doubleArrayOf(1.0,1.58,2.09,2.71,3.47,4.19,5.13,6.27,7.46,8.82,10.24,12.09),
            doubleArrayOf(2.8,4.2,3.9,3.6,3.3,3.0,2.7,2.4,2.1,1.8,1.5,1.2),
            doubleArrayOf(.86,1.12,1.02,.96,.90,.83,.75,.68,.60,.52,.43,.35),
            doubleArrayOf(1.0,1.06,1.01,.95,.89,.84,.78,.72,.66,.60,.54,.48), 4.8,1.15,.70,.24),
        Coast("Southern metal strum", doubleArrayOf(1.0,2.0,3.0,3.67,4.58,5.93,7.16,8.38,9.74,5.0,7.0,9.0),
            doubleArrayOf(.80,.43,.38,.95,.88,.82,.75,.68,.61,.35,.30,.25),
            doubleArrayOf(.84,.98,.91,1.07,1.00,.91,.82,.74,.65,.43,.37,.31),
            doubleArrayOf(1.0,1.10,1.02,1.14,1.06,.98,.89,.81,.73,.54,.48,.42), 2.5,.54,.31,.12),
    )
    /** Ecology and fluid follow the same eased adjacent-coast position as the material. */
    fun ecologyPosition(shore: Float): Float {
        val position=shore.coerceIn(0f,1f).toDouble()*4
        val lower=position.toInt().coerceAtMost(3)
        val f=position-lower
        return ((lower+f*f*(3-2*f))/4).toFloat()
    }
    fun morph(shore: Float): Morph {
        val position = shore.coerceIn(0f,1f).toDouble()*4
        val lower = position.toInt().coerceAtMost(3)
        val fraction = position-lower
        // Zero endpoint slope also keeps very small boundary edits from phase-amplifying
        // a newly introduced exciter while its neighbouring family is still at full strength.
        val t = fraction*fraction*(3-2*fraction)
        val a=coasts[lower]; val b=coasts[lower+1]
        fun geometric(x:DoubleArray,y:DoubleArray)=DoubleArray(12){exp(ln(x[it])+(ln(y[it])-ln(x[it]))*t)}
        val weights=DoubleArray(5); weights[lower]=1-t;weights[lower+1]=t
        return Morph(geometric(a.ratios,b.ratios),geometric(a.decay,b.decay),
            DoubleArray(12){a.radiation[it]+(b.radiation[it]-a.radiation[it])*t},weights)
    }
}
