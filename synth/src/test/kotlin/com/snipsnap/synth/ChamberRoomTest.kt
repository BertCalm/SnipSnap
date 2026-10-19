package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ChamberRoomTest {
    private fun prepare(config: ChamberConfig = ChamberConfig.hybrid(), material: Float = .4f,
        divide: Float = 1f, placement: Float = .6f) = ChamberRoom.prepare(config, material, divide, placement)

    @Test
    fun `surface excitation and pickup share a boundary point and cannot beat direct sound`() {
        for (config in listOf(ChamberConfig.hybrid(), ChamberConfig.aquarium())) for (placement in listOf(0f, .5f, 1f)) {
            val geometry = prepare(config, placement = placement)
            for (path in geometry.surfaceCouplings) {
                assertEquals(geometry.regionCenters[path.region.ordinal], path.point)
                val direct = geometry.directPaths[path.sourceIndex].delaySeconds
                assertTrue(path.delaySeconds + path.listenerDelaySeconds >= direct - 1e-7f,
                    "a boundary answer cannot arrive before its source's direct path")
                assertEquals(path.distanceMetres / ChamberRoom.SPEED_OF_SOUND, path.delaySeconds, 1e-7f)
            }
        }
    }

    @Test
    fun `physical direct and first order paths have image source travel times`() {
        val config = ChamberConfig.hybrid().copy(
            dimensions = ChamberDimensions(8f, 10f, 4f), listener = ChamberPoint(.5f, .8f, .5f),
            sources = ChamberSources(ChamberPoint(.25f, .3f, .2f), ChamberPoint(.4f, .2f, .3f),
                ChamberPoint(.65f, .25f, .4f), ChamberPoint(.85f, .45f, .6f)),
            placementMode = ChamberPlacementMode.EXPLICIT,
        )
        val geometry = prepare(config)
        assertEquals(4, geometry.directPaths.size)
        assertEquals(24, geometry.reflections.size)
        assertEquals(40, geometry.surfaceCouplings.size)
        val direct = geometry.directPaths.first()
        val west = geometry.reflections.first { it.sourceIndex == 0 && it.face == ChamberFace.WEST }
        assertEquals(sqrt(4f + 25f + 1.44f), direct.distanceMetres, 1e-5f)
        assertEquals(sqrt(36f + 25f + 1.44f), west.distanceMetres, 1e-5f)
        assertEquals(west.distanceMetres / 343f, west.delaySeconds, 1e-7f)
        assertEquals(0f, west.intersection.x, 1e-6f)
        assertEquals(west.distanceMetres,
            geometry.sources[0].distanceTo(west.intersection) + geometry.listener.distanceTo(west.intersection), 1e-5f)
        for (path in geometry.reflections) {
            assertTrue(path.delaySeconds > geometry.directPaths[path.sourceIndex].delaySeconds)
            assertEquals(1f, path.regionWeights.sum(), 1e-6f)
            assertEquals(1f, path.materialWeights.sum(), 1e-6f)
            assertTrue(path.intersection.x in -1e-5f..config.dimensions.width + 1e-5f)
            assertTrue(path.intersection.y in -1e-5f..config.dimensions.depth + 1e-5f)
            assertTrue(path.intersection.z in -1e-5f..config.dimensions.height + 1e-5f)
        }
    }

    @Test
    fun `required hybrid and aquarium assignments retain their independent regions`() {
        val hybrid = prepare()
        assertEquals(listOf(0f, 0f, .75f, .75f, 0f, 0f, .75f, .75f, .25f, .75f), hybrid.regionMaterialPositions)
        val aquarium = prepare(ChamberConfig.aquarium(), material = .95f)
        assertEquals(listOf(.25f, 1f, .25f, 1f, .25f, 1f, .25f, 1f, .25f, 1f), aquarium.regionMaterialPositions)
        assertEquals(listOf(0f, 1f, 0f, 0f, 0f), aquarium.regionMaterialWeights[ChamberRegion.FLOOR.ordinal])
        assertEquals(listOf(0f, 0f, 0f, 0f, 1f), aquarium.regionMaterialWeights[ChamberRegion.CEILING.ordinal])
        val wood = hybrid.reflections.first { it.face == ChamberFace.WEST }
        val glass = hybrid.reflections.first { it.face == ChamberFace.EAST }
        assertEquals(0f, wood.materialPosition)
        assertEquals(.75f, glass.materialPosition)
    }

    @Test
    fun `reflection material is selected at the actual boundary intersection`() {
        val config = ChamberConfig.aquarium().copy(listener = ChamberPoint(.5f, .8f, .5f),
            placementMode = ChamberPlacementMode.EXPLICIT)
        val low = prepare(config, .95f)
        val high = prepare(config.copy(sources = ChamberSources(
            config.sources.membrane.copy(z = .9f), config.sources.bar.copy(z = .9f),
            config.sources.cowbell.copy(z = .9f), config.sources.triangle.copy(z = .9f))), .95f)
        val lowPath = low.reflections.first { it.sourceIndex == 0 && it.face == ChamberFace.WEST }
        val highPath = high.reflections.first { it.sourceIndex == 0 && it.face == ChamberFace.WEST }
        assertTrue(lowPath.intersection.z < config.splitHeight * config.dimensions.height)
        assertTrue(highPath.intersection.z > config.splitHeight * config.dimensions.height)
        assertEquals(.25f, lowPath.materialPosition)
        assertEquals(1f, highPath.materialPosition)
        // A height edit keeps floor/ceiling material identities and changes their travel distances.
        assertEquals(low.regionMaterialWeights, high.regionMaterialWeights)
        assertNotEquals(low.reflections.first { it.face == ChamberFace.FLOOR }.distanceMetres,
            high.reflections.first { it.face == ChamberFace.FLOOR }.distanceMetres)
    }

    @Test
    fun `split boundary uses a narrow continuous response blend`() {
        val config = ChamberConfig.aquarium()
        val split = config.splitHeight * config.dimensions.height
        fun weights(z: Float) = ChamberRoom.regionWeights(config, ChamberFace.WEST, ChamberPoint(0f, 2f, z))
        val below = weights(split - config.boundaryBlendMetres)
        val at = weights(split)
        val above = weights(split + config.boundaryBlendMetres)
        assertEquals(1f, below[ChamberRegion.WEST_LOWER.ordinal])
        assertEquals(.5f, at[ChamberRegion.WEST_LOWER.ordinal], 1e-5f)
        assertEquals(.5f, at[ChamberRegion.WEST_UPPER.ordinal], 1e-5f)
        assertEquals(1f, above[ChamberRegion.WEST_UPPER.ordinal])
        val left = weights(split - .00001f)
        val right = weights(split + .00001f)
        assertTrue(left.indices.maxOf { abs(left[it] - right[it]) } < .001f)
        assertTrue(at.drop(2).all { it == 0f }, "split blended unrelated regions")
    }

    @Test
    fun `divide zero is globally uniform while full divide and material retain contrast`() {
        for (material in listOf(0f, .14f, .4f, .73f, 1f)) {
            val uniform = prepare(material = material, divide = 0f)
            assertTrue(uniform.regionMaterialWeights.all { it == ChamberRoom.materialWeights(material) })
            val divided = prepare(material = material)
            assertTrue(divided.regionMaterialPositions.max() - divided.regionMaterialPositions.min() > .4f)
        }
        val a = prepare(material = .35f)
        val b = prepare(material = .35001f)
        assertTrue(a.regionMaterialWeights.indices.maxOf { region ->
            a.regionMaterialWeights[region].indices.maxOf { abs(a.regionMaterialWeights[region][it] - b.regionMaterialWeights[region][it]) }
        } < .001f)
        assertNotEquals(prepare(material = 0f).regionMaterialWeights, prepare(material = 1f).regionMaterialWeights)
    }

    @Test
    fun `placement traverses valid bounded geometry and changes surface injection`() {
        for (config in listOf(ChamberConfig.hybrid(), ChamberConfig.aquarium())) {
            val first = prepare(config, placement = 0f)
            var previous = first
            for (step in 0..100) {
                val geometry = prepare(config, placement = step / 100f)
                assertEquals(first.listener, geometry.listener)
                for (source in geometry.sources) {
                    assertTrue(source.x >= ChamberRoom.MIN_CLEARANCE_METRES && source.x <= config.dimensions.width - ChamberRoom.MIN_CLEARANCE_METRES)
                    assertTrue(source.y >= ChamberRoom.MIN_CLEARANCE_METRES && source.y <= config.dimensions.depth - ChamberRoom.MIN_CLEARANCE_METRES)
                    assertTrue(source.z >= ChamberRoom.MIN_CLEARANCE_METRES && source.z <= config.dimensions.height - ChamberRoom.MIN_CLEARANCE_METRES)
                    assertTrue(source.distanceTo(geometry.listener) >= ChamberRoom.MIN_POINT_DISTANCE_METRES)
                }
                for (a in geometry.sources.indices) for (b in 0 until a)
                    assertTrue(geometry.sources[a].distanceTo(geometry.sources[b]) >= ChamberRoom.MIN_POINT_DISTANCE_METRES)
                assertTrue(geometry.sources.indices.all { geometry.sources[it].distanceTo(previous.sources[it]) < .10f })
                assertTrue(geometry.directPaths.all { it.gain.isFinite() && it.gain in 0f..1f })
                assertTrue(geometry.surfaceCouplings.all { it.weight.isFinite() && it.weight in 0f..1f && it.returnDelaySeconds > 0f })
                previous = geometry
            }
            assertNotEquals(first.surfaceCouplings.map { it.weight }, previous.surfaceCouplings.map { it.weight })
            assertEquals(first.surfaceCouplings.map { it.listenerGain }, previous.surfaceCouplings.map { it.listenerGain },
                "moving a source must not move the shared boundary's listener pickup")
        }
    }

    @Test
    fun `explicit placement preserves editor positions and unsafe crossing paths are rejected`() {
        val explicit = ChamberConfig.hybrid().copy(placementMode = ChamberPlacementMode.EXPLICIT)
        assertEquals(prepare(explicit, placement = 0f), prepare(explicit, placement = 1f))
        val base = ChamberConfig.hybrid()
        assertFailsWith<IllegalArgumentException> { base.copy(placementTarget = base.placementTarget.copy(
            membrane = base.sources.bar, bar = base.sources.membrane)) }
        assertFailsWith<IllegalArgumentException> { base.copy(sources = base.sources.copy(bar = base.sources.membrane)) }
        assertFailsWith<IllegalArgumentException> { base.copy(splitHeight = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { ChamberDimensions(width = 0f) }
        assertFailsWith<IllegalArgumentException> { prepare(material = Float.NaN) }
    }

    @Test
    fun `macro layouts and explicit target mode have declared independent precedence`() {
        val base = ChamberConfig.hybrid().copy(materialMode = ChamberMaterialMode.MACRO_LAYOUT,
            paletteLow = .2f, paletteHigh = .8f, materialReference = .5f)
        for (layout in ChamberLayout.entries) {
            val room = prepare(base.copy(layout = layout), material = .5f)
            if (layout == ChamberLayout.UNIFORM) assertTrue(room.regionMaterialPositions.all { it == .5f })
            else {
                assertEquals(.2f, room.regionMaterialPositions[ChamberRegion.FLOOR.ordinal], 1e-6f)
                assertEquals(.8f, room.regionMaterialPositions[ChamberRegion.CEILING.ordinal], 1e-6f)
                assertTrue(room.regionMaterialPositions.any { abs(it - .2f) < 1e-6f })
                assertTrue(room.regionMaterialPositions.any { abs(it - .8f) < 1e-6f })
            }
        }
        val explicit = base.copy(materialMode = ChamberMaterialMode.EXPLICIT_TARGETS)
        assertEquals(prepare(explicit.copy(layout = ChamberLayout.UNIFORM), material = .5f).regionMaterialWeights,
            prepare(explicit.copy(layout = ChamberLayout.LOWER_UPPER), material = .5f).regionMaterialWeights)
        val shifted = prepare(explicit, material = .7f)
        assertEquals(.1f, shifted.regionMaterialPositions[ChamberRegion.WEST_LOWER.ordinal], 1e-6f)
        assertEquals(.85f, shifted.regionMaterialPositions[ChamberRegion.EAST_LOWER.ordinal], 1e-6f)
    }

    @Test
    fun `normalized boundary positions clamp to physical clearance without singular gains`() {
        for (dimensions in listOf(ChamberDimensions(3f, 3f, 2.5f), ChamberDimensions(15f, 15f, 8f))) {
            val config = ChamberConfig.hybrid().copy(dimensions = dimensions,
                sources = ChamberSources(ChamberPoint(0f, 0f, 0f), ChamberPoint(1f, 0f, 0f),
                    ChamberPoint(0f, 1f, 1f), ChamberPoint(1f, 1f, 1f)),
                listener = ChamberPoint(.5f, .5f, .5f), placementMode = ChamberPlacementMode.EXPLICIT)
            val geometry = prepare(config)
            assertEquals(ChamberPoint(.18f, .18f, .18f), geometry.sources.first())
            assertTrue(geometry.reflections.all { it.gain.isFinite() && it.gain in 0f..1f })
            assertTrue(geometry.surfaceCouplings.all { it.distanceMetres >= ChamberRoom.MIN_CLEARANCE_METRES - 1e-5f })
            assertTrue(geometry.surfaceCouplings.all { it.weight.isFinite() && it.weight in 0f..1f })
        }
    }

    @Test
    fun `full authored configuration round trips without macro override ambiguity`() {
        val config = ChamberConfig.aquarium().copy(
            dimensions = ChamberDimensions(11.2f, 6.3f, 5.4f), splitHeight = .67f,
            materialMode = ChamberMaterialMode.MACRO_LAYOUT, layout = ChamberLayout.OPPOSING_WALLS,
            paletteLow = .17f, paletteHigh = .89f, materialReference = .61f, materialOffsetScale = .32f,
            placementMode = ChamberPlacementMode.EXPLICIT, boundaryBlendMetres = .09f,
            targets = ChamberSurfaceTargets(.12f, .24f, .36f, .48f, .6f, .72f, .84f, .96f, .13f, .91f),
            tuningRatios = ChamberTuningRatios(.75f, 1.2f, 2.3f, 3.7f),
            instrumentEnabled = ChamberInstrumentEnabled(true, false, true, false),
        )
        val decoded = ChamberConfig.fromJsonValue(Json.parse(Json.write(config.toJsonValue())))
        assertEquals(config, decoded)
        assertEquals(prepare(config, .29f, .86f, .37f), prepare(decoded, .29f, .86f, .37f))
        val incomplete = JsonValue.Obj(config.toJsonValue().entries - "materialPolicy")
        assertFailsWith<JsonException> { ChamberConfig.fromJsonValue(incomplete) }
        assertFailsWith<IllegalArgumentException> { ChamberTuningRatios(cowbell = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { ChamberTuningRatios(triangle = 9f) }
    }
}
