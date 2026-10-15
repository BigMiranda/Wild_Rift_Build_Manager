# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A personal, 100% local tool for League of Legends: Wild Rift (originally focused on Ornn): plan item purchase order
over time, price stats in gold, simulate fights, and train small AI policies in a 2D "Arena". It never touches the game
(no ADB, screen reading or automation). The README (Portuguese, long) is the authoritative description of every
game rule that is modeled — read the relevant section before changing a formula.

Language conventions: code, identifiers and code comments are in **English**; README, `docs/`, seed YAML content, UI
text and commit messages are in **Portuguese (pt-BR)**. Item/champion names come from the pt-BR game shop and are
never translated (`frontend/src/i18n.js` translates only the UI).

## Commands

Requirements: Java 11+ and Node 18+ (Vite 5; on this machine Node lives under nvm, `nvm use 24`). Maven is not
needed — use the wrapper (`mvnw.cmd` on Windows).

```bash
cd backend && ./mvnw spring-boot:run          # API on :8080; creates backend/data/ornn-planner.db and seeds it
cd frontend && npm install && npm run dev     # :5173, proxies /api to :8080
cd frontend && npm run build                  # emits the SPA into backend/src/main/resources/static (gitignored)
cd backend && ./mvnw test                     # all tests
cd backend && ./mvnw test -Dtest=TimelineEngineTest              # one class
cd backend && ./mvnw test -Dtest=TimelineEngineTest#methodName   # one method
```

`.claude/launch.json` defines `backend` and `frontend` preview servers. There is no linter and no frontend test suite;
"done" for a change has meant `./mvnw test` and `npm run build` both clean.

Opt-in tests, gated by system properties (skipped by default):
- `-Darena.bench=true` — `ArenaBenchmarkTest` (simulated seconds per second, 1 vs N threads).
- `-Darena.report=true` — report-style tests in `ArenaEscapeTest`, `ArenaLevelsTest`, `ArenaTrainerTest`.
- `-Darena.proof=true` — `OrnnProofTest` long proofs.
- `-Darena.golden.write=true` — **re-records** the golden files in `src/test/resources/arena/`
  (`ArenaRegressionTest`: SHA-256 fingerprints of full fight results for ~35 scenarios; `ArenaPolicyMigrationTest`:
  `golden-ai-v2.txt`). Only re-record when a behavior change is intended, and say so; a golden mismatch otherwise
  means the Arena stopped being deterministic or changed behavior.

Catalog tooling (Python, needs `opencv-python-headless numpy pyyaml`): `python tools/capturas_receitas.py capturas/7.3`
verifies transcribed recipes against shop screenshots; `tools/capturas_icones.py` crops item icons into
`seed/icones/`. Screenshots in `capturas/` are not versioned.

## Architecture

Backend: Spring Boot 2.7 (web + jdbc), Java 11, package `com.ornnplanner`. **No JPA** — SQLite via plain
`JdbcTemplate`, Hikari pool of 1 connection (single writer).

- `engine/` — pure calculation, no Spring: `TimelineEngine` (gold/XP over time → when each purchase happens, level,
  effective stats; its Javadoc documents the rules), `GoldPricing` (gold value per stat point, methodology from
  changchiyou/wildrift-gold-efficiency), `Model`, `Stats`.
- `combat/` — 1v1-style `CombatSimulator` (no positions); `AbilityParser` reads damage formulas out of the game's
  ability text; `CombatCatalog` loads `combate_7_3.yml` (item combat effects, rune effects, `gatilhos` triggers).
- `arena/` — 2D orchestrated fight (Howling Abyss as a straight lane): `Arena` is the deterministic simulator (0.02s
  steps); `ArenaService` runs scenarios; `ArenaModel` holds scenario/result types. Champion kits come from
  `kits_7_3.yml`; champions without a kit fall back to text-parsed damage on a targeted ability.
- `arena/ai/` — decision-making "brains": `Policy` interface with `ScriptedPolicy` (default "Roteiro"),
  `TacticalPolicy` (rules), `NeuralPolicy` (small MLP that is **residual over Tactical**: zero weights == Tactical),
  `Trainer` (OpenAI-ES, Java only, parallel and deterministic by seed), `Observation`/`ActionSpace`/`ActionMask`
  (versioned observation schema; saved weights store `obs_schema` and action count so incompatible ones are refused).
  `Gym`, `ArenaTraining*`, `ComboSearch`, `Reward`, `PolicyRepository` (table `arena_policy`) sit in `arena/`.
- `seed/` — `SchemaManager` (migrations), `CatalogImporter` (YAML → SQLite), catalogs for champions, runes, item
  effects, shop rules, `ReferenceSeeder`.
- `repo/` — JdbcTemplate data access. `service/PlannerService` assembles reference data and runs the engine.
- `api/` — REST controllers under `/api/...` (planner, admin, champions, runes, combat, arena, planner lab, item icons).

**Data lives in YAML seeds, not code** (`backend/src/main/resources/seed/`, patch-suffixed `_7_3`): `loja_7_3.yml`
(171 shop items; header documents each field), `precos_status.yml`, `efeitos_itens_7_3.yml`, `campeoes_7_3.yml`,
`runas_7_3.yml` (+ `runas/` icons), `feiticos_7_3.yml`, `combate_7_3.yml`, `kits_7_3.yml`, `regras_loja.yml`.
New game data or modeled effects usually mean editing these files plus a test, not Java. `LojaCatalogTest` enforces
catalog consistency (components exist and cost less, every stat has a price).

**Schema changes**: bump `SchemaManager.VERSION` and add an idempotent `if (version < N && …)` migration step (version
is `PRAGMA user_version`); `schema.sql` runs afterwards and must stay idempotent. Spring's own SQL init is disabled.
Catalog re-import (Admin screen) upserts by name and preserves hand-edited items.

**Determinism is a hard invariant of the Arena**: same scenario/seed ⇒ identical result, also across threads (no
`identityHashCode`, unordered-hash iteration, or wall-clock in simulation logic). The golden tests and the trainer's
validation depend on it.

Frontend: React 18 + Vite 5 + Recharts, plain JS (no TypeScript), **no router** — `App.jsx` switches pages
(`pages/Planner`, `Combat`, `Arena`, `Admin`, `About`). `api.js` wraps all backend calls. Client-side game math
mirrors the backend in `abilityMath.js`, `effective.js`, `passives.js`, `runes.js`, `shopModel.js`, `skills.js` —
keep these in sync with the Java side when a rule changes.

## Branch work and design docs

`docs/proposta-ia-arena.md` is the running plan/log for the Arena AI work on branch `arena-ia`: phases and
"Rodadas" (rounds) split into parts, each part one commit with a progress note (✅, decisions that changed, benchmark
numbers) appended to the doc. Read its latest round before continuing Arena/AI work, and keep that log updated in the
same style.
