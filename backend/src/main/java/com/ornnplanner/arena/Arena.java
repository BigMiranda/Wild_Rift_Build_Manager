package com.ornnplanner.arena;

import com.ornnplanner.arena.ArenaModel.Action;
import com.ornnplanner.arena.ArenaModel.Combo;
import com.ornnplanner.arena.ArenaModel.ComboStep;
import com.ornnplanner.arena.ArenaModel.Event;
import com.ornnplanner.arena.ArenaModel.FighterStats;
import com.ornnplanner.arena.ArenaModel.Frame;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ArenaModel.Shape;
import com.ornnplanner.arena.ArenaModel.TeamPlan;
import com.ornnplanner.arena.ArenaModel.UnitFrame;
import com.ornnplanner.arena.ai.ActionMask;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Facts;
import com.ornnplanner.arena.ai.Observation;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.arena.ai.ScriptedPolicy;
import com.ornnplanner.arena.fight.AbilityParser;
import com.ornnplanner.arena.fight.CombatCatalog;
import com.ornnplanner.arena.fight.DamageRules;
import com.ornnplanner.arena.fight.FighterBuilder;
import com.ornnplanner.arena.fight.Delay;
import com.ornnplanner.arena.fight.Fighter;
import com.ornnplanner.arena.fight.ItemEffect;
import com.ornnplanner.arena.fight.Ledger;
import com.ornnplanner.engine.Stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Orchestrated matchup on a 2D map (the Howling Abyss as a straight lane between walls).
 *
 * <h2>Time</h2>
 * Steps of {@value #DT}s move the champions, projectiles, dashes and displacements and test their hits (circles against
 * segments, cones and rectangles); everything that happens at a given time (end of a cast, a delayed hit, a damage
 * tick, the end of a crowd control) is a timed event run at that exact second. The run is deterministic: the same
 * scenario always gives the same result, so training can compare thousands of fights.
 *
 * <h2>Orders</h2>
 * Each champion follows the scenario's timeline (abilities, recasts, item actives, spells, moves, focus); an order that
 * cannot be done yet (on cooldown, casting, crowd controlled, out of range) waits until it can, and the wait is logged.
 * When nothing is ordered, it chases its target and attacks, and (if allowed) uses ready abilities in its priority.
 * Its target is the ordered focus, else the one its side's plan picks (when it follows the plan), else its fixed target,
 * else the nearest enemy.
 *
 * <h2>Abilities</h2>
 * From the champion's sheet ({@code kits_<patch>.yml}): line projectiles, areas, cones, channels, dashes, targeted
 * abilities, empowered attacks, self buffs, ground fields, Ornn's elemental, Vayne's Condemn and K'Sante's All Out,
 * each with its crowd control (slow, stun, knock-up, pull, push into walls), Brittle, bleed stacks and marks. Champions
 * without a sheet use the damage read from the game's text on a targeted ability of 550 range.
 *
 * <h2>Damage</h2>
 * Same rules as the quick matchup: raw x (1 + amplifications) x 100 / (100 + resistance after % reduction, % and flat
 * penetration), damage reductions, shields (typed ones only for their damage), health; lifesteal, omnivamp, Grievous
 * Wounds, shield reaver, resistance shred, item on-hit effects, spellblades, burns, auras and actives.
 */
public class Arena {

    public static final double DT = 0.02;
    public static final double FRAME_EVERY = 0.1;
    private static final int ATTACK = 0;
    private static final int ABILITY = 1;
    private static final int ITEM = 2;
    private static final int DOT = 3;
    private static final int REFLECT = 4;

    /** A champion of the scenario as the arena receives it. */
    public static class Input {
        public Fighter f;
        public String build;
        public Map<String, Object> kit;
        /** Champion effects switched by the arena (Noxian Might, Overheat, All Out): stat changes when on. */
        public Map<String, Map<String, Double>> forms = new LinkedHashMap<>();
        public double x;
        public double y;
        public Integer target;
        public boolean autoAbilities = true;
        public List<String> priority = new ArrayList<>(List.of("R", "3", "1", "2"));
        /** Decides when the timeline leaves the champion free (null = the scripted autopilot with {@link #priority}). */
        public Policy policy;
        /** Base attack speed at the fighter's level (attacks / s); bonus % comes from its stats. */
        public double baseAttackSpeed;
        /** Its side's plan (the same object for every champion of the side; null = no plan, each picks its own). */
        public TeamPlan team;
        /** Takes the target the side's plan picks (see {@link ArenaModel.Slot#followTeam}). */
        public boolean followTeam = true;
        /** Item actives and spells with their cooldown (s). */
        public Map<String, Double> activeCooldowns = new LinkedHashMap<>();
        /** Conditional effects of the build (items, shop lines, runes, champion) and when they count. */
        public List<FighterBuilder.Triggered> triggers = new ArrayList<>();
        /** The champion's combos a policy may run (ActionSpace.COMBO + c, best score first; at most COMBOS). */
        public List<Combo> combos = new ArrayList<>();
        /** A combo run as soon as the fight starts, without a policy (the bench); null = none. */
        public Combo script;
        /** A ragdoll (the lab): how it moves and the effects it applies; null = a champion. */
        public ArenaModel.Dummy dummy;
        /** Share of its health it starts with (null = full): drills of training, the lab. */
        public Double startHealth;
        /** Drills: seconds left of cooldowns at the start (slot or active), its resource, a knock-up on it. */
        public Map<String, Double> startCooldowns = new LinkedHashMap<>();
        public Double startResource;
        public Double startAirborne;
        /** Drills: the fighter (index) whose own mark is on this one at the start (Brittle, bolts, marks, bleed). */
        public Integer markedBy;

        /**
         * A shallow copy (same fighter, sheet and build effects, which a run only reads): to change the start or the
         * policy of one fight while other fights run on the original at the same time.
         */
        public Input copy() {
            Input c = new Input();
            c.f = f;
            c.build = build;
            c.kit = kit;
            c.forms = forms;
            c.x = x;
            c.y = y;
            c.target = target;
            c.autoAbilities = autoAbilities;
            c.priority = priority;
            c.policy = policy;
            c.team = team;
            c.followTeam = followTeam;
            c.baseAttackSpeed = baseAttackSpeed;
            c.activeCooldowns = activeCooldowns;
            c.triggers = triggers;
            c.combos = combos;
            c.script = script;
            c.dummy = dummy;
            c.startHealth = startHealth;
            c.startCooldowns = startCooldowns;
            c.startResource = startResource;
            c.startAirborne = startAirborne;
            c.markedBy = markedBy;
            return c;
        }
    }

    // ------------------------------------------------------------------ runtime

    private final class U {
        final int idx;
        final Input in;
        final Fighter f;
        final Policy policy;
        /** The last action of a policy that decides at intervals, and when it decides again. */
        int lastAction = -1;
        double nextDecision;
        /** Last action logged (learned policies). */
        int loggedAction = -1;
        /** Item actives and spells a policy can use, in the order of the build (see ActionSpace.ACTIVE). */
        final List<String> actives = new ArrayList<>();
        final FighterStats r = new FighterStats();
        double x;
        double y;
        double facing;
        double hp;
        double maxHp;
        boolean dead;
        double busyUntil;
        String busyState = "idle";
        double stunUntil = -1;
        double airUntil = -1;
        double stasisUntil = -1;
        double invisibleUntil = -1;
        /**
         * Displacement immunity of a channel (Ornn's Bellows Breath): pulls, pushes and knock-ups are void until then,
         * and stuns follow {@link #immuneCc} (see {@link Arena#ccRule}).
         */
        double immuneUntil = -1;
        String immuneCc = CC_IGNORE;
        double nextAttack;
        Double moveX;
        Double moveY;
        boolean hold;
        U focus;
        /** The plan's target picked from where this champion stands (plans that do not share), and when to pick again. */
        U pick;
        double pickAt = -1;
        /** Abilities that immobilize (stun, knock-up, pull, push), by slot: what a chained-control plan holds back. */
        final Map<String, Boolean> controls = new LinkedHashMap<>();
        Dash dash;
        Forced forced;
        double combatSince = -1;
        boolean stasisUsed;
        // cooldowns: ready time by slot / item / spell name
        Map<String, Double> ready = new LinkedHashMap<>();
        /** Last use of each ability / active / spell (frames only), where what it does is written. */
        final Map<String, ArenaModel.Use> lastUse = new java.util.HashMap<>();
        Map<String, Integer> charges = new LinkedHashMap<>();
        Map<String, Double> chargeAt = new LinkedHashMap<>();
        // shields by source key, any / magic / physical; with expiry for timed ones
        Map<String, Double> shield = new LinkedHashMap<>();
        Map<String, Double> shieldMagic = new LinkedHashMap<>();
        Map<String, Double> shieldPhysical = new LinkedHashMap<>();
        Map<String, Double> shieldUntil = new LinkedHashMap<>();
        double healPool;
        double healRate;
        // status changes
        List<double[]> slows = new ArrayList<>();              // {pct, until}
        List<double[]> speedUps = new ArrayList<>();           // {pct, until}
        Map<String, double[]> buffs = new LinkedHashMap<>();   // name -> {value, until} (AS %, omnivamp, AD, range...)
        Map<String, Double> formUntil = new LinkedHashMap<>();
        boolean[] delayOn;
        int hitsDealt;
        int abilityHitsTaken;
        // empowered next attack
        String empowered;
        double empoweredUntil;
        double empoweredRange;
        boolean bladeArmed;
        double bladeReady;
        int attacks;
        /** Cooldowns of item strikes and of sources (see {@link Arena#activate}). */
        Map<String, Double> strikeReady = new LinkedHashMap<>();
        /** Instant at which a source's cooldown started, and at which it was last counted (its parts go off together). */
        Map<String, Double> sourceFiredAt = new LinkedHashMap<>();
        Map<String, Double> sourceCountedAt = new LinkedHashMap<>();
        Map<String, Double> auraNext = new LinkedHashMap<>();
        // resources
        double resource;
        double overheatedUntil = -1;
        // debuffs on this unit
        double gw;
        double gwUntil = -1;
        String gwSource;
        double shieldCutPct;
        double shieldCutUntil = -1;
        String shieldCutSource;
        double[] shred = new double[2];
        double[] shredUntil = {-1, -1};
        String[] shredSource = new String[2];
        double mark;
        double markUntil = -1;
        String markSource;
        double exhaust;
        double exhaustUntil = -1;
        double brittleUntil = -1;
        U brittleBy;
        double damageReduction;
        double damageReductionUntil = -1;
        /** Stacks this unit put on each enemy (Darius' bleed, Vayne's bolts, K'Sante's marks): {count, until}. */
        Map<U, double[]> stacks = new LinkedHashMap<>();
        Map<U, double[]> bolts = new LinkedHashMap<>();
        Map<U, Double> ksMarks = new LinkedHashMap<>();
        double nextStackTick;
        // recast windows: slot -> {from, until}
        Map<String, double[]> recast = new LinkedHashMap<>();
        Elemental elemental;
        List<Action> orders = new ArrayList<>();
        // planner effects: triggers, decaying shields, flat damage cut (Bone Plating), who hit it (kills)
        final List<T> trigs = new ArrayList<>();
        final Map<String, double[]> shieldDecay = new LinkedHashMap<>();
        double flatCut;
        int flatCutLeft;
        double flatCutUntil = -1;
        String flatCutSource;
        final Map<U, Double> lastHitBy = new LinkedHashMap<>();
        double critTaken;
        Set<String> manualItems = new HashSet<>();
        // runes
        final Map<U, List<Double>> runeHits = new LinkedHashMap<>();
        final Map<U, Integer> seqMarks = new LinkedHashMap<>();
        double aeryBack;
        double firstStrikeUntil = -1;
        double dashedAt = -100;
        int attackChain;
        U lastAttackTarget;
        int cometHits;
        String lastCast;
        double nextRegen;
        // structured events (counters only: they never change the fight)
        /** Expiry of the Brittle being followed on this unit (-1 = none), to tell expired from consumed. */
        double brittleCounted = -1;
        /** Expiry of the stacks / bolts / marks this unit put on each enemy, being followed. */
        final Map<U, Double> stackCounted = new LinkedHashMap<>();
        final Map<U, Double> boltCounted = new LinkedHashMap<>();
        final Map<U, Double> ksCounted = new LinkedHashMap<>();
        /** Who applied the immobilize on this unit last, and the last instant it was held. */
        U ccBy;
        double ccHeldAt = -100;
        /** The open cast and recast of each slot (hit yet?), until the next one or the end of the fight. */
        final Map<String, Cast> casts = new LinkedHashMap<>();
        final Map<String, Cast> recasts = new LinkedHashMap<>();
        /** Recast windows followed (slot -> the window), the ones used, and why the last try failed. */
        final Map<String, double[]> recastWatch = new LinkedHashMap<>();
        final Set<double[]> recastUsed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final Map<String, String> recastTry = new LinkedHashMap<>();
        /** Marks of this unit consumed on each enemy (what the observation shows per target). */
        final Map<U, Integer> consumedOn = new LinkedHashMap<>();
        /** Health every 0.1s over the last 2s (oldest first): the recent loss a policy sees. */
        final java.util.ArrayDeque<double[]> hpHistory = new java.util.ArrayDeque<>();
        double nextHpSample;
        /** Cleanse: stuns and slows are void until then. */
        double cleanseUntil = -1;
        /** It reached its side's safe zone alive: out of the fight. */
        boolean escaped;
        /** Its dashes and leaps (slot -> kind), read once from the sheet. */
        Map<String, String> mobility;
        /** The combo it runs now (its state lives here, never in the policy), and whether the scripted one started. */
        ComboRun combo;
        boolean scriptStarted;
        /** A ragdoll: its next turn and way (side to side), its next on-hit effects. */
        double dummyTurnAt = 1.5;
        int dummyDir = 1;
        double dummyHitAt;

        U(int idx, Input in) {
            this.idx = idx;
            this.in = in;
            this.f = in.f;
            this.policy = in.policy != null ? in.policy : new ScriptedPolicy(in.priority);
            for (String name : in.activeCooldowns.keySet()) {
                if (actives.size() < ActionSpace.ACTIVES && (f.items.containsKey(name) || f.spells.contains(name))) {
                    actives.add(name);
                }
            }
            delayOn = new boolean[f.delays.size()];
        }

        int team() {
            return f.team;
        }

        boolean targetable() {
            return !dead && !escaped && now >= stasisUntil;
        }

        boolean visible() {
            return now >= invisibleUntil;
        }

        boolean canAct() {
            return !dead && now >= stunUntil && now >= airUntil && now >= stasisUntil && forced == null;
        }

        double stat(String s) {
            double v = f.stat(s);
            for (int i = 0; i < delayOn.length; i++) {
                if (!delayOn[i]) {
                    v -= f.delays.get(i).amounts.getOrDefault(s, 0.0);
                }
            }
            for (Map.Entry<String, Double> e : formUntil.entrySet()) {
                if (now < e.getValue()) {
                    v += in.forms.getOrDefault(e.getKey(), Map.of()).getOrDefault(s, 0.0);
                }
            }
            for (T t : trigs) {
                if (t.on()) {
                    v += t.d.delta.getOrDefault(s, 0.0) * t.factor;
                }
            }
            if (Stats.ATTACK_DAMAGE.equals(s)) {
                v += buff("dda") + buff("hubris");
            }
            if ("% Attack Speed".equals(s)) {
                v += buff("vel_ataque") * 100;
            }
            if ("% Omnivamp".equals(s)) {
                v += buff("vampirismo") * 100;
            }
            return v;
        }

        double base(String s) {
            return f.base.getOrDefault(s, 0.0);
        }

        double bonus(String s) {
            return stat(s) - base(s);
        }

        double buff(String name) {
            double[] b = buffs.get(name);
            return b != null && now < b[1] ? b[0] : 0;
        }

        double radius() {
            return num(kit("raio"), 80);
        }

        Object kit(String key) {
            return in.kit == null ? null : in.kit.get(key);
        }

        double attackRange() {
            return num(kit("alcance"), 175) + buff("alcance") + (empowered != null && now < empoweredUntil ? empoweredRange : 0);
        }

        double attackSpeed() {
            double as = in.baseAttackSpeed * (1 + stat("% Attack Speed") / 100) * (1 - attackSlow(this));
            return Math.max(0.2, Math.min(3.0, as));
        }

        /** The strongest slow on now (0..1) and when it ends (-1 = none). */
        double slowPct() {
            double slow = 0;
            for (double[] s : slows) {
                if (now < s[1]) {
                    slow = Math.max(slow, s[0]);
                }
            }
            return slow;
        }

        double slowUntil() {
            double pct = slowPct();
            double until = -1;
            for (double[] s : slows) {
                if (now < s[1] && s[0] >= pct - 1e-9) {
                    until = Math.max(until, s[1]);
                }
            }
            return until;
        }

        double moveSpeed() {
            double ms = stat(Stats.MOVE_SPEED) * (1 + stat("% Move Speed") / 100);
            double up = 0;
            for (double[] s : speedUps) {
                if (now < s[1]) {
                    up += s[0];
                }
            }
            double slow = 0;
            for (double[] s : slows) {
                if (now < s[1]) {
                    slow = Math.max(slow, s[0]);
                }
            }
            return Math.max(0, ms * (1 + up) * (1 - slow));
        }

        double shieldLeft() {
            return sum(shield) + sum(shieldMagic) + sum(shieldPhysical);
        }

        int rank(String slot) {
            int i = "R".equals(slot) ? 3 : "1".equals(slot) ? 0 : "2".equals(slot) ? 1 : "3".equals(slot) ? 2 : -1;
            return i < 0 ? 0 : f.ranks[i];
        }
    }

    /** A combo being run: which, on whom, its step and since when. */
    private static final class ComboRun {
        final Combo spec;
        final int action;
        final U target;
        final double start;
        int step;
        double stepStart;
        boolean waited;
        final ArenaModel.ComboReport report = new ArenaModel.ComboReport();

        ComboRun(Combo spec, int action, U target, double start) {
            this.spec = spec;
            this.action = action;
            this.target = target;
            this.start = start;
            this.stepStart = start;
        }
    }

    /** A cast of an ability or of its recast: whether it hit a champion (structured events). */
    private static final class Cast {
        final String slot;
        final boolean recast;
        boolean hit;

        Cast(String slot, boolean recast) {
            this.slot = slot;
            this.recast = recast;
        }
    }

    /** A dash of a champion (its ability or a recast). */
    private static final class Dash {
        /** The second dash of a recast (Renekton's Dice): its hits count for the recast. */
        boolean recast;
        /** {@code imune_deslocamento}: pulls, pushes and knock-ups on the champion are void while it lasts. */
        boolean immune;
        /** What a stun does meanwhile ({@code cc_durante_avanco}): continua_depois (default) or interrompe. */
        String ccMode = CC_AFTER;
        /** A stun held until the dash ends (continua_depois): when it would end, and who applied it. */
        double heldStunUntil = -1;
        U heldStunBy;
        double tx;
        double ty;
        double speed;
        double hitRadius;
        Set<U> hit = new HashSet<>();
        Runnable onEnd;
        java.util.function.Consumer<U> onHit;
        Runnable onWall;
    }

    /** Forced movement of a champion (pull, push, knock-back): crowd control while it lasts. */
    private static final class Forced {
        double tx;
        double ty;
        double speed;
        Runnable onWall;
        Runnable onEnd;
    }

    /** Line projectile. */
    private final class Projectile {
        String id;
        String label;
        U owner;
        double x;
        double y;
        double dx;
        double dy;
        double speed;
        double left;
        double width;
        boolean pierce;
        Double stopAfterChampion;
        U only;                     // homing to one target (attacks, Condemn)
        Set<U> hit = new HashSet<>();
        java.util.function.Consumer<U> onHit;
        Runnable onEnd;
    }

    /** Area that lasts on the ground (Rumble's ultimate) or a terrain pillar (Ornn's Volcanic Rupture). */
    private final class Field {
        String id;
        String label;
        U owner;
        String kind;                // rect | circle
        double x;
        double y;
        double angle;
        double length;
        double width;
        double radius;
        double from;
        double until;
        double interval;
        double nextTick;
        boolean terrain;
        java.util.function.Consumer<U> onTick;
    }

    /** Ornn's lava elemental. */
    private final class Elemental {
        /** Stable id for the frames (numbered in the order they appear). */
        final long id = elementalIds++;
        U owner;
        double x;
        double y;
        double dx;
        double dy;
        double speed;
        double accel;
        double maxSpeed;
        double left;
        double width;
        boolean redirected;
        int champsHit;
        Set<U> hit = new HashSet<>();
        double recastFrom;
        Map<String, Object> spec;
        int rank;
    }

    private static final class Timed {
        final double at;
        final long seq;
        final Runnable r;

        Timed(double at, long seq, Runnable r) {
            this.at = at;
            this.seq = seq;
            this.r = r;
        }
    }

    private final CombatCatalog catalog;
    private final Map<String, Object> map;
    private final List<U> units = new ArrayList<>();
    /** Each side's plan and shared target (blue, red). */
    private final Side[] sides = {new Side(), new Side()};
    private final List<Projectile> projectiles = new ArrayList<>();
    private final List<Field> fields = new ArrayList<>();
    private final List<Elemental> elementals = new ArrayList<>();
    private final List<Shape> flashes = new ArrayList<>();
    private final PriorityQueue<Timed> timed = new PriorityQueue<>(
            Comparator.<Timed>comparingDouble(t -> t.at).thenComparingLong(t -> t.seq));
    private final Map<String, Ledger> ledger = new LinkedHashMap<>();
    private final Result result = new Result();
    private long seq;
    private long ids;
    private long elementalIds;
    private double now;
    private double halfWidth;
    private double length;
    /** Time limit of the run (s). */
    private double duration;

    /** Records the frames for the screen (training turns it off: only the totals matter). */
    private boolean recordFrames = true;
    /** A side's safe zone (null = none), and its tower's next shot (every 0.5s). */
    private ArenaModel.SafeZone safeZone;
    private double nextTowerTick = 0.5;

    public Arena(CombatCatalog catalog, Map<String, Object> map) {
        this.catalog = catalog;
        this.map = map;
    }

    public Arena withoutFrames() {
        recordFrames = false;
        return this;
    }

    /** Also writes what each item and rune effect is worth as the fight starts ({@link FighterStats#effectValues}). */
    public Arena withEffectValues() {
        describeEffects = true;
        return this;
    }

    private boolean describeEffects;

    /** With a side's safe zone (null = none): see {@link ArenaModel.SafeZone}. */
    public Arena withSafeZone(ArenaModel.SafeZone zone) {
        this.safeZone = zone;
        return this;
    }

    // ------------------------------------------------------------------ run

    public Result run(List<Input> inputs, List<Action> actions, double duration) {
        this.duration = duration;
        length = num(map.get("comprimento"), 10000);
        halfWidth = num(map.get("largura"), 1100) / 2;
        result.mapLength = length;
        result.mapWidth = halfWidth * 2;
        result.frameEvery = FRAME_EVERY;
        for (int i = 0; i < inputs.size(); i++) {
            Input in = inputs.get(i);
            in.f.index = i;
            Set<String> manual = new HashSet<>();
            for (Action a : actions) {
                if (a.fighter == i && "item".equals(a.type) && a.name != null) {
                    manual.add(a.name);   // an active used by hand gives its shield then, not from the start
                }
            }
            U u = start(i, in, manual);
            u.manualItems = manual;
            units.add(u);
            if (in.team != null && sides[u.team()].plan == null) {
                sides[u.team()].plan = in.team;
            }
        }
        for (U u : units) {
            startTriggers(u);
        }
        for (U u : units) {
            drill(u);
            for (String st : List.of(Stats.MAX_HEALTH, Stats.ARMOR, Stats.MAGIC_RESIST, Stats.ATTACK_DAMAGE, Stats.ABILITY_POWER,
                    "% Armor Penetration", "Armor Penetration", "% Magic Penetration", "Magic Penetration")) {
                u.r.startStats.put(st, Stats.MAX_HEALTH.equals(st) ? u.maxHp : u.stat(st));
            }
            u.r.startShields.put("any", sum(u.shield));
            u.r.startShields.put("magico", sum(u.shieldMagic));
            u.r.startShields.put("fisico", sum(u.shieldPhysical));
            if (describeEffects) {
                describeEffects(u);
            }
        }
        List<Action> sorted = new ArrayList<>(actions);
        sorted.sort(Comparator.comparingDouble(a -> a.time));
        for (Action a : sorted) {
            if (a.fighter >= 0 && a.fighter < units.size()) {
                units.get(a.fighter).orders.add(a);
            }
        }
        // Shields given to allies are split among them at the start.
        for (U u : units) {
            List<U> allies = allies(u);
            if (!allies.isEmpty()) {
                u.f.allyShieldSources.forEach((src, amount) -> {
                    for (U a : allies) {
                        a.shield.merge(key(u, src), amount / allies.size(), Double::sum);
                    }
                });
            }
        }
        double nextFrame = 0;
        for (now = 0; now <= duration + 1e-9; now += DT) {
            runTimed();
            for (U u : units) {
                if (!u.dead) {
                    activate(u);
                    tickTriggers(u);
                    regen(u);
                }
            }
            for (U u : units) {
                if (!u.dead) {
                    step(u);
                }
            }
            moveProjectiles();
            moveElementals();
            tickFields();
            tickPeriodic();
            tickSafeZone();
            trackEvents();
            if (now >= nextFrame - 1e-9) {
                frame();
                nextFrame += FRAME_EVERY;
            }
            int a = alive(0);
            int b = alive(1);
            if (a == 0 || b == 0) {
                result.winner = a == 0 && b == 0 ? -1 : a == 0 ? 1 : 0;
                frame();
                break;
            }
            if (safeZone != null && (fighting(0) == 0 || fighting(1) == 0)) {
                frame();   // a side has nobody left in the fight: those alive escaped (no winner)
                break;
            }
        }
        result.duration = Math.min(now, duration);
        for (U u : units) {
            closeCasts(u);
            if (u.combo != null) {
                endCombo(u, "aborted", u.combo.target.dead ? "alvo morreu" : "fim da luta");
            }
        }
        for (U u : units) {
            u.r.eventSummary = ArenaEvents.summary(u.r.events);
            for (String slot : ActionSpace.SLOTS) {
                if (u.rank(slot) > 0 && ability(u, slot) != null) {
                    u.r.cooldownsLeft.put(slot, round3(Math.max(0, u.ready.getOrDefault(slot, 0.0) - now)));
                }
            }
            for (Action a : u.orders) {
                result.skipped.add(new Event(a.time, u.idx, describe(a) + ": não foi possível até o fim da luta"));
            }
            u.r.health = Math.max(0, u.hp);
            result.fighters.add(u.r);
        }
        if (safeZone != null) {
            double cx = safeZone.team == 0 ? safeZone.depth / 2 : length - safeZone.depth / 2;
            result.terrain.add(rect("zona-segura", "Zona segura" + (safeZone.damagePerSecond > 0 ? " (torre)" : ""),
                    safeZone.team, cx, 0, safeZone.depth, halfWidth * 2, 0));
        }
        result.terrain.add(rect("wall-top", "Parede", -1, length / 2, halfWidth + 50, length, 100, 0));
        result.terrain.add(rect("wall-bottom", "Parede", -1, length / 2, -halfWidth - 50, length, 100, 0));
        result.ledger.addAll(ledger.values());
        return result;
    }

    private U start(int i, Input in, Set<String> manualItems) {
        U u = new U(i, in);
        u.x = in.x;
        u.y = in.y;
        u.facing = in.f.team == 0 ? 0 : Math.PI;
        u.maxHp = Math.max(1, u.stat(Stats.MAX_HEALTH));
        u.hp = in.startHealth == null ? u.maxHp : Math.max(1, u.maxHp * Math.min(1, in.startHealth));
        in.f.shieldSources.forEach((src, v) -> {
            if (!manualItems.contains(src)) {
                u.shield.merge(key(u, src), v, Double::sum);
            }
        });
        in.f.magicShieldSources.forEach((src, v) -> u.shieldMagic.merge(key(u, src), v, Double::sum));
        in.f.physicalShieldSources.forEach((src, v) -> u.shieldPhysical.merge(key(u, src), v, Double::sum));
        u.healPool = in.f.healSources.values().stream().mapToDouble(Double::doubleValue).sum();
        u.healRate = u.healPool / 5;
        Map<String, Object> res = map(u.kit("recurso"));
        if (res != null && "furia".equals(res.get("tipo"))) {
            u.resource = 0;
        }
        FighterStats r = u.r;
        r.index = i;
        r.team = in.f.team;
        r.name = in.f.name;
        r.champion = in.f.champion;
        r.build = in.build;
        r.level = in.f.level;
        r.minute = in.f.minute;
        r.maxHealth = u.maxHp;
        r.actives.putAll(in.activeCooldowns);
        in.f.items.forEach((name, id) -> {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("name", name);
            it.put("id", id);
            r.items.add(it);
        });
        r.runes.addAll(in.f.runes);
        r.spells.addAll(in.f.spells);
        if (in.kit == null) {
            r.notes.add("sem ficha de habilidades: habilidades com alvo a 550 de alcance e o dano lido do texto do jogo");
        }
        for (String slot : List.of("1", "2", "3", "R")) {
            Map<String, Object> m = new LinkedHashMap<>();
            Map<String, Object> ab = ability(u, slot);
            m.put("slot", slot);
            m.put("name", ab == null ? slot : ab.get("nome"));
            m.put("rank", u.rank(slot));
            if (ab != null) {
                m.put("shape", ab.get("forma"));
                for (String k : List.of("alcance", "raio", "largura", "angulo", "conjuracao", "velocidade", "fontes")) {
                    if (ab.get(k) != null) {
                        m.put(k, ab.get(k));
                    }
                }
                if (u.rank(slot) > 0) {
                    m.put("cooldown", cooldown(u, slot, ab));
                }
            }
            r.abilities.add(m);
        }
        return u;
    }

    private void runTimed() {
        while (!timed.isEmpty() && timed.peek().at <= now + 1e-9) {
            timed.poll().r.run();
        }
    }

    private void at(double t, Runnable r) {
        timed.add(new Timed(t, seq++, r));
    }

    // ------------------------------------------------------------------ a champion's step

    private void step(U u) {
        if (u.escaped) {
            return;
        }
        if (u.combo != null && !u.canAct()) {
            endCombo(u, "aborted", "em controle");   // stunned, knocked up, displaced: the combo is broken
        }
        if (u.forced != null) {
            moveForced(u);
            return;
        }
        if (u.dash != null) {
            moveDash(u);
            return;
        }
        if (!u.canAct() && now < u.stunUntil && now >= u.airUntil && u.forced == null && now >= u.stasisUntil
                && !u.dead && !(u.policy instanceof ScriptedPolicy) && spellReady(u, "Purificar")) {
            stunnedTurn(u);   // a stun (only) keeps it from acting: it may still Cleanse
        }
        if (!u.canAct() || now < u.busyUntil) {
            return;
        }
        u.busyState = "idle";
        // The timeline: the first order due that can be done now.
        boolean acted = false;
        while (!u.orders.isEmpty() && u.orders.get(0).time <= now + 1e-9) {
            Action a = u.orders.get(0);
            String outcome = order(u, a);
            if ("done".equals(outcome)) {
                u.orders.remove(0);
                if (now - a.time > 0.05) {
                    event(u, "cumpriu a ordem de " + describe(a) + " depois de esperar " + fmt(now - a.time) + "s");
                }
                acted = true;
                if (now < u.busyUntil || u.dash != null) {
                    return;
                }
                continue;
            }
            if ("drop".equals(outcome)) {
                u.orders.remove(0);
                continue;
            }
            if ("range".equals(outcome)) {
                u.busyState = "move";
                return;   // walking into range of the ordered ability
            }
            break;   // waits for a cooldown: meanwhile it keeps fighting
        }
        if (acted && u.combo != null) {
            endCombo(u, "aborted", "ordem da linha do tempo");
        }
        if (u.in.dummy != null && !acted && dummyStep(u)) {
            return;
        }
        if (u.in.script != null && !u.scriptStarted && u.combo == null && !acted) {
            U t = target(u);
            if (t != null) {
                u.scriptStarted = true;
                u.combo = newRun(u, u.in.script, -1, t);
            }
        }
        if (u.combo != null) {
            Turn turn = new Turn(u, false);
            if (u.combo.action >= 0) {
                // the policy may only break off to dodge or Flash away
                int a = decide(turn, true);
                if (a == ActionSpace.DODGE || a == ActionSpace.FLASH_AWAY) {
                    endCombo(u, "aborted", "interrompido: " + ActionSpace.describe(a));
                    if (a == ActionSpace.DODGE) {
                        dodge(u, turn.threat());
                    } else if ("done".equals(instant(turn, a)) && recordFrames) {
                        result.policyOrders.add(asOrder(turn, a));
                    }
                    return;
                }
            }
            runCombo(u, turn);
            return;
        }
        // Free: the policy decides. A cast that fails is masked and it decides again; a cast that leaves the champion
        // free closes the casts for this step and it decides again (chase, attack or stay).
        Turn turn = new Turn(u, acted);
        boolean first = true;
        while (true) {
            int a = decide(turn, first);
            first = false;
            if (ActionSpace.isInstant(a)) {
                if (!turn.valid(a)) {
                    throw new IllegalStateException(u.f.champion + ": invalid action, " + ActionSpace.describe(a));
                }
                if ("done".equals(instant(turn, a))) {
                    if (recordFrames && !(u.policy instanceof ScriptedPolicy)) {
                        result.policyOrders.add(asOrder(turn, a));
                    }
                    if (now < u.busyUntil || u.dash != null) {
                        return;
                    }
                    turn.castsOver = true;
                } else {
                    turn.block(a);
                }
                continue;
            }
            if (ActionSpace.isCombo(a) && turn.valid(a)) {
                u.combo = newRun(u, u.in.combos.get(a - ActionSpace.COMBO), a, turn.target);
                runCombo(u, turn);
                return;
            }
            if (u.hold) {
                return;
            }
            if (u.moveX != null) {
                if (moveTo(u, u.moveX, u.moveY, u.moveSpeed() * DT)) {
                    u.moveX = null;
                    u.moveY = null;
                }
                u.busyState = "move";
                return;
            }
            if (a != ActionSpace.IDLE && turn.valid(a)) {
                if (ActionSpace.isAttack(a)) {
                    chase(u, turn.enemy(ActionSpace.enemyOf(a)));
                } else if (ActionSpace.isKite(a)) {
                    kite(u, turn.enemy(ActionSpace.enemyOf(a)));
                } else if (a == ActionSpace.RETREAT) {
                    retreat(u);
                } else if (a == ActionSpace.DODGE) {
                    dodge(u, turn.threat());
                }
            }
            return;
        }
    }

