package com.snipsnap.synth

import kotlin.math.max
import kotlin.math.sqrt

data class ChamberDirectPath(
    val sourceIndex: Int,
    val distanceMetres: Float,
    val delaySeconds: Float,
    val gain: Float,
)

data class ChamberReflectionPath(
    val sourceIndex: Int,
    val face: ChamberFace,
    val intersection: ChamberPoint,
    val distanceMetres: Float,
    val delaySeconds: Float,
    val gain: Float,
    /** Ten continuous weights selected at the real image-source intersection. */
    val regionWeights: List<Float>,
    /** Five parallel-response weights, never a global room material average. */
    val materialWeights: List<Float>,
) {
    val materialPosition get() = materialWeights.mapIndexed { i, weight -> i * .25f * weight }.sum()
}

data class ChamberSurfaceCoupling(
    val sourceIndex: Int,
    val region: ChamberRegion,
    val point: ChamberPoint,
    val distanceMetres: Float,
    val delaySeconds: Float,
    /** Two-way source -> region -> source propagation. */
    val returnDelaySeconds: Float,
    /** Bounded geometric weight; the DSP also normalizes all feedback junctions. */
    val weight: Float,
    val listenerDistanceMetres: Float,
    val listenerDelaySeconds: Float,
    val listenerGain: Float,
    val materialWeights: List<Float>,
)

data class ChamberGeometry(
    val dimensions: ChamberDimensions,
    /** Physical metres, after the editor coordinates and placement path are resolved. */
    val listener: ChamberPoint,
    val sources: List<ChamberPoint>,
    val directPaths: List<ChamberDirectPath>,
    val reflections: List<ChamberReflectionPath>,
    val surfaceCouplings: List<ChamberSurfaceCoupling>,
    val regionMaterialWeights: List<List<Float>>,
    val regionAreas: List<Float>,
    val regionCenters: List<ChamberPoint>,
) {
    val regionMaterialPositions get() = regionMaterialWeights.map { weights ->
        weights.mapIndexed { i, weight -> i * .25f * weight }.sum()
    }
}

/** Static rectangular image-source geometry, prepared once per render. */
object ChamberRoom {
    const val SPEED_OF_SOUND = 343f
    const val MIN_CLEARANCE_METRES = .18f
    const val MIN_POINT_DISTANCE_METRES = .25f
    const val MIN_ATTENUATION_DISTANCE_METRES = 1f

    fun prepare(configuration: ChamberConfig, material: Float, divide: Float, placement: Float): ChamberGeometry {
        require(listOf(material, divide, placement).all { it.isFinite() && it in 0f..1f }) {
            "CHAMBER room macros must be finite 0..1"
        }
        val dimensions = configuration.dimensions
        val listener = dimensions.physical(configuration.listener)
        val starts = configuration.sources.points.map(dimensions::physical)
        val ends = configuration.placementTarget.points.map(dimensions::physical)
        val sources = starts.mapIndexed { i, p ->
            if (configuration.placementMode == ChamberPlacementMode.EXPLICIT) p else p.mix(ends[i], placement)
        }
        val globalWeights = materialWeights(material)
        val targets = targetPositions(configuration, material)
        // Crossfade bounded parallel responses, not raw recursive filter coefficients.
        val regionMaterials = targets.map { target ->
            val targetWeights = materialWeights(target)
            globalWeights.indices.map { index -> globalWeights[index] * (1f - divide) + targetWeights[index] * divide }
        }
        val direct = sources.mapIndexed { i, source ->
            val distance = source.distanceTo(listener)
            ChamberDirectPath(i, distance, distance / SPEED_OF_SOUND, attenuation(distance))
        }
        val reflections = sources.flatMapIndexed { i, source ->
            ChamberFace.entries.map { face ->
                val image = imageSource(source, dimensions, face)
                val intersection = intersection(listener, image, dimensions, face)
                val distance = image.distanceTo(listener)
                val regions = regionWeights(configuration, face, intersection)
                val weights = List(ChamberMaterial.entries.size) { family ->
                    regions.indices.sumOf { region -> (regions[region] * regionMaterials[region][family]).toDouble() }.toFloat()
                }
                ChamberReflectionPath(i, face, intersection, distance, distance / SPEED_OF_SOUND,
                    attenuation(distance), regions, weights)
            }
        }
        val areas = ChamberRegion.entries.map { regionArea(configuration, it) }
        val centers = ChamberRegion.entries.map { regionCenter(configuration, it) }
        val totalArea = areas.sum()
        val couplings = sources.flatMapIndexed { i, source ->
            ChamberRegion.entries.map { region ->
                // The modal boundary is lumped at one fixed centre. Use that same
                // point for injection and listener pickup: mixing a closest-point
                // injection with centre pickup could precede the direct arrival.
                val point = centers[region.ordinal]
                val distance = source.distanceTo(point)
                val listenerDistance = listener.distanceTo(point)
                ChamberSurfaceCoupling(i, region, point, distance, distance / SPEED_OF_SOUND,
                    2f * distance / SPEED_OF_SOUND,
                    sqrt(areas[region.ordinal] / totalArea) * attenuation(distance),
                    listenerDistance, listenerDistance / SPEED_OF_SOUND, attenuation(listenerDistance),
                    regionMaterials[region.ordinal])
            }
        }
        return ChamberGeometry(dimensions, listener, sources, direct, reflections, couplings,
            regionMaterials, areas, centers)
    }

