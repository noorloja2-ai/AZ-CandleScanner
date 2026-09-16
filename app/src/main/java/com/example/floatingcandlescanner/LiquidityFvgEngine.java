package com.example.floatingcandlescanner;

import java.util.List;

/**
 * Visual liquidity + Fair Value Gap (FVG) confluence layer.
 *
 * This engine intentionally treats FVG/liquidity concepts as CONTEXT, not as
 * standalone trade commands. Candle geometry is reconstructed from screen
 * pixels, so every detection is an approximation and must agree with recent
 * structure, rejection and/or candle direction before it is marked confirmed.
 *
 * Screen coordinates are inverted: smaller Y = higher price.
 */
public final class LiquidityFvgEngine {
    private LiquidityFvgEngine() {}

    public static final class Result {
        public final String name;
        public final double directionalScore; // + BUY, - SELL
        public final double confidence;       // 0..1
        public final double uncertainty;      // 0..1
        public final boolean confirmed;
        public final boolean fvg;
        public final boolean liquiditySweep;
        public final boolean bos;

        Result(String n,double d,double c,double u,boolean ok,
               boolean hasFvg,boolean sweep,boolean hasBos){
            name=n;
            directionalScore=clamp(d,-1,1);
            confidence=clamp(c,0,1);
            uncertainty=clamp(u,0,1);
            confirmed=ok;
            fvg=hasFvg;
            liquiditySweep=sweep;
            bos=hasBos;
        }

        public boolean strong(){
            return confirmed && confidence>=.66 && Math.abs(directionalScore)>=.50 && uncertainty<=.48;
        }

        public boolean directional(){ return Math.abs(directionalScore)>=.18; }
    }

    private static final class Pick {
        String name="NO LIQUIDITY/FVG CONFLUENCE";
        double dir=0,conf=.10,unc=.70,merit=0;
        boolean confirmed=false,fvg=false,sweep=false,bos=false;

        void add(String n,double d,double c,double u,boolean ok,
                 boolean hasFvg,boolean hasSweep,boolean hasBos){
            double merit=Math.abs(d)*c*(ok?1.0:.70)
                    *(hasFvg&&hasSweep?1.16:1.0)
                    *(hasBos?1.08:1.0);
            if(merit>this.merit){
                name=n;dir=d;conf=c;unc=u;confirmed=ok;
                fvg=hasFvg;sweep=hasSweep;bos=hasBos;this.merit=merit;
            }
        }
    }

    private static final class Gap {
        int dir;            // +1 bullish, -1 bearish
        int index;          // third candle index of the gap
        double loY,hiY;     // screen-coordinate zone, loY < hiY
        boolean bos;
        Gap(int d,int i,double lo,double hi,boolean b){dir=d;index=i;loY=lo;hiY=hi;bos=b;}
    }

