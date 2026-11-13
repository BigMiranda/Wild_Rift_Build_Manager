package com.ornnplanner.arena.atlas;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Rodada 16: the atlas's arithmetic, no fights. A set's race is predicted from the measured ones by inclusion-exclusion
 * up to an order: the base, each condition's note (1 dimension), each pair's interaction (2) and each triple's (3).
 * Conditions enter with a weight (a stat by its amount over the level measured, a passive whole): the prediction is
 * multilinear in the weights, so a weight of 1 on measured sets gives back their measured race.
 */
public final class AtlasMath {

    private AtlasMath() {
    }

    /** The key of a set of side keys: sorted, joined by "|" ("" = the base). */
    public static String setKey(Collection<String> keys) {
        List<String> k = new ArrayList<>(keys);
        Collections.sort(k);
        return String.join("|", k);
    }

    /** One condition's note: its race minus the base (null = not measured). */
    public static Double note1(Map<String, Double> races, double base, String x) {
        Double r = races.get(x);
        return r == null ? null : r - base;
    }

    /** A pair's interaction: what the pair does beyond the two notes (null = not measured). */
    public static Double note2(Map<String, Double> races, double base, String x, String y) {
        Double xy = races.get(setKey(List.of(x, y)));
        Double rx = races.get(x);
        Double ry = races.get(y);
        return xy == null || rx == null || ry == null ? null : xy - rx - ry + base;
    }

    /** A triple's interaction: beyond the notes and the pairs' interactions (null = not measured). */
    public static Double note3(Map<String, Double> races, double base, String x, String y, String z) {
        Double xyz = races.get(setKey(List.of(x, y, z)));
        Double xy = races.get(setKey(List.of(x, y)));
        Double xz = races.get(setKey(List.of(x, z)));
        Double yz = races.get(setKey(List.of(y, z)));
        Double rx = races.get(x);
        Double ry = races.get(y);
        Double rz = races.get(z);
        if (xyz == null || xy == null || xz == null || yz == null || rx == null || ry == null || rz == null) {
            return null;
        }
        return xyz - xy - xz - yz + rx + ry + rz - base;
    }

    /** The predicted race of a weighted set (side key -> weight), with interactions up to {@code order}, in 0..100. */
    public static double predict(Map<String, Double> races, double base, Map<String, Double> weights, int order) {
        return Math.max(0, Math.min(100, sum(races, base, weights, factors(order))));
    }

    /** The same with a factor on each order's terms (the shrinkage the validation fitted: the measures are noisy). */
    public static double predict(Map<String, Double> races, double base, Map<String, Double> weights, double[] factors) {
        return Math.max(0, Math.min(100, sum(races, base, weights, factors)));
    }

    public static double predictLogit(Map<String, Double> logits, double logitBase, Map<String, Double> weights, double[] factors) {
        return expit(sum(logits, logitBase, weights, factors));
    }

    /** Whole terms up to an order, none above it. */
    public static double[] factors(int order) {
        return new double[] {1, order >= 2 ? 1 : 0, order >= 3 ? 1 : 0};
    }

    /**
     * The same on the logit scale: the races as log-odds ({@code races} and {@code base} already through {@link #logit}),
     * the notes add there and the sum goes back to 0..100. A bounded race, where whoever starts winning wins, adds badly
     * in points (a few good items pass 100); in odds each note shifts the odds and the gains taper near the ends.
     */
    public static double predictLogit(Map<String, Double> logits, double logitBase, Map<String, Double> weights, int order) {
        return expit(sum(logits, logitBase, weights, factors(order)));
    }

    /** A race (0..100) as log-odds, kept off the ends (0.5..99.5: a race of 100 is "never goes to zero"). */
    public static double logit(double race) {
        double p = Math.max(0.5, Math.min(99.5, race)) / 100;
        return Math.log(p / (1 - p));
    }

    public static double expit(double l) {
        return 100 / (1 + Math.exp(-l));
    }

    /** Every race through {@link #logit}. */
    public static Map<String, Double> logits(Map<String, Double> races) {
        Map<String, Double> out = new java.util.HashMap<>(races.size() * 2);
        races.forEach((k, v) -> out.put(k, logit(v)));
        return out;
    }

    /** The inclusion-exclusion sum, unbounded, each order's terms times its factor (0 = not computed). */
    static double sum(Map<String, Double> races, double base, Map<String, Double> weights, double[] f) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            if (e.getValue() != null && Math.abs(e.getValue()) > 1e-12) {
                keys.add(e.getKey());
            }
        }
        Collections.sort(keys);
        double sum = base;
        for (String x : keys) {
            Double n = note1(races, base, x);
            if (n != null) {
                sum += f[0] * weights.get(x) * n;
            }
        }
        if (f[1] != 0) {
            for (int i = 0; i < keys.size(); i++) {
                for (int j = i + 1; j < keys.size(); j++) {
                    Double n = note2(races, base, keys.get(i), keys.get(j));
                    if (n != null) {
                        sum += f[1] * weights.get(keys.get(i)) * weights.get(keys.get(j)) * n;
                    }
                }
            }
        }
        if (f[2] != 0) {
            for (int i = 0; i < keys.size(); i++) {
                for (int j = i + 1; j < keys.size(); j++) {
                    for (int k = j + 1; k < keys.size(); k++) {
                        Double n = note3(races, base, keys.get(i), keys.get(j), keys.get(k));
                        if (n != null) {
                            sum += f[2] * weights.get(keys.get(i)) * weights.get(keys.get(j)) * weights.get(keys.get(k)) * n;
                        }
                    }
                }
            }
        }
        return sum;
    }

    /** Pearson's correlation (0 when either side is flat). */
    public static double correlation(List<Double> a, List<Double> b) {
        int n = Math.min(a.size(), b.size());
        if (n < 2) {
            return 0;
        }
        double ma = 0;
        double mb = 0;
        for (int i = 0; i < n; i++) {
            ma += a.get(i) / n;
            mb += b.get(i) / n;
        }
        double sab = 0;
        double saa = 0;
        double sbb = 0;
        for (int i = 0; i < n; i++) {
            double da = a.get(i) - ma;
            double db = b.get(i) - mb;
            sab += da * db;
            saa += da * da;
            sbb += db * db;
        }
        return saa <= 1e-12 || sbb <= 1e-12 ? 0 : sab / Math.sqrt(saa * sbb);
    }

    /**
     * A stat's weights on its two measured levels for an amount: below the first level, a share of it; between the two,
     * shared between them; above the second, a share of the second (it extrapolates in a straight line).
     */
    public static double[] statWeights(double amount, double level1, double level2) {
        if (amount <= 0 || level1 <= 0) {
            return new double[] {0, 0};
        }
        if (level2 <= level1 || amount <= level1) {
            return new double[] {amount / level1, 0};
        }
        if (amount <= level2) {
            double t = (amount - level1) / (level2 - level1);
            return new double[] {1 - t, t};
        }
        return new double[] {0, amount / level2};
    }
}
