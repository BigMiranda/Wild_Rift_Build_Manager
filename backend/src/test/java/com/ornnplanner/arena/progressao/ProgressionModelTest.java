package com.ornnplanner.arena.progressao;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rodada 19: the chain of the natural progression on a small synthetic shop. */
class ProgressionModelTest {

    private static final long COMP = 900;

    private static ProgressionModel.Item item(long id, int cost, boolean boots, boolean upgraded) {
        ProgressionModel.Item it = new ProgressionModel.Item();
        it.id = id;
        it.name = "item " + id;
        it.cost = cost;
        it.boots = boots;
        it.upgradedBoots = upgraded;
        it.group = it.name;
        return it;
    }

    private static Map<Long, ProgressionModel.Item> shop() {
        Map<Long, ProgressionModel.Item> m = new LinkedHashMap<>();
        for (long id = 1; id <= 10; id++) {
            m.put(id, item(id, 3000, false, false));
        }
        m.get(7L).components.put(COMP, 1);
        m.get(8L).support = true;
        m.get(9L).stats.put("Magic Resistance", 60.0);
        m.get(10L).exclusive.add("grupo");
        m.get(6L).exclusive.add("grupo");
        m.put(20L, item(20, 1100, true, false));
        m.put(21L, item(21, 2000, true, true));
        return m;
    }

    private static ProgressionModel.Trajectory traj(String champ, long... ids) {
        ProgressionModel.Trajectory t = new ProgressionModel.Trajectory();
        t.champion = champ;
        t.source = "descoberta";
        for (long id : ids) {
            t.items.add(id);
        }
        return t;
    }

    private static ProgressionModel model() {
        List<ProgressionModel.Trajectory> known = new ArrayList<>();
        known.add(traj("A", 1, 21, 2, 3, 4, 5));
        known.add(traj("A", 1, 21, 2, 3, 4, 6));
        known.add(traj("A", 2, 1, 21, 3, 5, 4));
        known.add(traj("B", 9, 20, 3, 4, 5, 6));
        ProgressionModel.Rule mr = new ProgressionModel.Rule();
        mr.magicMin = 0.6;
        mr.stats.put("Magic Resistance", 3.0);
        ProgressionModel.Settings s = new ProgressionModel.Settings();
        s.composition = List.of(mr);
        // the component's cost (the shop has none: the model reads it from its items)
        Map<Long, ProgressionModel.Item> shop = shop();
        shop.put(COMP, item(COMP, 900, false, false));
        shop.get(COMP).name = "componente";
        return new ProgressionModel(shop, known, s);
    }

    private static ProgressionModel.Context ctx(String champ, long... held) {
        ProgressionModel.Context c = new ProgressionModel.Context();
        c.champion = champ;
        for (long id : held) {
            c.held.add(id);
        }
        return c;
    }

    @Test
    void theNextItemsSumToOneAndFollowTheKnownBuilds() {
        ProgressionModel m = model();
        Map<Long, Double> nx = m.next(ctx("A"));
        assertEquals(1.0, nx.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
        assertEquals(1L, nx.keySet().iterator().next(), "two of three builds of A start with item 1");
        Map<Long, Double> after = m.next(ctx("A", 1));
        assertEquals(1.0, after.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
        assertEquals(21L, after.keySet().iterator().next());
    }

    @Test
    void theShopRulesHold() {
        ProgressionModel m = model();
        Map<Long, Double> nx = m.next(ctx("A", 1, 21, 6));
        assertFalse(nx.containsKey(20L), "one boots");
        assertFalse(nx.containsKey(10L), "an exclusive group held");
        assertFalse(nx.containsKey(8L), "a support item only in support");
        ProgressionModel.Context full = ctx("A", 1, 2, 3, 4, 5, 21);
        assertTrue(m.next(full).isEmpty(), "five items and boots: complete");
        assertTrue(m.complete(full));
    }

    @Test
    void aComponentRaisesTheItemThatUsesIt() {
        ProgressionModel m = model();
        double before = m.next(ctx("A", 1, 21)).get(7L);
        ProgressionModel.Context c = ctx("A", 1, 21);
        c.components.add(COMP);
        double after = m.next(c).get(7L);
        assertTrue(after > before * 1.2, before + " -> " + after);
        // bought, the component is spent
        m.buy(c, 7L);
        assertTrue(c.components.isEmpty());
    }

    @Test
    void theCompositionLeansTheChain() {
        ProgressionModel m = model();
        double before = m.next(ctx("A", 1)).get(9L);
        ProgressionModel.Context c = ctx("A", 1);
        c.enemies = new ProgressionModel.Composition();
        c.enemies.enemies = 1;
        c.enemies.magic = 1;
        assertTrue(m.next(c).get(9L) > before * 2);
    }

    @Test
    void upgradedBootsNeverComeBeforeTheirMinute() {
        ProgressionModel m = model();
        // cheap first: 500 + 700 x t covers 3000 + 2000 at 6.4 min, but the boots wait for 10:00
        List<Double> t = m.minutes(List.of(1L, 21L, 2L), 700);
        assertEquals((3000 - 500) / 700.0, t.get(0), 1e-9);
        assertEquals(10.0, t.get(1), 1e-9);
        assertTrue(t.get(2) >= 10.0);
        for (ProgressionModel.Continuation c : m.continuations(ctx("A"), 5)) {
            for (Map.Entry<String, List<Double>> e : c.minutes.entrySet()) {
                for (int i = 0; i < c.items.size(); i++) {
                    if (c.items.get(i) == 21L) {
                        assertTrue(e.getValue().get(i) >= 10.0 - 1e-9, e.getKey());
                    }
                }
            }
        }
        for (ProgressionModel.Sample s : m.sample(ctx("A"), 8, 40, 3)) {
            assertFalse(s.items.contains(21L), "at minute 8 no sample has the upgraded boots");
        }
    }

    @Test
    void continuationsAndSamplesAreDeterministic() {
        ProgressionModel m = model();
        List<ProgressionModel.Continuation> c = m.continuations(ctx("A"), 3);
        assertEquals(3, c.size());
        assertTrue(c.get(0).probability >= c.get(1).probability);
        assertEquals(6, c.get(0).items.size());
        List<ProgressionModel.Sample> a = m.sample(ctx("A"), 15, 20, 7);
        List<ProgressionModel.Sample> b = m.sample(ctx("A"), 15, 20, 7);
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).items, b.get(i).items);
        }
    }

    @Test
    void leaveOneOutCountsTheRealNextItem() {
        ProgressionModel m = model();
        ProgressionModel.Check ch = m.leaveOneOut(List.of("A"), t -> ctx(t.champion));
        assertEquals(18, ch.cases);
        assertTrue(ch.top3 >= ch.top1);
        assertTrue(ch.top3 > 0);
    }
}
