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

    public static final int SCHEMA_VERSION = 3;
    public static final int ALLIES = 2;
    public static final String DAMAGE = "dano";
    public static final String DEFENSE = "defesa";
    private static final int SLOTS = ActionSpace.SLOTS.size();
    private static final int ACTIVES = ActionSpace.ACTIVES;

    public static final int SELF_SIZE = 4 + 4 * SLOTS + 8 + 2 * ACTIVES + 2;
    public static final int ENEMY_SIZE = 8 + SLOTS;
    public static final int ALLY_SIZE = 3;
    /** The schema 2 vector (85): what came before the predictions; schema 3 appends to it, so its indexes stay. */
    public static final int V2_SIZE = SELF_SIZE + ActionSpace.ENEMIES * ENEMY_SIZE + ALLIES * ALLY_SIZE + 1;
    /** Predictions of each ability on the current target: hit, recast hit, time to effect, chains, consumes, applies. */
    public static final int PREDICTIONS = 6;
    public static final int SELF3_SIZE = SLOTS * PREDICTIONS + 2 + 6;
    public static final int ENEMY3_SIZE = 12;
    public static final int SIZE = V2_SIZE + SELF3_SIZE + ActionSpace.ENEMIES * ENEMY3_SIZE;

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

        // schema 3: predictions of each ability on the current target (enemy slot 0), from the sheets
        /** 0..1: the target cannot step out of the ability's area before its effect (0 = out of reach, or no wall). */
        public final double[] hitChance = new double[SLOTS];
        /** 0..1: the same for the second part of an open recast (Ornn's elemental back, Renekton's second Dice). */
        public final double[] recastHit = new double[SLOTS];
        /** Seconds until the main effect lands on the target. */
        public final double[] timeToEffect = new double[SLOTS];
        /** It immobilizes and lands as the target's current control ends. */
        public final boolean[] chainsControl = new boolean[SLOTS];
        /** It consumes a mark of the side on the target (Brittle, bolts...) / it applies one (the recast's when open). */
        public final boolean[] consumesMark = new boolean[SLOTS];
        public final boolean[] appliesMark = new boolean[SLOTS];
        /** The same for a basic attack on the target. */
        public boolean attackConsumesMark;
        public boolean attackAppliesMark;
        /** Seconds left overheated (Rumble). */
        public double overheatLeft;
        /** The resource is in its strong zone (Rumble's Danger Zone, Renekton's 50 Fury). */
        public boolean resourceZone;
        /** In an immune dash now (displacements are void). */
        public boolean immuneDash;
        /** Seconds left of the open recast window that closes first (0 = none). */
        public double recastLeft;
        /** Seconds left of its own immobilize (stun, knock-up, displacement). */
        public double controlLeft;
        /** Share of its maximum health lost in the last 2 seconds. */
        public double recentLoss;
        /** Facts of the sheets the rules read (not in the vector): an aimed ability (it can miss); it immobilizes. */
        public final boolean[] aimed = new boolean[SLOTS];
        public final boolean[] immobilizes = new boolean[SLOTS];
        /**
         * Hit chance of the ability's own body on the target (a dash that needs terrain for its main effect still hits
         * whoever is on its way); the same as {@link #hitChance} for the others.
         */
        public final double[] bodyHit = new double[SLOTS];
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

        // schema 3 (enemies)
        /** Seconds left of the deciding side's mark on it (Brittle, bolts, K'Sante's mark, bleed; the longest). */
        public double markLeft;
        /** Marks of the deciding champion consumed on it in this fight. */
        public int marksConsumed;
        public double controlLeft;
        public double slowLeft;
        /** Its strongest slow now (0..1). */
        public double slowPct;
        /** Its resource over its maximum (0 without one). */
        public double resourcePct;
        public double overheatLeft;
        /** A dash or leap of its own is up; Flash; Cleanse. */
        public boolean dashReady;
        public boolean flashReady;
        public boolean cleanseReady;
        public double attackRange;
        /** The deciding champion is in its attack reach. */
        public boolean reachesMe;
    }

    /**
     * Where each input of an older schema's vector is in this one (an explicit index map): schema 2's 85 features are
     * the first 85 of schema 3 (the new ones are appended), so a migrated network sees them where it learned them.
     */
    public static int[] migration(int fromSchema) {
        if (fromSchema == SCHEMA_VERSION) {
            int[] same = new int[SIZE];
            for (int i = 0; i < SIZE; i++) {
                same[i] = i;
            }
            return same;
        }
        if (fromSchema != 2) {
            throw new IllegalArgumentException("no migration from observation schema " + fromSchema);
        }
        int[] map = new int[V2_SIZE];
        for (int i = 0; i < V2_SIZE; i++) {
            map[i] = i;
        }
        return map;
    }

    // created on the first read: most steps of a scripted fight never look
    private Self self;
    private Other[] enemies;
    private Other[] allies;
    private final Consumer<Observation> filler;
    private final Consumer<Observation> predictor;
    private boolean filled;
    private boolean predictedYet;
    private double time;
    private double duration;

    /** @param filler writes the facts into the observation (through the setters), once, when first read */
    public Observation(Consumer<Observation> filler) {
        this(filler, null);
    }

    /**
     * @param predictor writes the predictions and the enemies' schema 3 facts, once, when first asked for
     *                  ({@link #predicted}): they cost more and most decisions of the rules never read them
     */
    public Observation(Consumer<Observation> filler, Consumer<Observation> predictor) {
        this.filler = filler;
        this.predictor = predictor;
    }

    /**
     * Makes sure the predictions ({@link Self#hitChance} and the rest of schema 3's per-ability values) and the
     * enemies' schema 3 facts ({@link Other#markLeft} on) are filled; read them only after this.
     */
    public Observation predicted() {
        fill();
        if (!predictedYet) {
            predictedYet = true;
            if (predictor != null) {
                predictor.accept(this);
            }
        }
        return this;
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
        predicted();
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
        // schema 3, appended: the first V2_SIZE features keep their meaning and index
        for (int k = 0; k < SLOTS; k++) {
            v[i++] = s.hitChance[k];
            v[i++] = s.recastHit[k];
            v[i++] = Math.min(1, s.timeToEffect[k] / 2);
            v[i++] = flag(s.chainsControl[k]);
            v[i++] = flag(s.consumesMark[k]);
            v[i++] = flag(s.appliesMark[k]);
        }
        v[i++] = flag(s.attackConsumesMark);
        v[i++] = flag(s.attackAppliesMark);
        v[i++] = s.overheatLeft / 4;
        v[i++] = flag(s.resourceZone);
        v[i++] = flag(s.immuneDash);
        v[i++] = s.recastLeft / 4;
        v[i++] = s.controlLeft / 2;
        v[i++] = s.recentLoss;
        for (Other e : enemies) {
            if (!e.present) {
                i += ENEMY3_SIZE;
                continue;
            }
            v[i++] = e.markLeft / 4;
            v[i++] = e.marksConsumed / 5.0;
            v[i++] = e.controlLeft / 2;
            v[i++] = e.slowLeft / 2;
            v[i++] = e.slowPct;
            v[i++] = e.resourcePct;
            v[i++] = e.overheatLeft / 4;
            v[i++] = flag(e.dashReady);
            v[i++] = flag(e.flashReady);
            v[i++] = flag(e.cleanseReady);
            v[i++] = e.attackRange / 1000;
            v[i++] = flag(e.reachesMe);
        }
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
