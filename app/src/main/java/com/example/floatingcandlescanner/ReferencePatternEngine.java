package com.example.floatingcandlescanner;

import java.util.List;

/**
 * v10.0 educational-reference pattern layer.
 *
 * Social/educational pattern posters are used only as shape references. This
 * engine deliberately applies trend/location/confirmation context before a
 * pattern can influence the next-candle score. Visual inputs are normalized
 * candle proxies extracted from the broker chart rather than exchange OHLC.
 */
public final class ReferencePatternEngine {
    private ReferencePatternEngine() {}

    public static final class Result {
        public final String name;
        public final double directionalScore; // + BUY, - SELL
        public final double confidence;
        public final double uncertainty;
        public final int tier;                 // 3 strong, 2 medium, 1 weak
        public final boolean confirmed;

        Result(String n,double d,double c,double u,int t,boolean ok){
            name=n; directionalScore=clamp(d,-1,1); confidence=clamp(c,0,1);
            uncertainty=clamp(u,0,1); tier=t; confirmed=ok;
        }
        public boolean strong(){
            return confirmed && tier>=2 && confidence>=.66 && Math.abs(directionalScore)>=.50 && uncertainty<.60;
        }
    }

    private static final class Pick {
        String name="NO REFERENCE CONFLUENCE";
        double dir=0,conf=.10,unc=.55;
        int tier=0; boolean confirmed=false;
        void add(String n,double d,double c,double u,int t,boolean ok){
            double merit=Math.abs(d)*c*(1+.10*t)*(ok?1:.72);
            double cur=Math.abs(dir)*conf*(1+.10*tier)*(confirmed?1:.72);
            if(merit>cur){name=n;dir=d;conf=c;unc=u;tier=t;confirmed=ok;}
        }
    }

