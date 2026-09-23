package com.example.floatingcandlescanner;

import java.util.List;

/**
 * Context-aware candlestick knowledge layer.
 *
 * IMPORTANT: inputs are visual candle geometry reconstructed from broker-screen
 * pixels, not exchange OHLC. The engine therefore treats named patterns as
 * visual approximations and always combines the shape with prior trend and
 * recent support/resistance location before giving it directional weight.
 */
public final class CandlestickKnowledgeEngine {
    private CandlestickKnowledgeEngine() {}

    public static final int CATALOG_SIZE = 38;

    public static final class Result {
        public final String name;
        public final String family;
        public final double directionalScore; // + BUY, - SELL
        public final double confidence;       // 0..1 quality of the pattern match
        public final double uncertainty;      // 0..1 ambiguity / confirmation need
        public final int tier;                // 1 weak, 2 useful, 3 strong
        public final boolean confirmed;
        public final String context;

        Result(String n,String f,double d,double c,double u,int t,boolean ok,String x){
            name=n; family=f;
            directionalScore=clamp(d,-1,1);
            confidence=clamp(c,0,1);
            uncertainty=clamp(u,0,1);
            tier=Math.max(0,Math.min(3,t));
            confirmed=ok; context=x==null?"":x;
        }

        public boolean strong(){
            return tier>=2 && confirmed && confidence>=.66 &&
                    Math.abs(directionalScore)>=.50 && uncertainty<=.48;
        }

        public boolean directional(){ return Math.abs(directionalScore)>=.18; }
    }

    private static final class Pick {
        String name="NO CLEAR CANDLE PATTERN";
        String family="NONE";
        double dir=0, conf=.12, unc=.78, merit=0;
        int tier=0;
        boolean confirmed=false;
        String context="mixed";

        void add(String n,String f,double d,double c,double u,int t,boolean ok,String ctx){
            double directionalMerit=Math.abs(d)*c*(1.0+.12*t)*(ok?1.0:.74);
            // Indecision patterns are allowed to win the label even with no direction
            // when there is no meaningful directional candidate.
            if(Math.abs(d)<.05){
                if(merit<.08 && c>.28){
                    name=n; family=f; dir=0; conf=c; unc=u; tier=t; confirmed=ok;
                    context=ctx; merit=.08*c;
                }
                return;
            }
            if(directionalMerit>merit){
                name=n; family=f; dir=d; conf=c; unc=u; tier=t; confirmed=ok;
                context=ctx; merit=directionalMerit;
            }
        }
    }

