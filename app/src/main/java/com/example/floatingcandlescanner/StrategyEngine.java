package com.example.floatingcandlescanner;

import java.util.List;

/**
 * Strategy-level confluence scorer. These rules intentionally sit above raw
 * candlestick names: a shape only receives a strong score when market context
 * (trend, location, close quality and/or confirmation) agrees with it.
 *
 * This engine uses normalized visual candle geometry extracted from screenshots,
 * not exchange OHLC data. Scores are therefore conservative visual proxies.
 */
public final class StrategyEngine {
    private StrategyEngine() {}

    public static final class Result {
        public final String name;
        public final double directionalScore; // +BUY, -SELL
        public final double confidence;       // 0..1
        public final double riskPenalty;      // 0..1
        public final boolean confirmed;

        Result(String n,double d,double c,double risk,boolean confirmed){
            this.name=n;
            this.directionalScore=clamp(d,-1,1);
            this.confidence=clamp(c,0,1);
            this.riskPenalty=clamp(risk,0,1);
            this.confirmed=confirmed;
        }

        public boolean strong(){
            return confirmed && confidence>=.62 && Math.abs(directionalScore)>=.50;
        }
    }

    private static final class Pick {
        String name="NO STRATEGY CONFLUENCE";
        double dir=0;
        double conf=0;
        boolean confirmed=false;
        void add(String n,double d,double c,boolean ok){
            double merit=Math.abs(d)*c*(ok?1.0:.72);
            double best=Math.abs(dir)*conf*(confirmed?1.0:.72);
            if(merit>best){name=n;dir=d;conf=c;confirmed=ok;}
        }
    }

