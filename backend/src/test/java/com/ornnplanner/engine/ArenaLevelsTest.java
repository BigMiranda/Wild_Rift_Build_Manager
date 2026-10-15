package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel.Combo;
import com.ornnplanner.arena.ArenaModel.Condition;
import com.ornnplanner.arena.ArenaModel.Guideline;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ArenaTraining;
import com.ornnplanner.arena.ComboBench;
import com.ornnplanner.arena.ComboSearch;
import com.ornnplanner.arena.Reward;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.GuidedPolicy;
import com.ornnplanner.arena.ai.Network;
import com.ornnplanner.arena.ai.NeuralPolicy;
import com.ornnplanner.arena.ai.Observation;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.arena.ai.PolicyRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Training help: event rewards, drills from the sheets, guidelines, the help levels saved with a version. */
class ArenaLevelsTest {

    private static PolicyRepository repo(Path dir) {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("t.db"));
        ds.setDriverClassName("org.sqlite.JDBC");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        return new PolicyRepository(new JdbcTemplate(ds));
    }

    @Test
    void eventRewardsCountOnlyInTheTrainingFitness() {
        ArenaTraining.StageSpec s = new ArenaTraining.StageSpec();
        Map<String, Double> ev = new LinkedHashMap<>();
        ev.put("marca_consumida:Frágil:ataque", 2.0);
        ev.put("marca_sobrescrita:Frágil", 1.0);
        ev.put("cc_encadeado", 1.0);
        ev.put("combo_concluido:x", 1.0);
        ev.put("tempo_controlado", 3.0);
        assertEquals(2 * 150 - 100 + 80 + 50, Reward.of(ev, s), 1e-9);
        Guideline g = new Guideline();
        g.id = "r1";
        g.reward = 30.0;
        s.guidelines = List.of(g);
        ev.put("diretriz:r1", 2.0);
        assertEquals(2 * 150 - 100 + 80 + 50 + 60, Reward.of(ev, s), 1e-9);
    }

    @Test
    void drillsComeFromTheSheets() {
        List<String> ornn = Reward.drills(ArenaScenarios.champion("Ornn", 0, 1000, 0, 3000, 100, 90));
        assertTrue(ornn.containsAll(List.of("alvo marcado com o ataque pronto", "alvo no ar com 0,8 s restantes",
                "reconjuração aberta", "vida baixa perseguido", "ultimate em recarga")), ornn.toString());
        List<String> rumble = Reward.drills(ArenaScenarios.champion("Rumble", 0, 1000, 0, 3000, 100, 90));
        assertFalse(rumble.contains("alvo marcado com o ataque pronto"), "Rumble keeps no mark");
        assertFalse(rumble.contains("reconjuração aberta"));
    }

    @Test
    void aDrillStartsFromItsState() {
        // the marked-target drill: Ornn's Brittle is on Darius at the start, so his first attack breaks it
        Arena.Input ornn = ArenaScenarios.champion("Ornn", 0, 1000, 0, 3000, 100, 90);
        Arena.Input darius = ArenaScenarios.champion("Darius", 1, 1250, 0, 3000, 100, 90);
        darius.markedBy = 0;
        darius.startAirborne = 0.8;
        ornn.startCooldowns = new LinkedHashMap<>(Map.of("R", 40.0));
        ornn.autoAbilities = false;
        darius.autoAbilities = false;
        Result r = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(List.of(ornn, darius), List.of(), 2);
        assertEquals(1, r.fighters.get(0).events.getOrDefault("marca_consumida:Frágil:ataque", 0.0), 1e-9);
        assertTrue(r.fighters.get(1).controlSpans.get(0)[0] < 0.05, "in the air from the start");
        assertTrue(r.fighters.get(0).cooldownsLeft.get("R") > 37);
    }

    /** Picks whatever the network says (zeros: the tactical base), under the guidelines. */
    private static GuidedPolicy guided(List<Guideline> rules) {
        return new GuidedPolicy(new NeuralPolicy(Network.zeros(Network.DEFAULT_LAYERS), List.of("R", "3", "1", "2")), rules);
    }

    private static Guideline rule(String effect, String action, Condition... when) {
        Guideline g = new Guideline();
        g.id = effect + ":" + action;
        g.effect = effect;
        g.action = action;
        g.strength = 5;
        g.when = new ArrayList<>(List.of(when));
        return g;
    }

    @Test
    void guidelinesForbidForceAndPrefer() {
        Arena.Input ornn = ArenaScenarios.champion("Ornn", 0, 1000, 0, 400000, 100, 90);
        Arena.Input darius = ArenaScenarios.champion("Darius", 1, 1300, 0, 400000, 100, 90);
        darius.autoAbilities = false;
        darius.policy = (o, m) -> Policy.Decision.of(ActionSpace.IDLE);
        // forbid every cast: Ornn only attacks
        List<Guideline> noCasts = new ArrayList<>();
        for (String s : ActionSpace.SLOTS) {
            noCasts.add(rule("proibir", "habilidade:" + s));
        }
        ornn.policy = guided(noCasts);
        Result r = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(List.of(ornn, darius), List.of(), 4);
        assertTrue(r.events.stream().noneMatch(e -> e.text.contains("usou ")), r.events.toString());
        assertTrue(r.fighters.get(0).dealt > 0);
        // force the W whenever it is up
        Arena.Input ornn2 = ArenaScenarios.champion("Ornn", 0, 1000, 0, 400000, 100, 90);
        ornn2.policy = guided(List.of(rule("obrigar", "habilidade:2", new Condition("pronto:2", "==", 1))));
        Result w = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(List.of(ornn2, darius), List.of(), 1);
        assertTrue(w.events.stream().anyMatch(e -> e.text.contains("usou 2")), w.events.toString());
        assertTrue(w.fighters.get(0).events.getOrDefault("diretriz:obrigar:habilidade:2", 0.0) >= 1);
    }

    @Test
    void thePresetAvoidsOverwritingAMark() {
        List<Guideline> preset = GuidedPolicy.dontOverwriteMarks();
        assertEquals(4, preset.size());
        // Darius with Ornn's Brittle on: the W (it puts Brittle, does not break it) is avoided, the others are not
        Arena.Input ornn = ArenaScenarios.champion("Ornn", 0, 1000, 0, 400000, 100, 90);
        Arena.Input darius = ArenaScenarios.champion("Darius", 1, 1250, 0, 400000, 100, 90);
        darius.markedBy = 0;
        darius.autoAbilities = false;
        darius.policy = (o, m) -> Policy.Decision.of(ActionSpace.IDLE);
        List<String> seen = new ArrayList<>();
        GuidedPolicy g = guided(preset);
        ornn.policy = new Policy() {
            @Override
            public Decision decide(Observation obs, com.ornnplanner.arena.ai.ActionMask mask) {
                Decision d = g.decide(obs, mask);
                if (obs.time() < 0.05) {
                    seen.add(ActionSpace.describe(d.action) + "|" + d.tag);
                }
                return d;
            }

            @Override
            public double interval() {
                return g.interval();
            }
        };
        ornn.priority = new ArrayList<>(List.of("2", "1", "3", "R"));   // the base would cast W first
        new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(List.of(ornn, darius), List.of(), 0.1);
        assertFalse(seen.isEmpty());
        assertFalse(seen.get(0).startsWith("habilidade 2"), seen.toString());
    }

    @Test
    void theLevelIsSavedWithTheVersion(@TempDir Path dir) {
        PolicyRepository repo = repo(dir);
        PolicyRepository.Record r = new PolicyRepository.Record();
        r.champion = "Ornn";
        r.objective = "win_fast";
        r.accepted = true;
        r.obsSchema = Observation.SCHEMA_VERSION;
        r.actions = ActionSpace.SIZE;
        r.level = ArenaTraining.LEVEL_HINTS;
        r.guidelines = GuidedPolicy.dontOverwriteMarks();
        r.weights = PolicyRepository.Weights.trained(Network.zeros(Network.DEFAULT_LAYERS), 0.5);
        repo.insert(r);
        PolicyRepository.Record back = repo.find(r.id).orElseThrow();
        assertEquals(ArenaTraining.LEVEL_HINTS, back.level);
        assertEquals(4, back.guidelines.size());
        assertEquals("evitar", back.guidelines.get(0).effect);
        assertEquals(ArenaTraining.LEVEL_HINTS, repo.list("Ornn").get(0).level);
    }

    /**
     * "Comparar níveis" on Ornn × Darius: the same training (seed, budget) at each help level, numbers per validation
     * fight. Off by default ({@code -Darena.report=true}).
     */
    @EnabledIfSystemProperty(named = "arena.report", matches = "true")
    @Test
    void compareLevels(@TempDir Path dir) throws Exception {
        PolicyRepository repo = repo(dir);
        ArenaTraining t = new ArenaTraining(ArenaScenarios.CATALOG, ArenaScenarios.MAP, repo);
        List<Arena.Input> duel = List.of(ArenaScenarios.champion("Ornn", 0, 3000, 0, 3000, 140, 110),
                ArenaScenarios.champion("Darius", 1, 3700, 0, 2800, 170, 90));
        // the found combos: a search on the ragdoll (as the lab would), the manual one for the hints level
        ComboSearch.Config sc = new ComboSearch.Config();
        sc.budget = 3000;
        sc.threads = 10;
        List<Combo> found = new ArrayList<>();
        for (ComboSearch.Found f : new ComboSearch(OrnnProofTest.BENCH).search(duel.get(0), OrnnProofTest.dummy(),
                new ComboBench.Config(), sc, new ComboSearch.Listener() { }).best) {
            if (found.size() < ActionSpace.COMBOS) {
                found.add(f.combo);
            }
        }
        StringBuilder out = new StringBuilder("level | objective | wins | marks consumed | overwritten | chained | combos (per fight)\n");
        for (String level : ArenaTraining.LEVELS) {
            ArenaTraining.StageSpec s = new ArenaTraining.StageSpec();
            s.variants = List.of(duel);
            s.duration = 40;
            s.generations = 30;
            s.population = 24;
            s.seed = 6;
            s.level = level;
            if (!ArenaTraining.LEVEL_ALONE.equals(level)) {
                s.combos = new ArrayList<>(found);
            }
            if (ArenaTraining.LEVEL_HINTS.equals(level)) {
                List<Combo> withManual = new ArrayList<>(found.subList(0, Math.min(3, found.size())));
                withManual.add(0, OrnnProofTest.manual());
                s.combos = withManual;
                s.guidelines = GuidedPolicy.dontOverwriteMarks();
            }
            ArenaTraining.StageResult r = t.trainStage(s, new ArenaTraining.Listener() { });
            Map<String, Double> m = r.stats;
            out.append(String.format("%s | %.0f | %.0f%% | %.2f | %.2f | %.2f | %.2f  (accepted %s, better than script %.0f%%)%n", level,
                    m.get("objetivo"), 100 * m.get("vitorias"), m.get("marcas_consumidas"), m.get("marcas_sobrescritas"),
                    m.get("controle_encadeado"), m.get("combos_usados"), r.policy.accepted, 100 * r.policy.betterVsScript));
        }
        Files.write(Paths.get("target/levels.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(out);
    }
}
