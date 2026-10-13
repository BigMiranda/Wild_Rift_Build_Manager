package com.ornnplanner.arena.ai;

/**
 * Decides what a champion does when the timeline leaves it free. The arena asks every step the champion can act; a
 * decision the arena cannot carry out (a cast that fails) is masked and the policy is asked again in the same step.
 *
 * <p>A policy may be shared by arenas running on several threads at once (a search, a training): it must not keep
 * state between calls, or must keep it per fight.
 */
public interface Policy {

    Decision decide(Observation obs, ActionMask mask);

    /**
     * Seconds between decisions: meanwhile the arena repeats the last action while it stays valid (0 = asked every
     * step the champion is free).
     */
    default double interval() {
        return 0;
    }

    /** The chosen action, and optionally the scores behind it (for the event log of a learned policy). */
    final class Decision {
        private static final Decision[] PLAIN = new Decision[ActionSpace.SIZE];

        static {
            for (int i = 0; i < PLAIN.length; i++) {
                PLAIN[i] = new Decision(i, null);
            }
        }

        public final int action;
        /** Score of each action (null for a scripted choice). */
        public final double[] scores;

        public Decision(int action, double[] scores) {
            this.action = action;
            this.scores = scores;
        }

        public static Decision of(int action) {
            return PLAIN[action];
        }
    }
}
