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
        req.limit = Double.parseDouble(System.getProperty("match.limit", "20"));
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

    private static String nl() {
        return System.lineSeparator();
    }

    private static String mmss(double seconds) {
        return String.format("%d:%02d", (int) seconds / 60, (int) seconds % 60);
    }

    /**
     * The calibration of the fights (-Dmatch.probe=true): over many seeds, in parallel, the script against itself (length,
     * deaths, fights) and against the script that never goes back to buy above half health (does buying pay?).
     */
    private void probe(StringBuilder sb, int seeds) {
        MatchService.Draft d = matches.draft(request(1));
        com.ornnplanner.arena.rift.ScriptedMacro script = (com.ornnplanner.arena.rift.ScriptedMacro) matches.scripted();
        com.ornnplanner.arena.rift.MacroPolicy noBuy = (m, me) -> {
            com.ornnplanner.arena.rift.MacroPolicy.Intent i = script.suggest(m, me).intent;
            if (i.goal != com.ornnplanner.arena.rift.MacroPolicy.Goal.RECALL || me.hpFraction() < 0.5) {
                return i;
            }
            int a = "selva".equals(me.role) && m.nextCamp(me) != null ? com.ornnplanner.arena.rift.MacroActions.JUNGLE : com.ornnplanner.arena.rift.MacroActions.OWN_LANE;
            return com.ornnplanner.arena.rift.MacroActions.intent(a, m, me);
        };
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        List<java.util.concurrent.Future<MatchModel.Result>> even = new ArrayList<>();
        List<java.util.concurrent.Future<MatchModel.Result>> blue = new ArrayList<>();
        List<java.util.concurrent.Future<MatchModel.Result>> red = new ArrayList<>();
        for (long s = 1; s <= seeds; s++) {
            long seed = 500 + s;
            even.add(pool.submit(() -> matches.run(d, seed, 30, script, script)));
            blue.add(pool.submit(() -> matches.run(d, seed, 30, noBuy, script)));
            red.add(pool.submit(() -> matches.run(d, seed, 30, script, noBuy)));
        }
        try {
            double minutes = 0;
            int ended = 0;
            int deaths = 0;
            int fights = 0;
            int purchases = 0;
            List<Double> lengths = new ArrayList<>();
            for (java.util.concurrent.Future<MatchModel.Result> f : even) {
                MatchModel.Result r = f.get();
                if (r.winner >= 0) {
                    ended++;
                    minutes += r.seconds / 60;
                    lengths.add(r.seconds / 60);
                }
                deaths += r.players.stream().mapToInt(p -> p.deaths).sum();
                fights += r.fights.size();
                purchases += r.players.stream().mapToInt(p -> p.purchases.size()).sum();
            }
            java.util.Collections.sort(lengths);
            double score = 0;
            for (int i = 0; i < seeds; i++) {
                MatchModel.Result b = blue.get(i).get();
                MatchModel.Result r = red.get(i).get();
                score += b.winner == 0 ? 1 : b.winner < 0 ? 0.5 : 0;
                score += r.winner == 1 ? 1 : r.winner < 0 ? 0.5 : 0;
            }
            sb.append(String.format("probe (%d seeds, limit 30): script vs script ended %d (median %.1f min, range %.1f-%.1f), %.1f deaths, %.1f fights, %.1f items per match%n",
                    seeds, ended, lengths.isEmpty() ? 0 : lengths.get(lengths.size() / 2), lengths.isEmpty() ? 0 : lengths.get(0),
                    lengths.isEmpty() ? 0 : lengths.get(lengths.size() - 1), deaths / (double) seeds, fights / (double) seeds, purchases / (double) seeds));
            sb.append(String.format("probe: never back to buy above half health vs script: %.1f%% (%d games)%n", 100 * score / (2 * seeds), 2 * seeds));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void report() throws IOException {
        StringBuilder sb = new StringBuilder();
        // the pace over more seeds (-Dmatch.seeds=12): when the first tower falls and when the match ends
        int seeds = Integer.parseInt(System.getProperty("match.seeds", "3"));
        List<String> pace = new ArrayList<>();
        for (long seed = 1; seed <= seeds; seed++) {
            MatchModel.Result r = matches.run(request(seed));
            pace.add(String.format("%d: %s, %d structures (%d-%d), first tower %s", seed, r.winner < 0 ? "draw" : mmss(r.seconds),
                    r.events.stream().filter(e -> e.text.contains(" caiu")).count(), r.events.stream().filter(e -> e.text.contains(" caiu") && Integer.valueOf(0).equals(e.team)).count(),
                    r.events.stream().filter(e -> e.text.contains(" caiu") && Integer.valueOf(1).equals(e.team)).count(),
                    r.events.stream().filter(e -> e.text.contains(" caiu")).findFirst().map(e -> mmss(e.t)).orElse("-")));
            if (seed > 3) {
                continue;
            }
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
            // the match's pace: when each structure fell, and who died to the towers
            StringBuilder fell = new StringBuilder();
            r.events.stream().filter(e -> e.text.contains(" caiu")).forEach(e -> fell.append(String.format(" %s %s;", mmss(e.t), e.text.replace("torre ", "").replace(" da rota", "").replace(" caiu", ""))));
            sb.append(String.format("  structures (%d):%s%n", r.events.stream().filter(e -> e.text.contains(" caiu")).count(), fell));
            sb.append(String.format("  deaths to towers: %d; first at %s%n", r.events.stream().filter(e -> e.text.contains("morreu para")).count(),
                    r.events.stream().filter(e -> e.text.contains("morreu para")).findFirst().map(e -> mmss(e.t)).orElse("-")));
        }
        sb.append("pace: ").append(String.join(" | ", pace)).append(nl());
        if (Boolean.getBoolean("match.probe")) {
            probe(sb, Math.max(seeds, 12));
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
