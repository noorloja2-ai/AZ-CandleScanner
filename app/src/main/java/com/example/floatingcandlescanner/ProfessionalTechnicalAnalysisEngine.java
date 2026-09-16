package com.example.floatingcandlescanner;

import java.util.ArrayList;
import java.util.List;

/**
 * v12.1 professional chart-structure confirmation layer.
 *
 * Rules are intentionally confirmation-first:
 * - trend = relative swing peaks/troughs (HH/HL or LH/LL)
 * - support/resistance = repeated visual reversal zones
 * - chart patterns are not considered activated until a visual breakout occurs
 * - false/failed breakouts reduce confidence and can create an opposite bias
 * - double/triple turns, rectangles, triangles and flags require structural confirmation
 *
 * Inputs are screenshot-derived visual candle proxies, not broker OHLC values.
 */
public final class ProfessionalTechnicalAnalysisEngine {
    private ProfessionalTechnicalAnalysisEngine() {}

    public static final class Result {
        public final String name;
        public final double directionalScore; // +BUY / -SELL
        public final double confidence;       // 0..1
        public final double uncertainty;      // 0..1
        public final double trendScore;       // HH/HL +, LH/LL -
        public final boolean breakoutConfirmed;
        public final boolean falseBreakout;
        public final int supportTouches;
        public final int resistanceTouches;

        Result(String n,double d,double c,double u,double t,
               boolean b,boolean f,int st,int rt){
            name=n;
            directionalScore=clamp(d,-1,1);
            confidence=clamp(c,0,1);
            uncertainty=clamp(u,0,1);
            trendScore=clamp(t,-1,1);
            breakoutConfirmed=b;
            falseBreakout=f;
            supportTouches=st;
            resistanceTouches=rt;
        }

        public boolean strong(){
            return confidence>=.68 && Math.abs(directionalScore)>=.52 &&
                    uncertainty<.58 && (breakoutConfirmed || falseBreakout || Math.abs(trendScore)>=.70);
        }
    }

    private static final class Pick {
        String name="PRO STRUCTURE: UNCONFIRMED";
        double dir=0,conf=.10;
        boolean breakout=false,falseBreak=false;
        void add(String n,double d,double c,boolean b,boolean f){
            double merit=Math.abs(d)*c*(b||f?1.08:1.0);
            double current=Math.abs(dir)*conf*(breakout||falseBreak?1.08:1.0);
            if(merit>current){name=n;dir=d;conf=c;breakout=b;falseBreak=f;}
        }
    }

    private static final class Zones {
        double resistance=1.0; // screen Y of highest repeated area (smaller = higher price)
        double support=0.0;    // screen Y of lowest repeated area (larger = lower price)
        double tol=.004;
        int resistanceTouches=0,supportTouches=0;
    }

