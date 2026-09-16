package com.example.floatingcandlescanner;

public class SignalResult {
    public final String label;
    public final int strength;
    public final double score;
    public final int buyProbability;
    public final int sellProbability;
    public final int confidence;
    public final String regime;
    public final double rawBuyProbability;
    public final int setupQuality;
    public final String structure;
    public final String explanation;

    public SignalResult(String label, int strength, double score,
                        int buyProbability, int sellProbability,
                        int confidence, String regime,
                        double rawBuyProbability,
                        int setupQuality, String structure,
                        String explanation) {
        this.label = label;
        this.strength = strength;
        this.score = score;
        this.buyProbability = buyProbability;
        this.sellProbability = sellProbability;
        this.confidence = confidence;
        this.regime = regime;
        this.rawBuyProbability = rawBuyProbability;
        this.setupQuality = setupQuality;
        this.structure = structure;
        this.explanation = explanation;
    }

    public static SignalResult waitResult() {
        return new SignalResult(
                "WAIT", 50, 0.0, 50, 50, 0, "LOW DATA", 0.5,
                0, "UNKNOWN", "Not enough reliable chart information.");
    }
}