    /** Piecewise-linear convex family weights. All five DSP branches keep stable identities. */
    fun materialWeights(position: Float): List<Float> {
        require(position.isFinite() && position in 0f..1f)
        val scaled = position * 4f
        val lower = scaled.toInt().coerceAtMost(3)
        val fraction = scaled - lower
        return List(5) { family ->
            when (family) { lower -> 1f - fraction; lower + 1 -> fraction; else -> 0f }
        }
    }

    /** Region selector is public to make split-crossing behavior directly inspectable. */
    fun regionWeights(configuration: ChamberConfig, face: ChamberFace, intersection: ChamberPoint): List<Float> {
        val weights = MutableList(10) { 0f }
        if (face == ChamberFace.FLOOR) weights[ChamberRegion.FLOOR.ordinal] = 1f
        else if (face == ChamberFace.CEILING) weights[ChamberRegion.CEILING.ordinal] = 1f
        else {
            val lowerIndex = face.ordinal * 2
            val split = configuration.splitHeight * configuration.dimensions.height
            val t = ((intersection.z - split) / configuration.boundaryBlendMetres + .5f).coerceIn(0f, 1f)
            val upper = t * t * (3f - 2f * t)
            weights[lowerIndex] = 1f - upper
            weights[lowerIndex + 1] = upper
        }
        return weights.toList()
    }

    private fun targetPositions(configuration: ChamberConfig, material: Float): List<Float> {
        if (configuration.materialMode == ChamberMaterialMode.MACRO_LAYOUT && configuration.layout == ChamberLayout.UNIFORM)
            return List(10) { material }
        val offset = (material - configuration.materialReference) * configuration.materialOffsetScale
        return ChamberRegion.entries.map { region ->
            val target = if (configuration.materialMode == ChamberMaterialMode.EXPLICIT_TARGETS) configuration.targets[region]
            else when (configuration.layout) {
                ChamberLayout.UNIFORM -> material
                ChamberLayout.OPPOSING_WALLS -> when (region.face) {
                    ChamberFace.WEST, ChamberFace.EAST, ChamberFace.FLOOR -> configuration.paletteLow
                    else -> configuration.paletteHigh
                }
                ChamberLayout.ALTERNATING_WALLS -> when (region.face) {
                    ChamberFace.WEST, ChamberFace.SOUTH, ChamberFace.FLOOR -> configuration.paletteLow
                    else -> configuration.paletteHigh
                }
                ChamberLayout.LOWER_UPPER -> if (region.upper) configuration.paletteHigh else configuration.paletteLow
            }
            (target + offset).coerceIn(0f, 1f)
        }
    }

    private fun attenuation(distance: Float) = 1f / max(MIN_ATTENUATION_DISTANCE_METRES, distance)

    private fun imageSource(source: ChamberPoint, d: ChamberDimensions, face: ChamberFace): ChamberPoint = when (face) {
        ChamberFace.WEST -> source.copy(x = -source.x)
        ChamberFace.EAST -> source.copy(x = 2f * d.width - source.x)
        ChamberFace.SOUTH -> source.copy(y = -source.y)
        ChamberFace.NORTH -> source.copy(y = 2f * d.depth - source.y)
        ChamberFace.FLOOR -> source.copy(z = -source.z)
        ChamberFace.CEILING -> source.copy(z = 2f * d.height - source.z)
    }

    private fun intersection(listener: ChamberPoint, image: ChamberPoint, d: ChamberDimensions, face: ChamberFace): ChamberPoint {
        val fraction = when (face) {
            ChamberFace.WEST -> -listener.x / (image.x - listener.x)
            ChamberFace.EAST -> (d.width - listener.x) / (image.x - listener.x)
            ChamberFace.SOUTH -> -listener.y / (image.y - listener.y)
            ChamberFace.NORTH -> (d.depth - listener.y) / (image.y - listener.y)
            ChamberFace.FLOOR -> -listener.z / (image.z - listener.z)
            ChamberFace.CEILING -> (d.height - listener.z) / (image.z - listener.z)
        }
        return listener.mix(image, fraction)
    }

    private fun regionArea(configuration: ChamberConfig, region: ChamberRegion): Float {
        val d = configuration.dimensions
        return when (region.face) {
            ChamberFace.WEST, ChamberFace.EAST -> d.depth * d.height * if (region.upper) 1f - configuration.splitHeight else configuration.splitHeight
            ChamberFace.SOUTH, ChamberFace.NORTH -> d.width * d.height * if (region.upper) 1f - configuration.splitHeight else configuration.splitHeight
            ChamberFace.FLOOR, ChamberFace.CEILING -> d.width * d.depth
        }
    }

    private fun regionCenter(configuration: ChamberConfig, region: ChamberRegion): ChamberPoint {
        val d = configuration.dimensions
        val split = configuration.splitHeight * d.height
        val z = if (region.upper) (split + d.height) * .5f else split * .5f
        return when (region.face) {
            ChamberFace.WEST -> ChamberPoint(0f, d.depth * .5f, z)
            ChamberFace.EAST -> ChamberPoint(d.width, d.depth * .5f, z)
            ChamberFace.SOUTH -> ChamberPoint(d.width * .5f, 0f, z)
            ChamberFace.NORTH -> ChamberPoint(d.width * .5f, d.depth, z)
            ChamberFace.FLOOR -> ChamberPoint(d.width * .5f, d.depth * .5f, 0f)
            ChamberFace.CEILING -> ChamberPoint(d.width * .5f, d.depth * .5f, d.height)
        }
    }

}
