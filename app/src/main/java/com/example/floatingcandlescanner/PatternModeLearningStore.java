package com.example.floatingcandlescanner;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Isolated outcome history for the optional Automatic Pattern mode.
 *
 * These samples never calibrate OnlineLearner and are never uploaded as
 * validated community learning. They calibrate only Pattern Mode so the
 * experimental mode can improve without contaminating Safer Mode.
 */
public final class PatternModeLearningStore {
    private static final String PREF="pattern_mode_learning_v1";
    private static final int EXACT_PATTERN_MIN_SAMPLES=12;
    private static final int DIRECTION_MIN_SAMPLES=20;
    private static final int MODE_MIN_SAMPLES=30;
    private final Context context;
    private final SharedPreferences prefs;

    private static final class Pending {
        long createdAt,dueAt;
        int horizon,minutes;
        double entryY;
        String asset,direction,pattern;

        JSONObject json() throws Exception{
            JSONObject o=new JSONObject();
            o.put("t",createdAt);o.put("d",dueAt);o.put("h",horizon);
            o.put("m",minutes);o.put("y",entryY);o.put("a",asset);
            o.put("x",direction);o.put("p",pattern);return o;
        }

        static Pending from(JSONObject o){
            Pending p=new Pending();p.createdAt=o.optLong("t");p.dueAt=o.optLong("d");
            p.horizon=o.optInt("h");p.minutes=o.optInt("m");p.entryY=o.optDouble("y");
            p.asset=o.optString("a","AUTO_CHART");p.direction=o.optString("x","");
            p.pattern=o.optString("p","UNKNOWN");return p;
        }
    }

    public PatternModeLearningStore(Context context){
        this.context=context.getApplicationContext();
        prefs=this.context.getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }

    public synchronized void resolve(long boundary,String asset,int horizon,
                                     int minutes,double currentY){
        if(boundary<=0L || horizon<0 || minutes<=0)return;
        List<Pending> rows=load();
        ArrayList<Pending> keep=new ArrayList<>();
        for(Pending p:rows){
            if(p.dueAt==boundary && p.horizon==horizon && safe(asset).equals(safe(p.asset))){
                double delta=p.entryY-currentY;
                if(Math.abs(delta)>=.0035)appendResolved(p,currentY,delta>0);
            }else if(p.dueAt>boundary)keep.add(p);
        }

        save(keep);
    }

    public synchronized void addPrediction(long boundary,String asset,int horizon,
                                           int minutes,double currentY,SignalResult newSignal){
        if(boundary<=0L || horizon<0 || minutes<=0 || newSignal==null
                || !("BUY".equals(newSignal.label)||"SELL".equals(newSignal.label)))return;
        List<Pending> keep=load();
        Iterator<Pending> it=keep.iterator();
        while(it.hasNext()){
            Pending p=it.next();
            if(p.createdAt==boundary && p.horizon==horizon && safe(asset).equals(safe(p.asset)))it.remove();
        }
        Pending p=new Pending();p.createdAt=boundary;p.dueAt=boundary+minutes*60_000L;
        p.horizon=horizon;p.minutes=minutes;p.entryY=currentY;p.asset=safe(asset);
        p.direction=newSignal.label;p.pattern=setup(newSignal.structure);keep.add(p);
        save(keep);
    }

    private List<Pending> load(){
        ArrayList<Pending> out=new ArrayList<>();
        try{
            JSONArray a=new JSONArray(prefs.getString("pending","[]"));
            for(int i=0;i<a.length();i++){Pending p=Pending.from(a.getJSONObject(i));if(p.dueAt>0)out.add(p);}
        }catch(Exception ignored){}
        return out;
    }

    private void save(List<Pending> rows){
        JSONArray a=new JSONArray();
        try{for(Pending p:rows)a.put(p.json());prefs.edit().putString("pending",a.toString()).commit();}
        catch(Exception ignored){}
    }

