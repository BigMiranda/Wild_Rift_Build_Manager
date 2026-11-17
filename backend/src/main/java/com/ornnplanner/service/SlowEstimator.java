package com.ornnplanner.service;

import com.ornnplanner.engine.Model.ItemContribution;
import com.ornnplanner.engine.Model.MinutePoint;
import com.ornnplanner.engine.Model.SlowEstimate;
import com.ornnplanner.engine.Model.SlowSource;
import com.ornnplanner.engine.Stats;
import com.ornnplanner.repo.BuildRepository.Build;
import com.ornnplanner.repo.BuildRepository.ChampionSetup;
import com.ornnplanner.seed.ChampionCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
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
 * The build's average slow on the enemies at each series point, an estimate for the chart (the rules and the sources
 * are in {@code lentidao_<patch>.yml}): per source, slow x min(duration, interval) / interval x expected targets. The
 * abilities come from the Arena's sheets ({@code kits_<patch>.yml}), with the cooldowns cut by the point's haste.
 */
@Component
public class SlowEstimator {

    private static final String[] SLOTS = {"1", "2", "3", "R"};
    /** Effects that hold the target in place (for Glacial Augment). */
    private static final Set<String> IMMOBILIZE = Set.of("atordoa", "arremessa", "enraiza", "puxa", "suprime", "provoca",
            "encanta", "amedronta", "adormece", "polimorfa", "afasta");

    private final Map<String, Object> kits = new LinkedHashMap<>();
    /** The places with effects of each ability sheet (the sheets are read once and never change). */
    private final Map<Map<String, Object>, List<Map<String, Object>>> places =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    private final Map<String, Object> items;
    private final Map<String, Object> runes;
    private final Map<String, Boolean> immobilizing = new java.util.concurrent.ConcurrentHashMap<>();
    private final double refRadius;
    private final double maxTargets;

