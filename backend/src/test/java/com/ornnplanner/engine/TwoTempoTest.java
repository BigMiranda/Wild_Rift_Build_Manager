package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ArenaModel.RiftOptions;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.arena.ai.TacticalPolicy;
import com.ornnplanner.arena.rift.RiftMap;
import com.ornnplanner.arena.rift.RiftTemplates;
import com.ornnplanner.arena.rift.TwoTempo;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rodada 10, Parte 2: playing in two tempos, in the match's macro and in the arena with the map. */
class TwoTempoTest {

    @SuppressWarnings("unchecked")
    static Map<String, Object> weights() throws Exception {
        try (InputStream in = new ClassPathResource("seed/macro_7_3d.yml").getInputStream()) {
            Map<String, Object> m = new Yaml().load(in);
            return (Map<String, Object>) m.get("dois_tempos");
        }
    }

    /** A laner after a trade: a Honeyfruit 1.200 away, the base far, no Teleport, nothing to buy. */
    private static TwoTempo.Situation hurt(double hp, double danger) {
        TwoTempo.Situation s = new TwoTempo.Situation();
        s.hp = hp;
        s.maxHp = 2000;
        s.dangerPerS = danger;
        s.regenPerS = 0.004;
        s.laneGoldPerS = 9;
        s.laneXpPerS = 11;
        s.plates = 5;
        s.platesPerS = danger > 0 ? 0.03 : 0;
        s.deathCost = 900;
        s.fruitDistance = 1200;
        s.fruitHeal = 0.3;
        s.baseDistance = 7000;
        s.moveSpeed = 350;
        return s;
    }

    @Test
    void whenTheHealthRunsOutBeforeTheWaveItStepsOutToTheHoneyfruit() throws Exception {
        TwoTempo tempo = new TwoTempo(weights());
        // the trade takes 2% of the health a second and it has 35%: staying, it dies before the horizon is over
        TwoTempo.Choice out = tempo.choose(hurt(0.35, 0.02));
        assertEquals(TwoTempo.Option.HONEYFRUIT, out.option, out.values.toString());
        assertTrue(out.values.get(TwoTempo.Option.HONEYFRUIT) > out.values.get(TwoTempo.Option.STAY));
        assertTrue(out.hpGain > 0.25 && out.minionsLost > 0, out.hpGain + " " + out.minionsLost);
    }

    @Test
    void whenTheTradeIsSafeItStays() throws Exception {
        TwoTempo tempo = new TwoTempo(weights());
        // the same health, but no enemy pressing: the lane is worth more than the fruit
        TwoTempo.Choice stay = tempo.choose(hurt(0.6, 0));
        assertEquals(TwoTempo.Option.STAY, stay.option, stay.values.toString());
        // nearly dead with no fruit around: home on foot
        TwoTempo.Situation s = hurt(0.15, 0.03);
        s.fruitDistance = -1;
        assertEquals(TwoTempo.Option.RECALL_WALK, tempo.choose(s).option);
        // and by Teleport when it is up
        s.teleportReady = true;
        assertEquals(TwoTempo.Option.RECALL_TELEPORT, tempo.choose(s).option);
    }

    @Test
    void aSheetThatShopsAnywhereBuysWithoutGoingBack() throws Exception {
        TwoTempo tempo = new TwoTempo(weights());
        TwoTempo.Situation s = hurt(0.9, 0);
        s.itemGold = 1100;
        s.itemGain = 1300;
        s.shopsHere = true;
        assertEquals(TwoTempo.Option.SHOP_HERE, tempo.choose(s).option);
    }

    private static final RiftMap MAP = RiftTest.MAP;

    @Test
    void theNewActionsAreOnlyValidOnTheFullMap() {
        // a single lane: the tactical brain with low health retreats, never recovers or goes home
        Arena.Input a = ArenaScenarios.champion("Darius", 0, 3000, 0, 3000, 150, 80);
        Arena.Input b = ArenaScenarios.champion("Ornn", 1, 3100, 0, 9000, 400, 200);
        a.policy = new TacticalPolicy(List.of("1", "2", "3", "R"));
        Result r = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(new ArrayList<>(List.of(a, b)), List.of(), 12);
        assertEquals(0, r.fighters.get(0).events.getOrDefault("volta_base", 0.0));
        assertEquals(0, r.fighters.get(0).events.getOrDefault("recuperar:torre", 0.0) + r.fighters.get(0).events.getOrDefault("recuperar:frutomel", 0.0));
        assertEquals(38, ActionSpace.SIZE);
        assertEquals("recuar para recuperar", ActionSpace.describe(ActionSpace.RECOVER));
    }

    @Test
    void onTheMapAHurtBrainRecoversAndGoesHomeWhenAlone() {
        RiftOptions o = new RiftOptions();
        o.minute = 7;
        o.minions = false;
        o.jungle = false;
        o.objectives = false;
        o.plants = true;
        // hurt and alone near the middle: it goes home (a channel of 8 s), and stands at its fountain
        Arena.Input a = ArenaScenarios.champion("Darius", 0, 5200, 5200, 3000, 150, 80);
        a.startHealth = 0.2;
        a.policy = new TacticalPolicy(List.of("1", "2", "3", "R"));
        Arena.Input far = ArenaScenarios.champion("Ornn", 1, 10500, 10300, 3000, 150, 80);
        far.policy = (obs, m) -> Policy.Decision.of(ActionSpace.IDLE);
        Result r = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withRift(MAP, RiftTest.RULES, o)
                .run(new ArrayList<>(List.of(a, far)), List.of(), 12);
        assertTrue(r.fighters.get(0).events.getOrDefault("volta_base", 0.0) >= 1, r.fighters.get(0).events.toString());
        boolean home = r.frames.stream().map(f -> f.units.get(0))
                .anyMatch(u -> Math.hypot(u.x - MAP.fountainBlue[0], u.y - MAP.fountainBlue[1]) <= MAP.fountainBlue[2]);
        assertTrue(home, "it stood at its fountain");
    }

    @Test
    void theHoneyfruitTemplateIsThere() {
        RiftTemplates.Template t = RiftTemplates.all(MAP).stream().filter(x -> "x1-frutomel".equals(x.key)).findFirst().orElseThrow();
        assertTrue(t.scenario.rift.plants);
        assertEquals(2, t.scenario.fighters.size());
        assertTrue(t.scenario.rift.minute >= 6, "the Honeyfruit is up at 6:00");
    }
}
