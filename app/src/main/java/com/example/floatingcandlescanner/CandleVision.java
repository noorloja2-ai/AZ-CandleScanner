package com.example.floatingcandlescanner;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.ArrayList;
import java.util.List;

public class CandleVision {
    public static class BoardState {
        public final double trend, momentum, lastDirection, body, upperWick, lowerWick;
        public final double pricePosition, volatilityRatio, sequenceBias, acceleration;
        public final double recentTwoDirection;

        BoardState(double trend,double momentum,double lastDirection,double body,
                   double upperWick,double lowerWick,double pricePosition,double volatilityRatio,
                   double sequenceBias,double acceleration,double recentTwoDirection){
            this.trend=trend; this.momentum=momentum; this.lastDirection=lastDirection;
            this.body=body; this.upperWick=upperWick; this.lowerWick=lowerWick;
            this.pricePosition=pricePosition; this.volatilityRatio=volatilityRatio;
            this.sequenceBias=sequenceBias; this.acceleration=acceleration;
            this.recentTwoDirection=recentTwoDirection;
        }

        /** Compact quantized state so similar broker-board histories share learning. */
        public String bucket(){
            String t = trend>.28?"TU2":trend>.10?"TU1":trend<-.28?"TD2":trend<-.10?"TD1":"TF";
            String m = momentum>.28?"MU":momentum<-.28?"MD":"MF";
            String d = lastDirection>.16?"GU":lastDirection<-.16?"RD":"DX";
            String shape;
            if(body<.20) shape="DOJI";
            else if(lowerWick>.46 && lowerWick>upperWick*1.55) shape="HAM";
            else if(upperWick>.46 && upperWick>lowerWick*1.55) shape="STAR";
            else if(body>.68) shape="BODY";
            else shape="MIX";
            String p = pricePosition<.28?"LOW":pricePosition>.72?"HIGH":"MID";
            String v = volatilityRatio>1.45?"VH":volatilityRatio<.70?"VL":"VN";
            return t+"_"+m+"_"+d+"_"+shape+"_"+p+"_"+v;
        }
    }

    public static class Analysis {
        public final SignalResult[] horizons;
        public final int detectedBins;
        public final double latestY;
        public final boolean valid;
        public final BoardState boardState;

        public Analysis(SignalResult[] h, int d, double latestY, boolean valid, BoardState boardState) {
            horizons = h;
            detectedBins = d;
            this.latestY = latestY;
            this.valid = valid;
            this.boardState = boardState;
        }
    }

