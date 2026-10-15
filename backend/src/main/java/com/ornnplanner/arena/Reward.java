package com.ornnplanner.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Public doors to the training help of {@link ArenaTraining} (what the screen and the tests read). */
public final class Reward {

    private Reward() {
    }

    /** The training-only points of a fight's events for a stage (its weights and its guidelines' rewards). */
    public static double of(Map<String, Double> events, ArenaTraining.StageSpec spec) {
        return ArenaTraining.reward(events, spec);
    }

    /** The names of a champion's drills, from its sheet. */
    public static List<String> drills(Arena.Input champion) {
        List<String> out = new ArrayList<>();
        for (ArenaTraining.Drill d : ArenaTraining.drillsOf(champion)) {
            out.add(d.name);
        }
        return out;
    }
}
