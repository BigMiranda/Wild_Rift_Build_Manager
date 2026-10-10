package com.ornnplanner.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.util.List;

/**
 * Applies migrations, then the idempotent schema.sql, before anything reads the database.
 * The schema version lives in SQLite's {@code PRAGMA user_version}.
 */
@Component
public class SchemaManager {

    private static final Logger log = LoggerFactory.getLogger(SchemaManager.class);
    static final int VERSION = 9;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    /** Set when a migration added catalog data that only a re-import can fill. */
    private boolean catalogReimportNeeded;

    public SchemaManager(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void migrate() {
        Integer current = jdbc.queryForObject("PRAGMA user_version", Integer.class);
        int version = current == null ? 0 : current;
        if (version < 2 && tableExists("item")) {
            // v2 replaces the changchiyou catalog (English names) with the one transcribed from the shop (pt-BR).
            // Item ids change, so purchase sequences pointing at old items cannot be kept.
            Integer steps = jdbc.queryForObject("SELECT COUNT(*) FROM build_step", Integer.class);
            log.warn("Migrating to schema v2: replacing the item catalog; {} purchase step(s) of saved builds are removed", steps);
            jdbc.execute("PRAGMA foreign_keys = OFF");
            jdbc.execute("DELETE FROM build_step");
            for (String t : List.of("item_component", "item_stat", "item_passive", "item", "stat_def")) {
                jdbc.execute("DROP TABLE IF EXISTS " + t);
            }
            jdbc.execute("PRAGMA foreign_keys = ON");
            if (tableExists("build") && !columnExists("build", "include_conditional")) {
                jdbc.execute("ALTER TABLE build ADD COLUMN include_conditional INTEGER NOT NULL DEFAULT 1");
            }
        }
        if (version < 3 && tableExists("build_step") && !columnExists("build_step", "include_conditional")) {
            // v3: conditional effects are chosen per purchase; existing steps inherit their build's old choice.
            jdbc.execute("ALTER TABLE build_step ADD COLUMN include_conditional INTEGER NOT NULL DEFAULT 1");
            if (columnExists("build", "include_conditional")) {
                jdbc.execute("UPDATE build_step SET include_conditional = "
                        + "(SELECT b.include_conditional FROM build b WHERE b.id = build_step.build_id)");
            }
        }
        if (version < 4 && tableExists("item") && !columnExists("item", "exclusive_groups")) {
            // v4: exclusive groups come from the catalog file; ReferenceSeeder re-imports it (edited items are kept).
            jdbc.execute("ALTER TABLE item ADD COLUMN exclusive_groups TEXT");
            catalogReimportNeeded = true;
        }
        if (version < 5 && tableExists("build") && !columnExists("build", "assume_half_items")) {
            // v5: per-build options to assume the purchase of missing half / smaller items.
            jdbc.execute("ALTER TABLE build ADD COLUMN assume_half_items INTEGER NOT NULL DEFAULT 0");
            jdbc.execute("ALTER TABLE build ADD COLUMN assume_small_items INTEGER NOT NULL DEFAULT 0");
        }
        if (version < 6 && tableExists("build") && !columnExists("build", "rune_page")) {
            // v6: rune page and summoner spells, stored as JSON.
            jdbc.execute("ALTER TABLE build ADD COLUMN rune_page TEXT");
            jdbc.execute("ALTER TABLE build ADD COLUMN spells TEXT");
        }
        if (version < 7 && tableExists("build") && !columnExists("build", "match_end")) {
            jdbc.execute("ALTER TABLE build ADD COLUMN match_end REAL"); // v7: end of the match
        }
        if (version < 8 && tableExists("build") && !columnExists("build", "champion_setup")) {
            // v8: skill order and champion effect choices (options, conditional, stack rates), as JSON.
            jdbc.execute("ALTER TABLE build ADD COLUMN champion_setup TEXT");
        }
        if (version < 9 && tableExists("stat_def") && !columnExists("stat_def", "factor")) {
            // v9: stats priced as a share of another one (Ultimate Haste, shields and heals as temporary health...),
            // and the item effect choices of a build (on / off, options, stack rates), as JSON.
            jdbc.execute("ALTER TABLE stat_def ADD COLUMN factor REAL");
            catalogReimportNeeded = true;
        }
        if (version < 9 && tableExists("build") && !columnExists("build", "item_setup")) {
            jdbc.execute("ALTER TABLE build ADD COLUMN item_setup TEXT");
        }
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        if (version < VERSION) {
            jdbc.execute("PRAGMA user_version = " + VERSION);
        }
    }

    public boolean isCatalogReimportNeeded() {
        return catalogReimportNeeded;
    }

    private boolean tableExists(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                Integer.class, table);
        return n != null && n > 0;
    }

    private boolean columnExists(String table, String column) {
        return jdbc.queryForList("PRAGMA table_info(" + table + ")").stream()
                .anyMatch(r -> column.equalsIgnoreCase(String.valueOf(r.get("name"))));
    }
}
