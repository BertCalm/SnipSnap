import com.snipsnap.audio.*;
import com.snipsnap.kit.*;
import com.snipsnap.xpm.*;
import com.snipsnap.mpc3.*;
import com.snipsnap.shell.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Diagnostic tones distinguish velocity gating from ordinary layer cycling on hardware. */
public class MpcVelocityCycleBench {
 static final int RATE=44100;
 static Snip tone(boolean hard,int count) {
  float[] out=new float[(int)(.9*RATE)];
  for(int pulse=0;pulse<count;pulse++)for(int i=0;i<(int)(.12*RATE);i++) {
   int index=(int)((.08+.26*pulse)*RATE)+i;
   double edge=Math.min(1.,Math.min(i, .12*RATE-1-i)/(.005*RATE));
   out[index]=(float)((hard?.24:.18)*edge*Math.sin(2*Math.PI*(hard?440:220)*index/RATE));
  }
  return new Snip(out,1,RATE);
 }
 static KeygroupProgram program(String name,List<VelocityLayer> layers,boolean cycle) {
  return new KeygroupProgram(name,List.of(new Keygroup(60,60,60,layers,null,cycle)),0f,.1f,true,VoiceMode.POLY,false);
 }
 static void make(Path root,String folder,String name,List<String> stems,List<VelocityLayer> layers,
                  boolean cycle,Map<String,Snip> all,boolean candidate)throws Exception {
  File dest=root.resolve(folder).toFile();dest.mkdirs();
  var selected=new LinkedHashMap<String,Snip>();for(String stem:stems)selected.put(stem,all.get(stem));
  var phone=program(name,layers,cycle);
  OneNote.INSTANCE.writePackage(name,phone,selected,dest,true,false);
  if(candidate) {
   var plain=program(name,layers,false);
   String xml=new KeygroupWriter().write(plain).replaceFirst(Pattern.quote("<TuneCoarse>0</TuneCoarse>"),
       "<ZonePlay>0</ZonePlay>\n        <TuneCoarse>0</TuneCoarse>");
   Files.writeString(new File(dest,Mpc3TrackWriter.Companion.trackDataDirName(name)).toPath().resolve(name+".xpm"),xml);
   String json=new Mpc3TrackWriter().keygroupPayloadText(plain,Mpc3TrackWriter.DEFAULT_TRACK_COLOUR);
   Files.writeString(dest.toPath().resolve("candidate-native-payload.json"),json);
  }
  var inst=InstrumentStore.INSTANCE.read(InstrumentStore.INSTANCE.sidecar(dest,name));
  var decoded=new LinkedHashMap<String,float[]>();
  for(String file:inst.getSampleFiles())decoded.put(file,WavReader.INSTANCE.read(new File(dest,file)).getSamples());
  for(String pattern:List.of("soft","hard","alternating")) {
   var player=new InstrumentEngine(new InstrumentEngine.Loaded(inst,RATE,decoded),RATE,8);
   int period=(int)(1.2*RATE),frames=period*12;float[] out=new float[frames*2];
   for(int hit=0;hit<12;hit++) {
    int vel=pattern.equals("hard")?110:pattern.equals("soft")?40:(hit%2==0?40:110);
    if(!player.noteOn(60,vel/127f))throw new AssertionError("missing bench root");
    float[] block=new float[period*2];player.render(block,period);player.noteOff(60);
    for(int i=0;i<block.length;i++)out[hit*period*2+i]=block[i]*4f;
   }
   WavWriter.INSTANCE.write(new File(dest,"Reference-"+pattern+".wav"),new Snip(out,2,RATE),
       WavWriter.BitDepth.PCM_16,false,null,null);
  }
 }
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]);Files.createDirectories(root);
  var samples=new LinkedHashMap<String,Snip>();
  for(boolean hard:new boolean[]{false,true})for(int take=1;take<=3;take++)samples.put((hard?"Hard":"Soft")+take,tone(hard,take));
  long frames=samples.get("Soft1").getFrameCount();
  make(root,"01-Velocity-Control","HALO Bench VEL",List.of("Soft1","Hard1"),List.of(
      new VelocityLayer("Soft1",frames,0,79,0),new VelocityLayer("Hard1",frames,80,127,0)),false,samples,false);
  var single=new ArrayList<VelocityLayer>();for(int t=1;t<=3;t++)single.add(new VelocityLayer("Hard"+t,frames,0,127,0));
  make(root,"02-Cycle-Control","HALO Bench RR",List.of("Hard1","Hard2","Hard3"),single,true,samples,false);
  var grid=new ArrayList<VelocityLayer>();
  for(boolean hard:new boolean[]{false,true})for(int take=1;take<=3;take++)grid.add(new VelocityLayer((hard?"Hard":"Soft")+take,frames,hard?80:0,hard?127:79,0));
  make(root,"03-Velocity-Cycle-Candidate","HALO Bench VEL RR",new ArrayList<>(samples.keySet()),grid,true,samples,true);
  System.out.println("Three diagnostic programs and nine reference performances written.");
 }
}
