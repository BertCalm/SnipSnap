package com.snipsnap.synth;

import com.snipsnap.audio.Snip;
import com.snipsnap.audio.WavWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Exact model-2 compatibility and render-only timing against the unchanged b437cafc jars.
 *
 * Run with JDK 17 and synth/audio/json jars plus kotlin-stdlib on the classpath:
 *   cases OUTPUT.tsv
 *   capture CASES.tsv BASELINE.tsv CLEAN_BASELINE_CHECKOUT
 *   compare [CASES.tsv BASELINE.tsv]
 *   bench [raw|final|both]
 *
 * Capture refuses an existing output, a dirty/different baseline, or classes loaded from
 * anywhere except that checkout's three jars. Compare never updates the golden file.
 * Float hashes cover every Float.floatToRawIntBits value in little-endian order; WAV
 * hashes cover complete PCM16 files, including the actual repository writer's headers.
 *
 * These are exact arithmetic regression checks, not perceptual comparisons. Math
 * implementations/intrinsics can vary across JVM vendors, versions and architectures;
 * the baseline records its JDK/OS context and failures report the current context too.
 */
public final class OhmPerformanceProbe {
    private static final String BASELINE_REVISION = "b437cafcc0dcfb5c1f38725059a1f4e676c74640";
    private static final String RESOURCE_ROOT = "/ohm-performance/";
    private static final String[] MACROS = {"TOUCH", "BODY", "LENGTH", "MEDDLING", "COMMUNE"};
    private static final int CASE_COUNT = 134;
    private static final int WARMUPS = 12;
    private static final int TIMED = 45;
    private static final String CASE_HEADER = "id\tgroup\tTOUCH\tBODY\tLENGTH\tMEDDLING\tCOMMUNE\tmidi\tvelocity\tseed\tgesture\tmodel";
    private static final String GOLDEN_HEADER = "id\tframes\tsampleRate\tchannels\trawFloatBitsSha256\tfinalFloatBitsSha256\trawPcm16WavSha256\tfinalPcm16WavSha256";
    private static volatile Snip retainedRender;

    private OhmPerformanceProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("expected cases, capture, compare or bench");
        switch (args[0]) {
            case "cases" -> {
                require(args.length == 2, "cases OUTPUT.tsv");
                writeNew(Path.of(args[1]), caseText());
            }
            case "capture" -> {
                require(args.length == 4, "capture CASES.tsv BASELINE.tsv CLEAN_BASELINE_CHECKOUT");
                capture(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
            }
            case "compare" -> {
                require(args.length == 1 || args.length == 3, "compare [CASES.tsv BASELINE.tsv]");
                if (args.length == 1) compareBundled();
                else compare(Files.readString(Path.of(args[1])), Files.readString(Path.of(args[2])));
            }
            case "bench" -> {
                require(args.length <= 2, "bench [raw|final|both]");
                benchmark(args.length == 1 ? "final" : args[1]);
            }
            default -> throw new IllegalArgumentException("unknown mode " + args[0]);
        }
    }

    /** Reusable by the Kotlin JUnit test, using the checked-in immutable resources. */
    public static void compareBundled() throws IOException {
        compare(resource("cases.tsv"), resource("baseline-b437cafc.tsv"));
    }

