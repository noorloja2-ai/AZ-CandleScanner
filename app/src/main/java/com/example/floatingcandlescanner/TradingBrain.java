package com.example.floatingcandlescanner;

import java.util.List;
import java.util.Locale;

/**
 * Visual "Trading Brain".
 *
 * It uses chart-derived proxies for:
 * - candlestick impulse / doji / engulfing / rejection
 * - trend and market structure
 * - support/resistance proximity
 * - breakout and pullback behaviour
 * - momentum and acceleration
 * - volatility / choppiness
 * - continuation vs reversal
 * - multi-expert consensus
 * - asset-specific online calibration
 *
 * Because it reads pixels, these are visual proxies, not exact OHLC indicators.
 */
public final class TradingBrain {
    private TradingBrain(){}

    public static SignalResult[] predict(
            List<Double> dirs,
            List<Double> ys,
            List<Double> ranges,
            List<Double> densities,
            List<Double> bodyRatios,
            List<Double> upperWicks,
            List<Double> lowerWicks,
            int sensitivity,
            OnlineLearner learner,
            boolean highAccuracy) {

        // Keep broader context, but every horizon explicitly gives the newest
        // five completed visual candle segments much more influence. This avoids
        // an old trend dominating a fresh reversal.
        int[] windows = {6,8,10,12,14};
        SignalResult[] out = new SignalResult[5];

        for (int h=0; h<5; h++) {
            int k = Math.min(windows[h], dirs.size());
            int s = dirs.size()-k;
            out[h] = one(dirs,ys,ranges,densities,bodyRatios,upperWicks,lowerWicks,
                    s,k,h,sensitivity,learner,highAccuracy);
        }
        return out;
    }