    @SuppressWarnings("unchecked")
    public SlowEstimator(@Value("${planner.seed.kits:classpath:seed/kits_7_3f.yml}") Resource kitsFile,
                         @Value("${planner.seed.slows:classpath:seed/lentidao_7_3f.yml}") Resource slowFile) {
        Map<String, Object> rules;
        try (InputStream k = kitsFile.getInputStream(); InputStream s = slowFile.getInputStream()) {
            kits.putAll((Map<String, Object>) new Yaml().<Map<String, Object>>load(k).get("campeoes"));
            rules = new Yaml().load(s);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + slowFile, e);
        }
        items = map(rules.get("itens"));
        runes = map(rules.get("runas"));
        refRadius = num(rules.get("raio_referencia"), 250);
        maxTargets = num(rules.get("alvos_max"), 3);
    }

    /** Fills {@code slow} on every point of the series. */
    public void apply(Build build, String championName, boolean ranged, List<MinutePoint> series) {
        Map<String, Object> abilities = abilities(championName);
        ChampionSetup setup = build.championSetup != null ? build.championSetup : new ChampionSetup();
        Set<String> runeNames = new LinkedHashSet<>();
        if (build.runePage != null) {
            add(runeNames, build.runePage.keystone);
            build.runePage.primaryRunes.forEach(r -> add(runeNames, r));
            add(runeNames, build.runePage.secondaryRune);
        }
        boolean immobilizes = immobilizing.computeIfAbsent(championName == null ? "" : championName,
                k -> abilities.values().stream().anyMatch(SlowEstimator::immobilizes));
        for (MinutePoint p : series) {
            p.slow = estimate(p, abilities, ChampionCatalog.ranks(setup.skillOrder, setup.skillLevels, p.level), runeNames,
                    ranged, immobilizes);
        }
    }

    /** One learned ability at a point: its slot, its sheet, its cooldown now and its expected targets. */
    private static final class Cast {
        final String slot;
        final Map<String, Object> sheet;
        final int rank;
        final double cooldown;
        final double targets;
        final boolean damages;

        Cast(String slot, Map<String, Object> sheet, int rank, double cooldown, double targets, boolean damages) {
            this.slot = slot;
            this.sheet = sheet;
            this.rank = rank;
            this.cooldown = cooldown;
            this.targets = targets;
            this.damages = damages;
        }
    }

    SlowEstimate estimate(MinutePoint p, Map<String, Object> abilities, int[] ranks, Set<String> runeNames, boolean ranged,
                          boolean immobilizes) {
        SlowEstimate out = new SlowEstimate();
        List<Cast> casts = new ArrayList<>();
        for (int i = 0; i < SLOTS.length; i++) {
            Map<String, Object> ab = ability(abilities, SLOTS[i]);
            if (ab == null || ranks[i] <= 0 || isSelf(ab)) {
                continue;
            }
            double base = val(ab.get("recarga"), ranks[i], p.level, 0);
            if (base <= 0) {
                continue;
            }
            String slot = SLOTS[i];
            double cd = base * Stats.cooldownFactor(k -> p.total.getOrDefault(k, 0.0), slot);
            casts.add(new Cast(slot, ab, ranks[i], cd, targets(ab, ab), hasKey(ab, "dano")));
        }
        // the abilities' own slows: the strongest (slow x duration x targets) place in each sheet
        for (Cast c : casts) {
            double[] best = null;
            for (Map<String, Object> m : places.computeIfAbsent(c.sheet, k -> withEffects(k))) {
                Map<String, Object> ef = map(m.get("efeitos"));
                if (ef.get("lentidao") == null) {
                    continue;
                }
                double slow = val(ef.get("lentidao"), c.rank, p.level, 0);
                double dur = val(ef.get("duracao"), c.rank, p.level, 1);
                double tg = m == c.sheet ? c.targets : targets(m, c.sheet);
                if (best == null || slow * dur * tg > best[0] * best[1] * best[2]) {
                    best = new double[] {slow, dur, tg};
                }
            }
            if (best != null) {
                String name = String.valueOf(c.sheet.getOrDefault("nome", c.slot));
                add(out, name + " (" + ("R".equals(c.slot) ? "R" : c.slot) + ")", "habilidade", best[0], best[1], c.cooldown,
                        best[2], 1);
            }
        }
        Set<String> held = new LinkedHashSet<>();
        for (ItemContribution c : p.contributions) {
            if (!c.rune && !c.champion && c.itemName != null) {
                held.add(c.itemName);
            }
        }
        for (String name : held) {
            Map<String, Object> rule = map(items.get(name));
            if (!rule.isEmpty() && !(ranged && Boolean.TRUE.equals(rule.get("so_corpo_a_corpo")))) {
                source(out, name, "item", rule, p, casts);
            }
        }
        for (String name : runeNames) {
            Map<String, Object> rule = map(runes.get(name));
            if (!rule.isEmpty() && (immobilizes || !Boolean.TRUE.equals(rule.get("exige_imobilizar")))) {
                source(out, name, "runa", rule, p, casts);
            }
        }
        return out;
    }

    /** An item's or a rune's slow: on a fixed interval, or with every ultimate / ability. */
    private void source(SlowEstimate out, String name, String kind, Map<String, Object> rule, MinutePoint p, List<Cast> casts) {
        double bonusHealth = Math.max(0, p.total.getOrDefault(Stats.MAX_HEALTH, 0.0) - p.base.getOrDefault(Stats.MAX_HEALTH, 0.0));
        double slow = num(rule.get("lentidao"), 0) + num(rule.get("lentidao_por_vida_bonus"), 0) * bonusHealth;
        double dur = num(rule.get("duracao"), 1);
        double share = num(rule.get("fracao"), 1);
        double minInterval = num(rule.get("intervalo_min"), 0);
        Object every = rule.get("a_cada");
        double fixedTargets = rule.get("raio") != null ? circleTargets(num(rule.get("raio"), 0)) : -1;
        if (every == null) {
            add(out, name, kind, slow, dur, num(rule.get("intervalo"), 10), Math.max(1, fixedTargets), share);
            return;
        }
        // with the abilities: the rate of all of them (casts per second) and their mean targets, as one source
        double rate = 0;
        double weighted = 0;
        for (Cast c : casts) {
            boolean counts = "ultimate".equals(every) ? "R".equals(c.slot)
                    : !"habilidade_dano".equals(every) || c.damages;
            if (counts) {
                double r = 1 / Math.max(minInterval, c.cooldown);
                rate += r;
                weighted += r * (fixedTargets > 0 ? fixedTargets : c.targets);
            }
        }
        if (rate > 0) {
            add(out, name, kind, slow, dur, 1 / rate, weighted / rate, share);
        }
    }

    private static void add(SlowEstimate out, String name, String kind, double slow, double dur, double interval,
                            double targets, double share) {
        if (slow <= 0 || dur <= 0 || interval <= 0) {
            return;
        }
        SlowSource s = new SlowSource();
        s.name = name;
        s.kind = kind;
        s.slow = slow;
        s.duration = dur;
        s.interval = interval;
        s.targets = targets;
        s.share = share;
        s.value = 100 * slow * Math.min(dur, interval) / interval * targets * share;
        out.sources.add(s);
        out.total += s.value;
    }

    /** Expected targets of a place of a sheet (a zone, or the ability), its shape's area against the reference circle. */
    double targets(Map<String, Object> m, Map<String, Object> ability) {
        double area = area(m);
        if (area < 0 && m != ability) {
            area = area(ability);
        }
        return area < 0 ? 1 : Math.max(1, Math.min(maxTargets, area / (Math.PI * refRadius * refRadius)));
    }

    private double circleTargets(double radius) {
        return Math.max(1, Math.min(maxTargets, radius * radius / (refRadius * refRadius)));
    }

    /** Area of a shape in square units (-1 = one target). */
    @SuppressWarnings("unchecked")
    static double area(Map<String, Object> m) {
        String shape = String.valueOf(m.get("forma"));
        double r = num(m.get("raio"), 0);
        double ri = num(m.get("raio_interno"), 0);
        double width = num(m.get("largura"), 0);
        double length = num(m.get("comprimento"), num(m.get("alcance"), num(m.get("distancia"), 0)));
        switch (shape) {
            case "alvo": case "ataque": case "proprio":
                return -1;
            case "zonas": {
                double best = -1;
                if (m.get("zonas") instanceof List) {
                    for (Object z : (List<Object>) m.get("zonas")) {
                        if (z instanceof Map) {
                            best = Math.max(best, area((Map<String, Object>) z));
                        }
                    }
                }
                return best;
            }
            case "setor": case "cone": {
                double angle = num(m.get("angulo"), 60);
                double radius = r > 0 ? r : length;
                return angle / 360 * Math.PI * (radius * radius - ri * ri);
            }
            case "anel":
                return Math.PI * (r * r - ri * ri);
            case "linha":
                return Boolean.TRUE.equals(m.get("atravessa")) && width > 0 ? width * length : -1;
            case "retangulo": case "capsula":
                return width * length;
            default:
                if (r > 0) {
                    return Math.PI * (r * r - ri * ri);
                }
                // a moving shape (elemental, dash...) sweeps its width along its whole path
                double path = Math.max(length, Math.max(num(m.get("alcance"), 0), num(m.get("distancia"), 0)));
                return width > 0 && path > 0 ? width * path : -1;
        }
    }

    /** Every map in a sheet that carries {@code efeitos} (the ability, its zones, versions, lines...). */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> withEffects(Object node) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (node instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) node;
            if (m.get("efeitos") instanceof Map) {
                out.add(m);
            }
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!"efeitos".equals(e.getKey())) {
                    out.addAll(withEffects(e.getValue()));
                }
            }
        } else if (node instanceof List) {
            for (Object x : (List<Object>) node) {
                out.addAll(withEffects(x));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static boolean immobilizes(Object node) {
        for (Map<String, Object> m : withEffects(node)) {
            Map<String, Object> ef = (Map<String, Object>) m.get("efeitos");
            if (ef.keySet().stream().anyMatch(IMMOBILIZE::contains)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static boolean hasKey(Object node, String key) {
        if (node instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) node;
            return m.containsKey(key) || m.values().stream().anyMatch(v -> hasKey(v, key));
        }
        if (node instanceof List) {
            return ((List<Object>) node).stream().anyMatch(v -> hasKey(v, key));
        }
        return false;
    }

    private static boolean isSelf(Map<String, Object> ab) {
        return "proprio".equals(ab.get("forma")) && !(ab.get("efeitos") instanceof Map);
    }

    /** The champion's abilities by slot (the first form's for a champion with forms); empty without a sheet. */
    private Map<String, Object> abilities(String championName) {
        Map<String, Object> kit = map(kits.get(championName));
        Map<String, Object> abilities = map(kit.get("habilidades"));
        if (abilities.isEmpty() && kit.get("formas") instanceof Map) {
            Map<String, Object> forms = map(kit.get("formas"));
            Object first = kit.get("forma_inicial") != null ? forms.get(kit.get("forma_inicial"))
                    : forms.values().stream().findFirst().orElse(null);
            abilities = map(map(first).get("habilidades"));
        }
        return abilities;
    }

    private static Map<String, Object> ability(Map<String, Object> abilities, String slot) {
        Object ab = abilities.get(slot);
        if (ab == null && "R".equals(slot)) {
            ab = abilities.get("4");
        }
        return ab instanceof Map ? map(ab) : null;
    }

    /** A sheet value by rank (lists) and level ({@code _nv15} is not read here: the lists are what slows use). */
    @SuppressWarnings("unchecked")
    private static double val(Object v, int rank, int level, double def) {
        if (v instanceof List) {
            List<Object> list = (List<Object>) v;
            return list.isEmpty() ? def : num(list.get(Math.max(0, Math.min(list.size(), rank) - 1)), def);
        }
        return num(v, def);
    }

    private static double num(Object v, double def) {
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    private static void add(Set<String> names, String name) {
        if (name != null && !name.isBlank()) {
            names.add(name);
        }
    }
}
