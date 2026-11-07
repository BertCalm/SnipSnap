import com.snipsnap.synth.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Six dry mono comparisons. All audition conditioning is explicit; raw source files are untouched. */
public final class TwoSingerAudition {
 static final int RATE=44100, FRAMES=132300;
 static final double START_A=.20, START_B=.245;
 static final String[] IDS={"a-alone","b-alone","a-plus-a","a-plus-b","synthetic-b-alone","a-plus-synthetic-b"};
 static final double[] DOUBLE_RATIOS={1.0066572427749634,1.0034866333007812};
 static final double DOUBLE_RELATIVE_ONSET=-.0057596371882086;
 static Path out;
 static float[] a,b;
 static ConfluenceVoice.Analysis analysisA;
 static double nativeB;

 public static void main(String[] args)throws Exception {
  out=Path.of(args[0]);Files.createDirectories(out.resolve("audio"));Files.createDirectories(out.resolve("work"));
  float[] rawA=readWav(Path.of(args[1])),rawB=readWav(Path.of(args[2]));
  if(rawA.length!=88200 || rawB.length!=88200)throw new IllegalArgumentException("Expected the two qualified 2second source bodies");
  a=center(rawA);b=center(rawB);double hzA=Double.parseDouble(args[3]);nativeB=Double.parseDouble(args[4]);
  double midiA=69+12*StrictMath.log(hzA/440)/StrictMath.log(2);
  long before=System.nanoTime();analysisA=ConfluenceVoice.INSTANCE.analyze(a,(float)midiA);double analysisMs=(System.nanoTime()-before)/1e6;
  long renderStart=System.nanoTime();Map<String,float[]> first=render();double renderMs=(System.nanoTime()-renderStart)/1e6;
  Map<String,float[]> second=render();boolean identical=true;for(String id:IDS)identical &= Arrays.equals(first.get(id),second.get(id));
  if(!identical)throw new AssertionError("Float PCM nondeterminism");
  double target=.10;for(float[] x:first.values())target=Math.min(target,.85*rms(x)/peak(x));
  List<Object> clips=new ArrayList<>();
  for(String id:IDS){float[] x=first.get(id),matched=new float[x.length];float gain=(float)(target/rms(x));for(int i=0;i<x.length;i++)matched[i]=x[i]*gain;
   byte[] wav=wav(matched);Files.write(out.resolve("audio/"+id+".wav"),wav);writeFloat(out.resolve("work/"+id+".f32"),matched);
   Map<String,Object> row=new LinkedHashMap<>();row.put("id",id);row.put("path","audio/"+id+".wav");row.put("sha256",sha(wav));row.put("frames",matched.length);row.put("seconds",3.0);row.put("matchingGain",gain);row.put("preMatchRms",rms(x));row.put("preMatchPeak",peak(x));row.put("postMatchRms",rms(matched));row.put("postMatchPeak",peak(matched));clips.add(row);
  }
  float[] hz=new float[analysisA.getPeriods().length];for(int i=0;i<hz.length;i++)hz[i]=RATE/analysisA.getPeriods()[i];
  writeFloat(out.resolve("work/a-analysis-hz.f32"),hz);writeFloat(out.resolve("work/a-analysis-voiced.f32"),analysisA.getVoiced());
  List<Object> targetCurve=new ArrayList<>();for(int frame=0;frame<(int)(a.length*.992);frame+=441)targetCurve.add(List.of(frame/(double)RATE,IndependentSinger.INSTANCE.targetHz(frame/(double)RATE,nativeB)));
  Map<String,Object> report=new LinkedHashMap<>();report.put("format","snipsnap-two-singer-render/v1");report.put("actualRenderer","Unmodified production ConfluenceVoice.analyze and TEXTURE render; independent-clock PSOLA scratch adapter for GeneratedfromA");report.put("sampleRate",RATE);report.put("channels",1);report.put("commonFrames",FRAMES);report.put("targetWindowRms",target);report.put("deterministicFloatPcm",identical);report.put("analysisMilliseconds",analysisMs);report.put("renderAllSixMilliseconds",renderMs);report.put("sourceMeanRemovedA",mean(rawA));report.put("sourceMeanRemovedB",mean(rawB));report.put("sourceReferenceHzA",hzA);report.put("sourceReferenceHzB",nativeB);report.put("analysisAVoicedShare",analysisA.getVoicedShare());report.put("sourceEpochCount",analysisA.getEpochs().length);report.put("syntheticVersion",1);report.put("syntheticFormantScale",1.08);report.put("syntheticDurationRatio",.992);report.put("targetF0Curve",targetCurve);report.put("clips",clips);
  Files.writeString(out.resolve("render.json"),json(report)+"\n");System.out.println(json(report));
 }

