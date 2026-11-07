package com.ornnplanner.arena.discovery;

import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.seed.RuneCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;

/**
 * The page of runes, coordinate by coordinate (Rodada 9, part 4): the keystone, then the primary tree, each of its rows
 * and the secondary rune, each tried with the others fixed, repeated until nothing improves. The page's structure is the
 * catalog's (1 keystone, 1 rune of each row of the primary tree, 1 rune of another tree); no rune is named in code.
 */
public final class RuneSearch {

    /** The catalog's shape: keystones, and tree -> row -> runes, in the catalog's order. */
    public static final class Shape {
        public final List<String> keystones = new ArrayList<>();
        public final Map<String, Map<Integer, List<String>>> trees = new LinkedHashMap<>();

        public static Shape of(RuneCatalog.CatalogDto c) {
            Shape s = new Shape();
            c.keystones.forEach(k -> s.keystones.add(k.name));
            c.trees.forEach(t -> s.trees.put(t.name, new TreeMap<>()));
            for (RuneCatalog.Rune r : c.runes) {
                s.trees.computeIfAbsent(r.tree, x -> new TreeMap<>()).computeIfAbsent(r.row, x -> new ArrayList<>()).add(r.name);
            }
            return s;
        }

        /** Every rune of the trees other than {@code primary}, in order. */
        List<String> others(String primary) {
            List<String> out = new ArrayList<>();
            trees.forEach((t, rows) -> {
                if (!t.equals(primary)) {
                    rows.values().forEach(out::addAll);
                }
            });
            return out;
        }

        String treeOf(String rune) {
            for (Map.Entry<String, Map<Integer, List<String>>> t : trees.entrySet()) {
                for (List<String> row : t.getValue().values()) {
                    if (row.contains(rune)) {
                        return t.getKey();
                    }
                }
            }
            return null;
        }
    }

    /** What the search did: the page, its score, and how many pages it measured. */
    public static final class Outcome {
        public BuildRepository.RunePage page;
        public double score;
        public double start;
        public int measured;
        public int passes;
        public List<String> changes = new ArrayList<>();
    }

    private RuneSearch() {
    }

    /** A valid page from any page: the primary tree's rows filled, the secondary from another tree. */
    public static BuildRepository.RunePage complete(BuildRepository.RunePage p0, Shape s) {
        BuildRepository.RunePage p = copy(p0);
        if (p.keystone == null && !s.keystones.isEmpty()) {
            p.keystone = s.keystones.get(0);
        }
        if (p.primary == null || !s.trees.containsKey(p.primary)) {
            p.primary = s.trees.keySet().iterator().next();
        }
        Map<Integer, List<String>> rows = s.trees.get(p.primary);
        List<String> chosen = new ArrayList<>();
        int i = 0;
        for (Map.Entry<Integer, List<String>> row : rows.entrySet()) {
            String have = i < p.primaryRunes.size() ? p.primaryRunes.get(i) : null;
            chosen.add(have != null && row.getValue().contains(have) ? have : row.getValue().get(0));
            i++;
        }
        p.primaryRunes = chosen;
        List<String> others = s.others(p.primary);
        if (p.secondaryRune == null || !others.contains(p.secondaryRune)) {
            p.secondaryRune = others.isEmpty() ? null : others.get(0);
        }
        p.secondary = p.secondaryRune == null ? null : s.treeOf(p.secondaryRune);
        return p;
    }

