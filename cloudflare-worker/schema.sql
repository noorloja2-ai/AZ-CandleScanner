CREATE TABLE IF NOT EXISTS seen_events (
  id TEXT PRIMARY KEY,
  received_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS aggregates (
  asset TEXT NOT NULL,
  timeframe_minutes INTEGER NOT NULL,
  samples INTEGER NOT NULL DEFAULT 0,
  correct INTEGER NOT NULL DEFAULT 0,
  outcome_up INTEGER NOT NULL DEFAULT 0,
  raw_probability_sum REAL NOT NULL DEFAULT 0,
  PRIMARY KEY (asset, timeframe_minutes)
);

CREATE INDEX IF NOT EXISTS seen_events_received_at
ON seen_events(received_at);
