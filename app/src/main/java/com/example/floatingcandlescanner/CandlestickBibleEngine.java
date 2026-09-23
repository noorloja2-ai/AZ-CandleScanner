package com.example.floatingcandlescanner;

import java.util.ArrayList;
import java.util.List;

/**
 * Strategy layer derived from the user's uploaded 168-page Candlestick Trading Bible.
 *
 * The book repeatedly frames price-action trading as three questions:
 *   1) trend / market condition,
 *   2) key level,
 *   3) price-action signal.
 * It also emphasizes confluence, completed-candle confirmation, avoiding choppy
 * markets, and minimum 1:2 risk/reward.  This class converts those ideas into
 * conservative screenshot-geometry proxies.  It does NOT reproduce broker OHLC,
 * higher-timeframe top-down analysis, exact Fibonacci/MA values or account risk.
 *
 * Important source constraint: the book prefers 1H/4H/Daily (especially 4H/Daily)
 * for price-action setups. CandleScanner forecasts M1-M5, so this engine is used as
 * a confirmation/filter layer only, never as proof that the book endorses M1-M5.
 */
public final class CandlestickBibleEngine {
    private CandlestickBibleEngine() {}

    public static final class Result {
        public final String name;
        public final double directionalScore; // + BUY, - SELL
        public final double confidence;       // 0..1
        public final double uncertainty;      // 0..1
        public final double riskPenalty;      // 0..1
        public final boolean confirmed;
        public final boolean trendAligned;
        public final boolean keyLevel;
        public final boolean rangeSetup;
        public final boolean falseBreakout;
        public final boolean choppy;
        public final boolean riskRewardOk;

        Result(String n,double d,double c,double u,double rp,boolean ok,
               boolean ta,boolean kl,boolean rs,boolean fb,boolean ch,boolean rr){
            name=n;
            directionalScore=clamp(d,-1,1);
            confidence=clamp(c,0,1);
            uncertainty=clamp(u,0,1);
            riskPenalty=clamp(rp,0,1);
            confirmed=ok;
            trendAligned=ta;
            keyLevel=kl;
            rangeSetup=rs;
            falseBreakout=fb;
            choppy=ch;
            riskRewardOk=rr;
        }
        public boolean strong(){
            return confirmed && confidence>=.66 && Math.abs(directionalScore)>=.48
                    && uncertainty<.60 && riskPenalty<.62 && !choppy;
        }
        public boolean directional(){ return Math.abs(directionalScore)>=.28; }
    }

    private static final class Pick {
        String name="BIBLE: NO CONFIRMED SETUP";
        double dir=0,conf=.10,risk=.18;
        boolean ok=false,trend=false,key=false,range=false,falseBreak=false,rr=false;
        void add(String n,double d,double c,double rp,boolean confirmed,
                 boolean trendAligned,boolean keyLevel,boolean rangeSetup,
                 boolean falseBreakout,boolean riskRewardOk){
            double merit=Math.abs(d)*c*(confirmed?1.0:.68)*(1.0-.35*clamp(rp,0,1));
            double current=Math.abs(dir)*conf*(ok?1.0:.68)*(1.0-.35*clamp(risk,0,1));
            if(merit>current){
                name=n;dir=d;conf=c;risk=rp;ok=confirmed;trend=trendAligned;
                key=keyLevel;range=rangeSetup;falseBreak=falseBreakout;rr=riskRewardOk;
            }
        }
    }

    private static final class Regime {
        double trend=0;
        boolean range=false,choppy=false;
        double support=0,resistance=0,tol=.003;
        int supportTouches=0,resistanceTouches=0;
    }

