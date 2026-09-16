package com.example.floatingcandlescanner;

import java.util.List;

/**
 * Advanced next-candle confluence scorer.
 *
 * This layer intentionally does not let a single named pattern dictate a trade.
 * It scores pattern + location + structure + rejection/breakout context together.
 * Inputs are normalized visual candle proxies extracted from broker screenshots,
 * not exact exchange OHLC values.
 */
public final class AdvancedConfluenceEngine {
    private AdvancedConfluenceEngine() {}

    public static final class Result {
        public final String name;
        public final double directionalScore; // + BUY, - SELL
        public final double confidence;       // 0..1
        public final double uncertainty;      // 0..1
        public final boolean confirmed;

        Result(String n,double d,double c,double u,boolean ok){
            name=n;
            directionalScore=clamp(d,-1,1);
            confidence=clamp(c,0,1);
            uncertainty=clamp(u,0,1);
            confirmed=ok;
        }

        public boolean strong(){
            return confirmed && confidence>=.64 && Math.abs(directionalScore)>=.50 && uncertainty<.58;
        }
    }

    private static final class Pick {
        String name="NO ADVANCED CONFLUENCE";
        double dir=0, conf=0;
        boolean confirmed=false;
        void add(String n,double d,double c,boolean ok){
            double merit=Math.abs(d)*c*(ok?1.0:.70);
            double current=Math.abs(dir)*conf*(confirmed?1.0:.70);
            if(merit>current){name=n;dir=d;conf=c;confirmed=ok;}
        }
    }

