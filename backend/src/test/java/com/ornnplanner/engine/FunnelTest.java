package com.ornnplanner.engine;

import com.ornnplanner.arena.discovery.Funnel;
import com.ornnplanner.arena.discovery.Ridge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The discovery's funnel on a world known by heart (Rodada 9, parts 2 and 3): 30 items worth their id's pattern, item 7
 * clearly the best, items 11 and 12 with a planted synergy, items 3 and 4 in one exclusive group, two pairs of boots.
 */
class FunnelTest {

    static final List<Long> ITEMS = new ArrayList<>();
    static final List<Long> BOOTS = List.of(100L, 101L);

    static {
        for (long i = 1; i <= 30; i++) {
            ITEMS.add(i);
        }
    }

    static double worth(long i) {
        return i == 7 ? 40 : 10 + (i * 37 % 11);
    }

    static final class World implements Funnel.Evaluator {
        int quick;
        int full;

        @Override
        public boolean allowed(Funnel.Kit k) {
            return !(k.items.contains(3L) && k.items.contains(4L));
        }

        double value(Funnel.Kit k) {
            double v = 0;
            for (long i : k.items) {
                v += worth(i);
            }
            if (k.items.contains(11L) && k.items.contains(12L)) {
                v += 45;
            }
            v += k.boots == null ? 0 : k.boots == 101L ? 6 : 3;
            return v;
        }

        @Override
        public double quick(Funnel.Kit k) {
            quick++;
            return value(k) + (k.key().hashCode() % 7);   // a noisy, cheap reading
        }

        @Override
        public Funnel.Eval full(Funnel.Kit k) {
            full++;
            Funnel.Eval e = new Funnel.Eval();
            e.score = value(k);
            long low = k.items.stream().filter(i -> i <= 15).count();
            e.niche.add(low >= 3 ? "baixos" : "altos");
            return e;
        }

        @Override
        public Funnel.Eval confirm(Funnel.Kit k) {
            return full(k);
        }

        @Override
        public boolean stopped() {
            return false;
        }
    }

    private static Funnel.Config config(long seed) {
        Funnel.Config c = new Funnel.Config();
        c.seed = seed;
        c.generations = 12;
        c.population = 12;
        c.children = 30;
        c.fullPerGeneration = 8;
        return c;
    }

    @Test
    void theSearchFindsTheBestItemAndThePlantedSynergy() {
        World w = new World();
        Funnel.Result r = Funnel.search(w, ITEMS, BOOTS, List.of(), config(3));
        Funnel.Kit best = r.ranked.get(0).kit;
        assertTrue(best.items.contains(7L), "the clearly best item: " + best);
        assertTrue(best.items.contains(11L) && best.items.contains(12L), "the planted pair: " + best);
        assertEquals(101L, best.boots);
        List<Funnel.Synergy> s = Funnel.synergies(w, r.ranked.subList(0, 1), 10);
        assertEquals(11L, s.get(0).a);
        assertEquals(12L, s.get(0).b);
        assertEquals(45, s.get(0).value, 1e-9);
    }

    @Test
    void theShopRulesAlwaysHold() {
        Funnel.Result r = Funnel.search(new World(), ITEMS, BOOTS, List.of(new Funnel.Kit(List.of(3L, 4L, 5L, 6L, 8L), 100L)), config(5));
        for (Funnel.Entry e : r.ranked) {
            assertFalse(e.kit.items.contains(3L) && e.kit.items.contains(4L), e.kit.toString());
            assertEquals(5, e.kit.items.size());
            assertNotNull(e.kit.boots);
        }
    }

    @Test
    void mapElitesKeepsTheBestOfEachNiche() {
        Funnel.Result r = Funnel.search(new World(), ITEMS, BOOTS, List.of(), config(7));
        assertEquals(2, r.niches.size(), r.niches.keySet().toString());
        for (Map.Entry<String, Funnel.Entry> n : r.niches.entrySet()) {
            for (Funnel.Entry e : r.ranked) {
                if (String.join(" · ", e.full.niche).equals(n.getKey())) {
                    assertTrue(e.score() <= n.getValue().score() + 1e-9, n.getKey());
                }
            }
        }
    }

    @Test
    void theSurrogateDoesNotMakeTheSearchWorse() {
        double with = 0;
        double without = 0;
        for (long seed = 1; seed <= 6; seed++) {
            Funnel.Config a = config(seed);
            Funnel.Config b = config(seed);
            b.surrogate = false;
            with += Funnel.search(new World(), ITEMS, BOOTS, List.of(), a).ranked.get(0).score();
            without += Funnel.search(new World(), ITEMS, BOOTS, List.of(), b).ranked.get(0).score();
        }
        assertTrue(with >= without - 1e-9, "with " + with + ", without " + without);
        Funnel.Result r = Funnel.search(new World(), ITEMS, BOOTS, List.of(), config(2));
        assertNotNull(r.surrogateCorrelation);
        assertTrue(r.surrogateCorrelation > 0.5, "correlation " + r.surrogateCorrelation);
    }

    @Test
    void theShortListKeepsTheQuotaAndTheJokers() {
        Map<Long, Double> value = new LinkedHashMap<>();
        Map<Long, List<String>> groups = new LinkedHashMap<>();
        for (long i : ITEMS) {
            value.put(i, (double) i);
            groups.put(i, List.of(i <= 3 ? "raro" : "comum"));
        }
        List<Long> s = Funnel.shortlist(value, groups, 10, 2, 3, 1);
        assertEquals(13, s.size());
        assertTrue(s.contains(3L) && s.contains(2L), "the quota of the rare group: " + s);
        assertTrue(s.contains(30L));
        assertEquals(s, Funnel.shortlist(value, groups, 10, 2, 3, 1));
    }

    @Test
    void itIsDeterministicByTheSeed() {
        Funnel.Result a = Funnel.search(new World(), ITEMS, BOOTS, List.of(), config(9));
        Funnel.Result b = Funnel.search(new World(), ITEMS, BOOTS, List.of(), config(9));
        assertEquals(a.ranked.size(), b.ranked.size());
        for (int i = 0; i < a.ranked.size(); i++) {
            assertEquals(a.ranked.get(i).kit.key(), b.ranked.get(i).kit.key());
        }
    }

    @Test
    void ridgeLearnsAnAdditiveWorld() {
        List<java.util.Set<Long>> sets = new ArrayList<>();
        List<Double> y = new ArrayList<>();
        java.util.Random r = new java.util.Random(1);
        for (int n = 0; n < 60; n++) {
            java.util.Set<Long> s = new java.util.TreeSet<>();
            while (s.size() < 4) {
                s.add(1L + r.nextInt(10));
            }
            sets.add(s);
            y.add(s.stream().mapToDouble(i -> i * 2.0).sum());
        }
        Ridge m = new Ridge(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), List.of(), 0.01).fit(sets, y);
        assertEquals(2 * (1 + 2 + 3 + 10), m.predict(java.util.Set.of(1L, 2L, 3L, 10L)), 0.5);
    }
}
