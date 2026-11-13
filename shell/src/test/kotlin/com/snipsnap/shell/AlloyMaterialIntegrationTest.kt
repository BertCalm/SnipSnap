package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.synth.AlloyConfig
import com.snipsnap.synth.AlloyMaterialPresets
import com.snipsnap.synth.AlloyMaterialResponse
import com.snipsnap.synth.AlloyPatch
import com.snipsnap.synth.Patches
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class AlloyMaterialIntegrationTest {
    @Test
    fun `mapped user preset edit reopen and promotion retain anchor and double endpoints`() {
        val dir = createTempDirectory("alloy-material-promotion").toFile()
        try {
            for (source in AlloyMaterialPresets.all()) {
                val edited = Patches.edited(source, "BODY ${source.voice}", source.macros + ("MATERIAL" to .25f)) as AlloyPatch
                UserPresets.save(dir, edited, 1L)
                val reopened = UserPresets.read(dir).single { it.name == edited.name }.patch as AlloyPatch
                assertEquals(edited, reopened)
                val line = UserPresets.rosterLine(reopened)
                val arguments = mutableListOf<String>()
                var offset = 0
                while (true) {
                    val call = line.indexOf("Json.parse(", offset)
                    if (call < 0) break
                    val start = call + "Json.parse(".length
                    assertEquals('"', line[start])
                    var end = start + 1
                    while (end < line.length && line[end] != '"') {
                        end += if (line[end] == '\\') 2 else 1
                    }
                    arguments += Json.parse(line.substring(start, end + 1)).str()
                    offset = end + 1
                }
                assertEquals(2, arguments.size, line)
                assertEquals(reopened.config, AlloyConfig.fromJsonValue(Json.parse(arguments[0])))
                assertEquals(reopened.materialResponse, AlloyMaterialResponse.fromJsonValue(Json.parse(arguments[1])))
                assertEquals(source.config, reopened.config)
                assertEquals(source.materialResponse, reopened.materialResponse)
            }
        } finally { dir.deleteRecursively() }
    }
}