    public static Result analyze(List<Double>d,List<Double>y,List<Double>range,
                                 List<Double>body,List<Double>upper,List<Double>lower,
                                 int s,int k,double trend){
        if(d==null||y==null||range==null||body==null||upper==null||lower==null||
                k<1||s<0||s+k>d.size())
            return new Result("NO CLEAR CANDLE PATTERN","NONE",0,.10,.85,0,false,"low data");

        int i=s+k-1;
        Pick p=new Pick();
        int si=sign(d.get(i));
        double bi=clip01(body.get(i)), ui=clip01(upper.get(i)), li=clip01(lower.get(i));
        double loc=location(y,s,k); // 0 = visual high/resistance, 1 = visual low/support
        boolean resistance=loc<=.28, support=loc>=.72;
        boolean up=trend>.18, down=trend<-.18;
        String ctx=context(up,down,support,resistance);

        // ---------- single-candle family ----------
        boolean doji=bi<=.14 || Math.abs(d.get(i))<.10;
        boolean tinyRange=range.get(i)<=Math.max(.0025,mean(range,Math.max(s,i-4),Math.min(4,k))*.32);
        boolean longUpper=ui>=.42 && ui>=Math.max(.30,bi*1.90);
        boolean longLower=li>=.42 && li>=Math.max(.30,bi*1.90);
        boolean shortUpper=ui<=Math.max(.13,bi*.50);
        boolean shortLower=li<=Math.max(.13,bi*.50);
        boolean smallBody=bi<=.38;

        if(doji){
            if(tinyRange && ui<=.10 && li<=.10)
                p.add("FOUR-PRICE DOJI","DOJI",0,.42,.96,1,false,ctx);
            else if(li>=.55 && ui<=.20){
                double dir=(down&&support)?+.50:down?+.28:0;
                p.add("DRAGONFLY DOJI","DOJI",dir,.58,.70,1,down&&support,ctx);
            }else if(ui>=.55 && li<=.20){
                double dir=(up&&resistance)?-.50:up?-.28:0;
                p.add("GRAVESTONE DOJI","DOJI",dir,.58,.70,1,up&&resistance,ctx);
            }else if(ui>=.27 && li>=.27){
                p.add("LONG-LEGGED DOJI","DOJI",0,.60,.94,1,false,ctx);
            }else{
                p.add("DOJI","DOJI",0,.52,.88,1,false,ctx);
            }
        }else if(bi<=.34 && ui>=.20 && li>=.20){
            p.add("SPINNING TOP","INDECISION",0,.48,.78,1,false,ctx);
        }else if(bi<=.30 && ui>=.36 && li>=.36){
            p.add("HIGH-WAVE CANDLE","INDECISION",0,.48,.84,1,false,ctx);
        }

        // Marubozu / long control candles.
        if(si!=0 && bi>=.78 && ui<=.12 && li<=.12){
            boolean ok=(si>0&&up)||(si<0&&down);
            p.add(si>0?"BULLISH MARUBOZU":"BEARISH MARUBOZU","MOMENTUM",
                    si*.84,ok?.88:.72,.16,3,ok,ctx);
        }else if(si!=0 && bi>=.62 && ui<=.20 && li<=.20){
            boolean ok=(si>0&&up)||(si<0&&down);
            p.add(si>0?"LONG BULLISH CANDLE":"LONG BEARISH CANDLE","MOMENTUM",
                    si*.64,ok?.72:.60,.28,2,ok,ctx);
        }

        // Hammer family. Same geometry gets different meaning from prior trend.
        if(smallBody&&longLower&&shortUpper){
            if(down&&support) p.add("HAMMER","REVERSAL",+.82,.88,.20,3,true,ctx);
            else if(down) p.add("HAMMER","REVERSAL",+.62,.72,.36,2,true,ctx);
            else if(up&&resistance) p.add("HANGING MAN","REVERSAL",-.76,.82,.26,3,true,ctx);
            else if(up) p.add("HANGING MAN","REVERSAL",-.50,.62,.46,2,false,ctx);
            else p.add("HAMMER / HANGING-MAN SHAPE","REVERSAL",si>=0?.18:-.18,.38,.64,1,false,ctx);
        }
        if(smallBody&&longUpper&&shortLower){
            if(up&&resistance) p.add("SHOOTING STAR","REVERSAL",-.86,.90,.18,3,true,ctx);
            else if(up) p.add("SHOOTING STAR","REVERSAL",-.64,.74,.34,2,true,ctx);
            else if(down&&support) p.add("INVERTED HAMMER","REVERSAL",+.72,.80,.28,2,true,ctx);
            else if(down) p.add("INVERTED HAMMER","REVERSAL",+.50,.62,.46,2,false,ctx);
            else p.add("INVERTED-HAMMER / SHOOTING-STAR SHAPE","REVERSAL",si>=0?.16:-.16,.36,.68,1,false,ctx);
        }

        // Belt Hold: a large directional body that begins at one extreme with
        // almost no opening-side wick. It is strongest as a reversal at a level.
        if(si>0 && bi>=.58 && li<=.10){
            boolean ok=down&&support;
            p.add("BULLISH BELT HOLD","REVERSAL",+.58,ok?.72:.54,.40,2,ok,ctx);
        }
        if(si<0 && bi>=.58 && ui<=.10){
            boolean ok=up&&resistance;
            p.add("BEARISH BELT HOLD","REVERSAL",-.58,ok?.72:.54,.40,2,ok,ctx);
        }

        // ---------- two-candle family ----------
        if(k>=2){
            int a=i-1;
            int sa=sign(d.get(a));
            double ba=clip01(body.get(a));
            double aTop=bodyTop(y,range,body,a), aBot=bodyBottom(y,range,body,a);
            double cTop=bodyTop(y,range,body,i), cBot=bodyBottom(y,range,body,i);
            double ao=openY(y,range,body,d,a), ac=closeY(y,range,body,d,a);
            double co=openY(y,range,body,d,i), cc=closeY(y,range,body,d,i);
            double avgR=Math.max(.003,(range.get(a)+range.get(i))/2.0);
            double tol=Math.max(.0025,avgR*.14);

            boolean engulf=sa!=0&&si==-sa&&bi>=ba*1.06&&cTop<=aTop+tol&&cBot>=aBot-tol;
            if(engulf){
                boolean ok=(si>0&&(down||support))||(si<0&&(up||resistance));
                p.add(si>0?"BULLISH ENGULFING":"BEARISH ENGULFING","REVERSAL",
                        si*.90,ok?.93:.74,.14,3,ok,ctx);
            }

            boolean inside=cTop>=aTop-tol&&cBot<=aBot+tol&&bi<=ba*.72;
            if(inside && sa!=0 && (si==-sa || doji)){
                boolean ok=(sa<0&&(down||support))||(sa>0&&(up||resistance));
                if(doji)
                    p.add(sa<0?"BULLISH HARAMI CROSS":"BEARISH HARAMI CROSS","REVERSAL",
                            sa<0?+.56:-.56,ok?.68:.52,.44,2,ok,ctx);
                else
                    p.add(si>0?"BULLISH HARAMI":"BEARISH HARAMI","REVERSAL",
                            si*.52,ok?.64:.48,.46,2,ok,ctx);
            }

            // Piercing / Dark Cloud Cover use body midpoint and inferred open/close.
            double amid=(aTop+aBot)/2.0;
            if(sa<0&&si>0&&ba>=.42&&bi>=.38&&cc<amid&&co>=ac-tol){
                boolean ok=down||support;
                p.add("PIERCING LINE","REVERSAL",+.66,ok?.76:.56,.34,2,ok,ctx);
            }
            if(sa>0&&si<0&&ba>=.42&&bi>=.38&&cc>amid&&co<=ac+tol){
                boolean ok=up||resistance;
                p.add("DARK CLOUD COVER","REVERSAL",-.66,ok?.76:.56,.34,2,ok,ctx);
            }

            double topDiff=Math.abs(top(y,range,a)-top(y,range,i));
            double botDiff=Math.abs(bottom(y,range,a)-bottom(y,range,i));
            if(topDiff<=avgR*.18 && resistance && (sa<0||si<0))
                p.add("TWEEZER TOP","REVERSAL",-.60,.70,.34,2,true,ctx);
            if(botDiff<=avgR*.18 && support && (sa>0||si>0))
                p.add("TWEEZER BOTTOM","REVERSAL",+.60,.70,.34,2,true,ctx);

            // Kicker-like: gap data is not reliable from screenshots, so require
            // two large opposite control bodies and mark it as visual approximation.
            if(sa!=0&&si==-sa&&ba>=.62&&bi>=.62&&
                    clip01(upper.get(a))<=.18&&clip01(lower.get(a))<=.18&&
                    ui<=.18&&li<=.18){
                boolean ok=(si>0&&(down||support))||(si<0&&(up||resistance));
                p.add(si>0?"BULLISH KICKER-LIKE":"BEARISH KICKER-LIKE","REVERSAL",
                        si*.72,ok?.78:.62,.30,2,ok,ctx);
            }

            // Counterattack: opposite candles whose inferred closes meet near the
            // same level. Context determines whether the meeting is a reversal.
            if(sa!=0&&si==-sa&&ba>=.40&&bi>=.40&&Math.abs(ac-cc)<=avgR*.18){
                boolean ok=(si>0&&(down||support))||(si<0&&(up||resistance));
                p.add(si>0?"BULLISH COUNTERATTACK":"BEARISH COUNTERATTACK","REVERSAL",
                        si*.54,ok?.66:.50,.42,2,ok,ctx);
            }

            // Matching Low: two bearish closes at almost the same low after a fall.
            if(sa<0&&si<0&&down&&Math.abs(ac-cc)<=avgR*.14){
                p.add("MATCHING LOW","REVERSAL",support?+.54:+.38,support?.64:.52,.48,1,support,ctx);
            }

            // On-Neck / In-Neck / Thrusting: weak bullish retracement after a
            // strong bearish candle in a downtrend, generally continuation not reversal.
            if(down&&sa<0&&si>0&&ba>=.52&&bi<=ba*.78){
                double prevLow=ac; // bearish close approximates body low
                double retrace=Math.abs(cc-prevLow)/avgR;
                if(retrace<=.18)
                    p.add("ON-NECK BEARISH CONTINUATION","CONTINUATION",-.48,.58,.48,1,true,ctx);
                else if(retrace<=.34)
                    p.add("IN-NECK BEARISH CONTINUATION","CONTINUATION",-.44,.54,.52,1,true,ctx);
                else if(cc>amid && cc<aTop+avgR*.10)
                    p.add("THRUSTING BEARISH CONTINUATION","CONTINUATION",-.42,.52,.54,1,true,ctx);
            }
        }

        // ---------- three-candle family ----------
        if(k>=3){
            int a=i-2,b=i-1,c=i;
            int sa=sign(d.get(a)), sb=sign(d.get(b)), sc=sign(d.get(c));
            double ba=clip01(body.get(a)), bb=clip01(body.get(b)), bc=clip01(body.get(c));
            boolean bDoji=bb<=.14||Math.abs(d.get(b))<.10;
            double aTop=bodyTop(y,range,body,a), aBot=bodyBottom(y,range,body,a);
            double bTop=bodyTop(y,range,body,b), bBot=bodyBottom(y,range,body,b);
            double cTop=bodyTop(y,range,body,c), cBot=bodyBottom(y,range,body,c);
            double tol=Math.max(.0025,Math.min(range.get(a),range.get(b))*.15);

            if(sa<0&&ba>=.50&&bb<=.30&&sc>0&&bc>=.46){
                boolean ok=down||support;
                p.add(bDoji?"MORNING DOJI STAR":"MORNING STAR","REVERSAL",+.78,ok?.86:.64,.22,3,ok,ctx);
            }
            if(sa>0&&ba>=.50&&bb<=.30&&sc<0&&bc>=.46){
                boolean ok=up||resistance;
                p.add(bDoji?"EVENING DOJI STAR":"EVENING STAR","REVERSAL",-.78,ok?.86:.64,.22,3,ok,ctx);
            }


            // Abandoned-baby-like visual approximation. True session gaps are
            // uncommon in continuous FX/OTC and screenshot geometry is not exact,
            // so this pattern is deliberately lower-weight than Morning/Evening Star.
            // Bullish: strong bearish candle, isolated doji-like candle below it,
            // then a bullish candle isolated above the doji.
            boolean bullBabyGap = sa<0 && ba>=.50 && bDoji && sc>0 && bc>=.42
                    && top(y,range,b) > bottom(y,range,a)+tol*.35
                    && bottom(y,range,c) < top(y,range,b)-tol*.35;
            if(bullBabyGap){
                boolean ok=down||support;
                p.add("BULLISH ABANDONED BABY-LIKE","REVERSAL",+.70,ok?.76:.56,.38,2,ok,ctx);
            }
            boolean bearBabyGap = sa>0 && ba>=.50 && bDoji && sc<0 && bc>=.42
                    && bottom(y,range,b) < top(y,range,a)-tol*.35
                    && top(y,range,c) > bottom(y,range,b)+tol*.35;
            if(bearBabyGap){
                boolean ok=up||resistance;
                p.add("BEARISH ABANDONED BABY-LIKE","REVERSAL",-.70,ok?.76:.56,.38,2,ok,ctx);
            }

            boolean risingCenters=y.get(a)>y.get(b)&&y.get(b)>y.get(c);
            boolean fallingCenters=y.get(a)<y.get(b)&&y.get(b)<y.get(c);
            boolean healthyBullBodies=ba>=.40&&bb>=.36&&bc>=.36&&bb>=ba*.55&&bc>=bb*.55;
            boolean healthyBearBodies=ba>=.40&&bb>=.36&&bc>=.36&&bb>=ba*.55&&bc>=bb*.55;
            boolean shrinkingBull=ba>bb*1.18&&bb>bc*1.12;
            boolean shrinkingBear=ba>bb*1.18&&bb>bc*1.12;
            if(sa>0&&sb>0&&sc>0&&healthyBullBodies&&risingCenters&&!shrinkingBull)
                p.add("THREE WHITE SOLDIERS","MOMENTUM",+.82,.86,.18,3,true,ctx);
            if(sa<0&&sb<0&&sc<0&&healthyBearBodies&&fallingCenters&&!shrinkingBear)
                p.add("THREE BLACK CROWS","MOMENTUM",-.82,.86,.18,3,true,ctx);

            boolean bInside=bTop>=aTop-tol&&bBot<=aBot+tol&&bb<=ba*.75;
            if(sa<0&&bInside&&sc>0&&bc>=.38){
                boolean ok=down||support;
                p.add("THREE INSIDE UP","REVERSAL",+.70,ok?.78:.60,.30,2,ok,ctx);
            }
            if(sa>0&&bInside&&sc<0&&bc>=.38){
                boolean ok=up||resistance;
                p.add("THREE INSIDE DOWN","REVERSAL",-.70,ok?.78:.60,.30,2,ok,ctx);
            }

            boolean bEngulf=bTop<=aTop+tol&&bBot>=aBot-tol&&bb>=ba*1.05;
            if(sa<0&&sb>0&&bEngulf&&sc>0){
                boolean ok=down||support;
                p.add("THREE OUTSIDE UP","REVERSAL",+.84,ok?.88:.68,.18,3,ok,ctx);
            }
            if(sa>0&&sb<0&&bEngulf&&sc<0){
                boolean ok=up||resistance;
                p.add("THREE OUTSIDE DOWN","REVERSAL",-.84,ok?.88:.68,.18,3,ok,ctx);
            }

            // Tri-Star: three doji-like candles are mostly an exhaustion warning;
            // only weakly directional when they occur at an extreme.
            boolean aDoji=ba<=.15||Math.abs(d.get(a))<.10;
            boolean cDoji=bc<=.15||Math.abs(d.get(c))<.10;
            if(aDoji&&bDoji&&cDoji){
                double td=up&&resistance?-.40:down&&support?+.40:0;
                p.add("TRI-STAR","DOJI",td,.56,.78,1,false,ctx);
            }

            // Advance Block / Stalled Pattern: three bullish candles with shrinking
            // bodies and increasing upper rejection near resistance.
            if(sa>0&&sb>0&&sc>0&&risingCenters&&(up||resistance)&&
                    (shrinkingBull||bc<.30||clip01(upper.get(c))>=clip01(upper.get(b)))){
                p.add("BULLISH ADVANCE STALLED","EXHAUSTION",-.50,.66,.48,1,true,ctx);
            }
            if(sa<0&&sb<0&&sc<0&&fallingCenters&&(down||support)&&
                    (shrinkingBear||bc<.30||clip01(lower.get(c))>=clip01(lower.get(b)))){
                p.add("BEARISH DECLINE STALLED","EXHAUSTION",+.50,.66,.48,1,true,ctx);
            }
        }

        // ---------- five-candle continuation family ----------
        if(k>=5){
            int a=i-4,b=i-3,c=i-2,e=i-1,f=i;
            int sa=sign(d.get(a)), sb=sign(d.get(b)), sc=sign(d.get(c)), se=sign(d.get(e)), sf=sign(d.get(f));
            double ba=clip01(body.get(a)), bf=clip01(body.get(f));
            double aTop=top(y,range,a),aBottom=bottom(y,range,a);
            boolean midsInside=true;
            for(int q=b;q<=e;q++){
                if(top(y,range,q)<aTop-range.get(a)*.10 || bottom(y,range,q)>aBottom+range.get(a)*.10){
                    midsInside=false; break;
                }
            }
            boolean midMostlyBear=(sb<=0?1:0)+(sc<=0?1:0)+(se<=0?1:0)>=2;
            boolean midMostlyBull=(sb>=0?1:0)+(sc>=0?1:0)+(se>=0?1:0)>=2;
            if(sa>0&&sf>0&&ba>=.48&&bf>=.46&&midsInside&&midMostlyBear){
                boolean ok=up||y.get(f)<y.get(a);
                p.add("RISING THREE METHODS","CONTINUATION",+.72,ok?.78:.62,.30,2,ok,ctx);
            }
            if(sa<0&&sf<0&&ba>=.48&&bf>=.46&&midsInside&&midMostlyBull){
                boolean ok=down||y.get(f)>y.get(a);
                p.add("FALLING THREE METHODS","CONTINUATION",-.72,ok?.78:.62,.30,2,ok,ctx);
            }
        }

        // Strong contextual conflict lowers trust. A visual pattern in the middle
        // of a range without trend/level context is never treated as textbook certainty.
        if(Math.abs(p.dir)>.10 && Math.signum(p.dir)!=0 && Math.signum(trend)!=0 &&
                Math.signum(p.dir)!=Math.signum(trend) && !(support||resistance) && !p.confirmed){
            p.conf*=.82; p.unc=Math.max(p.unc,.58);
        }

        return new Result(p.name,p.family,p.dir,p.conf,p.unc,p.tier,p.confirmed,p.context);
    }

