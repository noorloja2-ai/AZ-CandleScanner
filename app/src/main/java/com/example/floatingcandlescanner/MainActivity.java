package com.example.floatingcandlescanner;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

import java.util.Locale;

public class MainActivity extends Activity {
    SharedPreferences prefs;
    TextView status,cropText,sensText,learnText,autoSymbolText;
    EditText marketKeyInput;
    OnlineLearner learner;
    AppUpdateManager updateManager;
    boolean pendingAccessibilityStart=false;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        prefs=getSharedPreferences("scanner",MODE_PRIVATE);
        learner=new OnlineLearner(this);
        learner.setAsset(currentDetectedAsset());

        if(Build.VERSION.SDK_INT>=33
                &&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                !=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},2001);

        ScrollView sc=new ScrollView(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(22),dp(18),dp(28));
        root.setBackgroundColor(Color.rgb(11,18,32));
        sc.addView(root);

        ImageView appLogo=new ImageView(this);
        appLogo.setImageResource(R.mipmap.ic_launcher);
        appLogo.setContentDescription("AZ app logo");
        appLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams logoLp=new LinearLayout.LayoutParams(dp(92),dp(92));
        logoLp.gravity=Gravity.CENTER_HORIZONTAL;
        logoLp.bottomMargin=dp(8);
        root.addView(appLogo,logoLp);

        TextView brand=tx("AZ",18,Color.rgb(34,211,238));
        brand.setTypeface(null,1);
        brand.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(brand);

        TextView title=tx("BUY / SELL Entry Signal v15.2 AZ TAP INFORMATION",22,Color.WHITE);
        title.setTypeface(null,1);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView d=tx(
                "v15.2 AZ Tap Information Edition: tap the floating AZ logo to scan immediately and open a closable information popup showing the chart, timeframe, duration and scanner status. HIGH CHANCE and MOST SURE signals still appear automatically. Screenshots and broker credentials are never stored; no automatic trading.",
                14,Color.rgb(148,163,184));
        d.setPadding(0,dp(8),0,dp(16));
        root.addView(d);

        Button st=btn("START FLOATING TAP SCAN");
        st.setOnClickListener(v->startScan());
        root.addView(st);

        Button stop=btn("Stop scanner");
        stop.setOnClickListener(v->{
            AutoSymbolAccessibilityService.setScannerEnabled(this,false);
            status.setText("Scanner stopped. Accessibility may stay enabled, but chart scanning and BUY/SELL alerts are off.");
        });
        root.addView(stop);

        status=tx("Status: ready",14,Color.rgb(134,239,172));
        status.setPadding(0,dp(12),0,dp(12));
        root.addView(status);

        updateManager=new AppUpdateManager(this,status);
        Button updateBtn=btn("CHECK / UPDATE APP");
        updateBtn.setOnClickListener(v->updateManager.checkForUpdate(true));
        root.addView(updateBtn);

        CheckBox autoUpdateCheck=new CheckBox(this);
        autoUpdateCheck.setText("Automatically check for app updates on startup");
        autoUpdateCheck.setTextColor(Color.rgb(167,243,208));
        autoUpdateCheck.setChecked(prefs.getBoolean("auto_update_check",true));
        autoUpdateCheck.setOnCheckedChangeListener((button,checked)->
                prefs.edit().putBoolean("auto_update_check",checked).apply());
        root.addView(autoUpdateCheck);

        TextView updateNote=tx(
                "App updates are checked from the permanent AZ GitHub update channel. A newer APK can be downloaded automatically, but Android always shows the normal install confirmation. Future APKs use the same permanent signing certificate.",
                12,Color.rgb(251,191,36));
        updateNote.setPadding(0,0,0,dp(12));
        root.addView(updateNote);

        TextView brokerTitle=tx("Broker browser / login",18,Color.WHITE);
        brokerTitle.setTypeface(null,1);
        brokerTitle.setPadding(0,dp(8),0,0);
        root.addView(brokerTitle);

        EditText brokerUrl=new EditText(this);
        brokerUrl.setSingleLine(true);
        brokerUrl.setTextColor(Color.WHITE);
        brokerUrl.setHintTextColor(Color.LTGRAY);
        brokerUrl.setHint("https://broker-site.com");
        brokerUrl.setText(prefs.getString("broker_url","https://pocketoption.com/"));
        root.addView(brokerUrl,new LinearLayout.LayoutParams(-1,dp(52)));

        Button openBroker=btn("Open Broker / Login");
        openBroker.setOnClickListener(v->{
            String url=brokerUrl.getText().toString().trim();
            if(!url.isEmpty()) prefs.edit().putString("broker_url",url).apply();
            startActivity(new Intent(this,BrokerActivity.class));
        });
        root.addView(openBroker);

        TextView brokerNote=tx(
                "Login stays inside the broker webpage/session. The scanner does not read or save your broker username/password. If embedded login is blocked, tap Chrome in the broker screen.",
                12,Color.rgb(251,191,36));
        brokerNote.setPadding(0,0,0,dp(10));
        root.addView(brokerNote);


        TextView assetTitle=tx("Automatic chart + timeframe recognition",18,Color.WHITE);
        assetTitle.setTypeface(null,1);
        root.addView(assetTitle);

        autoSymbolText=tx("Detected chart: checking…",14,Color.rgb(167,243,208));
        autoSymbolText.setPadding(0,dp(4),0,dp(6));
        root.addView(autoSymbolText);

        TextView timeframeLabel=tx("Signal timeframe",14,Color.WHITE);
        root.addView(timeframeLabel);

        Spinner timeframeSpinner=new Spinner(this);
        String[] timeframeValues={"AUTO","M1","M2","M3","M4","M5"};
        String[] timeframeNames={
                "AUTO — follow broker",
                "M1 — 1 minute",
                "M2 — 2 minutes",
                "M3 — 3 minutes",
                "M4 — 4 minutes",
                "M5 — 5 minutes"};
        timeframeSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,timeframeNames));
        String savedTf=prefs.getString("timeframe_mode","AUTO");
        int tfPos=0;
        for(int i=0;i<timeframeValues.length;i++)
            if(timeframeValues[i].equals(savedTf))tfPos=i;
        timeframeSpinner.setSelection(tfPos);
        timeframeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
                prefs.edit().putString("timeframe_mode",timeframeValues[pos]).apply();
                updateAutoSymbol();
            }
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        root.addView(timeframeSpinner);

        TextView timeframeNote=tx(
                "AUTO follows the timeframe exposed by the broker. If a canvas-based broker hides it, AUTO keeps the last detected timeframe; on first use the fallback is M1. Recommended duration: M1=60s, M2=120s, M3=180s, M4=240s, M5=300s. Enter only after the completed-candle signal.",
                12,Color.rgb(251,191,36));
        timeframeNote.setPadding(0,0,0,dp(8));
        root.addView(timeframeNote);

        Button autoDetect=btn("Enable automatic symbol recognition");
        autoDetect.setOnClickListener(v->{
            status.setText("In Accessibility, enable BUY SELL Signal Notifier for automatic pair recognition.");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        root.addView(autoDetect);

        TextView autoNote=tx(
                "No currency selection is required. AUTO can also follow M1–M5 when the broker exposes its timeframe. Accessibility is used to identify the active broker screen and capture frames for candle analysis. Screenshots are analyzed in memory and are not saved. Password fields and full screen text are not stored.",
                12,Color.rgb(251,191,36));
        autoNote.setPadding(0,0,0,dp(8));
        root.addView(autoNote);

        CheckBox brokerBoardLearning=new CheckBox(this);
        brokerBoardLearning.setText("Broker Board Learning (remember validated candle behaviour)");
        brokerBoardLearning.setTextColor(Color.rgb(125,211,252));
        brokerBoardLearning.setChecked(prefs.getBoolean("broker_board_learning",true));
        brokerBoardLearning.setOnCheckedChangeListener((button,checked)->
                prefs.edit().putBoolean("broker_board_learning",checked).apply());
        root.addView(brokerBoardLearning);

        TextView boardLearningNote=tx(
                "When enabled, AZ watches the chart while scanning, converts candle behaviour into numeric pattern states, waits for the exact future candle close, then learns the resolved UP/DOWN result. Only validated outcomes affect later predictions; images and login data are not saved.",
                12,Color.rgb(148,163,184));
        boardLearningNote.setPadding(0,0,0,dp(8));
        root.addView(boardLearningNote);

        CheckBox quickDecision=new CheckBox(this);
        quickDecision.setText("Quick High-Confidence Decision (3 stable live scans)");
        quickDecision.setTextColor(Color.rgb(167,243,208));
        quickDecision.setChecked(prefs.getBoolean("quick_decision",true));
        quickDecision.setOnCheckedChangeListener((button,checked)->
                prefs.edit().putBoolean("quick_decision",checked).apply());
        root.addView(quickDecision);

        TextView quickThresholdText=tx("Quick signal threshold: "+prefs.getInt("quick_decision_threshold",82)+"%",14,Color.WHITE);
        root.addView(quickThresholdText);
        SeekBar quickThreshold=new SeekBar(this);
        quickThreshold.setMax(12); // 78..90
        quickThreshold.setProgress(Math.max(0,Math.min(12,prefs.getInt("quick_decision_threshold",82)-78)));
        quickThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean fromUser){
                int value=78+p;
                quickThresholdText.setText("Quick signal threshold: "+value+"%");
                if(fromUser)prefs.edit().putInt("quick_decision_threshold",value).apply();
            }
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
        });
        root.addView(quickThreshold);

        TextView quickNote=tx(
                "QUICK BUY/SELL is provisional: it requires at least 3 consecutive live scans in the same direction, at least 4 independent confirmations, acceptable volatility and the selected score threshold. If the base model is still WAIT, the quick gate becomes even stricter. Official sound/notification remains tied to the completed-candle signal.",
                12,Color.rgb(251,191,36));
        quickNote.setPadding(0,0,0,dp(8));
        root.addView(quickNote);

        CheckBox highAccuracy=new CheckBox(this);
        highAccuracy.setText("High Accuracy Mode (stricter strong-alert threshold)");
        highAccuracy.setTextColor(Color.WHITE);
        highAccuracy.setChecked(prefs.getBoolean("high_accuracy",true));
        highAccuracy.setOnCheckedChangeListener((button,checked)->
                prefs.edit().putBoolean("high_accuracy",checked).apply());
        root.addView(highAccuracy);

        CheckBox eliteMode=new CheckBox(this);
        eliteMode.setText("Elite Precision Mode (4/4 agreement for strong alerts)");
        eliteMode.setTextColor(Color.rgb(167,243,208));
        eliteMode.setChecked(prefs.getBoolean("elite_mode",true));
        eliteMode.setOnCheckedChangeListener((button,checked)->
                prefs.edit().putBoolean("elite_mode",checked).apply());
        root.addView(eliteMode);

        TextView eliteNote=tx(
                "Elite mode is intentionally selective for strong alerts. Every valid scan still shows one leading BUY or SELL direction with its confidence percentage.",
                12,Color.rgb(251,191,36));
        eliteNote.setPadding(0,0,0,dp(8));
        root.addView(eliteNote);

        CheckBox soundAlerts=new CheckBox(this);
        soundAlerts.setText("Sound + vibration for high-confidence BUY / SELL");
        soundAlerts.setTextColor(Color.rgb(253,230,138));
        soundAlerts.setChecked(prefs.getBoolean("sound_alerts",true));
        soundAlerts.setOnCheckedChangeListener((button,checked)->
                prefs.edit().putBoolean("sound_alerts",checked).apply());
        root.addView(soundAlerts);

        TextView soundThresholdText=tx("Sound alert threshold: "+prefs.getInt("sound_alert_threshold",85)+"%",14,Color.WHITE);
        root.addView(soundThresholdText);
        SeekBar soundThreshold=new SeekBar(this);
        soundThreshold.setMax(15); // 75..90
        soundThreshold.setProgress(Math.max(0,Math.min(15,prefs.getInt("sound_alert_threshold",85)-75)));
        soundThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean fromUser){
                int value=75+p;
                soundThresholdText.setText("Sound alert threshold: "+value+"%");
                if(fromUser)prefs.edit().putInt("sound_alert_threshold",value).apply();
            }
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
        });
        root.addView(soundThreshold);

        TextView soundNote=tx(
                "Default 85%. The bot still analyzes every candle. Sound is sent only for an official completed-candle BUY/SELL signal at or above this level; live 1-second calculations never make the sound alert.",
                12,Color.rgb(251,191,36));
        soundNote.setPadding(0,0,0,dp(8));
        root.addView(soundNote);

        TextView refreshNote=tx(
                "Live AI refresh: every 1 second. v15.1 shows QUICK BUY/SELL only after stable multi-factor confirmation and blocks conflicting, flat, doji, extreme-chase and abnormal-volatility conditions as NO TRADE. The official entry signal and sound remain locked to the completed candle.",
                12,Color.rgb(125,211,252));
        refreshNote.setPadding(0,0,0,dp(8));
        root.addView(refreshNote);


        TextView dataTitle=tx("High Prediction data fusion",18,Color.WHITE);
        dataTitle.setTypeface(null,1);
        dataTitle.setPadding(0,dp(12),0,0);
        root.addView(dataTitle);

        marketKeyInput=new EditText(this);
        marketKeyInput.setSingleLine(true);
        marketKeyInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        marketKeyInput.setTextColor(Color.WHITE);
        marketKeyInput.setHintTextColor(Color.LTGRAY);
        marketKeyInput.setHint("Twelve Data API key (session only)");
        root.addView(marketKeyInput,new LinearLayout.LayoutParams(-1,dp(52)));

        Button setMarketKey=btn("Use Live OHLC Key");
        setMarketKey.setOnClickListener(v->{
            RuntimeSecrets.setMarketDataKey(marketKeyInput.getText().toString());
            marketKeyInput.setText("");
            status.setText(RuntimeSecrets.getMarketDataKey().isEmpty()
                    ?"Live OHLC key not set — visual AI only."
                    :"Live OHLC fusion ready. API key stays only in app memory.");
        });
        root.addView(setMarketKey);

        Button clearMarketKey=btn("Clear Live OHLC Key");
        clearMarketKey.setOnClickListener(v->{
            RuntimeSecrets.clear();
            marketKeyInput.setText("");
            status.setText("Live OHLC key cleared — visual AI only.");
        });
        root.addView(clearMarketKey);

        TextView sessionLabel=tx("Trading session filter",14,Color.WHITE);
        root.addView(sessionLabel);

        Spinner sessionSpinner=new Spinner(this);
        String[] sessions={"ALL","LONDON","NEW_YORK","OVERLAP","ASIA"};
        String[] sessionNames={"All sessions","London","New York","London + New York overlap","Asia"};
        ArrayAdapter<String> sessionAdapter=new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,sessionNames);
        sessionSpinner.setAdapter(sessionAdapter);
        String savedSession=prefs.getString("session_filter","ALL");
        int sessionPos=0;
        for(int i=0;i<sessions.length;i++)if(sessions[i].equals(savedSession))sessionPos=i;
        sessionSpinner.setSelection(sessionPos);
        sessionSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
                prefs.edit().putString("session_filter",sessions[pos]).apply();
            }
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        root.addView(sessionSpinner);

        Button news30=btn("High-impact News Lock: 30 minutes");
        news30.setOnClickListener(v->{
            long until=System.currentTimeMillis()+30L*60L*1000L;
            prefs.edit().putLong("news_lock_until",until).apply();
            status.setText("News Lock active for 30 minutes — BUY/SELL signals blocked.");
        });
        root.addView(news30);

        Button newsClear=btn("Clear News Lock");
        newsClear.setOnClickListener(v->{
            prefs.edit().putLong("news_lock_until",0L).apply();
            status.setText("News Lock cleared.");
        });
        root.addView(newsClear);

        TextView dataNote=tx(
                "Live OHLC uses a standard market-data feed for regular assets. Pocket Option OTC remains visual-only because standard market candles are not the same as broker OTC candles. The API key is not saved in the APK or preferences.",
                12,Color.rgb(251,191,36));
        dataNote.setPadding(0,0,0,dp(10));
        root.addView(dataNote);


        TextView knowledgeTitle=tx("Candlestick + professional chart knowledge",18,Color.WHITE);
        knowledgeTitle.setTypeface(null,1);
        root.addView(knowledgeTitle);

        TextView knowledge=tx(
                CandlestickKnowledgeEngine.CATALOG_SIZE+" main named pattern/shape rules with context: "+
                        CandlestickKnowledgeEngine.catalogSummary()+
                        ". Combined uploaded knowledge also includes HH/HL and LH/LL trend structure, support/resistance role reversal, confirmed/failed breakouts, double/triple tops and bottoms, rectangles, symmetrical/ascending/descending triangles, wedges, flags/pennants, Head & Shoulders, inverse Head & Shoulders, Cup & Handle, Pipe/Two-Bar Reversal, NR4/inside-range breakout, conservative gap-pivot logic, BOS/CHoCH-style confirmation, liquidity sweeps, FVG, EMA 9/21/50/200 context, RSI-14 and Fibonacci 50/61.8/78.6 confluence. Patterns never force a trade by name alone; confirmation and current structure must agree.",
                12,Color.rgb(167,243,208));
        knowledge.setPadding(0,dp(4),0,dp(10));
        root.addView(knowledge);

        TextView learnTitle=tx("Automatic learning status",18,Color.WHITE);
        learnTitle.setTypeface(null,1);
        root.addView(learnTitle);

        learnText=tx("",13,Color.rgb(203,213,225));
        learnText.setPadding(0,dp(6),0,dp(8));
        root.addView(learnText);

        Button reset=btn("Reset learned calibration");
        reset.setOnClickListener(v->new AlertDialog.Builder(this)
                .setTitle("Reset learning?")
                .setMessage("This clears automatic calibration statistics for the currently selected asset only. It does not affect app permissions.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Reset",(dialog,which)->{
                    learner.setAsset(currentDetectedAsset());
                    learner.resetCurrentAsset();
                    new TrainingStore(this).clearPending();
                    updateLearning();
                }).show());
        root.addView(reset);

        TextView communityTitle=tx("Automatic GitHub shared learning",18,Color.WHITE);
        communityTitle.setTypeface(null,1);
        communityTitle.setPadding(0,dp(12),0,0);
        root.addView(communityTitle);

        TextView communityNote=tx(
                "Always on. The app automatically queues resolved numeric learning, sends it only to the protected community gateway when configured, and downloads the latest validated aggregate model from GitHub. Other users receive new shared calibration automatically without installing a new APK. No screenshots, login details, Android identifiers or GitHub write token are sent.",
                12,Color.rgb(251,191,36));
        communityNote.setPadding(0,0,0,dp(6));
        root.addView(communityNote);

        TextView communityAutoStatus=tx(
                "Shared learning: "+CommunityLearningSync.status(this),
                12,Color.rgb(167,243,208));
        communityAutoStatus.setPadding(0,0,0,dp(6));
        root.addView(communityAutoStatus);

        TextView cal=tx("Chart area calibration",18,Color.WHITE);
        cal.setTypeface(null,1);
        cal.setPadding(0,dp(12),0,0);
        root.addView(cal);

        TextView h=tx(
                "If the result cannot see candles, adjust the crop so it covers mostly the chart.",
                13,Color.rgb(148,163,184));
        root.addView(h);

        slider(root,"Left crop %","left",0,30,5);
        slider(root,"Top crop %","top",0,50,18);
        slider(root,"Right crop %","right",70,100,96);
        slider(root,"Bottom crop %","bottom",55,100,80);

        cropText=tx("",13,Color.rgb(203,213,225));
        root.addView(cropText);

        TextView tl=tx("Candle colors",14,Color.WHITE);
        tl.setPadding(0,dp(14),0,dp(5));
        root.addView(tl);

        Spinner sp=new Spinner(this);
        String[] themes={"Auto (green vs red, UI-safe)","Green vs red","Blue vs red"};
        sp.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,themes));
        sp.setSelection(prefs.getInt("theme",0));
        sp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
                prefs.edit().putInt("theme",pos).apply();
            }
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        root.addView(sp);

        TextView sl=tx("AI signal threshold",14,Color.WHITE);
        sl.setPadding(0,dp(14),0,0);
        root.addView(sl);

        SeekBar sb=new SeekBar(this);
        sb.setMax(2);
        sb.setProgress(prefs.getInt("sensitivity",1));
        root.addView(sb);

        sensText=tx("",13,Color.rgb(203,213,225));
        root.addView(sensText);

        sb.setOnSeekBarChangeListener(new SimpleSeek(){
            public void onProgressChanged(SeekBar s,int p,boolean u){
                prefs.edit().putInt("sensitivity",p).apply();
                labels();
            }
        });

        labels();
        updateLearning();

        TextView note=tx(
                "Learning remains automatic on this phone and shared learning sync is always on. Only anonymous resolved numeric rows are queued for server-side validation; local calibration is never replaced. Validated GitHub community knowledge can add only a limited weight to predictions and is refreshed automatically. There are no I WON / I LOST buttons. Displayed BUY/SELL percentages are confidence estimates, not guaranteed probabilities or win rates.",
                13,Color.rgb(251,191,36));
        note.setPadding(0,dp(18),0,0);
        root.addView(note);

        setContentView(sc);
        if(prefs.getBoolean("auto_update_check",true))
            root.postDelayed(()->{ if(updateManager!=null) updateManager.checkForUpdate(false); },900);
    }

    @Override protected void onResume(){
        super.onResume();
        if(learner!=null){
            learner.setAsset(currentDetectedAsset());
            updateLearning();
        }
        updateAutoSymbol();
        CommunityLearningSync.refreshAndFlushAsync(this);
        if(pendingAccessibilityStart && AutoSymbolAccessibilityService.isConnected()){
            pendingAccessibilityStart=false;
            startScan();
        }
    }

    @Override protected void onDestroy(){
        if(updateManager!=null) updateManager.destroy();
        super.onDestroy();
    }

    String currentDetectedAsset(){
        String a=prefs==null?"":prefs.getString("detected_asset","");
        return a==null||a.trim().isEmpty()?"AUTO_CHART":a.trim();
    }

    void updateAutoSymbol(){
        if(autoSymbolText==null)return;
        String a=currentDetectedAsset();
        long t=prefs.getLong("detected_asset_time",0L);
        String mode=prefs.getString("timeframe_mode","AUTO");
        String tf;
        if(!"AUTO".equals(mode)) tf=mode+" manual";
        else{
            String detected=prefs.getString("detected_timeframe","");
            long tt=prefs.getLong("detected_timeframe_time",0L);
            if(!detected.isEmpty() && System.currentTimeMillis()-tt<=30*60_000L){
                tf=detected+" auto";
                prefs.edit().putString("last_valid_timeframe",detected).apply();
            }else{
                String fallback=prefs.getString("last_valid_timeframe","M1");
                if(!fallback.matches("M[1-5]"))fallback="M1";
                tf=fallback+" AUTO fallback";
            }
        }
        if("AUTO_CHART".equals(a))
            autoSymbolText.setText("Detected chart: AUTO visual mode • "+tf);
        else
            autoSymbolText.setText("Detected chart: "+a+(t>0?" • automatic":"")+" • "+tf);
    }

    void updateLearning(){
        if(learnText==null)return;
        StringBuilder s=new StringBuilder();
        int all=learner.totalSamplesAll();
        s.append("Detected chart: ").append(currentDetectedAsset()).append("\n");
        TrainingStore store=new TrainingStore(this);
        s.append("Resolved automatic labels: ").append(all).append("\n");
        s.append("Boundary-aligned CSV rows: ").append(store.resolvedRowCount()).append("\n");
        s.append("Pending exact-close labels: ").append(store.pendingCount()).append("\n");
        s.append("Community learning: ").append(CommunityLearningSync.status(this)).append("\n");
        for(int i=0;i<5;i++){
            int n=learner.totalSamples(i);
            s.append("M").append(i+1).append(": ")
                    .append(n).append(" samples");
            if(n>0)s.append(" • auto score ")
                    .append(learner.accuracyPct(i)).append("% all-time • ")
                    .append(learner.recentAccuracyPct(i)).append("% recent");
            String bestSetup=learner.bestSetup(i);
            if(!bestSetup.isEmpty())s.append(" • best setup: ").append(bestSetup);
            if(i<4)s.append("\n");
        }
        if(all<150)s.append("\n\nAutomatic learning is still building calibration.");
        else s.append("\n\nAutomatic learned calibration is active.");
        learnText.setText(s.toString());
    }

    void slider(LinearLayout root,String label,String key,int min,int max,int def){
        root.addView(tx(label,14,Color.WHITE));
        SeekBar b=new SeekBar(this);
        b.setMax(max-min);
        b.setProgress(prefs.getInt(key,def)-min);
        b.setTag(new Object[]{key,min});
        b.setOnSeekBarChangeListener(new SimpleSeek(){
            public void onProgressChanged(SeekBar s,int p,boolean u){
                Object[] t=(Object[])s.getTag();
                prefs.edit().putInt((String)t[0],p+(Integer)t[1]).apply();
                labels();
            }
        });
        root.addView(b);
    }

    void labels(){
        if(cropText!=null)
            cropText.setText(String.format(Locale.US,
                    "Crop: L%d%% T%d%% R%d%% B%d%%",
                    prefs.getInt("left",5),prefs.getInt("top",18),
                    prefs.getInt("right",96),prefs.getInt("bottom",80)));
        if(sensText!=null){
            String[] s={"More signals","Balanced","Stricter"};
            sensText.setText(s[prefs.getInt("sensitivity",1)]);
        }
    }

    void startScan(){
        if(Build.VERSION.SDK_INT<30){
            status.setText("Visual candle scanning without screen sharing requires Android 11 or newer.");
            return;
        }
        if(marketKeyInput!=null && !marketKeyInput.getText().toString().trim().isEmpty()){
            RuntimeSecrets.setMarketDataKey(marketKeyInput.getText().toString());
            marketKeyInput.setText("");
        }
        if(!AutoSymbolAccessibilityService.isConnected()){
            pendingAccessibilityStart=true;
            status.setText("Enable BUY SELL Signal Notifier in Accessibility, then return. No screen-share permission is needed.");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        AutoSymbolAccessibilityService.setScannerEnabled(this,true);
        status.setText("Floating TAP SCAN is active. Open the broker chart. AUTO follows the broker timeframe when detectable; otherwise choose M1–M5 on the main screen. Automatic mode scans candle-by-candle and retries failed frame captures before the entry boundary. Tap the round TAP SCAN button only when you want an extra immediate scan.");
    }

    Button btn(String s){
        Button b=new Button(this);
        b.setText(s); b.setAllCaps(false);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(52));
        lp.setMargins(0,0,0,dp(8));
        b.setLayoutParams(lp);
        return b;
    }

    TextView tx(String s,int sp,int c){
        TextView t=new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(c);
        return t;
    }

    int dp(int v){
        return Math.round(v*getResources().getDisplayMetrics().density);
    }

    abstract class SimpleSeek implements SeekBar.OnSeekBarChangeListener{
        public void onStartTrackingTouch(SeekBar b){}
        public void onStopTrackingTouch(SeekBar b){}
    }
}
