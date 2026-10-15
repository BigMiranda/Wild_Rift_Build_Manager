package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ArenaModel.SafeZone;
import com.ornnplanner.arena.ArenaScore;
import com.ornnplanner.arena.ArenaTraining;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.arena.ai.PolicyRepository;
import com.ornnplanner.arena.ai.TacticalPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The safe zone and the objective "escapar". */
class ArenaEscapeTest {

    static SafeZone zone(double dps) {
        SafeZone z = new SafeZone();
        z.team = 0;
        z.depth = 1200;
        z.damagePerSecond = dps;
        return z;
    }

    private static Policy always(int a) {
        return (o, m) -> Policy.Decision.of(m.valid(a) ? a : ActionSpace.IDLE);
    }

    @Test
    void reachingTheZoneAliveIsAnEscape() {
        Arena.Input ornn = ArenaScenarios.champion("Ornn", 0, 2000, 0, 5000, 100, 90);
        ornn.policy = always(ActionSpace.RETREAT);
        Arena.Input darius = ArenaScenarios.champion("Darius", 1, 2600, 0, 5000, 100, 90);
        darius.f.total.put(com.ornnplanner.engine.Stats.MOVE_SPEED, 300.0);
        Result r = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withSafeZone(zone(0)).run(List.of(ornn, darius), List.of(), 30);
        assertNotNull(r.fighters.get(0).escapedAt);
        assertTrue(r.duration < 30, "the fight ends: nobody left on that side");
        assertEquals(-1, r.winner);
        assertTrue(ArenaScore.score(r, "escapar", 0, 0) > 40000);
        // without a zone, the same run goes to the limit
        Result none = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(List.of(ornn, darius), List.of(), 10);
        assertNull(none.fighters.get(0).escapedAt);
    }

    @Test
    void theTowerHurtsEnemiesInside() {
        Arena.Input ornn = ArenaScenarios.champion("Ornn", 0, 300, 400, 5000, 1, 90);
        ornn.policy = always(ActionSpace.IDLE);
        ornn.autoAbilities = false;
        Arena.Input ally = ArenaScenarios.champion("Ornn", 0, 5000, 0, 5000, 1, 90);   // already in the fight, outside
        ally.policy = always(ActionSpace.IDLE);
        Arena.Input darius = ArenaScenarios.champion("Darius", 1, 900, 0, 5000, 1, 90);
        darius.policy = always(ActionSpace.IDLE);
        darius.autoAbilities = false;
        Result r = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withSafeZone(zone(400))
                .run(List.of(ally, darius), List.of(), 3);
        assertEquals(1200, r.fighters.get(1).taken, 1e-6, "400/s for 3s");
    }

    // ------------------------------------------------------------------ the report

    private static PolicyRepository repo(Path dir) {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("t.db"));
        ds.setDriverClassName("org.sqlite.JDBC");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        return new PolicyRepository(new JdbcTemplate(ds));
    }

    /** Ornn at 25% of health, Darius right behind him (with Ghost), the blue tower 3100 away. */
    static List<Arena.Input> chase() {
        Arena.Input ornn = ArenaScenarios.champion("Ornn", 0, 4300, 0, 3200, 140, 110);
        ornn.f.ranks = new int[] {4, 3, 2, 2};
        ornn.startHealth = 0.25;
        ornn.f.spells.add("Flash");
        ornn.activeCooldowns.put("Flash", 150.0);
        Arena.Input darius = ArenaScenarios.champion("Darius", 1, 4550, 0, 3200, 170, 90);
        darius.f.spells.add("Fantasma");
        darius.activeCooldowns.put("Fantasma", 90.0);
        return List.of(ornn, darius);
    }

    /**
     * 🎯 Ornn × Darius with "escapar": the AI trained alone (predictions, events) against the tactical autopilot on the
     * same starts: escapes, recasts used to flee, pulls voided. Off by default ({@code -Darena.report=true}).
     */
    @EnabledIfSystemProperty(named = "arena.report", matches = "true")
    @Test
    void ornnFleesDarius(@TempDir Path dir) throws Exception {
        ArenaTraining t = new ArenaTraining(ArenaScenarios.CATALOG, ArenaScenarios.MAP, repo(dir));
        ArenaTraining.StageSpec s = new ArenaTraining.StageSpec();
        s.variants = List.of(chase());
        s.duration = 30;
        s.objective = "escapar";
        s.safeZone = zone(300);
        s.generations = 40;
        s.population = 24;
        s.episodesPerGeneration = 8;
        s.seed = 4;
        ArenaTraining.StageResult r = t.trainStage(s, new ArenaTraining.Listener() { });
        Policy ai = r.policy.policy(chase().get(0).priority);
        StringBuilder out = new StringBuilder();
        out.append(String.format("trained %d generations; accepted %s; better than script %.0f%%%n", r.generations,
                r.policy.accepted, 100 * r.policy.betterVsScript));
        for (String label : List.of("Tático", "IA (sozinha)")) {
            int escapes = 0;
            int deaths = 0;
            double fled = 0;
            double voided = 0;
            double usedR = 0;
            Random rnd = new Random(99);
            int n = 32;
            for (int i = 0; i < n; i++) {
                List<Arena.Input> in = new ArrayList<>();
                double shift = rnd.nextInt(1201) - 300;
                for (Arena.Input x : chase()) {
                    Arena.Input c = x.copy();
                    c.x += shift;
                    c.y = rnd.nextInt(601) - 300;
                    in.add(c);
                }
                in.get(0).policy = "Tático".equals(label) ? new TacticalPolicy(in.get(0).priority) : ai;
                in.get(1).policy = new TacticalPolicy(in.get(1).priority);
                Result fight = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withoutFrames().withSafeZone(zone(300))
                        .run(in, List.of(), 30);
                escapes += fight.fighters.get(0).escapedAt != null ? 1 : 0;
                deaths += fight.fighters.get(0).deathTime != null ? 1 : 0;
                fled += fight.fighters.get(0).events.getOrDefault("reconjuracao_fuga:R", 0.0);
                voided += fight.fighters.get(0).events.getOrDefault("deslocamento_anulado:puxao", 0.0);
                usedR += fight.fighters.get(0).events.getOrDefault("habilidade_acertou:R", 0.0)
                        + fight.fighters.get(0).events.getOrDefault("habilidade_errou:R", 0.0);
            }
            out.append(String.format("%s: %d/%d fugas, %d mortes, R usada %.0f vezes, %.0f cabeçadas para fugir, %.0f puxões anulados%n",
                    label, escapes, n, deaths, usedR, fled, voided));
        }
        Files.write(Paths.get("target/ornn-escape.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(out);
    }
}
