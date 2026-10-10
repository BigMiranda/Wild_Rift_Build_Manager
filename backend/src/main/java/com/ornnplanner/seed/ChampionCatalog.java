package com.ornnplanner.seed;

import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.PassiveText;
import com.ornnplanner.engine.Model.RefScope;
import com.ornnplanner.engine.Model.StatGrowth;
import com.ornnplanner.engine.Model.StatLine;
import com.ornnplanner.engine.Model.UnitProfile;
import com.ornnplanner.engine.Stats;
import com.ornnplanner.engine.TimelineEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Champions transcribed from the game ({@code seed/campeoes_<patch>.yml}): passive, abilities and the stats measured
 * in the training mode at levels 1 and 15. Kept in memory as reference data; each champion is also seeded as a
 * planner unit whose base stats grow linearly between the two measured levels, and its modeled effects (block
 * {@code efeitos}) become engine items held from the start, like the runes.
 */
@Component
public class ChampionCatalog {

    /** Stats-block key -> unit stat. */
    private static final Map<String, String> STATUS = new LinkedHashMap<>();

    static {
        STATUS.put("Vida", Stats.MAX_HEALTH);
        STATUS.put("Mana", Stats.MAX_MANA);
        STATUS.put("RegVida", Stats.HEALTH_REGEN);
        STATUS.put("RegMana", Stats.MANA_REGEN);
        STATUS.put("Armadura", Stats.ARMOR);
        STATUS.put("RM", Stats.MAGIC_RESIST);
        STATUS.put("DdA", Stats.ATTACK_DAMAGE);
        STATUS.put("PdH", Stats.ABILITY_POWER);
        STATUS.put("AH", Stats.ABILITY_HASTE);
    }

    /** Levels the ultimate gains a rank. */
    static final int[] ULT_LEVELS = {5, 9, 13};
    static final int MAX_BASIC_RANK = 4;
    public static final List<Integer> DEFAULT_ORDER = List.of(1, 2, 3);

    /** Build option of an effect (health lost, stacks...). */
    public static class Option {
        public String name;
        public int min;
        public int max;
        public int defaultValue;
    }

    /** Stack rate of an effect that grows without limit (souls, stacks, kills): the build gives stacks per minute. */
    public static class Rate {
        public String name;
        /** First line's stat and value of one stack (rank 1), for the rate editor's estimate. */
        public String stat;
        public double perStack;
    }

    /** A modeled passive / ability as the planner shows it. */
    public static class Effect {
        public String name;
        /** P, 1, 2, 3 or R. */
        public String ability;
        /** Some lines only count when the build switches them on. */
        public boolean hasConditional;
        /** Conditional lines on unless the build says otherwise. */
        public boolean defaultOn;
        public Option option;
        public Rate rate;
        public String note;
        /** Lines in words ("+25/30/35/40% da Armadura total"). */
        public List<String> summary = new ArrayList<>();
        transient List<Map<String, Object>> lines = new ArrayList<>();
    }

    /** Engine item of an effect and whether its conditional lines count. */
    public static class EffectItem {
        public final ItemDef item;
        public final boolean conditional;

        EffectItem(ItemDef item, boolean conditional) {
            this.item = item;
            this.conditional = conditional;
        }
    }

    private final String patch;
    /** code -> champion entry as in the seed file, plus "code", "nome" and "modelados" (the effects). */
    private final Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
    private final Map<String, List<Effect>> effects = new LinkedHashMap<>();
    private final Map<String, String> statCodes = new HashMap<>();
    private final Map<String, String> statShopNames = new HashMap<>();

