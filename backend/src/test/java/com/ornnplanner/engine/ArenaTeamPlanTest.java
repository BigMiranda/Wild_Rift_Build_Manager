package com.ornnplanner.engine;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaModel.Action;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ArenaModel.TeamPlan;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Policy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Team plans: the side's shared target, its criteria, who follows it, and chained crowd control. */
class ArenaTeamPlanTest {

    private static Result run(List<Arena.Input> inputs, List<Action> actions, double duration) {
        return new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withoutFrames().run(inputs, actions, duration);
    }

    private static Arena.Input still(Arena.Input in) {
        in.autoAbilities = false;
        in.f.total.put(Stats.MOVE_SPEED, 0.0);
        in.f.total.put(Stats.ATTACK_DAMAGE, 0.0);
        return in;
    }

    /**
     * Blue Darius faces K'Sante (top) and blue Vayne faces Rumble (bottom); Rumble has less health. Each on its own
     * hits the one in front of it; with a shared lowest-health plan both hit Rumble.
     */
    private static List<Arena.Input> twoVsTwo(TeamPlan plan) {
        Arena.Input darius = ArenaScenarios.champion("Darius", 0, 1000, -300, 3000, 170, 90);
        Arena.Input vayne = ArenaScenarios.champion("Vayne", 0, 1000, 300, 3000, 170, 90);
        darius.autoAbilities = false;
        vayne.autoAbilities = false;
        Arena.Input ksante = still(ArenaScenarios.champion("K'Sante", 1, 1250, -300, 30000, 0, 90));
        Arena.Input rumble = still(ArenaScenarios.champion("Rumble", 1, 1250, 300, 20000, 0, 90));
        List<Arena.Input> in = List.of(darius, vayne, ksante, rumble);
        if (plan != null) {
            darius.team = plan;
            vayne.team = plan;
        }
        return in;
    }

    private static TeamPlan plan(String focus, boolean shared) {
        TeamPlan p = new TeamPlan();
        p.focus = focus;
        p.shared = shared;
        p.switchBelow = null;
        return p;
    }

    @Test
    void withoutAPlanEachHitsTheOneInFrontOfIt() {
        Result r = run(twoVsTwo(null), List.of(), 4);
        assertTrue(r.fighters.get(2).taken > 100, "K'Sante took " + r.fighters.get(2).taken);
        assertTrue(r.fighters.get(3).taken > 100, "Rumble took " + r.fighters.get(3).taken);
    }

    @Test
    void sharedLowestHealthPlanFocusesOneTarget() {
        Result r = run(twoVsTwo(plan("lowest_hp", true)), List.of(), 4);
        assertEquals(0, r.fighters.get(2).taken, 1e-9, "K'Sante should not be hit");
        assertTrue(r.fighters.get(3).taken > 1000, "Rumble took " + r.fighters.get(3).taken);
    }

    @Test
    void orderPlanFollowsThePriority() {
        TeamPlan p = plan("order", true);
        p.order = List.of(2, 3);
        Result r = run(twoVsTwo(p), List.of(), 4);
        assertEquals(0, r.fighters.get(3).taken, 1e-9, "Rumble should not be hit");
        assertTrue(r.fighters.get(2).taken > 1000, "K'Sante took " + r.fighters.get(2).taken);
    }

    @Test
    void championsNotFollowingThePlanPickTheirOwn() {
        List<Arena.Input> in = twoVsTwo(plan("lowest_hp", true));
        in.get(0).followTeam = false;   // Darius keeps to the nearest, K'Sante
        Result r = run(in, List.of(), 4);
        assertTrue(r.fighters.get(2).taken > 100, "K'Sante took " + r.fighters.get(2).taken);
        assertTrue(r.fighters.get(3).taken > 100, "Rumble took " + r.fighters.get(3).taken);
    }

    @Test
    void unsharedNearestPlanBehavesLikeNoPlan() {
        Result none = run(twoVsTwo(null), List.of(), 4);
        Result own = run(twoVsTwo(plan("nearest", false)), List.of(), 4);
        assertEquals(ArenaScenarios.summary(none), ArenaScenarios.summary(own));
    }

