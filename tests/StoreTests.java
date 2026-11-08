import com.inkecho.dictation.*;
import android.content.Context;
import java.util.*;
import java.util.concurrent.*;

public class StoreTests {
    static int checks=0;
    static void eq(Object a,Object b,String label){checks++;if(!Objects.equals(a,b))throw new AssertionError(label+": "+a+" != "+b);}
    static void rejects(Runnable r,String label){boolean failed=false;try{r.run();}catch(Exception e){failed=true;}eq(failed,true,label);}
    static Store.Row stat(Store db,long word){return db.one("SELECT * FROM stats WHERE word_id=?",word);}
    static long practice(Store db,long lesson,boolean wrong,boolean skip){long s=db.start(lesson,db.words(lesson),false,false,"all");List<Store.Row> items=db.items(s);for(Store.Row i:items){db.visit(s,i.i("seq"));if(skip)db.skip(i.l("id"),true);}db.grade(s);if(wrong&&!skip)for(Store.Row i:items)db.mark(i.l("id"),true);db.complete(s);return s;}
    public static void main(String[] args)throws Exception{
        String path="build/tests/store-"+System.nanoTime()+".db";final Store db=new Store(new Context(path));
        eq(db.units().size(),1,"initial unit");eq(db.lessons(1).size(),1,"initial lesson");
        int[] imported=db.importWords(1,Arrays.asList("run","runs","Run","run","  "),"paste");eq(imported[0],3,"strict word forms and case");eq(imported[1],1,"duplicate skipped");eq(imported[2],1,"empty skipped");
        eq(db.words(1).get(2).order,3,"original word order");db.importWords(1,Arrays.asList("apple"),"paste");eq(db.words(1).get(3).order,4,"append order");
        long second=db.createLesson(1,"Other lesson");db.importWords(second,Arrays.asList("run"),"paste");eq(db.words(second).size(),1,"cross lesson words independent");
        rejects(()->db.start(1,db.words(second),false,false,"selected"),"cross lesson scope rejected");eq(db.active().isEmpty(),true,"failed scope is atomic");
        long s=db.start(second,db.words(second),false,true,"all");long item=db.items(s).get(0).l("id"),word=db.words(second).get(0).id;
        rejects(()->db.start(1,db.words(1),false,true,"all"),"global session lock");eq(db.history(1).size(),0,"failed start no partial history");db.visit(s,0);db.grade(s);
        eq(stat(db,word).i("streak"),0,"unmarked grading is not prematurely correct");db.mark(item,true);db.mark(item,true);eq(stat(db,word).i("wrong"),1,"idempotent set wrong");eq(db.rows("SELECT * FROM marks WHERE revoked_at IS NULL").size(),1,"one live mark");
        db.mark(item,false);eq(stat(db,word).i("wrong"),0,"undo count");eq(stat(db,word).l("due"),0L,"undo provisional review plan");eq(db.rows("SELECT * FROM marks WHERE revoked_at IS NOT NULL").size(),1,"append-only revoked ledger");
        db.complete(s);eq(stat(db,word).i("streak"),1,"complete advances correct answer");long originalDue=stat(db,word).l("due");db.mark(item,true);eq(stat(db,word).i("wrong"),1,"retroactive mark");eq(stat(db,word).i("streak"),0,"retroactive mark resets streak");db.mark(item,false);eq(stat(db,word).i("streak"),1,"retroactive undo streak");eq(stat(db,word).l("due"),originalDue,"retroactive undo due");
        practice(db,second,true,false);practice(db,second,true,false);practice(db,second,true,false);eq(stat(db,word).i("wrong"),3,"three historical errors");practice(db,second,false,false);practice(db,second,false,false);eq(stat(db,word).i("streak"),2,"two correct rounds");eq(Engine.score(db.words(second).get(0).stat,.5),.75,"decay in persisted aggregates");
        long beforeDue=stat(db,word).l("due");practice(db,second,false,true);eq(stat(db,word).i("streak"),2,"skip no streak");eq(stat(db,word).l("due"),beforeDue,"skip no review advance");
        long early=db.start(1,db.words(1),false,false,"all");db.visit(early,0);db.grade(early);eq(db.items(early).get(0).i("skipped"),0,"visited word included");eq(db.items(early).get(1).i("skipped"),1,"unvisited auto-skipped");rejects(()->db.mark(db.items(early).get(1).l("id"),true),"skipped word not markable");db.complete(early);eq(db.words(1).get(0).stat.streak,1,"heard unmarked correct");eq(db.words(1).get(1).stat.streak,0,"unheard excluded");
        long copy=db.copyLesson(second,"Copy");eq(db.words(copy).size(),1,"copy words");eq(db.words(copy).get(0).stat.wrong,0,"copy no marks");eq(db.history(copy).size(),0,"copy no sessions");
        db.clearMarks(second,word);eq(stat(db,word).i("wrong"),0,"clear marks");eq(stat(db,word).i("streak"),6,"clear marks replays correct history excluding skip");
        // Force an insert failure after an earlier word has already been inserted.
        db.exec("CREATE TRIGGER fail_import BEFORE INSERT ON words WHEN NEW.text='fail' BEGIN SELECT RAISE(ABORT,'test'); END");int count=db.words(1).size();rejects(()->db.importWords(1,Arrays.asList("will-rollback","fail"),"test"),"failed import");eq(db.words(1).size(),count,"whole import rolled back");eq(db.keys(1).contains("will-rollback"),false,"no partial word");db.exec("DROP TRIGGER fail_import");
        db.exec("DELETE FROM lessons WHERE id=?",second);for(String table:Arrays.asList("words","sessions","marks","stats","imports"))eq(db.one("SELECT count(*) n FROM "+table+" WHERE lesson_id=?",second).i("n"),0,"cascade "+table);eq(db.words(1).size(),4,"other lesson intact after delete");
        db.cache("apple","n.","苹果","test");eq(db.translation("apple").s("meaning"),"苹果","translation cache");eq(db.translation("Apple").isEmpty(),true,"translation cache strict case");db.setting("language","en");db.close();Store reopened=new Store(new Context(path));eq(reopened.getSetting("language","zh"),"en","settings persist after reopen");eq(reopened.words(1).size(),4,"words persist after reopen");eq(reopened.history(1).size(),1,"history persists after reopen");reopened.close();
        System.out.println("PASS: "+checks+" SQLite integration assertions");
    }
}