    /** Coordinate by coordinate until no change improves the score (at most {@code maxPasses} passes). */
    public static Outcome search(BuildRepository.RunePage start, Shape s, ToDoubleFunction<BuildRepository.RunePage> score,
                                 int maxPasses, java.util.function.BooleanSupplier stopped) {
        Outcome o = new Outcome();
        Map<String, Double> cache = new LinkedHashMap<>();
        BuildRepository.RunePage best = complete(start, s);
        double bestScore = measure(best, score, cache, o);
        o.start = bestScore;
        boolean improved = true;
        while (improved && o.passes < maxPasses && !stopped.getAsBoolean()) {
            improved = false;
            o.passes++;
            // the keystone, then the primary tree (its rows reset to the first of each, the secondary moved if it
            // clashes), each page made from the best so far
            for (String k : s.keystones) {
                BuildRepository.RunePage p = copy(best);
                p.keystone = k;
                double v = measure(p, score, cache, o);
                if (v > bestScore + 1e-9) {
                    o.changes.add(describe(best, p));
                    best = p;
                    bestScore = v;
                    improved = true;
                }
            }
            for (String t : new ArrayList<>(s.trees.keySet())) {
                if (stopped.getAsBoolean() || t.equals(best.primary)) {
                    continue;
                }
                BuildRepository.RunePage p = copy(best);
                p.primary = t;
                p.primaryRunes = new ArrayList<>();
                p = complete(p, s);
                double v = measure(p, score, cache, o);
                if (v > bestScore + 1e-9) {
                    o.changes.add(describe(best, p));
                    best = p;
                    bestScore = v;
                    improved = true;
                }
            }
            // each row of the primary tree, then the secondary rune, with the tree the coordinate above left
            int rowIndex = 0;
            for (Map.Entry<Integer, List<String>> row : s.trees.get(best.primary).entrySet()) {
                for (String rune : row.getValue()) {
                    BuildRepository.RunePage p = copy(best);
                    p.primaryRunes.set(rowIndex, rune);
                    double v = measure(p, score, cache, o);
                    if (v > bestScore + 1e-9) {
                        o.changes.add(describe(best, p));
                        best = p;
                        bestScore = v;
                        improved = true;
                    }
                }
                rowIndex++;
            }
            for (String rune : s.others(best.primary)) {
                BuildRepository.RunePage p = copy(best);
                p.secondaryRune = rune;
                p.secondary = s.treeOf(rune);
                double v = measure(p, score, cache, o);
                if (v > bestScore + 1e-9) {
                    o.changes.add(describe(best, p));
                    best = p;
                    bestScore = v;
                    improved = true;
                }
            }
        }
        o.page = best;
        o.score = bestScore;
        return o;
    }

    private static double measure(BuildRepository.RunePage p, ToDoubleFunction<BuildRepository.RunePage> score,
                                  Map<String, Double> cache, Outcome o) {
        String key = p.keystone + "|" + p.primary + "|" + p.primaryRunes + "|" + p.secondaryRune;
        return cache.computeIfAbsent(key, k -> {
            o.measured++;
            return score.applyAsDouble(p);
        });
    }

    private static String describe(BuildRepository.RunePage a, BuildRepository.RunePage b) {
        List<String> d = new ArrayList<>();
        if (!java.util.Objects.equals(a.keystone, b.keystone)) {
            d.add(a.keystone + " → " + b.keystone);
        }
        if (!java.util.Objects.equals(a.primary, b.primary)) {
            d.add(a.primary + " → " + b.primary);
        }
        for (int i = 0; i < Math.min(a.primaryRunes.size(), b.primaryRunes.size()); i++) {
            if (!java.util.Objects.equals(a.primaryRunes.get(i), b.primaryRunes.get(i))) {
                d.add(a.primaryRunes.get(i) + " → " + b.primaryRunes.get(i));
            }
        }
        if (!java.util.Objects.equals(a.secondaryRune, b.secondaryRune)) {
            d.add(a.secondaryRune + " → " + b.secondaryRune);
        }
        return String.join(", ", d);
    }

    public static BuildRepository.RunePage copy(BuildRepository.RunePage p) {
        BuildRepository.RunePage c = new BuildRepository.RunePage();
        if (p == null) {
            return c;
        }
        c.primary = p.primary;
        c.secondary = p.secondary;
        c.keystone = p.keystone;
        c.primaryRunes = new ArrayList<>(p.primaryRunes == null ? List.of() : p.primaryRunes);
        c.secondaryRune = p.secondaryRune;
        c.options = new LinkedHashMap<>(p.options == null ? Map.of() : p.options);
        c.conditional = new LinkedHashMap<>(p.conditional == null ? Map.of() : p.conditional);
        c.rates = new LinkedHashMap<>(p.rates == null ? Map.of() : p.rates);
        return c;
    }

    // ------------------------------------------------------------------ versatility (part 4b)

    /**
     * Versatility of a choice made without knowing what the opponent will do: its points over the variations (the
     * scenarios). versatile = half the worst case + half the 20th percentile; the mixed score = {@code meanWeight} × mean +
     * the rest × versatile. Runes weigh versatility more (40% mean) than items (80%): they are chosen in the draft.
     */
    public static double[] versatility(List<Double> points, double meanWeight) {
        if (points.isEmpty()) {
            return new double[] {0, 0, 0, 0};
        }
        double mean = points.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double worst = points.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double p20 = com.ornnplanner.arena.calc.BuildScore.percentile(points, 0.2);
        double versatile = 0.5 * worst + 0.5 * p20;
        return new double[] {meanWeight * mean + (1 - meanWeight) * versatile, mean, worst, versatile};
    }
}
