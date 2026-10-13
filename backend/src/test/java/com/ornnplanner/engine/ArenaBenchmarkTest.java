package com.ornnplanner.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fights per second without frames, on one thread and on all cores: the budget of a training run
 * (population x scenarios x generations). Off by default; run it with
 * {@code ./mvnw test -Dtest=ArenaBenchmarkTest -Darena.bench=true}.
 */
@EnabledIfSystemProperty(named = "arena.bench", matches = "true")
class ArenaBenchmarkTest {

    private static final double SECONDS = 8;

    @Test
    void fightsPerSecond() throws Exception {
        List<ArenaScenarios.Scenario> all = ArenaScenarios.all();
        List<ArenaScenarios.Scenario> longFights = new ArrayList<>();
        List<ArenaScenarios.Scenario> duels = new ArrayList<>();
        for (ArenaScenarios.Scenario s : all) {
            if (s.name.startsWith("long")) {
                longFights.add(s);
            } else if (s.name.startsWith("duel")) {
                duels.add(s);
            }
        }
        double simulated = 0;
        for (ArenaScenarios.Scenario s : duels) {
            simulated += s.run(false).duration;
        }
        System.out.printf("%nduels on autopilot: mean %.1fs of fight each%n", simulated / duels.size());
        int cores = Runtime.getRuntime().availableProcessors();
        for (int threads : new int[] {1, cores}) {
            report("long 1v1 (60s, to the limit)", longFights, threads);
            report("duels 1v1 (until a death)", duels, threads);
        }
    }

    private static void report(String label, List<ArenaScenarios.Scenario> scenarios, int threads) throws Exception {
        run(scenarios, threads, 2);   // warm-up (JIT)
        AtomicInteger fights = new AtomicInteger();
        double[] fightSeconds = new double[1];
        long t0 = System.nanoTime();
        double elapsed = run(scenarios, threads, SECONDS, fights, fightSeconds);
        System.out.printf("%-30s %2d thread(s): %7.1f fights/s, %8.0f simulated s/s (%d fights in %.1fs)%n",
                label, threads, fights.get() / elapsed, fightSeconds[0] / elapsed, fights.get(), (System.nanoTime() - t0) / 1e9);
    }

    private static void run(List<ArenaScenarios.Scenario> scenarios, int threads, double seconds) throws Exception {
        run(scenarios, threads, seconds, new AtomicInteger(), new double[1]);
    }

    private static double run(List<ArenaScenarios.Scenario> scenarios, int threads, double seconds, AtomicInteger fights,
                              double[] fightSeconds) throws Exception {
        long end = System.nanoTime() + (long) (seconds * 1e9);
        long start = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Double>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int offset = t;
                futures.add(pool.submit(() -> {
                    double sim = 0;
                    for (int i = offset; System.nanoTime() < end; i++) {
                        sim += scenarios.get(i % scenarios.size()).run(false).duration;
                        fights.incrementAndGet();
                    }
                    return sim;
                }));
            }
            for (Future<Double> f : futures) {
                fightSeconds[0] += f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        return (System.nanoTime() - start) / 1e9;
    }
}