    public static Result analyze(List<Double>d,List<Double>y,List<Double>range,
                                 List<Double>body,List<Double>upper,List<Double>lower,
                                 int s,int k,double trend){
        if(d==null||y==null||range==null||body==null||upper==null||lower==null||
                k<3||s<0||s+k>d.size())
            return new Result("NO LIQUIDITY/FVG CONFLUENCE",0,.10,.82,false,false,false,false);

        int i=s+k-1;
        Pick p=new Pick();
        int si=sign(d.get(i));
        double bi=clip01(body.get(i));
        double ui=clip01(upper.get(i));
        double li=clip01(lower.get(i));
        double avgR=Math.max(.003,mean(range,Math.max(s,i-Math.min(6,k-1)),Math.min(6,k-1)));
        double tol=Math.max(.0025,avgR*.10);

        // ---------------------------------------------------------------
        // 1) Liquidity sweep of recent highs/lows.
        // ---------------------------------------------------------------
        int histStart=Math.max(s,i-Math.min(8,k-1));
        double priorHighY=1.0; // highest price = smallest Y
        double priorLowY=0.0;  // lowest price = largest Y
        for(int q=histStart;q<i;q++){
            priorHighY=Math.min(priorHighY,top(y,range,q));
            priorLowY=Math.max(priorLowY,bottom(y,range,q));
        }

        double curHigh=top(y,range,i), curLow=bottom(y,range,i);
        double close=closeY(y,range,body,d,i);
        boolean sweepBelow=curLow>priorLowY+tol*.45 && close<priorLowY+tol*.45
                && li>=Math.max(.28,bi*1.05);
        boolean sweepAbove=curHigh<priorHighY-tol*.45 && close>priorHighY-tol*.45
                && ui>=Math.max(.28,bi*1.05);

        boolean eqLows=equalLows(y,range,histStart,i,tol*1.55);
        boolean eqHighs=equalHighs(y,range,histStart,i,tol*1.55);

        if(sweepBelow){
            double conf=eqLows?.84:.75;
            p.add(eqLows?"EQ LOWS LIQUIDITY SWEEP":"LIQUIDITY SWEEP BELOW LOWS",
                    +.70,conf,.28,true,false,true,false);
        }
        if(sweepAbove){
            double conf=eqHighs?.84:.75;
            p.add(eqHighs?"EQ HIGHS LIQUIDITY SWEEP":"LIQUIDITY SWEEP ABOVE HIGHS",
                    -.70,conf,.28,true,false,true,false);
        }

        // ---------------------------------------------------------------
        // 2) Find visual three-candle FVGs / imbalances.
        //    Bullish FVG: candle 3 low is above candle 1 high.
        //    Bearish FVG: candle 3 high is below candle 1 low.
        // ---------------------------------------------------------------
        Gap latestBull=null,latestBear=null;
        int bullCount=0,bearCount=0;
        for(int c=Math.max(s+2,i-8);c<=i;c++){
            int a=c-2;
            double aHigh=top(y,range,a),aLow=bottom(y,range,a);
            double cHigh=top(y,range,c),cLow=bottom(y,range,c);
            double localR=Math.max(.003,(range.get(a)+range.get(c))/2.0);
            double gapTol=Math.max(.0020,localR*.06);

            if(cLow<aHigh-gapTol){
                boolean bos=breaksPriorHigh(y,range,s,a,c,gapTol);
                latestBull=new Gap(+1,c,cLow,aHigh,bos);
                bullCount++;
            }
            if(cHigh>aLow+gapTol){
                boolean bos=breaksPriorLow(y,range,s,a,c,gapTol);
                latestBear=new Gap(-1,c,aLow,cHigh,bos);
                bearCount++;
            }
        }

        // A newly formed FVG is context only. Require impulse/structure agreement
        // before giving it directional weight.
        if(latestBull!=null && latestBull.index==i){
            boolean ctx=si>0 && (trend>.10 || latestBull.bos);
            String n=latestBull.bos?"BULLISH FVG AFTER BOS":"BULLISH FVG";
            p.add(n,+.48,latestBull.bos?.68:.57,.46,ctx,true,false,latestBull.bos);
        }
        if(latestBear!=null && latestBear.index==i){
            boolean ctx=si<0 && (trend<-.10 || latestBear.bos);
            String n=latestBear.bos?"BEARISH FVG AFTER BOS":"BEARISH FVG";
            p.add(n,-.48,latestBear.bos?.68:.57,.46,ctx,true,false,latestBear.bos);
        }

        // Double FVG: two recent same-direction visual imbalances plus current
        // directional agreement. Still not enough by itself to bypass WAIT.
        if(bullCount>=2 && si>0)
            p.add("DOUBLE BULLISH FVG",+.54,.66,.40,trend>=-.08,true,false,
                    latestBull!=null&&latestBull.bos);
        if(bearCount>=2 && si<0)
            p.add("DOUBLE BEARISH FVG",-.54,.66,.40,trend<=.08,true,false,
                    latestBear!=null&&latestBear.bos);

        // ---------------------------------------------------------------
        // 3) FVG retest/hold. Search only gaps created before the latest candle.
        // ---------------------------------------------------------------
        Gap holdBull=findRetestedGap(d,y,range,body,s,k,+1,tol);
        Gap holdBear=findRetestedGap(d,y,range,body,s,k,-1,tol);
        boolean bullHold=holdBull!=null && si>0;
        boolean bearHold=holdBear!=null && si<0;

        if(bullHold){
            boolean combo=sweepBelow;
            p.add(combo?"LIQUIDITY SWEEP + BULLISH FVG HOLD":"BULLISH FVG RETEST/HOLD",
                    combo?+.84:+.66,combo?.90:.76,combo?.14:.28,true,true,combo,holdBull.bos);
        }
        if(bearHold){
            boolean combo=sweepAbove;
            p.add(combo?"LIQUIDITY SWEEP + BEARISH FVG HOLD":"BEARISH FVG RETEST/HOLD",
                    combo?-.84:-.66,combo?.90:.76,combo?.14:.28,true,true,combo,holdBear.bos);
        }

        // Liquidity sweep + same-direction recent FVG is useful confluence even if
        // the latest candle did not make a textbook zone retest.
        if(sweepBelow && latestBull!=null)
            p.add("BULLISH LIQUIDITY + FVG CONFLUENCE",+.78,.86,.18,true,true,true,latestBull.bos);
        if(sweepAbove && latestBear!=null)
            p.add("BEARISH LIQUIDITY + FVG CONFLUENCE",-.78,.86,.18,true,true,true,latestBear.bos);

        // Ambiguity guard: simultaneous opposite imbalances or tiny/doji candle.
        double uncertainty=p.unc;
        if(latestBull!=null&&latestBear!=null&&Math.abs(latestBull.index-latestBear.index)<=2)
            uncertainty=Math.max(uncertainty,.54);
        if(bi<=.14 && ui>=.20 && li>=.20)
            uncertainty=Math.max(uncertainty,.62);
        if(Math.abs(trend)>.25 && Math.abs(p.dir)>.10 && Math.signum(p.dir)!=Math.signum(trend)
                && !p.sweep)
            uncertainty=Math.max(uncertainty,.50);

        double conf=clamp(p.conf*(1.0-.30*Math.max(0,uncertainty-p.unc)),.10,.94);
        double dir=p.dir*(1.0-.20*Math.max(0,uncertainty-.30));
        return new Result(p.name,dir,conf,uncertainty,p.confirmed,p.fvg,p.sweep,p.bos);
    }

