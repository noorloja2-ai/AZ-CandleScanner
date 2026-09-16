package com.example.floatingcandlescanner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional live OHLC fusion using Twelve Data.
 *
 * This is standard market data, not a Pocket Option OTC feed.
 * M1/M5/M15 are cached to reduce rate-limit pressure.
 */
public class MarketDataService {
    public static class Candle {
        public final String time;
        public final double open, high, low, close;

        Candle(String time, double open, double high, double low, double close) {
            this.time = time;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
        }
    }

    public static class Bundle {
        public final List<Candle> m1;
        public final List<Candle> m5;
        public final List<Candle> m15;

        Bundle(List<Candle> m1, List<Candle> m5, List<Candle> m15) {
            this.m1 = m1;
            this.m5 = m5;
            this.m15 = m15;
        }
    }

    private static class CacheEntry {
        final long time;
        final List<Candle> data;
        CacheEntry(long time, List<Candle> data) {
            this.time = time;
            this.data = data;
        }
    }

    private final Map<String, CacheEntry> cache = new HashMap<>();

    public synchronized Bundle fetchAll(String apiKey, String symbol) throws Exception {
        List<Candle> m1 = fetch(apiKey, symbol, "1min", 240, 40_000L);
        List<Candle> m5 = fetch(apiKey, symbol, "5min", 240, 4 * 60_000L);
        List<Candle> m15 = fetch(apiKey, symbol, "15min", 240, 10 * 60_000L);
        return new Bundle(m1, m5, m15);
    }

    private List<Candle> fetch(String apiKey, String symbol, String interval,
                               int outputSize, long cacheMs) throws Exception {
        String cacheKey = symbol + "|" + interval;
        CacheEntry c = cache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (c != null && now - c.time < cacheMs) return c.data;

        String u = "https://api.twelvedata.com/time_series"
                + "?symbol=" + URLEncoder.encode(symbol, "UTF-8")
                + "&interval=" + URLEncoder.encode(interval, "UTF-8")
                + "&outputsize=" + outputSize
                + "&timezone=UTC&format=JSON"
                + "&apikey=" + URLEncoder.encode(apiKey, "UTF-8");

        HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
        con.setRequestMethod("GET");
        con.setConnectTimeout(8_000);
        con.setReadTimeout(10_000);
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("User-Agent", "M1M5-AI-Scanner/7.0");

        int code = con.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? con.getInputStream() : con.getErrorStream()));

        StringBuilder body = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) body.append(line);
        br.close();
        con.disconnect();

        JSONObject root = new JSONObject(body.toString());

        if (code == 429) {
            throw new Exception("Market data rate limit reached (HTTP 429). Visual AI is still available.");
        }
        if (code < 200 || code >= 300) {
            throw new Exception(root.optString("message", "Market data HTTP " + code));
        }
        if ("error".equalsIgnoreCase(root.optString("status"))) {
            throw new Exception(root.optString("message", "Market data error"));
        }

        JSONArray values = root.optJSONArray("values");
        if (values == null || values.length() < 55) {
            throw new Exception("Not enough " + interval + " OHLC candles returned.");
        }

        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject v = values.getJSONObject(i);
            out.add(new Candle(
                    v.optString("datetime", ""),
                    v.optDouble("open"),
                    v.optDouble("high"),
                    v.optDouble("low"),
                    v.optDouble("close")));
        }

        // ISO-like datetime strings sort chronologically.
        Collections.sort(out, Comparator.comparing(a -> a.time));
        cache.put(cacheKey, new CacheEntry(now, out));
        return out;
    }
}
