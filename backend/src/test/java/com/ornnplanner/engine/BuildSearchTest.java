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

    // ------------------------------------------------------------------ steps (Rodada 9, parte 0)

    /**
     * A shop by heart: items 1..6, tier 2 boots 20 and their upgrade 21 (from 10:00), components 30 (of item 1) and 31
     * (of item 2). Gold comes at 1 per minute; each step costs its price and an item consumes its component's price.
     */
    private static final Map<Long, Double> COST = Map.of(1L, 3.0, 2L, 3.0, 3L, 3.0, 4L, 3.0, 5L, 3.0, 6L, 3.0,
            20L, 1.0, 21L, 1.0, 30L, 1.0, 31L, 1.0);
    private static final Map<Long, Double> WORTH = Map.of(1L, 10.0, 2L, 9.0, 3L, 8.0, 4L, 7.0, 5L, 6.0, 6L, 1.0,
            20L, 3.0, 21L, 5.0, 30L, 6.0, 31L, 0.5);

    private static BuildSearch.Space shop(boolean aggressive, int length) {
        BuildSearch.Space sp = new BuildSearch.Space();
        for (long i = 1; i <= 6; i++) {
            sp.add(i, BuildSearch.Kind.ITEM);
        }
        sp.add(20, BuildSearch.Kind.BOOTS).add(21, BuildSearch.Kind.BOOTS3).add(30, BuildSearch.Kind.COMPONENT)
                .add(31, BuildSearch.Kind.COMPONENT);
        sp.parent(20, 21).parent(30, 1).parent(31, 2);
        sp.length = length;
        sp.aggressive = aggressive;
        return sp;
    }

    private static final class Shop implements BuildSearch.Oracle {
        final BuildSearch.Space sp;

        Shop(BuildSearch.Space sp) {
            this.sp = sp;
        }

        @Override
        public boolean allowed(List<Long> order) {
            return true;
        }

        @Override
        public double readyAt(List<Long> order, int index) {
            double gold = 0;
            double t = 0;
            for (int i = 0; i <= index; i++) {
                long id = order.get(i);
                double price = COST.get(id);
                for (int k = 0; k < i; k++) {
                    if (sp.parents.getOrDefault(order.get(k), java.util.Set.of()).contains(id)) {
                        price -= COST.get(order.get(k));
                    }
                }
                gold += Math.max(0, price);
                t = Math.max(t, gold);
                if (id == 21L) {
                    t = Math.max(t, 10);
                }
            }
            return t;
        }

        @Override
        public double score(List<Long> order, double minute) {
            double s = 0;
            for (int i = 0; i < order.size(); i++) {
                if (readyAt(order, i) > minute) {
                    break;
                }
                boolean consumed = false;
                for (int k = i + 1; k < order.size(); k++) {
                    consumed |= readyAt(order, k) <= minute && sp.parents.getOrDefault(order.get(i), java.util.Set.of()).contains(order.get(k));
                }
                if (!consumed && !(order.get(i) == 20L && order.contains(21L) && readyAt(order, order.indexOf(21L)) <= minute)) {
                    s += WORTH.get(order.get(i));
                }
            }
            return s;
        }

        @Override
        public boolean stopped() {
            return false;
        }
    }

    private static final double[] EARLY = {1, 2, 3, 4, 8, 12, 16, 20};

    @Test
    void oneSlotIsAlwaysBoots() {
        BuildSearch.Space sp = shop(false, 3);
        List<BuildSearch.Ranked> found = BuildSearch.steps(new Shop(sp), sp, 4, EARLY);
        assertFalse(found.isEmpty());
        for (BuildSearch.Ranked r : found) {
            assertTrue(r.order.contains(20L) || r.order.contains(21L), r.order.toString());
        }
        sp.noBoots = true;
        for (BuildSearch.Ranked r : BuildSearch.steps(new Shop(sp), sp, 4, EARLY)) {
            assertFalse(r.order.contains(20L) || r.order.contains(21L), "no boots asked: " + r.order);
        }
    }

    @Test
    void theModerateSearchOnlyUsesIsolatedComponents() {
        BuildSearch.Space sp = shop(false, 3);
        for (BuildSearch.Ranked r : BuildSearch.steps(new Shop(sp), sp, 4, EARLY)) {
            long components = r.order.stream().filter(i -> sp.kind(i) == BuildSearch.Kind.COMPONENT).count();
            assertTrue(components <= 1, r.order.toString());
            assertEquals(3, r.order.stream().filter(i -> sp.kind(i) == BuildSearch.Kind.ITEM).count(), r.order.toString());
        }
    }

    @Test
    void theStrongComponentIsBoughtEarlyAndClosedLater() {
        // component 30 is worth more than half its item early: with item 1 in the build, it is bought first and closed later
        BuildSearch.Space sp = shop(false, 2);
        sp.required = List.of(1L);
        Shop o = new Shop(sp);
        BuildSearch.Ranked best = BuildSearch.steps(o, sp, 6, EARLY).get(0);
        int c = best.order.indexOf(30L);
        assertTrue(c >= 0, best.order.toString());
        assertTrue(best.order.indexOf(1L) > c, "item 1 closes the component later: " + best.order);
    }

    @Test
    void theAggressiveSearchMayLeaveComponentsOpen() {
        BuildSearch.Space sp = shop(true, 2);
        List<BuildSearch.Ranked> found = BuildSearch.steps(new Shop(sp), sp, 6, EARLY);
        boolean open = found.stream().anyMatch(r -> r.order.contains(30L) && !r.order.contains(1L));
        assertTrue(open, "some build keeps a component unclosed");
        for (BuildSearch.Ranked r : found) {
            assertTrue(BuildSearch.complete(sp, r.order), r.order.toString());
        }
    }

    @Test
    void whichComponentFirstIsDecidedAfterTheSearch() {
        BuildSearch.Space sp = shop(false, 2);
        Shop o = new Shop(sp);
        BuildSearch.Ranked plain = new BuildSearch.Ranked(new ArrayList<>(List.of(1L, 2L, 20L)), 0, null);
        plain.score = java.util.Arrays.stream(EARLY).map(m -> o.score(plain.order, m)).sum() / EARLY.length;
        List<Long> improved = new ArrayList<>();
        List<BuildSearch.ComponentFirst> rows = BuildSearch.componentFirst(o, sp, plain, EARLY, improved);
        assertEquals(1, rows.stream().filter(r -> r.item == 1L && r.component == 30L).count(), "component 30 before item 1");
        assertEquals(List.of(30L, 1L), improved.subList(0, 2));
        assertTrue(improved.contains(20L));
        assertTrue(rows.get(0).gain > 0);
    }

    @Test
    void theStepSearchIsDeterministic() {
        BuildSearch.Space sp = shop(true, 3);
        List<BuildSearch.Ranked> a = BuildSearch.steps(new Shop(sp), sp, 4, EARLY);
        List<BuildSearch.Ranked> b = BuildSearch.steps(new Shop(sp), sp, 4, EARLY);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).order, b.get(i).order);
        }
    }
}