    private static SignalResult one(
            List<Double>d,List<Double>y,List<Double>r,List<Double>density,
            List<Double>body,List<Double>upper,List<Double>lower,
            int s,int k,int h,int sensitivity,
            OnlineLearner learner,boolean highAccuracy){

        double momentum = weighted(d,s,k);
        int recent5K=Math.min(5,k);
        int recent5S=s+k-recent5K;
        double recent5=weighted(d,recent5S,recent5K);
        int newest2K=Math.min(2,k);
        int newest2S=s+k-newest2K;
        double newest2=weighted(d,newest2S,newest2K);
        double shortBias=clamp(.68*recent5+.32*newest2,-1,1);
        double recent = weighted(d,Math.max(s,s+k-Math.min(3,k)),Math.min(3,k));
        double trend = clamp(-slope(y,s,k)*5.2,-1,1);
        double structure = marketStructure(y,s,k);
        double consistency = directionalConsistency(d,s,k);
        double acceleration = acceleration(d,s,k);
        double dirVol = std(d,s,k);
        double priceVol = returnStd(y,s,k);
        double avgRange = mean(r,s,k);
        double latestRange = r.get(s+k-1);
        double latestDir = d.get(s+k-1);
        double prevDir = k>=2 ? d.get(s+k-2) : 0;
        double densityNow = density.get(s+k-1);

        double supportResistance = supportResistanceScore(y,s,k);
        double breakout = breakoutScore(y,d,s,k);
        double pullback = pullbackScore(y,d,s,k,trend);
        double engulf = engulfingProxy(d,r,s,k);
        double rejection = rejectionProxy(d,r,density,s,k);
        double doji = dojiProxy(d,r,s,k);
        double streak = streakScore(d,s,k);
        double exhaustion = exhaustion(d,s,k);
        PatternIntelligence.Result pattern = PatternIntelligence.analyze(
                d,y,r,body,upper,lower,s,k,trend);
        StrategyEngine.Result strategy = StrategyEngine.analyze(
                d,y,r,body,upper,lower,s,k,trend);
        NextCandleKnowledge.Result next = NextCandleKnowledge.analyze(
                d,y,r,body,upper,lower,s,k,trend);
        AdvancedConfluenceEngine.Result advanced = AdvancedConfluenceEngine.analyze(
                d,y,r,body,upper,lower,s,k,trend);
        PriceActionStructureEngine.Result priceAction = PriceActionStructureEngine.analyze(
                d,y,r,body,upper,lower,s,k,trend);
        ReferencePatternEngine.Result reference = ReferencePatternEngine.analyze(
                d,y,r,body,upper,lower,s,k,trend);
        CandlestickKnowledgeEngine.Result candleKnowledge = CandlestickKnowledgeEngine.analyze(
                d,y,r,body,upper,lower,s,k,trend);
        ProfessionalTechnicalAnalysisEngine.Result professional = ProfessionalTechnicalAnalysisEngine.analyze(
                d,y,r,body,upper,lower,s,k,trend);
        LiquidityFvgEngine.Result liquidity = LiquidityFvgEngine.analyze(
                d,y,r,body,upper,lower,s,k,trend);
        StructureConfirmationEngine.Result confirmation = StructureConfirmationEngine.analyze(
                d,y,r,body,upper,lower,s,k,trend);
        UploadedKnowledgeEngine.Result uploaded = UploadedKnowledgeEngine.analyze(
                d,y,r,body,upper,lower,s,k,trend);
        CandlestickBibleEngine.Result bible = CandlestickBibleEngine.analyze(
                d,y,r,body,upper,lower,s,k,trend);
        HistoricalEntryEngine.Result history = HistoricalEntryEngine.analyze(
                d,y,r,body,upper,lower,s,k,trend);
        PressurePatternEngine.Result pressure = PressurePatternEngine.analyze(
                d,y,r,body,upper,lower,s,k,trend);

        /*
         * Twelve-method M1 confluence model.  These are deliberately grouped
         * into independent families so the same candle shape is not counted
         * repeatedly as several confirmations.  Pixel-derived EMA/RSI/Fibonacci
         * values are context proxies; they are never presented as exact broker
         * indicator readings.
         */
        double mStructure = clamp(.55*trend + .45*structure,-1,1);                 // 1
        double mLevel = clamp(supportResistance,-1,1);                            // 2
        double mCandle = clamp((pattern.directionalScore
                + candleKnowledge.directionalScore + bible.directionalScore)/3.0,-1,1); // 3
        double mPriceAction = clamp(priceAction.directionalScore,-1,1);            // 4
        double mBreakRetest = clamp(.62*breakout + .38*pullback,-1,1);             // 5
        double mLiquidity = clamp(liquidity.directionalScore,-1,1);                // 6
        double mBosChoch = clamp(confirmation.directionalScore,-1,1);              // 7
        double mEmaContext = clamp(.58*trend + .42*shortBias,-1,1);                // 8
        double mRsiMomentum = clamp(.62*momentum + .38*acceleration
                - .22*Math.signum(momentum)*exhaustion,-1,1);                      // 9
        double mFibContext = clamp(advanced.directionalScore,-1,1);                // 10
        double mTimeframe = clamp(.45*trend + .35*structure + .20*recent5,-1,1);  // 11
        double mLearned = learner==null ? 0.0 :
                (learner.recentPerformanceHealthy(h) ? shortBias*.55 : -shortBias*.30); // 12

        double methodScore = clamp(
                .13*mStructure + .10*mLevel + .11*mCandle + .09*mPriceAction
                + .09*mBreakRetest + .09*mLiquidity + .10*mBosChoch
                + .07*mEmaContext + .06*mRsiMomentum + .05*mFibContext
                + .07*mTimeframe + .04*mLearned,-1,1);
        int methodConfirmations = confirmationCount(Math.signum(methodScore), .16,
                mStructure,mLevel,mCandle,mPriceAction,mBreakRetest,mLiquidity,
                mBosChoch,mEmaContext,mRsiMomentum,mFibContext,mTimeframe,mLearned);
        boolean methodAgreement = Math.abs(methodScore)>=.22 && methodConfirmations>=5;
        boolean methodStrong = Math.abs(methodScore)>=.38 && methodConfirmations>=7;
        double patternSrConfluence = 0.0;
        if(Math.signum(pattern.reversalScore)!=0 &&
                Math.signum(pattern.reversalScore)==Math.signum(supportResistance))
            patternSrConfluence=Math.abs(supportResistance);

        // Expert 1: trend continuation. Older trend remains useful, but it is
        // deliberately weaker than the newest-candle model for next-candle calls.
        double eTrend = sigmoid(
                1.45*trend + 1.10*structure + .85*consistency +
                .55*momentum + .45*pullback + .40*breakout +
                .20*streak + .48*pattern.continuationScore + .42*strategy.directionalScore
                - .50*dirVol - .30*exhaustion - .16*pattern.indecision
                - .22*strategy.riskPenalty);

        // Expert 2: newest five candles. This is the primary next-candle expert.
        double eCandle = sigmoid(
                2.20*shortBias + .75*acceleration + .70*engulf +
                .45*rejection + .35*latestDir + .25*streak +
                1.34*pattern.directionalScore + .88*strategy.directionalScore
                + 1.10*next.directionalScore + .88*advanced.directionalScore
                + .92*priceAction.directionalScore + .82*reference.directionalScore
                + .72*candleKnowledge.directionalScore + .96*professional.directionalScore
                + .62*liquidity.directionalScore + .70*confirmation.directionalScore
                + .58*uploaded.directionalScore + .44*bible.directionalScore
                + .86*history.directionalScore + .92*pressure.directionalScore
                + .28*patternSrConfluence*Math.signum(pattern.reversalScore)
                - .66*pattern.indecision - .45*doji - .25*dirVol
                - .24*strategy.riskPenalty - .32*next.uncertainty
                - .20*candleKnowledge.uncertainty - .12*bible.uncertainty
                - .10*pressure.uncertainty - .08*bible.riskPenalty);

        // Expert 3: structure and S/R.
        double eStructure = sigmoid(
                1.20*structure + .95*trend + .75*breakout +
                .55*supportResistance + .35*pullback + .36*strategy.directionalScore
                + .44*next.directionalScore + .48*advanced.directionalScore
                + .58*priceAction.directionalScore + .54*reference.directionalScore
                + .36*candleKnowledge.directionalScore + .88*professional.directionalScore
                + .72*liquidity.directionalScore + .82*confirmation.directionalScore
                + .74*uploaded.directionalScore + .55*bible.directionalScore
                + .72*history.directionalScore + .68*pressure.directionalScore -
                .40*priceVol - .30*exhaustion - .20*strategy.riskPenalty
                - .18*next.uncertainty - .16*advanced.uncertainty
                - .10*candleKnowledge.uncertainty - .10*bible.uncertainty
                - .08*pressure.uncertainty - .10*bible.riskPenalty);

        // Expert 4: reversal. The newest five candles can override the older
        // trend when a real reversal develops.
        double reversalRaw =
                -.70*trend + 1.00*rejection + .80*engulf +
                .60*acceleration - .60*exhaustion + .75*shortBias +
                1.48*pattern.reversalScore + .92*strategy.directionalScore
                + .82*next.directionalScore + .76*advanced.directionalScore
                + .84*priceAction.directionalScore + .78*reference.directionalScore
                + .78*candleKnowledge.directionalScore + .86*professional.directionalScore
                + .66*liquidity.directionalScore + .78*confirmation.directionalScore
                + .68*uploaded.directionalScore + .50*bible.directionalScore
                + .82*history.directionalScore + .90*pressure.directionalScore +
                .34*patternSrConfluence*Math.signum(pattern.reversalScore)
                - .18*strategy.riskPenalty - .20*next.uncertainty
                - .12*candleKnowledge.uncertainty - .12*bible.uncertainty
                - .10*pressure.uncertainty - .08*bible.riskPenalty;
        double eReversal = sigmoid(reversalRaw);

        // Longer horizons trust structure/trend more, but even M5 keeps a
        // meaningful recent-candle component.
        double wTrend = .24 + h*.035;
        double wStructure = .21 + h*.030;
        double wCandle = .39 - h*.040;
        double wReversal = 1.0 - wTrend - wStructure - wCandle;

        double raw = wTrend*eTrend + wStructure*eStructure
                + wCandle*eCandle + wReversal*eReversal;

        double recentModel=sigmoid(
                2.80*shortBias + .75*acceleration +
                .45*engulf + .25*rejection + .95*pattern.directionalScore
                + .70*strategy.directionalScore + 1.25*next.directionalScore
                + .92*advanced.directionalScore + 1.00*priceAction.directionalScore
                + .88*reference.directionalScore + .82*candleKnowledge.directionalScore
                + .96*professional.directionalScore + .72*liquidity.directionalScore + .82*confirmation.directionalScore
                + .64*uploaded.directionalScore + .42*bible.directionalScore
                + .92*history.directionalScore + 1.02*pressure.directionalScore
                + 1.05*methodScore
                - .18*strategy.riskPenalty - .26*next.uncertainty - .20*advanced.uncertainty
                - .14*candleKnowledge.uncertainty - .10*bible.uncertainty - .10*pressure.uncertainty - .08*bible.riskPenalty);
        double shortWeight=.46-h*.04;
        raw=(1.0-shortWeight)*raw+shortWeight*recentModel;

        double maxE=max4(eTrend,eCandle,eStructure,eReversal);
        double minE=min4(eTrend,eCandle,eStructure,eReversal);
        double disagreement=maxE-minE;

        double quality = clamp(
                1.0
                - .40*dirVol
                - .30*priceVol*8.0
                - .52*disagreement
                - .20*doji
                - .24*pattern.indecision
                + .08*pattern.strength
                + .09*strategy.confidence
                + .10*next.confidence + .10*advanced.confidence + .11*priceAction.confidence
                + .09*reference.confidence + .10*candleKnowledge.confidence + .11*professional.confidence
                + .08*liquidity.confidence + .09*confirmation.confidence + .07*uploaded.confidence + .05*bible.confidence + .09*history.confidence + .10*pressure.confidence + .05*patternSrConfluence
                + (methodStrong ? .10 : methodAgreement ? .05 : -.06)
                - .20*strategy.riskPenalty - .07*bible.riskPenalty
                - .18*next.uncertainty - .14*advanced.uncertainty - .16*priceAction.uncertainty
                - .13*reference.uncertainty - .15*candleKnowledge.uncertainty - .17*professional.uncertainty
                - .10*liquidity.uncertainty - .11*confirmation.uncertainty - .09*uploaded.uncertainty - .07*bible.uncertainty
                - .10*history.uncertainty - .11*pressure.uncertainty,
                .25,1.0);

        raw=.5+(raw-.5)*quality;
        raw=clamp(raw,.08,.92);

        double buy=learner==null?raw:learner.calibrate(h,raw);
        buy=clamp(buy,.10,.90);
        double sell=1-buy;

        boolean allBull = eTrend>.5 && eCandle>.5 && eStructure>.5;
        boolean allBear = eTrend<.5 && eCandle<.5 && eStructure<.5;
        boolean coreAgreement = allBull || allBear;

        boolean patternOverride = Math.abs(pattern.reversalScore)>=.70
                && Math.signum(pattern.reversalScore)==Math.signum(shortBias)
                && Math.abs(shortBias)>.14;
        boolean strategyOverride = strategy.strong()
                && Math.signum(strategy.directionalScore)==Math.signum(shortBias)
                && Math.abs(shortBias)>.10;
        boolean nextCandleOverride = next.strong()
                && Math.signum(next.directionalScore)==Math.signum(shortBias)
                && Math.abs(shortBias)>.08;
        boolean advancedOverride = advanced.strong()
                && Math.signum(advanced.directionalScore)==Math.signum(shortBias)
                && Math.abs(shortBias)>.07;
        boolean priceActionOverride = priceAction.strong()
                && Math.signum(priceAction.directionalScore)==Math.signum(shortBias)
                && Math.abs(shortBias)>.06;
        boolean referenceOverride = reference.strong()
                && Math.signum(reference.directionalScore)==Math.signum(shortBias)
                && Math.abs(shortBias)>.05;
        boolean candleKnowledgeOverride = candleKnowledge.strong()
                && Math.signum(candleKnowledge.directionalScore)==Math.signum(shortBias)
                && Math.abs(shortBias)>.05;
        boolean professionalOverride = professional.strong()
                && (professional.falseBreakout || Math.signum(professional.directionalScore)==Math.signum(shortBias))
                && Math.abs(professional.directionalScore)>.50;
        boolean liquidityOverride = liquidity.strong()
                && Math.signum(liquidity.directionalScore)==Math.signum(shortBias)
                && Math.abs(shortBias)>.04;
        boolean confirmationOverride = confirmation.strong()
                && Math.signum(confirmation.directionalScore)==Math.signum(shortBias)
                && Math.abs(shortBias)>.04;
        boolean uploadedOverride = uploaded.strong()
                && Math.signum(uploaded.directionalScore)==Math.signum(shortBias)
                && Math.abs(shortBias)>.04;
        boolean bibleOverride = bible.strong()
                && (bible.falseBreakout || bible.keyLevel ||
                    Math.signum(bible.directionalScore)==Math.signum(shortBias))
                && Math.abs(bible.directionalScore)>.46
                && Math.abs(shortBias)>.035;
        boolean historyOverride = history.strong()
                && (Math.signum(history.directionalScore)==Math.signum(shortBias)
                    || (history.keyLevel && Math.abs(history.directionalScore)>.68));
        boolean pressureOverride = pressure.strong()
                && (Math.signum(pressure.directionalScore)==Math.signum(shortBias)
                    || (pressure.keyLevel && pressure.reversal && Math.abs(pressure.directionalScore)>.64));

        boolean conflict =
                !patternOverride && !strategyOverride && !nextCandleOverride && !advancedOverride && !priceActionOverride && !referenceOverride && !candleKnowledgeOverride && !professionalOverride && !liquidityOverride && !confirmationOverride && !uploadedOverride && !bibleOverride && !historyOverride && !pressureOverride &&
                Math.signum(momentum)!=0 &&
                Math.signum(trend)!=0 &&
                Math.signum(momentum)!=Math.signum(trend) &&
                Math.abs(momentum)>.18 && Math.abs(trend)>.18;

        boolean choppy = dirVol > .62 || priceVol > .055;
        boolean lowQuality = densityNow < .025 || quality < .43;
        boolean healthy = learner==null || learner.recentPerformanceHealthy(h);

        double threshold;
        if(highAccuracy){
            threshold=sensitivity==2?.85:sensitivity==0?.77:.81;
        }else{
            threshold=sensitivity==2?.73:sensitivity==0?.60:.66;
        }

        double maxP=Math.max(buy,sell);
        String label="WAIT";

        boolean consensusOk = !highAccuracy || patternOverride || strategyOverride || nextCandleOverride || advancedOverride || priceActionOverride || referenceOverride || candleKnowledgeOverride || professionalOverride || liquidityOverride || confirmationOverride || uploadedOverride || bibleOverride || historyOverride || pressureOverride ||
                methodStrong || (coreAgreement && disagreement <= .20);
        // High Accuracy now uses the requested 3-factor entry gate: candle/setup,
        // level/context and trend/momentum confirmation from recent history.
        boolean historyEntryGate = !highAccuracy || methodStrong || history.confirmations >= 3 || historyOverride
                || (pressure.confirmations >= 3 && pressure.directional()
                    && (Math.signum(pressure.directionalScore)==Math.signum(shortBias) || pressureOverride));

        if(maxP>=threshold && consensusOk && historyEntryGate && !conflict && !choppy
                && !lowQuality && healthy){
            label=buy>sell?"BUY":"SELL";
        }

        int bp=(int)Math.round(buy*100);
        int sp=100-bp;
        int confidence=(int)Math.round(
                clamp(Math.abs(buy-.5)*2*quality,0,1)*100);
        int setupQuality=(int)Math.round(quality*100);
        int strength=label.equals("WAIT")
                ?Math.max(50,Math.min(80,(int)Math.round(maxP*100)))
                :Math.max(60,Math.min(90,(int)Math.round(maxP*100)));

        String structureText=structureText(trend,structure,breakout,pullback);
        String regime=regimeText(
                healthy,conflict,choppy,lowQuality,coreAgreement,
                disagreement,trend,acceleration,breakout,pullback);

        if(learner!=null&&learner.totalSamples(h)>=30)regime+=" • LEARNED";

        String explanation=explain(
                label,trend,shortBias,structure,breakout,pullback,
                engulf,rejection,doji,supportResistance,pattern,strategy,next,advanced,priceAction,reference,candleKnowledge,professional,liquidity,confirmation,uploaded,bible,
                quality,healthy,conflict,choppy,coreAgreement);
        if(label.equals("WAIT") && highAccuracy && !historyEntryGate)
            explanation="History entry gate: wait for at least 3 confirmations. "+history.summary;
        else if(!label.equals("WAIT") && history.directional())
            explanation=explanation+" History: "+history.summary;
        if(pressure.directional())
            explanation=explanation+" Pressure: "+pressure.summary;

        String structureForLearning;
        if(pressure.strong()) structureForLearning=pressure.name+" • "+structureText;
        else if(history.strong()) structureForLearning=history.name+" • "+structureText;
        else if(bible.strong()) structureForLearning=bible.name+" • "+structureText;
        else if(uploaded.strong()) structureForLearning=uploaded.name+" • "+structureText;
        else if(confirmation.strong()) structureForLearning=confirmation.name+" • "+structureText;
        else if(professional.strong()) structureForLearning=professional.name+" • "+structureText;
        else structureForLearning = candleKnowledge.directional()
                ? candleKnowledge.name+" • "+structureText : structureText;
        if(candleKnowledge.strong()) regime += " • CANDLE "+candleKnowledge.name;
        if(professional.strong()) regime += " • PRO "+professional.name;
        if(liquidity.strong()) regime += " • LIQ/FVG "+liquidity.name;
        if(confirmation.strong()) regime += " • CONFIRM "+confirmation.name;
        if(uploaded.strong()) regime += " • UPLOAD "+uploaded.name;
        if(bible.strong()) regime += " • BIBLE "+bible.name;
        if(history.directional()) regime += " • HISTORY "+history.confirmations+"/4 "+history.name;
        if(pressure.directional()) regime += " • PRESSURE "+pressure.buyerPressure+"B/"+pressure.sellerPressure+"S "+pressure.name;
        regime += " • 12M "+methodConfirmations+"/12";
        if(professional.falseBreakout) regime += " • FALSE BREAKOUT";

        return new SignalResult(
                label,strength,buy-sell,bp,sp,confidence,regime,raw,
                setupQuality,structureForLearning,explanation);
    }

