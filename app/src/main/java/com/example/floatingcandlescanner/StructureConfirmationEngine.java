package com.example.floatingcandlescanner;

import java.util.List;

/**
 * v13 structure/confirmation layer built from visual candle geometry.
 *
 * The source charts supplied by the user emphasize: break of structure (BOS),
 * failed new highs/lows, break-and-retest, double-bottom/top confirmation,
 * wedge breaks, liquidity-grab confirmation and a confirmation candle after a
 * rejection pattern. This engine implements only what can be approximated from
 * screenshot-derived candle geometry. SMC labels are treated as context, never
 * as proof of institutional activity or as a stand-alone trade trigger.
 */
public final class StructureConfirmationEngine {
    private StructureConfirmationEngine() {}

    public static final class Result {
        public final String name;
        public final double directionalScore; // +BUY, -SELL
        public final double confidence;
        public final double uncertainty;
        public final boolean confirmed;
        public final boolean structureBreak;
        public final boolean retest;
        public final boolean failedBreak;

        Result(String n,double d,double c,double u,boolean ok,boolean bos,boolean rt,boolean fb){
            name=n; directionalScore=clamp(d,-1,1); confidence=clamp(c,0,1);
            uncertainty=clamp(u,0,1); confirmed=ok; structureBreak=bos; retest=rt; failedBreak=fb;
        }
        public boolean strong(){
            return confirmed && confidence>=.68 && Math.abs(directionalScore)>=.54 && uncertainty<=.50;
        }
    }

    private static final class Pick {
        String name="NO STRUCTURE CONFIRMATION";
        double dir=0, conf=.10, unc=.76, merit=0;
        boolean ok=false,bos=false,retest=false,failed=false;
        void add(String n,double d,double c,double u,boolean confirmed,boolean isBos,boolean isRetest,boolean isFailed){
            double m=Math.abs(d)*c*(confirmed?1.0:.72)*(isRetest?1.08:1.0)*(isFailed?1.05:1.0);
            if(m>merit){ name=n;dir=d;conf=c;unc=u;ok=confirmed;bos=isBos;retest=isRetest;failed=isFailed;merit=m; }
        }
    }

