-- Schema is idempotent (CREATE ... IF NOT EXISTS) and runs on every start, after SchemaManager migrations.
-- Version (PRAGMA user_version) 2: catalog transcribed from the Wild Rift shop (pt-BR).
-- Version 3: conditional effects chosen per purchase (build_step.include_conditional).
-- Version 4: exclusive item groups (item.exclusive_groups).
-- Version 5: build options assume_half_items / assume_small_items; purchase moments (build_moment).
-- Version 6: rune page and summoner spells of a build (build.rune_page, build.spells).

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
    category     TEXT    NOT NULL,           -- shop tabs joined by ", "
    image        TEXT,
    source_patch TEXT,
    edited       INTEGER NOT NULL DEFAULT 0, -- 1 = corrected by hand, re-import will not overwrite it
    section      TEXT,                       -- aprimorado | tier_medio | basico | preparacao | evolucao
    active       INTEGER NOT NULL DEFAULT 0,
    marker       TEXT,                       -- novo | reformulado | alterado
    summary      TEXT,
    item_group   TEXT,                       -- shop tile the item belongs to (base item for evolutions)
    capture      TEXT,                       -- screenshot the data came from
    exclusive_groups TEXT                    -- groups of which only one item can be held, joined by ", "
);

CREATE TABLE IF NOT EXISTS item_passive (
    item_id INTEGER NOT NULL REFERENCES item (id) ON DELETE CASCADE,
    seq     INTEGER NOT NULL,
    name    TEXT,
    text    TEXT    NOT NULL,
    PRIMARY KEY (item_id, seq)
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
    ref_scope TEXT    NOT NULL DEFAULT 'TOTAL',  -- TOTAL | BONUS | BASE
    value_max   REAL,                            -- value at level 15 for "X–Y (by level)" ranges
    conditional INTEGER NOT NULL DEFAULT 0       -- 1 = only counts when the build enables conditional effects
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
    include_conditional INTEGER NOT NULL DEFAULT 1, -- unused since v3 (kept for old databases)
    assume_half_items  INTEGER NOT NULL DEFAULT 0,
    assume_small_items INTEGER NOT NULL DEFAULT 0,
    rune_page    TEXT,      -- JSON: primary/secondary tree, keystone, runes, options (v6)
    spells       TEXT,      -- JSON: two summoner spell names (v6)
    created_at   TEXT    NOT NULL,
    updated_at   TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS build_step (
    build_id INTEGER NOT NULL REFERENCES build (id) ON DELETE CASCADE,
    seq      INTEGER NOT NULL,
    item_id  INTEGER NOT NULL REFERENCES item (id),
    include_conditional INTEGER NOT NULL DEFAULT 1, -- count this purchase's conditional effects
    PRIMARY KEY (build_id, seq)
);

-- Purchase moments ("back to base"): markers in the purchase sequence. seq shares the numbering of build_step, so
-- the two tables merged by seq give the sequence. The items after a moment are bought from its minute on: either
-- at_minute (exact game time) or after_minutes after the previous purchase.
CREATE TABLE IF NOT EXISTS build_moment (
    build_id      INTEGER NOT NULL REFERENCES build (id) ON DELETE CASCADE,
    seq           INTEGER NOT NULL,
    at_minute     REAL,
    after_minutes REAL,
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
