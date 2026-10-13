package com.ornnplanner.arena.ai;

import java.util.List;

/**
 * When a training stage stops improving: after {@link #minGenerations}, if the best value of the last {@link #window}
 * generations does not beat the best value before them by {@link #pct} points, the stage has reached a plateau.
 * The values are a metric in percent (higher is better), one per generation from the first.
 */
public final class Plateau {

    public final double pct;
    public final int window;
    public final int minGenerations;

    public Plateau(double pct, int window, int minGenerations) {
        this.pct = pct;
        this.window = window;
        this.minGenerations = minGenerations;
    }

    /** Never stops a stage. */
    public static Plateau off() {
        return new Plateau(0, 0, 0);
    }

    public boolean enabled() {
        return pct > 0 && window > 0;
    }

    public boolean reached(List<Double> values) {
        int n = values.size();
        if (!enabled() || n < minGenerations || n <= window) {
            return false;
        }
        double before = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n - window; i++) {
            before = Math.max(before, values.get(i));
        }
        double recent = Double.NEGATIVE_INFINITY;
        for (int i = n - window; i < n; i++) {
            recent = Math.max(recent, values.get(i));
        }
        return recent < before + pct - 1e-9;
    }
}
