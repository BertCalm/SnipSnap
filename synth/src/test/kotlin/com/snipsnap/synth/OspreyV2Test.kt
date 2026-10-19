package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R2 is measured at the controls on its listening page, not only in an isolated bright tap.
 * These are engineering contrast and masking checks, not a claim that a listener will like
 * or identify the coastlines. The paired R1/R2 audition remains the sound-design gate.
 */
class OspreyV2Test {
    private val shores = listOf(0f, .25f, .5f, .75f, 1f)
    private val seas = listOf(.1f, .5f, .9f)
    private val controls = mapOf("MOTION" to .45f, "DEPTH" to .55f, "SPACE" to 0f, "HOLD" to .45f)

    private fun probe(shore: Float = 0f, sea: Float = .5f, extra: Map<String, Float> = emptyMap(),
        seed: Int = 731, midi: Int = 60, velocity: Float = 1f, seconds: Float = 8f): Osprey.Probe =
        Osprey.probe(OspreyVoice.SWELL, controls + mapOf("SHORE" to shore, "SEA" to sea) + extra,
            midi = midi, velocity = velocity, seed = seed, durationSeconds = seconds, modelVersion = 2)

    private fun channel(stereo: FloatArray, c: Int = 0) = FloatArray(stereo.size / 2) { stereo[it * 2 + c] }

    private fun rms(stereo: FloatArray, start: Double = 0.0, end: Double = stereo.size / (2.0 * Dsp.RATE)): Double {
        val first = (start * Dsp.RATE * 2).roundToInt().coerceIn(0, stereo.size)
        val last = (end * Dsp.RATE * 2).roundToInt().coerceIn(first, stereo.size)
        var sum = 0.0
        for (i in first until last) sum += stereo[i].toDouble() * stereo[i]
        return sqrt(sum / (last - first).coerceAtLeast(1))
    }

    private data class Spectrum(val power: DoubleArray, val binHz: Double)

    /** Welch body power avoids accepting a single initiating impulse as persistent identity. */
    private fun spectrum(stereo: FloatArray, start: Double = .3, end: Double = 2.0): Spectrum {
        val samples = channel(stereo)
        val first = (start * Dsp.RATE).roundToInt()
        val last = (end * Dsp.RATE).roundToInt().coerceAtMost(samples.size)
        val n = 32_768
        require(last - first >= n)
        val total = DoubleArray(n / 2)
        var windows = 0
        var at = first
        while (at + n <= last) {
            val re = FloatArray(n) { i -> samples[at + i] * (.5 - .5 * cos(2 * PI * i / (n - 1))).toFloat() }
            val im = FloatArray(n)
            Fft.forward(re, im)
            for (k in total.indices) total[k] += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            windows++
            at += n / 2
        }
        assertTrue(windows > 0)
        return Spectrum(DoubleArray(total.size) { total[it] / windows }, Dsp.RATE.toDouble() / n)
    }

    private fun fraction(s: Spectrum, lower: Double, upper: Double = 18_000.0): Double {
        var region = 0.0
        var total = 0.0
        for (k in 1 until s.power.size) {
            val hz = k * s.binHz
            if (hz < 30 || hz > 18_000) continue
            total += s.power[k]
            if (hz in lower..upper) region += s.power[k]
        }
        assertTrue(total > 1e-12, "fraction needs audible body power")
        return region / total
    }

    private fun mass(s: Spectrum): DoubleArray {
        val bins = DoubleArray(40)
        for (k in 1 until s.power.size) {
            val hz = k * s.binHz
            if (hz < 30 || hz > 18_000) continue
            val band = (ln(hz / 30) / ln(2.0) * 4).toInt().coerceIn(bins.indices)
            bins[band] += s.power[k]
        }
        val total = bins.sum()
        assertTrue(total > 1e-12)
        return DoubleArray(bins.size) { bins[it] / total }
    }

    /** Fifty-ms power windows hear gesture duration and repeated contacts, without output gain. */
    private fun temporalMass(stereo: FloatArray, seconds: Double = 2.0): DoubleArray {
        val frames = minOf((seconds * Dsp.RATE).roundToInt(), stereo.size / 2)
        val bins = DoubleArray((seconds / .05).roundToInt())
        for (frame in 0 until frames) {
            val at = frame * 2
            bins[minOf(bins.lastIndex, frame * bins.size / frames)] +=
                stereo[at].toDouble() * stereo[at] + stereo[at + 1].toDouble() * stereo[at + 1]
        }
        val total = bins.sum()
        assertTrue(total > 1e-12)
        return DoubleArray(bins.size) { bins[it] / total }
    }

