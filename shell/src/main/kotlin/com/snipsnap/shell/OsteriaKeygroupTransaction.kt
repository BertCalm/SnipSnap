package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Names
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.OsteriaPatch
import com.snipsnap.xpm.KeygroupWriter
import com.snipsnap.xpm.VoiceMode
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Three sibling package entries commit with handled-failure rollback, not crash-atomic isolation. */
object OsteriaKeygroupTransaction {
    data class Hooks(
        val staged: (File) -> Unit = {},
        val moved: (String) -> Unit = {},
    )

    /**
     * Serializes OSTERIA package writers, including two calls to the same destination.
     * Existing readers and unrelated processes do not acquire this lock. The phone's
     * listing sidecar leaves first and arrives last, but existing readers are not isolated.
     */
    @Synchronized
    fun export(name: String, patch: OsteriaPatch,
               notes: Map<OsteriaKeygroupBank.Cell, OsteriaKeygroupBank.Note>,
               destination: File, overwrite: Boolean = false, hooks: Hooks = Hooks(), voiceMode: VoiceMode = VoiceMode.POLY): OsteriaKeygroupBank.Assembly {
        require(Names.isMpcSafe(name) && name.length <= com.snipsnap.kit.OneNote.MAX_NAME_LENGTH)
        require(!Files.isSymbolicLink(destination.toPath())) { "Package destination cannot be a symbolic link" }
        destination.mkdirs()
        require(destination.isDirectory)
        val dataName = "${name}_[TrackData]"
        val sidecarName = "$name${InstrumentStore.SUFFIX}"
        val entries = listOf(dataName, "$name.xty", sidecarName)
        entries.forEach { entry ->
            val target=File(destination,entry)
            require(!Files.isSymbolicLink(target.toPath())) { "Package entry cannot be a symbolic link: $entry" }
            require(overwrite || !target.exists()) { "Package already exists: $entry" }
        }
        val stage=Files.createTempDirectory(destination.toPath(), ".osteria-stage-").toFile()
        val backup=File(stage,"displaced").apply { mkdir() }
        val displaced=mutableListOf<String>()
        val published=mutableListOf<String>()
        var retainForRecovery=false
        try {
            val built=OsteriaKeygroupBank.writeStaged(name,patch,notes,stage,voiceMode)
            hooks.staged(stage)
            validate(name,stage,patch)
            // Do not expose a shelf entry whose WAVs or native descriptor are in transit.
            for(entry in listOf(sidecarName,"$name.xty",dataName)) {
                val target=File(destination,entry)
                if(target.exists()) {
                    move(target,File(backup,entry));displaced+=entry
                    hooks.moved("backup:$entry")
                }
            }
            for(entry in entries) {
                move(File(stage,entry),File(destination,entry));published+=entry
                hooks.moved("publish:$entry")
            }
            return built
        } catch(failure:Throwable) {
            try {
                for(entry in published.asReversed()) {
                    val target=File(destination,entry)
                    if(target.exists() && !target.deleteRecursively()) throw IOException("Cannot remove failed publication: $entry")
                }
                for(entry in displaced.asReversed()) move(File(backup,entry),File(destination,entry))
            } catch(rollback:Throwable) {
                // Never erase the only surviving old files when recovery itself encounters I/O failure.
                retainForRecovery=true
                failure.addSuppressed(IOException("Rollback failed; displaced originals retained in $backup",rollback))
            }
            throw failure
        } finally {
            if(!retainForRecovery) stage.deleteRecursively()
        }
    }

