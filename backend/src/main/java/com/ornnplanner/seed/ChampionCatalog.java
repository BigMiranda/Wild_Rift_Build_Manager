package com.ornnplanner.seed;

import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.PassiveText;
import com.ornnplanner.engine.Model.StatGrowth;
import com.ornnplanner.engine.Model.UnitProfile;
import com.ornnplanner.engine.Stats;
import com.ornnplanner.engine.TimelineEngine;
import com.ornnplanner.seed.EffectSpec.Effect;
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
        STATUS.put("VampFisico", "% Lifesteal");          // Nasus
        STATUS.put("Crit", "% Critical Rate");            // Tryndamere, Yasuo...
        STATUS.put("PenMag%", "% Magic Penetration");
    }

    /** Levels the ultimate gains a rank. */
    static final int[] ULT_LEVELS = {5, 9, 13};
    static final int MAX_BASIC_RANK = 4;
    public static final List<Integer> DEFAULT_ORDER = List.of(1, 2, 3);

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
    private final EffectSpec spec;
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
        spec = new EffectSpec(statCodes);
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
                raw.forEach((group, e) -> list.add(spec.parse(group, e)));
            }
            c.put("modelados", list);
            effects.put(code(name), list);
            byCode.put(code(name), c);
        });
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

    /** Every champion, for the picker: code, name, title, resource and the names of the passive and abilities. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> summaries() {
        List<Map<String, Object>> out = new ArrayList<>();
        byCode.forEach((code, c) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("nome", c.get("nome"));
            m.put("titulo", c.get("titulo"));
            Map<String, Object> status = (Map<String, Object>) c.get("status");
            m.put("recurso", status == null ? null : status.get("recurso"));
            List<String> abilities = new ArrayList<>();
            Map<String, Object> p = (Map<String, Object>) c.get("passiva");
            if (p != null) {
                abilities.add(String.valueOf(p.get("nome")));
            }
            for (Map<String, Object> a : (List<Map<String, Object>>) c.getOrDefault("habilidades", List.of())) {
                abilities.add(String.valueOf(a.get("nome")));
            }
            m.put("habilidades", abilities);
            m.put("efeitos", effects(code).size());
            out.add(m);
        });
        return out;
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
        return ranks(order, null, level);
    }

    /**
     * Same, from the ability chosen at each level when {@code levels} is a valid plan (see {@link #validLevels}); else
     * from the priority order.
     */
    public static int[] ranks(List<Integer> order, List<String> levels, int level) {
        List<String> plan = validLevels(levels) ? levels : planFromOrder(order);
        int[] r = new int[4];
        for (int l = 0; l < Math.min(level, plan.size()); l++) {
            String a = plan.get(l);
            r["R".equals(a) ? 3 : Integer.parseInt(a) - 1]++;
        }
        return r;
    }

    /** Ability upgraded at each level 1..15 by the priority rule. */
    public static List<String> planFromOrder(List<Integer> order) {
        List<Integer> prio = validOrder(order);
        int[] r = new int[3];
        List<String> plan = new ArrayList<>();
        int learned = 0;
        for (int l = 1; l <= TimelineEngine.MAX_LEVEL; l++) {
            final int lv = l;
            if (java.util.Arrays.stream(ULT_LEVELS).anyMatch(u -> u == lv)) {
                plan.add("R");
                continue;
            }
            int a = learned < 3 ? prio.get(learned++) : prio.stream().filter(x -> r[x - 1] < MAX_BASIC_RANK).findFirst().orElse(prio.get(0));
            r[a - 1]++;
            plan.add(String.valueOf(a));
        }
        return plan;
    }

    /**
     * A plan of 15 points the game allows: a basic ability reaches rank k from level 2k - 1 (max 4), the ultimate rank
     * k from level 5, 9, 13 (max 3).
     */
    public static boolean validLevels(List<String> levels) {
        if (levels == null || levels.size() != TimelineEngine.MAX_LEVEL) {
            return false;
        }
        int[] r = new int[4];
        for (int l = 1; l <= levels.size(); l++) {
            String a = levels.get(l - 1);
            int i = "R".equals(a) ? 3 : "1".equals(a) ? 0 : "2".equals(a) ? 1 : "3".equals(a) ? 2 : -1;
            if (i < 0) {
                return false;
            }
            int rank = ++r[i];
            if (i == 3 ? rank > ULT_LEVELS.length || l < ULT_LEVELS[rank - 1] : rank > MAX_BASIC_RANK || l < 2 * rank - 1) {
                return false;
            }
        }
        return true;
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
    public List<EffectItem> toItems(String code, List<Integer> order, List<String> levels, Map<String, Integer> options,
                                    Map<String, Boolean> conditional, Map<String, List<double[]>> rates, long firstId) {
        List<EffectItem> out = new ArrayList<>();
        long id = firstId;
        for (Effect f : effects(code)) {
            int opt = EffectSpec.option(f, options, f.name);
            List<double[]> periods = EffectSpec.periods(f, rates, f.name);
            if (periods == null) {
                continue;
            }
            ItemDef item = new ItemDef();
            item.id = id--;
            item.name = f.name;
            item.section = TimelineEngine.CHAMPION_SECTION;
            item.category = "Campeão";
            item.passives.add(new PassiveText(f.name, String.join("; ", f.summary)));
            item.stats.addAll(spec.lines(f, level -> rank(f.ability, order, levels, level), opt, periods, f.name));
            boolean on = conditional != null && conditional.get(f.name) != null ? conditional.get(f.name) : f.defaultOn;
            out.add(new EffectItem(item, on));
        }
        return out;
    }

    /** Rank of the effect's ability at a level (a passive counts as rank 1 from level 1). */
    private static int rank(String ability, List<Integer> order, List<String> levels, int level) {
        switch (ability) {
            case "P":
                return 1;
            case "R":
                return ranks(order, levels, level)[3];
            default:
                return ranks(order, levels, level)[Integer.parseInt(ability) - 1];
        }
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