    private static int confirmationCount(double side,double minimum,double... methods){
        if(side==0)return 0;
        int count=0;
        for(double value:methods)
            if(Math.abs(value)>=minimum && Math.signum(value)==side)count++;
        return count;
    }

    private static String explain(
            String label,double trend,double momentum,double structure,
            double breakout,double pullback,double engulf,double rejection,
            double doji,double sr,PatternIntelligence.Result pattern,StrategyEngine.Result strategy,NextCandleKnowledge.Result next,AdvancedConfluenceEngine.Result advanced,PriceActionStructureEngine.Result priceAction,ReferencePatternEngine.Result reference,CandlestickKnowledgeEngine.Result candleKnowledge,ProfessionalTechnicalAnalysisEngine.Result professional,LiquidityFvgEngine.Result liquidity,StructureConfirmationEngine.Result confirmation,UploadedKnowledgeEngine.Result uploaded,CandlestickBibleEngine.Result bible,double quality,
            boolean healthy,boolean conflict,boolean choppy,
            boolean agreement){

        if(!healthy) return "Recent learned performance is weak; signal blocked.";
        if(conflict) return "Trend and short-term momentum disagree.";
        if(choppy) return "Chart is too volatile/choppy for a clean setup.";
        if(!agreement) return "Trend, candle and structure models do not agree.";

        String dir = label.equals("BUY") ? "bullish" :
                label.equals("SELL") ? "bearish" : "mixed";

        String best="trend";
        double bestV=Math.abs(trend);
        if(pattern!=null && pattern.strong()){
            best=pattern.name.toLowerCase(Locale.US);
            bestV=Math.max(bestV,Math.abs(pattern.directionalScore)+.25);
        }
        if(strategy!=null && strategy.strong() && Math.abs(strategy.directionalScore)+.30>bestV){
            best=strategy.name.toLowerCase(Locale.US);
            bestV=Math.abs(strategy.directionalScore)+.30;
        }
        if(next!=null && next.strong() && Math.abs(next.directionalScore)+.34>bestV){
            best=next.reason.toLowerCase(Locale.US);
            bestV=Math.abs(next.directionalScore)+.34;
        }
        if(advanced!=null && advanced.strong() && Math.abs(advanced.directionalScore)+.38>bestV){
            best=advanced.name.toLowerCase(Locale.US);
            bestV=Math.abs(advanced.directionalScore)+.38;
        }
        if(priceAction!=null && priceAction.strong() && Math.abs(priceAction.directionalScore)+.42>bestV){
            best=priceAction.name.toLowerCase(Locale.US);
            bestV=Math.abs(priceAction.directionalScore)+.42;
        }
        if(reference!=null && reference.strong() && Math.abs(reference.directionalScore)+.46>bestV){
            best=reference.name.toLowerCase(Locale.US);
            bestV=Math.abs(reference.directionalScore)+.46;
        }
        if(candleKnowledge!=null && candleKnowledge.strong() && Math.abs(candleKnowledge.directionalScore)+.52>bestV){
            best=candleKnowledge.name.toLowerCase(Locale.US);
            bestV=Math.abs(candleKnowledge.directionalScore)+.52;
        }
        if(professional!=null && professional.strong() && Math.abs(professional.directionalScore)+.58>bestV){
            best=professional.name.toLowerCase(Locale.US);
            bestV=Math.abs(professional.directionalScore)+.58;
        }
        if(uploaded!=null && uploaded.strong() && Math.abs(uploaded.directionalScore)+.60>bestV){
            best=uploaded.name.toLowerCase(Locale.US);
            bestV=Math.abs(uploaded.directionalScore)+.60;
        }
        if(liquidity!=null && liquidity.strong() && Math.abs(liquidity.directionalScore)+.62>bestV){
            best=liquidity.name.toLowerCase(Locale.US);
            bestV=Math.abs(liquidity.directionalScore)+.62;
        }
        if(confirmation!=null && confirmation.strong() && Math.abs(confirmation.directionalScore)+.66>bestV){
            best=confirmation.name.toLowerCase(Locale.US);
            bestV=Math.abs(confirmation.directionalScore)+.66;
        }
        if(bible!=null && bible.strong() && Math.abs(bible.directionalScore)+.64>bestV){
            best=bible.name.toLowerCase(Locale.US);
            bestV=Math.abs(bible.directionalScore)+.64;
        }

        if(Math.abs(momentum)>bestV){best="momentum";bestV=Math.abs(momentum);}
        if(Math.abs(structure)>bestV){best="market structure";bestV=Math.abs(structure);}
        if(Math.abs(breakout)>bestV){best="breakout";bestV=Math.abs(breakout);}
        if(Math.abs(pullback)>bestV){best="pullback";bestV=Math.abs(pullback);}
        if(Math.abs(engulf)>bestV){best="engulfing impulse";bestV=Math.abs(engulf);}
        if(Math.abs(rejection)>bestV){best="rejection";bestV=Math.abs(rejection);}

        if(label.equals("WAIT")){
            if(bible!=null && bible.choppy)
                return "Candlestick Bible filter: choppy market; waiting for clearer structure and key levels.";
            if(bible!=null && bible.directional() && (bible.uncertainty>.44 || bible.riskPenalty>.44))
                return bible.name+" is visible, but book-based trend/level/risk confirmation is not strong enough yet.";
            if(professional!=null && professional.falseBreakout)
                return professional.name+" detected; waiting for completed-candle confirmation before a stronger alert.";
            if(professional!=null && professional.uncertainty>.62)
                return "Professional chart structure is unconfirmed or range-like; confidence reduced.";
            if(confirmation!=null && confirmation.uncertainty>.60 && (confirmation.structureBreak||confirmation.failedBreak))
                return "Structure event is visible but the confirmation candle/retest is not strong enough yet.";
            if(liquidity!=null && liquidity.uncertainty>.60 && (liquidity.fvg||liquidity.liquiditySweep))
                return "Liquidity/FVG context is visible but not sufficiently confirmed; waiting for confluence.";
            if(candleKnowledge!=null && candleKnowledge.uncertainty>.80)
                return candleKnowledge.name+" indicates indecision/confirmation risk; signal quality reduced.";
            if(pattern!=null && pattern.indecision>.72)
                return pattern.name+" indicates indecision; signal quality reduced.";
            if(strategy!=null && strategy.riskPenalty>.62)
                return "Volatility regime is unstable; next-candle confidence reduced.";
            if(doji>.55) return "Indecision/doji-like candle behaviour; waiting.";
            if(Math.abs(sr)>.55) return "Price is close to a visual support/resistance extreme.";
            if(quality<.55) return "Setup quality is below the high-accuracy requirement.";
            return "No sufficiently strong multi-factor setup.";
        }

        return String.format(Locale.US,
                "%s next-candle setup led by %s; newest five candles are weighted most, with %.0f%% setup quality.",
                dir.toUpperCase(Locale.US),best,quality*100.0);
    }