    public static Result analyze(
            List<Double>d,List<Double>y,List<Double>range,List<Double>body,
            List<Double>upper,List<Double>lower,int s,int k,double trend){
        if(d==null||y==null||range==null||body==null||upper==null||lower==null||k<3)
            return new Result("NO ADVANCED CONFLUENCE",0,.10,.80,false);

        int i=s+k-1;
        Pick p=new Pick();
        double loc=location(y,s,k); // 0 recent high/resistance, 1 recent low/support
        boolean resistance=loc<=.28;
        boolean support=loc>=.72;
        boolean up=trend>.18;
        boolean down=trend<-.18;
        int si=sign(d.get(i));
        double bi=clip01(body.get(i));
        double ui=clip01(upper.get(i));
        double li=clip01(lower.get(i));

        // --- Two-candle reversal families ---
        if(k>=2){
            int a=i-1;
            int sa=sign(d.get(a));
            double ba=clip01(body.get(a));
            double prevTop=bodyTop(y,range,body,a), prevBot=bodyBottom(y,range,body,a);
            double curTop=bodyTop(y,range,body,i), curBot=bodyBottom(y,range,body,i);
            double prevMid=(prevTop+prevBot)/2.0;
            double tol=Math.max(.0025,Math.min(range.get(a),range.get(i))*.14);

            // Piercing line: bearish body followed by bullish body closing through
            // at least the midpoint of the prior body, strongest after decline/support.
            boolean piercing=sa<0 && si>0 && bi>=.42 && curTop < prevMid && curBot>=prevBot-tol;
            if(piercing){
                boolean ctx=down||support;
                p.add("PIERCING LINE",+.82,ctx?.86:.60,ctx);
            }

            // Dark cloud cover: symmetric bearish reversal after advance/resistance.
            boolean darkCloud=sa>0 && si<0 && bi>=.42 && curBot > prevMid && curTop<=prevTop+tol;
            if(darkCloud){
                boolean ctx=up||resistance;
                p.add("DARK CLOUD COVER",-.82,ctx?.86:.60,ctx);
            }

            // Counterattack: opposite strong candles with similar close/centre region.
            double centreDiff=Math.abs(y.get(a)-y.get(i));
            double avgR=Math.max(.003,(range.get(a)+range.get(i))/2.0);
            if(sa!=0 && si==-sa && ba>=.48 && bi>=.48 && centreDiff<=avgR*.22){
                boolean ctx=(si>0&&(down||support))||(si<0&&(up||resistance));
                p.add(si>0?"BULLISH COUNTERATTACK":"BEARISH COUNTERATTACK",
                        si*.70,ctx?.73:.52,ctx);
            }

            // Belt-hold proxy: large directional body opening near one extreme and
            // closing near the other. Visual geometry cannot know true session gaps,
            // so this is deliberately a moderate score.
            if(si>0 && bi>=.70 && li<=.11 && ui<=.24){
                boolean ctx=down||support||up;
                p.add("BULLISH BELT-HOLD PRESSURE",+.62,ctx?.68:.52,ctx);
            }
            if(si<0 && bi>=.70 && ui<=.11 && li<=.24){
                boolean ctx=up||resistance||down;
                p.add("BEARISH BELT-HOLD PRESSURE",-.62,ctx?.68:.52,ctx);
            }

            // Rising/falling window proxy. True gaps are unusual in continuous FX/OTC,
            // so only give weight to an obvious non-overlap and never force a signal.
            double prevBottom=bottom(y,range,a), prevTopRange=top(y,range,a);
            double curBottom=bottom(y,range,i), curTopRange=top(y,range,i);
            double gapTol=Math.max(.0025,avgR*.10);
            if(si>0 && curBottom < prevTopRange-gapTol)
                p.add("RISING WINDOW / GAP",+.54,.54,up);
            if(si<0 && curTopRange > prevBottom+gapTol)
                p.add("FALLING WINDOW / GAP",-.54,.54,down);
        }

        // --- Three-candle confirmation families ---
        if(k>=3){
            int a=i-2,b=i-1,c=i;
            int sa=sign(d.get(a)), sb=sign(d.get(b)), sc=si;
            double ba=clip01(body.get(a)), bb=clip01(body.get(b)), bc=bi;

            // Three white soldiers / black crows: require three meaningful bodies
            // and progressive centre movement, not just three colours.
            boolean risingCentres=y.get(a)>y.get(b) && y.get(b)>y.get(c);
            boolean fallingCentres=y.get(a)<y.get(b) && y.get(b)<y.get(c);
            if(sa>0&&sb>0&&sc>0&&ba>=.38&&bb>=.38&&bc>=.38&&risingCentres){
                boolean reversalCtx=down||support;
                boolean continuationCtx=up;
                p.add("THREE WHITE SOLDIERS",+.78,reversalCtx?.86:continuationCtx?.77:.62,
                        reversalCtx||continuationCtx);
            }
            if(sa<0&&sb<0&&sc<0&&ba>=.38&&bb>=.38&&bc>=.38&&fallingCentres){
                boolean reversalCtx=up||resistance;
                boolean continuationCtx=down;
                p.add("THREE BLACK CROWS",-.78,reversalCtx?.86:continuationCtx?.77:.62,
                        reversalCtx||continuationCtx);
            }

            // Three inside up/down: harami-type first two candles followed by
            // confirmation in the reversal direction.
            double aTop=bodyTop(y,range,body,a), aBot=bodyBottom(y,range,body,a);
            double bTop=bodyTop(y,range,body,b), bBot=bodyBottom(y,range,body,b);
            double tol=Math.max(.0025,Math.min(range.get(a),range.get(b))*.15);
            boolean bInside=bTop>=aTop-tol && bBot<=aBot+tol && bb<=ba*.75;
            if(sa<0 && bInside && sc>0 && bc>=.42 && y.get(c)<y.get(b)){
                boolean ctx=down||support;
                p.add("THREE INSIDE UP",+.80,ctx?.84:.60,ctx);
            }
            if(sa>0 && bInside && sc<0 && bc>=.42 && y.get(c)>y.get(b)){
                boolean ctx=up||resistance;
                p.add("THREE INSIDE DOWN",-.80,ctx?.84:.60,ctx);
            }

            // Three outside up/down: second candle engulfs first and third confirms.
            double b2Top=bodyTop(y,range,body,b), b2Bot=bodyBottom(y,range,body,b);
            boolean bEngulfs=b2Top<=aTop+tol && b2Bot>=aBot-tol && bb>=ba*1.05;
            if(sa<0 && sb>0 && bEngulfs && sc>0 && y.get(c)<y.get(b)){
                boolean ctx=down||support;
                p.add("THREE OUTSIDE UP",+.86,ctx?.89:.67,ctx);
            }
            if(sa>0 && sb<0 && bEngulfs && sc<0 && y.get(c)>y.get(b)){
                boolean ctx=up||resistance;
                p.add("THREE OUTSIDE DOWN",-.86,ctx?.89:.67,ctx);
            }
        }

        // --- Market structure / rejection / breakout-retest ---
        double structure=structureScore(y,s,k);
        if(structure>.34) p.add("HH / HL BULLISH STRUCTURE",+.60,.67,true);
        if(structure<-.34) p.add("LH / LL BEARISH STRUCTURE",-.60,.67,true);

        double rejection=rejectionAtExtreme(d,y,body,upper,lower,s,k);
        if(rejection>.45) p.add("SUPPORT REJECTION",+.76,.80,support||down);
        if(rejection<-.45) p.add("RESISTANCE REJECTION",-.76,.80,resistance||up);

        double retest=breakRetest(y,d,body,s,k);
        if(retest>.45) p.add("BULLISH BREAKOUT + RETEST",+.80,.84,true);
        if(retest<-.45) p.add("BEARISH BREAKOUT + RETEST",-.80,.84,true);

        // Double-top / double-bottom proxy with neckline confirmation.
        double doubleTurn=doubleTopBottom(y,d,s,k);
        if(doubleTurn>.48) p.add("DOUBLE BOTTOM CONFIRMATION",+.74,.77,true);
        if(doubleTurn<-.48) p.add("DOUBLE TOP CONFIRMATION",-.74,.77,true);

        // Exhaustion: several same-direction candles with shrinking bodies and a
        // rejection wick at an extreme lower confidence in continuation and can
        // support a reversal only when location agrees.
        double exhaust=exhaustionScore(d,body,upper,lower,s,k,resistance,support);
        if(exhaust>.42) p.add("BEAR EXHAUSTION / BUY REVERSAL",+.60,.64,support||down);
        if(exhaust<-.42) p.add("BULL EXHAUSTION / SELL REVERSAL",-.60,.64,resistance||up);

        // Uncertainty: doji/compression or conflicting trend/structure should
        // soften the final confidence rather than forcing a direction.
        double uncertainty=0;
        if(bi<=.16 && ui>=.24 && li>=.24) uncertainty=Math.max(uncertainty,.72);
        double vr=volatilityRatio(range,s,k);
        if(vr<.52) uncertainty=Math.max(uncertainty,.58);
        if(vr>2.20) uncertainty=Math.max(uncertainty,.56);
        if(Math.abs(structure)>.35 && Math.abs(trend)>.25 && Math.signum(structure)!=Math.signum(trend))
            uncertainty=Math.max(uncertainty,.46);

        double conf=clamp(p.conf*(1.0-.42*uncertainty),.12,.94);
        double dir=p.dir*(1.0-.25*uncertainty);
        return new Result(p.name,dir,conf,uncertainty,p.confirmed);
    }