    /** Validate before publication or accepting a source sidecar as editable provenance. */
    fun validate(name:String, root:File, expectedSource:OsteriaPatch?=null): OsteriaKeygroupBank.Assembly {
        require(Names.isMpcSafe(name) && name.length <= com.snipsnap.kit.OneNote.MAX_NAME_LENGTH)
        val data=File(root,"${name}_[TrackData]")
        val metadata=File(data,"osteria-source-recipes.json")
        val sidecar=InstrumentStore.sidecar(root,name)
        val xty=File(root,"$name.xty")
        listOf(root,data,metadata,sidecar,xty).forEach { require(!Files.isSymbolicLink(it.toPath())) }
        val o=Json.parse(metadata.readText()).obj()
        require(o.keys==setOf("studyVersion","sourcePatch","samples") && o.getValue("studyVersion").int()==1)
        val source=OsteriaPatch.fromJsonValue(o.getValue("sourcePatch")) as OsteriaPatch
        require(o.getValue("sourcePatch")==source.toJsonValue()) { "Unsupported or noncanonical source fields cannot be discarded" }
        require(expectedSource==null || source.toJsonText()==expectedSource.toJsonText()) { "Unexpected source patch" }
        val expected=OsteriaKeygroupBank.recipes(source)
        val names=expected.keys.associateBy { "Osteria_${it.midi}_T${it.touch+1}.wav" }
        val rows=o.getValue("samples").arr()
        require(rows.size==expected.size) { "Incomplete source bindings" }
        val notes=linkedMapOf<OsteriaKeygroupBank.Cell,OsteriaKeygroupBank.Note>()
        for(row in rows) {
            val binding=row.obj()
            require(binding.keys==setOf("sample","sha256","recipe"))
            val sample=binding.getValue("sample").str()
            val cell=names[sample] ?: error("Unknown or unsafe sample binding: $sample")
            require(cell !in notes) { "Duplicate source binding: $sample" }
            val recipe=OsteriaPatch.fromJsonValue(binding.getValue("recipe")) as OsteriaPatch
            require(binding.getValue("recipe")==recipe.toJsonValue()) { "Unsupported recipe fields cannot be discarded: $sample" }
            require(recipe.toJsonText()==expected.getValue(cell).toJsonText()) { "Relabelled source binding: $sample" }
            val wav=File(data,sample)
            require(!Files.isSymbolicLink(wav.toPath()) && wav.isFile)
            require(sha(wav)==binding.getValue("sha256").str()) { "Stale source hash: $sample" }
            val snip=WavReader.read(wav)
            val sheet=checkNotNull(WavReader.readSmplStrict(wav))
            require(sheet.rootNote==cell.midi && sheet.loop==null) { "Source root/loop differs: $sample" }
            notes[cell]=OsteriaKeygroupBank.Note(recipe,snip)
        }
        // Voice mode is the only sampler-side edit this source-preserving route accepts.
        val instrument=InstrumentStore.read(sidecar)
        val built=OsteriaKeygroupBank.assemble(name,source,notes,instrument.voiceMode)
        val xpm=File(data,"$name.xpm")
        require(!Files.isSymbolicLink(xpm.toPath()))
        require(data.listFiles()!!.map { it.name }.toSet()==names.keys+setOf(metadata.name,xpm.name)) { "Unexpected package data entries" }
        require(instrument.toProgram()==built.program) { "Phone mapping differs from source recipes" }
        require(sidecar.readText()==InstrumentStore.render(built.program,data.name)) { "Phone descriptor contains unsupported changes" }
        require(xty.readBytes().contentEquals(Mpc3TrackWriter().writeKeygroup(built.program))) { "Native descriptor differs from source recipes" }
        require(xpm.readText()==KeygroupWriter().write(built.program)) { "Legacy descriptor differs from source recipes" }
        return built
    }

    private fun move(from:File,to:File) {
        require(!to.exists()) { "Destination appeared during package publication: $to" }
        try { Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE) }
        catch(_:AtomicMoveNotSupportedException) { Files.move(from.toPath(),to.toPath()) }
    }

    private fun sha(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer=ByteArray(65536)
            while(true) {val size=input.read(buffer);if(size<0)break;digest.update(buffer,0,size)}
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
