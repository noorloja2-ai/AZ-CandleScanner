package com.example.floatingcandlescanner;

import java.util.List;
import java.util.Locale;

/**
 * Technical ensemble calculated from exact OHLC values.
 */
public final class TechnicalModel {
    private TechnicalModel() {}

    public static class Frame {
        public final String interval;
        public final double buyProbability;
        public final double rsi;
        public final double adx;
        public final double macdHist;
        public final double atr;
        public final String direction;
        public final String summary;

        Frame(String interval, double buyProbability, double rsi, double adx,
              double macdHist, double atr, String direction, String summary) {
            this.interval = interval;
            this.buyProbability = buyProbability;
            this.rsi = rsi;
            this.adx = adx;
            this.macdHist = macdHist;
            this.atr = atr;
            this.direction = direction;
            this.summary = summary;
        }
    }

    public static class Multi {
        public final Frame m1, m5, m15;

        Multi(Frame m1, Frame m5, Frame m15) {
            this.m1 = m1;
            this.m5 = m5;
            this.m15 = m15;
        }
    }

    public static Multi analyze(MarketDataService.Bundle b) {
        return new Multi(
                analyzeFrame("M1", b.m1),
                analyzeFrame("M5", b.m5),
                analyzeFrame("M15", b.m15));
    }

    private static Frame analyzeFrame(String interval, List<MarketDataService.Candle> c) {
        int n = c.size();
        double[] close = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        double[] open = new double[n];

        for (int i=0;i<n;i++) {
            MarketDataService.Candle x=c.get(i);
            open[i]=x.open; high[i]=x.high; low[i]=x.low; close[i]=x.close;
        }

        double[] e9=ema(close,9), e21=ema(close,21), e50=ema(close,50);
        double[] e200=n>=200?ema(close,200):null;
        double rsi=rsi(close,14);
        double[] macd12=ema(close,12), macd26=ema(close,26);
        double[] macdLine=new double[n];
        for(int i=0;i<n;i++) macdLine[i]=macd12[i]-macd26[i];
        double[] macdSignal=ema(macdLine,9);
        double macdHist=macdLine[n-1]-macdSignal[n-1];

        double atr=atr(high,low,close,14);
        double adx=adx(high,low,close,14);

        double last=close[n-1];
        double range=Math.max(1e-9, high[n-1]-low[n-1]);
        double body=Math.abs(close[n-1]-open[n-1])/range;
        boolean green=close[n-1]>open[n-1];
        boolean red=close[n-1]<open[n-1];

        double support=Double.POSITIVE_INFINITY;
        double resistance=Double.NEGATIVE_INFINITY;
        int lb=Math.min(24,n);
        for(int i=n-lb;i<n;i++){
            support=Math.min(support,low[i]);
            resistance=Math.max(resistance,high[i]);
        }
        double srRange=Math.max(1e-9,resistance-support);
        double pos=(last-support)/srRange;

        double score=0;

        // Trend / moving averages.
        if(e9[n-1]>e21[n-1])score+=0.70;else score-=0.70;
        if(last>e50[n-1])score+=0.60;else score-=0.60;
        if(e21[n-1]>e50[n-1])score+=0.35;else score-=0.35;

        // EMA 9/21 + RSI knowledge layer.  The crossover itself matters most
        // when price also closes on the same side of both averages and the
        // confirmation candle agrees.  This is used as confluence, not as a
        // standalone instruction to force a trade.
        boolean bullCross=e9[n-1]>e21[n-1] && e9[n-2]<=e21[n-2];
        boolean bearCross=e9[n-1]<e21[n-1] && e9[n-2]>=e21[n-2];
        boolean bull921=e9[n-1]>e21[n-1] && last>e9[n-1] && last>e21[n-1]
                && rsi>50 && green;
        boolean bear921=e9[n-1]<e21[n-1] && last<e9[n-1] && last<e21[n-1]
                && rsi<50 && red;
        if(bull921)score+=0.48;
        if(bear921)score-=0.48;
        if(bullCross && bull921)score+=0.42;
        if(bearCross && bear921)score-=0.42;

        // Long-term EMA context from the supplied EMA/Fibonacci reference.
        // It is only used when enough exact candles exist to make EMA-200
        // meaningful; otherwise the older behaviour is left untouched.
        if(e200!=null){
            if(last>e200[n-1])score+=0.16;else score-=0.16;
            if(e50[n-1]>e200[n-1])score+=0.12;else score-=0.12;
        }

        // Momentum.
        if(rsi>=58)score+=0.55;
        else if(rsi<=42)score-=0.55;
        else if(rsi>52)score+=0.20;
        else if(rsi<48)score-=0.20;

        if(macdHist>0)score+=0.50;else score-=0.50;

        // Candle strength.
        if(body>=0.55){
            if(green)score+=0.35;
            if(red)score-=0.35;
        }

        // Trend-strength confirmation.
        double adxWeight=clamp((adx-15.0)/20.0,0.0,1.0);
        if(score>0)score+=0.35*adxWeight;
        else if(score<0)score-=0.35*adxWeight;

        // Don't chase directly into a recent extreme.
        if(pos>0.94 && score>0)score-=0.35;
        if(pos<0.06 && score<0)score+=0.35;

        // Fibonacci + EMA confluence.  We look for a reaction near the
        // 50%, 61.8% or 78.6% retracement of the most recent major swing.
        // A bullish reaction must close above EMA-9; a bearish reaction must
        // close below it.  The bonus stays deliberately modest because swing
        // selection on very short timeframes can be noisy.
        double fib=fibonacciEmaConfluence(high,low,close,open,e9,e21,atr);
        score+=fib;

        // Mild overbought / oversold exhaustion penalty.
        if(rsi>75 && score>0)score-=0.28;
        if(rsi<25 && score<0)score+=0.28;

        double buy=sigmoid(score*1.25);
        buy=clamp(buy,0.08,0.92);

        String direction=buy>=0.57?"UP":buy<=0.43?"DOWN":"FLAT";
        String cross=bullCross?"XUP":bearCross?"XDN":"-";
        String fibTag=fib>.20?"FIB+":fib<-.20?"FIB-":"";
        String summary=String.format(Locale.US,
                "%s RSI %.1f • ADX %.1f • MACD %s • EMA %s %s %s",
                interval,rsi,adx,macdHist>=0?"+":"-",
                e9[n-1]>e21[n-1]?"UP":"DOWN",cross,fibTag).trim();

        return new Frame(interval,buy,rsi,adx,macdHist,atr,direction,summary);
    }


