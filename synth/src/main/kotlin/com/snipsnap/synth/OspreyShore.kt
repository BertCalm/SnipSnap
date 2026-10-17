// SPDX-License-Identifier: MIT
// Copyright (c) 2026 XO_OX Designs
// Shore tables adapted from XO_OX-XOmnibus Source/DSP/ShoreSystem/ShoreSystem.h.
// https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Source/DSP/ShoreSystem/ShoreSystem.h
package com.snipsnap.synth

/** Original source tables describe spectral inspiration, not sampled or measured instruments. */
internal object OspreyShore {
    val names = listOf("Atlantic", "Nordic", "Mediterranean", "Pacific", "Southern")
    data class Instrument(val name: String, val centers: DoubleArray, val widths: DoubleArray,
        val gains: DoubleArray, val attackMs: Double, val decayMs: Double,
        val brightness: Double, val inharmonicity: Double)
    data class Creature(val name: String, val start: DoubleArray, val end: DoubleArray,
        val widths: DoubleArray, val sweepMs: Double, val gapMs: Double, val amplitude: Double)
    data class Fluid(val period: Double, val swellDepth: Double, val chopHz: Double,
        val chopAmount: Double, val depthBias: Double, val turbulenceOnset: Double)
    data class Profile(val instruments: List<Instrument>, val creatures: List<Creature>, val fluid: Fluid)

