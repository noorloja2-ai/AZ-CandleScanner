package com.example.floatingcandlescanner;

import java.util.List;
import java.util.Locale;

/**
 * Candle-history entry filter.
 *
 * This engine does not treat one candle as a prediction.  It scores the latest
 * completed candle pattern together with recent trend/structure, visual
 * support/resistance position, momentum and confirmation.  The named setup is
 * returned to TrainingStore through TradingBrain, so the existing OnlineLearner
 * can learn which setup families actually resolve correctly on each asset and
 * timeframe.
 */
public final class HistoricalEntryEngine {
    private HistoricalEntryEngine() {}

    public static final class Result {
        public final double directionalScore; // -1 bearish .. +1 bullish
        public final double confidence;       // 0..1
        public final double uncertainty;      // 0..1
        public final int confirmations;       // 0..4
        public final String name;
        public final String summary;
        public final boolean keyLevel;

        Result(double directionalScore, double confidence, double uncertainty,
               int confirmations, String name, String summary, boolean keyLevel) {
            this.directionalScore = clamp(directionalScore, -1, 1);
            this.confidence = clamp(confidence, 0, 1);
            this.uncertainty = clamp(uncertainty, 0, 1);
            this.confirmations = Math.max(0, Math.min(4, confirmations));
            this.name = name == null ? "HISTORY" : name;
            this.summary = summary == null ? "" : summary;
            this.keyLevel = keyLevel;
        }

        public boolean directional() { return Math.abs(directionalScore) >= .24; }
        public boolean strong() {
            return Math.abs(directionalScore) >= .58 && confidence >= .58 && confirmations >= 3;
        }
    }

    public static Result analyze(List<Double> d, List<Double> y, List<Double> r,
                                 List<Double> body, List<Double> upper, List<Double> lower,
                                 int s, int k, double trend) {
        if (d == null || y == null || r == null || k < 3) {
            return new Result(0, .10, .85, 0, "HISTORY LOW DATA",
                    "Not enough completed candles for the history gate.", false);
        }

        int end = s + k - 1;
        double now = safe(d, end);
        double prev = safe(d, end - 1);
        double prev2 = safe(d, end - 2);
        double bodyNow = safe(body, end);
        double upperNow = safe(upper, end);
        double lowerNow = safe(lower, end);
        double rangeNow = Math.max(.0001, safe(r, end));
        double rangePrev = Math.max(.0001, safe(r, end - 1));

        // Price location inside the recent visual range.  y grows downward on
        // screen, so 0 is near support/lows and 1 is near resistance/highs.
        int look = Math.min(k, 20);
        int ls = s + k - look;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int i = ls; i <= end; i++) {
            double v = safe(y, i);
            minY = Math.min(minY, v);
            maxY = Math.max(maxY, v);
        }
        double span = Math.max(.0001, maxY - minY);
        double pricePos = clamp((maxY - safe(y, end)) / span, 0, 1);
        boolean atSupport = pricePos <= .22;
        boolean atResistance = pricePos >= .78;
        boolean keyLevel = atSupport || atResistance;

        double recent3 = weightedDir(d, Math.max(s, end - 2), end);
        double recent5 = weightedDir(d, Math.max(s, end - 4), end);
        double recentBeforeNow = weightedDir(d, Math.max(s, end - 5), end - 1);

        String name = "HISTORY CONFLUENCE";
        double score = .30 * recent5 + .28 * trend;
        double patternStrength = 0;
        boolean reversalPattern = false;
        boolean continuationPattern = false;

        boolean bullEngulf = prev < -.16 && now > .22 && rangeNow >= rangePrev * .92
                && bodyNow >= .38;
        boolean bearEngulf = prev > .16 && now < -.22 && rangeNow >= rangePrev * .92
                && bodyNow >= .38;
        boolean hammer = lowerNow >= .42 && upperNow <= .30 && bodyNow >= .18
                && (atSupport || trend < -.18 || recentBeforeNow < -.20);
        boolean shootingStar = upperNow >= .42 && lowerNow <= .30 && bodyNow >= .18
                && (atResistance || trend > .18 || recentBeforeNow > .20);
        boolean morningStar = prev2 < -.24 && Math.abs(prev) <= .22 && now > .28
                && (atSupport || recentBeforeNow < -.18);
        boolean eveningStar = prev2 > .24 && Math.abs(prev) <= .22 && now < -.28
                && (atResistance || recentBeforeNow > .18);

