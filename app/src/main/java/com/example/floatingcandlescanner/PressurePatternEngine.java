package com.example.floatingcandlescanner;

import java.util.List;
import java.util.Locale;

/**
 * v14.8 buyer/seller pressure + candlestick context engine.
 *
 * The scanner only has visual candle proxies (direction, body, wicks, range and
 * vertical chart position), not broker OHLC values.  For that reason every
 * pattern is treated as evidence, not a guaranteed forecast.  The engine
 * deliberately combines the pattern with support/resistance, trend, momentum
 * and wick rejection before it can become a strong directional vote.
 */
public final class PressurePatternEngine {
    private PressurePatternEngine() {}

    public static final class Result {
        public final double directionalScore; // -1 SELL pressure .. +1 BUY pressure
        public final double confidence;       // 0..1
        public final double uncertainty;      // 0..1
        public final int buyerPressure;       // 0..100
        public final int sellerPressure;      // 0..100
        public final int confirmations;       // 0..5
        public final String name;
        public final String summary;
        public final boolean keyLevel;
        public final boolean reversal;
        public final boolean continuation;

        Result(double directionalScore, double confidence, double uncertainty,
               int buyerPressure, int sellerPressure, int confirmations,
               String name, String summary, boolean keyLevel,
               boolean reversal, boolean continuation) {
            this.directionalScore = clamp(directionalScore, -1, 1);
            this.confidence = clamp(confidence, 0, 1);
            this.uncertainty = clamp(uncertainty, 0, 1);
            this.buyerPressure = Math.max(0, Math.min(100, buyerPressure));
            this.sellerPressure = Math.max(0, Math.min(100, sellerPressure));
            this.confirmations = Math.max(0, Math.min(5, confirmations));
            this.name = name == null ? "PRESSURE" : name;
            this.summary = summary == null ? "" : summary;
            this.keyLevel = keyLevel;
            this.reversal = reversal;
            this.continuation = continuation;
        }

        public boolean directional() { return Math.abs(directionalScore) >= .24; }
        public boolean strong() {
            return Math.abs(directionalScore) >= .58 && confidence >= .58 && confirmations >= 3;
        }
    }

    public static Result analyze(List<Double> d, List<Double> y, List<Double> r,
                                 List<Double> body, List<Double> upper, List<Double> lower,
                                 int s, int k, double trend) {
        if (d == null || y == null || r == null || body == null || upper == null || lower == null || k < 3) {
            return new Result(0, .10, .90, 50, 50, 0,
                    "PRESSURE LOW DATA", "Not enough completed candles.", false, false, false);
        }

        int e = s + k - 1;
        double n = safe(d, e), p1 = safe(d, e - 1), p2 = safe(d, e - 2);
        double b0 = safe(body, e), b1 = safe(body, e - 1), b2 = safe(body, e - 2);
        double u0 = safe(upper, e), l0 = safe(lower, e);
        double rr0 = Math.max(.0001, safe(r, e));
        double rr1 = Math.max(.0001, safe(r, e - 1));

        int look = Math.min(k, 20);
        int ls = e - look + 1;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int i = ls; i <= e; i++) {
            double yy = safe(y, i);
            minY = Math.min(minY, yy);
            maxY = Math.max(maxY, yy);
        }
        double span = Math.max(.0001, maxY - minY);
        // Screen Y is inverted: lower y = higher market price.
        double pricePos = clamp((maxY - safe(y, e)) / span, 0, 1);
        boolean atSupport = pricePos <= .24;
        boolean atResistance = pricePos >= .76;
        boolean keyLevel = atSupport || atResistance;

        double recent3 = weightedDirection(d, body, r, Math.max(s, e - 2), e);
        double recent5 = weightedDirection(d, body, r, Math.max(s, e - 4), e);
        double prior5 = weightedDirection(d, body, r, Math.max(s, e - 6), Math.max(s, e - 1));

