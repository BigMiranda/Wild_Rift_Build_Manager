package com.ornnplanner.engine;

import com.ornnplanner.arena.calc.BuildSearch;
import com.ornnplanner.arena.discovery.RuneSearch;
import com.ornnplanner.repo.BuildRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runes and the start (Rodada 9, parts 4 and 4b), on synthetic shapes and measures. */
class RuneSearchTest {

    /** Two trees of two rows each, three keystones. */
    private static RuneSearch.Shape shape() {
        RuneSearch.Shape s = new RuneSearch.Shape();
        s.keystones.addAll(List.of("K1", "K2", "K3"));
        Map<Integer, List<String>> a = new TreeMap<>();
        a.put(1, List.of("A11", "A12"));
        a.put(2, List.of("A21", "A22", "A23"));
        Map<Integer, List<String>> b = new TreeMap<>();
        b.put(1, List.of("B11", "B12"));
        b.put(2, List.of("B21", "B22"));
        s.trees.put("A", a);
        s.trees.put("B", b);
        return s;
    }

    @Test
    void theCoordinateSearchFindsASeparableBest() {
        // K2 + tree B (B12, B22) + A23 as the secondary is the best; each part adds on its own
        Map<String, Double> worth = Map.of("K2", 5.0, "B", 3.0, "B12", 2.0, "B22", 2.0, "A23", 1.5, "A12", 0.5);
        BuildRepository.RunePage start = new BuildRepository.RunePage();
        start.keystone = "K1";
        start.primary = "A";
        RuneSearch.Outcome o = RuneSearch.search(start, shape(), p -> {
            double v = worth.getOrDefault(p.keystone, 0.0) + worth.getOrDefault(p.primary, 0.0) + worth.getOrDefault(p.secondaryRune, 0.0);
            for (String r : p.primaryRunes) {
                v += worth.getOrDefault(r, 0.0);
            }
            return v;
        }, 4, () -> false);
        assertEquals("K2", o.page.keystone);
        assertEquals("B", o.page.primary);
        assertEquals(List.of("B12", "B22"), o.page.primaryRunes);
        assertEquals("A23", o.page.secondaryRune);
        assertEquals("A", o.page.secondary);
        assertTrue(o.measured < 3 * 2 * 3 * 2 * 2 * 5, "fewer pages than every page: " + o.measured);
    }

    /** A versatile rune beats an all-or-nothing one when the matrix of variations asks; for items the mean weighs more. */
    @Test
    void aVersatileRuneBeatsAnAllOrNothingOne() {
        List<Double> steady = List.of(60.0, 60.0, 60.0, 60.0, 60.0);
        List<Double> allOrNothing = List.of(110.0, 110.0, 110.0, 30.0, 30.0);   // mean 78
        double runeSteady = RuneSearch.versatility(steady, 0.4)[0];
        double runeGamble = RuneSearch.versatility(allOrNothing, 0.4)[0];
        assertTrue(runeSteady > runeGamble, "runes: " + runeSteady + " vs " + runeGamble);
        double itemSteady = RuneSearch.versatility(steady, 0.8)[0];
        double itemGamble = RuneSearch.versatility(allOrNothing, 0.8)[0];
        assertTrue(itemGamble > itemSteady, "items, where the shop can react: " + itemGamble + " vs " + itemSteady);
    }

    /** A branch whose start loses to the weakest saved start is cut before its later items are measured. */
    @Test
    void aLosingStartIsPruned() {
        List<BuildSearch.Ranked> ranked = new ArrayList<>();
        ranked.add(new BuildSearch.Ranked(List.of(1L), 30, null));
        ranked.add(new BuildSearch.Ranked(List.of(2L), 21, null));
        ranked.add(new BuildSearch.Ranked(List.of(3L), 12, null));
        int[] pruned = new int[1];
        BuildSearch.DepthListener l = BuildSearch.startPruner(new double[] {20, Double.POSITIVE_INFINITY}, 0.05, pruned);
        List<BuildSearch.Ranked> kept = l.depth(0, ranked);
        assertEquals(2, kept.size());
        assertEquals(1, pruned[0]);
        assertEquals(3, l.depth(1, ranked).size(), "no bound at the second step");
    }
}
