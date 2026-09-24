package com.example.floatingcandlescanner;

import android.accessibilityservice.AccessibilityService;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Screen-share-free broker chart scanner.
 *
 * Android 11+ Accessibility screenshots are analyzed in memory. A small
 * TYPE_ACCESSIBILITY_OVERLAY AZ logo stays above the broker. Signal details
 * appear only for statistically verified setups. The service never stores screenshots or credentials.
 */
public class AutoSymbolAccessibilityService extends AccessibilityService {
    private static final String CCY = "EUR|GBP|USD|JPY|CHF|AUD|NZD|CAD|SGD|HKD|CNH|CNY|INR|BRL|MXN|ZAR|TRY|SEK|NOK|DKK|PLN|HUF|CZK|AED|SAR|JOD|BHD|KWD|QAR|OMR|ILS|THB|IDR|MYR|PHP|VND|KRW|PKR|BDT|EGP|MAD|RON|BGN|ISK";
    private static final String ASSET = CCY + "|XAU|XAG|BTC|ETH|SOL|BNB";
    private static final Pattern PAIR = Pattern.compile("(?i)(?<![A-Z0-9])(" + ASSET + ")\\s*[/\\-_:]?\\s*(" + ASSET + "|USDT)(?![A-Z0-9])");
    private static final Pattern TF_M = Pattern.compile("(?i)(?<![A-Z0-9])M\\s*([1-5])(?!\\d)");
    private static final Pattern TF_MIN = Pattern.compile("(?i)(?<!\\d)([1-5])\\s*(?:M|MIN|MINS|MINUTE|MINUTES)(?![A-Z])");

    private static final String PREFS="scanner";
    private static final String SIGNAL_CH="trade_signal_sound_v122";
    private static final String SIGNAL_POPUP_CH="trade_signal_popup_v161";
    private static final int SIGNAL_ID=5501;
    private static final long POST_CLOSE_SCAN_DELAY_MS=650L;
    private static final long ENTRY_WINDOW_MS=5_000L;
    private static final long AUTO_RETRY_MS=1_500L;
    private static final long LIVE_REFRESH_MS=1_000L;
    private static final long LIVE_CAPTURE_MIN_GAP_MS=900L;
    private static final long LIVE_BOUNDARY_GUARD_MS=1_500L;
    private static volatile AutoSymbolAccessibilityService instance;

    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean scanBusy=false;
    private WindowManager wm;
    private LinearLayout statusBox;
    private TextView statusText, symbolText, timingText, liveText, infoDetails;
    private LinearLayout signalCard;
    private LinearLayout topInfoBar;
    private TextView topInfoText;
    private WindowManager.LayoutParams topInfoLp;
    private boolean infoCardPinned=false;
    // A BUY/SELL popup belongs to the user once shown. Scanner refreshes may
    // update the banner behind it, but only the popup CLOSE action may remove it.
    private boolean signalCardManualCloseOnly=false;
    private OnlineLearner learner;
    private TrainingStore training;
    private PatternModeLearningStore patternLearning;
    private BrokerBoardLearner boardLearner;
    private SelfDecisionEngine selfDecision;
    private QuickDecisionEngine quickDecision;
    private MarketDataService marketData;
    private volatile TechnicalModel.Multi externalTechnical;
    private volatile String externalAsset="";
    private volatile String externalStatus="WEB DATA OFF";
    private volatile long externalUpdatedAt=0L;
    private volatile boolean externalFetching=false;
    private SharedPreferences prefs;
    private CandleVision.Analysis lastAnalysis;
    private long lastAlertAt=0L;
    private String lastAlertKey="";
    private String lastQuickPopupKey="";
    private String lastActivePackage="";
    private long lastAutoBoundaryMs=Long.MIN_VALUE;
    private int scheduledTimeframeMinutes=-1;
    private long predictionTargetStartMs=0L;
    private long preparedBoundaryMs=Long.MIN_VALUE;
    private long pendingBoundaryMs=Long.MIN_VALUE;
    private long lastCaptureAttemptAt=0L;
    private long lastLiveRefreshAt=0L;
    private long lastSuccessfulAutoScanAt=0L;
    private int lastScreenshotError=0;
    private String lastDirection="";
    private int lastDirectionPercent=0;
    private int lastSignalHorizon=0;
    private int lastWinRate=-1;
    private int lastWinRateSamples=0;
    private String lastLiveDirection="";
    private int lastLivePercent=0;
    private String lastLiveStatus="AI LIVE • starting";
    private SignalResult lastNotifiedSignal;
    private int lastNotifiedHorizon=1;

    private final Runnable scanTick=new Runnable(){
        @Override public void run(){
            if(scannerEnabled()) candleCadenceTick();
            main.postDelayed(this,1000L);
        }
    };

    // Learning is intentionally NOT timer-driven. Labels are created/resolved
    // only from official post-close scans at exact candle boundaries.
    private final Runnable learnTick=new Runnable(){
        @Override public void run(){ /* boundary-driven learning only */ }
    };

    @Override protected void onServiceConnected(){
        super.onServiceConnected();
        instance=this;
        prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        learner=new OnlineLearner(this);
        training=new TrainingStore(this);
        patternLearning=new PatternModeLearningStore(this);
        boardLearner=new BrokerBoardLearner(this);
        selfDecision=new SelfDecisionEngine();
        quickDecision=new QuickDecisionEngine();
        marketData=new MarketDataService();
        learner.setAsset(currentAsset());
        CommunityLearningSync.refreshAndFlushAsync(this);
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        if(scannerEnabled()) showStatusOverlay("READY");
        resetCadenceSchedule();
        main.removeCallbacks(scanTick);
        main.removeCallbacks(learnTick);
        // Pending records from older/timer-based versions are unsafe to resolve.
        training.clearPending();
        main.post(scanTick);
    }

    @Override public boolean onUnbind(Intent intent){
        instance=null;
        main.removeCallbacks(scanTick);
        main.removeCallbacks(learnTick);
        removeOverlays();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy(){
        instance=null;
        main.removeCallbacks(scanTick);
        main.removeCallbacks(learnTick);
        removeOverlays();
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        try{
            if(event!=null && event.getPackageName()!=null){
                String eventPackage=event.getPackageName().toString();
                // Never learn a pair back from AZ's own banner/card text. Doing
                // so can make a previously selected symbol look permanently
                // current after the broker has changed to another asset.
                if(getPackageName().equals(eventPackage))return;
                lastActivePackage=eventPackage;
            }

            AccessibilityNodeInfo root=getRootInActiveWindow();
            if(root==null)return;
            String visible=collectVisibleText(root);
            root.recycle();
            String symbol=detectSymbol(visible);
            String timeframe=detectTimeframe(visible);
            long now=System.currentTimeMillis();

            SharedPreferences.Editor edit=prefs.edit()
                    .putString("detected_package",lastActivePackage);
            if(symbol!=null&&!symbol.isEmpty()){
                String previous=prefs.getString("detected_asset","");
                if(!symbol.equals(previous)){
                    training.clearPending();
                    if(boardLearner!=null)boardLearner.clearPending();
                    learner.setAsset(symbol);
                    if(selfDecision!=null)selfDecision.reset();
                    if(quickDecision!=null)quickDecision.reset();
                    lastAlertKey="";
                }
                edit.putString("detected_asset",symbol)
                        .putLong("detected_asset_time",now);
            }
            if(timeframe!=null&&!timeframe.isEmpty())
                edit.putString("detected_timeframe",timeframe)
                        .putString("last_valid_timeframe",timeframe)
                        .putLong("detected_timeframe_time",now);
            edit.apply();
            updateSymbolText();
        }catch(Exception ignored){}
    }

    @Override public void onInterrupt(){}

    public static boolean isConnected(){ return instance!=null; }

    public static void setScannerEnabled(Context c,boolean enabled){
        c.getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean("scanner_enabled",enabled).apply();
        AutoSymbolAccessibilityService s=instance;
        if(s!=null){
            if(enabled){
                s.resetCadenceSchedule();
                s.showStatusOverlay("READY");
                s.main.removeCallbacks(s.scanTick);
                s.main.post(s.scanTick);
            }else{
                s.resetCadenceSchedule();
                if(s.training!=null)s.training.clearPending();
                if(s.boardLearner!=null)s.boardLearner.clearPending();
                s.removeOverlays();
                s.main.removeCallbacks(s.scanTick);
                s.cancelSignalNotification();
            }
        }
    }

    public static void setBannerMonitorEnabled(Context c,boolean enabled){
        c.getSharedPreferences(PREFS,MODE_PRIVATE).edit()
                .putBoolean("banner_monitor_enabled",enabled).apply();
        AutoSymbolAccessibilityService s=instance;
        if(s==null)return;
        s.main.post(()->{
            if(enabled && s.scannerEnabled()){
                s.createTopInfoBar();
                if(s.topInfoBar!=null)s.topInfoBar.setVisibility(View.VISIBLE);
            }else{
                s.removeTopInfoBar();
            }
        });
    }

    private boolean bannerMonitorEnabled(){
        if(prefs==null)prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        return prefs.getBoolean("banner_monitor_enabled",true);
    }

    private boolean scannerEnabled(){
        if(prefs==null)prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        return prefs.getBoolean("scanner_enabled",false);
    }

    private void resetCadenceSchedule(){
        scheduledTimeframeMinutes=-1;
        lastAutoBoundaryMs=Long.MIN_VALUE;
        predictionTargetStartMs=0L;
        preparedBoundaryMs=Long.MIN_VALUE;
        pendingBoundaryMs=Long.MIN_VALUE;
        lastCaptureAttemptAt=0L;
        lastLiveRefreshAt=0L;
        if(selfDecision!=null)selfDecision.reset();
        lastSuccessfulAutoScanAt=0L;
        lastScreenshotError=0;
        lastDirection="";
        lastDirectionPercent=0;
        lastSignalHorizon=0;
        lastWinRate=-1;
        lastWinRateSamples=0;
    }

    /**
     * Completed-candle next-entry mode. The scanner waits until the broker-aligned
     * candle has actually closed, pauses briefly so the chart can render that close,
     * and only then captures/analyzes the chart for the newly opened candle.
     *
     * This is intentionally different from pre-entry prediction: the just-finished
     * candle is never guessed before it closes. A small post-close processing delay
     * is unavoidable because the completed candle must exist before it can be read.
     */
    private void candleCadenceTick(){
        int minutes=selectedTimeframeMinutes();
        long now=System.currentTimeMillis();
        if(minutes<=0){
            scheduledTimeframeMinutes=-1;
            lastAutoBoundaryMs=Long.MIN_VALUE;
            preparedBoundaryMs=Long.MIN_VALUE;
            updateTimingText(now,0L);
            maybeLiveRefresh(now,0L,0L);
            return;
        }

        long interval=minutes*60_000L;
        long currentBoundary=(now/interval)*interval;
        long nextBoundary=currentBoundary+interval;

        if(scheduledTimeframeMinutes!=minutes){
            if(scheduledTimeframeMinutes!=-1 && training!=null)training.clearPending();
            scheduledTimeframeMinutes=minutes;
            // Do not lock an official signal from a random partially-completed
            // candle on startup. Live AI refresh can run, but the first official
            // next-candle decision waits for the next real close.
            lastAutoBoundaryMs=currentBoundary;
            preparedBoundaryMs=Long.MIN_VALUE;
            pendingBoundaryMs=Long.MIN_VALUE;
        }

        if(currentBoundary>lastAutoBoundaryMs && preparedBoundaryMs!=currentBoundary){
            long sinceClose=now-currentBoundary;
            if(sinceClose>=POST_CLOSE_SCAN_DELAY_MS && !scanBusy &&
                    now-lastCaptureAttemptAt>=LIVE_CAPTURE_MIN_GAP_MS){
                predictionTargetStartMs=currentBoundary;
                pendingBoundaryMs=currentBoundary;
                captureAndAnalyze(true,currentBoundary,false);
            }
        }

        // Mark the boundary consumed only after a valid screenshot was analyzed.
        // Failed screenshots remain eligible for retry on the following tick.
        if(preparedBoundaryMs==currentBoundary) lastAutoBoundaryMs=currentBoundary;

        // Every-second visual refresh. Suppress it just before/after a candle
        // boundary so Android's screenshot rate limit cannot steal the official
        // completed-candle capture.
        maybeLiveRefresh(now,currentBoundary,nextBoundary);
        updateTimingText(now,nextBoundary);
    }

    private void maybeLiveRefresh(long now,long currentBoundary,long nextBoundary){
        if(scanBusy || !scannerEnabled())return;
        if(now-lastLiveRefreshAt<LIVE_REFRESH_MS)return;
        if(now-lastCaptureAttemptAt<LIVE_CAPTURE_MIN_GAP_MS)return;

        if(nextBoundary>0L){
            long until=nextBoundary-now;
            long since=now-currentBoundary;
            if(until>=0L && until<=LIVE_BOUNDARY_GUARD_MS)return;
            if(since>=0L && since<=POST_CLOSE_SCAN_DELAY_MS+750L)return;
        }

        lastLiveRefreshAt=now;
        captureAndAnalyze(false,0L,true);
    }

    private int selectedTimeframeMinutes(){
        String tf=currentTimeframeLabel();
        if(tf.matches("M[1-5]")) return tf.charAt(1)-'0';
        return -1;
    }

    private long nextBoundary(long now,int minutes){
        if(minutes<=0)return 0L;
        long interval=minutes*60_000L;
        return ((now/interval)+1L)*interval;
    }

    private String clock(long when){
        if(when<=0L)return "--:--:--";
        return new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date(when));
    }

