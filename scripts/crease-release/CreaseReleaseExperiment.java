import com.snipsnap.synth.CreaseKeyProbe;
import com.snipsnap.synth.CreaseKeys;
import com.snipsnap.synth.CreasePatch;
import com.snipsnap.synth.CreasePreset;
import com.snipsnap.synth.CreasePresets;
import com.snipsnap.synth.CreaseVoice;
import com.snipsnap.synth.Keys;
import com.snipsnap.audio.Loudness;
import java.io.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Research only. Invoke the actual private model-3 circuit; do not duplicate or alter it.
 * Reflection is deliberately confined to this offline harness, with exact full-state copy
 * and public CreaseKeys parity checks. It is not a proposed production API or transport.
 */
public final class CreaseReleaseExperiment {
    static final int RATE = 44100;
    static final double TAU = 2 * Math.PI;
    static final Class<?> SHAPE, MODEL, OUTPUT;
    static final Constructor<?> MAKE_SHAPE, MAKE_MODEL, MAKE_OUTPUT;
    static final MethodHandle FORCE, STEP, CONDITION, MODEL_STATE, OUTPUT_STATE;
    static {
        try {
            SHAPE = Class.forName("com.snipsnap.synth.Crease$Shape");
            MODEL = Class.forName("com.snipsnap.synth.Crease$Model");
            OUTPUT = Class.forName("com.snipsnap.synth.Crease$OutputPath");
            Class<?> history = Class.forName("com.snipsnap.synth.CreaseHistory");
            MAKE_SHAPE = constructor(SHAPE, CreaseVoice.class, Map.class, double.class, int.class);
            MAKE_MODEL = constructor(MODEL, SHAPE, int.class, double.class, history, boolean.class);
            MAKE_OUTPUT = constructor(OUTPUT, int.class, int.class);
            FORCE = method(SHAPE, "forcing", double.class, double.class, Double.class);
            STEP = method(MODEL, "keyStep", double.class, double.class, double.class, double.class);
            CONDITION = method(OUTPUT, "step", Double.class, double.class);
            MODEL_STATE = method(MODEL, "state", double[].class);
            OUTPUT_STATE = method(OUTPUT, "state", double[].class);
        } catch (Throwable error) { throw new ExceptionInInitializerError(error); }
    }