 static Map<String,float[]> render()throws Exception {
  Map<String,float[]> stems=new LinkedHashMap<>();
  stems.put("a",stage(a,START_A,.060,.140));stems.put("b",stage(b,START_B,.090,.180));
  float[] d0=ConfluenceVoice.INSTANCE.render(analysisA,ConfluenceSourceMode.TEXTURE,0f,(float)DOUBLE_RATIOS[0],1f,0f,0f,0f);
  float[] d1=ConfluenceVoice.INSTANCE.render(analysisA,ConfluenceSourceMode.TEXTURE,0f,(float)DOUBLE_RATIOS[1],1f,0f,0f,0f);
  stems.put("double-0",stage(d0,START_A,.060,.140));stems.put("double-1",stage(d1,START_A+DOUBLE_RELATIVE_ONSET,.060,.140));
  float[] synthetic=IndependentSinger.INSTANCE.render(analysisA,nativeB);stems.put("synthetic-b",stage(synthetic,START_B,.090,.180));
  stems.put("synthetic-formant-control",stage(IndependentSinger.INSTANCE.render(analysisA,nativeB,1f),START_B,.090,.180));
  // Equal component energy prevents a quiet real B from being mistaken for absence of a second part.
  for(var e:stems.entrySet()){float[] x=e.getValue();float gain=(float)(.1/rms(x));for(int i=0;i<x.length;i++)x[i]*=gain;writeFloat(out.resolve("work/stem-"+e.getKey()+".f32"),x);}
  Map<String,float[]> clips=new LinkedHashMap<>();clips.put(IDS[0],stems.get("a"));clips.put(IDS[1],stems.get("b"));clips.put(IDS[2],mix(stems.get("double-0"),stems.get("double-1")));clips.put(IDS[3],mix(stems.get("a"),stems.get("b")));clips.put(IDS[4],stems.get("synthetic-b"));clips.put(IDS[5],mix(stems.get("a"),stems.get("synthetic-b")));return clips;
 }
 static float[] center(float[] x){double mean=mean(x);float[] y=new float[x.length];for(int i=0;i<x.length;i++)y[i]=(float)(x[i]-mean);return y;}
 static float[] stage(float[] body,double start,double attack,double release){float[] x=new float[FRAMES];double bodyMean=mean(body);int at=(int)Math.round(start*RATE),a=(int)Math.round(attack*RATE),r=(int)Math.round(release*RATE);if(at+body.length>FRAMES)throw new AssertionError("croppedbody");for(int i=0;i<body.length;i++){double envelope=1;if(i<a)envelope*=.5-.5*StrictMath.cos(Math.PI*i/a);if(i>=body.length-r)envelope*=.5-.5*StrictMath.cos(Math.PI*(body.length-1-i)/r);x[at+i]=(float)((body[i]-bodyMean)*envelope);}return x;}
 static float[] mix(float[] x,float[] y){float[] z=new float[x.length];for(int i=0;i<z.length;i++)z[i]=(x[i]+y[i])*(float)(1/StrictMath.sqrt(2));return z;}
 static double mean(float[] x){double s=0;for(float f:x)s+=f;return s/x.length;}
 static double rms(float[] x){double s=0;for(float f:x)s+=(double)f*f;return StrictMath.sqrt(s/x.length);}
 static double peak(float[] x){double p=0;for(float f:x)p=Math.max(p,Math.abs(f));return p;}
 static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
 static void writeFloat(Path path,float[] x)throws Exception {ByteBuffer b=ByteBuffer.allocate(x.length*4).order(ByteOrder.LITTLE_ENDIAN);for(float f:x)b.putFloat(f);Files.write(path,b.array());}
 static float[] readWav(Path path)throws Exception {byte[] bytes=Files.readAllBytes(path);ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);int p=12,at=-1,length=0,bits=0;while(p+8<=bytes.length){String tag=new String(bytes,p,4,java.nio.charset.StandardCharsets.US_ASCII);int n=b.getInt(p+4);if(n<0 || (long)p+8+n>bytes.length)throw new IOException("InvalidWAVchunk");if(tag.equals("fmt ")){if(b.getShort(p+8)!=1 || b.getShort(p+10)!=1 || b.getInt(p+12)!=RATE)throw new IOException("Expectedmono44100PCM");bits=b.getShort(p+22);}if(tag.equals("data")){at=p+8;length=n;}p+=8+n+(n&1);}if(at<0 || bits!=24)throw new IOException("ExpectedPCM24");float[] x=new float[length/3];for(int i=0;i<x.length;i++){int q=at+3*i;int v=(bytes[q]&255)|((bytes[q+1]&255)<<8)|(bytes[q+2]<<16);x[i]=Math.max(-1f,v/8388607f);}return x;}
 static byte[] wav(float[] x){int data=x.length*3,pad=data&1;ByteBuffer b=ByteBuffer.allocate(44+data+pad).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes()).putInt(36+data+pad).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(RATE).putInt(RATE*3).putShort((short)3).putShort((short)24).put("data".getBytes()).putInt(data);for(float sample:x){int v=Math.round(Math.max(-1f,Math.min(1f,sample))*8388607f);b.put((byte)v).put((byte)(v>>8)).put((byte)(v>>16));}return b.array();}
 static String json(Object x){if(x==null)return "null";if(x instanceof String)return "\""+((String)x).replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\"";if(x instanceof Number || x instanceof Boolean)return x.toString();if(x instanceof Map){List<String> s=new ArrayList<>();for(var e:((Map<?,?>)x).entrySet())s.add(json(e.getKey().toString())+":"+json(e.getValue()));return "{"+String.join(",",s)+"}";}if(x instanceof Iterable){List<String>s=new ArrayList<>();for(Object y:(Iterable<?>)x)s.add(json(y));return "["+String.join(",",s)+"]";}throw new IllegalArgumentException("UnsupportedJSON");}
}