    private String countdown(long millis){
        long sec=Math.max(0L,(millis+999L)/1000L);
        long h=sec/3600L; sec%=3600L;
        long m=sec/60L, s=sec%60L;
        return h>0?String.format(Locale.getDefault(),"%d:%02d:%02d",h,m,s)
                :String.format(Locale.getDefault(),"%02d:%02d",m,s);
    }

    private String entryState(long now,long target){
        if(target<=0L)return "ENTRY TIME UNKNOWN";
        if(now<target)return "ENTRY IN "+countdown(target-now);
        long late=now-target;
        if(late<=ENTRY_WINDOW_MS)return "ENTRY NOW • 00:00";
        return "LATE";
    }

    private String winRateText(){
        if(lastWinRateSamples<5 || lastWinRate<0)
            return "WIN RATE: LEARNING"+(lastWinRateSamples>0?" ("+lastWinRateSamples+")":"");
        return "RECENT WIN RATE "+lastWinRate+"% ("+lastWinRateSamples+")";
    }

    private void refreshSignalDisplay(long now){
        if(statusText==null || lastDirection.isEmpty())return;
        String entry=lastDirection+" ENTRY "+clock(predictionTargetStartMs);
        statusText.setText(lastDirection+" "+lastDirectionPercent+"%\n"+entry+"\n"+entryState(now,predictionTargetStartMs)+"\n"+winRateText());
        statusText.setTextColor("BUY".equals(lastDirection)?Color.rgb(134,239,172):Color.rgb(252,165,165));
    }

    private void updateTimingText(long now,long nextBoundary){
        main.post(()->{
            refreshSignalDisplay(now);
            refreshInfoCard(now);
            if(timingText==null)return;
            if(nextBoundary<=0L){
                timingText.setText("Candle time unavailable");
            }else if(predictionTargetStartMs>0L && now<=predictionTargetStartMs+ENTRY_WINDOW_MS){
                timingText.setText("M"+Math.max(1,lastSignalHorizon)+" • target candle "+clock(predictionTargetStartMs));
            }else{
                timingText.setText("Next entry candle "+clock(nextBoundary)+" • "+countdown(nextBoundary-now));
            }
        });
    }

    private void captureAndAnalyze(){
        captureAndAnalyze(false,predictionTargetStartMs,false);
    }

