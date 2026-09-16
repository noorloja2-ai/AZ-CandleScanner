package com.example.floatingcandlescanner;

import java.util.Locale;

/**
 * Fast live decision gate for the broker board.
 *
 * This engine never creates learning labels and never stores screenshots. It only
 * watches repeated 1-second board states and exposes a provisional BUY/SELL when
 * the same direction remains stable and several independent confirmations agree.
 * The official completed-candle signal remains separate.
 */
public final class QuickDecisionEngine {
    public static final class Result {
        public final String label;
        public final int score;
        public final int stableScans;
        public final int confirmations;
        public final String reason;
        public final boolean highChance;

        Result(String label, int score, int stableScans, int confirmations,
               String reason, boolean highChance) {
            this.label = label;
            this.score = score;
            this.stableScans = stableScans;
            this.confirmations = confirmations;
            this.reason = reason;
            this.highChance = highChance;
        }

        public static Result waitResult(String reason) {
            return new Result("WAIT", 50, 0, 0, reason, false);
        }
    }

    private String lastAsset = "";
    private int lastHorizon = -1;
    private String lastSide = "";
    private int stableScans = 0;
    private double emaLead = .50;
    private double emaContext = .50;
    private long lastAt = 0L;

    public synchronized void reset() {
        lastAsset = "";
        lastHorizon = -1;
        lastSide = "";
        stableScans = 0;
        emaLead = .50;
        emaContext = .50;
        lastAt = 0L;
    }

    public synchronized Result update(String asset, int horizon,
                                      CandleVision.BoardState s,
                                      SignalResult base,
                                      int thresholdPct) {
        if (s == null || base == null || horizon < 1 || horizon > 5)
            return Result.waitResult("NO LIVE BOARD STATE");

        long now = System.currentTimeMillis();
        String a = asset == null ? "AUTO_CHART" : asset.trim().toUpperCase(Locale.US);
        if (!a.equals(lastAsset) || horizon != lastHorizon || (lastAt > 0L && now - lastAt > 4200L)) {
            reset();
            lastAsset = a;
            lastHorizon = horizon;
        }
        lastAt = now;

        double model = (base.buyProbability - base.sellProbability) / 100.0;
        String side = model >= 0 ? "BUY" : "SELL";
        double sign = model >= 0 ? 1.0 : -1.0;
        double lead = Math.max(base.buyProbability, base.sellProbability) / 100.0;

        int confirmations = 0;
        double context = .50;
        StringBuilder why = new StringBuilder();

        // 1) Base model already has a completed multi-engine directional label.
        if (side.equals(base.label)) {
            confirmations++;
            context += .08;
            add(why, "MODEL");
        }

        // 2) Broader board trend agrees.
        if (Math.abs(s.trend) >= .12 && Math.signum(s.trend) == sign) {
            confirmations++;
            context += .08 + Math.min(.06, Math.abs(s.trend) * .10);
            add(why, "TREND");
        }

        // 3) Short momentum agrees.
        if (Math.abs(s.momentum) >= .12 && Math.signum(s.momentum) == sign) {
            confirmations++;
            context += .08 + Math.min(.06, Math.abs(s.momentum) * .10);
            add(why, "MOMENTUM");
        }

        // 4) Recent sequence flow agrees (several candles, not one pixel cluster).
        if (Math.abs(s.sequenceBias) >= .13 && Math.signum(s.sequenceBias) == sign) {
            confirmations++;
            context += .07 + Math.min(.05, Math.abs(s.sequenceBias) * .09);
            add(why, "FLOW");
        }

        // 5) Candle body / rejection geometry agrees.
        boolean geometry = false;
        if (sign > 0) {
            geometry = (s.lastDirection > .14 && s.body >= .38)
                    || (s.lowerWick >= .38 && s.lowerWick > s.upperWick * 1.35 && s.pricePosition <= .45);
        } else {
            geometry = (s.lastDirection < -.14 && s.body >= .38)
                    || (s.upperWick >= .38 && s.upperWick > s.lowerWick * 1.35 && s.pricePosition >= .55);
        }
        if (geometry) {
            confirmations++;
            context += .10;
            add(why, "CANDLE");
        }

        // 6) Acceleration is useful only when it supports, rather than chases,
        // the same direction. Small acceleration is treated as neutral.
        if (Math.abs(s.acceleration) >= .10 && Math.signum(s.acceleration) == sign) {
            confirmations++;
            context += .07;
            add(why, "ACCEL");
        }

        // Avoid calling a fast entry during visually extreme/spiky or dead states.
        boolean volatilityOk = s.volatilityRatio >= .48 && s.volatilityRatio <= 1.90;
        boolean dojiRisk = s.body < .13 && Math.max(s.upperWick, s.lowerWick) < .58;
        if (!volatilityOk) context -= .14;
        if (dojiRisk) context -= .12;

        context = clamp(context, .05, .95);
        emaLead = .62 * emaLead + .38 * lead;
        emaContext = .62 * emaContext + .38 * context;

        // Direction must remain the same across repeated screenshots. A flip resets
        // the streak so one noisy frame cannot create a QUICK signal.
        if (side.equals(lastSide)) stableScans = Math.min(9, stableScans + 1);
        else {
            lastSide = side;
            stableScans = 1;
        }

        int threshold = Math.max(78, Math.min(90, thresholdPct));
        double fused = clamp(.72 * emaLead + .28 * emaContext, .50, .91);
        int score = (int)Math.round(fused * 100.0);

        boolean highChance = score >= threshold
                && confirmations >= 4
                && stableScans >= 3
                && volatilityOk
                && !dojiRisk;

        // If the base engine is WAIT, require an even stronger live consensus.
        if ("WAIT".equals(base.label)) {
            highChance = highChance && confirmations >= 5 && score >= Math.max(threshold, 84);
        }

        String reason = (why.length() == 0 ? "NO CONFIRMATION" : why.toString())
                + " • STABLE " + stableScans + "/3"
                + " • " + confirmations + "/6";
        return new Result(highChance ? side : "WAIT", score, stableScans,
                confirmations, reason, highChance);
    }

    private static void add(StringBuilder b, String x) {
        if (b.length() > 0) b.append('+');
        b.append(x);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
