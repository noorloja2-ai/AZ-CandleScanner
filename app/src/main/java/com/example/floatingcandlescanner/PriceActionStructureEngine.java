package com.example.floatingcandlescanner;

import java.util.ArrayList;
import java.util.List;

/**
 * v9.9 price-action / chart-structure confirmation layer.
 *
 * This engine only scores structures that can be approximated from the candle
 * geometry extracted from the broker screenshot. It deliberately avoids
 * unverifiable social-media labels (for example order blocks/FVG) as hard rules.
 */
public final class PriceActionStructureEngine {
    private PriceActionStructureEngine() {}

    public static final class Result {
        public final String name;
        public final double directionalScore; // +BUY / -SELL
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
            return confirmed && confidence>=.66 && Math.abs(directionalScore)>=.52 && uncertainty<.56;
        }
    }

    private static final class Pick {
        String name="NO PRICE-ACTION CONFLUENCE";
        double dir=0, conf=.10;
        boolean confirmed=false;
        void add(String n,double d,double c,boolean ok){
            double merit=Math.abs(d)*c*(ok?1.0:.72);
            double current=Math.abs(dir)*conf*(confirmed?1.0:.72);
            if(merit>current){ name=n; dir=d; conf=c; confirmed=ok; }
        }
    }

    public static Result analyze(
            List<Double>d,List<Double>y,List<Double>range,List<Double>body,
            List<Double>upper,List<Double>lower,int s,int k,double trend){
        if(d==null||y==null||range==null||body==null||upper==null||lower==null||k<4)
            return new Result("NO PRICE-ACTION CONFLUENCE",0,.10,.82,false);

        int i=s+k-1;
        Pick p=new Pick();
        double loc=location(y,s,k); // 0 high/resistance, 1 low/support
        boolean nearSupply=loc<=.28;
        boolean nearDemand=loc>=.72;
        boolean up=trend>.18, down=trend<-.18;
        int si=sign(d.get(i));
        double bi=clip01(body.get(i));
        double ui=clip01(upper.get(i));
        double li=clip01(lower.get(i));

        ZoneStats zones=zoneStats(y,range,s,k);
        boolean repeatedSupply=nearSupply && zones.highTouches>=2;
        boolean repeatedDemand=nearDemand && zones.lowTouches>=2;

        // Supply/demand rejection: location + repeated touch + wick/body evidence.
        if((repeatedDemand||nearDemand) && li>=Math.max(.34,bi*1.55) && ui<=.28){
            double c=repeatedDemand?.88:.76;
            p.add("DEMAND REJECTION",+.82,c,down||repeatedDemand);
        }
        if((repeatedSupply||nearSupply) && ui>=Math.max(.34,bi*1.55) && li<=.28){
            double c=repeatedSupply?.88:.76;
            p.add("SUPPLY REJECTION",-.82,c,up||repeatedSupply);
        }

        if(k>=2){
            int a=i-1;
            int sa=sign(d.get(a));
            double ba=clip01(body.get(a));
            double aTop=bodyTop(y,range,body,a), aBot=bodyBottom(y,range,body,a);
            double cTop=bodyTop(y,range,body,i), cBot=bodyBottom(y,range,body,i);
            double tol=Math.max(.0028,Math.min(range.get(a),range.get(i))*.16);

            boolean engulf=sa!=0 && si==-sa && bi>=ba*1.05 && cTop<=aTop+tol && cBot>=aBot-tol;
            if(engulf && si>0 && nearDemand)
                p.add("BULLISH ENGULFING AT DEMAND",+.90,repeatedDemand?.94:.88,true);
            if(engulf && si<0 && nearSupply)
                p.add("BEARISH ENGULFING AT SUPPLY",-.90,repeatedSupply?.94:.88,true);

            boolean harami=sa!=0 && si==-sa && bi<=ba*.70 && cTop>=aTop-tol && cBot<=aBot+tol;
            if(harami && si>0 && (nearDemand||down))
                p.add("BULLISH HARAMI AT DEMAND",+.67,repeatedDemand?.77:.68,repeatedDemand||down);
            if(harami && si<0 && (nearSupply||up))
                p.add("BEARISH HARAMI AT SUPPLY",-.67,repeatedSupply?.77:.68,repeatedSupply||up);

            // Double hammer / double shooting-star style confirmation.
            boolean prevLower=lower.get(a)>=Math.max(.36,body.get(a)*1.45) && upper.get(a)<=.30;
            boolean curLower=li>=Math.max(.36,bi*1.45) && ui<=.30;
            if(prevLower&&curLower&&(nearDemand||down))
                p.add("DOUBLE LOWER-WICK REJECTION",+.74,repeatedDemand?.84:.75,true);

            boolean prevUpper=upper.get(a)>=Math.max(.36,body.get(a)*1.45) && lower.get(a)<=.30;
            boolean curUpper=ui>=Math.max(.36,bi*1.45) && li<=.30;
            if(prevUpper&&curUpper&&(nearSupply||up))
                p.add("DOUBLE UPPER-WICK REJECTION",-.74,repeatedSupply?.84:.75,true);
        }

        if(k>=3){
            int a=i-2,b=i-1;
            boolean aDoji=body.get(a)<=.18 && upper.get(a)+lower.get(a)>=.48;
            boolean bDoji=body.get(b)<=.18 && upper.get(b)+lower.get(b)>=.48;
            if(aDoji&&bDoji&&si>0&&bi>=.40&&(nearDemand||down))
                p.add("DOUBLE DOJI + BULLISH CONFIRMATION",+.64,.70,nearDemand||down);
            if(aDoji&&bDoji&&si<0&&bi>=.40&&(nearSupply||up))
                p.add("DOUBLE DOJI + BEARISH CONFIRMATION",-.64,.70,nearSupply||up);
        }

        // Strong body continuation is only accepted with trend/structure context.
        if(bi>=.78 && ui<=.15 && li<=.15){
            if(si>0&&up)p.add("BULLISH MARUBOZU CONTINUATION",+.68,.72,true);
            if(si<0&&down)p.add("BEARISH MARUBOZU CONTINUATION",-.68,.72,true);
        }

        double flag=flagContinuation(d,y,range,body,s,k);
        if(flag>.45)p.add("BULLISH FLAG CONTINUATION",+.78,.82,true);
        if(flag<-.45)p.add("BEARISH FLAG CONTINUATION",-.78,.82,true);

        double compression=compressionBreakout(d,y,range,body,s,k);
        if(compression>.45)p.add("BULLISH COMPRESSION BREAKOUT",+.80,.84,true);
        if(compression<-.45)p.add("BEARISH COMPRESSION BREAKOUT",-.80,.84,true);

        double shoulder=headShoulders(y,range,d,body,s,k);
        if(shoulder>.45)p.add("INVERSE HEAD & SHOULDERS BREAK",+.76,.78,true);
        if(shoulder<-.45)p.add("HEAD & SHOULDERS BREAK",-.76,.78,true);

        double turn=doubleTurn(y,range,d,body,s,k);
        if(turn>.45)p.add("DOUBLE BOTTOM BREAK",+.74,.78,true);
        if(turn<-.45)p.add("DOUBLE TOP BREAK",-.74,.78,true);

        // Uncertainty: doji, extreme expansion, or no clear zone/context.
        double uncertainty=0;
        if(bi<=.16 && ui>=.24 && li>=.24)uncertainty=Math.max(uncertainty,.74);
        double vr=volatilityRatio(range,s,k);
        if(vr<.48)uncertainty=Math.max(uncertainty,.60);
        if(vr>2.35)uncertainty=Math.max(uncertainty,.58);
        if(!nearSupply&&!nearDemand&&Math.abs(flag)<.45&&Math.abs(compression)<.45&&Math.abs(shoulder)<.45&&Math.abs(turn)<.45)
            uncertainty=Math.max(uncertainty,.22);

        double conf=clamp(p.conf*(1.0-.40*uncertainty),.10,.95);
        double dir=p.dir*(1.0-.22*uncertainty);
        return new Result(p.name,dir,conf,uncertainty,p.confirmed);
    }

    private static final class ZoneStats {
        int highTouches, lowTouches;
    }

    private static ZoneStats zoneStats(List<Double>y,List<Double>range,int s,int k){
        ZoneStats z=new ZoneStats();
        double hi=1,lo=0;
        for(int q=s;q<s+k;q++){
            hi=Math.min(hi,top(y,range,q));
            lo=Math.max(lo,bottom(y,range,q));
        }
        double span=Math.max(.004,lo-hi), tol=Math.max(.003,span*.10);
        for(int q=s;q<s+k;q++){
            if(Math.abs(top(y,range,q)-hi)<=tol)z.highTouches++;
            if(Math.abs(bottom(y,range,q)-lo)<=tol)z.lowTouches++;
        }
        return z;
    }

    private static double flagContinuation(List<Double>d,List<Double>y,List<Double>range,List<Double>body,int s,int k){
        if(k<7)return 0;
        int i=s+k-1;
        int a=i-6,b=i-5,c=i-4,p1=i-3,p2=i-2,p3=i-1;
        double impulse=mean(d,a,3);
        double pull=mean(d,p1,3);
        double impulseBody=mean(body,a,3), pullBody=mean(body,p1,3);
        int last=sign(d.get(i));
        if(impulse>.30 && pull<.10 && pull>-.34 && impulseBody>pullBody*1.12 && last>0 && body.get(i)>.40){
            double priorHigh=Math.min(Math.min(top(y,range,p1),top(y,range,p2)),top(y,range,p3));
            if(top(y,range,i)<priorHigh-.002)return .75;
        }
        if(impulse<-.30 && pull>-.10 && pull<.34 && impulseBody>pullBody*1.12 && last<0 && body.get(i)>.40){
            double priorLow=Math.max(Math.max(bottom(y,range,p1),bottom(y,range,p2)),bottom(y,range,p3));
            if(bottom(y,range,i)>priorLow+.002)return -.75;
        }
        return 0;
    }

    private static double compressionBreakout(List<Double>d,List<Double>y,List<Double>range,List<Double>body,int s,int k){
        if(k<6)return 0;
        int i=s+k-1;
        int start=i-4;
        double old=range.get(start), recent=mean(range,start+1,3);
        if(recent>old*.90)return 0;
        double priorTop=1,priorBottom=0;
        for(int q=start;q<i;q++){
            priorTop=Math.min(priorTop,top(y,range,q));
            priorBottom=Math.max(priorBottom,bottom(y,range,q));
        }
        if(sign(d.get(i))>0 && body.get(i)>=.46 && top(y,range,i)<priorTop-.0025)return .78;
        if(sign(d.get(i))<0 && body.get(i)>=.46 && bottom(y,range,i)>priorBottom+.0025)return -.78;
        return 0;
    }

    private static double headShoulders(List<Double>y,List<Double>range,List<Double>d,List<Double>body,int s,int k){
        if(k<9)return 0;
        int i=s+k-1, start=Math.max(s,i-10);
        List<Integer> highs=new ArrayList<>(), lows=new ArrayList<>();
        for(int q=start+1;q<i-1;q++){
            double tq=top(y,range,q);
            if(tq<=top(y,range,q-1)&&tq<=top(y,range,q+1))highs.add(q);
            double bq=bottom(y,range,q);
            if(bq>=bottom(y,range,q-1)&&bq>=bottom(y,range,q+1))lows.add(q);
        }
        if(highs.size()>=3){
            int a=highs.get(highs.size()-3),h=highs.get(highs.size()-2),b=highs.get(highs.size()-1);
            double ya=top(y,range,a), yh=top(y,range,h), yb=top(y,range,b);
            double span=Math.max(.004,Math.abs(Math.max(ya,yb)-yh));
            boolean shoulders=Math.abs(ya-yb)<=Math.max(.007,span*.55);
            boolean headHigher=yh<Math.min(ya,yb)-.003;
            if(shoulders&&headHigher&&sign(d.get(i))<0&&body.get(i)>.38&&y.get(i)>Math.max(y.get(a),y.get(b))+.002)
                return -.72;
        }
        if(lows.size()>=3){
            int a=lows.get(lows.size()-3),h=lows.get(lows.size()-2),b=lows.get(lows.size()-1);
            double ya=bottom(y,range,a), yh=bottom(y,range,h), yb=bottom(y,range,b);
            double span=Math.max(.004,Math.abs(yh-Math.min(ya,yb)));
            boolean shoulders=Math.abs(ya-yb)<=Math.max(.007,span*.55);
            boolean headLower=yh>Math.max(ya,yb)+.003;
            if(shoulders&&headLower&&sign(d.get(i))>0&&body.get(i)>.38&&y.get(i)<Math.min(y.get(a),y.get(b))-.002)
                return .72;
        }
        return 0;
    }

    private static double doubleTurn(List<Double>y,List<Double>range,List<Double>d,List<Double>body,int s,int k){
        if(k<7)return 0;
        int i=s+k-1,start=Math.max(s,i-9);
        double tol=.007;
        for(int a=start+1;a<=i-4;a++){
            boolean highA=top(y,range,a)<=top(y,range,a-1)&&top(y,range,a)<=top(y,range,a+1);
            boolean lowA=bottom(y,range,a)>=bottom(y,range,a-1)&&bottom(y,range,a)>=bottom(y,range,a+1);
            for(int b=a+2;b<=i-2;b++){
                boolean highB=top(y,range,b)<=top(y,range,b-1)&&top(y,range,b)<=top(y,range,b+1);
                boolean lowB=bottom(y,range,b)>=bottom(y,range,b-1)&&bottom(y,range,b)>=bottom(y,range,b+1);
                if(highA&&highB&&Math.abs(top(y,range,a)-top(y,range,b))<=tol&&sign(d.get(i))<0&&body.get(i)>.35&&y.get(i)>y.get(b)+.003)
                    return -.70;
                if(lowA&&lowB&&Math.abs(bottom(y,range,a)-bottom(y,range,b))<=tol&&sign(d.get(i))>0&&body.get(i)>.35&&y.get(i)<y.get(b)-.003)
                    return .70;
            }
        }
        return 0;
    }

    private static double volatilityRatio(List<Double>r,int s,int k){
        if(k<4)return 1;
        double recent=mean(r,s+k-Math.min(2,k),Math.min(2,k));
        double old=mean(r,s,Math.max(1,k-Math.min(2,k)));
        return old<=.0001?1:recent/old;
    }

    private static double location(List<Double>y,int s,int k){
        double min=1,max=0;
        for(int q=s;q<s+k;q++){min=Math.min(min,y.get(q));max=Math.max(max,y.get(q));}
        return clamp((y.get(s+k-1)-min)/Math.max(.004,max-min),0,1);
    }
    private static double bodyHeight(List<Double>range,List<Double>body,int i){return Math.max(.0005,range.get(i)*clip01(body.get(i)));}
    private static double bodyTop(List<Double>y,List<Double>range,List<Double>body,int i){return y.get(i)-bodyHeight(range,body,i)/2.0;}
    private static double bodyBottom(List<Double>y,List<Double>range,List<Double>body,int i){return y.get(i)+bodyHeight(range,body,i)/2.0;}
    private static double top(List<Double>y,List<Double>range,int i){return y.get(i)-range.get(i)/2.0;}
    private static double bottom(List<Double>y,List<Double>range,int i){return y.get(i)+range.get(i)/2.0;}
    private static double mean(List<Double>a,int s,int n){
        int st=Math.max(0,s),e=Math.min(a.size(),st+n); if(e<=st)return 0;
        double z=0; for(int q=st;q<e;q++)z+=a.get(q); return z/(e-st);
    }
    private static int sign(double v){return v>.12?1:v<-.12?-1:0;}
    private static double clip01(double v){return clamp(v,0,1);}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
}
