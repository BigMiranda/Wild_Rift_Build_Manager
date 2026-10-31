package com.ornnplanner.arena;

import java.util.function.DoubleUnaryOperator;

/**
 * Binary search of the gold in pure stats that takes a fight's result from {@code start} (no gold) to {@code target}.
 * The curve comes from fights, so it may not be perfectly monotonic: the search keeps the bracket and stops at the
 * tolerance or at its step limit.
 */
public final class GoldSearch {

    private GoldSearch() {
    }

    public static final class Found {
        public final double gold;
        /** Even the top of the range does not reach the target: the value is at least {@link #gold}. */
        public final boolean capped;
        public final int evaluations;

        Found(double gold, boolean capped, int evaluations) {
            this.gold = gold;
            this.capped = capped;
            this.evaluations = evaluations;
        }
    }

    public static Found find(DoubleUnaryOperator curve, double start, double target, double top, double tolerance, int steps) {
        if (Math.abs(target - start) <= tolerance) {
            return new Found(0, false, 0);
        }
        double hiValue = curve.applyAsDouble(top);
        int n = 1;
        if (hiValue < target - tolerance) {
            return new Found(top, true, n);
        }
        double lo = 0;
        double hi = top;
        double loValue = start;
        for (int k = 0; k < steps; k++) {
            // on a straight stretch the crossing is where the line says; the middle keeps the bracket shrinking
            double t = hiValue > loValue ? (target - loValue) / (hiValue - loValue) : 0.5;
            double mid = lo + Math.max(0.1, Math.min(0.9, t)) * (hi - lo);
            double v = curve.applyAsDouble(mid);
            n++;
            if (Math.abs(v - target) <= tolerance) {
                return new Found(mid, false, n);
            }
            if (v < target) {
                lo = mid;
                loValue = v;
            } else {
                hi = mid;
                hiValue = v;
            }
        }
        return new Found((lo + hi) / 2, false, n);
    }
}
