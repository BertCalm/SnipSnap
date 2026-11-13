package com.snipsnap.shell

import com.snipsnap.synth.MurmurKeptBank
import java.io.File

/** Source metadata travels with a freshly rendered kept starter before kit.json makes it visible. */
object MurmurKeptBankFiles {
    fun write(directory: File) {
        check(directory.isDirectory || directory.mkdirs()) { "cannot create kept source-bank directory" }
        val target = File(directory, MurmurKeptBank.SOURCE_FILE)
        require(target.canonicalFile.parentFile == directory.canonicalFile) { "kept source metadata escapes kit directory" }
        val text = MurmurKeptBank.sourceBankJson()
        if (target.exists()) {
            require(target.isFile && target.readText() == text) { "refusing to replace different kept source metadata" }
        } else target.writeText(text)
    }
}
