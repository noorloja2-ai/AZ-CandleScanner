# AZ CandleScanner v16.8 - Master Candlestick Guide

The uploaded Master Candlestick & Price Action Guide is connected to the live
signal pipeline through the existing 38-pattern detector. A recognized pattern
strengthens BUY/SELL only when the candle is confirmed and agrees with trend or
a measured support/resistance boundary. Doji and Spinning Top remain WAIT.
Licence, statistics, notifications and self-learning are preserved.

# CandleScanner v14.9 — Quick Decision + Broker Board Learning

This version keeps every v14.8 pressure/pattern feature and adds a fast live-decision gate. AZ scans the broker board roughly every second, reuses only previously validated board-history memory, and waits for repeated agreement before showing **QUICK BUY** or **QUICK SELL**.

A QUICK signal requires stable repeated frames plus model/trend/momentum/flow/candle/acceleration confirmation. It is provisional because the current candle is still changing. Learning is still outcome-based: only the exact future candle close can update the board model.

The app does not store screenshots or broker credentials and does not place trades automatically. Displayed percentages are model scores, not guaranteed probabilities.

See `CHANGELOG_v14.9.txt`.

# CandleScanner v14.8 — Buyer/Seller Pressure + Pattern Learning

This version keeps v14.7 broker-board learning and adds a dedicated pressure/pattern engine based on the new candlestick reference material supplied by the user. The engine does not assume a named candlestick guarantees the next candle. It combines body/wick geometry, recent flow, support/resistance position, trend/momentum and resolved historical outcomes. Strong patterns are saved into the existing training setup field so future closed-candle results can calibrate their influence.

The floating signal card now shows a **BUYER / SELLER PRESSURE** line when directional pressure is detected.

See `CHANGELOG_v14.8.txt` and `ADDED_KNOWLEDGE_v14.8.md`.

# CandleScanner v14.7 — Broker Board Learning

# CandleScanner v14.5 — Automatic Shared Learning

This version keeps all v14.4.2 features and removes manual community-learning controls. Shared learning is always automatic.

## v14.5 shared-learning behavior
- No Backup / Restore buttons are needed for community learning.
- Local self-learning remains on the phone and continues to calibrate predictions.
- Resolved anonymous numeric events are queued automatically.
- When a protected HTTPS submit gateway is configured in GitHub `community-learning.json`, queued events are uploaded automatically for validation and aggregation.
- The validated aggregate `community-model.json` is downloaded from GitHub automatically about every 15 minutes while the app is active.
- Other users receive new shared calibration without installing a new APK.
- GitHub write credentials are never stored in the APK.
- Screenshots, broker credentials, Android IDs, contacts and location are never included in community learning.
- Shared calibration stays capped so it cannot replace the phone's local learner.

## Important
GitHub can safely distribute the shared model, but Android clients cannot safely write directly to a repository without credentials. A protected gateway must hold the GitHub write credential on the server, validate incoming rows, aggregate them, and then update `community-model.json`.

# CandleScanner v14.4 — Google Drive Auto Update

This version keeps all prior PDF knowledge, candlestick engines, local self-learning, community-learning code, alerts, AZ branding, and scanner features.

## v14.4 update system
- Google Drive is now the primary update source.
- A permanent Drive-hosted manifest is checked by CHECK / UPDATE APP.
- The manifest can point to each newer versioned APK uploaded to the CandleScanner Updates folder.
- GitHub remains only as a backup source.
- Android still shows its normal install/update confirmation.
- Same package name is preserved so app data can survive in-place updates when APKs are signed with the same certificate.

# CandleScanner v14.3 — Community Learning

## v14.3 community learning update
- Keeps all v14.2 PDF/book knowledge and all existing local self-learning.
- Adds anonymous resolved-outcome queueing after exact candle-boundary validation.
- Adds GitHub-controlled `community-learning.json` configuration.
- Downloads a validated `community-model.json` from GitHub and blends it conservatively (maximum 25% weight) with the phone's local calibration.
- Never uploads screenshots, broker credentials, Android identifiers, or a GitHub write token.
- Community uploads require a protected HTTPS gateway. The gateway validates/aggregates rows and writes the aggregate model to GitHub with server-side credentials.
- Adds an in-app Community Learning switch and manual sync button.

# CandleScanner v14.2 — Full Knowledge + Candlestick Bible AZ

This update merges the strongest parts of v13 and v14 without removing the existing on-device learning.

## Preserved

- Package: `com.example.floatingcandlescanner`
- Automatic learner key: `online_learner_v4`
- Training-store compatibility: `training_store_v3` / existing training history
- AZ branding, floating tap scan, M1–M5 signals, app updater, alert sound/vibration
- No `I WON` / `I LOST` buttons
- No automatic trade execution

## Combined knowledge

- Candlestick and next-candle engines
- Price action and market structure
- Professional technical-analysis engine
- Liquidity / FVG context
- Structure-confirmation layer
- EMA / RSI / MACD / ADX / ATR / Fibonacci logic when live OHLC is available
- Uploaded chart-pattern knowledge (Head & Shoulders, inverse H&S, Cup & Handle, Pipe/Two-Bar Reversal, NR4/inside-range breakout, conservative gap-pivot continuation)
- **Candlestick Bible confirmation layer**: trend + key level + price-action signal, pin bars/engulfing, inside-bar breakout/false break, range boundary rejection, breakout-retest, supply/demand reaction, EMA/Fibonacci confluence, and choppy-market/risk filters

## v14.2 integration rule

The Bible layer is deliberately a confirmation/filter input rather than a promise of accuracy. Its contribution is capped and uncertainty/risk penalties are subtracted so it cannot simply inflate BUY/SELL confidence. Existing liquidity, confirmation, uploaded-knowledge and self-learning layers remain active.

## Limitations

Screenshot-derived candle geometry is approximate. Pocket Option OTC is visual-only because standard market-data candles do not reproduce broker OTC candles. Confidence is a model score, not a guaranteed win rate or probability. Binary options/trading can result in loss.

## Build

GitHub Actions builds `app-debug.apk` using Java 17, Gradle 8.9 and Android SDK 35. In-place Android updates require the same signing certificate as the installed app.
