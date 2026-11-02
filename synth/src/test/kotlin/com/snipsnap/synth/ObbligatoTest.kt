package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * First-proof contracts, before listening approval: a reproducible, dry flute/reed duet;
 * the same intended C4–C5 note for both; finite audio with <.015 DC; and reciprocal
 * response that survives a source-mute counterfactual. Musical acceptance belongs to
 * the listening pack, rather than to similarity with a particular implementation.
 */
class ObbligatoTest {
    private val states = linkedMapOf(
        "Harmony" to 0f,
        "Play" to (1f / 7f),
        "Fight" to (3f / 7f),
        "Protect" to (6f / 7f),
    )

    private fun macros(bond: Float = 0f, breath: Float = .65f, mischief: Float = .35f, hold: Float = .6f) =
        mapOf("BREATH" to breath, "BOND" to bond, "MISCHIEF" to mischief, "HOLD" to hold)

    private fun rms(x: FloatArray): Double =
        sqrt(x.sumOf { it.toDouble() * it } / x.size.coerceAtLeast(1))

    private fun windowRms(x: FloatArray, fromSeconds: Double, untilSeconds: Double, rate: Int): Double {
        val from = (fromSeconds * rate).toInt().coerceAtLeast(0)
        val until = minOf(x.size, (untilSeconds * rate).toInt())
        require(until > from) { "audio acceptance window is outside the rendered note" }
        var energy = 0.0
        for (i in from until until) energy += x[i].toDouble() * x[i]
        return sqrt(energy / (until - from))
    }

    private fun standardDeviation(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private fun envelopeCorrelation(a: List<Double>, b: List<Double>): Double {
        assertEquals(a.size, b.size)
        val meanA = a.average()
        val meanB = b.average()
        var covariance = 0.0
        var energyA = 0.0
        var energyB = 0.0
        for (i in a.indices) {
            val x = a[i] - meanA
            val y = b[i] - meanB
            covariance += x * y
            energyA += x * x
            energyB += y * y
        }
        return covariance / sqrt(energyA * energyB).coerceAtLeast(1e-20)
    }

    /** Match each complete note's RMS, so a gain-only relationship fails this gate. */
    private fun matchedDistance(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size, "same-note relationship fixtures have different clocks")
        val ra = rms(a)
        val rb = rms(b)
        require(ra > 1e-10 && rb > 1e-10) { "relationship fixture is silent" }
        var energy = 0.0
        for (i in a.indices) {
            val delta = a[i] / ra - b[i] / rb
            energy += delta * delta
        }
        return sqrt(energy / a.size)
    }

