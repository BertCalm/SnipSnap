import com.snipsnap.mpc3.*;
import com.snipsnap.kit.*;
import java.nio.file.*;
public class VerifyMpcBench {
 public static void main(String[] args)throws Exception {
  int containers=0,scores=0;
  try(var files=Files.walk(Path.of(args[0]))) {
   for(var path:files.filter(Files::isRegularFile).toList()) {
    if(path.toString().endsWith(".xty")) {var c=Acvs.INSTANCE.read(path.toFile());if(c.getPayload()==null)throw new AssertionError("unparsed native file");containers++;}
    if(path.toString().endsWith(".mid")) {
     var m=MidiGroove.INSTANCE.read(path.toFile(),path.getFileName().toString());
     if(m.getClip().getNotes().size()!=12 || m.getBpm()==null || Math.abs(m.getBpm()-72)>0.001)throw new AssertionError("invalid MIDI score");
     for(var note:m.getClip().getNotes())if(note.getNote()!=60)throw new AssertionError("wrong bench note");scores++;
    }
   }
  }
  if(containers!=3 || scores!=3)throw new AssertionError("missing bench assets");
  System.out.println("Production readers validated three MPC containers and three MIDI scores. Hardware playback remains unverified.");
 }
}