    private static String structureText(
            double trend,double structure,double breakout,double pullback){
        if(Math.abs(breakout)>.48)
            return breakout>0?"LOCAL BULL BREAKOUT":"LOCAL BEAR BREAKOUT";
        if(Math.abs(pullback)>.42)
            return pullback>0?"BULL PULLBACK":"BEAR PULLBACK";
        if(trend>.25&&structure>.15)return "HIGHER STRUCTURE";
        if(trend<-.25&&structure<-.15)return "LOWER STRUCTURE";
        return "RANGE / MIXED";
    }

    private static String regimeText(
            boolean healthy,boolean conflict,boolean choppy,boolean lowQuality,
            boolean agreement,double disagreement,double trend,double accel,
            double breakout,double pullback){
        if(!healthy)return "PAUSED • RECENT SCORE LOW";
        if(conflict)return "CONFLICT";
        if(choppy)return "CHOPPY";
        if(lowQuality)return "LOW QUALITY";
        if(!agreement)return "WAIT • MODELS DISAGREE";
        if(disagreement>.20)return "WAIT • WEAK CONSENSUS";
        if(Math.abs(breakout)>.45)return "BREAKOUT";
        if(Math.abs(pullback)>.40)return "PULLBACK";
        if(Math.abs(trend)>.32)return "TREND";
        if(Math.abs(accel)>.28)return "MOMENTUM";
        return "MIXED";
    }

