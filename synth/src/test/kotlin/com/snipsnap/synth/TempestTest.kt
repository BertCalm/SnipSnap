package com.snipsnap.synth

import com.snipsnap.audio.*
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class TempestTest {
    private fun energy(x: FloatArray, start: Int, end: Int) =
        (start until end).sumOf { x[it].toDouble().pow(2) } / (end - start)

    @Test fun `all finite macro corners are bounded before and after finishing`() {
        val keys = listOf("TUNE", "SOURCE", "FILTER", "DRIVE", "SPACE")
        for (voice in Tempest.APPROVED_VOICES) for (bits in 0 until 32) {
            val m = keys.mapIndexed { i, key -> key to if (bits and (1 shl i) == 0) 0f else 1f }.toMap()
            val raw = Tempest.synthesize(voice, m, Dsp.RATE * 4)
            assertTrue(raw.all { it.isFinite() }, "$voice $m raw finite")
            assertTrue(raw.maxOf { abs(it) } < 4f, "$voice $m raw bound")
            val out = Tempest.finish(raw)
            assertEquals(Tempest.OUTPUT_FRAMES, out.size)
            assertTrue(out.all { it.isFinite() && abs(it) <= 1f })
            assertTrue(out.maxOf { abs(it) } > .01f, "$voice $m not silent")
            assertTrue(abs(out.average()) < .01, "$voice $m DC")
            val peak = out.maxOf { abs(it) }
            assertTrue(Loudness.of(Snip(out, 1, Dsp.RATE)) >= .9f * Dsp.MELODIC_LOUDNESS_TARGET || peak >= .95f)
            // The tail must already have landed before the cosmetic 4 ms fade.
            val last = energy(raw, raw.size - Dsp.RATE * 4 / 100, raw.size)
            val attack = energy(raw, 0, Dsp.RATE * 4 / 5)
            assertTrue(last < attack * 1e-5, "$voice $m pre-fade tail $last vs $attack")
        }
    }

    @Test fun `continuous source stays bounded through former algorithm boundaries`() {
        for (position in listOf(0f, .3333333f, .6666667f, 1f)) {
            val source = Tempest.Source(position, 42)
            assertTrue((0..10000).all { abs(source.step(it * .021, 3.0)) <= 1.000001f })
        }
        for (boundary in listOf(1f/3f,2f/3f)) {
            val a=Tempest.Source(boundary-1e-5f,42)
            val b=Tempest.Source(boundary+1e-5f,42)
            repeat(1000) { i -> assertTrue(abs(a.step(i*.021,3.0)-b.step(i*.021,3.0)) < .0002f) }
        }
    }

    @Test fun `each sound macro moves at low middle and high settings after leveling`() {
        for (voice in Tempest.APPROVED_VOICES) for (key in listOf("TUNE", "SOURCE", "FILTER", "DRIVE", "SPACE")) {
            val renders = listOf(0f, .25f, .5f, .75f, 1f).map { Tempest.render(voice, mapOf(key to it)).samples }
            for ((a,b) in renders.zipWithNext()) {
                val difference = a.indices.sumOf { (a[it] - b[it]).toDouble().pow(2) }
                val reference = a.sumOf { it.toDouble().pow(2) }
                assertTrue(difference / reference > .03, "$voice $key inactive")
            }
        }
    }

    @Test fun `reserved HOLD and unknown keys never change audio or seed`() {
        for (voice in Tempest.APPROVED_VOICES) {
            val base = Tempest.render(voice, mapOf("SOURCE" to 1f)).samples
            for (hold in listOf(0f,.5f,1f)) assertContentEquals(base,
                Tempest.render(voice, mapOf("SOURCE" to 1f, "HOLD" to hold, "UNKNOWN" to .7f)).samples)
            for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                val m = Tempest.defaults(voice).mapValues { bad }
                assertEquals(Tempest.defaults(voice), Tempest.settled(voice, m))
                assertContentEquals(Tempest.render(voice).samples, Tempest.render(voice,m).samples)
            }
            repeat(20) { assertTrue(Tempest.scramble(voice,Random(it)).values.all { it in 0f..1f }) }
        }
    }

    @Test fun `isolated drive increases sine distortion within its analytic bound`() {
        val n=8192
        val thd=listOf(0.0,.25,.5,.75,1.0).map { gain ->
            val x=FloatArray(n) { i -> Tempest.drive(.12f*sin(2*PI*i/256).toFloat(),gain) }
            assertTrue(x.all { abs(it) <= 1+tanh(.2) })
            fun partial(h: Int): Double {
                var re=0.0; var im=0.0
                for (i in x.indices) {
                    re+=x[i]*cos(2*PI*h*i/256); im+=x[i]*sin(2*PI*h*i/256)
                }
                return re*re+im*im
            }
            (2..20).sumOf { partial(it) }/partial(1)
        }
        assertTrue(thd.zipWithNext().all { (a,b) -> b>a }, "$thd")
    }

    @Test fun `memory decay test rejects a growing feedback mutation`() {
        fun ratio(feedback: Float? = null): Double {
            val rate = Dsp.RATE
            val memory = Tempest.Memory(TempestVoice.METAL, 1f, rate, feedback)
            val x = FloatArray(rate * 3) { i -> memory.step(if (i < rate / 200) .5f else 0f) }
            return energy(x, rate * 2, rate * 3) / energy(x, 0, rate / 2)
        }
        assertTrue(ratio() < 1e-6)
        assertTrue(ratio(1.05f) >= 1e-6, "the independent late-window guard must catch energy growth")
    }

    @Test fun `SPACE increases late memory energy with the same excitation`() {
        val energies = listOf(0f,.25f,.5f,.75f,1f).map { space ->
            val rate = Dsp.RATE
            val memory = Tempest.Memory(TempestVoice.METAL,space,rate)
            val x = FloatArray(rate) { i -> memory.step(if (i < 100) sin(i * .2).toFloat() else 0f) }
            energy(x,rate/10,rate)
        }
        assertTrue(energies.zipWithNext().all { (a,b) -> b > a }, "$energies")
    }

    @Test fun `decay compensated tonal bodies retain the settled root`() {
        for (voice in Tempest.APPROVED_VOICES) for (tune in listOf(.25f,.5f,1f)) for (source in listOf(0f,1f/3f)) {
            val snip = Tempest.render(voice,mapOf("TUNE" to tune,"SOURCE" to source,"FILTER" to .25f,"DRIVE" to 0f,"SPACE" to 0f))
            // The detector compares a period peak to near-zero-lag correlation. A short
            // kick at 55 Hz loses too much amplitude per cycle for that raw-confidence rule.
            // Remove only the known exponential slope from an actual settled output window;
            // do not retune the audio or claim this is raw detector confidence.
            val from=(.12*Dsp.RATE).toInt()
            val tau=if (voice==TempestVoice.KICK) .035*4.0.pow(source.toDouble()) else .055*3.0.pow(source.toDouble())
            val body=FloatArray((.15*Dsp.RATE).toInt()) { i ->
                (snip.samples[from+i]*exp(i/(Dsp.RATE*tau))).toFloat()
            }
            val pitch = assertNotNull(Pitch.detect(Snip(body,1,Dsp.RATE),fromSec=0f,windowSec=.15f),
                "$voice $tune $source compensated pitch")
            val cents = 1200 * log2(pitch.hz / Tempest.frequencyFor(tune))
            assertTrue(abs(cents) < 30, "$voice $tune $source cents $cents")
            assertTrue(pitch.confidence >= .3f)
        }
    }

    @Test fun `model one retains eight independently retained native PCM24 hashes`() {
        val cases=listOf(
Triple(TempestVoice.KICK,mapOf("TUNE" to 0.2199999988079071f,"SOURCE" to 0.10000000149011612f,"FILTER" to 0.44999998807907104f,"DRIVE" to 0.550000011920929f,"SPACE" to 0.15000000596046448f,"HOLD" to 0f),"224c53cbdd5a571000956a145c437424e3b4cb51f59952832f4f67bc8de96a7a"),
Triple(TempestVoice.KICK,mapOf("TUNE" to 0.2199999988079071f,"SOURCE" to 1f,"FILTER" to 0.44999998807907104f,"DRIVE" to 0.550000011920929f,"SPACE" to 0.15000000596046448f,"HOLD" to 0f),"333947e452980e8db91ded28c98bcf6d0267a81ecb09c120ca3771ea0efe1b9e"),
Triple(TempestVoice.KICK,mapOf("TUNE" to 0.2199999988079071f,"SOURCE" to 0.10000000149011612f,"FILTER" to 1f,"DRIVE" to 0.550000011920929f,"SPACE" to 0.15000000596046448f,"HOLD" to 0f),"532c0745ed569429b5ea84d8dabd6dcc2fa550f490815f4f8026a4a216a28ed8"),
Triple(TempestVoice.KICK,mapOf("TUNE" to 0.2199999988079071f,"SOURCE" to 0.10000000149011612f,"FILTER" to 0.44999998807907104f,"DRIVE" to 0.550000011920929f,"SPACE" to 1f,"HOLD" to 0f),"61d260555cfc83a5f56395215d1ba3f301a5edada2c0faf06f58fd7952d1225b"),
Triple(TempestVoice.METAL,mapOf("TUNE" to 0.699999988079071f,"SOURCE" to 0.6499999761581421f,"FILTER" to 0.75f,"DRIVE" to 0.6499999761581421f,"SPACE" to 0.44999998807907104f,"HOLD" to 0f),"3dc2b9fb8af22ba2d1d0e42c323766ffe82b687451bf333ea268e9832e070502"),
Triple(TempestVoice.METAL,mapOf("TUNE" to 0.699999988079071f,"SOURCE" to 1f,"FILTER" to 0.75f,"DRIVE" to 0.6499999761581421f,"SPACE" to 0.44999998807907104f,"HOLD" to 0f),"2d0224b2695b565c73267de606a61020edaa6ea3b346e99c01460d26f3fa3487"),
Triple(TempestVoice.METAL,mapOf("TUNE" to 0.699999988079071f,"SOURCE" to 0.6499999761581421f,"FILTER" to 1f,"DRIVE" to 0.6499999761581421f,"SPACE" to 0.44999998807907104f,"HOLD" to 0f),"943ce1b05d1f805b29b7fc3ea2fb3994449c13e8bfb6dd82b5ce16b2b05ef535"),
Triple(TempestVoice.METAL,mapOf("TUNE" to 0.699999988079071f,"SOURCE" to 0.6499999761581421f,"FILTER" to 0.75f,"DRIVE" to 0.6499999761581421f,"SPACE" to 1f,"HOLD" to 0f),"b0a35f1cfaddd1407575b6439d540213c03e2d76fce3c7134bdc9edaaddcbd4a"))
        val dir=kotlin.io.path.createTempDirectory("tempest-legacy").toFile()
        try {
            for ((voice,macros,hash) in cases) {
                val file=java.io.File(dir,"probe.wav")
                WavWriter.write(file,Tempest.render(voice,macros,model=1),WavWriter.BitDepth.PCM_24)
                val actual=java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                assertEquals(hash,actual,"$voice $macros model-one file changed")
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `old missing-model recipes retain exact first-build PCM while new edits save model two`() {
        for (voice in Tempest.APPROVED_VOICES) {
            val old=TempestPatch("Original",voice,TempestLegacy.defaults(voice),model=1)
            val fields=old.toJsonValue().entries-"model"
            val recalled=TempestPatch.fromJsonValue(com.snipsnap.json.JsonValue.Obj(fields)) as TempestPatch
            assertEquals(1,recalled.model)
            assertContentEquals(TempestLegacy.render(voice,old.macros).samples,recalled.render().samples)
            val current=TempestPatch("Current",voice,Tempest.defaults(voice))
            assertEquals(2,(Patches.fromJsonText(current.toJsonText()) as TempestPatch).model)
            assertEquals(2,current.withMacros(mapOf("DRIVE" to .5f)).model)
        }
    }

    @Test fun `promised macro pairs change more than independent gain`() {
        for (voice in Tempest.APPROVED_VOICES) for ((a,b) in listOf("SOURCE" to "DRIVE", "FILTER" to "DRIVE", "SPACE" to "FILTER")) {
            val r = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).map { (x,y) ->
                Tempest.render(voice,mapOf(a to x,b to y)).samples
            }
            val residual = r[0].indices.sumOf { (r[3][it] - r[1][it] - r[2][it] + r[0][it]).toDouble().pow(2) }
            assertTrue(residual > 1e-4, "$voice $a x $b has no interaction")
        }
    }

    @Test fun `patch and velocity dispatch preserve valid finite output`() {
        for (voice in Tempest.APPROVED_VOICES) {
            val patch = TempestPatch("Strike",voice,Tempest.defaults(voice))
            assertEquals(patch,Patches.fromJsonText(patch.toJsonText()))
            assertContentEquals(patch.render().samples,TempestPatch.fromJsonText(patch.toJsonText()).render().samples)
            assertTrue(Velocity.atVelocity(patch,.3f).samples.all { it.isFinite() })
            assertEquals(Classifier.classify(patch.render()).drumClass,Tempest.drumClassFor(voice))
            assertTrue(patch.render().durationSeconds < 1.5f)
        }
    }

    @Test fun `approved model two retains ten published native PCM24 hashes`() {
        val cases = listOf(
            Triple(TempestVoice.KICK, "default", "bc6b9dc21caa5c5f28df3271f20f863b92d2d1cc2fa971edd51efd82125a7477"),
            Triple(TempestVoice.KICK, "SOURCE-1.0", "20cee105d3c08d9baef569093c9442cc6ec8f92b0f8d6f9bb6a9d95f8c46e481"),
            Triple(TempestVoice.KICK, "FILTER-1.0", "d130c348d88db73df3c3b424d7bf1f8536e73ee3a128b1a00f46fee5653c2307"),
            Triple(TempestVoice.KICK, "DRIVE-1.0", "606196e3ca3108df0291df26a61429052360cb61488c3d0357b0f2060728b927"),
            Triple(TempestVoice.KICK, "SPACE-1.0", "8e07d71752c9136208b9c6a34961a05c8e0a766bb812fef39bd7705c57e5481f"),
            Triple(TempestVoice.METAL, "default", "b2ca1beebb72097e8de0e1db2e1fc7a58501677ffd1a93a6e41c17f2c8c6b2b4"),
            Triple(TempestVoice.METAL, "SOURCE-1.0", "3e469206f71ea16d45b81abedd9bc153de93591349d8a0a33f3b4b920851d167"),
            Triple(TempestVoice.METAL, "FILTER-1.0", "db0451b37dd9151baa23905234b59dd5c8e554f4bb831f12851cb5db6146aa72"),
            Triple(TempestVoice.METAL, "DRIVE-1.0", "eb209661e9e3f1a3aafea48d79d77272509cb626195f64ec85fac2e7afa30d33"),
            Triple(TempestVoice.METAL, "SPACE-1.0", "202b03903c25f3d0c311a326fe5e48b9cbcf9d97c0e6800e375841acdeff28d1"),
        )
        val dir = kotlin.io.path.createTempDirectory("tempest-approved").toFile()
        try {
            for ((voice, case, expected) in cases) {
                val overrides = if (case == "default") emptyMap() else
                    case.split('-').let { mapOf(it[0] to it[1].toFloat()) }
                val file = java.io.File(dir, "probe.wav")
                WavWriter.write(file, Tempest.render(voice, overrides, model = 2), WavWriter.BitDepth.PCM_24)
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, hash, "$voice $case approved sound changed")
            }
        } finally { dir.deleteRecursively() }
    }
}