    public static Result analyze(List<Double>d,List<Double>y,List<Double>range,
                                 List<Double>body,List<Double>upper,List<Double>lower,
                                 int s,int k,double oldTrend){
        if(d==null||y==null||range==null||body==null||upper==null||lower==null)
            return lowData();
        int end=Math.min(d.size(),y.size());
        end=Math.min(end,Math.min(range.size(),Math.min(body.size(),Math.min(upper.size(),lower.size()))));
        int start=Math.max(0,end-220),n=end-start;
        if(n<8)return lowData();

        double[] p=new double[n];
        for(int q=0;q<n;q++)p[q]=1.0-y.get(start+q); // larger = higher market price
        int last=n-1, li=end-1, pi=end-2;
        int sign=sign(d.get(li)),prevSign=sign(d.get(pi));
        double b=clip01(body.get(li)),uw=clip01(upper.get(li)),lw=clip01(lower.get(li));
        double avgRange=meanRange(range,start,end,Math.min(12,n));
        Regime rg=regime(p,range,start,end);

        // The uploaded book's top-down chapter prefers 1H/4H/Daily and warns about
        // noise on small timeframes. CandleScanner is M1-M5, so always retain a
        // modest uncertainty floor for this source-specific layer.
        double uncertainty=.20;
        double riskPenalty=.12;
        if(rg.choppy){
            return new Result("BIBLE: CHOPPY / STAY AWAY",0,.18,.86,.80,false,
                    false,false,false,false,true,false);
        }

        Pick pick=new Pick();

        // v16.8 Master Guide patterns use the canonical named-pattern detector.
        // A candle name is never a signal by itself: confirmation plus trend or
        // a measured support/resistance location is mandatory below.
        ReferencePatternEngine.Result masterGuide = ReferencePatternEngine.analyze(
                d,y,range,body,upper,lower,s,k,rg.trend);

        // -------- Basic geometry / source patterns --------
        boolean bullPin=lw>=Math.max(.46,b*1.75) && uw<=.23 && b<=.46;
        boolean bearPin=uw>=Math.max(.46,b*1.75) && lw<=.23 && b<=.46;
        boolean bullishEngulf=engulfing(y,range,body,d,li,true);
        boolean bearishEngulf=engulfing(y,range,body,d,li,false);
        boolean bullishSignal=bullPin || bullishEngulf || (sign>0&&b>=.62);
        boolean bearishSignal=bearPin || bearishEngulf || (sign<0&&b>=.62);

        double price=p[last];
        boolean nearSupport=price<=rg.support+rg.tol*1.35;
        boolean nearResistance=price>=rg.resistance-rg.tol*1.35;

        if(masterGuide.strong()){
            boolean bull=masterGuide.directionalScore>0;
            boolean level=bull?nearSupport:nearResistance;
            boolean trendAligned=bull?rg.trend>.20:rg.trend<-.20;
            if(level||trendAligned){
                double dir=clamp(masterGuide.directionalScore*.88,-.82,.82);
                double conf=clamp(.58+.22*masterGuide.confidence
                        +(level?.06:0)+(trendAligned?.04:0),.58,.88);
                pick.add("MASTER GUIDE: "+masterGuide.name,dir,conf,
                        level?.15:.23,true,trendAligned,level,rg.range,false,true);
            }
        }

        // -------- 8/21 MA dynamic support/resistance from the book --------
        double ema8=ema(p,8,n),ema21=ema(p,21,n);
        double maTol=Math.max(.0022,avgRange*.36);
        boolean nearMa=Math.abs(price-ema21)<=maTol || Math.abs(p[Math.max(0,last-1)]-ema21)<=maTol*1.2;

        // -------- 50% / 61% Fibonacci pullback confluence --------
        Fib fib=fib(p,Math.min(45,n));
        boolean fibBull=fib.bull && fib.retr>=.47 && fib.retr<=.64;
        boolean fibBear=fib.bear && fib.retr>=.47 && fib.retr<=.64;

        // -------- Trend + level + signal: the book's core framework --------
        if(rg.trend>.38 && bullishSignal){
            boolean level=nearSupport || (nearMa&&ema8>=ema21) || fibBull || trendlineReaction(p,avgRange,true);
            int factors=count(level,nearSupport,nearMa&&ema8>=ema21,fibBull);
            if(level){
                double conf=.70+Math.min(.16,.035*factors)+(bullishEngulf?.035:0)+(bullPin?.025:0);
                pick.add(bullishEngulf?"BIBLE TREND + LEVEL + BULLISH ENGULFING":"BIBLE TREND + LEVEL + BULLISH PIN BAR",
                        +.76,conf,.15,true,true,true,false,false,true);
            }
        }
        if(rg.trend<-.38 && bearishSignal){
            boolean level=nearResistance || (nearMa&&ema8<=ema21) || fibBear || trendlineReaction(p,avgRange,false);
            int factors=count(level,nearResistance,nearMa&&ema8<=ema21,fibBear);
            if(level){
                double conf=.70+Math.min(.16,.035*factors)+(bearishEngulf?.035:0)+(bearPin?.025:0);
                pick.add(bearishEngulf?"BIBLE TREND + LEVEL + BEARISH ENGULFING":"BIBLE TREND + LEVEL + BEARISH PIN BAR",
                        -.76,conf,.15,true,true,true,false,false,true);
            }
        }

        // Explicit multi-confluence combinations described repeatedly in the book.
        if(rg.trend>.40 && bullishSignal && nearMa && fibBull){
            pick.add("BIBLE 21 MA + 50/61 FIB + BULL SIGNAL",+.84,.88,.12,true,true,true,false,false,true);
        }
        if(rg.trend<-.40 && bearishSignal && nearMa && fibBear){
            pick.add("BIBLE 21 MA + 50/61 FIB + BEAR SIGNAL",-.84,.88,.12,true,true,true,false,false,true);
        }

        // -------- Inside-bar continuation / breakout confirmation --------
        Inside ib=insideBar(p,d,range,body,start,end);
        if(ib.dir>0 && rg.trend>.30){
            pick.add("BIBLE INSIDE BAR BREAKOUT WITH UPTREND",+.68,.73,.18,true,true,ib.keyLevel,false,false,ib.rrOk);
        }
        if(ib.dir<0 && rg.trend<-.30){
            pick.add("BIBLE INSIDE BAR BREAKOUT WITH DOWNTREND",-.68,.73,.18,true,true,ib.keyLevel,false,false,ib.rrOk);
        }

        // -------- Inside-bar false breakout / stop-hunt pattern --------
        FalseBreak fb=insideFalseBreak(p,d,range,body,start,end,rg);
        if(fb.dir>0){
            pick.add("BIBLE INSIDE BAR FALSE BREAKOUT / BEAR TRAP",+.86,.88,.14,true,
                    rg.trend>=-.25,fb.keyLevel,rg.range,true,fb.rrOk);
        } else if(fb.dir<0){
            pick.add("BIBLE INSIDE BAR FALSE BREAKOUT / BULL TRAP",-.86,.88,.14,true,
                    rg.trend<=.25,fb.keyLevel,rg.range,true,fb.rrOk);
        }

        // -------- Range-bound rules: trade boundaries, not the middle --------
        if(rg.range){
            double longRR=rangeRiskReward(price,rg.support,rg.resistance,avgRange,true);
            double shortRR=rangeRiskReward(price,rg.support,rg.resistance,avgRange,false);
            if(nearSupport && bullishSignal){
                boolean rr=longRR>=1.8;
                pick.add("BIBLE RANGE SUPPORT REJECTION",+.72,rr?.80:.68,rr?.12:.42,true,false,true,true,false,rr);
            }
            if(nearResistance && bearishSignal){
                boolean rr=shortRR>=1.8;
                pick.add("BIBLE RANGE RESISTANCE REJECTION",-.72,rr?.80:.68,rr?.12:.42,true,false,true,true,false,rr);
            }
            // Bollinger bands are used by the source only as range confirmation.
            Bands bands=bands(p,20);
            if(nearSupport && bullishSignal && price<=bands.lower+maTol*.25)
                pick.add("BIBLE RANGE SUPPORT + LOWER BAND REJECTION",+.78,.84,.14,true,false,true,true,false,longRR>=1.8);
            if(nearResistance && bearishSignal && price>=bands.upper-maTol*.25)
                pick.add("BIBLE RANGE RESISTANCE + UPPER BAND REJECTION",-.78,.84,.14,true,false,true,true,false,shortRR>=1.8);
        }

        // -------- Range breakout + pullback/retest --------
        Retest retest=rangeBreakRetest(p,d,range,body,upper,lower,start,end,rg);
        if(retest.dir>0)
            pick.add("BIBLE RANGE BREAKOUT + SUPPORT RETEST",+.80,.84,.15,true,true,true,false,false,retest.rrOk);
        if(retest.dir<0)
            pick.add("BIBLE RANGE BREAKDOWN + RESISTANCE RETEST",-.80,.84,.15,true,true,true,false,false,retest.rrOk);

        // -------- Supply / demand zone reaction proxy --------
        ZoneReaction zone=zoneReaction(p,d,range,body,start,end);
        if(zone.dir>0 && bullishSignal)
            pick.add("BIBLE DEMAND ZONE + BULLISH SIGNAL",+.74,.79,.18,true,rg.trend>=-.15,true,rg.range,false,zone.rrOk);
        if(zone.dir<0 && bearishSignal)
            pick.add("BIBLE SUPPLY ZONE + BEARISH SIGNAL",-.74,.79,.18,true,rg.trend<=.15,true,rg.range,false,zone.rrOk);

        // Trend disagreement lowers confidence unless a clear false-break reversal exists.
        if(pick.dir!=0 && Math.abs(rg.trend)>.40 && Math.signum(pick.dir)!=Math.signum(rg.trend) && !pick.falseBreak){
            uncertainty=Math.max(uncertainty,.44);
            riskPenalty=Math.max(riskPenalty,.28);
        }
        if(pick.dir!=0 && Math.abs(oldTrend)>.52 && Math.signum(pick.dir)!=Math.signum(oldTrend) && !pick.falseBreak)
            uncertainty=Math.max(uncertainty,.40);

        if(pick.risk>.30)riskPenalty=Math.max(riskPenalty,pick.risk);
        if(!pick.rr && pick.range)riskPenalty=Math.max(riskPenalty,.45);
        if(Math.abs(ema8-ema21)<.0010 && !pick.range)uncertainty=Math.max(uncertainty,.30);
        if(b<=.12 && uw>.25 && lw>.25)uncertainty=Math.max(uncertainty,.48);

        double conf=clamp(pick.conf*(1.0-.24*uncertainty)*(1.0-.16*riskPenalty),.10,.95);
        double dir=pick.dir*(1.0-.16*uncertainty)*(1.0-.10*riskPenalty);
        return new Result(pick.name,dir,conf,uncertainty,riskPenalty,pick.ok,
                pick.trend,pick.key,pick.range,pick.falseBreak,false,pick.rr);
    }

