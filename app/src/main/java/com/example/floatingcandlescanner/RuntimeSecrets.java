package com.example.floatingcandlescanner;

/**
 * Keeps the market-data API key only in app process memory.
 * It is not written to SharedPreferences or bundled in the APK.
 */
public final class RuntimeSecrets {
    private static volatile String marketDataKey = "";

    private RuntimeSecrets() {}

    public static void setMarketDataKey(String key) {
        marketDataKey = key == null ? "" : key.trim();
    }

    public static String getMarketDataKey() {
        return marketDataKey;
    }

    public static void clear() {
        marketDataKey = "";
    }
}
