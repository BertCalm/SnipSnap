package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.OsteriaPatch
import com.snipsnap.synth.OsteriaPresets
import com.snipsnap.synth.OsteriaVoice
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OsteriaKeygroupTransactionTest {
@kotlin.test.Test fun validatesPackageTransactions() {
    val out=java.nio.file.Files.createTempDirectory("osteria-transaction-test").toFile()
    try {
    val dest=File(out,"rollback-fixture").apply {deleteRecursively();mkdirs()}
    val name="Osteria Transaction"
    val dataName="${name}_[TrackData]"
    val sidecarName="$name${InstrumentStore.SUFFIX}"
    val source=OsteriaPresets.forVoice(OsteriaVoice.PLUCK).first()
    val revised=source.copy(macros=source.macros+("ROOM" to .07f))
    fun fixture(patch:OsteriaPatch,gain:Float)=OsteriaKeygroupBank.recipes(patch).mapValues { (_,p) ->
        OsteriaKeygroupBank.Note(p,Snip(FloatArray(64) {i -> gain*(i/2+1)/128*(if(i%2==0)1f else -.5f)},2,44100))
    }
    val oldNotes=fixture(source,.5f);val newNotes=fixture(revised,.7f)
    fun snapshot(root:File)=root.walkTopDown().filter {it.isFile}.associate {
        it.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
    }
    File(dest,"unrelated.txt").writeText("Unrelated shelf item must survive")
    File(dest,"other-data").mkdir();File(dest,"other-data/keep.bin").writeBytes(byteArrayOf(2,7,9))
    OsteriaKeygroupBank.export(name,source,oldNotes,dest)
    val original=snapshot(dest)
    fun unchanged() {
        check(snapshot(dest)==original) {"Failed operation changed original output or unrelated sibling"}
        check(dest.listFiles()!!.none {it.name.startsWith(".osteria-stage-")}) {"Staging leaked after handled failure"}
        OsteriaKeygroupTransaction.validate(name,dest,source)
    }
    check(runCatching {OsteriaKeygroupBank.export(name,revised,newNotes,dest)}.isFailure)
    unchanged()
    var rollbackCases=0
    val boundaries=listOf("backup:$sidecarName","backup:$name.xty","backup:$dataName",
        "publish:$dataName","publish:$name.xty","publish:$sidecarName")
    for(boundary in boundaries) {
        val result=runCatching {OsteriaKeygroupTransaction.export(name,revised,newNotes,dest,true,
            OsteriaKeygroupTransaction.Hooks(moved={if(it==boundary)throw IOException("injected $boundary")}))}
        check(result.exceptionOrNull()?.message=="injected $boundary")
        unchanged();rollbackCases++
    }
    fun editMetadata(stage:File, edit:(MutableMap<String,JsonValue>)->Unit) {
        val file=File(stage,"$dataName/osteria-source-recipes.json")
        val map=Json.parse(file.readText()).obj().toMutableMap();edit(map);file.writeText(Json.write(JsonValue.Obj(map)))
    }
    fun editFirst(stage:File,edit:(MutableMap<String,JsonValue>)->Unit)=editMetadata(stage) {m ->
        val rows=m.getValue("samples").arr().toMutableList()
        val first=rows.first().obj().toMutableMap();edit(first);rows[0]=JsonValue.Obj(first)
        m["samples"]=JsonValue.Arr(rows)
    }
    val corruptions=linkedMapOf<String,(File)->Unit>(
        "missing binding" to {s ->editMetadata(s){it["samples"]=JsonValue.Arr(it.getValue("samples").arr().dropLast(1))}},
        "duplicate binding" to {s ->editMetadata(s){val rows=it.getValue("samples").arr();it["samples"]=JsonValue.Arr(rows.dropLast(1)+rows.first())}},
        "stale hash" to {s ->editFirst(s){it["sha256"]=JsonValue.Str("0".repeat(64))}},
        "unsafe sample path" to {s ->editFirst(s){it["sample"]=JsonValue.Str("../unrelated.txt")}},
        "wrong root recipe" to {s ->editFirst(s){val p=OsteriaPatch.fromJsonValue(it.getValue("recipe")) as OsteriaPatch;it["recipe"]=p.copy(midi=37).toJsonValue()}},
        "wrong velocity recipe" to {s ->editFirst(s){val p=OsteriaPatch.fromJsonValue(it.getValue("recipe")) as OsteriaPatch;it["recipe"]=p.copy(velocity=.99f).toJsonValue()}},
        "wrong source patch" to {s ->editMetadata(s){it["sourcePatch"]=source.toJsonValue()}},
        "unknown version" to {s ->editMetadata(s){it["studyVersion"]=JsonValue.Num(2.0)}},
        "unknown source field" to {s ->editMetadata(s){ meta -> val value=meta.getValue("sourcePatch").obj().toMutableMap();value["futureArticulation"]=JsonValue.Str("must not disappear");meta["sourcePatch"]=JsonValue.Obj(value)}},
        "unknown recipe field" to {s ->editFirst(s){ row -> val value=row.getValue("recipe").obj().toMutableMap();value["futureArticulation"]=JsonValue.Str("must not disappear");row["recipe"]=JsonValue.Obj(value)}},
        "changed WAV" to {s ->File(s,"$dataName/Osteria_36_T1.wav").appendBytes(byteArrayOf(0))},
        "missing WAV" to {s ->check(File(s,"$dataName/Osteria_36_T1.wav").delete())},
        "unexpected data file" to {s ->File(s,"$dataName/unbound.wav").writeText("unbound")},
        "changed native descriptor" to {s ->File(s,"$name.xty").appendBytes(byteArrayOf(0))},
        "changed legacy descriptor" to {s ->File(s,"$dataName/$name.xpm").appendText("wrong")},
        "wrong phone mapping" to {s ->val file=File(s,sidecarName);file.writeText(file.readText().replace("\"low\": 36","\"low\": 35"))},
    )
    for((label,corrupt) in corruptions) {
        var publicationStarted=false
        val result=runCatching {OsteriaKeygroupTransaction.export(name,revised,newNotes,dest,true,
            OsteriaKeygroupTransaction.Hooks(staged=corrupt,moved={publicationStarted=true}))}
        check(result.isFailure && !publicationStarted) {"$label was not rejected before publication"}
        unchanged()
    }
    for(boundary in boundaries.filter {it.startsWith("publish:")}) {
        val fresh=File(out,"fresh-failure").apply {deleteRecursively();mkdirs()}
        File(fresh,"unrelated.txt").writeText("keep")
        val before=snapshot(fresh)
        check(runCatching {OsteriaKeygroupTransaction.export(name,revised,newNotes,fresh,false,
            OsteriaKeygroupTransaction.Hooks(moved={if(it==boundary)throw IOException("fresh failure")}))}.isFailure)
        check(snapshot(fresh)==before && fresh.listFiles()!!.size==1)
        rollbackCases++
    }
    OsteriaKeygroupBank.export(name,revised,newNotes,dest,true)
    OsteriaKeygroupTransaction.validate(name,dest,revised)
    check(snapshot(dest)!=original)
    check(File(dest,"unrelated.txt").readText()=="Unrelated shelf item must survive")
    check(File(dest,"other-data/keep.bin").readBytes().contentEquals(byteArrayOf(2,7,9)))
    val race=File(out,"concurrent").apply {deleteRecursively();mkdirs()}
    val firstStaged=CountDownLatch(1);val release=CountDownLatch(1);val secondAttempt=CountDownLatch(1)
    val success=AtomicInteger();val refusal=AtomicInteger()
    val first=Thread {
        OsteriaKeygroupTransaction.export(name,source,oldNotes,race,false,
            OsteriaKeygroupTransaction.Hooks(staged={firstStaged.countDown();check(release.await(5,TimeUnit.SECONDS))}))
        success.incrementAndGet()
    }
    first.start();check(firstStaged.await(5,TimeUnit.SECONDS))
    val second=Thread {
        secondAttempt.countDown()
        if(runCatching {OsteriaKeygroupTransaction.export(name,revised,newNotes,race)}.isFailure)refusal.incrementAndGet()
        else success.incrementAndGet()
    }
    second.start();check(secondAttempt.await(5,TimeUnit.SECONDS))
    val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
    while(second.state!=Thread.State.BLOCKED && System.nanoTime()<deadline)Thread.yield()
    check(second.state==Thread.State.BLOCKED) {"Second publisher did not serialize"}
    release.countDown();first.join(5000);second.join(5000)
    check(!first.isAlive && !second.isAlive && success.get()==1 && refusal.get()==1)
    OsteriaKeygroupTransaction.validate(name,race,source)
    File(out,"transaction-checks.json").writeText("""{"status":"passed","rollback_boundaries":$rollbackCases,"prepublication_corruptions_refused":${corruptions.size},"old_output_byte_exact_on_failure":true,"unrelated_siblings_preserved":true,"successful_overwrite_validated":true,"same_destination_writers_serialized":true,"crash_atomic":false,"partial_read_isolation":false,"synthetic_audio_only":true}""")
    println("Passed $rollbackCases rollback boundaries,${corruptions.size} corruptions, byte-exact old output, unrelated siblings and concurrent writer serialization")
    } finally { out.deleteRecursively() }
}
}
