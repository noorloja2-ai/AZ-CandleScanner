package com.example.floatingcandlescanner;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.animation.AnimationUtils;
import android.widget.*;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Cyber dashboard. Use the top menu to open each user-facing page. */
public class MainActivity extends Activity {
    private static final int CYAN=Color.rgb(34,211,238), BLUE=Color.rgb(37,99,235);
    private static final int CARD=Color.rgb(10,24,47);
    SharedPreferences prefs;
    TextView status,cropText,sensText,learnText,autoSymbolText;
    TextView licenceExpiryText;
    TextView monthWin,monthLoss,monthTotal,yearWin,yearLoss,yearTotal;
    Button overviewToggle,bannerMonitorToggle;
    OnlineLearner learner;
    AppUpdateManager updateManager;
    ViewFlipper pager;
    Spinner topMenu;
    boolean pendingAccessibilityStart=false;
    final String[] pageNames={"OVERVIEW","SCANNER","CONTROLS","TRAINING","UPDATE"};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        prefs=getSharedPreferences("scanner",MODE_PRIVATE);
        learner=new OnlineLearner(this);
        learner.setAsset(currentDetectedAsset());
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                !=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},2001);

        LinearLayout shell=new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(14),dp(12),dp(14),dp(12));
        shell.setBackground(cyberBackground());
        shell.addView(header());

        topMenu=spinner(new String[]{"OVERVIEW","SCANNER","CONTROLS","TRAINING","UPDATE"});
        topMenu.setContentDescription("Choose AZ option");
        topMenu.setBackground(cardBg(CYAN));
        topMenu.setPadding(dp(12),dp(2),dp(12),dp(2));
        shell.addView(topMenu,space(-1,dp(52),10));

        pager=new ViewFlipper(this);
        pager.addView(page(buildOverview()));
        pager.addView(page(buildScanner()));
        pager.addView(page(buildControls()));
        pager.addView(page(buildTraining()));
        pager.addView(page(buildUpdate()));
        shell.addView(pager,new LinearLayout.LayoutParams(-1,0,1));
        topMenu.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){ showPage(position); }
        });
        setContentView(shell);

        labels(); updateLearning(); updateDashboard(); updateAutoSymbol();
        if(prefs.getBoolean("auto_update_check",true))
            pager.postDelayed(()->{ if(updateManager!=null) updateManager.checkForUpdate(false); },900);
    }

    View header(){
        LinearLayout h=new LinearLayout(this); h.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo=new ImageView(this); logo.setImageResource(R.mipmap.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        h.addView(logo,new LinearLayout.LayoutParams(dp(66),dp(66)));
        LinearLayout words=new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
        TextView brand=tx("AZ  NEURAL SCANNER",19,Color.WHITE); brand.setTypeface(null,Typeface.BOLD);
        TextView sub=tx("LIVE SIGNAL SYSTEM • v16.22",11,CYAN);
        words.addView(brand); words.addView(sub); h.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        return h;
    }

    LinearLayout buildOverview(){
        LinearLayout r=column();
        TextView hero=tx("LICENCE EXPIRY",20,Color.WHITE); hero.setTypeface(null,Typeface.BOLD);
        hero.setGravity(Gravity.CENTER); hero.setPadding(0,dp(18),0,dp(12)); r.addView(hero);
        licenceExpiryText=tx("Checking licence…",18,Color.WHITE);
        licenceExpiryText.setTypeface(null,Typeface.BOLD); licenceExpiryText.setGravity(Gravity.CENTER);
        licenceExpiryText.setPadding(dp(12),dp(22),dp(12),dp(22));
        licenceExpiryText.setBackground(cardBg(CYAN)); r.addView(licenceExpiryText,space(-1,-2,14));
        performanceBlock(r,"MONTHLY SCORE",true);
        performanceBlock(r,"YEARLY SCORE",false);
        status=tx("SYSTEM READY",13,Color.rgb(134,239,172)); status.setGravity(Gravity.CENTER);
        status.setPadding(dp(10),dp(12),dp(10),dp(12)); status.setBackground(cardBg(BLUE)); r.addView(status,space(-1,-2,12));
        overviewToggle=cyberButton("START SCANNER",CYAN);
        overviewToggle.setOnClickListener(v->{if(scannerActive())stopScan();else startScan();}); r.addView(overviewToggle);
        TextView developer=tx("Developed by Anamul Hossain\nSupport: anamul00pt@gmail.com",12,Color.WHITE);
        developer.setGravity(Gravity.CENTER); developer.setPadding(dp(10),dp(20),dp(10),dp(10)); r.addView(developer);
        return r;
    }

    void performanceBlock(LinearLayout parent,String title,boolean monthly){
        TextView heading=tx(title,14,Color.WHITE);heading.setTypeface(null,Typeface.BOLD);heading.setGravity(Gravity.CENTER);
        heading.setPadding(0,dp(6),0,dp(8));parent.addView(heading);
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        TextView win=scoreCard(row,"PROFIT / WIN",Color.rgb(74,222,128));
        TextView loss=scoreCard(row,"LOSS",Color.rgb(248,113,113));
        TextView total=scoreCard(row,"RESULTS",CYAN);parent.addView(row);
        if(monthly){monthWin=win;monthLoss=loss;monthTotal=total;}
        else{yearWin=win;yearLoss=loss;yearTotal=total;}
    }

    TextView scoreCard(LinearLayout parent,String label,int color){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER);
        card.setPadding(dp(4),dp(10),dp(4),dp(10));card.setBackground(cardBg(color));
        TextView value=tx("—",20,color);value.setTypeface(null,Typeface.BOLD);value.setGravity(Gravity.CENTER);
        TextView name=tx(label,9,Color.WHITE);name.setGravity(Gravity.CENTER);card.addView(value);card.addView(name);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(72),1);lp.setMargins(dp(3),0,dp(3),dp(10));parent.addView(card,lp);
        return value;
    }

    LinearLayout buildScanner(){
        LinearLayout r=column(); section(r,"FLOATING SCANNER");
        Button start=cyberButton("START FLOATING TAP SCAN",CYAN); start.setOnClickListener(v->startScan()); r.addView(start);
        Button stop=cyberButton("STOP SCAN",Color.rgb(248,113,113)); stop.setOnClickListener(v->stopScan()); r.addView(stop);
        section(r,"BROKER BANNER");
        bannerMonitorToggle=cyberButton("",CYAN);
        bannerMonitorToggle.setOnClickListener(v->{
            boolean enabled=!prefs.getBoolean("banner_monitor_enabled",true);
            AutoSymbolAccessibilityService.setBannerMonitorEnabled(this,enabled);
            updateBannerMonitorToggle();
            if(status!=null)status.setText(enabled
                    ?"BANNER MONITOR ENABLED • Open the broker chart"
                    :"BANNER MONITOR DISABLED • Signal notifications stay active");
        });
        r.addView(bannerMonitorToggle);
        updateBannerMonitorToggle();
        note(r,"The movable banner shows the next-candle decision, confidence score, confirmed pattern, trend and entry time. Notifications work separately.");
        section(r,"AUTOMATIC CHART RECOGNITION");
        autoSymbolText=tx("Detected chart: checking…",14,Color.rgb(167,243,208)); r.addView(autoSymbolText,space(-1,-2,8));
        String[] values={"AUTO","M1","M2","M3","M4","M5"};
        String[] names={"AUTO — follow broker","M1 — 1 minute","M2 — 2 minutes","M3 — 3 minutes","M4 — 4 minutes","M5 — 5 minutes"};
        Spinner tf=spinner(names); int pos=0; String saved=prefs.getString("timeframe_mode","AUTO");
        for(int i=0;i<values.length;i++)if(values[i].equals(saved))pos=i; tf.setSelection(pos);
        tf.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){ public void onNothingSelected(AdapterView<?> p){}
            public void onItemSelected(AdapterView<?> p,View v,int i,long id){prefs.edit().putString("timeframe_mode",values[i]).apply();updateAutoSymbol();}});
        r.addView(tf);
        Button access=cyberButton("ENABLE AUTOMATIC RECOGNITION",BLUE); access.setOnClickListener(v->{
            status.setText("Enable BUY SELL Signal Notifier in Accessibility."); startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}); r.addView(access);
        note(r,"AZ analyzes chart frames in memory. Screenshots, passwords and full screen text are not saved.");
        return r;
    }

    LinearLayout buildControls(){
        LinearLayout r=column(); section(r,"SIGNAL CONTROL");
        addCheck(r,"Broker Board Learning", "broker_board_learning",true,CYAN);
        addCheck(r,"Quick High-Confidence Decision", "quick_decision",true,Color.rgb(167,243,208));
        addSeek(r,"Quick signal threshold","quick_decision_threshold",78,90,82,"%");
        addCheck(r,"Automatic Pattern BUY / SELL", "auto_pattern_signals",false,Color.rgb(250,204,21));
        note(r,"OFF (recommended): patterns require trend, structure and market-quality confirmation. ON: each confirmed directional pattern creates BUY or SELL; market quality is shown only as a warning.");
        addCheck(r,"High Accuracy Mode", "high_accuracy",true,Color.WHITE);
        addCheck(r,"Elite Precision Mode", "elite_mode",true,Color.rgb(167,243,208));
        addCheck(r,"Sound + vibration alerts", "sound_alerts",true,Color.rgb(253,230,138));
        addSeek(r,"Sound alert threshold","sound_alert_threshold",75,90,85,"%");
        section(r,"NOTIFICATIONS");
        TextView notificationState=tx(notificationStatus(),13,Color.WHITE);
        notificationState.setPadding(dp(10),dp(10),dp(10),dp(10));
        notificationState.setBackground(cardBg(CYAN)); r.addView(notificationState,space(-1,-2,9));
        Button testNotification=cyberButton("TEST SIGNAL NOTIFICATION",CYAN);
        testNotification.setOnClickListener(v->{
            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    !=PackageManager.PERMISSION_GRANTED){
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},2001);
                notificationState.setText("Allow notifications, then tap Test again.");
            }else if(!AutoSymbolAccessibilityService.sendTestNotification()){
                notificationState.setText("Start the scanner service first, then test again.");
            }else notificationState.setText("Test sent. Check the notification shade.");
        }); r.addView(testNotification);
        Button notificationSettings=cyberButton("OPEN ANDROID NOTIFICATION SETTINGS",BLUE);
        notificationSettings.setOnClickListener(v->{
            Intent i=new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName());
            startActivity(i);
        }); r.addView(notificationSettings);
        section(r,"SESSION SAFETY");
        String[] sessions={"ALL","LONDON","NEW_YORK","OVERLAP","ASIA"};
        String[] names={"All sessions","London","New York","London + New York overlap","Asia"};
        Spinner session=spinner(names); int p=0; String saved=prefs.getString("session_filter","ALL");
        for(int i=0;i<sessions.length;i++)if(sessions[i].equals(saved))p=i; session.setSelection(p);
        session.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> x){}
            public void onItemSelected(AdapterView<?> x,View v,int i,long id){prefs.edit().putString("session_filter",sessions[i]).apply();}}); r.addView(session);
        Button lock=cyberButton("NEWS LOCK • 30 MINUTES",Color.rgb(251,191,36)); lock.setOnClickListener(v->{
            prefs.edit().putLong("news_lock_until",System.currentTimeMillis()+30L*60L*1000L).apply();status.setText("News Lock active for 30 minutes.");}); r.addView(lock);
        Button clear=cyberButton("CLEAR NEWS LOCK",BLUE); clear.setOnClickListener(v->{prefs.edit().putLong("news_lock_until",0L).apply();status.setText("News Lock cleared.");}); r.addView(clear);
        section(r,"AUTOMATIC CHART CALIBRATION");
        prefs.edit().putBoolean("auto_calibration",true).apply();
        cropText=tx("AUTO • AZ detects the usable chart area during every scan.",13,Color.rgb(167,243,208));
        cropText.setPadding(dp(10),dp(10),dp(10),dp(10)); cropText.setBackground(cardBg(CYAN)); r.addView(cropText,space(-1,-2,10));
        Button recalibrate=cyberButton("RECALIBRATE ON NEXT SCAN",BLUE);
        recalibrate.setOnClickListener(v->{prefs.edit().putBoolean("force_auto_calibration",true).apply();status.setText("Automatic chart recalibration is ready.");});
        r.addView(recalibrate);
        r.addView(tx("Candle colors",14,Color.WHITE),space(-1,-2,8));
        Spinner theme=spinner(new String[]{"Auto (green vs red, UI-safe)","Green vs red","Blue vs red"});
        theme.setSelection(prefs.getInt("theme",0)); theme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> x){}
            public void onItemSelected(AdapterView<?> x,View v,int i,long id){prefs.edit().putInt("theme",i).apply();}}); r.addView(theme);
        r.addView(tx("AI signal threshold",14,Color.WHITE),space(-1,-2,10));
        SeekBar sensitivity=new SeekBar(this); sensitivity.setMax(2); sensitivity.setProgress(prefs.getInt("sensitivity",1)); r.addView(sensitivity);
        sensText=tx("",13,Color.rgb(203,213,225)); r.addView(sensText);
        sensitivity.setOnSeekBarChangeListener(new SimpleSeek(){public void onProgressChanged(SeekBar s,int p,boolean u){prefs.edit().putInt("sensitivity",p).apply();labels();}});
        return r;
    }

    LinearLayout buildTraining(){
        LinearLayout r=column(); section(r,"AUTOMATIC TRAINING");
        learnText=tx("",13,Color.rgb(203,213,225)); learnText.setPadding(dp(12),dp(12),dp(12),dp(12));
        learnText.setBackground(cardBg(CYAN)); r.addView(learnText);
        Button reset=cyberButton("RESET LEARNED CALIBRATION",Color.rgb(248,113,113));
        reset.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Reset learning?")
                .setMessage("This clears calibration for the detected asset only.").setNegativeButton("Cancel",null)
                .setPositiveButton("Reset",(d,w)->{learner.setAsset(currentDetectedAsset());learner.resetCurrentAsset();new TrainingStore(this).clearPending();updateLearning();updateDashboard();}).show()); r.addView(reset);
        section(r,"TRAINING INFORMATION");
        note(r,"Training stays automatic. AZ learns only from completed, exact-close outcomes and keeps local calibration active.");
        note(r,"Shared learning sync validates anonymous numeric outcomes. No screenshots, credentials or Android identifiers are uploaded.");
        note(r,"Confidence scores are estimates, not guaranteed win probabilities. AZ never places a trade automatically.");
        return r;
    }

    LinearLayout buildUpdate(){
        LinearLayout r=column(); section(r,"AZ APP UPDATE");
        TextView current=tx("CURRENT VERSION  16.22",18,CYAN); current.setTypeface(null,Typeface.BOLD);
        current.setGravity(Gravity.CENTER); current.setPadding(0,dp(25),0,dp(20)); r.addView(current);
        updateManager=new AppUpdateManager(this,status);
        Button update=cyberButton("CHECK / UPDATE APP",CYAN); update.setOnClickListener(v->updateManager.checkForUpdate(true)); r.addView(update);
        CheckBox auto=new CheckBox(this); auto.setText("Automatically check on startup"); auto.setTextColor(Color.rgb(167,243,208));
        auto.setChecked(prefs.getBoolean("auto_update_check",true)); auto.setOnCheckedChangeListener((b,c)->prefs.edit().putBoolean("auto_update_check",c).apply()); r.addView(auto);
        note(r,"AZ will tell you when a newer version is ready. Android will ask you to confirm installation.");
        return r;
    }

    void showPage(int wanted){
        int n=pager.getChildCount(), old=pager.getDisplayedChild(), next=(wanted+n)%n; if(next==old)return;
        boolean forward=(wanted>old)||(old==n-1&&next==0);
        pager.setInAnimation(AnimationUtils.loadAnimation(this,forward?android.R.anim.slide_in_left:android.R.anim.fade_in));
        pager.setOutAnimation(AnimationUtils.loadAnimation(this,forward?android.R.anim.slide_out_right:android.R.anim.fade_out));
        pager.setDisplayedChild(next);
        if(topMenu!=null && topMenu.getSelectedItemPosition()!=next) topMenu.setSelection(next);
        if(next==0)updateDashboard(); if(next==3)updateLearning();
    }

    @Override protected void onResume(){ super.onResume(); if(learner!=null){learner.setAsset(currentDetectedAsset());updateLearning();updateDashboard();}
        updateAutoSymbol(); updateBannerMonitorToggle(); CommunityLearningSync.refreshAndFlushAsync(this);
        if(pendingAccessibilityStart&&AutoSymbolAccessibilityService.isConnected()){pendingAccessibilityStart=false;startScan();}}
    @Override protected void onDestroy(){if(updateManager!=null)updateManager.destroy();super.onDestroy();}

    void updateDashboard(){
        if(licenceExpiryText!=null){
            long expires=LicenseManager.expiresAt(this);
            if(expires<=0) licenceExpiryText.setText("NOT AVAILABLE");
            else {
                String when=new SimpleDateFormat("dd MMM yyyy  •  HH:mm",Locale.getDefault()).format(new Date(expires));
                long remaining=expires-System.currentTimeMillis();
                if(remaining<=0) licenceExpiryText.setText("EXPIRED\n"+when);
                else {
                    long days=remaining/86_400_000L, hours=(remaining%86_400_000L)/3_600_000L;
                    licenceExpiryText.setText(when+"\n"+days+" days "+hours+" hours remaining");
                }
            }
        }
        TrainingStore store=new TrainingStore(this);
        showStats(store.currentMonthStats(),monthWin,monthLoss,monthTotal);
        showStats(store.currentYearStats(),yearWin,yearLoss,yearTotal);
        updateOverviewToggle();
    }
    void showStats(TrainingStore.MonthlyStats stats,TextView win,TextView loss,TextView total){
        if(win==null||loss==null||total==null)return;
        win.setText(stats.total()==0?"—":stats.score()+"%");
        loss.setText(stats.total()==0?"—":(100-stats.score())+"%");
        total.setText(String.valueOf(stats.total()));
    }

    String currentDetectedAsset(){String a=prefs==null?"":prefs.getString("detected_asset","");return a==null||a.trim().isEmpty()?"AUTO_CHART":a.trim();}
    void updateAutoSymbol(){if(autoSymbolText==null)return;String a=currentDetectedAsset();String mode=prefs.getString("timeframe_mode","AUTO");String tf;
        if(!"AUTO".equals(mode))tf=mode+" manual";else{String detected=prefs.getString("detected_timeframe","");long t=prefs.getLong("detected_timeframe_time",0L);
            if(!detected.isEmpty()&&System.currentTimeMillis()-t<=30*60_000L){tf=detected+" auto";prefs.edit().putString("last_valid_timeframe",detected).apply();}
            else{String fallback=prefs.getString("last_valid_timeframe","M1");if(!fallback.matches("M[1-5]"))fallback="M1";tf=fallback+" AUTO fallback";}}
        autoSymbolText.setText("AUTO_CHART".equals(a)?"Detected chart: AUTO visual mode • "+tf:"Detected chart: "+a+" • "+tf);}

    void updateLearning(){if(learnText==null)return;StringBuilder s=new StringBuilder();int all=learner.totalSamplesAll();TrainingStore store=new TrainingStore(this);
        s.append("CHART  ").append(currentDetectedAsset()).append("\nRESOLVED  ").append(all).append("\nPENDING  ").append(store.pendingCount())
                .append("\nSYNC  ").append(CommunityLearningSync.status(this)).append("\n\n");
        for(int i=0;i<5;i++){int n=learner.totalSamples(i);s.append("M").append(i+1).append("  ").append(n).append(" samples");
            if(n>0)s.append(" • ").append(learner.accuracyPct(i)).append("% all-time • ").append(learner.recentAccuracyPct(i)).append("% recent");
            s.append("\n").append(learner.verification(i).summary());if(i<4)s.append("\n\n");}
        s.append(all<150?"\n\nCalibration is still building.":"\n\nLearned calibration is active.");learnText.setText(s.toString());}

    void startScan(){if(Build.VERSION.SDK_INT<30){status.setText("Android 11 or newer is required.");return;}
        if(!AutoSymbolAccessibilityService.isConnected()){pendingAccessibilityStart=true;status.setText("Enable BUY SELL Signal Notifier in Accessibility.");startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));return;}
        AutoSymbolAccessibilityService.setScannerEnabled(this,true);status.setText("SCANNER ACTIVE • Open the broker chart");updateOverviewToggle();}
    void stopScan(){AutoSymbolAccessibilityService.setScannerEnabled(this,false);status.setText("SCANNER STOPPED");updateOverviewToggle();}
    boolean scannerActive(){return prefs.getBoolean("scanner_enabled",false);}
    String notificationStatus(){
        NotificationManager nm=getSystemService(NotificationManager.class);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                !=PackageManager.PERMISSION_GRANTED)return "Status: permission required";
        if(nm!=null&&!nm.areNotificationsEnabled())return "Status: blocked in Android settings";
        return "Status: "+prefs.getString("notification_status","ready to test");
    }
    void updateOverviewToggle(){if(overviewToggle==null)return;boolean active=scannerActive();
        overviewToggle.setText(active?"SHUTDOWN SCANNER":"START SCANNER");
        overviewToggle.setBackground(cardBg(active?Color.rgb(248,113,113):CYAN));}
    void updateBannerMonitorToggle(){if(bannerMonitorToggle==null)return;boolean enabled=prefs.getBoolean("banner_monitor_enabled",true);
        bannerMonitorToggle.setText(enabled?"DISABLE BANNER MONITOR":"ENABLE BANNER MONITOR");
        bannerMonitorToggle.setBackground(cardBg(enabled?Color.rgb(248,113,113):CYAN));}

    void addCheck(LinearLayout r,String label,String key,boolean def,int color){CheckBox c=new CheckBox(this);c.setText(label);c.setTextColor(color);c.setChecked(prefs.getBoolean(key,def));c.setOnCheckedChangeListener((b,v)->prefs.edit().putBoolean(key,v).apply());r.addView(c);}
    void addSeek(LinearLayout r,String label,String key,int min,int max,int def,String suffix){TextView value=tx(label+": "+prefs.getInt(key,def)+suffix,14,Color.WHITE);r.addView(value);SeekBar b=new SeekBar(this);b.setMax(max-min);b.setProgress(prefs.getInt(key,def)-min);b.setOnSeekBarChangeListener(new SimpleSeek(){public void onProgressChanged(SeekBar s,int p,boolean u){int v=min+p;value.setText(label+": "+v+suffix);if(u)prefs.edit().putInt(key,v).apply();}});r.addView(b);}
    void slider(LinearLayout r,String label,String key,int min,int max,int def){r.addView(tx(label,13,Color.WHITE));SeekBar b=new SeekBar(this);b.setMax(max-min);b.setProgress(prefs.getInt(key,def)-min);b.setOnSeekBarChangeListener(new SimpleSeek(){public void onProgressChanged(SeekBar s,int p,boolean u){prefs.edit().putInt(key,p+min).apply();labels();}});r.addView(b);}
    void labels(){if(cropText!=null)cropText.setText("AUTO • AZ detects the usable chart area during every scan.");if(sensText!=null){String[] s={"More signals","Balanced","Stricter"};sensText.setText(s[prefs.getInt("sensitivity",1)]);}}

    ScrollView page(LinearLayout content){ScrollView s=new ScrollView(this);s.setFillViewport(true);s.addView(content);return s;}
    LinearLayout column(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setPadding(dp(6),dp(4),dp(6),dp(24));return r;}
    void section(LinearLayout r,String label){TextView t=tx(label,17,CYAN);t.setTypeface(null,Typeface.BOLD);t.setPadding(0,dp(14),0,dp(8));r.addView(t);}
    void note(LinearLayout r,String text){TextView t=tx(text,12,Color.rgb(148,163,184));t.setPadding(dp(10),dp(9),dp(10),dp(9));t.setBackground(cardBg(BLUE));r.addView(t,space(-1,-2,8));}
    Spinner spinner(String[] items){
        Spinner s=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,items){
            @Override public View getView(int position,View convertView,android.view.ViewGroup parent){
                TextView v=(TextView)super.getView(position,convertView,parent);return styleSpinnerText(v,false);
            }
            @Override public View getDropDownView(int position,View convertView,android.view.ViewGroup parent){
                TextView v=(TextView)super.getDropDownView(position,convertView,parent);return styleSpinnerText(v,true);
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter);s.setPopupBackgroundDrawable(new ColorDrawable(CARD));return s;
    }
    TextView styleSpinnerText(TextView v,boolean dropdown){v.setTextColor(Color.WHITE);v.setTextSize(16);v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(14),dp(dropdown?16:10),dp(14),dp(dropdown?16:10));
        v.setBackgroundColor(dropdown?CARD:Color.TRANSPARENT);return v;}
    Button cyberButton(String text,int color){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTypeface(null,Typeface.BOLD);b.setBackground(cardBg(color));b.setLayoutParams(space(-1,dp(54),9));return b;}
    LinearLayout.LayoutParams space(int w,int h,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMargins(0,0,0,dp(bottom));return p;}
    TextView tx(String s,int sp,int c){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(c);return t;}
    GradientDrawable cardBg(int stroke){GradientDrawable g=new GradientDrawable();g.setColor(CARD);g.setCornerRadius(dp(14));g.setStroke(dp(1),stroke);return g;}
    GradientDrawable cyberBackground(){return new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(2,6,23),Color.rgb(7,20,42),Color.rgb(3,7,18)});}
    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    abstract class SimpleSeek implements SeekBar.OnSeekBarChangeListener{public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}}
}
