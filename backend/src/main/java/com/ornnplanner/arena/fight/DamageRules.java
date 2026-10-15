package com.ornnplanner.arena.fight;

/** Damage rules shared by the arena and the views of a build (mitigation of a resistance). */
public final class DamageRules {

    private DamageRules() {
    }

    /** Damage multiplier of a resistance after % reduction, % penetration and flat penetration. */
    public static double mitigation(double resist, double shred, double pen, double flat) {
        double eff = resist > 0 ? Math.max(0, resist * (1 - shred) * (1 - pen) - flat) : resist;
        return eff >= 0 ? 100 / (100 + eff) : 2 - 100 / (100 - eff);
    }
}
