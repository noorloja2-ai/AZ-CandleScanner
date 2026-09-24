package com.example.floatingcandlescanner;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Asset-specific online calibration with recent-performance tracking.
 * Stored locally on device.
 */
public class OnlineLearner {
    public static final double BREAK_EVEN_92 = 1.0 / 1.92;
    private static final int VERIFIED_MIN_SAMPLES = 200;
    private static final int VERIFIED_MIN_RECENT = 50;
    private static final int LOCAL_ACTIVATION_SAMPLES = 30;
    private static final int SETUP_ACTIVATION_SAMPLES = 30;
    private static final int DRIFT_WINDOW = 30;
    private static final String PREF = "online_learner_v4";
    private final Context context;
    private final SharedPreferences p;
    private String asset = "GENERIC";

    public OnlineLearner(Context context) {
        this.context = context.getApplicationContext();
        p = this.context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public void setAsset(String assetName) {
        if (assetName == null || assetName.trim().isEmpty()) asset = "GENERIC";
        else asset = assetName.trim().toUpperCase(Locale.US).replace("/", "_").replace(" ", "_");
    }

    private String k(String base, int h) {
        return asset + "_" + base + "_" + h;
    }

    public synchronized double calibrate(int horizon, double rawP) {
        int n = totalSamples(horizon);
        double local = rawP;
        // Local calibration activates after 30 exact-close outcomes for this
        // pair/timeframe and pauses automatically while drift is quarantined.
        if (n >= LOCAL_ACTIVATION_SAMPLES && !p.getBoolean(k("drift",horizon),false)) {
            double bias = getD(k("bias", horizon), 0.0);
            double scale = getD(k("scale", horizon), 1.0);
            double learned = sigmoid(scale * logit(rawP) + bias);
            double learnedWeight = Math.min(0.55,
                    (n-LOCAL_ACTIVATION_SAMPLES+1) / 220.0 * 0.55);
            local = clamp(rawP * (1.0 - learnedWeight) + learned * learnedWeight, 0.10, 0.90);
            String recent=p.getString(k("recent",horizon),"");
            if(recent.length()>=20){
                int rw=0;for(int i=0;i<recent.length();i++)if(recent.charAt(i)=='1')rw++;
                double recentRate=(double)rw/recent.length();
                double confidence=Math.min(1.0,recent.length()/50.0);
                double direction=local>=.5?1.0:-1.0;
                local=clamp(local+direction*(recentRate-.55)*.10*confidence,.10,.90);
            }
        }
        // A validated community model from GitHub can add at most 25% weight,
        // so this phone's own learning and the base model remain dominant.
        return CommunityLearningSync.blendCommunity(context, asset, horizon, rawP, local);
    }

    public synchronized void update(int horizon, double rawP, boolean outcomeUp) {
        update(horizon, rawP, calibrate(horizon, rawP), outcomeUp);
    }

    /**
     * Update calibration from the raw model probability while scoring accuracy
     * against the probability/direction that was actually shown to the user.
     * This keeps the reported win-rate aligned with the visible BUY/SELL call.
     */
    public synchronized void update(int horizon, double rawP, double displayedBuyP, boolean outcomeUp) {
        int n = totalSamples(horizon);
        double bias = getD(k("bias", horizon), 0.0);
        double scale = getD(k("scale", horizon), 1.0);

        double x = logit(rawP);
        double pred = sigmoid(scale * x + bias);
        double y = outcomeUp ? 1.0 : 0.0;
        double error = y - pred;

        double lr = Math.max(0.004, 0.03 / Math.sqrt(1.0 + n / 25.0));
        bias = clamp(bias + lr * error, -0.9, 0.9);
        scale = clamp(scale + lr * error * x, 0.60, 1.45);

        boolean predictedUp = clamp(displayedBuyP, 0.0, 1.0) >= 0.5;
        boolean correct = predictedUp == outcomeUp;

        int wins = p.getInt(k("wins", horizon), 0) + (correct ? 1 : 0);
        int losses = p.getInt(k("losses", horizon), 0) + (correct ? 0 : 1);

        // Rolling last-50 direction outcomes as "1"/"0".
        String recent = p.getString(k("recent", horizon), "");
        recent += correct ? "1" : "0";
        if (recent.length() > 50) recent = recent.substring(recent.length() - 50);

        SharedPreferences.Editor edit=p.edit()
                .putLong(k("bias", horizon), Double.doubleToRawLongBits(bias))
                .putLong(k("scale", horizon), Double.doubleToRawLongBits(scale))
                .putInt(k("count", horizon), n + 1)
                .putInt(k("wins", horizon), wins)
                .putInt(k("losses", horizon), losses)
                .putString(k("recent", horizon), recent);

        // Detect market drift and restore the last healthy local checkpoint.
        int recentWins=0;
        for(int i=0;i<recent.length();i++)if(recent.charAt(i)=='1')recentWins++;
        double recentRate=recent.isEmpty()?1.0:(double)recentWins/recent.length();
        if(recent.length()>=DRIFT_WINDOW && recentRate<.50){
            double stableBias=getD(k("stable_bias",horizon),0.0);
            double stableScale=getD(k("stable_scale",horizon),1.0);
            boolean alreadyDrifting=p.getBoolean(k("drift",horizon),false);
            edit.putLong(k("bias",horizon),Double.doubleToRawLongBits(stableBias))
                    .putLong(k("scale",horizon),Double.doubleToRawLongBits(stableScale))
                    .putBoolean(k("drift",horizon),true)
                    .putInt(k("rollbacks",horizon),p.getInt(k("rollbacks",horizon),0)
                            +(alreadyDrifting?0:1));
        }else if(recent.length()>=DRIFT_WINDOW && recentRate>=.60){
            edit.putLong(k("stable_bias",horizon),Double.doubleToRawLongBits(bias))
                    .putLong(k("stable_scale",horizon),Double.doubleToRawLongBits(scale))
                    .putBoolean(k("drift",horizon),false);
        }
        edit.apply();
    }

    public int totalSamples(int horizon) { return p.getInt(k("count", horizon), 0); }
    public int wins(int horizon) { return p.getInt(k("wins", horizon), 0); }
    public int losses(int horizon) { return p.getInt(k("losses", horizon), 0); }

    public int totalSamplesAll() {
        int s=0; for(int h=0;h<5;h++) s += totalSamples(h); return s;
    }

    public int accuracyPct(int horizon) {
        int w=wins(horizon), l=losses(horizon);
        return (w+l)==0 ? 0 : Math.round(100f*w/(w+l));
    }

    public int recentAccuracyPct(int horizon) {
        String recent = p.getString(k("recent", horizon), "");
        if (recent.isEmpty()) return 0;
        int w=0;
        for(int i=0;i<recent.length();i++) if(recent.charAt(i)=='1') w++;
        return Math.round(100f*w/recent.length());
    }

    public int recentCount(int horizon) {
        return p.getString(k("recent", horizon), "").length();
    }

    public boolean driftDetected(int horizon){return p.getBoolean(k("drift",horizon),false);}
    public int rollbackCount(int horizon){return p.getInt(k("rollbacks",horizon),0);}

    /** Apply pattern quality after 30 outcomes for this pair/timeframe/setup. */
    public synchronized SignalResult applySetupQuality(int horizon,SignalResult r){
        if(r==null || !("BUY".equals(r.label)||"SELL".equals(r.label)))return r;
        String slug=setupSlug(r.structure);
        if(slug.isEmpty())return r;
        String prefix=k("setup_"+slug,horizon);
        int n=p.getInt(prefix+"_n",0),w=p.getInt(prefix+"_w",0);
        if(n<SETUP_ACTIVATION_SAMPLES)return r;
        double rate=(double)w/n;
        double lower=wilsonLower(w,n,1.959963984540054);
        if(rate<.55 || lower<=BREAK_EVEN_92){
            return new SignalResult("WAIT",r.strength,r.score,r.buyProbability,
                    r.sellProbability,r.confidence,r.regime,r.rawBuyProbability,
                    r.setupQuality,r.structure,"PATTERN QUALITY NO TRADE • "+w+"/"+n+
                    ". "+r.explanation);
        }
        int adjustment=(int)Math.round(clamp((rate-.60)*35.0,-5.0,5.0));
        int lead="BUY".equals(r.label)?r.buyProbability:r.sellProbability;
        int adjusted=(int)Math.round(clamp(lead+adjustment,50,95));
        int bp="BUY".equals(r.label)?adjusted:100-adjusted;
        int sp="SELL".equals(r.label)?adjusted:100-adjusted;
        return new SignalResult(r.label,Math.max(r.strength,adjusted),r.score,bp,sp,
                r.confidence,r.regime,r.rawBuyProbability,r.setupQuality,r.structure,
                "PATTERN QUALITY "+Math.round(rate*100)+"% ("+n+") • "+r.explanation);
    }

    /**
     * Conservative evidence range for user-facing alert labels.
     *
     * Model percentages are scores, not measured win probabilities.  Alerts use
     * the 95% Wilson lower bound of resolved, asset/timeframe-specific outcomes.
     * This prevents a short lucky streak from being displayed as HIGH CHANCE.
     */
    public synchronized Verification verification(int horizon) {
        int w = wins(horizon), l = losses(horizon), n = w + l;
        String recent = p.getString(k("recent", horizon), "");
        int rw = 0;
        for (int i=0;i<recent.length();i++) if (recent.charAt(i)=='1') rw++;
        int rn = recent.length();
        double allRate = n == 0 ? 0.0 : (double) w / n;
        double recentRate = rn == 0 ? 0.0 : (double) rw / rn;
        double lower = wilsonLower(w, n, 1.959963984540054);

        int tier = 0;
        String status;
        if (n < VERIFIED_MIN_SAMPLES || rn < VERIFIED_MIN_RECENT) {
            status = "UNVERIFIED";
        } else if (recentRate < .55 || lower <= BREAK_EVEN_92) {
            status = "NO TRADE";
        } else if (lower < .58 || recentRate < .60) {
            tier = 1;
            status = "POSSIBLE";
        } else if (n >= 300 && lower >= .60 && recentRate >= .65) {
            tier = 3;
            status = "STRONG VERIFIED";
        } else {
            tier = 2;
            status = "HIGH VERIFIED";
        }
        return new Verification(n, rn, allRate, recentRate, lower, tier, status);
    }

    public static final class Verification {
        public final int samples, recentSamples, tier;
        public final double winRate, recentWinRate, lower95;
        public final String status;
        Verification(int samples, int recentSamples, double winRate,
                     double recentWinRate, double lower95, int tier, String status) {
            this.samples=samples; this.recentSamples=recentSamples;
            this.winRate=winRate; this.recentWinRate=recentWinRate;
            this.lower95=lower95; this.tier=tier; this.status=status;
        }
        public boolean highVerified() { return tier >= 2; }
        public String summary() {
            if (samples < VERIFIED_MIN_SAMPLES || recentSamples < VERIFIED_MIN_RECENT)
                return status+" • "+samples+"/"+VERIFIED_MIN_SAMPLES+" resolved";
            return String.format(Locale.US,
                    "%s • 95%% lower %.1f%% • recent %.0f%% (%d)",
                    status, lower95*100.0, recentWinRate*100.0, recentSamples);
        }
    }

    static double wilsonLower(int wins, int samples, double z) {
        if (samples <= 0) return 0.0;
        double n = samples, phat = clamp((double) wins / n, 0.0, 1.0);
        double z2 = z*z;
        double centre = phat + z2/(2.0*n);
        double margin = z*Math.sqrt((phat*(1.0-phat)+z2/(4.0*n))/n);
        return clamp((centre-margin)/(1.0+z2/n), 0.0, 1.0);
    }

    public boolean recentPerformanceHealthy(int horizon) {
        int n = recentCount(horizon);
        if (n < 20) return true; // not enough data to gate yet
        return recentAccuracyPct(horizon) >= 55;
    }

    public boolean eliteReady(int horizon) {
        return totalSamples(horizon) >= 40 && recentCount(horizon) >= 25;
    }

    public boolean elitePerformanceHealthy(int horizon) {
        int n = recentCount(horizon);
        return n >= 25 && recentAccuracyPct(horizon) >= 68;
    }


    /** Track which named candle/setup families actually resolve correctly. */
    public synchronized void updateSetup(int horizon, String setup, boolean correct) {
        String slug = setupSlug(setup);
        if (slug.isEmpty()) return;
        String prefix = k("setup_" + slug, horizon);
        int n = p.getInt(prefix + "_n", 0) + 1;
        int w = p.getInt(prefix + "_w", 0) + (correct ? 1 : 0);
        String listKey = k("setup_keys", horizon);
        String keys = p.getString(listKey, "");
        if (!("|" + keys + "|").contains("|" + slug + "|")) {
            keys = keys.isEmpty() ? slug : keys + "|" + slug;
        }
        p.edit().putInt(prefix + "_n", n).putInt(prefix + "_w", w)
                .putString(prefix + "_label", setupLabel(setup))
                .putString(listKey, keys).apply();
    }

    public synchronized String bestSetup(int horizon) {
        String keys = p.getString(k("setup_keys", horizon), "");
        if (keys.isEmpty()) return "";
        String best = ""; int bestN = 0; double bestScore = -1;
        for (String slug : keys.split("\\|")) {
            if (slug.isEmpty()) continue;
            String prefix = k("setup_" + slug, horizon);
            int n = p.getInt(prefix + "_n", 0);
            int w = p.getInt(prefix + "_w", 0);
            if (n < 5) continue;
            double acc = (double) w / n;
            // Slight sample-size preference prevents a 5/5 setup beating a
            // stable 40/50 setup forever.
            double score = acc - 0.08 / Math.sqrt(n);
            if (score > bestScore) {
                bestScore = score; bestN = n;
                String label = p.getString(prefix + "_label", slug.replace('_',' '));
                best = label + " " + Math.round(acc * 100.0) + "% (" + n + ")";
            }
        }
        return best;
    }

    /** Compact per-pattern dashboard for the selected pair and timeframe. */
    public synchronized String setupReport(int horizon){
        String keys=p.getString(k("setup_keys",horizon),"");
        if(keys.isEmpty())return "No resolved pattern samples yet.";
        StringBuilder out=new StringBuilder();
        for(String slug:keys.split("\\|")){
            if(slug.isEmpty())continue;
            String prefix=k("setup_"+slug,horizon);
            int n=p.getInt(prefix+"_n",0),w=p.getInt(prefix+"_w",0);
            String label=p.getString(prefix+"_label",slug.replace('_',' '));
            int rate=n==0?0:Math.round(100f*w/n);
            String state=n<30?"LEARNING":(rate<55?"QUARANTINED":(rate>=65?"STRONG":"ACTIVE"));
            if(out.length()>0)out.append('\n');
            out.append(label).append(" • ").append(rate).append("% • ")
                    .append(w).append('/').append(n).append(" • ").append(state);
        }
        return out.length()==0?"No resolved pattern samples yet.":out.toString();
    }

    private static String setupSlug(String s) {
        if (s == null) return "";
        String x = s.trim().toUpperCase(Locale.US);
        int cut = x.indexOf(" • ");
        if (cut > 0) x = x.substring(0, cut);
        x = x.replaceAll("[^A-Z0-9]+", "_");
        if (x.length() > 48) x = x.substring(0, 48);
        while (x.startsWith("_")) x = x.substring(1);
        while (x.endsWith("_") && !x.isEmpty()) x = x.substring(0, x.length()-1);
        return x;
    }

    private static String setupLabel(String s) {
        if (s == null || s.trim().isEmpty()) return "MULTI-FACTOR";
        String x = s.trim();
        int cut = x.indexOf(" • ");
        if (cut > 0) x = x.substring(0, cut);
        return x.length() > 52 ? x.substring(0,52) : x;
    }

    public void resetCurrentAsset() {
        SharedPreferences.Editor e = p.edit();
        for(int h=0;h<5;h++) {
            String keys = p.getString(k("setup_keys",h), "");
            if(!keys.isEmpty()) {
                for(String slug:keys.split("\\|")) {
                    if(slug.isEmpty()) continue;
                    String prefix=k("setup_"+slug,h);
                    e.remove(prefix+"_n").remove(prefix+"_w").remove(prefix+"_label");
                }
            }
            e.remove(k("setup_keys",h));
            e.remove(k("bias",h)).remove(k("scale",h)).remove(k("count",h))
             .remove(k("wins",h)).remove(k("losses",h)).remove(k("recent",h))
             .remove(k("stable_bias",h)).remove(k("stable_scale",h))
             .remove(k("drift",h)).remove(k("rollbacks",h));
        }
        e.apply();
    }

    private double getD(String key,double def) {
        if(!p.contains(key)) return def;
        return Double.longBitsToDouble(p.getLong(key,Double.doubleToRawLongBits(def)));
    }

    private static double logit(double x) {
        x=clamp(x,0.02,0.98); return Math.log(x/(1-x));
    }
    private static double sigmoid(double x) { return 1.0/(1.0+Math.exp(-x)); }
    private static double clamp(double v,double lo,double hi) { return Math.max(lo,Math.min(hi,v)); }
}
