package com.example.floatingcandlescanner;

import java.util.ArrayList;
import java.util.List;

/**
 * v14 uploaded-learning consolidation layer.
 *
 * This class contains only chart rules that were present in the user's uploaded
 * learning material but were not already covered strongly by the older engines.
 * It follows the source material's confirmation-first framing: a named chart
 * pattern is not treated as active until the relevant breakout/confirmation
 * occurs.  Inputs are screenshot-derived candle geometry, so every result is a
 * visual approximation rather than proof of exact broker OHLC/order flow.
 */
public final class UploadedKnowledgeEngine {
    private UploadedKnowledgeEngine() {}

    public static final class Result {
        public final String name;
        public final double directionalScore;
        public final double confidence;
        public final double uncertainty;
        public final boolean confirmed;
        public final String family;

        Result(String n,double d,double c,double u,boolean ok,String f){
            name=n; directionalScore=clamp(d,-1,1); confidence=clamp(c,0,1);
            uncertainty=clamp(u,0,1); confirmed=ok; family=f;
        }
        public boolean strong(){
            return confirmed && confidence>=.68 && Math.abs(directionalScore)>=.52 && uncertainty<.58;
        }
    }

    private static final class Pick {
        String name="UPLOADED KNOWLEDGE: NO CONFIRMED SETUP";
        String family="NONE";
        double dir=0,conf=.10,unc=.68;
        boolean confirmed=false;
        void add(String n,String f,double d,double c,double u,boolean ok){
            double merit=Math.abs(d)*c*(ok?1.08:.70)*(1.0-.35*u);
            double best=Math.abs(dir)*conf*(confirmed?1.08:.70)*(1.0-.35*unc);
            if(merit>best){name=n;family=f;dir=d;conf=c;unc=u;confirmed=ok;}
        }
    }

    public static Result analyze(
            List<Double>d,List<Double>y,List<Double>range,List<Double>body,
            List<Double>upper,List<Double>lower,int s,int k,double trend){
        if(d==null||y==null||range==null||body==null||k<5)
            return new Result("UPLOADED KNOWLEDGE: LOW DATA",0,.10,.90,false,"NONE");

        int end=s+k;
        int longK=Math.min(34,end);
        int ls=Math.max(0,end-longK);
        int lk=end-ls;
        Pick p=new Pick();

        Pattern hs=headShoulders(d,y,range,body,ls,lk);
        if(hs!=null)p.add(hs.name,"HEAD_SHOULDERS",hs.dir,hs.conf,hs.unc,true);

        Pattern cup=cupHandle(d,y,range,body,ls,lk);
        if(cup!=null)p.add(cup.name,"CUP_HANDLE",cup.dir,cup.conf,cup.unc,true);

        Pattern pipe=pipeReversal(d,y,range,body,ls,lk,trend);
        if(pipe!=null)p.add(pipe.name,"PIPE_REVERSAL",pipe.dir,pipe.conf,pipe.unc,true);

        Pattern nr=narrowRangeBreakout(d,y,range,body,ls,lk);
        if(nr!=null)p.add(nr.name,"NARROW_RANGE",nr.dir,nr.conf,nr.unc,true);

        Pattern gap=gapPivot(d,y,range,body,ls,lk);
        if(gap!=null)p.add(gap.name,"GAP_PIVOT",gap.dir,gap.conf,gap.unc,true);

        // The uploaded material repeatedly emphasizes that pattern context matters.
        // A counter-trend setup is allowed only for explicit reversal structures;
        // otherwise reduce confidence rather than blindly following a label.
        double unc=p.unc;
        double conf=p.conf;
        if(p.dir!=0 && Math.abs(trend)>.55 && Math.signum(p.dir)!=Math.signum(trend)
                && !p.family.equals("HEAD_SHOULDERS") && !p.family.equals("PIPE_REVERSAL")){
            unc=Math.max(unc,.43);
            conf*=.82;
        }
        return new Result(p.name,p.dir,conf,unc,p.confirmed,p.family);
    }

    private static final class Pattern {
        String name; double dir,conf,unc;
        Pattern(String n,double d,double c,double u){name=n;dir=d;conf=c;unc=u;}
    }