    private static Result lowData(){
        return new Result("BIBLE: LOW DATA",0,.10,.88,.55,false,false,false,false,false,false,false);
    }

    private static Regime regime(double[] p,List<Double>range,int start,int end){
        Regime r=new Regime();int n=p.length,last=n-1;
        int from=Math.max(0,n-24),to=Math.max(from+3,last); // exclude latest for boundaries
        r.trend=trendScore(p,from,to);
        double hi=max(p,from,to),lo=min(p,from,to),span=Math.max(.006,hi-lo);
        r.tol=Math.max(.0025,span*.085);
        r.support=lo;r.resistance=hi;
        for(int q=from;q<to;q++){
            if(Math.abs(p[q]-lo)<=r.tol)r.supportTouches++;
            if(Math.abs(p[q]-hi)<=r.tol)r.resistanceTouches++;
        }
        r.range=Math.abs(r.trend)<.32 && r.supportTouches>=2 && r.resistanceTouches>=2;
        double recentSpan=max(p,Math.max(0,n-10),n)-min(p,Math.max(0,n-10),n);
        double avg=meanRange(range,start,end,Math.min(10,n));
        int flips=0,parts=0;int prev=0;
        for(int q=Math.max(start,end-10);q<end;q++){
            // Direction list is unavailable here; choppiness therefore uses price alternation.
            if(q<=start)continue;
            double a=1.0; // placeholder avoids another list dependency
            int sg=p[q-start]>p[q-start-1]?1:p[q-start]<p[q-start-1]?-1:0;
            if(sg!=0){if(prev!=0&&sg!=prev)flips++;prev=sg;parts++;}
        }
        double flipRate=parts>1?(double)flips/(parts-1):0;
        r.choppy=(Math.abs(r.trend)<.22 && recentSpan<Math.max(.008,avg*3.0) && flipRate>.62 && !r.range)
                || (span<Math.max(.010,avg*2.2) && flipRate>.72);
        return r;
    }

