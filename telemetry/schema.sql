-- Anonymous device heartbeats + crash/error events for InnerDesk.
-- uuid is a random client-generated id — not linked to a person or Google account.

CREATE TABLE IF NOT EXISTS devices (
  uuid TEXT PRIMARY KEY NOT NULL,
  first_seen INTEGER NOT NULL,
  last_seen INTEGER NOT NULL,
  version TEXT,
  version_code INTEGER,
  android_sdk INTEGER,
  locale TEXT,
  ping_count INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  uuid TEXT NOT NULL,
  type TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  version TEXT,
  payload TEXT,
  FOREIGN KEY (uuid) REFERENCES devices(uuid)
);

CREATE INDEX IF NOT EXISTS idx_devices_last_seen ON devices(last_seen);
CREATE INDEX IF NOT EXISTS idx_events_type_created ON events(type, created_at);
CREATE INDEX IF NOT EXISTS idx_events_uuid ON events(uuid);