    private fun relativeDifference(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size, "source ablation changed the note's clock")
        var error = 0.0
        for (i in a.indices) {
            val delta = a[i].toDouble() - b[i]
            error += delta * delta
        }
        return sqrt(error / a.size) / rms(a).coerceAtLeast(1e-10)
    }

    /** Ten-millisecond energy bins preserve breath phrasing while discarding phase. */
    private fun envelope(x: FloatArray, rate: Int): FloatArray {
        val hop = maxOf(1, rate / 100)
        val bins = FloatArray((x.size + hop - 1) / hop) { block ->
            val from = block * hop
            val until = minOf(x.size, from + hop)
            var energy = 0.0
            for (i in from until until) energy += x[i].toDouble() * x[i]
            sqrt(energy / (until - from)).toFloat()
        }
        val scale = rms(bins).coerceAtLeast(1e-10)
        return FloatArray(bins.size) { (bins[it] / scale).toFloat() }
    }

    private fun envelopeDistance(a: FloatArray, b: FloatArray, rate: Int): Double {
        val ea = envelope(a, rate)
        val eb = envelope(b, rate)
        assertEquals(ea.size, eb.size)
        var energy = 0.0
        for (i in ea.indices) {
            val delta = ea[i].toDouble() - eb[i]
            energy += delta * delta
        }
        return sqrt(energy / ea.size)
    }

    @Test
    fun `note seed and control order reproduce the dry duet and each player`() {
        val controls = macros(bond = 1f / 7f)
        val a = Obbligato.probe(controls, midi = 67, velocity = .7f, seed = 17)
        val b = Obbligato.probe(controls.entries.reversed().associate { it.key to it.value }, midi = 67, velocity = .7f, seed = 17)
        assertContentEquals(a.snip.samples, b.snip.samples, "map order changed the performance")
        assertContentEquals(a.air, b.air, "air player is not reproducible")
        assertContentEquals(a.reed, b.reed, "reed player is not reproducible")
        assertContentEquals(a.airEffort, b.airEffort, "air gesture is not reproducible")
        assertContentEquals(a.reedEffort, b.reedEffort, "reed gesture is not reproducible")
        assertEquals(a.diagnostics, b.diagnostics)
        val otherSeed = Obbligato.probe(controls, midi = 67, velocity = .7f, seed = 18)
        assertTrue(!a.snip.samples.contentEquals(otherSeed.snip.samples), "seed is not part of the saved performance")
    }

    @Test
    fun `patch and pad recipes retain note velocity seed model and regenerate audio`() {
        val source = ObbligatoPatch("Dry Play", macros(1f / 7f), midi = 67, velocity = .7f, seed = 17)
        val restored = Patches.fromJsonText(source.toJsonText())
        assertEquals(source, restored)
        assertEquals(source.toJsonText(), restored.toJsonText())
        assertContentEquals(source.render().samples, restored.render().samples)
        val recipe = PadRecipe(source)
        val again = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, again)
        assertNull(again.fx, "the first proof is dry")
        assertContentEquals(source.render().samples, again.render().samples)
        val edit = macros(3f / 7f)
        assertEquals(source.copy(name = "Dry Fight", macros = edit), Patches.edited(source, "Dry Fight", edit))
        assertEquals(source.copy(macros = edit), source.withMacros(edit))
    }

    @Test
    fun `native velocity preserves the saved note seed and relationship while changing excitation`() {
        val patch = ObbligatoPatch("Dry Play", macros(1f / 7f), midi = 67, velocity = .7f, seed = 17)
        val soft = Velocity.atVelocity(patch, .3f)
        val hard = Velocity.atVelocity(patch, 1f)
        assertContentEquals(patch.copy(velocity = .3f).render().samples, soft.samples,
            "native velocity lost the saved pitch, seed, or relationship")
        assertContentEquals(patch.copy(velocity = 1f).render().samples, hard.samples)
        assertTrue(matchedDistance(soft.samples, hard.samples) > .01,
            "soft and hard notes differ only in final gain, rather than their source excitation")
    }

    @Test
    fun `four relationships speak before leveling and remain finite within output and DC budgets`() {
        for ((name, bond) in states) for (midi in listOf(60, 72)) for (extreme in listOf(false, true)) {
            val controls = macros(bond, breath = if (extreme) 1f else .65f, mischief = if (extreme) 1f else .35f)
            val report = Obbligato.probe(controls, midi = midi, seed = 9)
            val output = Obbligato.render(controls, midi = midi, seed = 9)
            val label = "$name MIDI $midi extreme=$extreme"
            for ((tap, x) in listOf("air" to report.air, "reed" to report.reed, "raw duet" to report.snip.samples, "output" to output.samples)) {
                assertTrue(x.isNotEmpty(), "$label $tap is empty")
                assertTrue(x.all { it.isFinite() }, "$label $tap went non-finite")
                assertTrue(rms(x) > 1e-4, "$label $tap is silent before leveling")
            }
            assertEquals(1, output.channels, "the first proof must carry the relationship in mono")
            assertTrue(output.peak() <= 1f, "$label exceeds full scale: ${output.peak()}")
            assertTrue(abs(output.samples.average()) < .015, "$label DC=${output.samples.average()}")
            assertTrue(abs(report.snip.samples.average()) < .015, "$label prelevel DC=${report.snip.samples.average()}")
            assertEquals(report.snip.samples.size, report.air.size)
            assertEquals(report.snip.samples.size, report.reed.size)
            var sumError = 0.0
            for (i in report.snip.samples.indices) {
                sumError = maxOf(sumError, abs(report.snip.samples[i].toDouble() - report.air[i] - report.reed[i]))
            }
            assertTrue(sumError < 1e-6, "$label raw source taps do not reproduce the actual duet: error=$sumError")
        }
    }

    @Test
    fun `both players remain materially present in every same-note starter preset`() {
        val presets = ObbligatoPresets.all()
        assertEquals(4, presets.size, "the first audition covers four relationship candidates")
        for (preset in presets) {
            val report = Obbligato.probe(preset.macros, midi = 60, velocity = .8f, seed = 0)
            val air = rms(report.air)
            val reed = rms(report.reed)
            assertTrue(air > 1e-4 && reed > 1e-4, "${preset.name} lost a player before final leveling")
            // Read the actual additive stems at their existing radiation gains. Matching
            // each stem's level here would conceal the imbalance this gate must catch.
            val balanceDb = 20.0 * log10(air / reed)
            assertTrue(balanceDb in -12.0..12.0,
                "${preset.name}: air/reed balance is $balanceDb dB; both must remain materially present")
        }
    }

    @Test
    fun `both players actually sound the requested shared register rather than their Bore roots`() {
        for (midi in listOf(60, 66, 72)) {
            val p = Obbligato.probe(macros(mischief = 0f, hold = 1f), midi = midi, seed = 3)
            val hz = (440.0 * 2.0.pow((midi - 69) / 12.0)).toFloat()
            for ((name, samples) in listOf("air" to p.air, "reed" to p.reed)) {
                val cents = BoreMeasure.cents(samples, hz, .8f, 1.2f, p.snip.sampleRate)
                assertTrue(cents.isFinite() && abs(cents) < 25.0, "$name MIDI $midi played $cents cents from its requested note")
                // An octave-up voice also repeats at the requested period. Its half-period
                // correlation is equally strong; requiring a real first period catches it.
                val period = p.snip.sampleRate / hz.toDouble()
                val root = correlation(samples, period.toInt(), .8f, 1.2f, p.snip.sampleRate)
                val octave = correlation(samples, (period / 2).toInt(), .8f, 1.2f, p.snip.sampleRate)
                assertTrue(root > .75, "$name MIDI $midi has no stable requested-period tone: $root")
                assertTrue(root > octave + .02, "$name MIDI $midi is dominated by an octave-up tone: root=$root octave=$octave")
            }
        }
    }

    private fun correlation(x: FloatArray, lag: Int, from: Float, until: Float, rate: Int): Double {
        val first = (from * rate).toInt()
        val end = minOf(x.size - lag, (until * rate).toInt())
        var cross = 0.0
        var left = 0.0
        var right = 0.0
        for (i in first until end) {
            cross += x[i].toDouble() * x[i + lag]
            left += x[i].toDouble() * x[i]
            right += x[i + lag].toDouble() * x[i + lag]
        }
        return cross / sqrt(left * right).coerceAtLeast(1e-12)
    }

    @Test
    fun `all four relationships change the matched mono performance beyond gain`() {
        val reports = states.mapValues { (_, bond) -> Obbligato.probe(macros(bond, hold = 1f), seed = 11) }
        for ((index, a) in reports.entries.withIndex()) for (b in reports.entries.drop(index + 1)) {
            val distance = matchedDistance(a.value.snip.samples, b.value.snip.samples)
            assertTrue(distance > .03, "${a.key}/${b.key} differ only in gain: $distance")
        }
        val harmony = reports.getValue("Harmony")
        for (name in listOf("Play", "Fight", "Protect")) {
            val other = reports.getValue(name)
            val air = envelopeDistance(harmony.air, other.air, harmony.snip.sampleRate)
            val reed = envelopeDistance(harmony.reed, other.reed, harmony.snip.sampleRate)
            assertTrue(maxOf(air, reed) > .02, "$name has no change in either player's normalized breath phrasing: air=$air reed=$reed")
        }
    }

    @Test
    fun `legacy Play audibly hands emphasis from air to reed and back to air`() {
        val starter = ObbligatoPresets.all().single { it.name == "Play" }.macros
        for (hold in listOf(.45f, 1f)) for (midi in listOf(60, 63, 65, 67)) {
            val play = Obbligato.probe(starter + ("HOLD" to hold), midi = midi, velocity = .8f, seed = 0, model = Obbligato.LEGACY_MODEL)
            val label = "Play MIDI $midi HOLD $hold"
            val rate = play.snip.sampleRate
            val heldSeconds = play.snip.durationSeconds.toDouble() - .75
            val windows = listOf(.15 to .27, .45 to .58, .80 to .92)
            val balance = windows.map { (from, until) ->
                val air = windowRms(play.air, from * heldSeconds, until * heldSeconds, rate)
                val reed = windowRms(play.reed, from * heldSeconds, until * heldSeconds, rate)
                assertTrue(air > 1e-5 && reed > 1e-5, "$label handoff silenced a player completely")
                20.0 * log10(air / reed)
            }
            // These are actual rendered role contrasts at shared radiation gains. A change
            // in the requested control trace alone cannot establish an audible handoff.
            assertTrue(balance[0] > 6.0, "$label first gesture does not clearly favor air: $balance dB")
            assertTrue(balance[1] < -6.0, "$label never clearly gives the phrase to the reed: $balance dB")
            assertTrue(balance[2] > 6.0, "$label does not clearly hand the phrase back to air: $balance dB")
        }
    }

    @Test
    fun `legacy Fight overlaps continuously while prominence reverses several times`() {
        val starter = ObbligatoPresets.all().single { it.name == "Fight" }.macros
        for (hold in listOf(0f, .45f, 1f)) for (midi in listOf(60, 63, 65, 67)) {
            val fight = Obbligato.probe(starter + ("HOLD" to hold), midi = midi, velocity = .8f, seed = 0, model = Obbligato.LEGACY_MODEL)
            val label = "Fight MIDI $midi HOLD $hold"
            val heldSeconds = fight.snip.durationSeconds.toDouble() - .75
            val air = ArrayList<Double>()
            val reed = ArrayList<Double>()
            val duet = ArrayList<Double>()
            var from = .4
            while (from + .10 <= heldSeconds - .10) {
                air.add(windowRms(fight.air, from, from + .10, fight.snip.sampleRate))
                reed.add(windowRms(fight.reed, from, from + .10, fight.snip.sampleRate))
                duet.add(windowRms(fight.snip.samples, from, from + .10, fight.snip.sampleRate))
                from += .05
            }
            assertTrue(air.size > 10, "$label has too little active audio to establish sustained competition")
            for ((name, levels) in listOf("air" to air, "reed" to reed)) {
                val low = levels.min()
                val high = levels.max()
                assertTrue(low > 1e-4, "$label $name drops below a material raw level: $low")
                assertTrue(low >= high * .15,
                    "$label $name loses capture during the active note: min RMS=$low max RMS=$high")
            }
            // Separate stems have no cancellation from their relative wave phases. Their
            // combined RMS energy catches the old dropout seam independently of beating.
            val sourceEnergy = air.indices.map { sqrt(air[it] * air[it] + reed[it] * reed[it]) }
            assertTrue(sourceEnergy.min() >= sourceEnergy.max() * .60,
                "$label loses source energy between turns: min=${sourceEnergy.min()} max=${sourceEnergy.max()}")
            // The complete mono note may beat. Allow its floor more room than the stems,
            // while still rejecting a pronounced pause inside a continuously played note.
            assertTrue(duet.min() >= duet.max() * .30,
                "$label complete note has a pause: min RMS=${duet.min()} max RMS=${duet.max()}")

            var lastRole = 0
            var reversals = 0
            for (i in air.indices) {
                val balanceDb = 20.0 * log10(air[i] / reed[i])
                val role = when {
                    balanceDb > 3.0 -> 1
                    balanceDb < -3.0 -> -1
                    else -> 0
                }
                // Keep the previous decisive role through the neutral band. Counting
                // zero crossings or individual samples would mistake phase for rivalry.
                if (role != 0 && role != lastRole) {
                    if (lastRole != 0) reversals++
                    lastRole = role
                }
            }
            assertTrue(reversals >= 3, "$label prominence reverses only $reversals times")
        }
    }

    @Test
    fun `Fight opposing gestures outweigh the common beating heard in model four`() {
        val starter = ObbligatoPresets.all().single { it.name == "Fight" }.macros + ("HOLD" to .45f)
        for (midi in listOf(60, 63, 65, 67)) {
            val current = Obbligato.probe(starter, midi = midi, velocity = .8f, seed = 0, model = 5)
            val previous = Obbligato.probe(starter, midi = midi, velocity = .8f, seed = 0, model = 4)
            val label = "Fight MIDI $midi HOLD .45"

            fun levels(report: Obbligato.Report): Triple<List<Double>, List<Double>, List<Double>> {
                val air = ArrayList<Double>()
                val reed = ArrayList<Double>()
                val interference = ArrayList<Double>()
                val heldSeconds = report.snip.durationSeconds.toDouble() - .75
                var from = .4
                while (from + .05 <= heldSeconds - .10) {
                    val a = windowRms(report.air, from, from + .05, report.snip.sampleRate)
                    val b = windowRms(report.reed, from, from + .05, report.snip.sampleRate)
                    val duet = windowRms(report.snip.samples, from, from + .05, report.snip.sampleRate)
                    val sourcePower = a * a + b * b
                    assertTrue(sourcePower > 1e-10, "$label has no material overlapping sources")
                    air.add(a)
                    reed.add(b)
                    // The difference between mixed power and independent source power
                    // isolates common interference that can mask otherwise opposing roles.
                    interference.add((duet * duet - sourcePower) / sourcePower)
                    from += .01
                }
                return Triple(air, reed, interference)
            }

            val (air, reed, interference) = levels(current)
            val oldInterference = levels(previous).third
            val correlation = envelopeCorrelation(air, reed)
            assertTrue(correlation < -.5,
                "$label sources swell together rather than contest prominence: envelope correlation=$correlation")
            val meanAir = air.average()
            val meanReed = reed.average()
            val contrast = air.indices.map { .5 * (air[it] / meanAir - reed[it] / meanReed) }
            val roleMovement = standardDeviation(contrast)
            val commonBeat = standardDeviation(interference)
            assertTrue(roleMovement > 1e-6, "$label has no material opposing role movement")
            assertTrue(commonBeat <= roleMovement * .5,
                "$label common beating masks opposing gestures: beat variation=$commonBeat role variation=$roleMovement")
            assertTrue(commonBeat <= standardDeviation(oldInterference) * .75,
                "$label has not reduced the common beating heard in model four: current=$commonBeat previous=${standardDeviation(oldInterference)}")
        }
    }

    @Test
    fun `Protect presents a developed flute tone before the reed enters`() {
        val starter = ObbligatoPresets.all().single { it.name == "Protect" }.macros
        for (hold in listOf(0f, .45f, 1f)) for (midi in listOf(60, 63, 65, 67)) {
            val protect = Obbligato.probe(starter + ("HOLD" to hold), midi = midi, velocity = .8f, seed = 0)
            val label = "Protect opening MIDI $midi HOLD $hold"
            val air = windowRms(protect.air, .25, .60, protect.snip.sampleRate)
            val reed = windowRms(protect.reed, .25, .60, protect.snip.sampleRate)
            val laterAir = windowRms(protect.air, 1.0, 1.3, protect.snip.sampleRate)
            // A requested flute-first gate is insufficient: its physical source must have
            // developed a material tone before the conditioned reed can mask the opening.
            assertTrue(air > 1e-4, "$label contains only a negligible air/noise head: RMS=$air")
            assertTrue(air > laterAir * .5,
                "$label flute has not developed before reed entry: intro RMS=$air body RMS=$laterAir")
            assertTrue(reed < air * 10.0.pow(-12.0 / 20.0),
                "$label is already masked by reed: air RMS=$air reed RMS=$reed")
        }
    }

    @Test
    fun `Protect lets air finish first while the reed carries the audible ending`() {
        val starter = ObbligatoPresets.all().single { it.name == "Protect" }.macros
        for (hold in listOf(.45f, 1f)) for (midi in listOf(60, 63, 65, 67)) {
            val protect = Obbligato.probe(starter + ("HOLD" to hold), midi = midi, velocity = .8f, seed = 0)
            val label = "Protect MIDI $midi HOLD $hold"
            val end = protect.snip.durationSeconds.toDouble()
            val air = windowRms(protect.air, end - .45, end - .10, protect.snip.sampleRate)
            val reed = windowRms(protect.reed, end - .45, end - .10, protect.snip.sampleRate)
            assertTrue(reed > 1e-4, "$label ending is silence rather than a reed carrying the phrase")
            assertTrue(air < reed * .25,
                "$label air player has not yielded the ending: air RMS=$air reed RMS=$reed")
        }
    }

    @Test
    fun `model two preserves Harmony and Fight exactly while advancing Play and Protect`() {
        for (bond in listOf(0f, 3f / 7f)) {
            val controls = macros(bond)
            val old = Obbligato.probe(controls, midi = 67, velocity = .7f, seed = 17, model = 1)
            val current = Obbligato.probe(controls, midi = 67, velocity = .7f, seed = 17, model = 2)
            assertContentEquals(old.snip.samples, current.snip.samples, "unchanged relationship $bond lost its prior sound")
            assertContentEquals(old.air, current.air)
            assertContentEquals(old.reed, current.reed)
            assertContentEquals(old.airEffort, current.airEffort)
            assertContentEquals(old.reedEffort, current.reedEffort)
        }
        for (bond in listOf(1f / 7f, 6f / 7f)) {
            val controls = macros(bond, hold = 1f)
            val old = Obbligato.probe(controls, seed = 0, model = 1)
            val current = Obbligato.probe(controls, seed = 0, model = 2)
            assertTrue(matchedDistance(old.snip.samples, current.snip.samples) > .03,
                "updated relationship $bond kept the legacy performance or changed only gain")
            assertTrue(maxOf(
                envelopeDistance(old.air, current.air, old.snip.sampleRate),
                envelopeDistance(old.reed, current.reed, old.snip.sampleRate),
            ) > .02, "updated relationship $bond has no audible change in breath phrasing")
        }
    }

    @Test
    fun `model three preserves Harmony Play and Fight exactly while advancing Protect opening`() {
        for (bond in listOf(0f, 1f / 7f, 3f / 7f)) {
            val controls = macros(bond)
            val old = Obbligato.probe(controls, midi = 67, velocity = .7f, seed = 17, model = 2)
            val current = Obbligato.probe(controls, midi = 67, velocity = .7f, seed = 17, model = 3)
            assertContentEquals(old.snip.samples, current.snip.samples, "unchanged relationship $bond lost its model two sound")
            assertContentEquals(old.air, current.air)
            assertContentEquals(old.reed, current.reed)
            assertContentEquals(old.airEffort, current.airEffort)
            assertContentEquals(old.reedEffort, current.reedEffort)
        }
        val controls = ObbligatoPresets.all().single { it.name == "Protect" }.macros
        val old = Obbligato.probe(controls, midi = 60, velocity = .8f, seed = 0, model = 2)
        val current = Obbligato.probe(controls, midi = 60, velocity = .8f, seed = 0, model = 3)
        assertTrue(matchedDistance(old.snip.samples, current.snip.samples) > .03,
            "Protect model three kept the previous performance or changed only gain")
        assertTrue(envelopeDistance(old.reed, current.reed, old.snip.sampleRate) > .02,
            "Protect model three did not change the reed's audible entry")
    }

    @Test
    fun `model four preserves Harmony Play and Protect exactly while advancing Fight continuity`() {
        for (bond in listOf(0f, 1f / 7f, 6f / 7f)) {
            val controls = macros(bond)
            val old = Obbligato.probe(controls, midi = 67, velocity = .7f, seed = 17, model = 3)
            val current = Obbligato.probe(controls, midi = 67, velocity = .7f, seed = 17, model = 4)
            assertContentEquals(old.snip.samples, current.snip.samples, "unchanged relationship $bond lost its model three sound")
            assertContentEquals(old.air, current.air)
            assertContentEquals(old.reed, current.reed)
            assertContentEquals(old.airEffort, current.airEffort)
            assertContentEquals(old.reedEffort, current.reedEffort)
        }
        val controls = ObbligatoPresets.all().single { it.name == "Fight" }.macros
        val old = Obbligato.probe(controls, midi = 60, velocity = .8f, seed = 0, model = 3)
        val current = Obbligato.probe(controls, midi = 60, velocity = .8f, seed = 0, model = 4)
        assertTrue(matchedDistance(old.snip.samples, current.snip.samples) > .03,
            "Fight model four kept the previous performance or changed only gain")
        assertTrue(maxOf(
            envelopeDistance(old.air, current.air, old.snip.sampleRate),
            envelopeDistance(old.reed, current.reed, old.snip.sampleRate),
        ) > .02, "Fight model four did not change the actual competing source phrases")
    }

    @Test
    fun `model five preserves Harmony Play and Protect exactly while advancing Fight rivalry`() {
        assertEquals(5, Obbligato.LEGACY_MODEL)
        for (bond in listOf(0f, 1f / 7f, 6f / 7f)) {
            val controls = macros(bond)
            val old = Obbligato.probe(controls, midi = 67, velocity = .7f, seed = 17, model = 4)
            val current = Obbligato.probe(controls, midi = 67, velocity = .7f, seed = 17, model = 5)
            assertContentEquals(old.snip.samples, current.snip.samples, "unchanged relationship $bond lost its model four sound")
            assertContentEquals(old.air, current.air)
            assertContentEquals(old.reed, current.reed)
            assertContentEquals(old.airEffort, current.airEffort)
            assertContentEquals(old.reedEffort, current.reedEffort)
        }
        val controls = ObbligatoPresets.all().single { it.name == "Fight" }.macros
        val old = Obbligato.probe(controls, midi = 60, velocity = .8f, seed = 0, model = 4)
        val current = Obbligato.probe(controls, midi = 60, velocity = .8f, seed = 0, model = 5)
        assertTrue(matchedDistance(old.snip.samples, current.snip.samples) > .03,
            "Fight model five kept the previous performance or changed only gain")
        assertTrue(maxOf(
            envelopeDistance(old.air, current.air, old.snip.sampleRate),
            envelopeDistance(old.reed, current.reed, old.snip.sampleRate),
        ) > .02, "Fight model five did not change the actual competing source phrases")
    }

    @Test
    fun `legacy models one through four remain editable and regenerate their saved performance`() {
        for (model in listOf(1, 2, 3, 4)) for (bond in states.values) {
            val legacy = ObbligatoPatch("Legacy", macros(bond), midi = 67, velocity = .7f, seed = 17, model = model)
            val restored = Patches.fromJsonText(legacy.toJsonText())
            assertEquals(legacy, restored, "saved renderer model $model was upgraded implicitly")
            assertContentEquals(Obbligato.render(legacy.macros, legacy.midi, legacy.velocity, legacy.seed, model = model).samples,
                restored.render().samples, "legacy model $model patch dispatch used the new renderer model")
            val recipe = PadRecipe.fromJsonText(PadRecipe(legacy).toJsonText())
            assertEquals(legacy, recipe.patch)
            assertContentEquals(legacy.render().samples, recipe.render().samples)
            val edited = Patches.edited(legacy, "Edited legacy", macros(3f / 7f))
            assertEquals(legacy.copy(name = "Edited legacy", macros = macros(3f / 7f)), edited)
            assertContentEquals(legacy.copy(velocity = .3f).render().samples,
                Velocity.atVelocity(legacy, .3f).samples, "native velocity dropped renderer model $model")
        }
    }

    @Test
    fun `HOLD at its top is a longer finite note with a release, not a loop`() {
        // HOLD sets the held part to 2 + 3 HOLD seconds before a 0.75 s release; at 1 that is 5.75 s,
        // ending in silence and filed TONAL. There is no loop at the top step, and none is claimed.
        val top = Obbligato.render(Obbligato.defaults() + ("HOLD" to 1f))
        assertEquals(5.75, top.durationSeconds.toDouble(), 2.0 / Dsp.RATE, "HOLD 1 is ${top.durationSeconds} s")
        assertTrue(top.samples.all { it.isFinite() })
        val tail = top.samples.copyOfRange(top.samples.size - Dsp.RATE / 50, top.samples.size)
        val tailRms = sqrt(tail.sumOf { it.toDouble() * it } / tail.size)
        val bodyRms = sqrt(top.samples.sumOf { it.toDouble() * it } / top.samples.size)
        assertTrue(tailRms < bodyRms * .01, "HOLD 1 does not release: last 20 ms at ${tailRms / bodyRms} of the note")
        assertEquals(com.snipsnap.audio.DrumClass.TONAL, Obbligato.drumClassFor())
    }

    @Test
    fun `muting either player changes the survivor only when reciprocal response is active`() {
        val controls = macros(3f / 7f, hold = 1f)
        val coupled = Obbligato.probe(controls, seed = 23)
        val airOff = Obbligato.probe(controls, seed = 23, sourceA = false)
        val reedOff = Obbligato.probe(controls, seed = 23, sourceB = false)
        assertTrue(airOff.air.all { it == 0f }, "muted air still contributes")
        assertTrue(reedOff.reed.all { it == 0f }, "muted reed still contributes")
        val reedResponse = relativeDifference(coupled.reed, airOff.reed)
        val airResponse = relativeDifference(coupled.air, reedOff.air)
        assertTrue(reedResponse > .005, "reed did not respond to the absent air player: $reedResponse")
        assertTrue(airResponse > .005, "air did not respond to the absent reed player: $airResponse")
        assertTrue(relativeDifference(coupled.reedEffort, airOff.reedEffort) > .001,
            "reed audio changed without an observable change in its breath gesture")
        assertTrue(relativeDifference(coupled.airEffort, reedOff.airEffort) > .001,
            "air audio changed without an observable change in its breath gesture")

        // Negative controls: source muting must not change the survivor's random stream,
        // common clock or voicing. Otherwise ablation would claim coupling spuriously.
        val free = Obbligato.probe(controls, seed = 23, responses = false)
        val freeAirOff = Obbligato.probe(controls, seed = 23, responses = false, sourceA = false)
        val freeReedOff = Obbligato.probe(controls, seed = 23, responses = false, sourceB = false)
        assertContentEquals(free.reed, freeAirOff.reed, "air ablation changed an uncoupled reed")
        assertContentEquals(free.air, freeReedOff.air, "reed ablation changed an uncoupled air player")
        assertContentEquals(free.reedEffort, freeAirOff.reedEffort, "air ablation changed an uncoupled reed gesture")
        assertContentEquals(free.airEffort, freeReedOff.airEffort, "reed ablation changed an uncoupled air gesture")
    }

    @Test
    fun `zero breath and zero velocity remove excitation including pressure noise`() {
        for (controls in listOf(macros(breath = 0f, mischief = 1f), macros(3f / 7f, breath = 0f, mischief = 1f))) {
            val p = Obbligato.probe(controls, seed = 41)
            for (samples in listOf(p.air, p.reed, p.snip.samples, Obbligato.render(controls, seed = 41).samples)) {
                assertTrue(samples.all { it == 0f }, "BREATH 0 still excites a source")
            }
        }
        val controls = macros(3f / 7f, breath = 1f, mischief = 1f)
        val p = Obbligato.probe(controls, velocity = 0f, seed = 41)
        for (samples in listOf(p.air, p.reed, p.snip.samples, Obbligato.render(controls, velocity = 0f, seed = 41).samples)) {
            assertTrue(samples.all { it == 0f }, "velocity 0 still excites a source")
        }
    }

    @Test
    fun `HOLD extends a finite note without advertising an unproved loop`() {
        val short = Obbligato.render(macros(hold = 0f))
        val long = Obbligato.render(macros(hold = 1f))
        assertTrue(long.durationSeconds > short.durationSeconds + .5f, "HOLD has no meaningful duration range")
        assertTrue(long.durationSeconds < 10f, "the first proof must remain a finite rendered note")
        assertTrue(abs(long.samples.last()) < 1e-4f, "the finite release did not close quietly")
    }

    @Test
    fun `invalid recipes refuse unsupported controls notes velocities and models with useful errors`() {
        for (bad in listOf(mapOf("WIND" to .5f), mapOf("BREATH" to -.1f), mapOf("BOND" to 1.1f), mapOf("MISCHIEF" to Float.NaN))) {
            val error = assertFailsWith<IllegalArgumentException> { Obbligato.probe(bad) }
            assertTrue(!error.message.isNullOrBlank(), "invalid macro refusal needs a useful diagnostic")
            assertFailsWith<IllegalArgumentException> { ObbligatoPatch("Invalid", bad) }
        }
        for (midi in listOf(59, 73)) assertFailsWith<IllegalArgumentException> { Obbligato.probe(midi = midi) }
        for (velocity in listOf(-.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { Obbligato.probe(velocity = velocity) }
            assertFailsWith<IllegalArgumentException> { ObbligatoPatch("Invalid", emptyMap(), velocity = velocity) }
        }
        val patch = ObbligatoPatch("Valid", macros())
        for ((field, invalid) in listOf("model" to JsonValue.Num(99.0), "version" to JsonValue.Num(999.0), "midi" to JsonValue.Num(60.5))) {
            val changed = JsonValue.Obj(LinkedHashMap(patch.toJsonValue().entries).also { it[field] = invalid })
            assertFailsWith<JsonException>(field) { ObbligatoPatch.fromJsonValue(changed) }
        }
        for (model in listOf(0, 99)) {
            assertFailsWith<IllegalArgumentException> { Obbligato.probe(model = model) }
            assertFailsWith<IllegalArgumentException> { ObbligatoPatch("Invalid model", macros(), model = model) }
        }
    }
}