    private static double structureScore(List<Double>y,int s,int k){
        if(k<5)return 0;
        int n=Math.min(7,k), start=s+k-n;
        double first=y.get(start), last=y.get(s+k-1);
        double drift=clamp((first-last)*7.0,-1,1); // lower screen Y = higher price
        int higher=0,lower=0;
        for(int q=start+1;q<s+k;q++){
            double diff=y.get(q-1)-y.get(q);
            if(diff>.002)higher++;
            else if(diff<-.002)lower++;
        }
        double seq=(higher-lower)/(double)Math.max(1,n-1);
        return clamp(.58*drift+.42*seq,-1,1);
    }

    private static double rejectionAtExtreme(List<Double>d,List<Double>y,List<Double>body,
                                             List<Double>upper,List<Double>lower,int s,int k){
        int i=s+k-1;
        double loc=location(y,s,k), br=clip01(body.get(i));
        double lw=clip01(lower.get(i)), uw=clip01(upper.get(i));
        if(loc>=.68 && lw>=Math.max(.30,br*1.35)) return clamp(.55+lw*.45,0,1);
        if(loc<=.32 && uw>=Math.max(.30,br*1.35)) return -clamp(.55+uw*.45,0,1);
        return 0;
    }

    private static double breakRetest(List<Double>y,List<Double>d,List<Double>body,int s,int k){
        if(k<6)return 0;
        int i=s+k-1;
        int histEnd=i-2;
        double min=1,max=0;
        for(int q=s;q<=histEnd;q++){min=Math.min(min,y.get(q));max=Math.max(max,y.get(q));}
        double eps=Math.max(.003,(max-min)*.08);
        // bullish: prior breakout above resistance (lower Y), then retest, then bullish hold
        boolean bullBreak=y.get(i-2)<min+eps;
        boolean bullRetest=y.get(i-1)>y.get(i-2)+eps*.20 && y.get(i-1)<min+eps*2.2;
        boolean bullHold=sign(d.get(i))>0 && y.get(i)<=y.get(i-1)+eps*.20 && body.get(i)>=.34;
        if(bullBreak&&bullRetest&&bullHold)return .78;
        boolean bearBreak=y.get(i-2)>max-eps;
        boolean bearRetest=y.get(i-1)<y.get(i-2)-eps*.20 && y.get(i-1)>max-eps*2.2;
        boolean bearHold=sign(d.get(i))<0 && y.get(i)>=y.get(i-1)-eps*.20 && body.get(i)>=.34;
        if(bearBreak&&bearRetest&&bearHold)return -.78;
        return 0;
    }