        // Buyer/seller pressure from recent body participation + wick rejection.
        double flow = weightedDirection(d, body, r, Math.max(s, e - 5), e);
        double wick = 0, wsum = 0;
        for (int i = Math.max(s, e - 5); i <= e; i++) {
            double age = .58 + .42 * ((double)(i - Math.max(s, e - 5) + 1) / Math.max(1, e - Math.max(s, e - 5) + 1));
            // Lower wick = rejection of lower prices (buyers); upper wick = sellers.
            wick += age * (safe(lower, i) - safe(upper, i));
            wsum += age;
        }
        wick = wsum == 0 ? 0 : clamp(wick / wsum * 1.6, -1, 1);
        double levelPressure = atSupport ? .24 : atResistance ? -.24 : 0;
        double basePressure = clamp(.48 * flow + .22 * wick + .18 * trend + .12 * levelPressure, -1, 1);

        String name = "BUYER/SELLER PRESSURE";
        double pattern = 0;
        double patternStrength = 0;
        boolean reversal = false;
        boolean continuation = false;
        boolean indecisionPattern = false;

        // Single-candle families seen in the user's added references.
        boolean doji = b0 <= .16;
        boolean spinning = b0 <= .30 && u0 >= .22 && l0 >= .22;
        boolean bullMarubozu = n > .22 && b0 >= .68 && u0 <= .18 && l0 <= .18;
        boolean bearMarubozu = n < -.22 && b0 >= .68 && u0 <= .18 && l0 <= .18;
        boolean dragonfly = doji && l0 >= .55 && u0 <= .18;
        boolean gravestone = doji && u0 >= .55 && l0 <= .18;
        boolean hammer = l0 >= .46 && l0 >= u0 * 1.6 && b0 <= .46 && (atSupport || prior5 < -.16 || trend < -.16);
        boolean invertedHammer = u0 >= .46 && u0 >= l0 * 1.6 && b0 <= .46 && (atSupport || prior5 < -.18 || trend < -.18);
        boolean hangingMan = l0 >= .46 && l0 >= u0 * 1.6 && b0 <= .46 && (atResistance || prior5 > .18 || trend > .18);
        boolean shootingStar = u0 >= .46 && u0 >= l0 * 1.6 && b0 <= .46 && (atResistance || prior5 > .18 || trend > .18);

        // Two-candle families. Visual proxies cannot know exact open/close gaps,
        // so the range/body relationship is used conservatively.
        boolean bullEngulf = p1 < -.16 && n > .20 && b0 >= Math.max(.34, b1 * .88) && rr0 >= rr1 * .90;
        boolean bearEngulf = p1 > .16 && n < -.20 && b0 >= Math.max(.34, b1 * .88) && rr0 >= rr1 * .90;
        boolean bullHarami = p1 < -.22 && n > .08 && b1 >= .45 && b0 <= b1 * .72 && rr0 <= rr1 * .82;
        boolean bearHarami = p1 > .22 && n < -.08 && b1 >= .45 && b0 <= b1 * .72 && rr0 <= rr1 * .82;
        boolean piercing = p1 < -.25 && n > .18 && b0 >= .34 && rr0 >= rr1 * .62 && (atSupport || prior5 < -.15);
        boolean darkCloud = p1 > .25 && n < -.18 && b0 >= .34 && rr0 >= rr1 * .62 && (atResistance || prior5 > .15);
        boolean bullKicker = p1 < -.35 && n > .40 && b0 >= .58 && b1 >= .48;
        boolean bearKicker = p1 > .35 && n < -.40 && b0 >= .58 && b1 >= .48;

        // Tweezer proxy: adjacent highs/lows are visually close and directions oppose.
        double low0 = safe(y, e) + rr0 * .5, low1 = safe(y, e - 1) + rr1 * .5;
        double high0 = safe(y, e) - rr0 * .5, high1 = safe(y, e - 1) - rr1 * .5;
        boolean tweezerBottom = p1 < -.12 && n > .12 && Math.abs(low0 - low1) <= Math.max(.012, (rr0 + rr1) * .20) && (atSupport || prior5 < -.12);
        boolean tweezerTop = p1 > .12 && n < -.12 && Math.abs(high0 - high1) <= Math.max(.012, (rr0 + rr1) * .20) && (atResistance || prior5 > .12);

