package com.ornnplanner.arena.fight;

import com.ornnplanner.engine.Model.ItemContribution;
import com.ornnplanner.engine.Model.MinutePoint;
import com.ornnplanner.engine.Model.PassivePart;
import com.ornnplanner.engine.Stats;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The archetype of a build at a minute: what its items' gold went to, by families of stats, plus the kinds of fight
 * effects it holds. Every threshold lives in {@code classificacao_<patch>.yml}; no rule names an item.
 */
@Service
public class BuildArchetypes {

    /** An archetype: its key and name, the family of the tank's stats when it has one, and the shares it was read from. */
    public static class Archetype {
        public String key;
        public String name;
        public String detail;
        public Map<String, Double> shares = new LinkedHashMap<>();
        public Map<String, Integer> effects = new LinkedHashMap<>();
    }

    private final FighterBuilder combat;
    private final PlannerService planner;
    private final CombatCatalog catalog;
    private final Map<String, List<String>> families = new LinkedHashMap<>();
    private final Map<String, List<String>> effectGroups = new LinkedHashMap<>();
    private final List<Map<String, Object>> rules;

    @SuppressWarnings("unchecked")
    public BuildArchetypes(FighterBuilder combat, PlannerService planner, CombatCatalog catalog,
                           @Value("${planner.seed.classification:classpath:seed/classificacao_7_3d.yml}") Resource file) {
        this.combat = combat;
        this.planner = planner;
        this.catalog = catalog;
        try (InputStream in = file.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            ((Map<String, List<String>>) root.get("familias")).forEach((k, v) -> families.put(k, new ArrayList<>(v)));
            ((Map<String, List<String>>) root.get("efeitos")).forEach((k, v) -> effectGroups.put(k, new ArrayList<>(v)));
            rules = (List<Map<String, Object>>) root.get("arquetipos");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
    }

    /** The build at a minute (null = its end of match, else its last purchase). */
    public Archetype of(BuildRepository.Build build, Double minute) {
        FighterBuilder.Combatant c = new FighterBuilder.Combatant();
        c.build = build;
        c.minute = minute;
        MinutePoint p = combat.plannerPoint(c);
        Map<String, Double> gold = new LinkedHashMap<>();
        Map<String, Double> byStat = new LinkedHashMap<>();
        Map<String, Double> prices = planner.referenceData().statPrices;
        Set<String> seen = new LinkedHashSet<>();
        Map<String, Integer> effects = new LinkedHashMap<>();
        effectGroups.keySet().forEach(g -> effects.put(g, 0));
        for (ItemContribution ic : p.contributions) {
            if (ic.rune) {
                continue;
            }
            ic.flat.forEach((stat, v) -> add(stat, v, prices, gold, byStat));
            for (PassivePart part : ic.passiveParts) {
                add(part.stat, part.amount, prices, gold, byStat);
            }
            if (seen.add(ic.itemName)) {
                Set<String> kinds = new LinkedHashSet<>();
                catalog.effects(ic.itemName).forEach(e -> kinds.add(String.valueOf(e.get("tipo"))));
                effectGroups.forEach((group, types) -> {
                    if (types.stream().anyMatch(kinds::contains)) {
                        effects.merge(group, 1, Integer::sum);
                    }
                });
            }
        }
        double all = gold.values().stream().mapToDouble(Double::doubleValue).sum();
        Archetype out = new Archetype();
        families.keySet().forEach(f -> out.shares.put(f, all > 0 ? gold.getOrDefault(f, 0.0) / all : 0));
        out.effects = effects;
        for (Map<String, Object> r : rules) {
            if (passes(r, out)) {
                out.key = String.valueOf(r.get("chave"));
                out.name = String.valueOf(r.get("nome"));
                if (r.get("detalhe") != null) {
                    out.detail = detail(byStat, prices);
                }
                break;
            }
        }
        return out;
    }

    private void add(String stat, double value, Map<String, Double> prices, Map<String, Double> gold, Map<String, Double> byStat) {
        double price = prices.getOrDefault(stat, 0.0);
        if (value <= 0 || price <= 0) {
            return;
        }
        byStat.merge(stat, value * price, Double::sum);
        families.forEach((family, stats) -> {
            if (stats.contains(stat)) {
                gold.merge(family, value * price, Double::sum);
            }
        });
    }

    /** The archetypes in rule order: key -> name (the first rule that passes is a build's). */
    public Map<String, String> names() {
        Map<String, String> out = new LinkedHashMap<>();
        rules.forEach(r -> out.put(String.valueOf(r.get("chave")), String.valueOf(r.get("nome"))));
        return out;
    }

    /**
     * The archetype of a set of shop items alone (no build, no level): their flat stats at level 1 (the middle of a
     * range) by gold, and their fight effects. The Atlas's guide reads its assembled builds with it.
     */
    public Archetype ofItems(List<com.ornnplanner.engine.Model.ItemDef> items) {
        Map<String, Double> gold = new LinkedHashMap<>();
        Map<String, Double> byStat = new LinkedHashMap<>();
        Map<String, Double> prices = planner.referenceData().statPrices;
        Map<String, Integer> effects = new LinkedHashMap<>();
        effectGroups.keySet().forEach(g -> effects.put(g, 0));
        Set<String> seen = new LinkedHashSet<>();
        for (com.ornnplanner.engine.Model.ItemDef it : items) {
            if (it == null || !seen.add(it.name)) {
                continue;
            }
            for (com.ornnplanner.engine.Model.StatLine l : it.stats) {
                if (l.ratio == null && !l.conditional && l.value != null) {
                    add(l.type, l.valueMax == null ? l.value : (l.value + l.valueMax) / 2, prices, gold, byStat);
                }
            }
            Set<String> kinds = new LinkedHashSet<>();
            catalog.effects(it.name).forEach(e -> kinds.add(String.valueOf(e.get("tipo"))));
            effectGroups.forEach((group, types) -> {
                if (types.stream().anyMatch(kinds::contains)) {
                    effects.merge(group, 1, Integer::sum);
                }
            });
        }
        double all = gold.values().stream().mapToDouble(Double::doubleValue).sum();
        Archetype out = new Archetype();
        families.keySet().forEach(f -> out.shares.put(f, all > 0 ? gold.getOrDefault(f, 0.0) / all : 0));
        out.effects = effects;
        for (Map<String, Object> r : rules) {
            if (passes(r, out)) {
                out.key = String.valueOf(r.get("chave"));
                out.name = String.valueOf(r.get("nome"));
                break;
            }
        }
        return out;
    }

    /**
     * How far a build is from an archetype (0 = it is that archetype): what each of the target rule's conditions still
     * misses, as a part of its threshold, plus how deep the build is inside each rule ahead of it (a rule that passes
     * first wins, so a tank's build is no fighter). An unknown key is 0.
     */
    @SuppressWarnings("unchecked")
    public double distance(Archetype a, String target) {
        int t = -1;
        for (int i = 0; i < rules.size(); i++) {
            if (String.valueOf(rules.get(i).get("chave")).equals(target)) {
                t = i;
                break;
            }
        }
        if (t < 0 || a == null) {
            return 0;
        }
        double d = 0;
        for (Object c : (List<Object>) rules.get(t).getOrDefault("se", List.of())) {
            d += miss(String.valueOf(c), a);
        }
        for (int i = 0; i < t; i++) {
            if (passes(rules.get(i), a)) {
                double inside = Double.MAX_VALUE;
                for (Object c : (List<Object>) rules.get(i).getOrDefault("se", List.of())) {
                    inside = Math.min(inside, inside(String.valueOf(c), a));
                }
                d += inside == Double.MAX_VALUE ? 1 : 0.05 + inside;
            }
        }
        return d;
    }

    private static double left(String key, Archetype a) {
        return key.startsWith("efeito.") ? a.effects.getOrDefault(key.substring(7), 0) : a.shares.getOrDefault(key, 0.0);
    }

    /** What a condition misses, as a part of its threshold (0 when it passes). */
    private static double miss(String cond, Archetype a) {
        String[] x = cond.trim().split("\\s+");
        double l = left(x[0], a);
        double r = Double.parseDouble(x[2]);
        double scale = Math.max(Math.abs(r), 0.05);
        if (">=".equals(x[1]) || ">".equals(x[1])) {
            return Math.max(0, r - l) / scale;
        }
        return Math.max(0, l - r) / scale;
    }

    /** How far a passing condition is past its threshold, as a part of it. */
    private static double inside(String cond, Archetype a) {
        String[] x = cond.trim().split("\\s+");
        double l = left(x[0], a);
        double r = Double.parseDouble(x[2]);
        double scale = Math.max(Math.abs(r), 0.05);
        return (">=".equals(x[1]) || ">".equals(x[1]) ? l - r : r - l) / scale;
    }

    /** What a tank's gold went to most: armor, magic resist or health. */
    private static String detail(Map<String, Double> byStat, Map<String, Double> prices) {
        double armor = byStat.getOrDefault(Stats.ARMOR, 0.0);
        double mr = byStat.getOrDefault(Stats.MAGIC_RESIST, 0.0);
        double hp = byStat.getOrDefault(Stats.MAX_HEALTH, 0.0);
        return hp >= armor && hp >= mr ? "Vida" : armor >= mr ? "Armadura" : "RM";
    }

    @SuppressWarnings("unchecked")
    private static boolean passes(Map<String, Object> rule, Archetype a) {
        for (Object c : (List<Object>) rule.getOrDefault("se", List.of())) {
            String[] x = String.valueOf(c).trim().split("\\s+");
            double left = x[0].startsWith("efeito.") ? a.effects.getOrDefault(x[0].substring(7), 0) : a.shares.getOrDefault(x[0], 0.0);
            double right = Double.parseDouble(x[2]);
            boolean ok = ">=".equals(x[1]) ? left >= right : ">".equals(x[1]) ? left > right : "<".equals(x[1]) ? left < right : left <= right;
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