    private static double trendScore(double[] p,int from,int toExclusive){
        int n=toExclusive-from;if(n<6)return 0;
        double slope=linearSlope(p,from,n);
        ArrayList<Integer> highs=new ArrayList<>(),lows=new ArrayList<>();
        for(int q=from+1;q<toExclusive-1;q++){
            if(p[q]>p[q-1]&&p[q]>=p[q+1])highs.add(q);
            if(p[q]<p[q-1]&&p[q]<=p[q+1])lows.add(q);
        }
        double swing=0;int parts=0;
        if(highs.size()>=2){
            int a=highs.get(highs.size()-2),b=highs.get(highs.size()-1);
            swing+=p[b]>p[a]+.002?1:p[b]<p[a]-.002?-1:0;parts++;
        }
        if(lows.size()>=2){
            int a=lows.get(lows.size()-2),b=lows.get(lows.size()-1);
            swing+=p[b]>p[a]+.002?1:p[b]<p[a]-.002?-1:0;parts++;
        }
        double sw=parts==0?0:swing/parts;
        double sl=clamp(slope*110,-1,1);
        return clamp(.58*sw+.42*sl,-1,1);
    }

    private static boolean engulfing(List<Double>y,List<Double>range,List<Double>body,List<Double>d,int i,boolean bull){
        if(i<1)return false;int p=i-1;
        int s0=sign(d.get(p)),s1=sign(d.get(i));
        if(bull && !(s0<0&&s1>0))return false;
        if(!bull && !(s0>0&&s1<0))return false;
        double b0=clip01(body.get(p)),b1=clip01(body.get(i));
        if(b1<b0*1.03)return false;
        double tol=Math.max(.0025,(range.get(p)+range.get(i))*.06);
        return bodyTopY(y,range,body,i)<=bodyTopY(y,range,body,p)+tol
                && bodyBottomY(y,range,body,i)>=bodyBottomY(y,range,body,p)-tol;
    }

