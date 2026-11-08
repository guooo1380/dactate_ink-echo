package com.inkecho.dictation;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Locale;

/** Private, persistent pronunciation files. No Android or installed voice engine required. */
public final class AudioCache {
    private static final int MAX_BYTES=2*1024*1024;
    private final File directory;
    private final Transport transport;

    public interface Transport { Response open(String url,Cancellation cancellation) throws IOException; }
    public static final class Response implements Closeable {
        final InputStream input;
        final String type;
        final long length;
        public Response(InputStream input,String type,long length) {this.input=input;this.type=type;this.length=length;}
        @Override public void close() throws IOException {input.close();}
    }
    public static final class Cancellation {
        private volatile boolean cancelled;
        private HttpURLConnection connection;
        public void check() throws InterruptedIOException {if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Audio request cancelled");}
        public void cancel() {HttpURLConnection active;synchronized(this){cancelled=true;active=connection;connection=null;}if(active!=null)active.disconnect();}
        private synchronized void attach(HttpURLConnection value) throws InterruptedIOException {check();connection=value;}
        private synchronized void detach(HttpURLConnection value) {if(connection==value)connection=null;}
    }
    public AudioCache(File directory) {this(directory,AudioCache::download);}
    public AudioCache(File directory,Transport transport) {this.directory=directory;this.transport=transport;}

    public static String url(String word,String accent) throws IOException {
        return "https://dict.youdao.com/dictvoice?audio="+URLEncoder.encode(word,"UTF-8")+"&type="+(accent.equals("GB")?"1":"2");
    }
    private File file(String word,String accent) throws IOException {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(("youdao-mp3-v1\n"+accent+"\n"+word).getBytes("UTF-8"));
            StringBuilder key=new StringBuilder();for(byte b:digest)key.append(String.format(Locale.US,"%02x",b&255));
            return new File(directory,key+".mp3");
        }catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
    }
    /** Serializes writes so concurrent requests reuse the same complete cache file. */
    public synchronized File get(String word,String accent,Cancellation cancellation) throws IOException {
        cancellation.check();
        if(word==null||word.trim().isEmpty()||!("US".equals(accent)||"GB".equals(accent)))throw new IOException("Invalid pronunciation request");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create audio directory");
        File target=file(word,accent);
        if(valid(target))return target;
        if(target.exists()&&!target.delete())throw new IOException("Cannot replace damaged audio");
        IOException failure=null;
        for(int attempt=0;attempt<2;attempt++) {
            cancellation.check();File temporary=File.createTempFile("download-",".part",directory);
            try {
                try(Response response=transport.open(url(word,accent),cancellation)) {
                    String type=response.type==null?"":response.type.toLowerCase(Locale.ROOT).split(";")[0].trim();
                    if(!(type.equals("audio/mpeg")||type.equals("audio/mp3")||type.equals("application/octet-stream")))throw new IOException("Not MP3 audio");
                    if(response.length>MAX_BYTES||response.length==0)throw new IOException("Invalid audio size");
                    long size=0;byte[] buffer=new byte[8192];
                    try(OutputStream out=new FileOutputStream(temporary)) {
                        int count;while((count=response.input.read(buffer))!=-1){cancellation.check();size+=count;if(size>MAX_BYTES)throw new IOException("Audio too large");out.write(buffer,0,count);}
                    }
                    if(response.length>=0&&size!=response.length)throw new IOException("Incomplete audio");
                }
                cancellation.check();if(!valid(temporary))throw new IOException("Invalid MP3 frames");
                cancellation.check();if(!temporary.renameTo(target))throw new IOException("Cannot save audio");
                return target;
            }catch(IOException e){failure=e;cancellation.check();}
            finally {if(temporary.exists())temporary.delete();}
        }
        throw failure;
    }
    public synchronized void discard(String word,String accent) {
        try {File target=file(word,accent);if(target.exists())target.delete();}catch(IOException ignored){}
    }
    /** Check complete MPEG Layer III frames, including optional ID3 metadata. */
    private static boolean valid(File file) {
        if(!file.isFile()||file.length()<100||file.length()>MAX_BYTES)return false;
        try(RandomAccessFile in=new RandomAccessFile(file,"r")) {
            long offset=0,length=in.length();int frames=0;
            if(in.readUnsignedByte()=='I'&&in.readUnsignedByte()=='D'&&in.readUnsignedByte()=='3') {
                in.seek(5);int flags=in.readUnsignedByte();int size=0;
                for(int i=0;i<4;i++){int b=in.readUnsignedByte();if((b&128)!=0)return false;size=(size<<7)|b;}
                offset=10L+size+((flags&16)!=0?10:0);
            }
            while(offset+4<=length) {
                // ID3v1 metadata may follow the last complete frame.
                in.seek(offset);int header=in.readInt();
                if(length-offset==128&&(header>>>8)==0x544147)return frames>=2;
                int version=(header>>>19)&3,layer=(header>>>17)&3,bitrate=(header>>>12)&15,sampling=(header>>>10)&3;
                if((header&0xffe00000)!=0xffe00000||version==1||layer!=1||bitrate==0||bitrate==15||sampling==3)return false;
                int[] v1={0,32,40,48,56,64,80,96,112,128,160,192,224,256,320};
                int[] v2={0,8,16,24,32,40,48,56,64,80,96,112,128,144,160};
                int sampleRate=new int[]{44100,48000,32000}[sampling]/(version==3?1:version==2?2:4);
                int size=(version==3?144000:72000)*(version==3?v1[bitrate]:v2[bitrate])/sampleRate+((header>>>9)&1);
                if(size<4||offset+size>length)return false;
                offset+=size;frames++;
            }
            return frames>=2&&offset==length;
        }catch(IOException e){return false;}
    }
    private static Response download(String address,Cancellation cancellation) throws IOException {
        final HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();
        connection.setConnectTimeout(8000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent","InkAndEcho/1.0.1 (Android pronunciation)");
        cancellation.attach(connection);
        try {
            if(connection.getResponseCode()!=200)throw new IOException("Pronunciation service unavailable");
            InputStream input=connection.getInputStream();
            return new Response(new FilterInputStream(input){@Override public void close() throws IOException {try{super.close();}finally{cancellation.detach(connection);connection.disconnect();}}},connection.getContentType(),connection.getContentLengthLong());
        }catch(IOException e){cancellation.detach(connection);connection.disconnect();throw e;}
    }
}
