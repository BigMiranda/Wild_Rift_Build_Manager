package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel.Action;
import com.ornnplanner.arena.ai.ActionMask;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Observation;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.arena.ai.ScriptedPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The generic predictions of observation schema 3, from the sheets: hit chance, chained control, marks, recasts. */
class ArenaPredictionTest {

    private static final int Q = 0;
    private static final int W = 1;
    private static final int E = 2;
    private static final int R = 3;

    /** Decides like the script (or holds still) and keeps the first observation that passes a test. */
    private static final class Probe implements Policy {
        final Predicate<Observation> when;
        final Policy play;
        Observation seen;
        Observation.Self self;
        Observation.Other target;

        Probe(Predicate<Observation> when, Policy play) {
            this.when = when;
            this.play = play;
        }

        @Override
        public Decision decide(Observation obs, ActionMask mask) {
            obs.predicted();   // the schema 3 facts are filled on demand
            if (seen == null && when.test(obs)) {
                seen = obs;
                self = obs.self();
                target = obs.enemy(0);
                obs.vector();
            }
            return play.decide(obs, mask);
        }
    }

    private static Policy idle() {
        return (o, m) -> Policy.Decision.of(ActionSpace.IDLE);
    }

    private static Probe probe(Arena.Input me, Arena.Input enemy, List<Action> orders, Predicate<Observation> when,
                               double duration) {
        Probe p = new Probe(when, idle());
        me.policy = p;
        me.autoAbilities = false;
        enemy.autoAbilities = false;
        if (enemy.policy == null) {
            enemy.policy = idle();
        }
        new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withoutFrames().run(List.of(me, enemy), orders, duration);
        assertNotNull(p.seen, "the moment never came");
        return p;
    }

    @Test
    void vectorHasTheSchemaSize() {
        Arena.Input ornn = ArenaScenarios.champion("Ornn", 0, 1000, 0, 3000, 100, 90);
        Arena.Input darius = ArenaScenarios.champion("Darius", 1, 1400, 0, 3000, 100, 90);
        Probe p = probe(ornn, darius, List.of(), o -> true, 1);
        assertEquals(Observation.SIZE, p.seen.vector().length);
        assertEquals(3, Observation.SCHEMA_VERSION);
        for (double v : p.seen.vector()) {
            assertTrue(v >= -1 && v <= 1);
        }
    }

    @Test
    void ornnsChargeHitsOnlyIntoTerrain() {
        // open lane: the dash would pass through Darius (its body hits) but no wall holds him: no knock-up
        Arena.Input ornn = ArenaScenarios.champion("Ornn", 0, 1000, 0, 3000, 100, 90);
        Arena.Input darius = ArenaScenarios.champion("Darius", 1, 1400, 0, 3000, 100, 90);
        Probe open = probe(ornn, darius, List.of(), o -> true, 1);
        assertEquals(0, open.self.hitChance[E], 1e-9);
        assertTrue(open.self.bodyHit[E] > 0.5, "the dash's body: " + open.self.bodyHit[E]);
        // Darius against the wall, Ornn charging at him: the shockwave of the wall catches him
        Arena.Input ornn2 = ArenaScenarios.champion("Ornn", 0, 1000, 0, 3000, 100, 90);
        Arena.Input darius2 = ArenaScenarios.champion("Darius", 1, 1000, 440, 3000, 100, 90);
        Probe wall = probe(ornn2, darius2, List.of(), o -> true, 1);
        assertTrue(wall.self.hitChance[E] >= 0.6, "into the wall: " + wall.self.hitChance[E]);
        assertTrue(wall.self.timeToEffect[E] > 0.25 && wall.self.timeToEffect[E] < 0.6, "" + wall.self.timeToEffect[E]);
    }