    private static final class Fib { boolean bull=false,bear=false; double retr=0; }
    private static Fib fib(double[] p,int look){
        Fib f=new Fib();int n=p.length,from=Math.max(0,n-look),last=n-1;
        int low=from,high=from;
        for(int q=from+1;q<n;q++){if(p[q]<p[low])low=q;if(p[q]>p[high])high=q;}
        double move=Math.abs(p[high]-p[low]);if(move<.008)return f;
        if(low<high && high<last){f.bull=true;f.retr=(p[high]-p[last])/move;}
        if(high<low && low<last){f.bear=true;f.retr=(p[last]-p[low])/move;}
        return f;
    }

    private static boolean trendlineReaction(double[] p,double avgRange,boolean bull){
        int n=p.length;if(n<10)return false;int from=Math.max(0,n-12),count=n-1-from;
        if(count<6)return false;
        double slope=linearSlope(p,from,count),intercept=linearIntercept(p,from,count);
        double expected=intercept+slope*(n-1-from);
        double tol=Math.max(.003,avgRange*.60);
        if(Math.abs(p[n-1]-expected)>tol)return false;
        return bull?slope>0:slope<0;
    }

    private static final class Inside { double dir=0;boolean keyLevel=false,rrOk=false; }
    private static Inside insideBar(double[] p,List<Double>d,List<Double>range,List<Double>body,int start,int end){
        Inside o=new Inside();int n=p.length;if(n<4)return o;
        int m=n-3,in=n-2,last=n-1;
        double rm=Math.max(.002,range.get(start+m)),ri=Math.max(.002,range.get(start+in));
        double hm=p[m]+rm*.5,lm=p[m]-rm*.5,hi=p[in]+ri*.5,li=p[in]-ri*.5;
        double tol=Math.max(.0018,rm*.08);
        if(!(ri<=rm*.88&&hi<=hm+tol&&li>=lm-tol))return o;
        int sd=sign(d.get(end-1));double bl=clip01(body.get(end-1));
        if(sd>0&&bl>=.27&&p[last]>hm+tol*.12)o.dir=.68;
        if(sd<0&&bl>=.27&&p[last]<lm-tol*.12)o.dir=-.68;
        o.keyLevel=true;o.rrOk=true;return o;
    }

