package android.database.sqlite;
import android.content.Context;
public abstract class SQLiteOpenHelper {
    private SQLiteDatabase db; private final String path;
    public SQLiteOpenHelper(Context c,String name,Object factory,int version){path=c.database;}
    public void setWriteAheadLoggingEnabled(boolean enabled){}
    public abstract void onCreate(SQLiteDatabase db);
    public void onConfigure(SQLiteDatabase db){}
    public abstract void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion);
    public SQLiteDatabase getWritableDatabase(){if(db==null){db=new SQLiteDatabase(path);onConfigure(db);if(!db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='units'",new String[]{}).moveToNext())onCreate(db);}return db;}
    public SQLiteDatabase getReadableDatabase(){return getWritableDatabase();}
    public void close(){if(db!=null)db.close();db=null;}
}