    public static Result analyze(List<Double>d,List<Double>y,List<Double>range,List<Double>body,
                                 List<Double>upper,List<Double>lower,int s,int k,double trend){
        if(d==null||y==null||range==null||body==null||upper==null||lower==null||k<5)
            return new Result("NO STRUCTURE CONFIRMATION",0,.10,.82,false,false,false,false);

        int i=s+k-1;
        int si=sign(d.get(i));
        double bi=clip01(body.get(i)), ui=clip01(upper.get(i)), li=clip01(lower.get(i));
        double avgR=Math.max(.003,mean(range,Math.max(s,i-5),Math.min(5,k-1)));
        double tol=Math.max(.0025,avgR*.12);
        Pick p=new Pick();

        // Reference swing zone excludes the newest two candles so that a breakout
        // candidate cannot redefine the level that it is supposed to break.
        int refEnd=Math.max(s+2,i-1);
        double refHigh=1.0, refLow=0.0;
        for(int q=Math.max(s,refEnd-7);q<refEnd;q++){
            refHigh=Math.min(refHigh,top(y,range,q));
            refLow=Math.max(refLow,bottom(y,range,q));
        }

        double close=closeY(y,range,body,d,i);
        boolean bosUp=si>0 && bi>=.42 && close<refHigh-tol*.35;
        boolean bosDown=si<0 && bi>=.42 && close>refLow+tol*.35;
        if(bosUp) p.add("BULLISH MARKET STRUCTURE BREAK (BOS)",+.66,.74,.32,true,true,false,false);
        if(bosDown) p.add("BEARISH MARKET STRUCTURE BREAK (BOS)",-.66,.74,.32,true,true,false,false);

        // New-high/new-low failure: prior candle sweeps/breaks the old extreme,
        // then the latest candle closes back inside with opposite direction.
        if(k>=6){
            int a=i-1;
            double oldHigh=1.0,oldLow=0.0;
            for(int q=Math.max(s,a-6);q<a;q++){
                oldHigh=Math.min(oldHigh,top(y,range,q));
                oldLow=Math.max(oldLow,bottom(y,range,q));
            }
            boolean priorHighBreak=top(y,range,a)<oldHigh-tol*.30;
            boolean priorLowBreak=bottom(y,range,a)>oldLow+tol*.30;
            if(priorHighBreak && si<0 && close>oldHigh+tol*.15)
                p.add("NEW HIGH FAIL / BEARISH CHoCH",-.84,.87,.18,true,true,false,true);
            if(priorLowBreak && si>0 && close<oldLow-tol*.15)
                p.add("NEW LOW FAIL / BULLISH CHoCH",+.84,.87,.18,true,true,false,true);
        }

        // Breakout then retest of the broken level with wick/body rejection.
        // Search a recent breakout candle, then ask whether the newest candle
        // revisits that level and closes away from it.
        for(int b=Math.max(s+3,i-4);b<i;b++){
            double h=1.0,l=0.0;
            for(int q=Math.max(s,b-6);q<b;q++){ h=Math.min(h,top(y,range,q)); l=Math.max(l,bottom(y,range,q)); }
            double bc=closeY(y,range,body,d,b);
            boolean upBreak=sign(d.get(b))>0 && body.get(b)>=.40 && bc<h-tol*.30;
            boolean dnBreak=sign(d.get(b))<0 && body.get(b)>=.40 && bc>l+tol*.30;
            if(upBreak){
                boolean touch=bottom(y,range,i)>=h-tol*.75 && top(y,range,i)<=h+avgR*.65;
                boolean reject=si>0 && (li>=Math.max(.22,bi*.70) || bi>=.48) && close<h+tol*.20;
                if(touch&&reject) p.add("BREAKOUT + SUPPORT RETEST + BULLISH CONFIRMATION",+.88,.91,.14,true,true,true,false);
            }
            if(dnBreak){
                boolean touch=top(y,range,i)<=l+tol*.75 && bottom(y,range,i)>=l-avgR*.65;
                boolean reject=si<0 && (ui>=Math.max(.22,bi*.70) || bi>=.48) && close>l-tol*.20;
                if(touch&&reject) p.add("BREAKDOWN + RESISTANCE RETEST + BEARISH CONFIRMATION",-.88,.91,.14,true,true,true,false);
            }
        }

        // Confirmation-candle logic for hammer/shooting-star style rejection.
        if(k>=2){
            int a=i-1;
            double ab=clip01(body.get(a)), au=clip01(upper.get(a)), al=clip01(lower.get(a));
            boolean hammerShape=ab<=.40 && al>=Math.max(.40,ab*1.8) && au<=.28;
            boolean starShape=ab<=.40 && au>=Math.max(.40,ab*1.8) && al<=.28;
            if(hammerShape && si>0 && bi>=.40)
                p.add("HAMMER + BULLISH CONFIRMATION CANDLE",+.76,.82,.24,true,false,false,false);
            if(starShape && si<0 && bi>=.40)
                p.add("SHOOTING STAR + BEARISH CONFIRMATION CANDLE",-.76,.82,.24,true,false,false,false);
        }

        // Double-bottom / double-top + neckline break/retest proxy.
        double dbl=doubleTurnBreakRetest(d,y,range,body,upper,lower,s,k,tol);
        if(dbl>.50) p.add("DOUBLE BOTTOM + BOS + RETEST",+.90,.91,.13,true,true,true,false);
        if(dbl<-.50) p.add("DOUBLE TOP + BOS + RETEST",-.90,.91,.13,true,true,true,false);

        // Rising/falling wedge break proxy. The lines are estimated from recent
        // candle highs/lows; the latest completed candle must break the converging
        // boundary in the expected direction.
        double wedge=wedgeBreak(d,y,range,body,s,k);
        if(wedge>.45) p.add("FALLING WEDGE BULLISH BREAK",+.78,.80,.28,true,true,false,false);
        if(wedge<-.45) p.add("RISING WEDGE BEARISH BREAK",-.78,.80,.28,true,true,false,false);

        // Liquidity-grab style rejection at an old swing extreme plus structure
        // confirmation. This does not claim actual stop orders were present.
        boolean sweepLow=bottom(y,range,i)>refLow+tol*.30 && close<refLow+tol*.20 && li>=Math.max(.30,bi);
        boolean sweepHigh=top(y,range,i)<refHigh-tol*.30 && close>refHigh-tol*.20 && ui>=Math.max(.30,bi);
        if(sweepLow && (trend<-.10 || si>0))
            p.add("LOW-SWEEP + BULLISH REVERSAL CONFIRMATION",+.72,.78,.28,si>0,false,false,true);
        if(sweepHigh && (trend>.10 || si<0))
            p.add("HIGH-SWEEP + BEARISH REVERSAL CONFIRMATION",-.72,.78,.28,si<0,false,false,true);

        double uncertainty=p.unc;
        if(bi<=.14 && ui>=.20 && li>=.20) uncertainty=Math.max(uncertainty,.68);
        if(Math.abs(trend)<.08 && !p.retest && !p.failed && !p.bos) uncertainty=Math.max(uncertainty,.48);
        double conf=clamp(p.conf*(1.0-.24*Math.max(0,uncertainty-p.unc)),.10,.95);
        double dir=p.dir*(1.0-.18*Math.max(0,uncertainty-.30));
        return new Result(p.name,dir,conf,uncertainty,p.ok,p.bos,p.retest,p.failed);
    }

