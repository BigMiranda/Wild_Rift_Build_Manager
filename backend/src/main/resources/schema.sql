-- Schema is idempotent (CREATE ... IF NOT EXISTS) and runs on every start.

CREATE TABLE IF NOT EXISTS stat_def (
    name        TEXT PRIMARY KEY,
    seq         INTEGER NOT NULL,
    category    TEXT,
    base_type   TEXT,          -- first | second | exclude | NULL
    base_item   TEXT,
    fixed_price REAL,
    alias       TEXT,
    relevant    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS item (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    name         TEXT    NOT NULL UNIQUE,
    cost         INTEGER NOT NULL,
    category     TEXT    NOT NULL,
    image        TEXT,
    source_patch TEXT,
    edited       INTEGER NOT NULL DEFAULT 0  -- 1 = corrected by hand, re-import will not overwrite it
);

CREATE TABLE IF NOT EXISTS item_stat (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    item_id   INTEGER NOT NULL REFERENCES item (id) ON DELETE CASCADE,
    seq       INTEGER NOT NULL,
    stat_type TEXT    NOT NULL,
    value     REAL,
    passive   TEXT,
    ratio     REAL,
    ref       REAL,
    ref_type  TEXT,
    ref_scope TEXT    NOT NULL DEFAULT 'TOTAL'
);
CREATE INDEX IF NOT EXISTS idx_item_stat_item ON item_stat (item_id);

CREATE TABLE IF NOT EXISTS item_component (
    item_id      INTEGER NOT NULL REFERENCES item (id) ON DELETE CASCADE,
    component_id INTEGER NOT NULL REFERENCES item (id) ON DELETE CASCADE,
    quantity     INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (item_id, component_id)
);

CREATE TABLE IF NOT EXISTS unit (
    code         TEXT PRIMARY KEY,
    name         TEXT    NOT NULL,
    living_forge INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS unit_stat (
    unit_code TEXT NOT NULL REFERENCES unit (code) ON DELETE CASCADE,
    stat      TEXT NOT NULL,
    base      REAL NOT NULL,
    growth    REAL NOT NULL,
    PRIMARY KEY (unit_code, stat)
);

CREATE TABLE IF NOT EXISTS living_forge_tier (
    min_level INTEGER PRIMARY KEY,
    pct       REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS xp_level (
    level         INTEGER PRIMARY KEY,
    cumulative_xp REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS folder (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS build (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    folder_id    INTEGER NOT NULL REFERENCES folder (id),
    name         TEXT    NOT NULL,
    note         TEXT,
    unit_code    TEXT    NOT NULL REFERENCES unit (code),
    gold_per_min REAL    NOT NULL,
    xp_per_min   REAL    NOT NULL,
    created_at   TEXT    NOT NULL,
    updated_at   TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS build_step (
    build_id INTEGER NOT NULL REFERENCES build (id) ON DELETE CASCADE,
    seq      INTEGER NOT NULL,
    item_id  INTEGER NOT NULL REFERENCES item (id),
    PRIMARY KEY (build_id, seq)
);

-- "Boneco de pano": base stats are always user-defined, per build (NULL = left blank).
CREATE TABLE IF NOT EXISTS build_ragdoll_stat (
    build_id INTEGER NOT NULL REFERENCES build (id) ON DELETE CASCADE,
    stat     TEXT    NOT NULL,
    base     REAL,
    growth   REAL,
    PRIMARY KEY (build_id, stat)
);
