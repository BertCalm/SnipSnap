package com.snipsnap.synth

/** Provisional dry microsound-harp profiles for the first listening audition. */
object OrphicaPresets {
    private val presets = listOf(
        OrphicaPatch("Clear Harp", OrphicaConfig(
            material = OrphicaMaterial.NYLON, mode = OrphicaMode.CLEAR,
            fracture = 0f, pluck = .6f, damping = .25f,
        )),
        OrphicaPatch("Gentle Scatter", OrphicaConfig(
            material = OrphicaMaterial.NYLON, mode = OrphicaMode.SCATTER,
            fracture = .3f, grainSizeMs = 75f, grainRateHz = 24f, scatter = .3f,
        )),
        OrphicaPatch("Shattered Scatter", OrphicaConfig(
            material = OrphicaMaterial.STEEL, mode = OrphicaMode.SCATTER,
            pluck = .8f, fracture = .85f, grainSizeMs = 25f, grainRateHz = 48f,
            scatter = .85f, damping = .15f,
        )),
        OrphicaPatch("Reverse Glass", OrphicaConfig(
            material = OrphicaMaterial.CRYSTAL, mode = OrphicaMode.REVERSE,
            fracture = .7f, grainSizeMs = 95f, grainRateHz = 22f, scatter = .45f,
            damping = .18f,
        )),
        OrphicaPatch("Captured Sustain", OrphicaConfig(
            material = OrphicaMaterial.NYLON, mode = OrphicaMode.FREEZE,
            fracture = .8f, grainSizeMs = 85f, grainRateHz = 32f, scatter = .35f,
            captureSeconds = .18f,
        )),
    )

    fun forVoice(material: OrphicaMaterial): List<OrphicaPatch> = presets.filter { it.config.material == material }

    fun all(): List<OrphicaPatch> = presets
}