    public static String catalogSummary(){
        return "Doji/Spinning Top, Marubozu, Hammer/Hanging Man, Inverted Hammer/Shooting Star, " +
                "Belt Hold, Engulfing, Harami/Harami Cross, Piercing/Dark Cloud, Tweezers, " +
                "Kicker-like, Counterattack, Matching Low, On/In-Neck, Thrusting, Morning/Evening Star, " +
                "Morning/Evening Doji Star, Abandoned Baby-like, Three White Soldiers/Black Crows, Three Inside/Outside, " +
                "Tri-Star, Advance Block/Stalled, Rising/Falling Three Methods";
    }

    private static String context(boolean up,boolean down,boolean support,boolean resistance){
        if(support&&down)return "downtrend at support";
        if(resistance&&up)return "uptrend at resistance";
        if(support)return "near support";
        if(resistance)return "near resistance";
        if(up)return "uptrend";
        if(down)return "downtrend";
        return "range / mixed";
    }

    private static double location(List<Double> y,int s,int k){
        int look=Math.min(10,k), start=s+k-look;
        double lo=1,hi=0;
        for(int q=start;q<s+k;q++){lo=Math.min(lo,y.get(q));hi=Math.max(hi,y.get(q));}
        double span=Math.max(.004,hi-lo);
        return clamp((y.get(s+k-1)-lo)/span,0,1);
    }
    private static double bh(List<Double>r,List<Double>b,int i){return Math.max(.0005,r.get(i)*clip01(b.get(i)));}
    private static double bodyTop(List<Double>y,List<Double>r,List<Double>b,int i){return y.get(i)-bh(r,b,i)/2;}
    private static double bodyBottom(List<Double>y,List<Double>r,List<Double>b,int i){return y.get(i)+bh(r,b,i)/2;}
    private static double openY(List<Double>y,List<Double>r,List<Double>b,List<Double>d,int i){
        int s=sign(d.get(i)); return s>=0?bodyBottom(y,r,b,i):bodyTop(y,r,b,i);
    }
    private static double closeY(List<Double>y,List<Double>r,List<Double>b,List<Double>d,int i){
        int s=sign(d.get(i)); return s>=0?bodyTop(y,r,b,i):bodyBottom(y,r,b,i);
    }
    private static double top(List<Double>y,List<Double>r,int i){return y.get(i)-r.get(i)/2;}
    private static double bottom(List<Double>y,List<Double>r,int i){return y.get(i)+r.get(i)/2;}
    private static double mean(List<Double>a,int start,int count){
        if(a==null||a.isEmpty()||count<=0)return 0;
        int ss=Math.max(0,start), ee=Math.min(a.size(),ss+count);
        if(ee<=ss)return 0;
        double z=0;for(int q=ss;q<ee;q++)z+=a.get(q);return z/(ee-ss);
    }
    private static int sign(double v){return v>.08?1:v<-.08?-1:0;}
    private static double clip01(double v){return clamp(v,0,1);}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
}