    private static double fibonacciEmaConfluence(double[] high,double[] low,double[] close,
                                                  double[] open,double[] e9,double[] e21,
                                                  double atr){
        int n=close.length;
        int lb=Math.min(80,n-1);
        if(lb<20)return 0;
        int start=n-1-lb;
        double hi=-Double.MAX_VALUE,lo=Double.MAX_VALUE;
        int hiIdx=-1,loIdx=-1;
        for(int i=start;i<n-1;i++){
            if(high[i]>hi){hi=high[i];hiIdx=i;}
            if(low[i]<lo){lo=low[i];loIdx=i;}
        }
        double span=hi-lo;
        if(!(span>0))return 0;

        double last=close[n-1];
        boolean green=close[n-1]>open[n-1];
        boolean red=close[n-1]<open[n-1];
        double tolerance=Math.max(Math.max(atr*.35,span*.012),1e-9);
        double[] ratios={.50,.618,.786};
        double best=0;

        if(loIdx>=0&&hiIdx>=0&&loIdx<hiIdx){
            // Recent impulse was upward; look for a bullish pullback reaction.
            for(double ratio:ratios){
                double level=hi-span*ratio;
                if(Math.abs(last-level)<=tolerance && green && last>e9[n-1]){
                    double strength=ratio==.618?.36:ratio==.786?.32:.28;
                    if(e9[n-1]>e21[n-1])strength+=.08;
                    best=Math.max(best,strength);
                }
            }
        }else if(hiIdx>=0&&loIdx>=0&&hiIdx<loIdx){
            // Recent impulse was downward; look for a bearish pullback reaction.
            for(double ratio:ratios){
                double level=lo+span*ratio;
                if(Math.abs(last-level)<=tolerance && red && last<e9[n-1]){
                    double strength=ratio==.618?.36:ratio==.786?.32:.28;
                    if(e9[n-1]<e21[n-1])strength+=.08;
                    best=Math.min(best,-strength);
                }
            }
        }
        return best;
    }

