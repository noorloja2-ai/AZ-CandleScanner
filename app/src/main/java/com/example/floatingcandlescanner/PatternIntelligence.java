package com.example.floatingcandlescanner;

import java.util.List;

/**
 * Context-aware candlestick pattern scorer built from visual candle geometry.
 *
 * The screen scanner does not have broker OHLC values, so this class works with
 * normalized body/wick/range geometry extracted from candle pixels. Pattern
 * names are therefore "visual pattern" classifications rather than exchange
 * OHLC confirmations.
 */
public final class PatternIntelligence {
    private PatternIntelligence() {}

    public static final class Result {
        public final String name;
        public final double directionalScore;   // + BUY, - SELL
        public final double reversalScore;      // + bullish reversal, - bearish reversal
        public final double continuationScore;  // + bullish continuation, - bearish continuation
        public final double indecision;         // 0..1
        public final double strength;           // 0..1

        Result(String name,double directionalScore,double reversalScore,
               double continuationScore,double indecision,double strength){
            this.name=name;
            this.directionalScore=clamp(directionalScore,-1,1);
            this.reversalScore=clamp(reversalScore,-1,1);
            this.continuationScore=clamp(continuationScore,-1,1);
            this.indecision=clamp(indecision,0,1);
            this.strength=clamp(strength,0,1);
        }

        public boolean strong(){ return strength>=.58 && Math.abs(directionalScore)>=.45; }
    }

    private static final class Book {
        String name="NO CLEAR PATTERN";
        double directional=0;
        double reversal=0;
        double continuation=0;
        double indecision=0;
        double strength=0;

        void reversal(String n,double score,double s){
            if(Math.abs(score)*s > Math.abs(directional)*strength){
                name=n; directional=score; strength=s;
            }
            if(Math.abs(score)*s > Math.abs(reversal)*Math.max(.01,strength)) reversal=score;
        }
        void continuation(String n,double score,double s){
            if(Math.abs(score)*s > Math.abs(directional)*strength){
                name=n; directional=score; strength=s;
            }
            if(Math.abs(score)*s > Math.abs(continuation)*Math.max(.01,strength)) continuation=score;
        }
        void neutral(String n,double indec,double s){
            if(indec>indecision) indecision=indec;
            if(s>strength && Math.abs(directional)<.30){ name=n; strength=s; }
        }
    }

