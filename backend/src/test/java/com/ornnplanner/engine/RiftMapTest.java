package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.rift.Match;
import com.ornnplanner.arena.rift.RiftMap;
import com.ornnplanner.arena.rift.RiftRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rodada 10, Parte 1: the map traced from the minimap and the drakes of the user's prints. */
class RiftMapTest {

    static final RiftMap MAP = RiftTest.MAP;
    static final RiftRules RULES = RiftTest.RULES;

    private static void reachable(String what, double x, double y) {
        double[] from = MAP.fountainBlue;
        assertFalse(MAP.solid(x, y), what + " inside a wall: " + x + "," + y);
        assertTrue(MAP.path(from[0], from[1], x, y).length > 0 || MAP.clear(from[0], from[1], x, y), "no way to " + what);
        double[][] way = MAP.path(from[0], from[1], x, y);
        if (way.length > 0) {
            double[] end = way[way.length - 1];
            assertTrue(Math.hypot(end[0] - x, end[1] - y) < 150, "the way to " + what + " ends at it: " + end[0] + "," + end[1]);
        }
    }

    @Test
    void everyLanePitCampPlantAndTowerOfBothSidesIsReachableOnFoot() {
        MAP.lanes.forEach((name, pts) -> {
            for (double[] p : pts) {
                reachable("rota " + name, p[0], p[1]);
            }
        });
        MAP.towers.forEach(s -> reachable("torre " + s.lane + " " + s.kind + " " + s.team, s.x, s.y));
        MAP.camps.forEach(s -> reachable(s.name + " " + s.team, s.x, s.y));
        MAP.crabs.forEach(s -> reachable("Aronguejo", s.x, s.y));
        MAP.plants.forEach(s -> reachable(s.kind, s.x, s.y));
        reachable("fosso do Barão", MAP.baronPit[0], MAP.baronPit[1]);
        reachable("fosso do Dragão", MAP.dragonPit[0], MAP.dragonPit[1]);
        reachable("Nexo vermelho", MAP.nexusRed[0], MAP.nexusRed[1]);
    }

    @Test
    void theWallsComeFromTheGuideTracingAndAreMirrored() {
        assertTrue(MAP.walls.size() > 50, "the traced polygons, both halves: " + MAP.walls.size());
        // every wall cell has its mirror (at the centers of the path's cells, whose mirrors are centers too)
        for (double x = 150; x < MAP.size; x += 200) {
            for (double y = 150; y < MAP.size; y += 200) {
                assertEquals(MAP.solid(x, y), MAP.solid(MAP.size - x, MAP.size - y), x + "," + y);
            }
        }
    }

    @Test
    void mirroredTowersSwapTheBaronAndDragonLanes() {
        for (RiftMap.Spot t : MAP.towers) {
            if (t.team == 1 && "barao".equals(t.lane)) {
                assertTrue(t.y > 10000, "the red Baron lane runs along the top: " + t.x + "," + t.y);
            }
            if (t.team == 1 && "dragao".equals(t.lane)) {
                assertTrue(t.x > 10000, "the red Dragon lane runs along the right: " + t.x + "," + t.y);
            }
        }
    }

    @Test
    void campsAppearWhenTheFirstMiddleWavesMeetAndTheBuffsASecondLater() {
        assertEquals(25, RULES.nextWave(0), 1e-9);
        assertEquals(50, RULES.nextWave(25), 1e-9);
        assertEquals(25, RULES.waveGap(300), 1e-9);
        double[][] mid = MAP.lanes.get("meio");
        double length = 0;
        for (int i = 0; i + 1 < mid.length && mid[i][0] < MAP.size / 2; i++) {
            length += Math.hypot(mid[i + 1][0] - mid[i][0], mid[i + 1][1] - mid[i][1]);
        }
        double meet = RULES.nextWave(0) + length / RULES.num(325, "tropas", "velocidade");
        assertEquals(RULES.num(0, "selva", "primeira_aparicao"), meet, 0.5, "the first camps come at the clash: " + meet);
        assertEquals(RULES.campAppears("Lobos") + 1, RULES.campAppears("Sentinela Azul"), 1e-9);
        assertEquals(RULES.campAppears("Lobos") + 1, RULES.campAppears("Espinhento Vermelho"), 1e-9);
        assertEquals(90, RULES.num(0, "selva", "aronguejo", "aparece"), 1e-9);
    }

    private static Map<String, Object> element(String e) {
        return RULES.map("objetivos", "dragao", "elementos", e);
    }