    public static Result analyze(
            List<Double>d,List<Double>y,List<Double>range,List<Double>body,
            List<Double>upper,List<Double>lower,int s,int k,double oldTrend){
        if(d==null||y==null||range==null||body==null||k<6)
            return new Result("PRO STRUCTURE: LOW DATA",0,.10,.85,0,false,false,0,0);

        // Fidelity-style structures need more history than a single candlestick.
        int end=s+k;
        int longK=Math.min(28,end);
        int ls=Math.max(0,end-longK);
        int lk=end-ls;
        int i=end-1;

        double swingTrend=swingTrend(y,range,ls,lk);
        double trend=Math.abs(swingTrend)>=.35?swingTrend:clamp(oldTrend,-1,1);
        Zones z=zones(y,range,ls,lk,i); // zones are based on completed history before latest where possible
        Pick p=new Pick();

        double breakout=confirmedBreakout(d,y,range,body,ls,lk,z);
        boolean bullBreak=breakout>.45, bearBreak=breakout<-.45;
        if(bullBreak)p.add("CONFIRMED RESISTANCE BREAKOUT",+.90,.90,true,false);
        if(bearBreak)p.add("CONFIRMED SUPPORT BREAKOUT",-.90,.90,true,false);

        double falseBreak=falseBreakout(d,y,range,body,ls,lk,z);
        if(falseBreak>.45)p.add("FAILED BEAR BREAKOUT / BEAR TRAP REVERSAL",+.82,.86,false,true);
        if(falseBreak<-.45)p.add("FAILED BULL BREAKOUT / BULL TRAP REVERSAL",-.82,.86,false,true);

        double turns=doubleTripleTurn(d,y,range,body,ls,lk);
        if(turns>.55)p.add(turnName(true,y,range,ls,lk),+.86,.87,true,false);
        if(turns<-.55)p.add(turnName(false,y,range,ls,lk),-.86,.87,true,false);

        double rectangle=rectangleBreakout(d,y,range,body,ls,lk);
        if(rectangle>.50)p.add("RECTANGLE RANGE BREAKOUT UP",+.78,.80,true,false);
        if(rectangle<-.50)p.add("RECTANGLE RANGE BREAKOUT DOWN",-.78,.80,true,false);

        PatternScore tri=triangleBreakout(d,y,range,body,ls,lk);
        if(Math.abs(tri.dir)>.50)p.add(tri.name,tri.dir,.82,true,false);

        double flag=flagPennant(d,y,range,body,ls,lk);
        if(flag>.50)p.add("FLAG / PENNANT BREAKOUT UP",+.80,.83,true,false);
        if(flag<-.50)p.add("FLAG / PENNANT BREAKOUT DOWN",-.80,.83,true,false);

        // Confirmed support/resistance role reversal. A prior broken support can
        // become resistance and vice versa; visual retest/rejection gets useful weight.
        double retest=roleReversalRetest(d,y,range,body,upper,lower,ls,lk,z);
        if(retest>.45)p.add("RESISTANCE->SUPPORT RETEST",+.72,.76,true,false);
        if(retest<-.45)p.add("SUPPORT->RESISTANCE RETEST",-.72,.76,true,false);

        // Pure trend is supporting evidence, not a pattern activation by itself.
        if(p.dir==0 && swingTrend>.68)p.add("HIGHER HIGHS + HIGHER LOWS",+.54,.69,false,false);
        if(p.dir==0 && swingTrend<-.68)p.add("LOWER HIGHS + LOWER LOWS",-.54,.69,false,false);

        double uncertainty=0.0;
        double vol=volatilityRatio(range,ls,lk);
        if(vol>2.6)uncertainty=Math.max(uncertainty,.55);
        if(vol<.42)uncertainty=Math.max(uncertainty,.52);
        if(z.resistanceTouches<2 && z.supportTouches<2)uncertainty=Math.max(uncertainty,.28);
        if(Math.abs(swingTrend)<.28 && !p.breakout && !p.falseBreak)uncertainty=Math.max(uncertainty,.34);

        // Conflicting trend and chosen structure should lower confidence, not be ignored.
        if(p.dir!=0 && Math.abs(trend)>.45 && Math.signum(p.dir)!=Math.signum(trend) && !p.falseBreak)
            uncertainty=Math.max(uncertainty,.40);

        double c=clamp(p.conf*(1.0-.34*uncertainty),.10,.95);
        double dir=p.dir*(1.0-.20*uncertainty);
        return new Result(p.name,dir,c,uncertainty,swingTrend,
                p.breakout,p.falseBreak,z.supportTouches,z.resistanceTouches);
    }

    private static double swingTrend(List<Double>y,List<Double>r,int s,int k){
        if(k<7)return 0;
        int end=s+k;
        List<Integer> highs=new ArrayList<>(),lows=new ArrayList<>();
        for(int q=s+1;q<end-1;q++){
            double tq=top(y,r,q);
            if(tq<=top(y,r,q-1)&&tq<=top(y,r,q+1))highs.add(q);
            double bq=bottom(y,r,q);
            if(bq>=bottom(y,r,q-1)&&bq>=bottom(y,r,q+1))lows.add(q);
        }
        double score=0; int parts=0;
        if(highs.size()>=2){
            int a=highs.get(highs.size()-2),b=highs.get(highs.size()-1);
            double da=top(y,r,a),db=top(y,r,b);
            // smaller screen Y = higher price
            if(db<da-.0025)score+=1; else if(db>da+.0025)score-=1;
            parts++;
        }
        if(lows.size()>=2){
            int a=lows.get(lows.size()-2),b=lows.get(lows.size()-1);
            double da=bottom(y,r,a),db=bottom(y,r,b);
            // smaller screen Y = higher trough
            if(db<da-.0025)score+=1; else if(db>da+.0025)score-=1;
            parts++;
        }
        if(parts==0)return 0;
        return clamp(score/parts,-1,1);
    }

