package com.ornnplanner.engine;

import com.ornnplanner.arena.calc.BuildSearch;
import com.ornnplanner.arena.calc.GameTree;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The search over orders on a measure known by heart: an item is worth its value, the sooner the longer it counts. */
class BuildSearchTest {

    private static final double[] MINUTES = {8, 12, 16, 20};
    /** Each purchase takes 5 minutes; boots (item 9) are held until 10:00, and items 3 and 4 share a group. */
    private static final Map<Long, Double> VALUE = Map.of(1L, 10.0, 2L, 8.0, 3L, 6.0, 4L, 5.0, 7L, 40.0, 9L, 12.0);

    private static final class Known implements BuildSearch.Oracle {
        int measures;

        @Override
        public boolean allowed(List<Long> order) {
            return !(order.contains(3L) && order.contains(4L));
        }

        @Override
        public double readyAt(List<Long> order, int index) {
            double t = 0;
            for (int i = 0; i <= index; i++) {
                t += 5;
                if (order.get(i) == 9L) {
                    t = Math.max(t, 10);
                }
            }
            return t;
        }

        @Override
        public double score(List<Long> order, double minute) {
            measures++;
            double s = 0;
            for (int i = 0; i < order.size(); i++) {
                double ready = readyAt(order, i);
                if (ready <= minute) {
                    double base = VALUE.getOrDefault(order.get(i), 0.0);
                    // boots only pay later, once there is something to run with
                    s += order.get(i) == 9L && minute < 16 ? 0 : base;
                }
            }
            return s;
        }

        @Override
        public boolean stopped() {
            return false;
        }
    }

    private static final List<Long> ALL = List.of(1L, 2L, 3L, 4L, 7L, 9L);

    @Test
    void anItemClearlyBestComesFirst() {
        List<BuildSearch.Ranked> found = BuildSearch.beam(new Known(), ALL, List.of(), 3, 3, MINUTES);
        assertEquals(7L, found.get(0).order.get(0));
        assertEquals(List.of(7L, 1L), found.get(0).order.subList(0, 2));
    }

    @Test
    void theShopRulesHold() {
        for (BuildSearch.Ranked r : BuildSearch.beam(new Known(), ALL, List.of(), 5, 4, MINUTES)) {
            assertFalse(r.order.contains(3L) && r.order.contains(4L), r.order.toString());
        }
    }

    @Test
    void requiredItemsAreAlwaysIn() {
        List<BuildSearch.Ranked> found = BuildSearch.beam(new Known(), ALL, List.of(4L), 3, 3, MINUTES);
        assertTrue(found.get(0).order.contains(4L), found.get(0).order.toString());
    }

    @Test
    void importanceSaysWhatMovingAnItemCosts() {
        BuildSearch.Oracle o = new Known();
        BuildSearch.Ranked best = BuildSearch.beam(o, ALL, List.of(), 3, 3, MINUTES).get(0);
        List<BuildSearch.Importance> rows = BuildSearch.importance(o, best, MINUTES);
        assertEquals(3, rows.size());
        assertNotNull(rows.get(0).delayed);
        assertTrue(rows.get(0).delayed > 0, "delaying the best item costs");
        assertEquals(null, rows.get(0).advanced);
        assertTrue(rows.get(1).advanced < 0, "the second is worse first");
    }

    @Test
    void itSaysFromWhenTheBootsAreWorthIt() {
        BuildSearch.Oracle o = new Known();
        Double from = BuildSearch.worthFrom(o, new ArrayList<>(List.of(7L, 1L, 9L)), 9L, MINUTES, 0.01);
        assertNotNull(from);
        assertTrue(from >= 10, "the shop's rule holds: " + from);
    }

    @Test
    void theSearchIsDeterministic() {
        List<BuildSearch.Ranked> a = BuildSearch.beam(new Known(), ALL, List.of(), 4, 3, MINUTES);
        List<BuildSearch.Ranked> b = BuildSearch.beam(new Known(), ALL, List.of(), 4, 3, MINUTES);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).order, b.get(i).order);
        }
    }

    @Test
    void maximinPicksTheSafeOption() {
        // the first row wins big or loses big; the second never loses much
        double[][] m = {{5, 5, -4}, {1, 1, -0.5}, {-2, 3, -1}};
        GameTree.Solution s = GameTree.solve(m, 2000);
        assertEquals(1, s.maximin);
        assertEquals(-0.5, s.maximinValue, 1e-9);
        assertEquals(2.0 / 3, s.winRate[0], 1e-9);
        assertEquals(2, s.worstAnswer[0]);
    }

    @Test
    void fictitiousPlayFindsAMixedEquilibrium() {
        // rock, paper, scissors: a third of each, value 0
        double[][] m = {{0, -1, 1}, {1, 0, -1}, {-1, 1, 0}};
        GameTree.Solution s = GameTree.solve(m, 30000);
        for (double p : s.mixOurs) {
            assertEquals(1.0 / 3, p, 0.03);
        }
        assertEquals(0, s.value, 0.03);
    }
}
