import com.inkecho.dictation.*;
import java.util.*;
import java.io.*;
import java.nio.charset.*;
import java.util.zip.*;

/** Acceptance-oriented, no external test dependencies. */
public class CoreTests {
    static int checks=0;
    static void eq(Object actual,Object expected,String message){checks++;if(!Objects.equals(actual,expected))throw new AssertionError(message+": expected="+expected+" actual="+actual);}
    static void yes(boolean value,String message){eq(value,true,message);}
    static Engine.Word word(long id,int wrong,int streak){Engine.Word w=new Engine.Word(id,"word"+id,(int)id);w.stat.wrong=wrong;w.stat.streak=streak;return w;}
    static List<Long> ids(List<Engine.Question> questions){List<Long> ids=new ArrayList<>();for(Engine.Question q:questions)ids.add(q.word.id);return ids;}
    public static void main(String[] args)throws Exception {
        long now=System.currentTimeMillis();
        List<Engine.Word> words=Arrays.asList(word(1,0,0),word(2,1,0),word(3,3,0),word(4,0,0),word(5,0,0));
        words.get(3).stat.due=Engine.addDays(now,-1);
        List<Engine.Question> plan=Engine.plan(words,false,true,true,.5,42,now);
        eq(ids(plan),Arrays.asList(3L,2L,4L,1L,5L),"wrong then due then normal");
        eq(plan.get(0).zone,"priority","priority snapshot");eq(plan.get(2).zone,"review","review snapshot");
        eq(ids(Engine.plan(words,false,false,true,.5,42,now)),Arrays.asList(1L,2L,3L,4L,5L),"disabled bias restores original order");
        eq(ids(Engine.plan(words,false,true,false,.5,42,now)),Arrays.asList(3L,2L,1L,4L,5L),"disable due review");
        eq(Engine.plan(words,true,true,true,.5,42,now).size(),words.size(),"bias preserves count");
        eq(new HashSet<>(ids(plan)).size(),words.size(),"every word exactly once");
        eq(ids(Engine.plan(words,true,true,true,.5,42,now)),ids(Engine.plan(words,true,true,true,.5,42,now)),"seeded order reproducible");
        List<Engine.Word> many=new ArrayList<>();for(int i=1;i<=80;i++)many.add(word(i,0,0));
        List<Long> shuffle1=ids(Engine.plan(many,true,false,true,.5,42,now)),shuffle2=ids(Engine.plan(many,true,false,true,.5,43,now));yes(!shuffle1.equals(shuffle2),"seeds give different shuffles");eq(new HashSet<>(shuffle1).size(),80,"random full coverage");
        for(Engine.Question q:Engine.plan(many,true,true,true,.5,42,now))eq(q.zone,"normal","first session no priorities");
        Engine.Stat decayed=Engine.replay(Arrays.asList(new Engine.Outcome(now,true,false),new Engine.Outcome(now+1,true,false),new Engine.Outcome(now+2,true,false),new Engine.Outcome(now+3,false,false),new Engine.Outcome(now+4,false,false)));
        eq(decayed.wrong,3,"historical count retained");eq(decayed.streak,2,"correct streak");eq(Engine.score(decayed,.5),.75,"two correct answers decay 3 errors");
        List<Engine.Outcome> h=new ArrayList<>();h.add(new Engine.Outcome(now,true,false));h.add(new Engine.Outcome(now+1000,false,false));h.add(new Engine.Outcome(now+2000,true,false));Engine.Stat before=Engine.replay(h);h.set(1,new Engine.Outcome(now+1000,true,false));Engine.Stat changed=Engine.replay(h);eq(changed.wrong,3,"retroactive mark recomputes");h.set(1,new Engine.Outcome(now+1000,false,false));Engine.Stat restored=Engine.replay(h);eq(restored.wrong,before.wrong,"undo error count");eq(restored.streak,before.streak,"undo streak");eq(restored.due,before.due,"undo due");eq(restored.interval,before.interval,"undo interval");
        Engine.Stat skipped=Engine.replay(Arrays.asList(new Engine.Outcome(now,true,true)));eq(skipped.wrong,0,"skip no error");eq(skipped.lastAt,0L,"skip no practice");eq(skipped.due,0L,"skip no schedule");
        List<Engine.Outcome> correct=new ArrayList<>();for(int i=0;i<7;i++){correct.add(new Engine.Outcome(now+i,false,false));eq(Engine.replay(correct).interval,Engine.INTERVALS[Math.min(i,5)],"review interval "+i);}
        correct.add(new Engine.Outcome(now+10,true,false));eq(Engine.replay(correct).interval,1,"wrong resets review interval");eq(Engine.replay(correct).streak,0,"wrong resets streak");
        eq(Engine.accuracy(10,2,2),75.0,"skips excluded from denominator");yes(Double.isNaN(Engine.accuracy(2,0,2)),"all skipped has no accuracy");
        eq(WordImport.split("1. run\r\n2、 runs\n(3) Run\n④ look   forward to\n\n run\tapple,pear;banana、grape"),Arrays.asList("run","runs","Run","look forward to","run","apple","pear","banana","grape"),"list numbering and phrases");
        eq(WordImport.duplicates(Arrays.asList("run","runs","Run","run"," apple "),new HashSet<>(Arrays.asList("apple"))),Arrays.asList("new","new","new","duplicate","duplicate"),"strict case-sensitive deduplication");
        yes(!WordImport.text("apple".getBytes(StandardCharsets.UTF_8)).gbk,"UTF8 detection");eq(WordImport.text("苹果".getBytes("GBK")).text,"苹果","GBK fallback");yes(WordImport.text("苹果".getBytes("GBK")).gbk,"GBK notice");
        eq(WordImport.split("\uFEFF1. apple\n \n② banana"),Arrays.asList("apple","banana"),"BOM and blank cleanup");
        String xml="<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>1. app</w:t></w:r><w:r><w:t>le</w:t></w:r></w:p><w:p><w:r><w:t>look forward to</w:t></w:r></w:p></w:body></w:document>";
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry("word/document.xml"));zip.write(xml.getBytes(StandardCharsets.UTF_8));zip.closeEntry();zip.putNextEntry(new ZipEntry("word/header1.xml"));zip.write("ignore header".getBytes());zip.closeEntry();}
        eq(WordImport.split(WordImport.docx(new ByteArrayInputStream(out.toByteArray()))),Arrays.asList("apple","look forward to"),"DOCX run joining and header ignored");
        ByteArrayOutputStream unsafe=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(unsafe)){zip.putNextEntry(new ZipEntry("word/document.xml"));zip.write("<!DOCTYPE x [<!ENTITY a 'bad'>]><x/>".getBytes());zip.closeEntry();}boolean rejected=false;try{WordImport.docx(new ByteArrayInputStream(unsafe.toByteArray()));}catch(IOException e){rejected=true;}yes(rejected,"reject XML entity declarations");
        eq(WordImport.split(" , ;\n"),Collections.emptyList(),"empty import");
        System.out.println("PASS: "+checks+" core assertions");
    }
}
