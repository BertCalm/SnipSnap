import com.snipsnap.synth.Halobar;
import com.snipsnap.synth.HalobarVoice;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;

/** Replays the accepted native Float32 fixture; use the synth runtime classpath. */
public class NativeReplay {
    public static void main(String[] args) throws Exception {
        boolean publicRender = args.length > 1 && args[1].equals("render");
        var lines = Files.readAllLines(Path.of(args[0]));
        int cases = 0;
        for (String line : lines.subList(1, lines.size())) {
            cases++;
            String[] fields = line.split("\t", -1);
            HalobarVoice voice = HalobarVoice.valueOf(fields[1]);
            float velocity = Float.parseFloat(fields[2]);
            int oversample = Integer.parseInt(fields[3]);
            var controls = new LinkedHashMap<String, Float>();
            if (!fields[4].isEmpty()) for (String pair : fields[4].split(",")) {
                String[] value = pair.split("=");
                controls.put(value[0], Float.parseFloat(value[1]));
            }
            // The normal renderer is 4x. The three 8x cases use its existing
            // diagnostic entry point, whose JVM name includes the module suffix.
            float[] samples = publicRender && oversample == 4
                    ? Halobar.INSTANCE.render(voice, controls, velocity, 1).getSamples()
                    : Halobar.INSTANCE.renderProbe$synth(voice, controls, velocity, true, oversample).getSamples();
            ByteBuffer bytes = ByteBuffer.allocate(samples.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            bytes.asFloatBuffer().put(samples);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
            if (samples.length != Integer.parseInt(fields[5]) || !sha.equals(fields[6])) {
                throw new AssertionError(fields[0] + " changed: " + samples.length + " frames, " + sha);
            }
        }
        System.out.println("All " + cases + " native cases match the accepted Float32 fixture.");
    }
}
