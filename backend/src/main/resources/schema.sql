-- Schema is idempotent (CREATE ... IF NOT EXISTS) and runs on every start, after SchemaManager migrations.
-- Version (PRAGMA user_version) 2: catalog transcribed from the Wild Rift shop (pt-BR).
-- Version 3: conditional effects chosen per purchase (build_step.include_conditional).
-- Version 4: exclusive item groups (item.exclusive_groups).
-- Version 5: build options assume_half_items / assume_small_items; purchase moments (build_moment).
-- Version 6: rune page and summoner spells of a build (build.rune_page, build.spells).
-- Version 7: end of the match (build.match_end).
-- Version 8: champion setup of a build: skill order and effect choices (build.champion_setup).
-- Version 9: stats priced as a share of another (stat_def.factor); item effect choices of a build (build.item_setup).
-- Version 10: no table change; re-imports the stat prices (magic / physical shields).
-- Version 11: no table change; re-imports the catalog (Maldição Sanguinária in the % magic penetration group).
-- Version 12: a purchase step can be a sale of a held item (build_step.sell).
-- Version 13: orchestrated matchup scenarios (arena_scenario).
-- Version 14: learned arena policies (arena_policy).
-- Version 15: chain training of arena policies (arena_policy.chain_id, chain_round, team_plan, parent_id).
-- Version 16: combo library of the arena (arena_combo): found by the search or typed by the user.
-- Version 17: ragdolls of the lab saved by the user (arena_dummy).
-- Version 18: help levels of arena policies (arena_policy.level, guidelines) and the user's guidelines (arena_guideline).
-- Version 19: arena_policy.wins_scenario (the version wins its training scenario's own fight).

CREATE TABLE IF NOT EXISTS stat_def (
    name        TEXT PRIMARY KEY,
    seq         INTEGER NOT NULL,
    category    TEXT,
    base_type   TEXT,          -- first | second | exclude | NULL
    base_item   TEXT,
    fixed_price REAL,
    alias       TEXT,
    factor      REAL,          -- with alias: share of the alias' price (NULL = 1)
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
    match_end    REAL,      -- game minute the match ended (v7)
    champion_setup TEXT,    -- JSON: skill order, champion effect options / conditional / stack rates (v8)
    item_setup   TEXT,      -- JSON: modeled item effects on / off, options and stack rates, by "item|effect" (v9)
    created_at   TEXT    NOT NULL,
    updated_at   TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS build_step (
    build_id INTEGER NOT NULL REFERENCES build (id) ON DELETE CASCADE,
    seq      INTEGER NOT NULL,
    item_id  INTEGER NOT NULL REFERENCES item (id),
    include_conditional INTEGER NOT NULL DEFAULT 1, -- count this purchase's conditional effects
    sell     INTEGER NOT NULL DEFAULT 0,            -- 1 = sells one held copy of the item instead of buying it (v12)
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

-- Orchestrated matchups (arena): the whole scenario (champions, positions, timeline of orders) as JSON (v13).
CREATE TABLE IF NOT EXISTS arena_scenario (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT NOT NULL,
    data       TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- Learned arena policies (v14): a network trained for one champion and objective, its validation against the scripted
-- and tactical autopilots, and its weights as JSON ({layers, weights, bonus}). obs_schema / actions guard against
-- loading weights made for another observation or action layout.
CREATE TABLE IF NOT EXISTS arena_policy (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    champion           TEXT NOT NULL,
    objective          TEXT NOT NULL,
    version            INTEGER NOT NULL,
    fitness            REAL,
    better_vs_script   REAL,
    winrate            REAL,
    winrate_script     REAL,
    better_vs_tactical REAL,
    better_vs_previous REAL,
    accepted           INTEGER NOT NULL DEFAULT 0,
    generations        INTEGER,
    population         INTEGER,
    seed               INTEGER,
    scenario           TEXT,
    weights_json       TEXT NOT NULL,
    obs_schema         INTEGER NOT NULL,
    actions            INTEGER NOT NULL,
    created_at         TEXT NOT NULL,
    chain_id           TEXT,          -- chain training that made it (NULL = trained alone)
    chain_round        INTEGER,
    team_plan          TEXT,          -- JSON: the side's plan used in its training fights
    parent_id          INTEGER,       -- version its training started from (NULL = zero weights)
    level              TEXT,          -- help level of its training (sozinha | descobertos | dicas)
    guidelines         TEXT,          -- the guidelines it was trained with (JSON)
    wins_scenario      INTEGER        -- 1 = its side wins the training scenario's fight with it (NULL = not measured)
);
CREATE INDEX IF NOT EXISTS idx_arena_policy_champion ON arena_policy (champion);

CREATE TABLE IF NOT EXISTS arena_combo (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    champion    TEXT NOT NULL,
    name        TEXT NOT NULL,
    origin      TEXT NOT NULL DEFAULT 'manual',   -- descoberto | manual
    active      INTEGER NOT NULL DEFAULT 1,
    score       REAL,
    build       TEXT,                             -- build and minute it was found for (search)
    build_id    INTEGER,
    minute      REAL,
    data        TEXT NOT NULL,                    -- steps, conditions, max time, metrics, as JSON
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS arena_combo_champion ON arena_combo (champion);

CREATE TABLE IF NOT EXISTS arena_dummy (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL,
    data        TEXT NOT NULL,                    -- stats, behavior, build worn, effects, as JSON
    updated_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS arena_guideline (
    champion    TEXT PRIMARY KEY COLLATE NOCASE,
    data        TEXT NOT NULL,                    -- guidelines and event reward weights, as JSON
    updated_at  TEXT NOT NULL
);
