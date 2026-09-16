package com.example.floatingcandlescanner;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Community learning transport.
 *
 * Privacy/security design:
 * - No screenshots, credentials, Android identifiers or broker-login data are sent.
 * - Only resolved numeric/model context is queued.
 * - No GitHub write token is embedded in the APK.
 * - GitHub is the public control/model source. A protected submit gateway receives
 *   anonymous batches, validates/aggregates them, then publishes community-model.json
 *   to GitHub using server-side credentials.
 */
public final class CommunityLearningSync {
    private static final String PREF = "community_learning_v1";
    private static final String CONFIG_URL =
            "https://raw.githubusercontent.com/noorloja2-ai/AZ-CandleScanner/main/community-learning.json";
    private static final String DEFAULT_MODEL_URL =
            "https://raw.githubusercontent.com/noorloja2-ai/AZ-CandleScanner/main/community-model.json";
    private static final int MAX_QUEUE = 200;
    private static final long CONFIG_REFRESH_MS = 6L * 60L * 60L * 1000L;
    private static final long MODEL_REFRESH_MS = 15L * 60L * 1000L;
    private static final long UPLOAD_RETRY_MS = 60L * 1000L;
    private static final int UPLOAD_BATCH_TRIGGER = 10;
    private static final Object LOCK = new Object();
    private static boolean syncRunning = false;

    private CommunityLearningSync() {}

    /** Shared learning is automatic in v14.5. Kept as a method for source compatibility. */
    public static boolean enabled(Context context) {
        return true;
    }

    /** v14.5 does not expose an OFF switch; calling this simply starts a sync. */
    public static void setEnabled(Context context, boolean ignored) {
        if (context != null) refreshAndFlushAsync(context);
    }