        // Three-candle families.
        boolean morningStar = p2 < -.22 && Math.abs(p1) <= .20 && n > .24 && (atSupport || prior5 < -.14);
        boolean eveningStar = p2 > .22 && Math.abs(p1) <= .20 && n < -.24 && (atResistance || prior5 > .14);
        // Three White Soldiers / Three Black Crows require three substantial
        // completed bodies, progressive closes and no sharp loss of body size.
        // Three same-colour candles alone are not enough.
        boolean bullProgress = safe(y, e - 2) > safe(y, e - 1) && safe(y, e - 1) > safe(y, e);
        boolean bearProgress = safe(y, e - 2) < safe(y, e - 1) && safe(y, e - 1) < safe(y, e);
        boolean bullStalled = b2 > b1 * 1.18 && b1 > b0 * 1.12;
        boolean bearStalled = b2 > b1 * 1.18 && b1 > b0 * 1.12;
        boolean threeWhite = p2 > .16 && p1 > .16 && n > .16
                && b2 >= .38 && b1 >= .34 && b0 >= .34
                && b1 >= b2 * .55 && b0 >= b1 * .55
                && bullProgress && !bullStalled;
        boolean threeBlack = p2 < -.16 && p1 < -.16 && n < -.16
                && b2 >= .38 && b1 >= .34 && b0 >= .34
                && b1 >= b2 * .55 && b0 >= b1 * .55
                && bearProgress && !bearStalled;
        boolean bullishAdvanceStalled = p2 > .14 && p1 > .10 && n > .08 && bullProgress
                && (bullStalled || b0 < .30) && (atResistance || trend > .18);
        boolean bearishDeclineStalled = p2 < -.14 && p1 < -.10 && n < -.08 && bearProgress
                && (bearStalled || b0 < .30) && (atSupport || trend < -.18);
        boolean threeInsideUp = e >= s + 2 && safe(d, e - 2) < -.20 && safe(d, e - 1) > -.08
                && safe(body, e - 1) <= safe(body, e - 2) * .72 && n > .22;
        boolean threeInsideDown = e >= s + 2 && safe(d, e - 2) > .20 && safe(d, e - 1) < .08
                && safe(body, e - 1) <= safe(body, e - 2) * .72 && n < -.22;
        boolean threeOutsideUp = e >= s + 2 && safe(d, e - 2) < -.16 && safe(d, e - 1) > .22
                && safe(body, e - 1) >= safe(body, e - 2) * .88 && n > .14;
        boolean threeOutsideDown = e >= s + 2 && safe(d, e - 2) > .16 && safe(d, e - 1) < -.22
                && safe(body, e - 1) >= safe(body, e - 2) * .88 && n < -.14;

        // Five-candle rising/falling three methods (continuation).
        boolean risingThree = false, fallingThree = false;
        if (e - s + 1 >= 5) {
            int a = e - 4;
            risingThree = safe(d, a) > .24 && safe(d, e) > .24
                    && safe(d, a + 1) < .12 && safe(d, a + 2) < .12 && safe(d, a + 3) < .12
                    && Math.abs(safe(d, a + 1)) < Math.abs(safe(d, a))
                    && Math.abs(safe(d, a + 2)) < Math.abs(safe(d, a))
                    && Math.abs(safe(d, a + 3)) < Math.abs(safe(d, a));
            fallingThree = safe(d, a) < -.24 && safe(d, e) < -.24
                    && safe(d, a + 1) > -.12 && safe(d, a + 2) > -.12 && safe(d, a + 3) > -.12
                    && Math.abs(safe(d, a + 1)) < Math.abs(safe(d, a))
                    && Math.abs(safe(d, a + 2)) < Math.abs(safe(d, a))
                    && Math.abs(safe(d, a + 3)) < Math.abs(safe(d, a));
        }

