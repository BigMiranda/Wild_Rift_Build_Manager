package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaModel.Frame;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ArenaModel.RiftOptions;
import com.ornnplanner.arena.ArenaModel.Shape;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.arena.rift.RiftMap;
import com.ornnplanner.arena.rift.RiftRules;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The full map: the geometry, and the units that live on it. */
class RiftTest {

    static final RiftMap MAP = RiftMap.load(new ClassPathResource("seed/mapa_7_3d.yml"));
    static final RiftRules RULES = RiftRules.load(new ClassPathResource("seed/objetivos_7_3d.yml"));
    private static final Policy STILL = (o, m) -> Policy.Decision.of(ActionSpace.IDLE);

    private static Arena.Input champ(String name, int team, double x, double y) {
        Arena.Input in = ArenaScenarios.champion(name, team, x, y, 3000, 150, 80);
        in.policy = STILL;
        in.autoAbilities = false;
        return in;
    }

    private static RiftOptions only(Consumer<RiftOptions> on) {
        RiftOptions o = new RiftOptions();
        o.minions = false;
        o.towers = false;
        o.jungle = false;
        o.plants = false;
        o.objectives = false;
        on.accept(o);
        return o;
    }

    private static Result fight(RiftOptions o, double seconds, List<Arena.Input> in, ArenaModel.Action... orders) {
        return new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withRift(MAP, RULES, o).run(new ArrayList<>(in), List.of(orders), seconds);
    }

    private static long shapes(Frame f, String role) {
        return f.shapes.stream().filter(s -> role.equals(s.role)).count();
    }

    private static ArenaModel.Action move(int who, double t, double x, double y) {
        ArenaModel.Action a = ArenaScenarios.order(who, t, "move", null, null);
        a.x = x;
        a.y = y;
        return a;
    }

    @Test
    void everyPlaceOfTheMapCanBeReachedOnFoot() {
        List<double[]> places = new ArrayList<>();
        MAP.towers.forEach(s -> places.add(new double[] {s.x, s.y}));
        MAP.camps.forEach(s -> places.add(new double[] {s.x, s.y}));
        MAP.plants.forEach(s -> places.add(new double[] {s.x, s.y}));
        places.add(MAP.baronPit);
        places.add(MAP.dragonPit);
        double[] from = MAP.fountainBlue;
        for (double[] p : places) {
            assertFalse(MAP.solid(p[0], p[1]), "inside a wall: " + p[0] + "," + p[1]);
            assertTrue(MAP.path(from[0], from[1], p[0], p[1]).length > 0 || MAP.clear(from[0], from[1], p[0], p[1]),
                    "no way to " + p[0] + "," + p[1]);
        }
    }