    // Higher highs / lower lows proxy using normalized screen Y (lower Y = higher price).
    static double marketStructure(List<Double>y,int s,int k){
        if(k<5)return 0;
        int half=k/2;
        double old=mean(y,s,half);
        double recent=mean(y,s+half,k-half);
        return clamp((old-recent)*7.0,-1,1);
    }

    static double supportResistanceScore(List<Double>y,int s,int k){
        double latest=y.get(s+k-1),min=1,max=0;
        for(int i=0;i<k;i++){double q=y.get(s+i);min=Math.min(min,q);max=Math.max(max,q);}
        double range=Math.max(.005,max-min);
        double nearTop=(latest-min)/range; // low y = high price
        double nearBottom=(max-latest)/range;
        // Positive if near visual support, negative if near resistance.
        return clamp(nearBottom-nearTop,-1,1);
    }

    static double breakoutScore(List<Double>y,List<Double>d,int s,int k){
        if(k<5)return 0;
        double latest=y.get(s+k-1);
        double priorMin=1,priorMax=0;
        for(int i=0;i<k-1;i++){
            double q=y.get(s+i);
            priorMin=Math.min(priorMin,q);
            priorMax=Math.max(priorMax,q);
        }
        double dir=d.get(s+k-1);
        if(latest<priorMin-.002 && dir>.18)return clamp(.45+Math.abs(dir),0,1);
        if(latest>priorMax+.002 && dir<-.18)return -clamp(.45+Math.abs(dir),0,1);
        return 0;
    }