        // Prefer the strongest/contextual pattern rather than stacking every label.
        if (morningStar) { name="MORNING STAR"; pattern=.88; patternStrength=.92; reversal=true; }
        else if (eveningStar) { name="EVENING STAR"; pattern=-.88; patternStrength=.92; reversal=true; }
        else if (threeOutsideUp) { name="THREE OUTSIDE UP"; pattern=.80; patternStrength=.86; reversal=true; }
        else if (threeOutsideDown) { name="THREE OUTSIDE DOWN"; pattern=-.80; patternStrength=.86; reversal=true; }
        else if (threeInsideUp) { name="THREE INSIDE UP"; pattern=.72; patternStrength=.78; reversal=true; }
        else if (threeInsideDown) { name="THREE INSIDE DOWN"; pattern=-.72; patternStrength=.78; reversal=true; }
        else if (bullEngulf) { name="BULLISH ENGULFING"; pattern=.78; patternStrength=.85; reversal=prior5<-.05 || atSupport; continuation=!reversal; }
        else if (bearEngulf) { name="BEARISH ENGULFING"; pattern=-.78; patternStrength=.85; reversal=prior5>.05 || atResistance; continuation=!reversal; }
        else if (bullKicker) { name="BULLISH KICKER"; pattern=.76; patternStrength=.82; reversal=true; }
        else if (bearKicker) { name="BEARISH KICKER"; pattern=-.76; patternStrength=.82; reversal=true; }
        else if (bullishAdvanceStalled) { name="BULLISH ADVANCE STALLED"; pattern=-.18; patternStrength=.62; continuation=false; reversal=atResistance; }
        else if (bearishDeclineStalled) { name="BEARISH DECLINE STALLED"; pattern=.18; patternStrength=.62; continuation=false; reversal=atSupport; }
        else if (threeWhite) { name="THREE WHITE SOLDIERS"; pattern=.70; patternStrength=.80; continuation=trend>=-.05; reversal=!continuation; }
        else if (threeBlack) { name="THREE BLACK CROWS"; pattern=-.70; patternStrength=.80; continuation=trend<=.05; reversal=!continuation; }
        else if (piercing) { name="PIERCING LINE"; pattern=.66; patternStrength=.74; reversal=true; }
        else if (darkCloud) { name="DARK CLOUD COVER"; pattern=-.66; patternStrength=.74; reversal=true; }
        else if (tweezerBottom) { name="TWEEZER BOTTOM"; pattern=.62; patternStrength=.70; reversal=true; }
        else if (tweezerTop) { name="TWEEZER TOP"; pattern=-.62; patternStrength=.70; reversal=true; }
        else if (dragonfly && (atSupport || prior5<-.12)) { name="DRAGONFLY DOJI"; pattern=.58; patternStrength=.66; reversal=true; }
        else if (gravestone && (atResistance || prior5>.12)) { name="GRAVESTONE DOJI"; pattern=-.58; patternStrength=.66; reversal=true; }
        else if (hammer) { name="HAMMER"; pattern=.64; patternStrength=.72; reversal=true; }
        else if (shootingStar) { name="SHOOTING STAR"; pattern=-.64; patternStrength=.72; reversal=true; }
        else if (hangingMan) { name="HANGING MAN"; pattern=-.54; patternStrength=.62; reversal=true; }
        else if (invertedHammer) { name="INVERTED HAMMER"; pattern=.50; patternStrength=.58; reversal=true; }
        else if (bullHarami) { name="BULLISH HARAMI"; pattern=.48; patternStrength=.58; reversal=true; }
        else if (bearHarami) { name="BEARISH HARAMI"; pattern=-.48; patternStrength=.58; reversal=true; }
        else if (risingThree) { name="RISING THREE METHOD"; pattern=.62; patternStrength=.70; continuation=true; }
        else if (fallingThree) { name="FALLING THREE METHOD"; pattern=-.62; patternStrength=.70; continuation=true; }
        else if (bullMarubozu) { name="BULLISH MARUBOZU"; pattern=.54; patternStrength=.64; continuation=true; }
        else if (bearMarubozu) { name="BEARISH MARUBOZU"; pattern=-.54; patternStrength=.64; continuation=true; }
        else if (spinning || doji) { name=spinning?"SPINNING TOP":"DOJI"; indecisionPattern=true; patternStrength=.30; }

