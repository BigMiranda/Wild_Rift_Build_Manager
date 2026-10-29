package com.ornnplanner.arena;

import com.ornnplanner.arena.ArenaModel.Combo;
import com.ornnplanner.arena.ArenaModel.ComboReport;
import com.ornnplanner.arena.ArenaModel.Dummy;
import com.ornnplanner.arena.ArenaModel.FighterStats;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ai.ActionMask;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Observation;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.arena.ai.TacticalPolicy;
import com.ornnplanner.arena.fight.CombatCatalog;
import com.ornnplanner.arena.fight.Fighter;
import com.ornnplanner.engine.Stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A combo against the ragdoll, measured: no Spring, no frames (unless asked), deterministic. Two modes: uma_vez (out
 * of combat, everything up, from a starting distance: the combo once) and repetir (for some seconds, the combo again
 * whenever it can start, attacking or kiting in between: sustained damage). Each combo is measured against several
 * behaviors of the ragdoll (parado, anda, foge, reage) and scored by their mean or their worst, with a weight each.
 */
public final class ComboBench {

    public static final List<String> BEHAVIORS = List.of("parado", "anda", "foge", "reage");

    /** How a combo is measured and scored. */
    public static class Config {
        /** uma_vez | repetir */
        public String mode = "uma_vez";
        /** repetir: seconds of the run. */
        public double seconds = 12;
        /** Distance from the champion to the ragdoll at the start. */
        public double distance = 300;
        public List<String> behaviors = new ArrayList<>(BEHAVIORS);
        /** Weight of each behavior in the mean (missing = 1). */
        public Map<String, Double> weights = new LinkedHashMap<>();
        /** media (weighted mean) | pior (worst case) */
        public String aggregate = "media";
        /** Points per second of control on the ragdoll, and per mark consumed, added to the damage. */
        public double controlWeight = 400;
        public double markWeight = 150;
        /** Seconds after the combo's limit that the run goes on, so damage over time lands (uma_vez). */
        public double tail = 1.5;
    }

    /** One run's numbers. */
    public static class Metrics {
        public String behavior;
        public double damage;
        public double dps;
        /** Seconds from the combo's start to its end (or to the end of the run). */
        public double duration;
        public boolean done;
        public String reason;
        public Map<String, Double> bySource = new LinkedHashMap<>();
        public Map<String, Double> byType = new LinkedHashMap<>();
        /** Seconds the ragdoll was immobilized, and the longest stretch it was free while the combo ran. */
        public double control;
        public double longestFree;
        public double marksConsumed;
        public double marksApplied;
        public double marksOverwritten;
        public double chained;
        public double overlapped;
        public Map<String, Double> procs = new LinkedHashMap<>();
        /** The champion's structured events. */
        public Map<String, Double> events = new LinkedHashMap<>();
        public Map<String, Double> cooldownsLeft = new LinkedHashMap<>();
        /** Seconds until every ability the combo uses is up again (when it ended). */
        public double repeatIn;
        public double score;
        public List<ArenaModel.Event> steps = new ArrayList<>();
        /** Where the combo ended (what a search may append): see {@link ComboReport#snapshot}. */
        public Map<String, Double> snapshot = new LinkedHashMap<>();
    }

    /** A combo's numbers on each behavior, and its score. */
    public static class Report {
        public Map<String, Metrics> byBehavior = new LinkedHashMap<>();
        public double score;
    }

    private final CombatCatalog catalog;
    private final Map<String, Object> map;

    public ComboBench(CombatCatalog catalog, Map<String, Object> map) {
        this.catalog = catalog;
        this.map = map;
    }

    /** A ragdoll's fighter from its stats (no build worn): what the bench fights when no build is given. */
    public static Arena.Input dummyInput(Dummy d) {
        Fighter f = new Fighter();
        f.name = d.name == null ? "Boneco de pano" : d.name;
        f.champion = "Boneco de pano";
        f.team = 1;
        f.level = 1;
        for (Map<String, Double> m : List.of(f.total, f.base)) {
            m.put(Stats.MAX_HEALTH, d.health);
            m.put(Stats.ARMOR, d.armor);
            m.put(Stats.MAGIC_RESIST, d.magicResist);
            m.put(Stats.MOVE_SPEED, d.moveSpeed);
            m.put(Stats.ATTACK_DAMAGE, d.attackDamage);
        }
        f.ranks = new int[4];
        withStats(f, d, true);
        Arena.Input in = new Arena.Input();
        in.f = f;
        in.build = f.name;
        in.baseAttackSpeed = dummyAttackSpeed(d);
        in.kit = dummyKit(d);
        withSpells(in, d);
        in.dummy = d;
        return in;
    }

