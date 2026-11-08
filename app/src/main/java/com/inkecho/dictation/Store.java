package com.inkecho.dictation;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

/** One private database. All writes affecting history and aggregates share a transaction. */
public final class Store extends SQLiteOpenHelper {
    public static class Row extends HashMap<String,String> {
        public String s(String key) { String v=get(key); return v==null?"":v; }
        public long l(String key) { try{return Long.parseLong(s(key));}catch(Exception e){return 0;} }
        public int i(String key) { return (int)l(key); }
        public double d(String key) { try{return Double.parseDouble(s(key));}catch(Exception e){return 0;} }
    }
    public Store(Context c) { super(c,"ink_echo.db",null,1); setWriteAheadLoggingEnabled(true); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        String[] sql={
            "CREATE TABLE units(id INTEGER PRIMARY KEY,name TEXT NOT NULL,sort_order INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE lessons(id INTEGER PRIMARY KEY,unit_id INTEGER NOT NULL REFERENCES units(id) ON DELETE CASCADE,name TEXT NOT NULL,sort_order INTEGER NOT NULL DEFAULT 0,round_at INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE words(id INTEGER PRIMARY KEY,lesson_id INTEGER NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,text TEXT NOT NULL COLLATE BINARY,order_index INTEGER NOT NULL,created_at INTEGER NOT NULL,UNIQUE(lesson_id,text))",
            "CREATE TABLE sessions(id INTEGER PRIMARY KEY,lesson_id INTEGER NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,mode TEXT NOT NULL,bias INTEGER NOT NULL,review INTEGER NOT NULL,gamma REAL NOT NULL,seed INTEGER NOT NULL,scope TEXT NOT NULL,status TEXT NOT NULL,current_seq INTEGER NOT NULL DEFAULT 0,started_at INTEGER NOT NULL,event_at INTEGER NOT NULL DEFAULT 0,finished_at INTEGER NOT NULL DEFAULT 0,lock_key INTEGER NOT NULL DEFAULT 1)",
            "CREATE UNIQUE INDEX single_active_session ON sessions(lock_key) WHERE status IN ('in_progress','grading')",
            "CREATE TABLE items(id INTEGER PRIMARY KEY,session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,seq INTEGER NOT NULL,word_text TEXT NOT NULL,zone TEXT NOT NULL,score REAL NOT NULL,skipped INTEGER NOT NULL DEFAULT 0,heard INTEGER NOT NULL DEFAULT 0,UNIQUE(session_id,seq))",
            "CREATE TABLE marks(id INTEGER PRIMARY KEY,item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,lesson_id INTEGER NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,word_id INTEGER NOT NULL REFERENCES words(id) ON DELETE CASCADE,marked_at INTEGER NOT NULL,revoked_at INTEGER)",
            "CREATE UNIQUE INDEX one_live_mark ON marks(item_id) WHERE revoked_at IS NULL",
            "CREATE INDEX marks_word ON marks(word_id,marked_at)",
            "CREATE TABLE stats(word_id INTEGER PRIMARY KEY REFERENCES words(id) ON DELETE CASCADE,lesson_id INTEGER NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,wrong INTEGER NOT NULL DEFAULT 0,streak INTEGER NOT NULL DEFAULT 0,last_wrong INTEGER NOT NULL DEFAULT 0,last_at INTEGER NOT NULL DEFAULT 0,repetitions INTEGER NOT NULL DEFAULT 0,interval_days INTEGER NOT NULL DEFAULT 0,ease REAL NOT NULL DEFAULT 2.5,due INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX stats_review ON stats(lesson_id,due)",
            "CREATE INDEX sessions_lesson ON sessions(lesson_id,status)",
            "CREATE INDEX words_order ON words(lesson_id,order_index)",
            "CREATE INDEX items_word ON items(word_id,session_id)",
            "CREATE TABLE translations(word_key TEXT PRIMARY KEY COLLATE BINARY,pos TEXT NOT NULL,meaning TEXT NOT NULL,source TEXT NOT NULL,fetched_at INTEGER NOT NULL)",
            "CREATE TABLE imports(id INTEGER PRIMARY KEY,lesson_id INTEGER NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,source TEXT NOT NULL,parsed INTEGER NOT NULL,added INTEGER NOT NULL,duplicates INTEGER NOT NULL,ignored INTEGER NOT NULL,created_at INTEGER NOT NULL)",
            "CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)"
        };
        for(String s:sql) db.execSQL(s);
        db.execSQL("INSERT INTO units(id,name) VALUES(1,'Unit 1')");
        db.execSQL("INSERT INTO lessons(id,unit_id,name) VALUES(1,1,'Lesson 1')");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { throw new IllegalStateException("Unsupported schema version"); }
    public synchronized List<Row> rows(String sql,Object...args) {
        String[] a=new String[args.length]; for(int i=0;i<args.length;i++) a[i]=String.valueOf(args[i]);
        List<Row> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(sql,a)) {
            while(c.moveToNext()) { Row r=new Row(); for(int i=0;i<c.getColumnCount();i++) r.put(c.getColumnName(i),c.isNull(i)?null:c.getString(i)); out.add(r); }
        }
        return out;
    }
    public Row one(String sql,Object...args) { List<Row> r=rows(sql,args); return r.isEmpty()?new Row():r.get(0); }
    public void exec(String sql,Object...args) { getWritableDatabase().execSQL(sql,args); }
    public String getSetting(String key,String fallback) { Row r=one("SELECT value FROM settings WHERE key=?",key); return r.containsKey("value")?r.s("value"):fallback; }
    public boolean flag(String key,boolean def) { return getSetting(key,def?"1":"0").equals("1"); }
    public int number(String key,int def) { try{return Integer.parseInt(getSetting(key,""+def));}catch(Exception e){return def;} }
    public void setting(String key,Object value) { exec("INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)",key,String.valueOf(value)); }
    public List<Row> units() { return rows("SELECT * FROM units ORDER BY sort_order,id"); }
    public List<Row> lessons(long unit) {
        return rows("SELECT l.*,(SELECT count(*) FROM words w WHERE w.lesson_id=l.id) words,(SELECT count(*) FROM stats st WHERE st.lesson_id=l.id AND st.wrong>0) wrong,(SELECT count(*) FROM stats st WHERE st.lesson_id=l.id AND st.due>0 AND st.due<=?) due,(SELECT count(*) FROM sessions s WHERE s.lesson_id=l.id AND s.status='completed') batches,(SELECT max(started_at) FROM sessions s WHERE s.lesson_id=l.id) last_at FROM lessons l WHERE unit_id=? ORDER BY sort_order,id",Engine.day(System.currentTimeMillis()),unit);
    }
    public long createUnit(String name) { return insert("units","name",name,"sort_order",nextOrder("units",null)); }
    public long createLesson(long unit,String name) { return insert("lessons","unit_id",unit,"name",name,"sort_order",nextOrder("lessons",unit)); }
    private int nextOrder(String table,Long unit) { return one("SELECT COALESCE(max(sort_order),0)+1 n FROM "+table+(unit==null?"":" WHERE unit_id=?"),unit==null?new Object[]{}:new Object[]{unit}).i("n"); }
    private long insert(String table,Object...fields) {
        ContentValues v=new ContentValues();
        for(int i=0;i<fields.length;i+=2) { Object o=fields[i+1]; String k=fields[i].toString(); if(o instanceof Integer) v.put(k,(Integer)o); else if(o instanceof Long) v.put(k,(Long)o); else if(o instanceof Double) v.put(k,(Double)o); else v.put(k,String.valueOf(o)); }
        return getWritableDatabase().insertOrThrow(table,null,v);
    }
    public void moveOrder(String table,long id,boolean up) {
        if(!table.equals("units")&&!table.equals("lessons")) throw new IllegalArgumentException();
        Row target=one("SELECT * FROM "+table+" WHERE id=?",id);
        List<Row> all=rows("SELECT * FROM "+table+(table.equals("lessons")?" WHERE unit_id=?":"")+" ORDER BY sort_order,id",table.equals("lessons")?new Object[]{target.l("unit_id")}:new Object[]{});
        for(int i=0;i<all.size();i++) if(all.get(i).l("id")==id) {
            int j=up?i-1:i+1; if(j<0||j>=all.size()) return;
            Collections.swap(all,i,j); SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
            try { for(int n=0;n<all.size();n++) exec("UPDATE "+table+" SET sort_order=? WHERE id=?",n,all.get(n).l("id"));db.setTransactionSuccessful(); }finally{db.endTransaction();}return;
        }
    }
    public List<Engine.Word> words(long lesson) {
        List<Engine.Word> out=new ArrayList<>();
        for(Row r:rows("SELECT w.*,s.wrong,s.streak,s.last_wrong,s.last_at,s.repetitions,s.interval_days,s.ease,s.due FROM words w JOIN stats s ON s.word_id=w.id WHERE w.lesson_id=? ORDER BY w.order_index,w.id",lesson)) {
            Engine.Word w=new Engine.Word(r.l("id"),r.s("text"),r.i("order_index"));
            w.stat.wrong=r.i("wrong");w.stat.streak=r.i("streak");w.stat.lastWrong=r.l("last_wrong");w.stat.lastAt=r.l("last_at");w.stat.repetitions=r.i("repetitions");w.stat.interval=r.i("interval_days");w.stat.ease=r.d("ease");w.stat.due=r.l("due");out.add(w);
        }
        return out;
    }
    public Set<String> keys(long lesson) { Set<String> s=new HashSet<>();for(Row r:rows("SELECT text FROM words WHERE lesson_id=?",lesson))s.add(r.s("text"));return s; }
    public int[] importWords(long lesson,List<String> candidates,String source) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();int added=0,duplicates=0,ignored=0;
        try {
            Set<String> seen=keys(lesson);int order=one("SELECT COALESCE(max(order_index),0) n FROM words WHERE lesson_id=?",lesson).i("n");
            for(String candidate:candidates) {
                String text=WordImport.clean(candidate);
                if(text.isEmpty()) { ignored++;continue; } if(!seen.add(text)) {duplicates++;continue;}
                long word=insert("words","lesson_id",lesson,"text",text,"order_index",++order,"created_at",System.currentTimeMillis());
                insert("stats","word_id",word,"lesson_id",lesson);added++;
            }
            insert("imports","lesson_id",lesson,"source",source,"parsed",candidates.size(),"added",added,"duplicates",duplicates,"ignored",ignored,"created_at",System.currentTimeMillis());
            db.setTransactionSuccessful();return new int[]{added,duplicates,ignored};
        }finally{db.endTransaction();}
    }
    public long copyLesson(long id,String name) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Row l=one("SELECT * FROM lessons WHERE id=?",id);long result=createLesson(l.l("unit_id"),name);
            List<String> text=new ArrayList<>();for(Engine.Word w:words(id))text.add(w.text);
            importWords(result,text,"copy");db.setTransactionSuccessful();return result;
        }finally{db.endTransaction();}
    }
    public Row active() { return one("SELECT s.*,l.name lesson_name FROM sessions s JOIN lessons l ON l.id=s.lesson_id WHERE s.status IN ('in_progress','grading') LIMIT 1"); }
    public long start(long lesson,List<Engine.Word> words,boolean random,boolean bias,String scope) {
        if(words.isEmpty()) throw new IllegalArgumentException("EMPTY_SCOPE");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            if(!active().isEmpty()) throw new IllegalStateException("ACTIVE_SESSION");
            Set<Long> allowed=new HashSet<>();for(Row r:rows("SELECT id FROM words WHERE lesson_id=?",lesson))allowed.add(r.l("id"));
            for(Engine.Word w:words)if(!allowed.contains(w.id))throw new IllegalArgumentException("CROSS_LESSON_WORD");
            long now=System.currentTimeMillis(),seed=new Random().nextLong();double gamma=0.5;boolean review=flag("review",true);
            long id=insert("sessions","lesson_id",lesson,"mode",random?"random":"ordered","bias",bias?1:0,"review",review?1:0,"gamma",gamma,"seed",seed,"scope",scope,"status","in_progress","started_at",now);
            int i=0;for(Engine.Question q:Engine.plan(words,random,bias,review,gamma,seed,now))
                insert("items","session_id",id,"word_id",q.word.id,"seq",i++,"word_text",q.word.text,"zone",q.zone,"score",q.score);
            db.setTransactionSuccessful();return id;
        }finally{db.endTransaction();}
    }
    public Row session(long id) { return one("SELECT s.*,l.name lesson_name FROM sessions s JOIN lessons l ON l.id=s.lesson_id WHERE s.id=?",id); }
    public List<Row> items(long session) {
        return rows("SELECT i.*,CASE WHEN m.id IS NULL THEN 0 ELSE 1 END wrong FROM items i LEFT JOIN marks m ON m.item_id=i.id AND m.revoked_at IS NULL WHERE i.session_id=? ORDER BY i.seq",session);
    }
    public void visit(long session,int seq) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try { exec("UPDATE sessions SET current_seq=? WHERE id=? AND status='in_progress'",seq,session);exec("UPDATE items SET heard=1 WHERE session_id=? AND seq=?",session,seq);db.setTransactionSuccessful(); }finally{db.endTransaction();}
    }
    public void skip(long item,boolean value) { exec("UPDATE items SET skipped=? WHERE id=?",value?1:0,item); }
    public void grade(long session) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            // Ending early never silently treats unvisited words as correct.
            exec("UPDATE items SET skipped=1 WHERE session_id=? AND heard=0",session);
            exec("UPDATE sessions SET status='grading',event_at=? WHERE id=? AND status='in_progress'",System.currentTimeMillis(),session);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public void complete(long session) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            exec("UPDATE sessions SET status='completed',finished_at=? WHERE id=? AND status='grading'",System.currentTimeMillis(),session);
            for(Row r:rows("SELECT DISTINCT word_id FROM items WHERE session_id=?",session)) recompute(r.l("word_id"));
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public void abandon(long id) { exec("UPDATE sessions SET status='abandoned',finished_at=? WHERE id=? AND status='in_progress'",System.currentTimeMillis(),id); }
    public void mark(long item,boolean wrong) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Row r=one("SELECT i.*,s.lesson_id,s.status FROM items i JOIN sessions s ON s.id=i.session_id WHERE i.id=?",item);
            if(r.isEmpty()||r.i("skipped")==1||(!r.s("status").equals("grading")&&!r.s("status").equals("completed")))throw new IllegalStateException("NOT_GRADABLE");
            Row existing=one("SELECT id FROM marks WHERE item_id=? AND revoked_at IS NULL",item);
            if(wrong&&existing.isEmpty())insert("marks","item_id",item,"session_id",r.l("session_id"),"lesson_id",r.l("lesson_id"),"word_id",r.l("word_id"),"marked_at",System.currentTimeMillis());
            if(!wrong&&!existing.isEmpty())exec("UPDATE marks SET revoked_at=? WHERE id=?",System.currentTimeMillis(),existing.l("id"));
            recompute(r.l("word_id"));db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private void recompute(long word) {
        List<Engine.Outcome> history=new ArrayList<>();
        for(Row r:rows("SELECT i.skipped,s.event_at,CASE WHEN m.id IS NULL THEN 0 ELSE 1 END wrong FROM items i JOIN sessions s ON s.id=i.session_id LEFT JOIN marks m ON m.item_id=i.id AND m.revoked_at IS NULL WHERE i.word_id=? AND (s.status='completed' OR (s.status='grading' AND m.id IS NOT NULL)) ORDER BY s.started_at,s.id,i.seq",word))
            history.add(new Engine.Outcome(r.l("event_at"),r.i("wrong")==1,r.i("skipped")==1));
        Engine.Stat s=Engine.replay(history);
        exec("UPDATE stats SET wrong=?,streak=?,last_wrong=?,last_at=?,repetitions=?,interval_days=?,ease=?,due=? WHERE word_id=?",s.wrong,s.streak,s.lastWrong,s.lastAt,s.repetitions,s.interval,s.ease,s.due,word);
    }
    public void clearMarks(long lesson,Long word) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            if(word==null)exec("UPDATE marks SET revoked_at=? WHERE lesson_id=? AND revoked_at IS NULL",System.currentTimeMillis(),lesson);
            else exec("UPDATE marks SET revoked_at=? WHERE lesson_id=? AND word_id=? AND revoked_at IS NULL",System.currentTimeMillis(),lesson,word);
            for(Engine.Word w:words(lesson)) if(word==null||word==w.id)recompute(w.id);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public List<Row> history(long lesson) {
        return rows("SELECT s.*,count(i.id) total,COALESCE(sum(i.skipped),0) skipped,(SELECT count(*) FROM marks m WHERE m.session_id=s.id AND m.revoked_at IS NULL) wrong FROM sessions s JOIN items i ON i.session_id=s.id WHERE s.lesson_id=? GROUP BY s.id ORDER BY s.started_at DESC,s.id DESC",lesson);
    }
    public Row deletion(long lesson) {
        return one("SELECT (SELECT count(*) FROM words WHERE lesson_id=?) words,(SELECT count(*) FROM sessions WHERE lesson_id=?) batches,(SELECT count(*) FROM marks WHERE lesson_id=?) marks",lesson,lesson,lesson);
    }
    public synchronized Row translation(String key) { return one("SELECT * FROM translations WHERE word_key=?",key); }
    public synchronized void cache(String key,String pos,String meaning,String source) {
        exec("INSERT OR REPLACE INTO translations(word_key,pos,meaning,source,fetched_at) VALUES(?,?,?,?,?)",key,pos,meaning,source,System.currentTimeMillis());
    }
}
