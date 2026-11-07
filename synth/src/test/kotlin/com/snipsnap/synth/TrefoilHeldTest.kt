package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import java.util.concurrent.CancellationException
import kotlin.math.*
import kotlin.test.*

class TrefoilHeldTest {
    private fun patch(m: Map<String,Float> = emptyMap(),midi: Int=60,velocity: Float=.78f)=TrefoilPatch(
        "Held",TrefoilVoice.WEAVE,TrefoilHeld.defaults(TrefoilVoice.WEAVE)+m,midi,velocity,43012026L,
        playback=TrefoilPlayback.HELD)

    @Test fun `declared held defaults construct valid recipes for every voice`() {
        for (voice in TrefoilVoice.entries) {
            val specs = TrefoilHeld.macrosFor(voice)
            val declared = specs.associate { it.name to it.default }
            val p = TrefoilPatch("Defaults", voice, TrefoilHeld.defaults(voice) + declared,
                playback = TrefoilPlayback.HELD)
            assertEquals(.42f, p.macros.getValue("WEAVE"))
            assertEquals(specs, Velocity.macroSpecsFor(p))
            assertEquals(p, TrefoilPatch.fromJsonText(p.toJsonText()))
            for (spec in specs) assertEquals(TrefoilHeld.defaults(voice).getValue(spec.name), spec.neutral)
        }
    }
    @Test fun `ordered held state and genuine adjacent cycles close`() {
        val loop=TrefoilHeld.loop(patch())
        println("TREFOIL Kotlin held ${loop.diagnostics}")
        assertTrue(loop.diagnostics.stateRelativeError<1e-8)
        assertTrue(loop.diagnostics.precedingSeam<1e-8)
        assertTrue(loop.diagnostics.followingSeam<1e-8)
        assertTrue(loop.samples.all { it.isFinite() && abs(it)<.99f })
        assertEquals(123216,loop.samples.size)
        val pitch=assertNotNull(Pitch.detect(Snip(loop.samples,1,Dsp.RATE),.12f,.3f))
        val cents=1200*log2(pitch.hz/(440.0*2.0.pow((60-69)/12.0)))
        assertTrue(abs(cents)<5,"pitch $cents cents");assertTrue(pitch.confidence>.8)
    }
    @Test fun `root register material memory and motion corners close`() {
        for((midi,m) in listOf(36 to mapOf("WEAVE" to 1f,"MEMORY" to 0f),
            84 to mapOf("WEAVE" to 1f,"THREAD" to 1f),
            48 to mapOf("WEAVE" to 0f,"MEMORY" to 1f),
            60 to mapOf("WEAVE" to .515f),
            60 to mapOf("WEAVE" to TrefoilHeld.MIN_MOVING_WEAVE,"EXCHANGE" to 1f))) {
            val loop=TrefoilHeld.loop(patch(m,midi))
            assertTrue(loop.diagnostics.followingSeam<Keys.MAX_SEAM_ERROR,"$midi $m")
            assertTrue(loop.diagnostics.stateRelativeError<1e-8)
        }
    }
    @Test fun `zero velocity is exact silence with an explicit silent seam`() {
        val loop=TrefoilHeld.loop(patch(velocity=0f))
        assertTrue(loop.samples.all { it==0f });assertEquals(0.0,loop.diagnostics.precedingSeam)
    }
    @Test fun `unforced norm contracts and diagnostic detects injected growth`() {
        val p=TrefoilHeld.plan(patch()).let { it.copy(force=DoubleArray(it.force.size)) }
        val state=TrefoilHeld.State(DoubleArray(24){.01*sin(it+1.0)},DoubleArray(24){.01*cos(it+1.0)})
        assertEquals(0.0,TrefoilHeld.run(p,state,8192,off=0).unforcedRise)
        val broken=p.copy(co=DoubleArray(24){p.co[it]*1.0001},si=DoubleArray(24){p.si[it]*1.0001})
        assertTrue(TrefoilHeld.run(broken,state,8192,off=0).unforcedRise>1e-9)
    }
    @Test fun `exchange changes receiver modal state not just output gain`() {
        fun oneBody(exchange: Float): TrefoilHeld.Run {
            val p=TrefoilHeld.plan(patch(mapOf("EXCHANGE" to exchange)))
            for(f in 0 until p.count){p.force[f*3+1]=0.0;p.force[f*3+2]=0.0}
            return TrefoilHeld.run(p,count=(.4*Dsp.RATE*4).toInt())
        }
        val coupled=oneBody(.55f).state;val isolated=oneBody(0f).state
        assertTrue((0..23).filter { it%3!=0 }.sumOf { coupled.x[it].pow(2)+coupled.v[it].pow(2) }>1e-12)
        for(i in 0..23)if(i%3!=0){assertEquals(0.0,isolated.x[i]);assertEquals(0.0,isolated.v[i])}
    }
    @Test fun `note off changes source only and preserves exact causal prefix`() {
        val first=TrefoilHeld.renderNote(patch(),.4,1.0)
        val later=TrefoilHeld.renderNote(patch(),.7,1.0)
        val stop=(.4*Dsp.RATE*4).toInt()
        assertContentEquals(first.internalRaw.copyOf(stop),later.internalRaw.copyOf(stop))
        assertTrue((stop until stop+8192).any { first.internalRaw[it]!=later.internalRaw[it] })
        assertEquals(0.0,first.unforcedCoreRise)
        assertEquals((.425*Dsp.RATE).roundToInt(),first.driveStopsFrame)
    }
    @Test fun `held render cache owns bytes and excludes name and player release`() {
        TrefoilHeld.clearCache();val p=patch();val a=TrefoilHeld.render(p);val expected=a.snip.samples.copyOf()
        a.snip.samples.fill(0f)
        val b=TrefoilHeld.render(p.copy(name="Renamed",releaseSeconds=1f))
        assertContentEquals(expected,b.snip.samples);assertNotSame(a.snip.samples,b.snip.samples)
        assertTrue(TrefoilHeld.cacheBytes()<=TrefoilHeld.CACHE_MAX_BYTES)
        assertTrue(b.snip.durationSeconds>1.5)
        assertEquals(0f,b.snip.samples.first())
        TrefoilHeld.clearCache();assertContentEquals(expected,TrefoilHeld.render(p).snip.samples)
    }
    @Test fun `held playback round trips without changing finite JSON shape`() {
        val finite=TrefoilPresets.entries.first().patch
        assertFalse(finite.toJsonText().contains("playback"));assertFalse(finite.toJsonText().contains("heldVersion"))
        assertEquals(finite,TrefoilPatch.fromJsonText(finite.toJsonText()))
        val held=patch().copy(seed=Long.MIN_VALUE,releaseSeconds=.8f)
        assertEquals(held,Patches.fromJsonText(held.toJsonText()))
        assertEquals(held,PadRecipe.fromJsonText(PadRecipe(patch=held).toJsonText()).patch)
    }
    @Test fun `invalid held controls refuse before rounding to valid floats`() {
        val p=patch()
        for((key,value,expected) in listOf(Triple("NUDGE","1e-300","NUDGE"),
            Triple("HOLD","0.9999999999999999","HOLD"),Triple("WEAVE","0.1","WEAVE"))) {
            val marker="\"$key\":"
            val encoded=p.toJsonText();val regex=Regex("(\\\"$key\\\"\\s*:\\s*)[-+0-9.eE]+")
            val edited=encoded.replace(regex) { it.groupValues[1]+value }
            assertNotEquals(encoded,edited,marker)
            assertTrue(assertFailsWith<JsonException>{TrefoilPatch.fromJsonText(edited)}.message.orEmpty().contains(expected))
        }
        assertTrue(assertFailsWith<IllegalArgumentException>{patch(mapOf("NUDGE" to .1f))}.message.orEmpty().contains("NUDGE"))
        val minimum=patch(mapOf("WEAVE" to TrefoilHeld.MIN_MOVING_WEAVE))
        val literal=minimum.toJsonText().replace(Regex("(\\\"WEAVE\\\"\\s*:\\s*)[-+0-9.eE]+")) { it.groupValues[1]+"0.34" }
        assertEquals(minimum,TrefoilPatch.fromJsonText(literal))
    }
    @Test fun `extreme touch memory and material keep captured notes below the ceiling`() {
        for(midi in listOf(36,84))for(weave in listOf(TrefoilHeld.MIN_MOVING_WEAVE,1f)) {
            val p=patch(mapOf("WEAVE" to weave,"THREAD" to 1f,"MEMORY" to 1f,"EXCHANGE" to 1f),midi,1f)
            val note=TrefoilHeld.renderNote(p,.37,2.0)
            assertTrue(note.snip.samples.all { it.isFinite() && abs(it)<.99f })
            assertEquals(0.0,note.unforcedCoreRise)
        }
    }
    @Test fun `cancellation and work caps fail explicitly without publishing cache`() {
        TrefoilHeld.clearCache()
        val e=assertFailsWith<CancellationException>{TrefoilHeld.render(patch(),cancelled={true})}
        assertTrue(e.message.orEmpty().contains("TREFOIL"));assertEquals(0,TrefoilHeld.cacheBytes())
        val p=TrefoilHeld.plan(patch(mapOf("WEAVE" to 1f)))
        assertFailsWith<IllegalArgumentException>{TrefoilHeld.run(p,count=Dsp.RATE*4*61)}
    }
}