    /**
     * The ragdoll's other stats ({@link Dummy#stats}) on top of what it has. {@code base}: they count as its own base
     * too (a ragdoll without items); with items they were already its base in the planner, but for the ones the
     * planner's ragdoll unit has no base of (critical rate, penetrations, life steal...).
     */
    static void withStats(Fighter f, Dummy d, boolean base) {
        if (d.stats == null) {
            return;
        }
        d.stats.forEach((stat, v) -> {
            if (v == null || !base && Stats.UNIT_STATS.contains(stat)) {
                return;
            }
            f.total.merge(stat, v, Double::sum);
            if (base) {
                f.base.merge(stat, v, Double::sum);
            }
        });
    }

    /** The ragdoll's own spells (to react), with their cooldowns. */
    static void withSpells(Arena.Input in, Dummy d) {
        for (String s : d.spells) {
            if (!in.f.spells.contains(s)) {
                in.f.spells.add(s);
            }
            in.activeCooldowns.putIfAbsent(s, "Flash".equals(s) ? 150.0 : 110.0);
        }
    }

    /** A ragdoll that attacks hits once a second; the others keep the old 0.6 (they never attack). */
    static double dummyAttackSpeed(Dummy d) {
        if (d.attackSpeed != null && d.attackSpeed > 0) {
            return d.attackSpeed;
        }
        return "ataca".equals(d.behavior) ? 1.0 : 0.6;
    }

    /** The radius of the ragdoll goes into its sheet (it has no abilities). */
    static Map<String, Object> dummyKit(Dummy d) {
        Map<String, Object> kit = new LinkedHashMap<>();
        kit.put("raio", d.radius);
        kit.put("alcance", d.attackRange == null ? 0.0 : Math.max(0, d.attackRange));
        kit.put("habilidades", Map.of());
        return kit;
    }

    public Report evaluate(Arena.Input champion, Arena.Input dummy, Combo combo, Config cfg) {
        Report r = new Report();
        double sum = 0;
        double weights = 0;
        double worst = Double.POSITIVE_INFINITY;
        for (String b : cfg.behaviors) {
            Metrics m = metrics(run(champion, dummy, combo, b, cfg, false), combo, b, cfg);
            r.byBehavior.put(b, m);
            double w = cfg.weights.getOrDefault(b, 1.0);
            sum += w * m.score;
            weights += w;
            worst = Math.min(worst, m.score);
        }
        r.score = "pior".equals(cfg.aggregate) ? (Double.isInfinite(worst) ? 0 : worst) : weights <= 0 ? 0 : sum / weights;
        return r;
    }

    /** The fight of the combo against the ragdoll behaving one way (frames on for the screen). */
    public Result run(Arena.Input champion, Arena.Input dummy, Combo combo, String behavior, Config cfg, boolean frames) {
        Arena.Input me = champion.copy();
        me.f = champion.f;
        me.x = 1000;
        me.y = 0;
        me.team = null;
        me.target = 1;
        me.autoAbilities = true;
        Arena.Input d = dummy.copy();
        d.x = me.x + cfg.distance;
        d.y = 0;
        d.team = null;
        d.target = 0;
        d.autoAbilities = true;
        d.dummy = (dummy.dummy == null ? new Dummy() : dummy.dummy).copy(behavior);
        d.kit = dummyKit(d.dummy);
        d.policy = "reage".equals(behavior) ? new ReactPolicy(d.priority) : idle();
        double duration;
        if ("repetir".equals(cfg.mode)) {
            me.combos = List.of(combo);
            me.script = null;
            me.policy = new RepeatPolicy();
            duration = Math.max(1, cfg.seconds);
        } else {
            me.combos = List.of();
            me.script = combo;
            me.policy = idle();
            duration = combo.maxTime + cfg.tail;
        }
        Arena arena = new Arena(catalog, map);
        if (!frames) {
            arena.withoutFrames();
        }
        return arena.run(List.of(me, d), List.of(), duration);
    }

