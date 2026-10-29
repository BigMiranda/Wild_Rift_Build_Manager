package com.ornnplanner.engine;

import com.ornnplanner.arena.ComboRepository;
import com.ornnplanner.arena.ai.PolicyRepository;
import com.ornnplanner.seed.SchemaManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schema migrations on a copy of a real database (never the file itself):
 * {@code ./mvnw test -Dtest=SchemaMigrationCopyTest -Darena.dbcopy=data/ornn-planner.db}.
 */
@EnabledIfSystemProperty(named = "arena.dbcopy", matches = ".+")
class SchemaMigrationCopyTest {

    @Test
    void migratesACopyOfTheRealDatabase(@TempDir Path dir) throws Exception {
        Path copy = dir.resolve("copy.db");
        Files.copy(Paths.get(System.getProperty("arena.dbcopy")), copy);
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite:" + copy, true);
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        int before = jdbc.queryForObject("PRAGMA user_version", Integer.class);
        int policies = jdbc.queryForObject("SELECT COUNT(*) FROM arena_policy", Integer.class);
        int builds = jdbc.queryForObject("SELECT COUNT(*) FROM build", Integer.class);
        int combos = count(jdbc, "arena_combo");
        int dummies = count(jdbc, "arena_dummy");
        new SchemaManager(jdbc, ds).migrate();
        int after = jdbc.queryForObject("PRAGMA user_version", Integer.class);
        System.out.printf("schema %d -> %d; %d builds, %d policies%n", before, after, builds, policies);
        assertTrue(after >= 17);
        assertEquals(builds, (int) jdbc.queryForObject("SELECT COUNT(*) FROM build", Integer.class));
        assertEquals(policies, (int) jdbc.queryForObject("SELECT COUNT(*) FROM arena_policy", Integer.class));
        assertEquals(combos, count(jdbc, "arena_combo"));
        assertEquals(dummies, count(jdbc, "arena_dummy"));
        assertEquals(0, count(jdbc, "effect_test"), "v23: the effectiveness tests' table, empty");
        // the saved policies still read, older layouts migrated on load
        PolicyRepository repo = new PolicyRepository(jdbc);
        for (PolicyRepository.Record r : repo.list(null)) {
            PolicyRepository.Record full = repo.find(r.id).orElseThrow();
            if (full.compatible()) {
                assertEquals(com.ornnplanner.arena.ai.Observation.SIZE, full.weights.layers[0], "v" + r.version + " " + r.champion);
            }
            System.out.printf("  %s v%d schema %d compatible %s migrated %s%n", r.champion, r.version, r.obsSchema,
                    r.compatible(), full.migratedFrom);
        }
        assertEquals(combos, new ComboRepository(jdbc).list(null).size());
        ds.destroy();
    }

    /** Rows of a table (0 when the copy is older than the table). */
    private static int count(JdbcTemplate jdbc, String table) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                Integer.class, table);
        return exists == null || exists == 0 ? 0 : jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
