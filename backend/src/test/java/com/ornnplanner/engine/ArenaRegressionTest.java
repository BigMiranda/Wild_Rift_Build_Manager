package com.ornnplanner.engine;

import com.ornnplanner.arena.ArenaModel.Result;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the arena's results: the fixed scenarios of {@link ArenaScenarios} must give exactly the results recorded in
 * {@code arena/golden-fingerprints.txt} (a refactor of the decision logic must not change any fight), and the same
 * fight must give the same result run twice, or on many threads at once sharing the same inputs.
 *
 * <p>To record the golden file again after an intended change of behavior:
 * {@code ./mvnw test -Dtest=ArenaRegressionTest -Darena.golden.write=true}.
 */
class ArenaRegressionTest {

    private static final Path GOLDEN = Paths.get("src/test/resources/arena/golden-fingerprints.txt");

    @Test
    void fixedScenariosGiveTheRecordedResults() throws Exception {
        Map<String, String> now = new LinkedHashMap<>();
        Map<String, String> lines = new LinkedHashMap<>();
        for (ArenaScenarios.Scenario s : ArenaScenarios.all()) {
            Result r = s.run(true);
            now.put(s.name, ArenaScenarios.fingerprint(r));
            lines.put(s.name, ArenaScenarios.summary(r));
        }
        if (Boolean.getBoolean("arena.golden.write")) {
            List<String> out = new ArrayList<>();
            now.forEach((name, fp) -> out.add(fp + "\t" + name + "\t" + lines.get(name)));
            Files.createDirectories(GOLDEN.getParent());
            Files.write(GOLDEN, out, StandardCharsets.UTF_8);
            return;
        }
        assertTrue(Files.exists(GOLDEN), "missing " + GOLDEN + ": record it with -Darena.golden.write=true");
        Map<String, String> golden = new LinkedHashMap<>();
        Map<String, String> goldenLines = new LinkedHashMap<>();
        for (String l : Files.readAllLines(GOLDEN, StandardCharsets.UTF_8)) {
            String[] p = l.split("\t");
            golden.put(p[1], p[0]);
            goldenLines.put(p[1], p.length > 2 ? p[2] : "");
        }
        assertEquals(golden.keySet(), now.keySet(), "the scenario list changed");
        List<String> diffs = new ArrayList<>();
        golden.forEach((name, fp) -> {
            if (!fp.equals(now.get(name))) {
                diffs.add(name + "\n  before: " + goldenLines.get(name) + "\n  now:    " + lines.get(name));
            }
        });
        assertTrue(diffs.isEmpty(), "fights changed:\n" + String.join("\n", diffs));
    }

    @Test
    void sameFightTwiceGivesTheSameResult() {
        for (ArenaScenarios.Scenario s : ArenaScenarios.all()) {
            assertEquals(ArenaScenarios.fingerprint(s.run(true)), ArenaScenarios.fingerprint(s.run(true)), s.name);
        }
    }

    /** Many arenas at once, all reading the same input objects (as a parallel trainer would): identical results. */
    @Test
    void parallelRunsOnSharedInputsMatchTheSequentialOnes() throws Exception {
        List<ArenaScenarios.Scenario> scenarios = ArenaScenarios.all();
        Map<String, String> sequential = new LinkedHashMap<>();
        for (ArenaScenarios.Scenario s : scenarios) {
            sequential.put(s.name, ArenaScenarios.fingerprint(s.run(false)));
        }
        int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<String> names = new ArrayList<>();
            List<Future<String>> futures = new ArrayList<>();
            for (ArenaScenarios.Scenario s : scenarios) {
                List<com.ornnplanner.arena.Arena.Input> shared = s.inputs.get();
                for (int copy = 0; copy < 4; copy++) {
                    names.add(s.name);
                    futures.add(pool.submit(() -> ArenaScenarios.fingerprint(new com.ornnplanner.arena.Arena(
                            ArenaScenarios.CATALOG, ArenaScenarios.MAP).withoutFrames().run(shared, s.actions.get(), s.duration))));
                }
            }
            for (int i = 0; i < futures.size(); i++) {
                assertEquals(sequential.get(names.get(i)), futures.get(i).get(), names.get(i));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