    public static Result analyze(List<Double>d,List<Double>y,List<Double>range,List<Double>body,
                                 List<Double>upper,List<Double>lower,int s,int k,double trend){
        if(d==null||y==null||range==null||body==null||upper==null||lower==null||k<1)
            return new Result("NO REFERENCE CONFLUENCE",0,.10,.80,0,false);

        int i=s+k-1;
        int si=sign(d.get(i));
        double bi=clip01(body.get(i)), ui=clip01(upper.get(i)), li=clip01(lower.get(i));
        double loc=location(y,s,k); // 0 high/resistance, 1 low/support
        boolean resistance=loc<=.30, support=loc>=.70;
        boolean up=trend>.18, down=trend<-.18;
        Pick p=new Pick();

        // Indecision candles are not directional signals by themselves.
        boolean doji=bi<=.15 || Math.abs(d.get(i))<.12;
        boolean longLegged=doji && ui>=.28 && li>=.28;
        if(longLegged) p.add("LONG-LEGGED DOJI / INDECISION",0,.30,.88,1,false);
        else if(doji) p.add("DOJI / INDECISION",0,.30,.78,1,false);

        // Strong momentum candle / Marubozu.
        if(si!=0 && bi>=.76 && ui<=.13 && li<=.13){
            boolean ctx=(si>0&&up)||(si<0&&down);
            p.add(si>0?"BULLISH MARUBOZU":"BEARISH MARUBOZU",si*.82,ctx?.87:.70,.18,3,ctx);
        }

        // Hammer / hanging-man / inverted-hammer / shooting-star family.
        boolean smallBody=bi<=.38;
        boolean longLower=li>=.42 && li>=Math.max(.30,bi*1.90);
        boolean longUpper=ui>=.42 && ui>=Math.max(.30,bi*1.90);
        boolean shortUpper=ui<=Math.max(.16,bi*.55);
        boolean shortLower=li<=Math.max(.16,bi*.55);
        if(smallBody&&longLower&&shortUpper){
            if(down&&support) p.add("HAMMER AT SUPPORT",+.74,.80,.26,2,true);
            else if(up&&resistance) p.add("HANGING MAN AT RESISTANCE",-.70,.76,.30,2,true);
            else p.add("HAMMER / HANGING-MAN SHAPE",si>=0?+.20:-.20,.42,.55,1,false);
        }
        if(smallBody&&longUpper&&shortLower){
            if(down&&support) p.add("INVERTED HAMMER AT SUPPORT",+.66,.72,.33,2,true);
            else if(up&&resistance) p.add("SHOOTING STAR AT RESISTANCE",-.76,.82,.24,2,true);
            else p.add("INVERTED-HAMMER / SHOOTING-STAR SHAPE",si>=0?+.18:-.18,.40,.58,1,false);
        }

        if(k>=2){
            int a=i-1; int sa=sign(d.get(a));
            double ba=clip01(body.get(a));
            double aTop=bodyTop(y,range,body,a), aBot=bodyBottom(y,range,body,a);
            double cTop=bodyTop(y,range,body,i), cBot=bodyBottom(y,range,body,i);
            double tol=Math.max(.0025,Math.min(range.get(a),range.get(i))*.15);

            // Engulfing: strong when it reverses a prior trend at an extreme.
            boolean engulf=sa!=0&&si==-sa&&bi>=ba*1.08&&cTop<=aTop+tol&&cBot>=aBot-tol;
            if(engulf){
                boolean ctx=(si>0&&(down||support))||(si<0&&(up||resistance));
                p.add(si>0?"BULLISH ENGULFING":"BEARISH ENGULFING",si*.88,ctx?.91:.72,.16,3,ctx);
            }

            // Harami: useful but weaker and needs context/confirmation.
            boolean inside=cTop>=aTop-tol&&cBot<=aBot+tol&&bi<=ba*.72&&sa!=0&&si==-sa;
            if(inside){
                boolean ctx=(si>0&&(down||support))||(si<0&&(up||resistance));
                p.add(si>0?"BULLISH HARAMI":"BEARISH HARAMI",si*.52,ctx?.64:.48,.42,1,ctx);
            }

            // Piercing / dark cloud midpoint takeover.
            double mid=(aTop+aBot)/2.0;
            if(sa<0&&si>0&&bi>=.40&&cTop<mid&&cBot>=aBot-tol){
                boolean ctx=down||support;
                p.add("PIERCING LINE",+.62,ctx?.70:.52,.36,2,ctx);
            }
            if(sa>0&&si<0&&bi>=.40&&cBot>mid&&cTop<=aTop+tol){
                boolean ctx=up||resistance;
                p.add("DARK CLOUD COVER",-.62,ctx?.70:.52,.36,2,ctx);
            }

            // Tweezer extrema.
            double topDiff=Math.abs(top(y,range,a)-top(y,range,i));
            double botDiff=Math.abs(bottom(y,range,a)-bottom(y,range,i));
            double avgR=Math.max(.003,(range.get(a)+range.get(i))/2.0);
            if(topDiff<=avgR*.18 && resistance && (sa<0||si<0))
                p.add("TWEEZER TOP",-.58,.66,.36,2,true);
            if(botDiff<=avgR*.18 && support && (sa>0||si>0))
                p.add("TWEEZER BOTTOM",+.58,.66,.36,2,true);

            // Counterattack: opposite large candles terminating around same zone.
            if(sa!=0&&si==-sa&&ba>=.46&&bi>=.46&&Math.abs(y.get(a)-y.get(i))<=avgR*.22){
                boolean ctx=(si>0&&(down||support))||(si<0&&(up||resistance));
                p.add(si>0?"BULLISH COUNTERATTACK":"BEARISH COUNTERATTACK",si*.56,ctx?.65:.50,.40,2,ctx);
            }

            // On-neck is a bearish continuation pattern in a downtrend. Some social
            // posters mislabel it; keep the textbook directional context here.
            if(down&&sa<0&&si>0&&bi<=ba*.70){
                double prevLow=bottom(y,range,a);
                if(Math.abs(bodyTop(y,range,body,i)-prevLow)<=avgR*.22)
                    p.add("ON-NECK BEARISH CONTINUATION",-.52,.60,.44,1,true);
            }
        }

        if(k>=3){
            int a=i-2,b=i-1,c=i;
            int sa=sign(d.get(a)), sb=sign(d.get(b)), sc=sign(d.get(c));
            double ba=clip01(body.get(a)), bb=clip01(body.get(b)), bc=clip01(body.get(c));

            // Morning/evening stars.
            if(sa<0&&ba>=.50&&bb<=.30&&sc>0&&bc>=.46){
                boolean ctx=down||support;
                p.add("MORNING STAR",+.74,ctx?.82:.62,.24,3,ctx);
            }
            if(sa>0&&ba>=.50&&bb<=.30&&sc<0&&bc>=.46){
                boolean ctx=up||resistance;
                p.add("EVENING STAR",-.74,ctx?.82:.62,.24,3,ctx);
            }

            // Three soldiers/crows: strong but require progressive movement.
            boolean rise=y.get(a)>y.get(b)&&y.get(b)>y.get(c);
            boolean fall=y.get(a)<y.get(b)&&y.get(b)<y.get(c);
            if(sa>0&&sb>0&&sc>0&&ba>=.36&&bb>=.36&&bc>=.36&&rise){
                boolean ctx=up||down||support;
                p.add("THREE WHITE SOLDIERS",+.80,.84,.18,3,ctx);
            }
            if(sa<0&&sb<0&&sc<0&&ba>=.36&&bb>=.36&&bc>=.36&&fall){
                boolean ctx=down||up||resistance;
                p.add("THREE BLACK CROWS",-.80,.84,.18,3,ctx);
            }

            // Three inside up/down.
            double aTop=bodyTop(y,range,body,a), aBot=bodyBottom(y,range,body,a);
            double bTop=bodyTop(y,range,body,b), bBot=bodyBottom(y,range,body,b);
            double tol=Math.max(.0025,Math.min(range.get(a),range.get(b))*.15);
            boolean bInside=bTop>=aTop-tol&&bBot<=aBot+tol&&bb<=ba*.75;
            if(sa<0&&bInside&&sc>0&&bc>=.40){
                boolean ctx=down||support;
                p.add("THREE INSIDE UP",+.70,ctx?.77:.58,.30,2,ctx);
            }
            if(sa>0&&bInside&&sc<0&&bc>=.40){
                boolean ctx=up||resistance;
                p.add("THREE INSIDE DOWN",-.70,ctx?.77:.58,.30,2,ctx);
            }

            // Three outside up/down: second candle engulfs first, third confirms.
            boolean bEngulf=bTop<=aTop+tol&&bBot>=aBot-tol&&bb>=ba*1.05;
            if(sa<0&&sb>0&&bEngulf&&sc>0){
                boolean ctx=down||support;
                p.add("THREE OUTSIDE UP",+.82,ctx?.86:.66,.20,3,ctx);
            }
            if(sa>0&&sb<0&&bEngulf&&sc<0){
                boolean ctx=up||resistance;
                p.add("THREE OUTSIDE DOWN",-.82,ctx?.86:.66,.20,3,ctx);
            }

            // Double doji at a supply/demand extreme: only useful with a third
            // confirmation candle. Without confirmation it remains uncertainty.
            boolean da=clip01(body.get(a))<=.18, db=clip01(body.get(b))<=.18;
            if(da&&db){
                if(sc<0&&resistance) p.add("DOUBLE DOJI + BEARISH CONFIRMATION",-.58,.66,.38,2,true);
                else if(sc>0&&support) p.add("DOUBLE DOJI + BULLISH CONFIRMATION",+.58,.66,.38,2,true);
                else p.add("DOUBLE DOJI / INDECISION",0,.30,.82,1,false);
            }
        }

        // Supply/demand rejection confluence from the latest wick + location.
        if(support && li>=.34 && (si>0||bi<=.30))
            p.add("DEMAND REJECTION",+.64,.72,.30,2,true);
        if(resistance && ui>=.34 && (si<0||bi<=.30))
            p.add("SUPPLY REJECTION",-.64,.72,.30,2,true);

        // Conflicting context softens confidence.
        if(Math.signum(p.dir)!=0 && Math.signum(trend)!=0 && Math.signum(p.dir)!=Math.signum(trend)
                && !(support||resistance)){
            p.conf*=.82; p.unc=Math.max(p.unc,.48);
        }

        return new Result(p.name,p.dir,p.conf,p.unc,p.tier,p.confirmed);
    }

    private static double location(List<Double> y,int s,int k){
        int look=Math.min(8,k); int start=s+k-look;
        double lo=1,hi=0;
        for(int q=start;q<s+k;q++){ lo=Math.min(lo,y.get(q)); hi=Math.max(hi,y.get(q)); }
        double span=Math.max(.004,hi-lo);
        return clamp((y.get(s+k-1)-lo)/span,0,1);
    }
    private static double bh(List<Double>r,List<Double>b,int i){return Math.max(.0005,r.get(i)*clip01(b.get(i)));}
    private static double bodyTop(List<Double>y,List<Double>r,List<Double>b,int i){return y.get(i)-bh(r,b,i)/2;}
    private static double bodyBottom(List<Double>y,List<Double>r,List<Double>b,int i){return y.get(i)+bh(r,b,i)/2;}
    private static double top(List<Double>y,List<Double>r,int i){return y.get(i)-r.get(i)/2;}
    private static double bottom(List<Double>y,List<Double>r,int i){return y.get(i)+r.get(i)/2;}
    private static int sign(double v){return v>.05?1:v<-.05?-1:0;}
    private static double clip01(double v){return clamp(v,0,1);}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
}
