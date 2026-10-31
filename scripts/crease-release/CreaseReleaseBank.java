import com.snipsnap.synth.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Offline research adapter. All sound comes from the unchanged, compiled Kotlin circuit. */
public final class CreaseReleaseBank {
    static final int RATE = 44100;
    static final double RELEASE = 1.2;
    static final double[] EARLY = {0,.00025,.0005,.001,.0015,.002,.003,.004,.006,.008,
        .010,.014,.018,.022,.030,.040,.060,.080,.100};

    static String ints(Collection<Integer> values) {
        return values.toString();
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Throwable {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args[0]); Files.createDirectories(out);
        long started = System.nanoTime();
        CreasePatch patch = CreasePresets.INSTANCE.all().stream()
            .filter(p -> p.getId().equals("reed-mark")).findFirst().orElseThrow().toPatch();
        require(patch.getMidi() == 60 && patch.getModel() == 3, "approved patch changed");
        var warm = CreaseReleaseExperiment.warm(patch, 4, true);
        var base = warm.circuit;
        int loopFrames = base.frames, loopStart = warm.cycles * loopFrames;
        float[] held = warm.audio.array();
        int lead = (int)Math.ceil(loopFrames / (double)base.rootCycles / 2) + 4;
        int post = (int)Math.round(RELEASE * RATE) + lead + 8;
        int bankFrames = lead + post;

        Map<Integer, TreeSet<Integer>> grids = new LinkedHashMap<>();
        TreeSet<Integer> captures = new TreeSet<>();
        for (int n : new int[]{16,32,64}) {
            TreeSet<Integer> cuts = new TreeSet<>();
            for (double seconds : EARLY) cuts.add((int)Math.round(seconds * RATE));
            for (int j=0; j<(warm.cycles+1)*n; j++) cuts.add((int)Math.round(j*loopFrames/(double)n));
            grids.put(n,cuts); captures.addAll(cuts);
        }
        TreeSet<Integer> tests = new TreeSet<>();
        for (double seconds : new double[]{0,1.0/RATE,2.0/RATE,7.0/RATE,.0007,.0013,.0027,
                .005,.009,.013,.019,.029,.043,.071,.099,.137,.231,.411,.777,1.111,2.345,5.678,
                9.123,12.345,22.222}) tests.add((int)Math.round(seconds * RATE));
        Random random = new Random(0x43524541L);
        for(int i=0;i<80;i++) tests.add(1+random.nextInt(loopStart-1));
        for(int i=0;i<80;i++) tests.add(loopStart+random.nextInt(loopFrames));
        for(int p : new int[]{0,1,loopFrames/2,loopFrames-1})
            for(int d : new int[]{-2,-1,0,1,2}) if(loopStart+p+d>=0) tests.add(loopStart+p+d);
        captures.addAll(tests);
        Map<Integer,Integer> bankIds = new TreeMap<>();
        int serial = 0; TreeSet<Integer> allBankCuts = new TreeSet<>();
        for(var cuts : grids.values()) allBankCuts.addAll(cuts);
        for(int cut:allBankCuts) bankIds.put(cut,serial++);

        CreaseReleaseExperiment.writeFloat(out.resolve("held.f32"),held);
        var running = new CreaseReleaseExperiment.Circuit(patch,4);
        int at = 0, count = 0, repeats = 0;
        double prefixError = 0, forkError = 0;
        for(int cut:captures) {
            while(at<cut) {
                float actual = running.frame(false);
                if(at<held.length) prefixError = Math.max(prefixError,Math.abs((double)actual-held[at]));
                at++;
            }
            var released = running.copy();
            forkError = Math.max(forkError,CreaseReleaseExperiment.distance(running.state(),released.state()));
            float[] tail = new float[post];
            for(int i=0;i<post;i++) tail[i]=released.frame(true);
            require(released.rawPeak <= .91*patch.getVelocity()+1e-9,"raw ceiling");
            if(bankIds.containsKey(cut)) {
                float[] bank = new float[bankFrames];
                for(int i=0;i<lead;i++) {
                    int index=cut-lead+i;
                    bank[i]=index<0?0:held[index];
                }
                System.arraycopy(tail,0,bank,lead,tail.length);
                CreaseReleaseExperiment.writeFloat(out.resolve(String.format("bank-%04d.f32",bankIds.get(cut))),bank);
            }
            if(tests.contains(cut)) {
                CreaseReleaseExperiment.writeFloat(out.resolve(String.format("exact-%07d.f32",cut)),tail);
                var fading = running.copy();
                float[] fade = new float[post];
                for(int i=0;i<post;i++) fade[i]=fading.frame(false);
                CreaseReleaseExperiment.writeFloat(out.resolve(String.format("fade-%07d.f32",cut)),fade);
                if(count%19==0 || cut==0) {
                    var repeat=running.copy();
                    for(int i=0;i<post;i++) require(Float.floatToRawIntBits(repeat.frame(true))==
                        Float.floatToRawIntBits(tail[i]),"repeat differs");
                    repeats++;
                }
            }
            if(++count%32==0) System.out.printf("%d/%d real circuit captures%n",count,captures.size());
        }
        require(prefixError==0 && forkError==0,"held public prefix / state fork differs");
        try(PrintWriter w=new PrintWriter(Files.newBufferedWriter(out.resolve("capture.json")))) {
            w.printf("{\"preset\":\"reed-mark\",\"midi\":60,\"model\":3,\"rate\":44100,"+
                "\"velocity\":%.12g,\"loopStart\":%d,\"loopFrames\":%d,\"rootCycles\":%d,"+
                "\"hz\":%.12g,\"warmCycles\":%d,\"heldFrames\":%d,\"leadFrames\":%d,"+
                "\"tailFrames\":%d,\"releaseSeconds\":1.2,\"sustainLoudness\":%.12g,"+
                "\"publicPrefixError\":%.12g,\"forkStateError\":%.12g,\"deterministicRepeats\":%d,"+
                "\"captureSeconds\":%.9f,\"bankCuts\":%s,\"testCuts\":%s,\"grids\":{",
                (double)patch.getVelocity(),loopStart,loopFrames,base.rootCycles,base.hz,warm.cycles,
                held.length,lead,bankFrames,warm.loudness,prefixError,forkError,repeats,
                (System.nanoTime()-started)/1e9,ints(allBankCuts),ints(tests));
            int index=0;
            for(var entry:grids.entrySet()) w.printf("%s\"%d\":%s",index++==0?"":",",entry.getKey(),ints(entry.getValue()));
            w.print("},\"macros\":{"); index=0;
            for(var entry:patch.getMacros().entrySet()) w.printf("%s\"%s\":%.12g",index++==0?"":",",entry.getKey(),(double)entry.getValue());
            w.println("}}");
        }
        System.out.printf("Verified %d bank positions, %d exact tests, %d repeats; %.3f seconds%n",
            bankIds.size(),tests.size(),repeats,(System.nanoTime()-started)/1e9);
    }
}