    private static double[] ema(double[] x,int p){
        int n=x.length;
        double[] out=new double[n];
        double k=2.0/(p+1.0);
        out[0]=x[0];
        for(int i=1;i<n;i++)out[i]=x[i]*k+out[i-1]*(1-k);
        return out;
    }

    private static double rsi(double[] x,int p){
        if(x.length<=p)return 50;
        double gain=0,loss=0;
        for(int i=1;i<=p;i++){
            double d=x[i]-x[i-1];
            if(d>=0)gain+=d;else loss-=d;
        }
        gain/=p;loss/=p;
        double last=loss==0?100:100-100/(1+gain/loss);
        for(int i=p+1;i<x.length;i++){
            double d=x[i]-x[i-1];
            gain=(gain*(p-1)+Math.max(0,d))/p;
            loss=(loss*(p-1)+Math.max(0,-d))/p;
            last=loss==0?100:100-100/(1+gain/loss);
        }
        return last;
    }

    private static double atr(double[] h,double[] l,double[] c,int p){
        int n=c.length;
        if(n<2)return 0;
        double[] tr=new double[n];
        tr[0]=h[0]-l[0];
        for(int i=1;i<n;i++)
            tr[i]=Math.max(h[i]-l[i],Math.max(Math.abs(h[i]-c[i-1]),Math.abs(l[i]-c[i-1])));
        double a=0;
        int seed=Math.min(p,n);
        for(int i=0;i<seed;i++)a+=tr[i];
        a/=seed;
        for(int i=seed;i<n;i++)a=(a*(p-1)+tr[i])/p;
        return a;
    }

    private static double adx(double[] h,double[] l,double[] c,int p){
        int n=c.length;
        if(n<2*p+2)return 0;

        double[] tr=new double[n],plus=new double[n],minus=new double[n];
        for(int i=1;i<n;i++){
            double up=h[i]-h[i-1];
            double down=l[i-1]-l[i];
            plus[i]=(up>down&&up>0)?up:0;
            minus[i]=(down>up&&down>0)?down:0;
            tr[i]=Math.max(h[i]-l[i],Math.max(Math.abs(h[i]-c[i-1]),Math.abs(l[i]-c[i-1])));
        }

        double smTr=0,smPlus=0,smMinus=0;
        for(int i=1;i<=p;i++){smTr+=tr[i];smPlus+=plus[i];smMinus+=minus[i];}

        double[] dx=new double[n];
        for(int i=p;i<n;i++){
            if(i>p){
                smTr=smTr-smTr/p+tr[i];
                smPlus=smPlus-smPlus/p+plus[i];
                smMinus=smMinus-smMinus/p+minus[i];
            }
            double pdi=smTr==0?0:100*smPlus/smTr;
            double mdi=smTr==0?0:100*smMinus/smTr;
            dx[i]=(pdi+mdi)==0?0:100*Math.abs(pdi-mdi)/(pdi+mdi);
        }

        double a=0;
        int start=p,end=Math.min(n,p*2);
        int count=0;
        for(int i=start;i<end;i++){a+=dx[i];count++;}
        a=count==0?0:a/count;

        for(int i=end;i<n;i++)a=(a*(p-1)+dx[i])/p;
        return a;
    }

    private static double sigmoid(double x){return 1.0/(1.0+Math.exp(-x));}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
}
