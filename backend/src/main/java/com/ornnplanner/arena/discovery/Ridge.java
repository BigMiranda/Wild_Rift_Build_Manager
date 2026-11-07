package com.ornnplanner.arena.discovery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The surrogate model of the build search (Rodada 9, part 3): ridge regression on "item present" plus chosen pairs of
 * items, trained on the builds already measured, so children of a generation are ranked before any fight. Plain Java:
 * the normal equations solved by Gaussian elimination (a few hundred features at most).
 */
public final class Ridge {

    private final List<Long> items;
    private final List<long[]> pairs;
    private final double lambda;
    private double[] w;
    private double bias;

    public Ridge(List<Long> items, List<long[]> pairs, double lambda) {
        this.items = new ArrayList<>(items);
        this.pairs = new ArrayList<>(pairs);
        this.lambda = lambda;
    }

    /** The features of a set: one per item present, one per pair with both present. */
    double[] features(java.util.Collection<Long> set) {
        double[] x = new double[items.size() + pairs.size()];
        for (int i = 0; i < items.size(); i++) {
            x[i] = set.contains(items.get(i)) ? 1 : 0;
        }
        for (int k = 0; k < pairs.size(); k++) {
            long[] p = pairs.get(k);
            x[items.size() + k] = set.contains(p[0]) && set.contains(p[1]) ? 1 : 0;
        }
        return x;
    }

    /** Fits on the measured sets (centered target, so the bias is the mean). */
    public Ridge fit(List<? extends java.util.Collection<Long>> sets, List<Double> y) {
        int n = sets.size();
        int d = items.size() + pairs.size();
        bias = 0;
        for (double v : y) {
            bias += v;
        }
        bias = n == 0 ? 0 : bias / n;
        double[][] a = new double[d][d];
        double[] b = new double[d];
        for (int r = 0; r < n; r++) {
            double[] x = features(sets.get(r));
            double t = y.get(r) - bias;
            for (int i = 0; i < d; i++) {
                if (x[i] == 0) {
                    continue;
                }
                b[i] += x[i] * t;
                for (int j = 0; j < d; j++) {
                    a[i][j] += x[i] * x[j];
                }
            }
        }
        for (int i = 0; i < d; i++) {
            a[i][i] += lambda;
        }
        w = solve(a, b);
        return this;
    }

    public double predict(java.util.Collection<Long> set) {
        if (w == null) {
            return bias;
        }
        double[] x = features(set);
        double s = bias;
        for (int i = 0; i < x.length; i++) {
            s += w[i] * x[i];
        }
        return s;
    }

    /** The learned worth of each item (its weight), for the reports. */
    public Map<Long, Double> itemWeights() {
        Map<Long, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            out.put(items.get(i), w == null ? 0 : w[i]);
        }
        return out;
    }

    /** Gaussian elimination with partial pivoting (the matrix is positive definite thanks to lambda). */
    static double[] solve(double[][] a0, double[] b0) {
        int n = b0.length;
        double[][] a = new double[n][];
        for (int i = 0; i < n; i++) {
            a[i] = a0[i].clone();
        }
        double[] b = b0.clone();
        for (int c = 0; c < n; c++) {
            int p = c;
            for (int r = c + 1; r < n; r++) {
                if (Math.abs(a[r][c]) > Math.abs(a[p][c])) {
                    p = r;
                }
            }
            double[] tr = a[c];
            a[c] = a[p];
            a[p] = tr;
            double tb = b[c];
            b[c] = b[p];
            b[p] = tb;
            if (Math.abs(a[c][c]) < 1e-12) {
                continue;
            }
            for (int r = c + 1; r < n; r++) {
                double f = a[r][c] / a[c][c];
                if (f == 0) {
                    continue;
                }
                for (int k = c; k < n; k++) {
                    a[r][k] -= f * a[c][k];
                }
                b[r] -= f * b[c];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = b[r];
            for (int k = r + 1; k < n; k++) {
                s -= a[r][k] * x[k];
            }
            x[r] = Math.abs(a[r][r]) < 1e-12 ? 0 : s / a[r][r];
        }
        return x;
    }

    /** Pearson correlation of two lists (the surrogate's hit against the real measure). */
    public static double correlation(List<Double> a, List<Double> b) {
        int n = Math.min(a.size(), b.size());
        if (n < 2) {
            return 0;
        }
        double ma = 0;
        double mb = 0;
        for (int i = 0; i < n; i++) {
            ma += a.get(i);
            mb += b.get(i);
        }
        ma /= n;
        mb /= n;
        double sab = 0;
        double saa = 0;
        double sbb = 0;
        for (int i = 0; i < n; i++) {
            sab += (a.get(i) - ma) * (b.get(i) - mb);
            saa += (a.get(i) - ma) * (a.get(i) - ma);
            sbb += (b.get(i) - mb) * (b.get(i) - mb);
        }
        return saa <= 0 || sbb <= 0 ? 0 : sab / Math.sqrt(saa * sbb);
    }
}