    private fun distance(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { abs(a[it] - b[it]) }

    private fun shapeError(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        var aa = 0.0; var ab = 0.0; var bb = 0.0
        for (i in a.indices) { aa += a[i].toDouble() * a[i]; ab += a[i].toDouble() * b[i]; bb += b[i].toDouble() * b[i] }
        val gain = ab / aa.coerceAtLeast(1e-30)
        var residual = 0.0
        for (i in a.indices) { val d = b[i] - gain * a[i]; residual += d * d }
        return sqrt(residual / bb.coerceAtLeast(1e-30))
    }

    @Test
    fun `model two repeatability covers independent source contacts calls and all taps`() {
        val a = probe(.37f, .73f, mapOf("DEPTH" to .8f, "SPACE" to .55f))
        probe(.9f, .1f, seed = -71, seconds = 2f)
        val b = probe(.37f, .73f, mapOf("DEPTH" to .8f, "SPACE" to .55f))
        for ((x, y) in listOf(a.samples to b.samples, a.ocean to b.ocean, a.primary to b.primary,
            a.sympathy to b.sympathy, a.creatures to b.creatures, a.dry to b.dry)) assertContentEquals(x, y)
        assertEquals(a.contactEvents, b.contactEvents)
        assertEquals(a.callEvents, b.callEvents)
        assertEquals(a.metrics, b.metrics)
        assertTrue(a.contactEvents.isNotEmpty(), "determinism must exercise weather contacts")
        assertTrue(shapeError(a.samples, probe(.37f, .73f, mapOf("DEPTH" to .8f, "SPACE" to .55f), seed = 732).samples) > .1,
            "variation seed changed only a scalar level")
    }

    @Test
    fun `calm colored bodies survive the root at the actual listening controls`() {
        for (shore in shores) {
            val current = probe(shore, .1f)
            val full = spectrum(current.samples)
            val primary = spectrum(current.primary)
            val root = current.rootHz.toDouble()
            val fullRoot = fraction(full, root * .97, root * 1.03)
            val primaryRoot = fraction(primary, root * .97, root * 1.03)
            val upper = fraction(primary, root * 1.5)
            val legacy = Osprey.probe(OspreyVoice.SWELL,
                controls + mapOf("SHORE" to shore, "SEA" to .1f), seed = 731, durationSeconds = 8f, modelVersion = 1)
            val oldRoot = fraction(spectrum(legacy.samples), root * .97, root * 1.03)
            val opening = rms(current.samples, 0.0, .12) / rms(current.samples, .3, 2.0)
            println("OSPREY R2 shore=$shore calm root R1=$oldRoot R2=$fullRoot primaryRoot=$primaryRoot upper=$upper opening/body RMS=$opening")
            assertTrue(fullRoot < .75 && primaryRoot < .75,
                "shore $shore still masks its body with the common root: full=$fullRoot primary=$primaryRoot")
            assertTrue(upper >= .20, "shore $shore calm upper-modal power is only $upper")
            assertTrue(primaryRoot > .02, "shore $shore lost its useful root instead of revealing its body")
        }
    }

    @Test
    fun `hybrid coast bodies retain upper modes while bow pressure follows their tuning`() {
        val failures = mutableListOf<String>()
        for (shore in listOf(.125f, .30f, .375f, .45f, .625f, .875f)) {
            for (sea in seas) {
                val p = probe(shore, sea)
                val body = spectrum(p.primary)
                val root = p.rootHz.toDouble()
                val rootPower = fraction(body, root * .97, root * 1.03)
                val upperPower = fraction(body, root * 1.5)
                println("OSPREY R2 hybrid shore=$shore sea=$sea primaryRoot=$rootPower upper=$upperPower")
                if (rootPower >= .75 || upperPower < .20)
                    failures += "shore $shore sea $sea lost its blended material: root=$rootPower upper=$upperPower"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `neighboring shores differ in both spectrum and gesture at every sea state`() {
        for (sea in seas) {
            val renders = shores.map { probe(it, sea) }
            val spectra = renders.map { mass(spectrum(it.samples)) }
            val gestures = renders.map { temporalMass(it.samples) }
            val spectralDistances = (0..3).map { distance(spectra[it], spectra[it + 1]) }
            val gestureDistances = (0..3).map { distance(gestures[it], gestures[it + 1]) }
            println("OSPREY R2 sea=$sea adjacent spectral L1=$spectralDistances gesture L1=$gestureDistances")
            for (i in 0..3) {
                assertTrue(spectralDistances[i] > .25,
                    "sea $sea coast $i/${i + 1}: body redistribution ${spectralDistances[i]} still resembles R1")
                assertTrue(gestureDistances[i] > .20,
                    "sea $sea coast $i/${i + 1}: normalized source gesture scarcely differs: ${gestureDistances[i]}")
            }
        }
    }

    @Test
    fun `weather changes the source mechanism and rendered contour within every coast`() {
        val failures = mutableListOf<String>()
        for (shore in shores) {
            val renders = seas.map { probe(shore, it) }
            for ((state, p) in renders.withIndex()) {
                assertEquals(3, p.weatherWeights.size)
                assertTrue(abs(p.weatherWeights.sum() - 1f) < .001f)
                assertEquals(state, p.weatherWeights.indices.maxBy { p.weatherWeights[it] },
                    "shore $shore sea ${seas[state]} lacks its intended laminar/chop/breaker mechanism")
                val wanted = Osprey.ContactKind.entries[state]
                assertTrue(p.contactEvents.any { it.kind == wanted && it.energy > 0f },
                    "shore $shore sea ${seas[state]} never produced $wanted")
                assertTrue(p.contactEvents.all { it.timeSeconds >= 0 && it.timeSeconds < 8f &&
                    it.energy.isFinite() && it.energy >= 0f && it.widthMs.isFinite() && it.widthMs > 0f })
            }
            val gesture = renders.map { temporalMass(it.samples, seconds = 4.5) }
            val texture = renders.map { mass(spectrum(it.samples)) }
            val gestureChanges = (0..1).map { distance(gesture[it], gesture[it + 1]) }
            val textureChanges = (0..1).map { distance(texture[it], texture[it + 1]) }
            println("OSPREY R2 shore=$shore calm/rough/storm gesture L1=$gestureChanges spectral L1=$textureChanges contacts=${renders.map { it.contactEvents.size }}")
            for (i in 0..1) {
                if (gestureChanges[i] <= .15) failures +=
                    "shore $shore sea ${seas[i]} to ${seas[i + 1]} changes noise level rather than gesture: ${gestureChanges[i]}"
                if (textureChanges[i] <= .15) failures +=
                    "shore $shore sea ${seas[i]} to ${seas[i + 1]} lacks gain-independent texture contrast: ${textureChanges[i]}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    @Test
    fun `coast identity remains larger than same coast variation from another seed`() {
        val first = shores.map { probe(it, .5f) }
        val second = shores.map { probe(it, .5f, seed = 947) }
        val firstTexture = first.map { mass(spectrum(it.samples)) }
        val secondTexture = second.map { mass(spectrum(it.samples)) }
        for (i in shores.indices) {
            val within = distance(firstTexture[i], secondTexture[i])
            val between = shores.indices.filter { it != i }.minOf { distance(firstTexture[i], firstTexture[it]) }
            println("OSPREY R2 coast=$i spectral seed variation=$within nearest other coast=$between")
            assertTrue(between > within,
                "coast $i changes more with variation seed ($within) than coastline ($between)")
        }
    }

    @Test
    fun `root remains accurately audible across all coasts and three registers`() {
        for ((midi, hz) in listOf(48 to 130.81278265, 60 to 261.62556530, 72 to 523.25113060)) {
            for (shore in shores) {
                val p = probe(shore, .1f, mapOf("DEPTH" to 0f), midi = midi, seconds = 3f)
                assertEquals(listOf(0), p.rootIndices.toList(), "model 2 should have one authoritative root")
                val s = spectrum(p.primary)
                val lo = ((hz * .94) / s.binHz).toInt().coerceAtLeast(1)
                val hi = ((hz * 1.06) / s.binHz).toInt()
                val best = (lo..hi).maxBy { s.power[it] }
                val a = ln(s.power[best - 1].coerceAtLeast(1e-30))
                val b = ln(s.power[best].coerceAtLeast(1e-30))
                val c = ln(s.power[best + 1].coerceAtLeast(1e-30))
                val offset = (.5 * (a - c) / (a - 2 * b + c)).coerceIn(-.5, .5)
                val measured = (best + offset) * s.binHz
                val cents = 1200 * ln(measured / hz) / ln(2.0)
                assertTrue(abs(cents) < 20, "shore $shore MIDI $midi root is $cents cents out")
                assertTrue(fraction(s, hz * .97, hz * 1.03) > .01, "root diagnostic is not audible")
            }
        }
    }

    @Test
    fun `representative active scene morphs continuously at all coastline boundaries`() {
        val failures = mutableListOf<String>()
        for (boundary in listOf(.25f, .5f, .75f)) {
            val low = probe(boundary - .00001f, .65f, mapOf("DEPTH" to .7f), seed = 871, seconds = 6f)
            val high = probe(boundary + .00001f, .65f, mapOf("DEPTH" to .7f), seed = 871, seconds = 6f)
            val error = shapeError(low.samples, high.samples)
            println("OSPREY R2 boundary=$boundary full residual=$error primary=${shapeError(low.primary, high.primary)} creatures=${shapeError(low.creatures, high.creatures)}")
            if (error >= .025) failures += "a tiny coastline change jumps the active scene at $boundary: $error"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    @Test
    fun `dry and creature controls preserve honest component decomposition`() {
        val wet = probe(.6f, .8f, mapOf("SPACE" to .8f))
        val dry = probe(.6f, .8f)
        assertContentEquals(dry.samples, dry.dry)
        assertContentEquals(dry.primary, wet.primary)
        assertContentEquals(dry.creatures, wet.creatures)
        for (i in dry.samples.indices) {
            assertTrue(abs(dry.dry[i] - (dry.primary[i].toDouble() + dry.sympathy[i] + dry.creatures[i])) < 1e-6)
        }
        val mute = probe(.6f, .8f, mapOf("DEPTH" to 0f))
        assertTrue(mute.creatures.all { it == 0f })
        assertTrue(rms(dry.creatures) > 1e-5)
        assertTrue(shapeError(dry.ocean, mute.ocean) < 1e-6, "creature control changed the ocean source stream")
    }

    @Test
    fun `velocity changes injected energy and silence does not retain source activity`() {
        for (shore in shores) {
            val soft = probe(shore, .5f, velocity = .25f, seconds = 4f)
            val strong = probe(shore, .5f, velocity = 1f, seconds = 4f)
            assertTrue(soft.metrics.injectedEnergy > 0,
                "shore $shore lacks an injection diagnostic for physical velocity")
            assertTrue(strong.metrics.injectedEnergy > soft.metrics.injectedEnergy * 2,
                "shore $shore velocity only scaled final playback")
            assertTrue(rms(strong.samples) > rms(soft.samples) * 1.5,
                "shore $shore source energy was normalized away")
        }
        val silent = probe(.4f, .9f, mapOf("SPACE" to 1f, "DEPTH" to 1f), velocity = 0f, seconds = 3f)
        assertTrue(silent.samples.all { it == 0f })
        assertEquals(0.0, silent.metrics.injectedEnergy)
        assertTrue(silent.contactEvents.none { it.energy > 0f })
    }

    @Test
    fun `extreme settings remain finite bounded and free of material DC`() {
        val names = listOf("SHORE", "SEA", "MOTION", "DEPTH", "SPACE", "HOLD")
        val corners = listOf(emptyMap(), names.associateWith { 0f }, names.associateWith { 1f },
            mapOf("SEA" to 1f, "MOTION" to 0f, "DEPTH" to 1f, "SPACE" to 1f, "HOLD" to 0f))
        for (midi in listOf(36, 60, 84)) for (extra in corners) {
            val p = probe(.51f, .5f, extra, midi = midi, seconds = 4f)
            assertEquals(0, p.metrics.nonFiniteRecoveries)
            assertTrue(p.samples.all { it.isFinite() && it in -1f..1f }, "MIDI $midi $extra left PCM bounds")
            assertTrue(rms(p.samples) > 1e-4, "MIDI $midi $extra is effectively silent")
            for (c in 0..1) {
                val samples = channel(p.samples, c)
                val dc = abs(samples.average())
                assertTrue(dc < .003 && dc < rms(p.samples) * .03, "MIDI $midi $extra has material DC $dc")
            }
        }
    }

    @Test
    fun `finite weather stops feeding the bank and passive modal energy falls`() {
        for (shore in shores) {
            val p = probe(shore, .9f, mapOf("HOLD" to 0f, "DEPTH" to 0f), seconds = 10f)
            assertTrue(p.metrics.excitationStopSeconds < 8.5f, "shore $shore lacks a finite release window")
            assertTrue(p.metrics.peakModalEnergy > 1e-10)
            assertTrue(p.metrics.finalModalEnergy < p.metrics.peakModalEnergy * .20,
                "shore $shore retained or created passive energy after forcing stopped: ${p.metrics}")
            assertTrue(rms(p.samples, 9.0, 10.0) < rms(p.samples, .3, 2.0) * .5,
                "shore $shore audible tail did not decline")
        }
    }
}
