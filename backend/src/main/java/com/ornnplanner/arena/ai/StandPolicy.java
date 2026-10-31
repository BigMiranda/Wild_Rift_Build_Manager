package com.ornnplanner.arena.ai;

import java.util.List;

/**
 * The tactical rules without running away: retreating and the escape Flash are never valid. The fights of the
 * effectiveness tests use it: there is nowhere to escape to on a straight lane, so a champion that runs lives until the
 * wall and its time to die measures the walk, not what it holds (at 400 more health a champion with no items went from
 * 2.8 s to 20.8 s against the same attacker). Everything else is {@link TacticalPolicy}.
 */
public final class StandPolicy implements Policy {

    private final TacticalPolicy tactical;

    public StandPolicy(List<String> priority) {
        this.tactical = new TacticalPolicy(priority);
    }

    @Override
    public Decision decide(Observation obs, ActionMask mask) {
        return tactical.decide(obs, a -> a != ActionSpace.RETREAT && a != ActionSpace.FLASH_AWAY && mask.valid(a));
    }
}
