package com.snipsnap.synth

import java.util.Collections

/**
 * Permanent complete source anchors for materials-kit policy v1. Captured from
 * unchanged original source 0f7d0e26cfdf875a995e6c8a5aec8225e3fd1fe9. These are not
 * new factory presets or entries in the global roster. Future voice/default
 * additions must not migrate an already saved sixteen-pad materials bank.
 */
internal object MeridianMaterialsFactoryV1 {
    private val encoded = listOf(
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "FIRST ARC",
          "voice": "SWEEP",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.800000011920929,
            "LENGTH": 0.44999998807907104,
            "REACH": 0.6000000238418579,
            "SURFACE": 0.550000011920929,
            "MEMORY": 0.4000000059604645,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 2,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "BACK ARC",
          "voice": "SWEEP",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.20000000298023224,
            "LENGTH": 0.44999998807907104,
            "REACH": 0.6000000238418579,
            "SURFACE": 0.550000011920929,
            "MEMORY": 0.4000000059604645,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 2,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "CLOSE GRAIN",
          "voice": "SWEEP",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.699999988079071,
            "LENGTH": 0.3199999928474426,
            "REACH": 0.41999998688697815,
            "SURFACE": 0.1599999964237213,
            "MEMORY": 0.18000000715255737,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 2,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "DEEP KNOCK",
          "voice": "SWEEP",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.8999999761581421,
            "LENGTH": 0.18000000715255737,
            "REACH": 0.9200000166893005,
            "SURFACE": 0.7200000286102295,
            "MEMORY": 0.2800000011920929,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 2,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "STILL PAIR",
          "voice": "SWEEP",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.5,
            "LENGTH": 0.5,
            "REACH": 0.550000011920929,
            "SURFACE": 0.6000000238418579,
            "MEMORY": 0.44999998807907104,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 2,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "LONG TRAIL",
          "voice": "SWEEP",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.7200000286102295,
            "LENGTH": 0.7799999713897705,
            "REACH": 0.699999988079071,
            "SURFACE": 0.6200000047683716,
            "MEMORY": 0.8399999737739563,
            "HOLD": 0.6499999761581421
          },
          "midi": 57,
          "velocity": 1,
          "model": 2,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "ROUND THREAD",
          "voice": "SWEEP",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.5,
            "LENGTH": 0.550000011920929,
            "REACH": 0.5,
            "SURFACE": 0.18000000715255737,
            "MEMORY": 0.6000000238418579,
            "HOLD": 1
          },
          "midi": 57,
          "velocity": 1,
          "model": 2,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "FINE STRAND",
          "voice": "SWEEP",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.20000000298023224,
            "LENGTH": 0.7799999713897705,
            "REACH": 0.7599999904632568,
            "SURFACE": 0.8999999761581421,
            "MEMORY": 0.7799999713897705,
            "HOLD": 1
          },
          "midi": 57,
          "velocity": 1,
          "model": 2,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "HARD GRAIN",
          "voice": "CLACK",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.800000011920929,
            "LENGTH": 0.44999998807907104,
            "REACH": 0.6000000238418579,
            "SURFACE": 1,
            "MEMORY": 0.4000000059604645,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 3,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "GRAIN THREAD",
          "voice": "CLACK",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.5,
            "LENGTH": 0.44999998807907104,
            "REACH": 0.6000000238418579,
            "SURFACE": 1,
            "MEMORY": 0.4000000059604645,
            "HOLD": 1
          },
          "midi": 57,
          "velocity": 1,
          "model": 3,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "HOLLOW TONE",
          "voice": "CHIME",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.800000011920929,
            "LENGTH": 0.44999998807907104,
            "REACH": 0.6000000238418579,
            "SURFACE": 1,
            "MEMORY": 0.4000000059604645,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 3,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "LANTERN TONE",
          "voice": "CHIME",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.5,
            "LENGTH": 0.44999998807907104,
            "REACH": 0.6000000238418579,
            "SURFACE": 1,
            "MEMORY": 0.4000000059604645,
            "HOLD": 1
          },
          "midi": 57,
          "velocity": 1,
          "model": 3,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "BRIGHT EDGE",
          "voice": "RASP",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.800000011920929,
            "LENGTH": 0.44999998807907104,
            "REACH": 0.6000000238418579,
            "SURFACE": 1,
            "MEMORY": 0.4000000059604645,
            "HOLD": 0
          },
          "midi": 57,
          "velocity": 1,
          "model": 3,
          "seedContext": 4810
        }
        """.trimIndent(),
        """
        {
          "engine": "MERIDIAN",
          "version": 1,
          "name": "EDGE THREAD",
          "voice": "RASP",
          "macros": {
            "TUNE": 0.5,
            "LATITUDE": 0.5,
            "LENGTH": 0.44999998807907104,
            "REACH": 0.6000000238418579,
            "SURFACE": 1,
            "MEMORY": 0.4000000059604645,
            "HOLD": 1
          },
          "midi": 57,
          "velocity": 1,
          "model": 3,
          "seedContext": 4810
        }
        """.trimIndent(),
    )
    private val originals = Collections.unmodifiableMap(encoded.map { text ->
        val source = MeridianPatch.fromJsonText(text)
        source.copy(macros = Collections.unmodifiableMap(LinkedHashMap(source.macros)))
    }.associateBy { it.name })
    val patches: List<MeridianPatch> = Collections.unmodifiableList(originals.values.toList())
    fun forName(name: String): MeridianPatch = originals.getValue(name)
}
