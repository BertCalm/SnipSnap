package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Acvs
import com.snipsnap.synth.OsteriaPatch
import com.snipsnap.synth.OsteriaPresets
import com.snipsnap.synth.OsteriaVoice
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

class OsteriaKeygroupBankTest {
@kotlin.test.Test fun preservesRecipesAndStereoMappings() {
    val out = java.nio.file.Files.createTempDirectory("osteria-keygroup-test").toFile()
    try {
    var preservedRecipes = 0
    for (voice in OsteriaVoice.entries) {
        val source = OsteriaPresets.forVoice(voice).first()
        for ((cell, recipe) in OsteriaKeygroupBank.recipes(source)) {
            check(recipe.copy(midi=source.midi, velocity=source.velocity).toJsonText() == source.toJsonText())
            check(recipe.midi == cell.midi && recipe.velocity == OsteriaKeygroupBank.velocities[cell.touch])
            preservedRecipes++
        }
    }
    val source = OsteriaPresets.forVoice(OsteriaVoice.PLUCK).first()
    val recipes = OsteriaKeygroupBank.recipes(source)
    val notes = recipes.mapValues { (cell, patch) ->
        // Small format/placement fixture, not a synth render or a listening take.
        val pcm = FloatArray(64) { i -> ((i/2 + 1) / 256f) * (cell.touch + 1) * if (i%2==0) 1f else -.5f }
        OsteriaKeygroupBank.Note(patch, Snip(pcm,2,44100))
    }
    check(runCatching { OsteriaKeygroupBank.assemble("Incomplete",source,notes - notes.keys.first()) }.isFailure)
    val cell=notes.keys.first();val wrong=notes.toMutableMap()
    wrong[cell]=notes.getValue(cell).copy(patch=notes.getValue(cell).patch.copy(velocity=.99f))
    check(runCatching { OsteriaKeygroupBank.assemble("Relabelled",source,wrong) }.isFailure)
    wrong[cell]=notes.getValue(cell).copy(snip=Snip(FloatArray(32),1,44100))
    check(runCatching { OsteriaKeygroupBank.assemble("Mono",source,wrong) }.isFailure)
    val dest=File(out,"package");if(dest.exists()) dest.deleteRecursively()
    val name="Osteria Proof"
    val built=OsteriaKeygroupBank.export(name,source,notes,dest)
    val read=InstrumentStore.read(InstrumentStore.sidecar(dest,name))
    check(read.toProgram()==built.program)
    check(read.zones.size==13 && read.zones.first().lowNote==36 && read.zones.last().highNote==84)
    check(read.zoneFor(35)==null && read.zoneFor(85)==null)
    for (midi in 36..84) check(read.zones.count { midi in it.lowNote..it.highNote }==1)
    for (zone in read.zones) {
        check(zone.playableLayers.size==3)
        for (velocity in 0..127) check(zone.playableLayers.count { velocity in it.velStart..it.velEnd }==1)
        check(zone.layerFor(41)==zone.playableLayers[0] && zone.layerFor(42)==zone.playableLayers[1])
        check(zone.layerFor(84)==zone.playableLayers[1] && zone.layerFor(85)==zone.playableLayers[2])
    }
    val data=File(dest,"${name}_[TrackData]")
    val metadata=Json.parse(File(data,"osteria-source-recipes.json").readText()).obj()
    check(OsteriaPatch.fromJsonValue(metadata.getValue("sourcePatch")).toJsonText()==source.toJsonText())
    for (row in metadata.getValue("samples").arr()) {
        val o=row.obj();val wav=File(data,o.getValue("sample").str());val stem=wav.nameWithoutExtension
        val sha=MessageDigest.getInstance("SHA-256").digest(wav.readBytes()).joinToString("") { "%02x".format(it) }
        check(sha==o.getValue("sha256").str())
        val recipe=OsteriaPatch.fromJsonValue(o.getValue("recipe")) as OsteriaPatch
        check(recipe.toJsonText()==built.recipes.getValue(stem).toJsonText())
        val pcm=WavReader.read(wav);val original=built.samples.getValue(stem)
        check(pcm.channels==2 && pcm.frameCount==32)
        check(pcm.samples.indices.all { abs(pcm.samples[it]-original.samples[it])<.000001f })
        val sampler=checkNotNull(WavReader.readSmplStrict(wav))
        check(sampler.rootNote==recipe.midi && sampler.loop==null)
    }
    val native=Json.parse(Acvs.read(File(dest,"$name.xty")).payloadText)
    val objects=mutableListOf<Map<String,JsonValue>>()
    fun visit(v:JsonValue) { when(v) {
        is JsonValue.Obj -> {objects += v.entries;v.entries.values.forEach(::visit)}
        is JsonValue.Arr -> v.items.forEach(::visit)
        else -> Unit
    } }
    visit(native)
    val filled=objects.filter { o -> (o["layersv"] as? JsonValue.Arr)?.items?.any { it.obj()["sampleName"]?.str()?.isNotEmpty()==true }==true }
    check(filled.size==13)
    filled.zip(built.program.keygroups).forEach { (o,g) ->
        check(o.getValue("lowNote").int()==g.lowNote && o.getValue("highNote").int()==g.highNote)
        val layers=o.getValue("layersv").arr().map { it.obj() }.filter { it.getValue("sampleName").str().isNotEmpty() }
        check(layers.size==3)
        for(l in layers) {
            val layer=g.layers.single { it.sampleName==l.getValue("sampleName").str() }
            check(l.getValue("rootNote").int()==g.rootNote)
            check(l.getValue("velocityStart").int()==layer.velStart && l.getValue("velocityEnd").int()==layer.velEnd)
            check(l.getValue("sliceInfo").obj().getValue("LoopMode").int()==0)
        }
    }
    val xml=File(data,"$name.xpm").readText()
    check(xml.contains("<KeygroupNumKeygroups>13</KeygroupNumKeygroups>"))
    check(!xml.contains("<SliceLoop>1</SliceLoop>"))
    File(out,"checks.json").writeText("""{"status":"passed","source_recipes_preserved":$preservedRecipes,"stereo_samples":39,"keygroups":13,"root_range":[36,84],"velocity_layers":3,"native_descriptors_checked":true,"legacy_descriptor_written":true,"phone_sidecar_exact":true,"source_hash_bindings":39,"synth_audio_rendered":false,"hardware_tested":false,"standalone_native_keygroup_import":"unsupported"}""")
    println("Passed: $preservedRecipes source recipes,39 stereo WAV/hash bindings,13 bounded keygroups×3 native velocity layers, native descriptor and phone sidecar readback")
    } finally { out.deleteRecursively() }
}
}
