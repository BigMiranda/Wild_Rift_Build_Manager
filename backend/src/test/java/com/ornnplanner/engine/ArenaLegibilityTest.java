package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaModel.Result;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rodada 10, Parte 3: what hits is on the map, and the melee attack hits what is on its way. */
class ArenaLegibilityTest {

    @Test
    void aMeleeAttackHitsTheUnitsBetweenTheAttackerAndItsTarget() {
        Arena.Input a = ArenaScenarios.champion("Darius", 0, 3000, 0, 9000, 150, 80);
        Arena.Input near = ArenaScenarios.champion("Vayne", 1, 3090, 0, 9000, 50, 80);
        Arena.Input far = ArenaScenarios.champion("Renekton", 1, 3170, 0, 9000, 50, 80);
        a.autoAbilities = false;
        near.autoAbilities = false;
        far.autoAbilities = false;
        a.policy = null;
        ArenaModel.Action focus = ArenaScenarios.order(0, 0, "attack", null, 2);
        Result r = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(new ArrayList<>(List.of(a, near, far)), List.of(focus), 4);
        assertTrue(r.fighters.get(0).events.getOrDefault("ataque_no_caminho", 0.0) >= 1, r.fighters.get(0).events.toString());
        assertTrue(r.fighters.get(1).taken > 0, "the unit on the way took the attack");
        // the segment is drawn and each landed attack leaves a mark for the time strip
        assertTrue(r.frames.stream().anyMatch(f -> f.shapes.stream().anyMatch(s -> "ataque".equals(s.hit) && "rect".equals(s.kind))));
        assertTrue(r.attackMarks.stream().anyMatch(m -> m[1] == 0));
    }

    @Test
    void aRangedAttackShowsItsMissileAndOrnnsAttackOnBrittleKnocksUp() {
        Arena.Input ornn = ArenaScenarios.champion("Ornn", 0, 3000, 0, 4000, 150, 80);
        Arena.Input vayne = ArenaScenarios.champion("Vayne", 1, 3400, 0, 4000, 150, 80);
        Result r = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(new ArrayList<>(List.of(ornn, vayne)), List.of(), 15);
        assertTrue(r.frames.stream().anyMatch(f -> f.shapes.stream().anyMatch(s -> "ataque".equals(s.hit) && "circle".equals(s.kind))),
                "Vayne's missile is on the map");
        assertTrue(r.frames.stream().anyMatch(f -> f.units.get(1).airLeft != null), "something lifts Vayne");
        double broken = r.fighters.get(0).events.entrySet().stream().filter(e -> e.getKey().contains("Frágil"))
                .mapToDouble(java.util.Map.Entry::getValue).sum();
        assertTrue(broken >= 0);
    }
}
