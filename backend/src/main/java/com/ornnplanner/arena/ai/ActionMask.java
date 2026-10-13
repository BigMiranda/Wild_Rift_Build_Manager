package com.ornnplanner.arena.ai;

/**
 * Which actions of the {@link ActionSpace} can be done now (learned, off cooldown, enough resource, target present and
 * in range, not kept for an order of the timeline). Answered by the arena on demand, so a policy only pays for the
 * actions it asks about; asking has no effect on the fight.
 */
@FunctionalInterface
public interface ActionMask {

    boolean valid(int action);
}