    /** Queue one resolved result. No screenshot pixels or user identifiers are included. */
    public static void queueResolved(Context context, TrainingStore.Pending p,
                                     boolean outcomeUp, boolean correct) {
        if (context == null || p == null) return;
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            try {
                SharedPreferences prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
                JSONArray q = new JSONArray(prefs.getString("queue", "[]"));
                JSONObject e = new JSONObject();
                e.put("id", UUID.randomUUID().toString());
                e.put("schema", 1);
                e.put("asset", safeAsset(p.asset));
                e.put("timeframe_minutes", p.timeframeMinutes);
                e.put("horizon_index", p.horizon);
                e.put("raw_buy_probability", round6(p.rawBuyP));
                e.put("displayed_buy_probability", round6(p.displayedBuyP));
                e.put("predicted", p.displayedBuyP >= 0.5 ? "BUY" : "SELL");
                e.put("outcome", outcomeUp ? "UP" : "DOWN");
                e.put("correct", correct);
                e.put("setup", safeText(p.setup, 64));
                e.put("regime", safeText(p.regime, 40));
                e.put("app_version", BuildConfig.VERSION_NAME);
                q.put(e);

                // Keep the newest MAX_QUEUE events if the phone was offline for a long time.
                JSONArray trimmed = new JSONArray();
                int start = Math.max(0, q.length() - MAX_QUEUE);
                for (int i = start; i < q.length(); i++) trimmed.put(q.get(i));
                prefs.edit().putString("queue", trimmed.toString()).apply();
            } catch (Exception ignored) {}
        }
        refreshAndFlushAsync(app);
    }

    /**
     * Automatic background sync. GitHub config is refreshed every 6h and the shared
     * model every 15 minutes. Pending rows are uploaded through the protected gateway
     * when available; no GitHub credential is stored in the APK.
     */
    public static void refreshAndFlushAsync(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (syncRunning) return;
            syncRunning = true;
        }
        new Thread(() -> {
            try {
                SharedPreferences p = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
                long now = System.currentTimeMillis();
                long cfgAt = p.getLong("config_checked_at", 0L);
                long modelAt = p.getLong("model_checked_at", 0L);
                if (cfgAt == 0L || now - cfgAt >= CONFIG_REFRESH_MS) refreshConfig(app);
                if (modelAt == 0L || now - modelAt >= MODEL_REFRESH_MS) refreshModel(app);

                int pending = pendingCount(app);
                long uploadAt = p.getLong("last_upload_attempt_at", 0L);
                if (pending >= UPLOAD_BATCH_TRIGGER || uploadAt == 0L || now - uploadAt >= UPLOAD_RETRY_MS)
                    flush(app);
            } finally {
                synchronized (LOCK) { syncRunning = false; }
            }
        }, "community-learning-auto-sync").start();
    }

    private static void refreshConfig(Context context) {
        try {
            String body = httpGet(CONFIG_URL, 8000);
            JSONObject cfg = new JSONObject(body);
            String submit = cfg.optString("submit_url", "").trim();
            String model = cfg.optString("model_url", DEFAULT_MODEL_URL).trim();
            if (!submit.toLowerCase(Locale.US).startsWith("https://")) submit = "";
            if (!model.toLowerCase(Locale.US).startsWith("https://")) model = DEFAULT_MODEL_URL;
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putString("submit_url", submit)
                    .putString("model_url", model)
                    .putLong("config_checked_at", System.currentTimeMillis())
                    .apply();
        } catch (Exception e) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putString("last_error", "GitHub config unavailable: " + safeError(e))
                    .putLong("config_checked_at", System.currentTimeMillis())
                    .apply();
        }
    }

    private static void refreshModel(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String url = p.getString("model_url", DEFAULT_MODEL_URL);
        if (url == null || !url.toLowerCase(Locale.US).startsWith("https://")) return;
        try {
            String body = httpGet(url, 9000);
            JSONObject model = new JSONObject(body);
            if (model.optInt("schema", 0) != 1) throw new Exception("unsupported model schema");
            p.edit().putString("model_json", model.toString())
                    .putLong("model_updated_at", System.currentTimeMillis())
                    .putLong("model_checked_at", System.currentTimeMillis())
                    .remove("last_error").apply();
        } catch (Exception e) {
            // A missing model is normal until the first aggregate is published.
            p.edit().putString("last_error", "Community model unavailable: " + safeError(e))
                    .putLong("model_checked_at", System.currentTimeMillis()).apply();
        }
    }

    private static void flush(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        p.edit().putLong("last_upload_attempt_at", System.currentTimeMillis()).apply();
        String submitUrl = p.getString("submit_url", "");
        if (submitUrl == null || !submitUrl.toLowerCase(Locale.US).startsWith("https://")) return;

        JSONArray snapshot;
        synchronized (LOCK) {
            try {
                snapshot = new JSONArray(p.getString("queue", "[]"));
            } catch (Exception e) { return; }
        }
        if (snapshot.length() == 0) return;

        try {
            JSONObject payload = new JSONObject();
            payload.put("schema", 1);
            payload.put("source", "CandleScanner");
            payload.put("app_version", BuildConfig.VERSION_NAME);
            payload.put("events", snapshot);
            httpPostJson(submitUrl, payload.toString(), 10000);

            synchronized (LOCK) {
                // Remove only the exact IDs that were acknowledged in this snapshot.
                JSONArray current = new JSONArray(p.getString("queue", "[]"));
                java.util.HashSet<String> sent = new java.util.HashSet<>();
                for (int i = 0; i < snapshot.length(); i++)
                    sent.add(snapshot.getJSONObject(i).optString("id", ""));
                JSONArray keep = new JSONArray();
                for (int i = 0; i < current.length(); i++) {
                    JSONObject row = current.getJSONObject(i);
                    if (!sent.contains(row.optString("id", ""))) keep.put(row);
                }
                p.edit().putString("queue", keep.toString())
                        .putLong("last_upload_at", System.currentTimeMillis())
                        .remove("last_error").apply();
            }
        } catch (Exception e) {
            p.edit().putString("last_error", "Community upload waiting: " + safeError(e)).apply();
        }
    }

    /**
     * Blend a validated GitHub community calibration with local calibration.
     * Expected model format:
     * {"schema":1,"assets":{"EUR_USD":{"M1":{"samples":100,"bias":0.1,"scale":1.02}},
     *                         "GLOBAL":{"M1":{...}}}}
     */
    public static double blendCommunity(Context context, String asset, int horizon,
                                        double rawP, double localP) {
        if (context == null) return localP;
        try {
            String text = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getString("model_json", "");
            if (text == null || text.isEmpty()) return localP;
            JSONObject model = new JSONObject(text);
            JSONObject assets = model.optJSONObject("assets");
            if (assets == null) return localP;
            String key = safeAsset(asset);
            JSONObject a = assets.optJSONObject(key);
            if (a == null) a = assets.optJSONObject("GLOBAL");
            if (a == null) return localP;
            JSONObject tf = a.optJSONObject("M" + (horizon + 1));
            if (tf == null) return localP;
            int samples = tf.optInt("samples", 0);
            if (samples < 50) return localP;
            double bias = clamp(tf.optDouble("bias", 0.0), -0.60, 0.60);
            double scale = clamp(tf.optDouble("scale", 1.0), 0.70, 1.35);
            double communityP = sigmoid(scale * logit(rawP) + bias);
            // Community data is useful but never allowed to dominate this phone's model.
            double weight = Math.min(0.25, 0.25 * samples / 800.0);
            return clamp(localP * (1.0 - weight) + communityP * weight, 0.10, 0.90);
        } catch (Exception ignored) {
            return localP;
        }
    }

    public static int pendingCount(Context context) {
        try {
            return new JSONArray(context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getString("queue", "[]")).length();
        } catch (Exception e) { return 0; }
    }

    public static String status(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        int pending = pendingCount(context);
        long model = p.getLong("model_updated_at", 0L);
        long upload = p.getLong("last_upload_at", 0L);
        String submit = p.getString("submit_url", "");
        StringBuilder s = new StringBuilder();
        s.append("AUTO");
        if (submit == null || submit.isEmpty()) s.append(" • GitHub download active / upload gateway waiting");
        else s.append(" • upload+download active");
        s.append(" • pending ").append(pending);
        if (model > 0) s.append(" • shared model loaded");
        if (upload > 0) s.append(" • uploaded before");
        return s.toString();
    }

    private static String httpGet(String url, int timeout) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "CandleScanner-Community-Learning/" + BuildConfig.VERSION_NAME);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            StringBuilder b = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = r.readLine()) != null) b.append(line);
            }
            return b.toString();
        } finally { if (c != null) c.disconnect(); }
    }

    private static void httpPostJson(String url, String body, int timeout) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "CandleScanner-Community-Learning/" + BuildConfig.VERSION_NAME);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        } finally { if (c != null) c.disconnect(); }
    }

    private static String safeAsset(String s) {
        if (s == null || s.trim().isEmpty()) return "AUTO_CHART";
        String x = s.trim().toUpperCase(Locale.US).replace('/', '_').replace(' ', '_');
        x = x.replaceAll("[^A-Z0-9_-]", "");
        return x.length() > 32 ? x.substring(0, 32) : x;
    }

    private static String safeText(String s, int max) {
        if (s == null) return "";
        String x = s.replace('\n', ' ').replace('\r', ' ').trim();
        return x.length() > max ? x.substring(0, max) : x;
    }

    private static double round6(double v) { return Math.round(v * 1_000_000d) / 1_000_000d; }
    private static double logit(double x) { x = clamp(x, 0.02, 0.98); return Math.log(x / (1 - x)); }
    private static double sigmoid(double x) { return 1.0 / (1.0 + Math.exp(-x)); }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static String safeError(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return e.getClass().getSimpleName();
        return m.length() > 100 ? m.substring(0, 100) : m;
    }
}