    /**
     * Darius follows a plan that puts K'Sante first; Vayne (not following it) brings Rumble down. Once Rumble is in
     * Darius' reach and below 25% he switches to it, so it dies sooner than with no switch.
     */
    @Test
    void switchesToAnEnemyInReachBelowTheThreshold() {
        Double never = rumbleDeath(null);
        Double low = rumbleDeath(0.25);
        assertTrue(never != null && low != null && low < never - 0.1, "Rumble died at " + low + " vs " + never);
    }

    private static Double rumbleDeath(Double switchBelow) {
        TeamPlan p = plan("order", true);
        p.order = List.of(2, 3);
        p.switchBelow = switchBelow;
        Arena.Input darius = ArenaScenarios.champion("Darius", 0, 1000, 0, 3000, 170, 90);
        darius.autoAbilities = false;
        darius.team = p;
        Arena.Input vayne = ArenaScenarios.champion("Vayne", 0, 1000, 300, 3000, 500, 90);
        vayne.autoAbilities = false;
        vayne.team = p;
        vayne.followTeam = false;
        vayne.target = 3;
        Arena.Input ksante = still(ArenaScenarios.champion("K'Sante", 1, 1250, -100, 30000, 0, 90));
        Arena.Input rumble = still(ArenaScenarios.champion("Rumble", 1, 1250, 100, 1500, 0, 90));
        return run(List.of(darius, vayne, ksante, rumble), List.of(), 20).fighters.get(3).deathTime;
    }

    /**
     * Renekton's empowered attack stuns Vayne; Darius stands in range of his pull. Each step Darius is free, count
     * the steps his pull is allowed while Vayne is stunned: with chained control only in the stun's last 0.3s.
     */
    @Test
    void chainedControlWaitsForTheAllysToEnd() {
        int free = stunnedStepsWithPullAllowed(false);
        int chained = stunnedStepsWithPullAllowed(true);
        assertTrue(free >= 25, "steps without chaining: " + free);
        assertTrue(chained > 0 && chained <= 0.3 / Arena.DT + 1, "steps with chaining: " + chained);
    }

    private static int stunnedStepsWithPullAllowed(boolean chain) {
        TeamPlan p = plan("nearest", true);
        p.chainCc = chain;
        Arena.Input renekton = ArenaScenarios.champion("Renekton", 0, 1000, 0, 3000, 170, 90);
        renekton.autoAbilities = false;
        Arena.Input darius = ArenaScenarios.champion("Darius", 0, 1000, 350, 3000, 170, 90);
        darius.f.total.put(Stats.MOVE_SPEED, 0.0);
        int pull = ActionSpace.cast(ActionSpace.slotIndex("3"), 0);
        AtomicInteger steps = new AtomicInteger();
        darius.policy = (obs, mask) -> {
            if (obs.enemy(0).crowdControlled && mask.valid(pull)) {
                steps.incrementAndGet();
            }
            return Policy.Decision.of(ActionSpace.IDLE);
        };
        Arena.Input vayne = still(ArenaScenarios.champion("Vayne", 1, 1250, 100, 30000, 0, 90));
        renekton.team = p;
        darius.team = p;
        Action w = ArenaScenarios.order(0, 0.2, "ability", "2", 2);
        run(List.of(renekton, darius, vayne), List.of(w), 3);
        return steps.get();
    }

    @Test
    void scenariosSavedBeforePlansHaveNone() throws Exception {
        ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        ArenaModel.Scenario sc = json.readValue("{\"name\":\"old\",\"fighters\":[{\"team\":0,\"buildId\":1},"
                + "{\"team\":1,\"buildId\":2}],\"actions\":[],\"duration\":60}", ArenaModel.Scenario.class);
        assertNull(sc.teams);
        assertTrue(sc.fighters.get(0).followTeam);
    }
}