    public static Analysis analyze(Bitmap screen, int l, int t, int r, int b,
                                   int theme, int sensitivity,
                                   OnlineLearner learner, boolean highAccuracy) {
        if (screen == null) return empty();

        int x0 = clamp(screen.getWidth()*l/100, 0, screen.getWidth()-2);
        int y0 = clamp(screen.getHeight()*t/100, 0, screen.getHeight()-2);
        int x1 = clamp(screen.getWidth()*r/100, x0+1, screen.getWidth());
        int y1 = clamp(screen.getHeight()*b/100, y0+1, screen.getHeight());

        Bitmap crop;
        try { crop = Bitmap.createBitmap(screen, x0, y0, x1-x0, y1-y0); }
        catch (Exception e) { return empty(); }

        // Keep broker controls (large BUY/SELL buttons) out of the visual candle model.
        // The lower panel is detected from wide saturated red/green rows rather than
        // using a broker-specific fixed pixel coordinate.
        int scanHeight = detectChartBottom(crop);

        int activeTheme = theme;
        if (theme == 0) activeTheme = chooseAutoBullTheme(crop, scanHeight);

        int startX = crop.getWidth()/5;
        int bins = 32;

        List<Double> dirs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Double> ranges = new ArrayList<>();
        List<Double> densities = new ArrayList<>();
        List<Double> bodyRatios = new ArrayList<>();
        List<Double> upperWicks = new ArrayList<>();
        List<Double> lowerWicks = new ArrayList<>();

        float[] hsv = new float[3];

        for (int bi=0; bi<bins; bi++) {
            int bx0 = startX + (crop.getWidth()-startX)*bi/bins;
            int bx1 = startX + (crop.getWidth()-startX)*(bi+1)/bins;

            long bull=0, bear=0, bullY=0, bearY=0;
            int minY=scanHeight, maxY=-1, colored=0, sampled=0;

            int sx = Math.max(1, (bx1-bx0)/12);
            int sy = Math.max(1, scanHeight/150);
            int rowCount=(scanHeight+sy-1)/sy;
            int[] bullRows=new int[rowCount];
            int[] bearRows=new int[rowCount];

            for (int x=bx0; x<bx1; x+=sx) {
                int ri=0;
                for (int y=0; y<scanHeight; y+=sy,ri++) {
                    sampled++;
                    Color.colorToHSV(crop.getPixel(x,y), hsv);
                    if (hsv[1] < .30f || hsv[2] < .26f) continue;

                    boolean red = hsv[0] <= 24f || hsv[0] >= 336f;
                    boolean green = hsv[0] >= 68f && hsv[0] <= 172f;
                    boolean blue = hsv[0] >= 174f && hsv[0] <= 252f;
                    // IMPORTANT: blue broker UI/grid pixels must not be treated as
                    // bullish candles. AUTO selects one bullish candle colour first.
                    boolean bullish = activeTheme==2 ? blue : green;

                    if (bullish || red) {
                        colored++;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                    if (bullish) { bull++; bullY += y; bullRows[ri]++; }
                    if (red) { bear++; bearY += y; bearRows[ri]++; }
                }
            }

            long total = bull + bear;
            if (total < 10) continue;

            double direction = (double)(bull-bear)/total;
            boolean dominantBull=bull>=bear;
            double cy = dominantBull && bull>0 ? (double)bullY/bull
                    : bear>0 ? (double)bearY/bear : scanHeight/2.0;
            double range = maxY >= minY
                    ? (double)(maxY-minY+sy)/Math.max(1.0,scanHeight) : 0.0;
            double density = (double)colored / Math.max(1, sampled);

            // Estimate candle body vs wicks. A body occupies many horizontal
            // samples on the same row, while a wick usually occupies only one
            // or two. This makes Hammer/Shooting-Star/Doji/Marubozu scoring
            // possible without reading broker OHLC values.
            int[] rows=dominantBull?bullRows:bearRows;
            int maxHits=0;
            for(int hits:rows) if(hits>maxHits)maxHits=hits;
            int bodyThreshold=Math.max(2,(int)Math.ceil(maxHits*.52));
            int bodyTop=scanHeight,bodyBottom=-1;
            for(int ri=0;ri<rows.length;ri++){
                if(rows[ri]>=bodyThreshold){
                    int yy=ri*sy;
                    if(yy<bodyTop)bodyTop=yy;
                    if(yy>bodyBottom)bodyBottom=yy;
                }
            }
            double bodyRatio,upperRatio,lowerRatio;
            if(maxY>=minY && bodyBottom>=bodyTop){
                double full=Math.max(1.0,maxY-minY+sy);
                bodyRatio=Math.min(1.0,Math.max(.02,(bodyBottom-bodyTop+sy)/full));
                upperRatio=Math.max(0.0,(bodyTop-minY)/full);
                lowerRatio=Math.max(0.0,(maxY-bodyBottom)/full);
                double sum=bodyRatio+upperRatio+lowerRatio;
                if(sum>0){bodyRatio/=sum;upperRatio/=sum;lowerRatio/=sum;}
            }else{
                // Conservative fallback when anti-aliasing makes the body row
                // impossible to isolate. Directional pixel balance is only a
                // rough body proxy, so keep the geometry low-confidence.
                bodyRatio=Math.max(.08,Math.min(.70,Math.abs(direction)*.62+.10));
                double wick=(1.0-bodyRatio)/2.0;
                upperRatio=wick;lowerRatio=wick;
            }

            dirs.add(direction);
            ys.add(cy / Math.max(1.0, scanHeight));
            ranges.add(range);
            densities.add(density);
            bodyRatios.add(bodyRatio);
            upperWicks.add(upperRatio);
            lowerWicks.add(lowerRatio);
        }
        crop.recycle();

        if (dirs.size() < 8) return empty();

        // AUTO captures just after a candle boundary. The newly-opened live candle
        // can appear as a tiny, low-density sliver at the far-right edge. When that
        // geometry is clearly immature, remove it so the decision engine ends on
        // the candle that actually finished. Never remove a normal-sized last bar.
        dropClearlyIncompleteNewest(dirs,ys,ranges,densities,bodyRatios,upperWicks,lowerWicks);
        if (dirs.size() < 8) return empty();

        double latestY = ys.get(ys.size()-1);
        BoardState boardState = buildBoardState(dirs,ys,ranges,bodyRatios,upperWicks,lowerWicks);
        return new Analysis(
                TradingBrain.predict(
                        dirs, ys, ranges, densities,
                        bodyRatios, upperWicks, lowerWicks,
                        sensitivity, learner, highAccuracy),
                dirs.size(), latestY, true, boardState);
    }

    /**
     * Detect a wide coloured broker action panel near the bottom of the selected
     * region. Candle bodies occupy only a small fraction of a row; BUY/SELL
     * buttons occupy a large fraction. This prevents payout/action controls from
     * becoming fake candle evidence.
     */
    private static int detectChartBottom(Bitmap crop) {
        int h=crop.getHeight(), w=crop.getWidth();
        if(h<80 || w<80) return h;
        float[] hsv=new float[3];
        int run=0;
        int yStart=(int)(h*.55);
        int yStep=Math.max(2,h/240);
        int xStep=Math.max(2,w/180);

        for(int y=yStart;y<h;y+=yStep){
            int hit=0,total=0;
            for(int x=0;x<w;x+=xStep){
                total++;
                Color.colorToHSV(crop.getPixel(x,y),hsv);
                if(hsv[1] < .45f || hsv[2] < .35f) continue;
                boolean red=hsv[0] <= 24f || hsv[0] >= 336f;
                boolean green=hsv[0] >= 68f && hsv[0] <= 172f;
                if(red||green) hit++;
            }
            double ratio=(double)hit/Math.max(1,total);
            if(ratio>=.28){
                run++;
                if(run>=3){
                    int margin=Math.max(4,(int)(h*.018));
                    return Math.max((int)(h*.55),y-2*yStep-margin);
                }
            }else run=0;
        }
        return h;
    }

    /**
     * AUTO prefers green/red charts and only switches to blue/red when there is
     * strong evidence of bright saturated blue candle bodies. This is deliberately
     * conservative because many broker interfaces use blue for grids, labels and UI.
     */
    private static int chooseAutoBullTheme(Bitmap crop,int scanHeight){
        float[] hsv=new float[3];
        int green=0,blue=0;
        int xStep=Math.max(2,crop.getWidth()/180);
        int yStep=Math.max(2,scanHeight/180);
        for(int x=crop.getWidth()/5;x<crop.getWidth();x+=xStep){
            for(int y=0;y<scanHeight;y+=yStep){
                Color.colorToHSV(crop.getPixel(x,y),hsv);
                if(hsv[1] < .55f || hsv[2] < .45f) continue;
                if(hsv[0] >= 68f && hsv[0] <= 172f) green++;
                else if(hsv[0] >= 174f && hsv[0] <= 252f) blue++;
            }
        }
        // Require a clear blue majority before choosing blue. Otherwise use the
        // common green/red candle scheme and avoid false BUY bias from blue UI.
        return (blue>=120 && blue>green*2.2) ? 2 : 1;
    }

    private static void dropClearlyIncompleteNewest(
            List<Double>dirs,List<Double>ys,List<Double>ranges,List<Double>densities,
            List<Double>body,List<Double>upper,List<Double>lower){
        int n=ranges.size();
        if(n<9)return;
        int count=Math.min(5,n-1);
        double meanRange=0,meanDensity=0;
        for(int i=n-1-count;i<n-1;i++){
            meanRange+=ranges.get(i);
            meanDensity+=densities.get(i);
        }
        meanRange/=count; meanDensity/=count;
        double lastRange=ranges.get(n-1), lastDensity=densities.get(n-1);
        double lastBody=body.get(n-1);
        boolean tinyRange=meanRange>.0001 && lastRange<meanRange*.38;
        boolean sparse=meanDensity>.0001 && lastDensity<meanDensity*.48;
        boolean immature=(tinyRange && lastBody<.42) || (tinyRange && sparse);
        if(!immature)return;
        int i=n-1;
        dirs.remove(i);ys.remove(i);ranges.remove(i);densities.remove(i);
        body.remove(i);upper.remove(i);lower.remove(i);
    }


    private static BoardState buildBoardState(List<Double> dirs,List<Double> ys,List<Double> ranges,
                                              List<Double> bodies,List<Double> upper,List<Double> lower){
        int n=dirs.size();
        int a=Math.max(0,n-10);
        double trend=0,tw=0;
        for(int i=a;i<n;i++){ double w=.55+.45*(i-a+1.0)/Math.max(1,n-a); trend+=dirs.get(i)*w; tw+=w; }
        trend=tw==0?0:trend/tw;
        int m0=Math.max(0,n-3); double mom=0;
        for(int i=m0;i<n;i++)mom+=dirs.get(i);
        mom/=Math.max(1,n-m0);
        double last=dirs.get(n-1),body=bodies.get(n-1),uw=upper.get(n-1),lw=lower.get(n-1);

        int p0=Math.max(0,n-12); double minY=1,maxY=0;
        for(int i=p0;i<n;i++){double y=ys.get(i); if(y<minY)minY=y; if(y>maxY)maxY=y;}
        double pos=(maxY-minY)<.0001?.5:(maxY-ys.get(n-1))/(maxY-minY);
        pos=Math.max(0,Math.min(1,pos));

        int r0=Math.max(0,n-7); double avg=0; int c=0;
        for(int i=r0;i<n-1;i++){avg+=ranges.get(i);c++;}
        avg=c==0?ranges.get(n-1):avg/c;
        double vr=avg<.0001?1:ranges.get(n-1)/avg;

        int s0=Math.max(0,n-5); double seq=0,sw=0;
        for(int i=s0;i<n;i++){
            double w=.7+.3*(i-s0+1.0)/Math.max(1,n-s0);
            seq+=dirs.get(i)*w; sw+=w;
        }
        seq=sw==0?0:seq/sw;

        int recent0=Math.max(0,n-2); double recent=0; int rc=0;
        for(int i=recent0;i<n;i++){recent+=dirs.get(i);rc++;}
        recent/=Math.max(1,rc);
        int prior1=recent0, prior0=Math.max(0,prior1-3); double prior=0; int pc=0;
        for(int i=prior0;i<prior1;i++){prior+=dirs.get(i);pc++;}
        prior/=Math.max(1,pc);
        double accel=pc==0?0:Math.max(-1,Math.min(1,recent-prior));

        return new BoardState(trend,mom,last,body,uw,lw,pos,vr,seq,accel,recent);
    }

    private static Analysis empty() {
        SignalResult[] r = new SignalResult[5];
        for (int i=0; i<5; i++) r[i] = SignalResult.waitResult();
        return new Analysis(r, 0, 0.5, false, null);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
