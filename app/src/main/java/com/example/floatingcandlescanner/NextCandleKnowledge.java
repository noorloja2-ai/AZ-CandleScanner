package com.example.floatingcandlescanner;

import java.util.List;

/**
 * Completed-candle next-bar scorer.
 *
 * This is deliberately a confluence layer, not a promise that one textbook
 * pattern predicts the future. It combines the last completed candle with
 * recent pressure, trend/location, rejection, expansion/compression and
 * support/resistance context. Inputs are visual proxies extracted from the
 * broker chart screenshot, not broker OHLC data.
 */
public final class NextCandleKnowledge {
    private NextCandleKnowledge() {}

    public static final class Result {
        public final double directionalScore; // + BUY, - SELL
        public final double confidence;       // 0..1 evidence quality
        public final double uncertainty;      // 0..1 reasons to soften result
        public final String reason;
        Result(double d,double c,double u,String r){
            directionalScore=clamp(d,-1,1);
            confidence=clamp(c,0,1);
            uncertainty=clamp(u,0,1);
            reason=r;
        }
        public boolean strong(){
            return confidence>=.60 && Math.abs(directionalScore)>=.42 && uncertainty<.62;
        }
    }

    private static final class Vote {
        double sum=0, weight=0, uncertainty=0;
        String reason="RECENT CANDLE PRESSURE";
        double best=0;
        void add(String r,double direction,double w){
            if(w<=0 || direction==0)return;
            double d=clamp(direction,-1,1);
            sum+=d*w; weight+=w;
            double merit=Math.abs(d)*w;
            if(merit>best){best=merit;reason=r;}
        }
        void uncertain(double u){uncertainty=Math.max(uncertainty,clamp(u,0,1));}
    }

