package com.example.floatingcandlescanner;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Learns compact numeric candle-board states from the broker chart itself.
 *
 * It never stores screenshots, page text, usernames or passwords.  Each
 * completed-candle scan is reduced to a small state bucket (trend, momentum,
 * candle geometry, range position and volatility).  The next exact candle
 * close is then used as the label.  Only resolved labels can change the model.
 */
public final class BrokerBoardLearner {
    private static final String PREF = "broker_board_learning_v1";
    private static final String PENDING = "pending";
    private static final String KEYS = "keys";
    private static final int MAX_BUCKETS = 320;
    private final SharedPreferences p;

    private String liveKey = "";
    private int liveStreak = 0;
    private long lastLiveAt = 0L;

    public BrokerBoardLearner(Context c) {
        p = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public synchronized void observeLive(String asset, int horizon, CandleVision.BoardState s) {
        if (s == null || horizon < 0 || horizon > 4) return;
        long now = System.currentTimeMillis();
        String key = statKey(asset, horizon, s.bucket());
        if (key.equals(liveKey) && now - lastLiveAt <= 2500L) liveStreak = Math.min(12, liveStreak + 1);
        else { liveKey = key; liveStreak = 1; }
        lastLiveAt = now;
    }

    /** Resolve only predictions whose exact due candle has just closed. */
    public synchronized void resolve(long boundary, String asset, int horizon, double exitY) {
        if (boundary <= 0L || horizon < 0 || horizon > 4) return;
        List<Pending> all = loadPending();
        ArrayList<Pending> keep = new ArrayList<>();
        String a = normAsset(asset);
        for (Pending x : all) {
            if (!a.equals(x.asset) || x.horizon != horizon) {
                if (x.dueAt > boundary) keep.add(x);
                continue;
            }
            if (x.dueAt == boundary) {
                double delta = x.entryY - exitY; // screen Y falls when price rises
                if (Math.abs(delta) >= 0.0035) record(x.statKey, delta > 0);
            } else if (x.dueAt > boundary) keep.add(x);
            // Missed exact closes are discarded rather than mislabeled later.
        }
        savePending(keep);
    }

    /** Store the state that will be validated at the next exact timeframe close. */
    public synchronized void addPrediction(long boundary, String asset, int horizon,
                                           int timeframeMinutes, double entryY,
                                           CandleVision.BoardState state) {
        if (state == null || boundary <= 0L || timeframeMinutes <= 0 || horizon < 0 || horizon > 4) return;
        String a = normAsset(asset);
        String sk = statKey(a, horizon, state.bucket());
        List<Pending> all = loadPending();
        Iterator<Pending> it = all.iterator();
        while (it.hasNext()) {
            Pending old = it.next();
            if (old.createdAt == boundary && old.horizon == horizon && a.equals(old.asset)) it.remove();
        }
        Pending x = new Pending();
        x.createdAt = boundary;
        x.dueAt = boundary + timeframeMinutes * 60_000L;
        x.horizon = horizon;
        x.entryY = entryY;
        x.asset = a;
        x.statKey = sk;
        all.add(x);
        long cutoff = boundary - 10L * 60L * 1000L;
        it = all.iterator();
        while (it.hasNext()) if (it.next().dueAt < cutoff) it.remove();
        savePending(all);
    }

    /** Blend validated board-history evidence into the next prediction. */
    public synchronized SignalResult apply(String asset, int horizon,
                                           CandleVision.BoardState state,
                                           SignalResult base) {
        if (base == null || state == null || horizon < 0 || horizon > 4) return base;
        String key = statKey(asset, horizon, state.bucket());
        int up = p.getInt(key + "_u", 0);
        int dn = p.getInt(key + "_d", 0);
        int n = up + dn;
        if (n < 6) return base;

        // Beta(2,2) smoothing prevents tiny samples from looking certain.
        double learned = (up + 2.0) / (n + 4.0);
        double baseP = clamp(base.buyProbability / 100.0, .05, .95);
        double sampleWeight = Math.min(.28, .05 + .23 * Math.min(1.0, (n - 5.0) / 55.0));
        double edge = Math.abs(learned - .5) * 2.0;
        double stability = key.equals(liveKey) ? Math.min(1.0, liveStreak / 5.0) : .45;
        double w = sampleWeight * (.55 + .45 * edge) * (.78 + .22 * stability);
        double out = clamp(baseP * (1.0 - w) + learned * w, .10, .90);

        int buy = (int)Math.round(out * 100.0);
        int sell = 100 - buy;
        int lead = Math.max(buy, sell);
        String label = base.label;

        // Board memory may refine or flip an already directional call.  A WAIT is
        // only promoted when the matching history is both mature and decisive.
        if (!"WAIT".equals(base.label)) {
            label = buy >= sell ? "BUY" : "SELL";
        } else if (n >= 60 && boardDirectionVerified(up, dn) && lead >= 60) {
            label = buy >= sell ? "BUY" : "SELL";
        }

        int confidence = Math.max(base.confidence,
                Math.min(96, (int)Math.round(48 + 42 * Math.abs(out - .5) * 2.0 + Math.min(8, n / 8))));
        String memory = String.format(Locale.US, "BROKER BOARD %d%% (%d)", (int)Math.round(learned * 100.0), n);
        String structure = (base.structure == null || base.structure.isEmpty())
                ? memory : base.structure + " • " + memory;
        String explanation = (base.explanation == null ? "" : base.explanation + " ")
                + "Validated broker-board memory for a matching candle state: "
                + Math.round(learned * 100.0) + "% next-candle UP across " + n + " resolved examples.";

        return new SignalResult(label, lead, (out - .5) * 2.0,
                buy, sell, confidence, base.regime, base.rawBuyProbability,
                base.setupQuality, structure, explanation);
    }

    public synchronized int samplesFor(String asset, int horizon, CandleVision.BoardState state) {
        if (state == null) return 0;
        String key = statKey(asset, horizon, state.bucket());
        return p.getInt(key + "_u", 0) + p.getInt(key + "_d", 0);
    }

    public synchronized int totalSamples() {
        String keys = p.getString(KEYS, "");
        if (keys.isEmpty()) return 0;
        int sum = 0;
        for (String k : keys.split("\\|")) if (!k.isEmpty()) sum += p.getInt(k + "_u", 0) + p.getInt(k + "_d", 0);
        return sum;
    }

    public synchronized void clearPending() { p.edit().remove(PENDING).apply(); }

    private static boolean boardDirectionVerified(int up, int down) {
        int n=up+down;
        if(n<60)return false;
        boolean upLead=up>=down;
        int directionalWins=upLead?up:down;
        // A board bucket may promote WAIT only when its conservative 95% lower
        // bound clears 55%; the blended model still applies the normal gates.
        return OnlineLearner.wilsonLower(directionalWins,n,1.959963984540054)>=.55;
    }

    private void record(String key, boolean up) {
        int u = p.getInt(key + "_u", 0) + (up ? 1 : 0);
        int d = p.getInt(key + "_d", 0) + (up ? 0 : 1);
        String keys = p.getString(KEYS, "");
        if (!("|" + keys + "|").contains("|" + key + "|")) {
            ArrayList<String> list = new ArrayList<>();
            if (!keys.isEmpty()) for (String s : keys.split("\\|")) if (!s.isEmpty()) list.add(s);
            list.add(key);
            while (list.size() > MAX_BUCKETS) {
                String drop = list.remove(0);
                p.edit().remove(drop + "_u").remove(drop + "_d").apply();
            }
            StringBuilder b = new StringBuilder();
            for (String s : list) { if (b.length() > 0) b.append('|'); b.append(s); }
            keys = b.toString();
        }
        p.edit().putInt(key + "_u", u).putInt(key + "_d", d).putString(KEYS, keys).apply();
    }

    private String statKey(String asset, int h, String bucket) {
        return normAsset(asset) + "_H" + h + "_" + bucket;
    }

    private static String normAsset(String x) {
        if (x == null || x.trim().isEmpty()) return "AUTO_CHART";
        return x.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9]+", "_");
    }

    private List<Pending> loadPending() {
        ArrayList<Pending> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString(PENDING, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Pending x = new Pending();
                x.createdAt = o.optLong("c", 0L);
                x.dueAt = o.optLong("d", 0L);
                x.horizon = o.optInt("h", -1);
                x.entryY = o.optDouble("y", .5);
                x.asset = o.optString("a", "AUTO_CHART");
                x.statKey = o.optString("k", "");
                if (x.createdAt > 0L && x.dueAt > 0L && x.horizon >= 0 && !x.statKey.isEmpty()) out.add(x);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void savePending(List<Pending> list) {
        JSONArray a = new JSONArray();
        try {
            for (Pending x : list) {
                JSONObject o = new JSONObject();
                o.put("c", x.createdAt); o.put("d", x.dueAt); o.put("h", x.horizon);
                o.put("y", x.entryY); o.put("a", x.asset); o.put("k", x.statKey);
                a.put(o);
            }
            p.edit().putString(PENDING, a.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private static final class Pending {
        long createdAt, dueAt;
        int horizon;
        double entryY;
        String asset, statKey;
    }
}
