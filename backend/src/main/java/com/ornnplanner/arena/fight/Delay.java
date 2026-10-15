package com.ornnplanner.arena.fight;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Statuses (stat -> amount) of a source that only count once its conditions hold (time or hits in combat). The
 * builder no longer makes them (the arena's triggers do that job); the arena still honours them on a fighter given
 * them by hand.
 */
public class Delay {
    public String source;
    public Map<String, Double> amounts = new LinkedHashMap<>();
    public Double seconds;
    public Integer hits;
    public Integer hitsTaken;
    /** Seconds set by the user: then only the time counts. */
    public boolean fixed;
}
