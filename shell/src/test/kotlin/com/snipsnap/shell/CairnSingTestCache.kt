package com.snipsnap.shell

import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * One rendered capture cache for every CAIRN SING test in this JVM, so the nine
 * native renders run once rather than once per test class. It is filled through
 * the production [CairnSingRenderedCaptures] path, which refuses any render that
 * does not match its pin.
 */
internal object CairnSingTestCache {
    val dir: File by lazy {
        createTempDirectory("cairn-sing-rendered").toFile().also { dir ->
            dir.deleteOnExit()
            for (program in CairnSingInstrumentMaker.Program.entries) CairnSingRenderedCaptures.readCaptures(program, dir)
        }
    }

    fun load(program: CairnSingInstrumentMaker.Program, cancelled: () -> Boolean) =
        CairnSingRenderedCaptures.readCaptures(program, dir, cancelled,
            render = { error("CAIRN SING test cache should already hold every capture") })
}
