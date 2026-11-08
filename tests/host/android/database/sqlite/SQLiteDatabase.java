package android.database.sqlite;
import android.content.ContentValues;
import android.database.Cursor;
import java.sql.*;
import java.util.*;

/** The production Store runs unchanged; this adapter executes its SQL against real SQLite. */
public class SQLiteDatabase {
    private final Connection connection;
    private final Deque<Boolean> successful=new ArrayDeque<>();
    private boolean rollback=false;
    public SQLiteDatabase(String path){try{connection=DriverManager.getConnection("jdbc:sqlite:"+path);}catch(SQLException e){throw new RuntimeException(e);}}
    public void setForeignKeyConstraintsEnabled(boolean enabled){execSQL("PRAGMA foreign_keys="+(enabled?"ON":"OFF"));}
    public void execSQL(String sql){execSQL(sql,new Object[]{});}
    public void execSQL(String sql,Object[] args){try(PreparedStatement s=connection.prepareStatement(sql)){bind(s,args);s.execute();}catch(SQLException e){throw new RuntimeException(sql,e);}}
    private void bind(PreparedStatement s,Object[] args)throws SQLException{for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);}
    public Cursor rawQuery(String sql,String[] args){try(PreparedStatement s=connection.prepareStatement(sql)){bind(s,args);try(ResultSet rs=s.executeQuery()){int n=rs.getMetaData().getColumnCount();String[] keys=new String[n];for(int i=0;i<n;i++)keys[i]=rs.getMetaData().getColumnLabel(i+1);List<String[]> rows=new ArrayList<>();while(rs.next()){String[] values=new String[n];for(int i=0;i<n;i++)values[i]=rs.getString(i+1);rows.add(values);}return new Cursor(keys,rows);}}catch(SQLException e){throw new RuntimeException(sql,e);}}
    public long insertOrThrow(String table,String nullColumnHack,ContentValues values){String columns=String.join(",",values.keySet());String params=String.join(",",Collections.nCopies(values.size(),"?"));execSQL("INSERT INTO "+table+"("+columns+") VALUES("+params+")",values.values().toArray());try(Statement s=connection.createStatement();ResultSet rs=s.executeQuery("SELECT last_insert_rowid()")){rs.next();return rs.getLong(1);}catch(SQLException e){throw new RuntimeException(e);}}
    public void beginTransaction(){try{if(successful.isEmpty()){connection.setAutoCommit(false);rollback=false;}successful.push(false);}catch(SQLException e){throw new RuntimeException(e);}}
    public void setTransactionSuccessful(){successful.pop();successful.push(true);}
    public void endTransaction(){if(!successful.pop())rollback=true;if(successful.isEmpty())try{if(rollback)connection.rollback();else connection.commit();connection.setAutoCommit(true);}catch(SQLException e){throw new RuntimeException(e);}}
    public void close(){try{connection.close();}catch(SQLException e){throw new RuntimeException(e);}}
}
