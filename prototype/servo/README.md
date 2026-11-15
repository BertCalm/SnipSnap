# SERVO round 0 (Python prototype)

Machine drums on a weak power rail, in three kits for electro, ghetto tech and neurofunk. Design notes and
every measured number: [docs/SERVO.md](../../docs/SERVO.md). Python harness; the Kotlin engine (`Servo.kt`) is a port of it.
Nothing here has been heard.

```sh
python3 prototype/servo/servo_proto.py OUT          # one WAV per voice at its defaults
python3 prototype/servo/gen_audition.py OUT         # three kits x 16 pads x 3 zones x 4 takes, phrases, sweeps, checks (~1 min)
python3 prototype/servo/build_page.py OUT           # OUT/index.html, the listening page, and OUT/files.json
```

Needs NumPy and SciPy; `ffmpeg` on the path adds MP3 listening copies of the clips. Write OUT outside the
repository: the renders are not committed.

- `servo_proto.py`: the model (ten voices, the rail, TONE, HEAT, `phrase_rails` for a shared rail).
- `kits.py`: SERVO ELECTRO, SERVO JIT and SERVO NEURO, each with a groove, a roll and a riser.
- `measures.py`: the house distance measures, copied from `prototype/clutter/`.
- `gen_audition.py`: renders every section, measures it, writes `manifest.json`.
- `build_page.py` and `page.css`: the listening page in the house audition style; a second argument adds the
  Kotlin kits from `./gradlew :shell:generateServoKits`.
- `compare_kotlin.py`: the Kotlin port against this prototype, from `./gradlew :synth:generateServoAudition`.
