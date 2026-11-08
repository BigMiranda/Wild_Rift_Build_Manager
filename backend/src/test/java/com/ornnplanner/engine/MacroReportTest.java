package com.ornnplanner.engine;

import com.ornnplanner.arena.MacroTraining;
import com.ornnplanner.arena.MatchService;
import com.ornnplanner.arena.ai.Network;
import com.ornnplanner.arena.ai.Trainer;
import com.ornnplanner.arena.rift.MacroActions;
import com.ornnplanner.arena.rift.MatchModel;
import com.ornnplanner.arena.rift.NeuralMacro;
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
import java.util.Map;
import java.util.TreeMap;

/**
 * The macro's training on the saved builds of a <b>copy</b> of a database (report style, opt-in,
 * proposta-macro-treinada.md, Partes 3 e 5): trains a {@link NeuralMacro} against the script, then plays the validation
 * seeds with the script and with the trained macro and compares them.
 * {@code ./mvnw test -Dtest=MacroReportTest -Dmacro.report=data/ornn-planner.db} (+ {@code -Dmacro.generations=30
 * -Dmacro.population=24 -Dmacro.episodes=4 -Dmacro.seed=1}). Writes target/macro-report.txt and the weights to
 * target/macro-weights.txt.
 */
@EnabledIfSystemProperty(named = "macro.report", matches = ".+")
@SpringBootTest(properties = {"server.port=0"})
class MacroReportTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        Path copy = Files.createTempDirectory("macro-report").resolve("copy.db");
        Files.copy(Paths.get(System.getProperty("macro.report")), copy);
        r.add("planner.db-path", copy::toString);
    }

    @Autowired
    MatchService matches;
    @Autowired
    BuildRepository builds;

    /** Five saved builds by name (the first match of each), the same five on both sides. */
    private MatchService.Draft draft(List<String> names) {
        List<BuildRepository.Build> all = builds.findBuildSummaries();
        MatchModel.Request req = new MatchModel.Request();
        for (int side = 0; side < 2; side++) {
            for (String n : names) {
                BuildRepository.Build b = all.stream().filter(x -> n.equals(x.name)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("no build " + n));
                MatchModel.Slot s = new MatchModel.Slot();
                s.buildId = b.id;
                s.team = side;
                req.slots.add(s);
            }
        }
        return matches.draft(req);
    }

    private static int prop(String key, int def) {
        return Integer.parseInt(System.getProperty(key, String.valueOf(def)));
    }

    @Test
    void report() throws IOException {
        List<MatchService.Draft> drafts = new ArrayList<>();
        drafts.add(draft(List.of("Rumble Carry", "Big Carry v1", "Armor Shredder", "Darius de CdC", "Sup Desengaje")));
        drafts.add(draft(List.of("Rumble Redutor Off Tank", "Soldado v8", "Armor Shredder", "Darius de CdC", "Sup Rush Warmog")));
        MacroTraining.Config c = new MacroTraining.Config();
        c.generations = prop("macro.generations", c.generations);
        c.population = prop("macro.population", c.population);
        c.episodesPerGeneration = prop("macro.episodes", c.episodesPerGeneration);
        c.seed = prop("macro.seed", 1);
        MacroTraining t = new MacroTraining(matches, drafts, c, List.of());
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("drafts %d, population %d, generations %d, episodes %d (x2 sides), seed %d, threads %d%n",
                drafts.size(), c.population, c.generations, c.episodesPerGeneration, c.seed, c.threads));
        long t0 = System.nanoTime();
        MacroTraining.Validation base = t.validate(MacroTraining.zeros());
        sb.append(String.format("script vs script: winrate %.3f (%d games), %.1f s%n", base.winrate, base.games, (System.nanoTime() - t0) / 1e9));
        List<Network> centers = new ArrayList<>();
        long t1 = System.nanoTime();
        Network trained = t.train(MacroTraining.zeros(), new MacroTraining.Listener() {
            @Override
            public void generation(Trainer.Generation g) {
                centers.add(g.center);
                String line = String.format("gen %2d: mean %+.3f best %+.3f (%.0f s)%n", g.index, g.mean, g.best, (System.nanoTime() - t1) / 1e9);
                sb.append(line);
                System.out.print(line);
            }
        });
        double trainSeconds = (System.nanoTime() - t1) / 1e9;
        sb.append(String.format("training: %.0f s%n", trainSeconds));
        // the final weights and a few earlier ones, on the validation seeds
        for (int gen : new int[] {centers.size() / 3, 2 * centers.size() / 3}) {
            if (gen > 0) {
                MacroTraining.Validation v = t.validate(centers.get(gen - 1));
                sb.append(String.format("gen %2d vs script: winrate %.3f (%d-%d of %d), fitness %+.3f, gold/min %.0f vs %.0f, %.1f deviations/match%n",
                        gen, v.winrate, v.wins, v.losses, v.games, v.fitness, v.goldPerMin, v.opponentGoldPerMin, v.deviations));
            }
        }
        MacroTraining.Validation v = t.validate(trained);
        sb.append(String.format("final  vs script: winrate %.3f (%d-%d of %d), fitness %+.3f, gold/min %.0f vs %.0f, %.1f min, %.1f deviations/match%n",
                v.winrate, v.wins, v.losses, v.games, v.fitness, v.goldPerMin, v.opponentGoldPerMin, v.minutes, v.deviations));
        // where it leaves the script: the deviations of a few validation matches, by "action instead of action"
        Map<String, Integer> by = new TreeMap<>();
        NeuralMacro me = new NeuralMacro(trained, t.script());
        List<String> samples = new ArrayList<>();
        for (int s = 0; s < 4; s++) {
            MatchModel.Result r = matches.run(drafts.get(s % drafts.size()), MacroTraining.VALIDATION_SEEDS + s, c.limitMinutes, me, t.script());
            r.events.stream().filter(e -> e.text.contains("(macro treinada)")).forEach(e -> {
                String what = e.text.substring(e.text.indexOf("): ") + 3);
                by.merge(what, 1, Integer::sum);
                if (samples.size() < 12) {
                    samples.add(String.format("%.0f %s", e.t, e.text));
                }
            });
            sb.append(String.format("sample %d: winner %d at %.0f s%n", s, r.winner, r.seconds));
        }
        by.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(15)
                .forEach(e -> sb.append(String.format("  %4d  %s%n", e.getValue(), e.getKey())));
        samples.forEach(x -> sb.append("    ").append(x).append('\n'));
        sb.append("actions: ").append(String.join(" | ", MacroActions.LABELS)).append('\n');
        System.out.println(sb);
        Files.write(Paths.get("target/macro-report.txt"), sb.toString().getBytes("UTF-8"));
        StringBuilder w = new StringBuilder();
        for (double x : trained.weights()) {
            w.append(x).append('\n');
        }
        Files.write(Paths.get("target/macro-weights.txt"), w.toString().getBytes("UTF-8"));
    }
}