    /** The numbers of a run: the champion's damage and events, the ragdoll's control, the combo's steps. */
    public Metrics metrics(Result res, Combo combo, String behavior, Config cfg) {
        Metrics m = new Metrics();
        m.behavior = behavior;
        FighterStats me = res.fighters.get(0);
        FighterStats dummy = res.fighters.get(1);
        m.damage = me.dealt;
        m.bySource.putAll(me.damageBySource);
        m.byType.putAll(me.damageByType);
        m.events.putAll(me.events);
        m.cooldownsLeft.putAll(me.cooldownsLeft);
        double start = 0;
        double end = res.duration;
        ComboReport first = res.combos.isEmpty() ? null : res.combos.get(0);
        if (first != null) {
            start = first.start;
            end = first.end != null ? first.end : res.duration;
            m.done = "done".equals(first.outcome);
            m.reason = first.reason;
            m.steps.addAll(first.steps);
            m.snapshot.putAll(first.snapshot);
        }
        if ("repetir".equals(cfg.mode)) {
            m.duration = res.duration;
            m.done = res.combos.stream().anyMatch(c -> "done".equals(c.outcome));
            m.dps = m.damage / Math.max(1e-9, res.duration);
        } else {
            m.duration = Math.max(0, end - start);
            m.dps = m.damage / Math.max(0.5, m.duration);
        }
        m.control = dummy.events.getOrDefault("tempo_controlado", 0.0);
        m.longestFree = longestFree(dummy.controlSpans, start, "repetir".equals(cfg.mode) ? res.duration : end);
        for (Map.Entry<String, Double> e : me.events.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("marca_consumida:")) {
                m.marksConsumed += e.getValue();
            } else if (k.startsWith("marca_aplicada:")) {
                m.marksApplied += e.getValue();
            } else if (k.startsWith("marca_sobrescrita:")) {
                m.marksOverwritten += e.getValue();
            } else if (k.startsWith("runa_ativou:") || k.startsWith("item_ativou:")) {
                m.procs.put(k.substring(k.indexOf(':') + 1), e.getValue());
            }
        }
        m.chained = me.events.getOrDefault("cc_encadeado", 0.0);
        m.overlapped = me.events.getOrDefault("cc_sobreposto", 0.0);
        // when every ability of the combo is up again, counted from its end
        double repeat = 0;
        for (ArenaModel.ComboStep st : combo.steps) {
            if (st.action != null && st.action.startsWith("habilidade:")) {
                String slot = st.action.substring("habilidade:".length());
                repeat = Math.max(repeat, first == null ? 0 : first.snapshot.getOrDefault("pronto:" + slot, 0.0));
            }
        }
        m.repeatIn = repeat;
        m.score = m.damage + cfg.controlWeight * m.control + cfg.markWeight * m.marksConsumed;
        return m;
    }

    /** The longest stretch within [from, to] not covered by the spans. */
    static double longestFree(List<double[]> spans, double from, double to) {
        double longest = 0;
        double cursor = from;
        for (double[] s : spans) {
            if (s[1] <= cursor) {
                continue;
            }
            if (s[0] > cursor) {
                longest = Math.max(longest, Math.min(s[0], to) - cursor);
            }
            cursor = Math.max(cursor, s[1]);
            if (cursor >= to) {
                break;
            }
        }
        if (cursor < to) {
            longest = Math.max(longest, to - cursor);
        }
        return Math.max(0, longest);
    }

    private static Policy idle() {
        return (o, m) -> Policy.Decision.of(ActionSpace.IDLE);
    }

    /** repetir: the combo whenever it can start, else attack (melee) or kite (ranged) the ragdoll. */
    static final class RepeatPolicy implements Policy {
        @Override
        public Decision decide(Observation obs, ActionMask mask) {
            if (mask.valid(ActionSpace.combo(0))) {
                return Decision.of(ActionSpace.combo(0));
            }
            int move = obs.self().attackRange >= 400 ? ActionSpace.kite(0) : ActionSpace.attack(0);
            return Decision.of(mask.valid(move) ? move : ActionSpace.IDLE);
        }
    }

    /** reage: the tactical rules, but only their Flash away and Cleanse; anything else, it stays still. */
    public static final class ReactPolicy implements Policy {
        private final TacticalPolicy rules;

        public ReactPolicy(List<String> priority) {
            this.rules = new TacticalPolicy(priority);
        }

        @Override
        public Decision decide(Observation obs, ActionMask mask) {
            int a = rules.decide(obs, mask).action;
            Observation.Self s = obs.self();
            boolean cleanse = ActionSpace.isActive(a) && "Purificar".equals(s.activeName[a - ActionSpace.ACTIVE]);
            return Decision.of(a == ActionSpace.FLASH_AWAY || cleanse ? a : ActionSpace.IDLE);
        }
    }
}