    private void captureAndAnalyze(boolean automatic,long targetBoundary,boolean liveRefresh){
        if(Build.VERSION.SDK_INT<30||scanBusy||!scannerEnabled())return;
        scanBusy=true;
        lastCaptureAttemptAt=System.currentTimeMillis();
        Executor ex=getMainExecutor();
        TakeScreenshotCallback cb=new TakeScreenshotCallback(){
            @Override public void onSuccess(ScreenshotResult screenshot){
                Bitmap software=null;
                HardwareBuffer hb=null;
                boolean valid=false;
                try{
                    hb=screenshot.getHardwareBuffer();
                    Bitmap hw=Bitmap.wrapHardwareBuffer(hb,screenshot.getColorSpace());
                    if(hw!=null)software=hw.copy(Bitmap.Config.ARGB_8888,false);
                    if(software!=null) valid=analyzeBitmap(software,liveRefresh,automatic,targetBoundary);
                    else showUnavailable("NO FRAME • AUTO RETRY");
                }catch(Exception e){
                    showUnavailable("SCAN RETRY");
                }finally{
                    if(software!=null&&!software.isRecycled())software.recycle();
                    if(hb!=null)hb.close();
                    scanBusy=false;
                    if(automatic && valid){
                        preparedBoundaryMs=targetBoundary;
                        lastAutoBoundaryMs=targetBoundary;
                        pendingBoundaryMs=Long.MIN_VALUE;
                        lastSuccessfulAutoScanAt=System.currentTimeMillis();
                        lastScreenshotError=0;
                    }else if(automatic){
                        pendingBoundaryMs=Long.MIN_VALUE;
                    }
                }
            }
            @Override public void onFailure(int errorCode){
                scanBusy=false;
                lastScreenshotError=errorCode;
                if(automatic)pendingBoundaryMs=Long.MIN_VALUE;
                if(errorCode==ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT){
                    // The 1-second heartbeat retries automatically.
                    return;
                }
                if(liveRefresh)return;
                if(Build.VERSION.SDK_INT>=34 && errorCode==ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
                    showUnavailable("BROKER BLOCKS SCREEN CAPTURE");
                else if(errorCode==ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS)
                    showUnavailable("ACCESSIBILITY OFF");
                else showUnavailable("AUTO SCAN RETRY");
            }
        };

        // Android 14+ can capture just the active broker window. This avoids our
        // accessibility overlay being included in the candle image. Fall back to
        // the full display on Android 11-13 or when no active window is available.
        if(Build.VERSION.SDK_INT>=34){
            AccessibilityNodeInfo root=null;
            try{
                root=getRootInActiveWindow();
                if(root!=null){
                    int windowId=root.getWindowId();
                    root.recycle();
                    root=null;
                    takeScreenshotOfWindow(windowId,ex,cb);
                    return;
                }
            }catch(Exception ignored){
            }finally{
                if(root!=null)try{root.recycle();}catch(Exception ignored){}
            }
        }
        takeScreenshot(Display.DEFAULT_DISPLAY,ex,cb);
    }

    private CandleVision.Analysis analyzeWithAutoCalibration(Bitmap b){
        int theme=prefs.getInt("theme",0), sensitivity=prefs.getInt("sensitivity",1);
        boolean highAccuracy=prefs.getBoolean("high_accuracy",true);
        int savedL=prefs.getInt("left",5), savedT=prefs.getInt("top",18);
        int savedR=prefs.getInt("right",96), savedB=prefs.getInt("bottom",80);
        boolean force=prefs.getBoolean("force_auto_calibration",false);
        CandleVision.Analysis best=CandleVision.analyze(
                b,savedL,savedT,savedR,savedB,theme,sensitivity,learner,highAccuracy);
        int bestScore=calibrationScore(best);
        if(!force && bestScore>=108)return best;

        // Candidate windows cover common portrait broker layouts. CandleVision
        // independently removes wide BUY/SELL controls from the chosen region.
        int[][] candidates={
                {2,8,98,90},{3,12,97,86},{5,18,96,80},
                {6,22,95,78},{2,16,98,76},{8,12,94,82}
        };
        int[] chosen={savedL,savedT,savedR,savedB};
        for(int[] box:candidates){
            if(box[0]==savedL&&box[1]==savedT&&box[2]==savedR&&box[3]==savedB)continue;
            CandleVision.Analysis trial=CandleVision.analyze(
                    b,box[0],box[1],box[2],box[3],theme,sensitivity,learner,highAccuracy);
            int score=calibrationScore(trial);
            if(score>bestScore){best=trial;bestScore=score;chosen=box;}
        }
        if(bestScore>=108){
            prefs.edit().putInt("left",chosen[0]).putInt("top",chosen[1])
                    .putInt("right",chosen[2]).putInt("bottom",chosen[3])
                    .putBoolean("force_auto_calibration",false)
                    .putLong("auto_calibrated_at",System.currentTimeMillis()).apply();
        }
        return best;
    }

    private int calibrationScore(CandleVision.Analysis a){
        if(a==null)return 0;
        return (a.valid?100:0)+Math.max(0,a.detectedBins);
    }

    private boolean analyzeBitmap(Bitmap b,boolean liveRefresh,boolean automatic,long targetBoundary){
        boolean pairVerified=refreshDetectedContext();
        if(!pairVerified){
            showPairNotVerified();
            return false;
        }
        String asset=currentAsset();
        learner.setAsset(asset);
        CandleVision.Analysis a=analyzeWithAutoCalibration(b);
        if(a==null||!a.valid||a.detectedBins<8){
            if(liveRefresh)showLiveStatus("AI LIVE • FINDING CANDLES");
            else showUnavailable("FINDING CANDLES");
            return false;
        }
        requestExternalWebData(asset);
        TechnicalModel.Multi web=usableExternalData(asset);
        if(web!=null){
            boolean otc=asset.toUpperCase(Locale.US).contains("OTC");
            HybridPredictionEngine.Fusion fused=HybridPredictionEngine.fuse(
                    a.horizons,web,prefs.getBoolean("high_accuracy",true),
                    prefs.getBoolean("elite_mode",true),prefs.getInt("sensitivity",1),
                    prefs.getString("session_filter","ALL"),prefs.getLong("news_lock_until",0L),
                    learner,otc?.20:.45,otc?"REAL-MARKET CONTEXT FOR OTC":"LIVE OHLC");
            a=new CandleVision.Analysis(fused.results,a.detectedBins,a.latestY,a.valid,a.boardState);
            externalStatus=fused.dataStatus+(otc?" • OTC CONTEXT ONLY":"");
        }
        lastAnalysis=a;
        if(selfDecision!=null)selfDecision.observe(a.horizons);

        int selectedH=selectedHorizonIndex();
        if(selectedH<0){
            if(liveRefresh)showLiveStatus("AI LIVE • TIMEFRAME UNKNOWN");
            else showUnavailable("TIMEFRAME UNKNOWN");
            return false;
        }
        if(selectedH>=a.horizons.length){
            if(liveRefresh)showLiveStatus("AI LIVE • TIMEFRAME UNKNOWN");
            else showUnavailable("TIMEFRAME UNKNOWN");
            return false;
        }

        SignalResult best=a.horizons[selectedH];
        int horizon=selectedH+1;
        if(best==null){
            if(liveRefresh)showLiveStatus("AI LIVE • NO DATA");
            else showUnavailable("NO PROBABILITY DATA");
            return false;
        }

        SignalResult decision=selfDecision==null?best:selfDecision.decide(best,horizon,learner);

        // v14.7 broker-board learning: every live frame may contribute to state
        // stability, but only an exact future candle close is allowed to become
        // a training label. No screenshots are saved.
        boolean boardLearning=prefs.getBoolean("broker_board_learning",true);
        if(boardLearning && boardLearner!=null && a.boardState!=null){
            if(liveRefresh)boardLearner.observeLive(asset,selectedH,a.boardState);
            if(automatic && targetBoundary>0L)
                boardLearner.resolve(targetBoundary,asset,selectedH,a.latestY);
            // Resolved board memory is safe to use on every frame. Live frames are
            // never labels; they only query previously validated history.
            decision=boardLearner.apply(asset,selectedH,a.boardState,decision);
        }

        // Self-learning is aligned to the exact completed-candle boundary. Only
        // official automatic scans create/resolve labels; 1-second live frames
        // and manual taps never become training labels.
        if(automatic && targetBoundary>0L){
            processLearningAtBoundary(targetBoundary,a,selectedH,decision);
            if(boardLearning && boardLearner!=null && a.boardState!=null)
                boardLearner.addPrediction(targetBoundary,asset,selectedH,selectedH+1,a.latestY,a.boardState);
        }

        // Keep the optional aggressive pattern mode completely outside the safe
        // learner. Its outcomes go to a separate local store and never update
        // OnlineLearner or the validated community-learning queue.
        boolean automaticPatterns=prefs.getBoolean("auto_pattern_signals",false);
        boolean officialPostClose=automatic && targetBoundary>0L;
        if(automaticPatterns){
            // Pattern Mode may issue an official direction only from the
            // broker-aligned post-close scan. Live/manual frames can describe
            // the chart, but cannot recycle an older pattern into a new trade.
            if(officialPostClose)
                decision=applyAutomaticPatternSignal(decision,a.boardState);
            else
                decision=patternNoTrade(decision,"WAITING FOR LATEST CANDLE CLOSE");
        }else if(!officialPostClose){
            // Safer Mode follows the same timing rule as Pattern Mode: a live
            // or manually captured unfinished candle may update observations,
            // but it can never become an actionable next-candle signal.
            decision=patternNoTrade(decision,"WAITING FOR LATEST CANDLE CLOSE");
        }
        if(automatic && targetBoundary>0L && patternLearning!=null){
            patternLearning.resolveAndRecord(targetBoundary,asset,selectedH,selectedH+1,
                    a.latestY,automaticPatterns?decision:null);
        }

        if(liveRefresh){
            // Live Quick Decision remains disabled in both modes so it cannot
            // override the completed-candle gate with an intrabar BUY/SELL.
            showLiveDecision(decision,horizon,null);
            return true;
        }

        // Official/manual result: one clear next-candle direction. Strong alerts
        // still require the stricter completed-candle quality gates from the base
        // model, while the displayed probability uses the self-decision blend.
        showDirection(decision,horizon);
        if("BUY".equals(decision.label)||"SELL".equals(decision.label)){
            showStrongSignal(decision,horizon);
        } else {
            clearStrongSignalCard();
        }
        return true;
    }

    private void requestExternalWebData(String asset){
        String key=RuntimeSecrets.getMarketDataKey();
        String symbol=externalSymbol(asset);
        if(key==null||key.trim().isEmpty()||symbol.isEmpty()){
            externalStatus=key==null||key.trim().isEmpty()
                    ?"WEB DATA OFF • API KEY NEEDED":"WEB DATA UNSUPPORTED";
            return;
        }
        long now=System.currentTimeMillis();
        if(symbol.equals(externalAsset)&&externalTechnical!=null&&now-externalUpdatedAt<40_000L)return;
        if(externalFetching)return;
        externalFetching=true;
        new Thread(()->{
            try{
                MarketDataService.Bundle bundle=marketData.fetchAll(key,symbol);
                externalTechnical=TechnicalModel.analyze(bundle);
                externalAsset=symbol;
                externalUpdatedAt=System.currentTimeMillis();
                externalStatus="WEB OHLC ACTIVE • "+symbol;
            }catch(Exception e){
                externalStatus="WEB DATA WAITING • "+safeExternalError(e);
            }finally{externalFetching=false;}
        },"external-market-ai").start();
    }

    private TechnicalModel.Multi usableExternalData(String asset){
        String symbol=externalSymbol(asset);
        if(symbol.isEmpty()||!symbol.equals(externalAsset)||externalTechnical==null)return null;
        return System.currentTimeMillis()-externalUpdatedAt<=2L*60L*1000L?externalTechnical:null;
    }

    private static String externalSymbol(String asset){
        if(asset==null)return "";
        Matcher m=PAIR.matcher(asset.toUpperCase(Locale.US).replace("OTC"," "));
        if(!m.find())return "";
        return m.group(1)+"/"+m.group(2);
    }

    private static String safeExternalError(Exception e){
        String m=e==null?"unavailable":e.getMessage();
        if(m==null||m.trim().isEmpty())m="unavailable";
        return m.length()>80?m.substring(0,80):m;
    }

    private void showLiveStatus(String text){
        main.post(()->{
            if(!scannerEnabled())return;
            showStatusOverlay("READY");
            if(liveText!=null){
                liveText.setText(text);
                liveText.setTextColor(Color.rgb(125,211,252));
            }
        });
    }

    private void showLiveDecision(SignalResult r,int horizon,QuickDecisionEngine.Result quick){
        main.post(()->{
            if(!scannerEnabled()||r==null)return;
            showStatusOverlay("READY");
            updateTopInfoBar(r,horizon,quick);
            if("WAIT".equals(r.label)){
                lastLiveDirection="";
                lastLivePercent=0;
                lastLiveStatus="AI LIVE • WAITING FOR CANDLE CLOSE";
                clearStrongSignalCard();
                if(liveText!=null){
                    liveText.setText("AI LIVE • NO TRADE • WAITING FOR CANDLE CLOSE");
                    liveText.setTextColor(Color.rgb(250,204,21));
                }
                if(statusText!=null){
                    statusText.setText("NO TRADE • WAITING FOR COMPLETED CANDLE");
                    statusText.setTextColor(Color.rgb(250,204,21));
                }
                refreshInfoCard(System.currentTimeMillis());
                return;
            }
            boolean buy=r.buyProbability>=r.sellProbability;
            int pct=Math.max(r.buyProbability,r.sellProbability);
            lastLiveDirection=buy?"BUY":"SELL";
            lastLivePercent=pct;
            if(liveText!=null){
                int learned=boardLearner==null?0:boardLearner.totalSamples();
                OnlineLearner.Verification verified=learner.verification(Math.max(0,Math.min(4,horizon-1)));
                if(quick!=null && quick.highChance && verified.highVerified()){
                    boolean qb="BUY".equals(quick.label);
                    showQuickSignalCard(quick,horizon);
                    maybeNotifyQuick(quick,horizon,verified);
                    String verifiedLabel=confidenceTitle(quick.score);
                    lastLiveStatus=verifiedLabel+" "+quick.label+" "+quick.score+"% • "+quick.reason;
                    liveText.setText(verifiedLabel+" "+quick.label+" "+quick.score+"% • "+tradeDuration(horizon)+
                            " • "+quick.confirmations+"/6 • STABLE "+quick.stableScans+
                            (prefs.getBoolean("broker_board_learning",true)?" • BOARD "+learned:""));
                    liveText.setTextColor(qb?Color.rgb(74,222,128):Color.rgb(248,113,113));
                    if(statusText!=null){
                        statusText.setText(verifiedLabel+" "+quick.label+" • TRADE "+tradeDuration(horizon)+"\n"+verified.summary());
                        statusText.setTextColor(qb?Color.rgb(134,239,172):Color.rgb(252,165,165));
                    }
                }else{
                    String q=quick==null?"":(" • "+(quick.highChance?verified.status:"NO TRADE")+" "+quick.confirmations+"/6");
                    lastLiveStatus="AI LIVE • "+lastLiveDirection+" "+pct+"% • M"+horizon+q;
                    liveText.setText("AI LIVE • "+(buy?"BUY ":"SELL ")+pct+"% • M"+horizon+q+
                            (prefs.getBoolean("broker_board_learning",true)?" • BOARD "+learned:""));
                    liveText.setTextColor(buy?Color.rgb(134,239,172):Color.rgb(252,165,165));
                    if(quick!=null && statusText!=null && quick.reason.startsWith("NO TRADE:")){
                        statusText.setText(quick.reason);
                        statusText.setTextColor(Color.rgb(251,191,36));
                    }
                }
                refreshInfoCard(System.currentTimeMillis());
            }
        });
    }

    private void showUnavailable(String text){
        main.post(()->{
            if(!scannerEnabled())return;
            showStatusOverlay("READY");
            if(statusText!=null){
                statusText.setText(text);
                statusText.setTextColor(Color.rgb(34,211,238));
            }
            clearStrongSignalCard();
            updateSymbolText();
        });
    }

    private void showDirection(SignalResult r,int horizon){
        main.post(()->{
            if(!scannerEnabled())return;
            showStatusOverlay("READY");
            updateTopInfoBar(r,horizon,null);
            if(r==null || "WAIT".equals(r.label)){
                lastDirection="";
                lastDirectionPercent=0;
                lastSignalHorizon=horizon;
                clearStrongSignalCard();
                if(statusText!=null){
                    statusText.setText("NO TRADE • WAITING FOR COMPLETED CANDLE");
                    statusText.setTextColor(Color.rgb(250,204,21));
                }
                updateSymbolText();
                return;
            }
            boolean buyLead=r.buyProbability>=r.sellProbability;
            lastDirection=buyLead?"BUY":"SELL";
            lastDirectionPercent=Math.max(r.buyProbability,r.sellProbability);
            lastSignalHorizon=horizon;
            int hi=Math.max(0,Math.min(4,horizon-1));
            lastWinRateSamples=learner.recentCount(hi);
            lastWinRate=lastWinRateSamples==0?-1:learner.recentAccuracyPct(hi);
            if(predictionTargetStartMs<=0L){
                int minutes=selectedTimeframeMinutes();
                predictionTargetStartMs=minutes>0?nextBoundary(System.currentTimeMillis(),minutes):0L;
            }
            refreshSignalDisplay(System.currentTimeMillis());
            updateSymbolText();
        });
    }

    private void showStrongSignal(SignalResult r,int horizon){
        main.post(()->{
            if(!scannerEnabled()||r==null)return;
            int pct="BUY".equals(r.label)?r.buyProbability:r.sellProbability;
            if(pct<70){
                clearStrongSignalCard();
                return;
            }
            int c="BUY".equals(r.label)?Color.rgb(134,239,172):Color.rgb(252,165,165);
            showSignalCard(r,horizon,c);
            maybeNotify(r,horizon);
        });
    }

    private void clearStrongSignalCard(){
        if(infoCardPinned || signalCardManualCloseOnly)return;
        if(signalCard!=null){
            try{wm.removeView(signalCard);}catch(Exception ignored){}
            signalCard=null;
        }
    }

    /** Top broker-board banner requested for the marked chart-header space. */
    private void updateTopInfoBar(SignalResult r,int horizon,QuickDecisionEngine.Result quick){
        if(!bannerMonitorEnabled() || r==null)return;
        if(topInfoText==null)createTopInfoBar();
        if(topInfoText==null)return;
        String direction="WAIT".equals(r.label)?"NO TRADE":r.label;
        int pct=Math.max(r.buyProbability,r.sellProbability);
        boolean patternMode=prefs.getBoolean("auto_pattern_signals",false);
        if(!patternMode && quick!=null && quick.highChance){
            direction=quick.label;
            pct=quick.score;
        }
        if(!patternMode){
            int minimum=prefs.getInt("quick_decision_threshold",82);
            if(pct<minimum || "WAIT".equals(r.label))direction="NO TRADE";
        }
        String pattern=validatedBannerPattern(r);
        if(pattern.isEmpty() || "MULTI-FACTOR CONFLUENCE".equals(pattern))pattern="NOT DETECTED";
        long entryAt=predictionTargetStartMs>System.currentTimeMillis()
                ?predictionTargetStartMs:nextBoundary(System.currentTimeMillis(),Math.max(1,horizon));
        String entry=new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date(entryAt));
        String score="NO TRADE".equals(direction)?"":" • "+pct+"%";
        String trend=marketTrend(lastAnalysis==null?null:lastAnalysis.boardState);
        topInfoText.setText("NEXT CANDLE: "+direction+score+"\n"+
                "PATTERN: "+pattern+"\n"+
                "TREND: "+trend+"\n"+
                currentAsset()+" • M"+Math.max(1,horizon)+" • ENTRY "+entry);
        topInfoText.setTextColor("NO TRADE".equals(direction)
                ?Color.rgb(250,204,21):("BUY".equals(direction)
                ?Color.rgb(134,239,172):Color.rgb(252,165,165)));
    }

