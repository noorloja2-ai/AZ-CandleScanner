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
 * validated community learning. They exist so the experimental mode can be
 * evaluated without contaminating Safe Mode.
 */
public final class PatternModeLearningStore {
    private static final String PREF="pattern_mode_learning_v1";
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

    public synchronized void resolveAndRecord(long boundary,String asset,int horizon,
                                              int minutes,double currentY,SignalResult newSignal){
        if(boundary<=0L || horizon<0 || minutes<=0)return;
        List<Pending> rows=load();
        ArrayList<Pending> keep=new ArrayList<>();
        for(Pending p:rows){
            if(p.dueAt==boundary && p.horizon==horizon && safe(asset).equals(safe(p.asset))){
                double delta=p.entryY-currentY;
                if(Math.abs(delta)>=.0035)appendResolved(p,currentY,delta>0);
            }else if(p.dueAt>boundary)keep.add(p);
        }

        if(newSignal!=null && ("BUY".equals(newSignal.label)||"SELL".equals(newSignal.label))){
            Iterator<Pending> it=keep.iterator();
            while(it.hasNext()){
                Pending p=it.next();
                if(p.createdAt==boundary && p.horizon==horizon && safe(asset).equals(safe(p.asset)))it.remove();
            }
            Pending p=new Pending();p.createdAt=boundary;p.dueAt=boundary+minutes*60_000L;
            p.horizon=horizon;p.minutes=minutes;p.entryY=currentY;p.asset=safe(asset);
            p.direction=newSignal.label;p.pattern=setup(newSignal.structure);keep.add(p);
        }
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
        prefs.edit().putInt(key+"_n",prefs.getInt(key+"_n",0)+1)
                .putInt(key+"_w",prefs.getInt(key+"_w",0)+(correct?1:0)).apply();
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

    private static String setup(String value){
        if(value==null||value.trim().isEmpty())return "UNKNOWN";
        String x=value.trim();int cut=x.indexOf(" • ");if(cut>0)x=x.substring(0,cut);
        return x.length()>64?x.substring(0,64):x;
    }
    private static String safe(String value){return value==null||value.trim().isEmpty()?"AUTO_CHART":value.trim().toUpperCase(Locale.US);}
    private static String csv(String value){return value==null?"":value.replace(',',' ').replace('\n',' ').replace('\r',' ');}
}
