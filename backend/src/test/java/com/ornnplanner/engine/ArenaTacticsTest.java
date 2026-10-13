package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaModel.Action;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.arena.ai.ScriptedPolicy;
import com.ornnplanner.arena.ai.TacticalPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The moves and tactics beyond the scripted autopilot: retreat, kite, dodge, recasts, actives and spells. */
class ArenaTacticsTest {

    private static Result run(List<Arena.Input> inputs, List<Action> actions, double duration) {
        return new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(inputs, actions, duration);
    }

    private static Policy always(int action) {
        return (obs, mask) -> Policy.Decision.of(mask.valid(action) ? action : ActionSpace.IDLE);
    }

    private static Arena.Input still(Arena.Input in) {
        in.autoAbilities = false;
        in.f.total.put(Stats.MOVE_SPEED, 0.0);
        return in;
    }

    private static ArenaModel.UnitFrame last(Result r, int unit) {
        return r.frames.get(r.frames.size() - 1).units.get(unit);
    }

    @Test
    void retreatWalksAwayFromTheEnemies() {
        Arena.Input a = ArenaScenarios.champion("Darius", 0, 3000, 0, 3000, 170, 90);
        a.policy = always(ActionSpace.RETREAT);
        Result r = run(List.of(a, still(ArenaScenarios.champion("Vayne", 1, 3500, 0, 3000, 0, 90))), List.of(), 3);
        assertEquals(3000 - 3 * 350, last(r, 0).x, 30);
    }

    @Test
    void retreatRunsAlongTheWallWhenPinnedAgainstIt() {
        // Enemy straight below a champion at the top wall: away is into the wall, so it runs along the lane.
        Arena.Input a = ArenaScenarios.champion("Darius", 0, 3000, 470, 3000, 170, 90);
        a.policy = always(ActionSpace.RETREAT);
        Result r = run(List.of(a, still(ArenaScenarios.champion("Vayne", 1, 3000, 0, 3000, 0, 90))), List.of(), 2);
        assertTrue(Math.abs(last(r, 0).x - 3000) > 500, "x = " + last(r, 0).x);
    }

    @Test
    void kitingTakesLessDamageThanStandingAndTrading() {
        Function<Integer, Result> fight = move -> {
            Arena.Input vayne = ArenaScenarios.champion("Vayne", 0, 5000, 0, 3000, 170, 60);
            vayne.policy = always(move);
            Arena.Input darius = ArenaScenarios.champion("Darius", 1, 5700, 0, 30000, 170, 90);
            darius.autoAbilities = false;
            darius.f.total.put(Stats.MOVE_SPEED, 300.0);
            return run(List.of(vayne, darius), List.of(), 15);
        };
        Result kite = fight.apply(ActionSpace.kite(0));
        Result trade = fight.apply(ActionSpace.attack(0));
        assertTrue(kite.fighters.get(1).taken > 0);
        assertTrue(kite.fighters.get(0).taken < trade.fighters.get(0).taken * 0.5,
                "kite " + kite.fighters.get(0).taken + " vs trade " + trade.fighters.get(0).taken);
    }

    @Test
    void dodgeSideStepsOrnnsRupture() {
        Function<Policy, Double> fight = policy -> {
            Arena.Input ornn = still(ArenaScenarios.champion("Ornn", 0, 1000, 0, 3000, 100, 90));
            Arena.Input vayne = ArenaScenarios.champion("Vayne", 1, 1700, 0, 3000, 0, 90);
            vayne.f.total.put(Stats.MOVE_SPEED, 450.0);
            vayne.autoAbilities = false;
            vayne.policy = policy;
            Result r = run(List.of(ornn, vayne), new ArrayList<>(List.of(ArenaScenarios.order(0, 0.5, "ability", "1", 1))), 2);
            return r.fighters.get(0).damageBySource.getOrDefault("1 · Ruptura Vulcânica", 0.0);
        };
        assertTrue(fight.apply(always(ActionSpace.IDLE)) > 0);
        assertEquals(0, fight.apply(always(ActionSpace.DODGE)), 1e-9);
    }

    @Test
    void tacticalOrnnRecastsHisUltimateOnItsOwn() {
        Arena.Input ornn = ArenaScenarios.champion("Ornn", 0, 1000, 0, 5000, 100, 90);
        ornn.f.ranks = new int[] {4, 4, 4, 3};
        ornn.priority = new ArrayList<>(List.of("R"));
        ornn.policy = new TacticalPolicy(ornn.priority);
        Arena.Input b = still(ArenaScenarios.champion("Darius", 1, 1500, 0, 50000, 0, 90));
        Result r = run(List.of(ornn, b), List.of(), 8);
        assertTrue(r.events.stream().anyMatch(e -> e.text.contains("devolveu o elemental")), r.events.toString());
        // the scripted autopilot never recasts
        ornn.policy = new ScriptedPolicy(ornn.priority);
        Result s = run(List.of(ornn, still(ArenaScenarios.champion("Darius", 1, 1500, 0, 50000, 0, 90))), List.of(), 8);
        assertTrue(s.events.stream().noneMatch(e -> e.text.contains("devolveu o elemental")));
    }

