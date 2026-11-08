package com.inkecho.dictation;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.sqlite.SQLiteException;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.media.*;
import android.view.*;
import android.widget.*;
import android.text.*;
import android.content.res.ColorStateList;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int BG=0xffF6F7F2,INK=0xff213A34,MUTED=0xff6E7D75,GREEN=0xff276658,PALE=0xffEAF0E8,LINE=0xffE1E7DD,RED=0xffA74A3E,WHITE=0xffFFFFFF;
    private Store db;
    private Translations translations;
    private AudioCache audioCache;
    private AudioCache.Cancellation audioCancellation;
    private Future<?> audioTask;
    private MediaPlayer player;
    private AudioManager audioManager;
    private ProgressDialog audioProgress;
    private boolean zh=true,foreground=false;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final AudioManager.OnAudioFocusChangeListener audioFocus=change->{if(change<0)handler.post(this::stopSpeech);};
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService audioIo=Executors.newSingleThreadExecutor();
    private LinearLayout root,body,bar;
    private String page="home",tab="words";
    private long lessonId=1,sessionId=0;
    private int renderId=0,speechId=0,pages=1,gradingPage=1,previewPage=1;
    private boolean random=true,bias=true;
    private String scope="all",search="",wrongSort="count";
    private int wrongMin=1,wrongMax=999999,wrongDays=0;
    private boolean decayedOnly=false;
    private final Set<Long> selected=new HashSet<>();
    private List<String> importText=new ArrayList<>();
    private final Set<Integer> excluded=new HashSet<>();
    private String importSource="paste";
    private Button playButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);db=new Store(this);translations=new Translations(db);readLanguage();
        audioCache=new AudioCache(new File(getFilesDir(),"pronunciations"));audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);setVolumeControlStream(AudioManager.STREAM_MUSIC);
        if(Build.VERSION.SDK_INT>=35) getWindow().getDecorView().setOnApplyWindowInsetsListener((v,insets)->{ v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets; });
        random=db.flag("random",true);bias=db.flag("bias",true);
        if(state!=null) {page=state.getString("page","home");tab=state.getString("tab","words");lessonId=state.getLong("lesson",1);sessionId=state.getLong("session",0);}
        Store.Row active=db.active();
        if(!active.isEmpty()) {sessionId=active.l("id");lessonId=active.l("lesson_id");page=active.s("status").equals("grading")?"grading":"listen";}
        else if(page.equals("preview")||page.equals("import")||page.equals("setup")||page.equals("listen"))page="home";
        render();
    }
    @Override protected void onSaveInstanceState(Bundle state) {state.putString("page",page);state.putString("tab",tab);state.putLong("lesson",lessonId);state.putLong("session",sessionId);super.onSaveInstanceState(state);}
    @Override protected void onResume() {super.onResume();foreground=true;}
    @Override protected void onPause() {foreground=false;stopSpeech();super.onPause();}
    @Override protected void onDestroy() {stopSpeech();handler.removeCallbacksAndMessages(null);translations.close();io.shutdownNow();audioIo.shutdownNow();super.onDestroy();}
    private void readLanguage() {String l=db.getSetting("language","system");zh=l.equals("zh")||(l.equals("system")&&Locale.getDefault().getLanguage().equals("zh"));}
    private String t(String chinese,String english) {return zh?chinese:english;}
    private int dp(float n) {return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
    private void toast(String s) {Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private GradientDrawable bg(int color,int radius) {GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    // GradientDrawable clamps this radius to half the bounds, including taller text buttons.
    private GradientDrawable pill(int color) {return bg(color,1000);}
    private TextView text(String s,int size,int color) {TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(dp(3),1);return v;}
    private TextView title(String s,int size) {TextView v=text(s,size,INK);v.setTypeface(null,Typeface.BOLD);return v;}
    private LinearLayout column() {LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row() {LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);l.setBaselineAligned(false);return l;}
    private void gap(LinearLayout l,int n) {View v=new View(this);l.addView(v,new LinearLayout.LayoutParams(1,dp(n)));}
    private Button button(String label,Runnable action,boolean primary) {
        Button b=new Button(this);b.setTextSize(14);b.setAllCaps(false);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setTextColor(primary?WHITE:GREEN);b.setStateListAnimator(null);
        Drawable surface=new RippleDrawable(ColorStateList.valueOf(primary?0x26ffffff:0x18276658),pill(primary?GREEN:PALE),pill(WHITE));
        boolean iconOnly=label.equals("▶")||label.equals("···");
        if(iconOnly){Drawable icon=getDrawable(label.equals("▶")?R.drawable.ic_play:R.drawable.ic_more).mutate();icon.setTint(primary?WHITE:GREEN);LayerDrawable layers=new LayerDrawable(new Drawable[]{surface,icon});layers.setLayerSize(1,dp(22),dp(22));layers.setLayerGravity(1,Gravity.CENTER);surface=layers;b.setContentDescription(label.equals("▶")?t("播放发音","Play pronunciation"):t("更多选项","More options"));b.setText("");}
        else if(label.startsWith("▶")||label.startsWith("＋")){Drawable icon=getDrawable(label.startsWith("▶")?R.drawable.ic_play:R.drawable.ic_add).mutate();icon.setTint(primary?WHITE:GREEN);icon.setBounds(0,0,dp(18),dp(18));b.setCompoundDrawablesRelative(icon,null,null,null);b.setCompoundDrawablePadding(dp(8));b.setText(label.substring(1).trim());}
        else b.setText(label);
        b.setBackground(surface);b.setPadding(dp(14),dp(9),dp(14),dp(9));b.setOnClickListener(v->safe(action));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);b.setLayoutParams(lp);return b;
    }
    private void safe(Runnable action) {try{action.run();}catch(Exception e){toast(t("操作未完成，数据未更改。请重试。","The operation could not finish. Please try again.")+"\n"+e.getClass().getSimpleName());}}
    private LinearLayout card(LinearLayout parent) {LinearLayout l=column();l.setPadding(dp(20),dp(20),dp(20),dp(20));l.setBackground(bg(WHITE,28));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(14);parent.addView(l,p);return l;}
    private void small(String s) {body.addView(text(s,13,MUTED));}
    private void section(String s) {gap(body,20);body.addView(title(s,18));}
    private void actions(LinearLayout parent,Button...buttons) {
        LinearLayout r=row();
        // Keep vertical spacing on the row. A top margin on centered children clips their bottom edge.
        r.setPadding(0,dp(10),0,0);
        for(int i=0;i<buttons.length;i++){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);if(i>0)p.leftMargin=dp(8);r.addView(buttons[i],p);}
        parent.addView(r);
    }
    private EditText input(String hint,String value,boolean multiline) {EditText e=new EditText(this);e.setTextColor(INK);e.setHintTextColor(MUTED);e.setTextSize(15);e.setHint(hint);e.setText(value);e.setPadding(dp(16),dp(13),dp(16),dp(13));GradientDrawable surface=bg(BG,18);surface.setStroke(dp(1),LINE);e.setBackground(surface);e.setSingleLine(!multiline);if(multiline){e.setMinLines(5);e.setGravity(Gravity.TOP);}return e;}
    private ImageView image(int resource,int width,int height) {ImageView v=new ImageView(this);v.setImageResource(resource);v.setScaleType(ImageView.ScaleType.FIT_CENTER);v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);v.setLayoutParams(new LinearLayout.LayoutParams(dp(width),dp(height)));return v;}
    private CheckBox check(String label,boolean checked) {CheckBox c=new CheckBox(this);c.setText(label);c.setTextColor(INK);c.setTextSize(14);c.setMinHeight(dp(48));c.setButtonTintList(ColorStateList.valueOf(GREEN));c.setChecked(checked);return c;}
    private void prompt(String caption,String value,java.util.function.Consumer<String> done) {EditText e=input(caption,value,false);LinearLayout p=column();p.setPadding(dp(22),dp(8),dp(22),0);p.addView(e);AlertDialog d=new AlertDialog.Builder(this).setTitle(caption).setView(p).setNegativeButton(t("取消","Cancel"),null).setPositiveButton(t("确定","Confirm"),null).create();d.setOnShowListener(v->d.getButton(-1).setOnClickListener(w->{String name=e.getText().toString().trim();if(name.isEmpty()&&!caption.contains(t("搜索","Search"))){e.setError(t("请填写内容","Enter a value"));return;}safe(()->done.accept(name));d.dismiss();}));d.show();}
    private void confirm(String title,String message,Runnable yes) {new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton(t("取消","Cancel"),null).setPositiveButton(t("确认","Confirm"),(d,w)->safe(yes)).show();}
    private void choose(String caption,String[] labels,java.util.function.IntConsumer done) {new AlertDialog.Builder(this).setTitle(caption).setItems(labels,(d,w)->safe(()->done.accept(w))).show();}
    private String date(long at) {return at==0?t("从未","Never"):new SimpleDateFormat("MM-dd HH:mm",zh?Locale.CHINA:Locale.US).format(new Date(at));}
    private String day(long at) {return at==0?"—":new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date(at));}
    private String percent(double value) {return Double.isNaN(value)?"—":String.format(Locale.US,"%.0f%%",value);}
    private void navigate(String next) {stopSpeech();page=next;render();}
    private void render() {
        renderId++;playButton=null;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root=column();root.setBackgroundColor(BG);setContentView(root);
        bar=row();bar.setPadding(dp(18),dp(10),dp(18),dp(6));
        ImageView mark=image(R.drawable.ic_launcher,32,32);bar.addView(mark);TextView brand=title("INK & ECHO",13);brand.setLetterSpacing(.09f);brand.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams brandLayout=new LinearLayout.LayoutParams(0,dp(40),1);brandLayout.leftMargin=dp(10);bar.addView(brand,brandLayout);
        if(!page.equals("home")){Button back=button(t("返回","Back"),this::back,false);bar.addView(back,new LinearLayout.LayoutParams(dp(72),dp(40)));}
        root.addView(bar);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        body=column();body.setPadding(dp(22),dp(14),dp(22),dp(30));scroll.addView(body);
        switch(page){
            case "home":home();break;
            case "lesson":lesson();break;
            case "import":importPage();break;
            case "preview":preview();break;
            case "setup":setup();break;
            case "listen":listen();break;
            case "grading":grading();break;
            case "settings":settings();break;
            default:page="home";home();
        }
    }
    @Override public void onBackPressed() {back();}
    private void back() {
        if(page.equals("listen")){
            stopSpeech();
            choose(t("听写已自动保存","Your progress is saved"),new String[]{t("继续听写","Keep listening"),t("结束听写，开始对改","Finish listening & check"),t("暂时退出，稍后继续","Leave & resume later"),t("放弃本次（不计统计）","Abandon (no statistics)")},i->{if(i==1)endListening();if(i==2){stopSpeech();finish();}if(i==3)confirm(t("放弃本次听写？","Abandon this session?"),t("此次记录保留为已放弃，不计入错误次数与复习。","This session will be kept as abandoned and excluded from statistics and review."),()->{db.abandon(sessionId);navigate("home");});});return;
        }
        if(page.equals("grading")&&db.session(sessionId).s("status").equals("grading")){confirm(t("完成对改？","Finish checking?"),t("未标错且未跳过的题目将视为正确，以后仍可从历史补标。","Unmarked, unskipped answers count as correct. You can edit marks later in history."),()->{db.complete(sessionId);tab="stats";navigate("lesson");});return;}
        if(page.equals("home")){super.onBackPressed();return;}
        if(page.equals("lesson")||page.equals("settings"))navigate("home");
        else if(page.equals("preview"))navigate("import");
        else navigate("lesson");
    }
    private void heading(String overline,String name,String note) {TextView o=text(overline,11,GREEN);o.setLetterSpacing(.16f);body.addView(o);gap(body,8);body.addView(title(name,30));gap(body,8);small(note);gap(body,10);}
    private void home() {
        heading(t("听见 · 记住 · 再相遇","LISTEN · WRITE · REMEMBER"),t("把单词，慢慢记牢。","Make every word stay."),t("一本纸，一支笔，开始今天的听写。","A notebook, a pen, and a little practice."));
        Store.Row active=db.active();
        if(!active.isEmpty()) {LinearLayout c=card(body);c.addView(title(t("有一场未完成的听写","A session is waiting"),18));c.addView(text(active.s("lesson_name"),14,MUTED));c.addView(button(t("继续上次进度","Resume session"),()->resume(active),true));}
        actions(body,button(t("＋ 新建单元","＋ New unit"),()->prompt(t("单元名称","Unit name"),"",s->{db.createUnit(s);render();}),false),button(t("设置","Settings"),()->navigate("settings"),false));
        for(Store.Row unit:db.units()) {
            LinearLayout head=row();TextView name=title(unit.s("name"),19);head.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            Button menu=button("···",()->unitMenu(unit),false);head.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(40)));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=dp(24);body.addView(head,hp);
            List<Store.Row> lessons=db.lessons(unit.l("id"));
            for(Store.Row l:lessons) {
                LinearLayout c=card(body);LinearLayout top=row();TextView label=title(l.s("name"),21);top.addView(label,new LinearLayout.LayoutParams(0,-2,1));ImageView arrow=image(R.drawable.ic_arrow,20,20);arrow.setBackground(bg(PALE,20));arrow.setPadding(dp(5),dp(5),dp(5),dp(5));top.addView(arrow,new LinearLayout.LayoutParams(dp(34),dp(34)));c.addView(top);gap(c,10);
                c.addView(text(l.i("words")+t(" 个词  ·  "," words  ·  ")+l.i("batches")+t(" 次听写"," sessions"),14,MUTED));
                gap(c,8);c.addView(text(l.i("due")>0?l.i("due")+t(" 个词，今天再见一面"," words ready for review today"):t("按自己的节奏，一点点进步。","Small steps, at your own pace."),13,GREEN));
                if(l.i("wrong")>0)c.addView(text(l.i("wrong")+t(" 个错词待巩固"," words to strengthen"),13,RED));
                if(l.l("last_at")>0)c.addView(text(t("最近听写  ","Last session  ")+date(l.l("last_at")),12,MUTED));
                actions(c,button(t("打开词本","Open lesson"),()->openLesson(l.l("id"),"words"),true),button(t("开始听写","Start"),()->{lessonId=l.l("id");beginSetup();},false));
                actions(c,button(t("导入词表","Import"),()->{lessonId=l.l("id");if(guardActive())return;navigate("import");},false),button(t("错词","Mistakes"),()->openLesson(l.l("id"),"wrong"),false));
            }
            if(lessons.isEmpty())small(t("还没有听写框，为这个单元添加一个吧。","Add your first lesson to this unit."));
            body.addView(button(t("＋ 添加听写框","＋ Add lesson"),()->prompt(t("听写框名称","Lesson name"),"Lesson "+(lessons.size()+1),s->{db.createLesson(unit.l("id"),s);render();}),false));
        }
        gap(body,28);small(t("数据只保存在这台设备上 · 无账号 · 无云同步","Stored on this device · No account · No cloud sync"));
    }
    private void unitMenu(Store.Row u) {
        choose(u.s("name"),new String[]{t("重命名","Rename"),t("上移","Move up"),t("下移","Move down"),t("删除空单元","Delete empty unit")},i->{if(i==0)prompt(t("重命名","Rename"),u.s("name"),s->{db.exec("UPDATE units SET name=? WHERE id=?",s,u.l("id"));render();});if(i==1||i==2){db.moveOrder("units",u.l("id"),i==1);render();}if(i==3){if(!db.lessons(u.l("id")).isEmpty()){toast(t("请先移动或删除该单元下的听写框。","Move or delete the lessons in this unit first."));return;}confirm(t("删除空单元？","Delete empty unit?"),u.s("name"),()->{db.exec("DELETE FROM units WHERE id=?",u.l("id"));render();});}});
    }
    private void resume(Store.Row active) {sessionId=active.l("id");lessonId=active.l("lesson_id");gradingPage=1;navigate(active.s("status").equals("grading")?"grading":"listen");}
    private boolean guardActive() {Store.Row active=db.active();if(active.isEmpty())return false;new AlertDialog.Builder(this).setTitle(t("请先完成当前听写","Finish the current session first")).setMessage(active.s("lesson_name")).setPositiveButton(t("继续","Resume"),(d,w)->resume(active)).setNeutralButton(t("关闭","Close"),null).setNegativeButton(t("放弃","Abandon"),(d,w)->{if(active.s("status").equals("grading")){resume(active);return;}confirm(t("放弃当前听写？","Abandon the current session?"),t("不会计入任何统计。","It will not affect statistics."),()->{db.abandon(active.l("id"));render();});}).show();return true;}
    private void openLesson(long id,String nextTab) {if(guardActive())return;lessonId=id;tab=nextTab;search="";pages=1;navigate("lesson");}
    private void lesson() {
        Store.Row l=db.one("SELECT l.*,u.name unit_name FROM lessons l JOIN units u ON u.id=l.unit_id WHERE l.id=?",lessonId);
        if(l.isEmpty()){page="home";home();return;}
        heading(l.s("unit_name").toUpperCase(Locale.ROOT),l.s("name"),t("每一次重逢，都会更熟悉一点。","A little more familiar with every return."));
        actions(body,button(t("开始听写","Start dictation"),this::beginSetup,true),button(t("导入","Import"),()->navigate("import"),false),button("···",()->lessonMenu(l),false));
        LinearLayout tabs=row();String[] keys={"words","wrong","stats","history"};String[] labels={t("词表","Words"),t("错词","Mistakes"),t("统计","Insights"),t("历史","History")};
        for(int i=0;i<keys.length;i++){final String key=keys[i];Button b=button(labels[i],()->{tab=key;pages=1;search="";render();},tab.equals(key));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);if(i>0)p.leftMargin=dp(5);tabs.addView(b,p);}gap(body,14);body.addView(tabs);gap(body,12);
        if(tab.equals("words"))wordList(false);if(tab.equals("wrong"))wordList(true);if(tab.equals("stats"))stats();if(tab.equals("history"))history();
    }
    private void lessonMenu(Store.Row l) {
        choose(l.s("name"),new String[]{t("重命名","Rename"),t("移动到单元","Move to unit"),t("复制词表（不含历史）","Copy words (without history)"),t("上移","Move up"),t("下移","Move down"),t("开始新一轮词表","Start a new word round"),t("删除听写框","Delete lesson")},i->{
            if(i==0)prompt(t("重命名","Rename"),l.s("name"),s->{db.exec("UPDATE lessons SET name=? WHERE id=?",s,lessonId);render();});
            if(i==1){List<Store.Row> units=db.units();String[] labels=new String[units.size()];for(int j=0;j<labels.length;j++)labels[j]=units.get(j).s("name");choose(t("移动到","Move to"),labels,j->{db.exec("UPDATE lessons SET unit_id=? WHERE id=?",units.get(j).l("id"),lessonId);render();});}
            if(i==2)prompt(t("新听写框名称","New lesson name"),l.s("name")+t(" 副本"," copy"),s->{db.copyLesson(lessonId,s);toast(t("已复制，统计从零开始。","Copied with a fresh history."));render();});
            if(i==3||i==4){db.moveOrder("lessons",lessonId,i==3);render();}
            if(i==5)confirm(t("开始新一轮？","Start a new round?"),t("“本轮未听”从现在起重新计算，历史与错误记录保留。","Unpracticed this round will reset from now. History and errors are kept."),()->{db.exec("UPDATE lessons SET round_at=? WHERE id=?",System.currentTimeMillis(),lessonId);render();});
            if(i==6){Store.Row n=db.deletion(lessonId);confirm(t("删除听写框？","Delete lesson?"),t("将永久删除 ","Permanently delete ")+n.i("words")+t(" 个词、"," words, ")+n.i("batches")+t(" 个批次和 "," sessions and ")+n.i("marks")+t(" 条标记及复习计划。无法恢复。"," mark records and review schedules. This cannot be undone."),()->{db.exec("DELETE FROM lessons WHERE id=?",lessonId);navigate("home");});}
        });
    }
    private List<Engine.Word> filteredWords(boolean wrong) {
        List<Engine.Word> out=new ArrayList<>();long now=System.currentTimeMillis();
        for(Engine.Word w:db.words(lessonId)) {
            if(!w.text.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)))continue;
            if(wrong&&(w.stat.wrong<wrongMin||w.stat.wrong>wrongMax||(wrongDays>0&&w.stat.lastWrong<Engine.addDays(now,-wrongDays))||(decayedOnly&&Engine.score(w.stat,.5)>=1)))continue;
            out.add(w);
        }
        if(wrong)out.sort((a,b)->wrongSort.equals("recent")?Long.compare(b.stat.lastWrong,a.stat.lastWrong):wrongSort.equals("due")?Long.compare(a.stat.due,b.stat.due):Integer.compare(b.stat.wrong,a.stat.wrong));
        return out;
    }
    private void wordList(boolean wrong) {
        List<Engine.Word> all=filteredWords(wrong);small(all.size()+t(wrong?" 个错词":" 个词",wrong?" mistakes":" words"));
        actions(body,button(t("搜索","Search"),()->prompt(t("搜索单词（留空显示全部）","Search words (empty for all)"),search,s->{search=s;pages=1;render();}),false),button(t("清除搜索","Clear search"),()->{search="";pages=1;render();},false));
        if(wrong){actions(body,button(t("排序与筛选","Sort & filter"),this::wrongFilters,false),button(t("听写这些错词","Practice these"),()->{selected.clear();for(Engine.Word w:all)selected.add(w.id);scope="selected";navigate("setup");},true));
            body.addView(button(t("撤销本框所有错误标记","Revoke all marks in this lesson"),()->confirm(t("撤销所有标记？","Revoke all marks?"),t("所有历史题目将恢复为未标错，并重算连续答对与复习计划。","All historical answers become unmarked and the review schedules are recalculated."),()->{db.clearMarks(lessonId,null);render();}),false));}
        if(all.isEmpty()){LinearLayout c=card(body);c.addView(title(t(wrong?"这里暂时很安静。":"让第一个单词住进来。",wrong?"A clean page for now.":"Make room for your first word."),20));c.addView(text(t(wrong?"标错的词会出现在这里。":"导入 Word、TXT，或直接粘贴词表。",wrong?"Words you mark wrong will appear here.":"Import a Word or TXT file, or paste a word list."),14,MUTED));return;}
        int end=Math.min(all.size(),pages*50);for(int i=0;i<end;i++) {
            Engine.Word w=all.get(i);LinearLayout c=card(body);LinearLayout r=row();r.addView(title(w.text,19),new LinearLayout.LayoutParams(0,-2,1));r.addView(button("▶",()->speak(w.text,false),false),new LinearLayout.LayoutParams(dp(50),dp(42)));c.addView(r);
            if(wrong){c.addView(text(w.stat.wrong+t(" 次错误 · 连续答对 "," errors · Correct streak ")+w.stat.streak,13,RED));c.addView(text(t("最近错误 ","Last mistake ")+date(w.stat.lastWrong)+"\n"+t("下次复习 ","Next review ")+day(w.stat.due),12,MUTED));
                if(Engine.score(w.stat,.5)<1)c.addView(text(t("已衰减，暂离错词优先区","Decayed · outside error priority"),12,GREEN));}
            else c.addView(text("#"+w.order+"  ·  "+(w.stat.lastAt==0?t("从未听写","Not practiced"):t("复习 ","Review ")+day(w.stat.due)),12,MUTED));
            translation(c,w.text);
            if(wrong)c.addView(button(t("撤销此词所有标记","Revoke marks for this word"),()->confirm(t("撤销标记？","Revoke marks?"),w.text,()->{db.clearMarks(lessonId,w.id);render();}),false));
        }
        if(end<all.size())body.addView(button(t("再显示 50 个","Show 50 more"),()->{pages++;render();},false));
    }
    private void wrongFilters() {
        LinearLayout c=column();c.setPadding(dp(22),dp(10),dp(22),0);
        String[] sort={t("错误次数最多","Most errors"),t("最近标错","Most recent"),t("复习日期最早","Earliest review")};Spinner spinner=new Spinner(this);spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,sort));spinner.setSelection(wrongSort.equals("recent")?1:wrongSort.equals("due")?2:0);c.addView(spinner);
        EditText min=input(t("最少错误次数","Minimum error count"),""+wrongMin,false),max=input(t("最多错误次数","Maximum error count"),wrongMax==999999?"":""+wrongMax,false),days=input(t("最近 N 天（空表示不限）","Within N days (empty: any)"),wrongDays==0?"":""+wrongDays,false);for(EditText e:new EditText[]{min,max,days}){e.setInputType(2);gap(c,8);c.addView(e);}CheckBox decayed=check(t("只看优先级已衰减的词","Only decayed words"),decayedOnly);c.addView(decayed);
        new AlertDialog.Builder(this).setTitle(t("错词筛选","Mistake filters")).setView(c).setNegativeButton(t("取消","Cancel"),null).setNeutralButton(t("重置","Reset"),(d,w)->{wrongSort="count";wrongMin=1;wrongMax=999999;wrongDays=0;decayedOnly=false;render();}).setPositiveButton(t("应用","Apply"),(d,w)->{wrongSort=new String[]{"count","recent","due"}[spinner.getSelectedItemPosition()];wrongMin=Math.max(1,parse(min,1));wrongMax=Math.max(wrongMin,parse(max,999999));wrongDays=Math.max(0,parse(days,0));decayedOnly=decayed.isChecked();pages=1;render();}).show();
    }
    private int parse(EditText e,int fallback) {try{return Integer.parseInt(e.getText().toString().trim());}catch(Exception ex){return fallback;}}
    private void beginSetup() {if(guardActive())return;selected.clear();for(Engine.Word w:db.words(lessonId))selected.add(w.id);scope="all";pages=1;navigate("setup");}
    private void setup() {
        heading(t("准备一场听写","A MOMENT TO PREPARE"),t("今天，听哪些？","What shall we practice?"),t("范围内每个词听一遍。准备好纸和笔。","Every selected word, once. Get your pen and paper ready."));
        List<Engine.Word> words=db.words(lessonId);long round=db.one("SELECT round_at FROM lessons WHERE id=?",lessonId).l("round_at");
        LinearLayout c=card(body);c.addView(title(t("选词范围","Word selection"),18));
        String[] keys={"all","wrong","due","never","round","selected"};String[] labels={t("全部词条","All words"),t("历史错词","Historical mistakes"),t("到期复习","Due for review"),t("从未听过","Never practiced"),t("本轮未听","Unpracticed this round"),t("手动勾选","Choose manually")};
        Spinner filter=new Spinner(this);filter.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));for(int i=0;i<keys.length;i++)if(keys[i].equals(scope))filter.setSelection(i);c.addView(filter);
        filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){boolean first=true;public void onNothingSelected(android.widget.AdapterView<?> a){}public void onItemSelected(android.widget.AdapterView<?> a,View v,int p,long id){if(first){first=false;return;}scope=keys[p];if(!scope.equals("selected")){selected.clear();for(Engine.Word w:words)if(scope.equals("all")||(scope.equals("wrong")&&w.stat.wrong>0)||(scope.equals("due")&&Engine.due(w.stat,System.currentTimeMillis()))||(scope.equals("never")&&w.stat.lastAt==0)||(scope.equals("round")&&w.stat.lastAt<=round))selected.add(w.id);}pages=1;render();}});
        actions(c,button(t("全选","Select all"),()->{for(Engine.Word w:words)selected.add(w.id);scope="selected";render();},false),button(t("反选","Invert"),()->{for(Engine.Word w:words)if(!selected.remove(w.id))selected.add(w.id);scope="selected";render();},false),button(t("按区间","Range"),()->range(words),false));
        gap(c,14);TextView count=title(selected.size()+t(" 个词已选择"," words selected"),19);c.addView(count);
        RadioGroup mode=new RadioGroup(this);mode.setOrientation(LinearLayout.HORIZONTAL);RadioButton r=new RadioButton(this),o=new RadioButton(this);r.setId(View.generateViewId());o.setId(View.generateViewId());r.setText(t("随机顺序","Random"));o.setText(t("原文顺序","In order"));mode.addView(r);mode.addView(o);mode.check(random?r.getId():o.getId());mode.setOnCheckedChangeListener((g,id)->random=id==r.getId());c.addView(mode);
        CheckBox weighting=check(t("错词优先 · 到期复习优先","Prioritize mistakes & due reviews"),bias);weighting.setOnCheckedChangeListener((v,b)->bias=b);c.addView(weighting);c.addView(text(t("只调整出场顺序，所有选中的词都会听到。","Changes the order. Every selected word is included."),12,MUTED));
        Button start=button(t("开始听写","Start listening"),()->{
            if(guardActive())return;List<Engine.Word> chosen=new ArrayList<>();for(Engine.Word w:words)if(selected.contains(w.id))chosen.add(w);
            if(chosen.isEmpty())return;
            prepareSession(chosen);
        },true);start.setEnabled(!selected.isEmpty());start.setAlpha(selected.isEmpty()?.4f:1);c.addView(start);
        c.addView(text(t("开始前下载所选词的发音，完成后可离线听写。首次下载需联网，词条会发送到有道发音服务。","Selected pronunciations download before starting and then work offline. The first download needs internet and sends the words to Youdao."),12,MUTED));
        section(t("勾选词条","Choose words"));int end=Math.min(words.size(),pages*50);
        for(int i=0;i<end;i++){Engine.Word w=words.get(i);CheckBox box=check(w.order+"  "+w.text,selected.contains(w.id));box.setOnCheckedChangeListener((v,b)->{if(b)selected.add(w.id);else selected.remove(w.id);scope="selected";count.setText(selected.size()+t(" 个词已选择"," words selected"));start.setEnabled(!selected.isEmpty());start.setAlpha(selected.isEmpty()?.4f:1);});body.addView(box);}
        if(end<words.size())body.addView(button(t("再显示 50 个","Show 50 more"),()->{pages++;render();},false));
        if(words.isEmpty())small(t("这个词本还是空的，请先导入单词。","This lesson is empty. Import some words first."));
    }
    private void range(List<Engine.Word> words) {LinearLayout c=column();c.setPadding(dp(22),dp(10),dp(22),0);EditText from=input(t("起始序号","From number"),"1",false),to=input(t("结束序号","To number"),""+Math.min(80,words.size()),false);from.setInputType(2);to.setInputType(2);c.addView(from);gap(c,8);c.addView(to);new AlertDialog.Builder(this).setTitle(t("按原文序号选词","Select by original number")).setView(c).setNegativeButton(t("取消","Cancel"),null).setPositiveButton(t("选择","Select"),(d,w)->{int a=parse(from,1),b=parse(to,80);selected.clear();for(Engine.Word x:words)if(x.order>=a&&x.order<=b)selected.add(x.id);scope="selected";render();}).show();}
    private void importPage() {
        heading(t("给词本添一页","GROW YOUR WORD COLLECTION"),t("导入单词","Import words"),t("保持大小写与词形。重复词会自动跳过。","Case and word forms are kept. Exact duplicates are skipped."));
        LinearLayout c=card(body);c.addView(title(t("从文件导入","Import a file"),19));c.addView(text(t("支持 .docx / .txt · 不支持旧版 .doc","Supports .docx / .txt · Legacy .doc is not supported"),13,MUTED));c.addView(button(t("选择 Word 或 TXT 文件","Choose Word or TXT file"),()->{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("*/*");intent.addCategory(Intent.CATEGORY_OPENABLE);intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/vnd.openxmlformats-officedocument.wordprocessingml.document","text/plain","application/octet-stream"});startActivityForResult(intent,10);},true));
        section(t("或直接粘贴","Or paste your list"));EditText paste=input(t("每行一个词或词组\n例如：\napple\nlook forward to","One word or phrase per line\nFor example:\napple\nlook forward to"),"",true);paste.setMinLines(8);body.addView(paste);body.addView(button(t("预览词条","Preview words"),()->{List<String> parsed=WordImport.split(paste.getText().toString());if(parsed.isEmpty()){toast(t("还没有可导入的词条。","No words to import yet."));return;}importText=parsed;excluded.clear();previewPage=1;importSource="paste";navigate("preview");},true));
        gap(body,16);small(t("文件先预览、后确认。不会直接写入词表。最大文件 16 MB。","Preview before saving. Files are limited to 16 MB."));
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);if(request!=10||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();String filename="";try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())filename=c.getString(0);}catch(Exception ignored){}
        final String name=filename;String ext=name.toLowerCase(Locale.ROOT);if(!ext.endsWith(".docx")&&!ext.endsWith(".txt")){toast(t("请选择 .docx 或 .txt 文件。","Please choose a .docx or .txt file."));return;}
        ProgressDialog progress=ProgressDialog.show(this,t("正在读取词表","Reading word list"),t("只提取正文文字…","Extracting document text…"),true,false);
        io.execute(()->{try(InputStream in=getContentResolver().openInputStream(uri)){
            byte[] bytes=WordImport.read(in);WordImport.Decoded decoded=null;String content;
            if(ext.endsWith(".docx"))content=WordImport.docx(new ByteArrayInputStream(bytes));else{decoded=WordImport.text(bytes);content=decoded.text;}
            List<String> parsed=WordImport.split(content);final boolean gbk=decoded!=null&&decoded.gbk;
            runOnUiThread(()->{if(isFinishing())return;progress.dismiss();if(parsed.isEmpty()){toast(t("文档没有可导入的正文词条。","No word entries were found in the document body."));return;}importText=parsed;excluded.clear();importSource=name;previewPage=1;navigate("preview");if(gbk)toast(t("文件不是 UTF-8，已使用 GBK 解码。","Not UTF-8. The file was decoded using GBK."));});
        }catch(Exception e){runOnUiThread(()->{if(isFinishing())return;progress.dismiss();toast(t("无法读取文件。请确认格式正确，未加密，且小于 16 MB。","Cannot read this file. Use an unencrypted DOCX or TXT file smaller than 16 MB."));});}});
    }
    private List<String> previewStates() {
        List<String> out=new ArrayList<>();Set<String> seen=db.keys(lessonId);
        for(int i=0;i<importText.size();i++){String s=WordImport.clean(importText.get(i));out.add(excluded.contains(i)?"excluded":s.isEmpty()?"empty":seen.add(s)?"new":"duplicate");}return out;
    }
    private void preview() {
        List<String> states=previewStates();int fresh=0,dupes=0;for(String s:states){if(s.equals("new"))fresh++;if(s.equals("duplicate"))dupes++;}
        heading(t("确认之后，再放进词本","PREVIEW BEFORE SAVING"),t("检查一下词表","A look at your words"),fresh+t(" 个新词 · "," new words · ")+dupes+t(" 个重复项（自动跳过）"," duplicates (automatically skipped)"));
        Button commit=button(t("确认导入 ","Import ")+fresh+t(" 个词"," words"),()->{List<String> candidates=new ArrayList<>();for(int i=0;i<importText.size();i++)if(!excluded.contains(i))candidates.add(importText.get(i));int[] n=db.importWords(lessonId,candidates,importSource);toast(t("已导入 ","Imported ")+n[0]+t(" 个，跳过重复 ",", skipped duplicates ")+n[1]+t(" 个，无效 ",", invalid ")+n[2]);tab="words";pages=1;navigate("lesson");},true);commit.setEnabled(fresh>0);body.addView(commit);
        small(t("点“编辑”可修正内容；取消勾选可排除词条。","Edit entries or uncheck them to exclude them."));
        int end=Math.min(importText.size(),previewPage*50);for(int i=0;i<end;i++) {
            final int index=i;LinearLayout c=card(body);CheckBox include=check(importText.get(i),!excluded.contains(i));include.setOnCheckedChangeListener((v,b)->{if(b)excluded.remove(index);else excluded.add(index);render();});c.addView(include);
            String s=states.get(i);c.addView(text(s.equals("duplicate")?t("重复 · 将自动跳过","Duplicate · will be skipped"):s.equals("excluded")?t("不导入","Excluded"):s.equals("empty")?t("空条目","Empty entry"):t("新词条","New entry"),12,s.equals("duplicate")?RED:GREEN));
            if(importText.get(i).length()>60)c.addView(text(t("词条超过 60 字符，请确认是否需要拆分。","Over 60 characters. Consider splitting this entry."),12,RED));
            c.addView(button(t("编辑","Edit"),()->prompt(t("编辑词条","Edit entry"),importText.get(index),value->{importText.set(index,WordImport.clean(value));render();}),false));
        }
        if(end<importText.size())body.addView(button(t("再显示 50 个","Show 50 more"),()->{previewPage++;render();},false));
    }
    private void listen() {
        Store.Row s=db.session(sessionId);if(!s.s("status").equals("in_progress")){navigate("home");return;}
        List<Store.Row> items=db.items(sessionId);int current=Math.min(s.i("current_seq"),items.size()-1);if(current<0)return;Store.Row item=items.get(current);db.visit(sessionId,current);
        if(db.flag("screen",true))getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        heading(t("专心听，写在纸上","LISTEN CLOSELY. WRITE ON PAPER."),s.s("lesson_name"),t("不用着急，按自己的节奏来。","Take your time. This is your pace."));
        ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setProgressDrawable(getDrawable(R.drawable.listening_progress));progress.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));progress.setMax(items.size());progress.setProgress(current+1);progress.setProgressTintList(ColorStateList.valueOf(GREEN));progress.setBackground(bg(LINE,8));progress.setClipToOutline(true);body.addView(progress,new LinearLayout.LayoutParams(-1,dp(6)));gap(body,24);
        TextView number=title(String.format(Locale.US,"%02d",current+1),76);number.setGravity(Gravity.CENTER);body.addView(number);TextView total=text("/ "+items.size()+t(" 个词"," words"),16,MUTED);total.setGravity(Gravity.CENTER);body.addView(total);gap(body,24);
        LinearLayout stage=card(body);ImageView waves=image(R.drawable.listening_illustration,200,112);LinearLayout.LayoutParams wavesLayout=new LinearLayout.LayoutParams(dp(200),dp(112));wavesLayout.gravity=Gravity.CENTER_HORIZONTAL;stage.addView(waves,wavesLayout);gap(stage,12);TextView instruction=text(t("听到单词后，在纸上写下答案。","Listen to the word and write it on paper."),14,MUTED);instruction.setGravity(Gravity.CENTER);stage.addView(instruction);
        if(item.i("skipped")==1){stage.addView(text(t("这题已跳过，不计入统计。","Skipped · excluded from statistics."),13,RED));stage.addView(button(t("恢复此题并重听","Include this word & replay"),()->{db.skip(item.l("id"),false);stopSpeech();render();},false));}
        playButton=button(t("▶  再听一次","▶  Listen again"),()->speak(item.s("word_text"),true),true);stage.addView(playButton);
        actions(body,button(t("上一题","Previous"),()->{if(current>0){db.visit(sessionId,current-1);stopSpeech();render();}},false),button(current==items.size()-1?t("去对改","Check answers"):t("下一题 →","Next word →"),()->{if(current==items.size()-1)endListening();else{db.visit(sessionId,current+1);stopSpeech();render();}},true));
        body.addView(button(t("跳过这题","Skip this word"),()->{db.skip(item.l("id"),true);if(current==items.size()-1)endListening();else{db.visit(sessionId,current+1);stopSpeech();render();}},false));
        body.addView(button(t("提前结束","Finish early"),this::endListening,false));gap(body,20);small(t("答案与释义会在结束后揭晓。跳过的词不会计入统计。","Answers appear after listening. Skipped words do not count."));
        final int token=renderId,initialSpeech=speechId;handler.postDelayed(()->{if(token==renderId&&initialSpeech==speechId&&foreground&&page.equals("listen"))speak(item.s("word_text"),true);},350);
    }
    private void endListening() {
        List<Store.Row> items=db.items(sessionId);int unvisited=0;for(Store.Row i:items)if(i.i("heard")==0)unvisited++;
        Runnable end=()->{stopSpeech();db.grade(sessionId);gradingPage=1;navigate("grading");};
        if(unvisited>0)confirm(t("结束听写，开始对改？","Finish listening and check?"),unvisited+t(" 道未听到的题会按跳过处理，不计统计。"," unvisited words will be skipped and excluded from statistics."),end);else end.run();
    }
    private void prepareSession(List<Engine.Word> chosen) {
        stopSpeech();final int token=speechId;final String accent=db.getSetting("accent","US");
        final boolean mode=random,priority=bias;final long lesson=lessonId;final String selection=scope;
        final AudioCache.Cancellation cancellation=new AudioCache.Cancellation();audioCancellation=cancellation;
        ProgressDialog progress=new ProgressDialog(this);audioProgress=progress;progress.setTitle(t("准备听写发音","Preparing pronunciations"));
        progress.setMessage(t("首次下载需联网；已缓存的词会直接使用。","First downloads need internet. Cached words are reused."));
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);progress.setMax(chosen.size());progress.setCancelable(true);progress.setCanceledOnTouchOutside(false);
        progress.setButton(DialogInterface.BUTTON_NEGATIVE,t("取消","Cancel"),(d,w)->stopSpeech());progress.setOnCancelListener(d->stopSpeech());progress.show();
        audioTask=audioIo.submit(()->{
            try {
                for(int i=0;i<chosen.size();i++) {
                    cancellation.check();audioCache.get(chosen.get(i).text,accent,cancellation);final int done=i+1;
                    handler.post(()->{if(token==speechId&&foreground)progress.setProgress(done);});
                }
                handler.post(()->{if(token!=speechId||!foreground||!page.equals("setup"))return;audioTask=null;audioCancellation=null;audioProgress=null;progress.dismiss();safe(()->{db.setting("random",mode?1:0);sessionId=db.start(lesson,chosen,mode,priority,selection);navigate("listen");});});
            }catch(IOException e){handler.post(()->{if(token!=speechId||!foreground)return;stopSpeech();new AlertDialog.Builder(this).setTitle(t("发音尚未准备完成","Pronunciations are not ready")).setMessage(t("请检查网络后重试，或检查词条拼写。已下载的发音会保留；本次尚未创建听写记录。","Check your connection and word spelling, then retry. Completed downloads are kept. No session has been created."))
                .setNegativeButton(t("返回选词","Back to selection"),null).setPositiveButton(t("重试","Retry"),(d,w)->prepareSession(chosen)).show();});}
        });
    }
    private int speechRemaining=0;
    private void releasePlayer() {if(player!=null){player.setOnCompletionListener(null);player.setOnErrorListener(null);player.setOnPreparedListener(null);player.release();player=null;}}
    private void stopSpeech() {
        speechId++;speechRemaining=0;if(audioCancellation!=null){audioCancellation.cancel();audioCancellation=null;}if(audioTask!=null){audioTask.cancel(true);audioTask=null;}
        if(audioProgress!=null){audioProgress.dismiss();audioProgress=null;}releasePlayer();if(audioManager!=null)audioManager.abandonAudioFocus(audioFocus);
        if(playButton!=null)playButton.setText(t("再听一次","Listen again"));
    }
    private void audioFailed(int token) {
        if(token!=speechId||!foreground)return;stopSpeech();
        if(playButton!=null)playButton.setText(t("播放失败 · 点击重试","Playback failed · Tap to retry"));
        toast(t("暂时无法播放发音。请检查网络和媒体音量后重试；已缓存的发音可离线使用。","Cannot play pronunciation. Check your connection and media volume, then retry. Cached audio works offline."));
    }
    private void speak(String word,boolean sequence) {
        stopSpeech();if(!foreground)return;final int token=speechId;final String accent=db.getSetting("accent","US");speechRemaining=sequence?db.number("plays",1):1;
        final AudioCache.Cancellation cancellation=new AudioCache.Cancellation();audioCancellation=cancellation;
        if(playButton!=null)playButton.setText(t("正在准备发音…","Loading pronunciation…"));
        audioTask=audioIo.submit(()->{try{File file=audioCache.get(word,accent,cancellation);handler.post(()->{if(token!=speechId||!foreground)return;audioTask=null;audioCancellation=null;utterance(token,file,word,accent);});}catch(IOException e){handler.post(()->audioFailed(token));}});
    }
    private void utterance(int token,File file,String word,String accent) {
        if(token!=speechId||!foreground||speechRemaining<=0)return;
        try {
            releasePlayer();MediaPlayer current=new MediaPlayer();player=current;
            current.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());current.setDataSource(file.getAbsolutePath());
            current.setOnErrorListener((mp,what,extra)->{if(token==speechId&&player==mp){audioCache.discard(word,accent);audioFailed(token);}return true;});
            current.setOnPreparedListener(mp->{if(token!=speechId||!foreground||player!=mp)return;try{
                if(audioManager.requestAudioFocus(audioFocus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){audioFailed(token);return;}
                mp.setPlaybackParams(new PlaybackParams().setSpeed(db.number("rate",90)/100f).setPitch(1f));mp.start();if(playButton!=null)playButton.setText(t("正在播放…","Playing…"));
            }catch(RuntimeException e){audioFailed(token);}});
            current.setOnCompletionListener(mp->{if(token==speechId&&player==mp){releasePlayer();utteranceDone(token,file,word,accent);}});current.prepareAsync();
        }catch(IOException|RuntimeException e){audioFailed(token);}
    }
    private void utteranceDone(int token,File file,String word,String accent) {
        if(token!=speechId||!foreground)return;speechRemaining--;
        if(speechRemaining>0){if(playButton!=null)playButton.setText(t("等待重复播放…","Waiting to repeat…"));handler.postDelayed(()->utterance(token,file,word,accent),db.number("gap",2)*1000L);return;}
        audioManager.abandonAudioFocus(audioFocus);
        if(playButton!=null)playButton.setText(t("再听一次","Listen again"));
        if(page.equals("listen")&&db.flag("autonext",false))handler.postDelayed(()->{if(token!=speechId||!page.equals("listen")||!foreground)return;Store.Row s=db.session(sessionId);List<Store.Row> items=db.items(sessionId);int n=s.i("current_seq");if(n+1<items.size()){db.visit(sessionId,n+1);stopSpeech();render();}else toast(t("已听完全部词，请点击“去对改”。","All words played. Tap Check answers when ready."));},db.number("wait",10)*1000L);
    }
    private void grading() {
        Store.Row s=db.session(sessionId);if(!s.s("status").equals("grading")&&!s.s("status").equals("completed")){navigate("home");return;}List<Store.Row> items=db.items(sessionId);int wrong=0,skipped=0;for(Store.Row r:items){wrong+=r.i("wrong");skipped+=r.i("skipped");}
        heading(t("对照纸上的答案","CHECK YOUR NOTEBOOK"),s.s("status").equals("completed")?t("每次练习，都算数。","Every practice counts."):t("只标出写错的词。","Mark the words you missed."),s.s("lesson_name")+" · "+date(s.l("started_at")));
        LinearLayout summary=card(body);summary.addView(title(percent(Engine.accuracy(items.size(),wrong,skipped)),32));summary.addView(text(t("正确率 · 排除跳过题","Accuracy · excludes skipped words"),12,MUTED));summary.addView(text(items.size()+t(" 题 · 标错 "," words · ")+wrong+t(" · 跳过 "," wrong · ")+skipped+t(" · 未标错 "," skipped · ")+(items.size()-skipped-wrong)+t(""," unmarked"),13,INK));
        gap(summary,8);summary.addView(text(t("未标错、未跳过的题目在完成对改后视为正确。请核对每一题。标记即时保存；历史中仍可补标或撤销。","Unmarked, unskipped words count as correct when checking is finished. Check every answer. Marks save immediately and can be edited in history."),13,RED));
        if(s.s("status").equals("grading"))summary.addView(button(t("完成对改","Finish checking"),()->confirm(t("已检查每一题？","Have you checked every answer?"),t("未标错的答案将计为正确，并更新复习计划。","Unmarked answers will count as correct and update review schedules."),()->{db.complete(sessionId);tab="stats";navigate("lesson");}),true));
        else summary.addView(button(t("返回本框统计","Back to lesson insights"),()->{tab="stats";navigate("lesson");},true));
        int end=Math.min(items.size(),gradingPage*50);for(int i=0;i<end;i++) {
            Store.Row item=items.get(i);LinearLayout c=card(body);LinearLayout r=row();r.addView(title((i+1)+". "+item.s("word_text"),20),new LinearLayout.LayoutParams(0,-2,1));r.addView(button("▶",()->speak(item.s("word_text"),false),false),new LinearLayout.LayoutParams(dp(50),dp(42)));c.addView(r);
            if(!item.s("zone").equals("normal"))c.addView(text(item.s("zone").equals("priority")?t("错词优先 · 本次得分 ","Mistake priority · score ")+String.format(Locale.US,"%.2f",item.d("score")):t("到期复习","Due review"),12,GREEN));
            translation(c,item.s("word_text"));
            if(item.i("skipped")==1)c.addView(text(t("已跳过 · 不计统计或复习","Skipped · no statistics or review"),13,MUTED));
            else{CheckBox mark=check(t("标错","Mark wrong"),item.i("wrong")==1);mark.setTextColor(RED);mark.setButtonTintList(ColorStateList.valueOf(RED));mark.setOnCheckedChangeListener((v,b)->{safe(()->db.mark(item.l("id"),b));int y=((ScrollView)body.getParent()).getScrollY();render();handler.post(()->((ScrollView)body.getParent()).scrollTo(0,y));});c.addView(mark);}
        }
        if(end<items.size())body.addView(button(t("再显示 50 题","Show 50 more answers"),()->{gradingPage++;render();},false));
    }
    private void translation(LinearLayout parent,String word) {
        Store.Row cached=db.translation(word);TextView meaning=text(cached.isEmpty()?t("暂无释义","No definition yet"):cached.s("meaning"),14,MUTED);gap(parent,8);parent.addView(meaning);
        Button retry=button(t("获取 / 重试释义","Get / retry definition"),()->loadDefinition(word,meaning,null),false);parent.addView(retry);
        if(!cached.isEmpty()){retry.setVisibility(View.GONE);return;}
        if(db.flag("online",false)) {retry.setVisibility(View.GONE);loadDefinition(word,meaning,retry);}
    }
    private void loadDefinition(String word,TextView view,Button retry) {
        if(!db.flag("online",false)){confirm(t("启用在线词典？","Enable online dictionary?"),t("仅在查释义时将词条发送至有道公开词典。听写记录与标记留在本机。该免费公开接口可能不可用；失败不影响对改。","Lookup sends the word to Youdao's public dictionary. Sessions and marks stay on this device. The free public endpoint may be unavailable; checking still works."),()->{db.setting("online",1);loadDefinition(word,view,retry);});return;}
        if(page.equals("listen"))return;view.setText(t("正在查询…","Looking up…"));final int token=renderId;
        translations.get(word,value->runOnUiThread(()->{if(isFinishing()||token!=renderId||page.equals("listen"))return;if(value.isEmpty()){view.setText(t("暂无释义 · 可重试","No definition · retry available"));if(retry!=null)retry.setVisibility(View.VISIBLE);}else{view.setText(value.s("meaning"));if(retry!=null)retry.setVisibility(View.GONE);}}));
    }
    private void stats() {
        List<Engine.Word> words=db.words(lessonId);List<Store.Row> history=db.history(lessonId);List<Store.Row> complete=new ArrayList<>();int errors=0,due=0;for(Engine.Word w:words){if(w.stat.wrong>0)errors++;if(Engine.due(w.stat,System.currentTimeMillis()))due++;}double sum=0;int rated=0;for(Store.Row s:history)if(s.s("status").equals("completed")){complete.add(s);double p=Engine.accuracy(s.i("total"),s.i("wrong"),s.i("skipped"));if(!Double.isNaN(p)){sum+=p;rated++;}}
        LinearLayout c=card(body);c.addView(title(t("这个词本的足迹","Your lesson, in numbers"),20));gap(c,14);c.addView(text(words.size()+t(" 个词  /  "," words  /  ")+complete.size()+t(" 次完成听写"," completed sessions"),17,INK));gap(c,10);c.addView(text(errors+t(" 个错词  /  "," words with mistakes  /  ")+due+t(" 个今日待复习"," due for review"),15,GREEN));gap(c,10);c.addView(text(t("平均正确率  ","Average accuracy  ")+percent(rated==0?Double.NaN:sum/rated),17,INK));c.addView(text(t("最近听写  ","Last session  ")+(history.isEmpty()?"—":date(history.get(0).l("started_at"))),12,MUTED));
        section(t("最近 10 次 · 正确率","Last 10 sessions · accuracy"));body.addView(text(t("绿：正确率  ·  赭：错词数（独立刻度）","Green: accuracy · Clay: wrong answers (separate scale)"),12,MUTED));
        List<Store.Row> recent=new ArrayList<>(complete.subList(0,Math.min(10,complete.size())));Collections.reverse(recent);Trend trend=new Trend(this,recent);body.addView(trend,new LinearLayout.LayoutParams(-1,dp(190)));
        if(recent.isEmpty())small(t("完成第一次听写后，这里会留下你的进步轨迹。","Your progress starts here after your first completed session."));
        section(t("最需要再见面的词 · TOP 20","Words to revisit · TOP 20"));List<Engine.Word> ranked=new ArrayList<>(words);ranked.sort((a,b)->Integer.compare(b.stat.wrong,a.stat.wrong));int n=0;for(Engine.Word w:ranked){if(w.stat.wrong==0||n++>=20)break;LinearLayout r=card(body);r.addView(title(w.text+"   "+w.stat.wrong+t(" 次"," errors"),17));r.addView(button(t("查看最近相关批次","Open latest related session"),()->{Store.Row s=db.one("SELECT s.id FROM sessions s JOIN items i ON i.session_id=s.id WHERE i.word_id=? AND s.status='completed' ORDER BY s.started_at DESC LIMIT 1",w.id);if(!s.isEmpty()){sessionId=s.l("id");gradingPage=1;navigate("grading");}},false));}
    }
    private class Trend extends View {
        List<Store.Row> data;Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Trend(Context c,List<Store.Row> data){super(c);this.data=data;setContentDescription(t("最近十次听写正确率和错题数趋势","Accuracy and error trends for the last ten sessions"));}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float left=dp(36),right=getWidth()-dp(30),top=dp(20),bottom=getHeight()-dp(28);p.setTextSize(dp(10));for(int i=0;i<=2;i++){float y=top+(bottom-top)*i/2;p.setColor(LINE);p.setStrokeWidth(dp(1));canvas.drawLine(left,y,right,y,p);p.setColor(MUTED);canvas.drawText(""+(100-i*50),0,y+dp(4),p);}int max=1;for(Store.Row r:data)max=Math.max(max,r.i("wrong"));p.setColor(RED);canvas.drawText(""+max,right+dp(6),top+dp(4),p);canvas.drawText("0",right+dp(6),bottom+dp(4),p);for(int series=0;series<2;series++){float prevX=0,prevY=0;boolean valid=false;p.setColor(series==0?GREEN:RED);p.setStrokeWidth(dp(2));for(int i=0;i<data.size();i++){Store.Row r=data.get(i);double accuracy=Engine.accuracy(r.i("total"),r.i("wrong"),r.i("skipped"));if(series==0&&Double.isNaN(accuracy)){valid=false;continue;}float value=series==0?(float)accuracy:(float)r.i("wrong")/max*100;float x=data.size()==1?(left+right)/2:left+(right-left)*i/(data.size()-1);float y=bottom-(bottom-top)*value/100;if(valid)canvas.drawLine(prevX,prevY,x,y,p);canvas.drawCircle(x,y,dp(3),p);prevX=x;prevY=y;valid=true;}}p.setColor(MUTED);canvas.drawText(t("较早","Earlier"),left,getHeight()-dp(6),p);canvas.drawText(t("最近","Latest"),right-dp(30),getHeight()-dp(6),p);}
    }
    private void history() {
        List<Store.Row> history=db.history(lessonId);if(history.isEmpty())small(t("还没有听写记录。第一次，就从今天开始。","No sessions yet. Today is a good day to start."));
        for(Store.Row s:history){LinearLayout c=card(body);c.addView(title(date(s.l("started_at")),19));String status=s.s("status").equals("completed")?t("已完成","Completed"):s.s("status").equals("abandoned")?t("已放弃","Abandoned"):t("未完成","Unfinished");c.addView(text(status+" · "+s.i("total")+t(" 题 · "," words · ")+(s.s("mode").equals("random")?t("随机","Random"):t("有序","Ordered"))+(s.i("bias")==1?t(" · 偏向开启"," · Prioritized"):""),13,MUTED));
            if(s.s("status").equals("completed")){c.addView(text(percent(Engine.accuracy(s.i("total"),s.i("wrong"),s.i("skipped")))+" · "+s.i("wrong")+t(" 错 · "," wrong · ")+s.i("skipped")+t(" 跳过"," skipped"),15,GREEN));long duration=Math.max(0,(s.l("event_at")-s.l("started_at"))/1000);c.addView(text(t("听写用时 ","Listening time ")+duration/60+"m "+duration%60+"s",12,MUTED));c.addView(button(t("查看 / 补标 / 撤销","View / edit marks"),()->{sessionId=s.l("id");gradingPage=1;navigate("grading");},false));}
        }
    }
    private void settings() {
        heading(t("按你的习惯来","MAKE IT YOURS"),t("听写设置","Your preferences"),t("设置即时保存，下次听写时生效。","Saved instantly. Applied to your next session."));
        section(t("界面语言","Interface language"));String current=db.getSetting("language","system");settingChoice(t("语言","Language"),new String[]{t("跟随系统","System"),"简体中文","English"},new String[]{"system","zh","en"},current,value->{db.setting("language",value);readLanguage();render();});
        section(t("发音与节奏","Voice & pace"));small(t("使用在线单词音频，无需系统语音引擎。首次获取需联网，发音会保存在本机，同一词的美音、英音分别缓存。","Uses online word audio without a system voice engine. First downloads need internet. American and British audio are cached separately on this device."));
        body.addView(button(t("▶  试听发音（apple）","▶  Test pronunciation (apple)"),()->speak("apple",false),false));
        settingChoice(t("口音","Accent"),new String[]{t("美音","American English"),t("英音","British English")},new String[]{"US","GB"},db.getSetting("accent","US"),v->db.setting("accent",v));
        settingChoice(t("语速","Speech rate"),new String[]{"0.6×","0.8×","0.9×","1.0×","1.2×"},new String[]{"60","80","90","100","120"},db.getSetting("rate","90"),v->db.setting("rate",v));
        settingChoice(t("每题播放次数","Plays per word"),new String[]{"1","2","3"},new String[]{"1","2","3"},db.getSetting("plays","1"),v->db.setting("plays",v));
        settingChoice(t("重复播放间隔（秒）","Repeat gap (seconds)"),new String[]{"0","1","2","3","5"},new String[]{"0","1","2","3","5"},db.getSetting("gap","2"),v->db.setting("gap",v));
        toggle(t("自动下一题","Automatically advance"),"autonext",false);
        settingChoice(t("自动下一题等待（秒）","Wait before next word (seconds)"),new String[]{"5","10","15","20","30"},new String[]{"5","10","15","20","30"},db.getSetting("wait","10"),v->db.setting("wait",v));
        toggle(t("听写时保持屏幕常亮","Keep screen on while listening"),"screen",true);
        section(t("记忆与复习","Memory & review"));CheckBox cb=check(t("默认错词与到期词优先","Prioritize mistakes & due words by default"),db.flag("bias",true));cb.setOnCheckedChangeListener((v,b)->{db.setting("bias",b?1:0);bias=b;});body.addView(cb);toggle(t("遗忘曲线到期词优先","Prioritize due reviews"),"review",true);small(t("连续答对使错词优先分每次减半。复习间隔：1 / 2 / 4 / 7 / 15 / 30 天。历史错误次数仍保留。","Each correct streak step halves error priority. Review intervals: 1 / 2 / 4 / 7 / 15 / 30 days. Historical error counts are kept."));
        section(t("释义与隐私","Definitions & privacy"));body.addView(button(db.flag("online",false)?t("在线词典：已启用（点此关闭）","Online dictionary: on (tap to disable)"):t("启用在线英汉词典","Enable online English–Chinese dictionary"),()->{if(db.flag("online",false)){db.setting("online",0);render();}else confirm(t("启用在线词典","Enable online dictionary"),t("查询的单词会发送到有道公开词典；免费服务可能不可用。听写记录与标记保存在本地。","Words you look up are sent to Youdao's public dictionary. This free service may be unavailable. Sessions and marks stay on this device."),()->{db.setting("online",1);render();});},false));small(t("发音下载和释义查询会把相应词条发送到有道公开服务；释义开关只控制释义查询。发音与释义缓存保存在本机。已下载发音的词可离线听写，导入、标错和复习不需联网。卸载应用会删除本地数据。","Pronunciation downloads and definition lookups send the requested words to Youdao's public services. The dictionary switch controls definitions only. Audio and definitions are cached locally. Downloaded words work offline; import, marks and review need no internet. Uninstalling deletes local data."));gap(body,24);small("Ink & Echo  1.0.3");
    }
    private void toggle(String label,String key,boolean def) {CheckBox c=check(label,db.flag(key,def));c.setOnCheckedChangeListener((v,b)->db.setting(key,b?1:0));body.addView(c);}
    private void settingChoice(String label,String[] labels,String[] values,String value,java.util.function.Consumer<String> done) {
        LinearLayout c=card(body);c.addView(text(label,13,MUTED));int chosen=0;for(int i=0;i<values.length;i++)if(values[i].equals(value))chosen=i;Button b=button(labels[chosen],()->choose(label,labels,i->{done.accept(values[i]);render();}),false);c.addView(b);
    }
}
