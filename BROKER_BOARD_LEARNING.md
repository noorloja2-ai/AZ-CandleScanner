# AZ Broker Board Learning (v14.7)

AZ now learns from the active broker candle board without storing screenshots.

## What is remembered
Each valid chart scan is reduced to a compact numeric state: recent trend, short momentum, last candle direction, body/wick geometry, recent range position, and volatility ratio. Similar states share one learning bucket.

## How a label becomes valid
A state is saved as pending only after an official post-close scan. It is resolved only at the exact future timeframe boundary. If that boundary is missed, the pending label is discarded. Tiny/flat screen movement is not forced into an UP or DOWN label.

## How memory changes the next prediction
After enough matching resolved examples exist, the historical UP rate is blended conservatively with the existing model. The blend is capped so candlestick knowledge, structure, local calibration and community learning remain dominant.

## Privacy
No chart screenshot, broker password, username, full webpage text or trading order is stored by this learner. Only numeric state buckets and UP/DOWN counts are retained locally.