    /** Stunned with Cleanse up: the policy is asked with only Cleanse valid; anything else leaves it stunned. */
    private void stunnedTurn(U u) {
        int cleanse = u.actives.indexOf("Purificar");
        if (cleanse < 0) {
            return;
        }
        int action = ActionSpace.active(cleanse);
        Turn turn = new Turn(u, false);
        turn.only = action;
        if (u.policy.decide(turn.obs, turn).action == action && "done".equals(useSpellNimbus(u, "Purificar", null, null, null))) {
            if (recordFrames) {
                result.policyOrders.add(asOrder(turn, action));
            }
        }
    }

    /**
     * The policy's action: the last one again while its interval runs and it is still valid (first decision of the
     * step only), else a new decision. A learned policy's change of mind is logged with its best scores.
     */
    private int decide(Turn turn, boolean first) {
        U u = turn.u;
        double interval = u.policy.interval();
        if (first && interval > 0 && u.lastAction >= 0 && now < u.nextDecision - 1e-9 && turn.valid(u.lastAction)) {
            return u.lastAction;
        }
        Policy.Decision d = u.policy.decide(turn.obs, turn);
        if (d.tag != null) {
            count(u, "diretriz:" + d.tag);
        }
        if (interval > 0) {
            u.lastAction = d.action;
            u.nextDecision = now + interval;
        }
        if (recordFrames && d.scores != null && d.action != u.loggedAction) {
            u.loggedAction = d.action;
            Integer[] order = new Integer[d.scores.length];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            java.util.Arrays.sort(order, (x, y) -> Double.compare(d.scores[y], d.scores[x]));
            StringBuilder why = new StringBuilder();
            int n = 0;
            for (int i = 0; i < order.length && n < 3; i++) {
                if (turn.valid(order[i])) {
                    why.append(n++ == 0 ? "" : " · ").append(ActionSpace.describe(order[i])).append(' ')
                            .append(fmt(d.scores[order[i]]));
                }
            }
            result.decisions.add(new Event(Math.round(now * 1000) / 1000.0, u.idx,
                    u.f.name + ": " + ActionSpace.describe(d.action) + " (" + why + ")"));
        }
        return d.action;
    }

    /** A done instant action as an order of the timeline (what "turn into a timeline" gives back). */
    private Action asOrder(Turn turn, int a) {
        U u = turn.u;
        U t = turn.enemy(ActionSpace.enemyOf(a));
        Action o = new Action();
        o.fighter = u.idx;
        o.time = Math.round(now * 100) / 100.0;
        if (ActionSpace.isCast(a) || ActionSpace.isRecast(a)) {
            o.type = ActionSpace.isCast(a) ? "ability" : "recast";
            o.slot = ActionSpace.SLOTS.get(ActionSpace.slotOf(a));
            o.target = ActionSpace.isCast(a) && t != null ? t.idx : null;
        } else if (a == ActionSpace.FLASH_AWAY) {
            o.type = "spell";
            o.name = "Flash";
            o.x = (double) Math.round(u.x);   // where the Flash landed
            o.y = (double) Math.round(u.y);
        } else {
            o.name = u.actives.get(a - ActionSpace.ACTIVE);
            o.type = u.f.spells.contains(o.name) ? "spell" : "item";
            o.target = t != null ? t.idx : null;
        }
        return o;
    }

    /** A cast, recast, item active or spell chosen by the policy: done, wait or drop, like an order. */
    private String instant(Turn turn, int a) {
        U u = turn.u;
        U t = turn.enemy(ActionSpace.enemyOf(a));
        if (ActionSpace.isCast(a)) {
            return castAbility(u, ActionSpace.SLOTS.get(ActionSpace.slotOf(a)), t, t.x, t.y, null);
        }
        if (ActionSpace.isRecast(a)) {
            double tx = t != null ? t.x : u.x + Math.cos(u.facing) * 300;
            double ty = t != null ? t.y : u.y + Math.sin(u.facing) * 300;
            return recast(u, ActionSpace.SLOTS.get(ActionSpace.slotOf(a)), t, tx, ty);
        }
        if (a == ActionSpace.FLASH_AWAY) {
            double[] away = away(u);
            return useSpellNimbus(u, "Flash", null, u.x + away[0] * 400, u.y + away[1] * 400);
        }
        String name = u.actives.get(a - ActionSpace.ACTIVE);
        return u.f.spells.contains(name) ? useSpellNimbus(u, name, t, null, null) : useItem(u, name, t);
    }

    /** Unit vector away from the visible enemies' center (along the lane when that points into a wall). */
    private double[] away(U u) {
        double cx = 0;
        double cy = 0;
        int n = 0;
        for (U e : units) {
            if (e.team() != u.team() && e.targetable() && e.visible()) {
                cx += e.x;
                cy += e.y;
                n++;
            }
        }
        double ax = n == 0 ? 0 : u.x - cx / n;
        double ay = n == 0 ? 0 : u.y - cy / n;
        double d = Math.hypot(ax, ay);
        if (d < 1e-6) {
            return new double[] {u.team() == 0 ? -1 : 1, 0};
        }
        ax /= d;
        ay /= d;
        if (Math.abs(u.y) >= halfWidth - u.radius() - 5 && ay * u.y > 0) {
            // against the wall: run along it instead
            return new double[] {Math.abs(ax) < 1e-6 ? (u.team() == 0 ? -1 : 1) : Math.signum(ax), 0};
        }
        return new double[] {ax, ay};
    }

    private void retreat(U u) {
        double[] a = away(u);
        moveTo(u, u.x + a[0] * 100, u.y + a[1] * 100, u.moveSpeed() * DT);
        u.busyState = "move";
    }

    /**
     * Attacks when the attack is up and the target cannot reach the champion during the wind-up; otherwise keeps just
     * inside its own reach, stepping back when closer. Against a target that outranges it, it just chases.
     */
    private void kite(U u, U target) {
        double reach = u.attackRange() + u.radius() + target.radius();
        double d = dist(u.x, u.y, target.x, target.y);
        double windup = num(u.kit("espera_ataque"), 0.25) / u.attackSpeed();
        double safe = target.attackRange() + u.radius() + target.radius() + target.moveSpeed() * windup + 20;
        boolean up = now >= u.nextAttack - 1e-9;
        // not faster than the target: it never gets out of reach, so it attacks whenever it can (orb-walking)
        boolean outpaces = u.moveSpeed() > target.moveSpeed() + 10;
        if (d > reach || !target.visible() || safe >= reach - 60 || up && (d >= safe || !outpaces)) {
            chase(u, target);
            return;
        }
        if (d < reach - 60) {
            double bx = clampX(u.x + (u.x - target.x) / Math.max(1e-6, d) * 100);
            double by = clampY(u.y + (u.y - target.y) / Math.max(1e-6, d) * 100);
            if (dist(u.x, u.y, bx, by) < 50) {
                chase(u, target);   // cornered: no room to step back, so it fights
                return;
            }
            moveTo(u, bx, by, u.moveSpeed() * DT);
            u.busyState = "move";
        }
        u.facing = Math.atan2(target.y - u.y, target.x - u.x);
    }

    /** Side step out of the threat's path, to the side the champion already is on (the other one at a wall). */
    private void dodge(U u, double[] threat) {
        double nx = -threat[1];
        double ny = threat[0];
        double side = (u.x - threat[2]) * nx + (u.y - threat[3]) * ny >= 0 ? 1 : -1;
        if (Math.abs(u.y + ny * side * 60) > halfWidth - u.radius()) {
            side = -side;
        }
        moveTo(u, u.x + nx * side * 100, u.y + ny * side * 100, u.moveSpeed() * DT);
        u.busyState = "move";
    }

    /**
     * The enemy skillshot (line projectile or Ornn's elemental) that will hit the unit soonest if it stays put:
     * {dx, dy, x, y, seconds}, or null.
     */
    private double[] threat(U u) {
        double[] best = null;
        for (Projectile p : projectiles) {
            if (p.only == null && p.owner.team() != u.team() && !p.hit.contains(u)) {
                best = sooner(best, u, p.x, p.y, p.dx, p.dy, p.width, p.left, p.speed);
            }
        }
        for (Elemental e : elementals) {
            if (e.owner.team() != u.team()) {
                best = sooner(best, u, e.x, e.y, e.dx, e.dy, e.width, e.left, Math.max(e.speed, 300));
            }
        }
        return best;
    }

    private double[] sooner(double[] best, U u, double x, double y, double dx, double dy, double width, double left,
                            double speed) {
        double wx = u.x - x;
        double wy = u.y - y;
        double along = wx * dx + wy * dy;
        if (along < 0 || along > left + u.radius() || speed <= 0) {
            return best;
        }
        double across = Math.abs(wx * dy - wy * dx);
        if (across > width / 2 + u.radius() + 30) {
            return best;
        }
        double seconds = along / speed;
        return best == null || seconds < best[4] ? new double[] {dx, dy, x, y, seconds} : best;
    }

    /** Walks into attack reach of the target, then attacks it when the attack is up. */
    private void chase(U u, U target) {
        double reach = u.attackRange() + u.radius() + target.radius();
        double d = dist(u.x, u.y, target.x, target.y);
        if (d <= reach && target.visible()) {
            if (now >= u.nextAttack - 1e-9) {
                attack(u, target);
            }
            u.facing = Math.atan2(target.y - u.y, target.x - u.x);
        } else {
            moveTo(u, target.x, target.y, u.moveSpeed() * DT);
            u.busyState = "move";
        }
    }

    /**
     * One decision of a free champion: the enemy slots it sees (taken when the step starts, kept while it decides
     * again in the same step), what it may do, and its observation (filled only if the policy reads it).
     */
    private final class Turn implements ActionMask {
        final U u;
        /** An order of the timeline was done this step: no casts of its own. */
        final boolean acted;
        final U target;
        final Observation obs;
        boolean castsOver;
        /** The one valid action (Cleanse while stunned), or -1. */
        int only = -1;
        private List<U> others;
        private boolean[] blocked;
        private double[] threat;
        private boolean threatKnown;

        Turn(U u, boolean acted) {
            this.u = u;
            this.acted = acted;
            this.target = target(u);
            this.obs = new Observation(o -> observe(this, o), o -> predictAll(this, o));
        }

        /** Enemy slot k: 0 = the current target, then the other targetable, visible enemies by distance. */
        U enemy(int k) {
            if (k == 0 || target == null) {
                return target;
            }
            if (others == null) {
                others = new ArrayList<>();
                for (U e : units) {
                    if (e != target && e.team() != u.team() && e.targetable() && e.visible()) {
                        others.add(e);
                    }
                }
                others.sort(Comparator.comparingDouble(e -> dist(u.x, u.y, e.x, e.y)));
            }
            return k - 1 < others.size() ? others.get(k - 1) : null;
        }

        /** The skillshot coming at the champion (see {@link Arena#threat}), computed once per step. */
        double[] threat() {
            if (!threatKnown) {
                threatKnown = true;
                threat = Arena.this.threat(u);
            }
            return threat;
        }

        void block(int action) {
            if (blocked == null) {
                blocked = new boolean[ActionSpace.SIZE];
            }
            blocked[action] = true;
        }

        @Override
        public boolean valid(int action) {
            if (action < 0 || action >= ActionSpace.SIZE || blocked != null && blocked[action]) {
                return false;
            }
            if (only >= 0) {
                return action == only;
            }
            if (u.combo != null) {
                // a combo runs: go on with it (idle or the combo), or break off to dodge / Flash away
                if (action == ActionSpace.IDLE || action == u.combo.action) {
                    return true;
                }
                if (action == ActionSpace.DODGE) {
                    return threat() != null;
                }
                return action == ActionSpace.FLASH_AWAY && target != null && activeUsable(u, "Flash", null);
            }
            if (ActionSpace.isCombo(action)) {
                int c = action - ActionSpace.COMBO;
                return !acted && !castsOver && u.in.autoAbilities && target != null && c < u.in.combos.size()
                        && comboCanStart(this, u.in.combos.get(c));
            }
            if (ActionSpace.isCast(action)) {
                U t = enemy(ActionSpace.enemyOf(action));
                if (acted || castsOver || !u.in.autoAbilities || t == null) {
                    return false;
                }
                String slot = ActionSpace.SLOTS.get(ActionSpace.slotOf(action));
                Map<String, Object> ab = ability(u, slot);
                if (ab == null || !isReady(u, slot) || !inUseRange(u, t, ab)) {
                    return false;
                }
                TeamPlan plan = sides[u.team()].plan;
                if (plan != null && plan.chainCc && controlLeft(t) > CHAIN_SLACK && controls(u, slot)) {
                    return false;   // the target is still held by an ally's control
                }
                for (Action o : u.orders) {
                    if (o.time <= now + 1e-9 && slot.equals(o.slot)) {
                        return false;   // kept for the ordered cast
                    }
                }
                return true;
            }
            if (ActionSpace.isAttack(action) || ActionSpace.isKite(action)) {
                return enemy(ActionSpace.enemyOf(action)) != null;
            }
            if (action == ActionSpace.RETREAT) {
                if (target == null) {
                    return false;
                }
                double[] a = away(u);   // cornered at an end of the lane: nowhere to go
                return dist(u.x, u.y, clampX(u.x + a[0] * 20), clampY(u.y + a[1] * 20)) > 10;
            }
            if (action == ActionSpace.DODGE) {
                return threat() != null;
            }
            if (ActionSpace.isInstant(action) && (acted || castsOver || !u.in.autoAbilities)) {
                return false;
            }
            if (ActionSpace.isRecast(action)) {
                String slot = ActionSpace.SLOTS.get(ActionSpace.slotOf(action));
                double[] w = u.recast.get(slot);
                if (w == null || now < w[0] - 1e-9 || now > w[1]) {
                    return false;
                }
                for (Action o : u.orders) {
                    if (o.time <= now + 1e-9 && "recast".equals(o.type) && slot.equals(o.slot)) {
                        return false;
                    }
                }
                return true;
            }
            if (ActionSpace.isActive(action)) {
                int i = action - ActionSpace.ACTIVE;
                return i < u.actives.size() && activeUsable(u, u.actives.get(i), target);
            }
            if (action == ActionSpace.FLASH_AWAY) {
                return target != null && activeUsable(u, "Flash", null);
            }
            return true;
        }
    }

    private String activeKind(U u, String name) {
        if (u.f.spells.contains(name)) {
            return name;
        }
        for (ItemEffect e : u.f.effects) {
            if (e.item.equals(name) && "ativo".equals(e.kind())) {
                return Observation.DAMAGE;
            }
        }
        return Observation.DEFENSE;
    }

    /**
     * An item active or a spell that is up and would work now: not kept for the timeline, and with its target in
     * range when it needs one (Ignite, Exhaust, Flash towards the target, damage actives). A null target skips the
     * range check (Flash away).
     */
    private boolean activeUsable(U u, String name, U t) {
        boolean spell = u.f.spells.contains(name);
        if (!spell && !u.f.items.containsKey(name) || u.manualItems.contains(name)
                || now < u.ready.getOrDefault(name, 0.0) - 1e-9) {
            return false;
        }
        double range;
        if (spell) {
            range = "Incendiar".equals(name) ? 600 : "Exaustão".equals(name) ? 650 : "Flash".equals(name) ? 1e9 : -1;
        } else {
            range = Observation.DAMAGE.equals(activeKind(u, name)) ? 600 : -1;
        }
        if (range < 0 || t == null && "Flash".equals(name)) {
            return true;
        }
        return t != null && dist(u.x, u.y, t.x, t.y) - t.radius() <= range;
    }

    /**
     * The predictions on the current target and the enemies' schema 3 facts: computed apart, on the first read of them
     * ({@link Observation#predicted}), since most decisions of the rules never look at them.
     */
    private void predictAll(Turn turn, Observation o) {
        U u = turn.u;
        Observation.Self s = o.rawSelf();
        U t0 = turn.enemy(0);
        if (t0 != null) {
            for (int k = 0; k < ActionSpace.SLOTS.size(); k++) {
                predict(u, t0, k, s);
            }
            s.attackConsumesMark = attackConsumes(u, t0);
            s.attackAppliesMark = attackApplies(u);
        }
        for (int k = 0; k < ActionSpace.ENEMIES; k++) {
            enemyFacts(u, turn.enemy(k), o.rawEnemy(k));
        }
    }

    /** The facts a policy sees (see {@link Observation}). */
    private void observe(Turn turn, Observation o) {
        U u = turn.u;
        Observation.Self s = o.rawSelf();
        s.hp = u.hp;
        s.maxHp = u.maxHp;
        s.shield = u.shieldLeft();
        Map<String, Object> res = map(u.kit("recurso"));
        s.resource = res == null ? 0 : u.resource;
        s.resourceMax = res == null ? 0 : num(res.get("max"), 100);
        s.level = u.f.level;
        for (int k = 0; k < ActionSpace.SLOTS.size(); k++) {
            String slot = ActionSpace.SLOTS.get(k);
            s.learned[k] = u.rank(slot) > 0 && ability(u, slot) != null;
            s.ready[k] = isReady(u, slot);
            s.cooldownLeft[k] = Math.max(0, u.ready.getOrDefault(slot, 0.0) - now);
            double[] w = u.recast.get(slot);
            s.recastOpen[k] = w != null && now >= w[0] - 1e-9 && now <= w[1] + 1e-9;
        }
        s.attackRange = u.attackRange();
        s.moveSpeed = u.moveSpeed();
        s.stunned = now < u.stunUntil;
        s.airborne = now < u.airUntil;
        s.invisible = !u.visible();
        s.empowered = u.empowered != null && now < u.empoweredUntil;
        s.attackReady = now >= u.nextAttack - 1e-9;
        for (int k = 0; k < u.actives.size(); k++) {
            s.activeName[k] = u.actives.get(k);
            s.activeReady[k] = now >= u.ready.getOrDefault(u.actives.get(k), 0.0) - 1e-9;
            s.activeKind[k] = activeKind(u, u.actives.get(k));
        }
        s.wallDistance = Math.max(0, halfWidth - Math.abs(u.y));
        double[] threat = turn.threat();
        s.threatTime = threat == null ? Double.POSITIVE_INFINITY : threat[4];
        for (int k = 0; k < ActionSpace.ENEMIES; k++) {
            other(u, turn.enemy(k), o.rawEnemy(k), true);
        }
        // schema 3: predictions on the current target, and the champion's own new facts
        for (int k = 0; k < ActionSpace.SLOTS.size(); k++) {
            Map<String, Object> ab = ability(u, ActionSpace.SLOTS.get(k));
            s.aimed[k] = ab != null && AIMED.contains(String.valueOf(ab.get("forma")))
                    && !(Boolean.TRUE.equals(ab.get("sem_dano")) && ab.get("proximo_ataque") == null);   // not a bare dash
            s.immobilizes[k] = ab != null && controls(u, ActionSpace.SLOTS.get(k));
        }
        s.overheatLeft = Math.max(0, u.overheatedUntil - now);
        s.resourceZone = resourceZone(u);
        s.immuneDash = u.dash != null && u.dash.immune;
        double recastLeft = 0;
        for (Map.Entry<String, double[]> e : u.recast.entrySet()) {
            double[] w = e.getValue();
            if (!e.getKey().contains(":") && now >= w[0] - 1e-9 && now <= w[1]) {
                recastLeft = recastLeft == 0 ? w[1] - now : Math.min(recastLeft, w[1] - now);
            }
        }
        s.recastLeft = recastLeft;
        s.controlLeft = controlLeft(u);
        s.recentLoss = recentLoss(u);
        List<U> allies = allies(u);
        allies.sort(Comparator.comparingDouble(a -> dist(u.x, u.y, a.x, a.y)));
        for (int k = 0; k < Observation.ALLIES; k++) {
            other(u, k < allies.size() ? allies.get(k) : null, o.rawAlly(k), false);
        }
        o.setClock(now, duration);
    }

    private void other(U u, U x, Observation.Other o, boolean enemy) {
        o.present = x != null;
        if (x == null) {
            return;
        }
        o.hp = x.hp;
        o.maxHp = x.maxHp;
        o.shield = x.shieldLeft();
        o.dx = x.x - u.x;
        o.dy = x.y - u.y;
        o.distance = dist(u.x, u.y, x.x, x.y);
        o.crowdControlled = now < x.stunUntil || now < x.airUntil || x.forced != null;
        o.inAttackReach = o.distance <= u.attackRange() + u.radius() + x.radius();
        if (enemy) {
            for (int k = 0; k < ActionSpace.SLOTS.size(); k++) {
                Map<String, Object> ab = ability(u, ActionSpace.SLOTS.get(k));
                o.inAbilityRange[k] = ab != null && inUseRange(u, x, ab);
            }
        }
    }

    /** The schema 3 facts of an enemy slot (read with the predictions, only when a policy asks for them). */
    private void enemyFacts(U u, U x, Observation.Other o) {
        if (x == null) {
            return;
        }
        {
            o.markLeft = markLeft(u, x);
            o.marksConsumed = u.consumedOn.getOrDefault(x, 0);
            o.controlLeft = controlLeft(x);
            o.slowPct = x.slowPct();
            o.slowLeft = o.slowPct > 0 ? Math.max(0, x.slowUntil() - now) : 0;
            Map<String, Object> res = map(x.kit("recurso"));
            o.resourcePct = res == null ? 0 : x.resource / Math.max(1, num(res.get("max"), 100));
            o.overheatLeft = Math.max(0, x.overheatedUntil - now);
            o.dashReady = dashReady(x);
            o.flashReady = spellReady(x, "Flash");
            o.cleanseReady = spellReady(x, "Purificar");
            o.attackRange = x.attackRange();
            o.reachesMe = o.distance <= x.attackRange() + u.radius() + x.radius();
            o.airLeft = Math.max(0, x.airUntil - now);
        }
    }

    // ------------------------------------------------------------------ predictions (observation schema 3)

    /** Shapes of abilities that are aimed at a place, so the target can step out before they land. */
    static final Set<String> AIMED = Set.of("linha", "cone", "area", "canal", "avanco", "campo", "elemental");

    /** Seconds a target needs to react before it starts stepping out of a skillshot it sees coming. */
    static final double REACTION = 0.2;
    /**
     * How long before an effect lands a target can tell where it will land and start stepping out (the tactical
     * autopilot dodges within the same window): earlier than that, a long skillshot still looks like it could go anywhere.
     */
    static final double PERCEIVE = 0.7;
    /** A target free to move with a dash of its own up: the hit chance is cut by this. */
    static final double DASH_DODGE = 0.8;

    private boolean spellReady(U u, String name) {
        return u.f.spells.contains(name) && now >= u.ready.getOrDefault(name, 0.0) - 1e-9;
    }

    /** Share of its maximum health the unit lost in the last 2s (0 when it gained). */
    private double recentLoss(U u) {
        double[] first = u.hpHistory.peekFirst();
        return first == null ? 0 : Math.max(0, (first[1] - u.hp) / Math.max(1, u.maxHp));
    }

    private boolean resourceZone(U u) {
        Map<String, Object> res = map(u.kit("recurso"));
        if (res == null) {
            return false;
        }
        if ("calor".equals(res.get("tipo"))) {
            return u.resource >= num(res.get("zona_de_perigo"), 50) && now >= u.overheatedUntil;
        }
        return "furia".equals(res.get("tipo")) && u.resource >= num(res.get("limiar"), 50);
    }

    /** Move speed without its slows (the speed it gets back when they end). */
    private double unslowedSpeed(U u) {
        double ms = u.stat(Stats.MOVE_SPEED) * (1 + u.stat("% Move Speed") / 100);
        double up = 0;
        for (double[] x : u.speedUps) {
            if (now < x[1]) {
                up += x[0];
            }
        }
        return Math.max(0, ms * (1 + up));
    }

    /**
     * Chance (0..1) that a target cannot leave an area it needs `escape` units to get out of, before an effect that
     * lands in `seconds`: 1 when it is held until then (or cannot cover the distance), less the farther it can walk,
     * halved when it is free with a dash of its own up. 0 when it is already out (escape <= 0).
     */
    private double hitChance(U t, double seconds, double escape) {
        if (escape <= 0) {
            return 0;
        }
        double held = controlLeft(t);
        if (held >= seconds - 1e-9) {
            return 1;
        }
        // it starts stepping out once the effect is close enough to judge where it lands (and after reacting)
        double free = Math.min(seconds - held, PERCEIVE) - REACTION;
        if (free <= 0) {
            return 1;
        }
        double slowLeft = t.slowPct() > 0 ? Math.max(0, t.slowUntil() - now - held) : 0;
        double slowed = Math.min(free, slowLeft);
        double walk = t.moveSpeed() * slowed + unslowedSpeed(t) * (free - slowed);
        double p = walk <= escape ? 1 : escape / walk;
        return dashReady(t) ? p * DASH_DODGE : p;
    }

    /** Seconds Ornn's elemental (or any accelerating unit) takes to cover a distance. */
    private static double accelTime(double distance, double v0, double accel, double vmax) {
        if (distance <= 0) {
            return 0;
        }
        if (accel <= 0) {
            return distance / Math.max(1, v0);
        }
        double tMax = Math.max(0, (vmax - v0) / accel);
        double dMax = v0 * tMax + accel * tMax * tMax / 2;
        if (distance <= dMax) {
            return (-v0 + Math.sqrt(v0 * v0 + 2 * accel * distance)) / accel;
        }
        return tMax + (distance - dMax) / Math.max(1, vmax);
    }

    /**
     * Where a dash from the unit along an angle first meets terrain (a wall of the lane, a pillar) within its range, as
     * the dash itself stops: {x, y, distance}, or null.
     */
    private double[] terrainAlong(U u, double angle, double range) {
        double r = u.radius();
        for (double d = 20; d <= range + 1e-6; d += 20) {
            double x = u.x + Math.cos(angle) * d;
            double y = u.y + Math.sin(angle) * d;
            if (Math.abs(y) > halfWidth - r || x < 0 || x > length || blocked(x, y, r)) {
                return new double[] {u.x + Math.cos(angle) * (d - 20), u.y + Math.sin(angle) * (d - 20), d - 20};
            }
        }
        return null;
    }

    /** Effects of a sheet entry (or of its last hit) that immobilize: what consumes a Brittle on the target. */
    private static boolean immobilizesNow(Map<String, Object> ef) {
        return ef != null && (ef.get("atordoa") != null || ef.get("arremessa") != null || ef.get("puxa") != null);
    }

    /** The side's Brittle is on the target. */
    private static boolean brittleOn(U u, U t, double now) {
        return t.brittleBy != null && now < t.brittleUntil && t.brittleBy.team() == u.team() && map(t.brittleBy.kit("fragil")) != null;
    }

    /** Seconds left of the longest mark of the champion's side on the target. */
    private double markLeft(U u, U t) {
        double left = brittleOn(u, t, now) ? t.brittleUntil - now : 0;
        double[] b = u.bolts.get(t);
        if (b != null && b[0] > 0 && now < b[1]) {
            left = Math.max(left, b[1] - now);
        }
        Double k = u.ksMarks.get(t);
        if (k != null && now < k) {
            left = Math.max(left, k - now);
        }
        double[] st = u.stacks.get(t);
        if (st != null && st[0] > 0 && now < st[1]) {
            left = Math.max(left, st[1] - now);
        }
        return left;
    }

    /** The bolt that a hit now would add is the one that pops them (Vayne's third). */
    private boolean boltPops(U u, U t) {
        Map<String, Object> m = map(u.kit("marcas"));
        if (m == null || u.rank(String.valueOf(m.get("habilidade"))) <= 0) {
            return false;
        }
        double[] st = u.bolts.get(t);
        return st != null && now < st[1] && st[0] + 1 >= num(m.get("max"), 3);
    }

    private boolean attackConsumes(U u, U t) {
        Map<String, Object> fr = map(u.kit("fragil"));
        if (fr != null && fr.get("ataques") != null && brittleOn(u, t, now)) {
            return true;
        }
        Double k = u.ksMarks.get(t);
        return boltPops(u, t) || k != null && now < k && map(u.kit("marcas_ksante")) != null;
    }

    private boolean attackApplies(U u) {
        Map<String, Object> m = map(u.kit("marcas"));
        return m != null && u.rank(String.valueOf(m.get("habilidade"))) > 0 || map(u.kit("acumulos")) != null;
    }

    /** An ability's spec consumes / applies a mark on the target (its effects, last hit, wall, empowered attack). */
    private boolean consumes(U u, U t, Map<String, Object> ab, boolean recast) {
        Map<String, Object> rec = map(ab.get("reconjurar"));
        Map<String, Object> ef = recast && rec != null ? map(rec.get("efeitos")) : map(ab.get("efeitos"));
        boolean immobilize = immobilizesNow(ef) || !recast && (immobilizesNow(map(ab.get("ultimo_golpe")))
                || immobilizesNow(map(ab.get("terreno"))));
        if (immobilize && brittleOn(u, t, now)) {
            return true;
        }
        if (!recast && (Boolean.TRUE.equals(ab.get("marca")) || "ataque".equals(ab.get("forma")) && attackConsumes(u, t))
                && boltPops(u, t)) {
            return true;
        }
        if (!recast && ab.get("por_acumulo") != null) {
            double[] st = u.stacks.get(t);
            return st != null && st[0] > 0 && now < st[1];
        }
        return false;
    }

    private boolean applies(U u, Map<String, Object> ab, boolean recast) {
        Map<String, Object> rec = map(ab.get("reconjurar"));
        Map<String, Object> ef = recast && rec != null ? map(rec.get("efeitos")) : map(ab.get("efeitos"));
        if (ef != null && (ef.get("fragil") != null || ef.get("acumulos") != null || Boolean.TRUE.equals(ef.get("marca")))) {
            return true;
        }
        Map<String, Object> last = map(ab.get("ultimo_golpe"));
        return !recast && (last != null && last.get("fragil") != null || Boolean.TRUE.equals(ab.get("marca")));
    }

    /** What the ability of slot k would do on the target if cast now (the recast's part when its window is open). */
    private void predict(U u, U t, int k, Observation.Self s) {
        String slot = ActionSpace.SLOTS.get(k);
        Map<String, Object> ab = ability(u, slot);
        if (ab == null || u.rank(slot) <= 0) {
            return;
        }
        double[] w = u.recast.get(slot);
        boolean recastOpen = w != null && now >= w[0] - 1e-9 && now <= w[1];
        double[] cast = castPrediction(u, t, ab);
        s.hitChance[k] = cast[0];
        s.timeToEffect[k] = cast[1];
        s.bodyHit[k] = cast.length > 2 ? cast[2] : cast[0];
        if (recastOpen) {
            double[] rec = recastPrediction(u, t, slot, ab);
            s.recastHit[k] = rec[0];
            s.timeToEffect[k] = rec[1];
        }
        double held = controlLeft(t);
        boolean immobilizes = recastOpen ? immobilizesNow(map(map(ab.get("reconjurar")) == null ? null
                : map(ab.get("reconjurar")).get("efeitos"))) : controls(u, slot);
        s.chainsControl[k] = immobilizes && held > 1e-9 && s.timeToEffect[k] >= held - CHAIN_SLACK - 1e-9
                && s.timeToEffect[k] <= held + 0.1 + 1e-9;
        s.consumesMark[k] = consumes(u, t, ab, recastOpen);
        s.appliesMark[k] = applies(u, ab, recastOpen);
    }

