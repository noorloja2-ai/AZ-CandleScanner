CREATE TABLE IF NOT EXISTS versioned_aggregates (
  asset TEXT NOT NULL,
  timeframe_minutes INTEGER NOT NULL,
  app_version TEXT NOT NULL,
  samples INTEGER NOT NULL DEFAULT 0,
  correct INTEGER NOT NULL DEFAULT 0,
  outcome_up INTEGER NOT NULL DEFAULT 0,
  raw_probability_sum REAL NOT NULL DEFAULT 0,
  PRIMARY KEY (asset, timeframe_minutes, app_version)
);

CREATE INDEX IF NOT EXISTS versioned_aggregates_asset_timeframe
ON versioned_aggregates(asset, timeframe_minutes);

CREATE TABLE IF NOT EXISTS learning_quarantine (
  asset TEXT NOT NULL,
  timeframe_minutes INTEGER NOT NULL,
  source_table TEXT NOT NULL,
  reason TEXT NOT NULL,
  quarantined_at INTEGER NOT NULL,
  PRIMARY KEY (asset, timeframe_minutes, source_table)
);

INSERT OR REPLACE INTO learning_quarantine(
  asset,timeframe_minutes,source_table,reason,quarantined_at)
VALUES(
  'BTC_GBP',1,'aggregates',
  'Pre-v16.28 pair identity could be stale; retained for audit and excluded from models',
  CAST(strftime('%s','now') AS INTEGER) * 1000
);
