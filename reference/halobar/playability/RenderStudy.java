import com.snipsnap.synth.*;import com.snipsnap.audio.*;import com.snipsnap.shell.*;import com.snipsnap.kit.*;
import java.io.*;import java.nio.file.*;import java.util.*;
public class RenderStudy {
 public static void main(String[]args)throws Exception {
  File root=new File(args[0]);root.mkdirs();File source=new File(root,"source");
  var starter=Objects.requireNonNull(StarterKits.INSTANCE.byId("halobar-six"));
  var kit=starter.render("HALOBAR SIX STUDY",source,0);
  if(!kit.equals(KitStore.INSTANCE.load(source))||kit.getPads().size()!=16)throw new AssertionError("Study kit reload");
  for(var format:List.of(ExportFormat.PROGRAM_FOLDER,ExportFormat.MPC3_TRACK)){
   var result=Exporters.INSTANCE.export(format,kit,source,new File(root,format.getId()),true,
     Exporters.INSTANCE.defaultMeta(kit),false,null,null,null,null);
   for(var finding:ExportReadBack.INSTANCE.verify(kit,result))if(finding.getSeverity()==Severity.FAIL)throw new AssertionError(finding);
  }
  var notes=new ArrayList<Snip>();var hits=new ArrayList<PadHit.Hit>();
  var rows=new ArrayList<String>();File clips=new File(root,"clips");clips.mkdirs();
  for(var pad:kit.getPads()) {
   var snip=WavReader.INSTANCE.read(new File(source,pad.getSampleFile()));
   var hit=Objects.requireNonNull(PadHit.INSTANCE.resolve(pad,1f,0,file->(long)WavReader.INSTANCE.read(new File(source,file)).getFrameCount()));
   if(snip.getChannels()!=1||hit.getPitchRatio()!=1.0||hit.getLoopStartFrame()!=-1)throw new AssertionError("Unexpected study playback shape");
   String filename=String.format(Locale.ROOT,"pad-%02d.wav",pad.getSlot());
   WavWriter.INSTANCE.write(new File(clips,filename),snip,WavWriter.BitDepth.PCM_16,false,null,null);
   rows.add(pad.getSlot()+"\t"+pad.getDisplayName()+"\t"+filename+"\t"+snip.getFrameCount()+"\t"+hit.getGainLeft()+"\t"+hit.getGainRight());
   notes.add(snip);hits.add(hit);
  }
  int rate=44100,step=(int)Math.round(rate*60.0/72),frames=0;
  for(int n=0;n<notes.size();n++)frames=Math.max(frames,n*step+notes.get(n).getFrameCount());
  float[] mix=new float[frames*2];
  for(int n=0;n<notes.size();n++){
   var h=hits.get(n);float[] a=notes.get(n).getSamples();
   for(int i=(int)h.getStartFrame();i<h.getEndFrameExclusive();i++){
    int o=(n*step+i-(int)h.getStartFrame())*2;mix[o]+=a[i]*h.getGainLeft();mix[o+1]+=a[i]*h.getGainRight();
   }
  }
  float peak=0;for(float v:mix)peak=Math.max(peak,Math.abs(v));float gain=peak>.9f?.9f/peak:1f;
  for(int i=0;i<mix.length;i++)mix[i]*=gain;
  WavWriter.INSTANCE.write(new File(clips,"six-study-sequence.wav"),new Snip(mix,2,rate),WavWriter.BitDepth.PCM_16,false,null,null);
  Files.write(new File(root,"pads.tsv").toPath(),rows);
  Files.writeString(new File(root,"sequence.txt").toPath(),"bpm=72\nstepFrames="+step+"\nmasterGain="+gain+"\nsource=Saved PCM24 kit and shared PadHit selection/gains\n");
  System.out.println("Saved/reloaded sixteen-pad study; MPC 2 and MPC 3 read-back passed; all clips written.");
 }
}