    private static Zones zones(List<Double>y,List<Double>r,int s,int k,int latest){
        Zones z=new Zones();
        int end=Math.max(s+2,s+k-1); // exclude latest so it can be tested as breakout
        if(end<=s+2)end=s+k;
        double hi=1.0,lo=0.0;
        for(int q=s;q<end;q++){
            hi=Math.min(hi,top(y,r,q));
            lo=Math.max(lo,bottom(y,r,q));
        }
        double span=Math.max(.008,lo-hi);
        z.tol=Math.max(.0032,span*.085);
        z.resistance=hi; z.support=lo;
        for(int q=s;q<end;q++){
            if(Math.abs(top(y,r,q)-hi)<=z.tol)z.resistanceTouches++;
            if(Math.abs(bottom(y,r,q)-lo)<=z.tol)z.supportTouches++;
        }
        return z;
    }

    private static double confirmedBreakout(List<Double>d,List<Double>y,List<Double>r,List<Double>body,int s,int k,Zones z){
        if(k<5)return 0;
        int i=s+k-1;
        int si=sign(d.get(i));
        double close=y.get(i),t=top(y,r,i),b=bottom(y,r,i);
        boolean strongBody=body.get(i)>=.38;
        if(z.resistanceTouches>=2 && si>0 && strongBody &&
                t<z.resistance-z.tol*.35 && close<z.resistance-z.tol*.10)return .92;
        if(z.supportTouches>=2 && si<0 && strongBody &&
                b>z.support+z.tol*.35 && close>z.support+z.tol*.10)return -.92;
        return 0;
    }

    /** + means failed downside break -> bullish; - means failed upside break -> bearish. */
    private static double falseBreakout(List<Double>d,List<Double>y,List<Double>r,List<Double>body,int s,int k,Zones ignored){
        if(k<7)return 0;
        int i=s+k-1,p=i-1;
        // Build the reference zone from candles BEFORE the breakout candidate.
        // Otherwise the breakout candle would redefine the zone it is supposed to test.
        Zones z=zonesBefore(y,r,s,p);
        double pTop=top(y,r,p),pBot=bottom(y,r,p),cTop=top(y,r,i),cBot=bottom(y,r,i);
        boolean prevBullBreak=z.resistanceTouches>=2 && pTop<z.resistance-z.tol*.25;
        boolean prevBearBreak=z.supportTouches>=2 && pBot>z.support+z.tol*.25;
        boolean backInsideFromTop=prevBullBreak && y.get(i)>z.resistance-z.tol*.05 && sign(d.get(i))<0 && body.get(i)>=.25;
        boolean backInsideFromBottom=prevBearBreak && y.get(i)<z.support+z.tol*.05 && sign(d.get(i))>0 && body.get(i)>=.25;
        if(backInsideFromBottom && cTop<z.support+z.tol*.80)return .82;
        if(backInsideFromTop && cBot>z.resistance-z.tol*.80)return -.82;
        return 0;
    }

    private static Zones zonesBefore(List<Double>y,List<Double>r,int s,int endExclusive){
        Zones z=new Zones();
        if(endExclusive-s<3)return z;
        double hi=1.0,lo=0.0;
        for(int q=s;q<endExclusive;q++){
            hi=Math.min(hi,top(y,r,q));
            lo=Math.max(lo,bottom(y,r,q));
        }
        double span=Math.max(.008,lo-hi);
        z.tol=Math.max(.0032,span*.085);
        z.resistance=hi;z.support=lo;
        for(int q=s;q<endExclusive;q++){
            if(Math.abs(top(y,r,q)-hi)<=z.tol)z.resistanceTouches++;
            if(Math.abs(bottom(y,r,q)-lo)<=z.tol)z.supportTouches++;
        }
        return z;
    }

