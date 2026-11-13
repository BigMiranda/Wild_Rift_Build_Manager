package com.ornnplanner.arena.atlas;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
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
            List<String> tk = inTriples(races, keys);
            for (int i = 0; i < tk.size(); i++) {
                for (int j = i + 1; j < tk.size(); j++) {
                    for (int k = j + 1; k < tk.size(); k++) {
                        Double n = note3(races, base, tk.get(i), tk.get(j), tk.get(k));
                        if (n != null) {
                            sum += f[2] * weights.get(tk.get(i)) * weights.get(tk.get(j)) * weights.get(tk.get(k)) * n;
                        }
                    }
                }
            }
        }
        return sum;
    }

    /**
     * The terms of a weighted set by order, the first split by side: {notes of A, notes of B, pairs, triples}. The sum is
     * the base plus the four (what {@link #sum} gives with whole factors).
     */
    public static double[] terms(Map<String, Double> races, double base, Map<String, Double> weights) {
        List<String> keys = present(weights, null);
        double[] t = new double[4];
        for (String x : keys) {
            Double n = note1(races, base, x);
            if (n != null) {
                t[x.startsWith("A:") ? 0 : 1] += weights.get(x) * n;
            }
        }
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                Double n = note2(races, base, keys.get(i), keys.get(j));
                if (n != null) {
                    t[2] += weights.get(keys.get(i)) * weights.get(keys.get(j)) * n;
                }
            }
        }
        List<String> tk = inTriples(races, keys);
        for (int i = 0; i < tk.size(); i++) {
            for (int j = i + 1; j < tk.size(); j++) {
                for (int k = j + 1; k < tk.size(); k++) {
                    Double m = note3(races, base, tk.get(i), tk.get(j), tk.get(k));
                    if (m != null) {
                        t[3] += weights.get(tk.get(i)) * weights.get(tk.get(j)) * weights.get(tk.get(k)) * m;
                    }
                }
            }
        }
        return t;
    }

    /**
     * The sum's derivative in one condition's weight, by order: {its note, its pairs' interactions times the others'
     * weights, its triples' times the two others' weights}, the others being the set without it. The sum is multilinear,
     * so giving the condition a weight w adds exactly w times these (each times its order's factor).
     */
    public static double[] partial(Map<String, Double> races, double base, Map<String, Double> weights, String x) {
        List<String> keys = present(weights, x);
        double[] d = new double[3];
        Double n = note1(races, base, x);
        if (n != null) {
            d[0] = n;
        }
        for (int i = 0; i < keys.size(); i++) {
            Double p = note2(races, base, x, keys.get(i));
            if (p != null) {
                d[1] += weights.get(keys.get(i)) * p;
            }
        }
        if (triples(races).contains(x)) {
            List<String> tk = inTriples(races, keys);
            for (int i = 0; i < tk.size(); i++) {
                for (int j = i + 1; j < tk.size(); j++) {
                    Double t = note3(races, base, x, tk.get(i), tk.get(j));
                    if (t != null) {
                        d[2] += weights.get(tk.get(i)) * weights.get(tk.get(j)) * t;
                    }
                }
            }
        }
        return d;
    }

    /** The keys (in order) that belong to some measured triple: only they can have a triple's interaction. */
    private static List<String> inTriples(Map<String, Double> races, List<String> keys) {
        Set<String> in = triples(races);
        List<String> out = new ArrayList<>();
        for (String k : keys) {
            if (in.contains(k)) {
                out.add(k);
            }
        }
        return out;
    }

    private static final int CACHE = 8;
    /** The last maps asked (by identity) and their keys in triples: a map of races is read many times, never changed. */
    private static final List<Map<String, Double>> CACHED_MAPS = new ArrayList<>();
    private static final List<Set<String>> CACHED_KEYS = new ArrayList<>();

    /** Every condition key that is part of a measured set of three or more. */
    static Set<String> triples(Map<String, Double> races) {
        synchronized (CACHED_MAPS) {
            for (int i = 0; i < CACHED_MAPS.size(); i++) {
                if (CACHED_MAPS.get(i) == races) {
                    return CACHED_KEYS.get(i);
                }
            }
        }
        Set<String> out = new HashSet<>();
        for (String k : races.keySet()) {
            String[] parts = k.split("\\|");
            if (parts.length >= 3) {
                Collections.addAll(out, parts);
            }
        }
        synchronized (CACHED_MAPS) {
            if (CACHED_MAPS.size() >= CACHE) {
                CACHED_MAPS.remove(0);
                CACHED_KEYS.remove(0);
            }
            CACHED_MAPS.add(races);
            CACHED_KEYS.add(out);
        }
        return out;
    }

    /** The keys with a weight, sorted, without {@code skip}. */
    private static List<String> present(Map<String, Double> weights, String skip) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            if (e.getValue() != null && Math.abs(e.getValue()) > 1e-12 && !e.getKey().equals(skip)) {
                keys.add(e.getKey());
            }
        }
        Collections.sort(keys);
        return keys;
    }

    /**
     * The model of whole builds, in log-odds: the base, the four terms ({@link #terms} on the log-odds) times the model's
     * coefficients, and each condition's correction times its weight.
     */
    public static double modelLogit(Map<String, Double> logits, double logitBase, Map<String, Double> weights, AtlasModel.BuildModel m) {
        double[] t = terms(logits, logitBase, weights);
        double l = logitBase;
        for (int i = 0; i < 4; i++) {
            l += m.coef[i] * t[i];
        }
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            Double th = m.theta.get(e.getKey());
            if (th != null && e.getValue() != null) {
                l += th * e.getValue();
            }
        }
        return l;
    }

    /** The model's derivative in one condition's weight (log-odds), the set taken without it. */
    public static double modelGrad(Map<String, Double> logits, double logitBase, Map<String, Double> weights, String x, AtlasModel.BuildModel m) {
        double[] d = partial(logits, logitBase, weights, x);
        return m.coef[x.startsWith("A:") ? 0 : 1] * d[0] + m.coef[2] * d[1] + m.coef[3] * d[2] + m.theta.getOrDefault(x, 0.0);
    }

    /**
     * Ridge regression: the coefficients minimizing the squared error plus each coefficient's penalty times its square
     * (a penalty per column: small on the few global terms, larger on the many corrections). Gaussian elimination with
     * partial pivoting on the normal equations.
     */
    public static double[] ridge(double[][] x, double[] y, double[] penalty) {
        int p = penalty.length;
        double[][] a = new double[p][p + 1];
        for (int r = 0; r < x.length; r++) {
            for (int i = 0; i < p; i++) {
                if (x[r][i] == 0) {
                    continue;
                }
                for (int j = 0; j < p; j++) {
                    a[i][j] += x[r][i] * x[r][j];
                }
                a[i][p] += x[r][i] * y[r];
            }
        }
        for (int i = 0; i < p; i++) {
            a[i][i] += penalty[i];
        }
        for (int c = 0; c < p; c++) {
            int piv = c;
            for (int r = c + 1; r < p; r++) {
                if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) {
                    piv = r;
                }
            }
            double[] tmp = a[c];
            a[c] = a[piv];
            a[piv] = tmp;
            if (Math.abs(a[c][c]) < 1e-12) {
                continue;
            }
            for (int r = 0; r < p; r++) {
                if (r == c || a[r][c] == 0) {
                    continue;
                }
                double f = a[r][c] / a[c][c];
                for (int k = c; k <= p; k++) {
                    a[r][k] -= f * a[c][k];
                }
            }
        }
        double[] beta = new double[p];
        for (int i = 0; i < p; i++) {
            beta[i] = Math.abs(a[i][i]) < 1e-12 ? 0 : a[i][p] / a[i][i];
        }
        return beta;
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
