package android.content;
import java.util.*;
public class ContentValues extends LinkedHashMap<String,Object> {
    public void put(String key,Integer value) { super.put(key,value); }
    public void put(String key,Long value) { super.put(key,value); }
    public void put(String key,Double value) { super.put(key,value); }
    public void put(String key,String value) { super.put(key,value); }
}
