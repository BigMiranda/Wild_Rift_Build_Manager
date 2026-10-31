package com.ornnplanner.engine;

import com.ornnplanner.arena.AiResultRepository;
import com.ornnplanner.arena.AiResultRepository.Row;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.arena.ArenaTraining;
import com.ornnplanner.arena.AutoBrain;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Network;
import com.ornnplanner.arena.ai.NeuralPolicy;
import com.ornnplanner.arena.ai.Observation;
import com.ornnplanner.arena.ai.PolicyRepository;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The automatic brain on the seed's catalog (a new database, policies and results written by hand). */
@SpringBootTest(properties = {"server.port=0"})
class AutoBrainTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        String db = Files.createTempDirectory("auto-brain").resolve("t.db").toString();
        r.add("planner.db-path", () -> db);
    }

    @Autowired
    ArenaService arena;
    @Autowired
    AutoBrain auto;
    @Autowired
    PolicyRepository policies;
    @Autowired
    AiResultRepository results;
    @Autowired
    PlannerService planner;
    @Autowired
    JdbcTemplate jdbc;

    private final Map<String, Long> ids = new LinkedHashMap<>();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ai_result");
        jdbc.update("DELETE FROM arena_policy");
        planner.itemViews().forEach(i -> ids.put(i.name, i.id));
    }

    private BuildRepository.Build build(String unit, String item) {
        BuildRepository.Build b = new BuildRepository.Build();
        b.name = unit;
        b.unitCode = unit;
        b.goldPerMin = 1500;
        b.xpPerMin = 800;
        b.steps = new ArrayList<>(List.of(new BuildRepository.Step(ids.get(item), true)));
        return b;
    }

    private ArenaModel.Slot slot(int team, String unit, String brain, double x) {
        ArenaModel.Slot s = new ArenaModel.Slot();
        s.team = team;
        s.build = build(unit, "Coração de Aço");
        s.minute = 20.0;
        s.brain = brain;
        s.x = x;
        s.y = 0.0;
        return s;
    }

    private ArenaModel.Scenario scenario(String enemy, String brain) {
        ArenaModel.Scenario sc = new ArenaModel.Scenario();
        sc.duration = 30;
        sc.fighters = new ArrayList<>(List.of(slot(0, "ORNN", brain, 4000), slot(1, enemy, "script", 4600)));
        return sc;
    }

    private PolicyRepository.Record policy(String owner, boolean accepted) {
        PolicyRepository.Record r = new PolicyRepository.Record();
        r.champion = owner;
        r.objective = "win_fast";
        r.accepted = accepted;
        r.obsSchema = Observation.SCHEMA_VERSION;
        r.actions = ActionSpace.SIZE;
        r.weights = PolicyRepository.Weights.trained(Network.zeros(Network.DEFAULT_LAYERS), NeuralPolicy.BASE_BONUS);
        return policies.insert(r);
    }

    /** A real fight of the scenario with the AI in the slot, then its row as the test wants it. */
    private void result(ArenaModel.Scenario sc, PolicyRepository.Record p, boolean won, boolean clean, boolean died, double time) {
        ArenaModel.Slot me = sc.fighters.get(0);
        String brain = me.brain;
        me.brain = "ai:" + p.id;
        arena.run(sc);
        me.brain = brain;
        jdbc.update("UPDATE ai_result SET won = ?, clean = ?, died = ?, duration = ?, health = 0.5 WHERE policy_id = ?",
                won ? 1 : 0, clean ? 1 : 0, died ? 1 : 0, time, p.id);
    }

    private AutoBrain.Choice choice(ArenaModel.Scenario sc) {
        return arena.autoChoices(sc).get(0);
    }

    private static Row row(boolean won, boolean clean, boolean died, double time, double health) {
        Row r = new Row();
        r.won = won;
        r.clean = clean;
        r.died = died;
        r.time = time;
        r.health = health;
        return r;
    }

    /** It picks the AI that won this scenario, not one that only won another. */
    @Test
    void picksTheAiThatWonThisScenario() {
        ArenaModel.Scenario here = scenario("DARIUS", "auto");
        ArenaModel.Scenario other = scenario("VAYNE", "auto");
        PolicyRepository.Record mine = policy("Ornn", true);
        PolicyRepository.Record elsewhere = policy("Ornn", true);
        result(here, mine, true, true, false, 30);
        result(other, elsewhere, true, true, false, 5);
        AutoBrain.Choice c = choice(here);
        assertEquals("cenario", c.kind);
        assertEquals(mine.id, c.policyId);
        assertEquals(30, c.time, 1e-9);
    }

    /** Clean victory before victory, without dying before dying, then faster, then more health left. */
    @Test
    void theCriteriaComeInOrder() {
        List<String> order = auto.criteria();
        assertEquals(List.of("vitoria_limpa_sem_morrer", "vitoria_sem_morrer", "vitoria", "mais_rapido", "mais_vida"), order);
        assertTrue(AutoBrain.compare(order, row(true, true, false, 50, 0.1), row(true, false, false, 5, 0.9)) < 0, "clean beats a faster win");
        assertTrue(AutoBrain.compare(order, row(true, false, false, 50, 0.1), row(true, true, true, 5, 0.9)) < 0, "alive beats a win that died");
        assertTrue(AutoBrain.compare(order, row(true, false, true, 50, 0), row(false, false, false, 5, 0.9)) < 0, "a win beats no win");
        assertTrue(AutoBrain.compare(order, row(true, true, false, 5, 0.1), row(true, true, false, 9, 0.9)) < 0, "faster");
        assertTrue(AutoBrain.compare(order, row(true, true, false, 5, 0.8), row(true, true, false, 5, 0.2)) < 0, "more health");
        assertTrue(AutoBrain.compare(order, row(false, false, true, 30, 0), row(false, false, true, 10, 0)) < 0, "a loss that lasted longer");

        ArenaModel.Scenario here = scenario("DARIUS", "auto");
        PolicyRepository.Record win = policy("Ornn", true);
        PolicyRepository.Record cleanWin = policy("Ornn", true);
        result(here, win, true, false, false, 5);
        result(here, cleanWin, true, true, false, 25);
        assertEquals(cleanWin.id, choice(here).policyId);
    }

    /** With no history: the champion's AI, then its class's, then the tactical rules; and alike scenarios come first. */
    @Test
    void withNoHistoryItFallsBack() {
        ArenaModel.Scenario here = scenario("DARIUS", "auto");
        assertEquals("tatico", choice(here).kind);
        assertEquals("tactical", choice(here).brain);
        PolicyRepository.Record ofClass = policy("class:Tanque", true);
        AutoBrain.Choice c = choice(here);
        assertEquals("classe", c.kind);
        assertEquals(ofClass.id, c.policyId);
        assertEquals("Tanque", c.owner);
        PolicyRepository.Record own = policy("Ornn", true);
        assertEquals(own.id, choice(here).policyId);
        assertEquals("geral", choice(here).kind);
        // the same enemy at another minute is alike; the same class of enemy too
        PolicyRepository.Record alike = policy("Ornn", true);
        ArenaModel.Scenario moved = scenario("DARIUS", "auto");
        moved.fighters.get(0).x = 5000.0;
        moved.fighters.get(1).x = 5600.0;
        result(moved, alike, true, false, false, 10);
        assertEquals("parecido", choice(here).kind);
        assertEquals(alike.id, choice(here).policyId);
        assertEquals(1, arena.run(here).brainChoices.size());
    }

    /** A clean victory: the side wins, the champion ends alive and every enemy dies. */
    @Test
    void cleanVictoryNeedsEveryEnemyDeadAndTheChampionAlive() {
        ArenaModel.Result r = new ArenaModel.Result();
        r.winner = 0;
        ArenaModel.FighterStats me = new ArenaModel.FighterStats();
        ArenaModel.FighterStats enemy = new ArenaModel.FighterStats();
        enemy.team = 1;
        enemy.deathTime = 12.0;
        r.fighters.add(me);
        r.fighters.add(enemy);
        assertTrue(ArenaTraining.cleanWin(r, 0, 0));
        me.deathTime = 12.0;
        assertFalse(ArenaTraining.cleanWin(r, 0, 0), "it died in the win");
        me.deathTime = null;
        enemy.deathTime = null;
        assertFalse(ArenaTraining.cleanWin(r, 0, 0), "someone is left");
        enemy.deathTime = 12.0;
        r.winner = 1;
        assertFalse(ArenaTraining.cleanWin(r, 0, 0), "the other side won");
    }

    /** A saved scenario with a fixed brain does not change: no choice, no result of a non-AI brain. */
    @Test
    void aFixedBrainStaysAsItIs() {
        for (String brain : new String[] {"tactical", "script", null}) {
            ArenaModel.Scenario sc = scenario("DARIUS", brain);
            assertTrue(arena.autoChoices(sc).isEmpty());
            ArenaModel.Result r = arena.run(sc);
            assertTrue(r.brainChoices.isEmpty());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ai_result", Integer.class));
        // and an AI fixed in the slot plays, whatever the history says
        PolicyRepository.Record fixed = policy("Ornn", true);
        ArenaModel.Scenario sc = scenario("DARIUS", "ai:" + fixed.id);
        assertTrue(arena.run(sc).brainChoices.isEmpty());
        assertEquals(fixed.id, results.forChampion("Ornn").get(0).policyId);
    }

    /** Deleting an AI takes its results with it; the rows say who played. */
    @Test
    void theHistorySaysWhatHappened() {
        PolicyRepository.Record p = policy("Ornn", true);
        ArenaModel.Scenario sc = scenario("DARIUS", "ai:" + p.id);
        arena.run(sc);
        Row r = results.forChampion("Ornn").get(0);
        assertEquals(List.of("Darius"), r.enemies);
        assertEquals(List.of("Lutador"), r.enemyClasses);
        assertEquals("tela", r.source);
        assertEquals(20.0, r.minute, 1e-9);
        assertTrue(r.impression.contains("Darius"), r.impression);
        arena.run(sc);
        assertEquals(1, results.forChampion("Ornn").size(), "the same fight again adds nothing");
        results.forgetPolicy(p.id);
        assertTrue(results.forChampion("Ornn").isEmpty());
    }
}
