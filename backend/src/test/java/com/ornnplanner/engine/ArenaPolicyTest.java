package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Observation;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.arena.ai.ScriptedPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decision point of the arena: policies, the action mask and the observation. */
class ArenaPolicyTest {

    private static Result run(List<Arena.Input> inputs, double duration) {
        return new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(inputs, List.of(), duration);
    }

    @Test
    void explicitScriptedPolicyIsTheDefaultAutopilot() {
        for (ArenaScenarios.Scenario s : ArenaScenarios.all().subList(0, 8)) {
            List<Arena.Input> in = s.inputs.get();
            in.forEach(i -> i.policy = new ScriptedPolicy(i.priority));
            Result explicit = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(in, s.actions.get(), s.duration);
            assertEquals(ArenaScenarios.fingerprint(s.run(true)), ArenaScenarios.fingerprint(explicit), s.name);
        }
    }

    @Test
    void idlePolicyNeverAttacks() {
        Arena.Input a = ArenaScenarios.champion("Darius", 0, 1000, 0, 3000, 170, 90);
        a.policy = (obs, mask) -> Policy.Decision.of(ActionSpace.IDLE);
        Arena.Input b = ArenaScenarios.champion("Vayne", 1, 1300, 0, 3000, 170, 90);
        Result r = run(List.of(a, b), 10);
        assertEquals(0, r.fighters.get(0).dealt, 1e-9);
        assertTrue(r.fighters.get(1).dealt > 0);
    }

    @Test
    void enemySlotsPutTheTargetFirstThenTheOthersByDistance() {
        Arena.Input a = ArenaScenarios.champion("Darius", 0, 1000, 0, 3000, 170, 90);
        a.policy = (obs, mask) -> Policy.Decision.of(mask.valid(ActionSpace.attack(1)) ? ActionSpace.attack(1) : ActionSpace.IDLE);
        Arena.Input near = ArenaScenarios.champion("Ornn", 1, 1300, 0, 3000, 0, 90);
        Arena.Input far = ArenaScenarios.champion("Rumble", 1, 1900, 0, 3000, 0, 90);
        for (Arena.Input e : List.of(near, far)) {
            e.autoAbilities = false;
            e.f.total.put(Stats.MOVE_SPEED, 0.0);
        }
        Result r = run(List.of(a, near, far), 6);
        assertEquals(0, r.fighters.get(1).taken, 1e-9);   // slot 0, the nearest: left alone
        assertTrue(r.fighters.get(2).taken > 0);           // slot 1: chased and attacked
    }

    @Test
    void observationIsFilledOnlyWhenReadAndStaysWithinBounds() {
        List<double[]> seen = new ArrayList<>();
        ScriptedPolicy script = new ScriptedPolicy(List.of("R", "3", "1", "2"));
        Arena.Input a = ArenaScenarios.champion("Renekton", 0, 1000, 200, 3000, 170, 90);
        a.policy = (obs, mask) -> {
            if (seen.size() < 400) {
                seen.add(obs.vector());
            }
            return script.decide(obs, mask);
        };
        Arena.Input b = ArenaScenarios.champion("K'Sante", 1, 1800, 0, 3000, 170, 90);
        run(List.of(a, b), 20);
        assertTrue(seen.size() > 100);
        double[] first = seen.get(0);
        assertEquals(Observation.SIZE, first.length);
        assertEquals(1, first[0], 1e-9);                    // full health
        assertEquals(1, first[Observation.SELF_SIZE], 1e-9); // enemy slot 0 present
        assertEquals(0, first[Observation.SELF_SIZE + Observation.ENEMY_SIZE], 1e-9); // no second enemy
        for (double[] v : seen) {
            for (double x : v) {
                assertTrue(x >= -1 && x <= 1 && !Double.isNaN(x));
            }
        }
        // health goes down during the fight
        assertTrue(seen.get(seen.size() - 1)[0] < 1);
    }
}