    @Test
    void aMountainDrakeGivesFourAndThreePercentOfTheTotalAfterTheFixedPart() {
        // 100 of Armor before: +4, then 3% of 104 (the % reads the total after every other source, the drake's 4 too)
        Map<String, Double> one = RiftRules.dragonDelta(element("montanha"), 1, s -> 100);
        assertEquals(4 + 0.03 * 104, one.get("Armor"), 1e-9);
        assertEquals(4 + 0.03 * 104, one.get("Magic Resistance"), 1e-9);
        // two drakes: +8 and 6% of 108
        Map<String, Double> two = RiftRules.dragonDelta(element("montanha"), 2, s -> 100);
        assertEquals(8 + 0.06 * 108, two.get("Armor"), 1e-9);
        // Infernal: 4% of the total per drake
        assertEquals(0.08 * 200, RiftRules.dragonDelta(element("infernal"), 2, s -> 200).get("Attack Damage"), 1e-9);
        assertTrue(RiftRules.dragonDelta(element("oceano"), 1, s -> 100).isEmpty(), "the Ocean heals, it gives no stat");
    }

    @Test
    void onlyThreeElementsAndTheOrderIsDrawnBySeed() {
        assertEquals(new HashSet<>(List.of("oceano", "montanha", "infernal")), new HashSet<>(RULES.map("objetivos", "dragao", "elementos").keySet()));
        assertEquals(RULES.dragonOrder(7), RULES.dragonOrder(7));
        assertEquals(new HashSet<>(List.of("oceano", "montanha", "infernal")), new HashSet<>(RULES.dragonOrder(7)));
        boolean differs = false;
        for (long seed = 1; seed < 10; seed++) {
            differs |= !RULES.dragonOrder(seed).equals(RULES.dragonOrder(0));
        }
        assertTrue(differs, "the order changes between matches");
        assertEquals("do Oceano", Match.elementName("oceano"));
    }

    @Test
    void theSoulNumbersOfThePrintsFitTheFormulas() {
        // the prints show 518 of heal and 228 of damage at that moment; with 1880 of bonus Health and 320 of AP (no
        // bonus AD) both read: 250 + 25% of 320 + 10% of 1880 = 518, and 180 + 15% of 320 = 228
        Map<String, Object> ocean = RULES.map("objetivos", "dragao", "almas", "oceano", "cura");
        Map<String, Object> inferno = RULES.map("objetivos", "dragao", "almas", "infernal", "dano");
        double heal = RiftRules.value(ocean.get("base"), 0) + RiftRules.value(ocean.get("pdh"), 0) * 320 + RiftRules.value(ocean.get("vida_bonus"), 0) * 1880;
        double blast = RiftRules.value(inferno.get("base"), 0) + RiftRules.value(inferno.get("pdh"), 0) * 320;
        assertEquals(518, heal, 1e-9);
        assertEquals(228, blast, 1e-9);
    }

    @Test
    void oceanDrakesHealTheMissingHealthAndTheInfernalSoulBlasts() {
        Arena.Input a = ArenaScenarios.champion("Darius", 0, 3000, 0, 3000, 150, 80);
        Arena.Input b = ArenaScenarios.champion("Ornn", 1, 3100, 0, 3000, 150, 80);
        ArenaModel.ObjectiveBuffs ocean = new ArenaModel.ObjectiveBuffs();
        ocean.dragons.put("oceano", 2);
        Result with = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withObjectives(RULES, new ArenaModel.ObjectiveBuffs[] {ocean, null})
                .run(new ArrayList<>(List.of(a, b)), List.of(), 12);
        Result without = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withObjectives(RULES, null)
                .run(new ArrayList<>(List.of(a, b)), List.of(), 12);
        assertTrue(with.fighters.get(0).healed > without.fighters.get(0).healed, with.fighters.get(0).healed + " > " + without.fighters.get(0).healed);

        ArenaModel.ObjectiveBuffs soul = new ArenaModel.ObjectiveBuffs();
        soul.soul = "infernal";
        Result blast = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withObjectives(RULES, new ArenaModel.ObjectiveBuffs[] {soul, null})
                .run(new ArrayList<>(List.of(a, b)), List.of(), 12);
        assertTrue(blast.fighters.get(0).damageBySource.getOrDefault("Alma Infernal", 0.0) > 0, blast.fighters.get(0).damageBySource.toString());
        assertNotEquals(without.fighters.get(0).dealt, blast.fighters.get(0).dealt);
    }
}