    @Test
    void aTargetWithADashReadyMightStepOutOfALongSkillshot() {
        // Vayne with Tumble up: Ornn's elemental comes from far, so she could tumble out of it
        Arena.Input ornn = ArenaScenarios.champion("Ornn", 0, 1000, 0, 3000, 100, 90);
        Arena.Input vayne = ArenaScenarios.champion("Vayne", 1, 1500, 0, 3000, 100, 90);
        Probe free = probe(ornn, vayne, List.of(), o -> true, 1);
        assertTrue(free.target.dashReady, "Vayne's Tumble");
        assertTrue(free.self.hitChance[R] < 1, "she can tumble out: " + free.self.hitChance[R]);
        assertTrue(free.self.timeToEffect[R] > 2, "the elemental comes from far: " + free.self.timeToEffect[R]);
        assertEquals(1, free.self.hitChance[Q] > 0 ? 1 : 0, "the Q in range");
    }

    @Test
    void marksConsumedAndAppliedByEachAbility() {
        Arena.Input ornn = ArenaScenarios.champion("Ornn", 0, 1000, 300, 3000, 100, 90);
        Arena.Input darius = ArenaScenarios.champion("Darius", 1, 1000, 440, 3000, 100, 90);
        List<Action> orders = new ArrayList<>(List.of(ArenaScenarios.order(0, 0, "ability", "2", 1)));
        // W's last hit puts Brittle; E into the wall then consumes it
        Probe brittle = probe(ornn, darius, orders, o -> o.time() > 0.8 && o.enemy(0).markLeft > 0, 2);
        assertTrue(brittle.self.consumesMark[E], "E into the wall breaks the Brittle");
        assertTrue(brittle.self.attackConsumesMark, "so does his attack");
        assertFalse(brittle.self.consumesMark[Q], "the Q only slows");
        assertTrue(brittle.self.appliesMark[R] && brittle.self.appliesMark[W]);
    }

    @Test
    void chainedControlWindow() {
        // Renekton's empowered W stuns Ornn 0.75s; Darius's E (pull, 0.25s to land) chains in its last 0.3s + 0.1s
        Arena.Input darius = ArenaScenarios.champion("Darius", 0, 1000, 0, 3000, 100, 90);
        Arena.Input ornn = ArenaScenarios.champion("Ornn", 1, 1250, 0, 3000, 100, 90);
        Arena.Input renekton = ArenaScenarios.champion("Renekton", 0, 1100, 150, 3000, 100, 90);
        renekton.autoAbilities = false;
        renekton.policy = new ScriptedPolicy(List.of());
        List<Action> orders = new ArrayList<>(List.of(ArenaScenarios.order(2, 0, "ability", "2", 1)));
        Probe early = new Probe(o -> o.enemy(0).controlLeft > 0.6, idle());
        Probe late = new Probe(o -> o.enemy(0).controlLeft > 0 && o.enemy(0).controlLeft < 0.3, idle());
        darius.policy = (o, m) -> {
            early.decide(o, m);
            return late.decide(o, m);
        };
        darius.autoAbilities = false;
        ornn.autoAbilities = false;
        ornn.policy = idle();
        new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withoutFrames().run(List.of(darius, ornn, renekton), orders, 3);
        assertNotNull(early.seen);
        assertNotNull(late.seen);
        assertFalse(early.self.chainsControl[E], "too early: it would be wasted");
        assertTrue(late.self.chainsControl[E], "lands as the stun ends");
    }

    @Test
    void theRecastWouldHitWhenTheElementalComesBackThroughTheTarget() {
        Arena.Input ornn = ArenaScenarios.champion("Ornn", 0, 1000, 0, 3000, 100, 90);
        ornn.f.ranks = new int[] {4, 4, 4, 3};
        Arena.Input darius = ArenaScenarios.champion("Darius", 1, 1500, 0, 3000, 100, 90);
        List<Action> orders = new ArrayList<>(List.of(ArenaScenarios.order(0, 0, "ability", "R", 1)));
        Probe p = probe(ornn, darius, orders, o -> o.self().recastHit[R] > 0, 5);
        assertTrue(p.self.recastOpen[R]);
        assertTrue(p.self.recastHit[R] >= 0.6, "" + p.self.recastHit[R]);
        assertTrue(p.self.recastLeft > 0);
    }
}
