package com.example.floatingcandlescanner;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Keeps only numeric chart features/results. It does not save full screenshots.
 *
 * v2 learning samples are aligned to exact candle boundaries. A pending sample
 * is only resolved when its expected close boundary is actually scanned; if the
 * app misses that boundary, the stale sample is discarded instead of being
 * mislabeled from a later candle.
 */
public class TrainingStore {
    public static class Pending {
        public long createdAt;
        public long dueAt;
        public int horizon;
        public int timeframeMinutes;
        public double entryY;
        public double rawBuyP;
        public double displayedBuyP;
        public String asset;
        public String regime;
        public String setup;

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("t", createdAt);
            o.put("d", dueAt);
            o.put("h", horizon);
            o.put("m", timeframeMinutes);
            o.put("y", entryY);
            o.put("p", rawBuyP);
            o.put("q", displayedBuyP);
            o.put("a", asset);
            o.put("r", regime);
            o.put("s", setup);
            return o;
        }

        static Pending from(JSONObject o) {
            Pending x = new Pending();
            x.createdAt = o.optLong("t");
            x.dueAt = o.optLong("d", 0L);
            x.horizon = o.optInt("h");
            x.timeframeMinutes = o.optInt("m", x.horizon + 1);
            x.entryY = o.optDouble("y", 0.5);
            x.rawBuyP = o.optDouble("p", 0.5);
            x.displayedBuyP = o.optDouble("q", x.rawBuyP);
            x.asset = o.optString("a", "AUTO_CHART");
            x.regime = o.optString("r", "");
            x.setup = o.optString("s", "");
            return x;
        }
    }

    private final Context context;
    private final SharedPreferences prefs;

    public TrainingStore(Context c) {
        context = c.getApplicationContext();
        prefs = context.getSharedPreferences("training_store_v3", Context.MODE_PRIVATE);
    }

    public synchronized List<Pending> load() {
        List<Pending> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("pending", "[]"));
            for (int i=0; i<a.length(); i++) {
                Pending p = Pending.from(a.getJSONObject(i));
                // Old/un-aligned records are unsafe for learning and are ignored.
                if (p.dueAt > 0L) out.add(p);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public synchronized void save(List<Pending> list) {
        JSONArray a = new JSONArray();
        try {
            for (Pending p : list) a.put(p.json());
            prefs.edit().putString("pending", a.toString()).commit();
        } catch (Exception ignored) {}
    }

    /** Add exactly one prediction for the currently active chart timeframe. */
    public synchronized void addPrediction(long boundary, String asset,
                                           int horizonIndex, int timeframeMinutes,
                                           double entryY, SignalResult result) {
        if (result == null || boundary <= 0L || timeframeMinutes <= 0) return;
        List<Pending> list = load();

        // Avoid duplicate rows when Android retries the same close screenshot.
        Iterator<Pending> dup = list.iterator();
        while (dup.hasNext()) {
            Pending old = dup.next();
            if (old.createdAt == boundary && old.horizon == horizonIndex &&
                    safeAsset(old.asset).equals(safeAsset(asset))) dup.remove();
        }

        Pending p = new Pending();
        p.createdAt = boundary;
        p.dueAt = boundary + timeframeMinutes * 60_000L;
        p.horizon = horizonIndex;
        p.timeframeMinutes = timeframeMinutes;
        p.entryY = entryY;
        p.rawBuyP = result.rawBuyProbability;
        p.displayedBuyP = result.buyProbability / 100.0;
        p.asset = safeAsset(asset);
        p.regime = result.regime == null ? "" : result.regime;
        p.setup = setupName(result.structure);
        list.add(p);

        // A missed boundary must never be labeled later. Keep only near-future
        // samples plus the just-created one.
        long cutoff = boundary - 10L * 60L * 1000L;
        Iterator<Pending> it = list.iterator();
        while (it.hasNext()) {
            Pending x = it.next();
            if (x.dueAt < cutoff) it.remove();
        }
        save(list);
    }

    public synchronized void appendResolved(Pending p, double exitY,
                                            boolean outcomeUp, boolean correct) {
        try {
            File dir = new File(context.getFilesDir(), "training");
            if (!dir.exists()) dir.mkdirs();
            File csv = new File(dir, "training_samples_v3.csv");
            boolean fresh = !csv.exists();
            FileWriter w = new FileWriter(csv, true);
            if (fresh) {
                w.write("created_at,due_at,asset,timeframe_minutes,horizon_index,entry_y,exit_y,raw_buy_probability,displayed_buy_probability,predicted,outcome,correct,setup,regime\n");
            }
            String predicted = p.displayedBuyP >= 0.5 ? "BUY" : "SELL";
            w.write(String.format(Locale.US,
                    "%d,%d,%s,%d,%d,%.6f,%.6f,%.6f,%.6f,%s,%s,%s,%s,%s\n",
                    p.createdAt, p.dueAt, csvSafe(p.asset), p.timeframeMinutes,
                    p.horizon, p.entryY, exitY, p.rawBuyP, p.displayedBuyP,
                    predicted, outcomeUp ? "UP" : "DOWN", correct ? "1" : "0",
                    csvSafe(p.setup), csvSafe(p.regime)));
            w.close();
        } catch (Exception ignored) {}
    }

    public File csvFile() {
        return new File(new File(context.getFilesDir(), "training"),
                "training_samples_v3.csv");
    }

    public synchronized int resolvedRowCount() {
        File dir = new File(context.getFilesDir(), "training");
        return countRows(new File(dir,"training_samples_v2.csv")) +
                countRows(new File(dir,"training_samples_v3.csv"));
    }

    private int countRows(File f){
        if (!f.exists()) return 0;
        int rows = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line; boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first) { first = false; continue; }
                if (!line.trim().isEmpty()) rows++;
            }
        } catch (Exception ignored) {}
        return rows;
    }

    public synchronized int pendingCount() { return load().size(); }

    public void clearPending() {
        prefs.edit().remove("pending").commit();
        // Also clear old v1 pending records so an upgrade can never resolve them.
        context.getSharedPreferences("training_store", Context.MODE_PRIVATE)
                .edit().remove("pending").commit();
        context.getSharedPreferences("training_store_v2", Context.MODE_PRIVATE)
                .edit().remove("pending").commit();
    }

    private static String setupName(String s){
        if(s==null||s.trim().isEmpty())return "MULTI-FACTOR";
        String x=s.trim();
        int cut=x.indexOf(" • ");
        if(cut>0)x=x.substring(0,cut);
        if(x.length()>64)x=x.substring(0,64);
        return x;
    }

    private static String safeAsset(String s) {
        if (s == null || s.trim().isEmpty()) return "AUTO_CHART";
        return s.trim().toUpperCase(Locale.US);
    }

    private static String csvSafe(String s) {
        if (s == null) return "";
        return s.replace(',', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
