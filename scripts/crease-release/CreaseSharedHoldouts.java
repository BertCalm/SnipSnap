import com.snipsnap.synth.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Fresh ground truth, using the same compiled Kotlin circuit and state adapter. */
public final class CreaseSharedHoldouts {
    public static void main(String[] args) throws Throwable {
        Path out = Path.of(args[0]); Files.createDirectories(out);
        TreeSet<Integer> cuts = new TreeSet<>();
        for (String line: Files.readAllLines(Path.of(args[1]))) if (!line.isBlank()) cuts.add(Integer.parseInt(line));
        var patch = CreasePresets.INSTANCE.all().stream().filter(p -> p.getId().equals("reed-mark")).findFirst().orElseThrow().toPatch();
        var warm = CreaseReleaseExperiment.warm(patch, 4, true);
        var held = warm.audio.array();
        CreaseReleaseExperiment.writeFloat(out.resolve("held.f32"), held);
        var running = new CreaseReleaseExperiment.Circuit(patch, 4);
        int at=0, count=0, repeats=0;
        double prefix=0, fork=0;
        int frames=53106;
        for (int cut: cuts) {
            if (cut < 0 || cut >= held.length) throw new AssertionError("holdout outside captured trajectory");
            while (at < cut) {
                prefix=Math.max(prefix, Math.abs((double)running.frame(false)-held[at])); at++;
            }
            var released=running.copy();
            fork=Math.max(fork, CreaseReleaseExperiment.distance(running.state(), released.state()));
            float[] tail=new float[frames];
            for (int i=0;i<frames;i++) tail[i]=released.frame(true);
            if (released.rawPeak > .91*patch.getVelocity()+1e-9) throw new AssertionError("raw ceiling");
            if (count%19==0) {
                var repeat=running.copy();
                for(int i=0;i<frames;i++) if(Float.floatToRawIntBits(repeat.frame(true))!=Float.floatToRawIntBits(tail[i])) throw new AssertionError("repeat differs");
                repeats++;
            }
            CreaseReleaseExperiment.writeFloat(out.resolve(String.format("exact-%07d.f32",cut)), tail);
            if (++count%64==0) System.out.printf("%d/%d fresh exact continuations%n", count, cuts.size());
        }
        if (prefix!=0 || fork!=0) throw new AssertionError("prefix/fork mismatch");
        Files.writeString(out.resolve("capture.json"), String.format(Locale.ROOT,
            "{\"publicPrefixError\":%.12g,\"forkStateError\":%.12g,\"deterministicRepeats\":%d,\"testCuts\":%s}%n",prefix,fork,repeats,cuts));
    }
}