        if (morningStar) {
            name = "MORNING STAR + SUPPORT"; score += .86; patternStrength = .92; reversalPattern = true;
        } else if (eveningStar) {
            name = "EVENING STAR + RESISTANCE"; score -= .86; patternStrength = .92; reversalPattern = true;
        } else if (bullEngulf && (atSupport || recentBeforeNow < -.12)) {
            name = "BULLISH ENGULFING + SUPPORT"; score += .76; patternStrength = .84; reversalPattern = true;
        } else if (bearEngulf && (atResistance || recentBeforeNow > .12)) {
            name = "BEARISH ENGULFING + RESISTANCE"; score -= .76; patternStrength = .84; reversalPattern = true;
        } else if (hammer) {
            name = "HAMMER REJECTION + SUPPORT"; score += .66; patternStrength = .76; reversalPattern = true;
        } else if (shootingStar) {
            name = "SHOOTING STAR + RESISTANCE"; score -= .66; patternStrength = .76; reversalPattern = true;
        } else if (recent3 > .34 && trend > .18 && !atResistance && bodyNow >= .28) {
            name = "BULLISH MOMENTUM CONTINUATION"; score += .54; patternStrength = .67; continuationPattern = true;
        } else if (recent3 < -.34 && trend < -.18 && !atSupport && bodyNow >= .28) {
            name = "BEARISH MOMENTUM CONTINUATION"; score -= .54; patternStrength = .67; continuationPattern = true;
        }

        // Break/retest proxy: current direction resumes the broader trend after
        // a one/two-candle counter-move near the recent range edge.
        boolean bullRetest = trend > .24 && prev < -.10 && now > .20 && pricePos > .36 && pricePos < .82;
        boolean bearRetest = trend < -.24 && prev > .10 && now < -.20 && pricePos > .18 && pricePos < .64;
        if (!reversalPattern && bullRetest) {
            name = "BULLISH BREAKOUT RETEST"; score += .60; patternStrength = Math.max(patternStrength, .72); continuationPattern = true;
        } else if (!reversalPattern && bearRetest) {
            name = "BEARISH BREAKDOWN RETEST"; score -= .60; patternStrength = Math.max(patternStrength, .72); continuationPattern = true;
        }

        // Opposing extreme is a risk for continuation; supporting extreme helps
        // reversal patterns.  This is the level part of the 3-factor gate.
        if (score > 0) {
            if (atSupport) score += reversalPattern ? .22 : .08;
            if (atResistance && continuationPattern) score -= .28;
        } else if (score < 0) {
            if (atResistance) score -= reversalPattern ? .22 : .08;
            if (atSupport && continuationPattern) score += .28;
        }

        double dir = Math.tanh(score);
        int confirmations = 0;
        if (patternStrength >= .55) confirmations++;                 // candle setup
        if ((dir > 0 && (atSupport || !reversalPattern)) ||
                (dir < 0 && (atResistance || !reversalPattern))) confirmations++; // level/context
        if (Math.signum(dir) == Math.signum(trend) && Math.abs(trend) >= .16) confirmations++; // trend/structure
        if (Math.signum(dir) == Math.signum(recent3) && Math.abs(recent3) >= .16) confirmations++; // momentum

        // A reversal may legitimately oppose the old trend, but it needs both a
        // key level and a strong reversal candle before counting as confirmed.
        if (reversalPattern && keyLevel && patternStrength >= .72) {
            if (confirmations < 3) confirmations = 3;
        }

        double indecision = clamp(1.0 - Math.max(Math.abs(now), bodyNow), 0, 1);
        double confidence = clamp(.28 + .34 * Math.abs(dir) + .08 * confirmations
                + .18 * patternStrength - .24 * indecision, .08, .96);
        double uncertainty = clamp(.72 - .13 * confirmations - .22 * patternStrength
                + .22 * indecision, .05, .92);

        String side = dir > .12 ? "BUY" : dir < -.12 ? "SELL" : "WAIT";
        String level = atSupport ? "support" : atResistance ? "resistance" : "mid-range";
        String summary = String.format(Locale.US,
                "%s: %s bias, %d/4 confirmations, %s context.",
                name, side, confirmations, level);

        return new Result(dir, confidence, uncertainty, confirmations, name, summary, keyLevel);
    }

    private static double weightedDir(List<Double> d, int a, int b) {
        if (a > b) return 0;
        double sum = 0, wsum = 0;
        int n = Math.max(1, b - a + 1);
        for (int i = a; i <= b; i++) {
            double w = .55 + .45 * ((double)(i - a + 1) / n);
            sum += safe(d, i) * w;
            wsum += w;
        }
        return wsum == 0 ? 0 : clamp(sum / wsum, -1, 1);
    }

    private static double safe(List<Double> x, int i) {
        if (x == null || i < 0 || i >= x.size() || x.get(i) == null) return 0;
        double v = x.get(i);
        return Double.isFinite(v) ? v : 0;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