    static Constructor<?> constructor(Class<?> type, Class<?>... args) throws Exception {
        Constructor<?> c = type.getDeclaredConstructor(args); c.setAccessible(true); return c;
    }
    static MethodHandle method(Class<?> type, String name, Class<?> result, Class<?>... args) throws Exception {
        Method m = type.getDeclaredMethod(name, args); m.setAccessible(true);
        Class<?>[] signature = new Class<?>[args.length + 1]; signature[0] = Object.class;
        System.arraycopy(args, 0, signature, 1, args.length);
        return MethodHandles.lookup().unreflect(m).asType(MethodType.methodType(result, signature));
    }
    static Field field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f;
    }
    static double value(Object object, String name) throws Exception { return field(object, name).getDouble(object); }
    static double smooth(double t) { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); }
    static double distance(double[] a, double[] b) {
        if (a.length != b.length) throw new AssertionError("state length changed");
        double worst = 0; for (int i = 0; i < a.length; i++) worst = Math.max(worst, Math.abs(a[i] - b[i]));
        return worst;
    }
    static void copyPrimitiveState(Object from, Object to) throws Exception {
        for (Field f : from.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) continue;
            f.setAccessible(true);
            if (f.getType().isPrimitive()) f.set(to, f.get(from));
            else throw new AssertionError("New mutable reference needs an explicit snapshot rule: " + f.getName());
        }
    }
    static final class Floats {
        float[] data = new float[65536]; int size;
        void add(float v) { if (size == data.length) data = Arrays.copyOf(data, size * 2); data[size++] = v; }
        float[] array() { return Arrays.copyOf(data, size); }
        float[] last(int n) { if (size < n) throw new AssertionError("not enough real lead-in"); return Arrays.copyOfRange(data, size-n, size); }
    }
    static final class Circuit {
        final CreasePatch patch; final int os, rate, frames, rootCycles;
        final double hz, transitionStart, transitionEnd, settle, attack;
        final Object shape; Object model; final Object output;
        long tick; double rawPeak;
        Circuit(CreasePatch patch, int os) throws Exception {
            this.patch = patch; this.os = os; rate = RATE * os;
            Map<String, Float> macros = new LinkedHashMap<>(patch.getMacros()); macros.put("HOLD", 1f);
            shape = MAKE_SHAPE.newInstance(patch.getVoice(), macros, (double)patch.getVelocity(), 3);
            double requested = Keys.INSTANCE.midiHz(patch.getMidi());
            rootCycles = Math.max(8, (int)Math.round(value(shape, "loopSeconds") * requested));
            frames = (int)Math.round(rootCycles * (double)RATE / requested);
            hz = rootCycles * (double)RATE / frames;
            model = newModel(); output = MAKE_OUTPUT.newInstance(rate, os);
            int voice = patch.getVoice().ordinal();
            transitionStart = new double[]{.18,.24,.45}[voice];
            transitionEnd = new double[]{.50,.65,1.15}[voice];
            settle = new double[]{.18,.24,.50}[voice]; attack = value(shape, "attack");
        }
        Object newModel() throws Exception {
            Object history = Class.forName("com.snipsnap.synth.CreaseHistory").getEnumConstants()[0];
            return MAKE_MODEL.newInstance(shape, rate, hz, history, true);
        }
        Circuit copy() throws Throwable {
            Circuit c = new Circuit(patch, os); c.tick = tick; c.rawPeak = rawPeak;
            copyPrimitiveState(model, c.model); copyPrimitiveState(output, c.output);
            double[] sourceRing = (double[])field(output, "ring").get(output);
            System.arraycopy(sourceRing, 0, (double[])field(c.output, "ring").get(c.output), 0, sourceRing.length);
            Object[] sourceFilters = (Object[])field(output, "filters").get(output);
            Object[] targetFilters = (Object[])field(c.output, "filters").get(c.output);
            for (int i=0;i<sourceFilters.length;i++) copyPrimitiveState(sourceFilters[i], targetFilters[i]);
            if (distance(state(), c.state()) != 0) throw new AssertionError("Incomplete state fork");
            return c;
        }
        double[] state() throws Throwable {
            double[] a = (double[])MODEL_STATE.invokeExact(model), b = (double[])OUTPUT_STATE.invokeExact(output);
            double[] result = Arrays.copyOf(a, a.length + b.length + 2);
            System.arraycopy(b, 0, result, a.length, b.length);
            long period = (long)frames * os;
            result[result.length-2] = (tick % period) / (double)period;
            result[result.length-1] = ((tick % period) * rootCycles % period) / (double)period;
            return result;
        }
        double source() throws Throwable {
            long period = (long)frames * os, cycle = tick / period, i = tick % period;
            double t = cycle * (double)frames / RATE + i / (double)rate;
            Double fraction = i / (double)period;
            double periodic = (double)FORCE.invokeExact(shape, t, fraction);
            if (t >= transitionEnd) return periodic;
            double finite = (double)FORCE.invokeExact(shape, t, (Double)null);
            return finite + (periodic - finite) * smooth((t-transitionStart)/(transitionEnd-transitionStart));
        }
        double raw(boolean withdraw) throws Throwable {
            long period = (long)frames * os, cycle = tick / period, i = tick % period;
            double time = cycle * (double)frames / RATE + i / (double)rate;
            double phase = TAU * ((i * (double)rootCycles / period) % 1.0);
            double amp = patch.getVelocity() * smooth(time/attack) * (.68 + .32 * (1-smooth(time/settle)));
            double drive = withdraw ? 0 : source();
            double result = (double)STEP.invokeExact(model, phase, amp, drive);
            if (!Double.isFinite(result)) throw new AssertionError("nonfinite raw circuit");
            rawPeak = Math.max(rawPeak, Math.abs(result)); tick++; return result;
        }
        float frame(boolean withdraw) throws Throwable {
            Double out = null;
            for (int i=0;i<os;i++) out = (Double)CONDITION.invokeExact(output, raw(withdraw));
            if (out == null || !Double.isFinite(out)) throw new AssertionError("output phase or finiteness");
            return out.floatValue();
        }
        double excess() throws Throwable {
            double[] s = (double[])MODEL_STATE.invokeExact(model);
            double max = 0; for (int i=0;i<5;i++) max = Math.max(max, Math.abs(s[i])); return max;
        }
    }
    static final class Warm {
        Circuit circuit; Floats audio; int cycles; double recurrence, parity, loudness;
    }
    static Warm warm(CreasePatch patch, int os, boolean publicParity) throws Throwable {
        Warm w = new Warm(); w.circuit = new Circuit(patch, os); w.audio = new Floats();
        double[] before = w.circuit.state();
        for (int cycle=1;cycle<=28;cycle++) {
            if ((cycle+1L)*w.circuit.frames > 64L*RATE) throw new AssertionError("warm-up cap");
            for(int i=0;i<w.circuit.frames;i++) w.audio.add(w.circuit.frame(false));
            double[] after = w.circuit.state(); w.recurrence = distance(before, after); before = after;
            w.cycles = cycle;
            if (cycle >=4 && w.recurrence < 1e-7) break;
        }
        if (!(w.recurrence < 1e-7)) throw new AssertionError("did not converge");
        for(int i=0;i<w.circuit.frames;i++) w.audio.add(w.circuit.frame(false));
        w.loudness = Loudness.INSTANCE.of(w.audio.last(w.circuit.frames), 1, RATE);
        if(publicParity) {
            CreaseKeyProbe p = CreaseKeys.INSTANCE.probe(patch, patch.getMidi(), os, false, () -> false);
            float[] accepted = p.getPrelevel(), ours = w.audio.array();
            if (accepted.length != ours.length) throw new AssertionError("public keyboard trajectory length differs");
            for (int i=0;i<ours.length;i++) w.parity = Math.max(w.parity, Math.abs((double)ours[i]-accepted[i]));
            if (w.parity != 0) throw new AssertionError("public keyboard trajectory differs: " + w.parity);
        }
        return w;
    }
    static void writeFloat(Path path, float[] data) throws Exception {
        try(DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            for(float v:data) out.writeInt(Integer.reverseBytes(Float.floatToRawIntBits(v)));
        }
    }
    static void trace(PrintWriter out, double seconds, Circuit c, boolean withdraw, float gain) throws Throwable {
        double[] s = (double[])MODEL_STATE.invokeExact(c.model);
        out.printf(Locale.ROOT,"%.9f,%.12g",seconds,withdraw?0:c.source());
        for(double x:s) out.printf(Locale.ROOT,",%.12g",x);
        out.printf(Locale.ROOT,",%.12g%n",gain);
    }
    static void caseAt(Path folder, String id, Warm warm, double phase, double releaseSeconds) throws Throwable {
        Circuit cut = warm.circuit.copy(); Floats lead = new Floats();
        for(float x:warm.audio.last(RATE)) lead.add(x);
        int offset = (int)Math.round(phase * cut.frames);
        for(int f=0;f<offset;f++) lead.add(cut.frame(false));
        float[] prefix = lead.last(RATE); double[] captured = cut.state();
        String stem = id+"-p"+(int)Math.round(phase*100)+"-m"+cut.patch.getMidi()+"-x"+cut.os;
        int releaseFrames = (int)Math.round(releaseSeconds*RATE), postFrames = releaseFrames + (int)(.12*RATE);
        double drainSeconds = Math.min(60, Math.max(4, 14*(.012+1.5*value(cut.shape,"recovery"))*(1+2.2*value(cut.shape,"split"))+1));
        for(int mode=0;mode<3;mode++) {
            Circuit c = cut.copy(); double fork = distance(c.state(), captured);
            if(mode==2) c.model = c.newModel(); // deliberately wrong carry-state canary; conditioning and clocks continue
            double postResetError = distance(c.state(), captured);
            float[] samples = Arrays.copyOf(prefix, RATE+postFrames);
            float gain=1f, decrement=1f/((float)releaseSeconds*RATE);
            Path csv=folder.resolve(stem+"-"+mode+".csv");
            try(PrintWriter t = new PrintWriter(Files.newBufferedWriter(csv))) {
                t.println("seconds,source,pressure,deformationA,deformationB,recoveryA,recoveryB,energyA,energyB,loadA,loadB,gain");
                trace(t,0,c,mode!=0,gain);
                for(int f=0;f<postFrames;f++) {
                    float x=c.frame(mode!=0); gain=Math.max(0f,gain-decrement);
                    samples[RATE+f]=x*gain;
                    if((f+1)%221==0) trace(t,(f+1)/(double)RATE,c,mode!=0,gain);
                }
                if(mode==1) {
                    Circuit again = cut.copy(); float repeatGain = 1f;
                    for(int f=0;f<postFrames;f++) {
                        float x = again.frame(true); repeatGain = Math.max(0f, repeatGain-decrement);
                        if (Float.floatToRawIntBits(x*repeatGain) != Float.floatToRawIntBits(samples[RATE+f]))
                            throw new AssertionError("nondeterministic state fork");
                    }
                    int remaining=(int)Math.ceil(drainSeconds*c.rate)-(int)((long)postFrames*c.os);
                    for(int i=0;i<remaining;i++) {
                        c.raw(true); // inaudible mechanics audit only; no pretend passive-energy claim
                        if((i+1)%(c.os*221)==0) trace(t,(postFrames+(i+1)/(double)c.os)/RATE,c,true,0f);
                    }
                    if(c.excess()>=1e-4) throw new AssertionError("deformation did not drain: "+c.excess());
                }
            }
            if(c.rawPeak > .91*cut.patch.getVelocity()+1e-9) throw new AssertionError("analytic raw headroom bound");
            writeFloat(folder.resolve(stem+"-"+mode+".f32"),samples);
            try(PrintWriter m = new PrintWriter(Files.newBufferedWriter(folder.resolve(stem+"-"+mode+".json")))) {
                m.printf(Locale.ROOT,"{\"preset\":\"%s\",\"mode\":%d,\"phase\":%.9f,\"midi\":%d,\"oversample\":%d,\"warmCycles\":%d,\"loopFrames\":%d,\"rootCycles\":%d,\"renderedHz\":%.12g,\"recurrence\":%.12g,\"publicPrefixMaxError\":%.12g,\"forkStateError\":%.12g,\"postResetStateError\":%.12g,\"rawPeak\":%.12g,\"maxFilterState\":%.12g,\"finalExcess\":%.12g,\"sustainLoudness\":%.12g,\"velocity\":%.12g,\"deterministicForkVerified\":%s,\"releaseSeconds\":%.9f,\"mechanicsDrainSeconds\":%.9f}%n",
                    id,mode,phase,c.patch.getMidi(),c.os,warm.cycles,c.frames,c.rootCycles,c.hz,warm.recurrence,warm.parity,fork,postResetError,c.rawPeak,value(c.output,"maxAbsState"),c.excess(),warm.loudness,(double)c.patch.getVelocity(),mode==1?"true":"false",releaseSeconds,drainSeconds);
            }
        }
    }
    public static void main(String[] args) throws Throwable {
        Locale.setDefault(Locale.ROOT);
        if(args.length<1) throw new IllegalArgumentException("output directory required");
        Path folder=Path.of(args[0]); Files.createDirectories(folder);
        boolean reference=args.length>1 && args[1].equals("reference");
        boolean silence=args.length>1 && args[1].equals("silence");
        long start=System.nanoTime();
        for(String id:List.of("paper-keys","reed-mark","ink-ribbon")) {
            CreasePreset preset=CreasePresets.INSTANCE.all().stream().filter(p->p.getId().equals(id)).findFirst().orElseThrow();
            CreasePatch patch=preset.toPatch();
            if(reference) patch=patch.copy(patch.getName(),patch.getVoice(),patch.getMacros(),84,patch.getVelocity(),patch.getModel());
            if(silence) patch=patch.copy(patch.getName(),patch.getVoice(),patch.getMacros(),60,0f,patch.getModel());
            int os=reference?16:4;
            Warm warm=warm(patch,os,true);
            for(double phase:reference||silence?new double[]{.5}:new double[]{0,.25,.5,.75}) caseAt(folder,id,warm,phase,1.2);
            if(reference) { Warm normal=warm(patch,4,true);caseAt(folder,id,normal,.5,1.2); }
            System.out.printf("%s x%d: %d true warm cycles, public-prefix difference %.3g%n",id,os,warm.cycles,warm.parity);
        }
        System.out.printf("Completed actual Kotlin circuit experiment in %.3f seconds%n",(System.nanoTime()-start)/1e9);
    }
}
