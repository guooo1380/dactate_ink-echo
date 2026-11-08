package com.inkecho.dictation;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

public final class WordImport {
    private WordImport() {}
    public static final int MAX_BYTES=16*1024*1024;
    public static class Decoded {
        public String text; public boolean gbk;
        Decoded(String text,boolean gbk) {this.text=text;this.gbk=gbk;}
    }
    public static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[8192]; int n;
        while ((n=in.read(buf))!=-1) { if (out.size()+n>MAX_BYTES) throw new IOException("FILE_TOO_LARGE"); out.write(buf,0,n); }
        return out.toByteArray();
    }
    public static Decoded text(byte[] data) throws CharacterCodingException {
        try { return new Decoded(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString(),false); }
        catch(CharacterCodingException ex) { return new Decoded(Charset.forName("GBK").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString(),true); }
    }
    public static String clean(String line) {
        return withoutNumber(line).replaceAll("[\\s\\u00a0]+"," ").trim();
    }
    private static String withoutNumber(String line) {
        return line.replace("\uFEFF","").trim().replaceFirst("^(?:(?:\\d+[.、)．:：]\\s*)|(?:[（(]\\d+[）)]\\s*)|(?:[①-⑳]\\s*)|(?:[-•·]\\s+))","");
    }
    public static List<String> split(String text) {
        List<String> result=new ArrayList<>();
        for(String line:text.split("[\\r\\n]+")) {
            // Remove a paragraph's list number before splitting on the Chinese separator 、.
            for(String part:withoutNumber(line).split("[\\t,;，；、]+")) {
                String s=clean(part); if(!s.isEmpty()) result.add(s);
            }
        }
        return result;
    }
    public static List<String> duplicates(List<String> text,Set<String> existing) {
        Set<String> seen=new HashSet<>(existing); List<String> result=new ArrayList<>();
        for(String s:text) { String key=clean(s); result.add(key.isEmpty()?"empty":seen.add(key)?"new":"duplicate"); }
        return result;
    }
    public static String docx(InputStream stream) throws Exception {
        try(ZipInputStream zip=new ZipInputStream(stream)) {
            ZipEntry e;
            while((e=zip.getNextEntry())!=null) {
                if(!e.getName().equals("word/document.xml")) continue;
                byte[] xml=read(zip);
                String declaration=new String(xml,StandardCharsets.ISO_8859_1).replace("\u0000","");
                if(declaration.contains("<!DOCTYPE")||declaration.contains("<!ENTITY"))throw new IOException("UNSAFE_XML");
                SAXParserFactory factory=SAXParserFactory.newInstance(); factory.setNamespaceAware(true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
                final StringBuilder out=new StringBuilder();
                DefaultHandler handler=new DefaultHandler() {
                    boolean text=false;
                    private boolean word(String uri) { return uri.equals("http://schemas.openxmlformats.org/wordprocessingml/2006/main") || uri.equals("http://purl.oclc.org/ooxml/wordprocessingml/main"); }
                    @Override public InputSource resolveEntity(String p,String s) { return new InputSource(new StringReader("")); }
                    @Override public void startElement(String uri,String local,String q,Attributes a) {
                        if(!word(uri)) return;
                        if(local.equals("t")) text=true;
                        if(local.equals("tab")) out.append('\t');
                        if(local.equals("br")||local.equals("cr")) out.append('\n');
                    }
                    @Override public void characters(char[] chars,int start,int len) { if(text) out.append(chars,start,len); }
                    @Override public void endElement(String uri,String local,String q) {
                        if(!word(uri)) return;
                        if(local.equals("t")) text=false;
                        if(local.equals("p")) out.append('\n');
                    }
                };
                factory.newSAXParser().parse(new ByteArrayInputStream(xml),handler);
                return out.toString();
            }
        }
        throw new IOException("NO_DOCUMENT_TEXT");
    }
}