    static double pullbackScore(List<Double>y,List<Double>d,int s,int k,double trend){
        if(k<5)return 0;
        double last=d.get(s+k-1),prev=mean(d,s+k-Math.min(4,k),Math.min(3,k-1));
        if(trend>.25 && prev<-.05 && last>.12)return .60;
        if(trend<-.25 && prev>.05 && last<-.12)return -.60;
        return 0;
    }

    static double engulfingProxy(List<Double>d,List<Double>r,int s,int k){
        if(k<2)return 0;
        double a=d.get(s+k-1),b=d.get(s+k-2);
        double ra=r.get(s+k-1),rb=r.get(s+k-2);
        if(Math.signum(a)!=Math.signum(b) && Math.abs(a)>.35 && ra>rb*1.12)
            return clamp(a*1.25,-1,1);
        return 0;
    }

    static double rejectionProxy(
            List<Double>d,List<Double>r,List<Double>density,int s,int k){
        double dir=d.get(s+k-1),range=r.get(s+k-1),den=density.get(s+k-1);
        double weakBody=1-Math.min(1,Math.abs(dir));
        double longRange=clamp(range*12,0,1);
        double sparse=clamp(1-den*10,0,1);
        double magnitude=weakBody*longRange*(.55+.45*sparse);
        if(k>=2){
            double prev=d.get(s+k-2);
            if(Math.signum(dir)!=Math.signum(prev))magnitude*=1.25;
        }
        return clamp(Math.signum(dir)*magnitude,-1,1);
    }

