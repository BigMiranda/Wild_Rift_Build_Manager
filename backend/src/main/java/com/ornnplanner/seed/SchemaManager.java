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
    static final int VERSION = 2;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

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
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        if (version < VERSION) {
            jdbc.execute("PRAGMA user_version = " + VERSION);
        }
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