    private static final class FalseBreak { double dir=0;boolean keyLevel=false,rrOk=false; }
    private static FalseBreak insideFalseBreak(double[] p,List<Double>d,List<Double>range,List<Double>body,
                                                int start,int end,Regime rg){
        FalseBreak o=new FalseBreak();int n=p.length;if(n<7)return o;
        int mother=n-4,in=n-3,brk=n-2,last=n-1;
        double rm=Math.max(.002,range.get(start+mother)),ri=Math.max(.002,range.get(start+in)),rb=Math.max(.002,range.get(start+brk));
        double hm=p[mother]+rm*.5,lm=p[mother]-rm*.5,hi=p[in]+ri*.5,li=p[in]-ri*.5,hb=p[brk]+rb*.5,lb=p[brk]-rb*.5;
        double tol=Math.max(.0018,rm*.08);
        if(!(ri<=rm*.88&&hi<=hm+tol&&li>=lm-tol))return o;
        int sb=sign(d.get(end-2)),sl=sign(d.get(end-1));double bl=clip01(body.get(end-1));
        if(hb>hm+tol*.18&&sb>=0&&sl<0&&bl>=.28&&p[last]<hm){o.dir=-.86;}
        if(lb<lm-tol*.18&&sb<=0&&sl>0&&bl>=.28&&p[last]>lm){o.dir=.86;}
        if(o.dir!=0){
            o.keyLevel=(o.dir>0?Math.abs(p[brk]-rg.support)<=rg.tol*1.8:Math.abs(p[brk]-rg.resistance)<=rg.tol*1.8)||rg.range;
            double rr=rangeRiskReward(p[last],rg.support,rg.resistance,meanRange(range,start,end,8),o.dir>0);
            o.rrOk=!rg.range||rr>=1.8;
        }
        return o;
    }

    private static final class Bands { double upper=0,lower=0; }
    private static Bands bands(double[] p,int period){
        Bands b=new Bands();int n=p.length,from=Math.max(0,n-period),count=n-from;
        double m=0;for(int q=from;q<n;q++)m+=p[q];m/=Math.max(1,count);
        double v=0;for(int q=from;q<n;q++){double z=p[q]-m;v+=z*z;}v=Math.sqrt(v/Math.max(1,count));
        b.upper=m+2*v;b.lower=m-2*v;return b;
    }

    private static final class Retest { double dir=0;boolean rrOk=false; }
    private static Retest rangeBreakRetest(double[] p,List<Double>d,List<Double>range,List<Double>body,
                                           List<Double>upper,List<Double>lower,int start,int end,Regime rg){
        Retest o=new Retest();int n=p.length;if(n<10||!(rg.range||Math.abs(rg.trend)<.38))return o;
        int last=n-1;int from=Math.max(0,n-6);boolean upBreak=false,downBreak=false;
        for(int q=from;q<last;q++){if(p[q]>rg.resistance+rg.tol*.18)upBreak=true;if(p[q]<rg.support-rg.tol*.18)downBreak=true;}
        int sd=sign(d.get(end-1));double bl=clip01(body.get(end-1)),uw=clip01(upper.get(end-1)),lw=clip01(lower.get(end-1));
        if(upBreak&&sd>0&&bl>=.22&&lw>=.18&&p[last]>=rg.resistance-rg.tol*.30&&p[last]<=rg.resistance+rg.tol*1.4){
            o.dir=.80;o.rrOk=true;
        }
        if(downBreak&&sd<0&&bl>=.22&&uw>=.18&&p[last]<=rg.support+rg.tol*.30&&p[last]>=rg.support-rg.tol*1.4){
            o.dir=-.80;o.rrOk=true;
        }
        return o;
    }

