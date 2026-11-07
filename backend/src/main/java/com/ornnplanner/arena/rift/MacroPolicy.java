package com.ornnplanner.arena.rift;

/**
 * What a champion does on the map when no fight is going on, above the micro of the arena. The match asks it each coarse
 * step; a learned macro only has to implement this. Parameters of the scripted one: {@code macro_<patch>.yml}.
 */
public interface MacroPolicy {

    /** What it goes for now. */
    enum Goal { LANE, JUNGLE, RECALL, OBJECTIVE, GROUP, PUSH, RETREAT, SHOP }

    /** A goal and the place it is at. */
    final class Intent {
        public final Goal goal;
        public final double x;
        public final double y;
        /** The lane it is working on, the camp index, or the objective. */
        public final String what;

        public Intent(Goal goal, double x, double y, String what) {
            this.goal = goal;
            this.x = x;
            this.y = y;
            this.what = what;
        }
    }

    Intent decide(Match match, Match.Seat me);
}
