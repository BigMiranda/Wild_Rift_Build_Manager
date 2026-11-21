package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.arena.draft.DraftTablesService;
import com.ornnplanner.arena.league.LeagueService;
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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * Rodada 21, part 5 (opt-in): what the draft tables cost with the automatic brain against the tactical_stand they used,
 * on a copy: the same pairs of champions, 1 x 1 at a minute, the lifebar race of each with both brains.
 * <pre>./mvnw test -Dtest=DraftBrainCostTest -Ddraftbrain.db=data/ornn-planner.db [-Ddraftbrain.pairs=40]</pre>
 */
@SpringBootTest(properties = {"server.port=0"})
@EnabledIfSystemProperty(named = "draftbrain.db", matches = ".+")
class DraftBrainCostTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        Path copy = Paths.get("target/draftbrain/copy.db");
        Files.createDirectories(copy.getParent());
        Files.copy(Paths.get(System.getProperty("draftbrain.db")), copy, StandardCopyOption.REPLACE_EXISTING);
        r.add("planner.db-path", copy::toString);
    }

    @Autowired
    DraftTablesService tables;
    @Autowired
    ArenaService arena;
    @Autowired
    LeagueService league;

    @Test
    void autoAgainstStand() throws IOException {
        int n = Integer.getInteger("draftbrain.pairs", 40);
        List<String> pool = new ArrayList<>(league.ofClass("Lutador"));
        pool.addAll(league.ofClass("Tanque"));
        List<String[]> pairs = new ArrayList<>();
        for (int i = 0; i < pool.size() && pairs.size() < n; i++) {
            for (int j = i + 1; j < pool.size() && pairs.size() < n; j++) {
                pairs.add(new String[] {pool.get(i), pool.get(j)});
            }
        }
        double minute = 10;
        int starts = 3;
        double duration = 30;
        double[] secs = new double[2];
        double[] spread = new double[2];
        List<List<Double>> races = new ArrayList<>();
        for (int brain = 0; brain < 2; brain++) {
            long t0 = System.nanoTime();
            List<Callable<Double>> jobs = new ArrayList<>();
            for (String[] p : pairs) {
                Arena.Input a = tables.prepare(p[0], minute, 0);
                Arena.Input b = tables.prepare(p[1], minute, 1);
                if (brain == 1) {
                    a = arena.withBrain(a, ArenaService.STAND);
                    b = arena.withBrain(b, ArenaService.STAND);
                }
                Arena.Input x = a;
                Arena.Input y = b;
                jobs.add(() -> {
                    double s = 0;
                    for (int k = 0; k < starts; k++) {
                        s += tables.race(List.of(x), List.of(y), k, duration);
                    }
                    return s / starts;
                });
            }
            List<Double> r = ArenaService.parallel(jobs);
            secs[brain] = (System.nanoTime() - t0) / 1e9;
            for (double v : r) {
                spread[brain] += Math.abs(v - 50) / r.size();
            }
            races.add(r);
        }
        double diff = 0;
        int flips = 0;
        for (int i = 0; i < pairs.size(); i++) {
            diff += Math.abs(races.get(0).get(i) - races.get(1).get(i)) / pairs.size();
            flips += (races.get(0).get(i) - 50) * (races.get(1).get(i) - 50) < 0 ? 1 : 0;
        }
        String s = String.format(Locale.ROOT, "%d pares, %d largadas, minuto %.0f: Automático %.1f s, tactical_stand %.1f s (%.2fx); "
                        + "|corrida - 50| médio %.1f x %.1f; diferença média %.1f pontos; favorito trocado em %d pares",
                pairs.size(), starts, minute, secs[0], secs[1], secs[0] / Math.max(1e-9, secs[1]), spread[0], spread[1], diff, flips);
        System.out.println(s);
        Files.write(Paths.get("target/draftbrain/report.txt"), s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
