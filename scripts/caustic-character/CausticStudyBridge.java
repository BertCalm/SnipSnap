import com.snipsnap.synth.*;
import com.snipsnap.audio.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Small study adapter over the compiled Kotlin APIs. No production voice is changed. */
public final class CausticStudyBridge {
  static final int RATE=44100, INTERNAL=176400;
  static final float VELOCITY=.75f;
  static void floats(Path path,float[] data)throws Exception {
    ByteBuffer b=ByteBuffer.allocate(data.length*4).order(ByteOrder.LITTLE_ENDIAN);
    b.asFloatBuffer().put(data); Files.write(path,b.array());
  }
  static float[] floats(Path path)throws Exception {
    byte[] bytes=Files.readAllBytes(path);
    if(bytes.length%4!=0)throw new IllegalArgumentException("Invalid float buffer");
    float[] a=new float[bytes.length/4]; ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(a);return a;
  }
  static Map<String,Float> macros(float source,float body) {
    Map<String,Float> m=new LinkedHashMap<>();
    m.put("SOURCE",source);m.put("FOCUS",.19f);m.put("BODY",body);m.put("LENGTH",1f);m.put("HOLD",0f);return m;
  }
  static void export(Path root)throws Exception {
    Files.createDirectories(root);
    for(float focus:new float[]{.19f,.38f,.85f}) {
      CausticGeometry.Prepared g=CausticGeometry.INSTANCE.prepare(.5,(double)focus,256,.05);
      StringBuilder j=new StringBuilder("{\"source\":0.5,\"focus\":"+focus+",\"rays\":256,\"aperture\":0.05,\"densities\":[");
      for(int i=0;i<g.getDensities().length;i++){if(i>0)j.append(',');j.append(g.getDensities()[i]);}
      j.append("],\"paths\":[");int k=0;
      for(CausticGeometry.Path p:g.getPaths()) {
        if(k++>0)j.append(',');
        j.append("{\"pickup\":").append(p.getPickup()).append(",\"delayR\":").append(p.getDelayR())
          .append(",\"weight\":").append(p.getWeight()).append(",\"boundaryTheta\":").append(p.getBoundaryTheta()).append('}');
      }
      Files.writeString(root.resolve(String.format(Locale.ROOT,"geometry-%.2f.json",focus)),j.append("]}\n").toString());
    }
    for(int midi:new int[]{36,60,84}) {
      for(float source:new float[]{0f,.25f,.5f,.6f,1f}) {
        CausticInstrumentProbe p=CausticInstrument.INSTANCE.probe(CausticVoice.ALLOY,macros(source,0f),midi,VELOCITY,false,1,null,4);
        floats(root.resolve(String.format(Locale.ROOT,"native-m%d-s%.2f-b0.internal.f32",midi,source)),p.getInternalRaw());
      }
      CausticInstrumentProbe p=CausticInstrument.INSTANCE.probe(CausticVoice.ALLOY,macros(.5f,1f),midi,VELOCITY,false,1,null,4);
      floats(root.resolve("native-m"+midi+"-b1.internal.f32"),p.getInternalRaw());
    }
    System.out.println("Exported native geometry and 18 raw response buffers");
  }
  static float peak(float[] a){float p=0;for(float v:a){if(!Float.isFinite(v))throw new IllegalStateException("Nonfinite audio");p=Math.max(p,Math.abs(v));}return p;}
  static void finish(Path input,Path prefix)throws Exception {
    float[] internal=floats(input);float rawPeak=peak(internal);
    Tide.INSTANCE.bandLimit$synth(internal,INTERNAL);
    float[] out=Dsp.INSTANCE.decimate(internal,RATE,1);
    double sum=0;for(float v:out)sum+=v;float mean=(float)(sum/Math.max(1,out.length));
    Dsp.OnePole hp=new Dsp.OnePole(RATE);
    for(int i=0;i<out.length;i++){float v=out[i]-mean;out[i]=v-hp.lp(v,20f);}
    Dsp.INSTANCE.fadeTail(out,4f,RATE,1);
    Dsp.INSTANCE.levelTo(out,RATE,Dsp.MELODIC_LOUDNESS_TARGET*(float)Math.pow(VELOCITY,.4f),.99f,1);
    Snip snip=new Snip(out,1,RATE);
    Files.createDirectories(prefix.getParent());
    WavWriter.INSTANCE.write(prefix.resolveSibling(prefix.getFileName()+"-native.wav").toFile(),snip,WavWriter.BitDepth.PCM_24,true,null,null);
    float[] matched=out.clone();
    float gain=.03f/Math.max(1e-9f,Loudness.INSTANCE.of(snip));
    if(peak(matched)*gain>.99f)gain=.99f/peak(matched);
    for(int i=0;i<matched.length;i++)matched[i]*=gain;
    WavWriter.INSTANCE.write(prefix.resolveSibling(prefix.getFileName()+"-matched.wav").toFile(),new Snip(matched,1,RATE),WavWriter.BitDepth.PCM_24,true,null,null);
    floats(prefix.resolveSibling(prefix.getFileName()+".finished.f32"),out);
    Files.writeString(prefix.resolveSibling(prefix.getFileName()+".finish.json"),"{\"rawPeak\":"+rawPeak+",\"peak\":"+peak(out)+",\"matchedGain\":"+gain+",\"frames\":"+out.length+"}\n");
  }
  static void validate(Path wav,int midi)throws Exception {
    Snip s=WavReader.INSTANCE.read(wav.toFile());
    PitchEstimate p=Pitch.INSTANCE.detect(s,0f,Math.min(2f,s.getDurationSeconds()));
    Classification c=Classifier.INSTANCE.classify(s);
    double hz=440*Math.pow(2,(midi-69)/12.0);
    double cents=p==null?Double.NaN:1200*Math.log(p.getHz()/hz)/Math.log(2);
    double confidence=p==null?0:p.getConfidence();
    System.out.println("{\"file\":\""+wav.getFileName()+"\",\"midi\":"+midi+",\"peak\":"+peak(s.getSamples())+",\"pitchCents\":"+(Double.isFinite(cents)?cents:"null")+",\"pitchConfidence\":"+confidence+",\"drumClass\":\""+c.getDrumClass()+"\"}");
    if(!Double.isFinite(cents)||Math.abs(cents)>20||confidence<.3||peak(s.getSamples())>=1)throw new IllegalStateException("Study pitch or headroom gate failed");
  }
  public static void main(String[] args)throws Exception {
    switch(args[0]) {
      case "export":export(Path.of(args[1]));break;
      case "finish":finish(Path.of(args[1]),Path.of(args[2]));break;
      case "finishdir":
        try(var paths=Files.list(Path.of(args[1]))) {
          for(Path path:paths.filter(p->p.toString().endsWith(".internal.f32")).sorted().toList()) {
            String id=path.getFileName().toString().replace(".internal.f32","");
            Path prefix=Path.of(args[2]).resolve(id);finish(path,prefix);
            int midi=id.endsWith("-c2")?36:id.endsWith("-c4")?60:84;
            validate(prefix.resolveSibling(id+"-native.wav"),midi);
          }
        }break;
      case "validate":validate(Path.of(args[1]),Integer.parseInt(args[2]));break;
      default:throw new IllegalArgumentException("export, finish, or validate required");
    }
  }
}
