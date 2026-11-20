package com.ornnplanner.arena.draft;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rodada 20.b, Parte 5, on a <b>copy</b> of a database (opt-in): {@code ./mvnw test -Dtest=EncounterRiftProbeTest
 * -Ddraft.rift=data/ornn-planner.db}. What the first encounter would cost with minions and the tower (the Arena on the
 * whole map, the Barão lane's middle) against the single lane: the same fights, timed. The "refinar" button is offered
 * only if one lane's refinement fits in about 30 s. Numbers to target/draft-rift-probe.txt.
 */
@EnabledIfSystemProperty(named = "draft.rift", matches = ".+")
@SpringBootTest(properties = {"server.port=0"})
class EncounterRiftProbeTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        Path copy = Files.createTempDirectory("draft-rift").resolve("copy.db");
        Files.copy(Paths.get(System.getProperty("draft.rift")), copy);
        r.add("planner.db-path", copy::toString);
    }

    @Autowired
    DraftTablesService tables;
    @Autowired
    ArenaService arena;

    /** The point at a fraction of a lane's length, and the lane's direction there. */
    static double[] along(double[][] p, double f) {
        double total = 0;
        for (int i = 1; i < p.length; i++) {
            total += Math.hypot(p[i][0] - p[i - 1][0], p[i][1] - p[i - 1][1]);
        }
        double want = total * f;
        for (int i = 1; i < p.length; i++) {
            double seg = Math.hypot(p[i][0] - p[i - 1][0], p[i][1] - p[i - 1][1]);
            if (want <= seg) {
                double t = want / seg;
                return new double[] {p[i - 1][0] + t * (p[i][0] - p[i - 1][0]), p[i - 1][1] + t * (p[i][1] - p[i - 1][1]),
                    (p[i][0] - p[i - 1][0]) / seg, (p[i][1] - p[i - 1][1]) / seg};
            }
            want -= seg;
        }
        return new double[] {p[p.length - 1][0], p[p.length - 1][1], 1, 0};
    }

    @Test
    void probe() throws IOException {
        StringBuilder sb = new StringBuilder();
        double minute = 1.4;
        Arena.Input ornn = tables.prepare("Ornn", minute, 0);
        Arena.Input darius = tables.prepare("Darius", minute, 1);
        int n = 18;   // one ally start x the likely enemy start, 3 levels x 3 styles x 2 starts
        long t0 = System.nanoTime();
        for (int k = 0; k < n; k++) {
            tables.raceWithTrade(List.of(ornn), List.of(darius), k, 25, 4);
        }
        double plain = (System.nanoTime() - t0) / 1e9;
        double[] mid = along(arena.riftMap().lanes.get("barao"), 0.5);
        ArenaModel.Scenario sc = new ArenaModel.Scenario();
        sc.duration = 25;
        sc.rift = new ArenaModel.RiftOptions();
        sc.rift.minute = minute;
        sc.rift.minions = true;
        sc.rift.towers = true;
        sc.rift.jungle = false;
        sc.rift.plants = false;
        sc.rift.objectives = false;
        sc.rift.vision = false;
        sc.rift.around = new double[] {mid[0], mid[1], 3000};
        sc.fighters = new ArrayList<>();
        long t1 = System.nanoTime();
        int died = 0;
        for (int k = 0; k < n; k++) {
            Arena.Input a = ornn.copy();
            Arena.Input b = darius.copy();
            a.x = mid[0] - 350 * mid[2];
            a.y = mid[1] - 350 * mid[3];
            b.x = mid[0] + 350 * mid[2];
            b.y = mid[1] + 350 * mid[3];
            ArenaModel.Result r = arena.matchFight(new ArrayList<>(List.of(a, b)), sc, 999);
            died += r.fighters.get(0).deathTime != null || r.fighters.get(1).deathTime != null ? 1 : 0;
        }
        double rift = (System.nanoTime() - t1) / 1e9;
        double perLaneRefine = rift / n * 18;
        sb.append(String.format(Locale.ROOT, "rota única: %d lutas em %.2f s (%.0f ms cada)%n", n, plain, 1000 * plain / n));
        sb.append(String.format(Locale.ROOT, "mapa com tropas e torre: %d lutas em %.2f s (%.0f ms cada; %d com morte)%n", n, rift, 1000 * rift / n, died));
        sb.append(String.format(Locale.ROOT, "x%.1f; o encontro completo (1.800 lutas) no mapa: ~%.0f s; refinar 18 lutas: ~%.1f s%n",
                rift / Math.max(1e-9, plain), rift / n * 1800, perLaneRefine));
        Files.createDirectories(Paths.get("target"));
        Files.write(Paths.get("target/draft-rift-probe.txt"), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(sb);
    }
}