    private val instruments = listOf(
        listOf(
            Instrument("Guitarra", doubleArrayOf(320.0, 1200.0, 2800.0, 5500.0), doubleArrayOf(80.0, 150.0, 200.0, 300.0), doubleArrayOf(1.0, 0.7, 0.5, 0.3), 5.0, 800.0, 0.7, 0.05),
            Instrument("Kora", doubleArrayOf(250.0, 900.0, 2200.0, 4800.0), doubleArrayOf(60.0, 120.0, 180.0, 250.0), doubleArrayOf(1.0, 0.8, 0.4, 0.2), 3.0, 600.0, 0.6, 0.03),
            Instrument("Uilleann", doubleArrayOf(400.0, 1500.0, 3200.0, 6000.0), doubleArrayOf(100.0, 200.0, 250.0, 350.0), doubleArrayOf(1.0, 0.9, 0.6, 0.4), 15.0, 2000.0, 0.75, 0.02),
        ),
        listOf(
            Instrument("Hardingfele", doubleArrayOf(280.0, 1100.0, 2600.0, 5200.0), doubleArrayOf(70.0, 130.0, 170.0, 280.0), doubleArrayOf(1.0, 0.8, 0.6, 0.5), 8.0, 1200.0, 0.8, 0.04),
            Instrument("Langspil", doubleArrayOf(200.0, 700.0, 1800.0, 4000.0), doubleArrayOf(50.0, 100.0, 160.0, 220.0), doubleArrayOf(1.0, 0.6, 0.3, 0.15), 20.0, 3000.0, 0.4, 0.01),
            Instrument("Kulning", doubleArrayOf(500.0, 1800.0, 3500.0, 7000.0), doubleArrayOf(120.0, 250.0, 300.0, 400.0), doubleArrayOf(0.8, 1.0, 0.7, 0.3), 10.0, 1500.0, 0.85, 0.01),
        ),
        listOf(
            Instrument("Bouzouki", doubleArrayOf(350.0, 1300.0, 3000.0, 5800.0), doubleArrayOf(90.0, 160.0, 220.0, 320.0), doubleArrayOf(1.0, 0.75, 0.55, 0.35), 2.0, 500.0, 0.8, 0.06),
            Instrument("Oud", doubleArrayOf(220.0, 850.0, 2100.0, 4500.0), doubleArrayOf(55.0, 110.0, 170.0, 240.0), doubleArrayOf(1.0, 0.85, 0.5, 0.25), 4.0, 700.0, 0.55, 0.08),
            Instrument("Ney", doubleArrayOf(450.0, 1600.0, 3400.0, 6500.0), doubleArrayOf(110.0, 220.0, 280.0, 380.0), doubleArrayOf(0.9, 1.0, 0.65, 0.35), 25.0, 2500.0, 0.7, 0.01),
        ),
        listOf(
            Instrument("Koto", doubleArrayOf(300.0, 1150.0, 2700.0, 5400.0), doubleArrayOf(75.0, 140.0, 190.0, 290.0), doubleArrayOf(1.0, 0.7, 0.45, 0.2), 1.5, 400.0, 0.75, 0.04),
            Instrument("Conch", doubleArrayOf(180.0, 650.0, 1600.0, 3800.0), doubleArrayOf(45.0, 90.0, 150.0, 200.0), doubleArrayOf(1.0, 0.6, 0.35, 0.15), 30.0, 4000.0, 0.35, 0.12),
            Instrument("SingingBowl", doubleArrayOf(260.0, 980.0, 2400.0, 5000.0), doubleArrayOf(30.0, 60.0, 100.0, 150.0), doubleArrayOf(1.0, 0.9, 0.7, 0.5), 2.0, 8000.0, 0.65, 0.15),
        ),
        listOf(
            Instrument("Cavaquinho", doubleArrayOf(380.0, 1400.0, 3100.0, 6200.0), doubleArrayOf(95.0, 170.0, 230.0, 340.0), doubleArrayOf(1.0, 0.7, 0.5, 0.3), 1.0, 350.0, 0.85, 0.03),
            Instrument("Valiha", doubleArrayOf(240.0, 880.0, 2000.0, 4400.0), doubleArrayOf(60.0, 110.0, 165.0, 230.0), doubleArrayOf(1.0, 0.75, 0.45, 0.2), 3.0, 500.0, 0.6, 0.07),
            Instrument("Gamelan", doubleArrayOf(310.0, 1050.0, 2500.0, 5100.0), doubleArrayOf(40.0, 70.0, 120.0, 180.0), doubleArrayOf(1.0, 0.85, 0.7, 0.55), 1.0, 6000.0, 0.7, 0.2),
        ),
    )
    private val creatures = listOf(
        listOf(
            Creature("StormPetrel", doubleArrayOf(2500.0, 4200.0, 6800.0), doubleArrayOf(3800.0, 5500.0, 8000.0), doubleArrayOf(300.0, 400.0, 500.0), 120.0, 2000.0, 0.4),
            Creature("HumpbackWhale", doubleArrayOf(80.0, 250.0, 600.0), doubleArrayOf(200.0, 500.0, 1200.0), doubleArrayOf(30.0, 60.0, 100.0), 3000.0, 8000.0, 0.6),
            Creature("HarborFoghorn", doubleArrayOf(120.0, 350.0, 800.0), doubleArrayOf(120.0, 350.0, 800.0), doubleArrayOf(40.0, 70.0, 120.0), 4000.0, 15000.0, 0.5),
        ),
        listOf(
            Creature("ArcticTern", doubleArrayOf(3000.0, 5000.0, 7500.0), doubleArrayOf(4500.0, 6500.0, 9000.0), doubleArrayOf(350.0, 450.0, 550.0), 80.0, 3000.0, 0.35),
            Creature("BelugaWhale", doubleArrayOf(400.0, 1200.0, 3000.0), doubleArrayOf(800.0, 2000.0, 5000.0), doubleArrayOf(80.0, 150.0, 250.0), 800.0, 4000.0, 0.5),
            Creature("IceCrack", doubleArrayOf(50.0, 200.0, 500.0), doubleArrayOf(2000.0, 5000.0, 10000.0), doubleArrayOf(100.0, 300.0, 600.0), 50.0, 10000.0, 0.7),
        ),
        listOf(
            Creature("MedGull", doubleArrayOf(2200.0, 3800.0, 6000.0), doubleArrayOf(3200.0, 5000.0, 7500.0), doubleArrayOf(280.0, 380.0, 480.0), 200.0, 1500.0, 0.4),
            Creature("DolphinClick", doubleArrayOf(1000.0, 4000.0, 8000.0), doubleArrayOf(1200.0, 4500.0, 9000.0), doubleArrayOf(200.0, 400.0, 600.0), 15.0, 300.0, 0.3),
            Creature("CicadaDrone", doubleArrayOf(4000.0, 6000.0, 8500.0), doubleArrayOf(4000.0, 6000.0, 8500.0), doubleArrayOf(500.0, 700.0, 900.0), 10000.0, 2000.0, 0.25),
        ),
        listOf(
            Creature("Albatross", doubleArrayOf(1800.0, 3200.0, 5500.0), doubleArrayOf(2200.0, 3800.0, 6500.0), doubleArrayOf(250.0, 350.0, 450.0), 300.0, 5000.0, 0.3),
            Creature("PacificWhale", doubleArrayOf(60.0, 180.0, 450.0), doubleArrayOf(300.0, 700.0, 1500.0), doubleArrayOf(25.0, 50.0, 90.0), 5000.0, 12000.0, 0.65),
            Creature("ReefCrackle", doubleArrayOf(800.0, 3000.0, 7000.0), doubleArrayOf(1200.0, 4000.0, 9000.0), doubleArrayOf(400.0, 600.0, 800.0), 30.0, 100.0, 0.2),
        ),
        listOf(
            Creature("Tropicbird", doubleArrayOf(2800.0, 4500.0, 7000.0), doubleArrayOf(2000.0, 3200.0, 5000.0), doubleArrayOf(320.0, 420.0, 520.0), 150.0, 2500.0, 0.35),
            Creature("SouthernWhale", doubleArrayOf(50.0, 150.0, 400.0), doubleArrayOf(100.0, 300.0, 800.0), doubleArrayOf(20.0, 45.0, 80.0), 4000.0, 10000.0, 0.7),
            Creature("TropicalRain", doubleArrayOf(2000.0, 5000.0, 9000.0), doubleArrayOf(2000.0, 5000.0, 9000.0), doubleArrayOf(800.0, 1200.0, 1500.0), 30000.0, 5000.0, 0.3),
        ),
    )
    private val fluids = listOf(
        Fluid(12.0, .8, .4, .6, .5, .35),
        Fluid(18.0, .9, .2, .4, .7, .4),
        Fluid(5.0, .5, 1.2, .9, .3, .25),
        Fluid(25.0, .6, .15, .3, .6, .5),
        Fluid(8.0, .7, .6, .7, .4, .3),
    )