    /** + confirmed double/triple bottom; - confirmed double/triple top. */
    private static double doubleTripleTurn(List<Double>d,List<Double>y,List<Double>r,List<Double>body,int s,int k){
        if(k<8)return 0;
        int i=s+k-1;
        int end=i; // structure before latest breakout bar
        List<Integer> highs=new ArrayList<>(),lows=new ArrayList<>();
        for(int q=s+1;q<end-1;q++){
            if(top(y,r,q)<=top(y,r,q-1)&&top(y,r,q)<=top(y,r,q+1))highs.add(q);
            if(bottom(y,r,q)>=bottom(y,r,q-1)&&bottom(y,r,q)>=bottom(y,r,q+1))lows.add(q);
        }
        double tol=.008;
        // tops: two or three roughly equal peaks, latest candle breaks the intervening trough/neckline downward
        if(highs.size()>=2){
            int b=highs.get(highs.size()-1),a=highs.get(highs.size()-2);
            if(Math.abs(top(y,r,a)-top(y,r,b))<=tol){
                double neck=0;
                for(int q=a+1;q<b;q++)neck=Math.max(neck,bottom(y,r,q));
                if(neck>0 && sign(d.get(i))<0 && body.get(i)>=.32 && y.get(i)>neck+.0025)return -.80;
                if(highs.size()>=3){
                    int c=highs.get(highs.size()-3);
                    if(Math.abs(top(y,r,c)-top(y,r,b))<=tol*1.15 && sign(d.get(i))<0 && body.get(i)>=.32 && y.get(i)>neck+.002)return -.88;
                }
            }
        }
        if(lows.size()>=2){
            int b=lows.get(lows.size()-1),a=lows.get(lows.size()-2);
            if(Math.abs(bottom(y,r,a)-bottom(y,r,b))<=tol){
                double neck=1;
                for(int q=a+1;q<b;q++)neck=Math.min(neck,top(y,r,q));
                if(neck<1 && sign(d.get(i))>0 && body.get(i)>=.32 && y.get(i)<neck-.0025)return .80;
                if(lows.size()>=3){
                    int c=lows.get(lows.size()-3);
                    if(Math.abs(bottom(y,r,c)-bottom(y,r,b))<=tol*1.15 && sign(d.get(i))>0 && body.get(i)>=.32 && y.get(i)<neck-.002)return .88;
                }
            }
        }
        return 0;
    }

    private static String turnName(boolean bullish,List<Double>y,List<Double>r,int s,int k){
        // Keep the label conservative: the detector may have 2 or 3 valid touches.
        return bullish?"DOUBLE/TRIPLE BOTTOM + NECKLINE BREAK":"DOUBLE/TRIPLE TOP + NECKLINE BREAK";
    }

    private static double rectangleBreakout(List<Double>d,List<Double>y,List<Double>r,List<Double>body,int s,int k){
        if(k<9)return 0;
        int i=s+k-1,start=Math.max(s,i-10),end=i;
        double hi=1,lo=0;
        for(int q=start;q<end;q++){hi=Math.min(hi,top(y,r,q));lo=Math.max(lo,bottom(y,r,q));}
        double span=Math.max(.006,lo-hi),tol=Math.max(.003,span*.10);
        int ht=0,lt=0;
        for(int q=start;q<end;q++){
            if(Math.abs(top(y,r,q)-hi)<=tol)ht++;
            if(Math.abs(bottom(y,r,q)-lo)<=tol)lt++;
        }
        // A rectangle requires repeated interaction with both boundaries and no large drift.
        if(ht<2||lt<2)return 0;
        double drift=Math.abs(y.get(end-1)-y.get(start));
        if(drift>span*.65)return 0;
        if(sign(d.get(i))>0&&body.get(i)>.34&&y.get(i)<hi-tol*.15)return .72;
        if(sign(d.get(i))<0&&body.get(i)>.34&&y.get(i)>lo+tol*.15)return -.72;
        return 0;
    }

    private static final class PatternScore{String name="";double dir=0;}
    private static PatternScore triangleBreakout(List<Double>d,List<Double>y,List<Double>r,List<Double>body,int s,int k){
        PatternScore o=new PatternScore();
        if(k<9)return o;
        int i=s+k-1,start=Math.max(s,i-10),end=i;
        int mid=(start+end)/2;
        if(mid<=start||end-mid<2)return o;
        Ext a=extents(y,r,start,mid-start),b=extents(y,r,mid,end-mid);
        double highMove=b.hi-a.hi; // + means later highs are lower in price (screen lower-high)
        double lowMove=b.lo-a.lo;  // - means later lows are higher in price
        double eps=.0035;
        boolean upperFlat=Math.abs(highMove)<eps*1.5;
        boolean lowerFlat=Math.abs(lowMove)<eps*1.5;
        boolean lowerRising=lowMove< -eps;
        boolean upperFalling=highMove> eps;
        boolean symmetrical=upperFalling&&lowerRising;
        boolean ascending=upperFlat&&lowerRising;
        boolean descending=lowerFlat&&upperFalling;
        if(!symmetrical&&!ascending&&!descending)return o;

        double priorRes=Math.min(a.hi,b.hi),priorSup=Math.max(a.lo,b.lo);
        if(sign(d.get(i))>0&&body.get(i)>.34&&y.get(i)<priorRes-.002){
            o.dir=.76;
            o.name=ascending?"ASCENDING TRIANGLE BREAKOUT":symmetrical?"SYMMETRICAL TRIANGLE BREAKOUT UP":"DESCENDING TRIANGLE UPSIDE BREAK";
        }else if(sign(d.get(i))<0&&body.get(i)>.34&&y.get(i)>priorSup+.002){
            o.dir=-.76;
            o.name=descending?"DESCENDING TRIANGLE BREAKOUT":symmetrical?"SYMMETRICAL TRIANGLE BREAKOUT DOWN":"ASCENDING TRIANGLE DOWNSIDE BREAK";
        }
        return o;
    }

