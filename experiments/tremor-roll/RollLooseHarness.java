import com.snipsnap.synth.*;
import com.snipsnap.audio.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Focused ROLL audition; no product sources or existing recipes are changed. */
public final class RollLooseHarness {
  static Map<String,Object> obj(Object... pairs) {
    var result = new LinkedHashMap<String,Object>();
    for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);
    return result;
  }
  static String json(Object x) {
    if(x==null) return "null";
    if(x instanceof String s) return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
    if(x instanceof Float f) return Double.toString(f.doubleValue());
    if(x instanceof Number || x instanceof Boolean) return x.toString();
    if(x instanceof Map<?,?> map) {var join=new StringJoiner(",","{","}");for(var e:map.entrySet())join.add(json(e.getKey().toString())+":"+json(e.getValue()));return join.toString();}
    if(x instanceof Iterable<?> list) {var join=new StringJoiner(",","[","]");for(var value:list)join.add(json(value));return join.toString();}
    throw new IllegalArgumentException(x.getClass().toString());
  }
  static String hash(float[] samples) throws Exception {
    var bytes=ByteBuffer.allocate(samples.length*4);
    for(float sample:samples) bytes.putFloat(sample);
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
  }
  static Snip match(Snip snip) {
    float[] samples=snip.getSamples().clone();
    float gain=.03f/Math.max(1e-9f,Loudness.INSTANCE.of(snip));
    float peak=snip.peak();if(peak*gain>.99f)gain=.99f/peak;
    for(int i=0;i<samples.length;i++) samples[i]*=gain;
    return new Snip(samples,snip.getChannels(),snip.getSampleRate());
  }
  static void guard(TremorRender r, String id) {
    if(r.getRailHits()!=0 || !Float.isFinite(r.getRawPeak())) throw new IllegalStateException("Raw guard "+id);
    for(float x:r.getSnip().getSamples()) if(!Float.isFinite(x)||Math.abs(x)>1.00001f) throw new IllegalStateException("Output guard "+id);
    if(r.getLoopStart()>=0 && !(Keys.INSTANCE.seamError(r.getSnip().getSamples(),r.getLoopStart())<Keys.MAX_SEAM_ERROR)) throw new IllegalStateException("Held join "+id);
  }
  static boolean mechanics(TremorRender a, TremorRender b) {
    return a.getContactCount()==b.getContactCount() && a.getContactImpulse()==b.getContactImpulse()
      && a.getStrikes().equals(b.getStrikes()) && a.getCircuitWork()==b.getCircuitWork()
      && a.getActuatorPeak()==b.getActuatorPeak() && a.getMaxModalEnergy()==b.getMaxModalEnergy()
      && a.getEndBeadEnergy()==b.getEndBeadEnergy() && Arrays.equals(a.getEnergy(),b.getEnergy());
  }
  static TremorRender candidate(TremorVoice v,Map<String,Float> m,float velocity) {
    return TremorRollLooseCandidate.INSTANCE.play(v,m,velocity,new TremorProbe());
  }
  static TremorRender current(TremorVoice v,Map<String,Float> m,float velocity) {
    return TremorRollRoundedCandidate.INSTANCE.play(v,m,velocity,new TremorProbe());
  }
  static Map<String,Float> modified(Map<String,Float> base,Object... pairs) {
    var m=new LinkedHashMap<String,Float>(base);for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],(Float)pairs[i+1]);return m;
  }
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]);Files.createDirectories(root.resolve("audio"));Files.createDirectories(root.resolve("matrix"));Files.createDirectories(root.resolve("recipes"));
    var recipes=new ArrayList<Map<String,Object>>();
    var roster=new ArrayList<TremorPatch>();
    roster.add(new TremorPatch("ROLL DEFAULT",TremorVoice.ROLL,Tremor.INSTANCE.defaults(TremorVoice.ROLL),2));
    roster.addAll(TremorPresets.INSTANCE.forVoice(TremorVoice.ROLL));
    roster.add(new TremorPatch("HELD ROLL",TremorVoice.ROLL,modified(Tremor.INSTANCE.defaults(TremorVoice.ROLL),"HOLD",1f),2));
    var clips=new ArrayList<Map<String,Object>>();int deterministic=0;int mechanicsEqual=0;
    float[] strengths={.2f,.55f,1f};String[] touches={"soft","middle","hard"};
    for(TremorPatch patch:roster){
      String id=patch.getName().toLowerCase(Locale.ROOT).replace(' ','_');
      Files.writeString(root.resolve("recipes").resolve(id+".json"),patch.toJsonText()+"\n");
      recipes.add(obj("id",id,"name",patch.getName(),"voice","ROLL","midi",Tremor.INSTANCE.midiFor(TremorVoice.ROLL,patch.getMacros().get("TUNE")),"macros",patch.getMacros(),"held",patch.getMacros().get("HOLD")>=.5f,"isReference",patch.getName().equals("LIGHT BED"),"recipeFile","recipes/"+id+".json"));
      for(int t=0;t<3;t++){
        boolean isReference=patch.getName().equals("LIGHT BED");
        if(!isReference && t==0)continue;
        float velocity=strengths[t];long began=System.nanoTime();
        TremorRender baseline=current(TremorVoice.ROLL,patch.getMacros(),velocity),proposal=candidate(TremorVoice.ROLL,patch.getMacros(),velocity);
        var grainAudit=new LinkedHashMap<String,Double>(TremorRollLooseCandidate.INSTANCE.lastShakerDiagnostics());
        var previousGrainAudit=new LinkedHashMap<String,Double>(TremorRollRoundedCandidate.INSTANCE.lastShakerDiagnostics());
        guard(baseline,id);guard(proposal,id);
        if(isReference && !Arrays.equals(baseline.getSnip().getSamples(),proposal.getSnip().getSamples()))throw new IllegalStateException("Preferred LIGHT BED changed "+touches[t]);
        if(!mechanics(baseline,proposal)) throw new IllegalStateException("Mechanical state changed "+id+" "+touches[t]);
        mechanicsEqual++;
        if(!Arrays.equals(proposal.getSnip().getSamples(),candidate(TremorVoice.ROLL,patch.getMacros(),velocity).getSnip().getSamples()))throw new IllegalStateException("Not deterministic "+id);
        deterministic++;
        for(String variant:isReference?List.of("reference"):List.of("previous","looser")){
          TremorRender r=variant.equals("looser")?proposal:baseline;Snip s=match(r.getSnip());String clip=id+"_"+touches[t]+"_"+variant;
          WavWriter.INSTANCE.write(root.resolve("audio").resolve(clip+".wav").toFile(),s,WavWriter.BitDepth.PCM_16,false,null,null);
          clips.add(obj("id",clip,"presetId",id,"variant",variant,"touch",touches[t],"velocity",velocity,"file","audio/"+clip+".wav","frames",s.getFrameCount(),"seconds",s.getDurationSeconds(),"loopStartFrame",r.getLoopStart(),"loopStartSeconds",r.getLoopStart()<0?null:r.getLoopStart()/44100.0,"rawPeak",r.getRawPeak(),"railHits",r.getRailHits(),"level",Loudness.INSTANCE.of(s),"peak",s.peak(),"rawDc",r.getRawDc(),"contacts",r.getContactCount(),"contactImpulse",r.getContactImpulse(),"nativeFloatSha256",hash(r.getSnip().getSamples()),"matchedFloatSha256",hash(s.getSamples()),"mechanicsMatch",true,"shakerDiagnostics",variant.equals("looser")?grainAudit:previousGrainAudit,"constructedSeam",r.getLoopStart()<0?null:Keys.INSTANCE.seamError(r.getSnip().getSamples(),r.getLoopStart())));
        }
        System.out.println(id+" "+touches[t]+" contacts="+proposal.getContactCount()+" raw="+proposal.getRawPeak()+" elapsedMs="+(System.nanoTime()-began)/1e6);
      }
    }
    int unchanged=0;
    for(TremorVoice voice:TremorVoice.values()) if(voice!=TremorVoice.ROLL){
      for(float v:strengths){var m=Tremor.INSTANCE.defaults(voice);TremorRender a=current(voice,m,v),b=candidate(voice,m,v);guard(b,voice.name());if(!Arrays.equals(a.getSnip().getSamples(),b.getSnip().getSamples()))throw new IllegalStateException("Other voice changed "+voice);unchanged++;}
      var m=modified(Tremor.INSTANCE.defaults(voice),"HOLD",1f);if(!Arrays.equals(current(voice,m,.55f).getSnip().getSamples(),candidate(voice,m,.55f).getSnip().getSamples()))throw new IllegalStateException("Other held voice changed "+voice);unchanged++;
    }
    var matrix=new ArrayList<Map<String,Object>>();
    if(args.length<4||!args[3].equals("quick"))for(float tune:new float[]{0f,.5f,1f})for(float beads:new float[]{0f,.5f,1f})for(float velocity:strengths)for(float hold:new float[]{0f,1f}){
      var m=modified(Tremor.INSTANCE.defaults(TremorVoice.ROLL),"TUNE",tune,"BEADS",beads,"HOLD",hold);String id="roll_t"+tune+"_b"+beads+"_v"+velocity+"_h"+hold;
      long start=System.nanoTime();TremorRender a=current(TremorVoice.ROLL,m,velocity),b=candidate(TremorVoice.ROLL,m,velocity);guard(b,id);var grainAudit=new LinkedHashMap<String,Double>(TremorRollLooseCandidate.INSTANCE.lastShakerDiagnostics());
      if(!mechanics(a,b))throw new IllegalStateException("Matrix mechanics changed "+id);
      WavWriter.INSTANCE.write(root.resolve("matrix").resolve(id+".wav").toFile(),match(b.getSnip()),WavWriter.BitDepth.PCM_16,false,null,null);
      matrix.add(obj("id",id,"file","matrix/"+id+".wav","midi",Tremor.INSTANCE.midiFor(TremorVoice.ROLL,tune),"macros",m,"velocity",velocity,"loopStartFrame",b.getLoopStart(),"frames",b.getSnip().getFrameCount(),"rawPeak",b.getRawPeak(),"rails",b.getRailHits(),"mechanicsMatch",true,"shakerDiagnostics",grainAudit,"renderPairMs",(System.nanoTime()-start)/1e6,"seam",b.getLoopStart()<0?null:Keys.INSTANCE.seamError(b.getSnip().getSamples(),b.getLoopStart())));
    }
    Files.writeString(root.resolve("render-manifest.json"),json(obj("engine","tremor","phase","tremor_roll_shaker_r3","sampleRate",44100,"sourceCommit",args[1],"previousCandidateSha256","a472d6bf0b030c2ada13c87064fdec58bd607a0e9d015db09dd8d558099d90f0","candidateSha256",args[2],"status","Looser ROLL listening prototype; R2 grain tone and preferred LIGHT BED preserved","levelPolicy","Both variants matched to the loudest200ms RMS0.03; judge the bead texture, not playback volume.","loopPolicy","Repeat plays attack once then exact marked held region; constructed production join retained.","recipes",recipes,"cases",clips,"validation",obj("deterministicCandidateRepeats",deterministic,"mechanicsPairsExact",mechanicsEqual,"otherVoiceBuffersExact",unchanged,"matrix",matrix)))+"\n");
    System.out.println("PASS: "+clips.size()+" clips, "+deterministic+" deterministic repeats, "+mechanicsEqual+" exact mechanical pairs, "+unchanged+" other voices exact, "+matrix.size()+" matrix cases.");
  }
}