    @SuppressWarnings("unchecked")
    public ChampionCatalog(@Value("${planner.seed.champions:classpath:seed/campeoes_7_3.yml}") Resource file,
                           @Value("${planner.seed.prices}") Resource pricesFile) {
        Map<String, Map<String, Object>> prices = read(pricesFile);
        prices.forEach((code, v) -> {
            if (v.get("loja") != null) {
                statCodes.put(String.valueOf(v.get("loja")), code);
                statShopNames.put(code, String.valueOf(v.get("loja")));
            }
        });
        Map<String, Object> root = read(file);
        patch = String.valueOf(root.get("patch"));
        ((Map<String, Map<String, Object>>) root.get("campeoes")).forEach((name, v) -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", code(name));
            c.put("nome", name);
            c.putAll(v);
            c.remove("efeitos");
            List<Effect> list = new ArrayList<>();
            Map<String, Map<String, Object>> raw = (Map<String, Map<String, Object>>) v.get("efeitos");
            if (raw != null) {
                raw.forEach((group, e) -> list.add(effect(group, e)));
            }
            c.put("modelados", list);
            effects.put(code(name), list);
            byCode.put(code(name), c);
        });
    }

    @SuppressWarnings("unchecked")
    private Effect effect(String name, Map<String, Object> e) {
        Effect f = new Effect();
        f.name = name;
        f.ability = String.valueOf(e.get("habilidade"));
        f.defaultOn = Boolean.TRUE.equals(e.get("padrao"));
        f.note = e.get("nota") == null ? null : String.valueOf(e.get("nota"));
        boolean groupCond = Boolean.TRUE.equals(e.get("condicional"));
        for (Map<String, Object> l : (List<Map<String, Object>>) e.get("linhas")) {
            Map<String, Object> line = new LinkedHashMap<>(l);
            if (groupCond) {
                line.put("condicional", true);
            }
            f.lines.add(line);
            f.summary.add(describe(line));
        }
        f.hasConditional = f.lines.stream().anyMatch(l -> Boolean.TRUE.equals(l.get("condicional")));
        Map<String, Object> o = (Map<String, Object>) e.get("opcao");
        if (o != null) {
            f.option = new Option();
            f.option.name = String.valueOf(o.get("nome"));
            f.option.min = ((Number) o.get("min")).intValue();
            f.option.max = ((Number) o.get("max")).intValue();
            f.option.defaultValue = ((Number) o.get("padrao")).intValue();
        }
        if (e.get("por_minuto") != null) {
            f.rate = new Rate();
            f.rate.name = String.valueOf(e.get("por_minuto"));
            f.rate.stat = stat(f.lines.get(0).get("tipo"));
            f.rate.perStack = first(f.lines.get(0).get("valor"));
        }
        return f;
    }

    /** Unit code of a champion name: "Dr. Mundo" -> DR_MUNDO, "Cho'Gath" -> CHOGATH. */
    public static String code(String name) {
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.replace("'", "").replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "").toUpperCase();
    }

    public String patch() {
        return patch;
    }

    public Optional<Map<String, Object>> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    public List<Effect> effects(String code) {
        return effects.getOrDefault(code, List.of());
    }

    /** Every champion with a measured stats block as a unit: value(L) = nv1 + (nv15 - nv1) / 14 * (L - 1). */
    @SuppressWarnings("unchecked")
    public Map<String, UnitProfile> units() {
        Map<String, UnitProfile> units = new LinkedHashMap<>();
        byCode.forEach((code, c) -> {
            Map<String, Object> status = (Map<String, Object>) c.get("status");
            if (status == null) {
                return;
            }
            Map<String, Object> l1 = (Map<String, Object>) status.get("nv1");
            Map<String, Object> l15 = (Map<String, Object>) status.get("nv15");
            UnitProfile u = new UnitProfile();
            u.code = code;
            u.name = (String) c.get("nome");
            u.livingForge = false;
            STATUS.forEach((key, stat) -> {
                if (l1.get(key) != null && l15.get(key) != null) {
                    double a = num(l1.get(key));
                    double b = num(l15.get(key));
                    u.stats.put(stat, new StatGrowth(a, (b - a) / 14));
                }
            });
            if (status.get("VdM") != null) {
                u.stats.put(Stats.MOVE_SPEED, new StatGrowth(num(status.get("VdM")), 0));
            }
            units.put(code, u);
        });
        return units;
    }

    /**
     * Ranks of abilities 1, 2, 3 and R at a champion level: levels 1-3 learn the three basic abilities in priority
     * order, the ultimate ranks up at 5, 9 and 13, and every other point goes to the highest-priority basic ability
     * that is not maxed yet.
     */
    public static int[] ranks(List<Integer> order, int level) {
        List<Integer> prio = validOrder(order);
        int[] r = new int[4];
        int learned = 0;
        for (int l = 1; l <= Math.min(level, TimelineEngine.MAX_LEVEL); l++) {
            final int lv = l;
            if (java.util.Arrays.stream(ULT_LEVELS).anyMatch(u -> u == lv)) {
                r[3]++;
            } else if (learned < 3) {
                r[prio.get(learned++) - 1]++;
            } else {
                for (int a : prio) {
                    if (r[a - 1] < MAX_BASIC_RANK) {
                        r[a - 1]++;
                        break;
                    }
                }
            }
        }
        return r;
    }

    /** The given priority if it is a permutation of 1, 2, 3; else 1 > 2 > 3. */
    public static List<Integer> validOrder(List<Integer> order) {
        if (order != null && order.size() == 3 && order.containsAll(DEFAULT_ORDER)) {
            return order;
        }
        return DEFAULT_ORDER;
    }

    /**
     * The champion's effects as engine items for a build: values by level from the skill order, the option applied,
     * stack rates in periods ({start minute, stacks per minute}) and the conditional choice (default per effect).
     */
    public List<EffectItem> toItems(String code, List<Integer> order, Map<String, Integer> options,
                                    Map<String, Boolean> conditional, Map<String, List<double[]>> rates, long firstId) {
        List<EffectItem> out = new ArrayList<>();
        long id = firstId;
        for (Effect f : effects(code)) {
            int opt = f.option == null ? 0 : f.option.defaultValue;
            if (f.option != null && options != null && options.get(f.name) != null) {
                opt = Math.max(f.option.min, Math.min(f.option.max, options.get(f.name)));
            }
            List<double[]> periods = new ArrayList<>();
            if (f.rate != null) {
                if (rates == null || rates.get(f.name) == null) {
                    continue;
                }
                for (double[] p : rates.get(f.name)) {
                    if (p != null && p.length >= 2 && p[0] >= 0 && p[1] >= 0) {
                        periods.add(new double[] {p[0], p[1]});
                    }
                }
                periods.sort(java.util.Comparator.comparingDouble(p -> p[0]));
                if (periods.stream().noneMatch(p -> p[1] > 0)) {
                    continue;
                }
            }
            ItemDef item = new ItemDef();
            item.id = id--;
            item.name = f.name;
            item.section = TimelineEngine.CHAMPION_SECTION;
            item.category = "Campeão";
            item.passives.add(new PassiveText(f.name, String.join("; ", f.summary)));
            for (Map<String, Object> l : f.lines) {
                item.stats.add(line(f, l, order, opt, periods));
            }
            boolean on = conditional != null && conditional.get(f.name) != null ? conditional.get(f.name) : f.defaultOn;
            out.add(new EffectItem(item, on));
        }
        return out;
    }

    private StatLine line(Effect f, Map<String, Object> l, List<Integer> order, int opt, List<double[]> periods) {
        StatLine s = new StatLine();
        s.type = stat(l.get("tipo"));
        s.passive = f.name;
        s.conditional = Boolean.TRUE.equals(l.get("condicional"));
        s.refPre = Boolean.TRUE.equals(l.get("ref_pre"));
        boolean ratio = l.get("ratio") != null;
        List<Double> values = new ArrayList<>();
        for (int level = 1; level <= TimelineEngine.MAX_LEVEL; level++) {
            int rank = rank(f.ability, order, level);
            double v = 0;
            if (rank > 0) {
                if (ratio) {
                    v = at(l.get("ratio"), rank, level, null) + at(l.get("ratio_por_opcao"), rank, level, null) * opt;
                } else {
                    v = at(l.get("valor"), rank, level, l.get("valor_nv15"))
                            + at(l.get("valor_por_opcao"), rank, level, null) * opt;
                }
            }
            values.add(v);
        }
        if (ratio) {
            s.levelRatios = values;
            s.ratio = values.get(values.size() - 1);
            s.refType = stat(l.get("ref"));
            Object scope = l.get("escopo");
            s.refScope = "bonus".equals(scope) ? RefScope.BONUS : "base".equals(scope) ? RefScope.BASE : RefScope.TOTAL;
        } else {
            s.levelValues = values;
            s.value = 0.0;
            if (f.rate != null) {
                s.perMinute = periods;
            }
        }
        return s;
    }

    /** Rank of the effect's ability at a level (a passive counts as rank 1 from level 1). */
    private static int rank(String ability, List<Integer> order, int level) {
        switch (ability) {
            case "P":
                return 1;
            case "R":
                return ranks(order, level)[3];
            default:
                return ranks(order, level)[Integer.parseInt(ability) - 1];
        }
    }

    /** A value at a rank (lists are per rank) or, with a level-15 value, interpolated by the champion level. */
    @SuppressWarnings("unchecked")
    private static double at(Object v, int rank, int level, Object atMax) {
        if (v == null) {
            return 0;
        }
        if (v instanceof List) {
            List<Object> list = (List<Object>) v;
            return num(list.get(Math.max(1, Math.min(list.size(), rank)) - 1));
        }
        double a = num(v);
        return atMax == null ? a : a + (num(atMax) - a) * (level - 1) / 14.0;
    }

    @SuppressWarnings("unchecked")
    private static double first(Object v) {
        return v instanceof List ? num(((List<Object>) v).get(0)) : v == null ? 0 : num(v);
    }

    private String stat(Object shopName) {
        String s = String.valueOf(shopName);
        return statCodes.getOrDefault(s, s);
    }

    /** "+25/30/35/40% da Armadura total", "+18/20/22/24 Armadura", "Vida Máxima: −75% do adicional"... */
    @SuppressWarnings("unchecked")
    private String describe(Map<String, Object> l) {
        String tipo = String.valueOf(l.get("tipo"));
        StringBuilder b = new StringBuilder();
        if (l.get("ratio") != null) {
            String scope = "bonus".equals(l.get("escopo")) ? " adicional" : "base".equals(l.get("escopo")) ? " base" : "";
            String ref = String.valueOf(l.get("ref"));
            b.append(tipo.equals(ref) ? "" : tipo + ": ").append(signed(l.get("ratio"), 100)).append("% de ").append(ref)
                    .append(scope.isEmpty() ? " total" : scope);
            if (Boolean.TRUE.equals(l.get("ref_pre"))) {
                b.append(" (só o adicional de itens e runas)");
            }
        } else {
            boolean pct = tipo.startsWith("% ");
            String name = pct ? tipo.substring(2) : tipo;
            String unit = pct ? "%" : "";
            if (l.get("valor_por_opcao") != null) {
                b.append(signed(l.get("valor_por_opcao"), 1)).append(unit).append(" ").append(name).append(" por ponto da opção");
            } else if (l.get("valor_nv15") != null) {
                b.append(signed(l.get("valor"), 1)).append("–").append(fmt(num(l.get("valor_nv15")))).append(unit).append(" ")
                        .append(name).append(" (nível)");
            } else {
                b.append(signed(l.get("valor"), 1)).append(unit).append(" ").append(name);
            }
        }
        if (Boolean.TRUE.equals(l.get("condicional"))) {
            b.append(" (condicional)");
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static String signed(Object v, double scale) {
        List<Object> list = v instanceof List ? (List<Object>) v : List.of(v);
        StringBuilder b = new StringBuilder(num(list.get(0)) < 0 ? "−" : "+");
        for (int i = 0; i < list.size(); i++) {
            b.append(i > 0 ? "/" : "").append(fmt(Math.abs(num(list.get(i)) * scale)));
        }
        return b.toString();
    }

    private static String fmt(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-6 ? String.valueOf((long) Math.rint(v))
                : String.format(java.util.Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replace('.', ',');
    }

    private static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : Double.parseDouble(String.valueOf(o));
    }

    private static <T> T read(Resource r) {
        try (InputStream in = r.getInputStream()) {
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + r, e);
        }
    }
}
