package com.ornnplanner.engine;

import com.ornnplanner.arena.ArenaChain;
import com.ornnplanner.arena.ArenaTraining;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Network;
import com.ornnplanner.arena.ai.Observation;
import com.ornnplanner.arena.ai.Plateau;
import com.ornnplanner.arena.ai.PolicyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chain training (Darius x Vayne, tiny stages), the plateau rule and the warm start. */
class ArenaChainTest {

    private static PolicyRepository repo(Path dir, String name) {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve(name));
        ds.setDriverClassName("org.sqlite.JDBC");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        return new PolicyRepository(new JdbcTemplate(ds));
    }

    private static ArenaChain chain(PolicyRepository repo) {
        return new ArenaChain(new ArenaTraining(ArenaScenarios.CATALOG, ArenaScenarios.MAP, repo), repo);
    }

    /** Darius (blue) against Vayne (red), two tiny rounds; acceptance at `accept`. */
    private static ArenaChain.Spec spec(double accept) {
        ArenaChain.Spec s = new ArenaChain.Spec();
        s.variants = List.of(List.of(ArenaScenarios.champion("Darius", 0, 3000, 0, 2800, 300, 90),
                ArenaScenarios.champion("Vayne", 1, 3900, 0, 2800, 160, 90)));
        s.duration = 30;
        s.scenario = "teste";
        s.slots = List.of(0, 1);
        s.generations = 2;
        s.population = 4;
        s.episodesPerGeneration = 2;
        s.pool = 4;
        s.selection = 4;
        s.validation = 4;
        s.maxRounds = 2;
        s.seed = 3;
        s.threads = 2;
        s.accept = accept;
        return s;
    }

    private static ArenaChain.Job job() {
        ArenaChain.Job j = new ArenaChain.Job();
        j.id = "c1";
        return j;
    }

    private static List<PolicyRepository.Record> saved(PolicyRepository repo) {
        List<PolicyRepository.Record> out = new ArrayList<>();
        for (PolicyRepository.Record r : repo.list(null)) {
            out.add(repo.find(r.id).orElseThrow());
        }
        out.sort(Comparator.comparingLong(r -> r.id));
        return out;
    }

    @Test
    void sameSeedSameVersions(@TempDir Path dir) {
        PolicyRepository a = repo(dir, "a.db");
        PolicyRepository b = repo(dir, "b.db");
        ArenaChain.Job ja = job();
        ArenaChain.Job jb = job();
        chain(a).run(ja, spec(0));
        chain(b).run(jb, spec(0));
        assertEquals("done", ja.status, ja.message);
        List<PolicyRepository.Record> ra = saved(a);
        List<PolicyRepository.Record> rb = saved(b);
        assertEquals(4, ra.size());   // 2 champions x 2 rounds
        assertEquals(ra.size(), rb.size());
        for (int i = 0; i < ra.size(); i++) {
            assertEquals(ra.get(i).champion, rb.get(i).champion);
            assertArrayEquals(ra.get(i).weights.weights, rb.get(i).weights.weights, "version " + i);
        }
        assertEquals("c1", ra.get(0).chainId);
        assertEquals(Integer.valueOf(2), ra.get(3).chainRound);
    }

    @Test
    void warmStartFromThePreviousAcceptedVersion(@TempDir Path dir) {
        PolicyRepository repo = repo(dir, "t.db");
        ArenaChain.Job j = job();
        chain(repo).run(j, spec(0));
        ArenaChain.Stage darius1 = j.stages.get(0);
        ArenaChain.Stage darius2 = j.stages.get(2);
        assertNull(darius1.parentId);
        assertEquals(darius1.policy.id, darius2.parentId);
        assertEquals(darius2.parentId, repo.find(darius2.policy.id).orElseThrow().parentId);
        // the last accepted versions go to the slots
        assertEquals(darius2.policy.id, j.latest.get(0));
        assertEquals(j.stages.get(3).policy.id, j.latest.get(1));
        // round 2 measured Darius against Vayne as it was in round 1
        assertNotNull(darius2.vsOldOpponents);
    }

    @Test
    void incompatibleVersionStartsFromZeroWithAWarning(@TempDir Path dir) {
        PolicyRepository repo = repo(dir, "t.db");
        PolicyRepository.Record old = new PolicyRepository.Record();
        old.champion = "Darius";
        old.objective = "win_fast";
        old.accepted = true;
        old.obsSchema = Observation.SCHEMA_VERSION - 1;
        old.actions = ActionSpace.SIZE;
        old.weights = PolicyRepository.Weights.of(Network.zeros(Network.DEFAULT_LAYERS), 0.5);
        repo.insert(old);
        ArenaChain.Spec s = spec(0);
        s.maxRounds = 1;
        ArenaChain.Job j = job();
        chain(repo).run(j, s);
        ArenaChain.Stage darius = j.stages.get(0);
        assertNull(darius.parentId);
        assertNotNull(darius.warning);
        assertTrue(darius.warning.contains("outra versão da arena"), darius.warning);
    }

    @Test
    void stopAfterCurrentSavesTheStageAndStartsNoOther(@TempDir Path dir) {
        PolicyRepository repo = repo(dir, "t.db");
        ArenaChain.Job j = job();
        chain(repo).onGeneration((job, s) -> job.stopAfterCurrent = true).run(j, spec(0));
        assertEquals("stopped", j.status);
        assertEquals("accepted", j.stages.get(0).status);
        assertEquals(2, j.stages.get(0).generation);   // the stage ran to its end
        assertEquals("skipped", j.stages.get(1).status);
        assertEquals(2, j.stages.size());
        assertEquals(1, repo.list(null).size());
    }

    @Test
    void cancelSavesNothing(@TempDir Path dir) {
        PolicyRepository repo = repo(dir, "t.db");
        ArenaChain.Job j = job();
        chain(repo).onGeneration((job, s) -> job.cancel = true).run(j, spec(0));
        assertEquals("cancelled", j.status);
        assertEquals("cancelled", j.stages.get(0).status);
        assertEquals(1, j.stages.get(0).generation);   // stopped right after the generation it was asked in
        assertEquals("skipped", j.stages.get(1).status);
        assertTrue(repo.list(null).isEmpty());
    }

    @Test
    void aRoundWithoutAcceptanceConverges(@TempDir Path dir) {
        PolicyRepository repo = repo(dir, "t.db");
        ArenaChain.Job j = job();
        chain(repo).run(j, spec(1.01));
        assertEquals("converged", j.status);
        assertEquals(1, j.round);
        assertEquals(2, j.stages.size());
        assertEquals("rejected", j.stages.get(0).status);
        assertEquals(2, repo.list(null).size());   // rejected versions are still saved
    }

    @Test
    void sameChampionInTwoSlotsTrainsOnce(@TempDir Path dir) {
        PolicyRepository repo = repo(dir, "t.db");
        ArenaChain.Spec s = spec(0);
        s.variants = List.of(List.of(ArenaScenarios.champion("Darius", 0, 3000, -100, 2800, 300, 90),
                ArenaScenarios.champion("Darius", 0, 3000, 100, 2800, 300, 90),
                ArenaScenarios.champion("Vayne", 1, 3900, 0, 2800, 160, 90)));
        s.slots = List.of(0, 1);
        s.maxRounds = 1;
        ArenaChain.Job j = job();
        chain(repo).run(j, s);
        assertEquals("skipped", j.stages.get(1).status);
        assertEquals(Integer.valueOf(0), j.stages.get(1).sameAs);
        assertEquals(j.latest.get(0), j.latest.get(1));
        assertEquals(1, repo.list(null).size());
    }

    // ------------------------------------------------------------------ plateau

    private static List<Double> curve(double... v) {
        List<Double> out = new ArrayList<>();
        for (double x : v) {
            out.add(x);
        }
        return out;
    }

    @Test
    void plateauFiresOnAFlatCurve() {
        Plateau p = new Plateau(2, 6, 8);
        List<Double> flat = curve(10, 20, 30, 40, 41, 41.5, 40, 41, 40.5, 41, 40.8, 41.2);
        assertFalse(p.reached(flat.subList(0, 7)), "before the minimum of generations");
        assertTrue(p.reached(flat), "flat for 6 generations");
        assertTrue(p.reached(curve(50, 50, 50, 50, 50, 50, 50, 50)));
    }

    @Test
    void plateauWaitsForTheMinimumAndForRealGains() {
        Plateau p = new Plateau(2, 6, 8);
        assertFalse(p.reached(curve(50, 50, 50, 50, 50, 50, 50)), "7 < 8 generations");
        assertFalse(p.reached(curve(10, 12, 14, 16, 18, 20, 22, 24, 26, 28)), "rising 2 points a generation");
        assertFalse(p.reached(curve(10, 10, 10, 10, 10, 10, 10, 10, 13)), "a gain of 3 points in the window");
        assertFalse(Plateau.off().reached(curve(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)));
    }

    /**
     * The two candidate plateau metrics on one real training (Darius x Vayne, 20 generations), with 16 selection
     * fights: how much each jumps from one generation to the next compared with its whole range (lower is steadier),
     * and when the plateau rule (2 points, 6 generations, at least 8) would stop on each.
     */
    @Test
    void plateauMetricsOnARealTraining(@TempDir Path dir) {
        PolicyRepository repo = repo(dir, "t.db");
        ArenaTraining t = new ArenaTraining(ArenaScenarios.CATALOG, ArenaScenarios.MAP, repo);
        ArenaTraining.StageSpec s = new ArenaTraining.StageSpec();
        s.variants = spec(0).variants;
        s.duration = 30;
        s.generations = 20;
        s.population = 16;
        s.episodesPerGeneration = 6;
        s.seed = 5;
        List<ArenaTraining.Point> points = new ArrayList<>();
        ArenaTraining.StageResult r = t.trainStage(s, new ArenaTraining.Listener() {
            @Override
            public void point(ArenaTraining.Point p) {
                points.add(p);
            }
        });
        assertEquals(20, points.size());
        assertNotNull(r.policy);
        List<Double> fitness = new ArrayList<>();
        List<Double> better = new ArrayList<>();
        points.forEach(p -> {
            fitness.add(p.fitnessPct);
            better.add(p.betterPct);
        });
        System.out.println("fitness %: " + fitness);
        System.out.println("better %:  " + better);
        System.out.printf("roughness: fitness %.2f, better %.2f; plateau at generation: fitness %s, better %s%n",
                roughness(fitness), roughness(better), firstPlateau(fitness), firstPlateau(better));
    }

    private static double roughness(List<Double> v) {
        double jumps = 0;
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (int i = 0; i < v.size(); i++) {
            lo = Math.min(lo, v.get(i));
            hi = Math.max(hi, v.get(i));
            if (i > 0) {
                jumps += Math.abs(v.get(i) - v.get(i - 1));
            }
        }
        return jumps / (v.size() - 1) / Math.max(1e-9, hi - lo);
    }

    private static Integer firstPlateau(List<Double> v) {
        Plateau p = new Plateau(2, 6, 8);
        for (int n = 1; n <= v.size(); n++) {
            if (p.reached(v.subList(0, n))) {
                return n;
            }
        }
        return null;
    }

    @Test
    void stageStopsAtAPlateauAndStillValidates(@TempDir Path dir) {
        PolicyRepository repo = repo(dir, "t.db");
        ArenaTraining t = new ArenaTraining(ArenaScenarios.CATALOG, ArenaScenarios.MAP, repo);
        ArenaTraining.StageSpec s = new ArenaTraining.StageSpec();
        s.variants = spec(0).variants;
        s.duration = 30;
        s.generations = 12;
        s.population = 4;
        s.episodesPerGeneration = 2;
        s.pool = 4;
        s.selection = 4;
        s.validation = 4;
        s.plateau = new Plateau(100, 2, 3);   // demands +100 points in 2 generations: always a plateau at 3
        ArenaTraining.StageResult r = t.trainStage(s, new ArenaTraining.Listener() { });
        assertTrue(r.plateau);
        assertEquals(3, r.generations);
        assertNotNull(r.policy);
        assertEquals(Integer.valueOf(3), r.policy.generations);
    }
}
