import com.inkecho.dictation.AudioCache;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Cache behavior and invalid network responses, with no public service dependency. */
public class AudioCacheTests {
    static int checks;
    static void yes(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    interface Action {void run() throws Exception;}
    static void fails(Action action,String message) throws Exception {try{action.run();}catch(IOException e){checks++;return;}throw new AssertionError(message);}
    static byte[] mp3(){byte[] bytes=new byte[417*3];for(int i=0;i<3;i++){int offset=i*417;bytes[offset]=(byte)255;bytes[offset+1]=(byte)251;bytes[offset+2]=(byte)144;}return bytes;}
    static class Source implements AudioCache.Transport {
        byte[] bytes=mp3();String type="audio/mpeg",url;long length=-1;int calls,failures;
        @Override public AudioCache.Response open(String address,AudioCache.Cancellation cancellation)throws IOException {
            calls++;url=address;if(failures-->0)throw new IOException("Offline");
            return new AudioCache.Response(new ByteArrayInputStream(bytes),type,length);
        }
    }
    static File directory(String name)throws IOException{return Files.createTempDirectory(Paths.get("build"),"audio-"+name+"-").toFile();}
    static AudioCache.Cancellation token(){return new AudioCache.Cancellation();}
    static void noPartial(File directory){yes(Arrays.stream(directory.listFiles()).noneMatch(f->f.getName().endsWith(".part")),"no partial file survives failure or cancellation");}
    public static void main(String[] args)throws Exception {
        Source source=new Source();File folder=directory("reuse");AudioCache cache=new AudioCache(folder,source);
        File us=cache.get("apple","US",token());yes(us.isFile(),"complete audio is published");yes(source.calls==1,"first pronunciation downloads once");
        yes(source.url.endsWith("audio=apple&type=2"),"US source");
        source.failures=10;yes(cache.get("apple","US",token()).equals(us),"cached word plays offline");yes(source.calls==1,"cache hit never contacts provider");
        yes(new AudioCache(folder,source).get("apple","US",token()).equals(us),"cache persists across instance recreation");source.failures=0;
        yes(!cache.get("apple","GB",token()).equals(us),"accents have separate files");yes(source.url.endsWith("type=1"),"GB source");
        yes(!cache.get("Apple","US",token()).equals(us),"case preserved in cache keys");
        cache.get("look forward to?&é","US",token());yes(source.url.contains("look+forward+to%3F%26%C3%A9"),"phrases and special characters safely encoded");
        Files.write(us.toPath(),"<html>bad cache</html>".getBytes("UTF-8"));int previous=source.calls;cache.get("apple","US",token());yes(source.calls==previous+1,"damaged cached file redownloaded");
        cache.discard("apple","US");yes(!us.exists(),"decoder error can discard cached audio");
        Source retry=new Source();retry.failures=1;new AudioCache(directory("retry"),retry).get("word","US",token());yes(retry.calls==2,"transient error retries once");
        Source offline=new Source();offline.failures=10;File failed=directory("offline");fails(()->new AudioCache(failed,offline).get("word","US",token()),"offline must fail");yes(offline.calls==2,"permanent failure has bounded retries");yes(failed.list().length==0,"failed download creates no cache entry");
        for(int scenario=0;scenario<6;scenario++) {
            Source bad=new Source();
            if(scenario==0){bad.bytes="<html>not audio</html>".getBytes("UTF-8");bad.type="text/html";}
            if(scenario==1){bad.bytes=new byte[1024];}
            if(scenario==2){bad.length=bad.bytes.length+1;}
            if(scenario==3){bad.bytes=Arrays.copyOf(bad.bytes,600);}
            if(scenario==4){bad.bytes=new byte[2*1024*1024+1];}
            if(scenario==5){bad.length=2*1024*1024+1;}
            File dir=directory("invalid");AudioCache invalid=new AudioCache(dir,bad);fails(()->invalid.get("word","US",token()),"reject invalid audio "+scenario);yes(dir.list().length==0,"invalid response never cached "+scenario);noPartial(dir);
        }
        Source cancelledSource=new Source();AudioCache.Cancellation cancelled=token();cancelled.cancel();
        fails(()->new AudioCache(directory("cancelled"),cancelledSource).get("word","US",cancelled),"cancelled before start");yes(cancelledSource.calls==0,"cancelled job makes no request");
        File during=directory("during");final int[] requests={0};
        AudioCache stopping=new AudioCache(during,(address,cancellation)->{requests[0]++;return new AudioCache.Response(new ByteArrayInputStream(mp3()){
            @Override public synchronized int read(byte[] b,int off,int len){int read=super.read(b,off,len);cancellation.cancel();return read;}
        },"audio/mpeg",-1);});
        fails(()->stopping.get("word","US",token()),"cancel during download");yes(requests[0]==1,"cancelled download is never retried");yes(during.list().length==0,"cancelled download never published");noPartial(during);
        File interrupted=directory("interrupted");AudioCache broken=new AudioCache(interrupted,(address,cancellation)->new AudioCache.Response(new InputStream(){public int read()throws IOException{throw new IOException("Connection lost");}},"audio/mpeg",-1));
        fails(()->broken.get("word","US",token()),"interrupted stream fails");yes(interrupted.list().length==0,"interrupted stream leaves no cache entry");
        Source concurrentSource=new Source();AudioCache concurrent=new AudioCache(directory("concurrent"),concurrentSource);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<File> one=pool.submit(()->{start.await();return concurrent.get("same","US",token());});
            Future<File> two=pool.submit(()->{start.await();return concurrent.get("same","US",token());});start.countDown();
            yes(one.get(5,TimeUnit.SECONDS).equals(two.get(5,TimeUnit.SECONDS)),"concurrent callers reuse one file");yes(concurrentSource.calls==1,"duplicate downloads serialized");
        }finally{pool.shutdownNow();}
        fails(()->cache.get("","US",token()),"empty words rejected");fails(()->cache.get("apple","ZZ",token()),"invalid accent rejected");
        // Optional downloaded provider fixtures also pass production MP3 validation.
        for(String arg:args){Source real=new Source();real.bytes=Files.readAllBytes(Paths.get(arg));real.length=real.bytes.length;File result=new AudioCache(directory("real"),real).get("fixture","US",token());yes(result.length()==real.bytes.length,"real provider MP3 accepted: "+arg);}
        System.out.println("PASS: "+checks+" audio assertions");
    }
}