    private SignalResult applyAutomaticPatternSignal(SignalResult r,CandleVision.BoardState state){
        if(r==null)return null;
        String pattern=validatedBannerPattern(r);
        String direction=directionFromPattern(pattern);
        if(direction.isEmpty())return patternNoTrade(r,"NO STRONG CONFIRMED PATTERN");

        // Pattern mode is deliberately decisive only for a genuinely strong,
        // completed and visually confirmed setup from the newest closed candle.
        // It may override the base model, but never a weak/neutral/stale setup.
        int strength=Math.max(r.strength,Math.max(r.buyProbability,r.sellProbability));
        if(strength<70)return patternNoTrade(r,"PATTERN BELOW 70 STRENGTH");
        if(state==null)return patternNoTrade(r,"LATEST CANDLE NOT VERIFIED");

        // The newest completed candle must confirm the mapped direction. This
        // prevents an earlier green Marubozu (for example) from generating BUY
        // after the latest completed candle has turned strongly red.
        boolean latestConfirms="BUY".equals(direction)
                ?state.lastDirection>.12:state.lastDirection<-.12;
        boolean recentConfirms="BUY".equals(direction)
                ?state.recentTwoDirection>-.04:state.recentTwoDirection<.04;
        if(!latestConfirms || !recentConfirms)
            return patternNoTrade(r,"LATEST CLOSED CANDLE CONTRADICTS "+direction);

        int directional=Math.min(100,strength);
        int bp="BUY".equals(direction)?directional:100-directional;
        int sp="SELL".equals(direction)?directional:100-directional;
        String explanation="Automatic "+direction+" from strong confirmed pattern: "+pattern+
                ". "+r.explanation;
        return new SignalResult(direction,strength,r.score,bp,sp,r.confidence,
                r.regime,r.rawBuyProbability,r.setupQuality,r.structure,explanation);
    }

    private SignalResult patternNoTrade(SignalResult r,String reason){
        if(r==null)return null;
        String explanation=reason+(r.explanation==null||r.explanation.isEmpty()
                ?"":". "+r.explanation);
        return new SignalResult("WAIT",r.strength,r.score,r.buyProbability,r.sellProbability,
                r.confidence,r.regime,r.rawBuyProbability,r.setupQuality,r.structure,explanation);
    }

    private String directionFromPattern(String pattern){
        if(pattern==null)return "";
        String p=pattern.toUpperCase(Locale.US);
        if(p.isEmpty() || p.contains("NOT DETECTED") || p.contains("NOT CONFIRMED")
                || p.contains("AWAITING CONFIRMATION")
                || p.contains("SPINNING TOP") || p.contains("INDECISION"))return "";
        // Direction table for strong confirmed patterns. Dragonfly and
        // Gravestone are intentional directional Doji exceptions; plain Doji
        // remains neutral and cannot produce a trade.
        boolean dragonfly=p.contains("DRAGONFLY DOJI");
        boolean gravestone=p.contains("GRAVESTONE DOJI");
        if(p.contains("DOJI") && !dragonfly && !gravestone)return "";
        boolean bullish=dragonfly || p.contains("BULL") || p.contains("MORNING")
                || (p.contains("HAMMER") && !p.contains("HANGING MAN")) || p.contains("PIERCING")
                || p.contains("WHITE SOLDIER") || p.contains("THREE INSIDE UP")
                || p.contains("THREE OUTSIDE UP") || p.contains("RISING THREE")
                || p.contains("TWEEZER BOTTOM") || p.contains("INVERTED HAMMER")
                || p.contains("LOWER-WICK BUYER") || p.contains("BUY PRESSURE")
                || containsAny(p,"LADDER BOTTOM","MATCHING LOW","STICK SANDWICH",
                "HOMING PIGEON","THREE STARS IN THE SOUTH","THREE RIVER BOTTOM",
                "UNIQUE THREE RIVER","RISING WINDOW","UPSIDE TASUKI GAP");
        boolean bearish=gravestone || p.contains("BEAR") || p.contains("EVENING")
                || p.contains("SHOOTING STAR") || p.contains("HANGING MAN")
                || p.contains("DARK CLOUD") || p.contains("BLACK CROW")
                || p.contains("THREE INSIDE DOWN") || p.contains("THREE OUTSIDE DOWN")
                || p.contains("FALLING THREE") || p.contains("UPPER-WICK SELLER")
                || p.contains("TWEEZER TOP")
                || p.contains("SELL PRESSURE")
                || containsAny(p,"LADDER TOP","MATCHING HIGH","ADVANCE BLOCK",
                "STALLED PATTERN","DELIBERATION","FALLING WINDOW",
                "DOWNSIDE TASUKI GAP","IDENTICAL THREE CROWS");
        return bullish==bearish?"":(bullish?"BUY":"SELL");
    }

    private static boolean containsAny(String text,String... names){
        if(text==null)return false;
        for(String name:names)if(text.contains(name))return true;
        return false;
    }

    private String marketTrend(CandleVision.BoardState s){
        if(s==null)return "UNKNOWN";
        double directional=.36*s.trend+.30*s.sequenceBias+.22*s.momentum+.12*s.recentTwoDirection;
        if(directional>=.16)return s.pricePosition>=.82?"BULLISH / EXTENDED":"BULLISH";
        if(directional<=-.16)return s.pricePosition<=.18?"BEARISH / EXTENDED":"BEARISH";
        return "RANGE / MIXED";
    }

