package com.example.floatingcandlescanner;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Asset-specific online calibration with recent-performance tracking.
 * Stored locally on device.
 */
public class OnlineLearner {
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
        if (n >= 10) {
            double bias = getD(k("bias", horizon), 0.0);
            double scale = getD(k("scale", horizon), 1.0);
            double learned = sigmoid(scale * logit(rawP) + bias);
            double learnedWeight = Math.min(0.55, n / 250.0 * 0.55);
            local = clamp(rawP * (1.0 - learnedWeight) + learned * learnedWeight, 0.10, 0.90);
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

        p.edit()
                .putLong(k("bias", horizon), Double.doubleToRawLongBits(bias))
                .putLong(k("scale", horizon), Double.doubleToRawLongBits(scale))
                .putInt(k("count", horizon), n + 1)
                .putInt(k("wins", horizon), wins)
                .putInt(k("losses", horizon), losses)
                .putString(k("recent", horizon), recent)
                .apply();
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
             .remove(k("wins",h)).remove(k("losses",h)).remove(k("recent",h));
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
