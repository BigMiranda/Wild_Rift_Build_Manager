package com.ornnplanner.engine;

import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Network;
import com.ornnplanner.arena.ai.Observation;
import com.ornnplanner.arena.ai.PolicyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Saved learned policies on a fresh SQLite file with the real schema. */
class PolicyRepositoryTest {

    private static PolicyRepository repo(Path dir) {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("t.db"));
        ds.setDriverClassName("org.sqlite.JDBC");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        return new PolicyRepository(new JdbcTemplate(ds));
    }

    private static PolicyRepository.Record record(String champion, String objective, boolean accepted, long seed) {
        double[] w = new double[Network.count(Network.DEFAULT_LAYERS)];
        Random rnd = new Random(seed);
        for (int i = 0; i < w.length; i++) {
            w[i] = rnd.nextGaussian();
        }
        PolicyRepository.Record r = new PolicyRepository.Record();
        r.champion = champion;
        r.objective = objective;
        r.accepted = accepted;
        r.betterVsScript = 0.7;
        r.obsSchema = Observation.SCHEMA_VERSION;
        r.actions = ActionSpace.SIZE;
        r.weights = PolicyRepository.Weights.of(new Network(Network.DEFAULT_LAYERS, w), 0.5);
        return r;
    }

    @Test
    void savesVersionsAndReadsTheWeightsBack(@TempDir Path dir) {
        PolicyRepository repo = repo(dir);
        PolicyRepository.Record a = repo.insert(record("Darius", "win_fast", true, 1));
        PolicyRepository.Record b = repo.insert(record("Darius", "win_fast", false, 2));
        repo.insert(record("Vayne", "damage", true, 3));
        assertEquals(1, a.version);
        assertEquals(2, b.version);
        assertEquals(2, repo.list("darius").size());
        assertEquals(3, repo.list(null).size());

        PolicyRepository.Record read = repo.find(a.id).orElseThrow();
        assertTrue(read.compatible());
        assertEquals(0.7, read.betterVsScript, 1e-12);
        assertArrayEquals(record("Darius", "win_fast", true, 1).weights.weights, read.weights.weights);
        assertEquals(0.5, read.weights.bonus, 1e-12);

        // the best is the latest accepted version of that objective (v2 was not accepted)
        assertEquals(a.id, repo.best("Darius", "win_fast").orElseThrow().id);
        assertFalse(repo.best("Darius", "damage").isPresent());

        assertTrue(repo.delete(a.id));
        assertFalse(repo.find(a.id).isPresent());
        assertEquals(3, repo.nextVersion("Darius"));   // versions keep counting
    }
}