    private void createTopInfoBar(){
        if(wm==null || topInfoBar!=null || !bannerMonitorEnabled())return;
        topInfoBar=new LinearLayout(this);
        topInfoBar.setOrientation(LinearLayout.HORIZONTAL);
        topInfoBar.setGravity(Gravity.CENTER_VERTICAL);
        topInfoBar.setPadding(dp(12),dp(7),dp(6),dp(7));
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(Color.argb(238,11,18,32));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(2),Color.rgb(34,211,238));
        topInfoBar.setBackground(bg);

        topInfoText=new TextView(this);
        topInfoText.setText("AZ ANALYSING LIVE CHART…\nNEXT CANDLE • PATTERN • MARKET\nENTRY TIME");
        topInfoText.setTextSize(11);
        topInfoText.setTypeface(null,Typeface.BOLD);
        topInfoText.setGravity(Gravity.CENTER);
        topInfoText.setTextColor(Color.rgb(125,211,252));
        topInfoBar.addView(topInfoText,new LinearLayout.LayoutParams(0,dp(82),1));

        TextView hide=new TextView(this);
        hide.setText("×");
        hide.setTextSize(17);
        hide.setTypeface(null,Typeface.BOLD);
        hide.setGravity(Gravity.CENTER);
        hide.setTextColor(Color.WHITE);
        hide.setContentDescription("Hide AZ information banner");
        topInfoBar.addView(hide,new LinearLayout.LayoutParams(dp(28),dp(40)));
        hide.setOnClickListener(v->setBannerMonitorEnabled(this,false));

