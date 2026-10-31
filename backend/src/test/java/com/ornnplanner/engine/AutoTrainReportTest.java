package com.ornnplanner.engine;

import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.arena.ArenaTrainingService;
import com.ornnplanner.arena.AutoBrain;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The automatic brain on a <b>copy</b> of a database (report style, opt-in): which AI it picks in a saved scenario, a
 * "Treinar automaticamente" until the clean victory, and the AIs of the classes the sheets cover:
 * {@code ./mvnw test -Dtest=AutoTrainReportTest -Dauto.report=data/ornn-planner.db}. Writes target/auto-report.txt.
 * Optional: -Dauto.scenario=2 -Dauto.fighter=0 -Dauto.classGenerations=15.
 */
@EnabledIfSystemProperty(named = "auto.report", matches = ".+")
@SpringBootTest(properties = {"server.port=0"})
class AutoTrainReportTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        Path copy = Files.createTempDirectory("auto-report").resolve("copy.db");
        Files.copy(Paths.get(System.getProperty("auto.report")), copy);
        r.add("planner.db-path", copy::toString);
    }

    @Autowired
    ArenaService arena;
    @Autowired
    ArenaTrainingService training;
    @Autowired
    BuildRepository builds;
    @Autowired
    PlannerService planner;

    private final StringBuilder out = new StringBuilder();

    private void line(String f, Object... a) {
        String s = String.format(Locale.ROOT, f, a);
        out.append(s).append('\n');
        System.out.println(s);
        try {
            Files.write(Paths.get("target/auto-report.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void describe(String when, ArenaModel.Scenario sc) {
        ArenaModel.Result r = arena.run(sc);
        for (AutoBrain.Choice c : r.brainChoices) {
            line("%s: slot %d, %s, %s, IA v%s de %s, venceu=%s limpa=%s morreu=%s tempo=%s", when, c.fighter, c.kind, c.brain, c.version, c.owner,
                    c.won, c.clean, c.died, c.time);
        }
        line("%s: vencedor %d em %.1f s; %s", when, r.winner, r.duration, r.fighters.stream()
                .map(f -> String.format(Locale.ROOT, "%s (morreu %s, Vida %.0f)", f.champion, f.deathTime, f.health)).reduce((a, b) -> a + "; " + b).orElse(""));
    }

    private ArenaTrainingService.Job waitFor(ArenaTrainingService.Job job) throws InterruptedException {
        while (job.finishedAt == null && !"cancelled".equals(job.status)) {
            Thread.sleep(500);
        }
        return job;
    }

    /** A build of a champion that has a sheet and no saved build: only in this copy. */
    private long draft(String unit, String... items) {
        Map<String, Long> ids = new LinkedHashMap<>();
        planner.itemViews().forEach(i -> ids.put(i.name, i.id));
        BuildRepository.Build b = new BuildRepository.Build();
        b.name = "Relatório " + unit;
        b.unitCode = unit;
        b.goldPerMin = 1000;
        b.xpPerMin = 650;
        b.steps = new ArrayList<>();
        for (String i : items) {
            b.steps.add(new BuildRepository.Step(ids.get(i), true));
        }
        b.folderId = builds.findBuildSummaries().get(0).folderId;
        return builds.insertBuild(b);
    }

    private long id(String name) {
        return builds.findBuildSummaries().stream().filter(b -> name.equals(b.name)).findFirst().orElseThrow().id;
    }

    @Test
    void report() throws Exception {
        long scenario = Long.getLong("auto.scenario", 2);
        int fighter = Integer.getInteger("auto.fighter", 0);
        ArenaModel.Scenario sc = arena.find(scenario);
        line("# Cenário %d: %s (%.0f s), cérebro salvo do slot %d: %s", scenario, sc.name, sc.duration, fighter, sc.fighters.get(fighter).brain);
        sc.fighters.get(fighter).brain = AutoBrain.AUTO;
        describe("antes", sc);
        long t0 = System.nanoTime();
        ArenaTrainingService.Job job = waitFor(training.startAuto(sc, fighter));
        line("treinar automaticamente: %s, %s, vitória=%s, geração %d de %d, %.1f s, partiu de %s, IA v%s", job.status, job.message, job.victory, job.generation,
                job.generations, (System.nanoTime() - t0) / 1e9, job.startedFrom, job.policy == null ? "?" : job.policy.version);
        describe("depois", sc);

        int gens = Integer.getInteger("auto.classGenerations", 15);
        if (gens <= 0) {
            return;
        }
        Map<String, List<Long>> classes = new LinkedHashMap<>();
        classes.put("Tanque", List.of(id("Big Carry v1"), id("Manopla dos Glacinatas"),
                draft("KSANTE", "Coração de Aço", "Armadura de Espinhos", "Presságio de Randuin", "Desespero Eterno")));
        classes.put("Lutador", List.of(id("Darius de CdC"), id("Armor Shredder")));
        classes.put("Atirador", List.of(draft("VAYNE", "Gume do Infinito", "Canhão Fumegante", "Lembranças do Lorde Dominik", "Sedenta por Sangue")));
        for (Map.Entry<String, List<Long>> e : classes.entrySet()) {
            ArenaTrainingService.ClassRequest q = new ArenaTrainingService.ClassRequest();
            q.scenario = arena.find(scenario);
            q.scenario.fighters.get(fighter).brain = "tactical";
            q.fighter = fighter;
            q.className = e.getKey();
            q.builds = e.getValue();
            q.generations = gens;
            long t1 = System.nanoTime();
            ArenaTrainingService.Job c = waitFor(training.startClass(q));
            line("classe %s: %d builds, %s, %s, IA v%s, %d gerações, %.1f s, melhor que o Roteiro %.0f%%, que o Tático %.0f%%, aceita=%s", e.getKey(), e.getValue().size(),
                    c.status, c.message, c.policy == null ? "?" : c.policy.version, c.generation, (System.nanoTime() - t1) / 1e9,
                    c.policy == null ? 0 : 100 * c.policy.betterVsScript, c.policy == null ? 0 : 100 * c.policy.betterVsTactical, c.policy != null && c.policy.accepted);
        }
    }
}