    @Test
    void lanesAreFreeOfWalls() {
        MAP.lanes.forEach((name, pts) -> {
            for (int i = 0; i + 1 < pts.length; i++) {
                assertTrue(MAP.clear(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1]), name + " segment " + i);
            }
        });
    }

    @Test
    void thePathGoesAroundAWall() {
        double[][] way = MAP.path(3300, 4800, 4600, 4800);
        assertFalse(MAP.clear(3300, 4800, 4600, 4800), "a wall on the straight line");
        assertTrue(way.length >= 1);
        double ax = 3300;
        double ay = 4800;
        for (double[] p : way) {
            assertTrue(MAP.clear(ax, ay, p[0], p[1]));
            ax = p[0];
            ay = p[1];
        }
    }

    @Test
    void aChampionWalksAroundWallsAndADashStopsAtThem() {
        Arena.Input a = champ("Ornn", 0, 3300, 4800);
        Arena.Input b = champ("Darius", 1, 11000, 11000);
        Result r = fight(only(o -> { }), 10, List.of(a, b), move(0, 0.1, 4600, 4800));
        ArenaModel.UnitFrame end = r.frames.get(r.frames.size() - 1).units.get(0);
        assertTrue(Math.hypot(end.x - 4600, end.y - 4800) < 60, end.x + "," + end.y);
        for (Frame f : r.frames) {
            assertFalse(MAP.solid(f.units.get(0).x, f.units.get(0).y), "inside a wall");
        }
    }

    @Test
    void wavesLeaveOnTimeAlongEachLane() {
        RiftOptions o = only(x -> x.minions = true);
        o.minute = 0;
        Arena.Input a = champ("Ornn", 0, 6000, 200);
        Arena.Input b = champ("Darius", 1, 6000, 11800);
        Result r = fight(o, 26, List.of(a, b));   // the first wave leaves at 0:25 (the user, 05/11/2026)
        Frame before = r.frames.get(r.frames.size() - 15);
        Frame after = r.frames.get(r.frames.size() - 1);
        assertEquals(0, shapes(before, "tropa"));
        assertEquals(36, shapes(after, "tropa"), "6 by 3 lanes by 2 sides");
    }

    @Test
    void theThirdWaveBringsACannonAndTheGapShrinksAfterFourMinutes() {
        assertEquals(6, RULES.wave(1, false).size());
        assertEquals(7, RULES.wave(3, false).size());
        assertEquals(8, RULES.wave(3, true).size());
        assertEquals(25, RULES.waveGap(100), 1e-9);   // 25 s the whole match (the user, 05/11/2026)
        assertEquals(25, RULES.waveGap(300), 1e-9);
        assertEquals(25, RULES.waveGap(610), 1e-9);
    }

    @Test
    void wavesMeetAndFight() {
        RiftOptions o = only(x -> x.minions = true);
        o.minute = 0;
        Arena.Input a = champ("Ornn", 0, 6000, 200);
        Arena.Input b = champ("Darius", 1, 6000, 11800);
        Result r = fight(o, 60, List.of(a, b));
        long left = shapes(r.frames.get(r.frames.size() - 1), "tropa");
        assertTrue(left < 72, "they kill each other: " + left);
    }

    @Test
    void theLastHitGivesItsGoldAndTheRewardIsSharedAsExperience() {
        RiftOptions o = only(x -> x.minions = true);
        o.minute = 0;
        // on the middle lane, by the first clash
        Arena.Input a = ArenaScenarios.champion("Vayne", 0, 5800, 5800, 3000, 800, 300);
        Arena.Input b = champ("Darius", 1, 11500, 11500);
        a.autoAbilities = false;
        a.policy = null;
        // it waits for the wave: alone, it would walk off after the far champion
        Result r = fight(o, 90, List.of(a, b), ArenaScenarios.order(0, 0, "stop", null, null), ArenaScenarios.order(0, 48, "attack", null, null));
        double hits = r.fighters.get(0).events.getOrDefault("ultimo_golpe", 0.0);
        assertTrue(hits >= 1, "it took a last hit");
        assertTrue(r.fighters.get(0).goldEarned >= hits * 40);
        assertTrue(r.fighters.get(0).xpEarned > 0);
    }

    @Test
    void towersHitHarderInARowAndPreferMinions() {
        RiftOptions o = only(x -> x.towers = true);
        o.minute = 8;
        Arena.Input a = champ("Ornn", 0, 7200, 6900);   // under the enemy middle outer tower
        Arena.Input b = champ("Darius", 1, 11500, 11500);
        Result r = fight(o, 5, List.of(a, b));
        double taken = r.fighters.get(0).taken;
        assertTrue(taken > 0, "shot");
        assertTrue(r.fighters.get(0).damageBySource.isEmpty());
        assertEquals(4, ramp(), 1e-9);
    }

    private static double ramp() {
        return ((List<?>) RULES.map("torres", "ataques_seguidos").get("valores")).size();
    }

    @Test
    void aTowerSwitchesToAChampionHittingAnAlliedChampion() {
        RiftOptions o = only(x -> {
            x.towers = true;
            x.minions = false;
        });
        o.minute = 8;
        double[] t = MAP.towers.stream().filter(s -> s.team == 1 && "externa".equals(s.kind) && "meio".equals(s.lane)).findFirst()
                .map(s -> new double[] {s.x, s.y}).orElseThrow();
        Arena.Input a = ArenaScenarios.champion("Ornn", 0, t[0] - 500, t[1] - 300, 3000, 150, 80);
        Arena.Input b = champ("Darius", 1, t[0] - 450, t[1] - 300);
        a.autoAbilities = false;
        Result r = fight(o, 3, List.of(a, b));
        assertTrue(r.fighters.get(0).taken > 200, "the tower shot it");
    }

    @Test
    void aCampHitsBackAndGoesHomeAndGivesItsGold() {
        RiftOptions o = only(x -> x.jungle = true);
        o.minute = 2;
        RiftMap.Spot camp = MAP.camps.stream().filter(c -> c.name.equals("Sentinela Azul") && c.team == 0).findFirst().orElseThrow();
        Arena.Input a = ArenaScenarios.champion("Ornn", 0, camp.x + 150, camp.y, 4000, 900, 200);
        Arena.Input b = champ("Darius", 1, 11500, 11500);
        a.autoAbilities = false;
        a.policy = null;
        ArenaModel.Action focus = ArenaScenarios.order(0, 0.1, "attack", null, 2);
        Result r = fight(o, 12, List.of(a, b), focus);
        assertNotNull(r);
        assertEquals(1, r.fighters.get(0).events.getOrDefault("monstro:Sentinela Azul", 0.0), r.fighters.get(0).events.toString());
        assertTrue(r.fighters.get(0).goldEarned >= 130);
        assertEquals(1, r.fighters.get(0).events.getOrDefault("buff:azul", 0.0));
        assertTrue(r.fighters.get(0).taken > 0, "it hit back");
    }

    @Test
    void aBushHidesAChampionUntilItAttacks() {
        double[] bush = MAP.bushes.get(0);
        RiftOptions o = only(x -> { });
        Arena.Input a = champ("Ornn", 0, bush[0], bush[1]);
        Arena.Input b = champ("Darius", 1, bush[0] + 500, bush[1]);
        Result r = fight(o, 1, List.of(a, b));
        assertTrue(r.frames.get(r.frames.size() - 1).units.get(0).invisible);
        RiftOptions all = only(x -> x.vision = false);
        assertFalse(fight(all, 1, List.of(a, b)).frames.get(5).units.get(0).invisible);
    }

    @Test
    void baronAppliesItsCorrosionAndGivesItsHandToTheTeam() {
        RiftOptions o = only(x -> x.objectives = true);
        o.minute = 20;
        Arena.Input a = ArenaScenarios.champion("Vayne", 0, MAP.baronPit[0] + 300, MAP.baronPit[1], 9000, 2500, 300);
        Arena.Input b = champ("Darius", 1, 11500, 11500);
        a.autoAbilities = false;
        a.policy = null;
        ArenaModel.Action focus = ArenaScenarios.order(0, 0.1, "attack", null, 2);
        Result r = fight(o, 60, List.of(a, b), focus);
        assertTrue(r.fighters.get(0).events.getOrDefault("objetivo:barao", 0.0) >= 1
                || r.fighters.get(0).damageBySource.getOrDefault("Ataque", 0.0) > 0, r.fighters.get(0).events.toString());
        assertTrue(r.fighters.get(0).taken > 0, "Baron fought back");
    }

    @Test
    void objectiveBuffsChangeTheFight() {
        Arena.Input a = ArenaScenarios.champion("Darius", 0, 3000, 0, 3000, 150, 80);
        Arena.Input b = ArenaScenarios.champion("Ornn", 1, 3100, 0, 3000, 150, 80);
        ArenaModel.ObjectiveBuffs blue = new ArenaModel.ObjectiveBuffs();
        blue.dragons.put("infernal", 3);
        blue.baron = true;
        Result with = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withObjectives(RULES, new ArenaModel.ObjectiveBuffs[] {blue, null})
                .run(new ArrayList<>(List.of(a, b)), List.of(), 15);
        Result without = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(new ArrayList<>(List.of(a, b)), List.of(), 15);
        assertTrue(with.fighters.get(0).dealt > without.fighters.get(0).dealt, with.fighters.get(0).dealt + " > " + without.fighters.get(0).dealt);
    }

    @Test
    void theFullMapIsDeterministic() {
        RiftOptions o = new RiftOptions();
        o.minute = 12;
        o.jungle = true;
        String first = null;
        for (int i = 0; i < 2; i++) {
            Arena.Input a = ArenaScenarios.champion("Darius", 0, 5500, 5500, 3000, 150, 80);
            Arena.Input b = ArenaScenarios.champion("Vayne", 1, 6500, 6500, 3000, 150, 80);
            String fp = ArenaScenarios.fingerprint(fight(o, 40, List.of(a, b)));
            if (first != null) {
                assertEquals(first, fp);
            }
            first = fp;
        }
    }

    /** Opt-in: simulated seconds per second of a 5 v 5 at a lane's minions, towers and jungle. */
    @Test
    void benchmark() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("arena.bench"));
        RiftOptions o = new RiftOptions();
        o.minute = 12;
        o.jungle = true;
        long t0 = System.nanoTime();
        double simulated = 0;
        int fights = 0;
        while (System.nanoTime() - t0 < 8e9) {
            List<Arena.Input> in = new ArrayList<>();
            for (int k = 0; k < 5; k++) {
                in.add(ArenaScenarios.champion(ArenaScenarios.CHAMPIONS.get(k), 0, 5000 + 80 * k, 5000, 40000, 100, 90));
                in.add(ArenaScenarios.champion(ArenaScenarios.CHAMPIONS.get(k), 1, 7000 - 80 * k, 7000, 40000, 100, 90));
            }
            Result r = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withoutFrames().withRift(MAP, RULES, o).run(in, List.of(), 60);
            simulated += r.duration;
            fights++;
        }
        System.out.printf("full map 5v5, 1 thread: %.0f simulated s/s (%d fights)%n", simulated / ((System.nanoTime() - t0) / 1e9), fights);
    }

    @Test
    void shapesKnowTheirUnit() {
        RiftOptions o = only(x -> x.towers = true);
        Result r = fight(o, 0.5, List.of(champ("Ornn", 0, 6000, 200), champ("Darius", 1, 6000, 11800)));
        Shape tower = r.frames.get(r.frames.size() - 1).shapes.stream().filter(s -> "torre".equals(s.role)).findFirst().orElseThrow();
        assertNotNull(tower.hp);
        assertEquals(18 + 2, r.frames.get(r.frames.size() - 1).shapes.stream().filter(s -> s.role != null).count(), "towers and Nexus");
    }
}