    /** {hit chance, seconds to effect} of casting the ability at the target now. */
    private double[] castPrediction(U u, U t, Map<String, Object> ab) {
        String shape = String.valueOf(ab.get("forma"));
        double d = dist(u.x, u.y, t.x, t.y);
        double r = t.radius();
        double cast = num(ab.get("conjuracao"), 0);
        double angle = Math.atan2(t.y - u.y, t.x - u.x);
        switch (shape) {
            case "linha": {
                double range = num(ab.get("alcance"), 800);
                double time = cast + Math.max(0, d - r) / Math.max(1, num(ab.get("velocidade"), 1500));
                return new double[] {d - r > range ? 0 : hitChance(t, time, num(ab.get("largura"), 100) / 2 + r), time};
            }
            case "cone": {
                double range = num(ab.get("alcance"), 450);
                double half = Math.toRadians(num(ab.get("angulo"), 50)) / 2;
                double escape = Math.min(range + r - d, d * Math.sin(half) + r);
                return new double[] {d - r > range ? 0 : hitChance(t, cast, escape), cast};
            }
            case "area": {
                double radius = num(ab.get("raio"), 300);
                return new double[] {hitChance(t, cast, radius + r - d), cast};
            }
            case "canal": {
                double dur = num(ab.get("duracao"), 1);
                double time = ab.get("ultimo_golpe") != null ? dur : num(ab.get("intervalo"), 0.25);
                double range = num(ab.get("alcance"), 500);
                double escape = ab.get("angulo") != null ? range + r - d
                        : Math.min(range + r - d, num(ab.get("largura"), 300) / 2 + r);
                return new double[] {hitChance(t, time, escape), time};
            }
            case "avanco": {
                double range = num(ab.get("alcance"), 400);
                double speed = num(ab.get("velocidade"), 1200) + (Boolean.TRUE.equals(ab.get("velocidade_mais_vdm")) ? u.moveSpeed() : 0);
                Map<String, Object> charge = map(ab.get("carregar"));
                double wait = cast + (charge == null ? 0 : num(charge.get("max"), 1));
                Map<String, Object> wall = map(ab.get("terreno"));
                double hr = num(ab.get("raio"), 100);
                double bodyTime = wait + Math.max(0, d - hr - r) / Math.max(1, speed);
                double body = d - hr - r > range ? 0 : hitChance(t, bodyTime, hr + r);
                if (wall != null) {
                    // only the shockwave of a wall or pillar on the way holds the target (the body's hit is kept apart)
                    double[] hit = terrainAlong(u, angle, range);
                    if (hit == null) {
                        return new double[] {0, wait + range / Math.max(1, speed), body};
                    }
                    double time = wait + hit[2] / Math.max(1, speed);
                    return new double[] {hitChance(t, time, num(wall.get("raio"), 360) + r - dist(hit[0], hit[1], t.x, t.y)), time, body};
                }
                if (Boolean.TRUE.equals(ab.get("sem_dano"))) {
                    if (ab.get("proximo_ataque") == null) {
                        return new double[] {0, wait};   // a bare dash hits nobody
                    }
                    // a dash that empowers the next attack (Vayne's Tumble): the attack is targeted once in reach
                    double time = wait + range / Math.max(1, speed) + num(u.kit("espera_ataque"), 0.25) / u.attackSpeed();
                    return new double[] {d - r <= range + u.attackRange() + u.radius() ? 1 : 0, time};
                }
                return new double[] {body, bodyTime};
            }
            case "campo": {
                double time = cast + num(ab.get("atraso"), 0.75);
                return new double[] {d > num(ab.get("alcance"), 1700) ? 0 : hitChance(t, time, num(ab.get("largura"), 390) / 2 + r), time};
            }
            case "elemental": {
                double far = num(ab.get("distancia"), 2550);
                double time = cast + accelTime(far - d, num(ab.get("velocidade"), 450), num(ab.get("aceleracao"), 400),
                        num(ab.get("velocidade_final"), 1250));
                return new double[] {d > far ? 0 : hitChance(t, time, num(ab.get("largura"), 340) / 2 + r), time};
            }
            case "ataque":
                return new double[] {d <= u.attackRange() + num(ab.get("alcance_extra"), 0) + u.radius() + r ? 1 : 0,
                    cast + num(u.kit("espera_ataque"), 0.25) / u.attackSpeed()};
            case "proprio":
                return new double[] {1, cast};
            case "empurrao":
                return new double[] {d - r <= num(ab.get("alcance"), 500) ? 1 : 0,
                    cast + d / Math.max(1, num(ab.get("velocidade_projetil"), 2200))};
            default:   // alvo, tudo_ou_nada: targeted, it lands when in range
                return new double[] {inUseRange(u, t, ab) ? 1 : 0, cast};
        }
    }

    /** {hit chance, seconds to effect} of the open recast of a slot on the target. */
    private double[] recastPrediction(U u, U t, String slot, Map<String, Object> ab) {
        Map<String, Object> rec = map(ab.get("reconjurar"));
        if (rec == null) {
            return new double[] {0, 0};
        }
        if ("elemental".equals(ab.get("forma"))) {
            Elemental e = u.elemental;
            if (e == null || e.redirected) {
                return new double[] {0, 0};
            }
            double reach = e.width / 2 + u.radius() + num(rec.get("cabecada"), 200) + 60;
            if (dist(u.x, u.y, e.x, e.y) > reach) {
                return new double[] {0, 0};   // not within the headbutt's reach yet
            }
            double a = Math.atan2(e.y - u.y, e.x - u.x);
            double dx = Math.cos(a);
            double dy = Math.sin(a);
            double along = (t.x - e.x) * dx + (t.y - e.y) * dy;
            double across = Math.abs((t.x - e.x) * dy - (t.y - e.y) * dx);
            double far = num(rec.get("distancia"), 1500);
            double time = num(rec.get("cabecada"), 200) / 1600 + Math.max(0, along) / Math.max(1, num(rec.get("velocidade"), 1250));
            if (along < -t.radius() - e.width / 2 || along > far + t.radius()) {
                return new double[] {0, time};
            }
            return new double[] {hitChance(t, time, e.width / 2 + t.radius() - across), time};
        }
        return castPrediction(u, t, ab);   // a second dash of the same sheet (Renekton's Dice)
    }

    /** One order of the timeline: done, wait (try again next step) or drop (impossible). */
    private String order(U u, Action a) {
        switch (a.type == null ? "" : a.type) {
            case "move":
                if (a.x == null || a.y == null) {
                    return "drop";
                }
                u.moveX = a.x;
                u.moveY = clampY(a.y);
                u.hold = false;
                return "done";
            case "stop":
                u.hold = true;
                u.moveX = null;
                return "done";
            case "attack":
                if (a.target != null && a.target < units.size()) {
                    u.focus = units.get(a.target);
                }
                u.hold = false;
                u.moveX = null;
                return "done";
            case "ability":
            case "recast": {
                U t = a.target != null && a.target < units.size() ? units.get(a.target) : target(u);
                double tx = a.x != null ? a.x : t != null ? t.x : u.x + Math.cos(u.facing) * 300;
                double ty = a.y != null ? a.y : t != null ? t.y : u.y + Math.sin(u.facing) * 300;
                if ("recast".equals(a.type)) {
                    return recast(u, a.slot, t, tx, ty);
                }
                return castAbility(u, a.slot, t, tx, ty, a);
            }
            case "item":
                return useItem(u, a.name, a.target != null && a.target < units.size() ? units.get(a.target) : target(u));
            case "spell":
                return useSpellNimbus(u, a.name, a.target != null && a.target < units.size() ? units.get(a.target) : target(u),
                        a.x, a.y);
            default:
                return "drop";
        }
    }

