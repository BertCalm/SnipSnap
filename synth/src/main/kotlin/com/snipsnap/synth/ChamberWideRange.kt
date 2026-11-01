package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Model 4's explicit listening candidate. These are shape constants awaiting
 * listening acceptance, not measured material properties. Model 3 remains the
 * default, and each control's accepted default is an exact unchanged anchor.
 * All work here prepares a recording; none runs in the sample loop.
 */
internal object ChamberWideRange {
    private val controls = listOf("STRIKE", "BALANCE", "MATERIAL", "DIVIDE", "PLACEMENT")

    data class Profile(
        val contactScale: Double,
        val spectralTilt: Double,
        val balanceFloor: Double,
        val balanceWidth: Double,
        val materialDecayScale: Double,
        val reflectionCutoffScale: Double,
        val localityPower: Double,
        val roomPresence: Double,
        val placementScale: Double,
    ) {
        fun boundaryT60(legacy: Double): Double =
            if (materialDecayScale == 1.0) legacy else (legacy * materialDecayScale).coerceIn(.025, 3.0)

        /**
         * Soft contacts must not cross a new half-sine spectral notch at the
         * source's own fundamental. An already longer legacy contact is left
         * alone rather than making a softer gesture unexpectedly shorter.
         */
        fun contactFrames(legacy: Int, sourceHz: Double, rate: Int): Int {
            if (contactScale == 1.0) return legacy
            val desired = (legacy * contactScale).roundToInt()
            val softened = if (contactScale > 1.0)
                min(desired, max(legacy, (.80 * rate / sourceHz).toInt())) else desired
            return softened.coerceIn(max(2, (.00008 * rate).roundToInt()), (.020 * rate).roundToInt())
        }
    }

    fun resolve(voice: ChamberVoice, settled: Map<String, Float>): Profile? {
        val defaults = Chamber.defaults(voice)
        if (controls.all { settled.getValue(it) == defaults.getValue(it) }) return null
        fun edge(name: String): Double {
            val value = settled.getValue(name).toDouble()
            val anchor = defaults.getValue(name).toDouble()
            val signed = if (value < anchor) (value - anchor) / anchor else (value - anchor) / (1.0 - anchor)
            // A quadratic keeps small gestures near the accepted centre gentle
            // while letting the last part of the travel reach another behaviour.
            return signed * abs(signed)
        }
        val strike = edge("STRIKE")
        val balance = abs(edge("BALANCE"))
        val material = edge("MATERIAL")
        val divide = edge("DIVIDE")
        val placement = edge("PLACEMENT")
        return Profile(
            contactScale = if (strike < 0.0) exp(ln(2.0) * -strike) else exp(ln(.25) * strike),
            // The soft endpoint removes upper-mode participation as well as
            // lengthening contact. Hardness keeps the accepted source ratios.
            spectralTilt = -.22 * max(0.0, -strike),
            balanceFloor = .17 + (.025 - .17) * balance,
            balanceWidth = .69 + (.30 - .69) * balance,
            materialDecayScale = if (material < 0.0) exp(ln(.22) * -material) else exp(ln(2.0) * material),
            reflectionCutoffScale = if (material < 0.0) exp(ln(.30) * -material) else exp(ln(1.30) * material),
            localityPower = if (divide < 0.0) exp(ln(.50) * -divide) else exp(ln(2.50) * divide),
            // This changes room/source proportion, never feedback or master gain.
            // Decay, spectrum and spatial locality above supply the physical change.
            roomPresence = 1.0 + 9.0 * max(abs(material), abs(divide)),
            placementScale = if (placement < 0.0) exp(ln(.28) * -placement) else exp(ln(1.65) * placement),
        )
    }

    fun geometry(
        configuration: ChamberConfig,
        material: Float,
        divide: Float,
        placement: Float,
        profile: Profile?,
    ): ChamberGeometry {
        val legacy = ChamberRoom.prepare(configuration, material, divide, placement)
        if (profile == null || profile.placementScale == 1.0 ||
            configuration.placementMode == ChamberPlacementMode.EXPLICIT) return legacy
        val listener = legacy.listener
        val positions = legacy.sources
        val clearance = ChamberRoom.MIN_POINT_DISTANCE_METRES.toDouble()
        var minimumScale = 0.0
        var maximumScale = Double.POSITIVE_INFINITY
        val dimensions = doubleArrayOf(legacy.dimensions.width.toDouble(), legacy.dimensions.depth.toDouble(), legacy.dimensions.height.toDouble())
        val fixed = doubleArrayOf(listener.x.toDouble(), listener.y.toDouble(), listener.z.toDouble())
        for (index in positions.indices) {
            minimumScale = max(minimumScale, clearance / positions[index].distanceTo(listener))
            for (other in 0 until index)
                minimumScale = max(minimumScale, clearance / positions[index].distanceTo(positions[other]))
            val point = positions[index]
            val axes = doubleArrayOf(point.x.toDouble(), point.y.toDouble(), point.z.toDouble())
            for (axis in 0..2) {
                val displacement = axes[axis] - fixed[axis]
                if (displacement != 0.0) {
                    val boundary = if (displacement > 0.0) dimensions[axis] - ChamberRoom.MIN_CLEARANCE_METRES
                        else ChamberRoom.MIN_CLEARANCE_METRES.toDouble()
                    maximumScale = min(maximumScale, (boundary - fixed[axis]) / displacement)
                }
            }
        }
        // One common radial scale preserves relative source separation. A small
        // geometric margin also survives the Float editor-coordinate round trip.
        val scale = if (profile.placementScale < 1.0)
            max(profile.placementScale, min(1.0, minimumScale * 1.002))
        else min(profile.placementScale, max(1.0, maximumScale * .998))
        if (scale == 1.0) return legacy
        fun point(index: Int): ChamberPoint {
            val source = positions[index]
            return ChamberPoint(
                ((listener.x + (source.x - listener.x) * scale) / legacy.dimensions.width).toFloat(),
                ((listener.y + (source.y - listener.y) * scale) / legacy.dimensions.depth).toFloat(),
                ((listener.z + (source.z - listener.z) * scale) / legacy.dimensions.height).toFloat(),
            )
        }
        val resolved = configuration.copy(
            sources = ChamberSources(point(0), point(1), point(2), point(3)),
            placementMode = ChamberPlacementMode.EXPLICIT,
        )
        return ChamberRoom.prepare(resolved, material, divide, placement)
    }
}