    public static Result analyze(
            List<Double>d,List<Double>y,List<Double>range,List<Double>body,
            List<Double>upper,List<Double>lower,int s,int k,double trend){
        if(d==null||y==null||range==null||body==null||upper==null||lower==null||k<3)
            return new Result("NO STRATEGY CONFLUENCE",0,0,.30,false);

        int i=s+k-1;
        Pick p=new Pick();
        double loc=location(y,s,k); // 0 resistance/high, 1 support/low
        boolean nearResistance=loc<=.30;
        boolean nearSupport=loc>=.70;
        boolean upTrend=trend>.20;
        boolean downTrend=trend<-.20;

        int sign=sign(d.get(i));
        double br=clip01(body.get(i));
        double uw=clip01(upper.get(i));
        double lw=clip01(lower.get(i));

        // Candle-close pressure: long body + little wick in the direction of close.
        // This follows the general chart-reading principle that a strong close near
        // the extreme is more meaningful than colour alone.
        if(sign>0 && br>=.58 && uw<=.18)
            p.add("BULLISH CLOSE CONTROL",+.58,.64,upTrend||nearSupport);
        if(sign<0 && br>=.58 && lw<=.18)
            p.add("BEARISH CLOSE CONTROL",-.58,.64,downTrend||nearResistance);

        // Pin/Pinocchio rule: wick about >=2x body, but require the correct prior
        // trend/location. A pin in the middle of a range is deliberately weak.
        boolean longLower=lw>=.42 && lw>=Math.max(.18,br*1.90);
        boolean longUpper=uw>=.42 && uw>=Math.max(.18,br*1.90);
        if(longLower && br<=.38){
            if(downTrend && nearSupport) p.add("BULLISH PIN BAR",+.90,.90,true);
            else if(upTrend && nearResistance) p.add("HANGING-MAN REVERSAL",-.78,.78,true);
            else p.add("PIN BAR • NEEDS CONTEXT",sign>=0?+.25:-.25,.36,false);
        }
        if(longUpper && br<=.38){
            if(upTrend && nearResistance) p.add("BEARISH PIN BAR",-.91,.91,true);
            else if(downTrend && nearSupport) p.add("INVERTED-HAMMER REVERSAL",+.76,.78,true);
            else p.add("PIN BAR • NEEDS CONTEXT",sign<=0?-.25:+.25,.36,false);
        }

        if(k>=2){
            int a=i-1;
            int s1=sign(d.get(a)), s2=sign;
            double b1=clip01(body.get(a)), b2=br;
            double y1=y.get(a), y2=y.get(i);

            // Double-red strategy from the uploaded guide, plus the symmetric
            // double-green version. The second candle must continue the move and
            // the setup is stronger at resistance/support respectively.
            if(s1<0 && s2<0 && b1>=.28 && b2>=.28 && y2>y1+.0015){
                double sc=nearResistance?.88:upTrend?.72:.54;
                p.add("DOUBLE RED",-.82,sc,nearResistance||upTrend);
            }
            if(s1>0 && s2>0 && b1>=.28 && b2>=.28 && y2<y1-.0015){
                double sc=nearSupport?.88:downTrend?.72:.54;
                p.add("DOUBLE GREEN",+.82,sc,nearSupport||downTrend);
            }

            // Expansion after a small opposite candle = simple two-candle
            // continuation confirmation rather than blindly following streaks.
            if(s1!=0 && s2==-s1 && b2>=b1*1.35 && b2>=.48){
                boolean context=(s2>0&&nearSupport)||(s2<0&&nearResistance)
                        ||(s2>0&&upTrend)||(s2<0&&downTrend);
                p.add(s2>0?"CONFIRMED BULL IMPULSE":"CONFIRMED BEAR IMPULSE",
                        s2*.68,context?.73:.50,context);
            }
        }

        // 1-2-3 reversal proxy. We require a peak/trough, a counter-move, a failed
        // retest, and then a break of point 2. This avoids signalling before the
        // pattern has confirmation.
        if(k>=6){
            int a=i-5,b=i-4,c=i-3,e=i-2,f=i-1,g=i;
            double ya=y.get(a), yb=y.get(b), yc=y.get(c), ye=y.get(e), yf=y.get(f), yg=y.get(g);
            double eps=.0030;

            // Bearish: high (low screen-Y) -> lower move -> failed retest -> break down.
            boolean bear123=(yb<ya-eps || yc<ya-eps) &&
                    ye>Math.min(yb,yc)+eps &&
                    yf>Math.min(yb,yc)-eps*.35 &&
                    yf<ye-eps*.25 &&
                    yg>ye+eps*.25 && sign(d.get(g))<0;
            if(bear123) p.add("1-2-3 BEARISH REVERSAL",-.90,.86,true);

            // Bullish symmetric structure.
            boolean bull123=(yb>ya+eps || yc>ya+eps) &&
                    ye<Math.max(yb,yc)-eps &&
                    yf<Math.max(yb,yc)+eps*.35 &&
                    yf>ye+eps*.25 &&
                    yg<ye-eps*.25 && sign(d.get(g))>0;
            if(bull123) p.add("1-2-3 BULLISH REVERSAL",+.90,.86,true);
        }

        // Pullback continuation: established trend, 1-2 small opposing candles,
        // then a strong close back with the trend.
        if(k>=4){
            int q1=i-3,q2=i-2,q3=i-1;
            int a=sign(d.get(q1)), b=sign(d.get(q2)), c=sign(d.get(q3));
            double prevBodies=(clip01(body.get(q1))+clip01(body.get(q2))+clip01(body.get(q3)))/3.0;
            if(upTrend && sign>0 && br>=.48 && prevBodies<=.46 && (b<0||c<0))
                p.add("BULLISH PULLBACK CONTINUATION",+.72,.72,true);
            if(downTrend && sign<0 && br>=.48 && prevBodies<=.46 && (b>0||c>0))
                p.add("BEARISH PULLBACK CONTINUATION",-.72,.72,true);
        }

        // Volatility regime penalty. Very compressed candles lack information;
        // sudden extreme expansion can make a next-candle call unstable.
        double risk=volatilityRisk(range,s,k);
        double conf=p.conf*(1.0-.45*risk);
        double dir=p.dir*(1.0-.30*risk);
        return new Result(p.name,dir,conf,risk,p.confirmed);
    }

    private static double volatilityRisk(List<Double>r,int s,int k){
        if(k<5)return .25;
        int recent=Math.min(3,k);
        double recentMean=mean(r,s+k-recent,recent);
        double base=mean(r,s,k-recent>0?k-recent:k);
        if(base<=.0001)base=mean(r,s,k);
        double ratio=recentMean/Math.max(.0005,base);
        double low=ratio<.50?(.50-ratio)*1.6:0;
        double high=ratio>2.0?Math.min(1,(ratio-2.0)*.45):0;
        return clamp(Math.max(low,high),0,1);
    }

    private static double location(List<Double>y,int s,int k){
        double min=1,max=0;
        for(int q=s;q<s+k;q++){double v=y.get(q);min=Math.min(min,v);max=Math.max(max,v);}
        return clamp((y.get(s+k-1)-min)/Math.max(.004,max-min),0,1);
    }
    private static double mean(List<Double>a,int s,int k){
        if(k<=0)return 0; double z=0; for(int i=0;i<k;i++)z+=a.get(s+i); return z/k;
    }
    private static int sign(double v){return v>.12?1:v<-.12?-1:0;}
    private static double clip01(double v){return clamp(v,0,1);}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
}