        // Support/resistance rejection and breakout/retest from the user's added examples.
        boolean bullReject = atSupport && l0 >= .32 && n >= -.06;
        boolean bearReject = atResistance && u0 >= .32 && n <= .06;
        boolean bullBreakRetest = trend > .18 && p1 < -.08 && n > .18 && pricePos > .38 && pricePos < .84;
        boolean bearBreakRetest = trend < -.18 && p1 > .08 && n < -.18 && pricePos > .16 && pricePos < .62;
        if (patternStrength < .70) {
            if (bullBreakRetest) { name="BULLISH BREAKOUT + RETEST"; pattern=.64; patternStrength=.72; continuation=true; reversal=false; }
            else if (bearBreakRetest) { name="BEARISH BREAKDOWN + RETEST"; pattern=-.64; patternStrength=.72; continuation=true; reversal=false; }
            else if (bullReject) { name="BULLISH SUPPORT REJECTION"; pattern=.54; patternStrength=.64; reversal=true; }
            else if (bearReject) { name="BEARISH RESISTANCE REJECTION"; pattern=-.54; patternStrength=.64; reversal=true; }
        }

        // Context-gated pressure: a pattern without context is capped.
        double context = .36 * basePressure + .34 * pattern + .18 * recent3 + .12 * trend;
        if (reversal) {
            if (pattern > 0 && atSupport) context += .16;
            if (pattern < 0 && atResistance) context -= .16;
            if (pattern > 0 && atResistance) context -= .12;
            if (pattern < 0 && atSupport) context += .12;
        }
        if (continuation) {
            if (Math.signum(pattern) == Math.signum(trend)) context += .10 * Math.signum(pattern);
            else context *= .78;
            // Do not chase continuation directly into the opposing range edge.
            if (pattern > 0 && atResistance) context -= .18;
            if (pattern < 0 && atSupport) context += .18;
        }
        if (indecisionPattern) context *= .52;

        double dir = clamp(Math.tanh(context * 1.35), -1, 1);

        int confirmations = 0;
        if (patternStrength >= .55) confirmations++; // pattern
        if (keyLevel && reversal) confirmations++;  // support/resistance
        if (!reversal && Math.signum(dir)==Math.signum(trend) && Math.abs(trend)>=.14) confirmations++;
        if (Math.signum(dir)==Math.signum(recent3) && Math.abs(recent3)>=.14) confirmations++;
        if (Math.signum(dir)==Math.signum(wick) && Math.abs(wick)>=.12) confirmations++;
        if (Math.signum(dir)==Math.signum(flow) && Math.abs(flow)>=.16) confirmations++;
        confirmations = Math.min(5, confirmations);
        if (reversal && keyLevel && patternStrength >= .70 && confirmations < 3) confirmations = 3;

        double pressure = clamp(.62 * basePressure + .38 * dir, -1, 1);
        int buyer = (int)Math.round(50 + 50 * pressure);
        int seller = 100 - buyer;

        double uncertainty = clamp(.64 - .11 * confirmations - .22 * patternStrength
                + (indecisionPattern ? .24 : 0) + (keyLevel ? -.05 : .04), .06, .92);
        double confidence = clamp(.26 + .30 * Math.abs(dir) + .08 * confirmations
                + .20 * patternStrength - .20 * uncertainty, .08, .95);

        String side = dir > .14 ? "BUY" : dir < -.14 ? "SELL" : "WAIT";
        String level = atSupport ? "support" : atResistance ? "resistance" : "mid-range";
        String summary = String.format(Locale.US,
                "%s • %s • buyer %d%% / seller %d%% • %d/5 confirmations • %s.",
                name, side, buyer, seller, confirmations, level);

        return new Result(dir, confidence, uncertainty, buyer, seller, confirmations,
                name, summary, keyLevel, reversal, continuation);
    }

    private static double weightedDirection(List<Double> d, List<Double> body, List<Double> r,
                                            int a, int b) {
        if (a > b) return 0;
        double sum = 0, wsum = 0;
        double avgR = 0; int rc = 0;
        for (int i=a;i<=b;i++){avgR += Math.max(.0001, safe(r,i)); rc++;}
        avgR = rc==0 ? 1 : avgR/rc;
        int n = Math.max(1, b - a + 1);
        for (int i = a; i <= b; i++) {
            double recency = .50 + .50 * ((double)(i-a+1)/n);
            double participation = .35 + .65 * clamp(safe(body,i),0,1);
            double range = clamp(Math.max(.0001,safe(r,i))/Math.max(.0001,avgR), .55, 1.55);
            double w = recency * participation * range;
            sum += clamp(safe(d,i),-1,1) * w;
            wsum += w;
        }
        return wsum==0 ? 0 : clamp(sum/wsum,-1,1);
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