    private static double doubleTurnBreakRetest(List<Double>d,List<Double>y,List<Double>r,List<Double>body,
                                                List<Double>upper,List<Double>lower,int s,int k,double tol){
        if(k<9)return 0;
        int i=s+k-1;
        int start=Math.max(s,i-10);
        int l1=-1,l2=-1,h1=-1,h2=-1;
        for(int q=start+1;q<i-2;q++){
            double b=bottom(y,r,q),t=top(y,r,q);
            if(b>=bottom(y,r,q-1)&&b>=bottom(y,r,q+1)){ l1=l2;l2=q; }
            if(t<=top(y,r,q-1)&&t<=top(y,r,q+1)){ h1=h2;h2=q; }
        }
        double close=closeY(y,r,body,d,i);
        if(l1>=0&&l2>l1+1){
            double lowDiff=Math.abs(bottom(y,r,l1)-bottom(y,r,l2));
            if(lowDiff<=Math.max(tol*2.2,(r.get(l1)+r.get(l2))*.16)){
                double neckline=1.0;
                for(int q=l1+1;q<l2;q++)neckline=Math.min(neckline,top(y,r,q));
                boolean brokeBefore=false;
                for(int q=l2+1;q<i;q++) if(closeY(y,r,body,d,q)<neckline-tol*.25) brokeBefore=true;
                boolean retest=bottom(y,r,i)>=neckline-tol*.80 && top(y,r,i)<=neckline+r.get(i)*.70;
                if(brokeBefore&&retest&&sign(d.get(i))>0&&close<neckline+tol*.20)return .90;
            }
        }
        if(h1>=0&&h2>h1+1){
            double hiDiff=Math.abs(top(y,r,h1)-top(y,r,h2));
            if(hiDiff<=Math.max(tol*2.2,(r.get(h1)+r.get(h2))*.16)){
                double neckline=0.0;
                for(int q=h1+1;q<h2;q++)neckline=Math.max(neckline,bottom(y,r,q));
                boolean brokeBefore=false;
                for(int q=h2+1;q<i;q++) if(closeY(y,r,body,d,q)>neckline+tol*.25) brokeBefore=true;
                boolean retest=top(y,r,i)<=neckline+tol*.80 && bottom(y,r,i)>=neckline-r.get(i)*.70;
                if(brokeBefore&&retest&&sign(d.get(i))<0&&close>neckline-tol*.20)return -.90;
            }
        }
        return 0;
    }

    private static double wedgeBreak(List<Double>d,List<Double>y,List<Double>r,List<Double>body,int s,int k){
        if(k<7)return 0;
        int i=s+k-1;
        int a=Math.max(s,i-6), n=i-a;
        if(n<5)return 0;
        double topSlope=slopeEdge(y,r,a,i,true);
        double botSlope=slopeEdge(y,r,a,i,false);
        double oldWidth=bottom(y,r,a)-top(y,r,a);
        double newWidth=bottom(y,r,i-1)-top(y,r,i-1);
        if(newWidth>=oldWidth*.92)return 0;
        double predTop=linearPredictEdge(y,r,a,i,true);
        double predBot=linearPredictEdge(y,r,a,i,false);
        double c=closeY(y,r,body,d,i);
        // screen-y slope < 0 means price line is rising.
        if(topSlope<-.001 && botSlope<-.001 && c>predBot+.0025 && sign(d.get(i))<0 && body.get(i)>=.38)
            return -.76; // rising wedge breaks down
        if(topSlope>.001 && botSlope>.001 && c<predTop-.0025 && sign(d.get(i))>0 && body.get(i)>=.38)
            return .76; // falling wedge breaks up
        return 0;
    }

    private static double slopeEdge(List<Double>y,List<Double>r,int s,int end,boolean upper){
        int n=end-s;if(n<2)return 0;double sx=0,sy=0,sxx=0,sxy=0;
        for(int j=0;j<n;j++){double x=j,v=upper?top(y,r,s+j):bottom(y,r,s+j);sx+=x;sy+=v;sxx+=x*x;sxy+=x*v;}
        double den=n*sxx-sx*sx;return Math.abs(den)<1e-9?0:(n*sxy-sx*sy)/den;
    }
    private static double linearPredictEdge(List<Double>y,List<Double>r,int s,int end,boolean upper){
        int n=end-s;if(n<2)return upper?top(y,r,end-1):bottom(y,r,end-1);
        double m=slopeEdge(y,r,s,end,upper), sy=0,sx=0;
        for(int j=0;j<n;j++){sx+=j;sy+=upper?top(y,r,s+j):bottom(y,r,s+j);} double b=(sy-m*sx)/n;
        return b+m*n;
    }
    private static double closeY(List<Double>y,List<Double>r,List<Double>body,List<Double>d,int i){
        double h=Math.max(.0005,r.get(i)*clip01(body.get(i)))/2.0;int s=sign(d.get(i));
        if(s>0)return y.get(i)-h;if(s<0)return y.get(i)+h;return y.get(i);
    }
    private static double top(List<Double>y,List<Double>r,int i){return y.get(i)-r.get(i)/2.0;}
    private static double bottom(List<Double>y,List<Double>r,int i){return y.get(i)+r.get(i)/2.0;}
    private static double mean(List<Double>a,int s,int k){int st=Math.max(0,s),e=Math.min(a.size(),st+k);if(e<=st)return 0;double z=0;for(int q=st;q<e;q++)z+=a.get(q);return z/(e-st);}
    private static int sign(double v){return v>.12?1:v<-.12?-1:0;}
    private static double clip01(double v){return clamp(v,0,1);}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
}
