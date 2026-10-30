-- ReveriePaint 崩溃遥测 D1 schema (v1)
-- 用法: wrangler d1 execute reveriepaint_telemetry --file=./schema.sql --remote

CREATE TABLE IF NOT EXISTS crashes (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  sig       TEXT    NOT NULL,
  kind      TEXT    NOT NULL,
  at        INTEGER NOT NULL,   -- 崩溃发生时刻 (unix 秒)
  received  INTEGER NOT NULL,   -- 服务端收件时刻 (unix 秒), 用于配额窗口
  app_code  INTEGER NOT NULL,
  app_ver   TEXT    NOT NULL,
  abi       TEXT,
  sdk       INTEGER,
  model     TEXT,
  install   TEXT,               -- 随机 UUID, 仅用于配额与去重, 不参与展示
  payload   TEXT    NOT NULL    -- 归一化后的完整 JSON (已脱敏)
);

CREATE INDEX IF NOT EXISTS idx_crashes_sig  ON crashes(sig, at);
CREATE INDEX IF NOT EXISTS idx_crashes_inst ON crashes(install, received);
CREATE INDEX IF NOT EXISTS idx_crashes_at   ON crashes(at);
