# Candlestick Bible Knowledge Map — CandleScanner v13.0

This file documents how the user's uploaded 168-page *Candlestick Trading Bible* is represented in the app.

## Encoded directly as visual strategy rules
- Market structure: trend, range, choppy.
- Higher-high/higher-low and lower-high/lower-low bias.
- Impulse / pullback context.
- Swing support/resistance and role reversal.
- Pin-bar rejection at key levels.
- Bullish/bearish engulfing at key levels.
- Inside-bar continuation breakout.
- Inside-bar false breakout / trap reversal.
- Range-boundary rejection.
- Range breakout + pullback/retest.
- 8/21 moving-average dynamic support/resistance proxy.
- 50%/61% Fibonacci pullback proxy.
- Trendline reaction proxy.
- Bollinger confirmation only in ranges.
- Supply/demand departure-and-revisit proxy.
- Reward/risk quality check where visible geometry permits it.

## Already covered by existing dedicated candlestick engines
- Doji and variants.
- Dragonfly / Gravestone Doji.
- Morning / Evening Star.
- Hammer / Shooting Star.
- Harami / inside-bar family.
- Tweezers.
- Engulfing.
- Other multi-candle reversal/continuation families from earlier uploads.

## Source rules that cannot be truthfully reconstructed from one M1-M5 screenshot
- Weekly → Daily → 4H top-down analysis.
- Exact broker OHLC values.
- Exact 8/21/200 moving averages from broker data.
- Exact Fibonacci anchors chosen by a human analyst.
- True supply/demand order flow or institutional orders.
- Account equity, lot size and 1%-2% account-risk calculation.
- Automated stop loss, take profit, entry order or trade execution.

Those items are therefore represented as uncertainty, conservative proxies, documentation, or are intentionally not automated.
