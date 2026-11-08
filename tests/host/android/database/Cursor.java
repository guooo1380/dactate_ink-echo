package android.database;
import java.util.*;
public class Cursor implements AutoCloseable {
    private final String[] columns; private final List<String[]> rows; private int at=-1;
    public Cursor(String[] columns,List<String[]> rows){this.columns=columns;this.rows=rows;}
    public boolean moveToNext(){return ++at<rows.size();}
    public int getColumnCount(){return columns.length;}
    public String getColumnName(int i){return columns[i];}
    public boolean isNull(int i){return rows.get(at)[i]==null;}
    public String getString(int i){return rows.get(at)[i];}
    public void close(){}
}