    /** Numeric data morphs between adjacent regions; labels are only diagnostic. */
    fun profile(shore: Float): Profile {
        val position = shore.coerceIn(0f, 1f).toDouble() * 4.0
        val lower = position.toInt().coerceAtMost(3)
        val upper = lower + 1
        val t = position - lower
        fun blend(a: Double, b: Double) = a + (b - a) * t
        fun array(a: DoubleArray, b: DoubleArray) = DoubleArray(a.size) { blend(a[it], b[it]) }
        val ins = List(3) { i ->
            val a = instruments[lower][i]; val b = instruments[upper][i]
            Instrument(if (t < .5) a.name else b.name,
                array(a.centers, b.centers), array(a.widths, b.widths), array(a.gains, b.gains),
                blend(a.attackMs, b.attackMs), blend(a.decayMs, b.decayMs),
                blend(a.brightness, b.brightness), blend(a.inharmonicity, b.inharmonicity))
        }
        val calls = List(3) { i ->
            val a = creatures[lower][i]; val b = creatures[upper][i]
            Creature(if (t < .5) a.name else b.name,
                array(a.start, b.start), array(a.end, b.end), array(a.widths, b.widths),
                blend(a.sweepMs, b.sweepMs), blend(a.gapMs, b.gapMs), blend(a.amplitude, b.amplitude))
        }
        val a = fluids[lower]; val b = fluids[upper]
        return Profile(ins, calls, Fluid(blend(a.period, b.period), blend(a.swellDepth, b.swellDepth),
            blend(a.chopHz, b.chopHz), blend(a.chopAmount, b.chopAmount),
            blend(a.depthBias, b.depthBias), blend(a.turbulenceOnset, b.turbulenceOnset)))
    }
}