    /** Fidelity-style head-and-shoulders: 3 peaks/troughs + neckline break. */
    private static Pattern headShoulders(List<Double>d,List<Double>y,List<Double>r,List<Double>body,int s,int k){
        if(k<10)return null;
        int i=s+k-1;
        List<Integer> highs=new ArrayList<>(), lows=new ArrayList<>();
        for(int q=s+1;q<i-1;q++){
            if(top(y,r,q)<=top(y,r,q-1)&&top(y,r,q)<=top(y,r,q+1))highs.add(q);
            if(bottom(y,r,q)>=bottom(y,r,q-1)&&bottom(y,r,q)>=bottom(y,r,q+1))lows.add(q);
        }
        double span=windowSpan(y,r,s,k);
        double shoulderTol=Math.max(.004,span*.13);
        double headMin=Math.max(.003,span*.08);

        if(highs.size()>=3){
            int a=highs.get(highs.size()-3),b=highs.get(highs.size()-2),c=highs.get(highs.size()-1);
            double la=top(y,r,a), hb=top(y,r,b), rc=top(y,r,c);
            boolean shoulders=Math.abs(la-rc)<=shoulderTol;
            boolean headHigher=hb<Math.min(la,rc)-headMin; // smaller screen Y = higher price
            if(shoulders&&headHigher){
                double neck1=maxBottom(y,r,a+1,b), neck2=maxBottom(y,r,b+1,c);
                double neckline=(neck1+neck2)/2.0;
                if(sign(d.get(i))<0&&body.get(i)>=.28&&y.get(i)>neckline+Math.max(.0025,span*.025))
                    return new Pattern("HEAD & SHOULDERS TOP + NECKLINE BREAK",-.88,.88,.20);
            }
        }
        if(lows.size()>=3){
            int a=lows.get(lows.size()-3),b=lows.get(lows.size()-2),c=lows.get(lows.size()-1);
            double la=bottom(y,r,a), hb=bottom(y,r,b), rc=bottom(y,r,c);
            boolean shoulders=Math.abs(la-rc)<=shoulderTol;
            boolean headLower=hb>Math.max(la,rc)+headMin;
            if(shoulders&&headLower){
                double neck1=minTop(y,r,a+1,b), neck2=minTop(y,r,b+1,c);
                double neckline=(neck1+neck2)/2.0;
                if(sign(d.get(i))>0&&body.get(i)>=.28&&y.get(i)<neckline-Math.max(.0025,span*.025))
                    return new Pattern("INVERSE HEAD & SHOULDERS + NECKLINE BREAK",+.86,.84,.24);
            }
        }
        return null;
    }

    /** Rounded-bottom + handle + lip breakout proxy. Kept moderate because pixels are coarse. */
    private static Pattern cupHandle(List<Double>d,List<Double>y,List<Double>r,List<Double>body,int s,int k){
        if(k<14)return null;
        int i=s+k-1;
        int start=Math.max(s,i-15);
        int n=i-start;
        if(n<11)return null;
        int q=n/4;
        if(q<2)return null;

        double leftLip=minTop(y,r,start,start+q);
        double centerLow=maxBottom(y,r,start+q,start+3*q);
        double rightLip=minTop(y,r,start+3*q,i-1);
        double span=Math.max(.008,centerLow-Math.min(leftLip,rightLip));
        boolean lipsNear=Math.abs(leftLip-rightLip)<=Math.max(.006,span*.22);
        boolean roundedDepth=centerLow>Math.max(leftLip,rightLip)+Math.max(.008,span*.38);
        if(!lipsNear||!roundedDepth)return null;

        // Handle: last few pre-breakout candles pull back modestly but do not revisit cup bottom.
        double handleLow=0;
        for(int x=Math.max(start,i-4);x<i;x++)handleLow=Math.max(handleLow,bottom(y,r,x));
        boolean handleOk=handleLow<centerLow-span*.28;
        double lip=(leftLip+rightLip)/2.0;
        if(handleOk&&sign(d.get(i))>0&&body.get(i)>=.30&&top(y,r,i)<lip-Math.max(.002,span*.04))
            return new Pattern("CUP & HANDLE + LIP BREAKOUT",+.72,.72,.34);
        return null;
    }

    /** Two-bar reversal / pipe bottom or top, activated by breakout through the second bar. */
    private static Pattern pipeReversal(List<Double>d,List<Double>y,List<Double>r,List<Double>body,int s,int k,double trend){
        if(k<6)return null;
        int i=s+k-1,b=i-1,a=i-2;
        double base=mean(r,Math.max(s,a-4),Math.max(1,a-Math.max(s,a-4)));
        if(base<=.0001)return null;
        boolean largeA=r.get(a)>=base*1.15, largeB=r.get(b)>=base*1.10;
        if(!largeA||!largeB)return null;

        if(trend<-.28 && sign(d.get(a))<0 && sign(d.get(b))>0 && body.get(b)>=.35){
            if(sign(d.get(i))>0 && top(y,r,i)<top(y,r,b)-.0015)
                return new Pattern("PIPE BOTTOM / TWO-BAR REVERSAL BREAKOUT",+.70,.72,.30);
        }
        if(trend>.28 && sign(d.get(a))>0 && sign(d.get(b))<0 && body.get(b)>=.35){
            if(sign(d.get(i))<0 && bottom(y,r,i)>bottom(y,r,b)+.0015)
                return new Pattern("PIPE TOP / TWO-BAR REVERSAL BREAKOUT",-.70,.72,.30);
        }
        return null;
    }

