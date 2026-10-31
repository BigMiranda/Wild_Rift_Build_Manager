package com.ornnplanner.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.rift.Match;
import com.ornnplanner.arena.rift.MatchModel;
import com.ornnplanner.arena.rift.RiftMap;
import com.ornnplanner.arena.rift.RiftRules;
import com.ornnplanner.arena.rift.ScriptedMacro;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole match on a simple world: fixed stats, a shop of 3 steps, fights that always end without a death. */
class MatchTest {

    private static final RiftMap MAP = RiftTest.MAP;
    private static final RiftRules RULES = RiftTest.RULES;
    private static final Map<String, Object> MACRO;

    static {
        try (InputStream in = new ClassPathResource("seed/macro_7_3d.yml").getInputStream()) {
            MACRO = new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A world where nobody dies, with a shop of three steps; the second is held until 10:00. */
    private static final class Simple implements Match.World {
        int fights;
        int heldFrom = 1;

        @Override
        public double[] stats(int seat, int steps, int level, double minute) {
            return new double[] {1000 + 80 * level, 80 + 40 * steps, 0.8, 0, 350};
        }

        @Override
        public int steps(int seat) {
            return 3;
        }

        @Override
        public double cost(int seat, int steps) {
            return steps * 1000;
        }

        @Override
        public boolean sold(int seat, int index, double minute) {
            return index != heldFrom || minute >= 10;
        }

        @Override
        public int levelFor(double xp) {
            return (int) Math.min(15, 1 + xp / 900);
        }

        @Override
        public Match.FightOutcome fight(List<Match.Seat> who, double x, double y, double minute, List<int[]> fallen, Match match) {
            fights++;
            Match.FightOutcome o = new Match.FightOutcome();
            o.seconds = 5;
            for (Match.Seat s : who) {
                o.after.put(s.index, new double[] {Math.max(0.5, s.hp - 0.1), s.x, s.y, 0, 0, 0, 0});
            }
            return o;
        }
    }

    private static Match match(Simple w, List<String> roles, List<Integer> teams) {
        return new Match(MAP, RULES, MACRO, w, new ScriptedMacro(MACRO), 7, roles, teams);
    }

    private static Match five(Simple w) {
        return match(w, List.of("barao", "selva", "meio", "dupla", "suporte", "barao", "selva", "meio", "dupla", "suporte"),
                List.of(0, 0, 0, 0, 0, 1, 1, 1, 1, 1));
    }

    @Test
    void wavesLeaveOnTime() {
        Match m = five(new Simple());
        MatchModel.Result r = m.run(29);
        assertTrue(r.timeline.get(r.timeline.size() - 1).waves.isEmpty(), "no wave before 0:30");
        MatchModel.Result after = five(new Simple()).run(31);
        assertEquals(6, after.timeline.get(after.timeline.size() - 1).waves.size(), "one per lane, per side");
    }

    @Test
    void aMinionsLastHitPaysItsGold() {
        // alone on the lane, with no enemy champion: only its gold comes in
        Simple w = new Simple();
        Match m = match(w, List.of("meio"), List.of(0));
        m.run(100);
        Match.Seat s = m.seats().get(0);
        double passive = 500 + 3 * Math.floor(100 - 19);
        assertTrue(s.gold > passive, "gold " + s.gold + " over the passive " + passive);
        assertEquals(s.gold - passive - s.minions * 50, 0, s.minions * 10 + 1e-6, "melee 60, caster 40: " + s.minions + " minions");
    }

    @Test
    void aChampionOnLaneEarnsTheExpectedGoldAndExperienceByTen() {
        Simple w = new Simple();
        Match m = match(w, List.of("meio"), List.of(0));
        m.run(600);
        Match.Seat s = m.seats().get(0);
        // waves from 0:30 to 10:00: about 21 each, of which it sees the enemy half of every clash
        double passive = 3 * (600 - 20);
        double wave = 3 * 60 + 3 * 40;
        double perWave = wave * 0.8;
        double earned = s.gold - 500 - passive;
        assertTrue(earned > perWave * 12 && earned < perWave * 26, "lane gold " + earned + " by 10:00 at " + perWave + " per wave");
        double waveXp = 3 * 60 + 3 * 30;
        assertTrue(s.xp > waveXp * 12 && s.xp < waveXp * 28, "xp " + s.xp);
    }

    @Test
    void itBuysInTheBuildsOrderAndTheShopRuleHoldsTheSecondStep() {
        Simple w = new Simple();
        Match m = match(w, List.of("meio"), List.of(0));
        m.run(780);
        Match.Seat s = m.seats().get(0);
        assertTrue(s.purchases.size() >= 2, "bought " + s.purchases.size());
        assertEquals(1, s.purchases.get(0)[1], 1e-9);
        assertTrue(s.purchases.get(1)[0] >= 600, "the second step waits for 10:00: " + s.purchases.get(1)[0]);
    }

    @Test
    void theJunglerClearsItsCampsAndGoesAfterTheDrake() {
        Simple w = new Simple();
        Match m = five(w);
        MatchModel.Result r = m.run(600);
        assertTrue(m.seats().get(1).monsters >= 3, "camps: " + m.seats().get(1).monsters);
        assertTrue(w.fights > 0, "the lanes meet");
        assertTrue(r.events.stream().anyMatch(e -> e.text.contains("Dragão")), "drake");
    }

    @Test
    void aMatchCanEndWithANexus() {
        Simple w = new Simple();
        Match m = match(w, List.of("meio"), List.of(0));
        MatchModel.Result r = m.run(40 * 60);
        assertEquals(0, r.winner, "the side with a champion wins alone; ended at " + r.seconds);
    }

    @Test
    void theMatchIsDeterministic() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String a = json.writeValueAsString(five(new Simple()).run(900).timeline);
        String b = json.writeValueAsString(five(new Simple()).run(900).timeline);
        assertEquals(a, b);
    }
}