    public static Result analyze(
            List<Double>d,List<Double>y,List<Double>range,
            List<Double>body,List<Double>upper,List<Double>lower,
            int s,int k,double trend){
        if(k<=0 || d==null || y==null || range==null || body==null || upper==null || lower==null)
            return new Result("NO CLEAR PATTERN",0,0,0,0,0);

        int i=s+k-1;
        if(i<0 || i>=d.size() || i>=body.size())
            return new Result("NO CLEAR PATTERN",0,0,0,0,0);

        Book b=new Book();
        double dir=d.get(i);
        double br=clip01(body.get(i));
        double uw=clip01(upper.get(i));
        double lw=clip01(lower.get(i));
        int sign=sign(dir);
        boolean upTrend=trend>.20;
        boolean downTrend=trend<-.20;

        // Local price-location context. In normalized screen coordinates, smaller Y
        // is a higher price. Reversal candles become more meaningful near a recent
        // visual resistance/support extreme rather than in the middle of a range.
        int look=Math.min(8,k);
        double localMin=1.0, localMax=0.0;
        for(int q=i-look+1;q<=i;q++){
            if(q<0||q>=y.size())continue;
            localMin=Math.min(localMin,y.get(q));
            localMax=Math.max(localMax,y.get(q));
        }
        double localSpan=Math.max(.004,localMax-localMin);
        double location=clamp((y.get(i)-localMin)/localSpan,0,1);
        boolean nearResistance=location<=.30;
        boolean nearSupport=location>=.70;

        // Single-candle geometry. Professional candlestick descriptions usually
        // expect a hammer/shooting-star shadow to be roughly >=2x the real body.
        boolean smallBody=br<=.36;
        boolean tinyBody=br<=.15 || Math.abs(dir)<.14;
        boolean longLower=lw>=.42 && lw>=br*1.90;
        boolean longUpper=uw>=.42 && uw>=br*1.90;
        boolean shortUpper=uw<=Math.max(.14,br*.48);
        boolean shortLower=lw<=Math.max(.14,br*.48);

        if(br>=.78 && uw<=.12 && lw<=.12 && sign!=0){
            double s0=(sign>0&&upTrend)||(sign<0&&downTrend)?.88:.78;
            b.continuation(sign>0?"BULLISH MARUBOZU":"BEARISH MARUBOZU",sign*s0,.87);
        }

        if(longLower && shortUpper && smallBody){
            if(downTrend && nearSupport) b.reversal("HAMMER",+.90,.92);
            else if(downTrend) b.reversal("HAMMER",+.72,.78);
            else if(upTrend && nearResistance) b.reversal("HANGING MAN",-.84,.87);
            else if(upTrend) b.reversal("HANGING MAN",-.62,.70);
            else if(sign>0) b.reversal("HAMMER-LIKE",+.30,.48);
        }
        if(longUpper && shortLower && smallBody){
            if(upTrend && nearResistance) b.reversal("SHOOTING STAR",-.91,.93);
            else if(upTrend) b.reversal("SHOOTING STAR",-.74,.80);
            else if(downTrend && nearSupport) b.reversal("INVERTED HAMMER",+.78,.82);
            else if(downTrend) b.reversal("INVERTED HAMMER",+.61,.69);
            else if(sign<0) b.reversal("SHOOTING-STAR-LIKE",-.30,.48);
        }

        if(tinyBody){
            if(lw>=.58 && uw<=.20){
                double score=(downTrend&&nearSupport)?+.58:downTrend?+.36:0;
                if(score!=0)b.reversal("DRAGONFLY DOJI",score,.58);
                b.neutral("DRAGONFLY DOJI",.72,.68);
            }else if(uw>=.58 && lw<=.20){
                double score=(upTrend&&nearResistance)?-.58:upTrend?-.36:0;
                if(score!=0)b.reversal("GRAVESTONE DOJI",score,.58);
                b.neutral("GRAVESTONE DOJI",.74,.68);
            }else if(uw>=.28 && lw>=.28){
                b.neutral("LONG-LEGGED DOJI",.92,.82);
            }else{
                b.neutral("DOJI",.82,.72);
            }
        }else if(br<=.38 && uw>=.20 && lw>=.20){
            b.neutral("SPINNING TOP",.66,.62);
        }

        if(k>=2){
            int p=i-1;
            int ps=sign(d.get(p));
            double pBody=bodyHeight(range,body,p);
            double cBody=bodyHeight(range,body,i);
            double pTop=bodyTop(y,range,body,p), pBottom=bodyBottom(y,range,body,p);
            double cTop=bodyTop(y,range,body,i), cBottom=bodyBottom(y,range,body,i);
            double tol=Math.max(.0035,Math.min(range.get(p),range.get(i))*.16);

            if(sign!=0 && ps!=0 && sign==-ps){
                boolean engulfs=cBody>=pBody*1.10 && cTop<=pTop+tol && cBottom>=pBottom-tol;
                if(engulfs){
                    double score=sign*.72;
                    boolean trendContext=(sign>0&&downTrend)||(sign<0&&upTrend);
                    boolean levelContext=(sign>0&&nearSupport)||(sign<0&&nearResistance);
                    if(trendContext)score=sign*.86;
                    if(trendContext&&levelContext)score=sign*.96;
                    b.reversal(sign>0?"BULLISH ENGULFING":"BEARISH ENGULFING",score,
                            trendContext&&levelContext?.94:trendContext?.87:.70);
                }

                boolean harami=cBody<=pBody*.68 && cTop>=pTop-tol && cBottom<=pBottom+tol;
                if(harami){
                    if(sign>0&&downTrend)b.reversal("BULLISH HARAMI",nearSupport?+.68:+.52,nearSupport?.73:.62);
                    else if(sign<0&&upTrend)b.reversal("BEARISH HARAMI",nearResistance?-.68:-.52,nearResistance?.73:.62);
                }

                boolean kicker=body.get(p)>=.58 && br>=.58 && Math.abs(d.get(p))>.38 && Math.abs(dir)>.38;
                if(kicker){
                    b.reversal(sign>0?"BULLISH KICKER-LIKE":"BEARISH KICKER-LIKE",sign*.88,.86);
                }
            }

            double pTopRange=top(y,range,p), cTopRange=top(y,range,i);
            double pBottomRange=bottom(y,range,p), cBottomRange=bottom(y,range,i);
            if(Math.abs(pTopRange-cTopRange)<=tol && upTrend && (ps<0||sign<0))
                b.reversal("TWEEZER TOP",nearResistance?-.72:-.52,nearResistance?.76:.62);
            if(Math.abs(pBottomRange-cBottomRange)<=tol && downTrend && (ps>0||sign>0))
                b.reversal("TWEEZER BOTTOM",nearSupport?+.72:+.52,nearSupport?.76:.62);
        }

        if(k>=3){
            int a=i-2,m=i-1,c=i;
            int sa=sign(d.get(a)), sm=sign(d.get(m)), sc=sign(d.get(c));
            double ba=clip01(body.get(a)), bm=clip01(body.get(m)), bc=clip01(body.get(c));

            // Morning/evening star without relying on exchange-session gaps.
            if(sa<0 && ba>=.52 && bm<=.28 && sc>0 && bc>=.48 && downTrend)
                b.reversal("MORNING STAR",nearSupport?+.92:+.80,nearSupport?.93:.84);
            if(sa>0 && ba>=.52 && bm<=.28 && sc<0 && bc>=.48 && upTrend)
                b.reversal("EVENING STAR",nearResistance?-.92:-.80,nearResistance?.93:.84);

            // Three strong same-direction bodies are useful only with structure/context;
            // do not automatically treat any three colours as a high-confidence signal.
            if(sa>0&&sm>0&&sc>0&&ba>.40&&bm>.36&&bc>.40){
                if(upTrend)b.continuation("THREE BULLISH CANDLES",+.74,.76);
                else if(downTrend&&nearSupport)b.reversal("THREE BULLISH REVERSAL CANDLES",+.70,.72);
            }
            if(sa<0&&sm<0&&sc<0&&ba>.40&&bm>.36&&bc>.40){
                if(downTrend)b.continuation("THREE BEARISH CANDLES",-.74,.76);
                else if(upTrend&&nearResistance)b.reversal("THREE BEARISH REVERSAL CANDLES",-.70,.72);
            }

            // Shrinking bodies after a directional run are an exhaustion warning.
            if((upTrend||downTrend) && bc<bm*.78 && bm<ba*.88){
                b.indecision=Math.max(b.indecision,.48);
                b.continuation*=.76;
            }
        }

        if(k>=5){
            int a=i-4,b1=i-3,b2=i-2,b3=i-1,c=i;
            int sa=sign(d.get(a)), sc=sign(d.get(c));
            double ba=clip01(body.get(a)), bc=clip01(body.get(c));
            boolean middleSmall=body.get(b1)<.48&&body.get(b2)<.48&&body.get(b3)<.48;
            int opp=0;
            int[] mid={b1,b2,b3};
            for(int q:mid){ int sq=sign(d.get(q)); if(sq!=0 && sq==-sa)opp++; }
            double firstTop=top(y,range,a), firstBottom=bottom(y,range,a);
            boolean inside=true;
            for(int q:mid){
                if(top(y,range,q)<firstTop-.006 || bottom(y,range,q)>firstBottom+.006){inside=false;break;}
            }
            if(sa>0&&sc>0&&upTrend&&ba>.52&&bc>.48&&middleSmall&&opp>=2&&inside)
                b.continuation("RISING THREE METHODS",+.82,.84);
            if(sa<0&&sc<0&&downTrend&&ba>.52&&bc>.48&&middleSmall&&opp>=2&&inside)
                b.continuation("FALLING THREE METHODS",-.82,.84);
        }

        // If the strongest object is a doji/spinning-top, prevent a weak colour
        // imbalance from masquerading as a high-confidence directional pattern.
        double directional=b.directional;
        if(b.indecision>=.78 && Math.abs(directional)<.65) directional*=.45;

        // Reversal shapes away from an appropriate extreme are intentionally
        // discounted. This encodes the professional guidance that context and
        // support/resistance matter more than a shape in isolation.
        double reversal=b.reversal;
        if(reversal>0 && !nearSupport)reversal*=.82;
        if(reversal<0 && !nearResistance)reversal*=.82;
        if(Math.signum(directional)==Math.signum(b.reversal) && Math.abs(b.reversal)>.45){
            if((directional>0&&nearSupport)||(directional<0&&nearResistance))
                directional=clamp(directional*1.08,-1,1);
            else directional*=.88;
        }

        return new Result(b.name,directional,reversal,b.continuation,b.indecision,b.strength);
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
    private static int sign(double v){return v>.12?1:v<-.12?-1:0;}
    private static double clip01(double v){return clamp(v,0,1);}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
}
