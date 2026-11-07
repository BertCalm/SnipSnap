import com.snipsnap.audio.WavWriter;
import com.snipsnap.synth.AlloyPatch;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Replay the twelve accepted public exports through the existing Kotlin engine.
 * No MATERIAL mapping or substitute synthesis is implemented by this helper.
 */
public final class ReplayReferences {
    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: ReplayReferences <package-directory> <output-directory>");
        }
        Path root = Path.of(args[0]).toAbsolutePath();
        Path out = Path.of(args[1]).toAbsolutePath();
        if (out.startsWith(root)) {
            throw new IllegalArgumentException("Put replay output outside the committed package.");
        }
        Files.createDirectories(out);
        int compared = 0;
        for (String line : Files.readAllLines(root.resolve("reference/index.tsv"))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] row = line.split("\t", -1);
            if (row.length != 5) throw new AssertionError("Malformed reference row");
            AlloyPatch patch = AlloyPatch.Companion.fromJsonText(Files.readString(root.resolve(row[1])));
            byte[] expected = Files.readAllBytes(root.resolve(row[2]));
            if (!sha256(expected).equals(row[3])) {
                throw new AssertionError("Committed golden fingerprint changed: " + row[0]);
            }
            Path rendered = out.resolve(row[0] + ".wav");
            WavWriter.INSTANCE.write(rendered.toFile(), patch.render(), WavWriter.BitDepth.PCM_24,
                false, null, null);
            byte[] actual = Files.readAllBytes(rendered);
            if (!java.util.Arrays.equals(expected, actual)) {
                throw new AssertionError("Public native WAV differs from the accepted study: " + row[0]
                    + "; expected=" + row[3] + "; actual=" + sha256(actual));
            }
            compared++;
        }
        if (compared != 12) throw new AssertionError("Expected twelve native goldens, got " + compared);
        System.out.println("{\"nativeGoldenReplaysExact\":" + compared + ",\"actualExit\":0}");
    }
}