    private void appendResolved(Pending p,double exitY,boolean outcomeUp){
        boolean correct=("BUY".equals(p.direction))==outcomeUp;
        String key=safe(p.asset)+"_M"+p.minutes;
        String directionKey=key+"_D_"+safeKey(p.direction);
        String patternKey=directionKey+"_P_"+safeKey(p.pattern);
        SharedPreferences.Editor edit=prefs.edit();
        increment(edit,key,correct);
        increment(edit,directionKey,correct);
        increment(edit,patternKey,correct);
        edit.apply();
        try{
            File dir=new File(context.getFilesDir(),"training");if(!dir.exists())dir.mkdirs();
            File file=new File(dir,"pattern_mode_samples_v1.csv");boolean fresh=!file.exists();
            FileWriter w=new FileWriter(file,true);
            if(fresh)w.write("created_at,due_at,asset,timeframe_minutes,entry_y,exit_y,predicted,outcome,correct,pattern\n");
            w.write(String.format(Locale.US,"%d,%d,%s,%d,%.6f,%.6f,%s,%s,%s,%s\n",
                    p.createdAt,p.dueAt,csv(p.asset),p.minutes,p.entryY,exitY,p.direction,
                    outcomeUp?"UP":"DOWN",correct?"1":"0",csv(p.pattern)));
            w.close();
        }catch(Exception ignored){}
    }

    /**
     * Calibrate Pattern Mode only from its own exact-close outcomes.
     *
     * Exact asset/timeframe/pattern/direction evidence is preferred.  Until it
     * matures, direction-level and then whole-mode evidence may be used with
     * larger sample requirements.  The result can move by at most five points,
     * never flips direction, and the normal 70% gate remains authoritative.
     */
    public synchronized SignalResult apply(String asset,int minutes,SignalResult r){
        if(r==null || !("BUY".equals(r.label)||"SELL".equals(r.label)) || minutes<=0)return r;
        String base=safe(asset)+"_M"+minutes;
        String directionKey=base+"_D_"+safeKey(r.label);
        String patternKey=directionKey+"_P_"+safeKey(setup(r.structure));

        Stat stat=read(patternKey,EXACT_PATTERN_MIN_SAMPLES);
        String scope="PATTERN";
        if(stat==null){stat=read(directionKey,DIRECTION_MIN_SAMPLES);scope="DIRECTION";}
        if(stat==null){stat=read(base,MODE_MIN_SAMPLES);scope="MODE";}
        if(stat==null)return r;

        // Beta(3,3) smoothing keeps a short streak from dominating.  A 60%
        // resolved rate is neutral; weaker evidence can lower a floored 70%
        // signal below the existing trade threshold, while strong evidence has
        // only a small positive influence.
        double rate=(stat.wins+3.0)/(stat.samples+6.0);
        int adjustment=(int)Math.round(clamp((rate-.60)*30.0,-5.0,5.0));
        int lead="BUY".equals(r.label)?r.buyProbability:r.sellProbability;
        int adjusted=(int)Math.round(clamp(lead+adjustment,50,95));
        int bp="BUY".equals(r.label)?adjusted:100-adjusted;
        int sp="SELL".equals(r.label)?adjusted:100-adjusted;
        String note=String.format(Locale.US,"PATTERN LEARNING %s %d%% (%d)",
                scope,Math.round(100.0*stat.wins/stat.samples),stat.samples);
        String explanation=note+(r.explanation==null||r.explanation.isEmpty()
                ?"":" • "+r.explanation);
        return new SignalResult(r.label,adjusted,r.score,bp,sp,r.confidence,
                r.regime,r.rawBuyProbability,r.setupQuality,r.structure,explanation);
    }

    private void increment(SharedPreferences.Editor edit,String key,boolean correct){
        edit.putInt(key+"_n",prefs.getInt(key+"_n",0)+1)
                .putInt(key+"_w",prefs.getInt(key+"_w",0)+(correct?1:0));
    }

    private Stat read(String key,int minimum){
        int n=prefs.getInt(key+"_n",0),w=prefs.getInt(key+"_w",0);
        return n>=minimum?new Stat(n,w):null;
    }

    private static final class Stat{
        final int samples,wins;
        Stat(int samples,int wins){this.samples=samples;this.wins=wins;}
    }

    private static String setup(String value){
        if(value==null||value.trim().isEmpty())return "UNKNOWN";
        String x=value.trim();int cut=x.indexOf(" • ");if(cut>0)x=x.substring(0,cut);
        return x.length()>64?x.substring(0,64):x;
    }
    private static String safe(String value){return value==null||value.trim().isEmpty()?"AUTO_CHART":value.trim().toUpperCase(Locale.US);}
    private static String safeKey(String value){
        String x=value==null?"":value.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9]+","_");
        if(x.isEmpty())x="UNKNOWN";
        return x.length()>72?x.substring(0,72):x;
    }
    private static String csv(String value){return value==null?"":value.replace(',',' ').replace('\n',' ').replace('\r',' ');}
    private static double clamp(double value,double low,double high){return Math.max(low,Math.min(high,value));}
}