        topInfoLp=new WindowManager.LayoutParams(
                dp(330),WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        topInfoLp.gravity=Gravity.TOP|Gravity.START;
        int screenW=getResources().getDisplayMetrics().widthPixels;
        int screenH=getResources().getDisplayMetrics().heightPixels;
        int defaultX=Math.max(0,(screenW-dp(330))/2);
        if(prefs==null)prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        topInfoLp.x=Math.max(0,Math.min(prefs.getInt("banner_x",defaultX),Math.max(0,screenW-dp(330))));
        topInfoLp.y=Math.max(0,Math.min(prefs.getInt("banner_y",dp(66)),Math.max(0,screenH-dp(88))));

        // Press and drag anywhere on the banner text to move it. The close
        // button remains separately clickable. Save the position for next use.
        topInfoBar.setOnTouchListener(new View.OnTouchListener(){
            int startX,startY;
            float downX,downY;
            @Override public boolean onTouch(View v,MotionEvent e){
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:
                        startX=topInfoLp.x; startY=topInfoLp.y;
                        downX=e.getRawX(); downY=e.getRawY();
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int maxX=Math.max(0,getResources().getDisplayMetrics().widthPixels-dp(330));
                        int maxY=Math.max(0,getResources().getDisplayMetrics().heightPixels-dp(88));
                        topInfoLp.x=Math.max(0,Math.min(maxX,startX+Math.round(e.getRawX()-downX)));
                        topInfoLp.y=Math.max(0,Math.min(maxY,startY+Math.round(e.getRawY()-downY)));
                        try{wm.updateViewLayout(topInfoBar,topInfoLp);}catch(Exception ignored){}
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        prefs.edit().putInt("banner_x",topInfoLp.x)
                                .putInt("banner_y",topInfoLp.y).apply();
                        return true;
                    default:
                        return true;
                }
            }
        });
        try{wm.addView(topInfoBar,topInfoLp);}catch(Exception e){
            topInfoBar=null; topInfoText=null; topInfoLp=null;
        }
    }

    private void removeTopInfoBar(){
        if(wm!=null && topInfoBar!=null){try{wm.removeView(topInfoBar);}catch(Exception ignored){}}
        topInfoBar=null; topInfoText=null; topInfoLp=null;
    }

    private void showStatusOverlay(String state){
        if(wm==null)wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        if(statusBox!=null)return;
        if(bannerMonitorEnabled())createTopInfoBar();

        // Normal broker view stays clean: only the round AZ logo is visible.
        statusBox=new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setGravity(Gravity.CENTER_HORIZONTAL);
        statusBox.setPadding(dp(2),dp(2),dp(2),dp(2));

        final WindowManager.LayoutParams lp=new WindowManager.LayoutParams(
                dp(96),
                dp(96),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.END;
        lp.x=dp(12);
        lp.y=dp(120);

        // Floating round bot button, similar to a chat-head. Tap = scan now; drag = move.
        FrameLayout bubbleWrap=new FrameLayout(this);
        LinearLayout.LayoutParams bubbleWrapLp=new LinearLayout.LayoutParams(dp(104),dp(96));
        bubbleWrapLp.gravity=Gravity.CENTER_HORIZONTAL;
        statusBox.addView(bubbleWrap,bubbleWrapLp);

        ImageView scanBubble=new ImageView(this);
        scanBubble.setImageResource(R.mipmap.ic_launcher);
        scanBubble.setScaleType(ImageView.ScaleType.FIT_CENTER);
        scanBubble.setContentDescription("Tap to refresh. Long press for information. Drag to move.");
        scanBubble.setClickable(true);
        FrameLayout.LayoutParams scanLp=new FrameLayout.LayoutParams(dp(84),dp(84));
        scanLp.gravity=Gravity.CENTER;
        bubbleWrap.addView(scanBubble,scanLp);

        // Gentle cyber pulse keeps the shortcut alive without rotating the AZ letters.
        ObjectAnimator pulseX=ObjectAnimator.ofFloat(scanBubble,View.SCALE_X,1.0f,1.08f);
        ObjectAnimator pulseY=ObjectAnimator.ofFloat(scanBubble,View.SCALE_Y,1.0f,1.08f);
        ObjectAnimator glow=ObjectAnimator.ofFloat(scanBubble,View.ALPHA,.82f,1.0f);
        pulseX.setDuration(900L); pulseY.setDuration(900L); glow.setDuration(900L);
        pulseX.setRepeatCount(ValueAnimator.INFINITE);
        pulseY.setRepeatCount(ValueAnimator.INFINITE);
        glow.setRepeatCount(ValueAnimator.INFINITE);
        pulseX.setRepeatMode(ValueAnimator.REVERSE);
        pulseY.setRepeatMode(ValueAnimator.REVERSE);
        glow.setRepeatMode(ValueAnimator.REVERSE);
        AnimatorSet cyberPulse=new AnimatorSet();
        cyberPulse.playTogether(pulseX,pulseY,glow);
        cyberPulse.start();

        // Reliable tap-versus-drag handling. Small finger movement is still a TAP.
        final int touchSlop=ViewConfiguration.get(this).getScaledTouchSlop();
        scanBubble.setOnTouchListener(new View.OnTouchListener(){
            int startX,startY;
            float downX,downY;
            boolean dragging,longPressed;
            final Runnable openInformation=()->{
                if(dragging)return;
                longPressed=true;
                showInfoCard();
            };
            @Override public boolean onTouch(View v,MotionEvent e){
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:
                        startX=lp.x; startY=lp.y;
                        downX=e.getRawX(); downY=e.getRawY();
                        dragging=false; longPressed=false;
                        main.postDelayed(openInformation,ViewConfiguration.getLongPressTimeout());
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx=e.getRawX()-downX;
                        float dy=e.getRawY()-downY;
                        if(!dragging && (Math.abs(dx)>touchSlop || Math.abs(dy)>touchSlop)){
                            dragging=true;
                            main.removeCallbacks(openInformation);
                        }
                        if(dragging){
                            lp.x=Math.max(0,startX-(int)dx);
                            lp.y=Math.max(0,startY+(int)dy);
                            try{wm.updateViewLayout(statusBox,lp);}catch(Exception ignored){}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        main.removeCallbacks(openInformation);
                        v.setPressed(false);
                        if(!dragging && !longPressed){
                            runManualScan();
                            v.performClick();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(openInformation);
                        v.setPressed(false);
                        return true;
                    default:
                        return true;
                }
            }
        });

        try{wm.addView(statusBox,lp);}catch(Exception e){statusBox=null;}
    }

    private void runManualScan(){
        if(!scannerEnabled()){
            Toast.makeText(this,"Scanner is off",Toast.LENGTH_SHORT).show();
            return;
        }
        if(scanBusy){
            Toast.makeText(this,"Scan already running",Toast.LENGTH_SHORT).show();
            return;
        }
        if(statusText!=null){
            statusText.setText("SCANNING…");
            statusText.setTextColor(Color.rgb(34,211,238));
        }
        Toast.makeText(this,"Scanning candle…",Toast.LENGTH_SHORT).show();
        int minutes=selectedTimeframeMinutes();
        predictionTargetStartMs=minutes>0?nextBoundary(System.currentTimeMillis(),minutes):0L;
        captureAndAnalyze();
    }

    private void showInfoCard(){
        if(wm==null)return;
        if(signalCardManualCloseOnly && signalCard!=null)return;
        infoCardPinned=true;
        if(signalCard!=null){
            try{wm.removeView(signalCard);}catch(Exception ignored){}
            signalCard=null;
        }

        int horizon=Math.max(1,selectedTimeframeMinutes());
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16),dp(12),dp(16),dp(12));
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(Color.argb(247,11,18,32));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(2),Color.rgb(34,211,238));
        card.setBackground(bg);

        TextView title=new TextView(this);
        title.setText("AZ SCANNER INFORMATION");
        title.setTextSize(19); title.setTypeface(null,Typeface.BOLD);
        title.setTextColor(Color.rgb(34,211,238));
        card.addView(title);

        TextView details=new TextView(this);
        details.setTextSize(13); details.setTextColor(Color.WHITE);
        card.addView(details);
        infoDetails=details;
        refreshInfoCard(System.currentTimeMillis());

        Button close=new Button(this);
        close.setText("CLOSE"); close.setAllCaps(false);
        card.addView(close,new LinearLayout.LayoutParams(-1,dp(46)));
        close.setOnClickListener(v->{
            infoCardPinned=false;
            try{wm.removeView(card);}catch(Exception ignored){}
            if(signalCard==card)signalCard=null;
            if(infoDetails==details)infoDetails=null;
        });

        WindowManager.LayoutParams cp=new WindowManager.LayoutParams(
                dp(300),WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        cp.gravity=Gravity.CENTER_HORIZONTAL|Gravity.TOP; cp.y=dp(150);
        try{wm.addView(card,cp);signalCard=card;signalCardManualCloseOnly=true;}catch(Exception ignored){}
    }

    private void refreshInfoCard(long now){
        if(!infoCardPinned || infoDetails==null)return;
        int horizon=Math.max(1,selectedTimeframeMinutes());
        long next=nextBoundary(now,horizon);
        String finalSignal=lastDirection.isEmpty()?"FINAL: waiting for completed candle"
                :"FINAL: "+lastDirection+" "+lastDirectionPercent+"% • ENTRY "+clock(predictionTargetStartMs);
        String live=lastLiveDirection.isEmpty()?lastLiveStatus
                :"AI LIVE: "+lastLiveDirection+" "+lastLivePercent+"%";
        infoDetails.setText(
                "Chart: "+currentAsset()+" • M"+horizon+"\n"+
                "Recommended trade: "+tradeDuration(horizon)+"\n"+
                "Pattern Mode: strong confirmed patterns only\n\n"+
                live+"\n"+
                finalSignal+"\n"+
                entryState(now,predictionTargetStartMs)+"\n"+
                winRateText()+"\n\n"+
                "NEXT CANDLE: "+clock(next)+" • "+countdown(next-now)+"\n"+
                "LIVE analysis • FINAL = after candle close");
    }

    private String pressureLine(SignalResult r){
        if(r==null || r.regime==null)return "";
        String x=r.regime;
        int i=x.indexOf("PRESSURE ");
        if(i<0)return "";
        int end=x.indexOf(" • ",i);
        if(end<0)end=x.length();
        String v=x.substring(i,end).trim();
        return v.isEmpty()?"":"BUYER / SELLER "+v;
    }

    private void showQuickSignalCard(QuickDecisionEngine.Result quick,int horizon){
        if(infoCardPinned)return;
        if(quick==null || !quick.highChance || wm==null)return;
        if(signalCardManualCloseOnly && signalCard!=null)return;
        long slot=System.currentTimeMillis()/(Math.max(1,horizon)*60_000L);
        String key=currentAsset()+"|M"+horizon+"|"+quick.label+"|"+slot;
        if(key.equals(lastQuickPopupKey))return;
        lastQuickPopupKey=key;

        if(signalCard!=null){
            try{wm.removeView(signalCard);}catch(Exception ignored){}
            signalCard=null;
        }
        boolean buy="BUY".equals(quick.label);
        int color=buy?Color.rgb(74,222,128):Color.rgb(248,113,113);
        OnlineLearner.Verification verified=learner.verification(Math.max(0,Math.min(4,horizon-1)));

        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16),dp(12),dp(16),dp(12));
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(Color.argb(247,11,18,32));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(3),color);
        card.setBackground(bg);

        TextView title=new TextView(this);
        title.setText(confidenceTitle(quick.score)+" • "+quick.label+" "+quick.score+"%");
        title.setTextSize(20); title.setTypeface(null,Typeface.BOLD); title.setTextColor(color);
        card.addView(title);

        TextView details=new TextView(this);
        details.setText(currentAsset()+" • M"+horizon+" • TRADE "+tradeDuration(horizon)+"\n"+
                quick.reason+"\n"+verified.summary()+"\nWait for completed-candle confirmation");
        details.setTextSize(13); details.setTextColor(Color.WHITE);
        card.addView(details);

        Button close=new Button(this);
        close.setText("CLOSE"); close.setAllCaps(false);
        card.addView(close,new LinearLayout.LayoutParams(-1,dp(46)));
        close.setOnClickListener(v->{
            try{wm.removeView(card);}catch(Exception ignored){}
            if(signalCard==card){signalCard=null;signalCardManualCloseOnly=false;}
            cancelSignalNotification();
        });

        WindowManager.LayoutParams cp=new WindowManager.LayoutParams(
                dp(300),WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        cp.gravity=Gravity.CENTER_HORIZONTAL|Gravity.TOP; cp.y=dp(150);
        try{wm.addView(card,cp);signalCard=card;signalCardManualCloseOnly=true;}catch(Exception ignored){}
    }

    private String shortSetup(SignalResult r){
        if(r==null)return "MULTI-FACTOR CONFLUENCE";
        String e=r.explanation==null?"":r.explanation;
        String low=e.toLowerCase(Locale.US);
        int a=low.indexOf("led by ");
        if(a>=0){
            int start=a+7;
            int end=e.indexOf(';',start);
            if(end<0)end=Math.min(e.length(),start+42);
            String x=e.substring(start,end).trim();
            if(!x.isEmpty())return x.toUpperCase(Locale.US);
        }
        if(r.structure!=null&&!r.structure.trim().isEmpty())return r.structure.toUpperCase(Locale.US);
        return "MULTI-FACTOR CONFLUENCE";
    }

    /**
     * Do not display a visually detected candle name when it disagrees with the
     * newest chart pressure. Fixed-width screen sampling can occasionally split
     * one wide candle into several coloured regions; this guard prevents those
     * regions from being presented as a multi-candle pattern.
     */
    private String validatedBannerPattern(SignalResult r){
        String pattern=shortSetup(r);
        if(pattern==null || pattern.trim().isEmpty())return "NOT DETECTED";
        String p=pattern.toUpperCase(Locale.US);
        // Some expert summaries append a regime after the actual candle name.
        // The banner now displays trend separately, so never present that suffix
        // as if it were part of the detected pattern.
        p=p.replaceFirst("^(?:MASTER GUIDE|REFERENCE|PATTERN)\\s*:\\s*","");
        p=p.replaceAll("\\s*[•|]\\s*(?:RANGE\\s*/\\s*MIXED|BULLISH(?:\\s*/\\s*EXTENDED)?|BEARISH(?:\\s*/\\s*EXTENDED)?)\\s*$","").trim();
        CandleVision.BoardState state=lastAnalysis==null?null:lastAnalysis.boardState;
        if(state==null)return p;

        String mappedDirection=directionFromPattern(p);
        boolean bullish="BUY".equals(mappedDirection);
        boolean bearish="SELL".equals(mappedDirection);

        double newestPressure=.36*state.sequenceBias+.24*state.momentum
                +.16*state.lastDirection+.24*state.recentTwoDirection;
        boolean newestBearish=state.recentTwoDirection<-.20 || state.lastDirection<-.38;
        boolean newestBullish=state.recentTwoDirection>.20 || state.lastDirection>.38;
        boolean conflicts=(bullish && (newestPressure<-.10 || newestBearish))
                || (bearish && (newestPressure>.10 || newestBullish));
        if(conflicts)return "NOT CONFIRMED";

        // Multi-candle names need meaningful agreement from the newest sequence;
        // a single wide coloured candle must not masquerade as three candles.
        boolean multi=p.contains("THREE WHITE SOLDIERS") || p.contains("THREE BLACK CROWS")
                || p.contains("THREE BULLISH") || p.contains("THREE BEARISH");
        if(multi && (Math.abs(state.sequenceBias)<.24
                || (bullish && state.recentTwoDirection<=.08)
                || (bearish && state.recentTwoDirection>=-.08)))return "NOT CONFIRMED";

        // Reversal shapes need follow-through from more than one newest region.
        // A lone opposite candle is labelled as awaiting confirmation instead of
        // being advertised as a completed reversal setup.
        boolean reversal=p.contains("HAMMER") || p.contains("SHOOTING STAR")
                || p.contains("HANGING MAN") || p.contains("ENGULFING")
                || p.contains("MORNING STAR") || p.contains("EVENING STAR")
                || p.contains("PIERCING") || p.contains("DARK CLOUD")
                || p.contains("TWEEZER") || p.contains("THREE INSIDE")
                || p.contains("THREE OUTSIDE") || p.contains("DRAGONFLY DOJI")
                || p.contains("GRAVESTONE DOJI");
        if(reversal && ((bullish && state.recentTwoDirection<=.14)
                || (bearish && state.recentTwoDirection>=-.14)))return "AWAITING CONFIRMATION";
        return p;
    }

    /** Refresh pair/timeframe on every scan; some canvas brokers do not emit a
     * reliable accessibility event when their asset dropdown changes. */
    private boolean refreshDetectedContext(){
        AccessibilityNodeInfo root=null;
        try{
            root=getRootInActiveWindow();
            if(root==null)return false;
            CharSequence rootPackage=root.getPackageName();
            if(rootPackage!=null && getPackageName().contentEquals(rootPackage))return false;
            String visible=collectVisibleText(root);
            String symbol=detectSymbol(visible),timeframe=detectTimeframe(visible);
            long now=System.currentTimeMillis();
            SharedPreferences.Editor edit=prefs.edit();
            if(symbol!=null&&!symbol.isEmpty()){
                String previous=prefs.getString("detected_asset","");
                if(!symbol.equals(previous)){
                    if(training!=null)training.clearPending();
                    if(boardLearner!=null)boardLearner.clearPending();
                    if(learner!=null)learner.setAsset(symbol);
                    if(selfDecision!=null)selfDecision.reset();
                    if(quickDecision!=null)quickDecision.reset();
                    lastAlertKey="";
                }
                edit.putString("detected_asset",symbol).putLong("detected_asset_time",now);
            }
            if(timeframe!=null&&!timeframe.isEmpty())edit.putString("detected_timeframe",timeframe)
                    .putString("last_valid_timeframe",timeframe).putLong("detected_timeframe_time",now);
            edit.apply();
            return symbol!=null&&!symbol.isEmpty();
        }catch(Exception ignored){}finally{
            if(root!=null)try{root.recycle();}catch(Exception ignored){}
        }
        return false;
    }

    private void showPairNotVerified(){
        main.post(()->{
            if(!scannerEnabled())return;
            showStatusOverlay("READY");
            clearStrongSignalCard();
            cancelSignalNotification();
            if(quickDecision!=null)quickDecision.reset();
            lastQuickPopupKey="";
            if(statusText!=null){
                statusText.setText("PAIR NOT VERIFIED — NO TRADE");
                statusText.setTextColor(Color.rgb(251,191,36));
            }
            if(liveText!=null){
                liveText.setText("AI LIVE • PAIR NOT VERIFIED");
                liveText.setTextColor(Color.rgb(251,191,36));
            }
            if(bannerMonitorEnabled()){
                if(topInfoText==null)createTopInfoBar();
                if(topInfoText!=null){
                    topInfoText.setText("NEXT CANDLE: NO TRADE\n"+
                            "PAIR NOT VERIFIED\n"+
                            "Open the broker chart and keep the pair name visible");
                    topInfoText.setTextColor(Color.rgb(250,204,21));
                }
            }
        });
    }

    private void showSignalCard(SignalResult r,int horizon,int c){
        if(infoCardPinned)return;
        if(wm==null)return;
        if(signalCardManualCloseOnly && signalCard!=null)return;
        if(signalCard!=null){
            try{wm.removeView(signalCard);}catch(Exception ignored){}
            signalCard=null;
        }

        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16),dp(12),dp(16),dp(12));
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(Color.argb(245,11,18,32));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(2),c);
        card.setBackground(bg);

        TextView title=new TextView(this);
        String signalTime=clock(predictionTargetStartMs);
        int pct="BUY".equals(r.label)?r.buyProbability:r.sellProbability;
        OnlineLearner.Verification verified=learner.verification(Math.max(0,Math.min(4,horizon-1)));
        title.setText(confidenceTitle(pct)+" • "+r.label+"  "+pct+"%");
        title.setTextSize(22);
        title.setTypeface(null,Typeface.BOLD);
        title.setTextColor(c);
        card.addView(title);

        int hi=Math.max(0,Math.min(4,horizon-1));
        int recentN=learner.recentCount(hi);
        String recent=recentN<5?"WIN RATE: LEARNING":("RECENT WIN RATE "+learner.recentAccuracyPct(hi)+"% ("+recentN+")");
        TextView pair=new TextView(this);
        String pressure=pressureLine(r);
        pair.setText(currentAsset()+" • M"+horizon+" • TRADE "+tradeDuration(horizon)+"\n"+r.label+" ENTRY "+signalTime+"\n"+entryState(System.currentTimeMillis(),predictionTargetStartMs)+"\nSETUP: "+shortSetup(r)+(pressure.isEmpty()?"":"\n"+pressure)+"\n"+verified.summary()+"\n"+recent);
        pair.setTextSize(13);
        pair.setTextColor(Color.WHITE);
        card.addView(pair);

        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button open=new Button(this); open.setText("OPEN"); open.setAllCaps(false);
        Button close=new Button(this); close.setText("CLOSE"); close.setAllCaps(false);
        actions.addView(open,new LinearLayout.LayoutParams(0,dp(46),1));
        actions.addView(close,new LinearLayout.LayoutParams(0,dp(46),1));
        card.addView(actions);

        open.setOnClickListener(v->{
            Intent in=new Intent(this,MainActivity.class);
            in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(in);
        });
        close.setOnClickListener(v->{
            try{wm.removeView(card);}catch(Exception ignored){}
            if(signalCard==card){signalCard=null;signalCardManualCloseOnly=false;}
            cancelSignalNotification();
        });

        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(
                dp(275),WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.CENTER_HORIZONTAL|Gravity.TOP;
        lp.y=dp(170);
        try{wm.addView(card,lp);signalCard=card;signalCardManualCloseOnly=true;}catch(Exception ignored){}
    }

    private void maybeNotify(SignalResult r,int horizon){
        if(r==null || !("BUY".equals(r.label)||"SELL".equals(r.label)))return;
        int pct="BUY".equals(r.label)?r.buyProbability:r.sellProbability;
        if(pct<70)return;
        OnlineLearner.Verification verified=learner.verification(Math.max(0,Math.min(4,horizon-1)));

        // Every completed-candle BUY/SELL signal at 70%+ gets a visible popup.
        // Sound remains independently controlled by the user's sound threshold.
        int alertThreshold=Math.max(75,Math.min(90,
                prefs==null?85:prefs.getInt("sound_alert_threshold",85)));
        boolean soundEnabled=prefs==null || prefs.getBoolean("sound_alerts",true);
        boolean playSound=soundEnabled && pct>=alertThreshold;

        long now=System.currentTimeMillis();
        long candleKey=predictionTargetStartMs>0L?predictionTargetStartMs:(now/60_000L)*60_000L;
        String key=currentAsset()+"|"+r.label+"|M"+horizon+"|"+candleKey;
        if(key.equals(lastAlertKey))return;
        lastAlertKey=key; lastAlertAt=now;

        lastNotifiedSignal=r;
        lastNotifiedHorizon=horizon;

        Intent showIntent=new Intent(this,SignalDismissReceiver.class);
        showIntent.setAction("scanner.SHOW_SIGNAL");
        PendingIntent open=PendingIntent.getBroadcast(this,21,showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        Intent closeIntent=new Intent(this,SignalDismissReceiver.class);
        closeIntent.setAction("scanner.DISMISS_SIGNAL");
        PendingIntent close=PendingIntent.getBroadcast(this,22,closeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        int icon="BUY".equals(r.label)?android.R.drawable.arrow_up_float:android.R.drawable.arrow_down_float;
        Notification.Builder n=Build.VERSION.SDK_INT>=26
                ?new Notification.Builder(this,playSound?SIGNAL_CH:SIGNAL_POPUP_CH)
                :new Notification.Builder(this);
        int hi=Math.max(0,Math.min(4,horizon-1));
        int rn=learner.recentCount(hi);
        String wr=rn<5?"WIN RATE LEARNING":("WIN RATE "+learner.recentAccuracyPct(hi)+"%");
        String fullInfo=currentAsset()+" • M"+horizon+" • TRADE "+tradeDuration(horizon)+"\n"+
                r.label+" ENTRY "+clock(predictionTargetStartMs)+" • "+entryState(now,predictionTargetStartMs)+"\n"+
                "SETUP: "+shortSetup(r)+(pressureLine(r).isEmpty()?"":"\n"+pressureLine(r))+"\n"+verified.summary()+"\n"+wr;
        n.setSmallIcon(icon)
                .setContentTitle(confidenceTitle(pct)+" • "+r.label+" "+pct+"%")
                .setContentText(currentAsset()+" • M"+horizon+" • "+wr)
                .setStyle(new Notification.BigTextStyle().bigText(fullInfo))
                .setAutoCancel(false)
                .setOngoing(true)
                .setContentIntent(open)
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setOnlyAlertOnce(true)
                .setDefaults(Build.VERSION.SDK_INT<26 && playSound
                        ? (Notification.DEFAULT_SOUND|Notification.DEFAULT_VIBRATE) : 0)
                .addAction(new Notification.Action.Builder(0,"OPEN",open).build())
                .addAction(new Notification.Action.Builder(0,"CLOSE",close).build());
        postNotification(n.build());
    }

    /** Live verified signals used to show only an overlay. Notify Android too. */
    private void maybeNotifyQuick(QuickDecisionEngine.Result quick,int horizon,
                                  OnlineLearner.Verification verified){
        if(quick==null || !quick.highChance ||
                !("BUY".equals(quick.label)||"SELL".equals(quick.label)))return;
        int pct=quick.score;
        int alertThreshold=Math.max(75,Math.min(90,
                prefs==null?85:prefs.getInt("sound_alert_threshold",85)));
        boolean soundEnabled=prefs==null || prefs.getBoolean("sound_alerts",true);
        boolean playSound=soundEnabled && pct>=alertThreshold;
        long now=System.currentTimeMillis();
        long slot=now/(Math.max(1,horizon)*60_000L);
        String key=currentAsset()+"|LIVE|"+quick.label+"|M"+horizon+"|"+slot;
        if(key.equals(lastAlertKey))return;
        lastAlertKey=key; lastAlertAt=now;

        Intent openIntent=new Intent(this,MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open=PendingIntent.getActivity(this,31,openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent closeIntent=new Intent(this,SignalDismissReceiver.class);
        closeIntent.setAction("scanner.DISMISS_SIGNAL");
        PendingIntent close=PendingIntent.getBroadcast(this,32,closeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        int icon="BUY".equals(quick.label)?android.R.drawable.arrow_up_float:android.R.drawable.arrow_down_float;
        Notification.Builder n=Build.VERSION.SDK_INT>=26
                ?new Notification.Builder(this,playSound?SIGNAL_CH:SIGNAL_POPUP_CH)
                :new Notification.Builder(this);
        String details=currentAsset()+" • M"+horizon+" • TRADE "+tradeDuration(horizon)+"\n"+
                quick.reason+"\n"+verified.summary()+"\nLive signal; confirm at candle close.";
        n.setSmallIcon(icon)
                .setContentTitle(confidenceTitle(pct)+" • "+quick.label+" "+pct+"%")
                .setContentText(currentAsset()+" • M"+horizon+" • LIVE VERIFIED")
                .setStyle(new Notification.BigTextStyle().bigText(details))
                .setAutoCancel(false).setOngoing(true).setContentIntent(open)
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setOnlyAlertOnce(true)
                .setDefaults(Build.VERSION.SDK_INT<26 && playSound
                        ?(Notification.DEFAULT_SOUND|Notification.DEFAULT_VIBRATE):0)
                .addAction(new Notification.Action.Builder(0,"OPEN",open).build())
                .addAction(new Notification.Action.Builder(0,"CLOSE",close).build());
        postNotification(n.build());
    }

    private void postNotification(Notification notification){
        if(Build.VERSION.SDK_INT>=33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        !=android.content.pm.PackageManager.PERMISSION_GRANTED){
            if(prefs!=null)prefs.edit().putString("notification_status","Permission required").apply();
            return;
        }
        NotificationManager nm=getSystemService(NotificationManager.class);
        if(nm==null || !nm.areNotificationsEnabled()){
            if(prefs!=null)prefs.edit().putString("notification_status","Blocked in Android settings").apply();
            return;
        }
        try{
            nm.notify(SIGNAL_ID,notification);
            if(prefs!=null)prefs.edit().putString("notification_status","Working").apply();
        }catch(Exception e){
            if(prefs!=null)prefs.edit().putString("notification_status","Error: "+e.getClass().getSimpleName()).apply();
        }
    }

    public static boolean sendTestNotification(){
        AutoSymbolAccessibilityService s=instance;
        if(s==null)return false;
        s.main.post(()->{
            s.createNotificationChannel();
            Intent openIntent=new Intent(s,MainActivity.class);
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent open=PendingIntent.getActivity(s,41,openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b=Build.VERSION.SDK_INT>=26
                    ?new Notification.Builder(s,SIGNAL_CH):new Notification.Builder(s);
            b.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("AZ signal notifications are working")
                    .setContentText("You will be notified when a verified BUY or SELL signal is ready.")
                    .setStyle(new Notification.BigTextStyle().bigText(
                            "Test successful. Keep Android notifications enabled and do not restrict CandleScanner battery use."))
                    .setContentIntent(open).setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH);
            s.postNotification(b.build());
        });
        return true;
    }

    private String tradeDuration(int horizon){
        int minutes=Math.max(1,Math.min(5,horizon));
        return (minutes*60)+"s";
    }

    private String confidenceTitle(int score){
        if(score>=90)return "VERY HIGH CONFIDENCE";
        if(score>=85)return "HIGH CHANCE";
        if(score>=70)return "MEDIUM CHANCE";
        return "LOW CONFIDENCE";
    }

    public static void showLastSignalOverlay(){
        AutoSymbolAccessibilityService s=instance;
        if(s!=null)s.main.post(()->{
            if(s.lastNotifiedSignal!=null){
                int color="BUY".equals(s.lastNotifiedSignal.label)
                        ?Color.rgb(74,222,128):Color.rgb(248,113,113);
                s.showSignalCard(s.lastNotifiedSignal,s.lastNotifiedHorizon,color);
            }
        });
    }

    public static void dismissSignalOverlay(){
        AutoSymbolAccessibilityService s=instance;
        if(s!=null)s.main.post(()->{
            s.infoCardPinned=false;
            if(s.signalCard!=null){
                try{s.wm.removeView(s.signalCard);}catch(Exception ignored){}
                s.signalCard=null;
                s.signalCardManualCloseOnly=false;
            }
            s.cancelSignalNotification();
        });
    }

    private void cancelSignalNotification(){
        try{getSystemService(NotificationManager.class).cancel(SIGNAL_ID);}catch(Exception ignored){}
    }

    private void createNotificationChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel ch=new NotificationChannel(
                    SIGNAL_CH,"High-confidence BUY / SELL sound alerts",NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Sound and vibration only when the completed-candle signal reaches the selected confidence threshold");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0,180,90,220});
            Uri sound=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            AudioAttributes attrs=new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            ch.setSound(sound,attrs);
            NotificationManager nm=getSystemService(NotificationManager.class);
            nm.createNotificationChannel(ch);

            NotificationChannel popup=new NotificationChannel(
                    SIGNAL_POPUP_CH,"Automatic BUY / SELL popups",NotificationManager.IMPORTANCE_HIGH);
            popup.setDescription("Visible completed-candle BUY / SELL popups for confidence scores of 70% or higher");
            popup.enableVibration(false);
            popup.setSound(null,null);
            nm.createNotificationChannel(popup);
        }
    }

    private void updateSymbolText(){
        if(symbolText==null)return;
        String a=currentAsset();
        String tf=currentTimeframeLabel();
        String mode=prefs==null?"AUTO":prefs.getString("timeframe_mode","AUTO");
        long tt=prefs==null?0L:prefs.getLong("detected_timeframe_time",0L);
        boolean fallback="AUTO".equalsIgnoreCase(mode) &&
                (tt==0L || System.currentTimeMillis()-tt>30*60_000L);
        symbolText.setText(("AUTO_CHART".equals(a)?"AUTO chart":a)+" • "+tf+(fallback?" fallback":""));
    }

    private String currentTimeframeLabel(){
        if(prefs==null)prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        String mode=prefs.getString("timeframe_mode","AUTO").toUpperCase(Locale.US);
        if(!"AUTO".equals(mode))return mode;
        String tf=prefs.getString("detected_timeframe","").toUpperCase(Locale.US);
        long t=prefs.getLong("detected_timeframe_time",0L);
        if(tf.matches("M[1-5]") && System.currentTimeMillis()-t<=30*60_000L)return tf;
        String last=prefs.getString("last_valid_timeframe","M1").toUpperCase(Locale.US);
        // AUTO must never silently stop. When a canvas-based broker hides its
        // timeframe from Accessibility, keep scanning with the last known value;
        // M1 is the explicit first-run fallback and is shown in the UI as fallback.
        return last.matches("M[1-5]")?last:"M1";
    }

    private int selectedHorizonIndex(){
        String tf=currentTimeframeLabel();
        if(tf.matches("M[1-5]"))return tf.charAt(1)-'1';
        return -1;
    }

    private String currentAsset(){
        if(prefs==null)prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        String a=prefs.getString("detected_asset","").trim().toUpperCase(Locale.US);
        long t=prefs.getLong("detected_asset_time",0L);
        // Keep one learning identity stable during a normal trading session.
        // A new detected symbol still replaces it immediately.
        if(a.isEmpty()||System.currentTimeMillis()-t>60*60_000L)return "AUTO_CHART";
        return a;
    }

    private void processLearningAtBoundary(long boundary,CandleVision.Analysis now,
                                           int selectedH,SignalResult displayed){
        if(now==null||!now.valid||now.detectedBins<8||selectedH<0||selectedH>4)return;
        String asset=currentAsset();
        learner.setAsset(asset);

        List<TrainingStore.Pending> pending=training.load();
        java.util.ArrayList<TrainingStore.Pending> keep=new java.util.ArrayList<>();
        for(TrainingStore.Pending p:pending){
            // Never mix symbols or timeframes. These records are normally cleared
            // on chart changes, but the checks make the stored data robust to restarts.
            if(!asset.equalsIgnoreCase(p.asset) || p.horizon!=selectedH){
                if(p.dueAt>boundary)keep.add(p);
                continue;
            }

            if(p.dueAt==boundary){
                double delta=p.entryY-now.latestY; // screen Y falls when price rises
                if(Math.abs(delta)>=0.0035){
                    boolean up=delta>0;
                    boolean predictedUp=p.displayedBuyP>=0.5;
                    boolean correct=predictedUp==up;
                    learner.update(p.horizon,p.rawBuyP,p.displayedBuyP,up);
                    learner.updateSetup(p.horizon,p.setup,correct);
                    training.appendResolved(p,now.latestY,up,correct);
                    CommunityLearningSync.queueResolved(this,p,up,correct);
                }
                // Tiny/flat moves are deliberately left unlabelled rather than
                // forcing a noisy win/loss from anti-aliased screen pixels.
            }else if(p.dueAt>boundary){
                keep.add(p);
            }
            // p.dueAt < boundary means the exact close was missed. Discard it;
            // using a later candle would corrupt the learner.
        }
        training.save(keep);

        int minutes=selectedH+1;
        SignalResult base=now.horizons!=null && selectedH<now.horizons.length
                ?now.horizons[selectedH]:null;
        if(base!=null && displayed!=null){
            // Store raw model probability for calibration, but the probability
            // actually displayed by the Self-AI for win-rate accounting.
            SignalResult sample=new SignalResult(
                    displayed.label,displayed.strength,displayed.score,
                    displayed.buyProbability,displayed.sellProbability,
                    displayed.confidence,base.regime,base.rawBuyProbability,
                    displayed.setupQuality,displayed.structure,displayed.explanation);
            training.addPrediction(boundary,asset,selectedH,minutes,now.latestY,sample);
        }
    }

    private void removeOverlays(){
        if(wm!=null){
            if(signalCard!=null){try{wm.removeView(signalCard);}catch(Exception ignored){}}
            if(topInfoBar!=null){try{wm.removeView(topInfoBar);}catch(Exception ignored){}}
            if(statusBox!=null){try{wm.removeView(statusBox);}catch(Exception ignored){}}
        }
        signalCard=null; signalCardManualCloseOnly=false; infoCardPinned=false; infoDetails=null; statusBox=null; statusText=null; symbolText=null; timingText=null; liveText=null;
        topInfoBar=null; topInfoText=null; topInfoLp=null;
    }

    private String collectVisibleText(AccessibilityNodeInfo root){
        StringBuilder out=new StringBuilder(2048);
        ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();
        q.add(root); int visited=0;
        while(!q.isEmpty()&&visited<1200){
            AccessibilityNodeInfo n=q.removeFirst(); visited++;
            CharSequence t=n.getText(),d=n.getContentDescription();
            if(t!=null&&t.length()<=120)out.append(' ').append(t);
            if(d!=null&&d.length()<=120)out.append(' ').append(d);
            int count=n.getChildCount();
            for(int i=0;i<count;i++){
                AccessibilityNodeInfo child=n.getChild(i);
                if(child!=null)q.addLast(child);
            }
            if(n!=root)n.recycle();
        }
        return out.toString();
    }

    static String detectSymbol(String text){
        if(text==null)return null;
        String u=text.toUpperCase(Locale.US).replace('\u00A0',' ')
                .replace("／","/").replace("–","-").replace("—","-");
        Matcher m=PAIR.matcher(u);
        String best=null; int bestScore=Integer.MIN_VALUE;
        while(m.find()){
            String a=m.group(1).toUpperCase(Locale.US),b=m.group(2).toUpperCase(Locale.US);
            if(a.equals(b))continue;
            int s=Math.max(0,m.start()-24),e=Math.min(u.length(),m.end()+24);
            boolean otc=u.substring(s,e).contains("OTC");
            // Prefer an OTC-labelled selector and then the earliest visible
            // occurrence. Broker headers are exposed before lower controls;
            // choosing the last occurrence allowed hidden/stale selector text
            // (or an old AZ overlay on some Android versions) to win.
            int score=(otc?100000:0)-m.start();
            if(score>=bestScore){bestScore=score;best=a+"/"+b+(otc?" OTC":"");}
        }
        return best;
    }

    static String detectTimeframe(String text){
        if(text==null)return null;
        String u=text.toUpperCase(Locale.US).replace('\u00A0',' ')
                .replace("／","/").replace("–","-").replace("—","-");
        Matcher m=TF_M.matcher(u);
        if(m.find())return "M"+m.group(1);

        // Prefer values close to chart/time words when the broker exposes 1m/2 min style text.
        Matcher n=TF_MIN.matcher(u);
        while(n.find()){
            int s=Math.max(0,n.start()-36),e=Math.min(u.length(),n.end()+36);
            String around=u.substring(s,e);
            if(around.contains("TIME")||around.contains("CHART")||around.contains("CANDLE")||
                    around.contains("EXPIR")||around.contains("INTERVAL"))
                return "M"+n.group(1);
        }
        return null;
    }

    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
