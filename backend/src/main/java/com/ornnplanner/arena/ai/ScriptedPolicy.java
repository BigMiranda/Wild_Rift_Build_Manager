package com.ornnplanner.arena.ai;

import java.util.List;

/**
 * The arena's original autopilot: the first ability in priority order that is ready and in range of the current
 * target, else chase the target and attack it. Only ever acts on enemy slot 0 (the current target).
 */
public final class ScriptedPolicy implements Policy {

    private final List<String> priority;

    /** @param priority ability order (R, 3, 1, 2 by default); read at each decision, not copied. */
    public ScriptedPolicy(List<String> priority) {
        this.priority = priority;
    }

    @Override
    public Decision decide(Observation obs, ActionMask mask) {
        for (String slot : priority) {
            int s = ActionSpace.slotIndex(slot);
            if (s >= 0 && mask.valid(ActionSpace.cast(s, 0))) {
                return Decision.of(ActionSpace.cast(s, 0));
            }
        }
        return Decision.of(mask.valid(ActionSpace.attack(0)) ? ActionSpace.attack(0) : ActionSpace.IDLE);
    }
}