    public static void compare(String caseText, String baselineText) throws IOException {
        List<RenderCase> cases = parseCases(caseText);
        Table baseline = table(baselineText, GOLDEN_HEADER);
        require(BASELINE_REVISION.equals(baseline.metadata.get("sourceRevision")), "unexpected baseline source revision");
        require(sha(caseText.getBytes(StandardCharsets.UTF_8)).equals(baseline.metadata.get("casesSha256")),
                "case fixture changed: use the captured full float controls without refreshing the baseline");
        require(baseline.rows.size() == CASE_COUNT, "baseline must contain exactly " + CASE_COUNT + " rows");
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            RenderCase c = cases.get(i);
            String expected = baseline.rows.get(i);
            String actual = measure(c);
            if (!expected.equals(actual)) {
                String[] before = expected.split("\t", -1);
                String[] after = actual.split("\t", -1);
                String[] names = GOLDEN_HEADER.split("\t");
                for (int field = 0; field < names.length; field++) {
                    if (!before[field].equals(after[field])) failures.add(c.id + ": " + names[field]
                            + " expected " + before[field] + ", actual " + after[field]);
                }
            }
        }
        if (!failures.isEmpty()) throw new AssertionError("OHM model-2 exact compatibility failed in "
                + failures.size() + " fields. Baseline runtime: " + baseline.metadata.get("runtimeContext")
                + "; current runtime: " + runtimeContext() + "\n" + String.join("\n", failures));
        System.err.println("OHM exact compatibility: all " + cases.size() + " raw/final float and full PCM16 WAV hashes match; " + runtimeContext());
    }

    private static void capture(Path casesPath, Path output, Path baselineRoot) throws Exception {
        require(!Files.exists(output), "immutable baseline already exists: " + output);
        Map<String, String> jars = verifyCleanBaseline(baselineRoot);
        String casesText = Files.readString(casesPath);
        List<RenderCase> cases = parseCases(casesText);
        StringBuilder out = new StringBuilder();
        metadata(out, "sourceRevision", BASELINE_REVISION);
        metadata(out, "model", "2");
        metadata(out, "caseCount", Integer.toString(CASE_COUNT));
        metadata(out, "runtimeContext", runtimeContext());
        metadata(out, "casesSha256", sha(casesText.getBytes(StandardCharsets.UTF_8)));
        metadata(out, "floatHashEncoding", "Float.floatToRawIntBits, little-endian 4 bytes per sample, no metadata");
        metadata(out, "wavHashEncoding", "complete WavWriter PCM_16 output, no smpl chunk or true-peak processing");
        metadata(out, "compatibilityContext", "Exact hashes are pinned to the recorded JDK/architecture; cross-JVM Math differences require investigation, never an automatic golden refresh");
        jars.forEach((name, hash) -> metadata(out, name, hash));
        out.append(GOLDEN_HEADER).append('\n');
        for (int i = 0; i < cases.size(); i++) {
            out.append(measure(cases.get(i))).append('\n');
            if ((i + 1) % 10 == 0 || i + 1 == cases.size()) System.err.println("OHM baseline captured " + (i + 1) + "/" + cases.size());
        }
        // Build the complete capture in memory before creating the immutable output.
        writeNew(output, out.toString());
        System.err.println("OHM immutable baseline saved: " + output + " (" + CASE_COUNT + " cases)");
    }

    private static Map<String, String> verifyCleanBaseline(Path baselineRoot) throws Exception {
        require(Runtime.version().feature() == 17, "baseline capture requires JDK 17");
        Path root = baselineRoot.toRealPath();
        require(BASELINE_REVISION.equals(git(root, "rev-parse", "HEAD")), "capture checkout is not " + BASELINE_REVISION);
        require(git(root, "status", "--porcelain", "--untracked-files=no").isEmpty(), "capture checkout has tracked changes");
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String[] entry : new String[][] {
                {"synth", "com.snipsnap.synth.Ohm"}, {"audio", "com.snipsnap.audio.Snip"}, {"json", "com.snipsnap.json.Json"},
        }) {
            Class<?> type = Class.forName(entry[1]);
            Path loaded = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            Path expected = root.resolve(entry[0] + "/build/libs/" + entry[0] + ".jar").toRealPath();
            require(loaded.equals(expected), "capture " + entry[0] + " loaded from " + loaded + ", expected " + expected);
            hashes.put(entry[0] + "JarSha256", sha(Files.readAllBytes(expected)));
        }
        return hashes;
    }

    private static String measure(RenderCase c) throws IOException {
        Snip raw = render(c, true);
        Snip finished = render(c, false);
        checkSnip(c, raw);
        checkSnip(c, finished);
        require(raw.getFrameCount() == finished.getFrameCount(), c.id + ": raw/final frame counts differ");
        return c.id + "\t" + raw.getFrameCount() + "\t" + raw.getSampleRate() + "\t" + raw.getChannels()
                + "\t" + floatHash(raw) + "\t" + floatHash(finished)
                + "\t" + wavHash(raw) + "\t" + wavHash(finished);
    }

    private static Snip render(RenderCase c, boolean raw) {
        return raw ? Ohm.INSTANCE.renderRaw(c.controls, c.midi, c.velocity, c.seed, c.gesture, c.model)
                : Ohm.INSTANCE.render(c.controls, c.midi, c.velocity, c.seed, c.gesture, c.model);
    }

    private static void checkSnip(RenderCase c, Snip snip) {
        require(snip.getChannels() == 1 && snip.getSampleRate() == 44100 && snip.getFrameCount() > 0,
                c.id + ": invalid output format/length");
        for (float sample : snip.getSamples()) require(Float.isFinite(sample), c.id + ": non-finite output");
    }

    private static String floatHash(Snip snip) {
        MessageDigest digest = digest();
        byte[] buffer = new byte[16384];
        int used = 0;
        for (float sample : snip.getSamples()) {
            int bits = Float.floatToRawIntBits(sample);
            buffer[used++] = (byte) bits;
            buffer[used++] = (byte) (bits >>> 8);
            buffer[used++] = (byte) (bits >>> 16);
            buffer[used++] = (byte) (bits >>> 24);
            if (used == buffer.length) { digest.update(buffer); used = 0; }
        }
        digest.update(buffer, 0, used);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String wavHash(Snip snip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + snip.getSamples().length * 2);
        WavWriter.INSTANCE.write(out, snip, WavWriter.BitDepth.PCM_16, null, null);
        return sha(out.toByteArray());
    }

    /** Timers cover the render call alone: no hashing, encoding, file I/O or logging. */
    private static void benchmark(String path) {
        require(List.of("raw", "final", "both").contains(path), "bench path must be raw, final or both");
        StringBuilder json = new StringBuilder("{\"baselineRevision\":\"").append(BASELINE_REVISION)
                .append("\",\"runtimeContext\":").append(quoted(runtimeContext()))
                .append(",\"clock\":\"System.nanoTime\",\"timedScope\":\"render call only; no encoding, hashes or file I/O\",\"warmupsPerCase\":")
                .append(WARMUPS).append(",\"timedPerCase\":").append(TIMED).append(",\"cases\":[");
        boolean first = true;
        for (float length : new float[] {0f, .65f, 1f}) {
            for (String relationship : new String[] {"solo", "active", "gather"}) {
                RenderCase c = create("bench-" + relationship + "-" + Float.toString(length), "benchmark",
                        .5f, .5f, length, relationship.equals("solo") ? 0f : .65f, .5f,
                        60, .8f, 731, relationship.equals("gather") ? OhmGesture.GATHER : OhmGesture.STEADY);
                for (boolean raw : path.equals("both") ? new boolean[] {true, false} : new boolean[] {path.equals("raw")}) {
                    for (int i = 0; i < WARMUPS; i++) retainedRender = render(c, raw);
                    long[] elapsed = new long[TIMED];
                    int frames = 0;
                    for (int i = 0; i < TIMED; i++) {
                        long start = System.nanoTime();
                        Snip rendered = render(c, raw);
                        elapsed[i] = System.nanoTime() - start;
                        retainedRender = rendered;
                        frames = rendered.getFrameCount();
                    }
                    if (!first) json.append(',');
                    first = false;
                    double sum = 0;
                    for (long value : elapsed) sum += value / 1_000_000.0;
                    double mean = sum / TIMED;
                    double variance = 0;
                    for (long value : elapsed) { double d = value / 1_000_000.0 - mean; variance += d * d; }
                    long[] sorted = elapsed.clone();
                    Arrays.sort(sorted);
                    json.append("{\"id\":").append(quoted(c.id)).append(",\"path\":").append(quoted(raw ? "raw" : "final"))
                            .append(",\"length\":").append(length).append(",\"frames\":").append(frames)
                            .append(",\"durationSeconds\":").append(frames / 44100.0)
                            .append(",\"minMs\":").append(sorted[0] / 1_000_000.0)
                            .append(",\"medianMs\":").append(sorted[TIMED / 2] / 1_000_000.0)
                            .append(",\"p95Ms\":").append(sorted[(int) Math.ceil(.95 * TIMED) - 1] / 1_000_000.0)
                            .append(",\"meanMs\":").append(mean)
                            .append(",\"stdDevMs\":").append(Math.sqrt(variance / TIMED)).append(",\"samplesMs\":[");
                    for (int i = 0; i < elapsed.length; i++) { if (i > 0) json.append(','); json.append(elapsed[i] / 1_000_000.0); }
                    json.append("]}");
                    System.err.println("OHM benchmark complete " + c.id + " " + (raw ? "raw" : "final"));
                }
            }
        }
        System.out.println(json.append("]}"));
    }

    private record RenderCase(String id, String group, Map<String, Float> controls,
                              int midi, float velocity, int seed, OhmGesture gesture, int model) {}
    private record Table(Map<String, String> metadata, List<String> rows) {}

    private static List<RenderCase> deterministicCases() {
        List<RenderCase> out = new ArrayList<>();
        String[] factory = {"solo", "argument", "communion", "quiet-company", "gather"};
        float[] factoryM = {0f, .9f, .9f, .25f, .9f};
        float[] factoryC = {0f, 0f, 1f, 1f, 1f};
        for (int i = 0; i < factory.length; i++) out.add(create("factory-" + factory[i], "factory",
                .5f, .5f, .65f, factoryM[i], factoryC[i], 60, .8f, 731, i == 4 ? OhmGesture.GATHER : OhmGesture.STEADY));
        for (int solo = 0; solo <= 1; solo++) {
            int controlCount = solo == 1 ? 3 : 5;
            for (int macro = 0; macro < controlCount; macro++) for (int step = 0; step <= 4; step++) {
                float[] values = {.5f, .5f, .65f, solo == 1 ? 0f : .65f, .5f};
                values[macro] = step / 4f;
                out.add(create("sweep-" + (solo == 1 ? "solo-" : "active-") + MACROS[macro].toLowerCase(Locale.ROOT) + "-" + step,
                        "quarter-sweep", values[0], values[1], values[2], values[3], values[4], 60, .8f, 731, OhmGesture.STEADY));
            }
        }
        for (int m = 0; m < 3; m++) for (int c = 0; c < 3; c++) out.add(create("matrix-" + m + "-" + c, "matrix",
                .5f, .5f, .65f, new float[] {0f, .45f, .9f}[m], new float[] {0f, .5f, 1f}[c], 60, .8f, 731, OhmGesture.STEADY));
        for (int midi : new int[] {48, 60, 72}) for (int v = 0; v < 4; v++) for (OhmGesture gesture : OhmGesture.values()) {
            out.add(create("note-" + midi + "-v" + v + "-" + gesture.name().toLowerCase(Locale.ROOT), "register-velocity",
                    .5f, .5f, .65f, .65f, .5f, midi, new float[] {0f, .3f, .65f, 1f}[v], 731, gesture));
        }
        int[] seeds = {-73, 0, Integer.MIN_VALUE, Integer.MAX_VALUE};
        for (int bits = 0; bits < 32; bits++) out.add(create("corner-" + String.format(Locale.ROOT, "%02d", bits), "binary-corner",
                bits & 1, (bits >>> 1) & 1, (bits >>> 2) & 1, (bits >>> 3) & 1, (bits >>> 4) & 1,
                (bits & 1) == 0 ? 48 : 72, .8f, seeds[(bits / 4) % seeds.length],
                ((bits / 2) & 1) == 0 ? OhmGesture.STEADY : OhmGesture.GATHER));
        Random random = new Random(997);
        for (int i = 0; i < 24; i++) {
            float[] controls = new float[5];
            for (int macro = 0; macro < controls.length; macro++) controls[macro] = interiorFloat(random);
            out.add(create("interior-" + String.format(Locale.ROOT, "%02d", i), "seeded-interior",
                    controls[0], controls[1], controls[2], controls[3], controls[4],
                    48 + random.nextInt(25), interiorFloat(random), random.nextInt(), random.nextBoolean() ? OhmGesture.GATHER : OhmGesture.STEADY));
        }
        require(out.size() == CASE_COUNT, "case generator count drift");
        return out;
    }

    private static float interiorFloat(Random random) {
        float value;
        do value = random.nextFloat(); while (value <= 0f || value >= 1f);
        return value;
    }

    private static RenderCase create(String id, String group, float touch, float body, float length, float meddling,
                                     float commune, int midi, float velocity, int seed, OhmGesture gesture) {
        Map<String, Float> controls = new LinkedHashMap<>();
        float[] values = {touch, body, length, meddling, commune};
        for (int i = 0; i < MACROS.length; i++) controls.put(MACROS[i], values[i]);
        return new RenderCase(id, group, controls, midi, velocity, seed, gesture, 2);
    }

    private static String caseText() {
        StringBuilder out = new StringBuilder();
        metadata(out, "sourceRevision", BASELINE_REVISION);
        metadata(out, "captureJdk", "17");
        metadata(out, "caseCount", Integer.toString(CASE_COUNT));
        metadata(out, "floatEncoding", "Float.toHexString: exact full binary32 values, parsed with Float.parseFloat");
        metadata(out, "groups", "factory=5; quarter-sweep=40 (25 active plus 15 solo); matrix=9; register-velocity=24; binary-corner=32; seeded-interior=24");
        metadata(out, "corners", "all 32 five-control bit masks; alternating MIDI 48/72; gesture alternates every two masks; seeds [-73,0,INT_MIN,INT_MAX] alternate every four masks");
        metadata(out, "interiorGenerator", "java.util.Random(997); five nonzero nextFloat controls, nextInt(25)+48 note, nonzero nextFloat velocity, nextInt seed, nextBoolean gesture");
        out.append(CASE_HEADER).append('\n');
        for (RenderCase c : deterministicCases()) {
            out.append(c.id).append('\t').append(c.group);
            for (String macro : MACROS) out.append('\t').append(Float.toHexString(c.controls.get(macro)));
            out.append('\t').append(c.midi).append('\t').append(Float.toHexString(c.velocity))
                    .append('\t').append(c.seed).append('\t').append(c.gesture).append('\t').append(c.model).append('\n');
        }
        return out.toString();
    }

    private static List<RenderCase> parseCases(String text) {
        Table table = table(text, CASE_HEADER);
        require(BASELINE_REVISION.equals(table.metadata.get("sourceRevision")), "case fixture source revision changed");
        require(table.rows.size() == CASE_COUNT, "expected " + CASE_COUNT + " cases, got " + table.rows.size());
        List<RenderCase> out = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (String line : table.rows) {
            String[] cells = line.split("\t", -1);
            require(cells.length == 12, "case row must have 12 fields: " + line);
            require(seen.put(cells[0], true) == null, "duplicate case " + cells[0]);
            RenderCase c = create(cells[0], cells[1], Float.parseFloat(cells[2]), Float.parseFloat(cells[3]),
                    Float.parseFloat(cells[4]), Float.parseFloat(cells[5]), Float.parseFloat(cells[6]),
                    Integer.parseInt(cells[7]), Float.parseFloat(cells[8]), Integer.parseInt(cells[9]), OhmGesture.valueOf(cells[10]));
            require(Integer.parseInt(cells[11]) == 2, "fixture must stay on model 2");
            for (float value : c.controls.values()) require(Float.isFinite(value) && value >= 0f && value <= 1f, c.id + ": invalid control");
            require(c.midi >= 48 && c.midi <= 72 && Float.isFinite(c.velocity) && c.velocity >= 0f && c.velocity <= 1f, c.id + ": invalid performance");
            out.add(c);
        }
        return out;
    }

    private static Table table(String text, String header) {
        Map<String, String> metadata = new LinkedHashMap<>();
        List<String> rows = new ArrayList<>();
        boolean foundHeader = false;
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            if (line.startsWith("# ")) {
                String[] pair = line.substring(2).split("\t", 2);
                if (pair.length == 2) metadata.put(pair[0], pair[1]);
            } else if (!foundHeader) {
                require(line.equals(header), "unexpected TSV header: " + line);
                foundHeader = true;
            } else {
                require(line.split("\t", -1).length == header.split("\t").length, "wrong TSV row width: " + line);
                rows.add(line);
            }
        }
        require(foundHeader, "TSV header absent");
        return new Table(metadata, rows);
    }

    private static String resource(String name) throws IOException {
        try (InputStream stream = OhmPerformanceProbe.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            require(stream != null, "missing " + RESOURCE_ROOT + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void writeNew(Path path, String text) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static void metadata(StringBuilder out, String key, String value) { out.append("# ").append(key).append('\t').append(value).append('\n'); }
    private static String runtimeContext() { return "java=" + System.getProperty("java.runtime.version") + ";vendor=" + System.getProperty("java.vendor") + ";os=" + System.getProperty("os.name") + ";arch=" + System.getProperty("os.arch"); }
    private static String quoted(String text) { return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""; }
    private static String git(Path root, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        require(process.waitFor() == 0, "baseline git check failed: " + output);
        return output;
    }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); } }
    private static String sha(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
