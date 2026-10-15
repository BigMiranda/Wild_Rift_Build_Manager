package com.ornnplanner.arena.ai;

import java.util.List;

/**
 * The scripted autopilot with simple tactics on top, in this order:
 * <ol>
 *   <li>stunned for more than 1s: Cleanse;</li>
 *   <li>dodge an enemy skillshot about to hit;</li>
 *   <li>low on health: Barrier / Heal; bursted (more than 35% of its health lost in 2s) while behind: Flash away; when
 *       losing the trade, run (Flash away when it is close to dying, Ghost, retreat);</li>
 *   <li>recast an ability whose window is open (when the prediction says the recast hits);</li>
 *   <li>damage item actives, Ignite on a weakened target, Exhaust when hurt, Ghost to catch a weakened target;</li>
 *   <li>a basic attack that consumes a mark on the target (Brittle, Vayne's third bolt, K'Sante's mark);</li>
 *   <li>abilities: one that chains the target's control first, then in priority order on the current target (as the
 *       scripted autopilot); an aimed one only when the prediction says it hits (at least {@link #AIM_FROM}), and an
 *       immobilize not while the target's control would waste it;</li>
 *   <li>ranged champions kite their target, melee ones chase it.</li>
 * </ol>
 * The rules on predictions, Cleanse and the escape Flash came with observation schema 3; {@link #legacy} plays as
 * before them (the base of learned versions saved with schema 2, so they keep playing as they did).
 * Stateless: a decision only depends on what the champion sees, so it can be shared by parallel fights.
 */
public final class TacticalPolicy implements Policy {

    /** Seconds before an enemy skillshot hits under which it side steps. */
    static final double DODGE_WITHIN = 0.7;
    static final double DEFENSIVE_BELOW = 0.35;
    static final double RETREAT_BELOW = 0.25;
    static final double FLASH_AWAY_BELOW = 0.15;
    static final double RANGED_FROM = 400;
    /** Least predicted hit chance of an aimed ability (and of a recast) before it is cast. */
    public static final double AIM_FROM = 0.6;
    /** Cleanse a stun with more than this left. */
    static final double CLEANSE_OVER = 1.0;
    /** Flash away after losing this share of health in the last 2s, while behind the target. */
    static final double BURST_LOSS = 0.35;
    /** An immobilize is held while the target's control would waste more than this of it. */
    static final double OVERLAP = 0.3;

    // the rules that came with the predictions, one bit each (all on by default; none = legacy)
    public static final int CLEANSE = 1;
    public static final int BURST_FLASH = 2;
    public static final int RECAST_HITS = 4;
    public static final int ATTACK_MARK = 8;
    public static final int CHAIN_FIRST = 16;
    public static final int AIM = 32;
    public static final int NO_OVERLAP = 64;
    public static final int ALL = 127;

    private final List<String> priority;
    private final int rules;
    private final boolean predictive;

    /** @param priority ability order (R, 3, 1, 2 by default); read at each decision, not copied. */
    public TacticalPolicy(List<String> priority) {
        this(priority, ALL);
    }

    /** With only some of the rules on predictions (to measure each one). */
    public TacticalPolicy(List<String> priority, int rules) {
        this.priority = priority;
        this.rules = rules;
        this.predictive = rules != 0;
    }

    /** The rules as they were before the predictions (observation schema 2). */
    public static TacticalPolicy legacy(List<String> priority) {
        return new TacticalPolicy(priority, 0);
    }

    public boolean predictive() {
        return predictive;
    }

    private boolean on(int rule) {
        return (rules & rule) != 0;
    }

    @Override
    public Decision decide(Observation obs, ActionMask mask) {
        Observation.Self s = obs.self();
        Observation.Other t = obs.enemy(0);
        double hp = s.hp / Math.max(1, s.maxHp);
        double targetHp = t.present ? t.hp / Math.max(1, t.maxHp) : 1;

        if (on(CLEANSE) && s.stunned && s.controlLeft > CLEANSE_OVER) {
            int i = active(s, mask, "Purificar");
            if (i >= 0) {
                return Decision.of(ActionSpace.active(i));
            }
        }
        if (s.threatTime <= DODGE_WITHIN && mask.valid(ActionSpace.DODGE)) {
            return Decision.of(ActionSpace.DODGE);
        }
        if (hp < DEFENSIVE_BELOW) {
            int i = active(s, mask, "Barreira", "Curar");
            if (i >= 0) {
                return Decision.of(ActionSpace.active(i));
            }
        }
        if (on(BURST_FLASH) && s.recentLoss > BURST_LOSS && t.present && targetHp > hp && t.distance < 600
                && mask.valid(ActionSpace.FLASH_AWAY)) {
            return Decision.of(ActionSpace.FLASH_AWAY);   // bursted and behind: get out
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
            if (mask.valid(ActionSpace.recast(k)) && (!on(RECAST_HITS) || !t.present || obs.predicted().self().recastHit[k] >= AIM_FROM)) {
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
        if (t.present) {
            if (on(ATTACK_MARK) && s.attackReady && t.inAttackReach && mask.valid(ActionSpace.attack(0))
                    && obs.predicted().self().attackConsumesMark) {
                return Decision.of(ActionSpace.attack(0));   // the attack pops the mark now
            }
            for (String slot : on(CHAIN_FIRST) ? priority : List.<String>of()) {
                int k = ActionSpace.slotIndex(slot);
                if (k >= 0 && mask.valid(ActionSpace.cast(k, 0)) && obs.predicted().self().chainsControl[k] && castable(s, t, k)) {
                    return Decision.of(ActionSpace.cast(k, 0));
                }
            }
        }
        for (String slot : priority) {
            int k = ActionSpace.slotIndex(slot);
            if (k >= 0 && mask.valid(ActionSpace.cast(k, 0)) && (!predictive || castable(obs.predicted().self(), t, k))) {
                return Decision.of(ActionSpace.cast(k, 0));
            }
        }
        int move = s.attackRange >= RANGED_FROM ? ActionSpace.kite(0) : ActionSpace.attack(0);
        return Decision.of(mask.valid(move) ? move : ActionSpace.IDLE);
    }

    /** By the predictions: an aimed ability that would hit; an immobilize that the target's control would not waste. */
    private boolean castable(Observation.Self s, Observation.Other t, int k) {
        if (!t.present) {
            return true;
        }
        // a dash whose main effect needs terrain still deals its damage to whoever is on its way: that hit counts too
        if (on(AIM) && s.aimed[k] && s.hitChance[k] < AIM_FROM && s.bodyHit[k] < AIM_FROM) {
            return false;
        }
        return !(on(NO_OVERLAP) && s.immobilizes[k] && t.controlLeft - s.timeToEffect[k] > OVERLAP);
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