    public static Result analyze(
            List<Double>d,List<Double>y,List<Double>range,List<Double>body,
            List<Double>upper,List<Double>lower,int s,int k,double trend){
        if(d==null||y==null||range==null||body==null||upper==null||lower==null||k<3)
            return new Result(0,.20,.80,"NOT ENOUGH COMPLETED CANDLES");

        int i=s+k-1;
        Vote v=new Vote();
        int sign=sign(d.get(i));
        double br=clip01(body.get(i));
        double uw=clip01(upper.get(i));
        double lw=clip01(lower.get(i));
        double rr=Math.max(.0005,range.get(i));

        // 1) Most recent completed candles carry the largest next-bar weight.
        double recent=0,ws=0;
        double[] rw={.16,.27,.57};
        int n=Math.min(3,k);
        for(int q=0;q<n;q++){
            int idx=i-(n-1-q);
            double w=rw[3-n+q];
            recent+=clamp(d.get(idx),-1,1)*w; ws+=w;
        }
        if(ws>0)recent/=ws;
        v.add("LAST 3 CANDLE MOMENTUM",recent,.90);

        // 2) Body + wick reading: long body / short opposing wick means control
        // into the close; long rejection wick means the opposite side fought back.
        if(sign!=0 && br>=.55){
            double opposing=sign>0?uw:lw;
            double control=clamp((br-.45)*1.7 + (.22-opposing)*1.3,0,1);
            v.add(sign>0?"BULLISH CLOSE CONTROL":"BEARISH CLOSE CONTROL",sign*control,.95);
        }
        if(lw>=.42 && lw>=Math.max(.18,br*1.8))
            v.add("LOWER-WICK BUYER REJECTION",+.72,.80);
        if(uw>=.42 && uw>=Math.max(.18,br*1.8))
            v.add("UPPER-WICK SELLER REJECTION",-.72,.80);

        // 3) Recent support/resistance location. Screen Y is inverted: low Y is
        // high price, high Y is low price.
        double min=1,max=0;
        for(int q=s;q<=i;q++){min=Math.min(min,y.get(q));max=Math.max(max,y.get(q));}
        double span=Math.max(.004,max-min);
        double location=clamp((y.get(i)-min)/span,0,1);
        boolean resistance=location<=.22;
        boolean support=location>=.78;
        if(support && (lw>.25 || sign>0)) v.add("SUPPORT REACTION",+.62,.70);
        if(resistance && (uw>.25 || sign<0)) v.add("RESISTANCE REACTION",-.62,.70);

        // 4) Expansion / compression. A decisive expansion bar gets continuation
        // weight. A very small inside/compression bar is information-poor.
        double prevRangeMean=mean(range,Math.max(s,i-Math.min(5,k-1)),Math.min(5,k-1));
        if(prevRangeMean<=.0001)prevRangeMean=rr;
        double expansion=rr/Math.max(.0005,prevRangeMean);
        if(sign!=0 && expansion>=1.25 && br>=.52)
            v.add(sign>0?"BULLISH RANGE EXPANSION":"BEARISH RANGE EXPANSION",sign*.68,.72);
        if(expansion<=.58 || (br<=.20 && uw>=.24 && lw>=.24))
            v.uncertain(.66);
        if(expansion>=2.35)v.uncertain(.58); // shock candle: next bar is less stable

        // 5) Two-candle body takeover / engulfing proxy.
        if(k>=2){
            int p=i-1;
            int ps=sign(d.get(p));
            double pb=clip01(body.get(p));
            if(sign!=0 && ps==-sign && br>=Math.max(.42,pb*1.12)){
                boolean context=(sign>0 && (trend<-.15||support)) ||
                        (sign<0 && (trend>.15||resistance));
                v.add(sign>0?"BULLISH BODY TAKEOVER":"BEARISH BODY TAKEOVER",
                        sign*(context?.86:.60),context?.90:.62);
            }
        }

        // 6) Three-candle pressure, with exhaustion protection. Same-direction
        // candles matter more when bodies are not progressively collapsing.
        if(k>=3){
            int a=i-2,b=i-1;
            int sa=sign(d.get(a)),sb=sign(d.get(b));
            double ba=clip01(body.get(a)),bb=clip01(body.get(b));
            if(sign!=0 && sa==sign && sb==sign){
                boolean shrinking=br<bb*.78 && bb<ba*.86;
                if(shrinking){
                    v.uncertain(.56);
                    if((sign>0&&resistance)||(sign<0&&support))
                        v.add("THREE-CANDLE EXHAUSTION",-sign*.52,.62);
                }else{
                    v.add(sign>0?"THREE-CANDLE BUY PRESSURE":"THREE-CANDLE SELL PRESSURE",
                            sign*.60,.66);
                }
            }
        }

        // 7) Visual breakout / failed continuation relative to prior candle centres.
        if(k>=5){
            double priorMin=1,priorMax=0;
            for(int q=s;q<i;q++){priorMin=Math.min(priorMin,y.get(q));priorMax=Math.max(priorMax,y.get(q));}
            double eps=Math.max(.0025,mean(range,s,k)*.10);
            if(y.get(i)<priorMin-eps && sign>0)
                v.add("BULLISH STRUCTURE BREAK",+.72,.77);
            if(y.get(i)>priorMax+eps && sign<0)
                v.add("BEARISH STRUCTURE BREAK",-.72,.77);
        }

        // 8) Trend is context, not a dictator. It receives less weight than the
        // completed-candle evidence so a confirmed reversal can override it.
        if(Math.abs(trend)>.16)v.add("TREND CONTEXT",Math.signum(trend)*Math.min(.62,Math.abs(trend)),.52);

        double score=v.weight<=0?0:v.sum/v.weight;
        double evidence=clamp(v.weight/4.3,0,1);

        // If recent pressure and old trend strongly disagree, keep the direction
        // but lower the claimed confidence instead of forcing the old trend.
        if(Math.abs(recent)>.22 && Math.abs(trend)>.25 && Math.signum(recent)!=Math.signum(trend))
            v.uncertain(.48);

        double conf=clamp(evidence*(1.0-.45*v.uncertainty),.20,.94);
        return new Result(score,conf,v.uncertainty,v.reason);
    }

    private static double mean(List<Double>a,int start,int count){
        if(count<=0)return 0;
        int s=Math.max(0,start),e=Math.min(a.size(),s+count);
        if(e<=s)return 0;
        double z=0; for(int i=s;i<e;i++)z+=a.get(i); return z/(e-s);
    }
    private static int sign(double x){return x>.12?1:x<-.12?-1:0;}
    private static double clip01(double x){return clamp(x,0,1);}
    private static double clamp(double x,double lo,double hi){return Math.max(lo,Math.min(hi,x));}
}
