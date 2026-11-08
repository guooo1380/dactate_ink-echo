package com.inkecho.dictation;

import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in, bounded network access. A failed lookup never writes an empty cache entry. */
public final class Translations {
    public interface Result { void done(Store.Row value); }
    private final Store db;
    private final ExecutorService pool=Executors.newFixedThreadPool(3);
    private final Map<String,List<Result>> pending=new HashMap<>();
    public Translations(Store db) { this.db=db; }
    public synchronized void get(String word,Result callback) {
        Store.Row cached=db.translation(word);
        if(!cached.isEmpty()) { callback.done(cached); return; }
        if(pending.containsKey(word)) { pending.get(word).add(callback);return; }
        List<Result> callbacks=new ArrayList<>();callbacks.add(callback);pending.put(word,callbacks);
        pool.execute(() -> {
            Store.Row value=new Store.Row();
            for(int attempt=0;attempt<2;attempt++) {
                try {
                    JSONObject data=new JSONObject(fetch("https://dict.youdao.com/jsonapi?dicts=%7B%22count%22%3A5%2C%22dicts%22%3A%5B%5B%22ec%22%5D%5D%7D&q="+URLEncoder.encode(word,"UTF-8")));
                    JSONObject ec=data.optJSONObject("ec");JSONArray words=ec==null?null:ec.optJSONArray("word");
                    List<String> meanings=new ArrayList<>();String pos="";
                    if(words!=null&&words.length()>0) {
                        JSONArray trs=words.getJSONObject(0).optJSONArray("trs");
                        if(trs!=null) for(int i=0;i<trs.length()&&meanings.size()<5;i++) {
                            JSONArray tr=trs.getJSONObject(i).optJSONArray("tr");
                            if(tr==null||tr.length()==0)continue;
                            JSONArray ls=tr.getJSONObject(0).optJSONArray("l");
                            // The public dictionary uses either an object or array for l.
                            JSONObject l=tr.getJSONObject(0).optJSONObject("l");
                            if(l==null&&ls!=null&&ls.length()>0)l=ls.optJSONObject(0);
                            JSONArray entries=l==null?null:l.optJSONArray("i");
                            if(entries!=null)for(int j=0;j<entries.length();j++) { String text=entries.optString(j).replaceAll("<[^>]*>","");if(!text.isEmpty())meanings.add(text); }
                        }
                    }
                    if(meanings.isEmpty())break;
                    String meaning=join(meanings);java.util.regex.Matcher m=java.util.regex.Pattern.compile("^(?:[a-z]+\\.\\s*)+").matcher(meanings.get(0));if(m.find())pos=m.group().trim();
                    db.cache(word,pos,meaning,"youdao-public");value=db.translation(word);break;
                }catch(Exception ignored) { /* retry once; no account, key or payment is required */ }
            }
            List<Result> done;
            synchronized(this) { done=pending.remove(word); }
            if(done!=null)for(Result cb:done)cb.done(value);
        });
    }
    private static String join(List<String> lines) { StringBuilder b=new StringBuilder();for(String s:lines){if(b.length()>0)b.append('\n');b.append(s);}return b.toString(); }
    private static String fetch(String url) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(6000);c.setReadTimeout(6000);c.setRequestProperty("User-Agent","InkAndEcho/1.0 (Android dictionary lookup)");
        try(InputStream in=c.getInputStream()) { return new String(WordImport.read(in),StandardCharsets.UTF_8); }finally{c.disconnect();}
    }
    public void close() { pool.shutdownNow(); }
}
