package com.ornnplanner.arena.ai;

import java.util.List;

/**
 * The discrete actions a champion can choose when the timeline leaves it free. Targets are the enemy slots of the
 * {@link Observation}: slot 0 is the champion's current target (focus, fixed target or nearest), then the other enemies
 * by distance.
 *
 * <pre>
 *   0 .. 11   cast ability s (1, 2, 3, R) on enemy k (0 .. K-1)   index = s * K + k
 *   12 .. 14  chase and attack enemy k
 *   15        idle (keep the timeline's move / hold, else stay)
 *   16 .. 19  recast ability s (its window is open) towards the current target
 *   20 .. 25  use active i (item active or summoner spell, in the order of the build) on the current target
 *   26        Flash away from the enemies
 *   27        retreat: walk away from the enemies' center
 *   28 .. 30  kite enemy k: attack when the attack is up, else keep just inside attack reach, stepping back
 *   31        dodge: side step out of the path of the enemy skillshot coming at the champion
 *   32 .. 35  combo c: run the champion's c-th active combo (by score) on the current target
 * </pre>
 *
 * Casts, recasts and actives are instant decisions: when one leaves the champion free, it decides again in the same
 * step with all of them masked (one per step, as the autopilot always did). Moves (attack, retreat, kite, dodge) end
 * the step, and yield to a move or hold of the timeline. A combo runs over several steps (the arena keeps its state);
 * while it runs only dodging, Flash away, idle (go on) and the running combo itself are valid.
 */
public final class ActionSpace {

    /** Enemy slots seen and targetable. */
    public static final int ENEMIES = 3;
    /** Item actives and summoner spells a policy can use (the first ones of the build). */
    public static final int ACTIVES = 6;
    /** Combos of the champion's library a policy can run (its active ones, best score first). */
    public static final int COMBOS = 4;
    public static final List<String> SLOTS = List.of("1", "2", "3", "R");
    private static final int SLOT_COUNT = 4;

    public static final int CAST = 0;
    public static final int ATTACK = CAST + SLOT_COUNT * ENEMIES;
    public static final int IDLE = ATTACK + ENEMIES;
    public static final int RECAST = IDLE + 1;
    public static final int ACTIVE = RECAST + SLOT_COUNT;
    public static final int FLASH_AWAY = ACTIVE + ACTIVES;
    public static final int RETREAT = FLASH_AWAY + 1;
    public static final int KITE = RETREAT + 1;
    public static final int DODGE = KITE + ENEMIES;
    public static final int COMBO = DODGE + 1;
    public static final int SIZE = COMBO + COMBOS;

    /** Score of an action a migrated network did not have (a combo): never above a real action's. */
    public static final double NEW_ACTION_BIAS = -5;

    private ActionSpace() {
    }

    /**
     * Where each action of an older layout (by its action count) is in this one; the actions it did not have get
     * {@link #NEW_ACTION_BIAS}. The layouts so far only appended, so the index stays.
     */
    public static int[] migration(int oldSize) {
        int[] map = new int[oldSize];
        for (int i = 0; i < oldSize; i++) {
            map[i] = i;
        }
        return map;
    }

    public static int cast(int slot, int enemy) {
        return CAST + slot * ENEMIES + enemy;
    }

    public static int attack(int enemy) {
        return ATTACK + enemy;
    }

    public static int recast(int slot) {
        return RECAST + slot;
    }

    public static int active(int i) {
        return ACTIVE + i;
    }

    public static int kite(int enemy) {
        return KITE + enemy;
    }

    public static boolean isCast(int action) {
        return action >= CAST && action < ATTACK;
    }

    public static boolean isAttack(int action) {
        return action >= ATTACK && action < IDLE;
    }

    public static boolean isRecast(int action) {
        return action >= RECAST && action < ACTIVE;
    }

    public static boolean isActive(int action) {
        return action >= ACTIVE && action < FLASH_AWAY;
    }

    public static boolean isKite(int action) {
        return action >= KITE && action < DODGE;
    }

    public static int combo(int c) {
        return COMBO + c;
    }

    public static boolean isCombo(int action) {
        return action >= COMBO && action < SIZE;
    }

    /** Done at once, without ending the step: casts, recasts, actives, Flash. */
    public static boolean isInstant(int action) {
        return isCast(action) || isRecast(action) || isActive(action) || action == FLASH_AWAY;
    }

    /** Index of the ability (0 = 1 ... 3 = R) of a cast or a recast. */
    public static int slotOf(int action) {
        return isRecast(action) ? action - RECAST : (action - CAST) / ENEMIES;
    }

    /** Enemy slot of a cast, an attack or a kite (0, the current target, for the others). */
    public static int enemyOf(int action) {
        if (isCast(action)) {
            return (action - CAST) % ENEMIES;
        }
        if (isAttack(action)) {
            return action - ATTACK;
        }
        if (isKite(action)) {
            return action - KITE;
        }
        return 0;
    }

    /** Index of an ability name (1, 2, 3, R), or -1. */
    public static int slotIndex(String slot) {
        return SLOTS.indexOf(slot);
    }

    public static String describe(int action) {
        if (isCast(action)) {
            return "habilidade " + SLOTS.get(slotOf(action)) + " no inimigo " + (enemyOf(action) + 1);
        }
        if (isAttack(action)) {
            return "atacar o inimigo " + (enemyOf(action) + 1);
        }
        if (isRecast(action)) {
            return "reconjurar " + SLOTS.get(slotOf(action));
        }
        if (isActive(action)) {
            return "ativo " + (action - ACTIVE + 1);
        }
        if (isKite(action)) {
            return "kite no inimigo " + (enemyOf(action) + 1);
        }
        if (isCombo(action)) {
            return "combo " + (action - COMBO + 1);
        }
        switch (action) {
            case FLASH_AWAY:
                return "Flash para fugir";
            case RETREAT:
                return "recuar";
            case DODGE:
                return "desviar";
            default:
                return "parado";
        }
    }
}