    private U target(U u) {
        if (u.focus != null && !u.focus.dead && u.focus.targetable() && u.focus.visible()) {
            return u.focus;
        }
        if (u.in.followTeam && sides[u.team()].plan != null) {
            U t = teamTarget(u);
            if (t != null) {
                return t;
            }
        }
        if (u.in.target != null && u.in.target < units.size()) {
            U t = units.get(u.in.target);
            if (!t.dead && t.targetable() && t.visible() && t.team() != u.team()) {
                return t;
            }
        }
        U best = null;
        for (U e : units) {
            if (e.team() != u.team() && e.targetable() && e.visible()
                    && (best == null || dist(u.x, u.y, e.x, e.y) < dist(u.x, u.y, best.x, best.y))) {
                best = e;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ team plans

    /** Seconds between two picks of a plan's target (sooner when the target is gone). */
    static final double PICK_EVERY = 0.25;
    /** A new pick replaces the current target only when it is this much better (relative): no back and forth. */
    static final double PICK_MARGIN = 0.10;
    /** Chained control: an immobilize with more than this left on the target holds back the allies' own. */
    static final double CHAIN_SLACK = 0.3;
    /** "In reach" for switching to a low enemy: attack reach plus this. */
    private static final double SWITCH_REACH = 150;

    /** A side's plan and the target it shares. */
    private static final class Side {
        TeamPlan plan;
        U target;
        double pickAt = -1;
    }

    /** The target of the champion's side plan: the side's shared one, or its own pick by the plan's criterion. */
    private U teamTarget(U u) {
        Side side = sides[u.team()];
        if (side.plan.shared) {
            if (side.target == null || !aimable(u, side.target) || now >= side.pickAt - 1e-9) {
                List<U> from = new ArrayList<>();
                for (U a : units) {
                    if (a.team() == u.team() && !a.dead && a.in.followTeam) {
                        from.add(a);
                    }
                }
                side.target = pick(side.plan, from.isEmpty() ? List.of(u) : from, side.target);
                side.pickAt = now + PICK_EVERY;
            }
            return side.target;
        }
        if (u.pick == null || !aimable(u, u.pick) || now >= u.pickAt - 1e-9) {
            u.pick = pick(side.plan, List.of(u), u.pick);
            u.pickAt = now + PICK_EVERY;
        }
        return u.pick;
    }

    private static boolean aimable(U u, U e) {
        return e.team() != u.team() && !e.dead && e.targetable() && e.visible();
    }

    /**
     * The plan's pick seen from these champions (one, or the side's followers): an enemy in reach below the switch
     * threshold first, else the best by the criterion, ties by the plan's order, then distance. The current target
     * stays unless the new one is better by {@link #PICK_MARGIN}.
     */
    private U pick(TeamPlan plan, List<U> from, U current) {
        U me = from.get(0);
        List<U> enemies = new ArrayList<>();
        for (U e : units) {
            if (aimable(me, e)) {
                enemies.add(e);
            }
        }
        if (enemies.isEmpty()) {
            return null;
        }
        double cx = 0;
        double cy = 0;
        for (U a : from) {
            cx += a.x / from.size();
            cy += a.y / from.size();
        }
        double fx = cx;
        double fy = cy;
        List<Integer> order = plan.order == null ? List.of() : plan.order;
        Comparator<U> ties = Comparator.<U>comparingInt(e -> rank(order, e))
                .thenComparingDouble(e -> dist(fx, fy, e.x, e.y)).thenComparingInt(e -> e.idx);
        boolean keep = current != null && enemies.contains(current);
        if (plan.switchBelow != null) {
            U low = null;
            for (U e : enemies) {
                double f = e.hp / e.maxHp;
                if (f < plan.switchBelow && inReach(from, e) && (low == null || f < low.hp / low.maxHp - 1e-9
                        || Math.abs(f - low.hp / low.maxHp) <= 1e-9 && ties.compare(e, low) < 0)) {
                    low = e;
                }
            }
            if (low != null) {
                return keep && current.hp / current.maxHp < plan.switchBelow ? current : low;
            }
        }
        java.util.function.ToDoubleFunction<U> score = score(plan.focus, me.team(), order, fx, fy);
        U best = null;
        double bestScore = 0;
        for (U e : enemies) {
            double sc = score.applyAsDouble(e);
            if (best == null || sc < bestScore - 1e-9 || Math.abs(sc - bestScore) <= 1e-9 && ties.compare(e, best) < 0) {
                best = e;
                bestScore = sc;
            }
        }
        if (keep && best != current) {
            double cur = score.applyAsDouble(current);
            if (bestScore >= cur - PICK_MARGIN * Math.abs(cur) - 1e-9) {
                return current;
            }
        }
        return best;
    }

    private static int rank(List<Integer> order, U e) {
        int i = order.indexOf(e.idx);
        return i < 0 ? order.size() : i;
    }

    /** A plan's criterion as a score, lower is better. */
    private java.util.function.ToDoubleFunction<U> score(String focus, int team, List<Integer> order, double fx, double fy) {
        switch (focus == null ? "nearest" : focus) {
            case "lowest_hp":
                return e -> e.hp;
            case "lowest_effective_hp":
                return e -> (e.hp + e.shieldLeft()) / Math.max(0.05, damageThrough(team, e));
            case "most_damage":
                return e -> -e.r.dealt;
            case "order":
                return e -> rank(order, e);
            default:
                return e -> dist(fx, fy, e.x, e.y);
        }
    }

    private static boolean inReach(List<U> from, U e) {
        for (U a : from) {
            if (dist(a.x, a.y, e.x, e.y) <= a.attackRange() + a.radius() + e.radius() + SWITCH_REACH) {
                return true;
            }
        }
        return false;
    }

    /**
     * Share of a side's damage that gets through an enemy's resistances: each living champion of the side counts by
     * its attack damage plus ability power, split physical / magic in that proportion, with its penetration.
     */
    private double damageThrough(int team, U e) {
        double sum = 0;
        double weight = 0;
        for (U a : units) {
            if (a.team() != team || a.dead) {
                continue;
            }
            double ad = Math.max(0, a.stat(Stats.ATTACK_DAMAGE));
            double ap = Math.max(0, a.stat(Stats.ABILITY_POWER));
            double physical = ad + ap <= 0 ? 1 : ad / (ad + ap);
            double[] mult = new double[2];
            for (int res = 0; res < 2; res++) {
                double resist = e.stat(res == 0 ? Stats.ARMOR : Stats.MAGIC_RESIST);
                double shred = now < e.shredUntil[res] ? e.shred[res] : 0;
                double pen = a.stat(res == 0 ? "% Armor Penetration" : "% Magic Penetration") / 100;
                double flat = a.stat(res == 0 ? "Armor Penetration" : "Magic Penetration");
                mult[res] = DamageRules.mitigation(resist, shred, pen, flat);
            }
            double w = Math.max(1, ad + ap);
            sum += w * (physical * mult[0] + (1 - physical) * mult[1]);
            weight += w;
        }
        return weight <= 0 ? 1 : sum / weight;
    }

    /** Seconds left of the immobilize on a champion (stun, knock-up, a pull or push still moving it). */
    private double controlLeft(U t) {
        double left = Math.max(t.stunUntil, t.airUntil) - now;
        if (t.forced != null) {
            left = Math.max(left, dist(t.x, t.y, t.forced.tx, t.forced.ty) / Math.max(1, t.forced.speed));
        }
        return Math.max(0, left);
    }

    /** The ability immobilizes somewhere in its sheet (stun, knock-up, pull, push, Condemn). */
    private boolean controls(U u, String slot) {
        return u.controls.computeIfAbsent(slot, k -> immobilizes(ability(u, k)));
    }

    private static boolean immobilizes(Object o) {
        if (o instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                Object k = e.getKey();
                if ("atordoa".equals(k) || "arremessa".equals(k) || "puxa".equals(k) || "empurra".equals(k)
                        || "forma".equals(k) && "empurrao".equals(e.getValue()) || immobilizes(e.getValue())) {
                    return true;
                }
            }
        } else if (o instanceof List) {
            for (Object x : (List<?>) o) {
                if (immobilizes(x)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ attacks

    private void attack(U u, U t) {
        double interval = 1 / u.attackSpeed();
        double windup = num(u.kit("espera_ataque"), 0.25) * interval;
        u.nextAttack = now + interval;
        u.busyUntil = now + windup;
        u.busyState = "attack";
        u.facing = Math.atan2(t.y - u.y, t.x - u.x);
        Double missile = u.kit("velocidade_projetil") == null ? null : num(u.kit("velocidade_projetil"), 2000);
        at(now + windup, () -> {
            if (u.dead || t.dead || !u.canAct()) {
                return;
            }
            if (missile == null) {
                attackHit(u, t);
            } else {
                Projectile p = new Projectile();
                p.id = "atk" + (ids++);
                p.label = "Ataque de " + u.f.champion;
                p.owner = u;
                p.x = u.x;
                p.y = u.y;
                p.speed = missile;
                p.only = t;
                p.width = 20;
                p.left = 5000;
                p.onHit = v -> attackHit(u, v);
                projectiles.add(p);
            }
        });
    }

    private void attackHit(U s, U t) {
        if (t.dead || !t.targetable()) {
            return;
        }
        s.attacks++;
        double ad = s.stat(Stats.ATTACK_DAMAGE);
        double c = Math.min(1, s.stat("% Critical Rate") / 100);
        double mult = 2.0;
        for (ItemEffect e : s.f.effects) {
            if ("critico".equals(e.kind())) {
                mult = Math.max(mult, num(e.p.get("mult"), 2.0));
            }
        }
        double raw = ad * (1 + c * (mult - 1));
        deal(s, t, raw, "fisico", "Ataque", ATTACK, ad * c * mult);
        if (!t.dead) {
            fireAll(s, "ao_atacar", null);
            runeOnAttack(s, t);
            // Ornn: his attacks on a Brittle target knock it back and break the Brittle.
            Map<String, Object> fr = map(s.kit("fragil"));
            if (fr != null && fr.get("ataques") != null && t.brittleBy != null && now < t.brittleUntil
                    && t.brittleBy.team() == s.team() && !t.dead) {
                breakBrittle(s, t, "ataque");
                Map<String, Object> atk = map(fr.get("ataques"));
                if ("arremessa".equals(atk.get("efeito"))) {
                    airborne(t, num(atk.get("duracao"), 0.25), s);   // longest of the knock-ups on (airborne's rule)
                } else {
                    double a = Math.atan2(t.y - s.y, t.x - s.x);
                    double push = num(atk.get("empurra"), 150);
                    forcedMove(s, t, "empurrao", t.x + Math.cos(a) * push, t.y + Math.sin(a) * push, 1000, null, null);
                    controlled(s, t, true);
                }
            }
        }
        // Empowered attack of an ability (Darius' Crippling Strike, Renekton's Ruthless Predator, Vayne's Tumble).
        if (s.empowered != null && now <= s.empoweredUntil) {
            String slot = s.empowered;
            s.empowered = null;
            Map<String, Object> ab = ability(s, slot);
            credit(s, slot, false);
            boolean strong = s.buffs.containsKey("fortalecido:" + slot);
            s.buffs.remove("fortalecido:" + slot);
            Map<String, Object> dmg = map(strong && ab.get("fortalecido") != null ? ab.get("fortalecido")
                    : ab.get("proximo_ataque") != null ? map(ab.get("proximo_ataque")).get("dano") : ab.get("dano"));
            if (dmg != null) {
                deal(s, t, formula(dmg, s, t, s.rank(slot)), type(dmg), slot + " · " + ab.get("nome"), ABILITY, 0);
            }
            Map<String, Object> ef = map(strong && ab.get("efeitos_fortalecido") != null ? ab.get("efeitos_fortalecido")
                    : ab.get("efeitos"));
            if (ef != null) {
                effects(s, t, ef, s.rank(slot), slot);
            }
            gainResourceOnHit(s, t, true);
        }
        // K'Sante: an ability's mark is consumed by the next attack.
        Double mk = s.ksMarks.remove(t);
        Map<String, Object> ksm = map(s.kit("marcas_ksante"));
        if (mk != null && now < mk && ksm != null) {
            s.ksCounted.remove(t);
            count(s, "marca_consumida:" + ksMark(s) + ":ataque");
            s.consumedOn.merge(t, 1, Integer::sum);
            deal(s, t, formula(map(ksm.get("dano")), s, t, 1), "fisico", "Instinto Destemido", ABILITY, 0);
        }
        // Vayne: Silver Bolts on the third hit in a row.
        boltHit(s, t);
        // Darius: bleed stacks (all of them with Noxian Might).
        addStacks(s, t, true);
        // Rumble overheated: bonus on-hit.
        Map<String, Object> res = map(s.kit("recurso"));
        if (res != null && now < s.overheatedUntil && res.get("ao_contato_superaquecido") != null) {
            Map<String, Object> d = map(res.get("ao_contato_superaquecido"));
            deal(s, t, formula(d, s, t, 1), type(d), "Superaquecido", ITEM, 0);
        }
        if (res != null && "furia".equals(res.get("tipo"))) {
            gainFury(s, num(res.get("por_ataque"), 5));
        }
        onAttackItems(s, t, c);
        // Thorns of the target answer the attack.
        for (ItemEffect e : t.f.effects) {
            if ("espinhos".equals(e.kind()) && !s.dead) {
                deal(t, s, num(e.p.get("dano"), 0) + num(e.p.get("armadura_bonus"), 0) * t.bonus(Stats.ARMOR)
                        + num(e.p.get("vida_bonus"), 0) * t.bonus(Stats.MAX_HEALTH), type(e.p), e.item, REFLECT, 0);
            } else if ("feridas".equals(e.kind()) && Boolean.TRUE.equals(e.p.get("ao_ser_atingido"))) {
                applyGw(s, num(e.p.get("pct"), 0.4), 3, key(t, e.item));
            }
        }
    }

    private void onAttackItems(U s, U t, double crit) {
        double ad = s.stat(Stats.ATTACK_DAMAGE);
        for (ItemEffect e : s.f.effects) {
            if (t.dead) {
                return;
            }
            switch (e.kind()) {
                case "ao_contato":
                    deal(s, t, num(e.p.get("dano"), 0) + num(e.p.get("pdh"), 0) * s.stat(Stats.ABILITY_POWER)
                            + num(e.p.get("vida_atual_alvo"), 0) * Math.max(0, t.hp)
                            + num(e.p.get("mana"), 0) * s.stat(Stats.MAX_MANA), type(e.p), e.item, ITEM, 0);
                    break;
                case "a_cada_ataque":
                    if (s.attacks % (int) num(e.p.get("n"), 3) == 0) {
                        deal(s, t, level(e.p, "dano", s.f.level) + num(e.p.get("dda_base"), 0) * s.base(Stats.ATTACK_DAMAGE)
                                + num(e.p.get("vida"), 0) * s.maxHp + num(e.p.get("vida_bonus"), 0) * s.bonus(Stats.MAX_HEALTH),
                                type(e.p), e.item, ITEM, 0);
                    }
                    break;
                case "lamina":
                    if (s.bladeArmed && now >= s.bladeReady) {
                        double dmg = num(e.p.get("dda_base"), 0) * s.base(Stats.ATTACK_DAMAGE) + num(e.p.get("dda"), 0) * ad
                                + num(e.p.get("pdh"), 0) * s.stat(Stats.ABILITY_POWER)
                                + num(e.p.get("armadura_bonus"), 0) * s.bonus(Stats.ARMOR)
                                + num(e.p.get("vida_max_alvo"), 0) * t.maxHp + num(e.p.get("critico_dano"), 0) * crit;
                        deal(s, t, dmg, type(e.p), e.item, ITEM, 0);
                        proc(s, "item", e.item);
                        double heal = num(e.p.get("cura_vida_max_alvo"), 0) * t.maxHp
                                + num(e.p.get("cura_vida_bonus"), 0) * s.bonus(Stats.MAX_HEALTH)
                                + num(e.p.get("cura_pdh"), 0) * s.stat(Stats.ABILITY_POWER);
                        if (heal > 0) {
                            heal(s, heal, Map.of(key(s, e.item), heal));
                        }
                        s.bladeArmed = false;
                        s.bladeReady = now + 1.5;
                    }
                    break;
                case "golpe":
                    if (!"habilidade".equals(e.p.getOrDefault("gatilho", "qualquer"))) {
                        strike(s, t, e);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private void strike(U s, U t, ItemEffect e) {
        if (!activate(s, "item", e.item, num(e.p.get("recarga"), 10), 0, true)) {
            return;
        }
        double dmg = level(e.p, "dano", s.f.level) + num(e.p.get("vida_max"), 0) * s.maxHp
                + num(e.p.get("vida_max_alvo"), 0) * t.maxHp + num(e.p.get("pdh"), 0) * s.stat(Stats.ABILITY_POWER)
                + num(e.p.get("dda_bonus"), 0) * s.bonus(Stats.ATTACK_DAMAGE)
                + num(e.p.get("vida_bonus"), 0) * s.bonus(Stats.MAX_HEALTH) + num(e.p.get("mana"), 0) * s.stat(Stats.MAX_MANA);
        deal(s, t, dmg, type(e.p), e.item, ITEM, 0);
        fireAll(s, "golpe_do_item", sp -> e.item.equals(sp.get("item")));
    }

    /** Item effects triggered when an ability hits: burns, strikes, the next attack's spellblade. */
    private void onAbilityItems(U s, U t) {
        s.bladeArmed = true;
        if (t != null) {
            runeOnAbility(s, t);
        }
        for (ItemEffect e : s.f.effects) {
            if ("queimadura".equals(e.kind()) && t != null) {
                int ticks = (int) num(e.p.get("duracao"), 3);
                double per = num(e.p.get("por_s"), 0) + num(e.p.get("pdh_por_s"), 0) * s.stat(Stats.ABILITY_POWER)
                        + num(e.p.get("vida_max_alvo_por_s"), 0) * t.maxHp;
                for (int k = 1; k <= ticks; k++) {
                    at(now + k, () -> deal(s, t, per, type(e.p), e.item, DOT, 0));
                }
            } else if ("golpe".equals(e.kind()) && t != null && !"ataque".equals(e.p.getOrDefault("gatilho", "qualquer"))) {
                strike(s, t, e);
            }
        }
    }

    // ------------------------------------------------------------------ abilities

    /** The sheet of an ability, or one made from the game's text (targeted, 550 range) when there is no sheet. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> ability(U u, String slot) {
        Map<String, Object> abs = map(u.kit("habilidades"));
        if (abs != null) {
            return map(abs.get(slot));
        }
        int i = "R".equals(slot) ? 3 : "1".equals(slot) ? 0 : "2".equals(slot) ? 1 : "3".equals(slot) ? 2 : -1;
        if (i < 0 || i >= u.f.abilities.size()) {
            return null;
        }
        AbilityParser.Ability a = u.f.abilities.get(i);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nome", a.name);
        m.put("forma", "alvo");
        m.put("alcance", 550.0);
        m.put("conjuracao", 0.25);
        List<Object> cd = new ArrayList<>();
        for (double c : a.cooldown) {
            cd.add(c);
        }
        m.put("recarga", cd);
        m.put("_parsed", a);
        return m;
    }

    private double cooldown(U u, String slot, Map<String, Object> ab) {
        double base = val(ab, "recarga", u.rank(slot), u.f.level, 10);
        if ("R".equals(slot)) {
            return base / (1 + (u.stat(Stats.ABILITY_HASTE) + u.stat("Ultimate Haste")) / 100);
        }
        return base / (1 + (u.stat(Stats.ABILITY_HASTE) + u.stat("Basic Ability Haste")) / 100);
    }

    private boolean isReady(U u, String slot) {
        Map<String, Object> ab = ability(u, slot);
        if (ab == null || u.rank(slot) <= 0 || now < u.overheatedUntil) {
            return false;
        }
        if (ab.get("cargas") != null) {
            refreshCharges(u, slot, ab);
            return u.charges.getOrDefault(slot, (int) num(ab.get("cargas"), 1)) > 0
                    && now >= u.ready.getOrDefault(slot, 0.0) - 1e-9;
        }
        return now >= u.ready.getOrDefault(slot, 0.0) - 1e-9;
    }

    private void refreshCharges(U u, String slot, Map<String, Object> ab) {
        int max = (int) num(ab.get("cargas"), 1);
        int c = u.charges.getOrDefault(slot, max);
        Double at = u.chargeAt.get(slot);
        while (c < max && at != null && now >= at - 1e-9) {
            c++;
            at = c < max ? at + num(ab.get("recarga_carga"), 6) : null;
        }
        u.charges.put(slot, c);
        if (at == null) {
            u.chargeAt.remove(slot);
        } else {
            u.chargeAt.put(slot, at);
        }
    }

    private boolean inUseRange(U u, U t, Map<String, Object> ab) {
        String shape = String.valueOf(ab.get("forma"));
        double d = dist(u.x, u.y, t.x, t.y) - t.radius();
        switch (shape) {
            case "alvo":
            case "empurrao":
            case "tudo_ou_nada":
                return d <= num(ab.get("alcance"), 500);
            case "ataque":
                return d <= u.attackRange() + num(ab.get("alcance_extra"), 0) + u.radius() + 50;
            case "proprio":
                return d <= 700;
            case "area":
                return d <= num(ab.get("raio"), 300);
            case "cone":
            case "canal":
                return d <= num(ab.get("alcance"), 500);
            case "avanco":
                return d <= num(ab.get("alcance"), 400) + 100;
            case "elemental":
                return d <= 1200;
            case "campo":
                return d <= num(ab.get("alcance"), 1000);
            default:
                return d <= num(ab.get("alcance"), 600);
        }
    }

    /** Casts an ability: done, wait (not ready, moving into range) or drop (not learned). */
    private String castAbility(U u, String slot, U t, double tx, double ty, Action order) {
        Map<String, Object> ab = ability(u, slot);
        if (ab == null || u.rank(slot) <= 0) {
            if (order != null) {
                result.skipped.add(new Event(now, u.idx, describe(order) + ": habilidade não aprendida"));
            }
            return "drop";
        }
        if (!isReady(u, slot)) {
            return "wait";
        }
        String shape = String.valueOf(ab.get("forma"));
        if (List.of("alvo", "empurrao", "tudo_ou_nada").contains(shape)) {
            if (t == null || !t.targetable() || !t.visible()) {
                return "wait";
            }
            if (dist(u.x, u.y, t.x, t.y) - t.radius() > num(ab.get("alcance"), 500)) {
                moveTo(u, t.x, t.y, u.moveSpeed() * DT);   // walk into range, then cast
                return order == null ? "wait" : "range";
            }
        }
        int rank = u.rank(slot);
        // Cooldown, charges and resources.
        if (ab.get("cargas") != null) {
            int c = u.charges.getOrDefault(slot, (int) num(ab.get("cargas"), 1));
            u.charges.put(slot, c - 1);
            u.chargeAt.putIfAbsent(slot, now + num(ab.get("recarga_carga"), 6));
        }
        u.ready.put(slot, now + cooldown(u, slot, ab));
        if (!"proprio".equals(shape) && !(Boolean.TRUE.equals(ab.get("sem_dano")) && ab.get("proximo_ataque") == null)) {
            openCast(u, slot, false);   // casts that can hit a champion (not self buffs, not a bare dash)
        }
        boolean strong = spendResource(u, slot);
        if (strong) {
            u.buffs.put("fortalecido:" + slot, new double[] {1, now + 30});
        }
        u.facing = Math.atan2(ty - u.y, tx - u.x);
        double castTime = num(ab.get("conjuracao"), 0);
        u.busyUntil = now + castTime + num(ab.get("trava"), 0);
        u.busyState = "cast:" + slot;
        event(u, "usou " + slot + " (" + ab.get("nome") + ")" + (strong ? " fortalecida" : ""));
        noteUse(u, slot, String.valueOf(ab.get("nome")));
        u.lastCast = slot;
        fireAll(u, "ao_conjurar", null);
        if ("R".equals(slot)) {
            fireAll(u, "ao_usar_ult", null);
        }
        double fx = tx;
        double fy = ty;
        Runnable effect = () -> {
            if (u.dead || !u.canAct() && !"elemental".equals(shape)) {
                return;
            }
            perform(u, slot, ab, rank, t, fx, fy, strong, order);
        };
        if (castTime > 0) {
            at(now + castTime, effect);
        } else {
            effect.run();
        }
        return "done";
    }

    private void perform(U u, String slot, Map<String, Object> ab, int rank, U t, double tx, double ty, boolean strong,
                         Action order) {
        String shape = String.valueOf(ab.get("forma"));
        String label = ab.get("nome") + " (" + u.f.champion + ")";
        String source = slot + " · " + ab.get("nome");
        double dirx = Math.cos(u.facing);
        double diry = Math.sin(u.facing);
        switch (shape) {
            case "linha": {
                double range = num(ab.get("alcance"), 800);
                boolean secondStack = ab.get("segunda_carga") != null && u.buff("ntofo2") >= 2;
                Map<String, Object> second = map(ab.get("segunda_carga"));
                if (secondStack) {
                    range = num(second.get("alcance"), range);
                    u.buffs.remove("ntofo2");
                }
                Projectile p = new Projectile();
                p.id = slot + "-" + (ids++);
                p.label = label;
                p.owner = u;
                p.x = u.x;
                p.y = u.y;
                p.dx = dirx;
                p.dy = diry;
                p.speed = num(ab.get("velocidade"), 1500);
                p.left = range;
                p.width = num(ab.get("largura"), 100);
                p.pierce = Boolean.TRUE.equals(ab.get("atravessa"));
                p.stopAfterChampion = ab.get("para_apos_campeao") == null ? null : num(ab.get("para_apos_campeao"), 0);
                boolean danger = strong;
                int[] champs = new int[1];
                p.onHit = v -> {
                    double mult = danger && "calor".equals(resType(u)) ? 1.5 : 1;
                    hitAbility(u, v, ab, rank, source, mult, slot);
                    if (secondStack) {
                        forcedMove(u, v, "puxao", u.x + dirx * num(second.get("puxa"), 150), u.y + diry * num(second.get("puxa"), 150), 1500,
                                null, null);
                        stun(v, num(second.get("atordoa"), 0.8), u);
                    }
                    champs[0]++;
                    if (ab.get("segunda_carga") != null && !secondStack) {
                        u.buffs.put("ntofo2", new double[] {u.buff("ntofo2") + 1, now + 6});
                    }
                };
                Map<String, Object> pillar = map(ab.get("pilar"));
                if (pillar != null) {
                    p.onEnd = () -> {
                        double px = p.x;
                        double py = p.y;
                        at(now + num(pillar.get("atraso"), 1.125), () -> {
                            Field f = new Field();
                            f.id = "pilar-" + (ids++);
                            f.label = "Pilar de magma (" + u.f.champion + ")";
                            f.owner = u;
                            f.kind = "circle";
                            f.x = px;
                            f.y = clampY(py);
                            f.radius = num(pillar.get("raio"), 100);
                            f.from = now;
                            f.until = now + num(pillar.get("duracao"), 4);
                            f.terrain = true;
                            fields.add(f);
                        });
                    };
                }
                projectiles.add(p);
                break;
            }
            case "area": {
                double radius = num(ab.get(strong && ab.get("raio_fortalecido") != null ? "raio_fortalecido" : "raio"), 300)
                        + (u.buff("dominus") > 0 && ab.get("raio_fortalecido") != null ? 80 : 0);
                double inner = num(ab.get("raio_interno"), 0);
                flash("circle", label, u, u.x, u.y, radius, null, null, null);
                int hits = 0;
                for (U v : enemies(u)) {
                    double d = dist(u.x, u.y, v.x, v.y);
                    if (d <= radius + v.radius()) {
                        boolean handle = d < inner;
                        hitAbility(u, v, strongSpec(ab, strong), rank, source, handle ? num(ab.get("interno"), 1) : 1,
                                handle ? null : slot);
                        hits++;
                    }
                }
                renektonFury(u, slot, hits, strong);
                break;
            }
            case "cone": {
                double range = num(ab.get("alcance"), 450);
                double angle = Math.toRadians(num(ab.get("angulo"), 50));
                flash("cone", label, u, u.x, u.y, range, null, u.facing, angle);
                for (U v : enemies(u)) {
                    if (inCone(u.x, u.y, u.facing, range + v.radius(), angle, v.x, v.y)) {
                        hitAbility(u, v, ab, rank, source, 1, slot);
                    }
                }
                break;
            }
            case "canal":
                channel(u, slot, ab, rank, strong, label, source);
                break;
            case "avanco":
                dashAbility(u, slot, ab, rank, tx, ty, strong, label, source, order);
                break;
            case "alvo": {
                if (t == null || t.dead) {
                    return;
                }
                if (Boolean.TRUE.equals(ab.get("salta"))) {
                    double a = Math.atan2(u.y - t.y, u.x - t.x);
                    u.x = t.x + Math.cos(a) * (t.radius() + u.radius());
                    u.y = clampY(t.y + Math.sin(a) * (t.radius() + u.radius()));
                }
                double extra = 0;
                if (ab.get("por_acumulo") != null) {
                    double[] st = u.stacks.get(t);
                    extra = st != null && now < st[1] ? st[0] * num(ab.get("por_acumulo"), 0) : 0;
                    if (extra > 0) {
                        count(u, "marca_consumida:" + map(u.kit("acumulos")).get("nome") + ":" + slot);
                        u.consumedOn.merge(t, 1, Integer::sum);
                    }
                }
                if (ab.get("_parsed") != null) {
                    AbilityParser.Ability pa = (AbilityParser.Ability) ab.get("_parsed");
                    credit(u, source, false);
                    if (pa.damage != null) {
                        deal(u, t, pa.damage.at(rank, u::stat, u::base, t.maxHp), pa.damage.type, source, ABILITY, 0);
                    }
                    if (pa.immobilizes) {
                        breakBrittle(u, t, slot);
                        stun(t, 0.75, u);
                    }
                    onAbilityItems(u, t);
                } else {
                    hitAbility(u, t, ab, rank, source, 1 + extra, slot);
                }
                break;
            }
            case "ataque": {
                u.empowered = slot;
                u.empoweredUntil = now + num(ab.get("janela"), 4);
                u.empoweredRange = num(ab.get("alcance_extra"), 0);
                if (Boolean.TRUE.equals(ab.get("reinicia_ataque"))) {
                    u.nextAttack = now;
                }
                break;
            }
            case "proprio":
                selfBuff(u, slot, ab, rank, strong);
                break;
            case "campo":
                groundField(u, slot, ab, rank, tx, ty, strong, label, source);
                break;
            case "elemental":
                elemental(u, ab, rank, tx, ty);
                break;
            case "empurrao":
                condemn(u, t, ab, rank, label, source);
                break;
            case "tudo_ou_nada":
                allOut(u, t, ab, rank, source);
                break;
            default:
                break;
        }
    }

    private Map<String, Object> strongSpec(Map<String, Object> ab, boolean strong) {
        if (!strong || ab.get("fortalecido") == null) {
            return ab;
        }
        Map<String, Object> m = new LinkedHashMap<>(ab);
        m.put("dano", ab.get("fortalecido"));
        if (ab.get("efeitos_fortalecido") != null) {
            m.put("efeitos", ab.get("efeitos_fortalecido"));
        }
        return m;
    }

    /** Damage and effects of an ability on one enemy (mult: Danger Zone, Decimate's handle, per-stack bonus). */
    private void hitAbility(U u, U v, Map<String, Object> ab, int rank, String source, double mult, String stackSlot) {
        credit(u, source, u.dash != null && u.dash.recast);
        Map<String, Object> d = map(ab.get("dano"));
        if (d != null) {
            deal(u, v, formula(d, u, v, rank) * mult, type(d), source, ABILITY, 0);
        }
        Map<String, Object> ef = map(ab.get("efeitos"));
        if (ef != null && !v.dead) {
            Map<String, Object> e = new LinkedHashMap<>(ef);
            if (stackSlot == null) {
                e.remove("acumulos");   // Decimate's handle does not bleed
                e.remove("cura_vida_perdida");
            }
            effects(u, v, e, rank, source);
        }
        if (Boolean.TRUE.equals(ab.get("marca"))) {
            boltStack(u, v, slotOf(source));
        }
        if (map(u.kit("marcas_ksante")) != null && ef != null && Boolean.TRUE.equals(ef.get("marca"))) {
            Double on = u.ksCounted.get(v);
            if (on != null && now < on) {
                count(u, "marca_sobrescrita:" + ksMark(u));
            }
            u.ksMarks.put(v, now + num(map(u.kit("marcas_ksante")).get("duracao"), 4));
            u.ksCounted.put(v, u.ksMarks.get(v));
            count(u, "marca_aplicada:" + ksMark(u));
        }
        onAbilityItems(u, v);
    }

    /** Crowd control and other effects on a target hit by an ability. */
    private void effects(U s, U t, Map<String, Object> ef, int rank, String source) {
        double brittleBoost = 1;
        boolean immobilize = ef.get("atordoa") != null || ef.get("arremessa") != null || ef.get("puxa") != null;
        if (immobilize && t.brittleBy != null && now < t.brittleUntil && t.brittleBy.team() == s.team()) {
            Map<String, Object> fr = map(t.brittleBy.kit("fragil"));
            brittleBoost = 1 + num(fr == null ? null : fr.get("aumento_controle"), 0.3);
            breakBrittle(s, t, slotOf(source));
        }
        if (ef.get("lentidao") != null) {
            count(s, "cc_aplicado:lentidao");
            t.slows.add(new double[] {val(ef, "lentidao", rank, s.f.level, 0), now + val(ef, "duracao", rank, s.f.level, 1)});
            controlled(s, t, false);
        }
        if (ef.get("atordoa") != null) {
            stun(t, val(ef, "atordoa", rank, s.f.level, 0) * brittleBoost, s);
        }
        if (ef.get("arremessa") != null) {
            airborne(t, val(ef, "arremessa", rank, s.f.level, 0) * brittleBoost, s);
        }
        if (ef.get("puxa") != null) {
            double d = num(ef.get("puxa"), 150);
            double a = Math.atan2(t.y - s.y, t.x - s.x);
            forcedMove(s, t, "puxao", s.x + Math.cos(a) * d, s.y + Math.sin(a) * d, 1400, null, null);
            controlled(s, t, true);
        }
        if (ef.get("fragil") != null && !t.dead) {
            if (t.brittleCounted > 0 && now < t.brittleCounted) {
                count(s, "marca_sobrescrita:" + BRITTLE);   // a Brittle that does not immobilize replaces the one on
            }
            t.brittleUntil = now + num(ef.get("fragil"), 3);
            t.brittleBy = s;
            t.brittleCounted = t.brittleUntil;
            count(s, "marca_aplicada:" + BRITTLE);
        }
        if (ef.get("acumulos") != null) {
            addStacks(s, t, false);
        }
        if (ef.get("reduz_rm") != null) {
            shred(t, 1, map(ef.get("reduz_rm")), rank, key(s, source));
        }
        if (ef.get("reduz_arm") != null) {
            shred(t, 0, map(ef.get("reduz_arm")), rank, key(s, source));
        }
        if (Boolean.TRUE.equals(ef.get("remove_escudos"))) {
            t.shield.clear();
            t.shieldMagic.clear();
            t.shieldPhysical.clear();
        }
        if (ef.get("cura_vida_perdida") != null) {
            double h = num(ef.get("cura_vida_perdida"), 0) * Math.max(0, s.maxHp - s.hp);
            heal(s, h, Map.of(key(s, source), h));
        }
        if (ef.get("cura_por_campeao") != null) {
            double h = formula(map(ef.get("cura_por_campeao")), s, t, rank);
            heal(s, h, Map.of(key(s, source), h));
        }
    }

    private void shred(U t, int res, Map<String, Object> spec, int rank, String source) {
        double pct = val(spec, "pct", rank, 1, 0);
        int stack = (int) num(spec.get("acumula"), 1);
        double cur = now < t.shredUntil[res] ? t.shred[res] : 0;
        t.shred[res] = Math.min(pct * stack, cur + pct);
        t.shredUntil[res] = now + num(spec.get("duracao"), 4);
        t.shredSource[res] = source;
    }

    private void channel(U u, String slot, Map<String, Object> ab, int rank, boolean strong, String label, String source) {
        double duration = num(ab.get("duracao"), 1);
        double interval = num(ab.get("intervalo"), 0.25);
        int ticks = (int) Math.round(duration / interval);
        double mult = strong && "calor".equals(resType(u)) ? 1.5 : 1;
        u.busyUntil = now + duration;
        u.busyState = "channel:" + slot;
        if (Boolean.TRUE.equals(ab.get("imparavel")) || Boolean.TRUE.equals(ab.get("imune_deslocamento"))) {
            u.immuneUntil = now + duration;
            u.immuneCc = Boolean.TRUE.equals(ab.get("imparavel")) ? CC_IGNORE
                    : String.valueOf(ab.getOrDefault("cc_durante_avanco", CC_AFTER));
        }
        double advance = num(ab.get("avanca"), 0);
        double start = now;
        for (int k = 1; k <= ticks; k++) {
            boolean last = k == ticks;
            boolean first = k == 1;
            at(start + k * interval, () -> {
                if (u.dead || !u.canAct() && !Boolean.TRUE.equals(ab.get("imparavel"))) {
                    return;
                }
                if (advance > 0) {
                    double step = advance * u.moveSpeed() * interval;
                    moveTo(u, u.x + Math.cos(u.facing) * step, u.y + Math.sin(u.facing) * step, step);
                }
                U t = target(u);
                if (t != null && ab.get("angulo") != null) {   // the flamethrower follows its target
                    u.facing = Math.atan2(t.y - u.y, t.x - u.x);
                }
                double range = num(ab.get("alcance"), 500);
                Double angle = ab.get("angulo") == null ? null : Math.toRadians(num(ab.get("angulo"), 60));
                double width = num(ab.get("largura"), 300);
                if (angle != null) {
                    flash("cone", label, u, u.x, u.y, range, null, u.facing, angle);
                } else {
                    flash("rect", label, u, u.x + Math.cos(u.facing) * range / 2, u.y + Math.sin(u.facing) * range / 2,
                            null, range, u.facing, width);
                }
                Map<String, Object> d = map(ab.get("dano"));
                for (U v : enemies(u)) {
                    boolean in = angle != null ? inCone(u.x, u.y, u.facing, range + v.radius(), angle, v.x, v.y)
                            : inRect(u.x + Math.cos(u.facing) * range / 2, u.y + Math.sin(u.facing) * range / 2, u.facing,
                            range, width + 2 * v.radius(), v.x, v.y);
                    if (!in) {
                        continue;
                    }
                    credit(u, source, false);
                    if (d != null) {
                        deal(u, v, formula(d, u, v, rank) / ticks * mult, type(d), source, ABILITY, 0);
                    }
                    if (last && ab.get("ultimo_golpe") != null) {
                        effects(u, v, map(ab.get("ultimo_golpe")), rank, source);
                    }
                    if (first) {
                        onAbilityItems(u, v);
                    }
                }
            });
        }
    }

    private void dashAbility(U u, String slot, Map<String, Object> ab, int rank, double tx, double ty, boolean strong,
                             String label, String source, Action order) {
        Map<String, Object> charge = map(ab.get("carregar"));
        if (charge != null) {
            // K'Sante's Path Maker: charges (less damage taken), then dashes; the stun grows with the charge.
            double c = order != null && order.charge != null ? order.charge : num(charge.get("max"), 1);
            c = Math.max(num(charge.get("min"), 0.4), Math.min(num(charge.get("max"), 1), c));
            double charged = c;
            u.busyUntil = now + c;
            u.busyState = "channel:" + slot;
            u.damageReduction = num(charge.get("reducao_dano"), 0.3);
            u.damageReductionUntil = now + c;
            at(now + c, () -> {
                if (!u.dead && u.canAct()) {
                    Map<String, Object> ef = new LinkedHashMap<>(map(ab.get("efeitos")));
                    double min = num(ef.get("atordoa"), 0.5);
                    double max = num(ef.get("atordoa_max"), min);
                    double span = num(charge.get("max"), 1) - num(charge.get("min"), 0.4);
                    ef.put("atordoa", min + (max - min) * (span > 0 ? (charged - num(charge.get("min"), 0.4)) / span : 1));
                    Map<String, Object> spec = new LinkedHashMap<>(ab);
                    spec.put("efeitos", ef);
                    startDash(u, slot, spec, rank, u.x + Math.cos(u.facing) * 1000, u.y + Math.sin(u.facing) * 1000, strong,
                            label, source);
                }
            });
            return;
        }
        startDash(u, slot, ab, rank, tx, ty, strong, label, source);
    }

    private void startDash(U u, String slot, Map<String, Object> ab, int rank, double tx, double ty, boolean strong,
                           String label, String source) {
        double d = num(ab.get("alcance"), 400);   // the dashes of the sheets go their full distance (stop at walls)
        double a = Math.abs(tx - u.x) + Math.abs(ty - u.y) < 1e-6 ? u.facing : Math.atan2(ty - u.y, tx - u.x);
        Dash dash = new Dash();
        dash.tx = u.x + Math.cos(a) * d;
        dash.ty = u.y + Math.sin(a) * d;
        dash.speed = num(ab.get("velocidade"), 1200) + (Boolean.TRUE.equals(ab.get("velocidade_mais_vdm")) ? u.moveSpeed() : 0);
        dash.hitRadius = num(ab.get("raio"), 100);
        immunity(dash, ab);
        u.facing = a;
        boolean noDamage = Boolean.TRUE.equals(ab.get("sem_dano"));
        Map<String, Object> spec = strongSpec(ab, strong);
        int[] hits = new int[1];
        dash.onHit = noDamage ? null : v -> {
            hitAbility(u, v, spec, rank, source, 1, slot);
            if (map(spec.get("efeitos")) != null && map(spec.get("efeitos")).get("empurra") != null) {
                double pa = Math.atan2(v.y - u.y, v.x - u.x);
                double pd = num(map(spec.get("efeitos")).get("empurra"), 200);
                forcedMove(u, v, "empurrao", v.x + Math.cos(pa) * pd, v.y + Math.sin(pa) * pd, 1200, null, null);
                controlled(u, v, true);
            }
            hits[0]++;
        };
        Map<String, Object> wall = map(ab.get("terreno"));
        if (wall != null) {
            dash.onWall = () -> {
                double r = num(wall.get("raio"), 360);
                flash("circle", "Onda de choque (" + u.f.champion + ")", u, u.x, u.y, r, null, null, null);
                for (U v : enemies(u)) {
                    if (dist(u.x, u.y, v.x, v.y) <= r + v.radius()) {
                        if (!dash.hit.contains(v)) {
                            hitAbility(u, v, ab, rank, source, 1, slot);
                        }
                        Map<String, Object> ef = new LinkedHashMap<>();
                        ef.put("arremessa", wall.get("arremessa"));
                        effects(u, v, ef, rank, source);
                    }
                }
                if (Boolean.TRUE.equals(ab.get("destroi_pilar"))) {
                    fields.removeIf(f -> f.terrain && dist(f.x, f.y, u.x, u.y) <= f.radius + u.radius() + 60);
                }
            };
        }
        Map<String, Object> next = map(ab.get("proximo_ataque"));
        Map<String, Object> shieldSpec = map(ab.get("escudo"));
        Map<String, Object> rec = map(ab.get("reconjurar"));
        dash.onEnd = () -> {
            if (next != null) {
                u.empowered = slot;
                u.empoweredUntil = now + num(next.get("janela"), 4);
                u.empoweredRange = 0;
            }
            if (Boolean.TRUE.equals(ab.get("reinicia_ataque"))) {
                u.nextAttack = now;
            }
            if (ab.get("invisivel_com_R") != null && u.buff("hora_final") > 0) {
                u.invisibleUntil = now + num(ab.get("invisivel_com_R"), 1);
            }
            if (shieldSpec != null) {
                addShield(u, formula(shieldSpec, u, u, rank), key(u, source), now + num(ab.get("duracao_escudo"), 2), "any");
            }
            if (rec != null && (!Boolean.TRUE.equals(rec.get("se_acertou")) || hits[0] > 0)
                    && !u.recast.containsKey(slot + ":used")) {
                u.recast.put(slot, new double[] {now, now + num(rec.get("janela"), 4)});
            }
            u.recast.remove(slot + ":used");
            renektonFury(u, slot, hits[0], strong);
        };
        u.dash = dash;
        u.busyState = "dash";
        flash("line", label, u, u.x, u.y, null, d, a, dash.hitRadius * 2);
    }

    private void selfBuff(U u, String slot, Map<String, Object> ab, int rank, boolean strong) {
        double danger = strong && "calor".equals(resType(u)) ? 1.5 : 1;
        double duration = val(ab, "duracao", rank, u.f.level, 0);
        String src = key(u, slot + " · " + ab.get("nome"));
        if (ab.get("escudo") != null) {
            addShield(u, formula(map(ab.get("escudo")), u, u, rank) * danger, src, now + duration, "any");
        }
        if (ab.get("velocidade_movimento") != null) {
            u.speedUps.add(new double[] {val(ab, "velocidade_movimento", rank, u.f.level, 0) * danger,
                now + num(ab.get("duracao_velocidade"), duration)});
        }
        if (ab.get("velocidade_ataque") != null) {
            u.buffs.put("vel_ataque", new double[] {val(ab, "velocidade_ataque", rank, u.f.level, 0), now + duration});
        }
        if (ab.get("vampirismo") != null) {
            u.buffs.put("vampirismo", new double[] {num(ab.get("vampirismo"), 0), now + duration});
        }
        if (ab.get("dda") != null) {
            u.buffs.put("dda", new double[] {val(ab, "dda", rank, u.f.level, 0), now + duration});
            u.buffs.put("hora_final", new double[] {1, now + duration});
        }
        if (ab.get("alcance_extra") != null) {
            u.buffs.put("alcance", new double[] {num(ab.get("alcance_extra"), 0), now + duration});
        }
        if (ab.get("vida_extra") != null) {
            double extra = val(ab, "vida_extra", rank, u.f.level, 0);
            u.maxHp += extra;
            u.hp += extra;
            at(now + duration, () -> {   // the bonus health leaves; what is left of it stays as health (WR)
                u.maxHp -= extra;
                u.hp = Math.min(u.hp, u.maxHp);
            });
            u.buffs.put("dominus", new double[] {1, now + duration});
        }
        Map<String, Object> aura = map(ab.get("aura"));
        if (aura != null) {
            double interval = num(aura.get("intervalo"), 0.5);
            int ticks = (int) Math.round(duration / interval);
            String label = ab.get("nome") + " (" + u.f.champion + ")";
            for (int k = 1; k <= ticks; k++) {
                at(now + k * interval, () -> {
                    if (u.dead) {
                        return;
                    }
                    flash("circle", label, u, u.x, u.y, num(aura.get("raio"), 375), null, null, null);
                    for (U v : enemies(u)) {
                        if (dist(u.x, u.y, v.x, v.y) <= num(aura.get("raio"), 375) + v.radius()) {
                            deal(u, v, formula(map(aura.get("dano")), u, v, rank), type(map(aura.get("dano"))),
                                    slot + " · " + ab.get("nome"), DOT, 0);
                        }
                    }
                    if (ab.get("furia_por_s") != null) {
                        gainFury(u, num(ab.get("furia_por_s"), 0) * interval);
                    }
                });
            }
        }
        if (ab.get("furia_ao_usar") != null) {
            gainFury(u, num(ab.get("furia_ao_usar"), 0));
        }
    }

    private void groundField(U u, String slot, Map<String, Object> ab, int rank, double tx, double ty, boolean strong,
                             String label, String source) {
        double range = num(ab.get("alcance"), 1700);
        double d = Math.min(range, dist(u.x, u.y, tx, ty));
        double a = Math.atan2(ty - u.y, tx - u.x);
        double len = num(ab.get("comprimento"), 1000);
        // The field starts at the aimed point and runs along the cast direction.
        double sx = u.x + Math.cos(a) * Math.max(0, d - len / 2);
        double sy = u.y + Math.sin(a) * Math.max(0, d - len / 2);
        Field f = new Field();
        f.id = "campo-" + (ids++);
        f.label = label;
        f.owner = u;
        f.kind = "rect";
        f.x = sx + Math.cos(a) * len / 2;
        f.y = clampY(sy + Math.sin(a) * len / 2);
        f.angle = a;
        f.length = len;
        f.width = num(ab.get("largura"), 390);
        f.from = now + num(ab.get("atraso"), 0.75);
        f.until = f.from + num(ab.get("duracao"), 4.5);
        f.interval = num(ab.get("intervalo"), 0.5);
        f.nextTick = f.from;
        double mult = strong && "calor".equals(resType(u)) ? 1.5 : 1;
        f.onTick = v -> hitAbility(u, v, ab, rank, source, mult, slot);
        fields.add(f);
    }

    private void elemental(U u, Map<String, Object> ab, int rank, double tx, double ty) {
        double a = Math.atan2(ty - u.y, tx - u.x);
        Elemental e = new Elemental();
        e.owner = u;
        double dist = num(ab.get("distancia"), 2550);
        e.x = u.x + Math.cos(a) * dist;
        e.y = clampY(u.y + Math.sin(a) * dist);
        double back = Math.atan2(u.y - e.y, u.x - e.x);
        e.dx = Math.cos(back);
        e.dy = Math.sin(back);
        e.speed = num(ab.get("velocidade"), 450);
        e.maxSpeed = num(ab.get("velocidade_final"), 1250);
        e.accel = num(ab.get("aceleracao"), 400);
        e.left = dist + 200;
        e.width = num(ab.get("largura"), 340);
        e.spec = ab;
        e.rank = rank;
        Map<String, Object> rec = map(ab.get("reconjurar"));
        e.recastFrom = now + num(rec == null ? null : rec.get("apos"), 1.25);
        elementals.add(e);
        u.elemental = e;
        u.recast.put("R", new double[] {e.recastFrom, now + 30});
    }

    /** Recast of an ability (Ornn's headbutt, Renekton's Dice): done, wait or drop. */
    private String recast(U u, String slot, U t, double tx, double ty) {
        double[] w = u.recast.get(slot);
        if (w == null || now > w[1]) {
            return "wait";   // not cast yet (or the window closed): the order waits, and is logged if it never happens
        }
        if (now < w[0] - 1e-9) {
            return "wait";
        }
        Map<String, Object> ab = ability(u, slot);
        if (ab == null) {
            return "drop";
        }
        u.recast.remove(slot);
        if ("elemental".equals(ab.get("forma"))) {
            Elemental e = u.elemental;
            if (e == null || e.redirected) {
                return "drop";
            }
            Map<String, Object> rec = map(ab.get("reconjurar"));
            // Like a player: the headbutt waits for the elemental to come within reach.
            double reach = e.width / 2 + u.radius() + num(rec.get("cabecada"), 200) + 60;
            if (dist(u.x, u.y, e.x, e.y) > reach) {
                u.recast.put(slot, w);
                u.recastTry.put(slot, "fora de alcance");
                return "wait";
            }
            u.recastUsed.add(w);
            openCast(u, slot, true);
            tx = e.x;
            ty = e.y;
            double a = Math.atan2(ty - u.y, tx - u.x);
            u.facing = a;
            double headbutt = num(rec.get("cabecada"), 200);
            Dash dash = new Dash();
            dash.tx = u.x + Math.cos(a) * headbutt;
            dash.ty = u.y + Math.sin(a) * headbutt;
            dash.speed = 1600;
            dash.hitRadius = 0;
            immunity(dash, rec);
            U near = nearestEnemy(u);
            double before = near == null ? 0 : dist(u.x, u.y, near.x, near.y);
            dash.onEnd = () -> {
                if (near != null && dist(u.x, u.y, near.x, near.y) > before + 50) {
                    count(u, "reconjuracao_fuga:" + slot);   // the headbutt took it away from its nearest enemy
                }
                if (dist(u.x, u.y, e.x, e.y) <= e.width / 2 + u.radius() + 150 && elementals.contains(e)) {
                    e.redirected = true;
                    e.dx = Math.cos(a);
                    e.dy = Math.sin(a);
                    e.speed = num(rec.get("velocidade"), 1250);
                    e.maxSpeed = e.speed;
                    e.accel = 0;
                    e.left = num(rec.get("distancia"), 1500);
                    e.hit.clear();
                    e.champsHit = 0;
                    event(u, "devolveu o elemental (cabeçada)");
                } else {
                    event(u, "cabeçada sem acertar o elemental");
                }
            };
            u.dash = dash;
            u.busyState = "dash";
            event(u, "reconjurou R (cabeçada)");
            return "done";
        }
        // A second dash of the same ability (Renekton's Dice): no cooldown, empowered when Fury allows.
        u.recastUsed.add(w);
        openCast(u, slot, true);
        u.recast.put(slot + ":used", new double[] {now, now});
        boolean strong = spendResource(u, slot);
        event(u, "reconjurou " + slot + " (" + ab.get("nome") + ")" + (strong ? " fortalecida" : ""));
        startDash(u, slot, ab, u.rank(slot), tx, ty, strong, ab.get("nome") + " (" + u.f.champion + ")",
                slot + " · " + ab.get("nome"));
        if (u.dash != null) {
            u.dash.recast = true;
        }
        return "done";
    }

    private void condemn(U u, U t, Map<String, Object> ab, int rank, String label, String source) {
        if (t == null || t.dead) {
            return;
        }
        Projectile p = new Projectile();
        p.id = "condenar-" + (ids++);
        p.label = label;
        p.owner = u;
        p.x = u.x;
        p.y = u.y;
        p.speed = num(ab.get("velocidade_projetil"), 2200);
        p.only = t;
        p.width = 40;
        p.left = 3000;
        p.onHit = v -> {
            credit(u, source, false);
            Map<String, Object> d = map(ab.get("dano"));
            deal(u, v, formula(d, u, v, rank), type(d), source, ABILITY, 0);
            boltStack(u, v, slotOf(source));
            double a = Math.atan2(v.y - u.y, v.x - u.x);
            double dist = num(ab.get("distancia"), 475);
            Map<String, Object> wall = map(ab.get("terreno"));
            forcedMove(u, v, "empurrao", v.x + Math.cos(a) * dist, v.y + Math.sin(a) * dist, num(ab.get("velocidade"), 1000), () -> {
                Map<String, Object> wd = map(wall.get("dano"));
                deal(u, v, formula(wd, u, v, rank), type(wd), source + " (parede)", ABILITY, 0);
                stun(v, num(wall.get("atordoa"), 1.5), u);
                event(u, "prendeu " + v.f.name + " na parede");
            }, null);
            controlled(u, v, true);
            onAbilityItems(u, v);
        };
        projectiles.add(p);
    }

    private void allOut(U u, U t, Map<String, Object> ab, int rank, String source) {
        if (t == null || t.dead) {
            return;
        }
        credit(u, source, false);
        double a = u.facing;
        double push = num(ab.get("empurra"), 300);
        double wallAt = num(ab.get("terreno_ate"), 350);
        Map<String, Object> wall = map(ab.get("terreno"));
        // Into a wall within reach: the target is slammed and stunned; else pushed and briefly stunned.
        double ex = t.x + Math.cos(a) * (push + wallAt);
        double ey = t.y + Math.sin(a) * (push + wallAt);
        boolean hitsWall = Math.abs(ey) > halfWidth - t.radius() || ex < 0 || ex > length;
        controlled(u, t, true);
        forcedMove(u, t, "empurrao", t.x + Math.cos(a) * push, t.y + Math.sin(a) * push, 1800, null, () -> {
            if (hitsWall) {
                Map<String, Object> wd = map(wall.get("dano"));
                deal(u, t, formula(wd, u, t, rank), type(wd), source + " (parede)", ABILITY, 0);
                stun(t, num(wall.get("atordoa"), 0.5), u);
            } else {
                Map<String, Object> d = map(ab.get("dano"));
                deal(u, t, formula(d, u, t, rank), type(d), source, ABILITY, 0);
                effects(u, t, map(ab.get("efeitos")), rank, source);
            }
        });
        double behind = 175;
        u.x = t.x + Math.cos(a) * (push - behind);
        u.y = clampY(t.y + Math.sin(a) * (push - behind));
        formOn(u, String.valueOf(ab.get("nome")), num(ab.get("duracao"), 15));
        onAbilityItems(u, t);
    }

    // ------------------------------------------------------------------ resources and passives

    private String resType(U u) {
        Map<String, Object> res = map(u.kit("recurso"));
        return res == null ? null : String.valueOf(res.get("tipo"));
    }

    /** Spends the resource of a cast; true when the ability is empowered (Renekton 50 Fury, Rumble's Danger Zone). */
    private boolean spendResource(U u, String slot) {
        Map<String, Object> res = map(u.kit("recurso"));
        if (res == null) {
            return false;
        }
        if ("furia".equals(res.get("tipo"))) {
            if (!"R".equals(slot) && u.resource >= num(res.get("limiar"), 50)) {
                u.resource -= num(res.get("limiar"), 50);
                return true;
            }
            return false;
        }
        if ("calor".equals(res.get("tipo"))) {
            if ("R".equals(slot)) {
                return false;
            }
            boolean danger = u.resource >= num(res.get("zona_de_perigo"), 50);
            u.resource += num(res.get("por_habilidade"), 20);
            if (u.resource >= num(res.get("superaquece"), 150)) {
                u.overheatedUntil = now + num(res.get("superaquecido_por"), 4);
                u.resource = 0;
                formOn(u, firstForm(u), num(res.get("superaquecido_por"), 4));
                event(u, "superaqueceu");
            }
            return danger;
        }
        return false;
    }

    private String firstForm(U u) {
        Map<String, Object> forms = map(u.kit("efeitos_de_forma"));
        return forms == null || forms.isEmpty() ? "" : forms.keySet().iterator().next();
    }

    private void gainFury(U u, double amount) {
        Map<String, Object> res = map(u.kit("recurso"));
        if (res == null || !"furia".equals(res.get("tipo"))) {
            return;
        }
        double mult = u.hp < 0.5 * u.maxHp ? 1.5 : 1;
        u.resource = Math.min(num(res.get("max"), 100), u.resource + amount * mult);
    }

    private void renektonFury(U u, String slot, int champHits, boolean strong) {
        Map<String, Object> res = map(u.kit("recurso"));
        if (res == null || !"furia".equals(res.get("tipo")) || strong || "R".equals(slot)) {
            return;
        }
        gainFury(u, Math.min(30, champHits * num(res.get("por_campeao"), 10)));
    }

    private void gainResourceOnHit(U s, U t, boolean champion) {
        Map<String, Object> res = map(s.kit("recurso"));
        if (res != null && "furia".equals(res.get("tipo")) && champion && !s.buffs.containsKey("fortalecido:2")) {
            gainFury(s, num(res.get("por_campeao"), 10));
        }
    }

    private void formOn(U u, String form, double duration) {
        if (form == null || form.isEmpty() || !u.in.forms.containsKey(form)) {
            return;
        }
        double before = u.stat(Stats.MAX_HEALTH);
        u.formUntil.put(form, now + duration);
        double after = u.stat(Stats.MAX_HEALTH);
        if (Math.abs(after - before) > 1e-9) {
            u.maxHp = Math.max(1, u.maxHp + after - before);
            u.hp = Math.min(u.hp, u.maxHp);
        }
        event(u, form + " ativo por " + fmt(duration) + "s");
        at(now + duration + 1e-6, () -> {
            if (now >= u.formUntil.getOrDefault(form, 0.0) - 1e-6) {
                double hpNow = u.stat(Stats.MAX_HEALTH);
                double diff = hpNow - after;
                if (Math.abs(diff) > 1e-9) {
                    u.maxHp = Math.max(1, u.maxHp + diff);
                    u.hp = Math.min(u.hp, u.maxHp);
                }
            }
        });
    }

    /** Darius' bleed: 1 stack per hit, all at once with Noxian Might; 5 on a champion turn Noxian Might on. */
    private void addStacks(U s, U t, boolean attack) {
        Map<String, Object> m = map(s.kit("acumulos"));
        if (m == null || t.dead) {
            return;
        }
        int max = (int) num(m.get("max"), 5);
        boolean bleeding = s.stacks.values().stream().anyMatch(x -> now < x[1] && x[0] > 0);
        double[] st = s.stacks.computeIfAbsent(t, k -> new double[2]);
        if (now >= st[1]) {
            st[0] = 0;
        }
        String form = (String) m.get("cheio_ativa");
        boolean might = form != null && now < s.formUntil.getOrDefault(form, -1.0);
        st[0] = might ? max : Math.min(max, st[0] + 1);
        st[1] = now + num(m.get("duracao"), 5);
        count(s, "marca_aplicada:" + m.get("nome"));
        s.stackCounted.put(t, st[1]);
        if (!bleeding) {
            s.nextStackTick = now + 1;
        }
        if (st[0] >= max && form != null && !might) {
            Map<String, Object> forms = map(s.kit("efeitos_de_forma"));
            formOn(s, form, num(map(forms == null ? null : forms.get(form)) == null ? null
                    : map(forms.get(form)).get("duracao"), 5));
        }
    }

    private void tickStacks(U s) {
        Map<String, Object> m = map(s.kit("acumulos"));
        if (m == null || s.stacks.isEmpty() || now < s.nextStackTick - 1e-9) {
            return;
        }
        s.nextStackTick = now + 1;
        Map<String, Object> per = map(m.get("por_s"));
        for (Map.Entry<U, double[]> e : s.stacks.entrySet()) {
            double[] st = e.getValue();
            if (now < st[1] && st[0] > 0 && !e.getKey().dead) {
                deal(s, e.getKey(), formula(per, s, e.getKey(), 1) * st[0], type(per), String.valueOf(m.get("nome")), DOT, 0);
            }
        }
    }

    /** Vayne's Silver Bolts: the third hit in a row on the same target. */
    private void boltHit(U s, U t) {
        boltStack(s, t, "ataque");
    }

    private void boltStack(U s, U t, String via) {
        Map<String, Object> m = map(s.kit("marcas"));
        if (m == null || s.rank(String.valueOf(m.get("habilidade"))) <= 0) {
            return;
        }
        String mark = boltMark(s);
        for (U k : new ArrayList<>(s.boltCounted.keySet())) {
            if (k != t && now < s.boltCounted.get(k)) {
                count(s, "marca_expirou:" + mark);   // lost by hitting another enemy
            }
            if (k != t) {
                s.boltCounted.remove(k);
            }
        }
        s.bolts.keySet().removeIf(k -> k != t);   // stacks fall off when Vayne hits another enemy
        double[] st = s.bolts.computeIfAbsent(t, k -> new double[2]);
        if (now >= st[1]) {
            st[0] = 0;
        }
        st[0]++;
        st[1] = now + num(m.get("duracao"), 3.5);
        count(s, "marca_aplicada:" + mark);
        s.boltCounted.put(t, st[1]);
        if (st[0] >= num(m.get("max"), 3)) {
            st[0] = 0;
            s.boltCounted.remove(t);
            count(s, "marca_consumida:" + mark + ":" + via);
            s.consumedOn.merge(t, 1, Integer::sum);
            Map<String, Object> d = map(m.get("dano"));
            int rank = s.rank(String.valueOf(m.get("habilidade")));
            double v = Math.max(formula(d, s, t, rank), val(d, "minimo", rank, s.f.level, 0));
            deal(s, t, v, type(d), "2 · Dardos de Prata", ABILITY, 0);
        }
    }

    // ------------------------------------------------------------------ items and spells

    private String useItem(U u, String name, U t) {
        if (name == null || !u.f.items.containsKey(name)) {
            return "drop";
        }
        if (now < u.ready.getOrDefault(name, 0.0) - 1e-9) {
            return "wait";
        }
        double cd = u.in.activeCooldowns.getOrDefault(name, 60.0);
        boolean used = false;
        for (ItemEffect e : u.f.effects) {
            if (e.item.equals(name) && "ativo".equals(e.kind())) {
                if (t == null) {
                    return "wait";
                }
                double dmg = level(e.p, "dano", u.f.level) + num(e.p.get("dda"), 0) * u.stat(Stats.ATTACK_DAMAGE)
                        + num(e.p.get("dda_base"), 0) * u.base(Stats.ATTACK_DAMAGE)
                        + num(e.p.get("dda_bonus"), 0) * u.bonus(Stats.ATTACK_DAMAGE)
                        + num(e.p.get("pdh"), 0) * u.stat(Stats.ABILITY_POWER) + num(e.p.get("vida_max_alvo"), 0) * t.maxHp;
                deal(u, t, dmg, type(e.p), e.item, ITEM, 0);
                double heal = num(e.p.get("cura_dda"), 0) * u.stat(Stats.ATTACK_DAMAGE);
                if (heal > 0) {
                    heal(u, heal, Map.of(key(u, e.item), heal));
                }
                cd = num(e.p.get("recarga"), cd);
                used = true;
            }
        }
        // Actives of the planner's effects: shields (Placa Gargolítica, Medalhão), heals (Redenção), stasis (Zhonya),
        // cleanse (Cimitarra Mercurial).
        for (T x : u.trigs) {
            if ("ativo".equals(x.d.mode) && x.d.source.equals(name)) {
                fire(u, x);
                used = true;
            }
        }
        if (!used) {
            Double amount = u.in.f.shieldSources.get(name);
            if (amount != null) {
                addShield(u, amount, key(u, name), now + 2.5, "any");
                used = true;
            } else if ("Ampulheta de Zhonya".equals(name) || "Armaguarda da Caçadora".equals(name)) {
                u.stasisUntil = now + 2.5;
                u.stasisUsed = true;
                used = true;
            }
        }
        if (!used) {
            return "drop";
        }
        u.ready.put(name, now + cd);
        event(u, "usou " + name);
        noteUse(u, name, name);
        proc(u, "item", name);
        return "done";
    }

    private String useSpellNimbus(U u, String name, U t, Double x, Double y) {
        String out = useSpellBase(u, name, t, x, y);
        if ("done".equals(out)) {
            for (ItemEffect e : u.f.effects) {
                if ("nimbus".equals(e.kind())) {
                    u.speedUps.add(new double[] {num(e.p.get("pct"), 0.25), now + num(e.p.get("duracao"), 3)});
                    led(key(u, e.item));
                }
            }
        }
        return out;
    }

    private String useSpellBase(U u, String name, U t, Double x, Double y) {
        if (name == null || !u.f.spells.contains(name)) {
            return "drop";
        }
        if (now < u.ready.getOrDefault(name, 0.0) - 1e-9) {
            return "wait";
        }
        Map<String, Object> spell = catalog.spell(name);
        double cd = u.in.activeCooldowns.getOrDefault(name, 100.0);
        switch (name) {
            case "Flash": {
                double tx = x != null ? x : t != null ? t.x : u.x + Math.cos(u.facing) * 400;
                double ty = y != null ? y : t != null ? t.y : u.y;
                double a = Math.atan2(ty - u.y, tx - u.x);
                double d = Math.min(400, dist(u.x, u.y, tx, ty));
                u.x = clampX(u.x + Math.cos(a) * d);
                u.y = clampY(u.y + Math.sin(a) * d);
                u.dashedAt = now;
                break;
            }
            case "Incendiar":
                if (t == null || dist(u.x, u.y, t.x, t.y) > 600) {
                    return "wait";
                }
                double per = level(spell, "dano", u.f.level) / 5;
                for (int k = 1; k <= 5; k++) {
                    at(now + k, () -> deal(u, t, per, "verdadeiro", name, DOT, 0));
                }
                applyGw(t, num(spell.get("feridas"), 0.4), 5, key(u, name));
                break;
            case "Exaustão":
                if (t == null || dist(u.x, u.y, t.x, t.y) > 650) {
                    return "wait";
                }
                t.exhaust = num(spell.get("reduz_dano_causado"), 0.4);
                t.exhaustUntil = now + num(spell.get("duracao"), 2.5);
                t.slows.add(new double[] {0.35, now + 2.5});
                controlled(u, t, false);
                break;
            case "Barreira":
                addShield(u, level(spell, "escudo", u.f.level), key(u, name), now + 2.5, "any");
                break;
            case "Curar": {
                double v = level(spell, "cura", u.f.level);
                heal(u, v, Map.of(key(u, name), v));
                u.speedUps.add(new double[] {0.3, now + 2});
                break;
            }
            case "Fantasma":
                u.speedUps.add(new double[] {0.5, now + 3});
                u.speedUps.add(new double[] {0.25, now + 8});
                break;
            case "Purificar":
                // removes stuns, slows and Exhaust (not knock-ups or displacements); stuns are void for a moment after
                u.stunUntil = Math.min(u.stunUntil, now);
                u.slows.clear();
                u.exhaustUntil = Math.min(u.exhaustUntil, now);
                u.cleanseUntil = now + num(spell == null ? null : spell.get("imunidade"), 0.25);
                break;
            default:
                return "drop";
        }
        u.ready.put(name, now + cd);
        event(u, "usou " + name);
        noteUse(u, name, name);
        return "done";
    }

    /** Opens the record of a use (frames only: training never pays for it); what it does is added as it happens. */
    private void noteUse(U u, String key, String name) {
        if (!recordFrames) {
            return;
        }
        ArenaModel.Use use = new ArenaModel.Use();
        use.t = Math.round(now * 1000) / 1000.0;
        use.key = key;
        use.name = name;
        u.r.uses.add(use);
        u.lastUse.put(key, use);
    }

    /** The last use a damage / shield source belongs to: "slot · name" for abilities, the name for actives and spells. */
    private static ArenaModel.Use useOf(U u, String source) {
        int dot = source.indexOf(" · ");
        if (dot > 0 && u.lastUse.containsKey(source.substring(0, dot))) {
            return u.lastUse.get(source.substring(0, dot));
        }
        return u.lastUse.get(source);
    }

    // ------------------------------------------------------------------ movement and crowd control

    /** Moves a unit towards a point by at most `step`; true when there. Walls of the lane and pillars stop it. */
    private boolean moveTo(U u, double tx, double ty, double step) {
        double d = dist(u.x, u.y, tx, ty);
        if (d <= 1e-6) {
            return true;
        }
        double a = Math.atan2(ty - u.y, tx - u.x);
        u.facing = a;
        double s = Math.min(step, d);
        double nx = clampX(u.x + Math.cos(a) * s);
        double ny = clampY(u.y + Math.sin(a) * s);
        if (blocked(nx, ny, u.radius())) {
            // slide around the pillar
            double side = a + Math.PI / 2;
            nx = clampX(u.x + Math.cos(side) * s);
            ny = clampY(u.y + Math.sin(side) * s);
            if (blocked(nx, ny, u.radius())) {
                return false;
            }
        }
        u.x = nx;
        u.y = ny;
        return s >= d - 1e-6;
    }

    private void moveDash(U u) {
        Dash dash = u.dash;
        double d = dist(u.x, u.y, dash.tx, dash.ty);
        double s = Math.min(dash.speed * DT, d);
        double a = Math.atan2(dash.ty - u.y, dash.tx - u.x);
        double nx = u.x + Math.cos(a) * s;
        double ny = u.y + Math.sin(a) * s;
        boolean wall = Math.abs(ny) > halfWidth - u.radius() || nx < 0 || nx > length || blocked(nx, ny, u.radius());
        if (!wall) {
            u.x = nx;
            u.y = ny;
        }
        if (dash.onHit != null) {
            for (U v : enemies(u)) {
                if (!dash.hit.contains(v) && dist(u.x, u.y, v.x, v.y) <= dash.hitRadius + v.radius()) {
                    dash.hit.add(v);
                    dash.onHit.accept(v);
                }
            }
        }
        if (wall || s >= d - 1e-6) {
            u.dash = null;
            u.dashedAt = now;
            u.y = clampY(u.y);
            u.x = clampX(u.x);
            if (wall && dash.onWall != null) {
                dash.onWall.run();
            }
            if (dash.onEnd != null) {
                dash.onEnd.run();
            }
            u.busyUntil = Math.max(u.busyUntil, now);
            if (dash.heldStunUntil > now + 1e-9) {
                stun(u, dash.heldStunUntil - now, dash.heldStunBy);   // the stun lands now, with the time it has left
            }
        }
    }

    private void forcedMove(U by, U t, String type, double tx, double ty, double speed, Runnable onWall, Runnable onEnd) {
        if (t.dead || !t.targetable()) {
            return;
        }
        if (displacementImmune(t)) {
            count(t, "deslocamento_anulado:" + type);
            return;
        }
        ccNoted(by, t, type, controlLeft(t), dist(t.x, t.y, tx, ty) / Math.max(1, speed));
        Forced f = new Forced();
        f.tx = tx;
        f.ty = ty;
        f.speed = speed;
        f.onWall = onWall;
        f.onEnd = onEnd;
        t.forced = f;
        t.dash = null;
    }

    private void moveForced(U t) {
        Forced f = t.forced;
        double d = dist(t.x, t.y, f.tx, f.ty);
        double s = Math.min(f.speed * DT, d);
        double a = Math.atan2(f.ty - t.y, f.tx - t.x);
        double nx = t.x + Math.cos(a) * s;
        double ny = t.y + Math.sin(a) * s;
        boolean wall = Math.abs(ny) > halfWidth - t.radius() || nx < 0 || nx > length || blocked(nx, ny, t.radius());
        if (wall) {
            t.forced = null;
            t.x = clampX(t.x);
            t.y = clampY(t.y);
            if (f.onWall != null) {
                f.onWall.run();
            }
            if (f.onEnd != null) {
                f.onEnd.run();
            }
            return;
        }
        t.x = nx;
        t.y = ny;
        if (s >= d - 1e-6) {
            t.forced = null;
            if (f.onEnd != null) {
                f.onEnd.run();
            }
        }
    }

    private void stun(U t, double seconds, U by) {
        if (t.dead || !t.targetable() || seconds <= 0) {
            return;
        }
        String rule = ccRule(t);
        if (CC_IGNORE.equals(rule)) {
            return;
        }
        if (CC_AFTER.equals(rule)) {
            // the dash goes on; the stun lands when it ends, with the time it has left then
            if (t.dash != null) {
                if (now + seconds > t.dash.heldStunUntil) {
                    t.dash.heldStunUntil = now + seconds;
                    t.dash.heldStunBy = by;
                }
            } else {
                double end = now + seconds;
                at(t.immuneUntil, () -> {
                    if (end > now + 1e-9) {
                        stun(t, end - now, by);
                    }
                });
            }
            count(t, "atordoamento_adiado");
            return;
        }
        if (now < t.cleanseUntil) {
            return;   // Cleanse's moment of immunity
        }
        double before = controlLeft(t);
        double end = now + seconds * (1 - Math.min(0.9, t.stat("% Tenacity") / 100));
        ccNoted(by, t, "atordoamento", before, end - now);
        t.stunUntil = Math.max(t.stunUntil, now + seconds * (1 - Math.min(0.9, t.stat("% Tenacity") / 100)));
        t.dash = null;
        t.busyUntil = now;
        controlled(by, t, true);
    }

    private void airborne(U t, double seconds, U by) {
        if (t.dead || !t.targetable() || seconds <= 0) {
            return;
        }
        if (displacementImmune(t)) {
            count(t, "deslocamento_anulado:arremesso");
            return;
        }
        ccNoted(by, t, "arremesso", controlLeft(t), seconds);
        t.airUntil = Math.max(t.airUntil, now + seconds);   // tenacity does not shorten knock-ups
        t.dash = null;
        t.busyUntil = now;
        controlled(by, t, true);
    }

    private void breakBrittle(U s, U t, String via) {
        U by = t.brittleBy;
        if (by == null || now >= t.brittleUntil || by.team() != s.team() || t.dead) {
            return;
        }
        Map<String, Object> m = map(by.kit("fragil"));
        if (m == null) {
            return;
        }
        t.brittleUntil = -1;
        t.brittleBy = null;
        t.brittleCounted = -1;
        count(s, "marca_consumida:" + BRITTLE + ":" + via);
        s.consumedOn.merge(t, 1, Integer::sum);
        event(s, "quebrou o Frágil de " + t.f.name);
        Map<String, Object> d = map(m.get("dano"));
        deal(by, t, formula(d, by, t, 1), type(d), "Frágil", DOT, 0);
    }

    // ------------------------------------------------------------------ projectiles, fields, the elemental

    private void moveProjectiles() {
        for (Projectile p : new ArrayList<>(projectiles)) {
            if (p.only != null) {
                if (p.only.dead || !p.only.targetable()) {
                    projectiles.remove(p);
                    continue;
                }
                double d = dist(p.x, p.y, p.only.x, p.only.y);
                double s = p.speed * DT;
                if (d <= s + p.only.radius()) {
                    projectiles.remove(p);
                    p.onHit.accept(p.only);
                    continue;
                }
                double a = Math.atan2(p.only.y - p.y, p.only.x - p.x);
                p.x += Math.cos(a) * s;
                p.y += Math.sin(a) * s;
                p.dx = Math.cos(a);
                p.dy = Math.sin(a);
                continue;
            }
            double s = Math.min(p.speed * DT, p.left);
            double nx = p.x + p.dx * s;
            double ny = p.y + p.dy * s;
            for (U v : enemies(p.owner)) {
                if (!p.hit.contains(v) && segDist(p.x, p.y, nx, ny, v.x, v.y) <= p.width / 2 + v.radius()) {
                    p.hit.add(v);
                    p.onHit.accept(v);
                    if (!p.pierce) {
                        p.left = 0;
                        break;
                    }
                    if (p.stopAfterChampion != null) {
                        p.left = Math.min(p.left, p.stopAfterChampion + s);
                        p.stopAfterChampion = null;
                    }
                }
            }
            p.x = nx;
            p.y = ny;
            p.left -= s;
            if (p.left <= 1e-6 || Math.abs(p.y) > halfWidth + 200) {
                projectiles.remove(p);
                if (p.onEnd != null) {
                    p.onEnd.run();
                }
            }
        }
    }

    private void moveElementals() {
        for (Elemental e : new ArrayList<>(elementals)) {
            e.speed = Math.min(e.maxSpeed, e.speed + e.accel * DT);
            double s = Math.min(e.speed * DT, e.left);
            double nx = e.x + e.dx * s;
            double ny = e.y + e.dy * s;
            Map<String, Object> rec = map(e.spec.get("reconjurar"));
            for (U v : enemies(e.owner)) {
                if (!e.hit.contains(v) && segDist(e.x, e.y, nx, ny, v.x, v.y) <= e.width / 2 + v.radius()) {
                    e.hit.add(v);
                    String source = "R · " + e.spec.get("nome");
                    if (e.redirected) {
                        credit(e.owner, source, true);
                        Map<String, Object> d = map(rec.get("dano"));
                        deal(e.owner, v, formula(d, e.owner, v, e.rank), type(d), source, ABILITY, 0);
                        Map<String, Object> ef = new LinkedHashMap<>(map(rec.get("efeitos")));
                        if (e.champsHit > 0) {
                            ef.put("arremessa", ef.get("arremessa_demais"));
                        }
                        ef.remove("arremessa_demais");
                        effects(e.owner, v, ef, e.rank, source);
                        e.champsHit++;
                    } else {
                        hitAbility(e.owner, v, e.spec, e.rank, source, 1, "R");
                    }
                }
            }
            e.x = nx;
            e.y = ny;
            e.left -= s;
            if (e.left <= 1e-6) {
                elementals.remove(e);
                if (e.owner.elemental == e) {
                    e.owner.elemental = null;
                    e.owner.recast.remove("R");
                }
            }
        }
    }

    private void tickFields() {
        fields.removeIf(f -> now >= f.until);
        for (Field f : fields) {
            if (f.terrain || f.onTick == null || now < f.nextTick - 1e-9) {
                continue;
            }
            f.nextTick += f.interval;
            for (U v : enemies(f.owner)) {
                boolean in = "rect".equals(f.kind) ? inRect(f.x, f.y, f.angle, f.length, f.width + 2 * v.radius(), v.x, v.y)
                        : dist(f.x, f.y, v.x, v.y) <= f.radius + v.radius();
                if (in) {
                    f.onTick.accept(v);
                }
            }
        }
    }

    private boolean blocked(double x, double y, double r) {
        for (Field f : fields) {
            if (f.terrain && now >= f.from && dist(f.x, f.y, x, y) < f.radius + r) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ periodic effects

    private void tickPeriodic() {
        for (U s : units) {
            if (s.dead) {
                continue;
            }
            dummyEffects(s);
            tickStacks(s);
            if (s.combatSince < 0) {
                continue;
            }
            for (ItemEffect e : s.f.effects) {
                if (!"aura".equals(e.kind())) {
                    continue;
                }
                double every = num(e.p.get("a_cada"), 1);
                double next = s.auraNext.getOrDefault(e.item, s.combatSince + every);
                if (now >= next - 1e-9) {
                    double dealt = 0;
                    for (U t : enemies(s)) {
                        if (dist(s.x, s.y, t.x, t.y) <= 400 + t.radius()) {
                            double dmg = level(e.p, "dano", s.f.level) + num(e.p.get("vida_bonus"), 0) * s.bonus(Stats.MAX_HEALTH)
                                    + num(e.p.get("vida_max"), 0) * s.maxHp;
                            dealt += deal(s, t, dmg, type(e.p), e.item, DOT, 0);
                        }
                    }
                    double heal = dealt * num(e.p.get("cura_do_dano"), 0);
                    if (heal > 0) {
                        heal(s, heal, Map.of(key(s, e.item), heal));
                    }
                    s.auraNext.put(e.item, next + every);
                }
            }
            if (s.healPool > 0 && s.hp < s.maxHp) {
                double r = Math.min(s.healPool, s.healRate * DT);
                s.healPool -= r;
                heal(s, r, share(s, s.f.healSources, r));
            }
            for (Map.Entry<String, Double> e : new ArrayList<>(s.shieldUntil.entrySet())) {
                if (now >= e.getValue()) {
                    for (Map<String, Double> pool : List.of(s.shield, s.shieldMagic, s.shieldPhysical)) {
                        Double left = pool.remove(e.getKey());
                        if (left != null && left > 0) {
                            led(e.getKey()).shieldLost += left;
                        }
                    }
                    s.shieldUntil.remove(e.getKey());
                }
            }
            Map<String, Object> res = map(s.kit("recurso"));
            if (res != null && "calor".equals(res.get("tipo")) && now >= s.overheatedUntil) {
                s.resource = Math.max(0, s.resource - 10 * DT * (now - lastAbility(s) > 5 ? 1 : 0));
            }
        }
    }

    private double lastAbility(U u) {
        double last = -1;
        for (Map.Entry<String, Double> e : u.ready.entrySet()) {
            last = Math.max(last, e.getValue() - 5);
        }
        return last;
    }

    /** Statuses that wait for time / hits in combat (Duplaguarda, Ritmo Fatal...): on once their conditions hold. */
    private void activate(U u) {
        for (int i = 0; i < u.delayOn.length; i++) {
            if (u.delayOn[i]) {
                continue;
            }
            Delay d = u.f.delays.get(i);
            double inCombat = u.combatSince < 0 ? -1 : now - u.combatSince;
            boolean on = d.fixed ? now >= (d.seconds == null ? 0 : d.seconds) - 1e-9
                    : inCombat >= 0 && (d.seconds == null || inCombat >= d.seconds - 1e-9)
                    && (d.hits == null || u.hitsDealt >= d.hits) && (d.hitsTaken == null || u.abilityHitsTaken >= d.hitsTaken);
            if (on) {
                u.delayOn[i] = true;
                double hp = d.amounts.getOrDefault(Stats.MAX_HEALTH, 0.0);
                u.maxHp += hp;
                u.hp += hp;
                event(u, d.source + " ativo");
            }
        }
    }

    // ------------------------------------------------------------------ damage

    private double deal(U a, U t, double raw, String type, String source, int kind, double critPart) {
        if (raw <= 0 || t.dead || !t.targetable() || a.dead && kind != DOT) {
            return 0;
        }
        if ("adaptativo".equals(type)) {
            type = adaptive(a);
        }
        if (a.combatSince < 0) {
            a.combatSince = now;
        }
        if (t.combatSince < 0) {
            t.combatSince = now;
        }
        if (now < a.exhaustUntil) {
            raw *= 1 - a.exhaust;
            critPart *= 1 - a.exhaust;
        }
        Map<String, Double> amps = new LinkedHashMap<>();
        for (ItemEffect e : a.f.effects) {
            if (!"amplifica".equals(e.kind()) || e.p.get("dano_tipo") != null && !type.equals(e.p.get("dano_tipo"))) {
                continue;
            }
            if (Boolean.TRUE.equals(e.p.get("so_habilidade")) && kind != ABILITY) {
                continue;
            }
            if (Boolean.TRUE.equals(e.p.get("vida_alta")) && a.hp <= 0.5 * a.maxHp) {
                continue;
            }
            double pct = num(e.p.get("pct"), 0);
            if (e.p.get("pct_por_s") != null) {
                pct = Math.min(num(e.p.get("max"), 1), num(e.p.get("pct_por_s"), 0) * Math.max(0, now - a.combatSince));
            }
            if (e.p.get("alvo_vida_bonus") != null) {
                pct = num(e.p.get("max"), 0) * Math.min(1, Math.max(0, t.bonus(Stats.MAX_HEALTH)) / num(e.p.get("alvo_vida_bonus"), 1200));
            }
            amps.merge(key(a, e.item), pct, Math::max);
        }
        if (now < t.markUntil) {
            amps.merge(t.markSource, t.mark, Math::max);
        }
        runeAmps(a, t, kind, source, amps);
        Set<String> auras = new HashSet<>();
        for (U ally : units) {
            if (ally.team() != a.team() || ally.dead) {
                continue;
            }
            for (ItemEffect e : ally.f.effects) {
                if ("amplifica_alvo".equals(e.kind()) && Boolean.TRUE.equals(e.p.get("aura"))
                        && (e.p.get("dano_tipo") == null || type.equals(e.p.get("dano_tipo"))) && auras.add(e.item)
                        && dist(ally.x, ally.y, t.x, t.y) <= 650) {
                    amps.merge(key(ally, e.item), num(e.p.get("pct"), 0), Math::max);
                }
            }
        }
        double ampTotal = amps.values().stream().mapToDouble(Double::doubleValue).sum();
        double amped = raw * (1 + ampTotal);
        double mult = 1;
        double multNoShred = 1;
        int res = "fisico".equals(type) ? 0 : "magico".equals(type) ? 1 : -1;
        if (res >= 0) {
            double resist = t.stat(res == 0 ? Stats.ARMOR : Stats.MAGIC_RESIST);
            double shred = now < t.shredUntil[res] ? t.shred[res] : 0;
            double pen = a.stat(res == 0 ? "% Armor Penetration" : "% Magic Penetration") / 100;
            double flat = a.stat(res == 0 ? "Armor Penetration" : "Magic Penetration");
            mult = DamageRules.mitigation(resist, shred, pen, flat);
            multNoShred = DamageRules.mitigation(resist, 0, pen, flat);
        }
        double dmg = amped * mult;
        double extraShred = amped * (mult - multNoShred);
        double prevented = 0;
        for (ItemEffect e : t.f.effects) {
            if (!"reduz_dano".equals(e.kind())) {
                continue;
            }
            String against = String.valueOf(e.p.get("contra"));
            double cut = 0;
            if ("ataque".equals(against) && kind == ATTACK) {
                cut = dmg * num(e.p.get("pct"), 0);
            } else if ("critico".equals(against) && kind == ATTACK && critPart > 0) {
                cut = critPart * (1 + ampTotal) * mult * num(e.p.get("pct"), 0);
            }
            if (cut > 0) {
                prevented += cut;
                led(key(t, e.item)).prevented += cut;
            }
        }
        if (now < t.damageReductionUntil) {
            prevented += (dmg - prevented) * t.damageReduction;
        }
        if ((kind == ATTACK || kind == ABILITY) && t.flatCutLeft > 0 && now < t.flatCutUntil) {
            double cut = Math.min(Math.max(0, dmg - prevented), t.flatCut);
            prevented += cut;
            t.flatCutLeft--;
            led(t.flatCutSource).prevented += cut;
        }
        dmg = Math.max(0, dmg - prevented);
        if (kind == ATTACK || kind == ABILITY) {
            a.hitsDealt++;
        }
        if (kind == ABILITY) {
            t.abilityHitsTaken++;
        }
        a.r.dealt += dmg;
        t.r.taken += dmg;
        t.r.mitigated += amped - amped * mult;
        a.r.damageBySource.merge(source, dmg, Double::sum);
        if (dmg > 0) {
            a.r.damageByType.merge(type, dmg, Double::sum);
            a.r.damageBySourceType.computeIfAbsent(source, k -> new LinkedHashMap<>()).merge(type, dmg, Double::sum);
        }
        led(key(a, source)).damage += dmg;
        if (recordFrames && dmg > 0) {
            ArenaModel.Use use = useOf(a, source);
            if (use != null) {
                use.damage += dmg;
                use.targets.merge(t.f.name, dmg, Double::sum);
            }
        }
        if (res >= 0 && extraShred > 0 && t.shredSource[res] != null && now < t.shredUntil[res]) {
            led(t.shredSource[res]).extraShred += extraShred * (dmg / Math.max(1e-9, amped * mult));
        }
        if (ampTotal > 0) {
            for (Map.Entry<String, Double> e : amps.entrySet()) {
                if (e.getValue() > 0) {
                    led(e.getKey()).extraAmp += dmg * e.getValue() / (1 + ampTotal);
                }
            }
        }
        if (kind != REFLECT) {
            reaver(a, t);
        }
        double hpBefore = t.hp;
        t.lastHitBy.put(a, now);
        double left = dmg;
        List<Map<String, Double>> pools = new ArrayList<>();
        if ("magico".equals(type)) {
            pools.add(t.shieldMagic);
        } else if ("fisico".equals(type)) {
            pools.add(t.shieldPhysical);
        }
        pools.add(t.shield);
        for (Map<String, Double> pool : pools) {
            double sh = sum(pool);
            if (sh > 0 && left > 0) {
                double absorbed = Math.min(sh, left);
                consume(pool, absorbed, false);
                t.r.shieldAbsorbed += absorbed;
                left -= absorbed;
            }
        }
        t.hp -= left;
        if (t.hp <= 0 && !revive(t)) {
            t.dead = true;
            t.r.deathTime = now;
            t.dash = null;
            t.forced = null;
            event(t, "foi abatido por " + a.f.name + " (" + source + ")");
            killed(t);
        }
        if (kind != REFLECT && !a.dead) {
            double vamp = (kind == ATTACK ? a.stat("% Lifesteal") : 0) + a.stat("% Omnivamp");
            if (vamp > 0 && dmg > 0) {
                double h = dmg * vamp / 100;
                Map<String, Double> src = share(a, a.f.vampSources, h);
                if (src.isEmpty()) {
                    src = Map.of(key(a, "Vampirismo"), h);
                }
                double missing = Math.max(0, a.maxHp - a.hp);
                double landing = h * (1 - (now < a.gwUntil ? a.gw : 0));
                heal(a, h, src);
                if (landing > missing) {
                    overheal(a, landing - missing);
                }
            }
            onHit(a, t, type, kind);
        }
        afterDamage(a, t, dmg, type, kind, critPart);
        if (now < a.firstStrikeUntil && !"Primeiro Ataque".equals(source) && kind != REFLECT && dmg > 0) {
            deal(a, t, dmg * 0.07, "verdadeiro", "Primeiro Ataque", ITEM, 0);
        }
        runeOnHit(a, t, kind, hpBefore);
        return dmg;
    }

    private void onHit(U a, U t, String type, int kind) {
        for (ItemEffect e : a.f.effects) {
            switch (e.kind()) {
                case "feridas":
                    if (Boolean.TRUE.equals(e.p.get("ao_ser_atingido"))) {
                        break;
                    }
                    String g = String.valueOf(e.p.getOrDefault("gatilho", "qualquer"));
                    if ("qualquer".equals(g) || g.equals(type)) {
                        applyGw(t, num(e.p.get("pct"), 0.4), 3, key(a, e.item));
                    }
                    break;
                case "reduz_resistencia":
                    int res = "armadura".equals(e.p.get("resistencia")) ? 0 : 1;
                    String gt = String.valueOf(e.p.get("gatilho"));
                    boolean ok = "fisico".equals(gt) ? "fisico".equals(type) : "magico".equals(type) && kind != ATTACK;
                    if (ok) {
                        double cur = now < t.shredUntil[res] ? t.shred[res] : 0;
                        t.shred[res] = Math.min(num(e.p.get("max"), 0.3), cur + num(e.p.get("por_acerto"), 0.06));
                        t.shredUntil[res] = now + num(e.p.get("duracao"), 6);
                        t.shredSource[res] = key(a, e.item);
                    }
                    break;
                case "amplifica_alvo":
                    if (Boolean.TRUE.equals(e.p.get("marca")) && kind == ABILITY) {
                        t.mark = num(e.p.get("pct"), 0.07);
                        t.markUntil = now + num(e.p.get("duracao"), 4);
                        t.markSource = key(a, e.item);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /** Lifesteal over full health: Sedenta por Sangue's shield, up to its value. */
    private void overheal(U a, double extra) {
        for (T x : a.trigs) {
            if (!"excesso_de_cura".equals(x.when)) {
                continue;
            }
            String src = key(a, x.d.source);
            double cap = x.delta("Shield");
            double add = Math.min(extra, cap - a.shield.getOrDefault(src, 0.0));
            if (add > 1e-6) {
                if (x.rep.activations == 0) {
                    x.rep.first = Math.round(now * 100) / 100.0;
                }
                x.rep.activations++;
                addShield(a, add, src, Double.MAX_VALUE, "any");
                x.rep.shield += add;
            }
        }
    }

    private void reaver(U a, U t) {
        for (ItemEffect e : a.f.effects) {
            if (!"corta_escudo".equals(e.kind())) {
                continue;
            }
            double cut = Math.min(num(e.p.get("max"), 0.6), num(e.p.get("base"), 0)
                    + num(e.p.get("dda_bonus"), 0) * a.bonus(Stats.ATTACK_DAMAGE) + num(e.p.get("pdh"), 0) * a.stat(Stats.ABILITY_POWER));
            if (now >= t.shieldCutUntil) {
                double amount = t.shieldLeft() * cut;
                if (amount > 0) {
                    for (Map<String, Double> pool : List.of(t.shield, t.shieldMagic, t.shieldPhysical)) {
                        consume(pool, sum(pool) * cut, true);
                    }
                    led(key(a, e.item)).shieldCut += amount;
                }
            }
            t.shieldCutPct = Math.max(now < t.shieldCutUntil ? t.shieldCutPct : 0, cut);
            t.shieldCutUntil = now + 3;
            t.shieldCutSource = key(a, e.item);
        }
    }

    private double attackSlow(U s) {
        double slow = 0;
        for (U e : units) {
            if (e.team() != s.team() && !e.dead && dist(e.x, e.y, s.x, s.y) <= 700) {
                for (ItemEffect fx : e.f.effects) {
                    if ("reduz_vel_ataque".equals(fx.kind())) {
                        slow = Math.max(slow, num(fx.p.get("pct"), 0));
                    }
                }
            }
        }
        return Math.min(0.9, slow);
    }

    private void applyGw(U t, double pct, double seconds, String source) {
        if (now >= t.gwUntil || pct >= t.gw) {
            t.gw = pct;
            t.gwSource = source;
        }
        t.gwUntil = Math.max(t.gwUntil, now + seconds);
    }

    private void heal(U s, double amount, Map<String, Double> sources) {
        if (amount <= 0 || s.dead) {
            return;
        }
        amount *= revitalize(s);
        double gw = now < s.gwUntil ? s.gw : 0;
        double total = sources.values().stream().mapToDouble(Double::doubleValue).sum();
        double missing = Math.max(0, s.maxHp - s.hp);
        double effective = Math.min(amount * (1 - gw), missing);
        // Only the part that would have healed counts as cut (a heal over full health was never going to land).
        double cut = Math.min(amount, missing) - effective;
        if (cut > 0 && s.gwSource != null) {
            led(s.gwSource).healCut += cut;
        }
        s.r.healLost += cut;
        s.hp += effective;
        s.r.healed += effective;
        for (Map.Entry<String, Double> e : sources.entrySet()) {
            double sh = total > 0 ? e.getValue() / total : 0;
            led(e.getKey()).healed += effective * sh;
            led(e.getKey()).healLost += cut * sh;
        }
    }

    private void addShield(U s, double amount, String source, double until, String type) {
        amount *= revitalize(s);
        int bar = source.indexOf('|');
        if (recordFrames && bar > 0 && source.substring(0, bar).chars().allMatch(Character::isDigit)) {
            int owner = Integer.parseInt(source.substring(0, bar));
            ArenaModel.Use use = owner < units.size() ? useOf(units.get(owner), source.substring(bar + 1)) : null;
            if (use != null) {
                use.shield += amount;
            }
        }
        double cut = now < s.shieldCutUntil ? s.shieldCutPct : 0;
        if (cut > 0 && s.shieldCutSource != null) {
            led(s.shieldCutSource).shieldCut += amount * cut;
            led(source).shieldLost += amount * cut;
        }
        Map<String, Double> pool = "magico".equals(type) ? s.shieldMagic : "fisico".equals(type) ? s.shieldPhysical : s.shield;
        pool.merge(source, amount * (1 - cut), Double::sum);
        s.shieldUntil.put(source, Math.max(until, s.shieldUntil.getOrDefault(source, -1.0)));
    }

    /** Revitalizar: heals and shields on a target under 40% of health count more. */
    private double revitalize(U s) {
        for (ItemEffect e : s.f.effects) {
            if ("revitalizar".equals(e.kind()) && s.hp < num(e.p.get("vida_abaixo"), 0.4) * s.maxHp) {
                return 1 + num(e.p.get("pct"), 0.1);
            }
        }
        return 1;
    }

    private void consume(Map<String, Double> pool, double amount, boolean lost) {
        double total = sum(pool);
        if (total <= 0) {
            return;
        }
        for (Map.Entry<String, Double> e : pool.entrySet()) {
            double part = amount * e.getValue() / total;
            e.setValue(e.getValue() - part);
            if (lost) {
                led(e.getKey()).shieldLost += part;
            } else {
                led(e.getKey()).shieldAbsorbed += part;
            }
        }
    }

    // ------------------------------------------------------------------ planner effects: triggers

    /** Effective stats of a trigger given at once (not stats while it is on). */
    private static final Set<String> ONE_SHOT = Set.of("Shield", "Magic Shield", "Physical Shield", "Heal",
            "Ally Shield", "Ally Heal", "Stasis");

    /** A conditional effect of the build in the fight: when it fires, what it gives, what it did. */
    private final class T {
        final FighterBuilder.Triggered d;
        final Map<String, Object> s;
        final String when;
        /** Its stats count until this time (MAX_VALUE: the rest of the fight). */
        double until = -1;
        /** Share of its stats on (stacks, or how much of the scale). */
        double factor;
        int stacks;
        double hpAdded;
        boolean done;
        double nextPeriodic = -1;
        final List<double[]> window = new ArrayList<>();
        final ArenaModel.TriggerReport rep = new ArenaModel.TriggerReport();

        T(FighterBuilder.Triggered d) {
            this.d = d;
            this.s = d.spec == null ? Map.of() : d.spec;
            this.when = d.fixedAt != null ? "fixo" : d.mode;
        }

        boolean on() {
            return factor > 0 && now < until;
        }

        double delta(String stat) {
            return d.delta.getOrDefault(stat, 0.0);
        }

        boolean hasStats() {
            for (String k : d.delta.keySet()) {
                if (!ONE_SHOT.contains(k)) {
                    return true;
                }
            }
            return false;
        }
    }

    private void startTriggers(U u) {
        for (FighterBuilder.Triggered d : u.in.triggers) {
            T t = new T(d);
            u.trigs.add(t);
            ArenaModel.TriggerReport r = t.rep;
            r.fighter = u.idx;
            r.key = d.key;
            r.source = d.source;
            r.effect = d.effect;
            r.kind = d.kind;
            r.mode = d.fixedAt != null ? "fixo" : d.mode;
            r.how = howText(d);
            r.buildOn = d.buildOn;
            r.delta.putAll(d.delta);
            result.triggers.add(r);
            if ("inicio".equals(t.when) || "fora_de_combate".equals(t.when)) {
                fire(u, t);
            }
        }
    }

    /** What a trigger means, for the report. */
    private static String howText(FighterBuilder.Triggered d) {
        Map<String, Object> s = d.spec == null ? Map.of() : d.spec;
        String note = s.get("nota") == null ? "" : " (" + s.get("nota") + ")";
        if (d.fixedAt != null) {
            return "ligado à mão no cenário aos " + fmtS(d.fixedAt) + "s";
        }
        String dur = s.get("duracao") != null ? " por " + fmtS(num(s.get("duracao"), 0)) + "s" : "";
        String cd = s.get("recarga") != null ? "; recarga " + fmtS(num(s.get("recarga"), 0)) + "s" : "";
        String pct = s.get("vida") != null ? Math.round(num(s.get("vida"), 0) * 100) + "%" : "";
        Object dt = s.get("dano_tipo");
        String type = dt == null ? "" : " (" + (dt instanceof List ? ((List<?>) dt).stream().map(x -> typeText(String.valueOf(x)))
                .collect(java.util.stream.Collectors.joining(" ou ")) : typeText(String.valueOf(dt))) + ")";
        String text;
        switch (d.mode) {
            case "estatico":
                text = (d.buildOn ? "ligado" : "desligado") + " a luta toda, como na build: depende da partida, não da luta";
                break;
            case "modelado":
                text = "feito pelo efeito de combate";
                break;
            case "nunca":
                text = "não acontece numa luta da Arena";
                break;
            case "inicio":
                text = "desde o começo da luta";
                break;
            case "fora_de_combate":
                text = "até entrar em combate" + ("ataque".equals(s.get("ate")) ? " (até o 1º ataque)" : "");
                break;
            case "combate":
                text = "em combate" + (s.get("segundos") != null ? " depois de " + fmtS(num(s.get("segundos"), 0)) + "s" : "")
                        + (s.get("acertos") != null ? " depois de " + (int) num(s.get("acertos"), 0) + " acertos" : "")
                        + (s.get("acertos_sofridos") != null ? " depois de sofrer " + (int) num(s.get("acertos_sofridos"), 0)
                        + " habilidades" : "");
                break;
            case "vida_abaixo":
                text = "dano" + type + " que deixa a Vida abaixo de " + pct;
                break;
            case "vida_acima":
                text = "enquanto a Vida está acima de " + pct;
                break;
            case "escala_vida":
                text = "cresce com a Vida perdida, máximo abaixo de " + pct;
                break;
            case "ativo":
                text = "ativo do item: na linha do tempo ou, sem ordem, "
                        + (Boolean.TRUE.equals(s.get("auto_controlado")) ? "ao ser imobilizado"
                        : "com a Vida abaixo de " + Math.round(num(s.get("auto_vida_abaixo"), 0.5) * 100) + "%");
                break;
            case "ao_usar_ult":
                text = "ao usar a ultimate";
                break;
            case "ao_conjurar":
                text = "a cada habilidade conjurada";
                break;
            case "ao_atacar":
                text = "a cada ataque num Campeão";
                break;
            case "ao_causar_dano":
                text = "ao causar dano" + type + (s.get("de") != null ? " com " + s.get("de") : "") + " num Campeão";
                break;
            case "ao_sofrer_dano":
                text = "ao sofrer dano" + type + " de um Campeão";
                break;
            case "ao_sofrer_critico":
                text = "ao sofrer acerto crítico";
                break;
            case "ao_controlar":
                text = "ao causar Lentidão ou imobilizar um Campeão";
                break;
            case "ao_imobilizar":
                text = "ao imobilizar um Campeão";
                break;
            case "ao_ser_imobilizado":
                text = "ao ser imobilizado" + (Boolean.TRUE.equals(s.get("ou_imobilizar")) ? " ou imobilizar" : "");
                break;
            case "golpe_do_item":
                text = "junto do golpe do item";
                break;
            case "excesso_de_cura":
                text = "Roubo de Vida acima da Vida máxima vira escudo";
                break;
            case "ao_curar_aliado":
                text = "ao curar ou proteger um aliado";
                break;
            case "abate":
                text = "ao abater (Campeão ferido nos últimos 3s)";
                break;
            case "ao_morrer":
                text = "ao sofrer dano letal";
                break;
            case "acertos_janela":
                text = (int) num(s.get("acertos"), 3) + " acertos em " + fmtS(num(s.get("janela"), 4)) + "s";
                break;
            case "dano_sofrido_janela":
                text = "dano sofrido acima do limite em " + fmtS(num(s.get("janela"), 2.5)) + "s";
                break;
            case "periodico":
                text = "a cada " + fmtS(num(s.get("a_cada"), 1)) + "s em combate";
                break;
            default:
                text = d.mode;
                break;
        }
        return text + dur + cd + note;
    }

    /** Fires a trigger: its stats on (for its duration), its shields / heals / stasis given now. */
    private boolean fire(U u, T t) {
        if (u.dead) {
            return false;
        }
        Map<String, Object> s = t.s;
        // an item's active goes by the active's own cooldown, and counts in useItem
        boolean active = "ativo".equals(t.when);
        double cd = active || s.get("recarga") == null ? 0 : level(s, "recarga", u.f.level);
        if (!activate(u, t.d.kind, t.d.source, cd, 1e-9, !active)) {
            return false;
        }
        t.rep.activations++;
        if (t.rep.first == null) {
            t.rep.first = Math.round(now * 100) / 100.0;
        }
        String src = key(u, t.d.source);
        if (t.hasStats()) {
            boolean per = Boolean.TRUE.equals(s.get("por_acionamento"));
            double f = 1;
            if (s.get("acumulos") != null) {
                int n = (int) num(s.get("acumulos"), 1);
                t.stacks = Math.min(n, (t.on() ? t.stacks : 0) + 1);
                f = per ? t.stacks : (double) t.stacks / n;
            }
            setFactor(u, t, f);
            t.until = s.get("duracao") == null ? Double.MAX_VALUE : now + num(s.get("duracao"), 0);
        }
        double shieldFor = num(s.containsKey("duracao_escudo") ? s.get("duracao_escudo") : s.get("duracao"), -1);
        double shieldUntil = shieldFor < 0 ? Double.MAX_VALUE : now + shieldFor;
        boolean decays = Boolean.TRUE.equals(s.get("decai"));
        if (!"excesso_de_cura".equals(t.when)) {
            giveShield(u, t, t.delta("Shield"), src, shieldUntil, "any", decays);
            giveShield(u, t, t.delta("Magic Shield"), src, shieldUntil, "magico", decays);
            giveShield(u, t, t.delta("Physical Shield"), src, shieldUntil, "fisico", decays);
        }
        double heal = t.delta("Heal");
        if (s.get("cura_vida_perdida") != null) {
            heal = num(s.get("cura_fixa"), 0) + num(s.get("cura_vida_perdida"), 0) * Math.max(0, u.maxHp - u.hp);
        }
        if (heal > 0 && !"ao_morrer".equals(t.when)) {
            double over = num(s.get("cura_ao_longo"), 0);
            if (over > 0) {
                int ticks = (int) Math.max(1, Math.round(over / 0.25));
                double per = heal / ticks;
                for (int k = 1; k <= ticks; k++) {
                    at(now + k * 0.25, () -> trigHeal(u, t, per, src));
                }
            } else {
                trigHeal(u, t, heal, src);
            }
        }
        double allyShield = t.delta("Ally Shield");
        double allyHeal = t.delta("Ally Heal");
        if (allyShield > 0 || allyHeal > 0) {
            List<U> near = new ArrayList<>();
            for (U a : allies(u)) {
                if (dist(a.x, a.y, u.x, u.y) <= 1200) {
                    near.add(a);
                }
            }
            for (U a : near) {
                if (allyShield > 0) {
                    addShield(a, allyShield / near.size(), src, shieldUntil, "any");
                    t.rep.shield += allyShield / near.size();
                }
                if (allyHeal > 0) {
                    heal(a, allyHeal / near.size(), Map.of(src, allyHeal / near.size()));
                    t.rep.healed += allyHeal / near.size();
                }
            }
            if (!near.isEmpty()) {
                allyHelped(u);
            }
        }
        if (s.get("estase") != null && !"ao_morrer".equals(t.when)) {
            u.stasisUntil = now + num(s.get("estase"), 2.5);
            u.stasisUsed = true;
            u.dash = null;
            u.forced = null;
        }
        if (Boolean.TRUE.equals(s.get("purifica"))) {
            u.stunUntil = Math.min(u.stunUntil, now);
            u.slows.clear();
        }
        if (s.get("reduz_recargas") != null) {
            double pct = num(s.get("reduz_recargas"), 0);
            for (String slot : List.of("1", "2", "3")) {
                double r = u.ready.getOrDefault(slot, 0.0);
                if (r > now) {
                    u.ready.put(slot, now + (r - now) * (1 - pct));
                }
            }
        }
        if (s.get("velocidade") != null) {
            u.speedUps.add(new double[] {level(s, "velocidade", u.f.level), now + num(s.get("duracao"), 3)});
        }
        Map<String, Object> rg = map(s.get("reduz_golpes"));
        if (rg != null) {
            u.flatCut = level(rg, "valor", u.f.level);
            u.flatCutLeft = (int) num(rg.get("golpes"), 4);
            u.flatCutUntil = now + num(rg.get("janela"), 1.5);
            u.flatCutSource = src;
        }
        Map<String, Object> dmg = map(s.get("dano"));
        if (dmg != null) {
            for (U e : enemies(u)) {
                if (dist(e.x, e.y, u.x, u.y) <= num(dmg.get("raio"), 400) + e.radius()) {
                    deal(u, e, num(dmg.get("dano"), 0) + num(dmg.get("vida_bonus"), 0) * u.bonus(Stats.MAX_HEALTH),
                            String.valueOf(dmg.getOrDefault("dano_tipo", "magico")), t.d.source, ITEM, 0);
                }
            }
        }
        event(u, t.d.source + (t.d.effect.equals(t.d.source) ? "" : " · " + t.d.effect) + " ativou");
        return true;
    }

    private void giveShield(U u, T t, double amount, String src, double until, String type, boolean decays) {
        if (amount <= 0) {
            return;
        }
        addShield(u, amount, src, until, type);
        if (decays && until < Double.MAX_VALUE) {
            u.shieldDecay.put(src + "|" + type, new double[] {amount, now, until});
        }
        t.rep.shield += amount;
    }

    private void trigHeal(U u, T t, double amount, String src) {
        double before = u.r.healed;
        heal(u, amount, Map.of(src, amount));
        t.rep.healed += u.r.healed - before;
    }

    /** Stats of a trigger on at a share (0 = off); max health moves with it. */
    private void setFactor(U u, T t, double f) {
        double hp = t.delta(Stats.MAX_HEALTH) * f;
        double change = hp - t.hpAdded;
        if (Math.abs(change) > 1e-9) {
            u.maxHp = Math.max(1, u.maxHp + change);
            u.hp = change > 0 ? u.hp + change : Math.min(u.hp, u.maxHp);
        }
        t.hpAdded = hp;
        t.factor = f;
    }

    /** Every step: time-based and health-based triggers, expiries, auto actives. */
    private void tickTriggers(U u) {
        double frac = u.hp / Math.max(1, u.maxHp);
        boolean inCombat = u.combatSince >= 0;
        for (T t : u.trigs) {
            if (t.on()) {
                t.rep.activeSeconds += DT;
            }
            if (t.factor > 0 && now >= t.until) {
                setFactor(u, t, 0);
                t.stacks = 0;
            }
            switch (t.when) {
                case "fixo":
                    if (!t.done && now >= t.d.fixedAt - 1e-9) {
                        t.done = fire(u, t);
                    }
                    break;
                case "fora_de_combate": {
                    boolean end = "ataque".equals(t.s.get("ate")) ? u.attacks > 0 : inCombat;
                    if (!t.done && end) {
                        if (t.factor > 0) {
                            setFactor(u, t, 0);
                            t.until = -1;
                        }
                        t.done = true;
                    }
                    break;
                }
                case "combate":
                    if (!t.done && inCombat) {
                        double in = now - u.combatSince;
                        boolean ok = (t.s.get("segundos") == null || in >= num(t.s.get("segundos"), 0) - 1e-9)
                                && (t.s.get("acertos") == null || u.hitsDealt >= num(t.s.get("acertos"), 0))
                                && (t.s.get("acertos_sofridos") == null || u.abilityHitsTaken >= num(t.s.get("acertos_sofridos"), 0));
                        if (ok) {
                            t.done = fire(u, t);
                        }
                    }
                    break;
                case "vida_acima": {
                    boolean want = frac > num(t.s.get("vida"), 0.65);
                    if (want && t.factor == 0) {
                        t.rep.activations++;
                        if (t.rep.first == null) {
                            t.rep.first = Math.round(now * 100) / 100.0;
                        }
                    }
                    setFactor(u, t, want ? 1 : 0);
                    t.until = Double.MAX_VALUE;
                    break;
                }
                case "escala_vida": {
                    double v = num(t.s.get("vida"), 0.3);
                    double f = Math.max(0, Math.min(1, (1 - frac) / Math.max(1e-9, 1 - v)));
                    if (f > 0 && t.factor == 0) {
                        t.rep.activations++;
                        if (t.rep.first == null) {
                            t.rep.first = Math.round(now * 100) / 100.0;
                        }
                    }
                    if (Math.abs(f - t.factor) > 0.005) {
                        setFactor(u, t, f);
                    }
                    t.until = Double.MAX_VALUE;
                    break;
                }
                case "periodico":
                    if (inCombat) {
                        double every = num(t.s.get("a_cada"), 1);
                        if (t.nextPeriodic < 0) {
                            t.nextPeriodic = u.combatSince + every;
                        }
                        if (now >= t.nextPeriodic - 1e-9) {
                            fire(u, t);
                            t.nextPeriodic += every;
                        }
                    }
                    break;
                case "ativo":
                    autoActive(u, t, frac, inCombat);
                    break;
                default:
                    break;
            }
        }
        // shields that decay over their duration
        for (Map.Entry<String, double[]> e : new ArrayList<>(u.shieldDecay.entrySet())) {
            double[] d = e.getValue();
            String k = e.getKey().substring(0, e.getKey().lastIndexOf('|'));
            String type = e.getKey().substring(e.getKey().lastIndexOf('|') + 1);
            Map<String, Double> pool = "magico".equals(type) ? u.shieldMagic : "fisico".equals(type) ? u.shieldPhysical : u.shield;
            Double cur = pool.get(k);
            if (cur == null || now >= d[2]) {
                u.shieldDecay.remove(e.getKey());
                continue;
            }
            double cap = d[0] * (d[2] - now) / Math.max(1e-9, d[2] - d[1]);
            if (cur > cap) {
                pool.put(k, cap);
                led(k).shieldLost += cur - cap;
            }
        }
    }

    /** An item active nobody ordered: used on its own when its moment comes (low health, crowd controlled). */
    private void autoActive(U u, T t, double frac, boolean inCombat) {
        String item = t.d.source;
        if (u.manualItems.contains(item) || now < u.ready.getOrDefault(item, 0.0) - 1e-9 || !u.targetable()) {
            return;
        }
        boolean controlled = now < u.stunUntil || u.slows.stream().anyMatch(x -> now < x[1] && x[0] >= 0.3);
        boolean want = Boolean.TRUE.equals(t.s.get("auto_controlado")) ? controlled
                : inCombat && frac < num(t.s.get("auto_vida_abaixo"), 0.5);
        if (want) {
            useItem(u, item, target(u));
        }
    }

    /** Triggers of a kind on a unit (with a filter on the spec). */
    private void fireAll(U u, String when, java.util.function.Predicate<Map<String, Object>> ok) {
        if (u == null || u.dead) {
            return;
        }
        for (T t : u.trigs) {
            if (when.equals(t.when) && (ok == null || ok.test(t.s))) {
                fire(u, t);
            }
        }
    }

    private static boolean typeMatches(Map<String, Object> s, String type) {
        Object want = s.get("dano_tipo");
        if (want == null) {
            return true;
        }
        if (want instanceof List) {
            return ((List<?>) want).contains(type);
        }
        return want.equals(type);
    }

    private static boolean kindMatches(Map<String, Object> s, int kind) {
        Object want = s.get("de");
        if (want == null) {
            return true;
        }
        List<?> list = want instanceof List ? (List<?>) want : List.of(want);
        return list.contains("ataque") && kind == ATTACK || list.contains("habilidade") && kind == ABILITY;
    }

    /** After damage lands: the triggers of both sides, and the runes' procs of the attacker. */
    private void afterDamage(U a, U t, double dmg, String type, int kind, double critPart) {
        if (dmg <= 0 || a == t) {
            return;
        }
        t.lastHitBy.put(a, now);
        if (kind != REFLECT) {
            fireAll(a, "ao_causar_dano", s -> typeMatches(s, type) && kindMatches(s, kind));
        }
        fireAll(t, "ao_sofrer_dano", s -> typeMatches(s, type));
        double frac = t.hp / Math.max(1, t.maxHp);
        fireAll(t, "vida_abaixo", s -> frac < num(s.get("vida"), 0.35) && typeMatches(s, type));
        if (kind == ATTACK && critPart > 0) {
            t.critTaken += Math.min(1, a.stat("% Critical Rate") / 100);
            while (t.critTaken >= 1) {
                t.critTaken -= 1;
                fireAll(t, "ao_sofrer_critico", null);
            }
        }
        for (T x : t.trigs) {
            if ("dano_sofrido_janela".equals(x.when)) {
                x.window.add(new double[] {now, dmg});
                double win = num(x.s.get("janela"), 2.5);
                x.window.removeIf(w -> w[0] < now - win);
                double sum = x.window.stream().mapToDouble(w -> w[1]).sum();
                if (sum > level(x.s, "limite", t.f.level) && fire(t, x)) {
                    x.window.clear();
                }
            }
        }
        if (kind == ATTACK || kind == ABILITY) {
            for (T x : a.trigs) {
                if ("acertos_janela".equals(x.when)) {
                    x.window.add(new double[] {now, 1});
                    double win = num(x.s.get("janela"), 4);
                    x.window.removeIf(w -> w[0] < now - win);
                    if (x.window.size() >= num(x.s.get("acertos"), 3) && fire(a, x)) {
                        x.window.clear();
                    }
                }
            }
        }
        // auto actives at low health right away (Zhonya before the next hit)
        for (T x : t.trigs) {
            if ("ativo".equals(x.when)) {
                autoActive(t, x, t.hp / Math.max(1, t.maxHp), true);
            }
        }
    }

    /** A crowd control applied by `by` on `t` (slow or immobilize). */
    private void controlled(U by, U t, boolean immobilize) {
        if (by == null || by == t || by.team() == t.team()) {
            return;
        }
        fireAll(by, "ao_controlar", s -> immobilize || !Boolean.TRUE.equals(s.get("so_imobilizar")));
        if (immobilize) {
            fireAll(by, "ao_imobilizar", null);
            fireAll(by, "ao_ser_imobilizado", s -> Boolean.TRUE.equals(s.get("ou_imobilizar")));
            fireAll(t, "ao_ser_imobilizado", null);
            for (ItemEffect e : by.f.effects) {
                if ("suserano".equals(e.kind())) {
                    glacial(by, t, e);
                }
            }
            for (T x : t.trigs) {
                if ("ativo".equals(x.when)) {
                    autoActive(t, x, t.hp / Math.max(1, t.maxHp), true);
                }
            }
        }
    }

    private void allyHelped(U u) {
        fireAll(u, "ao_curar_aliado", null);
    }

    /** A champion died: its killer and whoever hit it in the last 3s get their kill triggers. */
    private void killed(U t) {
        for (Map.Entry<U, Double> e : t.lastHitBy.entrySet()) {
            U a = e.getKey();
            if (a.team() != t.team() && !a.dead && now - e.getValue() <= 3 + 1e-9) {
                fireAll(a, "abate", null);
                for (ItemEffect fx : a.f.effects) {
                    if ("hubris".equals(fx.kind())) {
                        a.buffs.put("hubris", new double[] {num(fx.p.get("dda"), 6), now + num(fx.p.get("duracao"), 30)});
                        led(key(a, fx.item));
                    }
                }
            }
        }
    }

    /** Lethal damage: Guardian Angel puts the champion in stasis and brings it back. */
    private boolean revive(U t) {
        for (T x : t.trigs) {
            if (!"ao_morrer".equals(x.when) || !fire(t, x)) {
                continue;
            }
            double back = Math.max(1, x.delta("Heal"));
            double stasis = num(x.s.get("estase"), 4);
            t.hp = 1e-6;
            t.stasisUntil = now + stasis;
            t.dash = null;
            t.forced = null;
            String src = key(t, x.d.source);
            at(now + stasis, () -> {
                if (!t.dead) {
                    double before = t.hp;
                    t.hp = Math.min(t.maxHp, back);
                    led(src).healed += t.hp - before;
                    t.r.healed += t.hp - before;
                    x.rep.healed += t.hp - before;
                    event(t, "voltou com " + Math.round(t.hp) + " de Vida (" + x.d.source + ")");
                }
            });
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ runes in the fight

    /** Damage type of a rune / item effect: adaptive is magic when the ability power passes the bonus attack damage. */
    private static String fxType(U s, Map<String, Object> p) {
        String t = String.valueOf(p.getOrDefault("dano_tipo", "fisico"));
        return "adaptativo".equals(t) ? adaptive(s) : t;
    }

    private static String adaptive(U s) {
        return s.stat(Stats.ABILITY_POWER) > s.bonus(Stats.ATTACK_DAMAGE) ? "magico" : "fisico";
    }

    /** Damage of a rune's formula: `dano` (+ `_nv15`), `dda_bonus`, `pdh`, `vida_bonus`, `vida_max`. */
    private static double runeDamage(U s, Map<String, Object> p) {
        return level(p, "dano", s.f.level) + num(p.get("dda_bonus"), 0) * s.bonus(Stats.ATTACK_DAMAGE)
                + num(p.get("pdh"), 0) * s.stat(Stats.ABILITY_POWER) + num(p.get("vida_bonus"), 0) * s.bonus(Stats.MAX_HEALTH)
                + num(p.get("vida_max"), 0) * s.maxHp;
    }

    /** Cooldown key of a source: a rune's "runa:<name>" (as its modeled effects), an item's name (as its strikes). */
    private static String sourceKey(String kind, String source) {
        return "runa".equals(kind) ? "runa:" + source : "campeao".equals(kind) ? "campeao:" + source : source;
    }

    /**
     * One activation of a source (rune or item) by one of its parts (modeled effect, trigger, stat lines): every part
     * with a cooldown shares the source's one cooldown, and the parts that go off at the same instant are one
     * activation, counted once. Parts without a cooldown of their own (stacks per attack) are never held back.
     */
    private boolean activate(U u, String kind, String source, double cooldown, double tolerance, boolean counts) {
        String k = sourceKey(kind, source);
        if (cooldown > 0) {
            Double at = u.sourceFiredAt.get(k);
            if (at == null || at != now) {
                if (now < u.strikeReady.getOrDefault(k, 0.0) - tolerance) {
                    return false;
                }
                u.sourceFiredAt.put(k, now);
                u.strikeReady.put(k, now + cooldown);
            }
        }
        if (counts && !"campeao".equals(kind)) {
            proc(u, kind, source);
        }
        return true;
    }

    /** A rune or item went off: counted once per instant (its parts together), with the second of each activation. */
    private void proc(U u, String kind, String source) {
        String k = sourceKey(kind, source);
        Double counted = u.sourceCountedAt.get(k);
        if (counted != null && counted == now) {
            return;
        }
        u.sourceCountedAt.put(k, now);
        count(u, ("runa".equals(kind) ? "runa_ativou:" : "item_ativou:") + source);
        u.r.procTimes.computeIfAbsent(source, x -> new ArrayList<>()).add(Math.round(now * 100) / 100.0);
    }

    private boolean runeReady(U s, String name) {
        return now >= s.strikeReady.getOrDefault("runa:" + name, 0.0) - 1e-9;
    }

    private void runeCooldown(U s, ItemEffect e) {
        if (e.p.get("recarga") != null) {
            s.strikeReady.put("runa:" + e.item, now + level(e.p, "recarga", s.f.level));
        }
    }

    /** Damage amplifications of runes on one hit (Coup de Grace, Cut Down, Last Stand, Zeal, Axiom Arcanist...). */
    private void runeAmps(U a, U t, int kind, String source, Map<String, Double> amps) {
        double tf = t.hp / Math.max(1, t.maxHp);
        double af = a.hp / Math.max(1, a.maxHp);
        boolean ult = source != null && source.startsWith("R ·");
        for (ItemEffect e : a.f.effects) {
            double pct = 0;
            switch (e.kind()) {
                case "misericordia":
                    pct = tf < num(e.p.get("vida_alvo_abaixo"), 0.4) ? num(e.p.get("pct"), 0.08) : 0;
                    break;
                case "dilacerar":
                    pct = kind == ATTACK && tf > num(e.p.get("vida_alvo_acima"), 0.6) ? num(e.p.get("pct"), 0.065) : 0;
                    break;
                case "ate_a_morte":
                    if (kind == ATTACK && af < num(e.p.get("vida_de"), 0.6)) {
                        double from = num(e.p.get("vida_de"), 0.6);
                        double to = num(e.p.get("vida_ate"), 0.3);
                        double x = Math.max(0, Math.min(1, (from - af) / Math.max(1e-9, from - to)));
                        pct = num(e.p.get("min"), 0.05) + (num(e.p.get("max"), 0.11) - num(e.p.get("min"), 0.05)) * x;
                    }
                    break;
                case "zelo":
                    if (kind == ABILITY && !ult && a.combatSince >= 0) {
                        pct = num(e.p.get("por_s"), 0.014) * Math.min(num(e.p.get("max"), 3), Math.floor(now - a.combatSince));
                    }
                    break;
                case "arcanista":
                    pct = ult && kind == ABILITY ? num(e.p.get("pct"), 0.1) : 0;
                    break;
                case "fortalecimento":
                    pct = now < a.buff("fortalecimento") ? num(e.p.get("amplifica"), 0.08) : 0;
                    break;
                default:
                    break;
            }
            if (pct > 0) {
                amps.merge(key(a, e.item), pct, Math::max);
            }
        }
    }

    /** Rune procs on any hit of an attack or ability on a champion (Electrocute, Dark Harvest, Cheap Shot...). */
    private void runeOnHit(U a, U t, int kind, double hpBefore) {
        if (kind != ATTACK && kind != ABILITY || t.dead && hpBefore <= 0) {
            return;
        }
        for (ItemEffect e : a.f.effects) {
            switch (e.kind()) {
                case "eletrocutar": {
                    List<Double> hits = a.runeHits.computeIfAbsent(t, k -> new ArrayList<>());
                    hits.add(now);
                    double win = num(e.p.get("janela"), 3);
                    hits.removeIf(h -> h < now - win);
                    if (hits.size() >= num(e.p.get("acertos"), 3) && runeReady(a, e.item)) {
                        hits.clear();
                        runeCooldown(a, e);
                        proc(a, "runa", e.item);
                        deal(a, t, runeDamage(a, e.p), fxType(a, e.p), e.item, ITEM, 0);
                    }
                    break;
                }
                case "colheita":
                case "tirano":
                    if (hpBefore / Math.max(1, t.maxHp) < num(e.p.get("vida_alvo"), 0.5) && runeReady(a, e.item)) {
                        runeCooldown(a, e);
                        proc(a, "runa", e.item);
                        deal(a, t, runeDamage(a, e.p), fxType(a, e.p), e.item, ITEM, 0);
                    }
                    break;
                case "golpe_desleal": {
                    boolean slowed = t.slows.stream().anyMatch(x -> now < x[1]) || now < t.stunUntil || now < t.airUntil;
                    if (slowed && runeReady(a, e.item)) {
                        runeCooldown(a, e);
                        proc(a, "runa", e.item);
                        deal(a, t, runeDamage(a, e.p), "verdadeiro", e.item, ITEM, 0);
                    }
                    break;
                }
                case "impacto":
                    if (now - a.dashedAt <= num(e.p.get("janela"), 4) && runeReady(a, e.item)) {
                        runeCooldown(a, e);
                        proc(a, "runa", e.item);
                        a.dashedAt = -100;
                        deal(a, t, runeDamage(a, e.p), "verdadeiro", e.item, ITEM, 0);
                    }
                    break;
                case "sequencia": {
                    Integer marks = a.seqMarks.get(t);
                    if (marks != null && marks > 0) {
                        a.seqMarks.put(t, marks - 1);
                        proc(a, "runa", e.item);
                        deal(a, t, runeDamage(a, e.p), fxType(a, e.p), e.item, ITEM, 0);
                    } else if (kind == ABILITY && runeReady(a, e.item)) {
                        runeCooldown(a, e);
                        a.seqMarks.put(t, (int) num(e.p.get("golpes"), 2));
                    }
                    break;
                }
                case "aery":
                    if (now >= a.aeryBack) {
                        a.aeryBack = now + num(e.p.get("retorno"), 2);
                        proc(a, "runa", e.item);
                        deal(a, t, runeDamage(a, e.p), fxType(a, e.p), e.item, ITEM, 0);
                    }
                    break;
                case "primeiro_ataque":
                    if (a.firstStrikeUntil < 0 && now - a.combatSince <= num(e.p.get("janela"), 0.25) + 1e-9) {
                        a.firstStrikeUntil = now + num(e.p.get("duracao"), 3);
                        proc(a, "runa", e.item);
                        event(a, "Primeiro Ataque ativo");
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /** Rune procs of an attack on a champion (Press the Attack, Grasp, Lethal Tempo, Phase Rush...). */
    private void runeOnAttack(U s, U t) {
        if (s.lastAttackTarget != t) {
            s.attackChain = 0;
        }
        s.lastAttackTarget = t;
        s.attackChain++;
        for (ItemEffect e : s.f.effects) {
            switch (e.kind()) {
                case "fortalecimento":
                    if (s.attackChain >= num(e.p.get("ataques"), 3) && runeReady(s, e.item)) {
                        s.attackChain = 0;
                        runeCooldown(s, e);
                        proc(s, "runa", e.item);
                        deal(s, t, runeDamage(s, e.p), fxType(s, e.p), e.item, ITEM, 0);
                        s.buffs.put("fortalecimento", new double[] {Double.MAX_VALUE, Double.MAX_VALUE});
                        event(s, "Fortalecimento: +" + Math.round(num(e.p.get("amplifica"), 0.08) * 100) + "% de dano");
                    }
                    break;
                case "ritmo_fatal":
                    if (trigOn(s, e.item)) {
                        double mult = 1 + num(e.p.get("por_vel_ataque"), 0.005) * Math.max(0, s.stat("% Attack Speed"));
                        deal(s, t, level(e.p, "dano", s.f.level) * mult, fxType(s, e.p), e.item, ITEM, 0);
                    }
                    break;
                case "aperto": {
                    double ready = s.strikeReady.getOrDefault("runa:" + e.item, s.combatSince + num(e.p.get("a_cada"), 3));
                    if (s.combatSince >= 0 && now >= ready - 1e-9) {
                        s.strikeReady.put("runa:" + e.item, now + num(e.p.get("a_cada"), 3));
                        proc(s, "runa", e.item);
                        deal(s, t, num(e.p.get("vida_max"), 0.033) * s.maxHp, String.valueOf(e.p.getOrDefault("dano_tipo", "magico")),
                                e.item, ITEM, 0);
                        double h = num(e.p.get("cura_vida_max"), 0.013) * s.maxHp;
                        heal(s, h, Map.of(key(s, e.item), h));
                    }
                    break;
                }
                case "ataque_fortalecido":
                    if (runeReady(s, e.item)) {
                        s.strikeReady.put("runa:" + e.item, now + num(e.p.get("a_cada"), 8));
                        proc(s, "runa", e.item);
                        deal(s, t, runeDamage(s, e.p), fxType(s, e.p), e.item, ITEM, 0);
                    }
                    break;
                case "lumescudo": {
                    String k = "lumescudo:" + t.idx;
                    if (now >= s.strikeReady.getOrDefault(k, 0.0) - 1e-9) {
                        s.strikeReady.put(k, now + num(e.p.get("recarga"), 6));
                        proc(s, "runa", e.item);
                        double ad = s.stat(Stats.ATTACK_DAMAGE);
                        double c = Math.min(1, s.stat("% Critical Rate") / 100);
                        double extra = ad * num(e.p.get("mult"), 1.6) - ad * (1 + c);   // the hit was an average crit
                        if (extra > 0) {
                            deal(s, t, extra, "fisico", e.item, ITEM, 0);
                        }
                        double h = num(e.p.get("cura_dda_base"), 1.25) * s.base(Stats.ATTACK_DAMAGE)
                                + num(e.p.get("cura_vida_perdida"), 0.06) * Math.max(0, s.maxHp - s.hp);
                        heal(s, h, Map.of(key(s, e.item), h));
                    }
                    break;
                }
                default:
                    break;
            }
        }
        // Navori: each attack cuts the basic abilities' remaining cooldowns.
        if (s.f.items.containsKey("Adagas Rápidas Navori")) {
            for (String slot : List.of("1", "2", "3")) {
                double r = s.ready.getOrDefault(slot, 0.0);
                if (r > now) {
                    s.ready.put(slot, now + (r - now) * 0.85);
                }
            }
        }
    }

    /** Rune procs of an ability that hit a champion (Comet, Scorch, Transcendence). */
    private void runeOnAbility(U s, U t) {
        for (ItemEffect e : s.f.effects) {
            switch (e.kind()) {
                case "cometa":
                    if (runeReady(s, e.item)) {
                        runeCooldown(s, e);
                        proc(s, "runa", e.item);
                        double dmg = runeDamage(s, e.p) + num(e.p.get("por_acerto"), 2) * s.cometHits;
                        at(now + num(e.p.get("atraso"), 1), () -> {
                            if (!t.dead && !s.dead) {
                                s.cometHits++;
                                deal(s, t, dmg, fxType(s, e.p), e.item, ITEM, 0);
                            }
                        });
                    }
                    break;
                case "chamuscar":
                    if (runeReady(s, e.item)) {
                        runeCooldown(s, e);
                        proc(s, "runa", e.item);
                        double dmg = runeDamage(s, e.p);
                        at(now + num(e.p.get("atraso"), 1), () -> deal(s, t, dmg, "magico", e.item, DOT, 0));
                    }
                    break;
                case "transcendencia":
                    if (s.f.level >= num(e.p.get("nivel_min"), 9) && s.lastCast != null && !"R".equals(s.lastCast)
                            && runeReady(s, e.item)) {
                        runeCooldown(s, e);
                        proc(s, "runa", e.item);
                        double r = s.ready.getOrDefault(s.lastCast, 0.0);
                        if (r > now) {
                            s.ready.put(s.lastCast, r - (r - now) * num(e.p.get("pct"), 0.08));
                        }
                        led(key(s, e.item));
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /** Glacial Augment: the ice field after an immobilize (damage around the target and a slow). */
    private void glacial(U by, U t, ItemEffect e) {
        if (!activate(by, "runa", e.item, level(e.p, "recarga", by.f.level), 1e-9, true)) {
            return;
        }
        double slow = num(e.p.get("lentidao"), 0.15) + num(e.p.get("lentidao_por_vida_bonus"), 0) * by.bonus(Stats.MAX_HEALTH);
        double x = t.x;
        double y = t.y;
        at(now + num(e.p.get("atraso"), 1), () -> {
            for (U v : enemies(by)) {
                if (dist(v.x, v.y, x, y) <= num(e.p.get("raio"), 300) + v.radius()) {
                    v.slows.add(new double[] {slow, now + 1.5});
                    deal(by, v, runeDamage(by, e.p), "magico", e.item, ITEM, 0);
                }
            }
        });
    }

    // ------------------------------------------------------------------ what each effect is worth (the planner)

    private static String n0(double v) {
        return String.format(java.util.Locale.ROOT, "%,.0f", v).replace(',', '.');
    }

    private static String n1(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v).replace('.', ',');
    }

    private static String pctText(double v) {
        return Math.round(v * 1000) / 10.0 % 1 == 0 ? Math.round(v * 100) + "%" : n1(v * 100) + "%";
    }

    /** Stat names as the shop writes them (for the planner's texts). */
    private static final Map<String, String> STAT_PT = Map.ofEntries(Map.entry(Stats.ARMOR, "Armadura"),
            Map.entry(Stats.MAGIC_RESIST, "Resistência Mágica"), Map.entry(Stats.MAX_HEALTH, "Vida"),
            Map.entry(Stats.ATTACK_DAMAGE, "Dano de Ataque"), Map.entry(Stats.ABILITY_POWER, "Poder de Habilidade"),
            Map.entry(Stats.ABILITY_HASTE, "Aceleração de Habilidade"), Map.entry(Stats.MOVE_SPEED, "Velocidade de Movimento"),
            Map.entry("% Move Speed", "% de Velocidade de Movimento"), Map.entry("% Attack Speed", "% de Velocidade de Ataque"),
            Map.entry("% Tenacity", "% de Tenacidade"), Map.entry("% Omnivamp", "% de Vampirismo Universal"),
            Map.entry("% Lifesteal", "% de Roubo de Vida"), Map.entry("% Armor Penetration", "% de Penetração de Armadura"),
            Map.entry("% Magic Penetration", "% de Penetração Mágica"), Map.entry("Armor Penetration", "Penetração de Armadura"),
            Map.entry("Magic Penetration", "Penetração Mágica"), Map.entry("% Critical Rate", "% de Chance de Crítico"));

    private static String typeText(String type) {
        return "magico".equals(type) ? "mágico" : "verdadeiro".equals(type) ? "verdadeiro" : "fisico".equals(type) ? "físico" : type;
    }

    private void value(U u, String source, String text, Double amount, String type, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("text", text);
        if (amount != null) {
            m.put("value", Math.round(amount * 10) / 10.0);
        }
        if (type != null) {
            m.put("type", type);
        }
        if (detail != null && !detail.isEmpty()) {
            m.put("detail", detail);
        }
        u.r.effectValues.add(m);
    }

    /**
     * A formula of the fight written term by term as it adds up: the value and its explanation come from the same
     * terms, so they never disagree ("20 + 6% × 248 Armadura adicional (15) = 35").
     */
    private static final class Calc {
        double total;
        final List<String> parts = new ArrayList<>();
        final List<String> target = new ArrayList<>();

        /** A flat amount (with where it comes from, or null). */
        Calc flat(double v, String what) {
            if (Math.abs(v) > 1e-9) {
                total += v;
                parts.add(n0(v) + (what == null ? "" : " (" + what + ")"));
            }
            return this;
        }

        /** `key` of the effect at the champion's level: its range when it grows with the level. */
        Calc level(Map<String, Object> p, String key, int level) {
            double v = Arena.level(p, key, level);
            if (Math.abs(v) <= 1e-9) {
                return this;
            }
            if (p.get(key + "_nv15") != null) {
                return flat(v, n0(num(p.get(key), 0)) + "–" + n0(num(p.get(key + "_nv15"), 0)) + " pelo nível; " + level + " agora");
            }
            return flat(v, null);
        }

        /** A ratio of one of the champion's stats. */
        Calc ratio(Map<String, Object> p, String key, double stat, String statName) {
            double r = num(p.get(key), 0);
            if (Math.abs(r) > 1e-9) {
                total += r * stat;
                parts.add(pctText(r) + " × " + n0(stat) + " " + statName + " (" + n0(r * stat) + ")");
            }
            return this;
        }

        /** A part that depends on the target (kept as a share of its stat). */
        Calc target(Map<String, Object> p, String key, String what) {
            double r = num(p.get(key), 0);
            if (r > 1e-9) {
                target.add(pctText(r) + " da " + what + " do alvo");
            }
            return this;
        }

        String detail() {
            String sum = parts.isEmpty() ? "0" : String.join(" + ", parts);
            return (parts.size() > 1 ? sum + " = " + n0(total) : sum) + (target.isEmpty() ? "" : "; mais " + String.join(" e ", target));
        }

        String targetText() {
            return target.isEmpty() ? "" : " + " + String.join(" + ", target);
        }
    }

    /** The champion's stats as the terms of the formulas name them (as the fight starts). */
    private static final String AD = "Dano de Ataque", AD_BASE = "Dano de Ataque base", AD_BONUS = "Dano de Ataque adicional",
            AP = "Poder de Habilidade", HP_MAX = "Vida máxima", HP_BONUS = "Vida adicional", ARMOR_BONUS = "Armadura adicional",
            MANA_MAX = "Mana máxima";

    /**
     * What each item and rune effect of a champion is worth: the same formulas the fight uses, each with its terms
     * ("detail"), on the planner's sheet when the fighter carries one (its stats with the build's conditional switches,
     * as the planner shows them), else on the stats as the fight starts; the parts that depend on the target stay as a
     * share of its health. Then the planner's conditional effects with what they give when on.
     */
    /** A stat for the planner's view: its sheet when given (the build's switches), else the fight's. */
    private static double st(U u, String s) {
        return u.f.sheet != null ? u.f.sheet.getOrDefault(s, 0.0) : u.stat(s);
    }

    private static double bs(U u, String s) {
        return u.f.sheetBase != null ? u.f.sheetBase.getOrDefault(s, 0.0) : u.base(s);
    }

    private static double bn(U u, String s) {
        return st(u, s) - bs(u, s);
    }

    private static double hp(U u) {
        return u.f.sheet != null ? st(u, Stats.MAX_HEALTH) : u.maxHp;
    }

    private void describeEffects(U u) {
        int lv = u.f.level;
        for (ItemEffect e : u.f.effects) {
            Map<String, Object> p = e.p;
            String type = type(p);
            switch (e.kind()) {
                case "ao_contato": {
                    Calc c = new Calc().flat(num(p.get("dano"), 0), null).ratio(p, "pdh", st(u, Stats.ABILITY_POWER), AP)
                            .ratio(p, "mana", st(u, Stats.MAX_MANA), MANA_MAX).target(p, "vida_atual_alvo", "Vida atual");
                    value(u, e.item, "Cada ataque: " + n0(c.total) + " de dano " + typeText(type) + c.targetText(), c.total, type, c.detail());
                    break;
                }
                case "a_cada_ataque": {
                    Calc c = new Calc().level(p, "dano", lv).ratio(p, "dda_base", bs(u, Stats.ATTACK_DAMAGE), AD_BASE)
                            .ratio(p, "vida", hp(u), HP_MAX).ratio(p, "vida_bonus", bn(u, Stats.MAX_HEALTH), HP_BONUS);
                    value(u, e.item, "A cada " + (int) num(p.get("n"), 3) + " ataques: " + n0(c.total) + " de dano " + typeText(type),
                            c.total, type, c.detail());
                    break;
                }
                case "lamina": {
                    Calc c = new Calc().ratio(p, "dda_base", bs(u, Stats.ATTACK_DAMAGE), AD_BASE).ratio(p, "dda", st(u, Stats.ATTACK_DAMAGE), AD)
                            .ratio(p, "pdh", st(u, Stats.ABILITY_POWER), AP).ratio(p, "armadura_bonus", bn(u, Stats.ARMOR), ARMOR_BONUS)
                            .target(p, "vida_max_alvo", "Vida máxima");
                    Calc heal = new Calc().ratio(p, "cura_vida_bonus", bn(u, Stats.MAX_HEALTH), HP_BONUS)
                            .ratio(p, "cura_pdh", st(u, Stats.ABILITY_POWER), AP).target(p, "cura_vida_max_alvo", "Vida máxima");
                    boolean heals = !heal.parts.isEmpty() || !heal.target.isEmpty();
                    value(u, e.item, "Lâmina Arcana (ataque depois de uma habilidade, recarga 1,5s): " + n0(c.total) + " de dano "
                            + typeText(type) + c.targetText() + (heals ? "; cura " + n0(heal.total) + heal.targetText() : ""),
                            c.total, type, "dano: " + c.detail() + (heals ? " · cura: " + heal.detail() : ""));
                    break;
                }
                case "queimadura": {
                    Calc c = new Calc().flat(num(p.get("por_s"), 0), null).ratio(p, "pdh_por_s", st(u, Stats.ABILITY_POWER), AP)
                            .target(p, "vida_max_alvo_por_s", "Vida máxima");
                    int ticks = (int) num(p.get("duracao"), 3);
                    value(u, e.item, "Queimadura das habilidades por " + ticks + "s, dano " + typeText(type) + ": "
                            + (c.total > 0 ? n0(c.total) + "/s (" + n0(c.total * ticks) + ")" : "") + (c.total > 0 && !c.target.isEmpty() ? " + " : "")
                            + (c.target.isEmpty() ? "" : String.join(" + ", c.target) + " por segundo"), c.total * ticks, type,
                            "por segundo: " + c.detail() + "; × " + ticks + "s");
                    break;
                }
                case "aura": {
                    double every = num(p.get("a_cada"), 1);
                    Calc c = new Calc().level(p, "dano", lv).ratio(p, "vida_bonus", bn(u, Stats.MAX_HEALTH), HP_BONUS)
                            .ratio(p, "vida_max", hp(u), HP_MAX);
                    double heal = num(p.get("cura_do_dano"), 0);
                    value(u, e.item, "Aura em combate (inimigos a 400): " + n0(c.total) + " de dano " + typeText(type) + " a cada " + n1(every)
                            + "s (" + n0(c.total / every) + "/s por inimigo)" + (heal > 0 ? "; cura " + pctText(heal) + " do dano causado ("
                            + n0(c.total * heal) + " por pulso num alvo, antes das resistências)" : ""), c.total / every, type,
                            "por pulso: " + c.detail() + (every != 1 ? "; ÷ " + n1(every) + "s" : "")
                                    + (heal > 0 ? "; cura " + pctText(heal) + " × " + n0(c.total) + " = " + n0(c.total * heal) : ""));
                    break;
                }
                case "golpe": {
                    Calc c = new Calc().level(p, "dano", lv).ratio(p, "vida_max", hp(u), HP_MAX).ratio(p, "pdh", st(u, Stats.ABILITY_POWER), AP)
                            .ratio(p, "dda_bonus", bn(u, Stats.ATTACK_DAMAGE), AD_BONUS).ratio(p, "vida_bonus", bn(u, Stats.MAX_HEALTH), HP_BONUS)
                            .ratio(p, "mana", st(u, Stats.MAX_MANA), MANA_MAX).target(p, "vida_max_alvo", "Vida máxima");
                    String when = "habilidade".equals(p.get("gatilho")) ? "ao acertar uma habilidade"
                            : "ataque".equals(p.get("gatilho")) ? "ao atacar" : "ao acertar";
                    value(u, e.item, "Golpe " + when + " (recarga " + n1(num(p.get("recarga"), 10)) + "s): " + n0(c.total) + " de dano "
                            + typeText(type) + c.targetText(), c.total, type, c.detail());
                    break;
                }
                case "espinhos": {
                    Calc c = new Calc().flat(num(p.get("dano"), 0), null).ratio(p, "armadura_bonus", bn(u, Stats.ARMOR), ARMOR_BONUS)
                            .ratio(p, "vida_bonus", bn(u, Stats.MAX_HEALTH), HP_BONUS);
                    value(u, e.item, "Reflete " + n0(c.total) + " de dano " + typeText(type) + " a cada ataque sofrido", c.total, type, c.detail());
                    break;
                }
                case "feridas":
                    value(u, e.item, "Feridas Dolorosas: −" + pctText(num(p.get("pct"), 0.4)) + " de cura e escudo por 3s, "
                            + (Boolean.TRUE.equals(p.get("ao_ser_atingido")) ? "em quem ataca este campeão"
                            : "ao causar dano" + ("qualquer".equals(String.valueOf(p.getOrDefault("gatilho", "qualquer"))) ? ""
                            : " " + typeText(String.valueOf(p.get("gatilho"))))), num(p.get("pct"), 0.4), null, "valor fixo do item");
                    break;
                case "corta_escudo": {
                    double base = num(p.get("base"), 0);
                    double ad = num(p.get("dda_bonus"), 0) * bn(u, Stats.ATTACK_DAMAGE);
                    double ap = num(p.get("pdh"), 0) * st(u, Stats.ABILITY_POWER);
                    double max = num(p.get("max"), 0.6);
                    double cut = Math.min(max, base + ad + ap);
                    String detail = pctText(base) + " base"
                            + (ad > 0 ? " + " + pctText(num(p.get("dda_bonus"), 0)) + " × " + n0(bn(u, Stats.ATTACK_DAMAGE)) + " Dano de Ataque adicional ("
                            + pctText(ad) + ")" : "")
                            + (ap > 0 ? " + " + pctText(num(p.get("pdh"), 0)) + " × " + n0(st(u, Stats.ABILITY_POWER)) + " Poder de Habilidade ("
                            + pctText(ap) + ")" : "")
                            + (ad + ap > 0 ? " = " + pctText(base + ad + ap) : "") + "; máximo " + pctText(max);
                    value(u, e.item, "Corta " + pctText(cut) + " dos escudos do alvo (os que ele tem e os que receber por 3s)", cut, null, detail);
                    break;
                }
                case "reduz_resistencia":
                    value(u, e.item, "Reduz a " + ("armadura".equals(p.get("resistencia")) ? "Armadura" : "Resistência Mágica") + " do alvo em "
                            + pctText(num(p.get("por_acerto"), 0.06)) + " por acerto, até " + pctText(num(p.get("max"), 0.3)) + " ("
                            + n1(num(p.get("duracao"), 6)) + "s)", num(p.get("max"), 0.3), null,
                            "o máximo vem com " + (int) Math.ceil(num(p.get("max"), 0.3) / Math.max(1e-9, num(p.get("por_acerto"), 0.06)) - 1e-9)
                                    + " acertos seguidos");
                    break;
                case "amplifica": {
                    String scope = (p.get("dano_tipo") != null ? " " + typeText(String.valueOf(p.get("dano_tipo"))) : "")
                            + (Boolean.TRUE.equals(p.get("so_habilidade")) ? " de habilidades" : "");
                    String text = p.get("pct_por_s") != null
                            ? "+" + pctText(num(p.get("pct_por_s"), 0)) + " de dano" + scope + " por segundo em combate, até " + pctText(num(p.get("max"), 1))
                            : p.get("alvo_vida_bonus") != null
                            ? "Até +" + pctText(num(p.get("max"), 0)) + " de dano" + scope + " (máximo com " + n0(num(p.get("alvo_vida_bonus"), 1200))
                            + " de Vida adicional do alvo)"
                            : "+" + pctText(num(p.get("pct"), 0)) + " de dano" + scope + (Boolean.TRUE.equals(p.get("vida_alta")) ? " acima de 50% da Vida" : "");
                    String detail = p.get("pct_por_s") != null
                            ? "chega ao máximo em " + n1(num(p.get("max"), 1) / Math.max(1e-9, num(p.get("pct_por_s"), 0))) + "s de combate"
                            : p.get("alvo_vida_bonus") != null ? "proporcional à Vida adicional do alvo, até " + n0(num(p.get("alvo_vida_bonus"), 1200))
                            : "multiplica todo o dano" + scope;
                    value(u, e.item, text, num(p.containsKey("pct") ? p.get("pct") : p.get("max"), 0), null, detail);
                    break;
                }
                case "amplifica_alvo":
                    value(u, e.item, (Boolean.TRUE.equals(p.get("aura")) ? "Inimigos próximos sofrem" : "O alvo de uma habilidade sofre")
                            + " +" + pctText(num(p.get("pct"), 0)) + " de dano" + (p.get("dano_tipo") != null ? " "
                            + typeText(String.valueOf(p.get("dano_tipo"))) : "") + (Boolean.TRUE.equals(p.get("marca"))
                            ? " por " + n1(num(p.get("duracao"), 4)) + "s" : ""), num(p.get("pct"), 0), null,
                            Boolean.TRUE.equals(p.get("aura")) ? "de todos os aliados, a até 650" : "de todos os aliados, enquanto durar a marca");
                    break;
                case "reduz_dano":
                    value(u, e.item, "−" + pctText(num(p.get("pct"), 0)) + " do dano "
                            + ("critico".equals(p.get("contra")) ? "de acertos críticos" : "de ataques") + " sofrido", num(p.get("pct"), 0), null,
                            "depois das resistências");
                    break;
                case "critico":
                    value(u, e.item, "Acertos críticos causam " + pctText(num(p.get("mult"), 2)), num(p.get("mult"), 2), null,
                            "sem o item: 200%; o crítico conta pelo valor esperado (chance × multiplicador)");
                    break;
                case "reduz_vel_ataque":
                    value(u, e.item, "Inimigos próximos atacam " + pctText(num(p.get("pct"), 0)) + " mais devagar", num(p.get("pct"), 0), null,
                            "a até 700; vale o maior entre os itens dos inimigos");
                    break;
                case "ativo": {
                    Calc c = new Calc().level(p, "dano", lv).ratio(p, "dda", st(u, Stats.ATTACK_DAMAGE), AD)
                            .ratio(p, "dda_base", bs(u, Stats.ATTACK_DAMAGE), AD_BASE).ratio(p, "dda_bonus", bn(u, Stats.ATTACK_DAMAGE), AD_BONUS)
                            .ratio(p, "pdh", st(u, Stats.ABILITY_POWER), AP).target(p, "vida_max_alvo", "Vida máxima");
                    Calc heal = new Calc().ratio(p, "cura_dda", st(u, Stats.ATTACK_DAMAGE), AD);
                    value(u, e.item, "Ativo (recarga " + n0(num(p.get("recarga"), 60)) + "s): " + n0(c.total) + " de dano " + typeText(type)
                            + c.targetText() + (heal.total > 0 ? "; cura " + n0(heal.total) : ""), c.total, type,
                            "dano: " + c.detail() + (heal.total > 0 ? " · cura: " + heal.detail() : ""));
                    break;
                }
                default:
                    if (p.get("dano") != null) {   // a rune's proc (Electrocute, Glacial Augment, Grasp...): runeDamage
                        String t = "adaptativo".equals(p.get("dano_tipo"))
                                ? (st(u, Stats.ABILITY_POWER) > bn(u, Stats.ATTACK_DAMAGE) ? "magico" : "fisico") : fxType(u, p);
                        Calc c = new Calc().level(p, "dano", lv).ratio(p, "dda_bonus", bn(u, Stats.ATTACK_DAMAGE), AD_BONUS)
                                .ratio(p, "pdh", st(u, Stats.ABILITY_POWER), AP).ratio(p, "vida_bonus", bn(u, Stats.MAX_HEALTH), HP_BONUS)
                                .ratio(p, "vida_max", hp(u), HP_MAX);
                        value(u, e.item, "Dano: " + n0(c.total) + " " + typeText(t) + (p.get("recarga") != null
                                ? " (recarga " + n1(level(p, "recarga", lv)) + "s)" : ""), c.total, t,
                                c.detail() + ("adaptativo".equals(p.get("dano_tipo")) ? "; adaptativo: mágico quando o Poder de Habilidade"
                                        + " passa do Dano de Ataque adicional" : ""));
                    } else if (p.get("vida_max") != null) {
                        Calc c = new Calc().ratio(p, "vida_max", hp(u), HP_MAX);
                        value(u, e.item, "Dano: " + n0(c.total), c.total, null, c.detail());
                    } else if (p.get("pct") != null) {
                        value(u, e.item, "+" + pctText(num(p.get("pct"), 0)) + " de dano", num(p.get("pct"), 0), null, null);
                    }
                    break;
            }
        }
        // the planner's conditional effects: when they turn on and what they give (shields, heals, stats)
        for (T t : u.trigs) {
            if (!t.d.fightTriggered() || t.d.delta.isEmpty()) {
                continue;
            }
            List<String> gives = new ArrayList<>();
            t.d.delta.forEach((stat, v) -> {
                if (Math.abs(v) < 0.5 && !stat.startsWith("%")) {
                    return;
                }
                String name = "Shield".equals(stat) ? "de escudo" : "Magic Shield".equals(stat) ? "de escudo mágico"
                        : "Physical Shield".equals(stat) ? "de escudo físico" : "Heal".equals(stat) ? "de cura"
                        : "Stasis".equals(stat) ? "s de estase" : STAT_PT.getOrDefault(stat, stat);
                gives.add(("Stasis".equals(stat) ? n1(v) : (v > 0 ? "+" : "") + n0(v)) + (name.startsWith("%") || name.startsWith("s ") ? "" : " ") + name);
            });
            if (!gives.isEmpty()) {
                value(u, t.d.source, (t.d.effect.equals(t.d.source) ? "" : t.d.effect + ": ") + String.join(", ", gives)
                        + " (" + t.rep.how + ")", t.d.delta.getOrDefault("Shield", t.d.delta.getOrDefault("Heal", null)), null,
                        "medido pelo planejador: os status da build com o efeito ligado menos desligado, neste minuto (Forja Viva"
                                + " e o resto da build incluídos); " + (t.d.buildOn ? "ligado na build: já está nos status da ficha"
                                : "desligado na build: fora dos status da ficha"));
            }
        }
    }

    /** Whether a trigger of a source is on (Lethal Tempo at full stacks). */
    private static boolean trigOn(U u, String source) {
        for (T t : u.trigs) {
            if (t.d.source.equals(source) && t.on()) {
                return true;
            }
        }
        return false;
    }

    /** Health regeneration (per 5s, with the % of the items and of triggers on). */
    private void regen(U u) {
        if (u.dead || now < u.nextRegen) {
            return;
        }
        u.nextRegen = now + 0.5;
        double per5 = u.stat(Stats.HEALTH_REGEN) * (1 + u.stat(Stats.PCT_HEALTH_REGEN) / 100);
        if (per5 > 0 && u.hp < u.maxHp && now >= u.stasisUntil) {
            heal(u, per5 / 10, Map.of(key(u, "Regeneração de Vida"), per5 / 10));
        }
    }

    private U nearestEnemy(U u) {
        U best = null;
        for (U e : enemies(u)) {
            if (best == null || dist(u.x, u.y, e.x, e.y) < dist(u.x, u.y, best.x, best.y)) {
                best = e;
            }
        }
        return best;
    }

    /** The start state of a drill: cooldowns, resource, a knock-up, an enemy's mark on it. */
    private void drill(U u) {
        Input in = u.in;
        in.startCooldowns.forEach((k, v) -> u.ready.put(k, Math.max(0, v)));
        if (in.startResource != null) {
            u.resource = in.startResource;
        }
        if (in.startAirborne != null && in.startAirborne > 0) {
            u.airUntil = in.startAirborne;
        }
        if (in.markedBy != null && in.markedBy >= 0 && in.markedBy < units.size()) {
            U by = units.get(in.markedBy);
            if (map(by.kit("fragil")) != null) {
                u.brittleUntil = 3;
                u.brittleBy = by;
                u.brittleCounted = 3;
            }
            Map<String, Object> bolts = map(by.kit("marcas"));
            if (bolts != null) {
                by.bolts.put(u, new double[] {num(bolts.get("max"), 3) - 1, 3});
                by.boltCounted.put(u, 3.0);
            }
            if (map(by.kit("marcas_ksante")) != null) {
                by.ksMarks.put(u, 3.0);
                by.ksCounted.put(u, 3.0);
            }
            Map<String, Object> stacks = map(by.kit("acumulos"));
            if (stacks != null) {
                by.stacks.put(u, new double[] {num(stacks.get("max"), 5) - 1, 4});
                by.stackCounted.put(u, 4.0);
                by.nextStackTick = 1;
            }
        }
    }

    /** Whether a champion's sheet keeps a mark on its targets (what a "marked target" drill needs). */
    public static boolean marks(Map<String, Object> kit) {
        return kit != null && (kit.get("fragil") != null || kit.get("marcas") != null || kit.get("marcas_ksante") != null
                || kit.get("acumulos") != null);
    }

    // ------------------------------------------------------------------ the ragdoll (lab)

    /**
     * A ragdoll's move: it never attacks or casts. parado holds still; anda walks side to side across the lane
     * (turning every 1.5s or at a wall); foge walks away from its attacker. reage stays still but its policy may Flash
     * away or Cleanse (true = the step is done).
     */
    private boolean dummyStep(U u) {
        String b = u.in.dummy.behavior == null ? "parado" : u.in.dummy.behavior;
        switch (b) {
            case "anda": {
                boolean wall = Math.abs(u.y + u.dummyDir * 30) > halfWidth - u.radius() - 45;
                if (now >= u.dummyTurnAt - 1e-9 || wall) {
                    u.dummyDir = -u.dummyDir;
                    u.dummyTurnAt = now + 1.5;
                }
                moveTo(u, u.x, clampY(u.y + u.dummyDir * 100), u.moveSpeed() * DT);
                u.busyState = "move";
                return true;
            }
            case "foge":
                if (target(u) != null) {
                    retreat(u);
                }
                return true;
            case "reage":
                return false;   // its policy (Flash away / Cleanse only) decides
            default:
                return true;
        }
    }

    /**
     * A ragdoll that applies effects: the on-hit effects of the items it wears (Grievous Wounds, armor / magic resist
     * shred, shield cut, marks) land on the champion it faces every few seconds as if it hit, or stay at their most.
     */
    private void dummyEffects(U u) {
        ArenaModel.Dummy d = u.in.dummy;
        if (d == null || !d.appliesEffects || now < u.dummyHitAt - 1e-9) {
            return;
        }
        U t = target(u);
        if (t == null) {
            return;
        }
        int hits = d.atMax ? 12 : 1;
        for (int k = 0; k < hits; k++) {
            onHit(u, t, "fisico", ATTACK);
            onHit(u, t, "magico", ABILITY);
            reaver(u, t);
        }
        // at their most: refreshed before anything can fall off
        u.dummyHitAt = now + (d.atMax ? 0.5 : Math.max(0.1, d.hitEvery));
    }

    // ------------------------------------------------------------------ combos

    /** A combo may start: its start conditions hold and its first step can be done now (or after its wait). */
    private boolean comboCanStart(Turn turn, Combo c) {
        if (c.steps == null || c.steps.isEmpty() || !Facts.all(turn.obs, c.start)) {
            return false;
        }
        ComboStep first = c.steps.get(0);
        if (first.until != null) {
            return true;   // it starts by waiting for an event
        }
        U u = turn.u;
        U t = turn.target;
        String[] a = action(first);
        switch (a[0]) {
            case "habilidade": {
                Map<String, Object> ab = ability(u, a[1]);
                return ab != null && isReady(u, a[1]) && inUseRange(u, t, ab);
            }
            case "reconjurar": {
                double[] w = u.recast.get(a[1]);
                return w != null && now >= w[0] - 1e-9 && now <= w[1];
            }
            case "ativo":
                return activeUsable(u, a[1], null);
            case "atacar":
                return dist(u.x, u.y, t.x, t.y) <= u.attackRange() + u.radius() + t.radius();
            default:
                return true;
        }
    }

    private static String[] action(ComboStep s) {
        String a = s.action == null ? "esperar" : s.action;
        int i = a.indexOf(':');
        return i < 0 ? new String[] {a, null} : new String[] {a.substring(0, i), a.substring(i + 1)};
    }

    private ComboRun newRun(U u, Combo c, int action, U target) {
        ComboRun run = new ComboRun(c, action, target, now);
        run.report.fighter = u.idx;
        run.report.combo = c.name;
        run.report.start = round3(now);
        count(u, "combo_iniciado:" + c.name);
        return run;
    }

    private void endCombo(U u, String outcome, String reason) {
        ComboRun run = u.combo;
        if (run == null) {
            return;
        }
        u.combo = null;
        run.report.end = round3(now);
        run.report.outcome = outcome;
        run.report.reason = reason;
        snapshot(u, run.target, run.report.snapshot);
        result.combos.add(run.report);
        count(u, "done".equals(outcome) ? "combo_concluido:" + run.spec.name : "combo_abortado:" + run.spec.name);
        if (!"done".equals(outcome)) {
            event(u, "abortou o combo " + run.spec.name + " no passo " + (run.step + 1) + ": " + reason);
        }
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    /** What the champion could do next and how the target is, when a combo ends (see ComboReport#snapshot). */
    private void snapshot(U u, U t, Map<String, Double> out) {
        for (String slot : ActionSpace.SLOTS) {
            Map<String, Object> ab = ability(u, slot);
            if (ab != null && u.rank(slot) > 0) {
                out.put("pronto:" + slot, round3(Math.max(0, u.ready.getOrDefault(slot, 0.0) - now)));
            }
            double[] w = u.recast.get(slot);
            if (w != null && now <= w[1]) {
                out.put("reconjurar:" + slot, round3(Math.max(0, w[0] - now)));
            }
        }
        for (String name : u.actives) {
            out.put("ativo:" + name, round3(Math.max(0, u.ready.getOrDefault(name, 0.0) - now)));
        }
        out.put("ataque", round3(Math.max(0, u.nextAttack - now)));
        out.put("ocupado", round3(Math.max(0, u.busyUntil - now) + (u.dash != null ? 0.2 : 0)));
        if (t != null && !t.dead) {
            out.put("alvo_no_ar", round3(Math.max(0, t.airUntil - now)));
            out.put("alvo_controle", round3(controlLeft(t)));
            out.put("alvo_marca", round3(markLeft(u, t)));
            out.put("alvo_distancia", round3(dist(u.x, u.y, t.x, t.y)));
        }
        out.put("t", round3(now));
    }

    /**
     * One step of the running combo: it stops when its time is up, the target is gone, an abort condition holds or a
     * step cannot be done; else it waits for the step's event (at most its max), then does the step's action, and
     * goes on to the next one in the same step while the champion stays free.
     */
    private void runCombo(U u, Turn turn) {
        ComboRun run = u.combo;
        U t = run.target;
        Combo c = run.spec;
        for (int guard = 0; guard < 20 && u.combo == run; guard++) {
            if (now - run.start > c.maxTime + 1e-9) {
                endCombo(u, "aborted", "tempo esgotado");
                return;
            }
            if (t.dead || !t.targetable() || !t.visible()) {
                endCombo(u, "aborted", t.dead ? "alvo morreu" : "alvo sumiu");
                return;
            }
            ArenaModel.Condition stop = Facts.any(turn.obs, c.abort);
            if (stop != null) {
                endCombo(u, "aborted", "abortar_se " + stop);
                return;
            }
            if (run.step >= c.steps.size()) {
                endCombo(u, "done", null);
                return;
            }
            ComboStep st = c.steps.get(run.step);
            String[] a = action(st);
            boolean move = "mover".equals(a[0]);
            if (st.until != null && !run.waited && !move) {
                if (!waitHolds(u, t, st, run) && now - run.stepStart < st.max - 1e-9) {
                    u.busyState = "combo";
                    return;   // waits for the event, in place
                }
                run.waited = true;
            }
            String outcome = comboAction(u, t, st, a, run, turn);
            if ("drop".equals(outcome)) {
                endCombo(u, "aborted", "passo impossível: " + st.action);
                return;
            }
            if (!"done".equals(outcome)) {
                return;
            }
            run.report.steps.add(new Event(round3(now), u.idx, (run.step + 1) + ". " + st));
            run.step++;
            run.stepStart = now;
            run.waited = false;
            if (run.step >= c.steps.size()) {
                endCombo(u, "done", null);
                return;
            }
            if (now < u.busyUntil || u.dash != null) {
                return;
            }
        }
    }

    /** The event a step waits for has come. */
    private boolean waitHolds(U u, U t, ComboStep st, ComboRun run) {
        switch (st.until) {
            case "alvo_pousa":
                return t.airUntil - now <= st.delta + 1e-9;
            case "alvo_no_chao":
                return now >= t.airUntil && now - t.airUntil >= st.delta - 1e-9;
            case "marca_aplicada":
                return markLeft(u, t) > 0;
            case "reconjuracao_acertaria": {
                String slot = st.slot != null ? st.slot : action(st)[1];
                Map<String, Object> ab = slot == null ? null : ability(u, slot);
                double[] w = slot == null ? null : u.recast.get(slot);
                return ab != null && w != null && now >= w[0] - 1e-9 && recastPrediction(u, t, slot, ab)[0] >= 0.6;
            }
            case "controle_restante":
                return controlLeft(t) <= st.delta + 1e-9;
            case "acertaria": {
                // the step's ability would hit now by the prediction (a wall or pillar on the way, the target held...)
                String slot = st.slot != null ? st.slot : action(st)[1];
                Map<String, Object> ab = slot == null ? null : ability(u, slot);
                return ab != null && castPrediction(u, t, ab)[0] >= 0.6;
            }
            case "ataque_pronto":
                return now >= u.nextAttack - 1e-9;
            case "tempo":
                return now - run.stepStart >= st.delta - 1e-9;
            default:
                return true;   // an unknown event does not hold the combo
        }
    }

    /** The action of a combo step: done, wait (try again next step) or drop (it cannot be done in the combo's time). */
    private String comboAction(U u, U t, ComboStep st, String[] a, ComboRun run, Turn turn) {
        double end = run.start + run.spec.maxTime;
        switch (a[0]) {
            case "habilidade": {
                Map<String, Object> ab = ability(u, a[1]);
                if (ab == null || u.rank(a[1]) <= 0) {
                    return "drop";
                }
                if (!isReady(u, a[1])) {
                    return u.ready.getOrDefault(a[1], 0.0) > end ? "drop" : "wait";
                }
                if (!inUseRange(u, t, ab)) {
                    moveTo(u, t.x, t.y, u.moveSpeed() * DT);   // into range first
                    u.busyState = "move";
                    return "wait";
                }
                String out = castAbility(u, a[1], t, t.x, t.y, null);
                if ("done".equals(out)) {
                    comboOrder(u, "ability", a[1], t, null);
                }
                return out;
            }
            case "reconjurar": {
                double[] w = u.recast.get(a[1]);
                boolean coming = w != null || "elemental".equals(ability(u, a[1]) == null ? null : ability(u, a[1]).get("forma"))
                        && u.elemental != null;
                if (!coming) {
                    return "drop";
                }
                String out = recast(u, a[1], t, t.x, t.y);
                if ("done".equals(out)) {
                    comboOrder(u, "recast", a[1], null, null);
                }
                return out;
            }
            case "ativo": {
                String name = a[1];
                boolean spell = u.f.spells.contains(name);
                if (!spell && !u.f.items.containsKey(name)) {
                    return "drop";
                }
                if (now < u.ready.getOrDefault(name, 0.0) - 1e-9) {
                    return u.ready.getOrDefault(name, 0.0) > end ? "drop" : "wait";
                }
                String out = spell ? useSpellNimbus(u, name, t, null, null) : useItem(u, name, t);
                if ("done".equals(out)) {
                    comboOrder(u, spell ? "spell" : "item", null, t, name);
                }
                return out;
            }
            case "atacar": {
                double reach = u.attackRange() + u.radius() + t.radius();
                if (dist(u.x, u.y, t.x, t.y) > reach || !t.visible()) {
                    moveTo(u, t.x, t.y, u.moveSpeed() * DT);
                    u.busyState = "move";
                    return "wait";
                }
                if (now < u.nextAttack - 1e-9) {
                    u.facing = Math.atan2(t.y - u.y, t.x - u.x);
                    return "wait";
                }
                attack(u, t);
                return "done";
            }
            case "mover": {
                if (st.until != null && waitHolds(u, t, st, run) || now - run.stepStart >= st.max - 1e-9) {
                    return "done";
                }
                if ("longe".equals(a[1])) {
                    double d = Math.max(1e-6, dist(u.x, u.y, t.x, t.y));
                    moveTo(u, u.x + (u.x - t.x) / d * 100, u.y + (u.y - t.y) / d * 100, u.moveSpeed() * DT);
                } else {
                    moveTo(u, t.x, t.y, u.moveSpeed() * DT);
                }
                u.busyState = "move";
                return "wait";
            }
            default:   // esperar: the wait was the step
                return "done";
        }
    }

    /** A cast of a combo as an order of the timeline ("turn into a timeline"). */
    private void comboOrder(U u, String type, String slot, U t, String name) {
        if (!recordFrames) {
            return;
        }
        Action o = new Action();
        o.fighter = u.idx;
        o.time = Math.round(now * 100) / 100.0;
        o.type = type;
        o.slot = slot;
        o.name = name;
        o.target = t != null && !"recast".equals(type) ? t.idx : null;
        result.policyOrders.add(o);
    }

    // ------------------------------------------------------------------ displacement immunity and mobility

    /** Stuns during an immunity: void (Ornn's W, unstoppable). */
    static final String CC_IGNORE = "ignora";
    /** Stuns during an immune dash: the dash goes on and the stun lands at its end, with its time left (default). */
    static final String CC_AFTER = "continua_depois";
    /** Stuns during an immune dash: they stop it, as without immunity. */
    static final String CC_BREAKS = "interrompe";

    /** {@code imune_deslocamento} and {@code cc_durante_avanco} of a sheet (an ability or its recast) on a dash. */
    private static void immunity(Dash dash, Map<String, Object> spec) {
        if (spec != null && Boolean.TRUE.equals(spec.get("imune_deslocamento"))) {
            dash.immune = true;
            dash.ccMode = String.valueOf(spec.getOrDefault("cc_durante_avanco", CC_AFTER));
        }
    }

    /** Pulls, pushes and knock-ups are void now: an immune channel or dash. */
    private boolean displacementImmune(U t) {
        return now < t.immuneUntil || t.dash != null && t.dash.immune;
    }

    /** What a stun does on the unit now: null (as always), ignora, continua_depois or interrompe. */
    private String ccRule(U t) {
        if (now < t.immuneUntil) {
            return t.immuneCc;
        }
        return t.dash != null && t.dash.immune ? t.dash.ccMode : null;
    }

    /**
     * The champion's mobility (slot -> avanco | salto | corrida | imune): its sheet's {@code mobilidade}, else read from
     * its abilities (dashes; targeted abilities that leap).
     */
    Map<String, String> mobility(U u) {
        if (u.mobility == null) {
            u.mobility = readMobility(u);
        }
        return u.mobility;
    }

    private Map<String, String> readMobility(U u) {
        Map<String, Object> m = map(u.kit("mobilidade"));
        Map<String, String> out = new LinkedHashMap<>();
        if (m != null) {
            m.forEach((slot, kind) -> out.put(slot, String.valueOf(kind)));
            return out;
        }
        for (String slot : ActionSpace.SLOTS) {
            Map<String, Object> ab = ability(u, slot);
            if (ab != null && "avanco".equals(ab.get("forma"))) {
                out.put(slot, "avanco");
            } else if (ab != null && Boolean.TRUE.equals(ab.get("salta"))) {
                out.put(slot, "salto");
            }
        }
        return out;
    }

    /** A dash or leap of its own is up (Flash aside): it can step out of a skillshot or follow. */
    boolean dashReady(U u) {
        for (Map.Entry<String, String> e : mobility(u).entrySet()) {
            String k = e.getValue();
            if (("avanco".equals(k) || "salto".equals(k) || "imune".equals(k)) && isReady(u, e.getKey())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the damage rules, for the lab

    /**
     * The share of a hit that gets through a resistance: the very function {@link #deal} uses (after the % reduction,
     * the % and the flat penetration).
     */
    public static double through(double resist, double shred, double pctPen, double flatPen) {
        return DamageRules.mitigation(resist, shred, pctPen, flatPen);
    }

    // ------------------------------------------------------------------ structured events

    /** The mark name of Ornn's passive in the events. */
    static final String BRITTLE = "Frágil";

    private static void count(U u, String key) {
        count(u, key, 1);
    }

    private static void count(U u, String key, double v) {
        if (u != null) {
            u.r.events.merge(key, v, Double::sum);
        }
    }

    /** The slot of an ability source ("R · Name" -> R; a bare slot stays). */
    private static String slotOf(String source) {
        int i = source == null ? -1 : source.indexOf(" ·");
        return i < 0 ? source : source.substring(0, i);
    }

    private String boltMark(U s) {
        Map<String, Object> m = map(s.kit("marcas"));
        Map<String, Object> ab = m == null ? null : ability(s, String.valueOf(m.get("habilidade")));
        return ab == null ? "Marca" : String.valueOf(ab.get("nome"));
    }

    private static String ksMark(U s) {
        Map<String, Object> m = map(s.kit("marcas_ksante"));
        return m != null && m.get("nome") != null ? String.valueOf(m.get("nome")) : "Instinto Destemido";
    }

    /** A new cast (or recast) of a slot: the previous one of the slot is settled (hit or missed). */
    private void openCast(U u, String slot, boolean recast) {
        Map<String, Cast> open = recast ? u.recasts : u.casts;
        Cast old = open.put(slot, new Cast(slot, recast));
        if (old != null) {
            settle(u, old);
        }
    }

    private static void settle(U u, Cast c) {
        if (!c.hit) {
            count(u, (c.recast ? "reconjuracao_errou:" : "habilidade_errou:") + c.slot);
        }
    }

    private void closeCasts(U u) {
        for (Cast c : u.casts.values()) {
            settle(u, c);
        }
        for (Cast c : u.recasts.values()) {
            settle(u, c);
        }
        u.casts.clear();
        u.recasts.clear();
    }

    /** An ability of `u` (its source) hit a champion: its open cast (or recast) counts as a hit, once. */
    private void credit(U u, String source, boolean recast) {
        String slot = slotOf(source);
        Cast c = (recast ? u.recasts : u.casts).get(slot);
        if (c != null && !c.hit) {
            c.hit = true;
            count(u, (recast ? "reconjuracao_acertou:" : "habilidade_acertou:") + slot);
        }
    }

    /**
     * An immobilize of a kind applied by `by` on `t`, which still had `before` seconds of control left: chained when
     * it starts as the previous one ends (at most {@link #CHAIN_SLACK} of overlap, or within 0.1s after it ended),
     * overlapped when more than that is wasted.
     */
    private void ccNoted(U by, U t, String type, double before, double seconds) {
        if (by == null || by.team() == t.team()) {
            return;
        }
        count(by, "cc_aplicado:" + type);
        if (recordFrames && by.lastCast != null && by.lastUse.containsKey(by.lastCast)) {
            by.lastUse.get(by.lastCast).cc.add(type + " " + String.format(java.util.Locale.ROOT, "%.2f", seconds)
                    .replace('.', ',') + "s em " + t.f.name);
        }
        if (before > 1e-9) {
            count(by, Math.min(before, seconds) > CHAIN_SLACK + 1e-9 ? "cc_sobreposto" : "cc_encadeado");
        } else if (now - t.ccHeldAt <= 0.1 + DT + 1e-9) {
            count(by, "cc_encadeado");
        }
        t.ccBy = by;
    }

    /** Every step: time under control, marks that expired, recast windows that closed unused. */
    private void trackEvents() {
        for (U t : units) {
            if (t.dead) {
                continue;
            }
            if (now >= t.nextHpSample - 1e-9) {
                t.nextHpSample = now + 0.1;
                t.hpHistory.addLast(new double[] {now, t.hp});
                while (t.hpHistory.size() > 1 && t.hpHistory.peekFirst()[0] < now - 2 - 1e-9) {
                    t.hpHistory.removeFirst();
                }
            }
            if (now < t.stunUntil || now < t.airUntil || t.forced != null) {
                count(t, "tempo_controlado", DT);
                count(t.ccBy, "controle_causado", DT);
                List<double[]> spans = t.r.controlSpans;
                double[] last = spans.isEmpty() ? null : spans.get(spans.size() - 1);
                if (last != null && now - last[1] <= DT + 1e-6) {
                    last[1] = round3(now + DT);
                } else {
                    spans.add(new double[] {round3(now), round3(now + DT)});
                }
                t.ccHeldAt = now;
            }
            if (t.brittleCounted > 0 && now >= t.brittleCounted - 1e-9) {
                count(t.brittleBy, "marca_expirou:" + BRITTLE);
                t.brittleCounted = -1;
            }
            expire(t, t.stackCounted, () -> String.valueOf(map(t.kit("acumulos")).get("nome")));
            expire(t, t.boltCounted, () -> boltMark(t));
            expire(t, t.ksCounted, () -> ksMark(t));
            watchRecasts(t);
        }
    }

    private void expire(U s, Map<U, Double> marks, java.util.function.Supplier<String> name) {
        if (marks.isEmpty()) {
            return;
        }
        for (java.util.Iterator<Map.Entry<U, Double>> it = marks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<U, Double> e = it.next();
            if (now >= e.getValue() - 1e-9) {
                count(s, "marca_expirou:" + name.get());
                it.remove();
            }
        }
    }

    /** Recast windows: followed from when they open; one that closes (or goes away) unused is a lost recast. */
    private void watchRecasts(U u) {
        for (Map.Entry<String, double[]> e : u.recast.entrySet()) {
            if (!e.getKey().contains(":") && now <= e.getValue()[1] && u.recastWatch.get(e.getKey()) != e.getValue()) {
                double[] old = u.recastWatch.put(e.getKey(), e.getValue());
                if (old != null) {
                    lostRecast(u, e.getKey(), old);
                }
            }
        }
        for (java.util.Iterator<Map.Entry<String, double[]>> it = u.recastWatch.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, double[]> e = it.next();
            double[] w = e.getValue();
            if (u.recastUsed.contains(w)) {
                it.remove();
            } else if (u.recast.get(e.getKey()) != w || now > w[1]) {
                it.remove();
                lostRecast(u, e.getKey(), w);
            }
        }
    }

    private void lostRecast(U u, String slot, double[] w) {
        if (u.recastUsed.remove(w)) {
            return;
        }
        String why = u.recastTry.remove(slot);
        if (why == null) {
            why = !u.canAct() ? "em controle" : now < u.busyUntil || u.dash != null ? "ocupado com " + u.busyState : "não tentou";
        }
        count(u, "reconjuracao_perdida:" + slot + ":" + why);
    }

    // ------------------------------------------------------------------ frames and helpers

    private void frame() {
        if (!recordFrames) {
            flashes.clear();
            return;
        }
        Frame f = new Frame();
        f.t = Math.round(now * 1000) / 1000.0;
        for (U u : units) {
            UnitFrame uf = new UnitFrame();
            uf.x = round(u.x);
            uf.y = round(u.y);
            uf.hp = round(Math.max(0, u.hp));
            uf.maxHp = round(u.maxHp);
            uf.shield = round(u.shieldLeft());
            uf.state = u.dead ? "dead" : now < u.stasisUntil ? "stasis" : now < u.airUntil ? "air" : now < u.stunUntil ? "stun"
                    : u.forced != null ? "displaced" : u.dash != null ? "dash" : now < u.busyUntil ? u.busyState : u.busyState;
            uf.invisible = !u.visible();
            Map<String, Object> res = map(u.kit("recurso"));
            if (res != null) {
                uf.resource = round(u.resource);
            }
            for (String slot : List.of("1", "2", "3", "R")) {
                if (u.rank(slot) > 0) {
                    uf.cooldowns.put(slot, round(Math.max(0, u.ready.getOrDefault(slot, 0.0) - now)));
                }
            }
            for (String name : u.in.activeCooldowns.keySet()) {
                uf.cooldowns.put(name, round(Math.max(0, u.ready.getOrDefault(name, 0.0) - now)));
            }
            if (!u.charges.isEmpty()) {
                uf.charges = new LinkedHashMap<>(u.charges);
            }
            if (now < u.brittleUntil) {
                uf.marks.add("Frágil");
            }
            for (double[] s : u.slows) {
                if (now < s[1]) {
                    uf.marks.add("Lentidão " + Math.round(s[0] * 100) + "%");
                    break;
                }
            }
            if (now < u.gwUntil) {
                uf.marks.add("Feridas Dolorosas");
            }
            for (Map.Entry<String, Double> e : u.formUntil.entrySet()) {
                if (now < e.getValue()) {
                    uf.marks.add(e.getKey());
                }
            }
            if (u.empowered != null && now <= u.empoweredUntil) {
                uf.marks.add("Ataque fortalecido");
            }
            for (T t : u.trigs) {
                if (t.on() && t.until < Double.MAX_VALUE / 2) {
                    uf.marks.add(t.d.effect.equals(t.d.source) ? t.d.source : t.d.source + " · " + t.d.effect);
                }
            }
            for (Map.Entry<String, double[]> e : u.recast.entrySet()) {
                if (!e.getKey().contains(":") && now >= e.getValue()[0] && now <= e.getValue()[1]) {
                    uf.marks.add("Reconjurar " + e.getKey());
                }
            }
            f.units.add(uf);
        }
        for (Projectile p : projectiles) {
            Shape s = new Shape();
            s.id = p.id;
            s.kind = "circle";
            s.team = p.owner.team();
            s.label = p.label;
            s.x = round(p.x);
            s.y = round(p.y);
            s.radius = Math.max(15, p.width / 2);
            s.angle = Math.atan2(p.dy, p.dx);
            f.shapes.add(s);
        }
        for (Field fl : fields) {
            if (now < fl.from && !fl.terrain) {
                continue;
            }
            f.shapes.add("rect".equals(fl.kind)
                    ? rect(fl.id, fl.label, fl.owner.team(), fl.x, fl.y, fl.length, fl.width, fl.angle)
                    : circle(fl.id, fl.label, fl.terrain ? -1 : fl.owner.team(), fl.x, fl.y, fl.radius));
        }
        for (Elemental e : elementals) {
            Shape s = rect("elemental-" + e.id, "Elemental de lava (Ornn)" + (e.redirected ? ", devolvido" : ""),
                    e.owner.team(), e.x, e.y, 120, e.width, Math.atan2(e.dy, e.dx));
            f.shapes.add(s);
        }
        f.shapes.addAll(flashes);
        flashes.clear();
        result.frames.add(f);
    }

    private void flash(String kind, String label, U owner, double x, double y, Double radius, Double length, Double angle,
                       Double arcOrWidth) {
        Shape s = new Shape();
        s.id = "fx" + (ids++);
        s.kind = kind;
        s.team = owner.team();
        s.label = label;
        s.x = round(x);
        s.y = round(y);
        s.radius = radius;
        s.length = length;
        s.angle = angle;
        if ("cone".equals(kind)) {
            s.arc = arcOrWidth;
        } else {
            s.width = arcOrWidth;
        }
        flashes.add(s);
    }

    private static Shape rect(String id, String label, int team, double x, double y, double length, double width, double angle) {
        Shape s = new Shape();
        s.id = id;
        s.kind = "rect";
        s.team = team;
        s.label = label;
        s.x = round(x);
        s.y = round(y);
        s.length = length;
        s.width = width;
        s.angle = angle;
        return s;
    }

    private static Shape circle(String id, String label, int team, double x, double y, double radius) {
        Shape s = new Shape();
        s.id = id;
        s.kind = "circle";
        s.team = team;
        s.label = label;
        s.x = round(x);
        s.y = round(y);
        s.radius = radius;
        return s;
    }

    private void event(U u, String text) {
        result.events.add(new Event(Math.round(now * 1000) / 1000.0, u.idx, u.f.name + " " + text));
    }

    private static String describe(Action a) {
        String what = "ability".equals(a.type) ? "habilidade " + a.slot : "recast".equals(a.type) ? "reconjurar " + a.slot
                : "item".equals(a.type) || "spell".equals(a.type) ? a.name : a.type;
        return fmt(a.time) + "s: " + what;
    }

    private List<U> enemies(U u) {
        List<U> out = new ArrayList<>();
        for (U x : units) {
            if (x.team() != u.team() && x.targetable()) {
                out.add(x);
            }
        }
        return out;
    }

    private List<U> allies(U u) {
        List<U> out = new ArrayList<>();
        for (U x : units) {
            if (x.team() == u.team() && x != u && !x.dead) {
                out.add(x);
            }
        }
        return out;
    }

    /** Champions of a side still in the fight (alive and not escaped). */
    private int fighting(int team) {
        int n = 0;
        for (U x : units) {
            if (x.team() == team && !x.dead && !x.escaped) {
                n++;
            }
        }
        return n;
    }

    /** In the side's safe zone (its stretch of the lane from its end). */
    private boolean inZone(U u, int team) {
        return team == 0 ? u.x <= safeZone.depth : u.x >= length - safeZone.depth;
    }

    /**
     * The safe zone: a champion of its side who gets there alive escapes (leaves the fight, as far as the others are
     * concerned); enemies inside take its damage per second (true damage, every 0.5s).
     */
    private void tickSafeZone() {
        if (safeZone == null) {
            return;
        }
        boolean towerTick = now >= nextTowerTick - 1e-9;
        if (towerTick) {
            nextTowerTick += 0.5;
        }
        for (U u : units) {
            if (u.dead || u.escaped) {
                continue;
            }
            if (u.team() == safeZone.team && inZone(u, safeZone.team)) {
                u.escaped = true;
                u.r.escapedAt = round3(now);
                u.dash = null;
                u.forced = null;
                if (u.combo != null) {
                    endCombo(u, "aborted", "escapou");
                }
                count(u, "escapou");
                event(u, "chegou à zona segura");
            } else if (u.team() != safeZone.team && safeZone.damagePerSecond > 0 && inZone(u, safeZone.team)
                    && towerTick) {
                U tower = null;
                for (U x : units) {
                    if (x.team() == safeZone.team) {
                        tower = x;
                        break;
                    }
                }
                if (tower != null) {
                    double before = u.hp;
                    u.hp -= safeZone.damagePerSecond * 0.5;
                    u.r.taken += before - Math.max(0, u.hp);
                    u.r.damageBySource.merge("Torre (sofrido)", before - Math.max(0, u.hp), Double::sum);
                    if (u.hp <= 0) {
                        u.dead = true;
                        u.r.deathTime = now;
                        event(u, "foi abatido pela torre");
                    }
                }
            }
        }
    }

    private int alive(int team) {
        int n = 0;
        for (U x : units) {
            if (x.team() == team && !x.dead) {
                n++;
            }
        }
        return n;
    }

    private Map<String, Double> share(U s, Map<String, Double> sources, double amount) {
        Map<String, Double> out = new LinkedHashMap<>();
        double total = sources.values().stream().mapToDouble(Double::doubleValue).sum();
        sources.forEach((k, v) -> out.put(key(s, k), total > 0 ? amount * v / total : 0));
        return out;
    }

    private static String key(U s, String source) {
        return s.idx + "|" + source;
    }

    private Ledger led(String key) {
        return ledger.computeIfAbsent(key, k -> {
            Ledger l = new Ledger();
            int bar = k.indexOf('|');
            l.fighter = Integer.parseInt(k.substring(0, bar));
            l.source = k.substring(bar + 1);
            return l;
        });
    }

    private double clampY(double y) {
        return Math.max(-halfWidth + 40, Math.min(halfWidth - 40, y));
    }

    private double clampX(double x) {
        return Math.max(40, Math.min(length - 40, x));
    }

    /**
     * A value of a sheet at a rank and level: lists are per rank; with `key_nv15` it goes from the value (level 1) to
     * that one (level 15).
     */
    @SuppressWarnings("unchecked")
    static double val(Map<String, Object> m, String key, int rank, int level, double def) {
        if (m == null || m.get(key) == null) {
            return def;
        }
        Object v = m.get(key);
        double a;
        if (v instanceof List) {
            List<Object> list = (List<Object>) v;
            a = num(list.get(Math.max(0, Math.min(list.size(), rank) - 1)), def);
        } else {
            a = num(v, def);
        }
        Object top = m.get(key + "_nv15");
        if (top == null) {
            return a;
        }
        return a + (num(top, a) - a) * (Math.max(1, Math.min(15, level)) - 1) / 14.0;
    }

    /** A damage / heal / shield formula of a sheet for a source on a target at a rank. */
    private static double formula(Map<String, Object> d, U s, U t, int rank) {
        if (d == null) {
            return 0;
        }
        int lv = s.f.level;
        return val(d, "base", rank, lv, 0) + val(d, "dda", rank, lv, 0) * s.stat(Stats.ATTACK_DAMAGE)
                + val(d, "dda_base", rank, lv, 0) * s.base(Stats.ATTACK_DAMAGE)
                + val(d, "dda_bonus", rank, lv, 0) * s.bonus(Stats.ATTACK_DAMAGE)
                + val(d, "pdh", rank, lv, 0) * s.stat(Stats.ABILITY_POWER)
                + val(d, "vida_max_alvo", rank, lv, 0) * (t == null ? 0 : t.maxHp)
                + val(d, "vida_bonus", rank, lv, 0) * Math.max(0, s.maxHp - s.base(Stats.MAX_HEALTH))
                + val(d, "vida_max", rank, lv, 0) * s.maxHp
                + val(d, "arm_bonus", rank, lv, 0) * s.bonus(Stats.ARMOR)
                + val(d, "rm_bonus", rank, lv, 0) * s.bonus(Stats.MAGIC_RESIST);
    }

    private static String type(Map<String, Object> p) {
        if (p == null) {
            return "fisico";
        }
        Object t = p.get("tipo");
        if (t == null || "ativo".equals(t) || "golpe".equals(t) || "aura".equals(t) || "ao_contato".equals(t)
                || "a_cada_ataque".equals(t) || "lamina".equals(t) || "queimadura".equals(t) || "espinhos".equals(t)) {
            return String.valueOf(p.getOrDefault("dano_tipo", "fisico"));
        }
        return String.valueOf(t);
    }

    static double level(Map<String, Object> p, String k, int level) {
        return val(p, k, 1, level, 0);
    }

    static double num(Object v, double def) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v == null) {
            return def;
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static double sum(Map<String, Double> pool) {
        return pool.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    static double dist(double ax, double ay, double bx, double by) {
        return Math.hypot(ax - bx, ay - by);
    }

    /** Distance from point p to segment a-b. */
    static double segDist(double ax, double ay, double bx, double by, double px, double py) {
        double dx = bx - ax;
        double dy = by - ay;
        double l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    static boolean inCone(double ox, double oy, double facing, double range, double arc, double px, double py) {
        double d = Math.hypot(px - ox, py - oy);
        if (d > range) {
            return false;
        }
        if (d < 1) {
            return true;
        }
        double a = Math.atan2(py - oy, px - ox) - facing;
        a = Math.atan2(Math.sin(a), Math.cos(a));
        return Math.abs(a) <= arc / 2;
    }

    /** Point in a rectangle centered at (cx, cy), along `angle`, of a length and width. */
    static boolean inRect(double cx, double cy, double angle, double length, double width, double px, double py) {
        double dx = px - cx;
        double dy = py - cy;
        double along = dx * Math.cos(angle) + dy * Math.sin(angle);
        double across = -dx * Math.sin(angle) + dy * Math.cos(angle);
        return Math.abs(along) <= length / 2 && Math.abs(across) <= width / 2;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** A number without trailing zeros (5 / 2,5). */
    private static String fmtS(double v) {
        return new java.math.BigDecimal(String.valueOf(Math.round(v * 100) / 100.0)).stripTrailingZeros().toPlainString()
                .replace('.', ',');
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v).replace('.', ',');
    }
}
