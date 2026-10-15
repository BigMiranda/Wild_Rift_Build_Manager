package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel.Action;
import com.ornnplanner.arena.ArenaModel.Dummy;
import com.ornnplanner.arena.ArenaModel.Frame;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ComboBench;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.combat.CombatSimulator.ItemEffect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lab's ragdoll in the arena: how it moves, how it reacts, the effects it applies. */
class ArenaDummyTest {

    private static Arena.Input dummy(String behavior) {
        Dummy d = new Dummy();
        d.behavior = behavior;
        Arena.Input in = ComboBench.dummyInput(d);
        in.x = 1500;
        in.y = 0;
        in.policy = "reage".equals(behavior) ? new ComboBench.ReactPolicy(in.priority)
                : (o, m) -> Policy.Decision.of(ActionSpace.IDLE);
        return in;
    }

    private static Arena.Input idleChampion(String name) {
        Arena.Input a = ArenaScenarios.champion(name, 0, 1000, 0, 400000, 100, 90);
        a.autoAbilities = false;
        a.policy = (o, m) -> Policy.Decision.of(ActionSpace.IDLE);
        return a;
    }

    private static Result run(List<Arena.Input> in, List<Action> orders, double duration) {
        return new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(in, orders, duration);
    }

    private static Frame at(Result r, double t) {
        return r.frames.stream().filter(f -> Math.abs(f.t - t) < 0.051).findFirst().orElseThrow();
    }

    @Test
    void itStandsWalksSideToSideOrRunsAway() {
        Result still = run(List.of(idleChampion("Darius"), dummy("parado")), List.of(), 4);
        assertEquals(1500, at(still, 3).units.get(1).x, 1e-6);
        assertEquals(0, at(still, 3).units.get(1).y, 1e-6);
        Result walk = run(List.of(idleChampion("Darius"), dummy("anda")), List.of(), 4);
        assertTrue(Math.abs(at(walk, 1).units.get(1).y) > 200, "walked across: " + at(walk, 1).units.get(1).y);
        assertTrue(at(walk, 2.5).units.get(1).y < at(walk, 1.4).units.get(1).y, "and turned back");
        Result flee = run(List.of(idleChampion("Darius"), dummy("foge")), List.of(), 3);
        assertTrue(at(flee, 2).units.get(1).x > 1900, "ran away: " + at(flee, 2).units.get(1).x);
        // it never deals damage
        assertEquals(0, still.fighters.get(1).dealt, 1e-9);
    }

    @Test
    void reactingItCleansesALongStun() {
        Arena.Input stunner = idleChampion("Ornn");
        stunner.kit = Map.of("raio", 80, "alcance", 175, "habilidades", Map.of("1", Map.of("nome", "Atordoar", "forma", "alvo",
                "alcance", 3000, "recarga", List.of(60), "dano", Map.of("tipo", "magico", "base", 1), "efeitos", Map.of("atordoa", 3.0))));
        Result r = run(List.of(stunner, dummy("reage")), new ArrayList<>(List.of(ArenaScenarios.order(0, 0.5, "ability", "1", 1))), 3);
        assertTrue(r.events.stream().anyMatch(e -> e.text.contains("usou Purificar")), r.events.toString());
        Result still = run(List.of(idleChampion("Ornn"), dummy("parado")), List.of(), 1);
        assertTrue(still.events.stream().noneMatch(e -> e.text.contains("Purificar")));
    }

    @Test
    void itAppliesTheOnHitEffectsOfItsItemsToTheChampion() {
        // Vayne at half health heals by Silver Bolts' lifesteal; a ragdoll wearing Grievous Wounds cuts it
        java.util.function.Function<Boolean, Double> healLost = applies -> {
            Arena.Input vayne = ArenaScenarios.champion("Vayne", 0, 1000, 0, 3000, 200, 90);
            vayne.startHealth = 0.5;
            Arena.Input d = dummy("parado");
            d.f.effects.add(new ItemEffect("Lembrete Mortal", Map.of("tipo", "feridas", "pct", 0.5, "gatilho", "fisico")));
            d.f.items.put("Lembrete Mortal", 1L);
            d.dummy.appliesEffects = applies;
            d.dummy.atMax = true;
            d.x = 1400;
            Result r = run(List.of(vayne, d), new ArrayList<>(List.of(ArenaScenarios.order(0, 0, "ability", "2", null))), 6);
            assertTrue(r.fighters.get(0).dealt > 0);
            return r.fighters.get(0).healLost;
        };
        assertEquals(0, healLost.apply(false), 1e-9);
        assertTrue(healLost.apply(true) > 10, "Grievous Wounds on Vayne");
    }
}
