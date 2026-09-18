CREATE TABLE IF NOT EXISTS licenses (
  key_hash TEXT PRIMARY KEY,
  months INTEGER NOT NULL CHECK(months BETWEEN 1 AND 12),
  status TEXT NOT NULL DEFAULT 'active',
  created_at INTEGER NOT NULL,
  install_id TEXT,
  activated_at INTEGER,
  expires_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_licenses_install_id ON licenses(install_id);
