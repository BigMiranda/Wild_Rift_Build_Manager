package com.ornnplanner.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Rodada 21, part 6: what the objectives give a champion, by the rules of {@code objetivos_<patch>.yml} (the "objetivos"
 * section), the same the fights use ({@code RiftRules.dragonDelta} delegates here): the drakes' stats, the Mountain
 * soul's shield and the Ocean soul's heal as sheet values (like an item's), the Hand of Baron's damage by the minute,
 * and the fight effects with no stat (the Infernal soul's blasts, the Ocean drake's regeneration, the Elder's burn) as
 * their value in {@link #TRADE} seconds of trade. {@link #apply} adds them to a minute of the planner as one source,
 * "Objetivos"; the planner's frontend mirrors {@link #parts} in {@code objectives.js} (the parity test reads the same
 * fixture, {@code frontend/test/objetivos-paridade.json}).
 */
public final class ObjectiveRules {

    /** The contribution's id and name in a minute of the planner (held like a rune: from the start, no cost). */
    public static final long ID = -900;
    public static final String NAME = "Objetivos";
    /** Seconds of the trade the fight effects are valued in. */
    public static final double TRADE = 10;
    /** Share of the health lost the Ocean drake's regeneration is read at. */
    public static final double LOST = 0.5;
    public static final List<String> ELEMENTS = List.of("oceano", "montanha", "infernal");

    private ObjectiveRules() {
    }

    /**
     * What {@code stacks} drakes of an element add to the stats: the fixed lines first, then the % of the total, which
     * reads the total after every other source and the fixed part of the drakes ({@code total} gives the stat before the
     * drakes). Each drake adds its lines again. Stat -> amount, in the order of the lines.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Double> dragonDelta(Map<String, Object> element, int stacks, ToDoubleFunction<String> total) {
        Map<String, Double> out = new LinkedHashMap<>();
        Object status = element == null ? null : element.get("status");
        if (!(status instanceof List) || stacks <= 0) {
            return out;
        }
        for (Map<String, Object> l : (List<Map<String, Object>>) status) {
            if (l.get("ratio") == null) {
                out.merge(String.valueOf(l.get("stat")), num(l.get("valor")) * stacks, Double::sum);
            }
        }
        Map<String, Double> fixed = new LinkedHashMap<>(out);
        for (Map<String, Object> l : (List<Map<String, Object>>) status) {
            if (l.get("ratio") != null) {
                String ref = String.valueOf(l.getOrDefault("ref", l.get("stat")));
                double before = total.applyAsDouble(ref) + fixed.getOrDefault(ref, 0.0);
                out.merge(String.valueOf(l.get("stat")), num(l.get("ratio")) * stacks * before, Double::sum);
            }
        }
        return out;
    }

    /** The objectives a reading of the planner holds (the selector's): drakes by element, the soul, the Elder, Baron. */
    public static class Objectives {
        public Map<String, Integer> dragons = new LinkedHashMap<>();
        public String soul;
        public boolean elder;
        public boolean baron;

        public boolean any() {
            return dragons.values().stream().anyMatch(n -> n != null && n > 0) || soul != null || elder || baron;
        }
    }

    /** A fight effect in {@link #TRADE} seconds of trade: what it does, how much, and how it was read. */
    public static class Fight {
        /** dano | dano_verdadeiro | cura */
        public String kind;
        public double value;
        public double seconds = TRADE;
        /** The value of one activation (one blast, one heal, one burn). */
        public double each;
        public double times;
        public String detail;
    }

    /** One source of the objectives: a drake element, a soul, the Elder or Baron, with its stats and its fight effect. */
    public static class Part {
        /** oceano | montanha | infernal | soul:&lt;element&gt; | elder | baron */
        public String source;
        public Integer stacks;
        public Map<String, Double> delta = new LinkedHashMap<>();
        /** null = only stats. */
        public Fight fight;
        /** Estimated numbers (the Hand of Baron's growth: EST in the seed). */
        public boolean estimate;
    }

    /** In Wild Rift one drake of each element at most: any stack above 1 is 1. */
    static int taken(Integer stacks) {
        return stacks == null ? 0 : Math.min(1, Math.max(0, stacks));
    }

    /** The soul counts only with the three drakes taken. */
    static String soulOf(Objectives o) {
        if (o.soul == null) {
            return null;
        }
        for (String e : ELEMENTS) {
            if (taken(o.dragons.get(e)) <= 0) {
                return null;
            }
        }
        return o.soul;
    }

    /**
     * Each source of the objectives at a minute: {@code total} and {@code base} are the stats before them (the bonus
     * stats a soul reads are total minus base). {@code rules} is the "objetivos" section of the seed.
     */
    @SuppressWarnings("unchecked")
    public static List<Part> parts(Objectives o, Map<String, Object> rules, double minute, Map<String, Double> total, Map<String, Double> base) {
        List<Part> out = new ArrayList<>();
        if (o == null || rules == null || !o.any()) {
            return out;
        }
        Map<String, Object> drake = map(rules.get("dragao"));
        Map<String, Object> elements = map(drake.get("elementos"));
        ToDoubleFunction<String> t = k -> total.getOrDefault(k, 0.0);
        ToDoubleFunction<String> bonus = k -> Math.max(0, total.getOrDefault(k, 0.0) - base.getOrDefault(k, 0.0));
        for (String e : ELEMENTS) {
            int n = taken(o.dragons.get(e));
            if (n <= 0) {
                continue;
            }
            Map<String, Object> el = map(elements.get(e));
            Part p = new Part();
            p.source = e;
            p.stacks = n;
            p.delta = dragonDelta(el, n, t);
            if (el.get("regeneracao_vida_perdida") != null) {
                double every = num(el.getOrDefault("a_cada", 5));
                Fight f = new Fight();
                f.kind = "cura";
                f.each = num(el.get("regeneracao_vida_perdida")) * LOST * t.applyAsDouble("Max Health") * n;
                f.times = Math.floor(TRADE / every + 1e-9);
                f.value = f.each * f.times;
                f.detail = "regeneracao_vida_perdida x " + LOST + " da Vida máxima a cada " + every + " s";
                p.fight = f;
            }
            out.add(p);
        }
        String soul = soulOf(o);
        if (soul != null) {
            Map<String, Object> s = map(map(drake.get("almas")).get(soul));
            Part p = new Part();
            p.source = "soul:" + soul;
            if ("montanha".equals(soul) && s.get("escudo") != null) {
                p.delta.put("Shield", num(s.get("escudo")));
            }
            if ("oceano".equals(soul) && s.get("cura") != null) {
                Map<String, Object> c = map(s.get("cura"));
                double heal = num(c.get("base")) + num(c.get("dda_bonus")) * bonus.applyAsDouble("Attack Damage")
                        + num(c.get("pdh")) * t.applyAsDouble("Ability Power") + num(c.get("vida_bonus")) * bonus.applyAsDouble("Max Health");
                p.delta.put("Heal", heal);
                Fight f = new Fight();
                f.kind = "cura";
                f.each = heal;
                f.times = 1;
                f.value = heal;
                f.seconds = num(s.getOrDefault("duracao", 4));
                f.detail = "cura em " + f.seconds + " s";
                p.fight = f;
            }
            if ("infernal".equals(soul) && s.get("dano") != null) {
                Map<String, Object> d = map(s.get("dano"));
                double each = num(d.get("base")) + num(d.get("dda_bonus")) * bonus.applyAsDouble("Attack Damage")
                        + num(d.get("pdh")) * t.applyAsDouble("Ability Power");
                double every = num(s.getOrDefault("recarga", 5));
                Fight f = new Fight();
                f.kind = "dano";
                f.each = each;
                f.times = Math.floor(TRADE / every + 1e-9);
                f.value = each * f.times;
                f.detail = "dano adaptativo em área a cada " + every + " s";
                p.fight = f;
            }
            out.add(p);
        }
        if (o.elder) {
            Map<String, Object> b = map(map(rules.get("anciao")).get("buff"));
            Part p = new Part();
            p.source = "elder";
            Fight f = new Fight();
            f.kind = "dano_verdadeiro";
            double in = num(b.getOrDefault("em", 3));
            f.each = num(b.get("verdadeiro"));
            f.times = TRADE / in;
            f.value = f.each * f.times;
            f.detail = "queima de " + f.each + " em " + in + " s, reaplicada; executa abaixo de "
                    + Math.round(100 * num(b.get("executa_abaixo"))) + "%";
            p.fight = f;
            out.add(p);
        }
        if (o.baron) {
            Map<String, Object> g = map(map(rules.get("mao_do_barao")).get("dda_pdh_por_min"));
            Part p = new Part();
            p.source = "baron";
            p.estimate = true;
            p.delta.put("Attack Damage", num(g.get("base_dda")) + num(g.get("dda")) * minute);
            p.delta.put("Ability Power", num(g.get("base_pdh")) + num(g.get("pdh")) * minute);
            out.add(p);
        }
        return out;
    }

    /** Every part's stats summed. */
    public static Map<String, Double> delta(List<Part> parts) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Part p : parts) {
            p.delta.forEach((k, v) -> out.merge(k, v, Double::sum));
        }
        return out;
    }

    /**
     * A minute of the planner with the objectives on top: their stats in the totals, one contribution "Objetivos" (held
     * like a rune, its gold by the stat prices) and the parts with their fight effects.
     */
    public static void apply(Model.MinutePoint p, Objectives o, Map<String, Object> rules, Map<String, Double> prices) {
        List<Part> parts = parts(o, rules, p.minute, p.total, p.base);
        if (parts.isEmpty()) {
            return;
        }
        Map<String, Double> d = delta(parts);
        Model.ItemContribution c = new Model.ItemContribution();
        c.purchaseIndex = -1;
        c.buildIndex = -1;
        c.rune = true;
        c.objective = true;
        c.itemId = ID;
        c.itemName = NAME;
        d.forEach((k, v) -> {
            p.total.merge(k, v, Double::sum);
            c.flat.put(k, v);
            c.flatGold += v * prices.getOrDefault(k, 0.0);
        });
        p.contributions.add(c);
        p.objectiveDelta = d;
        p.objectiveParts = parts;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }
}