    private static final class Ext{double hi=1,lo=0;}
    private static Ext extents(List<Double>y,List<Double>r,int s,int k){
        Ext e=new Ext();
        for(int q=s;q<s+k;q++){e.hi=Math.min(e.hi,top(y,r,q));e.lo=Math.max(e.lo,bottom(y,r,q));}
        return e;
    }

    private static double flagPennant(List<Double>d,List<Double>y,List<Double>r,List<Double>body,int s,int k){
        if(k<8)return 0;
        int i=s+k-1;
        int impulseStart=Math.max(s,i-7);
        int impulseEnd=Math.max(impulseStart+2,i-4);
        if(impulseEnd>=i-1)return 0;
        double impulse=mean(d,impulseStart,impulseEnd-impulseStart+1);
        double impulseBody=mean(body,impulseStart,impulseEnd-impulseStart+1);
        double cons=mean(d,impulseEnd+1,i-impulseEnd-1);
        double consBody=mean(body,impulseEnd+1,i-impulseEnd-1);
        if(Math.abs(impulse)<.26||impulseBody<consBody*1.05)return 0;
        // Consolidation should be smaller and usually counter/sideways to impulse.
        if(impulse>0 && cons<.18 && cons>-.38 && sign(d.get(i))>0 && body.get(i)>.36){
            double res=1;for(int q=impulseEnd+1;q<i;q++)res=Math.min(res,top(y,r,q));
            if(top(y,r,i)<res-.0018)return .74;
        }
        if(impulse<0 && cons>-.18 && cons<.38 && sign(d.get(i))<0 && body.get(i)>.36){
            double sup=0;for(int q=impulseEnd+1;q<i;q++)sup=Math.max(sup,bottom(y,r,q));
            if(bottom(y,r,i)>sup+.0018)return -.74;
        }
        return 0;
    }

    private static double roleReversalRetest(List<Double>d,List<Double>y,List<Double>r,List<Double>body,
                                             List<Double>upper,List<Double>lower,int s,int k,Zones z){
        if(k<7||upper==null||lower==null)return 0;
        int i=s+k-1;
        // Need a prior close beyond a zone, followed by current rejection near it.
        for(int q=Math.max(s,i-5);q<i;q++){
            boolean brokeUp=y.get(q)<z.resistance-z.tol*.12;
            boolean brokeDown=y.get(q)>z.support+z.tol*.12;
            if(brokeUp && Math.abs(bottom(y,r,i)-z.resistance)<=z.tol*1.5 &&
                    sign(d.get(i))>0 && lower.get(i)>=Math.max(.28,body.get(i)*.9))return .62;
            if(brokeDown && Math.abs(top(y,r,i)-z.support)<=z.tol*1.5 &&
                    sign(d.get(i))<0 && upper.get(i)>=Math.max(.28,body.get(i)*.9))return -.62;
        }
        return 0;
    }

    private static double volatilityRatio(List<Double>r,int s,int k){
        if(k<4)return 1;
        int i=s+k-1; double prev=0;int n=0;
        for(int q=Math.max(s,i-5);q<i;q++){prev+=r.get(q);n++;}
        prev/=Math.max(1,n);
        return prev>.0001?r.get(i)/prev:1;
    }

    private static double mean(List<Double>x,int s,int k){
        if(k<=0)return 0;double m=0;int n=0;
        for(int q=s;q<s+k&&q<x.size();q++){m+=x.get(q);n++;}
        return n==0?0:m/n;
    }

    private static int sign(double x){return x>.06?1:x<-.06?-1:0;}
    private static double top(List<Double>y,List<Double>r,int i){return y.get(i)-r.get(i)/2.0;}
    private static double bottom(List<Double>y,List<Double>r,int i){return y.get(i)+r.get(i)/2.0;}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
}
