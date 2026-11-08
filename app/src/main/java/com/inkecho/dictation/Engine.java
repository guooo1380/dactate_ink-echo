package com.inkecho.dictation;

import java.util.*;

/** Platform-independent rules. A saved session is the immutable source of its order. */
public final class Engine {
    private Engine() {}
    public static final int[] INTERVALS = {1, 2, 4, 7, 15, 30};
    public static class Stat {
        public int wrong, streak, repetitions, interval;
        public long lastWrong, lastAt, due;
        public double ease = 2.5;
    }
    public static class Word {
        public long id;
        public int order;
        public String text;
        public Stat stat = new Stat();
        public Word(long id, String text, int order) { this.id=id; this.text=text; this.order=order; }
    }
    public static class Outcome {
        public long at;
        public boolean wrong, skipped;
        public Outcome(long at, boolean wrong, boolean skipped) { this.at=at; this.wrong=wrong; this.skipped=skipped; }
    }
    public static class Question {
        public Word word;
        public String zone;
        public double score;
        Question(Word w, String z, double s) { word=w; zone=z; score=s; }
    }
    public static long day(long at) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(at);
        c.set(Calendar.HOUR_OF_DAY,0); c.set(Calendar.MINUTE,0); c.set(Calendar.SECOND,0); c.set(Calendar.MILLISECOND,0);
        return c.getTimeInMillis();
    }
    public static long addDays(long at, int days) {
        Calendar c=Calendar.getInstance(); c.setTimeInMillis(day(at)); c.add(Calendar.DAY_OF_YEAR,days); return c.getTimeInMillis();
    }
    public static boolean due(Stat s, long now) { return s.due>0 && s.due<=day(now); }
    public static double score(Stat s, double gamma) { return s.wrong*Math.pow(gamma,s.streak); }
    /** Replay in session chronology, including unmarked completed answers, never skipped answers. */
    public static Stat replay(List<Outcome> outcomes) {
        Stat s=new Stat();
        for (Outcome o:outcomes) {
            if (o.skipped) continue;
            s.lastAt=o.at;
            if (o.wrong) {
                s.wrong++; s.streak=0; s.repetitions=0; s.interval=1; s.lastWrong=o.at;
            } else {
                s.streak++; s.repetitions++;
                s.interval=INTERVALS[Math.min(s.repetitions-1,INTERVALS.length-1)];
            }
            s.due=addDays(o.at,s.interval);
        }
        return s;
    }
    public static List<Question> plan(List<Word> words, boolean random, boolean bias, boolean review, double gamma, long seed, long now) {
        List<Word> base=new ArrayList<>(words);
        base.sort(Comparator.comparingInt(w -> w.order));
        if (random) Collections.shuffle(base,new Random(seed));
        List<Question> a=new ArrayList<>(),b=new ArrayList<>(),c=new ArrayList<>();
        for (Word w:base) {
            double score=score(w.stat,gamma);
            if (bias && score>=1) a.add(new Question(w,"priority",score));
            else if (bias && review && due(w.stat,now)) b.add(new Question(w,"review",score));
            else c.add(new Question(w,"normal",score));
        }
        // Stable sorting keeps the seeded/base order as the final tie breaker.
        a.sort((x,y) -> { int n=Double.compare(y.score,x.score); return n!=0?n:Long.compare(y.word.stat.lastWrong,x.word.stat.lastWrong); });
        b.sort(Comparator.comparingLong(q -> q.word.stat.due));
        a.addAll(b); a.addAll(c); return a;
    }
    public static double accuracy(int total, int wrong, int skipped) {
        int answered=total-skipped; return answered==0?Double.NaN:100.0*(answered-wrong)/answered;
    }
}