    static double dojiProxy(List<Double>d,List<Double>r,int s,int k){
        double dir=Math.abs(d.get(s+k-1));
        double range=r.get(s+k-1);
        return clamp((.20-dir)*4.0 + range*.8,0,1);
    }

    static double streakScore(List<Double>d,int s,int k){
        int sign=0,count=0;
        for(int i=k-1;i>=0;i--){
            double v=d.get(s+i);
            int q=v>.10?1:v<-.10?-1:0;
            if(q==0)break;
            if(sign==0)sign=q;
            if(q!=sign)break;
            count++;
        }
        return clamp(sign*count/4.0,-1,1);
    }

    static double directionalConsistency(List<Double>d,int s,int k){
        int p=0,n=0;
        for(int i=0;i<k;i++){
            double x=d.get(s+i);
            if(x>.08)p++;else if(x<-.08)n++;
        }
        return(double)(p-n)/Math.max(1,k);
    }

    static double acceleration(List<Double>d,int s,int k){
        if(k<4)return 0;
        int h=k/2;
        return clamp(mean(d,s+h,k-h)-mean(d,s,h),-1,1);
    }

    static double exhaustion(List<Double>d,int s,int k){
        if(k<4)return 0;
        double last=d.get(s+k-1);
        double prior=mean(d,s+Math.max(0,k-4),Math.min(3,k-1));
        if(Math.signum(last)!=Math.signum(prior)&&Math.abs(prior)>.20)
            return Math.min(1,Math.abs(last-prior));
        if(Math.abs(prior)>.60 && Math.abs(last)<.18)return .70;
        return 0;
    }

