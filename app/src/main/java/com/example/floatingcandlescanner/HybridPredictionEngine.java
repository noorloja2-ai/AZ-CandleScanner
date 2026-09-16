package com.example.floatingcandlescanner;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

public final class HybridPredictionEngine {
    private HybridPredictionEngine() {}

    public static class Fusion {
        public final SignalResult[] results;
        public final String dataStatus;
        public final String timeframes;

        Fusion(SignalResult[] results,String dataStatus,String timeframes){
            this.results=results;
            this.dataStatus=dataStatus;
            this.timeframes=timeframes;
        }
    }

    public static Fusion visualOnly(SignalResult[] visual,String reason){
        return new Fusion(visual,reason,"Visual Trading Brain only");
    }

    public static Fusion fuse(SignalResult[] visual,TechnicalModel.Multi t,
                              boolean highAccuracy,boolean eliteMode,int sensitivity,
                              String session,long newsLockUntil,
                              OnlineLearner learner){
        SignalResult[] out=new SignalResult[5];

        boolean sessionOk=sessionOpen(session);
        boolean newsOk=System.currentTimeMillis()>=newsLockUntil;

        for(int h=0;h<5;h++){
            SignalResult v=visual[h];
            double visualBuy=v.buyProbability/100.0;

            double tech;
            if(h<=1){
                tech=.48*t.m1.buyProbability+.34*t.m5.buyProbability+.18*t.m15.buyProbability;
            }else{
                tech=.25*t.m1.buyProbability+.45*t.m5.buyProbability+.30*t.m15.buyProbability;
            }

            // Exact OHLC gets slightly more weight than screen pixels.
            double buy=.43*visualBuy+.57*tech;
            buy=clamp(buy,.08,.92);
            double sell=1-buy;

            int upVotes=0,downVotes=0;
            double[] votes={visualBuy,t.m1.buyProbability,t.m5.buyProbability,t.m15.buyProbability};
            for(double p:votes){
                if(p>=.56)upVotes++;
                else if(p<=.44)downVotes++;
            }

            boolean agreement=upVotes>=3||downVotes>=3;
            boolean m5Aligned=(buy>.5&&t.m5.buyProbability>.53)||(buy<.5&&t.m5.buyProbability<.47);
            boolean m15NotOpposite=(buy>.5&&t.m15.buyProbability>=.45)||(buy<.5&&t.m15.buyProbability<=.55);

            double threshold;
            if(eliteMode) threshold=sensitivity==2?.90:sensitivity==0?.84:.87;
            else if(highAccuracy) threshold=sensitivity==2?.86:sensitivity==0?.78:.82;
            else threshold=sensitivity==2?.75:sensitivity==0?.62:.68;

            double max=Math.max(buy,sell);
            String label="WAIT";
            String block="";

            boolean fourOfFour =
                    (visualBuy>=.56 && t.m1.buyProbability>=.56 && t.m5.buyProbability>=.56 && t.m15.buyProbability>=.56)
                    ||
                    (visualBuy<=.44 && t.m1.buyProbability<=.44 && t.m5.buyProbability<=.44 && t.m15.buyProbability<=.44);

            boolean eliteReady = learner != null && learner.eliteReady(h);
            boolean eliteHealthy = learner != null && learner.elitePerformanceHealthy(h);

            if(!sessionOk) block="Selected trading session is closed.";
            else if(!newsOk) block="Manual high-impact news lock is active.";
            else if(eliteMode && !eliteReady) block="ELITE learning gate: 40 resolved samples and 25 recent outcomes are required.";
            else if(eliteMode && !eliteHealthy) block="ELITE performance gate: recent learned direction score must be at least 68%.";
            else if(eliteMode && !fourOfFour) block="ELITE requires 4-of-4 agreement: visual + M1 + M5 + M15.";
            else if(!eliteMode && !agreement) block="Visual + OHLC models do not have 3-of-4 agreement.";
            else if((highAccuracy||eliteMode) && !m5Aligned) block="M5 trend does not confirm.";
            else if((highAccuracy||eliteMode) && !m15NotOpposite) block="M15 trend is opposing the setup.";
            else if(eliteMode && v.setupQuality < 72) block="ELITE setup-quality gate requires at least 72%.";
            else if(eliteMode && v.confidence < 35) block="ELITE confidence gate requires a stronger visual setup.";
            else if(max<threshold) block=eliteMode
                    ?"Fused score is below the ELITE threshold."
                    :"Fused score is below the High Accuracy threshold.";
            else label=buy>sell?"BUY":"SELL";

            int buyPct=(int)Math.round(buy*100);
            int sellPct=100-buyPct;

            int technicalQuality=(int)Math.round(
                    clamp((Math.abs(t.m1.buyProbability-.5)
                            +Math.abs(t.m5.buyProbability-.5)
                            +Math.abs(t.m15.buyProbability-.5))/1.20,0,1)*100);

            int quality=(int)Math.round(.48*v.setupQuality+.52*technicalQuality);
            int confidence=(int)Math.round(
                    clamp(Math.abs(buy-.5)*2*(quality/100.0),0,1)*100);

            String regime=label.equals("WAIT")
                    ?(eliteMode?"ELITE WAIT":"FUSION WAIT")
                    :(eliteMode?"ELITE 4/4 CONSENSUS":"4-MODEL CONSENSUS");

            String explanation=label.equals("WAIT")
                    ?block
                    :String.format(Locale.US,
                    "%s consensus: visual %d%% BUY, M1 %.0f%%, M5 %.0f%%, M15 %.0f%%.",
                    label,v.buyProbability,t.m1.buyProbability*100,
                    t.m5.buyProbability*100,t.m15.buyProbability*100);

            out[h]=new SignalResult(
                    label,
                    label.equals("WAIT")?Math.min(80,(int)Math.round(max*100)):(int)Math.round(max*100),
                    buy-sell,
                    buyPct,sellPct,confidence,regime,buy,
                    quality,
                    v.structure+" • M5 "+t.m5.direction+" • M15 "+t.m15.direction,
                    explanation);
        }

        String status=eliteMode?"LIVE OHLC • ELITE PRECISION":"LIVE OHLC FUSION ACTIVE";
        String tf=t.m1.summary+"\n"+t.m5.summary+"\n"+t.m15.summary;
        return new Fusion(out,status,tf);
    }

    private static boolean sessionOpen(String session){
        if(session==null||session.equals("ALL"))return true;

        Calendar c=Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        double h=c.get(Calendar.HOUR_OF_DAY)+c.get(Calendar.MINUTE)/60.0;

        if(session.equals("ASIA"))return h>=0&&h<9;
        if(session.equals("LONDON"))return h>=7&&h<16;
        if(session.equals("NEW_YORK"))return h>=12&&h<21;
        if(session.equals("OVERLAP"))return h>=12&&h<16;
        return true;
    }

    private static double clamp(double v,double lo,double hi){
        return Math.max(lo,Math.min(hi,v));
    }
}