    private static Gap findRetestedGap(List<Double>d,List<Double>y,List<Double>range,List<Double>body,
                                       int s,int k,int wanted,double tol){
        int i=s+k-1;
        for(int c=i-1;c>=Math.max(s+2,i-7);c--){
            int a=c-2;
            double aHigh=top(y,range,a),aLow=bottom(y,range,a);
            double cHigh=top(y,range,c),cLow=bottom(y,range,c);
            double localR=Math.max(.003,(range.get(a)+range.get(c))/2.0);
            double gapTol=Math.max(.0020,localR*.06);
            Gap g=null;
            if(wanted>0 && cLow<aHigh-gapTol)
                g=new Gap(+1,c,cLow,aHigh,breaksPriorHigh(y,range,s,a,c,gapTol));
            if(wanted<0 && cHigh>aLow+gapTol)
                g=new Gap(-1,c,aLow,cHigh,breaksPriorLow(y,range,s,a,c,gapTol));
            if(g==null) continue;

            double curTop=top(y,range,i),curBottom=bottom(y,range,i);
            boolean overlaps=curBottom>=g.loY-tol && curTop<=g.hiY+tol;
            if(!overlaps) continue;
            double close=closeY(y,range,body,d,i);
            if(wanted>0 && close<=g.hiY+tol*.35) return g;
            if(wanted<0 && close>=g.loY-tol*.35) return g;
        }
        return null;
    }

    private static boolean breaksPriorHigh(List<Double>y,List<Double>r,int s,int a,int c,double tol){
        if(a<=s)return false;
        double prior=1.0;
        for(int q=Math.max(s,a-5);q<a;q++)prior=Math.min(prior,top(y,r,q));
        return top(y,r,c)<prior-tol*.35;
    }

    private static boolean breaksPriorLow(List<Double>y,List<Double>r,int s,int a,int c,double tol){
        if(a<=s)return false;
        double prior=0.0;
        for(int q=Math.max(s,a-5);q<a;q++)prior=Math.max(prior,bottom(y,r,q));
        return bottom(y,r,c)>prior+tol*.35;
    }

    private static boolean equalLows(List<Double>y,List<Double>r,int s,int end,double tol){
        for(int a=s;a<end-1;a++) for(int b=a+1;b<end;b++)
            if(Math.abs(bottom(y,r,a)-bottom(y,r,b))<=tol) return true;
        return false;
    }

    private static boolean equalHighs(List<Double>y,List<Double>r,int s,int end,double tol){
        for(int a=s;a<end-1;a++) for(int b=a+1;b<end;b++)
            if(Math.abs(top(y,r,a)-top(y,r,b))<=tol) return true;
        return false;
    }

    private static double closeY(List<Double>y,List<Double>range,List<Double>body,List<Double>d,int i){
        double h=Math.max(.0005,range.get(i)*clip01(body.get(i)))/2.0;
        int s=sign(d.get(i));
        if(s>0)return y.get(i)-h; // bullish closes at upper body edge
        if(s<0)return y.get(i)+h; // bearish closes at lower body edge
        return y.get(i);
    }

    private static double top(List<Double>y,List<Double>range,int i){return y.get(i)-range.get(i)/2.0;}
    private static double bottom(List<Double>y,List<Double>range,int i){return y.get(i)+range.get(i)/2.0;}
    private static double mean(List<Double>a,int s,int k){
        if(k<=0)return 0; int st=Math.max(0,s),e=Math.min(a.size(),st+k); if(e<=st)return 0;
        double z=0;for(int q=st;q<e;q++)z+=a.get(q);return z/(e-st);
    }
    private static int sign(double v){return v>.12?1:v<-.12?-1:0;}
    private static double clip01(double v){return clamp(v,0,1);}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
}
