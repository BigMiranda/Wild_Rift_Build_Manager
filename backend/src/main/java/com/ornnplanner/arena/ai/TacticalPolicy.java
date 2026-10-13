package com.ornnplanner.arena.ai;

import java.util.List;

/**
 * The scripted autopilot with simple tactics on top, in this order:
 * <ol>
 *   <li>dodge an enemy skillshot about to hit;</li>
 *   <li>low on health: Barrier / Heal; when losing the trade, run (Flash away when it is close to dying, Ghost);</li>
 *   <li>recast an ability whose window is open;</li>
 *   <li>damage item actives, Ignite on a weakened target, Exhaust when hurt, Ghost to catch a weakened target;</li>
 *   <li>abilities in priority order on the current target (as the scripted autopilot);</li>
 *   <li>ranged champions kite their target, melee ones chase it.</li>
 * </ol>
 * Stateless: a decision only depends on what the champion sees, so it can be shared by parallel fights.
 */
public final class TacticalPolicy implements Policy {

    /** Seconds before an enemy skillshot hits under which it side steps. */
    static final double DODGE_WITHIN = 0.7;
    static final double DEFENSIVE_BELOW = 0.35;
    static final double RETREAT_BELOW = 0.25;
    static final double FLASH_AWAY_BELOW = 0.15;
    static final double RANGED_FROM = 400;

    private final List<String> priority;

    /** @param priority ability order (R, 3, 1, 2 by default); read at each decision, not copied. */
    public TacticalPolicy(List<String> priority) {
        this.priority = priority;
    }

    @Override
    public Decision decide(Observation obs, ActionMask mask) {
        Observation.Self s = obs.self();
        Observation.Other t = obs.enemy(0);
        double hp = s.hp / Math.max(1, s.maxHp);
        double targetHp = t.present ? t.hp / Math.max(1, t.maxHp) : 1;

        if (s.threatTime <= DODGE_WITHIN && mask.valid(ActionSpace.DODGE)) {
            return Decision.of(ActionSpace.DODGE);
        }
        if (hp < DEFENSIVE_BELOW) {
            int i = active(s, mask, "Barreira", "Curar");
            if (i >= 0) {
                return Decision.of(ActionSpace.active(i));
            }
        }
        if (hp < RETREAT_BELOW && t.present && targetHp > hp + 0.1) {
            if (hp < FLASH_AWAY_BELOW && t.distance < 450 && mask.valid(ActionSpace.FLASH_AWAY)) {
                return Decision.of(ActionSpace.FLASH_AWAY);
            }
            int ghost = active(s, mask, "Fantasma");
            if (ghost >= 0) {
                return Decision.of(ActionSpace.active(ghost));
            }
            if (mask.valid(ActionSpace.RETREAT)) {
                return Decision.of(ActionSpace.RETREAT);
            }
        }
        for (int k = 0; k < ActionSpace.SLOTS.size(); k++) {
            if (mask.valid(ActionSpace.recast(k))) {
                return Decision.of(ActionSpace.recast(k));
            }
        }
        if (t.present) {
            int i = active(s, mask, Observation.DAMAGE);
            if (i < 0 && targetHp < 0.5) {
                i = active(s, mask, "Incendiar");
            }
            if (i < 0 && hp < 0.5) {
                i = active(s, mask, "Exaustão");
            }
            if (i < 0 && targetHp < 0.5 && t.distance > s.attackRange + 250 && t.distance < 1500) {
                i = active(s, mask, "Fantasma");
            }
            if (i >= 0) {
                return Decision.of(ActionSpace.active(i));
            }
        }
        for (String slot : priority) {
            int k = ActionSpace.slotIndex(slot);
            if (k >= 0 && mask.valid(ActionSpace.cast(k, 0))) {
                return Decision.of(ActionSpace.cast(k, 0));
            }
        }
        int move = s.attackRange >= RANGED_FROM ? ActionSpace.kite(0) : ActionSpace.attack(0);
        return Decision.of(mask.valid(move) ? move : ActionSpace.IDLE);
    }

    /** The first usable active of one of these kinds ({@link Observation.Self#activeKind}), or -1. */
    private static int active(Observation.Self s, ActionMask mask, String... kinds) {
        for (int i = 0; i < ActionSpace.ACTIVES; i++) {
            if (s.activeKind[i] == null || !mask.valid(ActionSpace.active(i))) {
                continue;
            }
            for (String k : kinds) {
                if (k.equals(s.activeKind[i])) {
                    return i;
                }
            }
        }
        return -1;
    }
}