    /** NR4 / inside-range activation: previous bar is the narrowest of four, latest breaks it. */
    private static Pattern narrowRangeBreakout(List<Double>d,List<Double>y,List<Double>r,List<Double>body,int s,int k){
        if(k<6)return null;
        int i=s+k-1,p=i-1;
        if(p-3<s)return null;
        double rp=r.get(p);
        boolean nr4=rp<r.get(p-1)&&rp<r.get(p-2)&&rp<r.get(p-3);
        boolean inside=top(y,r,p)>=top(y,r,p-1)-.001 && bottom(y,r,p)<=bottom(y,r,p-1)+.001;
        if(!nr4)return null;
        if(sign(d.get(i))>0&&body.get(i)>=.26&&top(y,r,i)<top(y,r,p)-.0012)
            return new Pattern(inside?"INSIDE NR4 BREAKOUT UP":"NR4 BREAKOUT UP",+.60,.69,.31);
        if(sign(d.get(i))<0&&body.get(i)>=.26&&bottom(y,r,i)>bottom(y,r,p)+.0012)
            return new Pattern(inside?"INSIDE NR4 BREAKOUT DOWN":"NR4 BREAKOUT DOWN",-.60,.69,.31);
        return null;
    }

    /** Explosion-gap-pivot approximation from non-overlapping visual candle ranges. */
    private static Pattern gapPivot(List<Double>d,List<Double>y,List<Double>r,List<Double>body,int s,int k){
        if(k<7)return null;
        int i=s+k-1,g=i-2,t=i-1,pre=g-1;
        double preTop=top(y,r,pre),preBot=bottom(y,r,pre);
        double gTop=top(y,r,g),gBot=bottom(y,r,g);
        double tol=Math.max(.0015,windowSpan(y,r,Math.max(s,pre-4),Math.min(k,7))*.02);
        boolean gapUp=gBot<preTop-tol;   // whole gap candle above prior range
        boolean gapDown=gTop>preBot+tol; // whole gap candle below prior range

        if(gapUp){
            // throwback does not cover the gap and latest breaks gap bar high
            boolean held=bottom(y,r,t)<preTop+tol*.35;
            if(held&&sign(d.get(i))>0&&top(y,r,i)<gTop-.001)
                return new Pattern("GAP PIVOT CONTINUATION UP",+.56,.64,.40);
        }
        if(gapDown){
            boolean held=top(y,r,t)>preBot-tol*.35;
            if(held&&sign(d.get(i))<0&&bottom(y,r,i)>gBot+.001)
                return new Pattern("GAP PIVOT CONTINUATION DOWN",-.56,.64,.40);
        }
        return null;
    }

    private static double minTop(List<Double>y,List<Double>r,int from,int to){
        double v=1; for(int q=from;q<to;q++)v=Math.min(v,top(y,r,q)); return v;
    }
    private static double maxBottom(List<Double>y,List<Double>r,int from,int to){
        double v=0; for(int q=from;q<to;q++)v=Math.max(v,bottom(y,r,q)); return v;
    }
    private static double windowSpan(List<Double>y,List<Double>r,int s,int k){
        int end=Math.min(y.size(),s+k); double hi=1,lo=0;
        for(int q=s;q<end;q++){hi=Math.min(hi,top(y,r,q));lo=Math.max(lo,bottom(y,r,q));}
        return Math.max(.006,lo-hi);
    }
    private static double mean(List<Double>x,int s,int k){
        if(k<=0)return 0; double z=0; int n=0;
        for(int q=s;q<s+k&&q<x.size();q++){z+=x.get(q);n++;}
        return n==0?0:z/n;
    }
    private static int sign(double x){return x>.06?1:x<-.06?-1:0;}
    private static double top(List<Double>y,List<Double>r,int i){return y.get(i)-r.get(i)/2.0;}
    private static double bottom(List<Double>y,List<Double>r,int i){return y.get(i)+r.get(i)/2.0;}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
}