    private static double doubleTopBottom(List<Double>y,List<Double>d,int s,int k){
        if(k<7)return 0;
        int i=s+k-1;
        int start=Math.max(s,i-8);
        double tol=.0065;
        // scan two separated local highs (small Y) and two separated local lows (large Y)
        for(int a=start+1;a<=i-4;a++){
            boolean highA=y.get(a)<y.get(a-1)&&y.get(a)<=y.get(a+1);
            boolean lowA=y.get(a)>y.get(a-1)&&y.get(a)>=y.get(a+1);
            for(int b=a+2;b<=i-2;b++){
                boolean highB=y.get(b)<y.get(b-1)&&y.get(b)<=y.get(b+1);
                boolean lowB=y.get(b)>y.get(b-1)&&y.get(b)>=y.get(b+1);
                if(highA&&highB&&Math.abs(y.get(a)-y.get(b))<=tol && sign(d.get(i))<0 && y.get(i)>y.get(b)+.003)
                    return -.70;
                if(lowA&&lowB&&Math.abs(y.get(a)-y.get(b))<=tol && sign(d.get(i))>0 && y.get(i)<y.get(b)-.003)
                    return .70;
            }
        }
        return 0;
    }

    private static double exhaustionScore(List<Double>d,List<Double>body,List<Double>upper,List<Double>lower,
                                          int s,int k,boolean resistance,boolean support){
        if(k<4)return 0;
        int i=s+k-1,a=i-2,b=i-1;
        int sa=sign(d.get(a)),sb=sign(d.get(b)),sc=sign(d.get(i));
        double ba=clip01(body.get(a)),bb=clip01(body.get(b)),bc=clip01(body.get(i));
        if(sa>0&&sb>0 && ba>.38 && bb<ba*.90 && bc<bb*.90 && upper.get(i)>.30 && resistance)
            return -.68;
        if(sa<0&&sb<0 && ba>.38 && bb<ba*.90 && bc<bb*.90 && lower.get(i)>.30 && support)
            return .68;
        if(sa==sb&&sb==sc&&sc>0&&bc<bb*.70&&bb<ba*.82)return -.38;
        if(sa==sb&&sb==sc&&sc<0&&bc<bb*.70&&bb<ba*.82)return .38;
        return 0;
    }

    private static double volatilityRatio(List<Double>r,int s,int k){
        if(k<4)return 1;
        int recent=Math.min(2,k);
        double a=mean(r,s+k-recent,recent);
        double b=mean(r,s,Math.max(1,k-recent));
        return b<=.0001?1:a/b;
    }

    private static double location(List<Double>y,int s,int k){
        double min=1,max=0;
        for(int q=s;q<s+k;q++){min=Math.min(min,y.get(q));max=Math.max(max,y.get(q));}
        return clamp((y.get(s+k-1)-min)/Math.max(.004,max-min),0,1);
    }
    private static double bodyHeight(List<Double>range,List<Double>body,int i){
        return Math.max(.0005,range.get(i)*clip01(body.get(i)));
    }
    private static double bodyTop(List<Double>y,List<Double>range,List<Double>body,int i){
        return y.get(i)-bodyHeight(range,body,i)/2.0;
    }
    private static double bodyBottom(List<Double>y,List<Double>range,List<Double>body,int i){
        return y.get(i)+bodyHeight(range,body,i)/2.0;
    }
    private static double top(List<Double>y,List<Double>range,int i){return y.get(i)-range.get(i)/2.0;}
    private static double bottom(List<Double>y,List<Double>range,int i){return y.get(i)+range.get(i)/2.0;}
    private static double mean(List<Double>a,int s,int k){
        if(k<=0)return 0; int st=Math.max(0,s), e=Math.min(a.size(),st+k); if(e<=st)return 0;
        double z=0; for(int q=st;q<e;q++)z+=a.get(q); return z/(e-st);
    }
    private static int sign(double v){return v>.12?1:v<-.12?-1:0;}
    private static double clip01(double v){return clamp(v,0,1);}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
}
