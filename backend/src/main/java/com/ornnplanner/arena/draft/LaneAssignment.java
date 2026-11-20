package com.ornnplanner.arena.draft;

import java.util.List;

/**
 * Rodada 20.b, Parte 2: the likely assignment of a team's picks to the five lanes, pure and deterministic. Every
 * injective assignment of the k picks (k &lt;= 5) to the lanes is enumerated (5!/(5-k)!, at most 120) and weighed by the
 * product of each pick's weight in its lane (the meta's presence, else the seed's order, zero where the pick cannot go);
 * the result is each pick's distribution over the lanes, the chance of each lane still being open for the next pick and
 * the most likely whole assignment. When no assignment has weight (two picks that only play the same lane), every lane
 * gets a small weight and the result says it was relaxed: nothing breaks, the distribution only changes.
 */
public final class LaneAssignment {

    /** The weight every lane gets when the told lanes cannot all fit. */
    static final double RELAXED = 0.02;

    public static final class Result {
        /** pick x lane: the chance of the pick playing the lane (rows sum to 1). */
        public final double[][] marginal;
        /** lane: the chance of no pick of the team playing it. */
        public final double[] open;
        /** The most likely whole assignment: pick -> lane index. */
        public final int[] best;
        /** The told lanes could not fit, so every lane got a small weight. */
        public final boolean relaxed;

        Result(double[][] marginal, double[] open, int[] best, boolean relaxed) {
            this.marginal = marginal;
            this.open = open;
            this.best = best;
            this.relaxed = relaxed;
        }
    }

    private LaneAssignment() {
    }

    /** The assignment of the picks whose weights per lane are {@code weights} (pick x lane, &gt;= 0). */
    public static Result of(int lanes, List<double[]> weights) {
        int k = Math.min(weights.size(), lanes);
        double[][] w = new double[k][];
        for (int i = 0; i < k; i++) {
            w[i] = weights.get(i).clone();
        }
        Acc a = enumerate(lanes, w);
        boolean relaxed = false;
        if (a.total <= 0 && k > 0) {
            relaxed = true;
            for (double[] row : w) {
                for (int l = 0; l < lanes; l++) {
                    row[l] = Math.max(row[l], RELAXED);
                }
            }
            a = enumerate(lanes, w);
        }
        double[][] marginal = new double[k][lanes];
        double[] open = new double[lanes];
        if (a.total > 0) {
            for (int i = 0; i < k; i++) {
                for (int l = 0; l < lanes; l++) {
                    marginal[i][l] = a.marginal[i][l] / a.total;
                }
            }
            for (int l = 0; l < lanes; l++) {
                open[l] = a.open[l] / a.total;
            }
        } else {
            java.util.Arrays.fill(open, 1);
        }
        return new Result(marginal, open, a.best, relaxed);
    }

    private static final class Acc {
        double total;
        double[][] marginal;
        double[] open;
        int[] best;
        double bestWeight = -1;
    }

    private static Acc enumerate(int lanes, double[][] w) {
        Acc a = new Acc();
        a.marginal = new double[w.length][lanes];
        a.open = new double[lanes];
        a.best = new int[w.length];
        walk(w, lanes, 0, new int[w.length], new boolean[lanes], 1.0, a);
        return a;
    }

    private static void walk(double[][] w, int lanes, int i, int[] at, boolean[] used, double weight, Acc a) {
        if (weight <= 0) {
            return;
        }
        if (i == w.length) {
            a.total += weight;
            for (int p = 0; p < w.length; p++) {
                a.marginal[p][at[p]] += weight;
            }
            for (int l = 0; l < lanes; l++) {
                if (!used[l]) {
                    a.open[l] += weight;
                }
            }
            // ties keep the first found (lanes in the seed's order): deterministic
            if (weight > a.bestWeight * (1 + 1e-12)) {
                a.bestWeight = weight;
                a.best = at.clone();
            }
            return;
        }
        for (int l = 0; l < lanes; l++) {
            if (!used[l] && w[i][l] > 0) {
                used[l] = true;
                at[i] = l;
                walk(w, lanes, i + 1, at, used, weight * w[i][l], a);
                used[l] = false;
            }
        }
    }
}