    static double returnStd(List<Double>y,int s,int k){
        if(k<3)return 0;
        double[] a=new double[k-1];
        for(int i=1;i<k;i++)a[i-1]=y.get(s+i)-y.get(s+i-1);
        double m=0;for(double v:a)m+=v;m/=a.length;
        double z=0;for(double v:a){double q=v-m;z+=q*q;}
        return Math.sqrt(z/a.length);
    }

    static double weighted(List<Double>v,int s,int k){
        double a=0,w=0;
        for(int i=0;i<k;i++){double q=1+i*.24;a+=v.get(s+i)*q;w+=q;}
        return w==0?0:a/w;
    }

    static double mean(List<Double>v,int s,int k){
        if(k<=0)return 0;
        double z=0;for(int i=0;i<k;i++)z+=v.get(s+i);
        return z/k;
    }

    static double std(List<Double>v,int s,int k){
        if(k<=1)return 0;
        double m=mean(v,s,k),z=0;
        for(int i=0;i<k;i++){double q=v.get(s+i)-m;z+=q*q;}
        return Math.sqrt(z/k);
    }

    static double slope(List<Double>v,int s,int k){
        if(k<2)return 0;
        double sx=0,sy=0,sxx=0,sxy=0;
        for(int i=0;i<k;i++){
            double x=i,q=v.get(s+i);
            sx+=x;sy+=q;sxx+=x*x;sxy+=x*q;
        }
        double den=k*sxx-sx*sx;
        return Math.abs(den)<1e-9?0:(k*sxy-sx*sy)/den;
    }

    static double sigmoid(double x){return 1/(1+Math.exp(-x));}
    static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
    static double max4(double a,double b,double c,double d){return Math.max(Math.max(a,b),Math.max(c,d));}
    static double min4(double a,double b,double c,double d){return Math.min(Math.min(a,b),Math.min(c,d));}
}
