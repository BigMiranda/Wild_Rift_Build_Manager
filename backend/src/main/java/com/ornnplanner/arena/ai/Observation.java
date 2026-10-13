package com.ornnplanner.arena.ai;

import java.util.function.Consumer;

/**
 * What a champion sees when it decides: raw facts about itself, the enemy slots of the {@link ActionSpace} (slot 0 =
 * current target, then the others by distance), its nearest allies and the fight's clock. The arena fills it only
 * when a policy reads it (the scripted policy never does), and {@link #vector()} turns it into the fixed-size input of
 * a learned policy, every feature within [-1, 1].
 *
 * <p>Change {@link #SCHEMA_VERSION} whenever the vector's layout or meaning changes: weights saved for another version
 * cannot be read with this one.
 */
public final class Observation {

    public static final int SCHEMA_VERSION = 2;
    public static final int ALLIES = 2;
    public static final String DAMAGE = "dano";
    public static final String DEFENSE = "defesa";
    private static final int SLOTS = ActionSpace.SLOTS.size();
    private static final int ACTIVES = ActionSpace.ACTIVES;

    public static final int SELF_SIZE = 4 + 4 * SLOTS + 8 + 2 * ACTIVES + 2;
    public static final int ENEMY_SIZE = 8 + SLOTS;
    public static final int ALLY_SIZE = 3;
    public static final int SIZE = SELF_SIZE + ActionSpace.ENEMIES * ENEMY_SIZE + ALLIES * ALLY_SIZE + 1;

    /** The deciding champion. Distances in map units, times in seconds. */
    public static final class Self {
        public double hp;
        public double maxHp;
        public double shield;
        public double resource;
        /** 0 when the champion has no resource bar. */
        public double resourceMax;
        public int level;
        public final double[] cooldownLeft = new double[SLOTS];
        public final boolean[] learned = new boolean[SLOTS];
        /** Learned, off cooldown, with a charge, not overheated. */
        public final boolean[] ready = new boolean[SLOTS];
        public final boolean[] recastOpen = new boolean[SLOTS];
        public double attackRange;
        public double moveSpeed;
        public boolean stunned;
        public boolean airborne;
        public boolean invisible;
        public boolean empowered;
        public boolean attackReady;
        /** Item actives and summoner spells, in the order of the {@link ActionSpace} (null name = none). */
        public final String[] activeName = new String[ACTIVES];
        public final boolean[] activeReady = new boolean[ACTIVES];
        /** {@link #DAMAGE} (an item active that deals damage), {@link #DEFENSE} (other item actives) or the spell's name. */
        public final String[] activeKind = new String[ACTIVES];
        /** Distance to the nearest wall of the lane. */
        public double wallDistance;
        /** Seconds until the enemy skillshot coming at the champion hits it (infinite = none). */
        public double threatTime = Double.POSITIVE_INFINITY;
    }

    /** Another champion, relative to the deciding one. */
    public static final class Other {
        public boolean present;
        public double hp;
        public double maxHp;
        public double shield;
        public double dx;
        public double dy;
        public double distance;
        public boolean crowdControlled;
        public boolean inAttackReach;
        /** In use range of each of the deciding champion's abilities. */
        public final boolean[] inAbilityRange = new boolean[SLOTS];
    }

    // created on the first read: most steps of a scripted fight never look
    private Self self;
    private Other[] enemies;
    private Other[] allies;
    private final Consumer<Observation> filler;
    private boolean filled;
    private double time;
    private double duration;

    /** @param filler writes the facts into the observation (through the setters), once, when first read */
    public Observation(Consumer<Observation> filler) {
        this.filler = filler;
    }

    private void fill() {
        if (!filled) {
            filled = true;
            self = new Self();
            enemies = new Other[ActionSpace.ENEMIES];
            allies = new Other[ALLIES];
            for (int i = 0; i < enemies.length; i++) {
                enemies[i] = new Other();
            }
            for (int i = 0; i < allies.length; i++) {
                allies[i] = new Other();
            }
            filler.accept(this);
        }
    }

    public Self self() {
        fill();
        return self;
    }

    public Other enemy(int k) {
        fill();
        return enemies[k];
    }

    public Other ally(int k) {
        fill();
        return allies[k];
    }

    public double time() {
        fill();
        return time;
    }

    public double duration() {
        fill();
        return duration;
    }

    /** For the filler (during the fill): the raw objects. */
    public Self rawSelf() {
        return self;
    }

    public Other rawEnemy(int k) {
        return enemies[k];
    }

    public Other rawAlly(int k) {
        return allies[k];
    }

    public void setClock(double time, double duration) {
        this.time = time;
        this.duration = duration;
    }

    /** The input vector of a learned policy ({@link #SIZE} features in [-1, 1]). */
    public double[] vector() {
        fill();
        double[] v = new double[SIZE];
        int i = 0;
        Self s = self;
        double maxHp = Math.max(1, s.maxHp);
        v[i++] = s.hp / maxHp;
        v[i++] = s.shield / maxHp;
        v[i++] = s.resourceMax > 0 ? s.resource / s.resourceMax : 0;
        v[i++] = s.level / 18.0;
        for (int k = 0; k < SLOTS; k++) {
            v[i++] = s.cooldownLeft[k] / 20;
            v[i++] = flag(s.ready[k]);
            v[i++] = flag(s.learned[k]);
            v[i++] = flag(s.recastOpen[k]);
        }
        v[i++] = s.attackRange / 1000;
        v[i++] = s.moveSpeed / 1000;
        v[i++] = flag(s.stunned);
        v[i++] = flag(s.airborne);
        v[i++] = flag(s.invisible);
        v[i++] = flag(s.empowered);
        v[i++] = flag(s.attackReady);
        v[i++] = s.wallDistance / 1000;
        for (int k = 0; k < ACTIVES; k++) {
            v[i++] = flag(s.activeName[k] != null);
            v[i++] = flag(s.activeReady[k]);
        }
        boolean threat = s.threatTime < Double.POSITIVE_INFINITY;
        v[i++] = flag(threat);
        v[i++] = threat ? 1 - Math.min(1, s.threatTime / 1.5) : 0;
        for (Other e : enemies) {
            i = other(v, i, e, true);
        }
        for (Other a : allies) {
            i = other(v, i, a, false);
        }
        v[i++] = duration > 0 ? time / duration : 0;
        for (int k = 0; k < v.length; k++) {
            v[k] = Math.max(-1, Math.min(1, v[k]));
        }
        return v;
    }

    private static int other(double[] v, int i, Other o, boolean enemy) {
        if (!o.present) {
            return i + (enemy ? ENEMY_SIZE : ALLY_SIZE);
        }
        v[i++] = 1;
        v[i++] = o.hp / Math.max(1, o.maxHp);
        v[i++] = o.distance / 2000;
        if (!enemy) {
            return i;
        }
        v[i++] = o.shield / Math.max(1, o.maxHp);
        double d = Math.max(1e-9, Math.hypot(o.dx, o.dy));
        v[i++] = o.dx / d;
        v[i++] = o.dy / d;
        v[i++] = flag(o.crowdControlled);
        v[i++] = flag(o.inAttackReach);
        for (boolean b : o.inAbilityRange) {
            v[i++] = flag(b);
        }
        return i;
    }

    private static double flag(boolean b) {
        return b ? 1 : 0;
    }
}