    private static Result withSpells(double hp, double enemyHp) {
        Arena.Input a = ArenaScenarios.champion("Darius", 0, 1000, 0, hp, 170, 90);
        a.f.spells.addAll(List.of("Barreira", "Incendiar"));
        a.activeCooldowns.put("Barreira", 180.0);
        a.activeCooldowns.put("Incendiar", 180.0);
        a.policy = new TacticalPolicy(a.priority);
        return run(List.of(a, ArenaScenarios.champion("Renekton", 1, 1500, 0, enemyHp, 170, 90)), List.of(), 30);
    }

    private static ArenaModel.Event used(Result r, String what) {
        return r.events.stream().filter(e -> Integer.valueOf(0).equals(e.fighter) && e.text.contains("usou " + what))
                .findFirst().orElse(null);
    }

    @Test
    void tacticalUsesBarrierWhenLow() {
        Result r = withSpells(2500, 2500);
        ArenaModel.Event barrier = used(r, "Barreira");
        assertNotNull(barrier);
        ArenaModel.Frame before = r.frames.stream().filter(f -> f.t <= barrier.t + 1e-9).reduce((x, y) -> y).orElseThrow();
        assertTrue(before.units.get(0).hp < 0.36 * 2500, "Barreira at " + before.units.get(0).hp);
    }

    @Test
    void tacticalIgnitesAWeakenedTarget() {
        Result r = withSpells(5000, 2000);
        ArenaModel.Event ignite = used(r, "Incendiar");
        assertNotNull(ignite);
        ArenaModel.Frame after = r.frames.stream().filter(f -> f.t >= ignite.t - 1e-9).findFirst().orElseThrow();
        assertTrue(after.units.get(1).hp < 0.5 * after.units.get(1).maxHp, "Incendiar at " + after.units.get(1).hp + "/" + after.units.get(1).maxHp);
        assertNull(used(r, "Barreira"));   // never low enough to need it
    }

    @Test
    void scriptedAutopilotIgnoresTheNewActions() {
        // Same scenario, explicit scripted policy: no spells used on its own, no dodge, no retreat.
        Arena.Input a = ArenaScenarios.champion("Darius", 0, 1000, 0, 2500, 170, 90);
        a.f.spells.add("Barreira");
        a.activeCooldowns.put("Barreira", 180.0);
        Result r = run(List.of(a, ArenaScenarios.champion("Renekton", 1, 1500, 0, 2500, 170, 90)), List.of(), 30);
        assertTrue(r.events.stream().noneMatch(e -> e.text.contains("usou Barreira")));
    }

    private static Arena.Input withSpells(String champion, int team, double x, double y, String spell, boolean tactical) {
        Arena.Input in = ArenaScenarios.champion(champion, team, x, y, 2800, 160, 90);
        in.f.spells.addAll(List.of("Flash", spell));
        in.activeCooldowns.put("Flash", 150.0);
        in.activeCooldowns.put(spell, 120.0);
        if (tactical) {
            in.policy = new TacticalPolicy(in.priority);
        }
        return in;
    }

    /**
     * Every duel of the six champions, three starting lanes and three second spells: the tactical side against the
     * scripted one wins more and loses less than the scripted side did in the same fight.
     */
    @Test
    void tacticalBeatsTheScriptOverAllDuels() {
        int[] script = new int[3];
        int[] tactical = new int[3];
        for (String spell : List.of("Incendiar", "Barreira", "Exaustão")) {
            for (String a : ArenaScenarios.CHAMPIONS) {
                for (String b : ArenaScenarios.CHAMPIONS) {
                    for (double y : new double[] {-250, 0, 250}) {
                        if (a.equals(b)) {
                            continue;
                        }
                        for (boolean tac : new boolean[] {false, true}) {
                            Result r = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withoutFrames().run(List.of(
                                    withSpells(a, 0, 4000, y, spell, tac), withSpells(b, 1, 4900, -y, spell, false)), List.of(), 60);
                            (tac ? tactical : script)[r.winner == 0 ? 0 : r.winner == 1 ? 1 : 2]++;
                        }
                    }
                }
            }
        }
        System.out.printf("script x script: %d wins, %d losses, %d draws; tactical x script: %d wins, %d losses, %d draws%n",
                script[0], script[1], script[2], tactical[0], tactical[1], tactical[2]);
        assertTrue(tactical[0] >= script[0] && tactical[1] < script[1]);
    }
}