    private static final class ZoneReaction { double dir=0;boolean rrOk=false; }
    private static ZoneReaction zoneReaction(double[] p,List<Double>d,List<Double>range,List<Double>body,int start,int end){
        ZoneReaction o=new ZoneReaction();int n=p.length;if(n<12)return o;
        int last=n-1,from=Math.max(1,n-28);double avg=meanRange(range,start,end,Math.min(12,n));
        // Look for a prior compact base followed by a fast displacement. A revisit to
        // that base with a strong opposite candle is used as a supply/demand proxy.
        for(int base=from;base<=n-6;base++){
            double baseSpan=max(p,base,Math.min(n,base+3))-min(p,base,Math.min(n,base+3));
            if(baseSpan>Math.max(.006,avg*1.6))continue;
            double center=(max(p,base,base+3)+min(p,base,base+3))/2.0;
            double after=p[Math.min(n-1,base+5)]-center;
            if(Math.abs(after)<Math.max(.008,avg*2.0))continue;
            double tol=Math.max(.003,avg*.75);
            if(Math.abs(p[last]-center)<=tol){
                int sd=sign(d.get(end-1));double bl=clip01(body.get(end-1));
                if(after>0&&sd>0&&bl>=.30){o.dir=.74;o.rrOk=true;return o;} // demand departure up, bullish revisit
                if(after<0&&sd<0&&bl>=.30){o.dir=-.74;o.rrOk=true;return o;} // supply departure down
            }
        }
        return o;
    }

    private static double rangeRiskReward(double entry,double support,double resistance,double avgRange,boolean bull){
        double risk=Math.max(.0025,avgRange*.70);
        double reward=bull?resistance-entry:entry-support;
        return reward>0?reward/risk:0;
    }

    private static int count(boolean...x){int n=0;for(boolean v:x)if(v)n++;return n;}
    private static double ema(double[] p,int period,int count){
        count=Math.min(count,p.length);if(count<=0)return 0;int from=Math.max(0,count-Math.max(period*4,period+2));
        double e=p[from],a=2.0/(period+1.0);for(int q=from+1;q<count;q++)e=a*p[q]+(1-a)*e;return e;
    }
    private static double linearSlope(double[] a,int start,int count){
        if(count<2)return 0;int end=Math.min(a.length,start+count),n=end-start;if(n<2)return 0;
        double sx=0,sy=0,sxx=0,sxy=0;for(int q=0;q<n;q++){double x=q,v=a[start+q];sx+=x;sy+=v;sxx+=x*x;sxy+=x*v;}
        double den=n*sxx-sx*sx;return Math.abs(den)<1e-12?0:(n*sxy-sx*sy)/den;
    }
    private static double linearIntercept(double[] a,int start,int count){
        int end=Math.min(a.length,start+count),n=end-start;if(n<1)return 0;
        double slope=linearSlope(a,start,count),sx=0,sy=0;for(int q=0;q<n;q++){sx+=q;sy+=a[start+q];}
        return sy/n-slope*(sx/n);
    }
    private static double meanRange(List<Double>r,int start,int end,int look){
        int from=Math.max(start,end-Math.max(1,look));double s=0;int n=0;
        for(int q=from;q<end;q++){s+=Math.max(.001,r.get(q));n++;}return n==0?.005:s/n;
    }
    private static double max(double[]a,int from,int to){double v=-1e9;for(int q=Math.max(0,from);q<Math.min(a.length,to);q++)v=Math.max(v,a[q]);return v;}
    private static double min(double[]a,int from,int to){double v=1e9;for(int q=Math.max(0,from);q<Math.min(a.length,to);q++)v=Math.min(v,a[q]);return v;}
    private static double bodyHeightY(List<Double>r,List<Double>b,int i){return Math.max(.0005,Math.max(.001,r.get(i))*clip01(b.get(i)));}
    private static double bodyTopY(List<Double>y,List<Double>r,List<Double>b,int i){return y.get(i)-bodyHeightY(r,b,i)/2.0;}
    private static double bodyBottomY(List<Double>y,List<Double>r,List<Double>b,int i){return y.get(i)+bodyHeightY(r,b,i)/2.0;}
    private static int sign(double v){return v>.06?1:v<-.06?-1:0;}
    private static double clip01(double x){return clamp(x,0,1);}
    private static double clamp(double x,double a,double b){return Math.max(a,Math.min(b,x));}
}
