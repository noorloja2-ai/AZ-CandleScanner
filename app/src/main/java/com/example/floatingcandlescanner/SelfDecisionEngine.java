package com.example.floatingcandlescanner;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Local rolling self-decision layer.
 *
 * It does not place trades and does not claim to predict the market with certainty.
 * It blends the completed-candle model with a short rolling history of 1-second
 * visual calculations, then scales confidence using the asset/timeframe learner.
 */
public final class SelfDecisionEngine {
    private static final int HORIZONS = 5;
    private static final int MAX_LIVE_SAMPLES = 12;

    @SuppressWarnings("unchecked")
    private final Deque<Double>[] liveBuy = new Deque[HORIZONS];

    public SelfDecisionEngine() {
        for (int i=0;i<HORIZONS;i++) liveBuy[i]=new ArrayDeque<>();
    }

    public synchronized void reset() {
        for (Deque<Double> q: liveBuy) q.clear();
    }

    public synchronized void observe(SignalResult[] results) {
        if(results==null) return;
        for(int h=0;h<Math.min(HORIZONS,results.length);h++){
            SignalResult r=results[h];
            if(r==null) continue;
            // Keep the 1-second consensus independent from the learner's own
            // calibrated output to avoid a feedback loop where learned bias is
            // counted twice. The official completed-candle base remains calibrated.
            double p=Double.isNaN(r.rawBuyProbability)
                    ?clamp(r.buyProbability/100.0,.10,.90)
                    :clamp(r.rawBuyProbability,.10,.90);
            Deque<Double> q=liveBuy[h];
            q.addLast(p);
            while(q.size()>MAX_LIVE_SAMPLES) q.removeFirst();
        }
    }

    public synchronized SignalResult decide(SignalResult base,int horizon,OnlineLearner learner){
        if(base==null) return SignalResult.waitResult();
        int h=Math.max(0,Math.min(HORIZONS-1,horizon-1));
        Deque<Double> q=liveBuy[h];
        double baseBuy=clamp(base.buyProbability/100.0,.10,.90);
        double mean=baseBuy, sd=0.0;
        int n=q.size();
        if(n>0){
            double sum=0;
            for(double v:q)sum+=v;
            mean=sum/n;
            double ss=0;
            for(double v:q){double z=v-mean;ss+=z*z;}
            sd=Math.sqrt(ss/n);
        }

        // Live history is deliberately a minority vote: the official signal must
        // remain anchored to the fully completed candle model.
        double liveWeight=n>=3?Math.min(.22,.06+n*.014):0.0;
        double blended=(1.0-liveWeight)*baseBuy+liveWeight*mean;

        // Stable 1-second consensus can add a very small directional nudge.
        double stability=1.0-clamp(sd/.18,0.0,1.0);
        if(n>=4 && Math.signum(mean-.5)==Math.signum(baseBuy-.5)){
            blended += Math.signum(baseBuy-.5)*.018*stability;
        }

        // Learned performance changes confidence, not direction. Weak recent
        // performance shrinks the result toward 50%; healthy history allows a
        // small expansion. This avoids inventing certainty from a bad streak.
        int learnedN=learner==null?0:learner.recentCount(h);
        int learnedAcc=learner==null?0:learner.recentAccuracyPct(h);
        double perfFactor=1.0;
        if(learnedN>=10){
            double a=clamp(learnedAcc/100.0,.35,.80);
            perfFactor=clamp(.78+(a-.50)*.80,.72,1.12);
        }
        blended=.5+(blended-.5)*perfFactor;
        blended=clamp(blended,.10,.90);

        int bp=(int)Math.round(blended*100.0);
        int sp=100-bp;
        String direction=bp>=sp?"BUY":"SELL";
        String label="WAIT".equals(base.label)?"WAIT":direction;
        int lead=Math.max(bp,sp);
        int confidence=(int)Math.round(clamp(Math.abs(blended-.5)*2.0*stability,0,1)*100.0);
        int strength=Math.max(base.strength,Math.min(90,lead));

        String extra=String.format(Locale.US,
                "Self-AI: %ds rolling consensus %d%%, stability %d%%",
                Math.max(1,n), (int)Math.round(mean*100.0), (int)Math.round(stability*100.0));
        if(learnedN>=10) extra += " • learned recent "+learnedAcc+"%";
        String explanation=(base.explanation==null||base.explanation.isEmpty())
                ?extra:base.explanation+" • "+extra;
        String regime=(base.regime==null?"":base.regime)+" • SELF-AI";

        return new SignalResult(label,strength,blended-(1.0-blended),bp,sp,
                confidence,regime,base.rawBuyProbability,base.setupQuality,
                base.structure,explanation);
    }

    public synchronized int sampleCount(int horizon){
        int h=Math.max(0,Math.min(HORIZONS-1,horizon-1));
        return liveBuy[h].size();
    }

    private static double clamp(double v,double lo,double hi){
        return Math.max(lo,Math.min(hi,v));
    }
}
