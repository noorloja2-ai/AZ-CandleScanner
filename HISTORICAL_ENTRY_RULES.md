# v14.6 Historical Entry Learning

The scanner now treats a candle as a setup only when recent history provides context.

High Accuracy entry gate uses at least 3 of 4 confirmations:
1. Candle/setup pattern (engulfing, hammer/shooting star, morning/evening star, continuation or retest)
2. Support/resistance or valid level context
3. Trend/market-structure alignment
4. Recent momentum confirmation

Strong reversal patterns can oppose the older trend only when a key level and reversal pattern are both present.
Named setup families are passed into the existing TrainingStore/OnlineLearner so their resolved performance is learned per asset and timeframe.

Confidence values are model scores, not guaranteed probabilities or win rates.
