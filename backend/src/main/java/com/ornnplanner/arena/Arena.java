package com.ornnplanner.arena;

import com.ornnplanner.arena.ArenaModel.Action;
import com.ornnplanner.arena.ArenaModel.Event;
import com.ornnplanner.arena.ArenaModel.FighterStats;
import com.ornnplanner.arena.ArenaModel.Frame;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ArenaModel.Shape;
import com.ornnplanner.arena.ArenaModel.UnitFrame;
import com.ornnplanner.combat.AbilityParser;
import com.ornnplanner.combat.CombatCatalog;
import com.ornnplanner.combat.CombatSimulator.Delay;
import com.ornnplanner.combat.CombatSimulator.Fighter;
import com.ornnplanner.combat.CombatSimulator.ItemEffect;
import com.ornnplanner.combat.CombatSimulator.Ledger;
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
 * scenario always gives the same result, so a search can compare thousands of timelines.
 *
 * <h2>Orders</h2>
 * Each champion follows the scenario's timeline (abilities, recasts, item actives, spells, moves, focus); an order that
 * cannot be done yet (on cooldown, casting, crowd controlled, out of range) waits until it can, and the wait is logged.
 * When nothing is ordered, it chases its target and attacks, and (if allowed) uses ready abilities in its priority.
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
        /** Base attack speed at the fighter's level (attacks / s); bonus % comes from its stats. */
        public double baseAttackSpeed;
        /** Item actives and spells with their cooldown (s). */
        public Map<String, Double> activeCooldowns = new LinkedHashMap<>();
    }

    // ------------------------------------------------------------------ runtime

    private final class U {
        final int idx;
        final Input in;
        final Fighter f;
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
        double unstoppableUntil = -1;
        double nextAttack;
        Double moveX;
        Double moveY;
        boolean hold;
        U focus;
        Dash dash;
        Forced forced;
        double combatSince = -1;
        boolean stasisUsed;
        // cooldowns: ready time by slot / item / spell name
        Map<String, Double> ready = new LinkedHashMap<>();
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
        Map<String, Double> strikeReady = new LinkedHashMap<>();
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

        U(int idx, Input in) {
            this.idx = idx;
            this.in = in;
            this.f = in.f;
            delayOn = new boolean[f.delays.size()];
        }

        int team() {
            return f.team;
        }

        boolean targetable() {
            return !dead && now >= stasisUntil;
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
            if (Stats.ATTACK_DAMAGE.equals(s)) {
                v += buff("dda");
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

    /** A dash of a champion (its ability or a recast). */
    private static final class Dash {
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
    private double now;
    private double halfWidth;
    private double length;

    public Arena(CombatCatalog catalog, Map<String, Object> map) {
        this.catalog = catalog;
        this.map = map;
    }

    // ------------------------------------------------------------------ run

    public Result run(List<Input> inputs, List<Action> actions, double duration) {
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
            units.add(start(i, in, manual));
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
        }
        result.duration = Math.min(now, duration);
        for (U u : units) {
            for (Action a : u.orders) {
                result.skipped.add(new Event(a.time, u.idx, describe(a) + ": não foi possível até o fim da luta"));
            }
            u.r.health = Math.max(0, u.hp);
            result.fighters.add(u.r);
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
        u.hp = u.maxHp;
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
        if (u.forced != null) {
            moveForced(u);
            return;
        }
        if (u.dash != null) {
            moveDash(u);
            return;
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
                    event(u, describe(a) + " (esperou " + fmt(now - a.time) + "s)");
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
        U target = target(u);
        if (!acted && u.in.autoAbilities && target != null) {
            for (String slot : u.in.priority) {
                Map<String, Object> ab = ability(u, slot);
                if (ab != null && isReady(u, slot) && inUseRange(u, target, ab)) {
                    if (u.orders.stream().anyMatch(o -> o.time <= now + 1e-9 && slot.equals(o.slot))) {
                        continue;   // kept for the ordered cast
                    }
                    if ("done".equals(castAbility(u, slot, target, target.x, target.y, null))) {
                        if (now < u.busyUntil || u.dash != null) {
                            return;
                        }
                        break;
                    }
                }
            }
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
        if (target == null) {
            return;
        }
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
                return useSpell(u, a.name, a.target != null && a.target < units.size() ? units.get(a.target) : target(u),
                        a.x, a.y);
            default:
                return "drop";
        }
    }

    private U target(U u) {
        if (u.focus != null && !u.focus.dead && u.focus.targetable() && u.focus.visible()) {
            return u.focus;
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
        // Empowered attack of an ability (Darius' Crippling Strike, Renekton's Ruthless Predator, Vayne's Tumble).
        if (s.empowered != null && now <= s.empoweredUntil) {
            String slot = s.empowered;
            s.empowered = null;
            Map<String, Object> ab = ability(s, slot);
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
        if (now < s.strikeReady.getOrDefault(e.item, 0.0)) {
            return;
        }
        double dmg = level(e.p, "dano", s.f.level) + num(e.p.get("vida_max"), 0) * s.maxHp
                + num(e.p.get("vida_max_alvo"), 0) * t.maxHp + num(e.p.get("pdh"), 0) * s.stat(Stats.ABILITY_POWER)
                + num(e.p.get("dda_bonus"), 0) * s.bonus(Stats.ATTACK_DAMAGE)
                + num(e.p.get("vida_bonus"), 0) * s.bonus(Stats.MAX_HEALTH) + num(e.p.get("mana"), 0) * s.stat(Stats.MAX_MANA);
        deal(s, t, dmg, type(e.p), e.item, ITEM, 0);
        s.strikeReady.put(e.item, now + num(e.p.get("recarga"), 10));
    }

    /** Item effects triggered when an ability hits: burns, strikes, the next attack's spellblade. */
    private void onAbilityItems(U s, U t) {
        s.bladeArmed = true;
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
        boolean strong = spendResource(u, slot);
        if (strong) {
            u.buffs.put("fortalecido:" + slot, new double[] {1, now + 30});
        }
        u.facing = Math.atan2(ty - u.y, tx - u.x);
        double castTime = num(ab.get("conjuracao"), 0);
        u.busyUntil = now + castTime + num(ab.get("trava"), 0);
        u.busyState = "cast:" + slot;
        event(u, "usou " + slot + " (" + ab.get("nome") + ")" + (strong ? " fortalecida" : ""));
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
                        forcedMove(v, u.x + dirx * num(second.get("puxa"), 150), u.y + diry * num(second.get("puxa"), 150), 1500,
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
                }
                if (ab.get("_parsed") != null) {
                    AbilityParser.Ability pa = (AbilityParser.Ability) ab.get("_parsed");
                    if (pa.damage != null) {
                        deal(u, t, pa.damage.at(rank, u::stat, u::base, t.maxHp), pa.damage.type, source, ABILITY, 0);
                    }
                    if (pa.immobilizes) {
                        breakBrittle(u, t);
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
            boltStack(u, v);
        }
        if (map(u.kit("marcas_ksante")) != null && ef != null && Boolean.TRUE.equals(ef.get("marca"))) {
            u.ksMarks.put(v, now + num(map(u.kit("marcas_ksante")).get("duracao"), 4));
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
            breakBrittle(s, t);
        }
        if (ef.get("lentidao") != null) {
            t.slows.add(new double[] {val(ef, "lentidao", rank, s.f.level, 0), now + val(ef, "duracao", rank, s.f.level, 1)});
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
            forcedMove(t, s.x + Math.cos(a) * d, s.y + Math.sin(a) * d, 1400, null, null);
        }
        if (ef.get("fragil") != null && !t.dead) {
            t.brittleUntil = now + num(ef.get("fragil"), 3);
            t.brittleBy = s;
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
        if (Boolean.TRUE.equals(ab.get("imparavel"))) {
            u.unstoppableUntil = now + duration;
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
        u.facing = a;
        boolean noDamage = Boolean.TRUE.equals(ab.get("sem_dano"));
        Map<String, Object> spec = strongSpec(ab, strong);
        int[] hits = new int[1];
        dash.onHit = noDamage ? null : v -> {
            hitAbility(u, v, spec, rank, source, 1, slot);
            if (map(spec.get("efeitos")) != null && map(spec.get("efeitos")).get("empurra") != null) {
                double pa = Math.atan2(v.y - u.y, v.x - u.x);
                double pd = num(map(spec.get("efeitos")).get("empurra"), 200);
                forcedMove(v, v.x + Math.cos(pa) * pd, v.y + Math.sin(pa) * pd, 1200, null, null);
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
            double a = Math.atan2(ty - u.y, tx - u.x);
            u.facing = a;
            double headbutt = num(rec.get("cabecada"), 200);
            Dash dash = new Dash();
            dash.tx = u.x + Math.cos(a) * headbutt;
            dash.ty = u.y + Math.sin(a) * headbutt;
            dash.speed = 1600;
            dash.hitRadius = 0;
            dash.onEnd = () -> {
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
        u.recast.put(slot + ":used", new double[] {now, now});
        boolean strong = spendResource(u, slot);
        event(u, "reconjurou " + slot + " (" + ab.get("nome") + ")" + (strong ? " fortalecida" : ""));
        startDash(u, slot, ab, u.rank(slot), tx, ty, strong, ab.get("nome") + " (" + u.f.champion + ")",
                slot + " · " + ab.get("nome"));
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
            Map<String, Object> d = map(ab.get("dano"));
            deal(u, v, formula(d, u, v, rank), type(d), source, ABILITY, 0);
            boltStack(u, v);
            double a = Math.atan2(v.y - u.y, v.x - u.x);
            double dist = num(ab.get("distancia"), 475);
            Map<String, Object> wall = map(ab.get("terreno"));
            forcedMove(v, v.x + Math.cos(a) * dist, v.y + Math.sin(a) * dist, num(ab.get("velocidade"), 1000), () -> {
                Map<String, Object> wd = map(wall.get("dano"));
                deal(u, v, formula(wd, u, v, rank), type(wd), source + " (parede)", ABILITY, 0);
                stun(v, num(wall.get("atordoa"), 1.5), u);
                event(u, "prendeu " + v.f.name + " na parede");
            }, null);
            onAbilityItems(u, v);
        };
        projectiles.add(p);
    }

    private void allOut(U u, U t, Map<String, Object> ab, int rank, String source) {
        if (t == null || t.dead) {
            return;
        }
        double a = u.facing;
        double push = num(ab.get("empurra"), 300);
        double wallAt = num(ab.get("terreno_ate"), 350);
        Map<String, Object> wall = map(ab.get("terreno"));
        // Into a wall within reach: the target is slammed and stunned; else pushed and briefly stunned.
        double ex = t.x + Math.cos(a) * (push + wallAt);
        double ey = t.y + Math.sin(a) * (push + wallAt);
        boolean hitsWall = Math.abs(ey) > halfWidth - t.radius() || ex < 0 || ex > length;
        forcedMove(t, t.x + Math.cos(a) * push, t.y + Math.sin(a) * push, 1800, null, () -> {
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
        boltStack(s, t);
    }

    private void boltStack(U s, U t) {
        Map<String, Object> m = map(s.kit("marcas"));
        if (m == null || s.rank(String.valueOf(m.get("habilidade"))) <= 0) {
            return;
        }
        s.bolts.keySet().removeIf(k -> k != t);   // stacks fall off when Vayne hits another enemy
        double[] st = s.bolts.computeIfAbsent(t, k -> new double[2]);
        if (now >= st[1]) {
            st[0] = 0;
        }
        st[0]++;
        st[1] = now + num(m.get("duracao"), 3.5);
        if (st[0] >= num(m.get("max"), 3)) {
            st[0] = 0;
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
        if ("Ampulheta de Zhonya".equals(name) || "Armaguarda da Caçadora".equals(name)) {
            u.stasisUntil = now + 2.5;
            u.stasisUsed = true;
            u.ready.put(name, now + cd);
            event(u, "Estase (" + name + ")");
            return "done";
        }
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
                u.ready.put(name, now + num(e.p.get("recarga"), cd));
                event(u, "usou " + name);
                return "done";
            }
        }
        // Shield actives (Placa Gargolítica, Medalhão): the build's shield from that item, now.
        Double amount = u.in.f.shieldSources.get(name);
        if (amount != null) {
            addShield(u, amount, key(u, name), now + 2.5, "any");
            u.ready.put(name, now + cd);
            event(u, "usou " + name);
            return "done";
        }
        return "drop";
    }

    private String useSpell(U u, String name, U t, Double x, Double y) {
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
            default:
                return "drop";
        }
        u.ready.put(name, now + cd);
        event(u, "usou " + name);
        return "done";
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
            u.y = clampY(u.y);
            u.x = clampX(u.x);
            if (wall && dash.onWall != null) {
                dash.onWall.run();
            }
            if (dash.onEnd != null) {
                dash.onEnd.run();
            }
            u.busyUntil = Math.max(u.busyUntil, now);
        }
    }

    private void forcedMove(U t, double tx, double ty, double speed, Runnable onWall, Runnable onEnd) {
        if (t.dead || !t.targetable() || now < t.unstoppableUntil) {
            return;
        }
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
        if (t.dead || !t.targetable() || seconds <= 0 || now < t.unstoppableUntil) {
            return;
        }
        t.stunUntil = Math.max(t.stunUntil, now + seconds * (1 - Math.min(0.9, t.stat("% Tenacity") / 100)));
        t.dash = null;
        t.busyUntil = now;
    }

    private void airborne(U t, double seconds, U by) {
        if (t.dead || !t.targetable() || seconds <= 0 || now < t.unstoppableUntil) {
            return;
        }
        t.airUntil = Math.max(t.airUntil, now + seconds);   // tenacity does not shorten knock-ups
        t.dash = null;
        t.busyUntil = now;
    }

    private void breakBrittle(U s, U t) {
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
                    s.shield.remove(e.getKey());
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
            mult = com.ornnplanner.combat.CombatSimulator.mitigation(resist, shred, pen, flat);
            multNoShred = com.ornnplanner.combat.CombatSimulator.mitigation(resist, 0, pen, flat);
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
        led(key(a, source)).damage += dmg;
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
        if (t.hp <= 0.3 * t.maxHp && !t.stasisUsed && t.stat("Stasis") > 0) {
            t.stasisUsed = true;
            t.hp = Math.max(t.hp, 1);
            t.stasisUntil = now + t.stat("Stasis");
            event(t, "entrou em Estase (" + fmt(t.stat("Stasis")) + "s)");
        }
        if (t.hp <= 0) {
            t.dead = true;
            t.r.deathTime = now;
            t.dash = null;
            t.forced = null;
            event(t, "foi abatido por " + a.f.name + " (" + source + ")");
        }
        if (kind != REFLECT && !a.dead) {
            double vamp = (kind == ATTACK ? a.stat("% Lifesteal") : 0) + a.stat("% Omnivamp");
            if (vamp > 0 && dmg > 0) {
                double h = dmg * vamp / 100;
                Map<String, Double> src = share(a, a.f.vampSources, h);
                if (src.isEmpty()) {
                    src = Map.of(key(a, "Vampirismo"), h);
                }
                heal(a, h, src);
            }
            onHit(a, t, type, kind);
        }
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
        double gw = now < s.gwUntil ? s.gw : 0;
        double total = sources.values().stream().mapToDouble(Double::doubleValue).sum();
        if (gw > 0 && s.gwSource != null) {
            led(s.gwSource).healCut += amount * gw;
            s.r.healLost += amount * gw;
        }
        double effective = Math.min(amount * (1 - gw), Math.max(0, s.maxHp - s.hp));
        s.hp += effective;
        s.r.healed += effective;
        for (Map.Entry<String, Double> e : sources.entrySet()) {
            double sh = total > 0 ? e.getValue() / total : 0;
            led(e.getKey()).healed += effective * sh;
            led(e.getKey()).healLost += amount * gw * sh;
        }
    }

    private void addShield(U s, double amount, String source, double until, String type) {
        double cut = now < s.shieldCutUntil ? s.shieldCutPct : 0;
        if (cut > 0 && s.shieldCutSource != null) {
            led(s.shieldCutSource).shieldCut += amount * cut;
            led(source).shieldLost += amount * cut;
        }
        s.shield.merge(source, amount * (1 - cut), Double::sum);
        s.shieldUntil.put(source, until);
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

    // ------------------------------------------------------------------ frames and helpers

    private void frame() {
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
            Shape s = rect("elemental-" + System.identityHashCode(e), "Elemental de lava (Ornn)" + (e.redirected ? ", devolvido" : ""),
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
                || "a_cada_ataque".equals(t) || "lamina".equals(t) || "queimadura".equals(t)) {
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

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v).replace('.', ',');
    }
}
