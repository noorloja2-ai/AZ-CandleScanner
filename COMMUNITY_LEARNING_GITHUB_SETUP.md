# GitHub Community Learning setup

CandleScanner v14.3 never contains a GitHub write token. Putting a repository token in an APK would allow anyone to extract it and write to the repository.

The app uses GitHub as the public knowledge source:

1. Publish `community-learning.json` at the repository root.
2. `submit_url` must point to a protected HTTPS gateway you control.
3. The gateway accepts anonymous CandleScanner event batches, validates them, rejects spam/outliers, aggregates enough samples, and writes only aggregate calibration to `community-model.json` in GitHub using a server-side GitHub credential.
4. Phones periodically download `community-model.json` from GitHub.
5. The community model is used only after at least 50 samples and is capped at 25% influence; local self-learning remains dominant.

## Anonymous event fields

- asset
- timeframe_minutes / horizon_index
- raw_buy_probability
- displayed_buy_probability
- predicted direction
- resolved outcome
- correct true/false
- named setup
- market regime
- app version
- random per-event ID used only for delivery de-duplication

No screenshot, broker login, account number, Android ID, advertising ID, location, contact information, or GitHub credential is sent.

## Required server-side validation

Do not publish every phone event directly to the model. Enforce rate limiting, schema validation, allowed asset/timeframe values, probability bounds, duplicate rejection, minimum sample size, robust/outlier checks, and a holdout/recent-performance gate before updating `community-model.json`.
