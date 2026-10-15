package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel.Combo;
import com.ornnplanner.arena.ArenaModel.ComboStep;
import com.ornnplanner.arena.ArenaModel.Dummy;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ComboBench;
import com.ornnplanner.arena.ComboSearch;
import com.ornnplanner.arena.ai.ScriptedPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ragdoll bench and the beam search of combos. */
class ComboSearchTest {

    static final ComboBench BENCH = new ComboBench(ArenaScenarios.CATALOG, ArenaScenarios.MAP);

    static Arena.Input champion(String name) {
        return ArenaScenarios.champion(name, 0, 1000, 0, 3000, 160, 90);
    }

    static Arena.Input dummy() {
        return ComboBench.dummyInput(new Dummy());
    }

    static ComboBench.Config parado() {
        ComboBench.Config c = new ComboBench.Config();
        c.behaviors = List.of("parado");
        return c;
    }

    @Test
    void theBenchMeasuresACombo() {
        Combo c = new Combo();
        c.name = "puxar";
        c.steps = List.of(new ComboStep("habilidade:3"), new ComboStep("habilidade:1"), new ComboStep("atacar"));
        ComboBench.Report r = BENCH.evaluate(champion("Darius"), dummy(), c, new ComboBench.Config());
        assertEquals(4, r.byBehavior.size());
        ComboBench.Metrics m = r.byBehavior.get("parado");
        String info = "dmg " + m.damage + " control " + m.control + " free " + m.longestFree + " dur " + m.duration + " repeat "
                + m.repeatIn + " steps " + m.steps.size() + " snapshot " + m.snapshot + " reason " + m.reason;
        assertTrue(m.done, info);
        assertTrue(m.damage > 0);
        assertTrue(m.bySource.containsKey("1 · Dizimar"), m.bySource.toString());
        assertTrue(m.byType.containsKey("fisico"));
        assertTrue(m.control > 0, "the pull: " + info);
        assertTrue(m.longestFree > 0 && m.longestFree <= m.duration + 1e-9, info);
        assertTrue(m.repeatIn > 1, "Decimate and Apprehend are on cooldown at the end: " + info);
        assertEquals(r.score, BENCH.evaluate(champion("Darius"), dummy(), c, new ComboBench.Config()).score, 1e-9, "deterministic");
        // the behaviors make a difference
        assertTrue(Math.abs(r.byBehavior.get("foge").damage - m.damage) > 1 || Math.abs(r.byBehavior.get("anda").damage - m.damage) > 1);
    }

    /** 🎯 The search finds, for a plain champion, a sequence that beats the scripted autopilot's damage on the ragdoll. */
    @Test
    void searchBeatsTheScriptOnDarius() {
        ComboSearch.Config cfg = new ComboSearch.Config();
        cfg.beam = 16;
        cfg.maxSteps = 6;
        cfg.budget = 1500;
        cfg.threads = 4;
        ComboSearch.Result a = new ComboSearch(BENCH).search(champion("Darius"), dummy(), parado(), cfg, new ComboSearch.Listener() { });
        assertFalse(a.best.isEmpty());
        ComboSearch.Found best = a.best.get(0);
        ComboBench.Metrics m = best.report.byBehavior.get("parado");
        // the scripted autopilot on the same ragdoll, for as long as the combo's run
        Arena.Input d = champion("Darius");
        d.policy = new ScriptedPolicy(d.priority);
        Arena.Input dm = dummy();
        dm.x = 1300;
        dm.y = 0;
        dm.kit = java.util.Map.of("raio", 80.0, "alcance", 0.0, "habilidades", java.util.Map.of());
        dm.policy = (o, mask) -> com.ornnplanner.arena.ai.Policy.Decision.of(com.ornnplanner.arena.ai.ActionSpace.IDLE);
        Result script = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withoutFrames()
                .run(List.of(d, dm), List.of(), best.combo.maxTime + new ComboBench.Config().tail);
        double scriptDamage = script.fighters.get(0).dealt;
        System.out.printf("search: %d evaluated, depth %d; best %s: %.0f damage vs script %.0f%n", a.evaluated, a.depth,
                best.combo.name, m.damage, scriptDamage);
        a.log.forEach(l -> System.out.println("search log: " + l));
        assertTrue(m.damage > scriptDamage, best.combo.name + ": " + m.damage + " vs " + scriptDamage);
        // deterministic, whatever the threads
        cfg.threads = 1;
        ComboSearch.Result b = new ComboSearch(BENCH).search(champion("Darius"), dummy(), parado(), cfg, new ComboSearch.Listener() { });
        assertEquals(a.best.get(0).combo.name, b.best.get(0).combo.name);
        assertEquals(a.best.get(0).score, b.best.get(0).score, 1e-9);
        assertEquals("descoberto", best.combo.origin);
        assertTrue(a.best.size() > 1 && !a.best.get(1).diff.isEmpty(), "distinct combos, with what differs");
    }
}
