package com.ornnplanner.engine;

import com.ornnplanner.arena.MatchService;
import com.ornnplanner.arena.rift.MatchModel;
import com.ornnplanner.repo.BuildRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A whole match of the saved builds on a <b>copy</b> of a database (report style, opt-in), with its cost and the gold and
 * experience against the builds':
 * {@code ./mvnw test -Dtest=MatchReportTest -Dmatch.report=data/ornn-planner.db}. Writes target/match-report.txt.
 */
@EnabledIfSystemProperty(named = "match.report", matches = ".+")
@SpringBootTest(properties = {"server.port=0"})
class MatchReportTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        Path copy = Files.createTempDirectory("match-report").resolve("copy.db");
        Files.copy(Paths.get(System.getProperty("match.report")), copy);
        r.add("planner.db-path", copy::toString);
    }

    @Autowired
    MatchService matches;
    @Autowired
    BuildRepository builds;

    private MatchModel.Request request(long seed) {
        List<BuildRepository.Build> all = builds.findBuildSummaries();
        List<Long> ids = new ArrayList<>();
        for (String code : List.of("RUMBLE", "ORNN", "RENEKTON", "DARIUS", "ORNN")) {
            all.stream().filter(b -> code.equals(b.unitCode) && !ids.contains(b.id)).findFirst().ifPresent(b -> ids.add(b.id));
        }
        MatchModel.Request req = new MatchModel.Request();
        req.seed = seed;
        req.limit = 20;
        for (int team = 0; team < 2; team++) {
            for (long id : ids) {
                MatchModel.Slot s = new MatchModel.Slot();
                s.buildId = id;
                s.team = team;
                req.slots.add(s);
            }
        }
        return req;
    }

    @Test
    void report() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (long seed = 1; seed <= 3; seed++) {
            MatchModel.Result r = matches.run(request(seed));
            sb.append(String.format("seed %d: winner %d at %.0f s; %.1f s to run, %.1f s in %d fights%n", seed, r.winner, r.seconds, r.runSeconds, r.fightSeconds, r.fights.size()));
            for (MatchModel.Player p : r.players) {
                sb.append(String.format("  %d %-9s %-8s lv%-2d %d/%d gold/min %5.0f (build %5.0f) xp/min %5.0f (build %5.0f) cs %d+%d items %d%n", p.team, p.champion, p.role, p.level, p.kills, p.deaths,
                        p.goldPerMin, p.plannedGoldPerMin, p.xpPerMin, p.plannedXpPerMin, p.minions, p.monsters, p.purchases.size()));
            }
            r.events.stream().limit(14).forEach(e -> sb.append(String.format("    %.0f %s%n", e.t, e.text)));
            // the two-tempo decisions (Rodada 10): how many of each, and a few of them
            for (String kind : java.util.List.of("recuou para o Frutomel", "comeu o Frutomel", "voltou à base a pé", "para voltar de Teleporte",
                    "voltou à rota de Teleporte", "comprou")) {
                sb.append(String.format("  dois tempos: %-28s %d%n", kind, r.events.stream().filter(e -> e.text.contains(kind)).count()));
            }
            r.events.stream().filter(e -> e.text.contains("Frutomel")).limit(4).forEach(e -> sb.append(String.format("    %.0f %s%n", e.t, e.text)));
            r.events.stream().filter(e -> e.text.contains("Teleporte")).limit(2).forEach(e -> sb.append(String.format("    %.0f %s%n", e.t, e.text)));
        }
        MatchModel.Result a = matches.run(request(5));
        MatchModel.Result b = matches.run(request(5));
        assertEquals(a.seconds, b.seconds);
        assertEquals(a.fights.size(), b.fights.size());
        // the cost of a training generation (proposta-macro-treinada.md, Parte 1): one draft played many times
        MatchService.Draft d = matches.draft(request(1));
        com.ornnplanner.arena.rift.MacroPolicy script = matches.scripted();
        matches.run(d, 100, 20, script, script);
        long t0 = System.nanoTime();
        int one = 6;
        for (int i = 0; i < one; i++) {
            matches.run(d, 200 + i, 20, script, script);
        }
        double perMatch = (System.nanoTime() - t0) / 1e9 / one;
        int threads = Runtime.getRuntime().availableProcessors();
        int many = threads * 2;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        long t1 = System.nanoTime();
        List<java.util.concurrent.Future<MatchModel.Result>> out = new ArrayList<>();
        for (int i = 0; i < many; i++) {
            long seed = 300 + i;
            out.add(pool.submit(() -> matches.run(d, seed, 20, script, script)));
        }
        for (java.util.concurrent.Future<MatchModel.Result> f : out) {
            try {
                f.get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        pool.shutdown();
        double wall = (System.nanoTime() - t1) / 1e9;
        sb.append(String.format("training cost: %.2f s per match on a warm draft (1 thread); %d matches on %d threads in %.1f s = %.2f matches/s; %d fighters made%n",
                perMatch, many, threads, wall, many / wall, d.fightersMade()));
        System.out.println(sb);
        Files.write(Paths.get("target/match-report.txt"), sb.toString().getBytes("UTF-8"));
    }
}
